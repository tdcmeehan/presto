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
package com.facebook.presto.sql.planner;

import com.facebook.presto.Session;
import com.facebook.presto.SystemSessionProperties;
import com.facebook.presto.common.predicate.TupleDomain;
import com.facebook.presto.cost.StatsAndCosts;
import com.facebook.presto.execution.QueryManagerConfig;
import com.facebook.presto.execution.QueryManagerConfig.ExchangeMaterializationStrategy;
import com.facebook.presto.execution.scheduler.BucketNodeMap;
import com.facebook.presto.metadata.Metadata;
import com.facebook.presto.metadata.NewTableLayout;
import com.facebook.presto.metadata.PartitioningMetadata;
import com.facebook.presto.metadata.TableLayout.TablePartitioning;
import com.facebook.presto.metadata.TableLayoutResult;
import com.facebook.presto.metadata.TableMetadata;
import com.facebook.presto.operator.StageExecutionDescriptor;
import com.facebook.presto.spi.ColumnHandle;
import com.facebook.presto.spi.ColumnMetadata;
import com.facebook.presto.spi.ConnectorId;
import com.facebook.presto.spi.ConnectorNewTableLayout;
import com.facebook.presto.spi.Constraint;
import com.facebook.presto.spi.PrestoException;
import com.facebook.presto.spi.PrestoWarning;
import com.facebook.presto.spi.SchemaTableName;
import com.facebook.presto.spi.SourceLocation;
import com.facebook.presto.spi.TableHandle;
import com.facebook.presto.spi.WarningCollector;
import com.facebook.presto.spi.plan.Assignments;
import com.facebook.presto.spi.plan.PlanNode;
import com.facebook.presto.spi.plan.PlanNodeId;
import com.facebook.presto.spi.plan.PlanNodeIdAllocator;
import com.facebook.presto.spi.plan.ProjectNode;
import com.facebook.presto.spi.plan.ProjectNode.Locality;
import com.facebook.presto.spi.plan.TableScanNode;
import com.facebook.presto.spi.plan.ValuesNode;
import com.facebook.presto.spi.relation.ConstantExpression;
import com.facebook.presto.spi.relation.RowExpression;
import com.facebook.presto.spi.relation.VariableReferenceExpression;
import com.facebook.presto.spi.statistics.TableStatisticsMetadata;
import com.facebook.presto.sql.analyzer.FeaturesConfig;
import com.facebook.presto.sql.parser.SqlParser;
import com.facebook.presto.sql.planner.GroupedExecutionTagger.GroupedExecutionProperties;
import com.facebook.presto.sql.planner.StatisticsAggregationPlanner.TableStatisticAggregation;
import com.facebook.presto.sql.planner.plan.ExchangeNode;
import com.facebook.presto.sql.planner.plan.ExplainAnalyzeNode;
import com.facebook.presto.sql.planner.plan.MetadataDeleteNode;
import com.facebook.presto.sql.planner.plan.NativeExecutionNode;
import com.facebook.presto.sql.planner.plan.OutputNode;
import com.facebook.presto.sql.planner.plan.PlanFragmentId;
import com.facebook.presto.sql.planner.plan.RemoteSourceNode;
import com.facebook.presto.sql.planner.plan.SimplePlanRewriter;
import com.facebook.presto.sql.planner.plan.StatisticAggregations;
import com.facebook.presto.sql.planner.plan.StatisticsWriterNode;
import com.facebook.presto.sql.planner.plan.TableFinishNode;
import com.facebook.presto.sql.planner.plan.TableWriterMergeNode;
import com.facebook.presto.sql.planner.plan.TableWriterNode;
import com.facebook.presto.sql.planner.plan.TableWriterNode.InsertReference;
import com.facebook.presto.sql.planner.sanity.PlanChecker;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;

import javax.inject.Inject;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import static com.facebook.presto.SystemSessionProperties.getExchangeMaterializationStrategy;
import static com.facebook.presto.SystemSessionProperties.getQueryMaxStageCount;
import static com.facebook.presto.SystemSessionProperties.getTaskPartitionedWriterCount;
import static com.facebook.presto.SystemSessionProperties.isForceSingleNodeOutput;
import static com.facebook.presto.SystemSessionProperties.isNativeExecutionEnabled;
import static com.facebook.presto.SystemSessionProperties.isRecoverableGroupedExecutionEnabled;
import static com.facebook.presto.SystemSessionProperties.isTableWriterMergeOperatorEnabled;
import static com.facebook.presto.common.type.BigintType.BIGINT;
import static com.facebook.presto.common.type.VarbinaryType.VARBINARY;
import static com.facebook.presto.spi.StandardErrorCode.NOT_SUPPORTED;
import static com.facebook.presto.spi.StandardErrorCode.QUERY_HAS_TOO_MANY_STAGES;
import static com.facebook.presto.spi.StandardWarningCode.TOO_MANY_STAGES;
import static com.facebook.presto.sql.planner.SchedulingOrderVisitor.scheduleOrder;
import static com.facebook.presto.sql.planner.SystemPartitioningHandle.COORDINATOR_DISTRIBUTION;
import static com.facebook.presto.sql.planner.SystemPartitioningHandle.SINGLE_DISTRIBUTION;
import static com.facebook.presto.sql.planner.SystemPartitioningHandle.SOURCE_DISTRIBUTION;
import static com.facebook.presto.sql.planner.SystemPartitioningHandle.isCompatibleSystemPartitioning;
import static com.facebook.presto.sql.planner.VariablesExtractor.extractOutputVariables;
import static com.facebook.presto.sql.planner.plan.ExchangeNode.Scope.LOCAL;
import static com.facebook.presto.sql.planner.plan.ExchangeNode.Scope.REMOTE_MATERIALIZED;
import static com.facebook.presto.sql.planner.plan.ExchangeNode.Scope.REMOTE_STREAMING;
import static com.facebook.presto.sql.planner.plan.ExchangeNode.Type.REPARTITION;
import static com.facebook.presto.sql.planner.plan.ExchangeNode.Type.REPLICATE;
import static com.facebook.presto.sql.planner.plan.ExchangeNode.ensureSourceOrderingGatheringExchange;
import static com.facebook.presto.sql.planner.plan.ExchangeNode.gatheringExchange;
import static com.facebook.presto.sql.planner.plan.ExchangeNode.partitionedExchange;
import static com.facebook.presto.sql.planner.planPrinter.PlanPrinter.jsonFragmentPlan;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;
import static com.google.common.base.Verify.verify;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.collect.ImmutableMap.toImmutableMap;
import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static com.google.common.collect.Iterables.concat;
import static com.google.common.collect.Iterables.getOnlyElement;
import static com.google.common.collect.Streams.stream;
import static com.google.common.graph.Traverser.forTree;
import static java.lang.String.format;
import static java.util.Objects.requireNonNull;
import static java.util.function.Function.identity;

/**
 * Splits a logical plan into fragments that can be shipped and executed on distributed nodes
 */
public class PlanFragmenter
{
    public static final int ROOT_FRAGMENT_ID = 0;
    public static final String TOO_MANY_STAGES_MESSAGE = "If the query contains multiple DISTINCTs, please set the 'use_mark_distinct' session property to false. " +
            "If the query contains multiple CTEs that are referenced more than once, please create temporary table(s) for one or more of the CTEs.";

    private final Metadata metadata;
    private final NodePartitioningManager nodePartitioningManager;
    private final QueryManagerConfig config;
    private final SqlParser sqlParser;
    private final PlanChecker distributedPlanChecker;
    private final PlanChecker singleNodePlanChecker;

    @Inject
    public PlanFragmenter(Metadata metadata, NodePartitioningManager nodePartitioningManager, QueryManagerConfig queryManagerConfig, SqlParser sqlParser, FeaturesConfig featuresConfig)
    {
        this.metadata = requireNonNull(metadata, "metadata is null");
        this.nodePartitioningManager = requireNonNull(nodePartitioningManager, "nodePartitioningManager is null");
        this.config = requireNonNull(queryManagerConfig, "queryManagerConfig is null");
        this.sqlParser = requireNonNull(sqlParser, "sqlParser is null");
        this.distributedPlanChecker = new PlanChecker(requireNonNull(featuresConfig, "featuresConfig is null"), false);
        this.singleNodePlanChecker = new PlanChecker(requireNonNull(featuresConfig, "featuresConfig is null"), true);
    }

    public SubPlan createSubPlans(Session session, Plan plan, boolean forceSingleNode, PlanNodeIdAllocator idAllocator, WarningCollector warningCollector)
    {
        PlanVariableAllocator variableAllocator = new PlanVariableAllocator(plan.getTypes().allVariables());
        return createSubPlans(session, plan, forceSingleNode, idAllocator, variableAllocator, warningCollector);
    }

    public SubPlan createSubPlans(Session session, Plan plan, boolean forceSingleNode, PlanNodeIdAllocator idAllocator, PlanVariableAllocator variableAllocator, WarningCollector warningCollector)
    {
        Fragmenter fragmenter = new Fragmenter(
                session,
                metadata,
                plan.getStatsAndCosts(),
                forceSingleNode ? singleNodePlanChecker : distributedPlanChecker,
                warningCollector,
                sqlParser,
                idAllocator,
                variableAllocator,
                getTableWriterNodeIds(plan.getRoot()));

        FragmentProperties properties = new FragmentProperties(new PartitioningScheme(
                Partitioning.create(SINGLE_DISTRIBUTION, ImmutableList.of()),
                plan.getRoot().getOutputVariables()));
        if (forceSingleNode || isForceSingleNodeOutput(session)) {
            properties = properties.setSingleNodeDistribution();
        }
        PlanNode root = SimplePlanRewriter.rewriteWith(fragmenter, plan.getRoot(), properties);

        SubPlan subPlan = fragmenter.buildRootFragment(root, properties);
        subPlan = reassignPartitioningHandleIfNecessary(session, subPlan);
        if (!forceSingleNode) {
            // grouped execution is not supported for SINGLE_DISTRIBUTION
            subPlan = analyzeGroupedExecution(session, subPlan, false);
        }

        checkState(!isForceSingleNodeOutput(session) || subPlan.getFragment().getPartitioning().isSingleNode(), "Root of PlanFragment is not single node");

        // TODO: Remove query_max_stage_count session property and use queryManagerConfig.getMaxStageCount() here
        sanityCheckFragmentedPlan(
                subPlan,
                warningCollector,
                getExchangeMaterializationStrategy(session),
                getQueryMaxStageCount(session),
                config.getStageCountWarningThreshold());

        return subPlan;
    }

    private void sanityCheckFragmentedPlan(
            SubPlan subPlan,
            WarningCollector warningCollector,
            ExchangeMaterializationStrategy exchangeMaterializationStrategy,
            int maxStageCount,
            int stageCountSoftLimit)
    {
        subPlan.sanityCheck();

        int fragmentCount = subPlan.getAllFragments().size();
        if (fragmentCount > maxStageCount) {
            throw new PrestoException(QUERY_HAS_TOO_MANY_STAGES, format(
                    "Number of stages in the query (%s) exceeds the allowed maximum (%s). " + TOO_MANY_STAGES_MESSAGE,
                    fragmentCount, maxStageCount));
        }

        // When exchange materialization is enabled, only a limited number of stages will be executed concurrently
        //  (controlled by session property max_concurrent_materializations)
        if (exchangeMaterializationStrategy != ExchangeMaterializationStrategy.ALL) {
            if (fragmentCount > stageCountSoftLimit) {
                warningCollector.add(new PrestoWarning(TOO_MANY_STAGES, format(
                        "Number of stages in the query (%s) exceeds the soft limit (%s). " + TOO_MANY_STAGES_MESSAGE,
                        fragmentCount, stageCountSoftLimit)));
            }
        }
    }

    /*
     * In theory, recoverable grouped execution should be decided at query section level (i.e. a connected component of stages connected by remote exchanges).
     * This is because supporting mixed recoverable execution and non-recoverable execution within a query section adds unnecessary complications but provides little benefit,
     * because a single task failure is still likely to fail the non-recoverable stage.
     * However, since the concept of "query section" is not introduced until execution time as of now, it needs significant hacks to decide at fragmenting time.

     * TODO: We should introduce "query section" and make recoverability analysis done at query section level.
     */
    private SubPlan analyzeGroupedExecution(Session session, SubPlan subPlan, boolean parentContainsTableFinish)
    {
        PlanFragment fragment = subPlan.getFragment();
        GroupedExecutionProperties properties = fragment.getRoot().accept(new GroupedExecutionTagger(session, metadata, nodePartitioningManager), null);
        if (properties.isSubTreeUseful()) {
            boolean preferDynamic = fragment.getRemoteSourceNodes().stream().allMatch(node -> node.getExchangeType() == REPLICATE);
            BucketNodeMap bucketNodeMap = nodePartitioningManager.getBucketNodeMap(session, fragment.getPartitioning(), preferDynamic);
            if (bucketNodeMap.isDynamic()) {
                /*
                 * We currently only support recoverable grouped execution if the following statements hold true:
                 *   - Current session enables recoverable grouped execution and table writer merge operator
                 *   - Parent sub plan contains TableFinishNode
                 *   - Current sub plan's root is TableWriterMergeNode or TableWriterNode
                 *   - Input connectors supports split source rewind
                 *   - Output connectors supports partition commit
                 *   - Bucket node map uses dynamic scheduling
                 *   - One table writer per task
                 */
                boolean recoverable = isRecoverableGroupedExecutionEnabled(session) &&
                        isTableWriterMergeOperatorEnabled(session) &&
                        parentContainsTableFinish &&
                        (fragment.getRoot() instanceof TableWriterMergeNode || fragment.getRoot() instanceof TableWriterNode) &&
                        properties.isRecoveryEligible();
                if (recoverable) {
                    fragment = fragment.withRecoverableGroupedExecution(properties.getCapableTableScanNodes(), properties.getTotalLifespans());
                }
                else {
                    fragment = fragment.withDynamicLifespanScheduleGroupedExecution(properties.getCapableTableScanNodes(), properties.getTotalLifespans());
                }
            }
            else {
                fragment = fragment.withFixedLifespanScheduleGroupedExecution(properties.getCapableTableScanNodes(), properties.getTotalLifespans());
            }
        }
        ImmutableList.Builder<SubPlan> result = ImmutableList.builder();
        boolean containsTableFinishNode = containsTableFinishNode(fragment);
        for (SubPlan child : subPlan.getChildren()) {
            result.add(analyzeGroupedExecution(session, child, containsTableFinishNode));
        }
        return new SubPlan(fragment, result.build());
    }

    private static boolean containsTableFinishNode(PlanFragment planFragment)
    {
        PlanNode root = planFragment.getRoot();
        return root instanceof OutputNode && getOnlyElement(root.getSources()) instanceof TableFinishNode;
    }

    private SubPlan reassignPartitioningHandleIfNecessary(Session session, SubPlan subPlan)
    {
        return reassignPartitioningHandleIfNecessaryHelper(session, subPlan, subPlan.getFragment().getPartitioning());
    }

    private SubPlan reassignPartitioningHandleIfNecessaryHelper(Session session, SubPlan subPlan, PartitioningHandle newOutputPartitioningHandle)
    {
        PlanFragment fragment = subPlan.getFragment();

        PlanNode newRoot = fragment.getRoot();
        // If the fragment's partitioning is SINGLE or COORDINATOR_ONLY, leave the sources as is (this is for single-node execution)
        if (!fragment.getPartitioning().isSingleNode()) {
            PartitioningHandleReassigner partitioningHandleReassigner = new PartitioningHandleReassigner(fragment.getPartitioning(), metadata, session);
            newRoot = SimplePlanRewriter.rewriteWith(partitioningHandleReassigner, newRoot);
        }
        PartitioningScheme outputPartitioningScheme = fragment.getPartitioningScheme();
        Partitioning newOutputPartitioning = outputPartitioningScheme.getPartitioning();
        if (outputPartitioningScheme.getPartitioning().getHandle().getConnectorId().isPresent()) {
            // Do not replace the handle if the source's output handle is a system one, e.g. broadcast.
            newOutputPartitioning = newOutputPartitioning.withAlternativePartitioningHandle(newOutputPartitioningHandle);
        }
        PlanFragment newFragment = new PlanFragment(
                fragment.getId(),
                newRoot,
                fragment.getVariables(),
                fragment.getPartitioning(),
                fragment.getTableScanSchedulingOrder(),
                new PartitioningScheme(
                        newOutputPartitioning,
                        outputPartitioningScheme.getOutputLayout(),
                        outputPartitioningScheme.getHashColumn(),
                        outputPartitioningScheme.isReplicateNullsAndAny(),
                        outputPartitioningScheme.getBucketToPartition()),
                fragment.getStageExecutionDescriptor(),
                fragment.isOutputTableWriterFragment(),
                fragment.getStatsAndCosts(),
                fragment.getJsonRepresentation());

        ImmutableList.Builder<SubPlan> childrenBuilder = ImmutableList.builder();
        for (SubPlan child : subPlan.getChildren()) {
            childrenBuilder.add(reassignPartitioningHandleIfNecessaryHelper(session, child, fragment.getPartitioning()));
        }
        return new SubPlan(newFragment, childrenBuilder.build());
    }

    private static Set<PlanNodeId> getTableWriterNodeIds(PlanNode plan)
    {
        return stream(forTree(PlanNode::getSources).depthFirstPreOrder(plan))
                .filter(node -> node instanceof TableWriterNode)
                .map(PlanNode::getId)
                .collect(toImmutableSet());
    }

    private static class Fragmenter
            extends SimplePlanRewriter<FragmentProperties>
    {
        private final Session session;
        private final Metadata metadata;
        private final PlanNodeIdAllocator idAllocator;
        private final PlanVariableAllocator variableAllocator;
        private final StatsAndCosts statsAndCosts;
        private final PlanChecker planChecker;
        private final WarningCollector warningCollector;
        private final SqlParser sqlParser;
        private final Set<PlanNodeId> outputTableWriterNodeIds;
        private int nextFragmentId = ROOT_FRAGMENT_ID + 1;
        private final StatisticsAggregationPlanner statisticsAggregationPlanner;

        public Fragmenter(
                Session session,
                Metadata metadata,
                StatsAndCosts statsAndCosts,
                PlanChecker planChecker,
                WarningCollector warningCollector,
                SqlParser sqlParser,
                PlanNodeIdAllocator idAllocator,
                PlanVariableAllocator variableAllocator,
                Set<PlanNodeId> outputTableWriterNodeIds)
        {
            this.session = requireNonNull(session, "session is null");
            this.metadata = requireNonNull(metadata, "metadata is null");
            this.statsAndCosts = requireNonNull(statsAndCosts, "statsAndCosts is null");
            this.planChecker = requireNonNull(planChecker, "planChecker is null");
            this.warningCollector = requireNonNull(warningCollector, "warningCollector is null");
            this.sqlParser = requireNonNull(sqlParser, "sqlParser is null");
            this.idAllocator = requireNonNull(idAllocator, "idAllocator is null");
            this.variableAllocator = requireNonNull(variableAllocator, "variableAllocator is null");
            this.outputTableWriterNodeIds = ImmutableSet.copyOf(requireNonNull(outputTableWriterNodeIds, "outputTableWriterNodeIds is null"));
            this.statisticsAggregationPlanner = new StatisticsAggregationPlanner(variableAllocator, metadata);
        }

        public SubPlan buildRootFragment(PlanNode root, FragmentProperties properties)
        {
            return buildFragment(root, properties, new PlanFragmentId(ROOT_FRAGMENT_ID));
        }

        private PlanFragmentId nextFragmentId()
        {
            return new PlanFragmentId(nextFragmentId++);
        }

        private SubPlan buildFragment(PlanNode root, FragmentProperties properties, PlanFragmentId fragmentId)
        {
            List<PlanNodeId> schedulingOrder = scheduleOrder(root);
            checkArgument(
                    properties.getPartitionedSources().equals(ImmutableSet.copyOf(schedulingOrder)),
                    "Expected scheduling order (%s) to contain an entry for all partitioned sources (%s)",
                    schedulingOrder,
                    properties.getPartitionedSources());

            Set<VariableReferenceExpression> fragmentVariableTypes = extractOutputVariables(root);
            planChecker.validatePlanFragment(root, session, metadata, sqlParser, TypeProvider.fromVariables(fragmentVariableTypes), warningCollector);

            Set<PlanNodeId> tableWriterNodeIds = getTableWriterNodeIds(root);
            boolean outputTableWriterFragment = tableWriterNodeIds.stream().anyMatch(outputTableWriterNodeIds::contains);
            if (outputTableWriterFragment) {
                verify(
                        outputTableWriterNodeIds.containsAll(tableWriterNodeIds),
                        "outputTableWriterNodeIds %s must include either all or none of tableWriterNodeIds %s",
                        outputTableWriterNodeIds,
                        tableWriterNodeIds);
            }

            // Only delegate non-coordinatorOnly plan fragment to native engine
            if (isNativeExecutionEnabled(session) && !properties.getPartitioningHandle().isCoordinatorOnly()) {
                root = new NativeExecutionNode(root);
                schedulingOrder = scheduleOrder(root);
            }

            PlanFragment fragment = new PlanFragment(
                    fragmentId,
                    root,
                    fragmentVariableTypes,
                    properties.getPartitioningHandle(),
                    schedulingOrder,
                    properties.getPartitioningScheme(),
                    StageExecutionDescriptor.ungroupedExecution(),
                    outputTableWriterFragment,
                    statsAndCosts.getForSubplan(root),
                    Optional.of(jsonFragmentPlan(root, fragmentVariableTypes, metadata.getFunctionAndTypeManager(), session)));

            return new SubPlan(fragment, properties.getChildren());
        }

        @Override
        public PlanNode visitOutput(OutputNode node, RewriteContext<FragmentProperties> context)
        {
            if (isForceSingleNodeOutput(session)) {
                context.get().setSingleNodeDistribution();
            }

            return context.defaultRewrite(node, context.get());
        }

        @Override
        public PlanNode visitExplainAnalyze(ExplainAnalyzeNode node, RewriteContext<FragmentProperties> context)
        {
            context.get().setCoordinatorOnlyDistribution();
            return context.defaultRewrite(node, context.get());
        }

        @Override
        public PlanNode visitStatisticsWriterNode(StatisticsWriterNode node, RewriteContext<FragmentProperties> context)
        {
            context.get().setCoordinatorOnlyDistribution();
            return context.defaultRewrite(node, context.get());
        }

        @Override
        public PlanNode visitTableFinish(TableFinishNode node, RewriteContext<FragmentProperties> context)
        {
            context.get().setCoordinatorOnlyDistribution();
            return context.defaultRewrite(node, context.get());
        }

        @Override
        public PlanNode visitMetadataDelete(MetadataDeleteNode node, RewriteContext<FragmentProperties> context)
        {
            context.get().setCoordinatorOnlyDistribution();
            return context.defaultRewrite(node, context.get());
        }

        @Override
        public PlanNode visitTableScan(TableScanNode node, RewriteContext<FragmentProperties> context)
        {
            PartitioningHandle partitioning = metadata.getLayout(session, node.getTable())
                    .getTablePartitioning()
                    .map(TablePartitioning::getPartitioningHandle)
                    .orElse(SOURCE_DISTRIBUTION);
            context.get().addSourceDistribution(node.getId(), partitioning, metadata, session);
            return context.defaultRewrite(node, context.get());
        }

        @Override
        public PlanNode visitTableWriter(TableWriterNode node, RewriteContext<FragmentProperties> context)
        {
            if (node.getTablePartitioningScheme().isPresent()) {
                context.get().setDistribution(node.getTablePartitioningScheme().get().getPartitioning().getHandle(), metadata, session);
            }
            if (node.getPreferredShufflePartitioningScheme().isPresent()) {
                context.get().setDistribution(node.getPreferredShufflePartitioningScheme().get().getPartitioning().getHandle(), metadata, session);
            }
            return context.defaultRewrite(node, context.get());
        }

        @Override
        public PlanNode visitValues(ValuesNode node, RewriteContext<FragmentProperties> context)
        {
            context.get().setSingleNodeDistribution();
            return context.defaultRewrite(node, context.get());
        }

        @Override
        public PlanNode visitExchange(ExchangeNode exchange, RewriteContext<FragmentProperties> context)
        {
            switch (exchange.getScope()) {
                case LOCAL:
                    return context.defaultRewrite(exchange, context.get());
                case REMOTE_STREAMING:
                    return createRemoteStreamingExchange(exchange, context);
                case REMOTE_MATERIALIZED:
                    return createRemoteMaterializedExchange(exchange, context);
                default:
                    throw new IllegalArgumentException("Unexpected exchange scope: " + exchange.getScope());
            }
        }

        private PlanNode createRemoteStreamingExchange(ExchangeNode exchange, RewriteContext<FragmentProperties> context)
        {
            checkArgument(exchange.getScope() == REMOTE_STREAMING, "Unexpected exchange scope: %s", exchange.getScope());

            PartitioningScheme partitioningScheme = exchange.getPartitioningScheme();

            if (exchange.getType() == ExchangeNode.Type.GATHER) {
                context.get().setSingleNodeDistribution();
            }
            else if (exchange.getType() == ExchangeNode.Type.REPARTITION) {
                context.get().setDistribution(partitioningScheme.getPartitioning().getHandle(), metadata, session);
            }

            ImmutableList.Builder<SubPlan> builder = ImmutableList.builder();
            for (int sourceIndex = 0; sourceIndex < exchange.getSources().size(); sourceIndex++) {
                FragmentProperties childProperties = new FragmentProperties(partitioningScheme.translateOutputLayout(exchange.getInputs().get(sourceIndex)));
                builder.add(buildSubPlan(exchange.getSources().get(sourceIndex), childProperties, context));
            }

            List<SubPlan> children = builder.build();
            context.get().addChildren(children);

            List<PlanFragmentId> childrenIds = children.stream()
                    .map(SubPlan::getFragment)
                    .map(PlanFragment::getId)
                    .collect(toImmutableList());

            return new RemoteSourceNode(exchange.getSourceLocation(), exchange.getId(), childrenIds, exchange.getOutputVariables(), exchange.isEnsureSourceOrdering(), exchange.getOrderingScheme(), exchange.getType());
        }

        private PlanNode createRemoteMaterializedExchange(ExchangeNode exchange, RewriteContext<FragmentProperties> context)
        {
            checkArgument(exchange.getType() == REPARTITION, "Unexpected exchange type: %s", exchange.getType());
            checkArgument(exchange.getScope() == REMOTE_MATERIALIZED, "Unexpected exchange scope: %s", exchange.getScope());

            PartitioningScheme partitioningScheme = exchange.getPartitioningScheme();

            PartitioningHandle partitioningHandle = partitioningScheme.getPartitioning().getHandle();
            ConnectorId connectorId = partitioningHandle.getConnectorId()
                    .orElseThrow(() -> new PrestoException(
                            NOT_SUPPORTED,
                            "The \"partitioning_provider_catalog\" session property must be set to enable the exchanges materialization. " +
                                    "The catalog must support providing a custom partitioning and storing temporary tables."));

            Partitioning partitioning = partitioningScheme.getPartitioning();
            PartitioningVariableAssignments partitioningVariableAssignments = assignPartitioningVariables(partitioning);
            Map<VariableReferenceExpression, ColumnMetadata> variableToColumnMap = assignTemporaryTableColumnNames(exchange.getOutputVariables(), partitioningVariableAssignments.getConstants().keySet());
            List<VariableReferenceExpression> partitioningVariables = partitioningVariableAssignments.getVariables();
            List<String> partitionColumns = partitioningVariables.stream()
                    .map(variable -> variableToColumnMap.get(variable).getName())
                    .collect(toImmutableList());
            PartitioningMetadata partitioningMetadata = new PartitioningMetadata(partitioningHandle, partitionColumns);

            TableHandle temporaryTableHandle;

            try {
                temporaryTableHandle = metadata.createTemporaryTable(
                        session,
                        connectorId.getCatalogName(),
                        ImmutableList.copyOf(variableToColumnMap.values()),
                        Optional.of(partitioningMetadata));
            }
            catch (PrestoException e) {
                if (e.getErrorCode().equals(NOT_SUPPORTED.toErrorCode())) {
                    throw new PrestoException(
                            NOT_SUPPORTED,
                            format("Temporary table cannot be created in catalog \"%s\": %s", connectorId.getCatalogName(), e.getMessage()),
                            e);
                }
                throw e;
            }

            TableScanNode scan = createTemporaryTableScan(
                    exchange.getSourceLocation(),
                    temporaryTableHandle,
                    exchange.getOutputVariables(),
                    variableToColumnMap,
                    partitioningMetadata);

            checkArgument(
                    !exchange.getPartitioningScheme().isReplicateNullsAndAny(),
                    "materialized remote exchange is not supported when replicateNullsAndAny is needed");
            TableFinishNode write = createTemporaryTableWrite(
                    scan.getSourceLocation(),
                    temporaryTableHandle,
                    variableToColumnMap,
                    exchange.getOutputVariables(),
                    exchange.getInputs(),
                    exchange.getSources(),
                    partitioningVariableAssignments.getConstants(),
                    partitioningMetadata);

            FragmentProperties writeProperties = new FragmentProperties(new PartitioningScheme(
                    Partitioning.create(SINGLE_DISTRIBUTION, ImmutableList.of()),
                    write.getOutputVariables()));
            writeProperties.setCoordinatorOnlyDistribution();

            List<SubPlan> children = ImmutableList.of(buildSubPlan(write, writeProperties, context));
            context.get().addChildren(children);

            return visitTableScan(scan, context);
        }

        private PartitioningVariableAssignments assignPartitioningVariables(Partitioning partitioning)
        {
            ImmutableList.Builder<VariableReferenceExpression> variables = ImmutableList.builder();
            ImmutableMap.Builder<VariableReferenceExpression, RowExpression> constants = ImmutableMap.builder();
            for (RowExpression argument : partitioning.getArguments()) {
                checkArgument(argument instanceof ConstantExpression || argument instanceof VariableReferenceExpression, format("Expect argument to be ConstantExpression or VariableReferenceExpression, get %s (%s)", argument.getClass(), argument));
                VariableReferenceExpression variable;
                if (argument instanceof ConstantExpression) {
                    variable = variableAllocator.newVariable(argument.getSourceLocation(), "constant_partition", argument.getType());
                    constants.put(variable, argument);
                }
                else {
                    variable = (VariableReferenceExpression) argument;
                }
                variables.add(variable);
            }
            return new PartitioningVariableAssignments(variables.build(), constants.build());
        }

        private Map<VariableReferenceExpression, ColumnMetadata> assignTemporaryTableColumnNames(Collection<VariableReferenceExpression> outputVariables, Collection<VariableReferenceExpression> constantPartitioningVariables)
        {
            ImmutableMap.Builder<VariableReferenceExpression, ColumnMetadata> result = ImmutableMap.builder();
            int column = 0;
            for (VariableReferenceExpression outputVariable : concat(outputVariables, constantPartitioningVariables)) {
                String columnName = format("_c%d_%s", column, outputVariable.getName());
                result.put(outputVariable, new ColumnMetadata(columnName, outputVariable.getType()));
                column++;
            }
            return result.build();
        }

        private TableScanNode createTemporaryTableScan(
                Optional<SourceLocation> sourceLocation,
                TableHandle tableHandle,
                List<VariableReferenceExpression> outputVariables,
                Map<VariableReferenceExpression, ColumnMetadata> variableToColumnMap,
                PartitioningMetadata expectedPartitioningMetadata)
        {
            Map<String, ColumnHandle> columnHandles = metadata.getColumnHandles(session, tableHandle);
            Map<VariableReferenceExpression, ColumnMetadata> outputColumns = outputVariables.stream()
                    .collect(toImmutableMap(identity(), variableToColumnMap::get));
            Set<ColumnHandle> outputColumnHandles = outputColumns.values().stream()
                    .map(ColumnMetadata::getName)
                    .map(columnHandles::get)
                    .collect(toImmutableSet());

            TableLayoutResult selectedLayout = metadata.getLayout(session, tableHandle, Constraint.alwaysTrue(), Optional.of(outputColumnHandles));
            verify(selectedLayout.getUnenforcedConstraint().equals(TupleDomain.all()), "temporary table layout shouldn't enforce any constraints");
            verify(!selectedLayout.getLayout().getColumns().isPresent(), "temporary table layout must provide all the columns");
            TablePartitioning expectedPartitioning = new TablePartitioning(
                    expectedPartitioningMetadata.getPartitioningHandle(),
                    expectedPartitioningMetadata.getPartitionColumns().stream()
                            .map(columnHandles::get)
                            .collect(toImmutableList()));
            verify(selectedLayout.getLayout().getTablePartitioning().equals(Optional.of(expectedPartitioning)), "invalid temporary table partitioning");

            Map<VariableReferenceExpression, ColumnHandle> assignments = outputVariables.stream()
                    .collect(toImmutableMap(identity(), variable -> columnHandles.get(outputColumns.get(variable).getName())));

            return new TableScanNode(
                    sourceLocation,
                    idAllocator.getNextId(),
                    selectedLayout.getLayout().getNewTableHandle(),
                    outputVariables,
                    assignments,
                    TupleDomain.all(),
                    TupleDomain.all());
        }

        private TableFinishNode createTemporaryTableWrite(
                Optional<SourceLocation> sourceLocation, TableHandle tableHandle,
                Map<VariableReferenceExpression, ColumnMetadata> variableToColumnMap,
                List<VariableReferenceExpression> outputs,
                List<List<VariableReferenceExpression>> inputs,
                List<PlanNode> sources,
                Map<VariableReferenceExpression, RowExpression> constantExpressions,
                PartitioningMetadata partitioningMetadata)
        {
            if (!constantExpressions.isEmpty()) {
                List<VariableReferenceExpression> constantVariables = ImmutableList.copyOf(constantExpressions.keySet());

                // update outputs
                outputs = ImmutableList.<VariableReferenceExpression>builder()
                        .addAll(outputs)
                        .addAll(constantVariables)
                        .build();

                // update inputs
                inputs = inputs.stream()
                        .map(input -> ImmutableList.<VariableReferenceExpression>builder()
                                .addAll(input)
                                .addAll(constantVariables)
                                .build())
                        .collect(toImmutableList());

                // update sources
                sources = sources.stream()
                        .map(source -> {
                            Assignments.Builder assignments = Assignments.builder();
                            source.getOutputVariables().forEach(variable -> assignments.put(variable, new VariableReferenceExpression(variable.getSourceLocation(), variable.getName(), variable.getType())));
                            constantVariables.forEach(variable -> assignments.put(variable, constantExpressions.get(variable)));
                            return new ProjectNode(source.getSourceLocation(), idAllocator.getNextId(), source, assignments.build(), Locality.LOCAL);
                        })
                        .collect(toImmutableList());
            }

            NewTableLayout insertLayout = metadata.getInsertLayout(session, tableHandle)
                    // TODO: support insert into non partitioned table
                    .orElseThrow(() -> new IllegalArgumentException("insertLayout for the temporary table must be present"));

            PartitioningHandle partitioningHandle = partitioningMetadata.getPartitioningHandle();
            List<String> partitionColumns = partitioningMetadata.getPartitionColumns();
            ConnectorNewTableLayout expectedNewTableLayout = new ConnectorNewTableLayout(partitioningHandle.getConnectorHandle(), partitionColumns);
            verify(insertLayout.getLayout().equals(expectedNewTableLayout), "unexpected new table layout");

            Map<String, VariableReferenceExpression> columnNameToVariable = variableToColumnMap.entrySet().stream()
                    .collect(toImmutableMap(entry -> entry.getValue().getName(), Map.Entry::getKey));
            List<VariableReferenceExpression> partitioningVariables = partitionColumns.stream()
                    .map(columnNameToVariable::get)
                    .collect(toImmutableList());

            List<String> outputColumnNames = outputs.stream()
                    .map(variableToColumnMap::get)
                    .map(ColumnMetadata::getName)
                    .collect(toImmutableList());
            Set<VariableReferenceExpression> outputNotNullColumnVariables = outputs.stream()
                    .filter(variable -> variableToColumnMap.get(variable) != null && !(variableToColumnMap.get(variable).isNullable()))
                    .collect(Collectors.toSet());

            SchemaTableName schemaTableName = metadata.getTableMetadata(session, tableHandle).getTable();
            InsertReference insertReference = new InsertReference(tableHandle, schemaTableName);

            PartitioningScheme partitioningScheme = new PartitioningScheme(
                    Partitioning.create(partitioningHandle, partitioningVariables),
                    outputs,
                    Optional.empty(),
                    false,
                    Optional.empty());

            ExchangeNode writerRemoteSource = new ExchangeNode(
                    sourceLocation,
                    idAllocator.getNextId(),
                    REPARTITION,
                    REMOTE_STREAMING,
                    partitioningScheme,
                    sources,
                    inputs,
                    false,
                    Optional.empty());

            ExchangeNode writerSource;
            if (getTaskPartitionedWriterCount(session) == 1) {
                writerSource = gatheringExchange(
                        idAllocator.getNextId(),
                        LOCAL,
                        writerRemoteSource);
            }
            else {
                writerSource = partitionedExchange(
                        idAllocator.getNextId(),
                        LOCAL,
                        writerRemoteSource,
                        partitioningScheme);
            }

            String catalogName = tableHandle.getConnectorId().getCatalogName();
            TableMetadata tableMetadata = metadata.getTableMetadata(session, tableHandle);
            TableStatisticsMetadata statisticsMetadata = metadata.getStatisticsCollectionMetadataForWrite(session, catalogName, tableMetadata.getMetadata());
            TableStatisticAggregation statisticsResult = statisticsAggregationPlanner.createStatisticsAggregation(statisticsMetadata, columnNameToVariable, false);
            StatisticAggregations.Parts aggregations = statisticsResult.getAggregations().splitIntoPartialAndFinal(variableAllocator, metadata.getFunctionAndTypeManager());
            PlanNode tableWriterMerge;

            // Disabled by default. Enable when the column statistics are essential for future runtime adaptive plan optimizations
            boolean enableStatsCollectionForTemporaryTable = SystemSessionProperties.isEnableStatsCollectionForTemporaryTable(session);

            if (isTableWriterMergeOperatorEnabled(session)) {
                StatisticAggregations.Parts localAggregations = aggregations.getPartialAggregation().splitIntoPartialAndIntermediate(variableAllocator, metadata.getFunctionAndTypeManager());
                tableWriterMerge = new TableWriterMergeNode(
                        sourceLocation,
                        idAllocator.getNextId(),
                        gatheringExchange(
                                idAllocator.getNextId(),
                                LOCAL,
                                new TableWriterNode(
                                        sourceLocation,
                                        idAllocator.getNextId(),
                                        writerSource,
                                        Optional.of(insertReference),
                                        variableAllocator.newVariable("partialrows", BIGINT),
                                        variableAllocator.newVariable("partialfragments", VARBINARY),
                                        variableAllocator.newVariable("partialtablecommitcontext", VARBINARY),
                                        outputs,
                                        outputColumnNames,
                                        outputNotNullColumnVariables,
                                        Optional.of(partitioningScheme),
                                        Optional.empty(),
                                        enableStatsCollectionForTemporaryTable ? Optional.of(localAggregations.getPartialAggregation()) : Optional.empty())),
                        variableAllocator.newVariable("intermediaterows", BIGINT),
                        variableAllocator.newVariable("intermediatefragments", VARBINARY),
                        variableAllocator.newVariable("intermediatetablecommitcontext", VARBINARY),
                        enableStatsCollectionForTemporaryTable ? Optional.of(localAggregations.getIntermediateAggregation()) : Optional.empty());
            }
            else {
                tableWriterMerge = new TableWriterNode(
                        sourceLocation,
                        idAllocator.getNextId(),
                        writerSource,
                        Optional.of(insertReference),
                        variableAllocator.newVariable("partialrows", BIGINT),
                        variableAllocator.newVariable("partialfragments", VARBINARY),
                        variableAllocator.newVariable("partialtablecommitcontext", VARBINARY),
                        outputs,
                        outputColumnNames,
                        outputNotNullColumnVariables,
                        Optional.of(partitioningScheme),
                        Optional.empty(),
                        enableStatsCollectionForTemporaryTable ? Optional.of(aggregations.getPartialAggregation()) : Optional.empty());
            }

            return new TableFinishNode(
                    sourceLocation,
                    idAllocator.getNextId(),
                    ensureSourceOrderingGatheringExchange(
                            idAllocator.getNextId(),
                            REMOTE_STREAMING,
                            tableWriterMerge),
                    Optional.of(insertReference),
                    variableAllocator.newVariable("rows", BIGINT),
                    enableStatsCollectionForTemporaryTable ? Optional.of(aggregations.getFinalAggregation()) : Optional.empty(),
                    enableStatsCollectionForTemporaryTable ? Optional.of(statisticsResult.getDescriptor()) : Optional.empty());
        }

        private SubPlan buildSubPlan(PlanNode node, FragmentProperties properties, RewriteContext<FragmentProperties> context)
        {
            PlanFragmentId planFragmentId = nextFragmentId();
            PlanNode child = context.rewrite(node, properties);
            return buildFragment(child, properties, planFragmentId);
        }
    }

    private static class FragmentProperties
    {
        private final List<SubPlan> children = new ArrayList<>();

        private final PartitioningScheme partitioningScheme;

        private Optional<PartitioningHandle> partitioningHandle = Optional.empty();
        private final Set<PlanNodeId> partitionedSources = new HashSet<>();

        public FragmentProperties(PartitioningScheme partitioningScheme)
        {
            this.partitioningScheme = partitioningScheme;
        }

        public List<SubPlan> getChildren()
        {
            return children;
        }

        public FragmentProperties setSingleNodeDistribution()
        {
            if (partitioningHandle.isPresent() && partitioningHandle.get().isSingleNode()) {
                // already single node distribution
                return this;
            }

            checkState(!partitioningHandle.isPresent(),
                    "Cannot overwrite partitioning with %s (currently set to %s)",
                    SINGLE_DISTRIBUTION,
                    partitioningHandle);

            partitioningHandle = Optional.of(SINGLE_DISTRIBUTION);

            return this;
        }

        public FragmentProperties setDistribution(PartitioningHandle distribution, Metadata metadata, Session session)
        {
            if (!partitioningHandle.isPresent()) {
                partitioningHandle = Optional.of(distribution);
                return this;
            }

            PartitioningHandle currentPartitioning = this.partitioningHandle.get();

            if (isCompatibleSystemPartitioning(currentPartitioning, distribution)) {
                return this;
            }

            if (currentPartitioning.equals(SOURCE_DISTRIBUTION)) {
                this.partitioningHandle = Optional.of(distribution);
                return this;
            }

            // If already system SINGLE or COORDINATOR_ONLY, leave it as is (this is for single-node execution)
            if (currentPartitioning.isSingleNode()) {
                return this;
            }

            if (currentPartitioning.equals(distribution)) {
                return this;
            }

            Optional<PartitioningHandle> commonPartitioning = metadata.getCommonPartitioning(session, currentPartitioning, distribution);
            if (commonPartitioning.isPresent()) {
                partitioningHandle = commonPartitioning;
                return this;
            }

            if (metadata.isRefinedPartitioningOver(session, distribution, currentPartitioning)) {
                return this;
            }

            throw new IllegalStateException(format(
                    "Cannot set distribution to %s. Already set to %s",
                    distribution,
                    this.partitioningHandle));
        }

        public FragmentProperties setCoordinatorOnlyDistribution()
        {
            if (partitioningHandle.isPresent() && partitioningHandle.get().isCoordinatorOnly()) {
                // already single node distribution
                return this;
            }

            // only system SINGLE can be upgraded to COORDINATOR_ONLY
            checkState(!partitioningHandle.isPresent() || partitioningHandle.get().equals(SINGLE_DISTRIBUTION),
                    "Cannot overwrite partitioning with %s (currently set to %s)",
                    COORDINATOR_DISTRIBUTION,
                    partitioningHandle);

            partitioningHandle = Optional.of(COORDINATOR_DISTRIBUTION);

            return this;
        }

        public FragmentProperties addSourceDistribution(PlanNodeId source, PartitioningHandle distribution, Metadata metadata, Session session)
        {
            requireNonNull(source, "source is null");
            requireNonNull(distribution, "distribution is null");

            partitionedSources.add(source);
            return setDistribution(distribution, metadata, session);
        }

        public FragmentProperties addChildren(List<SubPlan> children)
        {
            this.children.addAll(children);

            return this;
        }

        public PartitioningScheme getPartitioningScheme()
        {
            return partitioningScheme;
        }

        public PartitioningHandle getPartitioningHandle()
        {
            return partitioningHandle.get();
        }

        public Set<PlanNodeId> getPartitionedSources()
        {
            return partitionedSources;
        }
    }

    private static final class PartitioningHandleReassigner
            extends SimplePlanRewriter<Void>
    {
        private final PartitioningHandle fragmentPartitioningHandle;
        private final Metadata metadata;
        private final Session session;

        public PartitioningHandleReassigner(PartitioningHandle fragmentPartitioningHandle, Metadata metadata, Session session)
        {
            this.fragmentPartitioningHandle = fragmentPartitioningHandle;
            this.metadata = metadata;
            this.session = session;
        }

        @Override
        public PlanNode visitTableScan(TableScanNode node, RewriteContext<Void> context)
        {
            PartitioningHandle partitioning = metadata.getLayout(session, node.getTable())
                    .getTablePartitioning()
                    .map(TablePartitioning::getPartitioningHandle)
                    .orElse(SOURCE_DISTRIBUTION);

            if (partitioning.equals(fragmentPartitioningHandle)) {
                // do nothing if the current scan node's partitioning matches the fragment's
                return node;
            }

            TableHandle newTableHandle = metadata.getAlternativeTableHandle(session, node.getTable(), fragmentPartitioningHandle);
            return new TableScanNode(
                    node.getSourceLocation(),
                    node.getId(),
                    newTableHandle,
                    node.getOutputVariables(),
                    node.getAssignments(),
                    node.getCurrentConstraint(),
                    node.getEnforcedConstraint());
        }
    }

    private static class PartitioningVariableAssignments
    {
        private final List<VariableReferenceExpression> variables;
        private final Map<VariableReferenceExpression, RowExpression> constants;

        private PartitioningVariableAssignments(List<VariableReferenceExpression> variables, Map<VariableReferenceExpression, RowExpression> constants)
        {
            this.variables = ImmutableList.copyOf(requireNonNull(variables, "variables is null"));
            this.constants = ImmutableMap.copyOf(requireNonNull(constants, "constants is null"));
            checkArgument(
                    ImmutableSet.copyOf(variables).containsAll(constants.keySet()),
                    "partitioningVariables list must contain all partitioning variables including constants");
        }

        public List<VariableReferenceExpression> getVariables()
        {
            return variables;
        }

        public Map<VariableReferenceExpression, RowExpression> getConstants()
        {
            return constants;
        }
    }
}
