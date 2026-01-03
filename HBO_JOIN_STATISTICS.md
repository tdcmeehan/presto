# HBO Join Statistics: Execution-Based Join Selectivity Learning

## Overview

This document describes a stepping stone project to improve join cardinality estimation by capturing join statistics during query execution and storing them in Presto's History-Based Optimizer (HBO) infrastructure. Future queries with the same table pairs will benefit from learned selectivity without requiring the full Adaptive Exchange Framework (AEF).

**Goal**: After executing a query with joins, subsequent queries joining the same tables should have more accurate cardinality estimates.

**Runtime**: Velox (C++) only. There are no plans to implement this in the Java runtime.

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

## 3. Velox Implementation: Collecting Statistics

### 3.1 Architecture Overview

```
┌─────────────────────────────────────────────────────────────────────┐
│                         Velox Task                                   │
│  ┌─────────────┐    ┌─────────────┐    ┌─────────────────────────┐  │
│  │ HashProbe   │───▶│ Driver      │───▶│ Task                    │  │
│  │ Operator    │    │             │    │                         │  │
│  │             │    │ Collects    │    │ Aggregates operator     │  │
│  │ Tracks:     │    │ operator    │    │ stats across drivers    │  │
│  │ - probeRows │    │ stats at    │    │                         │  │
│  │ - buildRows │    │ pipeline    │    │ Publishes to            │  │
│  │ - matches   │    │ completion  │    │ TaskStats               │  │
│  └─────────────┘    └─────────────┘    └─────────────────────────┘  │
└─────────────────────────────────────────────────────────────────────┘
                                │
                                ▼
┌─────────────────────────────────────────────────────────────────────┐
│                    Presto Worker (C++)                               │
│  ┌─────────────────────────────────────────────────────────────────┐│
│  │ PrestoTask                                                       ││
│  │                                                                  ││
│  │ updateHeartbeatInfo() / createTaskInfo()                        ││
│  │   - Reads Task::taskStats()                                     ││
│  │   - Extracts JoinSelectivityStats per join operator             ││
│  │   - Includes in TaskInfo sent to coordinator                    ││
│  └─────────────────────────────────────────────────────────────────┘│
└─────────────────────────────────────────────────────────────────────┘
                                │
                                ▼
┌─────────────────────────────────────────────────────────────────────┐
│                    Presto Coordinator (Java)                         │
│  ┌─────────────────────────────────────────────────────────────────┐│
│  │ SqlQueryExecution                                                ││
│  │                                                                  ││
│  │ On final TaskInfo:                                               ││
│  │   - Extract JoinSelectivityStats                                 ││
│  │   - Aggregate across tasks (weighted by row count)               ││
│  │   - Store to HBO via HistoryBasedPlanStatisticsProvider         ││
│  └─────────────────────────────────────────────────────────────────┘│
└─────────────────────────────────────────────────────────────────────┘
```

### 3.2 HashProbe Operator: Tracking Join Statistics

The `HashProbe` operator in Velox executes the probe phase of a hash join. We extend it to track join selectivity:

```cpp
// In velox/exec/HashProbe.h

class HashProbe : public Operator {
 public:
  // ... existing code ...

  // NEW: Join statistics tracking
  struct JoinStats {
    int64_t probeInputRows = 0;    // Rows received from probe side
    int64_t buildInputRows = 0;    // Rows in hash table (from HashBuild)
    int64_t outputRows = 0;        // Rows emitted from join

    // For canonical key generation
    std::string probeTableName;
    std::string buildTableName;
    std::vector<std::string> probeKeyColumns;
    std::vector<std::string> buildKeyColumns;

    double probeToBuildfanout() const {
      return probeInputRows > 0
          ? static_cast<double>(outputRows) / probeInputRows
          : 0.0;
    }

    double buildToProbeFanout() const {
      return buildInputRows > 0
          ? static_cast<double>(outputRows) / buildInputRows
          : 0.0;
    }
  };

  const JoinStats& joinStats() const { return joinStats_; }

 private:
  JoinStats joinStats_;
};
```

### 3.3 HashProbe: Populating Statistics During Execution

```cpp
// In velox/exec/HashProbe.cpp

void HashProbe::addInput(RowVectorPtr input) {
  // Track probe input rows
  joinStats_.probeInputRows += input->size();

  // ... existing probe logic ...
}

RowVectorPtr HashProbe::getOutput() {
  auto output = /* ... existing output generation ... */;

  if (output) {
    // Track output rows
    joinStats_.outputRows += output->size();
  }

  return output;
}

void HashProbe::initialize() {
  // Get build side stats from HashBuild via the hash table
  auto* hashTable = table_.get();
  joinStats_.buildInputRows = hashTable->numDistinct();  // Or total rows

  // Extract table and column names from plan node
  auto* joinNode = dynamic_cast<const core::HashJoinNode*>(planNode_.get());
  if (joinNode) {
    // Extract probe side table name (requires walking the plan tree)
    joinStats_.probeTableName = extractTableName(joinNode->sources()[0]);
    joinStats_.buildTableName = extractTableName(joinNode->sources()[1]);

    // Extract join key column names
    for (const auto& key : joinNode->leftKeys()) {
      joinStats_.probeKeyColumns.push_back(key->name());
    }
    for (const auto& key : joinNode->rightKeys()) {
      joinStats_.buildKeyColumns.push_back(key->name());
    }
  }
}

// Helper to extract base table name from a plan subtree
std::string HashProbe::extractTableName(
    const std::shared_ptr<const core::PlanNode>& node) {
  // Walk down to find TableScanNode
  if (auto* tableScan = dynamic_cast<const core::TableScanNode*>(node.get())) {
    // Extract table name from TableHandle
    // This is connector-specific; for Hive:
    auto* hiveHandle = dynamic_cast<const HiveTableHandle*>(
        tableScan->tableHandle().get());
    if (hiveHandle) {
      return hiveHandle->tableName();
    }
  }

  // Recurse into sources
  for (const auto& source : node->sources()) {
    auto name = extractTableName(source);
    if (!name.empty()) {
      return name;
    }
  }

  return "";  // Not a base table join
}
```

### 3.4 Operator Stats: Exposing Join Statistics

Velox operators expose statistics via `OperatorStats`. We extend this:

```cpp
// In velox/exec/OperatorStats.h

struct OperatorStats {
  // ... existing fields ...

  // NEW: Join-specific statistics
  struct JoinOperatorStats {
    int64_t probeInputRows = 0;
    int64_t buildInputRows = 0;
    int64_t outputRows = 0;
    std::string canonicalJoinKey;  // Pre-computed canonical key
    bool keyWasSwapped = false;    // Whether tables were swapped in key
  };

  std::optional<JoinOperatorStats> joinStats;
};

// In velox/exec/HashProbe.cpp

void HashProbe::close() {
  // Populate operator stats with join statistics
  auto stats = operatorStats();

  OperatorStats::JoinOperatorStats joinOpStats;
  joinOpStats.probeInputRows = joinStats_.probeInputRows;
  joinOpStats.buildInputRows = joinStats_.buildInputRows;
  joinOpStats.outputRows = joinStats_.outputRows;

  // Generate canonical key
  auto [key, swapped] = generateCanonicalJoinKey(
      joinStats_.probeTableName,
      joinStats_.probeKeyColumns,
      joinStats_.buildTableName,
      joinStats_.buildKeyColumns);

  joinOpStats.canonicalJoinKey = std::move(key);
  joinOpStats.keyWasSwapped = swapped;

  stats.joinStats = std::move(joinOpStats);

  Operator::close();
}
```

### 3.5 Task Stats: Aggregating Across Pipelines

```cpp
// In velox/exec/Task.cpp

TaskStats Task::taskStats() const {
  TaskStats stats;

  // ... existing stats collection ...

  // NEW: Collect join stats from all pipelines
  for (const auto& pipeline : pipelines_) {
    for (const auto& driver : pipeline.drivers) {
      for (const auto& op : driver->operators()) {
        if (op->operatorType() == "HashProbe") {
          auto opStats = op->stats();
          if (opStats.joinStats.has_value()) {
            stats.joinStats.push_back(opStats.joinStats.value());
          }
        }
      }
    }
  }

  return stats;
}
```

---

## 4. Presto Worker: Including Join Stats in TaskInfo

### 4.1 TaskInfo Protocol Buffer Extension

```protobuf
// In presto-native-execution/presto_protocol/presto_protocol.proto

message JoinSelectivityInfo {
  string canonical_join_key = 1;
  int64 probe_input_rows = 2;
  int64 build_input_rows = 3;
  int64 output_rows = 4;
  bool key_was_swapped = 5;
}

message TaskInfo {
  // ... existing fields ...

  // NEW: Join statistics observed during execution
  repeated JoinSelectivityInfo join_selectivity_stats = 20;
}
```

### 4.2 PrestoTask: Populating TaskInfo

```cpp
// In presto-native-execution/src/PrestoTask.cpp

protocol::TaskInfo PrestoTask::createTaskInfo() const {
  protocol::TaskInfo info;

  // ... existing TaskInfo population ...

  // NEW: Add join selectivity stats
  auto taskStats = task_->taskStats();
  for (const auto& joinStat : taskStats.joinStats) {
    if (!joinStat.canonicalJoinKey.empty()) {
      protocol::JoinSelectivityInfo jsInfo;
      jsInfo.canonical_join_key = joinStat.canonicalJoinKey;
      jsInfo.probe_input_rows = joinStat.probeInputRows;
      jsInfo.build_input_rows = joinStat.buildInputRows;
      jsInfo.output_rows = joinStat.outputRows;
      jsInfo.key_was_swapped = joinStat.keyWasSwapped;

      info.join_selectivity_stats.push_back(std::move(jsInfo));
    }
  }

  return info;
}
```

### 4.3 Heartbeat vs Final TaskInfo

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

### Phase 1: Velox Stats Collection (2 weeks)

1. Add `JoinStats` struct to `HashProbe`
2. Track rows in `addInput()` and `getOutput()`
3. Extract table/column names from plan node
4. Expose via `OperatorStats`
5. Aggregate in `Task::taskStats()`

**Deliverable**: Velox tasks collect join statistics.

### Phase 2: TaskInfo Transport (1 week)

1. Add `JoinSelectivityInfo` to protocol buffer
2. Populate in `PrestoTask::createTaskInfo()`
3. Mark complete vs incomplete observations

**Deliverable**: Join stats included in TaskInfo to coordinator.

### Phase 3: HBO Storage (1-2 weeks)

1. Extend `HistoryBasedPlanStatisticsProvider` interface
2. Implement Redis storage methods
3. Add aggregation in coordinator
4. Store on query completion

**Deliverable**: Join stats persisted to Redis.

### Phase 4: Optimizer Integration (1-2 weeks)

1. Implement `CanonicalJoinKeyGenerator`
2. Add `HistoryBasedJoinStatsRule`
3. Register rule in stats calculator
4. Add session properties

**Deliverable**: Second+ queries use HBO stats.

### Phase 5: Validation (1 week)

1. Add logging/metrics
2. Test with real workloads
3. Tune TTL and merge strategy
4. Document operational procedures

**Deliverable**: Production-ready feature.

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
| Stats collection overhead | < 1% CPU in HashProbe |
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
