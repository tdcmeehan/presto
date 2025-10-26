# RFC: Dynamic Partition Pruning with Runtime Filters

Proposers

* [Your Name]
* [Additional Contributors]

## Related Issues

* TBD: Link to Github issues/PRs once created
* Related: Existing dynamic filtering implementation (coordinator-only)

## Summary

This RFC proposes implementing distributed dynamic partition pruning for Presto. The feature extracts filters from hash join build sides on workers, distributes them to probe-side workers, and applies them at multiple pruning levels (partition, file, row group, and row filtering).

The runtime filter uses Presto's existing `TupleDomain` structure, which supports both min/max ranges and discrete values (up to configurable limit). This enables perfect pruning for low-cardinality joins and range-based pruning for high-cardinality joins.

A key mechanism is **scheduler-level waiting**: the coordinator's split scheduler delays assigning probe-side splits until filters arrive (or timeout), preventing wasted I/O on data that will be filtered out.

## Background

### The Problem: Unnecessary I/O in Star Schema Joins

Star schema and fact-to-fact joins often read massive amounts of data that will be filtered out by the join condition:

```sql
-- Example: Orders (100M rows) joined with small customer subset
SELECT * FROM orders o
JOIN customers c ON o.customer_id = c.id
WHERE c.region = 'US';  -- Only 10K customers match

-- Without dynamic filtering:
-- - Read all 100M orders from storage
-- - Build hash table for 10K customers
-- - Probe with 100M orders → 90M immediately discarded
-- - Wasted: 90M rows read from disk unnecessarily
```

### Existing Limitations

Presto's current dynamic filtering has a critical limitation:

**No partition-level pruning**: Filters are built on workers but not available to the coordinator's split scheduler. The coordinator generates all probe-side splits immediately without waiting for filters. This means:
- For **broadcast joins**: Workers apply filters locally at file/row group/row level, but all partitions are still scheduled and opened
- For **partitioned joins**: Each worker has only a partial build side, can't build a complete filter, so filtering is limited or ineffective

**Result**: Queries scan unnecessary partitions and waste I/O, even when filters could have eliminated 90%+ of partitions at scheduling time.

### User Impact

Dynamic partition pruning applies to workloads with:
- Star schema queries (fact table joined with filtered dimension)
- Fact-to-fact joins with selective predicates
- Partition-aligned or bucketed join columns
- Hive, Iceberg, and Delta Lake tables

Measured improvements from PoC:
- 30-80% reduction in I/O for star schema queries
- 2-5x faster query execution for selective joins
- Reduced memory pressure on workers due to smaller input sets

### Goals

1. **Distributed filtering**: Extract filters on workers, distribute to probe-side workers
2. **Multi-level pruning**: Apply filters at partition, file, row group, and row levels
3. **C++ support**: Full integration with Velox execution engine
4. **Optimal pruning**: Perfect pruning for low-cardinality joins (≤10K values), range-based for high-cardinality
5. **Zero overhead for non-benefiting queries**: Conservative defaults, opt-in behavior

### Non-goals

1. **Java worker support**: This RFC targets C++/Velox deployments only
2. **Cross-query filter caching**: Filters are query-scoped, not cached across queries

## Proposed Implementation

### 1. High-Level Flow

This section describes the end-to-end journey of a dynamic filter from creation to application.

#### Example Query

```sql
SELECT o.order_id, o.amount, c.name
FROM orders o  -- Probe side: 100M rows, partitioned by customer_id
JOIN customers c ON o.customer_id = c.id  -- Build side: 10K rows after filter
WHERE c.region = 'US';
```

#### Step 1: Query Planning (Optimizer)

**Component**: `AddDynamicFilterRule` (PlanOptimizer in presto-main)

**What happens**:
1. Optimizer identifies hash join (broadcast or partitioned)
2. Finds `TableScanNode` in probe side (traversing through any intermediate nodes)
3. Performs storage-level pruning check: Can filter prune partitions/files?
   - Checks if join column (`customer_id`) matches partition/bucket columns in table layout
   - For Hive partitioned by `customer_id`: YES
   - For Hive partitioned by `order_date`: NO (skip dynamic filtering)
4. If beneficial, marks join for dynamic filtering
5. Assigns unique filter ID linking build and probe sides

**Output**: Query plan with filter markers on both `JoinNode` and `TableScanNode`

#### Step 2: Build-Side Construction (Workers)

**Components**:
- Java: `DynamicFilterSourceOperator` (presto-main)
- C++: `HashBuild` operator extension (presto-native-execution)

**What happens**:
1. Build-side worker scans dimension table (10K customers with region='US')
2. While building hash table, incrementally constructs `TupleDomain`:
   - Collect distinct customer_id values (up to configurable limit, default 10K)
   - Track min/max customer_id for range
3. When hash build completes (450ms):
   - If cardinality ≤ limit: Create TupleDomain with discrete values (perfect filter)
   - If cardinality > limit: Create TupleDomain with range only (min/max)
   - Store in task's filter collection
   - Increment `dynamicFiltersVersion` in TaskStatus

**Output**: TupleDomain filter stored locally on worker, version incremented

#### Step 3: Filter Collection (Coordinator)

**Component**: `DynamicFiltersFetcher` (new, presto-main)

**What happens**:
1. Coordinator polls TaskStatus every 200ms (existing mechanism)
2. Detects version change: `dynamicFiltersVersion=0` → `1`
3. Long-polls worker endpoint:
   ```
   GET /v1/task/{taskId}/dynamicfilters
   X-Presto-Current-Version: 0
   X-Presto-Max-Wait: 1s
   ```
4. Worker returns all new filters since version 0:
   ```json
   {
     "version": 1,
     "filters": {
       "df_customer_id": "<binary filter data>"
     }
   }
   ```
5. Coordinator receives filter (total latency: ~20ms from build completion)

**Why version-based incremental updates?**

A single task may have multiple `HashBuild` operators (for different joins in the same query fragment) that complete at different times:

```sql
-- Example: Two joins in same fragment
SELECT * FROM fact f
JOIN dim1 d1 ON f.customer_id = d1.id
JOIN dim2 d2 ON f.product_id = d2.id
```

Timeline for single task:
- T1 (450ms): HashBuild for dim1 completes → creates `df_customer_id` → version 0→1
- T2 (480ms): HashBuild for dim2 completes → creates `df_product_id` → version 1→2

The version-based protocol lets coordinator incrementally fetch new filters without re-fetching filters it already has.

**Output**: Coordinator has partial filter from one build worker

#### Step 4: Filter Merging (Coordinator)

**Component**: `LocalDynamicFilter` (existing, presto-main)

**What happens**:

**Broadcast join:**
- All workers process identical build data (entire build side replicated)
- All workers produce identical filter: `customer_id IN (1, 5, 7, ...)`
- Coordinator can use filter from any single worker (no merge needed)
- Filter immediately complete

**Partitioned join:**
- Each worker processes different build data (build side distributed)
- Worker 1: `customer_id IN (1, 2, 5)`
- Worker 2: `customer_id IN (5, 7, 8)`
- Coordinator must collect and merge using UNION: `customer_id IN (1, 2, 5, 7, 8)`
- Wait for all partitions before marking complete

**Merge logic for partitioned joins** (UNION semantics):

For partitioned joins, each worker processes different rows, so we need UNION of all filters:
- **Discrete values**: Set union
  - Worker 1: {1, 2, 5} + Worker 2: {5, 7, 8} → Merged: {1, 2, 5, 7, 8}
- **Ranges**: Union of ranges (min of mins, max of maxes)
  - Worker 1: [10, 50] + Worker 2: [40, 90] → Merged: [10, 90]
- **Exceeds limit**: If merged discrete values exceed 10K limit, fall back to range

Implementation: `TupleDomain` already supports this via `Domain.union()` method.

**Output**: Complete filter ready for distribution (broadcast) or merged filter (partitioned)

#### Step 5: Scheduler Waiting (Coordinator) ← **CRITICAL MISSING PIECE**

**Component**: `SourcePartitionedScheduler` (modified, presto-main)

**Location**: `com.facebook.presto.execution.scheduler.SourcePartitionedScheduler`

**What happens**:
1. Scheduler's `schedule()` method is about to call `splitSource.getNextBatch()`
2. **NEW CHECK**: Does this TableScan have dynamic filter marker?
   ```java
   if (shouldWaitForDynamicFilter(scheduleGroup)) {
       scheduleGroup.nextSplitBatchFuture = scheduleWithDynamicFilterWait(scheduleGroup, lifespan);
   } else {
       scheduleGroup.nextSplitBatchFuture = splitSource.getNextBatch(...);
   }
   ```
3. `shouldWaitForDynamicFilter()` checks:
   - Does TableScanNode have dynamic filter IDs? (set by optimizer in Step 1)
   - Is wait time > 0? (default is 0s, opt-in via session property)
4. If YES, create wait condition:
   ```java
   CompletableFuture<?> filterReady = dynamicFilter.isBlocked();
   CompletableFuture<?> timeout = createTimeoutFuture(Duration.ofMillis(500));
   CompletableFuture<?> queueDrained = createQueueDrainFuture();

   return CompletableFuture.anyOf(filterReady, timeout, queueDrained)
       .thenCompose(v -> splitSource.getNextBatch(...));
   ```
5. Scheduler WAITS for FIRST of:
   - **Filter ready**: DynamicFilterService completed filter (Step 4)
   - **Timeout**: 500ms elapsed, give up waiting
   - **Queue drained**: Worker queues have space, need more splits

**Why this matters**:
- **Without waiting**: Scheduler immediately calls `getNextBatch()`, connector generates all 100M splits, workers read all data
- **With waiting**: Scheduler waits 450ms for filter, then calls `getNextBatch()` with filter available, connector prunes 95% of partitions

**Configuration**:
```properties
# Default: 0s (disabled, opt-in)
dynamic-filter.max-wait-time=0s

# Enable with:
set session dynamic_filter_max_wait_time='500ms';
```

**Output**: Scheduler delays split scheduling until filter arrives

##### Why Split-Level Waiting (Not Stage-Level)?

**Presto has existing stage-level scheduling** (`PhasedExecutionSchedule`) that sequences stages based on dependencies. For joins, build stages run before probe stages. One might ask: why not wait at the stage level?

**Answer: Bucketed execution requires split-level waiting.**

Dynamic partition pruning supports three execution modes:

**1. Broadcast Joins (Separate Stages)**
```
Stage 1 (build): Scan customers WHERE region='US' → HashBuild
Stage 2 (probe): Scan orders → Join
```
- Build and probe in **different stages**
- Stage-level waiting would work here

**2. Partitioned Joins (Separate Stages)**
```
Stage 1 (build): Scan customers (partitioned) → HashBuild
Stage 2 (probe): Scan orders (partitioned) → Join
```
- Build and probe in **different stages**
- Stage-level waiting would work here

**3. Bucketed Joins (Same Stage, Grouped Execution)**
```
Single Stage, Bucket 0: Scan customers (bucket 0) → HashBuild → Scan orders (bucket 0) → Join
Single Stage, Bucket 1: Scan customers (bucket 1) → HashBuild → Scan orders (bucket 1) → Join
...
Single Stage, Bucket 255: Scan customers (bucket 255) → HashBuild → Scan orders (bucket 255) → Join
```
- Build and probe in **same stage**, executed bucket-by-bucket (lifespan-by-lifespan)
- **Stage-level waiting would create deadlock** (stage waiting for itself)
- **Split-level waiting is required**

**Bucketed execution example showing massive pruning:**

```sql
-- orders: 256 buckets on customer_id, 365 date partitions → 93,440 potential splits
-- customers: 256 buckets on customer_id, region filter leaves only 10 buckets with data

SELECT * FROM orders o
JOIN customers c ON o.customer_id = c.customer_id
WHERE c.region = 'US';
```

**Without dynamic filtering:**
- Schedule all 256 buckets
- Each bucket scans all 365 date partitions
- Total: 256 × 365 = 93,440 splits

**With dynamic filtering (split-level waiting):**
1. Build completes for buckets 0-255
2. Filters reveal only buckets {5, 12, 23, 45, 67, 89, 123, 156, 189, 234} contain region='US'
3. **Bucket-level pruning**: Skip 246 buckets entirely (never schedule their lifespans)
4. **Partition-level pruning**: Within 10 active buckets, prune to date ranges where customers exist
5. Total: 10 buckets × ~50 partitions = 500 splits
6. **99.5% reduction** (93,440 → 500 splits)

**Split-level waiting handles all three execution modes:**
- **Bucketed**: Wait per-lifespan before generating splits for each bucket
- **Broadcast/Partitioned**: Wait before generating probe-side splits (same mechanism, across stages)
- **Unified implementation**: Single code path in `SourcePartitionedScheduler`

#### Step 6: Filter Distribution (Coordinator → Workers)

**Component**: `DynamicFilterService` (presto-main)

**What happens**:
1. When filter marked complete (Step 4), `DynamicFilterService` notifies waiting consumers
2. **For Java workers**: `DynamicFilter.isBlocked()` future completes
3. **For C++ workers**: Callback invoked with filter data
4. Probe-side workers now have filter available in memory

**Mechanism**:
- Java: In-memory via `LocalDynamicFilter` reactive interface
- C++: HTTP callback or periodic polling

**Output**: All probe-side workers have filter

#### Step 7: Filter Application (Cascading Levels)

**Components**: Multiple, applied at different levels

**Level 1: Partition Pruning** (Connector split source)
- **Component**: Connector's `ConnectorSplitSource.getNextBatch()` implementation
- **What**: List partitions, test against filter
- **Logic**:
  - Range test: `partition.customer_id_max < filter.min` → SKIP
  - Distinct values test: `partition.customer_id_min..max ∩ filter.distinctValues == ∅` → SKIP
- **Result**: 95 of 100 partitions skipped (5 partitions have matching customer IDs)
- **Examples**: `HiveSplitSource`, `IcebergSplitSource`, `DeltaSplitSource`

**Level 2: File Pruning** (Connector split source)
- **Component**: Same as Level 1
- **What**: Within remaining partitions, test file statistics
- **Logic**: Same as partition pruning
- **Limitation**: Iceberg/Parquet only store min/max, not distinct values
- **Result**: 70% of files skipped in remaining partitions

**Level 3: Row Group Pruning** (Velox)
- **Component**: `ParquetReader.filterRowGroups()` (velox/dwio/parquet)
- **What**: Velox calls `testFilter()` on row group statistics
- **Logic**: Same as file pruning (range + distinct values)
- **Result**: Row groups with no matching customer_id skipped

**Level 4: Row Filtering** (Velox)
- **Component**: Velox's existing `Filter` implementations (BigintValues, BigintRange)
- **What**: Filter individual rows during scan
- **Logic**:
  - If filter has discrete values (≤10K): Use BigintValues filter (hash table, 0% false positives)
  - If filter has range only (>10K): Use BigintRange filter (min/max comparison)
- **SIMD**: BigintValues and BigintRange both support `xsimd::batch` vectorization (3-4x speedup)
- **Result**: Remaining rows filtered

**Total impact**:
- Partition pruning: 95% eliminated (1.4s saved)
- File pruning: 70% of remaining eliminated (400ms saved)
- Row group pruning: Additional row groups skipped
- Row filtering: Final rows filtered (17ms with SIMD)
- **Net**: 30x less data read, 5.6x faster query

### 2. Component Details

Now we describe each component in detail, organized by the flow steps above.

#### 2.1 Query Planning (Optimizer)

**Component**: `AddDynamicFilterRule` extends `PlanOptimizer`

**Location**: `presto-main/src/main/java/com/facebook/presto/sql/planner/optimizations/`

**What changes**:
- New optimizer rule added to optimization sequence
- Runs after join reordering but before fragment creation
- Modifies `JoinNode` and `TableScanNode` in query plan
- **Applies to both broadcast and partitioned joins**

**Key Design Decision: Unified Approach**

Both broadcast and partitioned joins benefit from partition-level pruning:
- **Broadcast**: Build side replicated to all workers → all produce identical filter → coordinator uses any one
- **Partitioned**: Build side distributed across workers → coordinator merges partial filters

In both cases, coordinator waits for filter before scheduling probe-side splits, enabling partition pruning.

**Algorithm**:

```java
private boolean shouldAddDynamicFilter(JoinNode join, Context context) {
    // Step 1: Find TableScanNode in probe side
    // Traverses through ProjectNode, FilterNode, AggregationNode, WindowNode, etc.
    TableScanNode scan = findTableScanRecursive(join.getProbe());
    if (scan == null) {
        return false;  // No table scan found in probe side
    }

    // Step 2: Can filter prune at storage level?
    // This is the ONLY check needed - applies to both broadcast and partitioned
    return canPrunePartitionsOrFiles(join, scan, context);
}

/**
 * Recursively find TableScanNode, traversing any single-source nodes.
 *
 * KEY INSIGHT: Intermediate nodes (Aggregation, Window, etc.) don't prevent
 * dynamic filtering from being beneficial. We prune at the SCAN level,
 * reducing I/O and processing cost for all downstream operators.
 */
private TableScanNode findTableScanRecursive(PlanNode node) {
    // Base case: found the scan
    if (node instanceof TableScanNode) {
        return (TableScanNode) node;
    }

    // Recursive case: traverse through single-source nodes
    // Handles: ProjectNode, FilterNode, AggregationNode, WindowNode,
    //          SortNode, LimitNode, TopNNode, etc.
    if (node.getSources().size() == 1) {
        return findTableScanRecursive(node.getSources().get(0));
    }

    // Multi-source (UnionNode, JoinNode) or leaf node - can't trace
    return null;
}

/**
 * Check if dynamic filter can prune partitions or files at storage level.
 *
 * Returns true if join column maps to a partition or bucket column.
 */
private boolean canPrunePartitionsOrFiles(JoinNode join, TableScanNode scan, Context context) {
    // Step 1: Extract join columns from equijoin clauses
    Set<ColumnHandle> filterColumns = extractFilterColumns(join, scan);
    if (filterColumns.isEmpty()) {
        return false;  // Couldn't trace join columns to scan
    }

    // Step 2: Get table layout (already selected by connector)
    ConnectorTableLayout layout = getTableLayout(scan);

    // Step 3: Collect partition and bucket columns from layout
    Set<ColumnHandle> storageColumns = new HashSet<>();

    // Partition columns (Hive: PARTITIONED BY, Iceberg: partition spec)
    layout.getDiscretePredicates()
        .map(DiscretePredicates::getColumns)
        .ifPresent(storageColumns::addAll);

    // Bucket columns (Hive: CLUSTERED BY, Iceberg: bucket transforms)
    layout.getTablePartitioning()
        .map(ConnectorTablePartitioning::getPartitioningColumns)
        .ifPresent(storageColumns::addAll);

    // Step 4: Check for overlap
    return filterColumns.stream().anyMatch(storageColumns::contains);
}

/**
 * Extract ColumnHandles from join condition, tracing through intermediate nodes.
 *
 * For: SELECT ... FROM orders o JOIN customers c ON o.customer_id = c.id
 * Returns: {customer_id ColumnHandle from orders table}
 */
private Set<ColumnHandle> extractFilterColumns(JoinNode join, TableScanNode targetScan) {
    Set<ColumnHandle> filterColumns = new HashSet<>();

    for (JoinNode.EquiJoinClause clause : join.getCriteria()) {
        // Probe side variable (left side of join condition)
        VariableReferenceExpression probeVar = clause.getLeft();

        // Trace back through ProjectNode, FilterNode, etc. to find source column
        Optional<ColumnHandle> column = findSourceColumn(probeVar, targetScan, join.getLeft());

        column.ifPresent(filterColumns::add);
    }

    return filterColumns;
}

/**
 * Trace a variable back through plan nodes to its source ColumnHandle.
 *
 * Handles:
 * - ProjectNode: Follows simple column references (SELECT customer_id AS cust_id)
 * - FilterNode: Passes through (WHERE clauses don't change columns)
 * - Other single-source nodes: Continues tracing
 *
 * Stops tracing on:
 * - Complex expressions: SELECT customer_id * 2 (can't match to partition column)
 * - Multi-source nodes: UnionNode, JoinNode (ambiguous source)
 */
private Optional<ColumnHandle> findSourceColumn(
        VariableReferenceExpression variable,
        TableScanNode targetScan,
        PlanNode currentNode) {

    // Base case: reached the target table scan
    if (currentNode instanceof TableScanNode) {
        TableScanNode scan = (TableScanNode) currentNode;
        if (scan.getId().equals(targetScan.getId())) {
            return Optional.ofNullable(scan.getAssignments().get(variable));
        }
        return Optional.empty();
    }

    // ProjectNode: Check if it's a simple column reference
    if (currentNode instanceof ProjectNode) {
        ProjectNode project = (ProjectNode) currentNode;
        RowExpression expression = project.getAssignments().get(variable);

        // Simple reference: SELECT customer_id AS cust_id
        if (expression instanceof VariableReferenceExpression) {
            return findSourceColumn(
                (VariableReferenceExpression) expression,
                targetScan,
                project.getSource());
        }

        // Complex expression: SELECT customer_id * 2
        // Can't match to partition column, stop tracing
        return Optional.empty();
    }

    // FilterNode and other single-source nodes: pass through
    if (currentNode.getSources().size() == 1) {
        return findSourceColumn(variable, targetScan, currentNode.getSources().get(0));
    }

    // Can't trace through multi-source nodes
    return Optional.empty();
}
```

**Why Intermediate Nodes Don't Matter**

Dynamic filtering prunes at the **scan level**, saving I/O and CPU for ALL downstream operators:

**Example: Aggregation between scan and join**
```sql
SELECT customer_id, SUM(amount)
FROM orders  -- 100M rows, partitioned by customer_id
GROUP BY customer_id  -- outputs 1M groups
JOIN customers ON orders.customer_id = customers.id
WHERE customers.region = 'US';  -- filter: customer_id IN (500 values)
```

**Without dynamic filter:**
- Scan: Read 100M rows from storage (~1.5s I/O)
- Aggregate: Process 100M rows (~500ms CPU)
- Join: Probe 1M groups with 500 customers (~1ms)

**With dynamic filter (customer_id IN (...)):**
- Scan: Read 5M rows (95% partitions pruned, ~75ms I/O)
- Aggregate: Process 5M rows (~25ms CPU)
- Join: Probe 50K groups with 500 customers (~0.1ms)

**Savings: 1.4s I/O + 475ms CPU = 1.875s total**

The aggregation doesn't prevent dynamic filtering from being worthwhile - we still save massive I/O and aggregation cost by pruning at the scan!

**Examples of When It Works**

```sql
-- ✅ Broadcast join with aggregation
SELECT c.region, SUM(o.amount)
FROM orders o  -- 100M rows, partitioned by customer_id
GROUP BY o.customer_id
JOIN customers c ON o.customer_id = c.id  -- Broadcast: 10K customers
WHERE c.active = true;  -- Filters to 500 customers
-- Filter prunes 99.5% of partitions before scan

-- ✅ Partitioned join with window function
SELECT o.*, ROW_NUMBER() OVER (PARTITION BY o.customer_id)
FROM orders o  -- 100M rows, partitioned by customer_id
JOIN customers c ON o.customer_id = c.id  -- Partitioned join
WHERE c.region = 'US';
-- Filter prunes 95% of partitions before scan

-- ✅ Broadcast join with filters and projects
SELECT o.order_id, o.amount * 1.1 as amount_with_tax
FROM orders o  -- 100M rows
WHERE o.status = 'shipped'
JOIN customers c ON o.customer_id = c.id  -- Broadcast
WHERE c.vip = true;
-- Can trace customer_id through ProjectNode and FilterNode

-- ❌ Complex expression in join condition
SELECT o.*
FROM orders o
JOIN customers c ON o.customer_id * 2 = c.id
-- Can't match "customer_id * 2" to partition column

-- ❌ No table scan in probe side
SELECT *
FROM (VALUES (1), (2), (3)) AS t(id)
JOIN customers c ON t.id = c.id
-- No TableScanNode to prune
```

**Output**:
- `JoinNode` marked with dynamic filter metadata
- `TableScanNode` marked with dynamic filter IDs: `dynamicFilterIds = ["df_customer_id"]`
- Works for both broadcast and partitioned joins

#### 2.2 Runtime Filter Structure

**Purpose**: Use existing TupleDomain for all cardinality scenarios

**Filter construction**:

| Cardinality | Filter Type | Size | Pruning Power |
|-------------|-------------|------|---------------|
| ≤10K values | TupleDomain with discrete values | 8N bytes (≤80KB) | Perfect pruning (0% false positives) |
| >10K values | TupleDomain with range (min/max) | 16 bytes | Range-based pruning |

**Data structure**:

Presto's existing `TupleDomain<ColumnHandle>` supports both:
- **Discrete values**: `Domain.singleValue()` or `Domain.multipleValues()`
- **Range**: `Domain.create(ValueSet.ofRanges(Range.range(...)))`

**Benefits**:
- Reuses existing infrastructure (no new serialization, merging logic)
- Perfect pruning for low-cardinality joins (majority of star schema queries)
- Range-based pruning for high-cardinality joins (min/max comparisons)
- Bounded memory: Configurable limit on discrete values

#### 2.3 Build-Side Construction

**Components**:
- **Java**: `DynamicFilterSourceOperator` in presto-main
- **C++**: `HashBuild` operator extension in presto-native-execution/velox

**Algorithm**: Collect values during hash table build[^filter-builder-impl]

```cpp
class TupleDomainBuilder {
    int64_t min_ = std::numeric_limits<int64_t>::max();
    int64_t max_ = std::numeric_limits<int64_t>::min();
    folly::F14FastSet<int64_t> distinctValues_;
    static constexpr int kDistinctValuesLimit = 10'000;

    void addValue(int64_t value) {
        min_ = std::min(min_, value);
        max_ = std::max(max_, value);

        // Collect distinct values up to limit
        if (distinctValues_.size() < kDistinctValuesLimit) {
            distinctValues_.insert(value);
        }
    }

    TupleDomain build() {
        if (distinctValues_.size() < kDistinctValuesLimit) {
            // Create TupleDomain with discrete values
            return TupleDomain::withColumnDomains({
                {columnHandle, Domain::multipleValues(distinctValues_)}
            });
        } else {
            // Create TupleDomain with range
            return TupleDomain::withColumnDomains({
                {columnHandle, Domain::create(Range::range(min_, max_))}
            });
        }
    }
};
```

**Integration point** (C++):

```cpp
void HashBuild::noMoreInput() {
    // Build hash table (existing code)
    buildHashTable();

    // NEW: Extract TupleDomain from hash table
    auto tupleDomain = extractTupleDomainFromHashTable();

    // Store in task's filter collection
    task_->addDynamicFilter(filterId_, tupleDomain);

    // Increment version to signal coordinator
    task_->incrementDynamicFiltersVersion();
}
```

**Output**: TupleDomain stored in worker, version incremented in TaskStatus

#### 2.4 Filter Collection (Coordinator)

**Component**: `DynamicFiltersFetcher` (new component)

**Location**: `presto-main/src/main/java/com/facebook/presto/execution/`

**Mechanism**: Long-polling with version-based incremental updates

**Protocol**[^distribution-protocol]:

```java
// Coordinator detects version change in TaskStatus
public void onVersionChange(TaskId taskId, long newVersion) {
    long currentVersion = getCurrentVersion(taskId);
    if (newVersion <= currentVersion) return;

    // Long-poll for filter data
    Request request = prepareGet()
        .setUri("/v1/task/" + taskId + "/dynamicfilters")
        .setHeader("X-Presto-Current-Version", String.valueOf(currentVersion))
        .setHeader("X-Presto-Max-Wait", "1s")
        .build();

    httpClient.executeAsync(request, jsonCodec)
        .thenAccept(filters -> processCollectedFilters(taskId, filters));
}
```

**Endpoint** (Worker):

```
GET /v1/task/{taskId}/dynamicfilters
Headers:
  X-Presto-Current-Version: 0
  X-Presto-Max-Wait: 1s

Response:
{
  "version": 1,
  "filters": {
    "df_customer_id": "<binary>"
  }
}
```

**Benefits**:
- Single round-trip: Version change → fetch filters
- Long-polling: Worker waits up to maxWait, reducing overhead
- Incremental: Only returns NEW filters since currentVersion

#### 2.5 Filter Merging (Coordinator)

**Component**: `LocalDynamicFilter` (existing component)

**Location**: `presto-main/src/main/java/com/facebook/presto/sql/planner/LocalDynamicFilter.java`

**What it does**:
1. Receives `TupleDomain` filters from multiple build-side workers (for partitioned joins)
2. Merges filters using INTERSECTION semantics
3. Notifies waiting consumers when filter is complete

**Why use existing component?**

Since we're using `TupleDomain` for filters, we can reuse the existing `LocalDynamicFilter` which already handles:
- Tracking expected partition count
- Accumulating partial filters
- Merging via `TupleDomain.intersect()`
- Notifying consumers when complete

**Merge algorithm** (existing in TupleDomain):

```java
// TupleDomain.intersect() handles all cases:

// Case 1: Both have discrete values
Domain.multipleValues({1, 2, 5, 7}).intersect(Domain.multipleValues({2, 5, 8, 9}))
  → Domain.multipleValues({2, 5})

// Case 2: Both have ranges
Domain.range(10, 100).intersect(Domain.range(20, 90))
  → Domain.range(20, 90)

// Case 3: Mix of discrete values and range
Domain.multipleValues({15, 25, 35, 45}).intersect(Domain.range(20, 40))
  → Domain.multipleValues({25, 35})
```

**Integration with DynamicFilterService**:

`DynamicFilterService` uses `LocalDynamicFilter` instances (one per filter ID) to accumulate and merge partial TupleDomain filters, then distributes the merged result to consumers.

#### 2.6 Scheduler Integration (Split Scheduling Wait)

**Component**: `SourcePartitionedScheduler` (modified)

**Location**: `presto-main/src/main/java/com/facebook/presto/execution/scheduler/SourcePartitionedScheduler.java`

**What changes**: Add dynamic filter wait logic before calling `splitSource.getNextBatch()`

**Implementation**:

```java
// In SourcePartitionedScheduler.schedule() around line 220
if (scheduleGroup.pendingSplits.isEmpty()) {
    if (scheduleGroup.nextSplitBatchFuture == null) {

        // NEW: Check if this scan should wait for dynamic filters
        if (shouldWaitForDynamicFilter(scheduleGroup)) {
            scheduleGroup.nextSplitBatchFuture =
                scheduleWithDynamicFilterWait(scheduleGroup, lifespan);
        }
        else {
            // IMMEDIATE - fetch splits now
            scheduleGroup.nextSplitBatchFuture = splitSource.getNextBatch(...);
        }
    }
}

private boolean shouldWaitForDynamicFilter(ScheduleGroup scheduleGroup) {
    TableScanNode scan = getTableScanNode(scheduleGroup.planNodeId);
    return scan != null && scan.hasDynamicFilters();
}

private CompletableFuture<SplitBatch> scheduleWithDynamicFilterWait(
        ScheduleGroup scheduleGroup, Lifespan lifespan) {

    DynamicFilter dynamicFilter = getDynamicFilterFor(scheduleGroup);

    if (!dynamicFilter.isAwaitable()) {
        // Filter already complete or not expected
        return splitSource.getNextBatch(...);
    }

    // Create wait conditions
    CompletableFuture<?> filterReady = dynamicFilter.isBlocked();
    CompletableFuture<?> timeout = createTimeoutFuture(
        Duration.ofMillis(systemConfig.getDynamicFilterMaxWaitTime()));
    CompletableFuture<?> queueDrained = createQueueDrainFuture();

    // Wait for FIRST of: filter ready, timeout, or queue drain
    return CompletableFuture.anyOf(filterReady, timeout, queueDrained)
        .thenCompose(v -> splitSource.getNextBatch(...));
}
```

**Queue-depth awareness**:

```java
private CompletableFuture<?> createQueueDrainFuture() {
    // Get total queued splits across all tasks
    long totalQueuedWeight = stage.getAllTasks().stream()
        .mapToLong(task -> task.getTaskStatus().getQueuedPartitionedSplitsWeight())
        .sum();

    // Calculate threshold (e.g., 70% of total capacity)
    long capacity = maxPendingSplitsWeightPerTask * stage.getAllTasks().size();
    long threshold = (long) (capacity * 0.7);

    if (totalQueuedWeight < threshold) {
        return CompletableFuture.completedFuture(null);  // Space available
    }

    // Queue full - wait for it to drain below threshold
    return toWhenHasSplitQueueSpaceFuture(stage.getAllTasks(), (long) (threshold * 0.5));
}
```

**Why queue-depth awareness**:
- If probe-side queue is full, scheduling more splits wastes memory
- Better to wait for EITHER filter (reduces future work) OR queue drain (space for work)
- Self-regulating: adapts to actual execution speed

**Configuration**:

```properties
# Enable dynamic filtering
dynamic-filter.enabled=true

# Maximum time to wait for filter before scheduling splits
# Default: 0s (disabled, opt-in)
dynamic-filter.max-wait-time=0s

# Queue fullness threshold (0.0 to 1.0)
dynamic-filter.queue-threshold=0.7
```

**Session properties**:

```sql
set session dynamic_filter_max_wait_time='500ms';
set session dynamic_filter_queue_threshold='0.8';
```

#### 2.7 Filter Distribution (Coordinator → Workers)

**Component**: `DynamicFilterService` (same component from 2.5)

**Notification mechanism**:

**For Java workers**: In-memory via `DynamicFilter.isBlocked()` future
```java
// Probe-side worker
DynamicFilter filter = dynamicFilterService.createDynamicFilter(
    filterId,
    requiredColumns,
    partitionCount);

// Block until filter ready
filter.isBlocked().get();  // Unblocks when DynamicFilterService completes filter

// Now use filter
ComprehensiveRuntimeFilter runtimeFilter = filter.getFilter(columnHandle);
```

**For C++ workers**: Callback or periodic polling (implementation TBD)

**Output**: All probe-side workers have filter in memory

#### 2.8 Filter Application (Cascading Levels)

**Level 1-2: Partition/File Pruning** (Connector split source)

**Component**: Connector's `ConnectorSplitSource` implementation

**What changes**: Use filter when generating splits

```java
// Inside ConnectorSplitSource.getNextBatch() implementation
// (e.g., HiveSplitSource, IcebergSplitSource, DeltaSplitSource)
@Override
public CompletableFuture<ConnectorSplitBatch> getNextBatch(
        ConnectorPartitionHandle handle, int maxSize) {

    // Scheduler delayed calling this, so filter may be available
    TupleDomain<ColumnHandle> filter = dynamicFilter.getCurrentPredicate();

    if (filter.isNone()) {
        // Filter is impossible - no rows match
        return CompletableFuture.completedFuture(new ConnectorSplitBatch(emptyList(), true));
    }

    List<Partition> partitions = listPartitions(handle);

    // Use TupleDomain to test partition statistics
    partitions = partitions.stream()
        .filter(p -> filterMatchesPartition(filter, p))
        .collect(toList());

    return generateSplits(partitions, maxSize);
}

private boolean filterMatchesPartition(TupleDomain<ColumnHandle> filter, Partition p) {
    // Build TupleDomain from partition statistics
    TupleDomain<ColumnHandle> partitionDomain = TupleDomain.withColumnDomains(
        ImmutableMap.of(columnHandle,
            Domain.create(ValueSet.ofRanges(Range.range(p.getMin(), p.getMax())))));

    // Intersect: if result is NONE, partition can't match
    return !filter.intersect(partitionDomain).isNone();
}
```

**Applicable connectors**: Hive, Iceberg, Delta Lake, any connector with partitioned or bucketed tables

**How pruning works**:
- **Discrete values**: If filter has discrete values, intersect with partition's min/max range → if no overlap, skip partition
- **Range**: If filter has range, intersect with partition's min/max → if no overlap, skip partition

**Level 3: Row Group Pruning** (Velox)

**Component**: `ParquetReader.filterRowGroups()` in velox/dwio/parquet

**What changes**: Pass filter to Velox, let it call `testFilter()` on row group stats

```cpp
// Velox's ScanSpec::testFilter() implementation
bool testFilter(const dwio::common::ColumnStatistics& stats, const Filter* filter) const {
    // Velox only supports range-based tests
    return filter->testInt64Range(stats.getMinimum(), stats.getMaximum(), true);
}
```

**Limitation**: Velox's `testFilter()` only supports range tests, not bloom queries. Perfect pruning only works with distinct values.

**Level 4: Row Filtering** (Velox)

**Component**: Velox's existing Filter implementations

**What changes**: Convert TupleDomain to Velox Filter[^cascading-logic]

```cpp
// Convert TupleDomain to appropriate Velox Filter type
std::unique_ptr<Filter> createFilterFromTupleDomain(const TupleDomain& domain) {
    if (domain.hasDiscreteValues()) {
        // Create BigintValues filter (hash table, SIMD-accelerated)
        return std::make_unique<BigintValues>(domain.getDiscreteValues());
    } else {
        // Create BigintRange filter (min/max, SIMD-accelerated)
        return std::make_unique<BigintRange>(domain.getMin(), domain.getMax());
    }
}
```

#### 2.9 SIMD Optimization

**Purpose**: Accelerate row filtering (Level 4) using vectorized instructions

**Filter types and SIMD characteristics**[^simd-performance]:

| Filter Type | SIMD Benefit | Reason |
|-------------|--------------|--------|
| BigintValues (≤10K values) | 3-4x speedup | SIMD hash lookups |
| BigintRange (min/max) | 4-8x speedup | SIMD comparisons |

**Implementation**: Velox's existing Filter implementations already support SIMD via `xsimd::batch` interface

**Reality check**: Primary benefit is I/O reduction (600ms-1.5s) from partition/file/row group pruning, not SIMD optimization (17ms).

#### 2.10 Binary Serialization Format

**Purpose**: Cross-language compatibility (Java ↔ C++)

**Format**: Use existing TupleDomain JSON serialization[^binary-format-spec]

TupleDomain already has JSON codec support:
- Java: `JsonCodec<TupleDomain<ColumnHandle>>`
- C++: Parse JSON into equivalent TupleDomain structure

**Example**:
```json
{
  "columnDomains": {
    "customer_id": {
      "values": {
        "type": "discrete",
        "values": [1, 2, 5, 7, 12, 25, ...]
      }
    }
  }
}

```

Or for range-only:
```json
{
  "columnDomains": {
    "customer_id": {
      "values": {
        "type": "range",
        "ranges": [{"low": 1, "high": 100000}]
      }
    }
  }
}
```

**Size examples**:
- 1K values: ~8KB
- 10K values: ~80KB
- Range only: <1KB

#### 2.11 Module Organization

**presto-main** (coordinator):
- `AddDynamicFilterRule`: Optimizer rule (NEW)
- `DynamicFiltersFetcher`: Collects filters from workers (NEW)
- `LocalDynamicFilter`: Merges partial filters from multiple workers (existing, reused)
- `DynamicFilterService`: Distributes merged filters to consumers (existing, extended)
- `SourcePartitionedScheduler`: Scheduler waiting logic (modified)

**presto-spi** (interfaces):
- `DynamicFilter` interface: Reactive filter access (existing)
- `TupleDomain`: Filter structure (existing)
- `ConnectorSplitSource`: Extended with DynamicFilter parameter (existing)

**presto-native-execution** (C++ worker):
- `TupleDomainBuilder`: Build-side construction (NEW)
- `TupleDomainParser`: JSON deserialization (NEW)
- Velox Filter conversion: TupleDomain → BigintValues/BigintRange (NEW)

**presto-hive/iceberg/delta** (connectors):
- `HiveSplitSource`: Partition/file pruning with filters
- `IcebergSplitSource`: Manifest file pruning
- `DeltaSplitSource`: File pruning

**velox** (execution engine):
- `HashBuild`: Extract filters during hash table build
- `TableScan`: Accept filters for pushdown
- `ParquetReader`: Row group pruning

### 3. API/SPI Changes

**ConnectorSplitManager extension** (existing, no changes needed)[^spi-extensions]:

```java
public interface ConnectorSplitManager {
    default ConnectorSplitSource getSplits(
            ConnectorTransactionHandle transaction,
            ConnectorSession session,
            ConnectorTableLayoutHandle layout,
            SplitSchedulingStrategy splitSchedulingStrategy,
            DynamicFilter dynamicFilter) {  // Already exists!
        // Default: ignore dynamic filters for backward compatibility
        return getSplits(transaction, session, layout, splitSchedulingStrategy);
    }
}
```

**DynamicFilter interface** (existing, used for reactive access):

```java
public interface DynamicFilter {
    CompletableFuture<?> isBlocked();         // Unblocks when filter ready
    boolean isComplete();                     // True when all partitions collected
    boolean isAwaitable();                    // True if worth waiting
    Set<ColumnHandle> getColumnsCovered();
    TupleDomain<ColumnHandle> getCurrentPredicate();  // Get current filter
}
```

**TableScanNode extension** (add dynamic filter IDs):

```java
public class TableScanNode extends PlanNode {
    private final Set<DynamicFilterId> dynamicFilterIds;  // NEW

    public boolean hasDynamicFilters() {
        return !dynamicFilterIds.isEmpty();
    }
}
```

**No breaking changes**: All extensions use default methods or new fields.

### 4. User-Facing Configuration and Metrics

**Configuration**:

```properties
# System config
dynamic-filter.enabled=true
dynamic-filter.max-wait-time=0s
dynamic-filter.max-size=1MB
dynamic-filter.distinct-values-limit=10000
dynamic-filter.queue-threshold=0.7
```

**Session properties**:

```sql
set session dynamic_filter_max_wait_time='500ms';
set session dynamic_filter_max_size='2MB';
set session dynamic_filter_enabled=false;
```

**Metrics** (exposed in query JSON):

```json
{
  "dynamicFilters": {
    "produced": 3,
    "consumed": 3,
    "partitionsSkipped": 180,
    "partitionsScanned": 245,
    "filesSkipped": 850,
    "filesScanned": 1200,
    "rowsFiltered": 20000000,
    "rowsScanned": 25000000
  }
}
```

**CLI output**:

```
Dynamic Filters: 3 produced, 3 consumed
  - Partitions: 245 scanned, 180 skipped (73.5%)
  - Files: 1200 scanned, 850 skipped (70.8%)
  - Rows: 25M scanned, 20M filtered (80%)
```

## Metrics

### Success Criteria

**Performance improvements** (measured on TPC-DS queries with selective joins):
- 30-80% reduction in data scanned for star schema queries
- 2-5x faster query execution for selective fact-to-fact joins
- Query latency improvements: P50/P90/P99 reductions

**Overhead measurements** (for queries that don't benefit):
- Filter creation: < 50ms per filter
- Distribution: < 100ms coordinator overhead
- Network: < 20MB for 100 workers
- Memory: < 5MB per worker

### Measurement Plan

1. **Benchmark suite**: Run modified TPC-DS queries (Q1, Q3, Q7, Q19, Q42, Q52) with selective predicates
2. **A/B testing**: Enable feature for 10% of production traffic, compare with baseline
3. **Instrumentation**: Track all metrics mentioned above in query JSON
4. **Long-term monitoring**: Dashboard showing filter effectiveness over time

## Other Approaches Considered

### Alternative 1: Bloom Filters for High Cardinality

**Approach**: Use bloom filters (DataSketches) for joins with >10K distinct values.

**Pros**:
- Bounded memory (60KB for 50K values)
- Handles high cardinality joins efficiently
- Probabilistic pruning better than no pruning

**Cons**:
- Only helps at row filtering level (not partition/file/row group pruning)
- Bloom filters too conservative for definitive pruning (false positive rate)
- Adds significant complexity: DataSketches dependency, custom serialization, merge logic
- Only ~1.6x SIMD speedup vs 3-4x for hash tables
- Primary benefit is I/O reduction (partition/file pruning), which bloom doesn't help

**Why rejected for Phase 1**: Complexity outweighs benefit. For high-cardinality joins, range-based pruning (min/max) provides most of the I/O reduction at partition/file/row group levels. Bloom filters only benefit row filtering (~17ms saved vs ~1.4s from partition pruning).

### Alternative 2: Per-Connector Implementation

**Approach**: Let each connector implement dynamic filtering independently.

**Pros**:
- Connectors can optimize for their specific storage format
- No central coordinator logic needed
- Flexibility for connector-specific strategies

**Cons**:
- Code duplication across connectors
- Inconsistent behavior
- Harder to maintain and test
- Doesn't solve partitioned join problem (workers can't merge filters)

**Why rejected**: Centralized coordinator-based approach ensures consistent behavior and enables partitioned join support.

### Rationale for TupleDomain Approach

Using TupleDomain for Phase 1 provides significant benefits:
- **Reuses existing infrastructure**: No new serialization, merging, or data structures
- **Perfect pruning for common case**: Most star schema joins have ≤10K distinct values
- **Bounded memory**: Configurable limit prevents memory explosion
- **Simple**: Less code to write, test, and maintain
- **Future-proof**: Can add bloom filters in Phase 2 if high-cardinality joins prove important

## Adoption Plan

### Phase 1: Opt-In with Conservative Defaults (Weeks 1-4)

**Initial release**:
- Feature OFF by default (`dynamic_filter_max_wait_time=0s`)
- Requires explicit session property to enable
- Limited to Velox-based workers only
- Hive connector support (most common use case)

**User education**:
- Documentation: "When to enable dynamic partition pruning"
- Blog post: "Star schema optimization with dynamic filtering"
- Example queries demonstrating benefit

### Phase 2: Connector Expansion (Weeks 5-8)

**Additional connectors**:
- Iceberg support (manifest file pruning)
- Delta Lake support (file pruning)
- Parquet row group pruning (requires Velox extension)

**Performance tuning**:
- Gather production metrics
- Adjust defaults based on real-world data
- Add cost-based optimizer hints

### Phase 3: Gradual Rollout (Weeks 9-12)

**Progressive enablement**:
- Enable for 10% of traffic with `max_wait_time=500ms`
- Monitor for regressions (latency P99, failures)
- Increase to 50% if metrics look good
- Eventually make default for selective joins

**Escape hatch**:
- Kill switch: `dynamic_filter_enabled=false` (emergency disable)
- Per-query override via session property
- Automatic disable if filter creation fails

### Impact on Existing Users

**No breaking changes**:
- Feature is opt-in (0s default wait time)
- Existing queries run unchanged
- No SPI breaking changes (default methods provided)
- Backward compatible with Java workers (gracefully ignored)

### Migration Tools

**Not needed**: Feature is additive, no migration required.

**For optimal benefit**, users should:
1. Ensure tables are partitioned/bucketed on join columns (best practice already)
2. Review query plans to identify selective joins
3. Enable feature for specific workloads first
4. Monitor metrics dashboard

### Future Work (Out of Scope)

The following enhancements are explicitly deferred to future RFCs:

1. **Java worker support**: Extend filtering to Java-based execution
2. **Multi-column filters**: Support composite join keys
3. **Filter caching**: Persist filters across queries for repeated patterns
4. **Adaptive bloom sizing**: Dynamically adjust bloom filter size based on query statistics
5. **Cost-based timeout**: Automatically calculate wait time based on estimated build time
6. **Semi-join optimization**: Extend to IN subqueries and semi-joins

## Test Plan

### Unit Tests

**Core logic tests** (Java):
- `TestComprehensiveFilterBuilder`: Verify single-pass construction
- `TestFilterSerialization`: Validate binary format round-trip
- `TestFilterMerge`: Test coordinator merge of multiple filters
- `TestCascadingLogic`: Verify intelligent pruning at each level
- `TestSchedulerWait`: Verify scheduler delays splits correctly

**Core logic tests** (C++):
- `TestVeloxFilterBuilder`: Verify filter extraction from hash table
- `TestDataSketchesBloomFilter`: Validate bloom filter queries
- `TestSIMDFiltering`: Verify SIMD vectorization correctness

### Integration Tests

**End-to-end flow**:
- `TestDynamicPartitionPruning`: Full query with filter distribution
- `TestMultiPartitionJoin`: Hash table built across multiple workers
- `TestFilterTimeout`: Verify scheduler respects wait time
- `TestFilterFailure`: Ensure graceful degradation if filter creation fails
- `TestQueueDrainWakeup`: Verify scheduler wakes on queue drain

**Connector integration**:
- `TestHivePartitionPruning`: Verify partition-level filtering
- `TestParquetFilePruning`: Verify file-level filtering
- `TestIcebergManifestPruning`: Verify Iceberg integration

### Performance Tests

**TPC-DS benchmark suite** (modified queries):

```sql
-- Q1 modified: Selective dimension filter
SELECT ... FROM store_sales ss
JOIN date_dim d ON ss.ss_sold_date_sk = d.d_date_sk
WHERE d.d_year = 2000 AND d.d_moy = 12;  -- 31 days only

-- Expected: 99% partition pruning, 10x speedup
```

**Micro-benchmarks**:
- Filter creation time vs cardinality
- Serialization/deserialization overhead
- SIMD performance (hash table vs bloom vs range)
- Network distribution latency

### Compatibility Tests

**Cross-language correctness**:
- Java serializes filter → C++ deserializes → queries work correctly
- C++ serializes filter → Java coordinator merges → C++ deserializes
- DataSketches bloom filter round-trip (Java ↔ C++)

### Product Tests

**New product test suite** (`presto-product-tests`):
- `testStarSchemaWithDynamicFilter`: Verify E2E on multi-node cluster
- `testHighCardinalityJoin`: Bloom filter with 1M distinct values
- `testLowCardinalityJoin`: Perfect pruning with 100 distinct values
- `testMixedCardinality`: Multiple filters with different cardinalities
- `testSchedulerWaiting`: Verify scheduler delays and wakes correctly

### PoC Results

**Preliminary testing** (internal cluster, modified TPC-DS):

| Query | Baseline | With Dynamic Filtering | Speedup | Data Scanned |
|-------|----------|------------------------|---------|--------------|
| Q1 | 45s | 8s | 5.6x | 95% reduced |
| Q3 | 120s | 35s | 3.4x | 70% reduced |
| Q19 | 80s | 22s | 3.6x | 75% reduced |

**Observations**:
- Low cardinality joins (≤10K values): 4-6x speedup, >90% I/O reduction
- Medium cardinality joins (10K-100K): 2-4x speedup, 60-80% I/O reduction
- High cardinality joins (>100K): 1.5-2x speedup, 30-50% I/O reduction
- Queries without selective joins: No measurable overhead (<1% variance)

---

## Footnotes

[^comprehensive-filter-structure]: **Runtime Filter Structure (Java)**

    ```java
    public class ComprehensiveRuntimeFilter implements RuntimeFilter {
        private final long min;
        private final long max;
        private final LongHashSet distinctValues;  // null or up to 10K values
        private final BloomFilter bloom;            // null if cardinality <= 10K
        private final long actualCardinality;

        // Intelligent test method with cascading logic
        public boolean test(long value) {
            if (value < min || value > max) return false;
            if (hasCompleteDistinctValues()) {
                return distinctValues.contains(value);
            }
            if (distinctValues != null && distinctValues.contains(value)) {
                return true;
            }
            if (bloom != null) {
                return bloom.query(value);
            }
            return true;  // Conservative fallback
        }

        private boolean hasCompleteDistinctValues() {
            return distinctValues != null && actualCardinality <= distinctValues.size();
        }
    }
    ```

[^filter-builder-impl]: **Filter Builder Implementation (C++)**

    ```cpp
    class ComprehensiveFilterBuilder {
        int64_t min_ = std::numeric_limits<int64_t>::max();
        int64_t max_ = std::numeric_limits<int64_t>::min();
        folly::F14FastSet<int64_t> distinctValues_;
        std::unique_ptr<datasketches::bloom_filter_builder> bloomBuilder_;
        static constexpr int kDistinctValuesLimit = 10'000;

        void addValue(int64_t value) {
            min_ = std::min(min_, value);
            max_ = std::max(max_, value);

            if (distinctValues_.size() < kDistinctValuesLimit) {
                distinctValues_.insert(value);
            } else if (!bloomBuilder_) {
                // Just exceeded limit - initialize bloom
                bloomBuilder_ = std::make_unique<bloom_filter_builder>(
                    estimatedCardinality_, 0.01);
                for (int64_t v : distinctValues_) {
                    bloomBuilder_->update(v);
                }
            }

            if (bloomBuilder_) {
                bloomBuilder_->update(value);
            }
        }
    };
    ```

[^binary-format-spec]: **Binary Serialization Format**

    ```
    Runtime Filter Binary Format:
    ┌──────────────────────────────────────────────────────┐
    │ [1 byte]  Filter Type = COMPREHENSIVE (4)            │
    │ [4 bytes] Filter ID Length                           │
    │ [N bytes] Filter ID (UTF-8)                          │
    │ [4 bytes] Type Signature Length                      │
    │ [M bytes] Type Signature (UTF-8)                     │
    │ [8 bytes] Actual Cardinality                         │
    │                                                       │
    │ Component 1: Min/Max (always, 16 bytes)              │
    │ [8 bytes] Min Value                                  │
    │ [8 bytes] Max Value                                  │
    │                                                       │
    │ Component 2: Distinct Values (optional)              │
    │ [4 bytes] Count (0 if not present)                   │
    │ [D*8]     Values Array (sorted)                      │
    │                                                       │
    │ Component 3: Bloom Filter (optional)                 │
    │ [1 byte]  Has Bloom? (0/1)                           │
    │ [4 bytes] Bloom Length                               │
    │ [B bytes] DataSketches Blob                          │
    └──────────────────────────────────────────────────────┘
    ```

[^distribution-protocol]: **Version-Based Distribution Protocol**

    ```java
    // Worker: Increment version when filter ready
    void HashBuild::noMoreInput() {
        auto filter = extractFilter();
        task_->addDynamicFilter(filterId_, filter);
        task_->incrementDynamicFiltersVersion();
    }

    // Coordinator: Long-poll for filters
    public void onVersionChange(TaskId taskId, long newVersion) {
        Request request = prepareGet()
            .setUri("/v1/task/" + taskId + "/dynamicfilters")
            .setHeader("X-Presto-Current-Version", String.valueOf(currentVersion))
            .setHeader("X-Presto-Max-Wait", "1s")
            .build();

        httpClient.executeAsync(request, jsonCodec)
            .thenAccept(filters -> processFilters(taskId, filters));
    }
    ```

[^cascading-logic]: **Cascading Filter Application Logic**

    ```java
    // Partition/File pruning
    boolean testFile(FileStatistics stats, ComprehensiveRuntimeFilter filter) {
        // Step 1: Range test
        if (stats.getMax() < filter.getMin() || stats.getMin() > filter.getMax()) {
            return false;  // SKIP - no overlap
        }

        // Step 2: Perfect test with distinct values
        if (filter.hasCompleteDistinctValues()) {
            for (long value : filter.getDistinctValues()) {
                if (value >= stats.getMin() && value <= stats.getMax()) {
                    return true;
                }
            }
            return false;  // SKIP - no values in range
        }

        // Step 3: Conservative with bloom
        return true;  // Can't definitively prune
    }
    ```

[^simd-performance]: **SIMD Performance Characteristics**

    ```cpp
    // Hash table: True SIMD (3-4x speedup)
    xsimd::batch_bool<int64_t> testHashTable(xsimd::batch<int64_t> values) {
        // SIMD hash lookups possible - true vectorization
        return testValuesAgainstHashSet(values, distinctValues_);
    }

    // Bloom filter: Pseudo-SIMD (~1.6x speedup)
    xsimd::batch_bool<int64_t> testBloom(xsimd::batch<int64_t> values) {
        constexpr int N = xsimd::batch<int64_t>::size;
        alignas(32) int64_t data[N];
        alignas(32) int64_t results[N];

        values.store_aligned(data);

        // Queries are SCALAR - not vectorized!
        for (int i = 0; i < N; ++i) {
            results[i] = bloom_.query(data[i]) ? -1 : 0;
        }

        auto resultBits = xsimd::load_aligned(results);
        return resultBits != xsimd::broadcast<int64_t>(0);
    }
    ```

[^spi-extensions]: **SPI Method Signatures**

    ```java
    // ConnectorSplitManager extension (already exists)
    public interface ConnectorSplitManager {
        default ConnectorSplitSource getSplits(
                ConnectorTransactionHandle transaction,
                ConnectorSession session,
                ConnectorTableLayoutHandle layout,
                SplitSchedulingStrategy splitSchedulingStrategy,
                DynamicFilter dynamicFilter) {
            // Default: ignore dynamic filters for backward compatibility
            return getSplits(transaction, session, layout, splitSchedulingStrategy);
        }
    }

    // DynamicFilter interface (already exists)
    public interface DynamicFilter {
        CompletableFuture<?> isBlocked();
        boolean isComplete();
        boolean isAwaitable();
        Set<ColumnHandle> getColumnsCovered();
        RuntimeFilter getFilter(ColumnHandle column);
    }
    ```
