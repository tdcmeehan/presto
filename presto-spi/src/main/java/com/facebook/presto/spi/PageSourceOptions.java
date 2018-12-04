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
package com.facebook.presto.spi;

public class PageSourceOptions
{
    private final boolean reusePages;
    private final int[] outputChannels;
    private final FilterFunction[] filterFunctions;
    private final boolean reorderFilters;
    private final int targetBytes;

    public class FilterFunction
    {
        private int[] inputChannels;
        private int initialCost = 1;

        public FilterFunction(int[] inputChannels, int initialCost)
        {
        }

        public int filter(Page page, int[] inputRows, int[] outputRows)
        {
            return 0;
        }
    }

    public PageSourceOptions(int[] outputChannels, boolean reusePages, FilterFunction[] filterFunctions, boolean reorderFilters, int targetBytes)
    {
        this.outputChannels = outputChannels;
        this.reusePages = reusePages;
        this.filterFunctions = filterFunctions;
        this.reorderFilters = reorderFilters;
        this.targetBytes = targetBytes;
    }

    public int[] getOutputChannels()
    {
        return outputChannels;
    }

    public boolean getReusePages()
    {
        return reusePages;
    }

    public FilterFunction[] getFilterFunctions()
    {
        return filterFunctions;
    }

    public boolean getReorderFilters()
    {
        return reorderFilters;
    }

    public int getTargetBytes()
    {
        return targetBytes;
    }
}
