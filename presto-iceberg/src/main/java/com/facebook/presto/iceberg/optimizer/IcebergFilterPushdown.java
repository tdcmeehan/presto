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
import com.facebook.presto.hive.HivePartitionResult;
import com.facebook.presto.hive.rule.BaseHiveFilterPushdown;
import com.facebook.presto.iceberg.IcebergAbstractMetadata;
import com.facebook.presto.iceberg.IcebergColumnHandle;
import com.facebook.presto.iceberg.IcebergTableHandle;
import com.facebook.presto.iceberg.IcebergTableLayoutHandle;
import com.facebook.presto.iceberg.IcebergTransactionManager;
import com.facebook.presto.spi.ColumnHandle;
import com.facebook.presto.spi.ConnectorSession;
import com.facebook.presto.spi.ConnectorTableHandle;
import com.facebook.presto.spi.ConnectorTableLayoutHandle;
import com.facebook.presto.spi.Constraint;
import com.facebook.presto.spi.PrestoWarning;
import com.facebook.presto.spi.TableHandle;
import com.facebook.presto.spi.connector.ConnectorMetadata;
import com.facebook.presto.spi.function.FunctionMetadataManager;
import com.facebook.presto.spi.function.StandardFunctionResolution;
import com.facebook.presto.spi.plan.TableScanNode;
import com.facebook.presto.spi.plan.ValuesNode;
import com.facebook.presto.spi.relation.RowExpressionService;
import com.google.common.base.Functions;
import com.google.common.collect.ImmutableList;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static com.facebook.presto.iceberg.IcebergSessionProperties.isPushdownFilterEnabled;
import static com.facebook.presto.iceberg.IcebergWarningCode.ICEBERG_TABLESCAN_CONVERTED_TO_VALUESNODE;
import static com.google.common.base.Preconditions.checkState;
import static com.google.common.collect.ImmutableMap.toImmutableMap;
import static java.lang.String.format;
import static java.util.Objects.requireNonNull;

public class IcebergFilterPushdown
        extends BaseHiveFilterPushdown
{
    protected IcebergTransactionManager icebergTransactionManager;

    public IcebergFilterPushdown(
            RowExpressionService rowExpressionService,
            StandardFunctionResolution functionResolution,
            FunctionMetadataManager functionMetadataManager,
            IcebergTransactionManager transactionManager)
    {
        super(rowExpressionService, functionResolution, functionMetadataManager);

        this.icebergTransactionManager = requireNonNull(transactionManager, "transactionManager is null");
    }

    @Override
    protected ConnectorPushdownFilterResult getConnectorPushdownFilterResult(
            Map<String, ColumnHandle> columnHandles,
            ConnectorMetadata metadata,
            ConnectorSession session,
            TupleDomain<Subfield> domainPredicate,
            RemainingExpressions remainingExpressions,
            Set<String> predicateColumnNames,
            Optional<ConnectorTableLayoutHandle> currentLayoutHandle,
            ConnectorTableHandle tableHandle,
            HivePartitionResult hivePartitionResult)
    {
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

    @Override
    protected TupleDomain<ColumnHandle> getUnenforcedConstraint(HivePartitionResult hivePartitionResult, Constraint<ColumnHandle> constraint)
    {
        return TupleDomain.withColumnDomains(constraint.getSummary().getDomains().get());
    }

    @Override
    protected HivePartitionResult getHivePartitionResult(ConnectorMetadata metadata, ConnectorTableHandle tableHandle, Constraint<ColumnHandle> constraint, ConnectorSession session)
    {
        return null;
    }

    @Override
    protected TupleDomain<ColumnHandle> intersectionWithPartitionColumnPredicate(ConnectorTableLayoutHandle currentLayoutHandle, TupleDomain<ColumnHandle> entireColumnDomain)
    {
        return entireColumnDomain;
    }

    @Override
    protected boolean isPushdownFilterSupported(ConnectorSession session, TableHandle tableHandle)
    {
        return isPushdownFilterEnabled(session);
    }

    @Override
    protected ConnectorMetadata getMetadata(TableHandle tableHandle)
    {
        ConnectorMetadata metadata = icebergTransactionManager.get(tableHandle.getTransaction());
        checkState(metadata instanceof IcebergAbstractMetadata, "metadata must be IcebergAbstractMetadata");
        return metadata;
    }

    @Override
    protected PrestoWarning getPrestoWarning(TableScanNode tableScan)
    {
        return new PrestoWarning(
                ICEBERG_TABLESCAN_CONVERTED_TO_VALUESNODE,
                format(
                        "Table '%s' returns 0 rows, and is converted to an empty %s by %s",
                        tableScan.getTable().getConnectorHandle(),
                        ValuesNode.class.getSimpleName(),
                        IcebergFilterPushdown.class.getSimpleName()));
    }

    @Override
    protected Subfield toSubfield(ColumnHandle columnHandle)
    {
        return new Subfield(((IcebergColumnHandle) columnHandle).getName(), ImmutableList.of());
    }
}
