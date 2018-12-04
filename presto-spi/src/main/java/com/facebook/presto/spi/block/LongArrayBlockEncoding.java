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

import io.airlift.slice.Slice;
import io.airlift.slice.SliceInput;
import io.airlift.slice.SliceOutput;

import static com.facebook.presto.spi.block.EncoderUtil.decodeNullBits;
import static com.facebook.presto.spi.block.EncoderUtil.encodeNullsAsBits;

public class LongArrayBlockEncoding
        implements BlockEncoding
{
    public static final String NAME = "LONG_ARRAY";

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public void writeBlock(BlockEncodingSerde blockEncodingSerde, SliceOutput sliceOutput, Block block)
    {
        int positionCount = block.getPositionCount();
        sliceOutput.appendInt(positionCount);

        encodeNullsAsBits(sliceOutput, block);

        for (int position = 0; position < positionCount; position++) {
            if (!block.isNull(position)) {
                sliceOutput.writeLong(block.getLong(position, 0));
            }
        }
    }

    @Override
    public Block readBlock(BlockEncodingSerde blockEncodingSerde, SliceInput sliceInput)
    {
        int positionCount = sliceInput.readInt();

        boolean[] valueIsNull = decodeNullBits(sliceInput, positionCount).orElse(null);

        long[] values = new long[positionCount];
        for (int position = 0; position < positionCount; position++) {
            if (valueIsNull == null || !valueIsNull[position]) {
                values[position] = sliceInput.readLong();
            }
        }

        return new LongArrayBlock(0, positionCount, valueIsNull, values);
    }

    @Override
    public int reserveBytesInBuffer(BlockDecoder contents, int numValues, int startInBuffer, EncodingState state)
    {
        //  Reserves space for serialized 'rows' non-null longs
        // including headers. 5 for vallue count and null indicator, 4
        // for name character count + length of the name string.
        int size = 8 * numValues + 5 + 4 + NAME.length();
        state.startInBuffer = startInBuffer;
        state.bytesInBuffer = size;
        state.maxValues = numValues;
        state.encodingName = NAME;
        return startInBuffer + size;
    }

    @Override
    public void addValues(BlockDecoder contents, int[] rows, int firstRow, int numRows, EncodingState state)
    {
        long[] longs = contents.longs;
        int[] map = contents.rowNumberMap;
        int longsOffset = state.valueOffset + 5 + state.numValues * 8;
        for (int i = 0; i < numRows; i++) {
            state.topLevelBuffer.setLong(longsOffset + i * 8, longs[map[rows[i + firstRow]]]);
        }
        state.numValues += numRows;
    }

    @Override
    public int prepareFinish(EncodingState state, int newStartInBuffer)
    {
        state.newStartInBuffer = newStartInBuffer;
        return finalSize(state);
    }

    int finalSize(EncodingState state)
    {
        return 8 * state.numValues + (state.valueOffset - state.startInBuffer) + 5;
    }

    @Override
    public void finish(EncodingState state, Slice buffer)
    {
        state.topLevelBuffer.setInt(state.valueOffset, state.numValues);
        state.topLevelBuffer.setByte(state.valueOffset + 4, 0);
        if (buffer.getBase() == state.topLevelBuffer.getBase() && !state.anyNulls && state.startInBuffer == state.newStartInBuffer) {
            return;
        }
        int size = finalSize(state);
        System.arraycopy((byte[]) state.topLevelBuffer.getBase(),
                state.startInBuffer,
                (byte[]) buffer.getBase(),
                state.newStartInBuffer,
                size);
    }
}
