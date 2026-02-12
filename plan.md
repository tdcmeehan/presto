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
                │ Adaptive Statistics Provider (Redis/HBO) │
                │ persist for cross-query reuse            │
                └──────────────────────────────────────────┘
```

## Cache Key Design

**Three caches with different key granularities, composed at query time:**

### 1. Join Fanout Cache
```
Key:   (canonicalTable, canonicalJoinKeys)
Value: (leftToRightFanout: double, rightToLeftFanout: double)
```
- Sampled from **unfiltered** base table — no filter in the key
- One entry per (table, join key set) pair, reused across all queries
- Example: `(orders, [custkey])` → `(lr: 1.3, rl: 0.8)`

### 2. Filter Selectivity Cache
```
Key:   (canonicalTable, canonicalFilterPredicate)
Value: (selectivity: double)
```
- Filter constants ARE part of the key (different filter values → different entry)
- One entry per (table, filter combo)
- Example: `(orders, "ds = '2024-01-01'")` → `0.03`

### 3. Aggregation Reduction Cache
```
Key:   (canonicalTable, canonicalGroupByKeys)
Value: (reductionFactor: double)    // distinctGroups / totalRows
```
- Sampled from **unfiltered** base table — no filter in the key
- Captures the compound-key NDV that CBO cannot derive from per-column stats
- Example: `(users, [state, city])` → `0.001` (1K groups in 1M rows)

### Composition at Query Time

**Join estimation:**
```
SELECT * FROM orders WHERE ds='2024-01-01' JOIN customers ON custkey

ordersSelectivity   = filterCache.get(orders, "ds='2024-01-01'")     → 0.03
joinFanout          = fanoutCache.get((orders, customers), custkey)   → (1.3, 0.8)
effectiveFanout     = 1.3 * 1.0   (customers has no filter)
outputRows          = ordersRows * 0.03 * 1.3
```

**Aggregation estimation:**
```
SELECT state, city, COUNT(*) FROM users WHERE region='West' GROUP BY state, city

usersSelectivity    = filterCache.get(users, "region='West'")        → 0.25
reductionFactor     = reductionCache.get(users, [state, city])       → 0.001
filteredRows        = usersRows * 0.25
aggOutput           = filteredRows * 0.001

CBO would estimate:  filteredRows * NDV(state) * NDV(city) / filteredRows
                    = min(50 * 10000, filteredRows) = way too high
```

## Phases

### Phase 1: SPI Types

**New types in `presto-spi`:**

1. `AdaptiveStatisticsKey` — base type for cache keys
   - `String hash` (SHA256 of canonical form)

2. `JoinFanoutEstimate` — bidirectional fanout measurement
   - `double leftToRightFanout`
   - `double rightToLeftFanout`
   - `long sampledRows` (sample size, for confidence)
   - `long estimatedTableRows` (table size at sampling time, for staleness)

3. `FilterSelectivityEstimate` — selectivity measurement
   - `double selectivity`
   - `long sampledRows`
   - `long estimatedTableRows`

4. `AggregationReductionEstimate` — group-by reduction measurement
   - `double reductionFactor` (distinctGroups / inputRows)
   - `long sampledRows`
   - `long estimatedTableRows`
   - `long observedDistinctGroups` (from sample, for confidence assessment)

5. `AdaptiveStatisticsProvider` — SPI interface for storage
   - `Map<Key, JoinFanoutEstimate> getJoinFanouts(List<Key>, timeout)`
   - `void putJoinFanouts(Map<Key, JoinFanoutEstimate>)`
   - `Map<Key, FilterSelectivityEstimate> getFilterSelectivities(List<Key>, timeout)`
   - `void putFilterSelectivities(Map<Key, FilterSelectivityEstimate>)`
   - `Map<Key, AggregationReductionEstimate> getAggregationReductions(List<Key>, timeout)`
   - `void putAggregationReductions(Map<Key, AggregationReductionEstimate>)`

**Files to create:**
- `presto-spi/.../statistics/AdaptiveStatisticsKey.java`
- `presto-spi/.../statistics/JoinFanoutEstimate.java`
- `presto-spi/.../statistics/FilterSelectivityEstimate.java`
- `presto-spi/.../statistics/AggregationReductionEstimate.java`
- `presto-spi/.../statistics/AdaptiveStatisticsProvider.java`

---

### Phase 2: Canonicalization

**Three canonicalization utilities, sharing common infrastructure:**

#### A. Join Fanout Key Canonicalizer
Given a `JoinNode`, produce a key for each side's (table, joinKeys):
1. Walk from JoinNode to the leaf `TableScanNode` on each side
2. Canonicalize table identity (connector + table handle, **stripping** pushed-down predicates)
3. Canonicalize join key columns (map to table-relative column names, sort)
4. Order the two table sides deterministically (e.g., alphabetical by table name)
5. Hash → `AdaptiveStatisticsKey`

**Important:** The table identity is the *unfiltered* table. Pushed-down predicates
are stripped so the same base table always produces the same fanout key.

#### B. Filter Selectivity Key Canonicalizer
Given a `FilterNode` or pushed-down predicate:
1. Canonicalize table identity (same unfiltered base table)
2. Canonicalize filter predicate (preserve constants, normalize variable names)
3. Hash → `AdaptiveStatisticsKey`

#### C. Aggregation Reduction Key Canonicalizer
Given an `AggregationNode`:
1. Walk to the leaf `TableScanNode` feeding the aggregation
2. Canonicalize table identity (unfiltered)
3. Canonicalize grouping key columns (map to table-relative column names, sort)
4. Hash → `AdaptiveStatisticsKey`

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
5. Store in cache via `AdaptiveStatisticsProvider`

#### B. Filter Selectivity Sampling

For a (table, filter) pair:
1. Sample ~1% of table data (SYSTEM sampling)
2. Apply the filter predicate
3. `selectivity = passingRows / scannedRows`
4. Floor at configurable minimum to prevent underestimation
5. Store in cache

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
   - This captures the compound-key NDV that per-column NDV multiplication misses
4. Store in cache

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

Add caches in `HistoryBasedStatisticsCacheManager` (or a new `AdaptiveStatisticsCacheManager`)
for all three statistic types.

**Cache manager responsibilities:**
- `LoadingCache<AdaptiveStatisticsKey, JoinFanoutEstimate> joinFanoutCache`
- `LoadingCache<AdaptiveStatisticsKey, FilterSelectivityEstimate> filterSelectivityCache`
- `LoadingCache<AdaptiveStatisticsKey, AggregationReductionEstimate> aggregationReductionCache`
- Cache miss triggers sampling (async or sync depending on config)
- Cleanup on table metadata changes (partition additions, etc.)

**Pre-loading during planning:**
- In a new `AdaptiveStatisticsCalculator.registerPlan()` (or extend existing HBO calculator):
  - Walk plan tree, identify all `JoinNode`s, `FilterNode`s, `AggregationNode`s
  - Compute canonical keys for each
  - Bulk-load from `AdaptiveStatisticsProvider`
  - For cache misses: enqueue async sampling or sample with timeout

**Staleness detection:**
- Each cached entry stores `estimatedTableRows` at sampling time
- Compare against current table stats from `ConnectorMetadata.getTableStatistics()`
- If table has grown/shrunk by more than threshold, invalidate and re-sample

**Files to create/modify:**
- `presto-main-base/.../cost/adaptive/AdaptiveStatisticsCacheManager.java` (new)
- `presto-main-base/.../cost/adaptive/AdaptiveStatisticsCalculator.java` (new)
- Or extend existing `HistoryBasedStatisticsCacheManager` and `HistoryBasedPlanStatisticsCalculator`

---

### Phase 5: Stats Rule Integration

Modify `JoinStatsRule` and `AggregationStatsRule` to use adaptive statistics
when available, falling back to existing CBO logic.

#### A. JoinStatsRule Integration

**New flow in `computeInnerJoinStats()`:**
```
1. For each side of the join, look up filter selectivity:
   - If FilterNode or pushed-down predicate exists: get from cache
   - Else: selectivity = 1.0

2. Look up join fanout for the (leftTable, rightTable, joinKeys) edge

3. If fanout available:
   outputRows = leftInputRows * leftSelectivity * fanout.leftToRight
   confidence = HIGH

4. Else: fall back to existing NDV-based estimation
```

#### B. AggregationStatsRule Integration

**New flow in `groupBy()` estimation:**
```
Current CBO:
  outputRows = NDV(col1) * NDV(col2) * ... * NDV(colN)   // independence assumption
  outputRows = min(outputRows, inputRows)

New flow:
1. Look up aggregation reduction for (table, groupByKeys)

2. Look up filter selectivity if filters exist upstream

3. If reduction factor available:
   filteredInputRows = inputRows * filterSelectivity
   outputRows = filteredInputRows * reductionFactor
   confidence = HIGH

4. Else: fall back to existing NDV-product estimation
```

#### C. PushPartialAggregationThroughExchange

The existing `partialAggregationNotUseful()` check compares output/input sizes.
With accurate reduction factors from the adaptive cache, this decision becomes
much better — especially for multi-key aggregations, where Presto currently
always pushes partial aggregation blindly because it can't estimate the reduction.

No code changes needed here — the improvement flows automatically through
`AggregationStatsRule` returning better estimates to the stats provider.

**Files to modify:**
- `presto-main-base/.../cost/JoinStatsRule.java`
- `presto-main-base/.../cost/AggregationStatsRule.java`

---

### Phase 6: Testing and In-Memory Provider

**In-memory provider:**
- `InMemoryAdaptiveStatisticsProvider` implementing `AdaptiveStatisticsProvider`
- Backed by `ConcurrentHashMap`

**Unit tests:**
- Canonicalization:
  - Same join edge across different queries → same key
  - Same GROUP BY across different queries → same key
  - Different filters → different selectivity keys
  - Same table with different join/group keys → different keys
  - Filter changes don't affect fanout/reduction keys
- Sampling:
  - Join fanout: mock connector with known data, verify fanout
  - Filter selectivity: mock connector, verify selectivity
  - Aggregation reduction: mock connector with correlated columns, verify
    reduction factor captures correlation (vs CBO's NDV product overestimate)
- Stats rule integration:
  - JoinStatsRule: fanout * selectivity produces correct estimate
  - AggregationStatsRule: reduction * selectivity produces correct estimate
  - AggregationStatsRule: fallback to NDV-product when no adaptive stats
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

**Files to create:**
- `presto-main-base/.../cost/adaptive/InMemoryAdaptiveStatisticsProvider.java`
- `presto-main-base/.../cost/adaptive/TestJoinFanoutSampler.java`
- `presto-main-base/.../cost/adaptive/TestFilterSelectivitySampler.java`
- `presto-main-base/.../cost/adaptive/TestAggregationReductionSampler.java`
- `presto-main-base/.../cost/adaptive/TestJoinStatsRuleWithAdaptiveStats.java`
- `presto-main-base/.../cost/adaptive/TestAggregationStatsRuleWithAdaptiveStats.java`
- `presto-main-base/.../sql/planner/adaptive/TestKeyCanonicalizers.java`

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
Phase 1 (SPI types)
    │
    ├──► Phase 2 (Canonicalization — all three key types)
    │        │
    │        ├──► Phase 3 (Sampling — join, filter, aggregation)
    │        │        │
    │        │        ├──► Phase 4 (Cache infrastructure)
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
