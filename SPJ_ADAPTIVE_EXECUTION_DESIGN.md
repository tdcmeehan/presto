# Storage Partitioned Joins and Adaptive Execution for Presto

## Design Document

**Status**: Draft
**Authors**: Research Team
**Date**: December 2024
**Version**: 0.2

### Changelog
- **v0.2** (Dec 27, 2024): Added implementation gap analysis (Appendix C), detailed Presto code references with line numbers, Spark 2024 updates (SPARK-48613, SPARK-48012), property flow diagram
- **v0.1** (Dec 2024): Initial design document

---

## 1. Executive Summary

This document proposes a multi-phase approach to enabling adaptive query execution in Presto through Storage Partitioned Joins (SPJ). The key insight is that **compatible partitioning schemes create natural boundaries (Lifespans) where adaptation can occur without disrupting streaming execution**.

### Core Thesis

1. **SPJ eliminates shuffles** for compatibly-partitioned tables, improving baseline performance
2. **Lifespans provide restart boundaries** - each partition is an independent unit of work
3. **Pilot-based adaptation** - run initial lifespans, observe statistics, reoptimize remainder
4. **Existing infrastructure** - Presto's Lifespan and `rewindLifespan()` mechanisms already support this

### Why This Approach

| Challenge with Streaming Adaptivity | How SPJ Solves It |
|-------------------------------------|-------------------|
| No natural restart points | Each lifespan is independent, rewindable |
| Can't buffer intermediate results | Data read from storage per-lifespan |
| Remote splits are coarse-grained | Table splits are per-partition, fine-grained |
| Plan changes disrupt data flow | Different lifespans can use different plans |

---

## 2. Background

### 2.1 Presto's Current Execution Model

Presto uses a **streaming pull-based model**:
- Data flows continuously through operators
- No stage boundaries like Spark
- Exchanges stream data (not blocking)
- Limited natural points for adaptation

### 2.2 Grouped Execution and Lifespans

Presto already supports **grouped execution** for bucketed tables:

```java
// Lifespan.java - Two execution modes
public static Lifespan taskWide()        // Normal: single lifespan for entire task
public static Lifespan driverGroup(int id)  // Grouped: one lifespan per bucket/partition
```

**Key existing capabilities:**

1. **Lifespan scheduling** (`DynamicLifespanScheduler`):
   - Tracks running lifespans per task
   - Schedules new lifespans as others complete
   - Supports concurrent lifespan limits

2. **Lifespan recovery** (`rewindLifespan()`):
   - Resets split source to replay a partition
   - Re-enqueues lifespan for scheduling
   - Currently used for task failure recovery

3. **Recovery eligibility** (`SUPPORTS_REWINDABLE_SPLIT_SOURCE`):
   - Connector capability flag
   - Hive connector implements this
   - Required for grouped execution recovery

### 2.3 Spark's Storage Partitioned Join

Spark 3.3+ implements SPJ via [SPARK-37375](https://issues.apache.org/jira/browse/SPARK-37375):

**Key concepts:**
- `KeyGroupedPartitioning`: Partitioning based on partition values
- Compatible partitioning eliminates shuffle
- Supports bucket, identity, and transform-based partitioning
- `HasPartitionKey` interface reports partition values to planner

**How Spark SPJ Works:**

1. **V2ScanPartitioning rule**: Enriches `DataSourceV2Relation` with partition key info
2. **Physical planning**: `BatchScanExec.outputPartitioning` exposes `KeyGroupedPartitioning`
3. **Join planning**: If both sides have compatible `KeyGroupedPartitioning`, no shuffle
4. **Partition grouping**: Different partitions can share same value; Spark groups them

**Recent enhancements (2024):**
- [SPARK-48613](https://www.mail-archive.com/commits@spark.apache.org/msg68353.html): Auto-shuffle one side when join keys < partition keys
  - Projects partition values to match join keys
  - Pushes `KeyGroupedShuffleSpec` to `BatchScanExec`
- [SPARK-41413](https://issues.apache.org/jira/browse/SPARK-41413): Compatible join expressions with mismatched partition keys
- [SPARK-48012](https://www.mail-archive.com/issues@spark.apache.org/msg368173.html): Transform expressions for one-side shuffle
  - Extends SPARK-41471 to support DAY, YEAR, BUCKET transforms (not just identity)

### 2.4 Iceberg Partition Transforms

Iceberg supports multiple partition transform types:

```java
// PartitionTransformType.java
public enum PartitionTransformType {
    IDENTITY,   // partition by exact value
    HOUR,       // hour(timestamp)
    DAY,        // day(timestamp)
    MONTH,      // month(timestamp)
    YEAR,       // year(timestamp)
    BUCKET,     // bucket(N, column)
    TRUNCATE    // truncate(width, column)
}
```

Each produces discrete partition values that map to lifespans.

---

## 3. Design Overview

### 3.1 Phase 1: Storage Partitioned Joins for Iceberg

**Goal**: Eliminate shuffles when tables have compatible partitioning.

```
Before SPJ:
  orders ──► shuffle(customer_id) ──┐
                                    ├──► hash join
  customers ──► shuffle(customer_id)┘

After SPJ (both partitioned by customer_id):
  orders[partition=0] ──┐
                        ├──► local join ──► output[0]
  customers[partition=0]┘

  orders[partition=1] ──┐
                        ├──► local join ──► output[1]
  customers[partition=1]┘
  ...

  (No shuffle! Each partition joins locally)
```

### 3.2 Phase 2: Lifespan-Based Statistics Collection

**Goal**: Collect per-lifespan statistics for adaptive decisions.

```
Lifespan 0 completes:
  - Row count: 150,000
  - Execution time: 2.3s
  - Peak memory: 128MB
  - Join selectivity: 0.73

Lifespan 1 completes:
  - Row count: 148,000
  - Execution time: 2.1s
  - ...

Aggregate statistics → inform optimization decisions
```

### 3.3 Phase 3: Pilot-Based Adaptive Execution

**Goal**: Use initial lifespans as pilots to optimize remaining execution.

```
┌─────────────────────────────────────────────────────────────────────────┐
│  Phase A: Pilot Execution (Lifespans 0-3)                               │
│    - Execute with original plan                                         │
│    - Collect statistics                                                 │
│    - Stream results to output                                           │
└─────────────────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────────────────┐
│  Phase B: Analysis & Reoptimization                                     │
│    - Compare actual vs estimated cardinalities                          │
│    - If deviation > threshold: invoke optimizer with actual stats       │
│    - Generate new plan for remaining lifespans                          │
└─────────────────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────────────────┐
│  Phase C: Remainder Execution (Lifespans 4-N)                           │
│    - Execute with optimized plan                                        │
│    - May have different join order, distribution, parallelism           │
│    - Stream results to output                                           │
└─────────────────────────────────────────────────────────────────────────┘

Final Result = UNION ALL of all lifespan outputs (algebraically correct)
```

### 3.4 Phase 4: Dynamic Skew Handling

**Goal**: Detect and mitigate skew at runtime.

```
Lifespan 47 detected as skewed:
  - Execution time: 45s (vs 2s average)
  - Row count: 5M (vs 150K average)

Mitigation:
  1. rewindLifespan(47)
  2. Split into sub-lifespans (47a, 47b, 47c)
  3. Apply skew-resistant plan (replicate small side)
  4. Execute sub-lifespans in parallel
```

---

## 4. Partition Compatibility

### 4.1 What Makes Partitions Compatible?

Two tables can use SPJ when their partitioning is **join-compatible**:

| Compatibility Type | Example | SPJ Possible? |
|-------------------|---------|---------------|
| **Identical** | Both `BUCKET(256, customer_id)` | ✓ Yes |
| **Same transform, same column** | Both `DAY(event_time)` | ✓ Yes |
| **Equi-join columns** | A: `BUCKET(256, order_id)`, B: `BUCKET(256, id)` with `ON A.order_id = B.id` | ✓ Yes |
| **Subset** | A: `YEAR(ts), MONTH(ts)`, B: `MONTH(ts)` | ✓ Yes (with grouping) |
| **Multiple relationship** | A: `BUCKET(256, id)`, B: `BUCKET(128, id)` where 256 % 128 = 0 | ✓ Yes (with grouping) |
| **Incompatible** | A: `BUCKET(256, customer_id)`, B: `DAY(order_date)` | ✗ No |

### 4.2 Compatibility Check Algorithm

```
function isCompatible(leftPartitioning, rightPartitioning, joinCondition):
    // Extract partition columns and transforms
    leftCols = leftPartitioning.columns
    rightCols = rightPartitioning.columns
    leftTransform = leftPartitioning.transform
    rightTransform = rightPartitioning.transform

    // Check if partition columns align with join keys
    if not joinCondition.equates(leftCols, rightCols):
        return INCOMPATIBLE

    // Check transform compatibility
    if leftTransform == rightTransform:
        if leftTransform.parameters == rightTransform.parameters:
            return IDENTICAL
        else:
            return checkParameterCompatibility(leftTransform, rightTransform)

    // Check for subset relationships (e.g., MONTH is subset of DAY)
    if isSubsetTransform(leftTransform, rightTransform):
        return SUBSET_COMPATIBLE

    return INCOMPATIBLE
```

### 4.3 Lifespan Mapping for Different Partition Types

| Partition Type | Lifespan Granularity | Example |
|----------------|---------------------|---------|
| `BUCKET(N, col)` | N lifespans | 256 lifespans for 256 buckets |
| `IDENTITY(region)` | One per distinct value | 50 lifespans for 50 regions |
| `DAY(timestamp)` | One per day | 365 lifespans for one year |
| `YEAR(ts), MONTH(ts)` | One per (year, month) | 12 lifespans per year |

---

## 5. Detailed Design

### 5.1 Iceberg Connector Changes

#### 5.1.1 Expose Table Partitioning

```java
// New: IcebergPartitioningHandle
public class IcebergPartitioningHandle implements ConnectorPartitioningHandle {
    private final List<IcebergPartitionTransform> transforms;
    private final int partitionCount;  // Number of distinct partition values

    // For bucket: bucket count
    // For identity: distinct value count
    // For time-based: number of time periods
}

// IcebergAbstractMetadata.getTableLayout() enhancement
@Override
public ConnectorTableLayout getTableLayout(...) {
    // Current: returns layout without tablePartitioning
    // New: include tablePartitioning when table is partitioned

    Optional<ConnectorTablePartitioning> partitioning =
        getIcebergTablePartitioning(icebergTable);

    return new ConnectorTableLayout(
        handle,
        columns,
        predicate,
        partitioning,  // NEW: expose partitioning
        ...
    );
}
```

#### 5.1.2 Implement Rewindable Split Source

```java
// IcebergSplitSource enhancement
public class IcebergSplitSource implements ConnectorSplitSource {

    // Track splits by partition for rewind support
    private final Map<ConnectorPartitionHandle, List<IcebergSplit>> splitsByPartition;
    private final Map<ConnectorPartitionHandle, Integer> partitionReadIndex;

    @Override
    public void rewind(ConnectorPartitionHandle partitionHandle) {
        // Reset read position for this partition
        partitionReadIndex.put(partitionHandle, 0);
    }

    @Override
    public CompletableFuture<ConnectorSplitBatch> getNextBatch(
            ConnectorPartitionHandle partitionHandle, int maxSize) {
        // Return splits for specific partition
        List<IcebergSplit> splits = splitsByPartition.get(partitionHandle);
        int index = partitionReadIndex.get(partitionHandle);
        // ... return batch starting from index
    }
}
```

#### 5.1.3 Report Connector Capabilities

```java
// IcebergConnector enhancement
@Override
public Set<ConnectorCapabilities> getCapabilities() {
    return ImmutableSet.of(
        SUPPORTS_REWINDABLE_SPLIT_SOURCE,  // Enable recovery/adaptivity
        // ... existing capabilities
    );
}
```

### 5.2 Optimizer Changes

#### 5.2.1 Partition Compatibility Detection

```java
// New: PartitionCompatibilityAnalyzer
public class PartitionCompatibilityAnalyzer {

    public CompatibilityResult analyze(
            TablePartitioning left,
            TablePartitioning right,
            JoinCondition condition) {

        // Check if partition columns match join keys
        if (!joinKeysMatchPartitionColumns(left, right, condition)) {
            return CompatibilityResult.incompatible();
        }

        // Check transform compatibility
        TransformCompatibility transformCompat =
            checkTransformCompatibility(
                left.getPartitioningHandle(),
                right.getPartitioningHandle());

        if (transformCompat.isCompatible()) {
            return CompatibilityResult.compatible(
                transformCompat.getCommonPartitioning(),
                transformCompat.getLifespanCount());
        }

        return CompatibilityResult.incompatible();
    }
}
```

#### 5.2.2 SPJ-Aware AddExchanges

```java
// AddExchanges.java enhancement
@Override
public PlanNode visitJoin(JoinNode node, Context context) {
    // Check for SPJ opportunity
    Optional<TablePartitioning> leftPartitioning = getTablePartitioning(node.getLeft());
    Optional<TablePartitioning> rightPartitioning = getTablePartitioning(node.getRight());

    if (leftPartitioning.isPresent() && rightPartitioning.isPresent()) {
        CompatibilityResult compat = partitionCompatibilityAnalyzer.analyze(
            leftPartitioning.get(),
            rightPartitioning.get(),
            node.getCriteria());

        if (compat.isCompatible()) {
            // No exchange needed! Enable grouped execution
            return planSPJJoin(node, compat);
        }
    }

    // Fall back to normal exchange planning
    return super.visitJoin(node, context);
}
```

### 5.3 Scheduler Changes

#### 5.3.1 Pilot Lifespan Scheduling

```java
// New: AdaptiveLifespanScheduler extends DynamicLifespanScheduler
public class AdaptiveLifespanScheduler extends DynamicLifespanScheduler {

    private final int pilotLifespanCount;
    private final AtomicInteger completedPilotLifespans = new AtomicInteger(0);
    private volatile boolean pilotPhaseComplete = false;
    private volatile Optional<PlanFragment> optimizedPlan = Optional.empty();

    @Override
    public void scheduleInitial(SourceScheduler scheduler) {
        // Schedule only pilot lifespans initially
        for (int i = 0; i < pilotLifespanCount && i < totalLifespans; i++) {
            scheduler.startLifespan(Lifespan.driverGroup(i), partitionHandles.get(i));
        }
    }

    @Override
    public void onLifespanExecutionFinished(Iterable<Lifespan> completed) {
        super.onLifespanExecutionFinished(completed);

        for (Lifespan lifespan : completed) {
            if (lifespan.getId() < pilotLifespanCount) {
                int count = completedPilotLifespans.incrementAndGet();
                if (count == pilotLifespanCount) {
                    // All pilots complete - trigger reoptimization
                    triggerReoptimization();
                }
            }
        }
    }

    private void triggerReoptimization() {
        // Collect statistics from pilot lifespans
        LifespanStatistics stats = collectPilotStatistics();

        // Check if reoptimization is warranted
        if (shouldReoptimize(stats)) {
            // Invoke optimizer with actual statistics
            optimizedPlan = Optional.of(reoptimize(stats));
        }

        pilotPhaseComplete = true;

        // Schedule remaining lifespans (with potentially new plan)
        scheduleRemainingLifespans();
    }
}
```

#### 5.3.2 Lifespan Statistics Collection

```java
// New: LifespanStatistics
public class LifespanStatistics {
    private final int lifespanId;
    private final long rowCount;
    private final long executionTimeNanos;
    private final long peakMemoryBytes;
    private final Map<PlanNodeId, OperatorStatistics> operatorStats;

    // Per-operator statistics for join reordering decisions
    public static class OperatorStatistics {
        private final long inputRows;
        private final long outputRows;
        private final double selectivity;  // outputRows / inputRows
    }
}

// Coordinator aggregates statistics
public class LifespanStatisticsAggregator {
    private final Map<Integer, LifespanStatistics> lifespanStats = new ConcurrentHashMap<>();

    public void recordLifespanComplete(int lifespanId, LifespanStatistics stats) {
        lifespanStats.put(lifespanId, stats);
    }

    public AggregatedStatistics getAggregatedStats() {
        // Compute averages, detect outliers (skew), extrapolate totals
        return new AggregatedStatistics(
            averageRowCount(),
            averageExecutionTime(),
            skewedLifespans(),
            estimatedTotalRows()
        );
    }
}
```

### 5.4 Plan Reoptimization

#### 5.4.1 Reoptimization Trigger Conditions

```java
public class ReoptimizationDecider {

    private final double cardinalityDeviationThreshold = 2.0;  // 2x difference
    private final double selectivityDeviationThreshold = 0.5; // 50% difference

    public boolean shouldReoptimize(
            LifespanStatistics actual,
            PlanStatistics estimated) {

        // Check cardinality deviation
        double cardinalityRatio = (double) actual.getRowCount() / estimated.getRowCount();
        if (cardinalityRatio > cardinalityDeviationThreshold ||
            cardinalityRatio < 1.0 / cardinalityDeviationThreshold) {
            return true;
        }

        // Check join selectivity deviation
        for (PlanNodeId joinId : actual.getJoinNodeIds()) {
            double actualSelectivity = actual.getSelectivity(joinId);
            double estimatedSelectivity = estimated.getSelectivity(joinId);
            if (Math.abs(actualSelectivity - estimatedSelectivity) > selectivityDeviationThreshold) {
                return true;
            }
        }

        return false;
    }
}
```

#### 5.4.2 Incremental Reoptimization

```java
public class IncrementalReoptimizer {

    public PlanFragment reoptimize(
            PlanFragment originalPlan,
            LifespanStatistics actualStats) {

        // Update cost model with actual statistics
        CostModel updatedCostModel = costModel.withActualStats(actualStats);

        // Re-run join ordering with actual cardinalities
        PlanNode reorderedPlan = joinOrderer.optimize(
            originalPlan.getRoot(),
            updatedCostModel);

        // Re-run distribution decisions with actual sizes
        PlanNode redistributedPlan = distributionOptimizer.optimize(
            reorderedPlan,
            updatedCostModel);

        return originalPlan.withRoot(redistributedPlan);
    }
}
```

---

## 6. Algebraic Correctness

### 6.1 Why SPJ Results Are Correct

For compatibly-partitioned tables, SPJ produces correct results because:

```
Given:
  R partitioned by hash(key) into N buckets
  S partitioned by hash(key) into N buckets

For equi-join R ⋈_{R.key = S.key} S:
  - All R rows with key k are in bucket hash(k) % N
  - All S rows with key k are in bucket hash(k) % N
  - Therefore, all matching rows are in the same bucket
  - Local join per bucket produces complete result

Result = ⋃_{i=0}^{N-1} (R[i] ⋈ S[i])
       = R ⋈ S  (algebraically equivalent)
```

### 6.2 Why Pilot-Based Adaptation Is Correct

Using IVM-like reasoning:

```
R = R_pilot ∪ R_remainder

For any query Q with only monotonic operators (join, filter, project):
  Q(R) = Q(R_pilot ∪ R_remainder)
       = Q(R_pilot) ∪ Q(R_remainder)  -- by monotonicity

For SPJ with partition-wise execution:
  Q(R ⋈ S) = ⋃_i Q(R[i] ⋈ S[i])

  Pilot lifespans: Q(R[0..k] ⋈ S[0..k])
  Remainder lifespans: Q(R[k+1..N] ⋈ S[k+1..N])

  Result = Pilot ∪ Remainder = Complete result
```

### 6.3 Handling Non-Monotonic Operators

For aggregations at the top:

```sql
SELECT region, SUM(sales) FROM orders ⋈ regions GROUP BY region
```

With SPJ on region:
```
Lifespan "US-EAST": orders[US-EAST] ⋈ regions[US-EAST] → {(US-EAST, 1000)}
Lifespan "US-WEST": orders[US-WEST] ⋈ regions[US-WEST] → {(US-WEST, 500)}

Result = UNION of all lifespans (no merging needed - groups are disjoint)
```

For non-aligned aggregations:
```sql
SELECT product_category, SUM(sales) FROM orders ⋈ products
GROUP BY product_category
-- orders partitioned by region, not product_category
```

Requires partial aggregation per lifespan, then final aggregation:
```
Lifespan 0: partial_agg → {(Electronics, 100), (Clothing, 50)}
Lifespan 1: partial_agg → {(Electronics, 150), (Clothing, 75)}

Final: merge partial aggregates → {(Electronics, 250), (Clothing, 125)}
```

---

## 7. Skew Handling

### 7.1 Skew Detection

```java
public class SkewDetector {

    private final double skewThreshold = 3.0;  // 3x median

    public Set<Integer> detectSkewedLifespans(Map<Integer, LifespanStatistics> stats) {
        // Compute median execution time
        long medianTime = computeMedian(
            stats.values().stream()
                .map(LifespanStatistics::getExecutionTimeNanos)
                .collect(toList()));

        // Identify outliers
        return stats.entrySet().stream()
            .filter(e -> e.getValue().getExecutionTimeNanos() > medianTime * skewThreshold)
            .map(Map.Entry::getKey)
            .collect(toSet());
    }
}
```

### 7.2 Skew Mitigation Strategies

| Strategy | When to Use | How It Works |
|----------|-------------|--------------|
| **Sub-partitioning** | Large partition with uniform key distribution | Split partition into N sub-partitions using secondary hash |
| **Replication** | Small build side, large skewed probe | Replicate build side to all sub-partition tasks |
| **Salting** | Extreme skew on single key | Add random salt to key, aggregate results |

### 7.3 Implementation

```java
public class SkewMitigator {

    public List<Lifespan> mitigateSkew(
            Lifespan skewedLifespan,
            SkewMitigationStrategy strategy) {

        switch (strategy) {
            case SUB_PARTITION:
                // Split into N sub-lifespans
                int subPartitions = 8;
                return IntStream.range(0, subPartitions)
                    .mapToObj(i -> createSubLifespan(skewedLifespan, i, subPartitions))
                    .collect(toList());

            case REPLICATE_BUILD:
                // Single lifespan but with replicated build
                return List.of(skewedLifespan.withReplicatedBuild());

            default:
                return List.of(skewedLifespan);
        }
    }
}
```

---

## 8. Implementation Roadmap

### Phase 1: SPJ Foundation (Q1)

| Task | Description | Components |
|------|-------------|------------|
| 1.1 | Iceberg partitioning exposure | `IcebergPartitioningHandle`, `getTableLayout()` |
| 1.2 | Partition compatibility analyzer | `PartitionCompatibilityAnalyzer` |
| 1.3 | SPJ-aware exchange planning | `AddExchanges` enhancement |
| 1.4 | Grouped execution for Iceberg | Enable `SUPPORTS_REWINDABLE_SPLIT_SOURCE` |
| 1.5 | Testing | SPJ correctness tests, performance benchmarks |

**Deliverable**: Shuffle-free joins for compatibly-partitioned Iceberg tables.

### Phase 2: Statistics Infrastructure (Q2)

| Task | Description | Components |
|------|-------------|------------|
| 2.1 | Per-lifespan statistics collection | `LifespanStatistics`, operator instrumentation |
| 2.2 | Statistics aggregation | `LifespanStatisticsAggregator` |
| 2.3 | Statistics reporting | Coordinator ↔ Worker protocol |
| 2.4 | Telemetry | Metrics for lifespan execution |

**Deliverable**: Visibility into per-lifespan execution characteristics.

### Phase 3: Pilot-Based Adaptation (Q3)

| Task | Description | Components |
|------|-------------|------------|
| 3.1 | Pilot lifespan scheduling | `AdaptiveLifespanScheduler` |
| 3.2 | Reoptimization trigger | `ReoptimizationDecider` |
| 3.3 | Incremental reoptimization | `IncrementalReoptimizer` |
| 3.4 | Plan switching for remainder | Scheduler integration |
| 3.5 | Testing | Adaptation correctness, performance |

**Deliverable**: Adaptive join reordering based on pilot lifespan statistics.

### Phase 4: Skew Handling (Q4)

| Task | Description | Components |
|------|-------------|------------|
| 4.1 | Skew detection | `SkewDetector` |
| 4.2 | Sub-partitioning | `SkewMitigator` |
| 4.3 | Dynamic lifespan splitting | Scheduler integration |
| 4.4 | Testing | Skew handling correctness, performance |

**Deliverable**: Automatic skew mitigation for partitioned execution.

---

## 9. Configuration

### 9.1 Session Properties

```properties
# Enable SPJ (Phase 1)
storage_partitioned_join_enabled=true

# Enable adaptive execution (Phase 3)
adaptive_lifespan_execution_enabled=true

# Pilot configuration
adaptive_pilot_lifespan_count=4
adaptive_pilot_lifespan_fraction=0.02  # Alternative: percentage-based

# Reoptimization thresholds
adaptive_cardinality_deviation_threshold=2.0
adaptive_selectivity_deviation_threshold=0.5

# Skew handling (Phase 4)
adaptive_skew_detection_enabled=true
adaptive_skew_threshold=3.0
adaptive_skew_mitigation_strategy=SUB_PARTITION  # or REPLICATE_BUILD
```

### 9.2 Connector Properties

```properties
# Iceberg connector
iceberg.spj.enabled=true
iceberg.spj.preserve-data-grouping=true  # Similar to Spark's setting
```

---

## 10. Compatibility and Migration

### 10.1 Backward Compatibility

- SPJ is opt-in via session property
- Non-partitioned tables continue to use shuffle-based joins
- Existing bucketed Hive tables work unchanged

### 10.2 Spark Compatibility

| Spark Feature | Presto Equivalent | Status |
|---------------|-------------------|--------|
| `KeyGroupedPartitioning` | `ConnectorTablePartitioning` | Exists, needs enhancement |
| `HasPartitionKey` | `ConnectorPartitionHandle` per partition | Needs implementation |
| Auto-shuffle one side | Fallback to exchange | Natural fallback |
| `spark.sql.sources.v2.bucketing.enabled` | `storage_partitioned_join_enabled` | New |

### 10.3 Iceberg Version Requirements

- Minimum: Iceberg 1.2.0 (SPJ support)
- Recommended: Iceberg 1.4.0+ (improved partition pruning)

---

## 11. Open Questions

### 11.1 Multi-Stage Plans

**Question**: For multi-stage plans (multiple shuffles), how do we maintain partition identity through exchanges?

**Options**:
1. Limit SPJ to single-stage (leaf-level) joins
2. Propagate partition identity through exchanges
3. Use hash-based routing to maintain partition alignment

### 11.2 Partition Count Mismatch

**Question**: How to handle tables with different partition counts (e.g., 256 buckets vs 128 buckets)?

**Options**:
1. Require exact match (strict)
2. Allow multiples (256 = 2 × 128, group accordingly)
3. Allow superset (project common partitions)

### 11.3 Mixed Partition Types

**Question**: Can we join identity-partitioned with bucket-partitioned tables?

**Answer**: Only if the bucket function produces the same grouping as identity for the join keys. Generally, these are incompatible.

### 11.4 Dynamic Partition Pruning Integration

**Question**: How does SPJ interact with dynamic partition pruning?

**Options**:
1. Apply dynamic filters to remaining lifespans only
2. Skip lifespans where dynamic filter eliminates all data
3. Combine with pilot phase (pilots inform dynamic filters)

---

## 12. References

### Spark Documentation
- [SPARK-37375: Storage Partitioned Join Umbrella](https://issues.apache.org/jira/browse/SPARK-37375)
- [SPARK-41413: SPJ with Mismatched Partition Keys](https://issues.apache.org/jira/browse/SPARK-41413)
- [SPARK-48613: Auto-shuffle with Less Join Keys](https://www.mail-archive.com/commits@spark.apache.org/msg68353.html) (July 2024)
- [Storage-Partitioned Joins Internals](https://books.japila.pl/spark-sql-internals/storage-partitioned-joins/)
- [SPARK-48012: Transform Expressions for One Side Shuffle](https://www.mail-archive.com/issues@spark.apache.org/msg368173.html)

### Presto Code References (with Line Numbers)
- `Lifespan.java`: Lifespan abstraction (`taskWide()`, `driverGroup(id)`)
- `DynamicLifespanScheduler.java`: Lifespan scheduling, `rewindLifespan()` for recovery
- `GroupedExecutionTagger.java:283-301`: `visitTableScan()` - determines grouped execution eligibility
- `PropertyDerivations.java:876-958`: `visitTableScan()` - derives global properties from table partitioning
- `PropertyDerivations.java:938-950`: Uses `layout.getTablePartitioning()` for partitioned properties
- `AddExchanges.java:950-1055`: `visitJoin()` - checks `isNodePartitionedOn()` before adding exchanges
- `HiveMetadata.java:2820-2869`: Reference implementation for exposing `ConnectorTablePartitioning`
- `IcebergAbstractMetadata.java:425-458`: `getTableLayout()` - currently returns `Optional.empty()` for tablePartitioning
- `IcebergConnector.java:116-119`: `getCapabilities()` - missing `SUPPORTS_REWINDABLE_SPLIT_SOURCE`
- `HivePartitioningHandle.java`: Bucket partitioning with `bucketCount`, `bucketFunctionType`, `maxCompatibleBucketCount`
- `PartitionTransformType.java`: Iceberg partition transforms enum

### Academic References
- Graefe, G. "Query Evaluation Techniques for Large Databases" (1993)
- Deshpande, A. et al. "Adaptive Query Processing" (2007)

---

## Appendix C: Current Implementation Gaps

Based on code analysis, here are the specific gaps that need to be addressed for SPJ:

### C.1 Iceberg Connector Gaps

#### Gap 1: Missing `tablePartitioning` in Layout

**Current State** (`IcebergAbstractMetadata.java:425-458`):
```java
// Lines 425-433: getTableLayout() always passes Optional.empty() for tablePartitioning
return new ConnectorTableLayout(
    icebergTableLayoutHandle,
    Optional.empty(),           // columns
    predicate,
    Optional.empty(),           // tablePartitioning - THIS IS THE GAP
    Optional.empty(),           // streamPartitioning
    discretePredicates,
    ImmutableList.of(),
    Optional.of(combinedRemainingPredicate));
```

**Reference Implementation** (`HiveMetadata.java:2820-2869`):
```java
if (bucketExecutionEnabled && hiveLayoutHandle.getBucketHandle().isPresent()) {
    HiveBucketHandle hiveBucketHandle = hiveLayoutHandle.getBucketHandle().get();
    HivePartitioningHandle partitioningHandle = ...;

    tablePartitioning = Optional.of(new ConnectorTablePartitioning(
        partitioningHandle,
        hiveBucketHandle.getColumns().stream()
            .map(ColumnHandle.class::cast)
            .collect(toImmutableList())));
}
```

**Required Change**: Create `IcebergPartitioningHandle` and expose it via `ConnectorTablePartitioning`.

#### Gap 2: Missing Connector Capability

**Current State** (`IcebergConnector.java:116-119`):
```java
@Override
public Set<ConnectorCapabilities> getCapabilities() {
    return immutableEnumSet(NOT_NULL_COLUMN_CONSTRAINT);  // Missing SUPPORTS_REWINDABLE_SPLIT_SOURCE
}
```

**Required Change**: Add `SUPPORTS_REWINDABLE_SPLIT_SOURCE` to enable lifespan recovery.

#### Gap 3: Missing Partition Handle Listing

**Dependency** (`GroupedExecutionTagger.java:289`):
```java
List<ConnectorPartitionHandle> partitionHandles =
    nodePartitioningManager.listPartitionHandles(session, tablePartitioning.get().getPartitioningHandle());
```

**Required Change**: Implement `IcebergNodePartitioningProvider.listPartitionHandles()` to return partition handles based on Iceberg partition spec.

### C.2 How Properties Flow Through the Planner

1. **Table Scan** → `PropertyDerivations.visitTableScan()` (line 876)
2. **Get Layout** → `metadata.getLayout(session, node.getTable())` (line 878)
3. **Check Partitioning** → `layout.getTablePartitioning()` (line 938)
4. **Derive Global Properties** → `deriveGlobalProperties()` (line 933)
5. **Partitioned On** → `partitionedOn(tablePartitioning.getPartitioningHandle(), arguments, streamPartitioning)` (line 950)
6. **Join Planning** → `AddExchanges.visitJoin()` checks `isNodePartitionedOn()` (line 989)
7. **Skip Exchange** → If already partitioned on join keys, no shuffle needed

### C.3 Key Session Properties

| Property | Default | Purpose |
|----------|---------|---------|
| `plan_with_table_node_partitioning` | true | Enable table partitioning in planning |
| `colocated_join` | false | Allow co-located joins without exchange |
| `grouped_execution` | false | Enable lifespan-based execution |

### C.4 Spark vs Presto SPJ Comparison

| Aspect | Spark | Presto |
|--------|-------|--------|
| **Partitioning Interface** | `KeyGroupedPartitioning` | `ConnectorTablePartitioning` |
| **Partition Handle** | `InputPartition` with `HasPartitionKey` | `ConnectorPartitionHandle` |
| **Planning Hook** | `V2ScanPartitioning` rule | `PropertyDerivations.visitTableScan()` |
| **Exchange Skip Logic** | `EnsureRequirements` rule | `AddExchanges.visitJoin()` |
| **Grouped Execution** | N/A (batch model) | Lifespans via `GroupedExecutionTagger` |
| **Recovery Mechanism** | Stage retry | `rewindLifespan()` |
| **Transform Support** | Full (DAY, YEAR, BUCKET, etc.) | Partial (needs implementation) |

**Key Architectural Difference:**
- **Spark**: Batch model with stage boundaries - natural checkpoints
- **Presto**: Streaming model with lifespans - requires explicit partitioning for checkpoints

---

## Appendix A: Partition Transform Compatibility Matrix

| Left Transform | Right Transform | Compatible? | Notes |
|---------------|-----------------|-------------|-------|
| `BUCKET(N, col)` | `BUCKET(N, col)` | ✓ | Same bucket count |
| `BUCKET(N, col)` | `BUCKET(M, col)` | ✓ if N % M = 0 | Group N/M buckets |
| `IDENTITY(col)` | `IDENTITY(col)` | ✓ | Same column |
| `DAY(ts)` | `DAY(ts)` | ✓ | Same time granularity |
| `DAY(ts)` | `HOUR(ts)` | ✓ | Group 24 hours per day |
| `MONTH(ts)` | `DAY(ts)` | ✓ | Group ~30 days per month |
| `BUCKET(N, col)` | `IDENTITY(col)` | ✗ | Incompatible schemes |
| `BUCKET(N, col)` | `DAY(ts)` | ✗ | Incompatible schemes |

## Appendix B: Example Query Plans

### Before SPJ

```
Fragment 0 [SOURCE]
  TableScan[orders, partitioned by bucket(256, customer_id)]

Fragment 1 [SOURCE]
  TableScan[customers, partitioned by bucket(256, customer_id)]

Fragment 2 [HASH]
  Join[orders.customer_id = customers.customer_id]
    RemoteSource[0] -- shuffled by customer_id
    RemoteSource[1] -- shuffled by customer_id
```

### After SPJ

```
Fragment 0 [GROUPED, 256 lifespans]
  Join[orders.customer_id = customers.customer_id]
    TableScan[orders, partition=lifespan_id]
    TableScan[customers, partition=lifespan_id]

  -- No shuffle! Each lifespan joins matching partitions locally
```

---

*Document Version: 0.2*
*Last Updated: December 27, 2024*
