/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.facebook.presto.operator;

import com.facebook.presto.Session;
import com.facebook.presto.spi.Page;
import com.facebook.presto.spi.PageBuilder;
import com.facebook.presto.spi.block.BlockDecoder;
import com.facebook.presto.spi.block.ExprContext;
import com.facebook.presto.spi.block.LongArrayBlock;
import com.facebook.presto.spi.type.Type;
import com.facebook.presto.sql.gen.JoinFilterFunctionCompiler.JoinFilterFunctionFactory;
import io.airlift.slice.Slice;
import io.airlift.slice.Slices;
import sun.misc.Unsafe;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;

import static com.facebook.presto.spi.type.BigintType.BIGINT;

public class AriaHash
{
    private AriaHash() {}

    static Unsafe unsafe;

    static {
        try {
            Field field = Unsafe.class.getDeclaredField("theUnsafe");
            field.setAccessible(true);
            unsafe = (Unsafe) field.get(null);
            if (unsafe == null) {
                throw new RuntimeException("Unsafe access not available");
            }
        }
        catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static boolean silent;
    private static boolean useBloomFilter;
    private static boolean recycleTable = true;

    static List<Slice> sliceReserve = new ArrayList<>();

    static void clearAllocCache()
    {
        sliceReserve.clear();
    }

    static Slice getSlice()
    {
        if (recycleTable) {
            synchronized (sliceReserve) {
                if (!sliceReserve.isEmpty()) {
                    return sliceReserve.remove(sliceReserve.size() - 1);
                }
            }
        }
        return Slices.allocate((128 * 1024));
    }

    static void releaseSlice(Slice slice)
    {
        if (recycleTable) {
            synchronized (sliceReserve) {
                sliceReserve.add(slice);
            }
        }
    }

    static boolean supportsLayout(List<Type> types, List<Integer> hashChannels)
    {
        return hashChannels.size() == 2
                && types.size() == 3
                && types.get(0) == BIGINT
                && types.get(1) == BIGINT
                && types.get(2) == BIGINT;
    }

    public static class HashTable
    {
        int statusMask;
        long[] status;
        long[] table;
        Slice[] slices = new Slice[16];
        long[] slabs = new long[16];
        int[] fill = new int[16];
        int currentSlab = -1;
        long[] bloomFilter;
        int bloomFilterSize;

        long nextResult(long entry, int offset)
        {
            Slice aslice;
            int aoffset;
            {
                aslice = slices[(int) ((entry) >> 17)];
                aoffset = (int) (entry) & 0x1ffff;
            }

            return aslice.getLong(aoffset + offset);
        }

        public long allocBytes(int bytes)
        {
            if (currentSlab == -1 || fill[currentSlab] + bytes > (128 * 1024)) {
                long w = newSlab();
                fill[currentSlab] = bytes;
                return w;
            }
            int off = fill[currentSlab];
            fill[currentSlab] += bytes;
            return (((currentSlab) << 17) + (off));
        }

        long newSlab()
        {
            ++currentSlab;
            if (slices.length <= currentSlab) {
                int newSize = slices.length * 2;
                slices = Arrays.copyOf(slices, newSize);
                fill = Arrays.copyOf(fill, newSize);
            }
            Slice s = getSlice();
            slices[currentSlab] = s;
            return (((currentSlab) << 17) + (0));
        }

        void release()
        {
            for (Slice slice : slices) {
                if (slice != null) {
                    releaseSlice(slice);
                }
            }
        }

        void setSize(int count)
        {
            if (count == 0) {
                statusMask = 0;
                return;
            }
            count *= 1.3;
            int size = 1024;
            while (size < count) {
                size *= 2;
            }
            table = new long[size];
            status = new long[size / 8];
            statusMask = (size >> 3) - 1;
            for (int i = 0; i <= statusMask; ++i) {
                status[i] = 0x8080808080808080L;
            }
        }

        long getJoinPositionCount()
        {
            return statusMask + 1;
        }

        long getSizeInBytes()
        {
            return 8 * (statusMask + 1) + (128 * 1024) * (currentSlab + 1);
        }
    }

    public static class HashBuild
            extends ExprContext
    {
        HashTable table = new HashTable();
        BlockDecoder k1 = new BlockDecoder();
        BlockDecoder k2 = new BlockDecoder();
        BlockDecoder d1 = new BlockDecoder();
        int entryCount;
        boolean makeBloomFilter;

        public HashBuild(List<Integer> hashChannels, List<Integer> dataChannels) {}

        long hashRow(long row)
        {
            int statusMask = table.statusMask;
            Slice[] slices = table.slices;
            Slice kslice;
            int koffset;
            {
                kslice = slices[(int) ((row) >> 17)];
                koffset = (int) (row) & 0x1ffff;
            }
            long h;
            {
                long kElement = kslice.getLong(koffset + 0);
                kElement *= 0xc6a4a7935bd1e995L;
                kElement ^= kElement >> 47;
                h = kElement * 0xc6a4a7935bd1e995L;
            }

            {
                long kElement = kslice.getLong(koffset + 8);
                kElement *= 0xc6a4a7935bd1e995L;
                kElement ^= kElement >> 47;
                kElement *= 0xc6a4a7935bd1e995L;
                h ^= kElement;
                h *= 0xc6a4a7935bd1e995L;
            }

            return h;
        }

        public void addInput(Page page)
        {
            k1.decodeBlock(page.getBlock(0), intArrayAllocator);
            k2.decodeBlock(page.getBlock(1), intArrayAllocator);
            d1.decodeBlock(page.getBlock(2), intArrayAllocator);
            int positionCount = page.getPositionCount();
            nullsInBatch = null;
            int[] k1Map = k1.rowNumberMap;
            int[] k2Map = k2.rowNumberMap;
            int[] d1Map = d1.rowNumberMap;
            addNullFlags(k1.valueIsNull, k1.isIdentityMap ? null : k1Map, positionCount);
            addNullFlags(k2.valueIsNull, k2.isIdentityMap ? null : k2Map, positionCount);

            int statusMask = table.statusMask;
            Slice[] slices = table.slices;

            for (int i = 0; i < positionCount; ++i) {
                if (nullsInBatch == null || !nullsInBatch[i]) {
                    ++entryCount;
                    long row = table.allocBytes(32);

                    slices = table.slices;

                    Slice kslice;
                    int koffset;
                    {
                        kslice = slices[(int) ((row) >> 17)];
                        koffset = (int) (row) & 0x1ffff;
                    }
                    kslice.setLong(koffset + 0, k1.longs[k1Map[i]]);
                    kslice.setLong(koffset + 8, k2.longs[k1Map[i]]);
                    kslice.setLong(koffset + 16, d1.longs[d1Map[i]]);
                    kslice.setLong(koffset + 24, -1);
                }
            }
            k1.release(intArrayAllocator);
            k2.release(intArrayAllocator);
            d1.release(intArrayAllocator);
        }

        public static class AriaLookupSourceSupplier
                implements LookupSourceSupplier
        {
            AriaLookupSource lookupSource;

            public AriaLookupSourceSupplier(AriaLookupSource lookupSource)
            {
                this.lookupSource = lookupSource;
            }

            @Override
            public LookupSource get()
            {
                return lookupSource;
            }

            public long getHashCollisions()
            {
                return 0;
            }

            public double getExpectedHashCollisions()
            {
                return 0;
            }

            public long checksum()
            {
                return 1234;
            }
        }

        public LookupSourceSupplier createLookupSourceSupplier(
                Session session,
                List<Integer> joinChannels,
                OptionalInt hashChannel,
                Optional<JoinFilterFunctionFactory> filterFunctionFactory,
                Optional<Integer> sortChannel,
                List<JoinFilterFunctionFactory> searchFunctionFactories,
                Optional<List<Integer>> outputChannels)
        {
            build();
            return new AriaLookupSourceSupplier(new AriaLookupSource(table, false));
        }

        public void build()
        {
            int statusMask = table.statusMask;
            Slice[] slices = table.slices;
            table.setSize(entryCount);
            int batch = 1024;
            long[] hashes = new long[batch];
            long[] entries = new long[batch];
            int fill = 0;
            for (int slab = 0; slab <= table.currentSlab; ++slab) {
                int slabFill = table.fill[slab];
                for (int offset = 0; offset < slabFill; offset += 32) {
                    long entry = (((slab) << 17) + (offset));
                    entries[fill] = entry;
                    hashes[fill++] = hashRow(entry);
                    if (fill == batch) {
                        insertHashes(hashes, entries, fill);
                        fill = 0;
                    }
                }
            }
            insertHashes(hashes, entries, fill);
        }

        void insertHashes(long[] hashes, long[] entries, int fill)
        {
            int statusMask = table.statusMask;
            Slice[] slices = table.slices;
            for (int i = 0; i < fill; ++i) {
                int h = (int) hashes[i] & statusMask;
                long field = (hashes[i] >> 56) & 0x7f;
                byte statusByte = (byte) field;
                field |= field << 8;
                field |= field << 16;
                field |= field << 32;
                nextKey:
                do {
                    long st = table.status[h];
                    long hits = st ^ field;
                    hits = st - 0x0101010101010101L;
                    hits &= 0x8080808080808080L;
                    Slice aslice;
                    int aoffset;
                    Slice bslice;
                    int boffset;
                    while (hits != 0) {
                        {
                            bslice = slices[(int) ((entries[i]) >> 17)];
                            boffset = (int) (entries[i]) & 0x1ffff;
                        }
                        int pos = Long.numberOfTrailingZeros(hits) >> 3;
                        {
                            aslice = slices[(int) ((table.table[h * 8 + pos]) >> 17)];
                            aoffset = (int) (table.table[h * 8 + pos]) & 0x1ffff;
                        }
                        if (aslice.getLong(aoffset + 0) == bslice.getLong(boffset + 0)
                                && aslice.getLong(aoffset + 8) == bslice.getLong(boffset + 8)) {
                            aslice.setLong(aoffset + 24, entries[i]);
                            break nextKey;
                        }
                        hits &= (hits - 1);
                    }

                    st &= 0x8080808080808080L;
                    if (st != 0) {
                        int pos = Long.numberOfTrailingZeros(st) >> 3;
                        table.status[h] = table.status[h] ^ (long) (statusByte | 0x80) << (pos * 8);

                        table.table[h * 8 + pos] = entries[i];
                        break;
                    }
                    h = (h + 1) & statusMask;
                }
                while (true);
            }
            if (makeBloomFilter) {
                int size;
                long[] bloomArray;

                if (table.bloomFilterSize == 0) {
                    size = (entryCount / 8) + 1;
                    table.bloomFilter = new long[size];
                    bloomArray = table.bloomFilter;
                    table.bloomFilterSize = size;
                    for (int i = 0; i < size; ++i) {
                        bloomArray[i] = 0;
                    }
                }
                else {
                    size = table.bloomFilterSize;
                    bloomArray = table.bloomFilter;
                }
                for (int i = 0; i < fill; i++) {
                    long h = hashes[i];
                    int w = (int) ((h & 0x7fffffff) % size);
                    long mask =
                            ((1L << (63 & ((h) >> 32)))
                                    | (1L << (63 & ((h) >> 38)))
                                    | (1L << (63 & ((h) >> 44)))
                                    | (1L << (63 & ((h) >> 50))));

                    bloomArray[w] = bloomArray[w] | mask;
                }
            }
        }
    }

    public static class AriaLookupSource
            extends ExprContext
            implements LookupSource
    {
        BlockDecoder k1 = new BlockDecoder();
        BlockDecoder k2 = new BlockDecoder();
        long[] hashes;
        HashTable table;
        int currentInput;
        long nextRow;
        long[] k1d;
        long[] k2d;
        int[] k1Map;
        int[] k2Map;
        int maxResults = 1024;
        int[] candidates;
        int candidateFill;
        int positionCount;
        int[] resultMap;
        int resultFill;
        long[] result1;
        long currentResult;
        int currentProbe;
        Page resultPage;
        Page returnPage;
        boolean reuseResult;
        boolean unroll = true;

        AriaLookupSource(HashTable table, boolean reuseResult)
        {
            this.table = table;
            this.reuseResult = reuseResult;
        }

        @Override
        public boolean isJoinPushedDown()
        {
            return true;
        }

        @Override
        public void addInput(Page page)
        {
            k1.decodeBlock(page.getBlock(0), intArrayAllocator);
            k2.decodeBlock(page.getBlock(1), intArrayAllocator);
            positionCount = page.getPositionCount();
            if (hashes == null || hashes.length < positionCount) {
                hashes = new long[positionCount + 10];
            }
            nullsInBatch = null;
            k1d = k1.longs;
            k2d = k2.longs;
            k1Map = k1.rowNumberMap;
            k2Map = k2.rowNumberMap;
            addNullFlags(k1.valueIsNull, k1.isIdentityMap ? null : k1Map, positionCount);
            addNullFlags(k2.valueIsNull, k2.isIdentityMap ? null : k2Map, positionCount);
            int statusMask = table.statusMask;
            Slice[] slices = table.slices;
            if (candidates == null || candidates.length < positionCount) {
                candidates = intArrayAllocator.getIntArray(positionCount);
            }
            if (nullsInBatch != null) {
                for (int i = 0; i < positionCount; ++i) {
                    if (nullsInBatch[i]) {
                        candidates[candidateFill++] = i;
                    }
                }
            }
            else {
                for (int i = 0; i < positionCount; ++i) {
                    candidates[i] = i;
                }
                candidateFill = positionCount;
            }
            for (int i = 0; i < candidateFill; ++i) {
                int row = candidates[i];
                long h;
                {
                    long kElement = k1d[k1Map[row]];
                    kElement *= 0xc6a4a7935bd1e995L;
                    kElement ^= kElement >> 47;
                    h = kElement * 0xc6a4a7935bd1e995L;
                }
                {
                    long kElement = k2d[k2Map[row]];
                    kElement *= 0xc6a4a7935bd1e995L;
                    kElement ^= kElement >> 47;
                    kElement *= 0xc6a4a7935bd1e995L;
                    h ^= kElement;
                    h *= 0xc6a4a7935bd1e995L;
                }
                hashes[row] = h;
            }
            if (result1 == null) {
                result1 = new long[maxResults];
                resultMap = new int[maxResults];
            }
            if (table.bloomFilterSize != 0) {
                int newFill = 0;
                int size = table.bloomFilterSize;
                long[] bloomArray = table.bloomFilter;
                for (int i = 0; i < candidateFill; ++i) {
                    int candidate = candidates[i];
                    long h = hashes[candidate];
                    int w = (int) ((h & 0x7fffffff) % size);
                    long mask =
                            ((1L << (63 & ((h) >> 32)))
                                    | (1L << (63 & ((h) >> 38)))
                                    | (1L << (63 & ((h) >> 44)))
                                    | (1L << (63 & ((h) >> 50))));
                    if (mask == (bloomArray[w] & mask)) {
                        candidates[newFill++] = candidate;
                    }
                }
                candidateFill = newFill;
            }
            currentProbe = 0;
            currentResult = -1;
        }

        public boolean addResult(long entry, int candidate)
        {
            int probeRow = candidates[candidate];
            int statusMask = table.statusMask;
            Slice[] slices = table.slices;
            do {
                resultMap[resultFill] = probeRow;
                Slice aslice;
                int aoffset;
                {
                    aslice = slices[(int) ((entry) >> 17)];
                    aoffset = (int) (entry) & 0x1ffff;
                }
                result1[resultFill] = aslice.getLong(aoffset + 16);
                entry = aslice.getLong(aoffset + 24);
                ++resultFill;
                if (resultFill >= maxResults) {
                    currentResult = entry;
                    currentProbe = candidate;
                    finishResult();
                    return true;
                }
            }
            while (entry != -1);
            currentResult = -1;
            return false;
        }

        void finishResult()
        {
            if (currentResult == -1 && currentProbe < candidateFill) {
                ++currentProbe;
            }
            if (currentProbe == candidateFill) {
                k1.release(intArrayAllocator);
                k2.release(intArrayAllocator);
            }
            if (resultFill == 0) {
                returnPage = null;
                return;
            }
            if (!reuseResult || resultPage == null) {
                resultPage = new Page(new LongArrayBlock(resultFill, Optional.empty(), result1));
                if (!reuseResult) {
                    result1 = new long[maxResults];
                    resultMap = new int[maxResults];
                }
            }
            else {
                resultPage.setPositionCount(resultFill);
            }
            resultFill = 0;
            returnPage = resultPage;
        }

        @Override
        public boolean needsInput()
        {
            return currentResult == -1 && currentProbe == candidateFill;
        }

        @Override
        public Page getOutput()
        {
            if (table.statusMask == 0) {
                return null;
            }
            int statusMask = table.statusMask;
            Slice[] slices = table.slices;
            if (currentResult != -1) {
                if (addResult(currentResult, currentProbe)) {
                    return returnPage;
                }
            }
            long tempHash;
            int unrollFill = unroll ? candidateFill : 0;
            for (; currentProbe + 3 < unrollFill; currentProbe += 4) {
                long entry0 = -1;
                long field0;
                long empty0;
                long hits0;
                int hash0;
                int row0;
                boolean match0 = false;
                Slice g0slice;
                int g0offset;
                long entry1 = -1;
                long field1;
                long empty1;
                long hits1;
                int hash1;
                int row1;
                boolean match1 = false;
                Slice g1slice;
                int g1offset;
                long entry2 = -1;
                long field2;
                long empty2;
                long hits2;
                int hash2;
                int row2;
                boolean match2 = false;
                Slice g2slice;
                int g2offset;
                long entry3 = -1;
                long field3;
                long empty3;
                long hits3;
                int hash3;
                int row3;
                boolean match3 = false;
                Slice g3slice;
                int g3offset;
                row0 = candidates[currentProbe + 0];
                tempHash = hashes[row0];
                hash0 = (int) tempHash & statusMask;
                field0 = (tempHash >> 56) & 0x7f;
                hits0 = table.status[hash0];
                field0 |= field0 << 8;
                field0 |= field0 << 16;
                field0 |= field0 << 32;
                row1 = candidates[currentProbe + 1];
                tempHash = hashes[row1];
                hash1 = (int) tempHash & statusMask;
                field1 = (tempHash >> 56) & 0x7f;
                hits1 = table.status[hash1];
                field1 |= field1 << 8;
                field1 |= field1 << 16;
                field1 |= field1 << 32;
                row2 = candidates[currentProbe + 2];
                tempHash = hashes[row2];
                hash2 = (int) tempHash & statusMask;
                field2 = (tempHash >> 56) & 0x7f;
                hits2 = table.status[hash2];
                field2 |= field2 << 8;
                field2 |= field2 << 16;
                field2 |= field2 << 32;
                row3 = candidates[currentProbe + 3];
                tempHash = hashes[row3];
                hash3 = (int) tempHash & statusMask;
                field3 = (tempHash >> 56) & 0x7f;
                hits3 = table.status[hash3];
                field3 |= field3 << 8;
                field3 |= field3 << 16;
                field3 |= field3 << 32;
                empty0 = hits0 & 0x8080808080808080L;
                hits0 ^= field0;
                hits0 -= 0x0101010101010101L;
                hits0 &= 0x8080808080808080L ^ empty0;
                if (hits0 != 0) {
                    int pos = Long.numberOfTrailingZeros(hits0) >> 3;
                    hits0 &= hits0 - 1;
                    entry0 = table.table[hash0 * 8 + pos];
                    {
                        g0slice = slices[(int) ((entry0) >> 17)];
                        g0offset = (int) (entry0) & 0x1ffff;
                    }
                    match0 =
                            g0slice.getLong(g0offset + 0) == k1d[k1Map[row0]]
                                    & g0slice.getLong(g0offset + 8) == k2d[k2Map[row0]];
                }
                empty1 = hits1 & 0x8080808080808080L;
                hits1 ^= field1;
                hits1 -= 0x0101010101010101L;
                hits1 &= 0x8080808080808080L ^ empty1;
                if (hits1 != 0) {
                    int pos = Long.numberOfTrailingZeros(hits1) >> 3;
                    hits1 &= hits1 - 1;
                    entry1 = table.table[hash1 * 8 + pos];
                    {
                        g1slice = slices[(int) ((entry1) >> 17)];
                        g1offset = (int) (entry1) & 0x1ffff;
                    }
                    match1 =
                            g1slice.getLong(g1offset + 0) == k1d[k1Map[row1]]
                                    & g1slice.getLong(g1offset + 8) == k2d[k2Map[row1]];
                }
                empty2 = hits2 & 0x8080808080808080L;
                hits2 ^= field2;
                hits2 -= 0x0101010101010101L;
                hits2 &= 0x8080808080808080L ^ empty2;
                if (hits2 != 0) {
                    int pos = Long.numberOfTrailingZeros(hits2) >> 3;
                    hits2 &= hits2 - 1;
                    entry2 = table.table[hash2 * 8 + pos];
                    {
                        g2slice = slices[(int) ((entry2) >> 17)];
                        g2offset = (int) (entry2) & 0x1ffff;
                    }
                    match2 =
                            g2slice.getLong(g2offset + 0) == k1d[k1Map[row2]]
                                    & g2slice.getLong(g2offset + 8) == k2d[k2Map[row2]];
                }
                empty3 = hits3 & 0x8080808080808080L;
                hits3 ^= field3;
                hits3 -= 0x0101010101010101L;
                hits3 &= 0x8080808080808080L ^ empty3;
                if (hits3 != 0) {
                    int pos = Long.numberOfTrailingZeros(hits3) >> 3;
                    hits3 &= hits3 - 1;
                    entry3 = table.table[hash3 * 8 + pos];
                    {
                        g3slice = slices[(int) ((entry3) >> 17)];
                        g3offset = (int) (entry3) & 0x1ffff;
                    }
                    match3 =
                            g3slice.getLong(g3offset + 0) == k1d[k1Map[row3]]
                                    & g3slice.getLong(g3offset + 8) == k2d[k2Map[row3]];
                }
                if (match0) {
                    if (addResult(entry0, currentProbe + 0)) {
                        return returnPage;
                    }
                }
                else {
                    bucketLoop0:
                    for (; ; ) {
                        while (hits0 != 0) {
                            int pos = Long.numberOfTrailingZeros(hits0) >> 3;
                            entry0 = table.table[hash0 * 8 + pos];
                            {
                                g0slice = slices[(int) ((entry0) >> 17)];
                                g0offset = (int) (entry0) & 0x1ffff;
                            }
                            if (g0slice.getLong(g0offset + 0) == k1d[k1Map[row0]]
                                    && g0slice.getLong(g0offset + 8) == k2d[k2Map[row0]]) {
                                if (addResult(entry0, currentProbe + 0)) {
                                    return returnPage;
                                }
                                break bucketLoop0;
                            }
                            hits0 &= hits0 - 1;
                        }
                        if (empty0 != 0) {
                            break;
                        }
                        hash0 = (hash0 + 1) & statusMask;
                        hits0 = table.status[hash0];
                        empty0 = hits0 & 0x8080808080808080L;
                        hits0 ^= field0;
                        hits0 -= 0x0101010101010101L;
                        hits0 &= 0x8080808080808080L ^ empty0;
                    }
                }
                if (match1) {
                    if (addResult(entry1, currentProbe + 1)) {
                        return returnPage;
                    }
                }
                else {
                    bucketLoop1:
                    for (; ; ) {
                        while (hits1 != 0) {
                            int pos = Long.numberOfTrailingZeros(hits1) >> 3;
                            entry1 = table.table[hash1 * 8 + pos];
                            {
                                g1slice = slices[(int) ((entry1) >> 17)];
                                g1offset = (int) (entry1) & 0x1ffff;
                            }
                            if (g1slice.getLong(g1offset + 0) == k1d[k1Map[row1]]
                                    && g1slice.getLong(g1offset + 8) == k2d[k2Map[row1]]) {
                                if (addResult(entry1, currentProbe + 1)) {
                                    return returnPage;
                                }
                                break bucketLoop1;
                            }
                            hits1 &= hits1 - 1;
                        }
                        if (empty1 != 0) {
                            break;
                        }
                        hash1 = (hash1 + 1) & statusMask;
                        hits1 = table.status[hash1];
                        empty1 = hits1 & 0x8080808080808080L;
                        hits1 ^= field1;
                        hits1 -= 0x0101010101010101L;
                        hits1 &= 0x8080808080808080L ^ empty1;
                    }
                }
                if (match2) {
                    if (addResult(entry2, currentProbe + 2)) {
                        return returnPage;
                    }
                }
                else {
                    bucketLoop2:
                    for (; ; ) {
                        while (hits2 != 0) {
                            int pos = Long.numberOfTrailingZeros(hits2) >> 3;
                            entry2 = table.table[hash2 * 8 + pos];
                            {
                                g2slice = slices[(int) ((entry2) >> 17)];
                                g2offset = (int) (entry2) & 0x1ffff;
                            }
                            if (g2slice.getLong(g2offset + 0) == k1d[k1Map[row2]]
                                    && g2slice.getLong(g2offset + 8) == k2d[k2Map[row2]]) {
                                if (addResult(entry2, currentProbe + 2)) {
                                    return returnPage;
                                }
                                break bucketLoop2;
                            }
                            hits2 &= hits2 - 1;
                        }
                        if (empty2 != 0) {
                            break;
                        }
                        hash2 = (hash2 + 1) & statusMask;
                        hits2 = table.status[hash2];
                        empty2 = hits2 & 0x8080808080808080L;
                        hits2 ^= field2;
                        hits2 -= 0x0101010101010101L;
                        hits2 &= 0x8080808080808080L ^ empty2;
                    }
                }
                if (match3) {
                    if (addResult(entry3, currentProbe + 3)) {
                        return returnPage;
                    }
                }
                else {
                    bucketLoop3:
                    for (; ; ) {
                        while (hits3 != 0) {
                            int pos = Long.numberOfTrailingZeros(hits3) >> 3;
                            entry3 = table.table[hash3 * 8 + pos];
                            {
                                g3slice = slices[(int) ((entry3) >> 17)];
                                g3offset = (int) (entry3) & 0x1ffff;
                            }
                            if (g3slice.getLong(g3offset + 0) == k1d[k1Map[row3]]
                                    && g3slice.getLong(g3offset + 8) == k2d[k2Map[row3]]) {
                                if (addResult(entry3, currentProbe + 3)) {
                                    return returnPage;
                                }
                                break bucketLoop3;
                            }
                            hits3 &= hits3 - 1;
                        }
                        if (empty3 != 0) {
                            break;
                        }
                        hash3 = (hash3 + 1) & statusMask;
                        hits3 = table.status[hash3];
                        empty3 = hits3 & 0x8080808080808080L;
                        hits3 ^= field3;
                        hits3 -= 0x0101010101010101L;
                        hits3 &= 0x8080808080808080L ^ empty3;
                    }
                }
            }
            for (; currentProbe < candidateFill; ++currentProbe) {
                long entry0 = -1;
                long field0;
                long empty0;
                long hits0;
                int hash0;
                int row0;
                boolean match0 = false;
                Slice g0slice;
                int g0offset;
                row0 = candidates[currentProbe + 0];
                tempHash = hashes[row0];
                hash0 = (int) tempHash & statusMask;
                field0 = (tempHash >> 56) & 0x7f;
                hits0 = table.status[hash0];
                field0 |= field0 << 8;
                field0 |= field0 << 16;
                field0 |= field0 << 32;
                empty0 = hits0 & 0x8080808080808080L;
                hits0 ^= field0;
                hits0 -= 0x0101010101010101L;
                hits0 &= 0x8080808080808080L ^ empty0;
                if (hits0 != 0) {
                    int pos = Long.numberOfTrailingZeros(hits0) >> 3;
                    hits0 &= hits0 - 1;
                    entry0 = table.table[hash0 * 8 + pos];
                    {
                        g0slice = slices[(int) ((entry0) >> 17)];
                        g0offset = (int) (entry0) & 0x1ffff;
                    }
                    match0 =
                            g0slice.getLong(g0offset + 0) == k1d[k1Map[row0]]
                                    & g0slice.getLong(g0offset + 8) == k2d[k2Map[row0]];
                }
                if (match0) {
                    if (addResult(entry0, currentProbe + 0)) {
                        return returnPage;
                    }
                }
                else {
                    bucketLoop0:
                    for (; ; ) {
                        while (hits0 != 0) {
                            int pos = Long.numberOfTrailingZeros(hits0) >> 3;
                            entry0 = table.table[hash0 * 8 + pos];
                            {
                                g0slice = slices[(int) ((entry0) >> 17)];
                                g0offset = (int) (entry0) & 0x1ffff;
                            }

                            if (g0slice.getLong(g0offset + 0) == k1d[k1Map[row0]]
                                    && g0slice.getLong(g0offset + 8) == k2d[k2Map[row0]]) {
                                if (addResult(entry0, currentProbe + 0)) {
                                    return returnPage;
                                }
                                break bucketLoop0;
                            }
                            hits0 &= hits0 - 1;
                        }
                        if (empty0 != 0) {
                            break;
                        }
                        hash0 = (hash0 + 1) & statusMask;
                        hits0 = table.status[hash0];
                        empty0 = hits0 & 0x8080808080808080L;
                        hits0 ^= field0;
                        hits0 -= 0x0101010101010101L;
                        hits0 &= 0x8080808080808080L ^ empty0;
                    }
                }
            }
            finishResult();
            return returnPage;
        }

        @Override
        public int getChannelCount()
        {
            return 1;
        }

        @Override
        public long getInMemorySizeInBytes()
        {
            return table.getSizeInBytes();
        }

        @Override
        public long getJoinPositionCount()
        {
            return table.getJoinPositionCount();
        }

        @Override
        public long joinPositionWithinPartition(long joinPosition)
        {
            throw new UnsupportedOperationException();
        }

        @Override
        public long getJoinPosition(
                int position, Page hashChannelsPage, Page allChannelsPage, long rawHash)
        {
            throw new UnsupportedOperationException();
        }

        @Override
        public long getJoinPosition(int position, Page hashChannelsPage, Page allChannelsPage)
        {
            throw new UnsupportedOperationException();
        }

        @Override
        public long getNextJoinPosition(
                long currentJoinPosition, int probePosition, Page allProbeChannelsPage)
        {
            throw new UnsupportedOperationException();
        }

        @Override
        public void appendTo(long position, PageBuilder pageBuilder, int outputChannelOffset)
        {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean isJoinPositionEligible(
                long currentJoinPosition, int probePosition, Page allProbeChannelsPage)
        {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean isEmpty()
        {
            return table.statusMask == 0;
        }

        @Override
        public void close()
        {
            table = null;
        }
    }
}
