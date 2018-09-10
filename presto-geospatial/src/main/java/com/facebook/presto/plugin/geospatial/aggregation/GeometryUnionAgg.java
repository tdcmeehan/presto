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
import com.facebook.presto.geospatial.serde.GeometrySerde;
import com.facebook.presto.spi.block.BlockBuilder;
import com.facebook.presto.spi.function.AggregationFunction;
import com.facebook.presto.spi.function.AggregationState;
import com.facebook.presto.spi.function.CombineFunction;
import com.facebook.presto.spi.function.Description;
import com.facebook.presto.spi.function.InputFunction;
import com.facebook.presto.spi.function.OutputFunction;
import com.facebook.presto.spi.function.SqlType;
import io.airlift.slice.Slice;
import io.airlift.units.DataSize;

import java.util.List;

import static com.facebook.presto.geospatial.GeometryUtils.flatten;
import static com.facebook.presto.geospatial.GeometryUtils.union;
import static com.facebook.presto.geospatial.serde.GeometrySerde.deserialize;
import static com.facebook.presto.plugin.geospatial.GeometryType.GEOMETRY;
import static com.facebook.presto.plugin.geospatial.GeometryType.GEOMETRY_TYPE_NAME;

/**
 * Aggregate form of ST_Union which takes a set of geometries and unions them into a single geometry, resulting in no intersecting
 * regions.  The output may be a multi-geometry, a single geometry or a geometry collection.
 *
 * This function will first buffer geometries into a configurable buffer size and then unioned in bulk.  The underlying algorithm
 * will attempt to take advantage of any touching/overlapping geometries, reducing the number of intermediate geometries that need
 * to be unioned, potentially resulting in faster execution times than geometry_mem_union_agg.
 */
@Description("Returns a geometry that represents the point set union of the input geometries.")
@AggregationFunction("geometry_union_agg")
public class GeometryUnionAgg
{
    // TODO: is it possible to make this a session parameter with a sensible default
    private static final long AGGREGATION_MEMORY_LIMIT = DataSize.valueOf("100 MB").toBytes();

    private GeometryUnionAgg() {}

    @InputFunction
    public static void input(@AggregationState BufferedGeometryState state, @SqlType(GEOMETRY_TYPE_NAME) Slice input)
    {
        unionWithList(state, flatten(deserialize(input)));
    }

    @CombineFunction
    public static void combine(@AggregationState BufferedGeometryState state, @AggregationState BufferedGeometryState otherState)
    {
        unionWithList(state, otherState.getGeometries());
    }

    @OutputFunction(GEOMETRY_TYPE_NAME)
    public static void output(@AggregationState BufferedGeometryState state, BlockBuilder out)
    {
        if (state.getGeometries() == null || state.getGeometries().isEmpty()) {
            out.appendNull();
        }
        else {
            GEOMETRY.writeSlice(out, GeometrySerde.serialize(union(state.getGeometries())));
        }
    }

    private static void unionWithList(BufferedGeometryState state, List<OGCGeometry> geometries)
    {
        state.addGeometries(geometries);
        if (state.getEstimatedBufferSize() > AGGREGATION_MEMORY_LIMIT) {
            OGCGeometry unioned = union(state.getGeometries());
            state.reset();
            state.addGeometries(flatten(unioned));
        }
    }
}
