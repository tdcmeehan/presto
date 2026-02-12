# Presto Adaptive Optimizer

## What It Does

The Adaptive Optimizer teaches Presto's query planner about the actual characteristics
of your data — automatically, with zero manual intervention.

Today, Presto's cost-based optimizer (CBO) relies on pre-collected table statistics to
make decisions like join ordering and aggregation strategy. In practice, most lakehouse
deployments have no statistics at all. When stats are missing, the optimizer guesses —
and guesses wrong. Bad join orders and suboptimal aggregation strategies are among the
most common causes of slow queries.

The Adaptive Optimizer fixes this by **sampling real data on demand** and caching what
it learns. The first time Presto sees a new join edge or grouping pattern, it reads a
small sample (~10K rows) from the underlying tables and measures the actual data
characteristics. The result is cached in the existing HBO (History-Based Optimization)
infrastructure and reused across all future queries — regardless of filters, query
structure, or who wrote the query.

The more queries run against a cluster, the more the optimizer learns. Statistics
accumulate following the Pareto principle: the tables and join patterns that matter most
get learned first because they appear most frequently.

## What It Improves

### 1. Join Ordering

**The problem:** Without statistics, CBO assumes join keys are uniformly distributed
and estimates selectivity as `1 / max(NDV_left, NDV_right)`. With skewed real-world
data, this can be off by orders of magnitude, leading to catastrophic join orders
(e.g., building a hash table from 1B rows instead of 1K rows).

**The fix:** Sample the actual join key frequency distribution from both tables.
Measure empirical fanout — how many rows on the right actually match each row on the
left, and vice versa. Cache per (table pair, join keys). Reuse across all queries
joining those tables, regardless of filters.

### 2. Aggregation Cardinality

**The problem:** For multi-column `GROUP BY`, CBO multiplies per-column NDV values
assuming independence. `GROUP BY (state, city)` with NDV(state)=50 and NDV(city)=10,000
estimates 500,000 groups. The actual number is ~1,000 because each state has only ~20
cities. This 500x overestimate cascades into bad decisions downstream — wrong join
orders when the aggregation feeds a join, and suboptimal partial aggregation strategy.

**The fix:** Sample the actual compound-key distinct count from the table. Cache per
(table, grouping keys). The sampled reduction factor captures column correlations that
per-column NDV cannot represent.

### 3. Partial Aggregation Decisions

**The problem:** Presto can push partial aggregation before a shuffle (exchange) to
reduce network traffic. But for multi-column grouping keys, it has no way to estimate
whether partial aggregation actually reduces data — so it always pushes it, even when
it adds overhead without benefit. Today this is only optimized for single-key
aggregations with HBO execution history from prior runs.

**The fix:** The adaptive reduction factor tells the optimizer whether partial
aggregation is worthwhile before the query ever runs. Multi-key aggregations get
informed decisions instead of blind defaults.

### 4. Filter Selectivity

**The problem:** Without statistics, CBO uses heuristic constants (e.g., 0.9 for
unknown filters) that bear no relation to the actual data.

**The fix:** Sample 1% of the table with the filter applied, measure the pass rate.
Cache per (table, filter predicate).

## How It Works

Three types of statistics, collected independently and composed at query time:

| Statistic | Cache Key | Filter in Key? | What's Measured |
|-----------|-----------|----------------|-----------------|
| Join fanout | (table pair, join keys) | No | Avg matching rows per key |
| Aggregation reduction | (table, group-by keys) | No | Distinct groups / total rows |
| Filter selectivity | (table, filter predicate) | Yes | Rows passing / rows scanned |

**Composition example:**
```
Query: SELECT ... FROM orders WHERE ds='2024-01-01' JOIN customers ON custkey
                                    GROUP BY state, city

Filter selectivity:     orders with ds='2024-01-01'  → 3% of rows pass
Join fanout:            orders ⋈ customers on custkey → 1.3 matches per order
Aggregation reduction:  customers by (state, city)    → 0.1% reduction

Estimated join output:  orders_rows × 0.03 × 1.3
Estimated agg output:   join_output × 0.001
```

Join fanout and aggregation reduction are sampled from **unfiltered** base tables —
the cache entry is reused across all queries regardless of WHERE clauses. Filter
selectivity is the only piece that varies with the query. This maximizes cache hit rate.

## What We Build On

The Adaptive Optimizer extends infrastructure that already exists in Presto:

| Component | Status | What We Add |
|-----------|--------|-------------|
| HBO Redis provider | Exists | 6 new methods (3 get + 3 put) for adaptive stats |
| HBO cache manager | Exists | 3 new `LoadingCache` instances alongside existing ones |
| HBO plan registration | Exists | Extend tree walk to identify join/filter/agg nodes |
| Canonical plan generator | Exists | 3 small canonicalizers reusing existing utilities |
| `SampledSplitSource` | Exists | Used directly for split-level sampling |
| `StatsCalculator` pipeline | Exists | Modify 2 rules: `JoinStatsRule`, `AggregationStatsRule` |
| `JoinFanoutEstimate` type | **New** | 1 new SPI type |
| Samplers | **New** | 3 sampler classes (~200 lines each) |

No new infrastructure systems. No new storage backends. No new execution engines.
The adaptive optimizer is a layer on top of the existing HBO and CBO infrastructure.

## Scope of Changes

| Area | Files Modified | Files Created | Complexity |
|------|---------------|---------------|------------|
| SPI types | 1 | 1 | Low |
| Canonicalization | 0 | 3 | Medium |
| Sampling | 0 | 3-4 | Medium |
| Cache infrastructure | 3 | 0 | Low |
| Stats rule integration | 3 | 0 | Medium |
| Tests | 1 | 6 | Low |
| **Total** | **8** | **13-14** | |

Estimated **~2,000-3,000 lines of new code** (excluding tests), primarily
straightforward plumbing following established patterns in the codebase.

## Value vs. Effort

**Why the effort is small:**
- HBO infrastructure (Redis, caching, canonical plans, confidence selection) already exists
- Sampling algorithm is well-understood (proven in Axiom's production deployment)
- Integration follows existing patterns (same stats calculator pipeline, same provider SPI)
- No architectural changes — purely additive on existing systems

**Why the value is large:**
- Eliminates the #1 prerequisite for effective CBO in lakehouses: pre-collected statistics
- Statistics accumulate automatically following workload patterns (Pareto principle)
- Fixes join ordering, aggregation estimation, and partial agg decisions simultaneously
- Works across all connectors that support split-level sampling (Hive, Iceberg, Delta)
- Zero operational burden — no ANALYZE jobs to schedule, no stats collection pipelines to maintain
- Complements (not replaces) existing HBO — exact subtree matches still use HBO history;
  adaptive stats fill in when HBO has no match

## Rollout Strategy

Feature-flagged behind `adaptive_optimizer_enabled` (default: false). Individual
components can be toggled independently:
- `adaptive_optimizer_join_fanout_enabled`
- `adaptive_optimizer_aggregation_reduction_enabled`
- `adaptive_optimizer_filter_selectivity_enabled`

Async sampling by default — first query on a cold cache falls back to CBO, sampling
runs in background, subsequent queries benefit. No planning latency regression.

## Known Limitations

The composition formula (`fanout × selectivity`) assumes filter predicates don't
change join key distribution. This independence assumption can break when filter
columns correlate with join/grouping keys. In practice this is usually a much smaller
error than the CBO's uniformity assumption it replaces. A future enhancement adds
post-execution validation to detect and correct for correlated cases.
