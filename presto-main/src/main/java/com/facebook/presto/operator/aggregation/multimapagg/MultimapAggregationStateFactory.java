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
package com.facebook.presto.operator.aggregation.multimapagg;

import com.facebook.presto.spi.function.AccumulatorStateFactory;
import com.facebook.presto.spi.type.Type;

public class MultimapAggregationStateFactory
        implements AccumulatorStateFactory<MultimapAggregationState>
{
    private final Type keyType;
    private final Type valueType;
    private final MultimapAggGroupImplementation implementation;

    public MultimapAggregationStateFactory(Type keyType, Type valueType, MultimapAggGroupImplementation implementation)
    {
        this.keyType = keyType;
        this.valueType = valueType;
        this.implementation = implementation;
    }

    @Override
    public MultimapAggregationState createSingleState()
    {
        return new SingleMultimapAggregationState(keyType, valueType);
    }

    @Override
    public Class<? extends MultimapAggregationState> getSingleStateClass()
    {
        return SingleMultimapAggregationState.class;
    }

    @Override
    public MultimapAggregationState createGroupedState()
    {
        switch (implementation) {
            case NEW:
                return new GroupedMultimapAggregationState(keyType, valueType);
            case LEGACY:
            default:
                return new LegacyGroupedMultimapAggregationState(keyType, valueType);
        }
    }

    @Override
    public Class<? extends MultimapAggregationState> getGroupedStateClass()
    {
        switch (implementation) {
            case NEW:
                return GroupedMultimapAggregationState.class;
            case LEGACY:
            default:
                return LegacyGroupedMultimapAggregationState.class;
        }
    }
}
