# Adaptive Phased Execution for Presto

## Design Document

**Status**: Draft
**Authors**: Research Team
**Date**: December 2024

---

## 1. Executive Summary

This document describes a design for adaptive query execution in Presto that works within its pull-based streaming execution model. The key insight is that **hash join builds are natural phase boundaries** where we have complete information about one side of a join and can make informed decisions about subsequent execution.

Rather than attempting mid-stream re-optimization (which conflicts with streaming), we propose:

1. **Bottom-up decision making**: At each join build completion, decide only the immediate next step
2. **Parallel variant preparation**: Build hash tables for multiple join candidates in parallel
3. **Variant selection via existing mechanisms**: Use `noMoreSplits` and split scheduling to activate/deactivate prepared variants
4. **No additional storage**: Hash tables already reside in memory; no separate materialization needed

---

## 2. Problem Statement

### 2.1 The Data Lake Statistics Problem

Data lakes suffer from unreliable cardinality estimates:
- Statistics collection is expensive and often skipped
- Data evolves continuously, making statistics stale
- Complex predicates defeat histogram-based estimation
- Partition pruning radically changes effective cardinalities

### 2.2 Impact on Join Ordering

For a query joining tables A, B, C, D:
- Optimal join order depends on intermediate result sizes
- Wrong order can cause 10-100x performance degradation
- Static optimization commits to order before knowing actual sizes

### 2.3 Constraints of Streaming Execution

Presto's pull-based streaming model means:
- Data flows continuously through exchanges (no stage boundaries like Spark)
- Cannot pause mid-stream to re-optimize
- Cannot restart with different plan once results flowing to client

### 2.4 The Opportunity

Hash join builds are **blocking operators**:
- Build side must complete before probe begins
- At build completion, we know exact build-side cardinality
- Probe side hasn't started yet - we can still make decisions

---

## 3. Design Overview

### 3.1 Core Concept: Bottom-Up Adaptive Join Ordering

For a multi-way join, make decisions incrementally at each phase boundary:

```
Query: A ⋈ B ⋈ C ⋈ D

Traditional (static):
  Optimizer picks order upfront: ((A ⋈ B) ⋈ C) ⋈ D
  Execute with fingers crossed

Adaptive (bottom-up):
  Phase 1: Build hash tables for all base tables (A, B, C, D)
           → Now we know all base cardinalities

  Phase 2: Pick best first join, say A ⋈ B
           Build hash table for A ⋈ B result
           → Now we know |A ⋈ B|

  Phase 3: Pick next join partner (C or D) based on actual |A ⋈ B|
           Build hash table for (A ⋈ B) ⋈ X
           → Now we know |A ⋈ B ⋈ X|

  Phase 4: Join with remaining table
           Stream to output
```

### 3.2 Key Insight: All Builds Can Run in Parallel

Hash builds for different tables are independent. We can:
- Start all builds in parallel
- As each completes, we learn its actual cardinality
- Make join decisions based on complete information

```
Time →
───────────────────────────────────────────────────────

A scan ──► A build ──────────────────┐
                                     │
B scan ──► B build ──────┐           │
                         ├─► Decision: A ⋈ B
C scan ──► C build ──────┤           │
                         │           ▼
D scan ──► D build ──────┴─► (A ⋈ B) build ──┐
                                              │
                              ┌───────────────┘
                              ▼
                         Decision: join C or D next?
                         Based on actual |A ⋈ B|
```

### 3.3 Variant Preparation and Selection

Rather than re-planning at runtime, **pre-generate variant plan fragments**:

```
At Optimization Time:
  - Identify phase boundaries (join builds)
  - Generate variant fragments for each decision point
  - All variants share common upstream (the completed build)

At Execution Time:
  - Run all builds in parallel
  - At decision point, select best variant
  - Activate chosen variant (schedule its splits)
  - Deactivate other variants (noMoreSplits)
```

---

## 4. Detailed Design

### 4.1 Phase Boundary Identification

A **phase boundary** occurs at every hash join build completion. The optimizer identifies these during planning:

```java
class PhaseBoundary {
    PlanFragmentId buildFragment;      // The fragment doing the build
    List<PlanVariant> variants;         // Possible next steps
    SelectionCriteria criteria;         // How to pick between variants
}

class PlanVariant {
    PlanFragmentId probeFragment;       // Fragment to activate
    JoinOrder joinOrder;                // What this variant represents
    EstimatedCost cost;                 // Cost estimate for this path
}
```

### 4.2 Coordinator-Side Phase Tracking

The coordinator tracks phase execution state:

```java
class AdaptiveExecutionState {
    // Current phase being executed
    int currentPhase;

    // Completed builds and their observed statistics
    Map<PlanFragmentId, BuildStatistics> completedBuilds;

    // Pending decisions (waiting for builds to complete)
    List<PendingDecision> pendingDecisions;

    // Activated variants (chosen path)
    Set<PlanFragmentId> activatedFragments;

    // Deactivated variants (not chosen)
    Set<PlanFragmentId> deactivatedFragments;
}

class BuildStatistics {
    long rowCount;
    long sizeBytes;
    long distinctKeyCount;
    long nullKeyCount;
    // Optional: key distribution histogram
}
```

### 4.3 Build Completion Reporting

When a hash build completes, the worker reports to coordinator:

```java
class BuildCompleteEvent {
    TaskId taskId;
    PlanFragmentId fragmentId;
    PlanNodeId buildNodeId;

    // Actual statistics from the build
    long actualRowCount;
    long hashTableSizeBytes;
    long distinctKeyCount;
    long nullKeyCount;
    Duration buildTime;
}
```

This uses existing task status reporting infrastructure, extended with build-specific statistics.

### 4.4 Variant Selection Logic

At each decision point, the coordinator selects the best variant:

```java
PlanVariant selectVariant(
    PhaseBoundary boundary,
    Map<PlanFragmentId, BuildStatistics> actualStats
) {
    // Re-estimate costs using actual statistics
    for (PlanVariant variant : boundary.variants) {
        Cost revisedCost = costModel.estimate(variant, actualStats);
        variant.setRevisedCost(revisedCost);
    }

    // Pick lowest cost variant
    return boundary.variants.stream()
        .min(comparing(PlanVariant::getRevisedCost))
        .orElseThrow();
}
```

### 4.5 Variant Activation/Deactivation

Using existing Presto mechanisms:

**Activating chosen variant:**
```java
void activateVariant(PlanVariant variant) {
    // Schedule the probe fragment normally
    // It will receive RemoteSplits pointing to the completed build's output
    stageScheduler.schedule(variant.probeFragment);
}
```

**Deactivating unchosen variants:**
```java
void deactivateVariant(PlanVariant variant) {
    // Tell the fragment there's no work to do
    // It will finish immediately without processing data
    stageExecution.noMoreSplits(variant.probeFragment);
}
```

### 4.6 Plan Structure: DAG with Shared Builds

The plan becomes a DAG where build outputs can be consumed by multiple potential probe variants:

```
                                    ┌─► Variant A: probe C next
                                    │   (C build) ⋈ (A⋈B output)
A scan ─► A build ─┐                │
                   ├─► (A⋈B) build ─┼─► Variant B: probe D next
B scan ─► B build ─┘                │   (D build) ⋈ (A⋈B output)
                                    │
C scan ─► C build ──────────────────┤
                                    │
D scan ─► D build ──────────────────┘

At decision point:
  - All builds complete, we know actual cardinalities
  - Select Variant A or B based on costs with actual stats
  - Activate chosen, deactivate other
```

### 4.7 Memory Management

**Hash tables for unchosen variants:**
- Built in parallel (work is done)
- Memory can be reclaimed after decision
- Trade-off: extra memory during decision window vs. ability to choose

**Hash table for chosen variant:**
- Consumed by probe as normal
- Memory reclaimed as probe progresses

**No additional storage needed:**
- Hash tables are the only "materialization"
- They exist in memory as part of normal join execution
- No checkpointing to disk or external storage

---

## 5. Worked Example

### 5.1 Query

```sql
SELECT *
FROM fact f
JOIN dim1 d1 ON f.d1_key = d1.key
JOIN dim2 d2 ON f.d2_key = d2.key
JOIN dim3 d3 ON f.d3_key = d3.key
```

### 5.2 Static Estimates (Uncertain)

```
|fact| = 1B rows (estimated, high confidence)
|dim1| = 10K rows (estimated)
|dim2| = 100K rows (estimated)
|dim3| = 1M rows (estimated)
|fact ⋈ dim1| = 500M rows (estimated, LOW confidence)
|fact ⋈ dim2| = 800M rows (estimated, LOW confidence)
```

### 5.3 Static Optimizer Choice

Based on estimates, optimizer chooses:
```
((fact ⋈ dim1) ⋈ dim2) ⋈ dim3
```

### 5.4 Adaptive Execution

**Phase 1: All builds run in parallel**

```
fact scan  ──► fact build (partitioned)
dim1 scan  ──► dim1 build ──► complete: 10K rows ✓
dim2 scan  ──► dim2 build ──► complete: 95K rows ✓
dim3 scan  ──► dim3 build ──► complete: 1.2M rows ✓
```

Dimension builds complete first (small tables).

**Decision Point 1: First join partner for fact**

We now know actual dimension sizes. The decision:
- Join with dim1 (10K build) → smaller build, good for broadcast
- Join with dim2 (95K build)
- Join with dim3 (1.2M build) → largest, probably not first

Coordinator selects: **fact ⋈ dim1** (smallest dimension)

Activate dim1 probe variant, deactivate dim2/dim3 probe variants (for now).

**Phase 2: fact ⋈ dim1 executes**

```
fact scan ──► probe dim1 hash table ──► (fact ⋈ dim1) build
                                              │
                                              ▼
                                        complete: 50M rows
                                        (10x smaller than estimated!)
```

**Decision Point 2: Next join partner**

We now know:
- |fact ⋈ dim1| = 50M (actual, was estimated 500M)
- |dim2| = 95K
- |dim3| = 1.2M

With 50M intermediate rows (much smaller than expected), joining dim3 next might be fine.

Coordinator re-evaluates costs and selects: **(fact ⋈ dim1) ⋈ dim2**

**Phase 3: (fact ⋈ dim1) ⋈ dim2 executes**

```
(fact ⋈ dim1) output ──► probe dim2 hash table ──► ((f ⋈ d1) ⋈ d2) build
                                                          │
                                                          ▼
                                                    complete: 45M rows
```

**Phase 4: Final join with dim3**

Only one option left. Execute and stream to output.

### 5.5 Benefit

If static plan had chosen wrong initial order (e.g., fact ⋈ dim3 first due to bad estimates), intermediate result could have been huge. Adaptive execution recovered by observing actual cardinality after dim1 join.

---

## 6. Integration with Existing Presto

### 6.1 Components to Modify

| Component | Change |
|-----------|--------|
| **Optimizer** | Generate variant fragments at phase boundaries |
| **PlanFragment** | Support multiple downstream variants |
| **SqlQueryScheduler** | Track phase state, make variant decisions |
| **StageExecution** | Report build completion statistics |
| **Task/Operator** | Emit build statistics on completion |

### 6.2 Relationship to PhasedExecutionSchedule

The existing `PhasedExecutionSchedule` controls **scheduling order** (build before probe) but doesn't wait for phases or make adaptive decisions.

Our design:
- Uses same dependency analysis (build → probe edges)
- Adds **decision points** at build completions
- Adds **variant selection** based on observed statistics
- Maintains streaming execution within each phase

### 6.3 Existing Mechanisms Leveraged

| Mechanism | How We Use It |
|-----------|---------------|
| `noMoreSplits` | Deactivate unchosen variants |
| Split scheduling | Activate chosen variants |
| Task status reporting | Learn when builds complete |
| OutputBuffer | Completed build output consumed by chosen probe |
| Fragment dependencies | Identify phase boundaries |

---

## 7. Open Questions

### 7.1 Output Buffer Handling for Multiple Variants

**Question**: If multiple probe variants have RemoteSource pointing to the same build output, and we only activate one, does the output buffer handle this correctly?

**Current Understanding**:
- Output buffers support multiple consumers
- If a consumer never reads (deactivated variant), buffer should handle gracefully
- Need to verify: does `noMoreSplits` to a stage cause it to close its input buffers cleanly?

**Action Needed**: Review OutputBuffer and RemoteSourceNode interaction when consumer is immediately closed.

### 7.2 Timing of Variant Decision

**Question**: When exactly can we make the variant decision?

**Scenario**:
```
Build A completes at T=10s
Build B completes at T=12s
Build C completes at T=15s

We need all of A, B, C complete to make fully informed first join decision.
But can probe of A start while waiting for B and C?
```

**Options**:
1. **Wait for all sibling builds**: Most information, but delays start
2. **Decide as soon as any build completes**: Less information, earlier start
3. **Speculative execution**: Start most likely probe, switch if better option emerges

**Recommendation**: Start with option 1 (wait for all sibling builds). Simpler, and build times for dimensions are usually fast anyway.

### 7.3 Memory Pressure from Parallel Builds

**Question**: Building hash tables for all join candidates in parallel increases peak memory usage. Is this acceptable?

**Analysis**:
- For star schemas: dimension tables are typically small (fit in memory)
- For arbitrary joins: could be multiple large tables

**Mitigations**:
1. Only parallelize builds for tables below size threshold
2. Spill large builds to disk (existing spill infrastructure)
3. Limit concurrent builds based on memory budget

**Recommendation**: Implement with configurable limit on parallel builds. Default to dimension-table-sized threshold.

### 7.4 Number of Variants to Generate

**Question**: How many variant plan fragments should optimizer generate?

**Analysis**:
- For N tables in join: N! possible orders (factorial explosion)
- Can't generate all variants

**Pruning Strategies**:
1. **Top-K by estimated cost**: Generate variants for K best estimated orders
2. **Confidence-based**: Generate variants where estimate confidence is low
3. **Structure-preserving**: Only vary join order, not join algorithms
4. **Greedy at each level**: At each decision point, only 2-3 next-step variants

**Recommendation**: Greedy approach - at each phase boundary, generate variants only for immediate next join (not entire remaining plan). This bounds variants to O(N) per decision point instead of O(N!).

### 7.5 Partial Build Statistics

**Question**: What statistics should builds report?

**Minimum Viable**:
- Row count (most important for join ordering)

**Useful Additions**:
- Hash table size in bytes (memory planning)
- Distinct key count (join selectivity estimation)
- Null key count (null handling optimization)
- Build duration (performance modeling)

**Future Extensions**:
- Key distribution histogram (skew detection)
- Hot keys (for skew handling)

**Recommendation**: Start with row count and hash table size. Add others based on need.

### 7.6 Interaction with Dynamic Filtering

**Question**: How does adaptive phased execution interact with dynamic filtering?

**Current State**:
- Dynamic filters are generated from build side
- Pushed to probe-side scans
- Applied before probe reads data

**With Adaptive Execution**:
- If we change which build is probed first, different dynamic filters apply
- The fact table scan might start before we decide join order
- How to handle dynamic filters for a not-yet-chosen join?

**Options**:
1. **Delay fact scan until decision made**: Simplest, but delays everything
2. **Apply all dynamic filters**: Collect filters from all builds, intersect them
3. **No early dynamic filtering**: Only apply after variant selection

**Recommendation**: Option 2 (apply all filters) - dimension builds complete fast, fact scan benefits from all filters regardless of join order chosen.

### 7.7 Cascading Decisions

**Question**: After first join decision, subsequent decisions have different input. How to handle?

**Example**:
```
Phase 1: fact, dim1, dim2, dim3 builds complete
Decision 1: fact ⋈ dim1 (now we wait for this to complete)

Phase 2: (fact ⋈ dim1) build completes
Decision 2: (fact ⋈ dim1) ⋈ ?
           At this point, dim2 and dim3 builds already done
           Decision is purely based on cardinalities
```

**Observation**: This works naturally. Each decision only needs statistics from:
- Completed builds (available)
- Not-yet-chosen dimension builds (already complete)

No special handling needed for cascading.

### 7.8 Probe-Side Scheduling Delay

**Question**: Does Presto's current scheduler start probe-side stages before builds complete?

**Current Behavior** (from PhasedExecutionSchedule):
- Build fragments scheduled before probe fragments
- But both may be "active" concurrently
- Probe tasks might be scheduled even if they can't run yet

**Concern**: If probe fragment is already scheduled, can we still "deactivate" it?

**Investigation Needed**:
- What happens if we send `noMoreSplits` to an already-scheduled stage?
- Does it gracefully finish with no output?
- Any resource leaks?

### 7.9 Fallback Behavior

**Question**: What if adaptive decision-making fails or times out?

**Scenarios**:
- Statistics not available (worker failure, timeout)
- Decision logic errors
- Memory pressure prevents parallel builds

**Fallback**: Revert to statically-chosen plan (the default optimizer choice).

**Implementation**:
- All variant fragments include the "default" from static optimization
- If anything goes wrong, activate default variant
- Graceful degradation to non-adaptive execution

---

## 8. Implementation Roadmap

### Phase 1: Foundation (Experimental)

1. **Extend Optimizer for Variant Generation**
   - Identify join phase boundaries
   - Generate 2 variants per decision point (default + one alternative)
   - Represent as DAG plan structure

2. **Add Build Statistics Reporting**
   - Hash build operator reports row count on completion
   - Wire through task status to coordinator

3. **Basic Variant Selection**
   - Coordinator tracks build completions
   - Simple cost re-calculation with actual row counts
   - Activate/deactivate variants

4. **Testing**
   - Queries with known cardinality estimation errors
   - Verify correct variant selection
   - Measure overhead

### Phase 2: Robustness

5. **Handle Edge Cases**
   - Empty builds
   - Skewed data
   - Memory pressure

6. **Fallback Mechanisms**
   - Timeout handling
   - Error recovery
   - Graceful degradation

7. **Dynamic Filter Integration**
   - Apply filters from all parallel builds
   - Correct filter handling after variant selection

### Phase 3: Optimization

8. **Smarter Variant Generation**
   - Confidence-based pruning
   - Cost-based variant ranking
   - Limit total variants

9. **Memory Management**
   - Parallel build memory budgeting
   - Eager reclamation of unchosen hash tables

10. **Performance Tuning**
    - Minimize decision latency
    - Optimize coordinator overhead
    - Benchmark on production-like workloads

---

## 9. Success Metrics

### 9.1 Correctness
- Adaptive execution produces same results as static execution
- All query semantics preserved

### 9.2 Performance (Positive Cases)
- Queries with significant estimation errors: 2-10x improvement
- Star schema queries with selective dimension joins: measurable improvement

### 9.3 Performance (Overhead)
- Queries with accurate estimates: <5% overhead from parallel builds
- Decision latency: <100ms per phase boundary
- Memory overhead: bounded and configurable

### 9.4 Reliability
- Graceful fallback on errors
- No new crash scenarios
- Production-stable after Phase 2

---

## 10. Alternatives Considered

### 10.1 Full Re-optimization at Phase Boundaries

**Description**: Invoke the full optimizer with updated statistics after each phase.

**Rejected Because**:
- Optimization latency added to query execution
- Complex plan diffing to reuse completed work
- Overkill for join ordering decisions

### 10.2 Spark-Style Stage Materialization

**Description**: Materialize to storage at phase boundaries, re-optimize, read back.

**Rejected Because**:
- Conflicts with Presto's streaming model
- Adds I/O latency
- Requires fault-tolerance infrastructure

### 10.3 Eddies-Style Per-Tuple Routing

**Description**: Route each tuple through operators adaptively.

**Rejected Because**:
- Too fine-grained for vectorized execution
- Conflicts with pull-based model
- High overhead

### 10.4 Speculative Execution of All Variants

**Description**: Run all variants in parallel, use fastest result.

**Rejected Because**:
- Massive resource waste
- Complex result reconciliation
- Memory explosion

---

## 11. References

1. Presto `PhasedExecutionSchedule.java` - Existing phase-based scheduling
2. Presto `SqlQueryScheduler.java` - Query execution coordination
3. "Adaptive Query Processing" (Deshpande et al.) - Academic foundations
4. "Eddies: Continuously Adaptive Query Processing" (Avnur & Hellerstein) - Per-tuple adaptation
5. Spark AQE Documentation - Stage-based adaptation (different model)
6. SQL Server Adaptive Joins - Single-node adaptation

---

## Appendix A: Glossary

| Term | Definition |
|------|------------|
| **Phase Boundary** | Point where a blocking operator (hash build) completes |
| **Variant** | Alternative plan fragment for execution after a phase boundary |
| **Activation** | Scheduling a variant for execution |
| **Deactivation** | Sending noMoreSplits to prevent variant execution |
| **Bottom-Up** | Making decisions incrementally at each phase boundary |

## Appendix B: Configuration Properties (Proposed)

```properties
# Enable adaptive phased execution
adaptive_phased_execution_enabled=true

# Maximum variants per decision point
adaptive_max_variants_per_decision=3

# Maximum concurrent parallel builds
adaptive_max_parallel_builds=10

# Memory threshold for parallel builds (bytes)
adaptive_parallel_build_memory_limit=10GB

# Decision timeout (fall back to default after this)
adaptive_decision_timeout=5s
```

## Appendix C: Telemetry (Proposed)

Metrics to track:
- `adaptive.decisions.total` - Number of variant decisions made
- `adaptive.decisions.changed_from_default` - Decisions that differed from static plan
- `adaptive.build_statistics.reported` - Build completions with statistics
- `adaptive.decision_latency_ms` - Time to make each decision
- `adaptive.variants.activated` - Variants that were executed
- `adaptive.variants.deactivated` - Variants that were skipped
- `adaptive.memory.parallel_builds_peak` - Peak memory for concurrent builds
