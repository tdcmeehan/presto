# Adaptive Exchange Framework for Presto

## Design Document

**Status**: Draft
**Authors**: Tim Meehan
**Date**: December 2024
**Version**: 0.2
**Runtime**: Velox (C++) only

---

## 1. Executive Summary

This document describes the **Adaptive Exchange Framework (AEF)**, a unified approach to adaptive query execution in Presto that works within its streaming execution model. The key insight is that **exchanges are natural observation and decision points** where we can:

1. **Buffer a sample** of the data stream (first N rows)
2. **Observe actual statistics** (cardinality, distribution, skew)
3. **Probe hash tables** with the buffer sample to measure join selectivity
4. **Cascade hold signals** upstream to create global synchronization points
5. **Reoptimize** the query plan based on actual statistics
6. **Release buffers** and continue streaming execution

This approach provides Axiom-like statistics quality (actual cardinalities, measured join selectivities) without separate pilot queries, using the real query execution as an inline "pilot."

> **Note**: This design targets the Velox (C++) execution engine only. There are no plans to port this functionality to the Java runtime.

### Why This Approach

| Challenge | How AEF Solves It |
|-----------|-------------------|
| Unknown base table cardinalities | Buffer at scan exchanges, observe actual filtered sizes |
| Unknown join selectivity | Probe buffer sample through hash table before full probe |
| Limited reoptimization scope | Cascading holds create global synchronization for full replan |
| Pilot query overhead | No separate pilots--real execution IS the pilot |
| Streaming compatibility | Buffer-then-stream preserves Presto's model |

### Comparison with Other Approaches

| Approach | Statistics Source | Scope | Overhead | Streaming |
|----------|------------------|-------|----------|-----------|
| **Static optimization** | Catalog estimates | N/A | None | Yes |
| **Spark AQE** | Stage boundaries | Local (no join reorder) | Stage materialization | No (stage-based) |
| **Axiom pilots** | Separate sample queries | Full plan | Pilot execution | N/A |
| **AEF (this design)** | Inline buffer samples | Full plan (with cascading) | Buffer fill time | Yes |

---

## 2. Problem Statement

### 2.1 The Core Problem: Join Cardinality Estimation

The fundamental challenge in query optimization is estimating join output cardinalities:

```
Leaf estimates (usually okay):
  |fact| = 1B rows        <-- catalog stats, often accurate
  |dim1| = 10K rows       <-- catalog stats, often accurate

Join estimates (often wrong):
  |fact JOIN dim1| = ???     <-- depends on selectivity

  Estimate: 500M (assumes independence)
  Actual: 50M (correlated filters, skewed keys)
  Error: 10x

Nested join estimates (errors compound):
  |fact JOIN dim1 JOIN dim2| = ???

  Estimate: 400M
  Actual: 40M
  Error: 10x (compounded from previous)
```

### 2.2 Why Existing Approaches Fall Short

**Static optimization**: Commits to plan before seeing any actual data. Estimation errors are locked in.

**Spark AQE**: Observes statistics at stage boundaries but explicitly does NOT do join reordering. Limited to join strategy switching, partition coalescing, and skew handling.

**Axiom pilots**: Runs separate sample queries during optimization. Adds latency for ad-hoc queries. Requires complex sampling infrastructure.

**Our previous designs** (phased execution, output gating): Observe actual cardinalities but can only adapt the remainder of the query, not reconsider earlier decisions.

### 2.3 The Opportunity: Exchanges as Observation Points

Presto's distributed execution requires **exchanges** to shuffle data between fragments. These are natural points where:

1. Data is already being serialized (small buffer adds minimal overhead)
2. Coordinator already tracks progress (statistics reporting is natural)
3. Build-side exchanges complete before probe begins (natural synchronization)

**Key insight**: If we buffer exchanges and cascade hold signals, we can create **global synchronization points** where the coordinator has full freedom to reoptimize.

---

## 3. Design Overview

### 3.1 Core Concept: Adaptive Exchange Operator

An **Adaptive Exchange** is an exchange variant that:

1. Buffers the first N rows (configurable, default 100K)
2. Computes statistics on the buffer (cardinality, key distribution, skew)
3. Signals upstream exchanges to hold (cascading backpressure)
4. Reports statistics to coordinator
5. Waits for coordinator decision
6. Releases buffer and continues streaming

```
+------------------------------------------------------------------+
|                     Adaptive Exchange                            |
+------------------------------------------------------------------+
|                                                                  |
|   Input Stream ---> [Buffer: first N rows]                       |
|                           |                                      |
|                           +-- Compute statistics                 |
|                           +-- Signal upstream HOLD               |
|                           +-- Report to coordinator              |
|                           +-- Wait for decision                  |
|                           |                                      |
|                           v                                      |
|                    [Coordinator Decision]                        |
|                           |                                      |
|                           +-- CONTINUE: release buffer, stream   |
|                           +-- REOPTIMIZE: apply new plan         |
|                                                                  |
|   After decision: ---> Output Stream (buffer + remainder)        |
|                                                                  |
+------------------------------------------------------------------+
```

### 3.2 Cascading Hold Signals

When an adaptive exchange buffers and holds, it signals upstream exchanges to also hold:

```
[AE-1] ---> [AE-2] ---> [AE-3] ---> Join ---> Output
  |          |          |
  |          |          +-- AE-3 buffers, signals HOLD upstream
  |          |
  |          +-- AE-2 receives HOLD, stops emitting, signals HOLD upstream
  |
  +-- AE-1 receives HOLD, stops emitting

Result: Entire pipeline pauses at a consistent point
        Coordinator can make global decisions
        Then release all and continue
```

This creates a **global synchronization point** without full materialization.

### 3.3 Join Output Estimation

The core statistic driving adaptive decisions is **join output cardinality**. Rather than relying solely on optimizer estimates, we gather multiple signals during execution to continuously refine this estimate. All signals feed into a unified estimator that triggers reoptimization when deviation is significant.

#### Why Static Estimation Fails

The classic join cardinality formula is:

```
|A ⋈ B| ≈ |A| × |B| / max(NDV(A.key), NDV(B.key))
```

This formula fails in practice because:
- **NDV from catalog is often stale or missing** - especially for complex expressions or derived tables
- **Assumes uniform key distribution** - real data is often skewed
- **Ignores key containment** - are A's keys a subset of B's, or mostly disjoint?
- **Ignores fanout** - when keys match, how many rows on each side?

**A better model:**

```
Selectivity = Containment × Fanout

Where:
  Containment = fraction of probe keys that exist in build
  Fanout = average build rows per matching probe key

|A ⋈ B| = |A| × Containment × Fanout
```

The key insight: **we can measure containment and fanout directly from actual data**, giving 10-100x more accurate estimates than catalog-based formulas.

#### What We Observe From Actual Execution

**From hash build (exact values, free as we build):**
```java
class HashBuildStats {
    long rowCount;              // Exact count of build rows
    long distinctKeyCount;      // Exact NDV (hash table size)
    long nullCount;             // Keys with null values
    TopKTracker keyFrequencies; // For skew detection
}
```

**From probe buffer (sample, extrapolate for full):**
```java
class ProbeBufferStats {
    long sampleRowCount;        // Buffer size
    long sampleDistinctKeys;    // Distinct keys in buffer
    HyperLogLog ndvEstimator;   // For extrapolating full probe NDV
    long nullCount;             // Null keys in sample
}
```

**From probing buffer through hash table (measured directly):**
```java
class JoinProbeStats {
    long probeKeysFound;        // Probe keys that exist in build
    long totalMatches;          // Total matching row pairs

    double containment() {
        return (double) probeKeysFound / sampleDistinctKeys;
    }

    double fanout() {
        return probeKeysFound > 0
            ? (double) totalMatches / probeKeysFound
            : 0;
    }
}
```

#### Signal 1: Build-Side Progress (Early, Indirect)

As the hash build proceeds, we track rows processed, NDV, and can extrapolate:

```
dim1 scan ---> Hash Build (reporting progress)
                   |
                   +-- 20% complete: 20K rows, 18K distinct keys
                   |                 Projected: 100K rows, 90K NDV
                   |                 Expected: 10K rows, 10K NDV
                   |                 --> 10x cardinality deviation!
                   |
                   +-- Coordinator updates estimate early
                       Can trigger reoptimization BEFORE build completes
```

This catches cardinality estimation errors early, minimizing wasted work.

#### Signal 2: Probe Buffer Analysis (After Build, Direct)

Once build completes, probe the buffer through the hash table to measure actual containment and fanout:

```
dim1 scan ---> [Exchange] ---> Hash Build ---> (complete)
                                   |
                                   v
                          +-----------------+
                          |  Hash Table     |
                          |  50K distinct   |
                          +--------^--------+
                                   |
                                   | Probe buffer!
                                   |
fact scan ---> [Adaptive Exchange: 100K buffer, 80K distinct keys]
                    |
                    +-- 1. Probe 80K distinct keys through hash table
                    +-- 2. Found: 60K keys match (containment = 0.75)
                    +-- 3. Those 60K keys match 72K build rows (fanout = 1.2)
                    +-- 4. Selectivity = 0.75 × 1.2 = 0.9
                    +-- 5. |fact ⋈ dim1| ≈ |fact| × 0.9
```

**Comparison with static formula:**
```
Static (catalog-based):
  |fact| = 1B, |dim1| = 100K (estimated)
  NDV(fact.key) = 1M, NDV(dim1.key) = 50K (estimated, often wrong)
  |fact ⋈ dim1| = 1B × 100K / max(1M, 50K) = 2B (WRONG)

Dynamic (observed):
  Containment = 0.75 (measured)
  Fanout = 1.2 (measured)
  |fact ⋈ dim1| = 1B × 0.75 × 1.2 = 900M (ACCURATE)
```

#### Signal 3: Early Selectivity Probe (Optional, During Build)

If the probe buffer is ready before build completes, we can probe the partial hash table for an early signal:

```
At 50% build complete:
  - Hash table has ~50% of keys
  - Probe buffer keys through partial table
  - Scale containment: measured_containment / 0.5
  - Get early selectivity estimate

Caveats:
  - Only reliable if build stream is unordered by join key
  - Use as early warning, validate with full probe
```

#### Signal 4: Actual Output (During Probe, Exact)

As the join executes, track actual output rows:

```
Join executing:
  - 10M probe rows processed so far
  - 9M output rows produced
  - Observed selectivity = 0.9
  - Confirms or refines earlier estimates
  - Can update estimates for downstream joins
```

#### Unified Estimator

All signals feed into one estimate, using the most accurate available:

| Signal | Timing | What's Measured | Confidence |
|--------|--------|-----------------|------------|
| Build progress | During build | Build cardinality, NDV | Medium |
| Early probe | During build | Containment (scaled) | Medium |
| Full probe | After build | Containment, fanout (exact) | High |
| Actual output | During probe | True selectivity | Highest |

```java
class JoinOutputEstimator {
    // Observed statistics
    private HashBuildStats buildStats;
    private ProbeBufferStats probeStats;
    private JoinProbeStats joinProbeStats;
    private ActualOutputStats actualStats;

    long getBestEstimate() {
        if (actualStats != null) {
            // Highest confidence: extrapolate from actual output
            return actualStats.extrapolateTotal();
        }

        if (joinProbeStats != null) {
            // High confidence: measured containment and fanout
            double selectivity = joinProbeStats.containment() * joinProbeStats.fanout();
            long probeRows = probeStats.estimateFullRowCount();
            return (long) (probeRows * selectivity);
        }

        if (buildStats != null && probeStats != null) {
            // Medium confidence: use observed NDV in formula
            long buildNDV = buildStats.distinctKeyCount;
            long probeNDV = probeStats.estimateFullNDV();
            double formulaSelectivity = 1.0 / Math.max(buildNDV, probeNDV);
            long probeRows = probeStats.estimateFullRowCount();
            return (long) (probeRows * formulaSelectivity);
        }

        if (buildStats != null) {
            // Low-medium confidence: scale by build deviation only
            double buildRatio = (double) buildStats.projectedTotal / originalBuildEstimate;
            return (long) (originalEstimate * buildRatio);
        }

        return originalEstimate;  // Low confidence (optimizer estimate)
    }

    double getConfidence() {
        if (actualStats != null) return 0.95;
        if (joinProbeStats != null) return 0.85;
        if (buildStats != null && probeStats != null) return 0.7;
        if (buildStats != null) return 0.5;
        return 0.3;
    }
}
```

#### Reoptimization Trigger

One decision point, regardless of which signal updated the estimate:

```java
void onEstimateUpdated(JoinNode join, long newEstimate, double confidence) {
    double deviation = (double) newEstimate / originalEstimate;

    // Higher confidence --> can act on smaller deviations
    double threshold = BASE_THRESHOLD / confidence;  // e.g., 5.0 / 0.85 = 5.9x

    if (deviation > threshold || deviation < 1.0 / threshold) {
        triggerReoptimization(join, newEstimate);
    }
}
```

#### Summary: Static vs Dynamic Estimation

| Input | Static Planning | Dynamic (AEF) |
|-------|-----------------|---------------|
| Build row count | Catalog estimate | Exact (from build) |
| Probe row count | Catalog estimate | Extrapolated from buffer |
| Build NDV | Catalog (often stale) | **Exact** (hash table size) |
| Probe NDV | Catalog (often stale) | **Estimated** (HyperLogLog) |
| Containment | Assumed 1.0 | **Measured** directly |
| Fanout | Assumed 1.0 | **Measured** directly |

**Key insight:** We don't just observe cardinality—we measure the actual components of join selectivity (NDV, containment, fanout) from real data. This gives 10-100x more accurate estimates than catalog-based formulas that rely on assumptions about data distribution.

### 3.4 Confidence-Based Insertion

The optimizer inserts adaptive exchanges **selectively** based on statistics confidence:

```java
class AdaptiveExchangePlanner {

    PlanNode insertAdaptiveExchanges(PlanNode plan) {
        for (ExchangeNode exchange : plan.getExchanges()) {
            ConfidenceLevel confidence = getInputConfidence(exchange);

            if (confidence == LOW) {
                // Uncertain stats - use adaptive exchange
                exchange.setAdaptive(true);
                exchange.setBufferSize(computeBufferSize(exchange));

                if (feedsJoinBuild(exchange)) {
                    // Build side - observe cardinality during build
                    exchange.setReportBuildProgress(true);
                } else if (feedsJoinProbe(exchange)) {
                    // Probe side - can probe hash table for selectivity
                    exchange.setProbeHashTable(true);
                }
            } else {
                // HIGH/FACT confidence - normal streaming
                exchange.setAdaptive(false);
            }
        }
        return plan;
    }
}
```

---

## 4. Detailed Design

### 4.1 Adaptive Exchange States

```java
enum AdaptiveExchangeState {
    STREAMING,          // Normal operation, no buffering
    BUFFERING,          // Collecting initial buffer
    BUFFER_READY,       // Buffer full, computing stats
    PROBING,            // Probing hash table with buffer (if applicable)
    WAITING_DECISION,   // Stats reported, waiting for coordinator
    RELEASING           // Decision received, releasing buffer
}
```

State transitions:

```
STREAMING ------------------------------------------> (normal exchange)
     |
     | (if adaptive)
     v
BUFFERING ---> BUFFER_READY ---> PROBING ---> WAITING_DECISION ---> RELEASING ---> STREAMING
                  |                              ^
                  | (if no hash table to probe)  |
                  +------------------------------+
```

### 4.2 Buffer Statistics

When buffer is ready, compute and report:

```java
class BufferStatistics {
    // Basic cardinality
    long rowCount;
    long bufferSizeBytes;
    double fractionOfEstimate;  // rowCount / estimated total

    // For extrapolation
    long inputRowsProcessed;    // How many rows produced this buffer
    long estimatedTotalInput;   // Total expected input rows
    long projectedOutputRows;   // Extrapolated total output

    // Key distribution (for skew detection)
    Map<Object, Long> keyFrequencies;  // Top-K frequent keys
    long distinctKeyCount;
    double skewFactor;          // max_frequency / average_frequency

    // Join probe results (if applicable)
    Optional<JoinProbeStatistics> joinProbe;
}

class JoinProbeStatistics {
    long bufferRowsProbed;
    long matchingRows;
    double selectivity;         // matchingRows / bufferRowsProbed
    long projectedJoinOutput;   // |probe_side| x selectivity
}
```

### 4.3 Build Progress Reporting

Hash build operators report statistics as they build:

```java
class HashBuildOperator {

    void addInput(Page page) {
        rowsProcessed += page.getPositionCount();
        hashTable.addPage(page);

        // Report progress periodically
        if (shouldReportProgress()) {
            double fractionComplete = estimateFractionComplete();
            long projectedTotal = (long) (rowsProcessed / fractionComplete);

            coordinator.reportBuildProgress(
                this.planNodeId,
                rowsProcessed,
                projectedTotal,
                hashTable.getEstimatedMemoryBytes(),
                fractionComplete
            );
        }
    }

    double estimateFractionComplete() {
        // Option A: Use split progress if available
        if (inputSplitProgress.isPresent()) {
            return inputSplitProgress.get();
        }

        // Option B: Use bytes read vs estimated total bytes
        if (estimatedInputBytes > 0) {
            return (double) bytesRead / estimatedInputBytes;
        }

        // Option C: Can't estimate, report raw counts only
        return Double.NaN;
    }
}
```

**Decision thresholds for build progress:**

| Progress | Deviation to trigger |
|----------|---------------------|
| < 10%    | Don't act (noisy)   |
| 10-30%   | > 10x deviation     |
| 30-50%   | > 5x deviation      |
| > 50%    | Record only (too late to reorder) |

### 4.4 Coordinator-Side Decision Logic

```java
class AdaptiveExchangeCoordinator {

    // Track all adaptive exchanges in query
    Map<ExchangeId, BufferStatistics> pendingDecisions;
    Map<PlanNodeId, JoinOutputEstimator> joinEstimators;
    Set<ExchangeId> upstreamHolds;

    void onBuildProgress(PlanNodeId buildId, BuildProgress progress) {
        JoinOutputEstimator estimator = joinEstimators.get(getJoinFor(buildId));
        estimator.updateFromBuildProgress(progress);

        if (estimator.getConfidence() > MIN_CONFIDENCE) {
            checkForReoptimization(estimator);
        }
    }

    void onBufferReady(ExchangeId id, BufferStatistics stats) {
        pendingDecisions.put(id, stats);

        if (stats.joinProbe.isPresent()) {
            JoinOutputEstimator estimator = joinEstimators.get(stats.joinId);
            estimator.updateFromProbeBuffer(stats.joinProbe.get());
            checkForReoptimization(estimator);
        }

        // Check if we have enough information to decide
        if (canMakeDecision()) {
            makeGlobalDecision();
        }
    }

    void checkForReoptimization(JoinOutputEstimator estimator) {
        long newEstimate = estimator.getBestEstimate();
        long originalEstimate = estimator.getOriginalEstimate();
        double deviation = (double) newEstimate / originalEstimate;
        double threshold = BASE_THRESHOLD / estimator.getConfidence();

        if (deviation > threshold || deviation < 1.0 / threshold) {
            triggerReoptimization(estimator.getJoin(), newEstimate);
        }
    }

    void makeGlobalDecision() {
        // Collect actual statistics from all sources
        ActualStatistics actual = collectActualStats(pendingDecisions, joinEstimators);

        // Compare to original estimates
        DeviationAnalysis deviation = analyzeDeviation(
            originalPlan.getEstimates(),
            actual
        );

        if (deviation.isSignificant()) {
            // Reoptimize with actual statistics
            PlanNode newPlan = reoptimize(originalPlan, actual);

            // Determine which parts of plan changed
            PlanDiff diff = computeDiff(originalPlan, newPlan);

            // Send decisions to workers
            for (ExchangeId id : pendingDecisions.keySet()) {
                if (diff.affectsExchange(id)) {
                    sendDecision(id, Decision.reoptimize(diff.getNewPlanFor(id)));
                } else {
                    sendDecision(id, Decision.continueAsPlanned());
                }
            }
        } else {
            // Original plan is fine, release all buffers
            for (ExchangeId id : pendingDecisions.keySet()) {
                sendDecision(id, Decision.continueAsPlanned());
            }
        }

        // Signal upstream holds to release
        releaseUpstreamHolds();
    }
}
```

### 4.5 Hold Signal Protocol

```java
// Worker-side: Adaptive exchange signals upstream to hold
class AdaptiveExchangeOperator {

    void signalUpstreamHold() {
        // Find all upstream exchanges
        Set<ExchangeId> upstreamExchanges = findUpstreamExchanges();

        for (ExchangeId upstream : upstreamExchanges) {
            // Send hold signal via existing task communication
            taskContext.sendHoldSignal(upstream, this.exchangeId);
        }

        // Report to coordinator
        coordinator.reportHoldInitiated(this.exchangeId, upstreamExchanges);
    }

    void onHoldSignalReceived(ExchangeId fromDownstream) {
        // Stop emitting, but continue buffering input
        holdForDownstream.add(fromDownstream);

        // Cascade hold upstream
        if (!alreadyHolding) {
            signalUpstreamHold();
        }
    }

    void onReleaseSignal() {
        holdForDownstream.clear();

        // Resume emitting buffered data
        releaseBuffer();

        // Signal upstream to release
        signalUpstreamRelease();
    }
}
```

### 4.6 Reoptimization Scope

With cascading holds, the coordinator can reoptimize at different scopes:

**Scope 1: Downstream only (no cascading holds)**
```
Exchange buffers --> reoptimize downstream of this exchange only
Similar to our previous output gating design
```

**Scope 2: Subtree (hold immediate upstream)**
```
Exchange buffers --> hold upstream exchanges feeding this subtree
Can reoptimize the entire subtree
```

**Scope 3: Global (cascade to all upstream)**
```
Exchange buffers --> cascade holds to all upstream exchanges
Can reoptimize entire query plan
```

Configuration:

```properties
# Reoptimization scope
adaptive_exchange_reoptimization_scope=SUBTREE  # or DOWNSTREAM_ONLY, GLOBAL

# Hold cascading depth (-1 = unlimited)
adaptive_exchange_hold_cascade_depth=2
```

---

## 5. Execution Flow Example

### 5.1 Query

```sql
SELECT *
FROM fact f
JOIN dim1 d1 ON f.d1_key = d1.key
JOIN dim2 d2 ON f.d2_key = d2.key
JOIN dim3 d3 ON f.d3_key = d3.key
WHERE f.region = 'US'
  AND d1.category = 'Electronics'
```

### 5.2 Original Plan (with Adaptive Exchanges)

```
fact scan ---> [AE-fact] ---+
                            +--> Join1 ---> [AE-J1] ---+
dim1 scan ---> [AE-dim1] ---+                          |
                                                       +--> Join2 ---> [AE-J2] ---+
dim2 scan ---> [AE-dim2] --------------------------+---+                          |
                                                                                  +--> Join3 ---> Output
dim3 scan ---> [AE-dim3] -----------------------------------------------------+---+

Confidence levels:
  fact scan: LOW (filter selectivity unknown)
  dim1 scan: HIGH (small table, good stats)
  dim2 scan: HIGH (small table, good stats)
  dim3 scan: HIGH (small table, good stats)
  Join1 output: LOW (join selectivity unknown)
  Join2 output: LOW
  Join3 output: LOW
```

### 5.3 Execution Timeline

**T=0: Execution starts**

```
All scans begin producing rows
Hash builds start, reporting progress
Adaptive exchanges start buffering
```

**T=50ms: Build progress reports arrive**

```
dim1 Hash Build: 20% complete, 2K rows so far, projecting 10K total
  - Expected: 10K --> No deviation, continue

dim2 Hash Build: 15% complete, 15K rows so far, projecting 100K total
  - Expected: 100K --> No deviation, continue
```

**T=100ms: Scan exchanges buffer full**

```
AE-fact: 100K rows buffered
  - Projected |fact after filter| ~ 50M (was estimated 500M!)
  - Signals HOLD upstream (none)
  - Reports to coordinator

AE-dim1: 10K rows (complete - small table)
  - |dim1| = 10K (exact)
  - Reports to coordinator

AE-dim2, AE-dim3: similarly complete
```

**T=110ms: Coordinator receives all scan statistics**

```
Actual:
  |fact| ~ 50M (10x smaller than estimated)
  |dim1| = 10K
  |dim2| = 100K
  |dim3| = 1M

Deviation: fact is 10x smaller, significant!

Decision: Release scan exchanges, but watch Join1 output carefully
          Original plan might still be okay, but join selectivity matters
```

**T=120ms: Scan exchanges release, Join1 build completes**

```
dim1 --> Hash Build complete (10K rows)
fact buffer releases --> starts probing
```

**T=200ms: AE-J1 (Join1 output) buffers and probes**

```
AE-J1 buffers first 100K rows of probe input
Probes through dim1 hash table (already complete)

Observed:
  100K fact rows probed --> 1K matches
  Selectivity = 0.01 (was expected 0.5, 50x off!)
  Projected |fact JOIN dim1| ~ 50M x 0.01 = 500K rows

This changes everything!
Signals HOLD upstream, reports to coordinator
```

**T=210ms: Coordinator reoptimizes**

```
JoinOutputEstimator for Join1:
  - Build progress signal: 10K rows (as expected)
  - Probe buffer signal: selectivity = 0.01 (50x off!)
  - Best estimate: 500K rows (was 250M)
  - Confidence: HIGH (direct measurement)
  - Deviation: 500x --> triggers reoptimization

With only 500K intermediate rows:
  - Can broadcast to all remaining dimension joins
  - No need for partitioned joins
  - Completely different plan!

New plan:
  (fact JOIN dim1) broadcast ---> Join with dim2
                             ---> Join with dim3
                             (pipeline, no shuffles!)
```

**T=215ms: Apply new plan**

```
AE-J1 releases buffer
Downstream fragments receive new plan
Broadcast join instead of partitioned
Massive speedup!
```

**T=400ms: Query completes**

```
Final result returned
Actual execution benefited from:
  - Early detection of 10x smaller fact table
  - Accurate join selectivity measurement via buffer probe
  - Dynamic switch to broadcast join
  - Partition coalescing based on actual sizes
```

---

## 6. Optimizations Enabled

The Adaptive Exchange Framework enables all the optimizations Spark AQE provides, plus more:

### 6.1 Dynamic Join Strategy Switching

```
Original: Partitioned join (shuffle both sides)
Observed: Build side is small (< broadcast threshold)
Adapted: Switch to broadcast join

Implementation:
  - Build progress or AE on build side observes small cardinality
  - Coordinator decides to broadcast
  - Build side buffer replicated to all workers
  - Probe side continues without shuffle
```

### 6.2 Partition Coalescing

```
Original: 200 partitions
Observed: Most partitions have < 1MB data
Adapted: Coalesce to 20 partitions

Implementation:
  - AE observes key distribution in buffer
  - Coordinator computes optimal partition count
  - Downstream operators use coalesced partitioning
```

### 6.3 Skew Handling

```
Original: Uniform partitioning
Observed: Key "BigCorp" has 50% of data
Adapted: Split BigCorp partition, replicate other side

Implementation:
  - AE detects skew from key frequencies in buffer
  - Coordinator identifies hot keys
  - Skewed partitions split into sub-partitions
  - Join build side replicated for hot keys
```

### 6.4 Join Reordering

```
Original: fact JOIN dim1 JOIN dim2 JOIN dim3
Observed: dim3 join is highly selective (via build progress or buffer probe)
Adapted: fact JOIN dim3 JOIN dim1 JOIN dim2

Implementation:
  - JoinOutputEstimator detects selectivity deviation
  - Coordinator reoptimizes join order
  - Upstream exchanges held until decision
  - New plan uses different join order
```

### 6.5 Parallelism Adjustment

```
Original: 100 tasks per stage
Observed: Data is 10x smaller than estimated
Adapted: 10 tasks per stage

Implementation:
  - AE observes actual cardinalities
  - Coordinator adjusts task count for downstream stages
  - Fewer, larger tasks reduce scheduling overhead
```

---

## 7. Configuration

### 7.1 Session Properties

```properties
# Enable adaptive exchanges
adaptive_exchange_enabled=true

# Buffer size (rows)
adaptive_exchange_buffer_rows=100000

# Buffer size (bytes) - alternative limit
adaptive_exchange_buffer_bytes=100MB

# Maximum total buffer memory per query
adaptive_exchange_max_buffer_memory=1GB

# Timeout waiting for coordinator decision
adaptive_exchange_decision_timeout=5s

# Reoptimization scope
adaptive_exchange_reoptimization_scope=SUBTREE

# Hold cascade depth (-1 = unlimited)
adaptive_exchange_hold_cascade_depth=-1

# Deviation threshold to trigger reoptimization
adaptive_exchange_deviation_threshold=5.0

# Minimum confidence to skip adaptive exchange
adaptive_exchange_min_confidence=HIGH

# Enable hash table probing for join selectivity
adaptive_exchange_probe_join_selectivity=true

# Enable build progress reporting
adaptive_exchange_build_progress_enabled=true

# Build progress reporting interval (rows)
adaptive_exchange_build_progress_interval=10000
```

### 7.2 Coordinator Properties

```properties
# Maximum concurrent adaptive decisions
adaptive_exchange_max_concurrent_decisions=10

# Reoptimization timeout
adaptive_exchange_reoptimization_timeout=1s

# Enable global (full query) reoptimization
adaptive_exchange_global_reoptimization_enabled=true
```

### 7.3 Memory Management and Spilling

Adaptive exchange buffers can grow large when upstream stages produce more data than expected. The framework uses Velox's spilling infrastructure to handle memory pressure.

**Current Velox Spilling Status:**

The Velox spilling framework (`velox/exec/Spiller.h`) supports:
- Hash Aggregation, Order By, Hash Join, TableWriter, RowNumber, TopNRowNumber, Window

The Exchange operator does **not** currently support spilling in core Velox. For adaptive exchanges, we will extend the operator to integrate with the spilling framework.

**Adaptive Exchange Spilling Design:**

```cpp
class AdaptiveExchangeOperator : public Exchange {
 public:
  // Memory reclaim callback for memory arbitration
  uint64_t reclaim(uint64_t targetBytes, MemoryReclaimer::Stats& stats) override {
    // Spill buffered pages to disk
    return spiller_->spill(targetBytes);
  }

 private:
  // Buffer for statistics collection
  std::vector<RowVectorPtr> buffer_;

  // Spiller for memory pressure handling
  std::unique_ptr<Spiller> spiller_;

  // Row container backing the spiller
  std::unique_ptr<RowContainer> spillContainer_;
};
```

**Spilling Behavior:**

| State | Memory Pressure Response |
|-------|-------------------------|
| BUFFERING | Spill oldest pages from buffer to disk |
| STATS_READY | Spill entire buffer if needed (stats already reported) |
| STREAMING | Normal Exchange behavior (no buffering) |

**Configuration:**

```properties
# Enable spilling for adaptive exchange buffers
adaptive_exchange_spill_enabled=true

# Spill compression codec (ZSTD, LZ4, SNAPPY, NONE)
adaptive_exchange_spill_compression_codec=ZSTD

# Maximum spill file size
adaptive_exchange_max_spill_file_size=1GB

# Spill directory (uses Velox spill path by default)
adaptive_exchange_spill_path=/tmp/presto/spill
```

**Integration with Memory Arbitration:**

Velox's memory arbitration system will automatically trigger spilling when:
1. Query memory usage exceeds limits
2. System-wide memory pressure is detected
3. Memory reservation fails

The adaptive exchange operator registers with the memory arbitration framework via `MemoryReclaimer`, allowing graceful degradation under memory pressure while preserving correctness.

---

## 8. Comparison with Previous Designs

### 8.1 Relationship to Adaptive Phased Execution

The Adaptive Phased Execution design (ADAPTIVE_PHASED_EXECUTION_DESIGN.md) proposed:
- Phase boundaries at hash build completion
- Variant selection based on observed statistics
- Output gating with deviation detection

**AEF subsumes and extends this:**
- Adaptive exchanges are more general than phase boundaries
- Buffer probing gives join selectivity, not just build cardinality
- Build progress reporting catches errors earlier
- Cascading holds enable broader reoptimization scope
- Same output gating concept, but at exchange level

### 8.2 Relationship to SPJ Design

The SPJ design (SPJ_ADAPTIVE_EXECUTION_DESIGN.md) proposed:
- Lifespan-based execution for partitioned tables
- Pilot lifespans to observe statistics
- Reoptimization between lifespan batches

**AEF complements SPJ:**
- AEF works for non-partitioned tables
- SPJ provides finer-grained adaptation within partitions
- Can combine: AEF for initial optimization, SPJ for per-partition adaptation

### 8.3 Relationship to Axiom

Axiom's approach:
- Separate pilot queries during optimization
- Bottom-up DP enumeration of all join orders
- Sampling-based statistics

**AEF vs Axiom tradeoffs:**

| Aspect | Axiom | AEF |
|--------|-------|-----|
| When statistics gathered | Optimization time | Execution time |
| Statistics source | Sampled pilot queries | Actual execution buffers + build progress |
| Join enumeration | Exhaustive (all orders) | Greedy (current + remainder) |
| Overhead for ad-hoc | Pilot query latency | Buffer fill latency |
| Reoptimization scope | Full plan | Configurable (downstream to global) |
| Bushy plans | Yes | Limited (depends on cascade depth) |
| Catches errors early | No (pilots run once) | Yes (build progress monitoring) |

### 8.4 Building on Existing Infrastructure

Presto already has runtime reoptimization infrastructure for materialized exchanges. AEF builds on this foundation rather than creating parallel systems.

**Existing Components (in `SqlQueryScheduler`):**

| Component | Current Use | AEF Use |
|-----------|-------------|---------|
| `FragmentStatsProvider` | Stores stats from completed materialized exchanges | Stores stats from `TaskOutput` |
| `runtimePlanOptimizers` | List of optimizers to apply at runtime | Same, with additional rules |
| `performRuntimeOptimizations()` | Applies optimizers to plan fragments | Same method, no changes |
| `updateStageExecutions()` | Rebuilds stage executions after plan change | Same method, no changes |
| `tryCostBasedOptimize()` | Entry point for runtime optimization | New trigger, same flow |

**Current Trigger (Materialized Exchanges):**

```java
// SqlQueryScheduler.java - current flow
private boolean isReadyForExecution(StreamingPlanSection section) {
    for (StreamingPlanSection child : section.getChildren()) {
        if (rootStageExecution.getState() != FINISHED) {
            return false;  // Wait for materialized exchange to complete
        }
    }
    return true;
}

// When ready, optimize and execute
sectionsReadyForExecution()
    .filter(this::isReadyForExecution)
    .map(this::tryCostBasedOptimize)  // Apply runtimePlanOptimizers
    ...
```

**New Trigger (Streaming with TaskOutput):**

```java
// New flow for streaming exchanges
void onTaskOutputReceived(TaskId taskId, TaskOutput output) {
    if (output instanceof StatsOutput) {
        StatsOutput stats = (StatsOutput) output;

        // Feed stats into existing infrastructure
        fragmentStatsProvider.putStats(
            queryId,
            stats.getPlanNodeId(),
            convertToStatsEstimate(stats));

        // Check if we should reoptimize
        if (shouldTriggerReoptimization(stats)) {
            // Use existing reoptimization flow
            StreamingPlanSection section = getSectionFor(stats.getPlanNodeId());
            tryCostBasedOptimize(section);
        }
    }
}
```

**Key Insight: Same Reoptimization, Different Trigger**

The reoptimization logic (`performRuntimeOptimizations`, `updateStageExecutions`) doesn't care whether stats came from:
- A completed materialized exchange (current)
- A `TaskOutput` from an adaptive exchange (AEF)

We just need to:
1. Feed `TaskOutput` stats into `FragmentStatsProvider`
2. Provide a new trigger mechanism (stats received vs. section finished)
3. Add hold/release coordination for streaming

**Current Runtime Optimizer:**

```java
// PlanOptimizers.java - only one rule today
runtimeBuilder.add(new IterativeOptimizer(
    metadata, ruleStats, statsCalculator, costCalculator,
    ImmutableList.of(),
    ImmutableSet.of(new RuntimeReorderJoinSides(...))));  // Just join side swap
```

`RuntimeReorderJoinSides` compares output sizes and swaps join sides if beneficial. This works immediately with AEF once we feed stats into `FragmentStatsProvider`.

### 8.5 Adaptive Exchanges as Section Boundaries

A key architectural insight simplifies the entire plan propagation problem: **treat adaptive exchanges as section boundaries**, just like materialized exchanges.

#### 8.5.1 The Section Model

Presto already has the concept of **Sections** (see `StreamingPlanSection`):

```java
// Current section extraction - splits at materialized exchanges
public static StreamingPlanSection extractStreamingSections(SubPlan subPlan) {
    // Children not in streamingFragmentIds become separate sections
    for (SubPlan child : subPlan.getChildren()) {
        if (streamingFragmentIds.contains(child.getFragment().getId())) {
            streamingSources.add(...);  // Same section, streaming
        } else {
            materializedExchangeChildren.add(child);  // Different section
        }
    }
}

// Section scheduling - waits for child sections before starting
private boolean isReadyForExecution(StreamingPlanSection section) {
    for (StreamingPlanSection child : section.getChildren()) {
        if (rootStageExecution.getState() != FINISHED) {
            return false;  // Child section not complete
        }
    }
    return true;  // All children done, can schedule this section
}
```

**Key insight**: Materialized exchange sections work seamlessly with reoptimization because `tryCostBasedOptimize()` runs BEFORE scheduling. No running tasks, no complexity.

#### 8.5.2 Adaptive Exchanges as Section Boundaries

Instead of complex hold/release protocols with running tasks, we treat adaptive exchanges like materialized exchanges for scheduling purposes:

```java
// Extended Scope enum
public enum Scope {
    LOCAL(false),
    REMOTE_STREAMING(true),
    REMOTE_MATERIALIZED(true),
    REMOTE_ADAPTIVE(true),     // NEW: creates section boundary like materialized
}
```

**Modified section extraction:**
```java
// extractStreamingSections treats REMOTE_ADAPTIVE like REMOTE_MATERIALIZED
for (SubPlan child : subPlan.getChildren()) {
    ExchangeNode.Scope scope = getExchangeScope(child);
    if (scope == REMOTE_STREAMING) {
        streamingSources.add(...);  // Same section
    } else {
        // REMOTE_MATERIALIZED or REMOTE_ADAPTIVE -> separate section
        sectionBoundaryChildren.add(child);
    }
}
```

**Modified readiness check:**
```java
private boolean isReadyForExecution(StreamingPlanSection section) {
    for (StreamingPlanSection child : section.getChildren()) {
        if (isAdaptiveSection(child)) {
            // Adaptive: ready when buffer filled and stats collected
            if (!adaptiveStatsReady(child)) {
                return false;
            }
        } else {
            // Materialized: ready when fully finished
            if (rootStageExecution.getState() != FINISHED) {
                return false;
            }
        }
    }
    return true;
}
```

#### 8.5.3 Why This Simplifies Everything

| Aspect | Hold/Release Approach | Section Boundary Approach |
|--------|----------------------|--------------------------|
| Running tasks to cancel | Yes, complex | No, section not started |
| Shuffle link rewiring | Yes, while running | No, fresh start |
| Data consistency | Complex protocol | Trivial (data buffered) |
| Code changes | Significant | Minimal |
| Reuse of existing infra | Partial | Complete |

**The section boundary approach means:**

1. **No running tasks in downstream section** - it hasn't started yet
2. **`tryCostBasedOptimize()` works unchanged** - runs before scheduling
3. **`updateStageExecutions()` works unchanged** - creates fresh executions
4. **Shuffle links established normally** - via existing `StageLinkage`

#### 8.5.4 Execution Flow

```
Timeline with Section Boundaries:

1. [Section 1 executes]
   - Source stages run, produce data
   - Adaptive exchange buffers first N rows
   - Stats reported via TaskOutput

2. [Section 1 signals STATS_READY]
   - Not FINISHED (still has buffered + incoming data)
   - But stats are available for optimization

3. [Coordinator checks isReadyForExecution(Section 2)]
   - Child section (Section 1) is STATS_READY
   - Returns true for adaptive sections

4. [tryCostBasedOptimize(Section 2)]
   - Existing method, no changes
   - Uses stats from FragmentStatsProvider
   - May swap join sides, change distribution, etc.

5. [Schedule Section 2]
   - Fresh stage executions created
   - Shuffle links wired to Section 1 tasks
   - Section 1 releases buffer, streams remaining data
```

#### 8.5.5 Comparison with Materialized Exchanges

| Aspect | Materialized | Adaptive |
|--------|-------------|----------|
| Section boundary | Yes | Yes |
| Wait condition | FINISHED | STATS_READY |
| Data storage | Full materialization to temp table | Buffer first N rows in memory |
| Memory usage | Bounded by temp table | Bounded by buffer size config |
| Latency | Wait for all data | Wait for buffer fill only |
| Streaming preserved | No (stage-based) | Yes (after buffer) |

**Key difference**: Materialized exchanges wait for ALL data and write to storage. Adaptive exchanges only buffer enough for statistics, then stream.

#### 8.5.6 Plan Propagation is Trivial

Because the downstream section hasn't started:

```java
// After optimization, same flow as materialized exchanges
StreamingPlanSection optimizedSection = tryCostBasedOptimize(section);

if (planChanged) {
    // Update plan (coordinator view)
    updatePlan(oldToNewFragment);

    // Create stage executions with new fragments
    updateStageExecutions(section, oldToNewFragment);
}

// Schedule normally - shuffle links established via StageLinkage
scheduleSection(optimizedSection);
```

No special handling for:
- Cancelling running tasks (none exist)
- Rewiring shuffle links (fresh setup)
- Draining in-flight data (buffered at exchange)
- Output buffer management (normal flow)

#### 8.5.7 Adaptive Section States

```java
enum AdaptiveSectionState {
    EXECUTING,      // Section running, buffer filling
    STATS_READY,    // Buffer full, stats reported, awaiting downstream
    STREAMING,      // Downstream scheduled, releasing buffer + streaming
    FINISHED        // All data sent
}
```

The coordinator tracks this state and uses it in `isReadyForExecution()`:

```java
boolean adaptiveStatsReady(StreamingPlanSection section) {
    return getAdaptiveSectionState(section) == STATS_READY
        || getAdaptiveSectionState(section) == STREAMING
        || getAdaptiveSectionState(section) == FINISHED;
}
```

---

## 9. Implementation Roadmap

AEF takes an incremental approach, starting with the existing infrastructure and progressively adding capabilities.

### Phase 0: Wire TaskOutput to Existing Infrastructure

**Goal**: Get end-to-end reoptimization working with existing `RuntimeReorderJoinSides`.

**Changes:**

1. **TaskOutput stats types** (builds on DPP RFC infrastructure):
```java
@JsonSubTypes({
    @JsonSubTypes.Type(value = DynamicFilterOutput.class, name = "dynamicFilter"),
    @JsonSubTypes.Type(value = BuildStatsOutput.class, name = "buildStats"),
    @JsonSubTypes.Type(value = ExchangeStatsOutput.class, name = "exchangeStats")})
public interface TaskOutput { ... }

public class BuildStatsOutput implements TaskOutput {
    private final PlanNodeId planNodeId;
    private final long rowCount;
    private final long outputSizeBytes;
    private final long distinctKeyCount;
    // ...
}
```

2. **Feed stats to FragmentStatsProvider**:
```java
// In TaskOutputDispatcher
void dispatch(TaskOutput output) {
    if (output instanceof BuildStatsOutput) {
        BuildStatsOutput stats = (BuildStatsOutput) output;
        PlanNodeStatsEstimate estimate = PlanNodeStatsEstimate.builder()
            .setOutputRowCount(stats.getRowCount())
            .setTotalSize(stats.getOutputSizeBytes())
            .build();
        fragmentStatsProvider.putStats(queryId, fragmentId, estimate);
    }
}
```

3. **Trigger reoptimization** when build completes (simpler than buffer-based):
```java
void onBuildComplete(PlanNodeId buildId) {
    // Stats already in FragmentStatsProvider
    StreamingPlanSection section = getSectionContaining(buildId);
    section = tryCostBasedOptimize(section);  // Existing method!

    if (planChanged) {
        updateStageExecutions(section, oldToNewFragment);  // Existing method!
    }
}
```

**What Works After Phase 0:**
- Join side swapping based on actual build size
- Uses 100% existing reoptimization code path
- No hold/release needed yet (decision at build completion)

### Phase 1: Add Buffer-Based Statistics

**Goal**: Collect statistics from exchange buffers for earlier/better decisions.

**Changes:**

1. **Adaptive exchange buffer** (new operator):
```java
class AdaptiveExchangeOperator {
    private final int bufferSize;
    private List<Page> buffer = new ArrayList<>();
    private BufferStatistics stats;

    void addInput(Page page) {
        if (buffer.size() < bufferSize) {
            buffer.add(page);
            updateStats(page);
        }
        if (bufferFull() && !statsReported) {
            reportStats();  // Send TaskOutput
        }
        // Continue flowing data (no hold yet)
    }
}
```

2. **ExchangeStatsOutput**:
```java
public class ExchangeStatsOutput implements TaskOutput {
    private final PlanNodeId planNodeId;
    private final long rowCount;
    private final long outputSizeBytes;
    private final Map<String, Long> keyFrequencies;  // For skew detection
}
```

3. **Deviation detection**:
```java
void onExchangeStats(ExchangeStatsOutput stats) {
    PlanNodeStatsEstimate original = getOriginalEstimate(stats.getPlanNodeId());
    PlanNodeStatsEstimate actual = convertToEstimate(stats);

    double deviation = actual.getOutputRowCount() / original.getOutputRowCount();
    if (deviation > THRESHOLD || deviation < 1.0/THRESHOLD) {
        fragmentStatsProvider.putStats(queryId, fragmentId, actual);
        tryCostBasedOptimize(section);
    }
}
```

**What Works After Phase 1:**
- Earlier deviation detection (don't wait for full build)
- Join side swapping based on exchange buffer stats
- Stats feed into section readiness check (preparation for Phase 2)

### Phase 2: Section Boundary Integration

**Goal**: Integrate adaptive exchanges as section boundaries for clean reoptimization.

**Changes:**

1. **Add REMOTE_ADAPTIVE scope**:
```java
// ExchangeNode.java
public enum Scope {
    LOCAL(false),
    REMOTE_STREAMING(true),
    REMOTE_MATERIALIZED(true),
    REMOTE_ADAPTIVE(true),     // NEW
}
```

2. **Modify section extraction**:
```java
// StreamingPlanSection.java - treat REMOTE_ADAPTIVE as section boundary
private static StreamingSubPlan extractStreamingSection(SubPlan subPlan, ...) {
    for (SubPlan child : subPlan.getChildren()) {
        ExchangeNode.Scope scope = getExchangeScope(subPlan.getFragment(), child.getFragment());
        if (scope == REMOTE_STREAMING) {
            streamingSources.add(extractStreamingSection(child, ...));
        } else {
            // REMOTE_MATERIALIZED or REMOTE_ADAPTIVE
            sectionBoundaryChildren.add(child);
        }
    }
}
```

3. **Modify readiness check**:
```java
// SqlQueryScheduler.java
private boolean isReadyForExecution(StreamingPlanSection section) {
    for (StreamingPlanSection child : section.getChildren()) {
        if (isAdaptiveSection(child)) {
            // Adaptive: ready when stats collected (STATS_READY state)
            if (!adaptiveStatsReady(child)) {
                return false;
            }
        } else {
            // Materialized: ready when FINISHED
            if (getStageExecution(child).getState() != FINISHED) {
                return false;
            }
        }
    }
    return true;
}
```

4. **Add adaptive section state tracking**:
```java
// New: track adaptive section state in SqlQueryScheduler
private final Map<PlanFragmentId, AdaptiveSectionState> adaptiveSectionStates = new ConcurrentHashMap<>();

enum AdaptiveSectionState {
    EXECUTING,      // Buffer filling
    STATS_READY,    // Stats reported, waiting for downstream
    STREAMING,      // Downstream started, releasing buffer
    FINISHED
}

void onAdaptiveStatsReported(PlanFragmentId fragmentId) {
    adaptiveSectionStates.put(fragmentId, STATS_READY);
    // Check if any downstream sections can now start
    startScheduling();
}
```

**What Works After Phase 2:**
- Adaptive exchanges create natural synchronization points
- Downstream sections wait for stats before scheduling
- `tryCostBasedOptimize()` runs before any downstream tasks exist
- No running tasks to cancel, no shuffle links to rewire
- Existing infrastructure (StageLinkage, updateStageExecutions) works unchanged

### Phase 3: Additional Runtime Optimizer Rules

**Goal**: Add more rules to `runtimePlanOptimizers` for broader adaptation.

**New Rules:**

1. **RuntimeSwitchJoinDistribution**:
```java
// Switch PARTITIONED ↔ REPLICATED based on actual sizes
public class RuntimeSwitchJoinDistribution implements Rule<JoinNode> {
    public Result apply(JoinNode join, Captures captures, Context context) {
        double buildSize = context.getStatsProvider().getStats(join.getRight())
            .getOutputSizeInBytes();

        if (join.getDistributionType() == PARTITIONED &&
            buildSize < BROADCAST_THRESHOLD) {
            // Switch to broadcast - smaller build fits in memory
            return Result.ofPlanNode(withDistribution(join, REPLICATED));
        }

        if (join.getDistributionType() == REPLICATED &&
            buildSize > BROADCAST_THRESHOLD * 2) {
            // Switch to partitioned - build too large for broadcast
            return Result.ofPlanNode(withDistribution(join, PARTITIONED));
        }

        return Result.empty();
    }
}
```

2. **RuntimeAdjustParallelism**:
```java
// Adjust task count based on actual data volume
public class RuntimeAdjustParallelism implements Rule<ExchangeNode> {
    public Result apply(ExchangeNode exchange, Captures captures, Context context) {
        double actualSize = context.getStatsProvider().getStats(exchange.getSources().get(0))
            .getOutputSizeInBytes();
        double originalSize = originalEstimates.get(exchange.getId());

        if (actualSize < originalSize / 10) {
            // Much less data than expected - reduce parallelism
            return Result.ofPlanNode(withReducedPartitions(exchange));
        }

        return Result.empty();
    }
}
```

3. **Register in PlanOptimizers**:
```java
// PlanOptimizers.java
runtimeBuilder.add(new IterativeOptimizer(
    metadata, ruleStats, statsCalculator, costCalculator,
    ImmutableList.of(),
    ImmutableSet.of(
        new RuntimeReorderJoinSides(...),           // Existing
        new RuntimeSwitchJoinDistribution(...),     // New
        new RuntimeAdjustParallelism(...)           // New
    )));
```

**What Works After Phase 3:**
- Join distribution type adaptation
- Parallelism adjustment
- Multiple optimizations can fire together

### Phase 4: Join Reordering

**Goal**: Reorder joins across the plan based on actual statistics.

**New Rule:**

```java
// More complex: reorder joins in a join graph
public class RuntimeReorderJoins implements Rule<JoinNode> {
    public Result apply(JoinNode join, Captures captures, Context context) {
        // Build join graph from current position
        JoinGraph graph = JoinGraph.buildFrom(join, context.getLookup());

        // Get actual stats for all tables in graph
        Map<PlanNodeId, PlanNodeStatsEstimate> actualStats =
            graph.getNodes().stream()
                .collect(toMap(PlanNode::getId,
                    node -> context.getStatsProvider().getStats(node)));

        // Greedy reordering with actual stats
        List<PlanNode> newOrder = greedyJoinOrder(graph, actualStats);

        if (!newOrder.equals(graph.getOriginalOrder())) {
            return Result.ofPlanNode(buildJoinTree(newOrder, graph.getEdges()));
        }

        return Result.empty();
    }
}
```

**What Works After Phase 4:**
- Full join reordering based on actual cardinalities
- Can fix severe mis-estimates in complex queries

### Phase 5: Advanced Features

**Goal**: Skew handling, partition coalescing, memory optimization.

1. **Skew detection and handling**
2. **Partition coalescing**
3. **Dynamic memory allocation**

---

## 10. Success Metrics

### 10.1 Correctness
- Query results identical to non-adaptive execution
- No data loss or duplication from buffering
- Consistent behavior under concurrent queries

### 10.2 Performance (Positive Cases)
- Queries with 10x+ estimation errors: 2-5x improvement
- Skewed data: significant improvement from skew handling
- Variable workloads: automatic adaptation without tuning

### 10.3 Performance (Overhead)
- Queries with accurate estimates: <5% overhead
- Buffer fill latency: <200ms per adaptive exchange
- Decision latency: <100ms per decision point
- Memory overhead: bounded by configuration

### 10.4 Observability
- Clear visibility into adaptive decisions
- Statistics accuracy tracking
- Reoptimization frequency metrics

---

## 11. Open Questions

### 11.1 Buffer Representativeness

**Question**: First N rows may not be representative of full data.

**Options**:
1. **First-N sampling**: Simple, but biased if data is sorted
2. **Reservoir sampling**: Unbiased, but requires seeing more data
3. **Stratified sampling**: Sample from each partition, more representative
4. **Progressive refinement**: Start with first-N, refine if deviation detected later

**Recommendation**: Start with first-N, add reservoir sampling as enhancement.

### 11.2 Section Scheduling Deadlocks

**Question**: Could section dependencies cause deadlocks?

**Analysis**: No, because:
- Sections form a DAG (directed acyclic graph)
- A section only waits for its child sections (data dependencies)
- Child sections never wait for parent sections
- Therefore, no circular wait is possible

**Note**: The section-based approach eliminates the hold/release protocol complexity. Sections naturally wait for their inputs to be ready (STATS_READY for adaptive, FINISHED for materialized) without explicit coordination messages.

### 11.3 Partial Reoptimization Complexity

**Question**: How to handle reoptimization when some operators have already executed?

**Resolution (Section-Based Approach)**: This complexity is largely eliminated by treating adaptive exchanges as section boundaries:

1. **Downstream sections haven't started** - When reoptimization occurs, the consuming section is still in PLANNED state
2. **Upstream sections continue unchanged** - Only the plan for the not-yet-scheduled section is modified
3. **No restart needed** - Fresh scheduling of the optimized section

The section boundary approach naturally provides "downstream-only" reoptimization without any special handling. The only consideration is that upstream sections (which have already started) cannot be reoptimized—but their stats are the input to optimization, so this is the correct behavior.

### 11.4 Multi-Query Interaction

**Question**: How do adaptive exchanges interact with concurrent queries?

**Considerations**:
- Buffer memory is shared resource
- Coordinator decisions should be query-isolated
- Statistics from one query shouldn't affect another

**Recommendation**: Per-query buffer pools, isolated decision making.

### 11.5 Integration with Existing Features

**Question**: How does AEF interact with dynamic filtering, history-based stats, etc.?

**Analysis**:
- Dynamic filtering: AEF can observe filter effectiveness, adjust
- History-based stats: AEF validates/updates historical predictions
- Fault-tolerant execution: Buffers provide natural checkpoints

**Recommendation**: Design AEF as complementary to existing features.

### 11.6 Build Progress Accuracy

**Question**: How accurately can we estimate fraction complete during build?

**Analysis**:
- Split-based progress: Good for partitioned tables with many splits
- Byte-based progress: Depends on accurate source size estimates
- Single large file: May not have progress until near completion

**Recommendation**: Use split progress when available, byte progress as fallback, disable early detection when neither available.

---

## 12. Appendix A: Glossary

| Term | Definition |
|------|------------|
| **Adaptive Exchange (AE)** | Exchange operator that buffers, observes statistics, and enables reoptimization |
| **Buffer** | Temporary storage for first N rows of exchange output |
| **Hold Signal** | Message telling upstream exchange to pause emission |
| **Cascading Hold** | Hold signals propagating up the query tree |
| **Buffer Probe** | Probing buffer rows through hash table to measure selectivity |
| **Build Progress** | Statistics reported during hash table construction |
| **JoinOutputEstimator** | Unified component that combines multiple signals to estimate join output |
| **Decision Point** | Moment when coordinator decides whether to reoptimize |
| **Reoptimization Scope** | How much of the query can be changed (downstream, subtree, global) |

## Appendix B: Comparison with Spark AQE

| Feature | Spark AQE | AEF |
|---------|-----------|-----|
| Partition coalescing | Yes | Yes |
| Join strategy switching | Yes | Yes |
| Skew handling | Yes | Yes |
| Join reordering | No | Yes |
| Execution model | Stage-based (blocking) | Streaming (buffer-then-stream) |
| Statistics source | Shuffle file stats | Buffer samples + build progress + hash probes |
| Early error detection | No (waits for stage) | Yes (build progress monitoring) |
| Reoptimization scope | Within stage | Configurable (up to global) |

## Appendix C: Telemetry

Metrics to track:

**Buffer metrics:**
- `adaptive_exchange.buffers_created` - Number of adaptive exchanges used
- `adaptive_exchange.buffer_fill_time_ms` - Time to fill buffer
- `adaptive_exchange.buffer_memory_bytes` - Memory used by buffers

**Build progress metrics:**
- `adaptive_exchange.build_progress_reports` - Number of progress reports received
- `adaptive_exchange.build_deviation_detected` - Build deviations that triggered action
- `adaptive_exchange.build_early_reoptimization` - Reoptimizations triggered by build progress

**Decision metrics:**
- `adaptive_exchange.decisions_total` - Total coordinator decisions
- `adaptive_exchange.decisions_reoptimize` - Decisions that triggered reoptimization
- `adaptive_exchange.decision_latency_ms` - Time for coordinator to decide

**Statistics metrics:**
- `adaptive_exchange.cardinality_deviation_ratio` - Actual vs estimated cardinality
- `adaptive_exchange.selectivity_deviation_ratio` - Actual vs estimated join selectivity
- `adaptive_exchange.skew_factor_detected` - Skew factor in buffered data

**Hold metrics:**
- `adaptive_exchange.hold_signals_sent` - Hold signals issued
- `adaptive_exchange.hold_duration_ms` - Time exchanges spent holding
- `adaptive_exchange.hold_cascade_depth` - Depth of hold cascading

**Performance metrics:**
- `adaptive_exchange.query_speedup_ratio` - Performance gain vs non-adaptive
- `adaptive_exchange.overhead_ms` - Added latency from adaptation

---

*Document Version: 0.2*
*Last Updated: December 2024*