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

public class Filter
{
    private long nIn;
    private long nOut;
    private long time;

    public boolean testLong(long value)
    {
        return false;
    }

    public boolean testBytes(byte[] buffer, int offset, int length)
    {
        return false;
    }

    public boolean testNull()
    {
        return false;
    }

    void updateStats(int nIn, int nOut, long time)
    {
        this.nIn += nIn;
        this.nOut += nOut;
        this.time += time;
    }

    double getTimePerDroppedValue()
    {
        return (double) time / (1 + nIn - nOut);
    }

    void decayStats()
    {
        nIn /= 2;
        nOut /= 2;
        time /= 2;
    }

    // If there are no scores, return a number for making initial filter order. Less is better.
    int staticScore()
    {
        return 100;
    }
}
