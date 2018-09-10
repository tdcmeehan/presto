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

import com.esri.core.geometry.ogc.OGCConcreteGeometryCollection;
import com.facebook.presto.geospatial.serde.GeometrySerde;
import com.facebook.presto.spi.block.Block;
import com.facebook.presto.spi.block.BlockBuilder;
import com.facebook.presto.spi.function.AccumulatorStateSerializer;
import com.facebook.presto.spi.type.Type;

import static com.facebook.presto.geospatial.GeometryUtils.flatten;
import static com.facebook.presto.plugin.geospatial.GeometryType.GEOMETRY;

public class BufferedGeometryStateSerializer
        implements AccumulatorStateSerializer<BufferedGeometryState>
{
    @Override
    public Type getSerializedType()
    {
        return GEOMETRY;
    }

    @Override
    public void serialize(BufferedGeometryState state, BlockBuilder out)
    {
        if (state.getGeometries() == null || state.getGeometries().isEmpty()) {
            out.appendNull();
        }
        else {
            GEOMETRY.writeSlice(out, GeometrySerde.serialize(new OGCConcreteGeometryCollection(state.getGeometries(), null)));
        }
    }

    @Override
    public void deserialize(Block block, int index, BufferedGeometryState state)
    {
        state.reset();
        state.addGeometries(flatten(GeometrySerde.deserialize(GEOMETRY.getSlice(block, index))));
    }
}
