# Plan: Per-Join-Edge Fanout Statistics in Presto

## Goal

Bring Axiom-style join fanout estimation to Presto by:
1. Sampling unfiltered base tables to measure join key frequency distributions
2. Independently sampling filter selectivity for filtered scans
3. Composing the two at optimization time: `effectiveFanout = sampledFanout * filterSelectivity`
4. Caching both in HBO infrastructure (Redis) for cross-query reuse

This replaces the CBO's `1/max(leftNdv, rightNdv)` uniformity assumption with
empirical measurements from the actual data.

## Architecture Overview

```
                  ┌─────────────────────┐
                  │    JoinStatsRule     │
                  │                     │
                  │  effective_fanout =  │
                  │  join_fanout *       │
                  │  left_selectivity *  │
                  │  right_selectivity   │
                  └───┬─────────────┬───┘
                      │             │
            ┌─────────▼───┐   ┌────▼──────────┐
            │ JoinFanout  │   │ FilterSelect. │
            │ Cache       │   │ Cache         │
            │             │   │               │
            │ key: (table,│   │ key: (table,  │
            │  joinKeys)  │   │  filter)      │
            └──────┬──────┘   └──────┬────────┘
                   │                 │
              cache miss        cache miss
                   │                 │
                   ▼                 ▼
            ┌────────────┐   ┌────────────────┐
            │ Sample     │   │ Sample         │
            │ unfiltered │   │ with filter    │
            │ base table │   │ applied        │
            │ join keys  │   │ count pass/    │
            │ ~10K rows  │   │ total ratio    │
            └────────────┘   └────────────────┘
                   │                 │
                   ▼                 ▼
            ┌────────────────────────────────┐
            │ HBO Provider (Redis/storage)   │
            │ persist for cross-query reuse  │
            └────────────────────────────────┘
```

## Cache Key Design

**Two separate caches with different key granularities:**

### Join Fanout Cache
```
Key:   (canonicalTable, canonicalJoinKeys)
Value: (leftToRightFanout: double, rightToLeftFanout: double)
```
- No filter in the key — sampled from unfiltered base table
- One entry per (table, join key set) pair, reused across all queries
- Example: `(orders, [custkey])` → `(fanout: 1.3, 0.8)`

### Filter Selectivity Cache
```
Key:   (canonicalTable, canonicalFilterPredicate)
Value: (selectivity: double)
```
- Filter constants ARE part of the key (different filters → different selectivity)
- One entry per (table, filter combo), reused across all queries hitting that table+filter
- Example: `(orders, "ds = '2024-01-01'")` → `0.03`

### Composition at Query Time
```
For: SELECT * FROM orders WHERE ds='2024-01-01' JOIN customers ON custkey

ordersSelectivity  = filterSelectivityCache.get(orders, "ds='2024-01-01'")  → 0.03
customerSelectivity = 1.0  (no filter)
joinFanout         = joinFanoutCache.get((orders, customers), custkey)      → (1.3, 0.8)

effectiveLeftToRight = 1.3 * customerSelectivity  = 1.3
effectiveRightToLeft = 0.8 * ordersSelectivity     = 0.024
outputRows          = ordersRows * ordersSelectivity * effectiveLeftToRight
                    = ordersRows * 0.03 * 1.3
```

## Phases

### Phase 1: SPI Types

**New types in `presto-spi`:**

1. `JoinFanoutKey` — identifies a (table, joinKeys) pair
   - `String hash` (SHA256 of canonical form)

2. `JoinFanoutEstimate` — bidirectional fanout measurement
   - `double leftToRightFanout`
   - `double rightToLeftFanout`
   - `long sampledRows` (sample size, for confidence)
   - `long estimatedTableRows` (table size at time of sampling, for staleness detection)

3. `FilterSelectivityKey` — identifies a (table, filter) pair
   - `String hash` (SHA256 of canonical form)

4. `FilterSelectivityEstimate` — selectivity measurement
   - `double selectivity`
   - `long sampledRows`
   - `long estimatedTableRows`

5. `JoinEdgeStatisticsProvider` — SPI interface for storage
   - `Map<JoinFanoutKey, JoinFanoutEstimate> getJoinFanouts(List<JoinFanoutKey>, timeout)`
   - `void putJoinFanouts(Map<JoinFanoutKey, JoinFanoutEstimate>)`
   - `Map<FilterSelectivityKey, FilterSelectivityEstimate> getFilterSelectivities(List<FilterSelectivityKey>, timeout)`
   - `void putFilterSelectivities(Map<FilterSelectivityKey, FilterSelectivityEstimate>)`

**Files to create:**
- `presto-spi/.../statistics/JoinFanoutKey.java`
- `presto-spi/.../statistics/JoinFanoutEstimate.java`
- `presto-spi/.../statistics/FilterSelectivityKey.java`
- `presto-spi/.../statistics/FilterSelectivityEstimate.java`
- `presto-spi/.../statistics/JoinEdgeStatisticsProvider.java`

---

### Phase 2: Canonicalization

**Two canonicalization utilities:**

#### A. Join Fanout Key Canonicalizer
Given a `JoinNode`, produce a canonical key for each side's (table, joinKeys):
1. Walk from JoinNode to the leaf `TableScanNode` on each side
2. Canonicalize table identity (connector + table handle, ignoring filters/predicates)
3. Canonicalize join key columns (map to table-relative names, sort)
4. Order the two sides deterministically
5. Hash → `JoinFanoutKey`

**Important:** The table identity here is the *unfiltered* table. Any pushed-down
predicates in the table handle are stripped for the fanout key.

#### B. Filter Selectivity Key Canonicalizer
Given a `FilterNode` (or predicate pushed into `TableScanNode`):
1. Canonicalize table identity (same as above)
2. Canonicalize filter predicate (preserve constants, normalize variable names)
3. Hash → `FilterSelectivityKey`

**Reuse from existing infrastructure:**
- `CanonicalPlanGenerator` expression canonicalization utilities
- `CanonicalTableScanNode.getCanonicalTableHandle()` for table identity

**Files to create:**
- `presto-main-base/.../sql/planner/JoinFanoutKeyCanonicalizer.java`
- `presto-main-base/.../sql/planner/FilterSelectivityKeyCanonicalizer.java`

---

### Phase 3: Sampling Execution

This is the core new capability. On cache miss, execute a lightweight sample query
to measure join key frequency or filter selectivity.

#### A. Join Fanout Sampling

For a (table, joinKeys) pair:
1. Determine sample fraction:
   - Small tables (<10K rows): sample all
   - Large tables: fraction = 10000 / estimatedRowCount
2. Construct sample scan:
   - Read from table with SYSTEM sampling (split-level via `SampledSplitSource`)
   - Project only the join key columns
   - Hash join keys: `hash_combine(hash(key1), hash(key2))`
   - Apply deterministic filter: `(hash % modulus) < limit` for ~10K rows
3. Build frequency map: `Map<hashValue, count>`
4. For each pair of tables in a join, intersect frequency maps:
   - `hits = sum of min(leftCount[h], rightCount[h]) for matching hashes`
   - `leftToRightFanout = hits / leftSample.size()`
   - `rightToLeftFanout = hits / rightSample.size()`
5. Store in HBO cache

#### B. Filter Selectivity Sampling

For a (table, filter) pair:
1. Sample ~1% of table data (SYSTEM sampling)
2. Apply the filter predicate
3. `selectivity = passingRows / scannedRows`
4. Floor at some minimum (Axiom uses 0.9, we may want to tune this)
5. Store in HBO cache

#### Execution Mechanism

**Option A: Connector-level sampling (preferred for Phase 3)**
- Add `SamplingCapableConnector` SPI interface
- Connectors that support it (Hive, Iceberg) implement sampling natively
  (read a subset of files/splits, apply filter, return counts)
- This avoids dispatching a full Presto query during planning
- `SampledSplitSource` already exists for split-level sampling

**Option B: Internal query dispatch (Phase 7 enhancement)**
- For connectors that don't support native sampling
- Use ANALYZE-like mechanism to dispatch an internal query
- More complex but universally applicable

**Latency management:**
- Sampling is async: on cache miss, fall back to CBO for this query,
  kick off background sampling, next query benefits
- Or: synchronous with timeout (e.g., 500ms), fall back to CBO if timeout
- Configurable via session property

**Files to create:**
- `presto-main-base/.../cost/JoinFanoutSampler.java`
- `presto-main-base/.../cost/FilterSelectivitySampler.java`
- `presto-spi/.../connector/SamplingCapableConnector.java` (optional SPI)

**Files to modify:**
- Connector implementations that opt in to native sampling

---

### Phase 4: Cache Infrastructure

Add caches in `HistoryBasedStatisticsCacheManager` for both fanout and selectivity.

**Modify `HistoryBasedStatisticsCacheManager`:**
- Add `LoadingCache<JoinFanoutKey, JoinFanoutEstimate> joinFanoutCache`
- Add `LoadingCache<FilterSelectivityKey, FilterSelectivityEstimate> filterSelectivityCache`
- Cache miss triggers sampling (async or sync depending on config)
- Cache cleanup on table metadata changes (partition additions, etc.)

**Pre-loading during planning:**
- In `HistoryBasedPlanStatisticsCalculator.registerPlan()`:
  - Walk plan tree, identify all `JoinNode`s and `FilterNode`s
  - Compute keys for each
  - Bulk-load from HBO provider
  - For cache misses: either sample synchronously (with timeout) or enqueue async

**Staleness detection:**
- Compare `estimatedTableRows` in cached entry against current table stats
- If table has grown/shrunk by more than threshold, invalidate and re-sample

**Files to modify:**
- `presto-main-base/.../cost/HistoryBasedStatisticsCacheManager.java`
- `presto-main-base/.../cost/HistoryBasedPlanStatisticsCalculator.java`
- `presto-main-base/.../cost/HistoryBasedPlanStatisticsManager.java`

---

### Phase 5: JoinStatsRule Integration

Modify `JoinStatsRule` to use sampled fanout + selectivity when available.

**New flow in `computeInnerJoinStats()`:**

```
1. For each side of the join, look up filter selectivity:
   - If FilterNode or pushed-down predicate exists above/in the TableScan:
     selectivity = filterSelectivityCache.get(table, filter)
   - Else: selectivity = 1.0

2. Look up join fanout for the (leftTable, rightTable, joinKeys) edge:
   fanout = joinFanoutCache.get(joinFanoutKey)

3. If fanout available:
   outputRows = leftInputRows * leftSelectivity * fanout.leftToRight
   // or equivalently: rightInputRows * rightSelectivity * fanout.rightToLeft
   confidence = HIGH

4. Else: fall back to existing NDV-based estimation
```

**Integration with ReorderJoins:**
- `ReorderJoins` already calls `JoinStatsRule` via `StatsCalculator`
- Better fanout estimates automatically improve join order selection
- No changes needed to `ReorderJoins` itself

**Files to modify:**
- `presto-main-base/.../cost/JoinStatsRule.java`

---

### Phase 6: Testing and In-Memory Provider

**In-memory provider:**
- `InMemoryJoinEdgeStatisticsProvider` implementing `JoinEdgeStatisticsProvider`
- Backed by `ConcurrentHashMap`

**Unit tests:**
- Canonicalization: same join edge across different queries → same key
- Canonicalization: different filters → different selectivity keys
- Canonicalization: same table with different join keys → different fanout keys
- Fanout sampling: mock connector returning known data, verify computed fanout
- Selectivity sampling: mock connector, verify selectivity
- JoinStatsRule: verify fanout * selectivity produces correct estimate
- Independence assumption: test case where assumption holds, one where it doesn't

**Integration tests:**
- End-to-end: query → sample → cache → re-query → cache hit → better plan
- Staleness: insert data → cache invalidation → re-sample
- Timeout: slow sampling → falls back to CBO gracefully

**Files to create:**
- `presto-main-base/.../cost/InMemoryJoinEdgeStatisticsProvider.java`
- `presto-main-base/.../cost/TestJoinFanoutSampler.java`
- `presto-main-base/.../cost/TestFilterSelectivitySampler.java`
- `presto-main-base/.../cost/TestJoinStatsRuleWithFanout.java`
- `presto-main-base/.../sql/planner/TestJoinFanoutKeyCanonicalizer.java`

---

### Phase 7 (Future): Enhancements

1. **Internal query dispatch** — for connectors without native sampling support
2. **Proactive cache warming** — background job samples popular join edges
3. **Post-execution recording** — after query completes, record actual fanout to
   validate/update sampled estimates (combines with existing HBO tracking)
4. **Correlation detection** — if post-execution fanout consistently differs from
   sampled fanout * selectivity, flag the independence assumption violation
5. **Multi-key frequency maps** — cache raw frequency maps (not just scalar fanout)
   to support more sophisticated estimation in the future

---

## Dependency Graph

```
Phase 1 (SPI types)
    │
    ├──► Phase 2 (Canonicalization)
    │        │
    │        ├──► Phase 3 (Sampling execution)
    │        │        │
    │        │        ├──► Phase 4 (Cache infrastructure)
    │        │        │        │
    │        │        │        └──► Phase 5 (JoinStatsRule integration)
    │        │        │                 │
    │        │        │                 └──► Phase 6 (Testing)
    │        │        │
    │        │        └──► Phase 6 (Testing - sampler unit tests)
    │        │
    │        └──► Phase 5 (for cache lookup keys)
```

Phase 1-2: Pure infrastructure, no behavioral change.
Phase 3: Core new capability — sampling.
Phase 4-5: Close the loop — optimizer uses sampled data.
Phase 6: Validate everything.

## Known Limitation: Independence Assumption

The composition `effectiveFanout = sampledFanout * filterSelectivity` assumes
that filtered rows have the same join key distribution as unfiltered rows.

This breaks when:
- `WHERE region = 'US'` selects customers who happen to place 10x more orders
- `WHERE status = 'cancelled'` selects orders with NULL customer keys

Mitigation (Phase 7): post-execution validation. After the query runs, compare
actual fanout against the estimate. If they consistently diverge, either:
- Fall back to CBO for that join edge
- Cache a correction factor keyed by (table, filter, joinKeys)

## Session Properties

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `hbo_join_fanout_sampling_enabled` | boolean | false | Enable fanout sampling |
| `hbo_join_fanout_sample_size` | int | 10000 | Target rows per sample |
| `hbo_join_fanout_sampling_timeout_ms` | int | 500 | Max time for sync sampling |
| `hbo_join_fanout_async_sampling` | boolean | true | Sample async on miss (vs sync with timeout) |
| `hbo_filter_selectivity_sampling_enabled` | boolean | false | Enable selectivity sampling |
| `hbo_filter_selectivity_sample_ratio` | double | 0.01 | Fraction of table to sample for selectivity |
| `hbo_join_fanout_staleness_threshold` | double | 0.5 | Re-sample if table size changed by >50% |
