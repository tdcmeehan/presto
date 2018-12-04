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
package com.facebook.presto.orc;

import java.util.Arrays;

public class QualifyingSet
{
    // begin and end define the range of rows coverd. If a row >=
    // begin and < end and is not in positions rangeBegins[i] <= row <
    // rangeEnds[i] then row is not in the qualifying set.
    private int begin;
    private int end;
    private int[] rangeBegins;
    private int[] rangeEnds;
    private int[] positions;
    private int numRanges;
    private int positionCount;

    private int[] inputNumbers;
    private boolean isRanges;

    static int[] wholeRowGroup;
    static int[] allZeros;
    private int[] ownedPositions;
    private int[] ownedInputNumbers;

    static {
        wholeRowGroup = new int[10000];
        allZeros = new int[10000];
        Arrays.fill(allZeros, 0);
        for (int i = 0; i < 10000; i++) {
            wholeRowGroup[i] = i;
        }
    }

    public void setRange(int begin, int end)
    {
        this.begin = begin;
        this.end = end;
        int[] zeros = allZeros;
        if (zeros.length < end - begin) {
            int[] newZeros = new int[end - begin];
            Arrays.fill(newZeros, 0);
            allZeros = newZeros;
            inputNumbers = newZeros;
        }
        else {
            inputNumbers = zeros;
        }
        if (begin == 0) {
            int[] rowGroup = wholeRowGroup;
            if (rowGroup.length >= end) {
                positions = rowGroup;
            }
            else {
                // Thread safe.  If many concurrently create a new wholeRowGroup, many are created but all but one become garbage and everybody has a right size array.
                int[] newWholeRowGroup = new int[end];
                for (int i = 0; i < end; i++) {
                    newWholeRowGroup[i] = i;
                }
                positions = newWholeRowGroup;
                wholeRowGroup = newWholeRowGroup;
            }
            positionCount = end;
        }
        else {
            if (ownedPositions == null || ownedPositions.length < end - begin) {
                ownedPositions = new int[(int) ((end - begin) * 1.2)];
            }
            positions = ownedPositions;

            for (int i = begin; i < end; i++) {
                positions[i - begin] = i;
            }
        }
    }

    public boolean isEmpty()
    {
        return positionCount == 0;
    }

    public int[] getPositions()
    {
        return positions;
    }

    public int[] getInputNumbers()
    {
        return inputNumbers;
    }

    public int[] getMutablePositions(int minSize)
    {
        if (ownedPositions == null || ownedPositions.length < minSize) {
            minSize = (int) (minSize * 1.2);
            ownedPositions = new int[minSize];
        }
        positions = ownedPositions;
        return positions;
    }

    public int[] getMutableInputNumbers(int minSize)
    {
        if (ownedInputNumbers == null || ownedInputNumbers.length < minSize) {
            minSize = (int) (minSize * 1.2);
            ownedInputNumbers = new int[minSize];
        }
        inputNumbers = ownedInputNumbers;
        return inputNumbers;
    }

    public int getBegin()
    {
        return begin;
    }

    public void setBegin(int begin)
    {
        this.begin = begin;
    }

    public int getEnd()
    {
        return end;
    }

    public void setEnd(int end)
    {
        this.end = end;
    }

    public int getPositionCount()
    {
        return positionCount;
    }

    public void setPositionCount(int positionCount)
    {
        this.positionCount = positionCount;
    }

    // Erases qulifying rows and corresponding input numbers below position.
    public void eraseBelowPosition(int position)
    {
        if (positions != ownedPositions) {
            throw new UnsupportedOperationException("QualifyingSet must own its positions in eraseBelowPosition");
        }
        if (positions[positionCount - 1] < position) {
            positionCount = 0;
            return;
        }
        for (int i = positionCount - 1; i >= 0; i--) {
            if (positions[i] < position) {
                // Found first position below the cutoff. Erase this and all below it.
                int numErasedInputs = inputNumbers[i];
                for (int i1 = i; i1 < positionCount; i1++) {
                    positions[i1 - i] = positions[i1];
                    inputNumbers[i1 - i] = inputNumbers[i] - numErasedInputs;
                }
                positionCount -= i;
            }
        }
    }

    // Sets this to be those rows of input that are above the end of output.
    public void setContinueAfterTruncate(QualifyingSet input, QualifyingSet truncated)
    {
    }
}
