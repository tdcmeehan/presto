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
package com.facebook.presto.orc.reader;

import com.facebook.presto.orc.Filter;
import com.facebook.presto.orc.QualifyingSet;
import com.facebook.presto.spi.block.Block;

abstract class ColumnReader
        implements StreamReader
{
    QualifyingSet inputQualifyingSet;
    QualifyingSet outputQualifyingSet;
    Block block;
    int outputChannel = -1;
    Filter filter;
    int expectNumValues = 10000;

    public QualifyingSet getInputQualifyingSet()
    {
        return inputQualifyingSet;
    }

    public void setInputQualifyingSet(QualifyingSet qualifyingSet)
    {
        inputQualifyingSet = qualifyingSet;
    }

    public QualifyingSet getOutputQualifyingSet()
    {
        return outputQualifyingSet;
    }

    @Override
    public void setFilterAndChannel(Filter filter, int channel)
    {
        this.filter = filter;
        outputChannel = channel;
    }

    @Override
    public int getChannel()
    {
        return outputChannel;
    }
    @Override
    public Filter getFilter()
    {
        return filter;
    }
}
