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
import com.facebook.presto.operator.aggregation.state.StateCompiler;
import com.facebook.presto.plugin.geospatial.GeometryType;
import com.facebook.presto.spi.block.Block;
import com.facebook.presto.spi.block.BlockBuilder;
import com.facebook.presto.spi.function.AccumulatorStateFactory;
import com.facebook.presto.spi.function.AccumulatorStateSerializer;
import org.testng.annotations.Test;

import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import static com.esri.core.geometry.ogc.OGCGeometry.fromText;
import static java.util.Arrays.asList;
import static org.testng.Assert.assertEquals;

public class TestBufferedGeometryStateSerializer
{
    @Test
    public void testSerializeDeserialize()
    {
        AccumulatorStateFactory<BufferedGeometryState> factory = StateCompiler.generateStateFactory(BufferedGeometryState.class);
        AccumulatorStateSerializer<BufferedGeometryState> serializer = StateCompiler.generateStateSerializer(BufferedGeometryState.class);
        BufferedGeometryState state = factory.createSingleState();

        state.addGeometries(asList(fromText("POINT (1 2)"), fromText("POINT (2 3)")));

        BlockBuilder builder = GeometryType.GEOMETRY.createBlockBuilder(null, 1);
        serializer.serialize(state, builder);
        Block block = builder.build();

        assertEquals(GeometryType.GEOMETRY.getObjectValue(null, block, 0), "GEOMETRYCOLLECTION (POINT (1 2), POINT (2 3))");

        state.reset();
        serializer.deserialize(block, 0, state);

        assertEquals(geometriesAsText(state), asList("POINT (1 2)", "POINT (2 3)"));
    }
    @Test
    public void testSerializeDeserializeGrouped()
    {
        AccumulatorStateFactory<BufferedGeometryState> factory = StateCompiler.generateStateFactory(BufferedGeometryState.class);
        AccumulatorStateSerializer<BufferedGeometryState> serializer = StateCompiler.generateStateSerializer(BufferedGeometryState.class);
        BufferedGeometryStateFactory.GroupedGeometryState state = (BufferedGeometryStateFactory.GroupedGeometryState) factory.createGroupedState();

        // Add state to group 1
        state.setGroupId(1);
        state.addGeometries(asList(fromText("POINT (1 2)"), fromText("POINT (2 3)")));
        // Add another state to group 2, to show that this doesn't affect the group under test (group 1)
        state.setGroupId(2);
        state.addGeometries(asList(fromText("POINT (3 4)"), fromText("POINT (4 5)")));
        // Return to group 1
        state.setGroupId(1);

        BlockBuilder builder = GeometryType.GEOMETRY.createBlockBuilder(null, 1);
        serializer.serialize(state, builder);
        Block block = builder.build();

        assertEquals(GeometryType.GEOMETRY.getObjectValue(null, block, 0), "GEOMETRYCOLLECTION (POINT (1 2), POINT (2 3))");

        state.reset();
        serializer.deserialize(block, 0, state);

        // Assert the state of group 1
        assertEquals(geometriesAsText(state), asList("POINT (1 2)", "POINT (2 3)"));
        // Verify nothing changed in group 2
        state.setGroupId(2);
        assertEquals(geometriesAsText(state), asList("POINT (3 4)", "POINT (4 5)"));
        // Groups we did not touch are null
        state.setGroupId(3);
        assertEquals(state.getGeometries(), Collections.emptyList());
    }

    private List<String> geometriesAsText(BufferedGeometryState state)
    {
        return state.getGeometries().stream().map(OGCGeometry::asText).collect(Collectors.toList());
    }
}
