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
package com.facebook.presto.hive.rule;

import com.facebook.presto.common.Subfield;
import com.facebook.presto.common.predicate.Domain;
import com.facebook.presto.common.predicate.NullableValue;
import com.facebook.presto.common.predicate.TupleDomain;
import com.facebook.presto.expressions.DefaultRowExpressionTraversalVisitor;
import com.facebook.presto.expressions.LogicalRowExpressions;
import com.facebook.presto.hive.BaseHiveTableLayoutHandle;
import com.facebook.presto.hive.HivePartitionResult;
import com.facebook.presto.hive.SubfieldExtractor;
import com.facebook.presto.spi.ColumnHandle;
import com.facebook.presto.spi.ConnectorPlanOptimizer;
import com.facebook.presto.spi.ConnectorPlanRewriter;
import com.facebook.presto.spi.ConnectorSession;
import com.facebook.presto.spi.ConnectorTableHandle;
import com.facebook.presto.spi.ConnectorTableLayout;
import com.facebook.presto.spi.ConnectorTableLayoutHandle;
import com.facebook.presto.spi.Constraint;
import com.facebook.presto.spi.PrestoException;
import com.facebook.presto.spi.PrestoWarning;
import com.facebook.presto.spi.TableHandle;
import com.facebook.presto.spi.VariableAllocator;
import com.facebook.presto.spi.connector.ConnectorMetadata;
import com.facebook.presto.spi.function.FunctionMetadataManager;
import com.facebook.presto.spi.function.StandardFunctionResolution;
import com.facebook.presto.spi.plan.FilterNode;
import com.facebook.presto.spi.plan.PlanNode;
import com.facebook.presto.spi.plan.PlanNodeIdAllocator;
import com.facebook.presto.spi.plan.TableScanNode;
import com.facebook.presto.spi.plan.ValuesNode;
import com.facebook.presto.spi.relation.ConstantExpression;
import com.facebook.presto.spi.relation.DomainTranslator.ExtractionResult;
import com.facebook.presto.spi.relation.RowExpression;
import com.facebook.presto.spi.relation.RowExpressionService;
import com.facebook.presto.spi.relation.VariableReferenceExpression;
import com.google.common.collect.BiMap;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

import static com.facebook.presto.common.predicate.TupleDomain.withColumnDomains;
import static com.facebook.presto.expressions.DynamicFilters.isDynamicFilter;
import static com.facebook.presto.expressions.DynamicFilters.removeNestedDynamicFilters;
import static com.facebook.presto.expressions.LogicalRowExpressions.FALSE_CONSTANT;
import static com.facebook.presto.expressions.LogicalRowExpressions.TRUE_CONSTANT;
import static com.facebook.presto.expressions.LogicalRowExpressions.and;
import static com.facebook.presto.expressions.LogicalRowExpressions.extractConjuncts;
import static com.facebook.presto.expressions.RowExpressionNodeInliner.replaceExpression;
import static com.facebook.presto.spi.ConnectorPlanRewriter.rewriteWith;
import static com.facebook.presto.spi.StandardErrorCode.DIVISION_BY_ZERO;
import static com.facebook.presto.spi.StandardErrorCode.GENERIC_INTERNAL_ERROR;
import static com.facebook.presto.spi.StandardErrorCode.INVALID_CAST_ARGUMENT;
import static com.facebook.presto.spi.StandardErrorCode.INVALID_FUNCTION_ARGUMENT;
import static com.facebook.presto.spi.StandardErrorCode.NUMERIC_VALUE_OUT_OF_RANGE;
import static com.facebook.presto.spi.relation.ExpressionOptimizer.Level.OPTIMIZED;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.collect.ImmutableBiMap.toImmutableBiMap;
import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static com.google.common.collect.Sets.intersection;
import static java.lang.String.format;
import static java.util.Collections.emptyList;
import static java.util.Objects.requireNonNull;

public abstract class BaseHiveFilterPushdown
        implements ConnectorPlanOptimizer
{
    private static final ConnectorTableLayout EMPTY_TABLE_LAYOUT = new ConnectorTableLayout(
            new ConnectorTableLayoutHandle() {},
            Optional.empty(),
            TupleDomain.none(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            emptyList());

    protected final RowExpressionService rowExpressionService;
    private final StandardFunctionResolution functionResolution;
    private final FunctionMetadataManager functionMetadataManager;

    public BaseHiveFilterPushdown(
            RowExpressionService rowExpressionService,
            StandardFunctionResolution functionResolution,
            FunctionMetadataManager functionMetadataManager)
    {
        this.rowExpressionService = requireNonNull(rowExpressionService, "rowExpressionService is null");
        this.functionResolution = requireNonNull(functionResolution, "functionResolution is null");
        this.functionMetadataManager = requireNonNull(functionMetadataManager, "functionMetadataManager is null");
    }

    @Override
    public PlanNode optimize(PlanNode maxSubplan, ConnectorSession session, VariableAllocator variableAllocator, PlanNodeIdAllocator idAllocator)
    {
        return rewriteWith(new Rewriter(session, idAllocator), maxSubplan);
    }

    public ConnectorPushdownFilterResult pushdownFilter(
            ConnectorSession session,
            ConnectorMetadata metadata,
            ConnectorTableHandle tableHandle,
            RowExpression filter,
            Optional<ConnectorTableLayoutHandle> currentLayoutHandle)
    {
        checkArgument(!FALSE_CONSTANT.equals(filter), "Cannot pushdown filter that is always false");

        if (TRUE_CONSTANT.equals(filter) && currentLayoutHandle.isPresent()) {
            return new ConnectorPushdownFilterResult(
                    metadata.getTableLayout(session, currentLayoutHandle.get()),
                    TRUE_CONSTANT);
        }

        // Split the filter into 3 groups of conjuncts:
        //  - range filters that apply to entire columns,
        //  - range filters that apply to subfields,
        //  - the rest. Intersect these with possibly pre-existing filters.
        ExtractionResult<Subfield> decomposedFilter = rowExpressionService.getDomainTranslator()
                .fromPredicate(session, filter, new SubfieldExtractor(
                        functionResolution,
                        rowExpressionService.getExpressionOptimizer(),
                        session).toColumnExtractor());

        if (currentLayoutHandle.isPresent()) {
            BaseHiveTableLayoutHandle currentHiveLayout = (BaseHiveTableLayoutHandle) currentLayoutHandle.get();
            decomposedFilter = intersectExtractionResult(
                    new ExtractionResult(currentHiveLayout.getDomainPredicate(), currentHiveLayout.getRemainingPredicate()),
                    decomposedFilter);
        }

        if (decomposedFilter.getTupleDomain().isNone()) {
            return new ConnectorPushdownFilterResult(EMPTY_TABLE_LAYOUT, FALSE_CONSTANT);
        }

        RowExpression optimizedRemainingExpression = rowExpressionService.getExpressionOptimizer()
                .optimize(decomposedFilter.getRemainingExpression(), OPTIMIZED, session);
        if (optimizedRemainingExpression instanceof ConstantExpression) {
            ConstantExpression constantExpression = (ConstantExpression) optimizedRemainingExpression;
            if (FALSE_CONSTANT.equals(constantExpression) || constantExpression.getValue() == null) {
                return new ConnectorPushdownFilterResult(EMPTY_TABLE_LAYOUT, FALSE_CONSTANT);
            }
        }

        Map<String, ColumnHandle> columnHandles = metadata.getColumnHandles(session, tableHandle);
        TupleDomain<ColumnHandle> entireColumnDomain = decomposedFilter.getTupleDomain()
                .transform(subfield -> isEntireColumn(subfield) ? subfield.getRootName() : null)
                .transform(columnHandles::get);

        if (currentLayoutHandle.isPresent()) {
            entireColumnDomain = intersectionWithPartitionColumnPredicate(currentLayoutHandle.get(), entireColumnDomain);
        }

        Constraint<ColumnHandle> constraint = new Constraint<>(entireColumnDomain);

        // Extract deterministic conjuncts that apply to partition columns and specify these as Constraint#predicate
        constraint = extractDeterministicConjuncts(
                session,
                decomposedFilter,
                columnHandles,
                entireColumnDomain,
                constraint);

        HivePartitionResult hivePartitionResult = getHivePartitionResult(metadata, tableHandle, constraint, session);

        TupleDomain<ColumnHandle> unenforcedConstraint = getUnenforcedConstraint(hivePartitionResult, constraint);

        TupleDomain<Subfield> domainPredicate = getDomainPredicate(decomposedFilter, unenforcedConstraint);

        RemainingExpressions remainingExpressions = getRemainingExpressions(tableHandle, decomposedFilter, columnHandles);

        Set<String> predicateColumnNames = getPredicateColumnNames(optimizedRemainingExpression, domainPredicate);

        return getConnectorPushdownFilterResult(
                columnHandles,
                metadata,
                session,
                domainPredicate,
                remainingExpressions,
                predicateColumnNames,
                currentLayoutHandle,
                tableHandle,
                hivePartitionResult);
    }

    protected abstract ConnectorPushdownFilterResult getConnectorPushdownFilterResult(
            Map<String, ColumnHandle> columnHandles,
            ConnectorMetadata metadata,
            ConnectorSession session,
            TupleDomain<Subfield> domainPredicate,
            RemainingExpressions remainingExpressions,
            Set<String> predicateColumnNames,
            Optional<ConnectorTableLayoutHandle> currentLayoutHandle,
            ConnectorTableHandle tableHandle,
            HivePartitionResult hivePartitionResult);

    protected abstract TupleDomain<ColumnHandle> getUnenforcedConstraint(
            HivePartitionResult hivePartitionResult,
            Constraint<ColumnHandle> constraint);

    protected abstract HivePartitionResult getHivePartitionResult(
            ConnectorMetadata metadata,
            ConnectorTableHandle tableHandle,
            Constraint<ColumnHandle> constraint,
            ConnectorSession session);

    protected abstract TupleDomain<ColumnHandle> intersectionWithPartitionColumnPredicate(
            ConnectorTableLayoutHandle currentLayoutHandle,
            TupleDomain<ColumnHandle> entireColumnDomain);

    protected abstract boolean isPushdownFilterSupported(ConnectorSession session, TableHandle tableHandle);

    protected abstract ConnectorMetadata getMetadata(TableHandle tableHandle);

    protected abstract PrestoWarning getPrestoWarning(TableScanNode tableScan);

    protected abstract Subfield toSubfield(ColumnHandle columnHandle);

    protected class Rewriter
            extends ConnectorPlanRewriter<Void>
    {
        private final ConnectorSession session;
        private final PlanNodeIdAllocator idAllocator;

        public Rewriter(ConnectorSession session, PlanNodeIdAllocator idAllocator)
        {
            this.session = requireNonNull(session, "session is null");
            this.idAllocator = requireNonNull(idAllocator, "idAllocator is null");
        }

        @Override
        public PlanNode visitFilter(FilterNode filter, RewriteContext<Void> context)
        {
            if (!(filter.getSource() instanceof TableScanNode)) {
                return visitPlan(filter, context);
            }

            TableScanNode tableScan = (TableScanNode) filter.getSource();
            if (!isPushdownFilterSupported(session, tableScan.getTable())) {
                return filter;
            }

            RowExpression expression = filter.getPredicate();
            TableHandle handle = tableScan.getTable();
            ConnectorMetadata metadata = getMetadata(handle);

            BiMap<VariableReferenceExpression, VariableReferenceExpression> symbolToColumnMapping =
                    getSymbolToColumnMapping(tableScan, handle, metadata, session);

            RowExpression replacedExpression = replaceExpression(expression, symbolToColumnMapping);
            // replaceExpression() may further optimize the expression;
            // if the resulting expression is always false, then return empty Values node
            if (FALSE_CONSTANT.equals(replacedExpression)) {
                session.getWarningCollector().add(getPrestoWarning(tableScan));
                return new ValuesNode(
                        tableScan.getSourceLocation(),
                        idAllocator.getNextId(),
                        tableScan.getOutputVariables(),
                        ImmutableList.of(),
                        Optional.of(tableScan.getTable().getConnectorHandle().toString()));
            }
            ConnectorPushdownFilterResult pushdownFilterResult = pushdownFilter(
                    session,
                    metadata,
                    handle.getConnectorHandle(),
                    replacedExpression,
                    handle.getLayout());

            ConnectorTableLayout layout = pushdownFilterResult.getLayout();
            if (layout.getPredicate().isNone()) {
                session.getWarningCollector().add(getPrestoWarning(tableScan));
                return new ValuesNode(
                        tableScan.getSourceLocation(),
                        idAllocator.getNextId(),
                        tableScan.getOutputVariables(),
                        ImmutableList.of(),
                        Optional.of(tableScan.getTable().getConnectorHandle().toString()));
            }

            return getPlanNode(tableScan, handle, symbolToColumnMapping, pushdownFilterResult, layout, idAllocator);
        }

        @Override
        public PlanNode visitTableScan(TableScanNode tableScan, RewriteContext<Void> context)
        {
            if (!isPushdownFilterSupported(session, tableScan.getTable())) {
                return tableScan;
            }

            TableHandle handle = tableScan.getTable();
            ConnectorMetadata metadata = getMetadata(handle);
            ConnectorPushdownFilterResult pushdownFilterResult = pushdownFilter(
                    session,
                    metadata,
                    handle.getConnectorHandle(),
                    TRUE_CONSTANT,
                    handle.getLayout());
            if (pushdownFilterResult.getLayout().getPredicate().isNone()) {
                session.getWarningCollector().add(getPrestoWarning(tableScan));
                return new ValuesNode(
                        tableScan.getSourceLocation(),
                        idAllocator.getNextId(),
                        tableScan.getOutputVariables(),
                        ImmutableList.of(),
                        Optional.of(tableScan.getTable().getConnectorHandle().toString()));
            }

            return getTableScanNode(tableScan, handle, pushdownFilterResult);
        }
    }

    private Constraint<ColumnHandle> extractDeterministicConjuncts(
            ConnectorSession session,
            ExtractionResult<Subfield> decomposedFilter,
            Map<String, ColumnHandle> columnHandles,
            TupleDomain<ColumnHandle> entireColumnDomain,
            Constraint<ColumnHandle> constraint)
    {
        if (!TRUE_CONSTANT.equals(decomposedFilter.getRemainingExpression())) {
            LogicalRowExpressions logicalRowExpressions = new LogicalRowExpressions(
                    rowExpressionService.getDeterminismEvaluator(),
                    functionResolution,
                    functionMetadataManager);
            RowExpression deterministicPredicate = logicalRowExpressions.filterDeterministicConjuncts(
                    decomposedFilter.getRemainingExpression());
            if (!TRUE_CONSTANT.equals(deterministicPredicate)) {
                ConstraintEvaluator evaluator = new ConstraintEvaluator(
                        rowExpressionService,
                        session,
                        columnHandles,
                        deterministicPredicate);
                constraint = new Constraint<>(entireColumnDomain, evaluator::isCandidate);
            }
        }
        return constraint;
    }

    private TupleDomain<Subfield> getDomainPredicate(
            ExtractionResult<Subfield> decomposedFilter,
            TupleDomain<ColumnHandle> unenforcedConstraint)
    {
        return withColumnDomains(ImmutableMap.<Subfield, Domain>builder()
                .putAll(unenforcedConstraint
                        .transform(this::toSubfield)
                        .getDomains()
                        .orElse(ImmutableMap.of()))
                .putAll(decomposedFilter.getTupleDomain()
                        .transform(subfield -> !isEntireColumn(subfield) ? subfield : null)
                        .getDomains()
                        .orElse(ImmutableMap.of()))
                .build());
    }

    private static Set<String> getPredicateColumnNames(
            RowExpression optimizedRemainingExpression,
            TupleDomain<Subfield> domainPredicate)
    {
        Set<String> predicateColumnNames = new HashSet<>();
        domainPredicate.getDomains().get().keySet().stream()
                .map(Subfield::getRootName)
                .forEach(predicateColumnNames::add);
        // Include only columns referenced in the optimized expression. Although the expression is sent to the worker node
        // unoptimized, the worker is expected to optimize the expression before executing.
        extractVariableExpressions(optimizedRemainingExpression).stream()
                .map(VariableReferenceExpression::getName)
                .forEach(predicateColumnNames::add);

        return predicateColumnNames;
    }

    private RemainingExpressions getRemainingExpressions(
            ConnectorTableHandle tableHandle,
            ExtractionResult<Subfield> decomposedFilter,
            Map<String, ColumnHandle> columnHandles)
    {
        LogicalRowExpressions logicalRowExpressions = new LogicalRowExpressions(
                rowExpressionService.getDeterminismEvaluator(),
                functionResolution,
                functionMetadataManager);
        List<RowExpression> conjuncts = extractConjuncts(decomposedFilter.getRemainingExpression());
        ImmutableList.Builder<RowExpression> dynamicConjuncts = ImmutableList.builder();
        ImmutableList.Builder<RowExpression> staticConjuncts = ImmutableList.builder();
        for (RowExpression conjunct : conjuncts) {
            if (isDynamicFilter(conjunct) || useDynamicFilter(conjunct, tableHandle, columnHandles)) {
                dynamicConjuncts.add(conjunct);
            }
            else {
                staticConjuncts.add(conjunct);
            }
        }
        RowExpression dynamicFilterExpression = logicalRowExpressions.combineConjuncts(dynamicConjuncts.build());
        RowExpression remainingExpression = logicalRowExpressions.combineConjuncts(staticConjuncts.build());
        remainingExpression = removeNestedDynamicFilters(remainingExpression);
        return new RemainingExpressions(dynamicFilterExpression, remainingExpression);
    }

    private static BiMap<VariableReferenceExpression, VariableReferenceExpression> getSymbolToColumnMapping(
            TableScanNode tableScan,
            TableHandle handle,
            ConnectorMetadata metadata,
            ConnectorSession session)
    {
        BiMap<VariableReferenceExpression, VariableReferenceExpression> symbolToColumnMapping =
                tableScan.getAssignments().entrySet().stream().collect(toImmutableBiMap(
                        Map.Entry::getKey,
                        entry -> new VariableReferenceExpression(
                                Optional.empty(),
                                getColumnName(session, metadata, handle.getConnectorHandle(), entry.getValue()),
                                entry.getKey().getType())));
        return symbolToColumnMapping;
    }

    private static String getColumnName(
            ConnectorSession session,
            ConnectorMetadata metadata,
            ConnectorTableHandle tableHandle,
            ColumnHandle columnHandle)
    {
        return metadata.getColumnMetadata(session, tableHandle, columnHandle).getName();
    }

    /**
     * If this method returns true, the expression will not be pushed inside the TableScan node.
     * This is exposed for dynamic or computed column functionality support.
     * Consider the following case:
     * Read a base row from the file. Modify the row to add a new column or update an existing column
     * all inside the TableScan. If filters are pushed down inside the TableScan, it would try to apply
     * it on base row. In these cases, override this method and return true. This will prevent the
     * expression from being pushed into the TableScan but will wrap the TableScanNode in a FilterNode.
     * @param expression expression to be evaluated.
     * @param tableHandle tableHandler where the expression to be evaluated.
     * @param columnHandleMap column name to column handle Map for all columns in the table.
     * @return true, if this expression should not be pushed inside the table scan, else false.
     */
    public static boolean useDynamicFilter(
            RowExpression expression,
            ConnectorTableHandle tableHandle,
            Map<String, ColumnHandle> columnHandleMap)
    {
        return false;
    }

    private static TableScanNode getTableScanNode(
            TableScanNode tableScan,
            TableHandle handle,
            ConnectorPushdownFilterResult pushdownFilterResult)
    {
        TableScanNode node = new TableScanNode(
                tableScan.getSourceLocation(),
                tableScan.getId(),
                new TableHandle(
                        handle.getConnectorId(),
                        handle.getConnectorHandle(),
                        handle.getTransaction(),
                        Optional.of(pushdownFilterResult.getLayout().getHandle())),
                tableScan.getOutputVariables(),
                tableScan.getAssignments(),
                tableScan.getTableConstraints(),
                pushdownFilterResult.getLayout().getPredicate(),
                TupleDomain.all());

        RowExpression unenforcedFilter = pushdownFilterResult.getUnenforcedConstraint();
        if (!TRUE_CONSTANT.equals(unenforcedFilter)) {
            throw new PrestoException(
                    GENERIC_INTERNAL_ERROR,
                    format("Unenforced filter found %s but not handled", unenforcedFilter));
        }
        return node;
    }

    private static PlanNode getPlanNode(
            TableScanNode tableScan,
            TableHandle handle,
            BiMap<VariableReferenceExpression, VariableReferenceExpression> symbolToColumnMapping,
            ConnectorPushdownFilterResult pushdownFilterResult,
            ConnectorTableLayout layout,
            PlanNodeIdAllocator idAllocator)
    {
        TableScanNode node = new TableScanNode(
                tableScan.getSourceLocation(),
                tableScan.getId(),
                new TableHandle(
                        handle.getConnectorId(),
                        handle.getConnectorHandle(),
                        handle.getTransaction(),
                        Optional.of(pushdownFilterResult.getLayout().getHandle())),
                tableScan.getOutputVariables(),
                tableScan.getAssignments(),
                tableScan.getTableConstraints(),
                layout.getPredicate(),
                TupleDomain.all());

        RowExpression unenforcedFilter = pushdownFilterResult.getUnenforcedConstraint();
        if (!TRUE_CONSTANT.equals(unenforcedFilter)) {
            return new FilterNode(
                    tableScan.getSourceLocation(),
                    idAllocator.getNextId(),
                    node,
                    replaceExpression(unenforcedFilter, symbolToColumnMapping.inverse()));
        }

        return node;
    }

    private static ExtractionResult intersectExtractionResult(
            ExtractionResult left,
            ExtractionResult right)
    {
        RowExpression newRemainingExpression;
        if (right.getRemainingExpression().equals(TRUE_CONSTANT)) {
            newRemainingExpression = left.getRemainingExpression();
        }
        else if (left.getRemainingExpression().equals(TRUE_CONSTANT)) {
            newRemainingExpression = right.getRemainingExpression();
        }
        else {
            newRemainingExpression = and(left.getRemainingExpression(), right.getRemainingExpression());
        }
        return new ExtractionResult(
                left.getTupleDomain().intersect(right.getTupleDomain()), newRemainingExpression);
    }

    private static boolean isEntireColumn(Subfield subfield)
    {
        return subfield.getPath().isEmpty();
    }

    public static class ConnectorPushdownFilterResult
    {
        private final ConnectorTableLayout layout;
        private final RowExpression unenforcedConstraint;

        public ConnectorPushdownFilterResult(ConnectorTableLayout layout, RowExpression unenforcedConstraint)
        {
            this.layout = requireNonNull(layout, "layout is null");
            this.unenforcedConstraint = requireNonNull(unenforcedConstraint, "unenforcedConstraint is null");
        }

        public ConnectorTableLayout getLayout()
        {
            return layout;
        }

        public RowExpression getUnenforcedConstraint()
        {
            return unenforcedConstraint;
        }
    }

    private static class ConstraintEvaluator
    {
        private final Map<String, ColumnHandle> assignments;
        private final RowExpressionService evaluator;
        private final ConnectorSession session;
        private final RowExpression expression;
        private final Set<ColumnHandle> arguments;

        public ConstraintEvaluator(
                RowExpressionService evaluator,
                ConnectorSession session,
                Map<String, ColumnHandle> assignments,
                RowExpression expression)
        {
            this.assignments = assignments;
            this.evaluator = evaluator;
            this.session = session;
            this.expression = expression;

            arguments = ImmutableSet.copyOf(extractVariableExpressions(expression)).stream()
                    .map(VariableReferenceExpression::getName)
                    .map(assignments::get)
                    .collect(toImmutableSet());
        }

        private boolean isCandidate(Map<ColumnHandle, NullableValue> bindings)
        {
            if (intersection(bindings.keySet(), arguments).isEmpty()) {
                return true;
            }

            Function<VariableReferenceExpression, Object> variableResolver = variable -> {
                ColumnHandle column = assignments.get(variable.getName());
                checkArgument(column != null, "Missing column assignment for %s", variable);

                if (!bindings.containsKey(column)) {
                    return variable;
                }

                return bindings.get(column).getValue();
            };

            // Skip pruning if evaluation fails in a recoverable way. Failing here can cause
            // spurious query failures for partitions that would otherwise be filtered out.
            Object optimized = null;
            try {
                optimized = evaluator.getExpressionOptimizer().optimize(expression, OPTIMIZED, session, variableResolver);
            }
            catch (PrestoException e) {
                propagateIfUnhandled(e);
                return true;
            }

            // If any conjuncts evaluate to FALSE or null, then the whole predicate will never be true and so the partition should be pruned
            return !Boolean.FALSE.equals(optimized) && optimized != null
                    && (!(optimized instanceof ConstantExpression) || !((ConstantExpression) optimized).isNull());
        }

        private static void propagateIfUnhandled(PrestoException e)
                throws PrestoException
        {
            int errorCode = e.getErrorCode().getCode();
            if (errorCode == DIVISION_BY_ZERO.toErrorCode().getCode()
                    || errorCode == INVALID_CAST_ARGUMENT.toErrorCode().getCode()
                    || errorCode == INVALID_FUNCTION_ARGUMENT.toErrorCode().getCode()
                    || errorCode == NUMERIC_VALUE_OUT_OF_RANGE.toErrorCode().getCode()) {
                return;
            }

            throw e;
        }
    }

    private static Set<VariableReferenceExpression> extractVariableExpressions(RowExpression expression)
    {
        ImmutableSet.Builder<VariableReferenceExpression> builder = ImmutableSet.builder();
        expression.accept(new VariableReferenceBuilderVisitor(), builder);
        return builder.build();
    }

    private static class VariableReferenceBuilderVisitor
            extends DefaultRowExpressionTraversalVisitor<ImmutableSet.Builder<VariableReferenceExpression>>
    {
        @Override
        public Void visitVariableReference(
                VariableReferenceExpression variable,
                ImmutableSet.Builder<VariableReferenceExpression> builder)
        {
            builder.add(variable);
            return null;
        }
    }

    public static class RemainingExpressions
    {
        public final RowExpression dynamicFilterExpression;
        public final RowExpression remainingExpression;

        public RemainingExpressions(RowExpression dynamicFilterExpression, RowExpression remainingExpression)
        {
            this.dynamicFilterExpression = dynamicFilterExpression;
            this.remainingExpression = remainingExpression;
        }

        public RowExpression getDynamicFilterExpression()
        {
            return dynamicFilterExpression;
        }

        public RowExpression getRemainingExpression()
        {
            return remainingExpression;
        }
    }
}
