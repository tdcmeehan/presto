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
package com.facebook.presto.iceberg.optimizer;

import com.facebook.presto.common.Subfield;
import com.facebook.presto.common.predicate.TupleDomain;
import com.facebook.presto.hive.rule.BaseRewriter;
import com.facebook.presto.iceberg.IcebergAbstractMetadata;
import com.facebook.presto.iceberg.IcebergColumnHandle;
import com.facebook.presto.iceberg.IcebergTableHandle;
import com.facebook.presto.iceberg.IcebergTableLayoutHandle;
import com.facebook.presto.iceberg.IcebergTransactionManager;
import com.facebook.presto.spi.ColumnHandle;
import com.facebook.presto.spi.ConnectorPlanOptimizer;
import com.facebook.presto.spi.ConnectorSession;
import com.facebook.presto.spi.ConnectorTableHandle;
import com.facebook.presto.spi.ConnectorTableLayoutHandle;
import com.facebook.presto.spi.Constraint;
import com.facebook.presto.spi.TableHandle;
import com.facebook.presto.spi.VariableAllocator;
import com.facebook.presto.spi.connector.ConnectorMetadata;
import com.facebook.presto.spi.function.FunctionMetadataManager;
import com.facebook.presto.spi.function.StandardFunctionResolution;
import com.facebook.presto.spi.plan.PlanNode;
import com.facebook.presto.spi.plan.PlanNodeIdAllocator;
import com.facebook.presto.spi.relation.DomainTranslator;
import com.facebook.presto.spi.relation.RowExpression;
import com.facebook.presto.spi.relation.RowExpressionService;
import com.google.common.base.Functions;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static com.facebook.presto.hive.rule.FilterPushdownUtils.getDomainPredicate;
import static com.facebook.presto.hive.rule.FilterPushdownUtils.getPredicateColumnNames;
import static com.facebook.presto.iceberg.IcebergSessionProperties.isPushdownFilterEnabled;
import static com.facebook.presto.spi.ConnectorPlanRewriter.rewriteWith;
import static com.google.common.base.Preconditions.checkState;
import static com.google.common.collect.ImmutableMap.toImmutableMap;
import static java.util.Objects.requireNonNull;

public class IcebergFilterPushdown
        implements ConnectorPlanOptimizer
{
    private final RowExpressionService rowExpressionService;
    private final StandardFunctionResolution functionResolution;
    private final FunctionMetadataManager functionMetadataManager;
    protected IcebergTransactionManager icebergTransactionManager;

    public IcebergFilterPushdown(
            RowExpressionService rowExpressionService,
            StandardFunctionResolution functionResolution,
            FunctionMetadataManager functionMetadataManager,
            IcebergTransactionManager transactionManager)
    {
        this.rowExpressionService = requireNonNull(rowExpressionService, "rowExpressionService is null");
        this.functionResolution = requireNonNull(functionResolution, "functionResolution is null");
        this.functionMetadataManager = requireNonNull(functionMetadataManager, "functionMetadataManager is null");
        this.icebergTransactionManager = requireNonNull(transactionManager, "transactionManager is null");
    }

    @Override
    public PlanNode optimize(PlanNode maxSubplan, ConnectorSession session, VariableAllocator variableAllocator, PlanNodeIdAllocator idAllocator)
    {
        if (!isPushdownFilterEnabled(session)) {
            return maxSubplan;
        }
        return rewriteWith(new Rewriter(session, idAllocator, rowExpressionService, functionResolution, functionMetadataManager), maxSubplan);
    }

    private class Rewriter
            extends BaseRewriter
    {
        public Rewriter(
                ConnectorSession session,
                PlanNodeIdAllocator idAllocator,
                RowExpressionService rowExpressionService,
                StandardFunctionResolution functionResolution,
                FunctionMetadataManager functionMetadataManager)
        {
            super(session, idAllocator, rowExpressionService, functionResolution, functionMetadataManager, tableHandle -> getConnectorMetadata(icebergTransactionManager, tableHandle));
        }

        @Override
        protected ConnectorPushdownFilterResult getConnectorPushdownFilterResult(
                Map<String, ColumnHandle> columnHandles,
                ConnectorMetadata metadata,
                ConnectorSession session,
                RemainingExpressions remainingExpressions,
                DomainTranslator.ExtractionResult<Subfield> decomposedFilter,
                RowExpression optimizedRemainingExpression,
                Constraint<ColumnHandle> constraint,
                Optional<ConnectorTableLayoutHandle> currentLayoutHandle,
                ConnectorTableHandle tableHandle)
        {
            TupleDomain<ColumnHandle> unenforcedConstraint = TupleDomain.withColumnDomains(constraint.getSummary().getDomains().get());

            TupleDomain<Subfield> domainPredicate = getDomainPredicate(decomposedFilter, unenforcedConstraint);

            Set<String> predicateColumnNames = getPredicateColumnNames(optimizedRemainingExpression, domainPredicate);

            Map<String, IcebergColumnHandle> predicateColumns = predicateColumnNames.stream()
                    .map(columnHandles::get)
                    .map(IcebergColumnHandle.class::cast)
                    .collect(toImmutableMap(IcebergColumnHandle::getName, Functions.identity()));

            Optional<Set<IcebergColumnHandle>> requestedColumns = currentLayoutHandle.map(layout -> ((IcebergTableLayoutHandle) layout).getRequestedColumns()).orElse(Optional.empty());

            return new ConnectorPushdownFilterResult(
                    metadata.getTableLayout(
                            session,
                            new IcebergTableLayoutHandle(
                                    domainPredicate,
                                    remainingExpressions.getRemainingExpression(),
                                    predicateColumns,
                                    requestedColumns,
                                    true,
                                    (IcebergTableHandle) tableHandle)),
                    remainingExpressions.getDynamicFilterExpression());
        }
    }

    private static ConnectorMetadata getConnectorMetadata(IcebergTransactionManager icebergTransactionManager, TableHandle tableHandle)
    {
        requireNonNull(icebergTransactionManager, "icebergTransactionManager is null");
        ConnectorMetadata metadata = icebergTransactionManager.get(tableHandle.getTransaction());
        checkState(metadata instanceof IcebergAbstractMetadata, "metadata must be IcebergAbstractMetadata");
        return metadata;
    }
}
