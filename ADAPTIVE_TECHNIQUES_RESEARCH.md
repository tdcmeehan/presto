# Adaptive Query Processing Techniques for Presto with Velox

## Executive Summary

This document presents comprehensive research on adaptive query processing techniques that could significantly improve Presto's performance with Velox as the execution engine, particularly for data lake and lakehouse environments where statistics are often incomplete or stale.

**Key Finding**: Data lakes suffer from "spotty statistics" because:
1. Statistics collection is expensive and often skipped
2. Data evolves continuously, making statistics stale
3. Complex file formats (Parquet, ORC, Iceberg) have varying metadata quality
4. Multi-engine access means no single system owns statistics maintenance

Adaptive query processing (AQP) addresses these challenges by making runtime decisions based on observed execution characteristics rather than relying solely on pre-computed statistics.

---

## Table of Contents

1. [Current State of Adaptive Features in Presto](#1-current-state-of-adaptive-features-in-presto)
2. [Academic Research on Adaptive Query Processing](#2-academic-research-on-adaptive-query-processing)
3. [Adaptive Techniques in Modern Data Systems](#3-adaptive-techniques-in-modern-data-systems)
4. [Velox-Specific Opportunities](#4-velox-specific-opportunities)
5. [Recommended Research Directions](#5-recommended-research-directions)
6. [Implementation Roadmap](#6-implementation-roadmap)
7. [References](#7-references)

---

## 1. Current State of Adaptive Features in Presto

### 1.1 Existing Adaptive Capabilities

Presto already implements several adaptive features, though many are limited to the Presto-on-Spark execution path:

#### Dynamic Filtering
**Location**: `presto-main-base/src/main/java/com/facebook/presto/operator/DynamicFilterSourceOperator.java`

- Collects values from broadcast join build side
- Creates runtime filtering constraints pushed to probe-side scans
- Supports value lists (small builds) and min/max ranges (large builds)
- **Gap**: Velox collects statistics but lacks feedback loop to coordinator

#### History-Based Plan Statistics
**Location**: `presto-main-base/src/main/java/com/facebook/presto/cost/HistoryBasedPlanStatisticsCalculator.java`

- Records executed query plans and actual statistics
- Uses historical data to improve future plan choices
- Supports confidence-based broadcast decisions
- **Session Properties**: `use_history_based_plan_statistics`, `track_history_based_plan_statistics`

#### Adaptive Join Side Switching (Spark Path)
**Location**: `presto-spark-base/src/main/java/com/facebook/presto/spark/planner/optimizers/PickJoinSides.java`

- Dynamically selects join build/probe sides based on runtime size estimates
- Re-optimizes remaining plan fragments after early stages complete
- **Session Property**: `adaptive_join_side_switching_enabled`

#### Confidence-Based Broadcast Optimization
**Location**: `presto-main-base/src/main/java/com/facebook/presto/sql/planner/iterative/ConfidenceBasedBroadcastUtil.java`

- Chooses distribution type based on statistic confidence levels
- Handles low-confidence zero estimations specially
- **Session Property**: `confidence_based_broadcast_enabled`

#### Adaptive Partial Aggregation
**Location**: `presto-main-base/src/main/java/com/facebook/presto/sql/planner/LocalExecutionPlanner.java`

- Monitors row count reduction ratio during partial aggregation
- Can disable partial aggregation when reduction is poor
- **Config**: `experimental.adaptive-partial-aggregation`

#### Memory-Based Adaptations
**Location**: `presto-main/src/main/java/com/facebook/presto/execution/MemoryRevokingScheduler.java`

- Monitors memory pool usage and triggers spilling
- Per-task and per-pool revoking strategies
- Configurable thresholds (0.9 default trigger, 0.5 default target)

### 1.2 Gaps in Current Implementation

| Feature | Java Engine | Velox Engine | Gap |
|---------|------------|--------------|-----|
| Adaptive Join Selection | Via Spark AQE | Not implemented | Missing join algorithm switching |
| Runtime Re-optimization | Fragment-level (Spark) | None | No mid-query replan capability |
| Dynamic Filter Feedback | Full implementation | Statistics only | Missing selectivity tracking |
| Memory Arbitration | Global + per-pool | Periodic check only | No operator-level decisions |
| Skew Handling | Basic detection | Histogram collection | No automatic mitigation |

---

## 2. Academic Research on Adaptive Query Processing

### 2.1 Foundational Techniques

#### Eddies: Continuously Adaptive Query Processing
**Authors**: Avnur & Hellerstein (UC Berkeley, SIGMOD 2000)

Eddies treat query processing as **tuple routing**, making per-tuple decisions about operator ordering. Key concepts:

- **Adaptive Routing**: Each tuple can take a different path through operators
- **Lottery Scheduling**: Probabilistic operator selection based on observed performance
- **State Modules (SteMs)**: Encapsulate join state to enable operator reordering
- **Symmetric Hash Join**: Enables mid-stream join reordering

**Applicability to Presto/Velox**: While per-tuple routing is too fine-grained for vectorized execution, the concept of **batch-level routing** could work:
- Route batches of tuples through operators based on learned selectivity
- Reorder operators within a pipeline dynamically
- Particularly valuable for multi-way joins with uncertain selectivities

#### Progressive/Mid-Query Re-optimization
**Key Paper**: "Robust Query Processing Through Progressive Optimization" (SIGMOD 2004)

Progressive optimization validates cardinality estimates against actual values during execution:
1. Insert **checkpoints** at materialization points in the query plan
2. Measure actual cardinalities at checkpoints
3. If deviation exceeds threshold, **re-invoke optimizer** with updated statistics
4. Continue with potentially different plan for remaining work

**Applicability to Presto/Velox**:
- Natural checkpoint locations: shuffle boundaries, broadcast collection, spill points
- Could implement progressive optimization for the Velox path
- Re-optimization decisions could be made at the coordinator

#### Parametric Query Optimization (PQO)
**Key Paper**: "Kepler: Robust Learning for Parametric Query Optimization" (SIGMOD 2023)

PQO addresses queries with parameters that affect optimal plan choice:
- **Row Count Evolution (RCE)**: Generate plan candidates by perturbing sub-plan cardinalities
- **Neural Network Uncertainty**: Predict fastest plan while avoiding regressions
- **Plan Caching**: Store multiple plans per query template

**Applicability to Presto/Velox**:
- Data lake queries often parameterized by partition predicates
- Could cache multiple plans per query pattern with different cardinality assumptions
- Select plan at runtime based on observed/estimated cardinalities

### 2.2 Robust Query Processing

#### POLAR: Adaptive Join Reordering
**Authors**: BIFOLD Berlin (2024)

POLAR is a non-invasive adaptive join reordering technique:
- Keeps compilation and execution phases separate
- Generates small set of plan options during compilation
- Selects optimal plan at runtime based on observed cardinalities
- Demonstrated **up to 9x speedup** in DuckDB on IMDB benchmark

**Key Insight**: Rather than continuous adaptation, generate a "plan portfolio" and select at runtime.

**Applicability to Presto/Velox**:
- Generate multiple plan variants during optimization
- Select variant at stage boundaries based on observed statistics
- Lower overhead than continuous re-optimization

#### Lookahead Information Passing (LIP)
**Paper**: "Looking Ahead Makes Query Plans Robust" (VLDB 2017)

LIP passes filter data structures (Bloom filters) from dimension to fact tables:
1. Build Bloom filters on dimension table join keys during hash build
2. Probe all filters against fact table before expensive hash lookups
3. Trades expensive DRAM hash probes for efficient cache-resident filter probes

**Performance Impact**:
- Reduces intermediate result sizes dramatically
- Converts hash probes to cache-efficient filter checks
- Particularly effective for star schemas common in data warehouses

**Applicability to Presto/Velox**:
- Extend dynamic filtering to generate Bloom filters for all join branches
- Apply all filters to fact table scans simultaneously
- Could integrate with Velox's adaptive filter ordering

### 2.3 Learned Query Optimization

#### Cardinality Estimation with Machine Learning
**Key Systems**: CardBench (Google, 2024), Eraser (VLDB 2024)

Modern approaches to cardinality estimation:

| Approach | Description | Pros | Cons |
|----------|-------------|------|------|
| **Query-Driven** | ML model trained on query → cardinality | Fast inference | Workload shift sensitivity |
| **Data-Driven** | Learn data distribution models | Generalizes better | Training overhead |
| **Hybrid** | Combine both approaches | Best of both | Complexity |

**CardBench Findings**:
- Fine-tuned GNN models achieve median q-error of 1.32
- Zero-shot models struggle with unseen datasets
- Graph neural networks capture query structure well

**Applicability to Presto/Velox**:
- Could train models on historical query execution data
- Use predictions to improve initial plan choices
- Fall back to runtime adaptation when predictions fail

#### Reinforcement Learning for Query Optimization
**Key Systems**: SkinnerDB (Cornell), QTune (Tsinghua)

SkinnerDB uses UCB (Upper Confidence Bounds) bandit algorithm:
- Each join order is an "arm" of the bandit
- Learn optimal join order through exploration/exploitation
- Can switch join orders mid-query based on observed performance

**Applicability to Presto/Velox**:
- Could apply bandit-style learning for operator ordering
- Learn optimal configurations (batch sizes, parallelism) online
- Balance exploration (trying new strategies) with exploitation (using known-good strategies)

---

## 3. Adaptive Techniques in Modern Data Systems

### 3.1 Apache Spark Adaptive Query Execution (AQE)

Spark AQE (enabled by default since 3.2.0) provides three major features:

#### Coalescing Post-Shuffle Partitions
- Analyzes shuffle output statistics at stage boundaries
- Merges small partitions to reduce task overhead
- Eliminates manual `spark.sql.shuffle.partitions` tuning

**Mechanism**:
```
Before: 200 partitions (default) → many tiny partitions
After:  AQE analyzes sizes → coalesces to optimal count
Result: 10x fewer tasks for many queries
```

#### Dynamic Join Strategy Switching
- Converts sort-merge to broadcast when runtime sizes permit
- Decision made at stage boundary, not at optimization time
- Threshold: `spark.sql.adaptive.autoBroadcastJoinThreshold`

#### Skew Join Optimization
- Detects skewed partitions from shuffle statistics
- Splits large partitions into smaller sub-partitions
- Replicates matching data from other side

**Applicability to Presto/Velox**:
- Presto-on-Spark already leverages some AQE features
- Native Velox execution lacks equivalent capabilities
- Could implement similar techniques at stage boundaries

### 3.2 Trino Adaptive Features

Trino (Presto fork) has implemented:

#### Adaptive Plan Optimizations (Fault-Tolerant Mode)
- Reorders partitioned joins based on actual runtime sizes
- Only available with fault-tolerant execution enabled
- **Config**: `fault-tolerant-execution-adaptive-query-planning-enabled`

#### Enhanced Dynamic Filtering
- Local dynamic filters (producer/consumer in same fragment)
- Distributed dynamic filters (coordinator-mediated)
- Supports multiple comparison operators (=, <, >, <=, >=, IS NOT DISTINCT FROM)

### 3.3 Snowflake Adaptive Compute

Snowflake's new Adaptive Compute (2024) represents cloud-native adaptivity:
- Automatically selects cluster size and count
- Shared cluster pool within account for efficiency
- No user configuration for size, concurrency, or auto-suspend
- **Key Insight**: Abstract away resource decisions entirely

### 3.4 DuckDB Techniques

DuckDB implements several adaptive techniques in an embedded setting:
- **Morsel-driven parallelism**: Work-stealing scheduler for load balancing
- **Adaptive aggregation**: Strategy selection based on data characteristics
- **POLAR integration**: Demonstrated adaptive join reordering

### 3.5 Velox Native Adaptive Features

Velox already implements some adaptive techniques:

#### Adaptive Filter Ordering
```cpp
// Score = time / (1 + values_in - values_out)
// Lower score = filter evaluated first
```
Filters are dynamically reordered so the most selective filter runs first.

#### Adaptive Column Prefetching
- Tracks column access frequencies per-query
- Schedules prefetches for hot columns
- Takes I/O off critical path for interactive queries

#### Adaptive Hash Table Layout
- Hash layout decided adaptively as data arrives
- VectorHashers can be pushed down to scans as IN filters

---

## 4. Velox-Specific Opportunities

### 4.1 Statistics Flow Enhancement

**Current State**:
Statistics are collected but largely held until task completion.

**Opportunity**:
Implement streaming statistics reporting:
1. Report lightweight summary stats every N batches
2. Include: cardinality, memory usage, spill metrics
3. Enable coordinator to make runtime decisions

**Implementation Location**: `presto-native-execution/presto_cpp/main/PrestoTask.cpp`

### 4.2 Operator-Level Memory Arbitration

**Current State**:
Memory arbitration is global/query-level, not operator-aware.

**Opportunity**:
Implement operator-aware memory arbitration:
1. Track per-operator memory curves
2. Predict memory needs based on observed patterns
3. Proactively spill operators with poor memory efficiency
4. Prioritize operators on critical path

**Research Reference**: "Towards Operator-Level Memory Management" (Microsoft Research)

### 4.3 Dynamic Filter Feedback Loop

**Current State**:
Dynamic filters are generated but selectivity not tracked.

**Opportunity**:
Complete the feedback loop:
1. Track filter selectivity at worker
2. Report back to coordinator
3. Coordinator adjusts filter strategies:
   - Increase/decrease Bloom filter size
   - Switch between range and point filters
   - Disable ineffective filters

### 4.4 Join Algorithm Switching

**Current State**:
Join algorithm chosen at planning time, no runtime switching.

**Opportunity**:
Implement adaptive join selection:
1. Start with hash join
2. Monitor build-side size
3. If exceeds threshold: switch to grace hash or sort-merge
4. Consider nested loop for very small builds

**Statistics Available** (already collected):
- `joinBuildKeyCount`
- `logHistogramProbes`
- Memory reservation metrics

### 4.5 Aggregation Strategy Adaptation

**Current State**:
Partial aggregation has fixed memory limits.

**Opportunity**:
Implement adaptive aggregation:
1. Track reduction ratio in real-time
2. If reduction ratio < threshold: disable partial aggregation
3. Dynamically adjust group-by strategy (hash vs. sort)
4. Consider streaming aggregation for sorted input

---

## 5. Recommended Research Directions

### 5.1 High Priority: Immediate Impact

#### 5.1.1 Progressive Re-optimization for Velox
**Problem**: Query plans are fixed once execution starts.

**Approach**:
1. Insert checkpoints at shuffle/broadcast boundaries
2. Collect actual cardinalities at checkpoints
3. If deviation > 2x: consider re-optimization
4. Re-optimize remaining stages with updated statistics

**Expected Impact**: 3-10x improvement for queries with significant estimation errors.

**Research Needed**:
- Checkpoint placement optimization
- Re-optimization cost model
- Incremental plan comparison algorithms

#### 5.1.2 Lookahead Information Passing (LIP) Integration
**Problem**: Dynamic filters are applied one at a time.

**Approach**:
1. Build compact Bloom filters for all dimension tables
2. Apply all filters simultaneously to fact table scan
3. Use SIMD-optimized filter probing

**Expected Impact**: 2-5x improvement for star-schema queries.

**Research Needed**:
- Optimal Bloom filter sizing given memory constraints
- Integration with Velox's adaptive filter ordering
- Learned Bloom filters for skewed distributions

#### 5.1.3 Runtime Join Reordering (POLAR-style)
**Problem**: Join order fixed at planning time.

**Approach**:
1. Generate 2-5 plan variants with different join orders
2. At first stage boundary, measure actual cardinalities
3. Select best remaining plan variant

**Expected Impact**: Up to 9x for some queries (based on POLAR results).

**Research Needed**:
- Plan variant generation algorithms
- Variant selection decision logic
- Overhead analysis

### 5.2 Medium Priority: Framework Building

#### 5.2.1 Cardinality Feedback System
**Problem**: Estimation errors compound through query plan.

**Approach**:
1. Record actual vs. estimated cardinalities per operator
2. Build feedback model for common subexpressions
3. Apply corrections to future queries with same patterns

**Research Needed**:
- Pattern matching across queries
- Decay function for outdated feedback
- Integration with history-based statistics

#### 5.2.2 Memory-Aware Task Scheduling
**Problem**: Memory pressure causes unpredictable spilling.

**Approach**:
1. Estimate memory requirements from query plan
2. Admit tasks based on available memory
3. Schedule complementary workloads (memory-heavy with memory-light)

**Research Needed**:
- Memory requirement prediction models
- Scheduling algorithms under memory constraints
- Fair resource allocation

#### 5.2.3 Adaptive Parallelism
**Problem**: Fixed parallelism doesn't match data distribution.

**Approach**:
1. Monitor per-partition processing rates
2. Dynamically adjust parallelism (work stealing)
3. Handle skewed partitions with adaptive replication

**Research Reference**: Morsel-Driven Parallelism (HyPer, SIGMOD 2014)

### 5.3 Long-Term: Advanced Adaptivity

#### 5.3.1 Learned Cost Models
**Problem**: Static cost models don't capture system complexity.

**Approach**:
1. Train ML models on historical query execution
2. Predict cost for operator + data + system state
3. Use learned costs for plan selection

**Research Needed**:
- Feature engineering for cost prediction
- Model architecture (GNN, transformer, etc.)
- Online learning for adaptation

#### 5.3.2 Multi-Objective Adaptive Optimization
**Problem**: Current optimization targets latency only.

**Approach**:
1. Consider multiple objectives: latency, memory, I/O, CPU
2. Generate Pareto-optimal plans
3. Select based on current resource availability

**Research Needed**:
- Multi-objective optimization algorithms
- Resource availability prediction
- SLA-aware plan selection

#### 5.3.3 Cross-Query Adaptive Optimization
**Problem**: Each query optimized independently.

**Approach**:
1. Identify common subexpressions across queries
2. Share computed results when possible
3. Co-optimize concurrent queries for resource efficiency

**Research Reference**: "Multi-Query Optimization" (classic DB research)

---

## 6. Implementation Roadmap

### Phase 1: Foundation (1-2 Quarters)

| Item | Description | Files to Modify |
|------|-------------|-----------------|
| Streaming Statistics | Report stats every N batches | `PrestoTask.cpp`, protocol definitions |
| Filter Effectiveness | Track filter selectivity | `DynamicFilterStats`, operator code |
| Memory Metrics | Per-operator memory tracking | `PeriodicMemoryChecker`, operator code |

### Phase 2: Core Adaptivity (2-3 Quarters)

| Item | Description | Files to Modify |
|------|-------------|-----------------|
| Progressive Checkpoints | Insert measurement points | Velox plan conversion |
| LIP Integration | Multi-filter scan optimization | Scan operators, filter framework |
| Plan Variants | Generate multiple plans | Optimizer, plan selection logic |

### Phase 3: Advanced Features (3-4 Quarters)

| Item | Description | Files to Modify |
|------|-------------|-----------------|
| Join Switching | Runtime algorithm selection | Join operators, memory arbitration |
| Cardinality Feedback | Historical corrections | Statistics framework, history tracking |
| Adaptive Parallelism | Work stealing, skew handling | Scheduler, task management |

### Phase 4: ML Integration (Future)

| Item | Description | Research Needed |
|------|-------------|-----------------|
| Learned Cardinality | ML-based estimation | Model training, deployment infrastructure |
| Learned Cost Model | ML-based cost prediction | Feature engineering, model architecture |
| Reinforcement Learning | Online optimization | Reward modeling, exploration strategy |

---

## 7. References

### Academic Papers

1. Avnur, R., & Hellerstein, J. M. (2000). [Eddies: Continuously Adaptive Query Processing](https://dl.acm.org/doi/10.1145/342009.335420). SIGMOD 2000.

2. Markl, V., et al. (2004). [Robust Query Processing Through Progressive Optimization](https://dl.acm.org/doi/10.1145/1007568.1007642). SIGMOD 2004.

3. Leis, V., et al. (2014). [Morsel-Driven Parallelism: A NUMA-Aware Query Evaluation Framework](https://dl.acm.org/doi/10.1145/2588555.2610507). SIGMOD 2014.

4. Zhu, J., et al. (2017). [Looking Ahead Makes Query Plans Robust](https://www.vldb.org/pvldb/vol10/p889-zhu.pdf). PVLDB 2017.

5. Trummer, I. (2019). SkinnerDB: Regret-Bounded Query Processing. SIGMOD 2019.

6. Wu, Z., et al. (2023). [Kepler: Robust Learning for Parametric Query Optimization](https://dl.acm.org/doi/10.1145/3588963). SIGMOD 2023.

7. Haritsa, J. R. (2024). [Robust Query Processing: A Survey](https://www.nowpublishers.com/article/Details/DBS-089). Foundations and Trends in Databases.

8. Google AI (2024). [CardBench: A Benchmark for Learned Cardinality Estimation](https://www.marktechpost.com/2024/09/02/google-ai-introduces-cardbench-a-comprehensive-benchmark-featuring-over-20-real-world-databases-and-thousands-of-queries-to-revolutionize-learned-cardinality-estimation/).

### Industry Systems

9. Databricks. (2020). [Adaptive Query Execution: Speeding Up Spark SQL at Runtime](https://www.databricks.com/blog/2020/05/29/adaptive-query-execution-speeding-up-spark-sql-at-runtime.html).

10. Trino Project. [Adaptive Plan Optimizations](https://trino.io/docs/current/optimizer/adaptive-plan-optimizations.html).

11. Pedreira, P., et al. (2022). [Velox: Meta's Unified Execution Engine](https://www.vldb.org/pvldb/vol15/p3372-pedreira.pdf). PVLDB 2022.

12. BIFOLD Berlin. (2024). [POLAR: Adaptive Join Reordering](https://www.bifold.berlin/news-events/news/view/news-detail/polar-lowers-the-adoption-barrier-for-adaptive-query-processing-in-database-systems).

### Additional Resources

13. [Querify Labs: Dynamic Filtering in Analytical Engines](https://www.querifylabs.com/blog/dynamic-filtering-in-analytical-engines)

14. [CMU 15-721: Advanced Database Systems - Vectorization](https://15721.courses.cs.cmu.edu/spring2024/notes/06-vectorization.pdf)

15. [Apache Iceberg Performance & Architecture](https://www.dremio.com/blog/how-apache-iceberg-is-built-for-open-optimized-performance/)

---

## Appendix A: Presto Configuration for Adaptive Features

### Currently Available Session Properties

```sql
-- Dynamic Filtering
SET SESSION enable_dynamic_filtering = true;
SET SESSION dynamic_filtering_max_per_driver_row_count = 100;
SET SESSION dynamic_filtering_max_per_driver_size = '10MB';

-- History-Based Statistics
SET SESSION use_history_based_plan_statistics = true;
SET SESSION track_history_based_plan_statistics = true;

-- Confidence-Based Optimization
SET SESSION confidence_based_broadcast_enabled = true;
SET SESSION treat_low_confidence_zero_estimation_unknown_enabled = true;

-- Join Optimization
SET SESSION join_distribution_type = 'AUTOMATIC';
SET SESSION size_based_join_distribution_type_enabled = true;

-- Adaptive Aggregation (Java Path)
SET SESSION is_adaptive_partial_aggregation_enabled = true;

-- Spark AQE (Presto-on-Spark)
SET SESSION spark_adaptive_query_execution_enabled = true;
SET SESSION adaptive_join_side_switching_enabled = true;
```

### Velox-Specific Properties

```sql
-- Spilling
SET SESSION native_spill_enabled = true;
SET SESSION native_join_spill_enabled = true;
SET SESSION native_aggregation_spill_enabled = true;

-- Memory Management
SET SESSION native_max_partial_aggregation_memory = '16MB';
SET SESSION native_max_extended_partial_aggregation_memory = '160MB';

-- Scaling
SET SESSION native_scaled_writer_rebalance_max_memory_usage_ratio = 0.7;
SET SESSION native_table_scan_scaled_processing_enabled = true;
```

---

## Appendix B: Key Code Locations

### Presto Core

| Component | Location |
|-----------|----------|
| Dynamic Filters | `presto-main-base/src/main/java/com/facebook/presto/operator/DynamicFilterSourceOperator.java` |
| History Stats | `presto-main-base/src/main/java/com/facebook/presto/cost/HistoryBasedPlanStatisticsCalculator.java` |
| Confidence Broadcast | `presto-main-base/src/main/java/com/facebook/presto/sql/planner/iterative/ConfidenceBasedBroadcastUtil.java` |
| Adaptive Execution (Spark) | `presto-spark-base/src/main/java/com/facebook/presto/spark/execution/PrestoSparkAdaptiveQueryExecution.java` |
| Memory Revoking | `presto-main/src/main/java/com/facebook/presto/execution/MemoryRevokingScheduler.java` |

### Velox Integration

| Component | Location |
|-----------|----------|
| Task Statistics | `presto-native-execution/presto_cpp/main/PrestoTask.cpp` |
| Session Properties | `presto-native-execution/presto_cpp/main/SessionProperties.h` |
| Memory Checker | `presto-native-execution/presto_cpp/main/PeriodicMemoryChecker.h` |
| Query Config | `presto-native-execution/presto_cpp/main/PrestoToVeloxQueryConfig.cpp` |

---

*Document Version: 1.0*
*Date: December 2024*
*Author: Research Agent*
