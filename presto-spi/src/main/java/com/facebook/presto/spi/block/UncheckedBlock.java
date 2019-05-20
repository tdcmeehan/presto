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
import io.airlift.slice.Slice;

/**
 * Accessors for Block which provide for raw indexing into the underlying data structure of the Block.
 * It is intended for hot path use cases where the speed of the boundary checks on Block are a performance
 * bottleneck.  Methods in this case must be used with more care, as they may return invalid data if the
 * indexing is not performed with respect to {@link #getOffsetBase} and {@link #getPositionCount()}.
 */
@Experimental
public interface UncheckedBlock
        extends Block
{
    /**
     * Gets a byte in the value at {@code position - getOffsetBase()}.
     */
    default byte getByteUnchecked(int position)
    {
        throw new UnsupportedOperationException(getClass().getName());
    }

    /**
     * Gets a little endian short at in the value at {@code position - getOffsetBase()}.
     */
    default short getShortUnchecked(int position)
    {
        throw new UnsupportedOperationException(getClass().getName());
    }

    /**
     * Gets a little endian int in the value at {@code position - getOffsetBase()}.
     */
    default int getIntUnchecked(int position)
    {
        throw new UnsupportedOperationException(getClass().getName());
    }

    /**
     * Gets a little endian long in the value at {@code position - getOffsetBase()}.
     */
    default long getLongUnchecked(int position)
    {
        throw new UnsupportedOperationException(getClass().getName());
    }

    /**
     * Gets a little endian long at {@code offset} in the value at {@code position - getOffsetBase()}.
     */
    default long getLongUnchecked(int position, int offset)
    {
        throw new UnsupportedOperationException(getClass().getName());
    }

    /**
     * Gets the length of the value at the {@code position - getOffsetBase()}.
     * This method must be implemented if @{code getSlice} is implemented.
     */
    default int getSliceLengthUnchecked(int position)
    {
        throw new UnsupportedOperationException(getClass().getName());
    }

    /**
     * Gets a slice at {@code offset} in the value at {@code position - getOffsetBase()}.
     */
    default Slice getSliceUnchecked(int position, int offset, int length)
    {
        throw new UnsupportedOperationException(getClass().getName());
    }

    /**
     * Gets a Block in the value at {@code position - getOffsetBase()}.
     */
    default Block getBlockUnchecked(int position)
    {
        throw new UnsupportedOperationException(getClass().getName());
    }

    /**
     * @return is the value at {@code position - getOffsetBase()} null
     */
    boolean isNullUnchecked(int position);

    /**
     * @return the internal offset of the underlying data structure of this block
     */
    int getOffsetBase();
}
