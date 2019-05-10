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

import javax.annotation.concurrent.GuardedBy;
import javax.annotation.concurrent.ThreadSafe;

import java.util.ArrayDeque;
import java.util.Deque;

import static java.lang.String.format;

@Experimental
@ThreadSafe
public class SimpleArrayAllocator
        implements ArrayAllocator
{
    private static final int DEFAULT_MAX_OUTSTANDING = 1000;
    private final int maxOutstandingArrays;

    @GuardedBy("this")
    private final Deque<int[]> intArrays = new ArrayDeque<>();
    @GuardedBy("this")
    private int outstandingArrays;

    public SimpleArrayAllocator()
    {
        this(DEFAULT_MAX_OUTSTANDING);
    }

    public SimpleArrayAllocator(int maxOutstandingArrays)
    {
        this.maxOutstandingArrays = maxOutstandingArrays;
    }

    @Override
    public synchronized int[] borrowIntArray(int positionCount)
    {
        checkState(outstandingArrays < maxOutstandingArrays, "Requested too many arrays: %s", outstandingArrays);
        outstandingArrays++;
        while (!intArrays.isEmpty() && intArrays.peek().length < positionCount) {
            intArrays.pop();
        }
        if (intArrays.isEmpty()) {
            return new int[positionCount];
        }
        return intArrays.pop();
    }

    @Override
    public synchronized void returnArray(int[] array)
    {
        checkState(outstandingArrays > 0, "Returned more arrays than leased");
        outstandingArrays--;
        intArrays.push(array);
    }

    @Override
    public synchronized int getOutstandingArrays()
    {
        return outstandingArrays;
    }

    private static void checkState(boolean condition, String message, Object... formatArguments)
    {
        if (!condition) {
            throw new IllegalStateException(format(message, formatArguments));
        }
    }
}
