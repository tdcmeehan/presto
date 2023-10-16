package com.facebook.presto.hive.rule;

import com.facebook.presto.common.Subfield;
import com.facebook.presto.common.predicate.NullableValue;
import com.facebook.presto.common.predicate.TupleDomain;
import com.facebook.presto.expressions.DefaultRowExpressionTraversalVisitor;
import com.facebook.presto.expressions.LogicalRowExpressions;
import com.facebook.presto.expressions.RowExpressionNodeInliner;
import com.facebook.presto.hive.BaseHiveTableLayoutHandle;
import com.facebook.presto.hive.SubfieldExtractor;
import com.facebook.presto.hive.SupportsPartitionColumnPredicate;
import com.facebook.presto.spi.ColumnHandle;
import com.facebook.presto.spi.ConnectorPlanRewriter;
import com.facebook.presto.spi.ConnectorSession;
import com.facebook.presto.spi.ConnectorTableHandle;
import com.facebook.presto.spi.ConnectorTableLayout;
import com.facebook.presto.spi.ConnectorTableLayoutHandle;
import com.facebook.presto.spi.Constraint;
import com.facebook.presto.spi.PrestoException;
import com.facebook.presto.spi.PrestoWarning;
import com.facebook.presto.spi.TableHandle;
import com.facebook.presto.spi.connector.ConnectorMetadata;
import com.facebook.presto.spi.function.FunctionMetadataManager;
import com.facebook.presto.spi.function.StandardFunctionResolution;
import com.facebook.presto.spi.plan.FilterNode;
import com.facebook.presto.spi.plan.PlanNode;
import com.facebook.presto.spi.plan.PlanNodeIdAllocator;
import com.facebook.presto.spi.plan.TableScanNode;
import com.facebook.presto.spi.plan.ValuesNode;
import com.facebook.presto.spi.relation.ConstantExpression;
import com.facebook.presto.spi.relation.DomainTranslator;
import com.facebook.presto.spi.relation.RowExpression;
import com.facebook.presto.spi.relation.RowExpressionService;
import com.facebook.presto.spi.relation.VariableReferenceExpression;
import com.google.common.collect.BiMap;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

import static com.facebook.presto.expressions.DynamicFilters.isDynamicFilter;
import static com.facebook.presto.expressions.DynamicFilters.removeNestedDynamicFilters;
import static com.facebook.presto.expressions.LogicalRowExpressions.extractConjuncts;
import static com.facebook.presto.hive.HiveWarningCode.HIVE_TABLESCAN_CONVERTED_TO_VALUESNODE;
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

public abstract class BaseRewriter
        extends ConnectorPlanRewriter<Void>
{
    private static final ConnectorTableLayout EMPTY_TABLE_LAYOUT = new ConnectorTableLayout(
            new ConnectorTableLayoutHandle() {},
            Optional.empty(),
            TupleDomain.none(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            emptyList());

    private final ConnectorSession session;
    private final PlanNodeIdAllocator idAllocator;
    protected final RowExpressionService rowExpressionService;
    private final StandardFunctionResolution functionResolution;
    private final FunctionMetadataManager functionMetadataManager;
    private Function<TableHandle, ConnectorMetadata> transactionToMetadata;

    public BaseRewriter(
            ConnectorSession session,
            PlanNodeIdAllocator idAllocator,
            RowExpressionService rowExpressionService,
            StandardFunctionResolution functionResolution,
            FunctionMetadataManager functionMetadataManager,
            Function<TableHandle, ConnectorMetadata> transactionToMetadata)
    {
        this.session = requireNonNull(session, "session is null");
        this.idAllocator = requireNonNull(idAllocator, "idAllocator is null");
        this.rowExpressionService = requireNonNull(rowExpressionService, "rowExpressionService is null");
        this.functionResolution = requireNonNull(functionResolution, "functionResolution is null");
        this.functionMetadataManager = requireNonNull(functionMetadataManager, "functionMetadataManager is null");
        this.transactionToMetadata = transactionToMetadata;
    }

    @Override
    public PlanNode visitFilter(FilterNode filter, RewriteContext<Void> context)
    {
        if (!(filter.getSource() instanceof TableScanNode)) {
            return visitPlan(filter, context);
        }

        TableScanNode tableScan = (TableScanNode) filter.getSource();

        RowExpression expression = filter.getPredicate();
        TableHandle handle = tableScan.getTable();
        ConnectorMetadata metadata = transactionToMetadata.apply(handle);

        BiMap<VariableReferenceExpression, VariableReferenceExpression> symbolToColumnMapping = getSymbolToColumnMapping(tableScan, handle, metadata, session);

        RowExpression replacedExpression = RowExpressionNodeInliner.replaceExpression(expression, symbolToColumnMapping);
        // replaceExpression() may further optimize the expression;
        // if the resulting expression is always false, then return empty Values node
        if (LogicalRowExpressions.FALSE_CONSTANT.equals(replacedExpression)) {
            session.getWarningCollector().add(new PrestoWarning(
                    HIVE_TABLESCAN_CONVERTED_TO_VALUESNODE,
                    format(
                            "Table '%s' returns 0 rows, and is converted to an empty %s by %s",
                            tableScan.getTable().getConnectorHandle(),
                            ValuesNode.class.getSimpleName(),
                            BaseRewriter.class.getSimpleName())));
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
            session.getWarningCollector().add(new PrestoWarning(
                    HIVE_TABLESCAN_CONVERTED_TO_VALUESNODE,
                    format(
                            "Table '%s' returns 0 rows, and is converted to an empty %s by %s",
                            tableScan.getTable().getConnectorHandle(),
                            ValuesNode.class.getSimpleName(),
                            BaseRewriter.class.getSimpleName())));
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
        TableHandle handle = tableScan.getTable();
        ConnectorMetadata metadata = transactionToMetadata.apply(handle);
        ConnectorPushdownFilterResult pushdownFilterResult = pushdownFilter(
                session,
                metadata,
                handle.getConnectorHandle(),
                LogicalRowExpressions.TRUE_CONSTANT,
                handle.getLayout());
        if (pushdownFilterResult.getLayout().getPredicate().isNone()) {
            session.getWarningCollector().add(new PrestoWarning(
                    HIVE_TABLESCAN_CONVERTED_TO_VALUESNODE,
                    format(
                            "Table '%s' returns 0 rows, and is converted to an empty %s by %s",
                            tableScan.getTable().getConnectorHandle(),
                            ValuesNode.class.getSimpleName(),
                            BaseRewriter.class.getSimpleName())));
            return new ValuesNode(
                    tableScan.getSourceLocation(),
                    idAllocator.getNextId(),
                    tableScan.getOutputVariables(),
                    ImmutableList.of(),
                    Optional.of(tableScan.getTable().getConnectorHandle().toString()));
        }

        return getTableScanNode(tableScan, handle, pushdownFilterResult);
    }

    public ConnectorPushdownFilterResult pushdownFilter(
            ConnectorSession session,
            ConnectorMetadata metadata,
            ConnectorTableHandle tableHandle,
            RowExpression filter,
            Optional<ConnectorTableLayoutHandle> currentLayoutHandle)
    {
        checkArgument(!LogicalRowExpressions.FALSE_CONSTANT.equals(filter), "Cannot pushdown filter that is always false");

        if (LogicalRowExpressions.TRUE_CONSTANT.equals(filter) && currentLayoutHandle.isPresent()) {
            return new ConnectorPushdownFilterResult(
                    metadata.getTableLayout(session, currentLayoutHandle.get()),
                    LogicalRowExpressions.TRUE_CONSTANT);
        }

        // Split the filter into 3 groups of conjuncts:
        //  - range filters that apply to entire columns,
        //  - range filters that apply to subfields,
        //  - the rest. Intersect these with possibly pre-existing filters.
        DomainTranslator.ExtractionResult<Subfield> decomposedFilter = rowExpressionService.getDomainTranslator()
                .fromPredicate(session, filter, new SubfieldExtractor(
                        functionResolution,
                        rowExpressionService.getExpressionOptimizer(),
                        session).toColumnExtractor());

        if (currentLayoutHandle.isPresent()) {
            BaseHiveTableLayoutHandle currentHiveLayout = (BaseHiveTableLayoutHandle) currentLayoutHandle.get();
            decomposedFilter = intersectExtractionResult(
                    new DomainTranslator.ExtractionResult(currentHiveLayout.getDomainPredicate(), currentHiveLayout.getRemainingPredicate()),
                    decomposedFilter);
        }

        if (decomposedFilter.getTupleDomain().isNone()) {
            return new ConnectorPushdownFilterResult(EMPTY_TABLE_LAYOUT, LogicalRowExpressions.FALSE_CONSTANT);
        }

        RowExpression optimizedRemainingExpression = rowExpressionService.getExpressionOptimizer()
                .optimize(decomposedFilter.getRemainingExpression(), OPTIMIZED, session);
        if (optimizedRemainingExpression instanceof ConstantExpression) {
            ConstantExpression constantExpression = (ConstantExpression) optimizedRemainingExpression;
            if (LogicalRowExpressions.FALSE_CONSTANT.equals(constantExpression) || constantExpression.getValue() == null) {
                return new ConnectorPushdownFilterResult(EMPTY_TABLE_LAYOUT, LogicalRowExpressions.FALSE_CONSTANT);
            }
        }

        Map<String, ColumnHandle> columnHandles = metadata.getColumnHandles(session, tableHandle);
        TupleDomain<ColumnHandle> entireColumnDomain = decomposedFilter.getTupleDomain()
                .transform(subfield -> isEntireColumn(subfield) ? subfield.getRootName() : null)
                .transform(columnHandles::get);

        if (currentLayoutHandle.isPresent()) {
            ConnectorTableLayoutHandle layoutHandle = currentLayoutHandle.get();
            if (layoutHandle instanceof SupportsPartitionColumnPredicate) {
                entireColumnDomain = entireColumnDomain.intersect(((SupportsPartitionColumnPredicate) layoutHandle).getPartitionColumnPredicate());
            }
        }

        Constraint<ColumnHandle> constraint = new Constraint<>(entireColumnDomain);

        // Extract deterministic conjuncts that apply to partition columns and specify these as Constraint#predicate
        constraint = extractDeterministicConjuncts(
                session,
                decomposedFilter,
                columnHandles,
                entireColumnDomain,
                constraint);

        RemainingExpressions remainingExpressions = getRemainingExpressions(tableHandle, decomposedFilter, columnHandles);

        return getConnectorPushdownFilterResult(
                columnHandles,
                metadata,
                session,
                remainingExpressions,
                decomposedFilter,
                optimizedRemainingExpression,
                constraint,
                currentLayoutHandle,
                tableHandle);
    }

    protected abstract ConnectorPushdownFilterResult getConnectorPushdownFilterResult(
            Map<String, ColumnHandle> columnHandles,
            ConnectorMetadata metadata,
            ConnectorSession session,
            RemainingExpressions remainingExpressions,
            DomainTranslator.ExtractionResult<Subfield> decomposedFilter,
            RowExpression optimizedRemainingExpression,
            Constraint<ColumnHandle> constraint,
            Optional<ConnectorTableLayoutHandle> currentLayoutHandle,
            ConnectorTableHandle tableHandle);

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
        if (!LogicalRowExpressions.TRUE_CONSTANT.equals(unenforcedFilter)) {
            return new FilterNode(
                    tableScan.getSourceLocation(),
                    idAllocator.getNextId(),
                    node,
                    RowExpressionNodeInliner.replaceExpression(unenforcedFilter, symbolToColumnMapping.inverse()));
        }

        return node;
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
        if (!LogicalRowExpressions.TRUE_CONSTANT.equals(unenforcedFilter)) {
            throw new PrestoException(
                    GENERIC_INTERNAL_ERROR,
                    format("Unenforced filter found %s but not handled", unenforcedFilter));
        }
        return node;
    }

    private static DomainTranslator.ExtractionResult intersectExtractionResult(
            DomainTranslator.ExtractionResult left,
            DomainTranslator.ExtractionResult right)
    {
        RowExpression newRemainingExpression;
        if (right.getRemainingExpression().equals(LogicalRowExpressions.TRUE_CONSTANT)) {
            newRemainingExpression = left.getRemainingExpression();
        }
        else if (left.getRemainingExpression().equals(LogicalRowExpressions.TRUE_CONSTANT)) {
            newRemainingExpression = right.getRemainingExpression();
        }
        else {
            newRemainingExpression = LogicalRowExpressions.and(left.getRemainingExpression(), right.getRemainingExpression());
        }
        return new DomainTranslator.ExtractionResult(
                left.getTupleDomain().intersect(right.getTupleDomain()), newRemainingExpression);
    }

    private static boolean isEntireColumn(Subfield subfield)
    {
        return subfield.getPath().isEmpty();
    }

    private Constraint<ColumnHandle> extractDeterministicConjuncts(
            ConnectorSession session,
            DomainTranslator.ExtractionResult<Subfield> decomposedFilter,
            Map<String, ColumnHandle> columnHandles,
            TupleDomain<ColumnHandle> entireColumnDomain,
            Constraint<ColumnHandle> constraint)
    {
        if (!LogicalRowExpressions.TRUE_CONSTANT.equals(decomposedFilter.getRemainingExpression())) {
            LogicalRowExpressions logicalRowExpressions = new LogicalRowExpressions(
                    rowExpressionService.getDeterminismEvaluator(),
                    functionResolution,
                    functionMetadataManager);
            RowExpression deterministicPredicate = logicalRowExpressions.filterDeterministicConjuncts(
                    decomposedFilter.getRemainingExpression());
            if (!LogicalRowExpressions.TRUE_CONSTANT.equals(deterministicPredicate)) {
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

    private RemainingExpressions getRemainingExpressions(
            ConnectorTableHandle tableHandle,
            DomainTranslator.ExtractionResult<Subfield> decomposedFilter,
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

    public static boolean useDynamicFilter(
            RowExpression expression,
            ConnectorTableHandle tableHandle,
            Map<String, ColumnHandle> columnHandleMap)
    {
        return false;
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
