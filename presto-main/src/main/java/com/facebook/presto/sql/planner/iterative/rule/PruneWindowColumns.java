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
package com.facebook.presto.sql.planner.iterative.rule;

import com.facebook.presto.spi.plan.PlanNodeIdAllocator;
import com.facebook.presto.spi.relation.VariableReferenceExpression;
import com.facebook.presto.sql.planner.Symbol;
import com.facebook.presto.sql.planner.SymbolAllocator;
import com.facebook.presto.sql.planner.optimizations.WindowNodeUtil;
import com.facebook.presto.sql.planner.plan.PlanNode;
import com.facebook.presto.sql.planner.plan.WindowNode;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Maps;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static com.facebook.presto.sql.planner.iterative.rule.Util.restrictOutputs;
import static com.facebook.presto.sql.planner.plan.Patterns.window;
import static com.google.common.collect.ImmutableSet.toImmutableSet;

public class PruneWindowColumns
        extends ProjectOffPushDownRule<WindowNode>
{
    public PruneWindowColumns()
    {
        super(window());
    }

    @Override
    protected Optional<PlanNode> pushDownProjectOff(PlanNodeIdAllocator idAllocator, SymbolAllocator symbolAllocator, WindowNode windowNode, Set<Symbol> referencedOutputs)
    {
        Set<String> referencedOutputNames = referencedOutputs.stream().map(Symbol::getName).collect(toImmutableSet());
        Map<VariableReferenceExpression, WindowNode.Function> referencedFunctions = Maps.filterKeys(
                windowNode.getWindowFunctions(),
                variable -> referencedOutputNames.contains(variable.getName()));

        if (referencedFunctions.isEmpty()) {
            return Optional.of(windowNode.getSource());
        }

        ImmutableSet.Builder<Symbol> referencedInputs = ImmutableSet.<Symbol>builder()
                .addAll(windowNode.getSource().getOutputSymbols().stream()
                        .filter(referencedOutputs::contains)
                        .iterator())
                .addAll(windowNode.getPartitionBy().stream().map(VariableReferenceExpression::getName).map(Symbol::new).collect(toImmutableSet()));

        windowNode.getOrderingScheme().ifPresent(
                orderingScheme -> orderingScheme
                        .getOrderBy()
                        .forEach(variable -> referencedInputs.add(new Symbol(variable.getName()))));
        windowNode.getHashVariable().ifPresent(variable -> referencedInputs.add(new Symbol(variable.getName())));

        for (WindowNode.Function windowFunction : referencedFunctions.values()) {
            referencedInputs.addAll(WindowNodeUtil.extractWindowFunctionUnique(windowFunction));
            windowFunction.getFrame().getStartValue().ifPresent(variable -> referencedInputs.add(new Symbol(variable.getName())));
            windowFunction.getFrame().getEndValue().ifPresent(variable -> referencedInputs.add(new Symbol(variable.getName())));
        }

        PlanNode prunedWindowNode = new WindowNode(
                windowNode.getId(),
                restrictOutputs(idAllocator, symbolAllocator, windowNode.getSource(), referencedInputs.build())
                        .orElse(windowNode.getSource()),
                windowNode.getSpecification(),
                referencedFunctions,
                windowNode.getHashVariable(),
                windowNode.getPrePartitionedInputs(),
                windowNode.getPreSortedOrderPrefix());

        if (prunedWindowNode.getOutputSymbols().size() == windowNode.getOutputSymbols().size()) {
            // Neither function pruning nor input pruning was successful.
            return Optional.empty();
        }

        return Optional.of(prunedWindowNode);
    }
}
