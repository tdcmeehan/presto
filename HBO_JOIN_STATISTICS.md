# HBO Join Statistics: Execution-Based Join Selectivity Learning

## Overview

This document describes a stepping stone project to improve join cardinality estimation by capturing join statistics during query execution and storing them in Presto's History-Based Optimizer (HBO) infrastructure. Future queries with the same table pairs will benefit from learned selectivity without requiring the full Adaptive Exchange Framework (AEF).

**Goal**: After executing a query with joins, subsequent queries joining the same tables should have more accurate cardinality estimates.

**Runtime**: Velox (C++) only. There are no plans to implement this in the Java runtime.

**Key Design Decision**: No Velox or presto-native-execution modifications required. The existing `TaskInfo` protocol already contains `inputPositions`, `outputPositions`, `operatorType`, and `planNodeId` for every operator. The coordinator simply reads this existing data.

---

## 1. Motivation

### 1.1 The Problem

Join cardinality estimation is the primary source of query optimizer errors. The classic formula:

```
|A ⋈ B| ≈ |A| × |B| / max(NDV(A.key), NDV(B.key))
```

Fails because it assumes:
- Uniform key distribution (rarely true)
- Key containment = 1.0 (all probe keys exist in build)
- Fanout = 1.0 (one match per key)

### 1.2 The Opportunity

During join execution, we observe the **actual** selectivity:

```
Actual output rows = probe input rows × probe-to-build fanout
                   = build input rows × build-to-probe fanout
```

If we capture and store this, future queries benefit without additional overhead.

### 1.3 Why This Is a Good Stepping Stone

| Aspect | Full AEF | HBO Join Stats (This Project) |
|--------|----------|-------------------------------|
| Complexity | High (buffering, sections, reopt) | Low (capture and store) |
| First query benefit | Yes (adapts during execution) | No (learns for next time) |
| Second+ query benefit | Yes | Yes |
| Infrastructure changes | Significant | Minimal (extends HBO) |
| Risk | Higher | Lower |

---

## 2. Data Model

### 2.1 Bidirectional Fanout

Following Axiom's model, we store bidirectional fanout for each join edge:

```cpp
struct JoinSelectivityStats {
  // Bidirectional fanout - supports both join orderings from single observation
  double leftToRightFanout;   // output_rows / left_input_rows
  double rightToLeftFanout;   // output_rows / right_input_rows

  // Observation metadata
  int64_t observationTimestamp;  // Unix epoch millis
  int32_t observationCount;      // Number of times observed
  double variance;               // Stability across observations (Welford's algorithm)

  // For cache invalidation (optional)
  int64_t leftTableVersion;
  int64_t rightTableVersion;
};
```

**Why bidirectional?**

```
Query 1: SELECT * FROM orders JOIN customers ON o_custkey = c_custkey
  - orders is probe (left), customers is build (right)
  - Observe: lr_fanout = 0.95 (most orders have a customer)
  - Observe: rl_fanout = 10.5 (each customer has ~10 orders)

Query 2: SELECT * FROM customers JOIN orders ON c_custkey = o_custkey
  - customers is probe (left), orders is build (right)
  - Need: lr_fanout from customer perspective = 10.5
  - Use stored rl_fanout from Query 1 (swap!)
```

### 2.2 Canonical Key Generation

Keys must be deterministic regardless of SQL join order:

```cpp
// Generates a canonical key for any join between two tables
// Returns: (canonical_key, was_swapped)
std::pair<std::string, bool> generateCanonicalJoinKey(
    const std::string& leftTable,
    const std::vector<std::string>& leftKeys,
    const std::string& rightTable,
    const std::vector<std::string>& rightKeys) {

  // 1. Sort join keys alphabetically for determinism
  std::vector<size_t> indices(leftKeys.size());
  std::iota(indices.begin(), indices.end(), 0);
  std::sort(indices.begin(), indices.end(),
    [&](size_t a, size_t b) { return leftKeys[a] < leftKeys[b]; });

  // 2. Build canonical strings: "table key1 key2 ... "
  std::string leftCanonical = leftTable + " ";
  std::string rightCanonical = rightTable + " ";
  for (size_t i : indices) {
    leftCanonical += leftKeys[i] + " ";
    rightCanonical += rightKeys[i] + " ";
  }

  // 3. Lexicographically smaller table first (ensures A-B and B-A map to same key)
  if (leftCanonical < rightCanonical) {
    return {leftCanonical + "  " + rightCanonical, false};
  }
  return {rightCanonical + "  " + leftCanonical, true};
}

// Examples:
// orders JOIN customers ON o_custkey = c_custkey
//   → "customers c_custkey   orders o_custkey " (swapped=true)
//
// customers JOIN orders ON c_custkey = o_custkey
//   → "customers c_custkey   orders o_custkey " (swapped=false)
//
// Both queries map to the SAME key!
```

---

## 3. Implementation: Leveraging Existing Velox Stats

### 3.1 Key Insight: No Velox Modifications Required

Velox already tracks the statistics we need. Every operator, including `HashProbe` and `HashBuild`, has:

```cpp
// In velox/exec/OperatorStats.h (already exists)
struct OperatorStats {
  uint64_t inputPositions = 0;   // Total input rows
  uint64_t outputPositions = 0;  // Total output rows
  std::string operatorType;      // "HashProbe", "HashBuild", etc.
  std::string planNodeId;        // Links operators to plan nodes
  // ... other existing fields ...
};
```

**What we can compute from existing stats:**

| Metric | Source | Notes |
|--------|--------|-------|
| Probe input rows | `HashProbe.inputPositions` | Rows entering probe side |
| Build input rows | `HashBuild.inputPositions` | Rows added to hash table |
| Output rows | `HashProbe.outputPositions` | Join result (0-N per probe row) |
| Probe fanout | `HashProbe.output / HashProbe.input` | |
| Build fanout | `HashProbe.output / HashBuild.input` | |

**Important**: `HashBuild.outputPositions` is always 0 (it builds a hash table, doesn't produce output rows). The join output comes from `HashProbe.outputPositions`.

### 3.2 Architecture: Coordinator-Only Changes

```
┌─────────────────────────────────────────────────────────────────────┐
│                    Velox Task (UNCHANGED)                            │
│  ┌─────────────┐    ┌─────────────┐                                  │
│  │ HashProbe   │    │ HashBuild   │                                  │
│  │             │    │             │                                  │
│  │ Already     │    │ Already     │                                  │
│  │ tracks:     │    │ tracks:     │                                  │
│  │ -inputPos   │    │ -inputPos   │  ← Same planNodeId               │
│  │ -outputPos ←──── join output   │  (outputPos = 0)                 │
│  └─────────────┘    └─────────────┘                                  │
└─────────────────────────────────────────────────────────────────────┘
                                │
                                ▼
┌─────────────────────────────────────────────────────────────────────┐
│                 Presto Worker (C++) - UNCHANGED                      │
│  ┌─────────────────────────────────────────────────────────────────┐│
│  │ PrestoTask                                                       ││
│  │                                                                  ││
│  │ Already sends in TaskInfo.stats.pipelines[*].operatorSummaries:  ││
│  │   - operatorType ("LookupJoinOperator", "HashBuilderOperator")   ││
│  │   - planNodeId                                                   ││
│  │   - inputPositions                                               ││
│  │   - outputPositions (only meaningful for HashProbe)              ││
│  └─────────────────────────────────────────────────────────────────┘│
└─────────────────────────────────────────────────────────────────────┘
                                │
                                ▼
┌─────────────────────────────────────────────────────────────────────┐
│                 Presto Coordinator (Java) - MODIFIED                 │
│  ┌─────────────────────────────────────────────────────────────────┐│
│  │ SqlQueryExecution + JoinStatsCollector                           ││
│  │                                                                  ││
│  │ On final TaskInfo from ALL tasks:                                ││
│  │   1. Extract operator stats from existing TaskInfo               ││
│  │   2. Identify join operators by operatorType                     ││
│  │   3. Correlate HashProbe + HashBuild by planNodeId               ││
│  │   4. Sum across tasks:                                           ││
│  │      - HashProbe.inputPositions  → probe input                   ││
│  │      - HashProbe.outputPositions → join output                   ││
│  │      - HashBuild.inputPositions  → build input                   ││
│  │   5. Compute fanouts from sums                                   ││
│  │   6. Map planNodeId → canonical join key                         ││
│  │   7. Store to HBO                                                ││
│  └─────────────────────────────────────────────────────────────────┘│
└─────────────────────────────────────────────────────────────────────┘
```

**Why fanout must be computed on coordinator**: Workers process different partitions. Summing raw counts preserves the row-weighted average; averaging pre-computed fanouts would not.

### 3.3 Mapping planNodeId to Canonical Join Key

The coordinator has the `PlanFragment` and can map `planNodeId` → canonical join key:

```java
// In SqlQueryExecution or JoinStatsCollector
private Map<String, CanonicalJoinKey> buildPlanNodeToJoinKeyMap(PlanFragment fragment) {
    Map<String, CanonicalJoinKey> result = new HashMap<>();

    fragment.getRoot().accept(new PlanVisitor<Void, Void>() {
        @Override
        public Void visitJoin(JoinNode node, Void context) {
            // Extract table names by walking to TableScan nodes
            Optional<String> leftTable = findBaseTableName(node.getLeft());
            Optional<String> rightTable = findBaseTableName(node.getRight());

            if (leftTable.isPresent() && rightTable.isPresent()) {
                CanonicalJoinKey key = CanonicalJoinKey.create(
                    leftTable.get(),
                    extractColumnNames(node.getCriteria(), true),
                    rightTable.get(),
                    extractColumnNames(node.getCriteria(), false)
                );
                result.put(node.getId().toString(), key);
            }
            return super.visitJoin(node, context);
        }
    }, null);

    return result;
}
```

Since the coordinator has the plan, no worker changes are needed to obtain table/column names.

---

## 4. Coordinator: Extracting Join Stats from Existing TaskInfo

### 4.1 Key Insight: No Protocol Changes Needed

The existing `OperatorStats` in TaskInfo **already contains everything we need**:

```cpp
// In presto_protocol_core.h - ALREADY EXISTS
struct OperatorStats {
  PlanNodeId planNodeId = {};           // Links HashProbe + HashBuild
  String operatorType = {};             // "LookupJoinOperator" or "HashBuilderOperator"
  int64_t inputPositions = {};          // Probe/build input rows
  int64_t outputPositions = {};         // Join output rows

  // Even has join-specific stats already!
  int64_t joinBuildKeyCount = {};       // Build side rows
  int64_t joinProbeKeyCount = {};       // Probe side rows
  int64_t nullJoinBuildKeyCount = {};
  int64_t nullJoinProbeKeyCount = {};
};
```

**No changes to presto-native-execution needed!** The coordinator simply reads existing data.

### 4.2 Handling Broadcast (REPLICATED) Joins

**Critical issue**: For broadcast joins, the build side is replicated to all workers:

```
PARTITIONED join (100 workers):
  - Build: 1M rows total, ~10K per worker → sum = 1M ✓
  - Probe: 100M rows total, 1M per worker → sum = 100M ✓

REPLICATED join (100 workers):
  - Build: 1M rows REPLICATED to each worker → sum = 100M ✗ (actual: 1M)
  - Probe: 100M rows total, 1M per worker → sum = 100M ✓
```

**Solution**: Check distribution type from plan and handle accordingly:

```java
public Map<String, JoinOperatorPair> extractJoinStats(
        List<TaskInfo> taskInfos,
        Map<PlanNodeId, JoinNode> joinNodes) {

    Map<String, JoinOperatorPair> joinPairs = new HashMap<>();

    for (TaskInfo taskInfo : taskInfos) {
        for (PipelineStats pipeline : taskInfo.getStats().getPipelines()) {
            for (OperatorStats op : pipeline.getOperatorSummaries()) {

                if ("LookupJoinOperator".equals(op.getOperatorType())) {
                    JoinOperatorPair pair = joinPairs.computeIfAbsent(
                        op.getPlanNodeId(), k -> new JoinOperatorPair());
                    // Probe side: always sum (partitioned across workers)
                    pair.probeInputRows += op.getInputPositions();
                    pair.outputRows += op.getOutputPositions();
                }

                if ("HashBuilderOperator".equals(op.getOperatorType())) {
                    JoinOperatorPair pair = joinPairs.computeIfAbsent(
                        op.getPlanNodeId(), k -> new JoinOperatorPair());

                    // Check if this is a broadcast join
                    JoinNode joinNode = joinNodes.get(op.getPlanNodeId());
                    boolean isBroadcast = joinNode.getDistributionType()
                        .map(t -> t == JoinDistributionType.REPLICATED)
                        .orElse(false);

                    if (isBroadcast) {
                        // Build side replicated: take MAX (all workers have same data)
                        pair.buildInputRows = Math.max(
                            pair.buildInputRows, op.getInputPositions());
                    } else {
                        // Build side partitioned: sum across workers
                        pair.buildInputRows += op.getInputPositions();
                    }
                }
            }
        }
    }

    return joinPairs;
}
```

**Why MAX for broadcast**: All workers have identical build-side data, so any single worker's count is the true total. Using MAX handles edge cases where some workers might report 0 due to early termination.

### 4.3 Verified: planNodeId Correlation Is Reliable

**Concern**: Do HashBuild and HashProbe share the same planNodeId across stages?

**Answer**: Yes, always. A JoinNode is never split across fragments:

```java
// LocalExecutionPlanner.java - both use node.getId()
HashBuilderOperatorFactory(..., node.getId(), ...);  // line 2436
LookupJoinOperatorFactory(..., node.getId(), ...);   // line 2556
```

Exchanges are inserted as **children** of the JoinNode, not replacements:

```
Fragment 1: TableScan → Exchange (broadcast/partition)
                              ↓
Fragment 2: Exchange → HashBuild ─┐
            TableScan → HashProbe ←┘  ← Same JoinNode.getId() for both
```

| Join Type | Same planNodeId? |
|-----------|------------------|
| REPLICATED (broadcast) | ✅ |
| PARTITIONED (shuffle) | ✅ |
| Nested loop / Cross | ✅ |

### 4.4 Operator Stats in Final TaskInfo

**Good news**: The `summarize` parameter only affects heartbeats. Final TaskInfo **always** includes pipeline stats:

```cpp
// PrestoTask.cpp line 698
includePipelineStats = isFinalState(prestoTaskStatus.state) || !summarize;
//                     ^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^
//                     TRUE for FINISHED/FAILED/ABORTED/CANCELED
```

| TaskInfo Type | Pipeline Stats | Our Action |
|---------------|----------------|------------|
| Heartbeat (during execution) | ❌ Stripped if summarize=true | Ignore |
| Final (on completion) | ✅ Always included | Extract join stats |

**No session property needed!** We extract join stats from the final TaskInfo, which always has operator-level detail. The coordinator just needs to intercept the final TaskInfo before any post-processing discards the pipeline stats.

```java
// In task completion handler
public void onTaskCompleted(TaskInfo finalTaskInfo) {
    // finalTaskInfo.getStats().getPipelines() is populated!
    if (isTrackHboJoinSamplesEnabled()) {
        joinStatsCollector.extractAndStore(finalTaskInfo);
    }
}
```

### 4.5 Heartbeat vs Final TaskInfo

Presto workers send TaskInfo in two contexts:

1. **Heartbeat**: Periodic updates during execution (partial stats)
2. **Final**: When task completes (complete stats)

We only use **final** TaskInfo for HBO updates to ensure complete statistics:

```cpp
// In PrestoTask.cpp

void PrestoTask::updateHeartbeatInfo() {
  auto info = createTaskInfo();

  // For heartbeats, we might send partial join stats for monitoring
  // but mark them as incomplete
  for (auto& jsInfo : info.join_selectivity_stats) {
    jsInfo.is_complete = false;  // Partial observation
  }

  sendHeartbeat(info);
}

void PrestoTask::onTaskComplete() {
  auto info = createTaskInfo();

  // Final stats are complete
  for (auto& jsInfo : info.join_selectivity_stats) {
    jsInfo.is_complete = true;  // Full observation
  }

  sendFinalInfo(info);
}
```

---

## 5. Coordinator: Aggregating and Storing to HBO

### 5.1 Receiving Join Stats from Workers

```java
// In SqlQueryExecution.java or StageStateMachine.java

public void updateTaskInfo(TaskInfo taskInfo) {
    // ... existing task info handling ...

    // NEW: Collect join selectivity stats
    if (taskInfo.isComplete()) {
        for (JoinSelectivityInfo jsInfo : taskInfo.getJoinSelectivityStats()) {
            joinStatsCollector.addObservation(jsInfo);
        }
    }
}
```

### 5.2 Aggregating Across Tasks

Multiple tasks execute the same join (partitioned). We aggregate:

```java
public class JoinStatsCollector {

    // Key: canonical join key
    // Value: aggregated stats across all tasks
    private final Map<String, AggregatedJoinStats> statsMap = new ConcurrentHashMap<>();

    public void addObservation(JoinSelectivityInfo info) {
        statsMap.compute(info.getCanonicalJoinKey(), (key, existing) -> {
            if (existing == null) {
                return new AggregatedJoinStats(info);
            }
            return existing.merge(info);
        });
    }

    public static class AggregatedJoinStats {
        private long totalProbeInputRows = 0;
        private long totalBuildInputRows = 0;
        private long totalOutputRows = 0;
        private boolean keyWasSwapped;

        public AggregatedJoinStats(JoinSelectivityInfo first) {
            this.totalProbeInputRows = first.getProbeInputRows();
            this.totalBuildInputRows = first.getBuildInputRows();
            this.totalOutputRows = first.getOutputRows();
            this.keyWasSwapped = first.isKeyWasSwapped();
        }

        public AggregatedJoinStats merge(JoinSelectivityInfo other) {
            // Sum across partitions
            this.totalProbeInputRows += other.getProbeInputRows();
            this.totalBuildInputRows += other.getBuildInputRows();
            this.totalOutputRows += other.getOutputRows();
            return this;
        }

        public JoinSampleStatistics toJoinSampleStatistics() {
            double lrFanout = totalProbeInputRows > 0
                ? (double) totalOutputRows / totalProbeInputRows
                : 0.0;
            double rlFanout = totalBuildInputRows > 0
                ? (double) totalOutputRows / totalBuildInputRows
                : 0.0;

            // If key was swapped, fanouts are from swapped perspective
            // Store in canonical order (swap back)
            if (keyWasSwapped) {
                return new JoinSampleStatistics(rlFanout, lrFanout, ...);
            }
            return new JoinSampleStatistics(lrFanout, rlFanout, ...);
        }
    }
}
```

### 5.3 Storing to HBO on Query Completion

```java
// In SqlQueryExecution.java

@Override
public void queryComplete() {
    // ... existing completion logic ...

    // NEW: Store join stats to HBO
    if (isTrackHboJoinSamplesEnabled(session)) {
        storeJoinStatsToHbo();
    }
}

private void storeJoinStatsToHbo() {
    HistoryBasedPlanStatisticsProvider hboProvider = getHboProvider();

    for (Map.Entry<String, AggregatedJoinStats> entry :
            joinStatsCollector.getStats().entrySet()) {

        String canonicalKey = entry.getKey();
        JoinSampleStatistics stats = entry.getValue().toJoinSampleStatistics();

        // Merge with existing observation if present
        Optional<JoinSampleStatistics> existing = hboProvider.getJoinSample(canonicalKey);
        if (existing.isPresent()) {
            stats = existing.get().mergeObservation(stats);
        }

        hboProvider.putJoinSample(canonicalKey, stats);

        log.debug("Stored join stats for %s: lr=%.3f, rl=%.3f",
            canonicalKey, stats.getLeftToRightFanout(), stats.getRightToLeftFanout());
    }
}
```

---

## 6. HBO Provider: Storage Interface

### 6.1 Extended Interface

```java
public interface HistoryBasedPlanStatisticsProvider {
    // Existing HBO methods
    String getName();

    Map<PlanNodeId, HistoricalPlanStatistics> getStats(
        List<PlanNodeWithHash> planNodesWithHash,
        long timeoutInMillis);

    void putStats(Map<PlanNodeWithHash, HistoricalPlanStatistics> stats);

    // NEW: Join sample methods
    default Optional<JoinSampleStatistics> getJoinSample(String joinKey) {
        return Optional.empty();
    }

    default void putJoinSample(String joinKey, JoinSampleStatistics statistics) {
        // Default: no-op
    }

    default Map<String, JoinSampleStatistics> getJoinSamples(
            Set<String> joinKeys,
            long timeoutInMillis) {
        Map<String, JoinSampleStatistics> result = new HashMap<>();
        for (String key : joinKeys) {
            getJoinSample(key).ifPresent(s -> result.put(key, s));
        }
        return result;
    }
}
```

### 6.2 Redis Implementation

```java
public class RedisHistoryBasedPlanStatisticsProvider
        implements HistoryBasedPlanStatisticsProvider {

    private static final String JOIN_SAMPLE_PREFIX = "hbo:join:";
    private static final int JOIN_SAMPLE_TTL_SECONDS = 7 * 24 * 3600; // 1 week

    private final JedisPool jedisPool;
    private final ObjectMapper objectMapper;

    @Override
    public Optional<JoinSampleStatistics> getJoinSample(String joinKey) {
        try (Jedis jedis = jedisPool.getResource()) {
            String value = jedis.get(JOIN_SAMPLE_PREFIX + hashKey(joinKey));
            if (value == null) {
                return Optional.empty();
            }
            return Optional.of(objectMapper.readValue(value, JoinSampleStatistics.class));
        } catch (Exception e) {
            log.warn("Failed to get join sample: " + joinKey, e);
            return Optional.empty();
        }
    }

    @Override
    public void putJoinSample(String joinKey, JoinSampleStatistics stats) {
        try (Jedis jedis = jedisPool.getResource()) {
            String value = objectMapper.writeValueAsString(stats);
            jedis.setex(JOIN_SAMPLE_PREFIX + hashKey(joinKey),
                        JOIN_SAMPLE_TTL_SECONDS, value);
        } catch (Exception e) {
            log.warn("Failed to store join sample: " + joinKey, e);
        }
    }

    // Hash long keys to fixed-length for Redis efficiency
    private String hashKey(String joinKey) {
        return Hashing.sha256()
            .hashString(joinKey, StandardCharsets.UTF_8)
            .toString()
            .substring(0, 32);
    }
}
```

### 6.3 Redis Key Schema

```
Key:   hbo:join:{sha256(canonical_key)[0:32]}
Value: {
  "lr": 10.5,              // left-to-right fanout
  "rl": 0.95,              // right-to-left fanout
  "ts": 1704067200000,     // observation timestamp (epoch ms)
  "cnt": 5,                // observation count
  "var": 0.02,             // variance across observations
  "key": "customer c_custkey   orders o_custkey "  // original key for debugging
}
TTL: 7 days (configurable)
```

---

## 7. Optimizer Integration

### 7.1 HistoryBasedJoinStatsRule

```java
public class HistoryBasedJoinStatsRule
        implements ComposableStatsCalculator.Rule<JoinNode> {

    private final HistoryBasedPlanStatisticsProvider hboProvider;

    @Override
    public Pattern<JoinNode> getPattern() {
        return join();
    }

    @Override
    public Optional<PlanNodeStatsEstimate> calculate(
            JoinNode node,
            StatsProvider statsProvider,
            Lookup lookup,
            Session session) {

        if (!isUseHboJoinSamplesEnabled(session)) {
            return Optional.empty();
        }

        // Only support base table joins
        Optional<TableInfo> leftTable = extractBaseTableInfo(node.getLeft());
        Optional<TableInfo> rightTable = extractBaseTableInfo(node.getRight());

        if (leftTable.isEmpty() || rightTable.isEmpty()) {
            return Optional.empty();
        }

        // Generate canonical key
        List<String> leftKeys = extractJoinColumnNames(node.getCriteria(), true);
        List<String> rightKeys = extractJoinColumnNames(node.getCriteria(), false);

        CanonicalJoinKey keyResult = CanonicalJoinKeyGenerator.generate(
            leftTable.get().tableName(), leftKeys,
            rightTable.get().tableName(), rightKeys);

        // Lookup in HBO
        Optional<JoinSampleStatistics> sample =
            hboProvider.getJoinSample(keyResult.getKey());

        if (sample.isEmpty()) {
            return Optional.empty();  // No historical data, fall back
        }

        JoinSampleStatistics stats = sample.get();

        // Swap fanouts if key was reversed
        double fanout = keyResult.isSwapped()
            ? stats.getRightToLeftFanout()
            : stats.getLeftToRightFanout();

        // Check staleness (optional)
        if (isStale(stats, leftTable.get(), rightTable.get())) {
            return Optional.empty();
        }

        // Compute output cardinality
        PlanNodeStatsEstimate leftStats = statsProvider.getStats(node.getLeft());
        double outputRows = leftStats.getOutputRowCount() * fanout;

        return Optional.of(PlanNodeStatsEstimate.builder()
            .setOutputRowCount(outputRows)
            .build());
    }
}
```

### 7.2 Registering the Rule

```java
// In StatsCalculatorModule.java or similar

@Override
protected void setup(Binder binder) {
    // ... existing rules ...

    // NEW: HBO join stats rule
    binder.bind(HistoryBasedJoinStatsRule.class).in(Scopes.SINGLETON);

    // Add to composable stats calculator
    Multibinder<ComposableStatsCalculator.Rule<?>> rulesBinder =
        Multibinder.newSetBinder(binder, new TypeLiteral<ComposableStatsCalculator.Rule<?>>() {});
    rulesBinder.addBinding().to(HistoryBasedJoinStatsRule.class);
}
```

---

## 8. Session Properties

```java
// In SystemSessionProperties.java

public static final String USE_HBO_JOIN_SAMPLES = "use_hbo_join_samples";
public static final String TRACK_HBO_JOIN_SAMPLES = "track_hbo_join_samples";

propertyMetadataBuilder(USE_HBO_JOIN_SAMPLES)
    .description("Use historical join samples for cardinality estimation")
    .booleanValue()
    .defaultValue(false)
    .build();

propertyMetadataBuilder(TRACK_HBO_JOIN_SAMPLES)
    .description("Record join execution statistics for future optimization")
    .booleanValue()
    .defaultValue(false)
    .build();
```

---

## 9. End-to-End Example

### Query 1: First Execution (Learning)

```sql
SET SESSION track_hbo_join_samples = true;

SELECT o.order_id, c.name
FROM orders o
JOIN customers c ON o.customer_id = c.id
WHERE o.status = 'SHIPPED';
```

**Execution flow:**

1. Optimizer uses traditional estimation: `|orders ⋈ customers| ≈ 1M × 100K / 100K = 1M`

2. HashProbe executes, observes:
   - `probeInputRows = 500,000` (filtered orders)
   - `buildInputRows = 100,000` (all customers)
   - `outputRows = 475,000` (most orders have customers)

3. Task completes, sends final TaskInfo with:
   ```
   JoinSelectivityInfo {
     canonical_key: "customers id   orders customer_id "
     probe_input_rows: 500000
     build_input_rows: 100000
     output_rows: 475000
     key_was_swapped: true
   }
   ```

4. Coordinator aggregates (sums across tasks) and stores to HBO:
   ```
   Key: hbo:join:a1b2c3d4...
   Value: {
     "lr": 0.95,    // 475K/500K - most orders match
     "rl": 4.75,    // 475K/100K - each customer has ~5 shipped orders
     "ts": 1704067200000,
     "cnt": 1
   }
   ```

### Query 2: Second Execution (Benefiting)

```sql
SET SESSION use_hbo_join_samples = true;

SELECT c.name, COUNT(*)
FROM customers c
JOIN orders o ON c.id = o.customer_id  -- Note: reversed join order!
WHERE o.status = 'PENDING'
GROUP BY c.name;
```

**Optimization flow:**

1. Optimizer sees join: `customers ⋈ orders`

2. Generates canonical key: `"customers id   orders customer_id "` (same as before!)

3. Queries HBO, gets: `lr=0.95, rl=4.75`

4. Since join is `customers JOIN orders`:
   - customers is probe (left)
   - Key was NOT swapped (customers < orders lexicographically)
   - Use `lr_fanout = 0.95`... wait, that's wrong direction

5. Actually: Key WAS generated with customers first, so:
   - Original observation had orders as probe (swapped=true)
   - Current query has customers as probe (swapped=false)
   - Need to use `rl_fanout = 4.75` (each customer has ~5 orders)

6. Estimate: `|customers| × 4.75 = 100K × 4.75 = 475K` (accurate!)

7. Better plan selected based on accurate cardinality.

---

## 10. Implementation Plan

**Key simplification**: No Velox or presto-native-execution changes required. All changes are coordinator-side only.

### Phase 1: Coordinator Stats Extraction (1 week)

1. Add `JoinStatsCollector` to extract stats from existing `TaskInfo`
2. Iterate `TaskInfo.stats.pipelines[*].operatorSummaries[*]`
3. Identify `LookupJoinOperator` (probe) and `HashBuilderOperator` (build)
4. Correlate by `planNodeId`, sum `inputPositions`/`outputPositions` across tasks
5. Map `planNodeId` → canonical join key using plan metadata

**Deliverable**: Coordinator extracts join stats from existing TaskInfo.

**Files changed**:
- `presto-main/.../execution/SqlQueryExecution.java` (or StageStateMachine)
- New `JoinStatsCollector.java`

### Phase 2: HBO Storage (1 week)

1. Extend `HistoryBasedPlanStatisticsProvider` with join sample methods
2. Implement Redis storage for join selectivity
3. Compute fanout after aggregation: `sum(output) / sum(probe)`
4. Handle observation merging (Welford's for variance)

**Deliverable**: Join stats persisted to Redis.

**Files changed**:
- `presto-main/.../statistics/HistoryBasedPlanStatisticsProvider.java`
- Redis implementation class

### Phase 3: Optimizer Integration (1-2 weeks)

1. Implement `CanonicalJoinKeyGenerator`
2. Add `HistoryBasedJoinStatsRule` to stats calculator
3. Look up fanout during join cardinality estimation
4. Add session properties (`use_hbo_join_samples`, `track_hbo_join_samples`)

**Deliverable**: Second+ queries use learned fanouts.

### Phase 4: Validation (1 week)

1. Add logging/metrics for cache hit rates
2. Test with TPC-H/DS workloads
3. Tune TTL and merge strategy
4. Verify no performance regression

**Deliverable**: Production-ready feature.

**Total: 4-5 weeks** (coordinator-only changes)

---

## 11. Success Metrics

### Correctness

- Query results unchanged (statistics only)
- Fanout values mathematically consistent (lr × probe_rows = rl × build_rows = output)

### Performance

| Metric | Target |
|--------|--------|
| Second query plan improvement | Better join order in 20%+ of cases with misestimates |
| HBO lookup latency | < 10ms (Redis mget) |
| Stats collection overhead | ~0% (uses existing Velox stats) |
| Storage per join pair | < 500 bytes |

### Observability

```
Metrics:
- hbo.join_stats.observations_stored
- hbo.join_stats.observations_retrieved
- hbo.join_stats.cache_hits
- hbo.join_stats.cache_misses
- hbo.join_stats.estimate_improvement_ratio
```

---

## 12. Limitations and Future Work

### Current Limitations

1. **Base tables only**: Cannot track joins on derived tables/CTEs
2. **No predicate awareness**: Same fanout used regardless of WHERE clause
3. **First query doesn't benefit**: Learning is retrospective
4. **Staleness risk**: Data changes may invalidate stored fanouts

### Future Enhancements

1. **Predicate-qualified stats**: Key by (tables, columns, predicate_hash)
2. **Hash samples**: Store key samples for new table pair estimation
3. **AEF integration**: Use HBO for confidence, AEF for first-query adaptation
4. **Automatic invalidation**: Hook into table modification events

---

*Document Version: 0.2*
*Last Updated: January 2025*
