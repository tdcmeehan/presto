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
import com.facebook.presto.orc.metadata.ColumnEncoding;
import com.facebook.presto.orc.stream.InputStreamSources;
import com.facebook.presto.spi.block.Block;
import com.facebook.presto.spi.type.Type;

import java.io.IOException;
import java.util.List;

public interface StreamReader
{
    default boolean scanAtEnd()
    {
        return true;
    }

    default void setInputQualifyingSet(QualifyingSet qualifyingSet)
    {
        throw new UnsupportedOperationException();
    }

    default QualifyingSet getInputQualifyingSet()
    {
        return null;
    }

    default QualifyingSet getOutputQualifyingSet()
    {
        return null;
    }

    /* If filter is non-null, sets the output QualifyingSet by
     * applying filter to the rows in the input QualifyingSet. If
     * channel is not -1, appends the values in the post-filter rows
     * to a Block. The Block can be retrieved by getBlock(). */
    default void setFilterAndChannel(Filter filter, int channel)
    {
        throw new UnsupportedOperationException();
    }

    /* True if the extracted values depend on a row group
     * dictionary. Cannot move to the next row group without losing
     * the dictionary encoding .*/
    default boolean mustReturnAfterRowGroup()
    {
        return false;
    }

    default int getChannel()
    {
        return -1;
    }

    default Block getBlock(boolean mayReuse)
    {
        return null;
    }

    default Filter getFilter()
    {
        return null;
    }

    default int getValueSize()
    {
        return 8;
    }

    default int erase(int begin, int end, int numValuesBeforeRowGroup, int numErasedFromInput)
    {
        throw new UnsupportedOperationException();
    }

    default int scan(int maxResultBytes)
            throws IOException
    {
        throw new UnsupportedOperationException();
    }

    Block readBlock(Type type)
            throws IOException;

    void prepareNextRead(int batchSize);

    void startStripe(InputStreamSources dictionaryStreamSources, List<ColumnEncoding> encoding)
            throws IOException;

    void startRowGroup(InputStreamSources dataStreamSources)
            throws IOException;

    void close();

    long getRetainedSizeInBytes();
}
