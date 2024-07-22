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
package com.facebook.presto.sql.expressions;

import com.facebook.presto.metadata.FunctionAndTypeManager;
import com.facebook.presto.spi.NodeManager;
import com.facebook.presto.spi.RowExpressionSerde;
import com.facebook.presto.spi.function.FunctionMetadataManager;
import com.facebook.presto.spi.function.StandardFunctionResolution;
import com.facebook.presto.spi.relation.ExpressionOptimizer;
import com.facebook.presto.spi.sql.planner.ExpressionOptimizerFactory;
import com.facebook.presto.sql.relational.RowExpressionOptimizer;
import com.google.inject.Inject;

import java.util.Map;

import static java.util.Objects.requireNonNull;

public class JavaEvalExpressionOptimizerFactory
        implements ExpressionOptimizerFactory
{
    private final FunctionAndTypeManager functionAndTypeManager;

    @Inject
    public JavaEvalExpressionOptimizerFactory(FunctionAndTypeManager functionAndTypeManager)
    {
        this.functionAndTypeManager = requireNonNull(functionAndTypeManager, "functionAndTypeManager is null");
    }

    @Override
    public ExpressionOptimizer createOptimizer(Map<String, String> config, NodeManager nodeManager, RowExpressionSerde rowExpressionSerde, FunctionMetadataManager functionMetadataManager, StandardFunctionResolution functionResolution)
    {
        return new RowExpressionOptimizer(functionAndTypeManager);
    }

    @Override
    public String getName()
    {
        return "default";
    }
}
