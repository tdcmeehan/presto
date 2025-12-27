# Adaptive Phased Execution for Presto

## Design Document

**Status**: Draft
**Authors**: Research Team
**Date**: December 2024

---

## 1. Executive Summary

This document describes a design for adaptive query execution in Presto that works within its pull-based streaming execution model. The key insight is that **hash join builds are natural phase boundaries** where we have complete information about one side of a join and can make informed decisions about subsequent execution.

Rather than attempting mid-stream re-optimization (which conflicts with streaming), we propose:

1. **Confidence-based materialization points**: Use Presto's existing `ConfidenceLevel` (LOW/HIGH/FACT) to decide where runtime measurement is needed
2. **Bottom-up decision making**: At each join build completion, decide only the immediate next step
3. **On-demand builds**: Only build hash tables when needed, not all in parallel (memory efficient)
4. **Variant selection via existing mechanisms**: Use `noMoreSplits` and split scheduling to activate/deactivate prepared variants
5. **No additional storage**: Hash tables already reside in memory; no separate materialization needed

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

### 3.1 Confidence-Based Materialization Points

Presto already has a statistics confidence system we can leverage:

```java
// From presto-spi/src/main/java/com/facebook/presto/spi/statistics/SourceInfo.java
public enum ConfidenceLevel {
    LOW(0),   // Unreliable - derived estimates, missing stats
    HIGH(1),  // Good - catalog stats, histograms available
    FACT(2);  // Exact - VALUES clause, completed aggregation
}
```

**Existing usage**: `ConfidenceBasedBroadcastUtil` already uses confidence to choose join distribution type.

**Our extension**: Use confidence to decide where materialization points (MPs) are needed:

| Confidence | Source Examples | MP Decision |
|------------|-----------------|-------------|
| **FACT** | VALUES clause, global agg | No MP - stats are exact |
| **HIGH** | Table scan with good catalog stats | No MP - trust estimate |
| **LOW** | Join output, complex filter, missing stats | **Insert MP** - measure at runtime |

This is more general than "leaf vs. non-leaf":
- A leaf scan with **missing** catalog stats → LOW → needs MP
- A leaf scan with **good** catalog stats → HIGH → no MP needed
- Any join output → LOW (JoinStatsRule doesn't boost confidence) → needs MP

### 3.2 Core Concept: Bottom-Up Adaptive Join Ordering

For a multi-way join, make decisions incrementally at each phase boundary:

```
Query: A ⋈ B ⋈ C ⋈ D

Traditional (static):
  Optimizer picks order upfront: ((A ⋈ B) ⋈ C) ⋈ D
  Execute with fingers crossed

Adaptive (bottom-up):
  Phase 1: Use catalog stats (HIGH confidence) to pick first join
           → Catalog says |dim1| < |dim2| < |dim3|
           → Pick: fact ⋈ dim1
           → Build dim1 hash table, probe with fact

  Phase 2: Join completes (LOW confidence output becomes FACT)
           → Actual |fact ⋈ dim1| = 50M (was estimated 500M!)
           → Pick next: (fact ⋈ dim1) ⋈ dim2
           → Build dim2 hash table now (not earlier!)

  Phase 3: Join completes
           → Actual |(fact ⋈ dim1) ⋈ dim2| = 45M
           → Continue with dim3
           → Stream to output
```

### 3.3 On-Demand Builds (Memory Efficient)

**Key insight**: We don't need to build all hash tables upfront.

- **Catalog stats (HIGH confidence)** tell us base table sizes
- **Only build what we need** for the current join
- **Measure output** at join completion (LOW → FACT)
- **Decide next join** based on actual cardinality

```
Memory-Efficient Execution:

Phase 1:
  - Trust catalog: |dim1|=10K, |dim2|=100K, |dim3|=1M
  - Build only dim1 hash table (10K rows)
  - Execute fact ⋈ dim1
  - Memory: just dim1 hash table

Phase 2:
  - Actual |fact ⋈ dim1| = 50M known
  - Build dim2 hash table (100K rows)
  - Execute (fact ⋈ dim1) ⋈ dim2
  - Memory: dim2 hash table + intermediate

Phase 3:
  - Build dim3 hash table (1M rows)
  - Execute final join
  - Stream to output
```

Compare to parallel builds:
```
Parallel (wasteful):
  Memory = |dim1 HT| + |dim2 HT| + |dim3 HT| simultaneously

On-demand (efficient):
  Memory = max(|dim1 HT|, |dim2 HT|, |dim3 HT|) at any time
```

### 3.4 Variant Preparation and Selection

Rather than re-planning at runtime, **pre-generate variant plan fragments** for uncertain decision points:

```
At Optimization Time:
  - Identify materialization points (MPs) where stats have LOW confidence
  - Generate variant fragments for each decision point
  - All variants share common upstream (the completed build)

At Execution Time:
  - Build hash table for current join (on-demand)
  - Execute join, measure output cardinality
  - At MP completion, select best variant for next step
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

### 4.6 Plan Structure: On-Demand Execution Flow

The plan executes with on-demand builds, making decisions at materialization points:

```
Phase 1: First Join (catalog stats are HIGH confidence)
  dim1 scan ─► dim1 build ─┐
                           ├─► fact ⋈ dim1 ─► MP: measure output
  fact scan ────────────────┘
                              │
                              ▼ (output now FACT confidence)
                         Decision Point 1

Phase 2: Second Join (choose based on actual cardinality)
                                    ┌─► Variant A: (fact⋈dim1) ⋈ dim2
  Activate chosen variant ─────────┤
                                    └─► Variant B: (fact⋈dim1) ⋈ dim3

  dim2 scan ─► dim2 build (on-demand) ─┐
                                       ├─► (fact⋈dim1) ⋈ dim2 ─► MP
  (fact⋈dim1) output ──────────────────┘
                                          │
                                          ▼
                                     Decision Point 2
Phase 3: Final Join
  dim3 scan ─► dim3 build (on-demand) ─┐
                                       ├─► final join ─► output
  previous output ─────────────────────┘
```

At each decision point:
  - Current join completes, we measure actual cardinality
  - Select best variant for next join based on actual stats
  - Build hash table for chosen dimension (on-demand)
  - Activate chosen, deactivate others

### 4.7 Memory Management

**On-demand builds (memory efficient):**
- Hash tables built only when needed for current join
- Memory footprint: one hash table at a time (plus intermediate results)
- No wasted memory on unchosen variants

**Hash table lifecycle:**
- Built on-demand when join is scheduled
- Consumed by probe as normal
- Memory reclaimed as probe progresses

**No additional storage needed:**
- Hash tables are the only "materialization"
- They exist in memory as part of normal join execution
- Materialization points measure statistics, not store data
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

**Phase 1: First Join (using catalog stats)**

Catalog has HIGH confidence stats for dimensions: |dim1|=10K, |dim2|=100K, |dim3|=1M

Static optimizer picks dim1 first (smallest). Build dim1 on-demand:

```
dim1 scan ──► dim1 build ──► complete: 10K rows ✓
                    │
fact scan ──────────┴──► probe dim1 hash table ──► MP: measure output
                                                          │
                                                          ▼
                                                    actual: 50M rows
                                                    (10x smaller than estimated!)
```

**Decision Point 1: Next join partner**

We now know:
- |fact ⋈ dim1| = 50M (actual FACT, was estimated 500M with LOW confidence)
- |dim2| = 100K (catalog stats, HIGH confidence)
- |dim3| = 1M (catalog stats, HIGH confidence)

Coordinator re-evaluates: with 50M rows (not 500M), cost model picks dim2 next.

**Phase 2: (fact ⋈ dim1) ⋈ dim2 (on-demand build)**

Build dim2 hash table now (not earlier - saves memory):

```
dim2 scan ──► dim2 build ──► complete: 95K rows ✓
                    │
(f⋈d1) output ──────┴──► probe dim2 hash table ──► MP: measure output
                                                          │
                                                          ▼
                                                    actual: 45M rows
```

**Decision Point 2: Final join**

Only dim3 remains. No decision needed.

**Phase 3: Final join with dim3 (on-demand build)**

Build dim3 hash table, execute final join, stream to output:

```
dim3 scan ──► dim3 build ──► complete: 1.2M rows ✓
                    │
((f⋈d1)⋈d2) ────────┴──► probe dim3 hash table ──► stream to output
```

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

With on-demand builds, the timing is simpler:

```
Phase 1: Build dim1 → probe fact → MP reports |fact⋈dim1|
         Decision: which dimension to join next?

Phase 2: Build chosen dimension → probe result → MP reports cardinality
         Decision: which dimension for final join?
         (Only 1 choice remaining - no decision needed)
```

**Key Insight**: Each decision is made at the materialization point (MP), after the current join completes. We have:
- Actual cardinality from just-completed join (FACT confidence)
- Catalog estimates for remaining dimensions (HIGH confidence if available)

**No waiting dilemma**: We build one hash table at a time, make a decision at each MP. No parallel builds means no question of "when to decide."

### 7.3 First Join Decision with On-Demand Builds

**Question**: With on-demand builds, how do we make the first join decision (before any join output is observed)?

**Analysis**:
- For the first join, we only have catalog statistics
- If catalog stats have HIGH confidence, trust them for the first decision
- If catalog stats have LOW confidence (missing stats), we may need special handling

**Options**:
1. **Trust catalog for base tables**: Assume catalog stats are "good enough" for dimension tables even if marked LOW. Dimension sizing is usually reasonable from catalogs.
2. **Build smallest estimated dimension first**: Conservative approach - if wrong, limited downside.
3. **Add table scan statistics**: Extend table scans to report row count before join (minimal overhead).

**Recommendation**: Option 2 (build smallest estimated dimension first). This is what static optimization does anyway, and the first join cardinality is typically dominated by fact table selectivity, not dimension choice.

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

**With On-Demand Adaptive Execution**:
- We build dimension hash tables one at a time
- Each build generates a dynamic filter for its join
- Filters are applied to the probe side (fact or intermediate result)

**Interaction**:
```
Phase 1: Build dim1 → generates DF1 for d1_key
         Probe fact with DF1 applied → (fact ⋈ dim1)

Phase 2: Build dim2 → generates DF2 for d2_key
         Probe (fact ⋈ dim1) with DF2 applied → ((fact ⋈ dim1) ⋈ dim2)
```

**Observation**: Dynamic filtering works naturally with on-demand builds. Each phase has exactly one build generating one dynamic filter, applied to the current probe side.

**Enhancement Opportunity**: If all dimensions are small and build fast, could generate all dynamic filters upfront and apply intersection to fact scan (separate from adaptive join ordering).

### 7.7 Cascading Decisions

**Question**: After first join decision, subsequent decisions have different input. How to handle?

**Example with On-Demand Builds**:
```
Phase 1: Static optimizer picks dim1 first (smallest catalog estimate)
         Build dim1, execute fact ⋈ dim1
         MP reports: |fact ⋈ dim1| = 50M (actual)

Decision 1: Next join = (fact ⋈ dim1) ⋈ ?
            Inputs: actual 50M rows, catalog estimates for dim2 (100K), dim3 (1M)
            → Pick dim2 (smaller remaining dimension)

Phase 2: Build dim2 (on-demand), execute join
         MP reports: |(fact ⋈ dim1) ⋈ dim2| = 45M

Decision 2: Only dim3 remains
            → No real decision, proceed with dim3
```

**Observation**: This works naturally with on-demand builds. Each decision only needs:
- Actual cardinality from just-completed join (FACT)
- Catalog estimates for remaining dimensions (HIGH confidence)

The bottom-up approach means we never need to speculate about future joins.

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

# Confidence level threshold for inserting materialization points
# LOW = insert MP (need runtime measurement)
# HIGH or FACT = skip MP (trust estimates)
adaptive_mp_confidence_threshold=LOW

# Decision timeout (fall back to static plan after this)
adaptive_decision_timeout=5s

# Minimum cardinality estimation error ratio to trigger re-ordering
# e.g., 2.0 means actual must be 2x different from estimate to consider change
adaptive_reorder_threshold=2.0
```

## Appendix C: Telemetry (Proposed)

Metrics to track:
- `adaptive.decisions.total` - Number of variant decisions made
- `adaptive.decisions.changed_from_default` - Decisions that differed from static plan
- `adaptive.materialization_points.hit` - MPs where cardinality was measured
- `adaptive.cardinality_error_ratio` - Ratio of actual vs estimated cardinality
- `adaptive.decision_latency_ms` - Time to make each decision
- `adaptive.variants.activated` - Variants that were executed
- `adaptive.variants.deactivated` - Variants that were skipped
- `adaptive.query_improvement_ratio` - Performance gain vs static plan (A/B testing)
