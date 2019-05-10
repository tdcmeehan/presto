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
package com.facebook.presto.spi.block;

import com.facebook.presto.spi.api.Experimental;

import java.util.Arrays;

import static com.facebook.presto.spi.block.ClosingBlockLease.newLease;
import static java.util.Objects.requireNonNull;

@Experimental
public class BlockFlattener
{
    private final ArrayAllocator allocator;

    public BlockFlattener(ArrayAllocator allocator)
    {
        this.allocator = requireNonNull(allocator, "allocator is null");
    }

    public BlockLease flatten(Block block)
    {
        requireNonNull(block, "block is null");
        if (block instanceof DictionaryBlock) {
            return flattenDictionary((DictionaryBlock) block);
        }
        if (block instanceof RunLengthEncodedBlock) {
            return flattenRLE((RunLengthEncodedBlock) block);
        }
        return newLease(block);
    }

    public BlockLease flattenDictionary(DictionaryBlock dictionaryBlock)
    {
        Block block = dictionaryBlock.getDictionary();
        int[] map = (int[]) dictionaryBlock.getIds().getBase();
        int positionCount = dictionaryBlock.getPositionCount();
        int[] leasedMap = null;
        while (true) {
            if (block instanceof DictionaryBlock) {
                dictionaryBlock = (DictionaryBlock) block;
                int[] ids = (int[]) dictionaryBlock.getIds().getBase();
                int[] newMap = allocator.borrowIntArray(positionCount);

                for (int i = 0; i < positionCount; ++i) {
                    newMap[i] = ids[map[i] + dictionaryBlock.getOffsetBase()];
                }
                if (leasedMap != null) {
                    allocator.returnArray(leasedMap);
                }
                leasedMap = newMap;
                map = newMap;

                block = dictionaryBlock.getDictionary();
            }
            else if (block instanceof RunLengthEncodedBlock) {
                RunLengthEncodedBlock rle = (RunLengthEncodedBlock) block;
                int[] newMap = allocator.borrowIntArray(positionCount);
                Arrays.fill(newMap, 0, positionCount, 0);
                if (leasedMap != null) {
                    allocator.returnArray(leasedMap);
                }
                map = newMap;
                leasedMap = newMap;

                block = rle.getValue();
            }
            else {
                block = new DictionaryBlock(positionCount, block, map);
                if (leasedMap != null) {
                    int[] leasedMapToReturn = leasedMap; // effectively final
                    return newLease(block, () -> allocator.returnArray(leasedMapToReturn));
                }
                return newLease(block);
            }
        }
    }

    private BlockLease flattenRLE(RunLengthEncodedBlock rleBLock)
    {
        Block block = rleBLock;
        while (true) {
            if (block instanceof RunLengthEncodedBlock) {
                RunLengthEncodedBlock rle = (RunLengthEncodedBlock) block;
                block = rle.getValue();
            }
            else if (block instanceof DictionaryBlock) {
                DictionaryBlock dictionaryBlock = (DictionaryBlock) block;
                block = dictionaryBlock.getDictionary().copyRegion(dictionaryBlock.getId(0), 1);
            }
            else {
                return newLease(new RunLengthEncodedBlock(block, rleBLock.getPositionCount()));
            }
        }
    }
}
