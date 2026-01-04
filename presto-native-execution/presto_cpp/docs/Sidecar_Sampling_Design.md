# Sidecar-Based Sampling for Join Cardinality Estimation

## Overview

This document describes a design for using the Presto native sidecar to perform table sampling during query optimization, enabling more accurate join cardinality estimation. The approach is inspired by Axiom's statistics collection system but adapted to work within Presto's existing CBO infrastructure.

## Motivation

Presto's current Cost-Based Optimizer (CBO) relies on connector-provided statistics for join ordering decisions. The `JoinStatsRule` uses NDV (Number of Distinct Values) from table statistics to estimate join selectivity. However:

1. **NDV-based estimation assumes uniform distribution** - Real data often has skewed key distributions
2. **Connector statistics may be stale or unavailable** - Not all connectors provide accurate statistics
3. **Join fanout is not directly measured** - The actual match ratio between tables is estimated, not observed

Sampling-based statistics collection can provide empirical measurements of join behavior, leading to better join ordering decisions.

## Background: Axiom's Approach

Axiom collects two types of sampling-based statistics during optimization:

### Join Fanout Sampling
- **Trigger:** Per join edge during optimizer initialization
- **Method:** Hash-based sampling query: `SELECT hash(key1, key2, ...) FROM table WHERE (hash % 10000) < fraction`
- **Output:** Hash frequency maps for each table, compared to compute bidirectional fanout
- **Cache Key:** Canonical string like `"table1 col1 col2 table2 col1 col2"` (filters excluded)

### Filter Selectivity Sampling
- **Trigger:** During TableLayout construction
- **Method:** 1% sample counting rows before/after filter application
- **Output:** Selectivity ratio (0.0 to 1.0)
- **Cache Key:** Full table handle including filters

### Three-Cache Architecture
| Cache | Key Format | Value | Purpose |
|-------|------------|-------|---------|
| Filter Selectivity | `HiveTableHandle::toString()` with filters | `float` | Row filtering impact |
| Join Sample | `"table1 col1 table2 col1"` (no filters) | `(lr_fanout, rl_fanout)` | Join match ratios |
| Plan Node History | Recursive canonical plan hash | `NodePrediction` | Full subtree statistics |

## Presto's Existing Infrastructure

### Statistics Flow
```
TableScanNode
    → TableScanStatsRule.doCalculate() [TableScanStatsRule.java:56]
    → Metadata.getTableStatistics()
    → Connector provides TableStatistics
    → PlanNodeStatsEstimate built with row count, column stats
```

### Join Statistics Calculation
```
JoinNode
    → JoinStatsRule.doCalculate() [JoinStatsRule.java:86]
    → Get left/right stats from StatsProvider
    → Compute cross-join stats
    → Apply equi-join selectivity based on NDV/range intersection
    → Fall back to DEFAULT_JOIN_SELECTIVITY_COEFFICIENT if unknown
```

### Optimization Timing
1. Logical optimization (no stats)
2. `PickTableLayout` rule applies [PlanOptimizers.java:320] - **layouts assigned**
3. Iterative optimization with `StatsCalculator` [PlanOptimizers.java:327+] - **CBO runs**
4. `ReorderJoins` enumerates join orders using `CostComparator`

**Key Finding:** Table layouts ARE available during CBO, so sampling integration is feasible at the statistics rule level.

### Native Sidecar Endpoints
Current endpoints in `PrestoServer.cpp:1757-1817`:
- `GET /v1/properties/session` - Session properties
- `GET /v1/functions` - Function metadata
- `POST /v1/expressions` - Expression optimization
- `POST /v1/velox/plan` - Plan conversion/validation

## Proposed Design

### Architecture Overview

```
┌─────────────────────────────────────────────────────────────────────┐
│                         COORDINATOR                                  │
│  ┌─────────────────────────────────────────────────────────────┐   │
│  │                    Query Optimizer                            │   │
│  │  ┌─────────────────┐    ┌──────────────────────────────┐    │   │
│  │  │ TableScanStats  │    │      JoinSamplingStats       │    │   │
│  │  │     Rule        │    │          Rule                │    │   │
│  │  └────────┬────────┘    └──────────────┬───────────────┘    │   │
│  │           │                            │                      │   │
│  │           v                            v                      │   │
│  │  ┌─────────────────────────────────────────────────────────┐ │   │
│  │  │              SidecarSamplingClient                      │ │   │
│  │  │  - sampleTable(table, columns, fraction, constraint)    │ │   │
│  │  │  - sampleJoinKeys(tables, joinColumns, fraction)        │ │   │
│  │  └────────────────────────┬────────────────────────────────┘ │   │
│  └───────────────────────────│──────────────────────────────────┘   │
│                              │                                       │
│  ┌───────────────────────────v──────────────────────────────────┐   │
│  │                   SampleStatsCache                            │   │
│  │  ┌─────────────────┐  ┌─────────────────┐  ┌──────────────┐  │   │
│  │  │ FilterSelectivity│  │  JoinSample    │  │ StoredSample │  │   │
│  │  │     Cache        │  │    Cache       │  │   Reader     │  │   │
│  │  └─────────────────┘  └─────────────────┘  └──────────────┘  │   │
│  └───────────────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────────────┘
                                   │
                                   │ HTTP POST /v1/sample
                                   v
┌─────────────────────────────────────────────────────────────────────┐
│                       NATIVE SIDECAR                                 │
│  ┌───────────────────────────────────────────────────────────────┐  │
│  │                    SamplingEndpoint                            │  │
│  │  - Parse sampling request                                      │  │
│  │  - Build Velox table scan with sampling filter                 │  │
│  │  - Execute via Velox TableScan operator                        │  │
│  │  - Compute statistics (row count, NDV, hash frequency map)     │  │
│  │  - Return SampleResponse                                       │  │
│  └───────────────────────────────────────────────────────────────┘  │
│                              │                                       │
│                              v                                       │
│  ┌───────────────────────────────────────────────────────────────┐  │
│  │                   Velox Execution                              │  │
│  │  - Connector table scan                                        │  │
│  │  - Hash-based row filtering                                    │  │
│  │  - Aggregation for statistics                                  │  │
│  └───────────────────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────────────────┘
```

### New Sidecar Endpoint: `/v1/sample`

#### Request Format
```json
{
  "requestType": "TABLE_SAMPLE" | "JOIN_SAMPLE",
  "tables": [
    {
      "catalogName": "hive",
      "schemaName": "default",
      "tableName": "orders",
      "columns": ["orderkey", "custkey"],
      "constraint": { ... }  // TupleDomain representation
    }
  ],
  "sampleFraction": 0.01,
  "joinColumns": [  // Only for JOIN_SAMPLE
    {"left": "custkey", "right": "custkey"}
  ],
  "options": {
    "computeHashFrequency": true,
    "maxSampleRows": 1000000
  }
}
```

#### Response Format
```json
{
  "success": true,
  "tableSamples": [
    {
      "tableName": "hive.default.orders",
      "sampledRowCount": 15000,
      "estimatedTotalRowCount": 1500000,
      "columnStatistics": {
        "orderkey": {
          "distinctValuesCount": 14850,
          "nullsFraction": 0.0,
          "minValue": 1,
          "maxValue": 6000000
        },
        "custkey": {
          "distinctValuesCount": 9950,
          "nullsFraction": 0.0,
          "minValue": 1,
          "maxValue": 150000
        }
      },
      "hashFrequencyMap": {
        "12345678": 3,
        "23456789": 1,
        ...
      }
    }
  ],
  "joinFanout": {  // Only for JOIN_SAMPLE
    "leftToRightFanout": 1.5,
    "rightToLeftFanout": 0.67,
    "matchingFraction": 0.85
  },
  "executionTimeMs": 245
}
```

### C++ Implementation Sketch

```cpp
// In PrestoServer.cpp, add to registerSidecarEndpoints():

httpServer_->registerPost(
    "/v1/sample",
    [this](
        proxygen::HTTPMessage* message,
        const std::vector<std::unique_ptr<folly::IOBuf>>& body,
        proxygen::ResponseHandler* downstream) {

      auto requestJson = util::extractMessageBody(body);
      auto request = protocol::SampleRequest::fromJson(requestJson);

      // Execute sampling query via Velox
      auto result = executeSamplingQuery(
          request,
          nativeWorkerPool_.get(),
          driverExecutor_.get());

      http::sendOkResponse(downstream, json(result));
    });

// New file: SamplingHandler.cpp
protocol::SampleResponse executeSamplingQuery(
    const protocol::SampleRequest& request,
    folly::CPUThreadPoolExecutor* executor,
    velox::memory::MemoryPool* pool) {

  // Build Velox plan for sampling
  auto tableScan = buildSamplingTableScan(
      request.table,
      request.columns,
      request.sampleFraction,
      request.constraint);

  // Add aggregation for statistics collection
  auto aggregation = buildStatsAggregation(tableScan, request.columns);

  // Execute and collect results
  auto task = velox::exec::Task::create(...);
  // ... execute and gather statistics

  return buildSampleResponse(results);
}
```

### Java Integration

#### SidecarSamplingClient
```java
// New class in presto-native-sidecar-plugin
public class SidecarSamplingClient {
    private final NodeManager nodeManager;
    private final OkHttpClient httpClient;
    private final JsonCodec<SampleRequest> requestCodec;
    private final JsonCodec<SampleResponse> responseCodec;

    public CompletableFuture<SampleResponse> sampleTable(
            TableHandle table,
            List<ColumnHandle> columns,
            double sampleFraction,
            TupleDomain<ColumnHandle> constraint) {

        SampleRequest request = SampleRequest.builder()
            .requestType(RequestType.TABLE_SAMPLE)
            .addTable(table, columns, constraint)
            .sampleFraction(sampleFraction)
            .build();

        return executeAsync(request);
    }

    public CompletableFuture<JoinFanoutResult> sampleJoinFanout(
            TableHandle leftTable, List<ColumnHandle> leftColumns,
            TableHandle rightTable, List<ColumnHandle> rightColumns,
            List<JoinColumn> joinColumns,
            double sampleFraction) {

        SampleRequest request = SampleRequest.builder()
            .requestType(RequestType.JOIN_SAMPLE)
            .addTable(leftTable, leftColumns)
            .addTable(rightTable, rightColumns)
            .joinColumns(joinColumns)
            .sampleFraction(sampleFraction)
            .computeHashFrequency(true)
            .build();

        return executeAsync(request)
            .thenApply(SampleResponse::getJoinFanout);
    }
}
```

#### SamplingStatsRule Integration
```java
// Option 1: New rule that supplements existing stats
public class SamplingEnhancedTableScanStatsRule
        extends SimpleStatsRule<TableScanNode> {

    private final Metadata metadata;
    private final SidecarSamplingClient samplingClient;
    private final SampleStatsCache cache;

    @Override
    protected Optional<PlanNodeStatsEstimate> doCalculate(
            TableScanNode node,
            StatsProvider sourceStats,
            Lookup lookup,
            Session session,
            TypeProvider types) {

        // First, get connector statistics (existing behavior)
        TableStatistics connectorStats = metadata.getTableStatistics(...);

        // Check if sampling is enabled and beneficial
        if (!isSamplingEnabled(session) || hasGoodStats(connectorStats)) {
            return buildEstimate(connectorStats);
        }

        // Check cache
        String cacheKey = buildCacheKey(node);
        Optional<SampleStats> cached = cache.get(cacheKey);
        if (cached.isPresent()) {
            return mergeWithConnectorStats(connectorStats, cached.get());
        }

        // Check for stored samples table
        Optional<SampleStats> stored = readStoredSample(node);
        if (stored.isPresent()) {
            cache.put(cacheKey, stored.get());
            return mergeWithConnectorStats(connectorStats, stored.get());
        }

        // Execute live sampling (async with timeout)
        try {
            SampleResponse response = samplingClient
                .sampleTable(node.getTable(), ...)
                .get(getSamplingTimeout(session), TimeUnit.MILLISECONDS);

            cache.put(cacheKey, response.toSampleStats());
            return mergeWithConnectorStats(connectorStats, response);
        } catch (TimeoutException e) {
            // Fall back to connector stats
            return buildEstimate(connectorStats);
        }
    }
}
```

#### JoinSamplingStatsRule
```java
public class JoinSamplingStatsRule extends SimpleStatsRule<JoinNode> {

    @Override
    protected Optional<PlanNodeStatsEstimate> doCalculate(
            JoinNode node,
            StatsProvider sourceStats,
            Lookup lookup,
            Session session,
            TypeProvider types) {

        // Extract base tables from both sides
        Set<TableScanNode> leftTables = extractTableScans(node.getLeft());
        Set<TableScanNode> rightTables = extractTableScans(node.getRight());

        // Build canonical join key (excluding filters, like Axiom)
        String joinKey = buildCanonicalJoinKey(
            leftTables, rightTables, node.getCriteria());

        // Check cache
        Optional<JoinFanout> cached = joinSampleCache.get(joinKey);
        if (cached.isPresent()) {
            return applyFanoutToStats(cached.get(), sourceStats, node);
        }

        // Sample both sides and compute fanout
        JoinFanoutResult fanout = samplingClient.sampleJoinFanout(
            leftTables, extractJoinColumns(node.getCriteria(), LEFT),
            rightTables, extractJoinColumns(node.getCriteria(), RIGHT),
            node.getCriteria(),
            getSampleFraction(session)
        ).get(timeout);

        joinSampleCache.put(joinKey, fanout);

        // Apply fanout with filter selectivity adjustment
        return applyFanoutToStats(fanout, sourceStats, node);
    }

    private PlanNodeStatsEstimate applyFanoutToStats(
            JoinFanout fanout,
            StatsProvider sourceStats,
            JoinNode node) {

        PlanNodeStatsEstimate leftStats = sourceStats.getStats(node.getLeft());
        PlanNodeStatsEstimate rightStats = sourceStats.getStats(node.getRight());

        // Effective fanout = sampled fanout × filter selectivity
        // (Independence assumption, same as Axiom)
        double leftSelectivity = estimateFilterSelectivity(node.getLeft());
        double rightSelectivity = estimateFilterSelectivity(node.getRight());

        double adjustedLeftRows = leftStats.getOutputRowCount() * leftSelectivity;
        double outputRows = adjustedLeftRows * fanout.getLeftToRightFanout();

        return PlanNodeStatsEstimate.builder()
            .setOutputRowCount(outputRows)
            // ... build full estimate
            .build();
    }
}
```

### Caching Strategy

#### Key Design Principle: Separation of Join Containment and Filter Selectivity

Following Axiom's approach, we cache **join fanout** and **filter selectivity** as independent variables. This is critical for cache efficiency:

```
┌─────────────────────────────────────────────────────────────────────────┐
│                    INDEPENDENT CACHING PRINCIPLE                         │
├─────────────────────────────────────────────────────────────────────────┤
│                                                                          │
│  Join Fanout Cache (filters EXCLUDED from key):                          │
│  ┌─────────────────────────────────────────────────────────────────┐    │
│  │  "orders:custkey | customer:custkey"  →  (lr: 1.0, rl: 15.2)    │    │
│  │  "lineitem:orderkey | orders:orderkey" →  (lr: 4.2, rl: 1.0)    │    │
│  └─────────────────────────────────────────────────────────────────┘    │
│                                                                          │
│  Filter Selectivity Cache (predicate-specific):                          │
│  ┌─────────────────────────────────────────────────────────────────┐    │
│  │  "orders[total > 1000]"           →  0.15                       │    │
│  │  "orders[status = 'PENDING']"     →  0.08                       │    │
│  │  "customer[nation = 'US']"        →  0.22                       │    │
│  └─────────────────────────────────────────────────────────────────┘    │
│                                                                          │
│  At query time, COMBINE:                                                 │
│  ┌─────────────────────────────────────────────────────────────────┐    │
│  │  output_rows = left_rows × left_selectivity ×                    │    │
│  │                right_selectivity × join_fanout                   │    │
│  └─────────────────────────────────────────────────────────────────┘    │
│                                                                          │
└─────────────────────────────────────────────────────────────────────────┘
```

**Why this matters for cache coverage:**

| Scenario | Combined Cache | Separated Caches |
|----------|---------------|------------------|
| 10 filters on `orders` | - | 10 selectivity entries |
| 5 join patterns with `orders` | - | 5 fanout entries |
| All combinations | 50 entries needed | 15 entries (10 + 5) |
| New filter added | 5 new entries | 1 new entry |
| New join added | 10 new entries | 1 new entry |

**Cache warm-up acceleration:**
- Query 1: `orders JOIN customer WHERE orders.total > 1000`
  - Caches: `orders:custkey|customer:custkey` fanout + `orders[total>1000]` selectivity
- Query 2: `orders JOIN customer WHERE orders.status = 'PENDING'`
  - Reuses: `orders:custkey|customer:custkey` fanout (cache HIT)
  - Caches: `orders[status='PENDING']` selectivity
- Query 3: `orders JOIN lineitem WHERE orders.total > 1000`
  - Reuses: `orders[total>1000]` selectivity (cache HIT)
  - Caches: `orders:orderkey|lineitem:orderkey` fanout

With combined caching, all three queries would require full sampling. With separated caching, queries 2 and 3 each get partial cache hits.

#### Independence Assumption

This approach assumes filter selectivity and join behavior are **statistically independent**:
- Filtering on `orders.total > 1000` doesn't change which `custkey` values are present
- The join fanout between filtered orders and customers equals base fanout × selectivity

**When this breaks down:**
- Filter on join key: `orders WHERE custkey < 1000` - affects join containment
- Correlated filters: `orders WHERE region = 'US'` joined with `customers WHERE country = 'US'`

For correlated cases, the separated caches may underestimate or overestimate. Future work could detect correlations and fall back to combined sampling.

#### Cache Hierarchy
```
┌─────────────────────────────────────────────────────────────┐
│                    SampleStatsCache                          │
├─────────────────────────────────────────────────────────────┤
│  Level 1: Session-scoped in-memory cache                    │
│  - Fast lookup for repeated access within query             │
│  - Evicted at session end                                   │
├─────────────────────────────────────────────────────────────┤
│  Level 2: HBO Provider integration                          │
│  - Uses existing HistoryBasedPlanStatisticsProvider         │
│  - Persistent across queries                                │
│  - TTL-based invalidation                                   │
├─────────────────────────────────────────────────────────────┤
│  Level 3: Stored Samples Table (optional)                   │
│  - Pre-computed samples in catalog table                    │
│  - Updated by background job or ANALYZE                     │
│  - Fallback when live sampling unavailable                  │
└─────────────────────────────────────────────────────────────┘
```

#### Cache Key Canonicalization

**Filter Selectivity Key:**
```
catalog.schema.table|constraint_hash
Example: "hive.default.orders|a1b2c3d4"
```

**Join Sample Key (filters excluded, like Axiom):**
```
table1:col1,col2|table2:col1,col2  (sorted lexicographically)
Example: "hive.default.customer:custkey|hive.default.orders:custkey"
```

### Session Properties

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `sidecar_sampling_enabled` | boolean | false | Enable sampling-based statistics |
| `sidecar_sampling_fraction` | double | 0.01 | Default sample fraction (1%) |
| `sidecar_sampling_timeout_ms` | long | 5000 | Timeout for sampling requests |
| `sidecar_sampling_min_table_rows` | long | 10000 | Min rows to trigger sampling |
| `sidecar_sampling_cache_ttl_minutes` | long | 60 | Cache entry TTL |
| `sidecar_sampling_use_stored_samples` | boolean | true | Check stored samples table |

### Stored Samples Table Schema

```sql
CREATE TABLE system.sampling.stored_samples (
    catalog_name VARCHAR,
    schema_name VARCHAR,
    table_name VARCHAR,
    column_names ARRAY(VARCHAR),
    sample_timestamp TIMESTAMP,
    sample_fraction DOUBLE,
    sampled_row_count BIGINT,
    estimated_total_rows BIGINT,
    column_statistics JSON,  -- Serialized column stats
    hash_frequency_map VARBINARY,  -- Compressed frequency map
    PRIMARY KEY (catalog_name, schema_name, table_name, column_names)
);
```

## Approximate Data Structures for Join Fanout

Computing join fanout requires tracking both **key membership** (which keys exist) and **key frequency** (how many rows per key). For distributed/mergeable sampling, we need approximate data structures.

**Critical:** Join fanout sketches are built on **unfiltered base tables**. Filter selectivity is computed and cached separately. This enables maximum cache reuse across queries with different predicates (see "Separation of Join Containment and Filter Selectivity" above).

### Requirements

For join fanout computation:
- **Left-to-right fanout** = Σ(right_freq[k] for k in intersection) / |left_keys|
- **Right-to-left fanout** = Σ(left_freq[k] for k in intersection) / |right_keys|

We need structures that:
1. Track distinct keys (for intersection)
2. Track frequency of each key
3. Are mergeable (for distributed sampling)
4. Have bounded memory

### Available in Presto

| Structure | Purpose | Mergeable | Tracks Frequency |
|-----------|---------|-----------|------------------|
| **Theta Sketch** | Distinct count, set intersection | ✅ | ❌ |
| **KHyperLogLog** | Jaccard index, intersection cardinality | ✅ | ❌ |
| **KLL Sketch** | Quantile estimation | ✅ | ❌ |
| **HyperLogLog** | Distinct count | ✅ | ❌ |

**Gap:** None of these track key *frequency*, which is required for fanout.

### Recommended: Theta Sketch + Count-Min Sketch Hybrid

```
┌─────────────────────────────────────────────────────────────────────┐
│                      JoinFanoutSketch                               │
├─────────────────────────────────────────────────────────────────────┤
│  ┌──────────────────────────┐  ┌────────────────────────────────┐  │
│  │      Theta Sketch        │  │       Count-Min Sketch         │  │
│  │   (Apache DataSketches)  │  │                                │  │
│  │                          │  │  ┌───┬───┬───┬───┬───┬───┐    │  │
│  │  - Distinct key count    │  │  │ 3 │ 0 │ 5 │ 1 │ 2 │...│ d1 │  │
│  │  - Set intersection      │  │  ├───┼───┼───┼───┼───┼───┤    │  │
│  │  - Retained hash values  │  │  │ 1 │ 4 │ 0 │ 3 │ 1 │...│ d2 │  │
│  │  - Jaccard estimation    │  │  ├───┼───┼───┼───┼───┼───┤    │  │
│  │                          │  │  │ 2 │ 1 │ 3 │ 0 │ 4 │...│ d3 │  │
│  │  O(k) memory             │  │  └───┴───┴───┴───┴───┴───┘    │  │
│  │  k = nominal entries     │  │  width × depth counters       │  │
│  └──────────────────────────┘  └────────────────────────────────┘  │
│                                                                     │
│  totalRowCount: long        // Total rows observed                  │
│  sampleTheta: double        // Sampling rate for extrapolation      │
└─────────────────────────────────────────────────────────────────────┘
```

#### Component Roles

**Theta Sketch:**
- Provides distinct key estimation
- Enables set intersection between left and right join keys
- Returns retained hash values to iterate over sampled keys
- Uses `org.apache.datasketches.theta.*` (already in Presto)

**Count-Min Sketch:**
- Provides frequency estimation for any key
- `update(key)` increments counters at hash positions
- `estimateCount(key)` returns minimum of counters at hash positions
- Mergeable via element-wise addition
- Standard implementation: width=2048, depth=4 gives <1% error

#### Fanout Computation Algorithm

```python
def compute_fanout(left_sketch: JoinFanoutSketch,
                   right_sketch: JoinFanoutSketch) -> (float, float):
    """
    Compute bidirectional join fanout using approximate sketches.
    """
    # Step 1: Compute intersection using Theta Sketches
    intersection = left_sketch.theta.intersect(right_sketch.theta)
    intersection_size = intersection.getEstimate()

    if intersection_size == 0:
        return (0.0, 0.0)

    # Step 2: Estimate total join output rows
    # For each key in intersection sample, estimate contribution
    total_join_rows = 0
    sampled_keys = intersection.getRetainedHashValues()

    for key_hash in sampled_keys:
        left_freq = left_sketch.count_min.estimate(key_hash)
        right_freq = right_sketch.count_min.estimate(key_hash)
        total_join_rows += left_freq * right_freq

    # Step 3: Scale up based on theta (accounts for sampling)
    # Theta represents the fraction of key space retained
    scale_factor = 1.0 / intersection.getTheta()
    estimated_join_rows = total_join_rows * scale_factor

    # Step 4: Compute fanouts
    left_distinct = left_sketch.theta.getEstimate()
    right_distinct = right_sketch.theta.getEstimate()

    left_to_right = estimated_join_rows / left_sketch.total_rows
    right_to_left = estimated_join_rows / right_sketch.total_rows

    return (left_to_right, right_to_left)
```

#### Merging Distributed Sketches

```python
def merge(sketch1: JoinFanoutSketch,
          sketch2: JoinFanoutSketch) -> JoinFanoutSketch:
    """
    Merge two sketches from different partitions/workers.
    """
    merged = JoinFanoutSketch()

    # Theta Sketch: union operation
    merged.theta = Union.builder().buildUnion()
    merged.theta.union(sketch1.theta)
    merged.theta.union(sketch2.theta)

    # Count-Min Sketch: element-wise addition
    merged.count_min = CountMinSketch(width, depth)
    for i in range(depth):
        for j in range(width):
            merged.count_min[i][j] = (
                sketch1.count_min[i][j] + sketch2.count_min[i][j]
            )

    # Sum row counts
    merged.total_rows = sketch1.total_rows + sketch2.total_rows

    return merged
```

### Alternative: Apache DataSketches Tuple Sketch

DataSketches provides `TupleSketch<S>` which associates a custom summary with each key:

```java
// Tuple Sketch with frequency summary
UpdateSketch<IntegerSummary> sketch = new UpdateSketchBuilder<>(
    new IntegerSummaryFactory(IntegerSummary.Mode.Sum)
).build();

// Each update increments the frequency for that key
for (Row row : sample) {
    sketch.update(row.getJoinKey(), new IntegerSummary(1));
}

// Intersection preserves summaries from both sides
Intersection<IntegerSummary> intersection = new Intersection<>(
    new IntegerSummarySetOperations(IntegerSummary.Mode.Sum)
);
intersection.intersect(leftSketch);
intersection.intersect(rightSketch);

// Result contains matching keys with combined frequencies
CompactSketch<IntegerSummary> result = intersection.getResult();
```

**Pros:**
- Single integrated structure
- Native intersection with summary preservation
- Well-tested library

**Cons:**
- Less flexible than separate structures
- May need custom summary type for bidirectional frequency

### Memory and Error Bounds

| Structure | Memory | Error Bound |
|-----------|--------|-------------|
| Theta Sketch (k=4096) | ~32 KB | ~1.5% for distinct count |
| Count-Min Sketch (w=2048, d=4) | ~32 KB | ε = e/w ≈ 0.1% overestimate |
| **Combined** | ~64 KB per table | Suitable for optimizer use |

### Serialization Format

```
JoinFanoutSketch Binary Format:
┌────────────────────────────────────────┐
│ Header (16 bytes)                      │
│  - Magic: 4 bytes ("JFSK")             │
│  - Version: 2 bytes                    │
│  - Flags: 2 bytes                      │
│  - Total Row Count: 8 bytes            │
├────────────────────────────────────────┤
│ Theta Sketch (variable)                │
│  - Length prefix: 4 bytes              │
│  - Serialized sketch bytes             │
├────────────────────────────────────────┤
│ Count-Min Sketch (fixed ~32KB)         │
│  - Width: 4 bytes                      │
│  - Depth: 4 bytes                      │
│  - Counters: width × depth × 4 bytes   │
└────────────────────────────────────────┘
```

### Integration with Sampling Endpoint

The `/v1/sample` response would include serialized sketches:

```json
{
  "tableSamples": [
    {
      "tableName": "hive.default.orders",
      "sampledRowCount": 15000,
      "joinFanoutSketch": "<base64-encoded JoinFanoutSketch>",
      "columnStatistics": { ... }
    }
  ]
}
```

The coordinator deserializes sketches, caches them, and computes fanout when evaluating join edges.

## Implementation Phases

### Phase 1: Basic Infrastructure
1. Define protocol structures (SampleRequest, SampleResponse)
2. Add `/v1/sample` endpoint to native sidecar
3. Create `SidecarSamplingClient` Java class
4. Add session properties

### Phase 2: Table Scan Statistics Enhancement
1. Implement `SamplingEnhancedTableScanStatsRule`
2. Create `SampleStatsCache` with session-scoped caching
3. Integrate with `StatsCalculatorModule`
4. Add fallback to connector statistics

### Phase 3: Join Fanout Estimation
1. Implement `JoinSamplingStatsRule`
2. Add hash frequency map support to sampling endpoint
3. Create join sample cache with canonical keys
4. Integrate with `ReorderJoins` rule

### Phase 4: Stored Samples Support
1. Define stored samples table schema
2. Implement `StoredSampleReader`
3. Add freshness checking logic
4. Create ANALYZE command extension for sample generation

### Phase 5: Production Hardening
1. Add metrics and monitoring
2. Implement circuit breaker for sidecar failures
3. Add adaptive sample sizing
4. Performance optimization and tuning

## Risks and Mitigations

| Risk | Impact | Mitigation |
|------|--------|------------|
| Sampling latency impacts planning | High | Async execution, aggressive caching, strict timeouts |
| Independence assumption violation | Medium | Document limitation; future: correlation detection |
| Sidecar unavailability | Medium | Circuit breaker, fall back to connector stats |
| Memory for hash frequency maps | Medium | Cap sample size, use approximate structures (HyperLogLog) |
| Cache staleness | Medium | TTL-based invalidation, version tracking |
| Skewed sample results | Medium | Stratified sampling for known high-cardinality columns |

## Limitations

1. **Independence Assumption:** Like Axiom, this approach assumes filter selectivity and join behavior are independent. This can be problematic when filter columns correlate with join keys.

2. **Base Tables Only:** Sampling only works for base table scans. Derived tables (subqueries, CTEs) fall back to statistical estimation.

3. **Single-Process Sampling:** Initial implementation samples on a single sidecar node. For very large tables, distributed sampling may be needed.

4. **Cold Start:** First queries against new tables will experience sampling latency until cache is populated.

## Future Enhancements

1. **Correlation Detection:** Detect when filter and join columns are correlated and adjust estimation accordingly.

2. **Distributed Sampling:** For very large tables, distribute sampling across multiple workers.

3. **Adaptive Sample Sizing:** Automatically adjust sample fraction based on table size and query complexity.

4. **Sample Reuse Across Queries:** Share samples between concurrent queries to reduce redundant work.

5. **Integration with ANALYZE:** Extend ANALYZE command to populate stored samples table.

## References

- Axiom Statistics Collection: `axiom/optimizer/docs/Statistics_Collection_And_Usage.md`
- Axiom HBO Storage: `axiom/optimizer/docs/HBO_Storage.md`
- Presto TableScanStatsRule: `presto-main-base/.../cost/TableScanStatsRule.java`
- Presto JoinStatsRule: `presto-main-base/.../cost/JoinStatsRule.java`
- Presto Native Sidecar Endpoints: `presto-native-execution/.../PrestoServer.cpp:1757-1817`
- Presto HBO Provider: `presto-spi/.../statistics/HistoryBasedPlanStatisticsProvider.java`
