# TPC-DS Optimization Techniques: Database Research Literature Survey

## Overview

This document surveys the database research literature for optimization techniques that have the most significant impact on TPC-DS (Transaction Processing Performance Council - Decision Support) benchmark performance. TPC-DS models a retail decision-support workload with 99 complex SQL queries against 24 tables (1 GB to 3 TB), testing joins, aggregations, subqueries, window functions, and set operations. For each technique, we summarize the research findings, measured impact, and current implementation status in Presto.

---

## 1. Dynamic Filtering / Runtime Filters

### Research Findings

Dynamic filtering creates runtime predicates on the probe side of a join based on values collected from the build side. This is one of the single most impactful optimizations for TPC-DS because the benchmark's star/snowflake schema queries repeatedly join large fact tables (e.g., `store_sales`, `web_sales`) against small, heavily filtered dimension tables (e.g., `date_dim`, `item`, `customer`).

**Measured Impact:**
- Individual TPC-DS queries show up to **9x speedup** (e.g., TPC-DS Q71) when dynamic filtering is enabled (Varada/Trino benchmarks).
- Dynamic partition pruning improved 29 TPC-DS queries by **10-30%** with **20% CPU reduction** and **27% less data read** (Trino measurements).
- Bloom filter-based runtime filters (used by PolarDB-X, StarRocks, Doris) provide type-agnostic filtering with low space/time complexity and can be pushed down to storage nodes to reduce network I/O.

**Key Design Considerations:**
- Build-side selection is critical: the CBO must choose the smaller dimension table as the build side.
- Threshold management: systems maintain `max-distinct-values-per-driver` thresholds; when exceeded, they degrade to min/max range filters which are less precise but still beneficial.
- Distributed coordination: filter values must be passed from build-side workers to probe-side scan operators, potentially via coordinator-based collection services.

### Presto Implementation Status: **Fully Implemented**

- `LocalDynamicFilter.java` handles build-side value collection using `TupleDomain<VariableReferenceExpression>`.
- `DynamicFilterSourceOperator.java` collects values at runtime.
- `RemoveUnsupportedDynamicFilters.java` cleans up dynamic filters that cannot be applied.
- Supports inner and right joins with equality and inequality conditions.

**Potential Gaps:** Presto's dynamic filtering is local (within a single pipeline). Large-scale distributed dynamic filtering with bloom filters pushed to storage connectors could provide additional gains, particularly for partitioned joins across many workers.

---

## 2. Cost-Based Join Reordering

### Research Findings

Join ordering is widely recognized as the most important decision a query optimizer makes. TPC-DS queries routinely join 5-10+ tables, and the search space of possible orderings grows factorially. Poor join orders can cause orders-of-magnitude performance regression.

**Measured Impact:**
- The VLDB 2024 paper "Still Asking: How Good Are Query Optimizers, Really?" (Leis et al.) demonstrates that even state-of-the-art optimizers frequently produce suboptimal join orders, with **up to 100x performance gaps** between optimal and chosen plans.
- POLAR (VLDB 2024) introduces adaptive, non-invasive join order selection that significantly reduces worst-case plans.
- FOSS (reinforcement learning-based) achieves **1.30x to 9.09x total latency speedup** over PostgreSQL and **1.35x to 7.14x over MySQL** across TPC-DS, JOB, and Stack Overflow workloads.

**Key Design Considerations:**
- Cardinality estimation accuracy is the primary bottleneck: join ordering quality is limited by the quality of row-count estimates for intermediate joins.
- Exhaustive enumeration (dynamic programming) is optimal for small join counts (<10-12 tables) but requires heuristics for larger queries.
- History-based and learned approaches can compensate for estimation errors by leveraging actual execution statistics from prior runs.

### Presto Implementation Status: **Fully Implemented**

- `ReorderJoins.java` implements cost-based join reordering with a configurable maximum of 9 joins (default).
- `DetermineJoinDistributionType.java` selects between replicated (broadcast) and partitioned (hash) join strategies based on cost.
- `EliminateCrossJoins.java` removes unnecessary cross joins.
- `CostCalculator.java` uses a weighted model (75% CPU, 10% memory, 15% network).

**Potential Gaps:** The default limit of 9 reorderable joins may be insufficient for some TPC-DS queries with many joins. History-based plan statistics (`HistoryBasedPlanStatisticsCalculator.java`) exist but may not be widely used to correct estimation errors on repeated query patterns.

---

## 3. Adaptive Query Execution (AQE)

### Research Findings

Adaptive Query Execution re-optimizes query plans at runtime based on actual data statistics collected at pipeline boundaries (shuffles, exchanges). This addresses the fundamental limitation of static cost-based optimization: inaccurate cardinality estimates.

**Measured Impact:**
- Spark's AQE yields up to **8x speedup** on individual TPC-DS queries and **25x per-query speedup** in extended lakehouses-at-scale evaluations (Databricks 2024).
- Three key features: (1) dynamic partition coalescing, (2) dynamic join strategy switching, and (3) skew join optimization.
- Even on TPC-DS's synthetic (non-skewed) data, AQE improves 32 queries by more than 1.1x; real-world workloads with skewed data see dramatically larger gains.

**Key Design Considerations:**
- AQE exploits natural pipeline breakers (shuffles/exchanges) to collect accurate statistics without introducing additional materialization points.
- Dynamic join strategy switching (e.g., converting sort-merge join to broadcast hash join at runtime) eliminates the cost of mispredicted join strategies.
- Dynamic partition coalescing reduces the overhead of too-small partitions created by hash partitioning.

### Presto Implementation Status: **Partially Implemented**

- Presto has `runtimeOptimizerEnabled` configuration but lacks the full spectrum of AQE capabilities seen in Spark.
- Dynamic filtering provides some runtime adaptivity for join probe-side filtering.
- No evidence of runtime join strategy switching or dynamic partition coalescing.

**Potential Gaps:** This is a significant area for improvement. Full AQE with runtime re-optimization at exchange boundaries could substantially improve Presto's TPC-DS performance, especially for queries where cardinality estimates are inaccurate.

---

## 4. Cardinality Estimation & Statistics

### Research Findings

Accurate cardinality estimation underlies every cost-based optimization decision. TPC-DS specifically challenges estimators with multi-table joins, correlated predicates, and complex expressions.

**Measured Impact:**
- The "Looking Glass" paper (Leis et al.) showed that **cardinality estimation errors compound exponentially** across multi-way joins, causing plans that are 10-1000x slower than optimal.
- ML-based approaches (Abbasi et al. 2024) using supervised/unsupervised learning for PostgreSQL query optimization demonstrated a **42% decrease in query execution times** on TPC-DS by improving cardinality estimates.
- History-based optimization (HBO) that learns from prior executions can largely eliminate estimation errors for recurring query patterns.

**Key Design Considerations:**
- Column correlation awareness: TPC-DS has correlated columns (e.g., `date_dim.d_year` and `date_dim.d_moy`) that independence-assumption estimators handle poorly.
- Multi-column statistics (histograms, sketches) provide better estimates but are expensive to compute and maintain.
- Feedback-driven estimation (using actual runtime cardinalities to update estimates) converges quickly for repeated query patterns.

### Presto Implementation Status: **Fully Implemented (with room for improvement)**

- `StatsCalculator.java` and associated rules (`TableScanStatsRule`, `FilterStatsRule`, `JoinStatsRule`, `AggregationStatsRule`) provide cardinality estimation.
- `HistoryBasedPlanStatisticsCalculator.java` supports learning from prior executions.
- `EqualityInference.java` and `InequalityInference.java` derive additional predicates.

**Potential Gaps:** Column correlation detection, multi-column histograms, and sketch-based estimators could improve estimation quality for complex TPC-DS predicates. The history-based optimizer could be more aggressively leveraged.

---

## 5. Vectorized Execution & SIMD

### Research Findings

Vectorized execution processes data in columnar batches (typically 1K-4K rows) rather than row-at-a-time, enabling SIMD instructions, better CPU cache utilization, and reduced interpretation overhead. This is the dominant execution paradigm in modern high-performance OLAP engines.

**Measured Impact:**
- StarRocks' vectorized engine achieves **3-10x operator speedup** via SIMD; Trino's overall query time is **5.54x slower** than StarRocks on TPC-DS 1TB.
- Databricks Photon (native vectorized engine) holds the TPC-DS 100TB world record.
- CockroachDB saw **up to 70x CPU time improvement** in microbenchmarks and **4x end-to-end** improvement on TPC-H after adding vectorized execution.
- VLDB comparison (Tectorwise vs. Typer) shows vectorized and compiled approaches perform within ~74% of each other, but both are **1-2 orders of magnitude faster** than traditional row-at-a-time engines.

**Key Design Considerations:**
- Requires columnar data layout throughout the execution pipeline (storage, network, memory).
- Full vectorization is a major engineering undertaking: all operators (filter, join, aggregate, sort, window) must be rewritten.
- Late materialization (deferring tuple construction) is critical for preserving vectorization benefits.

### Presto Implementation Status: **Partially Implemented**

- Presto uses block/page-at-a-time processing (batch-oriented) but not full columnar vectorization with SIMD.
- `ExpressionCompiler.java` compiles expressions to JVM bytecode, providing some compilation benefits.
- Operators process `Page` objects containing `Block` columns, which is structurally columnar but not SIMD-optimized.

**Potential Gaps:** This is the largest performance gap between Presto and modern OLAP engines like StarRocks, ClickHouse, and DuckDB. Moving to a native vectorized execution engine (similar to Meta's Velox, which Presto's native execution project targets) could provide multi-fold speedups on TPC-DS.

---

## 6. Predicate Pushdown & Partition Pruning

### Research Findings

Predicate pushdown reduces data scanned by pushing filter conditions as close to the storage layer as possible. Partition pruning extends this by eliminating entire data partitions before reading.

**Measured Impact:**
- Partition pruning can eliminate 90%+ of I/O for time-bounded TPC-DS queries on date-partitioned fact tables.
- Combined with dynamic filtering, pushdown enables near-index-like selectivity on fact table scans.
- Column-level predicate pushdown to file formats (Parquet/ORC row group pruning) provides additional filtering at the storage layer.

**Key Design Considerations:**
- Connector integration is critical: the optimizer must communicate predicate domains to the storage connector.
- File-format-level statistics (Parquet/ORC min/max, bloom filters) enable sub-partition pruning.
- Transitive predicate derivation (inferring new predicates from join conditions and existing filters) expands pruning opportunities.

### Presto Implementation Status: **Fully Implemented**

- `PredicatePushDown.java` (102KB) is one of the largest optimization components, supporting pushdown through joins, aggregations, and projections.
- `PickTableLayout.java` translates predicates to `TupleDomain` constraints for connector-level partition elimination.
- `RowExpressionDomainTranslator.java` converts expressions to column domains.
- Hive/Iceberg connectors use these domains for partition pruning and ORC/Parquet file pruning.

**Potential Gaps:** Ensuring that predicate pushdown works correctly through CTEs and complex subqueries. CTE materialization can prevent predicate pushdown if predicates cannot be pushed into the CTE definition.

---

## 7. Subquery Decorrelation

### Research Findings

TPC-DS makes heavy use of correlated subqueries (EXISTS, IN, scalar subqueries). Naive correlated execution evaluates the subquery once per outer row, which is catastrophically slow. Decorrelation transforms these into joins or semi-joins that can be executed in bulk.

**Measured Impact:**
- Without decorrelation, correlated subqueries in TPC-DS can cause queries to run for hours instead of seconds.
- Effective decorrelation is a prerequisite for competitive TPC-DS performance rather than an incremental optimization.
- Nested aggregation planning (GroupJoin, VLDB Journal 2022) provides further optimization for correlated aggregation subqueries.

**Key Design Considerations:**
- Must handle scalar, IN, EXISTS, and lateral subquery correlations.
- Edge cases around NULL semantics (NOT IN vs. NOT EXISTS) require careful transformation.
- Some correlated patterns resist decorrelation and may need specialized operators (e.g., nested loop with lateral join).

### Presto Implementation Status: **Fully Implemented**

- `PlanNodeDecorrelator.java` provides the core decorrelation framework.
- `TransformCorrelatedScalarSubquery.java`, `TransformCorrelatedInPredicateToJoin.java`, `TransformUncorrelatedInPredicateSubqueryToSemiJoin.java` handle specific patterns.
- `TransformCorrelatedLateralJoinToJoin.java` handles lateral join decorrelation.

**Potential Gaps:** Some complex multi-level correlated subqueries may not decorrelate fully. The `GroupJoin` optimization (fusing join with grouped aggregation) is not present in Presto.

---

## 8. Window Function Optimization

### Research Findings

TPC-DS uses window functions extensively (RANK, ROW_NUMBER, SUM OVER, etc.). Window function optimization focuses on reducing redundant sorts and partitionings.

**Measured Impact:**
- Merging compatible window functions (same PARTITION BY / ORDER BY) eliminates redundant data shuffles and sorts, which can be **2-5x faster** for queries with multiple window functions.
- Segment-based execution (detecting partition boundaries during streaming) avoids full materialization.

**Key Design Considerations:**
- Window function ordering: arranging window functions to maximize partition/sort reuse.
- Removing redundant window functions (e.g., converting RANK-based filters to TopN).
- Pushing filters through window operations when semantically valid (e.g., `WHERE rank <= 10` can become a TopN).

### Presto Implementation Status: **Fully Implemented**

- `GatherAndMergeWindows.java` combines compatible window functions sharing the same partitioning/ordering.
- `WindowFilterPushDown.java` pushes filters below window operations.
- `MinMaxByToWindowFunction.java` converts aggregates to efficient window functions.
- `TopNRowNumberNode` optimizes `ROW_NUMBER() <= N` patterns into partial TopN operations.

**Potential Gaps:** Limited compared to other areas. Further optimization of multi-window-function queries with partially overlapping partition/order specifications could help.

---

## 9. Data Skew Handling

### Research Findings

Data skew causes load imbalance in parallel execution, where one partition receives disproportionately more data than others, creating a bottleneck.

**Measured Impact:**
- PRPD (Partial Redistribution & Partial Duplication, SIGMOD 2008) significantly speeds up parallel joins under skew.
- Spark AQE's skew join optimization detects skew from shuffle statistics and splits skewed partitions, achieving **up to 81% reduction in execution time** under moderate to high skew.
- Oracle's adaptive parallel execution uses multi-stage parallelization with runtime distribution decisions.

**Key Design Considerations:**
- TPC-DS synthetic data has relatively uniform distributions, so skew handling primarily benefits real-world deployments rather than the benchmark itself.
- However, certain TPC-DS queries (e.g., those grouping by low-cardinality columns) can create partial aggregation skew.
- Runtime detection is preferred over static analysis since skew patterns depend on data and predicate selectivity.

### Presto Implementation Status: **Limited**

- Presto supports `PARTITIONED` and `REPLICATED` join strategies but lacks automatic skew detection and mitigation.
- No evidence of runtime partition splitting for skewed keys.
- Partial aggregation can mitigate some aggregation skew.

**Potential Gaps:** Adding runtime skew detection at exchanges (similar to Spark AQE) and automatic partition splitting would improve robustness on real-world workloads.

---

## 10. Common Table Expression (CTE) Materialization

### Research Findings

TPC-DS queries frequently use CTEs (WITH clauses) and reference them multiple times. Materializing a CTE once and reusing it avoids redundant computation.

**Measured Impact:**
- For queries that reference a CTE multiple times, materialization can cut execution time by **50% or more** by eliminating redundant scans and computations.
- The trade-off is materialization overhead (writing/reading intermediate results) vs. recomputation cost.

**Key Design Considerations:**
- Heuristic: materialize when referenced more than once and the CTE is expensive to compute.
- Cost-based: compare materialization + read cost vs. recomputation cost.
- Predicate pushdown into materialized CTEs requires special handling.

### Presto Implementation Status: **Implemented**

- `CteProjectionAndPredicatePushDown.java` handles CTE-specific predicate pushdown.
- CTE materialization strategy exists with single-use inlining.

**Potential Gaps:** Cost-based decision-making about when to materialize vs. inline CTEs could be improved.

---

## Ranked Impact Summary

Based on the research literature, here are the optimization techniques ranked by their measured impact on TPC-DS performance:

| Rank | Technique | Typical TPC-DS Impact | Presto Status |
|------|-----------|----------------------|---------------|
| 1 | **Vectorized Execution / SIMD** | 3-10x overall throughput | Partial (Velox/native in progress) |
| 2 | **Dynamic Filtering** | Up to 9x on selective joins | Fully implemented |
| 3 | **Adaptive Query Execution** | Up to 25x per-query | Partially implemented |
| 4 | **Cost-Based Join Reordering** | Up to 100x for bad orderings | Fully implemented |
| 5 | **Cardinality Estimation (ML/HBO)** | 42% overall improvement | Implemented, improvable |
| 6 | **Predicate Pushdown / Partition Pruning** | 90%+ I/O reduction | Fully implemented |
| 7 | **Subquery Decorrelation** | Required for correctness | Fully implemented |
| 8 | **CTE Materialization** | 50%+ for multi-ref CTEs | Implemented |
| 9 | **Window Function Merging** | 2-5x for window-heavy queries | Fully implemented |
| 10 | **Data Skew Handling** | Up to 81% time reduction | Limited |

## Key Recommendations for Presto TPC-DS Improvement

Based on this research, the highest-impact improvements for Presto's TPC-DS performance would be:

1. **Native Vectorized Execution (Velox):** The single largest performance gap. Presto's native execution project using Velox is the right direction and would close the 3-5x gap with engines like StarRocks and ClickHouse.

2. **Full Adaptive Query Execution:** Adding runtime re-optimization at exchange boundaries, including dynamic join strategy switching and dynamic partition coalescing, would address cardinality estimation failures.

3. **Enhanced Dynamic Filtering:** Extending dynamic filtering with bloom filters that can be pushed to storage connectors (ORC/Parquet bloom filter integration) and supporting distributed dynamic filtering for large-scale partitioned joins.

4. **Improved Cardinality Estimation:** Leveraging history-based optimization more aggressively, adding column correlation awareness, and considering ML-based cardinality estimation for complex predicates.

5. **Runtime Skew Mitigation:** Adding skew detection at exchanges and automatic partition splitting for skewed join keys.

---

## References

- Leis et al., "Still Asking: How Good Are Query Optimizers, Really?" PVLDB Vol. 18 (2025)
- Leis et al., "Query Optimization Through the Looking Glass" TU Munich (2018)
- Abbasi et al., "Machine Learning Approaches for Enhancing Query Optimization" (2024)
- Xu et al., "Handling data skew in parallel joins in shared-nothing systems" ACM SIGMOD (2008)
- Databricks, "Adaptive Query Execution: Speeding Up Spark SQL at Runtime" (2020)
- Databricks, "Photon: A Fast Query Engine for Lakehouse Systems" SIGMOD (2022)
- Kersten et al., "Everything You Always Wanted to Know About Compiled and Vectorized Queries But Were Afraid to Ask" PVLDB Vol. 11 (2018)
- Trino Blog, "Dynamic filtering for highly-selective join optimization" (2019)
- Trino Blog, "Dynamic partition pruning" (2020)
- Querify Labs, "Dynamic Filtering: a Critical Performance Optimization in Analytical Engines"
- Alibaba Cloud, "Query Performance Optimization – Runtime Filter" (PolarDB-X)
- StarRocks Documentation, "TPC-DS Benchmarking"
- CockroachDB Blog, "How we built a vectorized execution engine"
- Dreseler et al., "Quantifying TPC-H Choke Points and Their Optimizations" PVLDB Vol. 13 (2020)
