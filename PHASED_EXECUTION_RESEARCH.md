# Phased Execution with Materialization Points: Adaptive Query Processing for Presto

## Abstract

This document explores a novel approach to adaptive query processing in Presto that respects its pull-based streaming execution model. Traditional adaptive techniques assume either stage-based execution (like Spark) or single-node processing (like Oracle/SQL Server adaptive joins). Presto's distributed streaming model requires a different approach.

We propose **Phased Execution with Materialization Points (PEMP)**, where the optimizer strategically inserts materialization points at high-uncertainty locations in the query plan. After each phase completes, actual cardinalities inform re-optimization of subsequent phases. We also explore **DAG-based Plan Variants**, where multiple downstream plan alternatives are pre-generated and selected at runtime based on observed statistics.

---

## Table of Contents

1. [Problem Statement](#1-problem-statement)
2. [Why Traditional Approaches Fail in Presto](#2-why-traditional-approaches-fail-in-presto)
3. [Phased Execution Model](#3-phased-execution-model)
4. [Materialization Point Placement](#4-materialization-point-placement)
5. [Re-optimization at Phase Boundaries](#5-re-optimization-at-phase-boundaries)
6. [DAG-Based Plan Variants](#6-dag-based-plan-variants)
7. [Coordinator Protocol Design](#7-coordinator-protocol-design)
8. [Cost Model for Phased Execution](#8-cost-model-for-phased-execution)
9. [Implementation Architecture](#9-implementation-architecture)
10. [Research Questions](#10-research-questions)
11. [Related Work](#11-related-work)
12. [Appendix: Formal Model](#appendix-formal-model)

---

## 1. Problem Statement

### 1.1 The Data Lake Statistics Challenge

Data lakes and lakehouses suffer from unreliable statistics due to:

1. **Collection Cost**: Statistics gathering requires scanning data; often skipped or sampled
2. **Staleness**: Data continuously ingested; statistics decay quickly
3. **Format Heterogeneity**: Parquet, ORC, Iceberg have varying metadata quality
4. **Predicate Selectivity Uncertainty**: Complex predicates, UDFs, and cross-column correlations defeat histogram-based estimation
5. **Partition Dynamics**: Partition pruning radically changes effective table size at runtime

### 1.2 Impact of Estimation Errors

Cardinality estimation errors compound through the query plan:

```
Actual vs. Estimated Cardinality Error Propagation:

Scan: 1.5x error
  → Join 1: 1.5 × 2.0 = 3x error (join selectivity also estimated)
    → Join 2: 3 × 1.8 = 5.4x error
      → Join 3: 5.4 × 2.2 = 12x error
```

A 12x error at a critical join can result in:
- Wrong join order (100x+ performance difference)
- Wrong join distribution type (broadcast vs. partitioned)
- Memory pressure, spilling, or OOM
- Suboptimal parallelism

### 1.3 The Opportunity

Blocking operators (hash builds, sorts, final aggregations) provide **natural checkpoints** where actual cardinalities become known. If we can leverage these checkpoints to inform decisions about subsequent execution, we can recover from estimation errors.

---

## 2. Why Traditional Approaches Fail in Presto

### 2.1 Presto's Execution Model

Presto uses **distributed pull-based streaming execution**:

```
┌─────────────────────────────────────────────────────────────────┐
│                         Coordinator                              │
│  - Parses query                                                  │
│  - Optimizes plan (once, before execution)                       │
│  - Distributes plan fragments to workers                         │
│  - Tracks task status                                            │
└─────────────────────────────────────────────────────────────────┘
                              │
          ┌───────────────────┼───────────────────┐
          ▼                   ▼                   ▼
    ┌──────────┐        ┌──────────┐        ┌──────────┐
    │ Worker 1 │◄──────►│ Worker 2 │◄──────►│ Worker 3 │
    │          │ stream │          │ stream │          │
    │ Pipeline │  data  │ Pipeline │  data  │ Pipeline │
    └──────────┘        └──────────┘        └──────────┘
```

Key characteristics:
- **No stage boundaries**: Data streams continuously through exchanges
- **Pipelined execution**: Downstream operators start as soon as upstream produces data
- **Pull semantics**: Consumers request data from producers
- **Plan fixed at start**: Optimizer runs once, plan doesn't change during execution

### 2.2 Why Spark-Style AQE Doesn't Apply

Spark's Adaptive Query Execution relies on **shuffle boundaries**:

```
Spark Stage Model:

Stage 1: Scan + Filter + Map
    │
    ▼ (shuffle write - BLOCKING)
═══════════════════════════════════
    │ (shuffle read)
    ▼
Stage 2: Reduce + Aggregate
```

At shuffle boundaries:
- All map tasks complete before reduce tasks start
- Statistics collected from shuffle files
- Optimizer re-invoked with actual statistics
- New plan generated for subsequent stages

**In Presto**: Exchanges are pipelined, not blocking. There is no moment when "all upstream work is done" before downstream begins.

### 2.3 Why Single-Node Adaptive Techniques Don't Apply

SQL Server's adaptive joins and Oracle's adaptive plans work because:
- Single node: Easy to switch strategies without distributed coordination
- Lower latency: Operator switching is local, sub-millisecond
- Iterator model: Easy to swap one iterator implementation for another

**In Presto**:
- Distributed execution requires coordinator involvement
- Plan fragments already distributed to workers
- Switching strategies requires network round-trips
- Workers may have already started executing the "wrong" strategy

### 2.4 What CAN Work in Presto

Despite the constraints, Presto does have blocking points:

| Operator | Blocks Until | Information Available |
|----------|--------------|----------------------|
| Hash Build | All build-side input consumed | Exact build cardinality, memory footprint, key distribution |
| Sort | All input consumed | Exact cardinality, sort cost |
| Window (per partition) | Full partition received | Partition sizes |
| Final Aggregation | All partial results received | Group count, reduction ratio |

These blocking points are **phase boundaries** where we can:
1. Materialize results
2. Report statistics to coordinator
3. Re-optimize remaining query
4. Execute subsequent phase with better plan

---

## 3. Phased Execution Model

### 3.1 Core Concept

Instead of executing the entire query plan as one continuous streaming operation, we **decompose execution into phases** separated by **Materialization Points (MPs)**.

```
Traditional Presto Execution:
═══════════════════════════════════════════════════════════════
 Scan A → Join1 → Join2 → Join3 → Aggregate → Output
═══════════════════════════════════════════════════════════════
 ▲                                                           ▲
 │                                                           │
 Start                                                      End
 (one continuous streaming execution)


Phased Execution:
═══════════════════════════════════════════════════════════════
 Phase 1           │  Phase 2           │  Phase 3
───────────────────┼────────────────────┼──────────────────────
 Scan A ─┐         │                    │
         ├─ Join1 ─┼─► [MP1]            │
 Scan B ─┘         │    │               │
                   │    ▼               │
                   │  Scan C ─┐         │
                   │          ├─ Join2 ─┼─► [MP2]
                   │  [MP1] ──┘         │    │
                   │                    │    ▼
                   │                    │  Scan D ─┐
                   │                    │          ├─ Join3 ─► Output
                   │                    │  [MP2] ──┘
───────────────────┼────────────────────┼──────────────────────
                   │                    │
               Re-optimize          Re-optimize
               with MP1 stats       with MP2 stats
═══════════════════════════════════════════════════════════════
```

### 3.2 Phase Execution Semantics

Each phase executes as standard Presto streaming execution:

```
Phase N Execution:
1. Coordinator distributes Phase N plan to workers
2. Workers execute plan (pull-based streaming as usual)
3. Results written to Materialization Point storage
4. Workers report completion + statistics to Coordinator
5. Coordinator re-optimizes Phase N+1 plan using actual statistics
6. Proceed to Phase N+1
```

Within a phase, execution is unchanged from current Presto behavior. The adaptation happens **between** phases, not during streaming.

### 3.3 Materialization Point Semantics

A Materialization Point (MP) is a special operator that:

1. **Consumes** all input from upstream (blocking)
2. **Persists** data to intermediate storage
3. **Reports** statistics to coordinator:
   - Exact row count
   - Data size in bytes
   - Column statistics (min/max, NDV, null count)
   - Optional: sample for histogram estimation
4. **Waits** for coordinator signal to proceed (or terminate if plan changes)
5. **Produces** data for next phase when signaled

```
┌─────────────────────────────────────────────────────────────────┐
│                    Materialization Point                         │
├─────────────────────────────────────────────────────────────────┤
│                                                                  │
│   Input ──► Buffer ──► Spill to Storage ──► Await Signal        │
│               │                                                  │
│               └──► Collect Statistics ──► Report to Coordinator │
│                                                                  │
│   On Signal: Read from Storage ──► Output to Next Phase         │
│                                                                  │
└─────────────────────────────────────────────────────────────────┘
```

### 3.4 When Materialization Adds Value

Materialization has cost (write + read overhead). It adds value when:

```
Expected Benefit of Re-optimization > Materialization Cost

Where:
  Benefit = P(plan changes) × (Cost(bad_plan) - Cost(good_plan))
  Cost = Write cost + Read cost + Coordination latency
```

This is profitable when:
- **High uncertainty**: Estimate confidence is low (poor statistics)
- **High stakes**: Many joins remaining, complex downstream plan
- **Large potential improvement**: Orders of magnitude difference between good and bad plans

---

## 4. Materialization Point Placement

### 4.1 Candidate Locations

MPs should be placed at **natural blocking points** to avoid introducing new blocking where streaming would otherwise occur:

| Location | Natural Blocking? | Information Gained | Typical Value |
|----------|-------------------|-------------------|---------------|
| After hash build | Yes (for build side) | Build cardinality, hash stats | High |
| After sort | Yes | Sorted cardinality | Medium |
| After window function | Yes (per partition) | Partition sizes | Low-Medium |
| After final aggregation | Yes | Group count | Medium |
| Arbitrary point in pipeline | NO - introduces blocking | Cardinality at that point | Must justify cost |

### 4.2 Placement Heuristics

**Heuristic 1: Confidence-Based Placement**

```
For each blocking operator B in the plan:
  If confidence(cardinality_estimate(B.output)) < THRESHOLD_LOW:
    If remaining_plan_complexity(B) > THRESHOLD_COMPLEXITY:
      Mark B as Materialization Point
```

**Heuristic 2: Error Propagation Analysis**

```
Estimate error amplification through plan:
  error_at_root = product(selectivity_uncertainty(each_join))

If error_at_root > THRESHOLD_ERROR:
  Find join J where error_before(J) < THRESHOLD but error_after(J) > THRESHOLD
  Place MP after J
```

**Heuristic 3: Cost Sensitivity Analysis**

```
For each possible MP placement P:
  Compute plan_cost(P, estimate=low), plan_cost(P, estimate=high)
  sensitivity(P) = |cost_high - cost_low|

Place MP at locations with highest sensitivity
```

### 4.3 Placement Algorithm

```python
def place_materialization_points(plan, stats_confidence, cost_model):
    """
    Returns modified plan with MPs inserted at strategic locations.
    """
    candidates = find_blocking_operators(plan)

    selected_mps = []
    remaining_plan = plan

    for candidate in candidates:
        # Compute expected benefit
        uncertainty = 1.0 - stats_confidence(candidate.output)
        downstream_complexity = count_operators(candidate.downstream)
        plan_sensitivity = compute_sensitivity(candidate, cost_model)

        expected_benefit = uncertainty * downstream_complexity * plan_sensitivity

        # Compute materialization cost
        estimated_size = estimate_output_size(candidate)
        mp_cost = compute_mp_cost(estimated_size, storage_type)

        # Decision
        if expected_benefit > mp_cost * SAFETY_MARGIN:
            selected_mps.append(candidate)

    # Avoid redundant MPs (e.g., two in sequence)
    selected_mps = prune_redundant(selected_mps)

    return insert_mps(plan, selected_mps)
```

### 4.4 Dynamic MP Placement

Rather than deciding all MPs upfront, we can place them **dynamically**:

1. Place first MP at earliest high-uncertainty point
2. Execute Phase 1
3. Based on observed statistics, decide where to place next MP (if any)
4. Execute Phase 2
5. Repeat

This avoids over-materializing when early phases reveal estimates were accurate.

---

## 5. Re-optimization at Phase Boundaries

### 5.1 Information Available at MP

When an MP completes, the coordinator receives:

```protobuf
message MaterializationPointComplete {
  string mp_id = 1;
  int64 row_count = 2;
  int64 size_bytes = 3;
  repeated ColumnStatistics column_stats = 4;
  StorageLocation location = 5;
  Duration materialization_time = 6;
}

message ColumnStatistics {
  string column_name = 1;
  int64 distinct_count = 2;
  int64 null_count = 3;
  optional bytes min_value = 4;
  optional bytes max_value = 5;
  optional Histogram histogram = 6;  // If sampled
}
```

### 5.2 Re-optimization Scope

**Option A: Full Re-optimization**

Re-invoke the entire optimizer for remaining query, treating MPs as temporary tables:

```sql
-- Original remaining plan:
SELECT ... FROM join2_result JOIN dim3 ON ...

-- After MP, rewritten as:
SELECT ... FROM mp_storage_location JOIN dim3 ON ...
```

Pros:
- Full flexibility to change join order, distribution, etc.
- Uses latest optimizer logic

Cons:
- Optimization latency added to query execution
- May produce very different plan, losing any warmup/caching benefits

**Option B: Constrained Re-optimization**

Only re-optimize specific aspects while keeping plan structure:

```
Re-optimize:
  - Join distribution type (broadcast vs. partitioned)
  - Parallelism levels
  - Memory allocation per operator

Keep fixed:
  - Join order
  - Operator types (hash join vs. merge join)
  - Filter placement
```

Pros:
- Faster optimization
- More predictable behavior
- Smaller plan diffs

Cons:
- May miss significant optimization opportunities

**Option C: Plan Selection from Pre-generated Variants**

(Covered in Section 6)

### 5.3 Incremental Re-optimization

For multi-MP queries, we want to avoid re-scanning unchanged parts:

```
After MP1:
  - We know: Actual cardinality of MP1 output
  - We need to re-optimize: Everything downstream of MP1
  - We preserve: MP1 itself (already materialized)

After MP2:
  - We know: Actual cardinality of MP2 output (which consumed MP1)
  - We need to re-optimize: Everything downstream of MP2
  - We preserve: MP1 (still valid), MP2 (just completed)
```

This requires the optimizer to support:
1. Taking a partial plan as "fixed" input
2. Optimizing only the remaining portions
3. Preserving references to materialized storage

### 5.4 Handling Subplan Reuse

A key insight: if the new plan still wants to use the same subplan that was already computed, don't re-execute it.

**Example:**

```
Original plan:
  ((A ⋈ B) ⋈ C) ⋈ D

After MP1 (after A ⋈ B):
  - Actual cardinality of (A ⋈ B) = 10x expected
  - Re-optimizer determines: should have been ((A ⋈ B) ⋈ D) ⋈ C

New plan:
  ((MP1 ⋈ D) ⋈ C)    -- MP1 contains (A ⋈ B), no need to re-execute
```

The materialized result of A ⋈ B is reused even though the join order changed.

**What if re-optimizer wants to change the already-executed subplan?**

```
After MP1 (after A ⋈ B):
  - Re-optimizer determines: should have been (A ⋈ D) ⋈ (B ⋈ C)

This requires re-scanning A and B with different join partners.
```

Options:
1. **Don't allow**: Constrain re-optimizer to reuse MP outputs
2. **Allow with cost**: If benefit exceeds re-execution cost, permit it
3. **Checkpoint earlier**: If we had placed MPs after Scan A and Scan B separately, we could reuse those

This motivates **finer-grained materialization** in high-uncertainty queries.

---

## 6. DAG-Based Plan Variants

### 6.1 Motivation

Re-optimization at phase boundaries adds latency. An alternative: **pre-generate multiple plan variants** and select at runtime.

```
Optimization Time:
  Analyze query → Identify uncertainty → Generate N variants → Distribute to workers

Execution Time:
  Execute shared prefix → Observe statistics → Select best variant → Execute selected variant
```

This amortizes optimization cost and eliminates re-optimization latency.

### 6.2 Plan Representation Change

Currently, Presto plans are **trees**: each operator has exactly one consumer.

```
Tree Plan:
        Aggregate
            │
         Join2
         /    \
     Join1    ScanC
     /    \
 ScanA   ScanB
```

For variant selection, we need **DAG plans**: operators can have multiple consumers.

```
DAG Plan with Variants:

                    ┌──► Variant A: (result ⋈ C) ⋈ D
 ScanA ──┐          │
         ├─ Join1 ──┼──► Variant B: (result ⋈ D) ⋈ C
 ScanB ──┘          │
                    └──► Variant C: result ⋈ (C ⋈ D)
```

`Join1`'s output is consumed by three different variant subplans.

### 6.3 Variant Generation

**Step 1: Identify Variance Points**

Find locations in the plan where:
- Cardinality uncertainty is high
- Multiple valid orderings/strategies exist downstream

```python
def find_variance_points(plan, uncertainty_model):
    points = []
    for node in plan.nodes:
        if is_blocking(node) and uncertainty_model.is_uncertain(node.output):
            downstream_variants = enumerate_valid_plans(node.downstream)
            if len(downstream_variants) > 1:
                points.append((node, downstream_variants))
    return points
```

**Step 2: Generate Variant Plans**

For each variance point, generate alternative downstream plans:

```python
def generate_variants(variance_point):
    node, candidates = variance_point
    variants = []

    for candidate_plan in candidates:
        # Score this variant under different cardinality assumptions
        score_if_small = cost_model.evaluate(candidate_plan, assume=SMALL)
        score_if_large = cost_model.evaluate(candidate_plan, assume=LARGE)

        variants.append(Variant(
            plan=candidate_plan,
            condition=derive_condition(candidate_plan),  # When to pick this
            expected_cost=expected_cost(candidate_plan)
        ))

    return variants
```

**Step 3: Prune Variant Space**

With N variance points and M alternatives each, we get M^N total plans. This explodes quickly. Pruning strategies:

1. **Dominance pruning**: If Variant A is better than B for all cardinality assumptions, eliminate B
2. **K-best**: Keep only top K variants by expected cost
3. **Threshold pruning**: Eliminate variants only good for unlikely cardinality ranges

### 6.4 Variant Selection at Runtime

At each variance point, the runtime selector evaluates conditions:

```python
def select_variant(variance_point, observed_stats):
    for variant in variance_point.variants:
        if variant.condition.evaluate(observed_stats):
            return variant.plan

    # Fallback to default
    return variance_point.default_variant
```

**Condition Examples:**

```
Variant A condition: row_count < 100,000
Variant B condition: row_count >= 100,000 AND row_count < 10,000,000
Variant C condition: row_count >= 10,000,000
```

### 6.5 DAG Execution Semantics

**Shared Prefix Execution:**

```
1. Execute common prefix up to variance point
2. Materialize result at variance point
3. Report statistics to coordinator
4. Coordinator selects variant
5. Coordinator signals workers which variant to execute
6. Workers execute selected variant, reading from materialized input
7. Unselected variants are never executed
```

**Multiple Variance Points:**

```
Plan with two variance points:

Prefix A → [VP1] → Segment B → [VP2] → Segment C

Execution:
1. Execute Prefix A
2. Select at VP1 → say, Variant A.2
3. Execute Segment B (from Variant A.2)
4. Select at VP2 → say, Variant A.2.1
5. Execute Segment C (from Variant A.2.1)
```

This is a tree of variant paths through execution.

### 6.6 Plan Fragment Distribution

Challenge: How to distribute variant plans to workers?

**Option A: Distribute All Variants**

Send all variants to workers. Workers wait for selection signal.

Pros: No distribution latency after selection
Cons: More data to distribute, worker memory for unused plans

**Option B: Distribute on Demand**

Send only selected variant after variance point completes.

Pros: Less data transferred overall
Cons: Added latency after each variance point

**Option C: Hybrid**

For first K (e.g., 2-3) most likely variants, distribute upfront. For remaining variants, distribute on demand.

Pros: Low latency for common cases
Cons: Complexity

---

## 7. Coordinator Protocol Design

### 7.1 New Message Types

```protobuf
// Sent from Worker to Coordinator when MP completes
message PhaseComplete {
  string query_id = 1;
  string phase_id = 2;
  string mp_id = 3;
  MaterializationStats stats = 4;
  StorageLocation location = 5;
}

message MaterializationStats {
  int64 row_count = 1;
  int64 size_bytes = 2;
  Duration execution_time = 3;
  repeated ColumnStats columns = 4;
}

// Sent from Coordinator to Workers to start next phase
message StartPhase {
  string query_id = 1;
  string phase_id = 2;
  PlanFragment plan = 3;
  repeated MaterializationInput inputs = 4;  // References to prior MPs
}

// For variant-based execution
message SelectVariant {
  string query_id = 1;
  string variance_point_id = 2;
  string selected_variant_id = 3;
}
```

### 7.2 Coordinator State Machine

```
                                    ┌─────────────────────────┐
                                    │                         │
                                    ▼                         │
┌─────────┐    ┌─────────────┐    ┌───────────────┐    ┌─────────────────┐
│ QUEUED  │───►│ OPTIMIZING  │───►│ EXECUTING     │───►│ PHASE_COMPLETE  │
│         │    │  PHASE 1    │    │  PHASE 1      │    │                 │
└─────────┘    └─────────────┘    └───────────────┘    └────────┬────────┘
                                                                │
                                                                ▼
                     ┌──────────────────────────────────────────┴───┐
                     │                                              │
                     ▼                                              ▼
              ┌─────────────┐                               ┌──────────────┐
              │ OPTIMIZING  │                               │   FINISHED   │
              │  PHASE N    │                               │              │
              └──────┬──────┘                               └──────────────┘
                     │
                     ▼
              ┌───────────────┐
              │ EXECUTING     │
              │  PHASE N      │
              └───────────────┘
```

### 7.3 Failure Handling

**Phase failure**: Re-execute failed phase from its input MPs (already persisted)

**MP corruption**: Re-execute phase that produced the MP

**Coordinator failure**:
- If using persistent MP storage, can recover and resume from last completed phase
- This provides natural checkpointing for long-running queries

### 7.4 Variant Selection Protocol

```
Worker                    Coordinator
   │                          │
   │ ──PhaseComplete──────►   │
   │                          │  (evaluate variant conditions)
   │                          │  (select best variant)
   │   ◄──SelectVariant────   │
   │                          │
   │  (load selected variant) │
   │  (begin execution)       │
   │                          │
```

---

## 8. Cost Model for Phased Execution

### 8.1 When is Phased Execution Beneficial?

Let:
- `C_streaming` = Cost of single-phase streaming execution with estimated plan
- `C_phased` = Cost of phased execution
- `C_mp` = Cost of materialization (write + read + coordination)
- `C_reopt` = Cost of re-optimization
- `P_wrong` = Probability that initial plan is suboptimal
- `R_improvement` = Expected cost ratio improvement when re-optimized

```
C_phased = C_phase1 + C_mp + C_reopt + E[C_remaining_phases]

Where:
  E[C_remaining_phases] = (1 - P_wrong) × C_remaining(original_plan)
                        + P_wrong × C_remaining(better_plan)

Phased execution is beneficial when:
  C_phased < C_streaming

Simplified:
  C_mp + C_reopt < P_wrong × (C_streaming × (1 - 1/R_improvement))
```

### 8.2 Estimating `P_wrong`

The probability that the initial plan is wrong depends on:
- Statistics confidence (from catalog, history, or confidence intervals)
- Sensitivity of plan to cardinality changes
- Historical accuracy for similar queries

```python
def estimate_p_wrong(plan, stats_confidence, history):
    # Check if plan is sensitive to estimate changes
    sensitivity = compute_plan_sensitivity(plan)

    # Check historical accuracy for similar patterns
    historical_error = history.get_typical_error(plan.pattern)

    # Combine into probability
    p_wrong = sensitivity * (1 - stats_confidence) * historical_error
    return min(p_wrong, 1.0)
```

### 8.3 Estimating `R_improvement`

The potential improvement from re-optimization:

```python
def estimate_improvement_ratio(plan, uncertainty_range):
    # Generate plans for low/high cardinality assumptions
    plan_low = optimize(plan, assume=uncertainty_range.low)
    plan_high = optimize(plan, assume=uncertainty_range.high)

    # If same plan, no improvement possible
    if plan_low == plan_high:
        return 1.0

    # Estimate improvement as ratio of costs
    cost_wrong = cost(plan_low, actual=uncertainty_range.high)  # Wrong plan on high data
    cost_right = cost(plan_high, actual=uncertainty_range.high)  # Right plan

    return cost_wrong / cost_right
```

### 8.4 Materialization Cost Model

```python
def compute_mp_cost(estimated_rows, row_width, storage_type):
    data_size = estimated_rows * row_width

    if storage_type == MEMORY:
        write_cost = data_size / MEMORY_BANDWIDTH
        read_cost = data_size / MEMORY_BANDWIDTH
    elif storage_type == LOCAL_SSD:
        write_cost = data_size / SSD_WRITE_BANDWIDTH
        read_cost = data_size / SSD_READ_BANDWIDTH
    elif storage_type == DISTRIBUTED_STORAGE:
        write_cost = data_size / NETWORK_BANDWIDTH + STORAGE_LATENCY
        read_cost = data_size / NETWORK_BANDWIDTH + STORAGE_LATENCY

    coordination_cost = COORDINATOR_RTT + REOPTIMIZATION_TIME

    return write_cost + read_cost + coordination_cost
```

---

## 9. Implementation Architecture

### 9.1 Component Overview

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                              Coordinator                                     │
│  ┌─────────────────┐  ┌─────────────────┐  ┌─────────────────────────────┐  │
│  │   Query Parser  │  │    Optimizer    │  │    Phase Orchestrator       │  │
│  │                 │  │   (extended)    │  │  - MP placement             │  │
│  └────────┬────────┘  └────────┬────────┘  │  - Phase tracking           │  │
│           │                    │           │  - Variant selection        │  │
│           ▼                    ▼           │  - Re-optimization trigger  │  │
│  ┌────────────────────────────────────┐   └─────────────────────────────┘  │
│  │          Execution Planner         │                 │                   │
│  │  - Phase decomposition             │◄────────────────┘                   │
│  │  - MP insertion                    │                                     │
│  │  - Variant generation              │                                     │
│  └────────────────────────────────────┘                                     │
│                    │                                                        │
└────────────────────┼────────────────────────────────────────────────────────┘
                     │
    ┌────────────────┼────────────────┐
    ▼                ▼                ▼
┌────────┐      ┌────────┐      ┌────────┐
│Worker 1│      │Worker 2│      │Worker 3│
│        │      │        │      │        │
│ ┌────┐ │      │ ┌────┐ │      │ ┌────┐ │
│ │ MP │ │      │ │ MP │ │      │ │ MP │ │
│ │Oper│ │      │ │Oper│ │      │ │Oper│ │
│ └────┘ │      └────┘ │      │ └────┘ │
│        │      │        │      │        │
│ ┌─────────┐   │ ┌─────────┐   │ ┌─────────┐
│ │MP      │   │ │MP      │   │ │MP      │
│ │Storage │   │ │Storage │   │ │Storage │
│ └─────────┘   │ └─────────┘   │ └─────────┘
└────────┘      └────────┘      └────────┘
```

### 9.2 Optimizer Extensions

```java
public interface PhasedExecutionOptimizer {

    /**
     * Analyze plan and identify materialization point candidates.
     */
    List<MaterializationCandidate> identifyMPCandidates(
        PlanNode root,
        StatsProvider stats,
        CostModel costModel);

    /**
     * Insert MPs and decompose into phases.
     */
    PhasedPlan decompose(
        PlanNode root,
        List<MaterializationCandidate> selectedMPs);

    /**
     * Generate variant plans for a variance point.
     */
    List<PlanVariant> generateVariants(
        VariancePoint vp,
        PlanNode downstreamPlan,
        int maxVariants);

    /**
     * Re-optimize remaining plan given completed MP stats.
     */
    PlanNode reoptimize(
        PlanNode remainingPlan,
        MaterializationStats mpStats,
        Session session);
}
```

### 9.3 Materialization Point Operator

```java
public class MaterializationPointOperator implements Operator {

    private final OperatorContext context;
    private final MaterializationStorage storage;
    private final StatsCollector statsCollector;
    private final CoordinatorClient coordinator;

    // Accumulates all input before writing
    private final PageBuffer inputBuffer;

    @Override
    public void addInput(Page page) {
        inputBuffer.add(page);
        statsCollector.update(page);
    }

    @Override
    public void finish() {
        // Write to storage
        StorageLocation location = storage.write(inputBuffer);

        // Report to coordinator
        MaterializationStats stats = statsCollector.getStats();
        coordinator.reportPhaseComplete(context.getPhaseId(), stats, location);

        // Wait for signal to proceed or terminate
        PhaseDirective directive = coordinator.awaitDirective();

        if (directive == PROCEED) {
            // Next phase will read from storage location
            state = COMPLETED;
        } else if (directive == TERMINATE) {
            // Plan changed, this output not needed
            storage.delete(location);
            state = CANCELLED;
        }
    }

    @Override
    public Page getOutput() {
        // Output is read in next phase via MaterializationSourceOperator
        throw new UnsupportedOperationException(
            "MP output consumed in subsequent phase");
    }
}
```

### 9.4 Materialization Source Operator

```java
public class MaterializationSourceOperator implements Operator {

    private final StorageLocation location;
    private final MaterializationStorage storage;
    private Iterator<Page> pageIterator;

    @Override
    public void start() {
        // Open handle to materialized data
        pageIterator = storage.read(location);
    }

    @Override
    public Page getOutput() {
        if (pageIterator.hasNext()) {
            return pageIterator.next();
        }
        return null;
    }

    @Override
    public boolean isFinished() {
        return !pageIterator.hasNext();
    }
}
```

### 9.5 Storage Options

| Storage Type | When to Use | Pros | Cons |
|--------------|-------------|------|------|
| **Memory** | Small MPs, fast subsequent access | Lowest latency | Memory pressure |
| **Local SSD** | Medium MPs, single-node access | Fast, no network | Node-local only |
| **Distributed (S3, HDFS)** | Large MPs, any node can read | Durable, scalable | Latency, cost |
| **Exchange Buffers** | When MP aligns with exchange | Reuse existing infra | Timeout concerns |

Selection logic:
```python
def select_storage_type(estimated_size, phase_duration, cluster_config):
    if estimated_size < MEMORY_THRESHOLD:
        return MEMORY
    elif estimated_size < LOCAL_SSD_THRESHOLD:
        return LOCAL_SSD
    else:
        return DISTRIBUTED
```

---

## 10. Research Questions

### 10.1 Open Questions

**Q1: Optimal MP Granularity**

Should MPs be:
- Coarse-grained (one per major join level) → fewer phases, less adaptation
- Fine-grained (after each operator) → more phases, more overhead

How do we find the optimal granularity for a given query and uncertainty profile?

**Q2: Variant Generation Bounds**

How many variants is too many? At what point does the overhead of variant management exceed the benefit of runtime selection?

**Q3: MP Storage Management**

For long-running queries with many phases:
- When can MP storage be reclaimed?
- How to handle storage failures?
- What durability guarantees are needed?

**Q4: Interaction with Existing Features**

How does phased execution interact with:
- Dynamic filtering (can filters span phases?)
- Spill-to-disk (already a form of materialization)
- Fault-tolerant execution (checkpointing similarities)

**Q5: Multi-Query Optimization**

If multiple concurrent queries have similar subplans:
- Can MPs be shared across queries?
- How to coordinate materialization timing?

**Q6: Learning from Phases**

Can we use phase-by-phase execution data to:
- Improve statistics over time?
- Learn optimal MP placement?
- Predict which variants will be selected?

### 10.2 Experimental Questions

**E1: What is the typical materialization overhead?**

Measure across different:
- Data sizes (1GB, 10GB, 100GB, 1TB)
- Storage types (memory, SSD, S3)
- Data formats (compressed, uncompressed)

**E2: What is the re-optimization latency?**

Measure optimizer invocation time for:
- Simple remaining plans (2-3 operators)
- Complex remaining plans (10+ operators)
- With and without variant pre-generation

**E3: How often does re-optimization help?**

Analyze real query workloads:
- What fraction of queries have estimation errors > 2x?
- For those queries, what is the plan cost difference?
- How often would phased execution have chosen a different plan?

**E4: What is the break-even point?**

Find the threshold where phased execution overhead equals benefit:
- As a function of estimation error magnitude
- As a function of downstream plan complexity
- As a function of MP size

---

## 11. Related Work

### 11.1 Academic Research

**Progressive Optimization (Markl et al., SIGMOD 2004)**
- Mid-query re-optimization at checkpoints
- Focused on single-node systems
- Our work extends to distributed streaming

**Eddies (Avnur & Hellerstein, SIGMOD 2000)**
- Per-tuple adaptive routing
- Too fine-grained for vectorized execution
- Our work operates at phase granularity

**POP: Plan Bouquets (Dutt & Haritsa, VLDB 2014)**
- Pre-generate plans for different selectivity ranges
- Select at runtime based on observed selectivity
- Similar to our variant approach, but we apply to distributed systems

**Kepler (Marcus et al., SIGMOD 2023)**
- Learned plan selection for parametric queries
- Uses neural networks for robust selection
- Could inform our variant selection logic

### 11.2 Industrial Systems

**Spark AQE**
- Stage-based re-optimization at shuffle boundaries
- Shuffle is blocking, natural MP
- Our work brings similar benefits to streaming systems

**SQL Server Adaptive Query Processing**
- Adaptive joins (hash ↔ nested loop)
- Memory grant feedback
- Single-node focus

**Oracle Adaptive Plans**
- Statistics collectors in plan
- Runtime plan switching
- More complex to implement in distributed setting

### 11.3 Key Differences from Prior Work

| Prior Work | Model | Granularity | Our Contribution |
|------------|-------|-------------|------------------|
| Progressive Opt | Single-node | Arbitrary checkpoints | Distributed streaming |
| Eddies | Per-tuple | Fine | Phase-level batching |
| Spark AQE | Stage-based | Shuffle boundary | Works within streaming model |
| POP/Bouquets | Pre-generated | Query start | Runtime selection at multiple variance points |

---

## Appendix: Formal Model

### A.1 Query Execution Model

A query plan P is a DAG of operators:
```
P = (V, E, root)
where:
  V = set of operators
  E ⊆ V × V = data flow edges
  root ∈ V = output operator
```

An operator v ∈ V has:
- Input cardinality estimate: `est_in(v)`
- Output cardinality estimate: `est_out(v)`
- Actual input cardinality (at runtime): `act_in(v)`
- Actual output cardinality (at runtime): `act_out(v)`

### A.2 Blocking Operators

An operator v is **blocking** if it must consume all input before producing output:
```
blocking(v) ⟺ ∀ output o of v: o is produced after all inputs consumed
```

Examples: HashBuild, Sort, FinalAggregation

### A.3 Materialization Points

A Materialization Point MP at operator v:
```
MP(v) = {
  input: output of v,
  storage: persistent location for output,
  stats: {row_count, size, column_stats},
  state: WRITING | COMPLETE | CONSUMED
}
```

### A.4 Phased Execution

A phased execution of plan P with MPs at operators M ⊆ V:
```
Phases(P, M) = [Phase_1, Phase_2, ..., Phase_k]

where:
  Phase_i = subgraph of P ending at MP_i (or root if final phase)
  k = |M| + 1
```

### A.5 Cost Model

Total cost of phased execution:
```
Cost(Phases(P, M)) = Σ_i Cost(Phase_i) + Σ_m∈M Cost_mp(m) + (k-1) × Cost_reopt

where:
  Cost(Phase_i) = execution cost of phase i's operators
  Cost_mp(m) = write_cost(m) + read_cost(m) + coordination_cost
  Cost_reopt = optimizer invocation cost
```

### A.6 Benefit of Phased Execution

Expected benefit:
```
Benefit(M) = E[Cost(P_initial)] - E[Cost(Phases(P, M))]

where:
  P_initial = plan optimized with estimates only
  Phases uses actual cardinalities for later phases
```

Phased execution is beneficial when `Benefit(M) > 0`.

---

## Document Version

Version: 1.0
Date: December 2024
Authors: Research Team

## Next Steps

1. **Prototype MP operator** in Velox to measure materialization overhead
2. **Implement coordinator protocol** for phase tracking
3. **Extend optimizer** with MP placement heuristics
4. **Benchmark** on TPC-H/TPC-DS with artificially degraded statistics
5. **Evaluate** variant generation strategies on real workloads
