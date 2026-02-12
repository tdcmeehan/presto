# Plan: Per-Join-Edge Fanout Statistics in Presto

## Goal

Bring Axiom-style per-join-edge fanout estimation to Presto by:
1. Decomposing HBO's cache from per-subtree to per-join-edge granularity
2. Recording actual join fanout after execution (warm path)
3. Adding sampling-based fanout estimation for cold start (cold path)

This gives the optimizer empirical join selectivity data instead of relying on
`1/max(leftNdv, rightNdv)` with uniformity assumptions.

## Architecture Overview

```
                  ┌─────────────────────┐
                  │    JoinStatsRule     │
                  │  (uses fanout if     │
                  │   available, else    │
                  │   falls back to CBO) │
                  └─────┬───────────────┘
                        │ lookup
                        ▼
              ┌─────────────────────┐
              │ JoinEdgeFanout      │
              │ CacheManager        │
              │ (per-query cache)   │
              └────┬──────────┬─────┘
                   │          │
          cache miss     cache hit
                   │          │
                   ▼          ▼
     ┌──────────────────┐   return
     │ HBO Provider      │   cached
     │ (Redis/storage)   │   fanout
     └──────────────────┘
          ▲          ▲
          │          │
    ┌─────┘          └──────┐
    │ record after          │ sample on
    │ execution             │ cold start
    │ (Phase 3)             │ (Phase 5)
    └───────────────┘       └───────────┘
```

## Cache Key Design

The join edge key is a tuple of:
```
(leftTableCanonical + leftFilter, rightTableCanonical + rightFilter, joinKeysCanonical)
```

Canonicalized so that:
- Variable names are normalized
- Left/right are ordered deterministically (e.g., by table name)
- Filter predicates preserve constants (same as current HBO FilterNode behavior)
- Join keys are sorted

This means the same physical join edge matches across different queries regardless
of surrounding plan structure.

## Phases

### Phase 1: SPI Types for Join Edge Fanout

**New types in `presto-spi`:**

1. `JoinEdgeKey` — serializable key identifying a join edge
   - `String hash` (SHA256 of canonical join edge)
   - Used as cache/storage key

2. `JoinEdgeFanoutStatistics` — fanout data for a single join edge
   - `double leftToRightFanout` (avg rows on right per left key)
   - `double rightToLeftFanout` (avg rows on left per right key)
   - `double outputSelectivity` (outputRows / crossProductRows)
   - `long observedLeftRows`, `long observedRightRows` (input sizes at time of observation)
   - `double confidence`

3. `HistoricalJoinEdgeFanoutStatistics` — multiple observations over time
   - `List<JoinEdgeFanoutEntry>` (last N runs, like HistoricalPlanStatistics)

4. `JoinEdgeFanoutStatisticsProvider` — SPI interface (parallel to `HistoryBasedPlanStatisticsProvider`)
   - `Map<JoinEdgeKey, HistoricalJoinEdgeFanoutStatistics> getFanoutStats(List<JoinEdgeKey>, timeout)`
   - `void putFanoutStats(Map<JoinEdgeKey, HistoricalJoinEdgeFanoutStatistics>)`

**Files to create:**
- `presto-spi/src/main/java/com/facebook/presto/spi/statistics/JoinEdgeKey.java`
- `presto-spi/src/main/java/com/facebook/presto/spi/statistics/JoinEdgeFanoutStatistics.java`
- `presto-spi/src/main/java/com/facebook/presto/spi/statistics/HistoricalJoinEdgeFanoutStatistics.java`
- `presto-spi/src/main/java/com/facebook/presto/spi/statistics/JoinEdgeFanoutEntry.java`
- `presto-spi/src/main/java/com/facebook/presto/spi/statistics/JoinEdgeFanoutStatisticsProvider.java`

**Files to modify:**
- None yet — these are purely additive SPI types.

---

### Phase 2: Join Edge Canonicalization

Build a canonicalization scheme for join edges that produces a stable hash
independent of the surrounding plan structure.

**New class: `JoinEdgeCanonicalizer`** in `presto-main-base/.../sql/planner/`

Given a `JoinNode`, produces a canonical key by:
1. For each side of the join, walk down to the leaf `TableScanNode`(s)
2. Canonicalize the table identity (connector + table handle)
3. Canonicalize filter predicates between the table scan and the join
   (preserving constants, normalizing variable names)
4. Canonicalize the join criteria (sort equi-clauses deterministically)
5. Order left/right deterministically (e.g., alphabetical by table name)
6. Hash the canonical representation → `JoinEdgeKey`

**Reuse from existing infrastructure:**
- `CanonicalPlanGenerator` already handles variable renaming, expression canonicalization,
  and deterministic ordering. We can reuse its expression canonicalization utilities.
- `CachingPlanCanonicalInfoProvider` already extracts input table statistics for leaf nodes.

**Key difference from subtree hashing:** We only hash the join edge's immediate
properties (tables, filters, join keys), NOT the full subtree. This means:
- `A JOIN B JOIN C` and `A JOIN B JOIN D` share the `(A, B)` edge fanout
- Different queries hitting the same tables with the same filters share fanout

**Files to create:**
- `presto-main-base/src/main/java/com/facebook/presto/sql/planner/JoinEdgeCanonicalizer.java`

**Files to modify:**
- None yet — this is a standalone utility.

---

### Phase 3: Recording Actual Fanout After Execution

Extend the existing HBO tracking infrastructure to record per-join-edge fanout
statistics after query execution completes.

**Modify `HistoryBasedPlanStatisticsTracker`:**
- In `getQueryStats()` (which already walks the plan tree and collects execution stats),
  add logic to extract per-join-edge fanout:
  - For each `JoinNode` in the executed plan:
    - Compute `JoinEdgeKey` using `JoinEdgeCanonicalizer`
    - Extract actual output rows from `PlanNodeStatsAndCostSummary`
    - Extract actual input rows for both sides
    - Compute `leftToRightFanout = outputRows / leftInputRows`
    - Compute `rightToLeftFanout = outputRows / rightInputRows`
    - Compute `outputSelectivity = outputRows / (leftInputRows * rightInputRows)`
  - Call `JoinEdgeFanoutStatisticsProvider.putFanoutStats()` with collected data

**Modify `HistoryBasedPlanStatisticsManager`:**
- Wire in `JoinEdgeFanoutStatisticsProvider` (injected via Guice)
- Create tracker with access to the provider

**New session properties:**
- `hbo_use_join_edge_fanout` (boolean, default false) — feature flag
- `hbo_join_edge_fanout_matching_threshold` (double, default 0.2) — how similar
  input table sizes need to be for a fanout match (±20%)

**Files to modify:**
- `presto-main-base/src/main/java/com/facebook/presto/cost/HistoryBasedPlanStatisticsTracker.java`
- `presto-main-base/src/main/java/com/facebook/presto/cost/HistoryBasedPlanStatisticsManager.java`
- `presto-main-base/src/main/java/com/facebook/presto/SystemSessionProperties.java`

---

### Phase 4: Cache Infrastructure

Add a parallel cache in `HistoryBasedStatisticsCacheManager` for join edge fanout,
following the same pattern as the existing subtree statistics cache.

**Modify `HistoryBasedStatisticsCacheManager`:**
- Add `Map<QueryId, LoadingCache<JoinEdgeKey, HistoricalJoinEdgeFanoutStatistics>> joinEdgeFanoutCache`
- Add `getJoinEdgeFanoutCache(queryId, provider, timeout)` method
- Cleanup on query completion (same lifecycle as existing caches)

**Pre-loading during planning:**
- Extend `HistoryBasedPlanStatisticsCalculator.registerPlan()`:
  - Walk the plan tree, find all `JoinNode`s
  - Compute `JoinEdgeKey` for each
  - Bulk-load fanout stats from provider (same timeout approach as existing HBO)

**Files to modify:**
- `presto-main-base/src/main/java/com/facebook/presto/cost/HistoryBasedStatisticsCacheManager.java`
- `presto-main-base/src/main/java/com/facebook/presto/cost/HistoryBasedPlanStatisticsCalculator.java`

---

### Phase 5: JoinStatsRule Integration

Modify `JoinStatsRule` to use cached fanout when available.

**Modify `JoinStatsRule.computeInnerJoinStats()`:**

```
Current flow:
  1. Compute crossJoinStats (leftRows * rightRows)
  2. filterByEquiJoinClauses() → apply NDV-based selectivity
  3. If unknown, use defaultJoinSelectivityCoefficient (0.15)

New flow:
  1. Compute crossJoinStats (leftRows * rightRows)
  2. Check joinEdgeFanoutCache for this JoinNode's edge key
  3. If fanout available AND input sizes match within threshold:
     a. Use historical outputSelectivity to compute outputRows
     b. Set confidence to HIGH (or whatever HBO uses)
  4. Else: fall back to filterByEquiJoinClauses() (existing CBO logic)
```

**Matching logic:**
- Compare current `leftRows` and `rightRows` against the historical
  `observedLeftRows` and `observedRightRows`
- If within `hbo_join_edge_fanout_matching_threshold`, use the historical fanout
- If multiple historical entries exist, select the closest match by input size ratio

**How fanout feeds into cost:**
- `outputRows = leftRows * leftToRightFanout` (or equivalently `rightRows * rightToLeftFanout`)
- This replaces the NDV-based `leftRows * rightRows / max(leftNdv, rightNdv)` formula
- The fanout implicitly captures skew, NULL distribution, and correlation

**Files to modify:**
- `presto-main-base/src/main/java/com/facebook/presto/cost/JoinStatsRule.java`

---

### Phase 6: In-Memory Provider for Testing

Implement a simple in-memory `JoinEdgeFanoutStatisticsProvider` for testing.

**Files to create:**
- `presto-main-base/src/main/java/com/facebook/presto/cost/InMemoryJoinEdgeFanoutStatisticsProvider.java`

**Tests to create:**
- Unit tests for `JoinEdgeCanonicalizer` — verify same edge in different queries produces same key
- Unit tests for `JoinStatsRule` with fanout — verify fanout overrides NDV estimate
- Integration test — run a query, verify fanout is recorded, run again, verify fanout is used
- Test that different filter values produce different edge keys
- Test that input size matching threshold works correctly

---

### Phase 7 (Future): Sampling on Cache Miss

This phase adds Axiom-style sampling for cold start. It's the most architecturally
novel piece and can be deferred — Phases 1-6 already provide the core benefit for
recurring queries.

**Approach: Internal query dispatch**

On cache miss for a join edge:
1. Construct a sampling query:
   ```sql
   SELECT hash_combine(hash(key1), hash(key2)) AS h, COUNT(*) AS cnt
   FROM tableA
   WHERE (hash(key1) % 10000) < sampleFraction
     AND <original filters>
   GROUP BY 1
   ```
   (And similarly for tableB)
2. Execute as an internal query (similar to ANALYZE mechanism)
3. Build frequency maps, compute fanout
4. Store in HBO cache

**Challenges to address:**
- Planning latency: Use async execution with timeout, fall back to CBO if too slow
- Resource isolation: Internal sample queries should not compete with user queries
  (use a separate resource group or priority)
- Query complexity: For simple base table joins this is straightforward; for joins
  against subqueries/CTEs, fall back to execution-based recording (Phase 3)

**Existing infrastructure to reuse:**
- `SampledSplitSource` for split-level sampling
- `StatisticsAggregationPlanner` for building aggregation queries
- The ANALYZE execution path as a reference for internal query dispatch

---

## Dependency Graph

```
Phase 1 (SPI types)
    │
    ├──► Phase 2 (Canonicalization)
    │        │
    │        ├──► Phase 3 (Recording after execution)
    │        │        │
    │        │        └──► Phase 4 (Cache infrastructure)
    │        │                 │
    │        │                 └──► Phase 5 (JoinStatsRule integration)
    │        │                          │
    │        │                          └──► Phase 6 (Testing)
    │        │                                   │
    │        │                                   └──► Phase 7 (Sampling - future)
    │        │
    │        └──► (Phase 2 also needed by Phase 5 for cache lookup key)
```

Phases 1-2 are pure infrastructure with no behavioral change.
Phase 3 starts recording data (write path only, no read path yet).
Phase 4-5 close the loop (read path — optimizer uses the data).
Phase 6 validates everything.
Phase 7 is an enhancement for cold start.

## Risk Assessment

| Risk | Mitigation |
|------|-----------|
| Fanout from prior run doesn't match current data | Input size matching threshold (±20%); fall back to CBO when no match |
| Cache pollution from outlier queries | Store last N runs, use median; discard entries with very small input sizes |
| Planning latency from cache lookups | Bulk pre-load during registerPlan() with timeout, same as existing HBO |
| Join edge key collisions | SHA256 hash of canonical form; extremely unlikely |
| Filter changes invalidate cached fanout | By design — different filters produce different keys. This is correct behavior. |
| Complex subquery inputs (not base tables) | Only record fanout for join edges whose inputs resolve to base table scans. Subquery-backed joins fall back to CBO. |

## Session Properties Summary

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `hbo_use_join_edge_fanout` | boolean | false | Enable per-join-edge fanout statistics |
| `hbo_join_edge_fanout_matching_threshold` | double | 0.2 | Input size similarity threshold for matching |
| `hbo_join_edge_fanout_max_history` | int | 10 | Max historical runs to keep per edge |
