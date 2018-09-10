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
package com.facebook.presto.plugin.geospatial.aggregation;

import com.esri.core.geometry.ogc.OGCGeometry;
import com.facebook.presto.array.LongBigArray;
import com.facebook.presto.array.ObjectBigArray;
import com.facebook.presto.spi.function.AccumulatorStateFactory;
import com.facebook.presto.spi.function.GroupedAccumulatorState;
import org.openjdk.jol.info.ClassLayout;

import java.util.ArrayList;
import java.util.List;

import static com.facebook.presto.geospatial.GeometryUtils.getGeometryMemorySize;

public class BufferedGeometryStateFactory
        implements AccumulatorStateFactory<BufferedGeometryState>
{
    private static final long ARRAY_LIST_SIZE = ClassLayout.parseClass(ArrayList.class).instanceSize();

    @Override
    public BufferedGeometryState createSingleState()
    {
        return new SingleGeometryState();
    }

    @Override
    public Class<? extends BufferedGeometryState> getSingleStateClass()
    {
        return SingleGeometryState.class;
    }

    @Override
    public BufferedGeometryState createGroupedState()
    {
        return new GroupedGeometryState();
    }

    @Override
    public Class<? extends BufferedGeometryState> getGroupedStateClass()
    {
        return GroupedGeometryState.class;
    }

    public static class GroupedGeometryState
            implements BufferedGeometryState, GroupedAccumulatorState
    {
        private long groupId;
        private ObjectBigArray<List<OGCGeometry>> lists = new ObjectBigArray<>();
        private LongBigArray memoryEstimates = new LongBigArray();
        private long totalMemoryEstimate;

        @Override
        public void ensureCapacity(long size)
        {
            lists.ensureCapacity(size);
            memoryEstimates.ensureCapacity(size);
        }

        @Override
        public long getEstimatedSize()
        {
            return lists.sizeOf() + memoryEstimates.sizeOf() + totalMemoryEstimate;
        }

        @Override
        public long getEstimatedBufferSize()
        {
            return memoryEstimates.get(groupId);
        }

        @Override
        public final void setGroupId(long groupId)
        {
            this.groupId = groupId;
        }

        @Override
        public List<OGCGeometry> getGeometries()
        {
            List<OGCGeometry> list = lists.get(groupId);
            if (list == null) {
                list = new ArrayList<>();
                lists.set(groupId, list);
                memoryEstimates.add(groupId, ARRAY_LIST_SIZE);
                totalMemoryEstimate += ARRAY_LIST_SIZE;
            }
            return list;
        }

        @Override
        public void addGeometries(List<OGCGeometry> geometries)
        {
            List<OGCGeometry> list = lists.get(groupId);
            if (list == null) {
                list = new ArrayList<>();
                lists.set(groupId, list);
                memoryEstimates.add(groupId, ARRAY_LIST_SIZE);
                totalMemoryEstimate += ARRAY_LIST_SIZE;
            }
            for (OGCGeometry geometry : geometries) {
                long geometryMemorySize = getGeometryMemorySize(geometry);
                memoryEstimates.add(groupId, geometryMemorySize);
                totalMemoryEstimate += geometryMemorySize;
                list.add(geometry);
            }
        }

        @Override
        public void reset()
        {
            List<OGCGeometry> list = lists.get(groupId);
            if (list != null) {
                totalMemoryEstimate -= memoryEstimates.get(groupId);
                memoryEstimates.set(groupId, 0);
                lists.set(groupId, null);
            }
        }
    }

    public static class SingleGeometryState
            implements BufferedGeometryState
    {
        private List<OGCGeometry> list;
        private long sizeOfGeometries;

        @Override
        public long getEstimatedSize()
        {
            return ARRAY_LIST_SIZE + sizeOfGeometries;
        }

        @Override
        public long getEstimatedBufferSize()
        {
            return getEstimatedSize();
        }

        @Override
        public List<OGCGeometry> getGeometries()
        {
            if (list == null) {
                list = new ArrayList<>();
                sizeOfGeometries += ARRAY_LIST_SIZE;
            }
            return list;
        }

        @Override
        public void addGeometries(List<OGCGeometry> geometries)
        {
            if (list == null) {
                list = new ArrayList<>();
                sizeOfGeometries += ARRAY_LIST_SIZE;
            }
            for (OGCGeometry geometry : geometries) {
                sizeOfGeometries += getGeometryMemorySize(geometry);
                list.add(geometry);
            }
        }

        @Override
        public void reset()
        {
            sizeOfGeometries = 0;
            list = null;
        }
    }
}
