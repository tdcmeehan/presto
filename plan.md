# Presto Adaptive Optimizer

## Overview

The Adaptive Optimizer automatically learns empirical statistics about a workload's
data characteristics by sampling base tables on demand. It collects three types of
statistics — join fanout, filter selectivity, and aggregation reduction — caches them
in HBO infrastructure, and composes them at query time to produce cost estimates
grounded in real data rather than uniformity/independence assumptions.

The more queries run, the more the optimizer learns. Statistics accumulate following
the Pareto principle: the 20% of tables and join edges that appear in 80% of queries
get sampled almost immediately, providing zero-config optimization for the most
impactful parts of the workload.

## Storage Model (aligned with Axiom)

Following Axiom's architecture, all adaptive statistics are stored in the **existing
HBO provider** (Redis). There is no separate storage system. The HBO provider stores
four types of statistics in distinct key spaces within the same backend:

```
┌─────────────────────────────────────────────────────────────┐
│                  HBO Provider (Redis)                        │
│                                                             │
│  ┌─────────────┐  ┌──────────┐  ┌───────────┐  ┌────────┐ │
│  │ leaves      │  │ joins    │  │ aggs      │  │ plans  │ │
│  │             │  │          │  │           │  │        │ │
│  │ filter      │  │ join     │  │ agg       │  │ plan   │ │
│  │ selectivity │  │ fanout   │  │ reduction │  │ node   │ │
│  │             │  │          │  │           │  │ history│ │
│  │ key: table  │  │ key: tbl │  │ key: tbl  │  │        │ │
│  │  + filter   │  │  pair +  │  │  + group  │  │ (exist │ │
│  │             │  │  joinKeys│  │  Keys     │  │  -ing  │ │
│  │ val: float  │  │          │  │           │  │  HBO)  │ │
│  │             │  │ val: lr, │  │ val: float│  │        │ │
│  │             │  │  rl      │  │           │  │        │ │
│  └─────────────┘  └──────────┘  └───────────┘  └────────┘ │
│                                                             │
│  ◄── new adaptive caches ──────────────────►  ◄─existing─► │
└─────────────────────────────────────────────────────────────┘
```

### Why One Provider, Not Two

Axiom stores all three of its cache types (`leaves`, `joins`, `plans`) through
a single storage layer serialized as one JSON structure. We follow the same pattern:

- **Single SPI**: Extend `HistoryBasedPlanStatisticsProvider` with new methods for
  adaptive statistics, rather than creating a separate `AdaptiveStatisticsProvider`
- **Single Redis backend**: All key spaces share the same Redis instance/cluster
- **Unified lifecycle**: Cache warming, staleness detection, and invalidation are
  coordinated through one manager rather than two independent systems
- **Existing HBO preserved**: The `plans` key space (existing subtree-level HBO)
  continues to work unchanged. Adaptive statistics are additive.

### Four Key Spaces

| Key Space | Key | Value | Source | Filter in Key? |
|-----------|-----|-------|--------|---------------|
| `leaves` | `(table, filter)` | `selectivity: float` | Sampling | Yes |
| `joins` | `(tablePair, joinKeys)` | `(lr_fanout, rl_fanout)` | Sampling | **No** |
| `aggs` | `(table, groupByKeys)` | `reductionFactor: float` | Sampling | **No** |
| `plans` | `(canonicalSubtree)` | `(cardinality, memory, cpu)` | Post-execution | N/A (full subtree) |

The first three are the adaptive optimizer. The fourth is existing HBO.

### Key Canonicalization (aligned with Axiom)

Axiom uses **canonical string keys**, not hashes. Join keys use a format that orders
table names and join columns lexicographically:

```
Join key example:  "customers id   orders cust_id "
                    ^^^^^^^^^ ^^   ^^^^^^ ^^^^^^^
                    table1   col1  table2  col2     (sorted lexicographically)

Leaf key example:  "hive.default.orders[total>1000]"
                    ^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^
                    full table handle toString() including filters
```

We should follow the same approach: **canonical strings as keys, not SHA256 hashes**.
This makes the cache inspectable/debuggable (you can read the Redis keys and
understand what they represent) and avoids hash collision concerns.

## Architecture

```
                    ┌──────────────────────────────────┐
                    │        Adaptive Optimizer         │
                    │                                  │
                    │  JoinStatsRule: fanout * sel      │
                    │  AggStatsRule:  reduction * sel   │
                    │  FilterStats:   selectivity       │
                    └──────┬──────────┬──────────┬─────┘
                           │          │          │
                ┌──────────▼──┐ ┌─────▼─────┐ ┌──▼───────────┐
                │ JoinFanout  │ │ Filter    │ │ Aggregation  │
                │ Cache       │ │ Select.   │ │ Reduction    │
                │             │ │ Cache     │ │ Cache        │
                │ key: table, │ │ key: tbl, │ │ key: table,  │
                │  joinKeys   │ │  filter   │ │  groupKeys   │
                └──────┬──────┘ └─────┬─────┘ └──────┬───────┘
                       │              │              │
                  cache miss     cache miss     cache miss
                       │              │              │
                       ▼              ▼              ▼
                ┌────────────┐ ┌────────────┐ ┌────────────┐
                │ Sample     │ │ Sample     │ │ Sample     │
                │ unfiltered │ │ with       │ │ unfiltered │
                │ join keys  │ │ filter     │ │ group keys │
                │ ~10K rows  │ │ applied    │ │ ~10K rows  │
                └────────────┘ └────────────┘ └────────────┘
                       │              │              │
                       ▼              ▼              ▼
                ┌──────────────────────────────────────────┐
                │ HBO Provider (Redis)                     │
                │ leaves[] + joins[] + aggs[] + plans[]    │
                └──────────────────────────────────────────┘
```

## Composition at Query Time

**Join estimation:**
```
SELECT * FROM orders WHERE ds='2024-01-01' JOIN customers ON custkey

ordersSelectivity   = leaves.get(orders, "ds='2024-01-01'")            → 0.03
joinFanout          = joins.get((orders, customers), custkey)           → (1.3, 0.8)
effectiveFanout     = 1.3 * 1.0   (customers has no filter)
outputRows          = ordersRows * 0.03 * 1.3
```

**Aggregation estimation:**
```
SELECT state, city, COUNT(*) FROM users WHERE region='West' GROUP BY state, city

usersSelectivity    = leaves.get(users, "region='West'")               → 0.25
reductionFactor     = aggs.get(users, [state, city])                   → 0.001
filteredRows        = usersRows * 0.25
aggOutput           = filteredRows * 0.001

CBO would estimate:  min(NDV(state) * NDV(city), filteredRows)
                    = min(50 * 10000, 250K) = 250K   (vs actual ~250)
```

**Existing HBO still works for exact subtree matches:**
```
If the plan subtree hash matches plans[] cache → use exact historical stats
Else → compose from leaves[] + joins[] + aggs[]
Else → fall back to CBO
```

## Phases

### Phase 1: SPI Types and Provider Extension

**Extend existing `HistoryBasedPlanStatisticsProvider`** with new methods:

```java
// New methods added to HistoryBasedPlanStatisticsProvider (or a sub-interface)

// Filter selectivity: (table+filter canonical string) → selectivity
Map<String, Double> getFilterSelectivities(List<String> keys, long timeoutMs);
void putFilterSelectivities(Map<String, Double> selectivities);

// Join fanout: (canonical join edge string) → (lr_fanout, rl_fanout)
Map<String, JoinFanoutEstimate> getJoinFanouts(List<String> keys, long timeoutMs);
void putJoinFanouts(Map<String, JoinFanoutEstimate> fanouts);

// Aggregation reduction: (canonical agg key string) → reductionFactor
Map<String, Double> getAggregationReductions(List<String> keys, long timeoutMs);
void putAggregationReductions(Map<String, Double> reductions);
```

**New value types in `presto-spi`:**

1. `JoinFanoutEstimate`
   - `double leftToRightFanout`
   - `double rightToLeftFanout`
   - `long sampledRows` (for confidence)
   - `long estimatedTableRows` (for staleness detection)

**Note:** Filter selectivity and aggregation reduction are simple doubles (with
metadata), following Axiom's approach. Join fanout needs a pair, hence a dedicated type.

**Files to modify:**
- `presto-spi/.../statistics/HistoryBasedPlanStatisticsProvider.java` (add default methods)

**Files to create:**
- `presto-spi/.../statistics/JoinFanoutEstimate.java`

---

### Phase 2: Canonicalization

**Three canonicalization utilities producing readable string keys (not hashes),
following Axiom's approach:**

#### A. Join Fanout Key Canonicalizer
Given a `JoinNode`, produce a canonical string:
1. Walk from JoinNode to the leaf `TableScanNode` on each side
2. Canonicalize table identity (connector + table, **stripping** pushed-down predicates)
3. Canonicalize join key columns (map to table-relative column names)
4. Sort table sides lexicographically, sort columns within each side
5. Produce string: `"customers id   orders cust_id "` (Axiom format)

**Important:** No filters in the key. The same base table always produces the same
fanout key regardless of what WHERE clauses appear in the query.

#### B. Filter Selectivity Key Canonicalizer
Given a `FilterNode` or pushed-down predicate:
1. Canonicalize table identity including connector and schema
2. Canonicalize filter predicate (preserve constants, normalize variable names, sort conjuncts)
3. Produce string: `"hive.default.orders[ds='2024-01-01' AND total>1000]"`

Filters ARE part of the key — different filter values produce different keys.

#### C. Aggregation Reduction Key Canonicalizer
Given an `AggregationNode`:
1. Walk to the leaf `TableScanNode` feeding the aggregation
2. Canonicalize table identity (unfiltered)
3. Canonicalize grouping key columns (map to table-relative column names, sort)
4. Produce string: `"hive.default.users state city"`

No filters in the key — same rationale as join fanout.

**Reuse from existing infrastructure:**
- `CanonicalPlanGenerator` expression canonicalization utilities
- `CanonicalTableScanNode.getCanonicalTableHandle()` for table identity

**Files to create:**
- `presto-main-base/.../sql/planner/adaptive/JoinFanoutKeyCanonicalizer.java`
- `presto-main-base/.../sql/planner/adaptive/FilterSelectivityKeyCanonicalizer.java`
- `presto-main-base/.../sql/planner/adaptive/AggregationReductionKeyCanonicalizer.java`

---

### Phase 3: Sampling Execution

Core new capability. On cache miss, execute a lightweight sample to measure the
statistic empirically.

#### A. Join Fanout Sampling

For a (table, joinKeys) pair:
1. Determine sample fraction:
   - Small tables (<10K rows): sample all
   - Large tables: fraction = 10000 / estimatedRowCount
2. Construct sample scan:
   - Read from **unfiltered** table with SYSTEM sampling (split-level via `SampledSplitSource`)
   - Project only the join key columns
   - Hash join keys: `hash_combine(hash(key1), hash(key2))`
   - Apply deterministic filter: `(hash % modulus) < limit` for ~10K rows
3. Build frequency map: `Map<hashValue, count>`
4. For each pair of tables in a join, intersect frequency maps:
   - `hits = sum of min(leftCount[h], rightCount[h]) for matching hashes`
   - `leftToRightFanout = hits / leftSample.size()`
   - `rightToLeftFanout = hits / rightSample.size()`
5. Store in HBO provider via `putJoinFanouts()`

#### B. Filter Selectivity Sampling

For a (table, filter) pair:
1. Sample ~1% of table data (SYSTEM sampling)
2. Apply the filter predicate
3. `selectivity = passingRows / scannedRows`
4. Floor at configurable minimum to prevent underestimation
5. Store in HBO provider via `putFilterSelectivities()`

#### C. Aggregation Reduction Sampling

For a (table, groupByKeys) pair:
1. Determine sample fraction (same logic as join fanout — target ~10K rows)
2. Construct sample scan:
   - Read from **unfiltered** table with SYSTEM sampling
   - Project only the grouping key columns
   - Hash grouping keys: `hash_combine(hash(key1), hash(key2), ...)`
   - Count distinct hash values in sample
3. Compute reduction factor:
   - `reductionFactor = distinctHashValues / sampledRows`
   - Captures compound-key NDV that per-column NDV multiplication misses
4. Store in HBO provider via `putAggregationReductions()`

**Note on accuracy:** For high-cardinality grouping keys (many groups relative to
input), the sample-based distinct count may underestimate. Use HyperLogLog on the
sample for better accuracy with large group counts. For low-cardinality keys
(the common case where CBO overestimates most), a simple count is sufficient.

#### Execution Mechanism

**Option A: Connector-level sampling (preferred)**
- Add optional `SamplingCapableConnector` SPI interface
- Connectors that support it (Hive, Iceberg) implement sampling natively
  (read a subset of splits, project columns, return data)
- This avoids dispatching a full Presto query during planning
- `SampledSplitSource` already exists for split-level sampling

**Option B: Internal query dispatch (future enhancement)**
- For connectors that don't support native sampling
- Use ANALYZE-like mechanism to dispatch an internal query
- More complex but universally applicable

**Latency management:**
- Async by default: on cache miss, fall back to CBO for this query,
  kick off background sampling, next query benefits
- Optional synchronous mode with timeout (e.g., 500ms)
- Configurable via session property

**Files to create:**
- `presto-main-base/.../cost/adaptive/JoinFanoutSampler.java`
- `presto-main-base/.../cost/adaptive/FilterSelectivitySampler.java`
- `presto-main-base/.../cost/adaptive/AggregationReductionSampler.java`
- `presto-spi/.../connector/SamplingCapableConnector.java` (optional SPI)

---

### Phase 4: Cache Infrastructure

Extend `HistoryBasedStatisticsCacheManager` with in-memory caches for the three
adaptive statistic types, backed by the HBO provider.

**Extend `HistoryBasedStatisticsCacheManager`:**
- Add `LoadingCache<String, Double> filterSelectivityCache`
- Add `LoadingCache<String, JoinFanoutEstimate> joinFanoutCache`
- Add `LoadingCache<String, Double> aggregationReductionCache`
- Cache miss triggers sampling (async or sync depending on config)
- Cleanup on table metadata changes (partition additions, etc.)

**Pre-loading during planning:**
- Extend `HistoryBasedPlanStatisticsCalculator.registerPlan()`:
  - Walk plan tree, identify all `JoinNode`s, `FilterNode`s, `AggregationNode`s
  - Compute canonical string keys for each
  - Bulk-load from HBO provider (joins[], leaves[], aggs[])
  - For cache misses: enqueue async sampling or sample with timeout

**Staleness detection:**
- Each cached entry optionally stores `estimatedTableRows` at sampling time
- Compare against current table stats from `ConnectorMetadata.getTableStatistics()`
- If table has grown/shrunk by more than threshold, invalidate and re-sample

**Files to modify:**
- `presto-main-base/.../cost/HistoryBasedStatisticsCacheManager.java`
- `presto-main-base/.../cost/HistoryBasedPlanStatisticsCalculator.java`
- `presto-main-base/.../cost/HistoryBasedPlanStatisticsManager.java`

---

### Phase 5: Stats Rule Integration

Modify `JoinStatsRule` and `AggregationStatsRule` to use adaptive statistics
when available, falling back to existing CBO logic.

#### A. JoinStatsRule Integration

**New flow in `computeInnerJoinStats()`:**
```
1. For each side of the join, look up filter selectivity:
   - If FilterNode or pushed-down predicate exists: get from leaves[] cache
   - Else: selectivity = 1.0

2. Look up join fanout from joins[] cache

3. If fanout available:
   outputRows = leftInputRows * leftSelectivity * fanout.leftToRight
   confidence = HIGH

4. Else: fall back to existing NDV-based estimation
```

#### B. AggregationStatsRule Integration

**Two distinct consumers of aggregation cardinality, with different integration points:**

##### Consumer 1: Downstream Join Ordering (via AggregationStatsRule)

When an aggregation feeds into a join, `ReorderJoins` needs accurate output
cardinality to cost the join. The adaptive reduction factor plugs directly into
`AggregationStatsRule`:

```
Current CBO:
  outputRows = NDV(col1) * NDV(col2) * ... * NDV(colN)
  outputRows = min(outputRows, inputRows)

New flow:
1. Look up aggregation reduction from aggs[] cache

2. Look up filter selectivity from leaves[] cache if filters exist upstream

3. If reduction factor available:
   filteredInputRows = inputRows * filterSelectivity
   outputRows = filteredInputRows * reductionFactor
   confidence = HIGH

4. Else: fall back to existing NDV-product estimation
```

This flows transparently into join costing — `ReorderJoins` calls `StatsCalculator`
which calls `AggregationStatsRule`, and the better estimate improves join ordering.

##### Consumer 2: Partial Aggregation Decision (via PushPartialAggregationThroughExchange)

`PushPartialAggregationThroughExchange` does NOT use `AggregationStatsRule`. It has
its own `partialAggregationNotUseful()` check that operates in two modes:

- **Without history**: Compares output bytes vs input bytes. Only evaluates single-key
  aggregations; multi-key aggregations **always push partial agg blindly**.
- **With history** (`use_partial_aggregation_history=true`): Uses
  `PartialAggregationStatsEstimate` populated by HBO from actual prior execution stats.
  Compares row count reduction.

The adaptive reduction factor doesn't automatically reach this code path. We need to
**synthesize a `PartialAggregationStatsEstimate`** from the adaptive cache when HBO
execution history isn't available:

```
If adaptive reduction factor is available for (table, groupByKeys):
  Synthesize PartialAggregationStatsEstimate:
    inputRowCount  = estimated input rows to partial agg
    outputRowCount = inputRowCount * reductionFactor
    inputBytes     = inputRowCount * avgRowSize
    outputBytes    = outputRowCount * avgRowSize
  Attach to the AggregationNode's PlanNodeStatsEstimate
```

This makes the adaptive data look like HBO history to the existing decision code,
enabling `partialAggregationNotUseful()` to make informed decisions — especially
for **multi-key aggregations**, where it currently always pushes blindly.

**Caveat: Global vs. Partial Reduction Factor**

Our sample gives the **global** reduction factor (across all data). The **partial**
reduction factor (per-worker, on one partition of data) may differ:
- If data is partitioned by a grouping key → partial agg achieves near-full reduction
- If partitioned by unrelated key → partial agg achieves less reduction

The global reduction factor is a reasonable **upper bound** on partial agg
effectiveness. If the global factor is close to 1.0 (little reduction), partial agg
is definitely not useful. If the global factor is very low (high reduction), partial
agg is likely useful regardless of partitioning. The edge cases are when the global
factor is moderate — but this is still far better than the current behavior of
pushing blindly for multi-key or relying on HBO execution history for single-key.

#### C. Interaction with Existing HBO (plans[])

The priority order for stats is:
1. **Existing HBO (plans[])** — if the exact subtree hash matches, use it (highest confidence)
2. **Adaptive composition** — compose from joins[]/leaves[]/aggs[] caches
3. **CBO** — fall back to model-based estimation

This preserves existing HBO behavior while layering adaptive estimation on top.

For the partial aggregation decision specifically:
1. **HBO execution history** — actual `PartialAggregationStatistics` from prior runs (highest confidence)
2. **Adaptive synthesized estimate** — derived from sampled reduction factor
3. **Existing heuristic** — bytes comparison for single-key, always-push for multi-key

**Files to modify:**
- `presto-main-base/.../cost/JoinStatsRule.java`
- `presto-main-base/.../cost/AggregationStatsRule.java`
- `presto-main-base/.../cost/HistoryBasedPlanStatisticsCalculator.java` (synthesize PartialAggregationStatsEstimate)

---

### Phase 6: Testing and In-Memory Provider

**In-memory provider:**
- Extend existing `InMemoryHistoryBasedPlanStatisticsProvider` to implement the
  new adaptive statistics methods (backed by `ConcurrentHashMap` per key space)

**Unit tests:**
- Canonicalization:
  - Same join edge across different queries → same key string
  - Same GROUP BY across different queries → same key string
  - Different filters → different selectivity key strings
  - Same table with different join/group keys → different keys
  - Filter changes don't affect fanout/reduction keys
  - Key strings are human-readable and debuggable
- Sampling:
  - Join fanout: mock connector with known data, verify fanout
  - Filter selectivity: mock connector, verify selectivity
  - Aggregation reduction: mock connector with correlated columns, verify
    reduction factor captures correlation (vs CBO's NDV product overestimate)
- Stats rule integration:
  - JoinStatsRule: fanout * selectivity produces correct estimate
  - AggregationStatsRule: reduction * selectivity produces correct estimate
  - Fallback to existing HBO when subtree hash matches
  - Fallback to CBO when no adaptive stats available
- Independence assumption:
  - Test where assumption holds (estimate matches actual)
  - Test where assumption fails (estimate diverges from actual)

**Integration tests:**
- End-to-end join: query → sample → cache → re-query → cache hit → better plan
- End-to-end aggregation: query with multi-key GROUP BY → sample → cache →
  re-query → accurate output estimate (vs 100x+ CBO overestimate)
- Staleness: data change → invalidation → re-sample
- Timeout: slow sampling → falls back to CBO gracefully
- Cross-query reuse: two different queries hitting same tables → shared cache entries
- HBO + adaptive: verify existing HBO still takes priority for exact subtree matches

**Files to create:**
- `presto-main-base/.../cost/adaptive/TestJoinFanoutSampler.java`
- `presto-main-base/.../cost/adaptive/TestFilterSelectivitySampler.java`
- `presto-main-base/.../cost/adaptive/TestAggregationReductionSampler.java`
- `presto-main-base/.../cost/adaptive/TestJoinStatsRuleWithAdaptiveStats.java`
- `presto-main-base/.../cost/adaptive/TestAggregationStatsRuleWithAdaptiveStats.java`
- `presto-main-base/.../sql/planner/adaptive/TestKeyCanonicalizers.java`

**Files to modify:**
- `presto-main-base/.../cost/InMemoryHistoryBasedPlanStatisticsProvider.java`

---

### Phase 7 (Future): Enhancements

1. **Internal query dispatch** — for connectors without native sampling support
2. **Proactive cache warming** — background job samples popular join edges and group-by keys
3. **Post-execution validation** — after query completes, compare actual vs estimated
   fanout/reduction to validate the independence assumption
4. **Correlation detection** — if estimated consistently diverges from actual,
   fall back to CBO or cache a correction factor keyed by (table, filter, keys)
5. **HyperLogLog for high-cardinality groups** — improve distinct count accuracy
   for aggregation reduction sampling when group count is large relative to sample
6. **Multi-key frequency maps** — cache raw frequency maps (not just scalar values)
   to support more sophisticated estimation in the future
7. **Adaptive sampling frequency** — re-sample more frequently for volatile tables,
   less frequently for stable ones (based on observed staleness rate)

---

## Dependency Graph

```
Phase 1 (SPI types + provider extension)
    │
    ├──► Phase 2 (Canonicalization — all three key types)
    │        │
    │        ├──► Phase 3 (Sampling — join, filter, aggregation)
    │        │        │
    │        │        ├──► Phase 4 (Cache infrastructure in existing HBO manager)
    │        │        │        │
    │        │        │        └──► Phase 5 (JoinStatsRule + AggregationStatsRule)
    │        │        │                 │
    │        │        │                 └──► Phase 6 (Testing)
    │        │        │
    │        │        └──► Phase 6 (Sampler unit tests)
    │        │
    │        └──► Phase 5 (for cache lookup keys)
```

Phase 1-2: Pure infrastructure, no behavioral change.
Phase 3: Core new capability — sampling for all three statistic types.
Phase 4-5: Close the loop — optimizer uses sampled data.
Phase 6: Validate everything.

## Known Limitation: Independence Assumption

All three composition formulas assume independence:
- `joinOutput = fanout * filterSelectivity` — filters don't change join key distribution
- `aggOutput = reductionFactor * filterSelectivity` — filters don't change grouping key distribution

This breaks when filter columns correlate with join/grouping keys. Example:
- `WHERE region = 'US'` selects customers who place 10x more orders → join fanout is wrong
- `WHERE status = 'active'` selects users concentrated in a few cities → aggregation reduction is wrong

**Mitigation (Phase 7):** Post-execution validation. Compare actual vs estimated after
each query. If they consistently diverge for a (table, filter, keys) triple, either:
- Fall back to CBO for that specific combination
- Cache a correction factor keyed by (table, filter, keys) — effectively promoting
  to a filter-aware entry for the correlated cases, while keeping the filter-free
  cache for the uncorrelated majority

## Session Properties

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `adaptive_optimizer_enabled` | boolean | false | Master switch for adaptive optimizer |
| `adaptive_optimizer_sample_size` | int | 10000 | Target rows per sample |
| `adaptive_optimizer_sampling_timeout_ms` | int | 500 | Max time for sync sampling |
| `adaptive_optimizer_async_sampling` | boolean | true | Sample async on miss (vs sync) |
| `adaptive_optimizer_filter_sample_ratio` | double | 0.01 | Fraction for selectivity sampling |
| `adaptive_optimizer_staleness_threshold` | double | 0.5 | Re-sample if table size changed >50% |
| `adaptive_optimizer_join_fanout_enabled` | boolean | true | Enable join fanout (when master on) |
| `adaptive_optimizer_aggregation_reduction_enabled` | boolean | true | Enable agg reduction (when master on) |
| `adaptive_optimizer_filter_selectivity_enabled` | boolean | true | Enable filter selectivity (when master on) |
