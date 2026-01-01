# HBO Join Statistics: Execution-Based Join Selectivity Learning

## Overview

This document describes a stepping stone project to improve join cardinality estimation by capturing join statistics during query execution and storing them in Presto's History-Based Optimizer (HBO) infrastructure. Future queries with the same table pairs will benefit from learned selectivity without requiring the full Adaptive Exchange Framework (AEF).

**Goal**: After executing a query with joins, subsequent queries joining the same tables should have more accurate cardinality estimates.

## Motivation

### The Problem

Join cardinality estimation is the primary source of query optimizer errors. The classic formula:

```
|A ⋈ B| ≈ |A| × |B| / max(NDV(A.key), NDV(B.key))
```

Fails because it assumes:
- Uniform key distribution (rarely true)
- Key containment = 1.0 (all probe keys exist in build)
- Fanout = 1.0 (one match per key)

### The Opportunity

During join execution, we observe the **actual** selectivity:

```
Actual output rows = left input rows × left-to-right fanout
                   = right input rows × right-to-left fanout
```

If we capture and store this, future queries benefit without additional overhead.

### Why This Is a Good Stepping Stone

| Aspect | Full AEF | HBO Join Stats (This Project) |
|--------|----------|-------------------------------|
| Complexity | High (buffering, sections, reopt) | Low (capture and store) |
| First query benefit | Yes (adapts during execution) | No (learns for next time) |
| Second+ query benefit | Yes | Yes |
| Infrastructure changes | Significant | Minimal (extends HBO) |
| Risk | Higher | Lower |

## Design

### High-Level Flow

```
Query 1: SELECT ... FROM orders JOIN customers ON ...
  ├─ Execute normally
  ├─ At join operator, observe: left_rows=1M, right_rows=100K, output=950K
  ├─ Compute: lr_fanout = 950K/1M = 0.95, rl_fanout = 950K/100K = 9.5
  ├─ Store in HBO: ("orders o_custkey", "customers c_custkey") → (0.95, 9.5)
  └─ Return results

Query 2: SELECT ... FROM orders JOIN customers ON ... (different columns selected)
  ├─ Optimizer queries HBO for ("orders", "customers", "o_custkey=c_custkey")
  ├─ HBO returns: lr_fanout=0.95, rl_fanout=9.5
  ├─ Optimizer uses accurate fanout instead of assumptions
  └─ Better plan selected
```

### Data Structures

#### JoinSampleStatistics

```java
public class JoinSampleStatistics {
    // Bidirectional fanout - supports both join orderings
    private final double leftToRightFanout;   // output_rows / left_input_rows
    private final double rightToLeftFanout;   // output_rows / right_input_rows

    // Metadata for cache management
    private final long sampleTimestamp;
    private final long leftTableVersion;      // For invalidation
    private final long rightTableVersion;

    // Observation quality
    private final long observationCount;      // How many times observed
    private final double variance;            // Stability across observations

    public JoinSampleStatistics(
            double leftToRightFanout,
            double rightToLeftFanout,
            long sampleTimestamp,
            long leftTableVersion,
            long rightTableVersion) {
        this.leftToRightFanout = leftToRightFanout;
        this.rightToLeftFanout = rightToLeftFanout;
        this.sampleTimestamp = sampleTimestamp;
        this.leftTableVersion = leftTableVersion;
        this.rightTableVersion = rightTableVersion;
        this.observationCount = 1;
        this.variance = 0.0;
    }

    // Swap for when join direction is reversed
    public JoinSampleStatistics swap() {
        return new JoinSampleStatistics(
            rightToLeftFanout, leftToRightFanout,
            sampleTimestamp, rightTableVersion, leftTableVersion);
    }

    // Merge with new observation (running average)
    public JoinSampleStatistics mergeObservation(JoinSampleStatistics newer) {
        long newCount = this.observationCount + 1;
        double newLrFanout = this.leftToRightFanout +
            (newer.leftToRightFanout - this.leftToRightFanout) / newCount;
        double newRlFanout = this.rightToLeftFanout +
            (newer.rightToLeftFanout - this.rightToLeftFanout) / newCount;
        // ... update variance using Welford's algorithm
        return new JoinSampleStatistics(newLrFanout, newRlFanout, ...);
    }
}
```

#### Canonical Key Generation

Keys must be deterministic regardless of SQL join order:

```java
public class CanonicalJoinKeyGenerator {

    /**
     * Generate a canonical key for a join between two tables.
     * Returns (key, swapped) where swapped indicates if tables were reordered.
     */
    public static Pair<String, Boolean> generateKey(
            QualifiedObjectName leftTable,
            List<String> leftKeys,
            QualifiedObjectName rightTable,
            List<String> rightKeys) {

        // Sort join keys alphabetically for determinism
        List<Integer> indices = IntStream.range(0, leftKeys.size())
            .boxed()
            .sorted(Comparator.comparing(leftKeys::get))
            .collect(Collectors.toList());

        // Build canonical table+keys strings
        StringBuilder left = new StringBuilder(leftTable.getObjectName() + " ");
        StringBuilder right = new StringBuilder(rightTable.getObjectName() + " ");

        for (int i : indices) {
            left.append(leftKeys.get(i)).append(" ");
            right.append(rightKeys.get(i)).append(" ");
        }

        String leftStr = left.toString();
        String rightStr = right.toString();

        // Lexicographically smaller table first
        if (leftStr.compareTo(rightStr) < 0) {
            return Pair.of(leftStr + "  " + rightStr, false);
        }
        return Pair.of(rightStr + "  " + leftStr, true);
    }
}

// Examples:
// A JOIN B ON a.x = b.y → "a x   b y " (not swapped)
// B JOIN A ON b.y = a.x → "a x   b y " (swapped=true, need to swap fanouts)
```

### Integration Points

#### 1. Capturing Statistics During Execution

**Option A: Final TaskInfo (Recommended)**

Presto's TaskInfo contains execution statistics. The "final" TaskInfo has the most complete data:

```java
// In TaskInfo or a new JoinExecutionStats
public class JoinExecutionStats {
    private final PlanNodeId joinNodeId;
    private final String canonicalJoinKey;
    private final long leftInputRows;
    private final long rightInputRows;
    private final long outputRows;

    public JoinSampleStatistics toJoinSampleStatistics() {
        double lrFanout = (double) outputRows / leftInputRows;
        double rlFanout = (double) outputRows / rightInputRows;
        return new JoinSampleStatistics(lrFanout, rlFanout, ...);
    }
}
```

**Where to capture:**

```java
// In HashJoinOperator or similar
class HashJoinOperator implements Operator {

    private long leftInputPositions = 0;
    private long rightInputPositions = 0;
    private long outputPositions = 0;

    @Override
    public void finish() {
        // Capture join stats
        operatorContext.recordJoinStats(new JoinExecutionStats(
            planNodeId,
            getCanonicalJoinKey(),
            leftInputPositions,
            rightInputPositions,
            outputPositions));
    }
}
```

**Aggregating at coordinator:**

```java
// In SqlQueryExecution or QueryStateMachine
void onStageComplete(StageId stageId, StageInfo stageInfo) {
    for (TaskInfo task : stageInfo.getTasks()) {
        for (JoinExecutionStats joinStats : task.getJoinStats()) {
            // Aggregate across tasks (sum rows, compute average fanout)
            aggregatedJoinStats.merge(joinStats);
        }
    }
}

void onQueryComplete() {
    // Store aggregated stats to HBO
    for (JoinSampleStatistics stats : aggregatedJoinStats.values()) {
        hboProvider.putJoinSample(stats.getCanonicalKey(), stats);
    }
}
```

#### 2. Extended HBO Provider Interface

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
        return Optional.empty();  // Default: not implemented
    }

    default void putJoinSample(String joinKey, JoinSampleStatistics statistics) {
        // Default: no-op
    }

    default Map<String, JoinSampleStatistics> getJoinSamples(
            Set<String> joinKeys,
            long timeoutInMillis) {
        // Default: iterate over keys
        Map<String, JoinSampleStatistics> result = new HashMap<>();
        for (String key : joinKeys) {
            getJoinSample(key).ifPresent(s -> result.put(key, s));
        }
        return result;
    }
}
```

#### 3. Redis Implementation

```java
public class RedisHistoryBasedPlanStatisticsProvider
        implements HistoryBasedPlanStatisticsProvider {

    private static final String JOIN_SAMPLE_PREFIX = "hbo:join_sample:";
    private static final int JOIN_SAMPLE_TTL_SECONDS = 7 * 24 * 3600; // 1 week

    private final JedisPool jedisPool;
    private final ObjectMapper objectMapper;

    @Override
    public Optional<JoinSampleStatistics> getJoinSample(String joinKey) {
        try (Jedis jedis = jedisPool.getResource()) {
            String value = jedis.get(JOIN_SAMPLE_PREFIX + joinKey);
            if (value == null) {
                return Optional.empty();
            }
            return Optional.of(objectMapper.readValue(value, JoinSampleStatistics.class));
        } catch (Exception e) {
            log.warn("Failed to get join sample for key: " + joinKey, e);
            return Optional.empty();
        }
    }

    @Override
    public void putJoinSample(String joinKey, JoinSampleStatistics stats) {
        try (Jedis jedis = jedisPool.getResource()) {
            String value = objectMapper.writeValueAsString(stats);
            jedis.setex(JOIN_SAMPLE_PREFIX + joinKey, JOIN_SAMPLE_TTL_SECONDS, value);
        } catch (Exception e) {
            log.warn("Failed to store join sample for key: " + joinKey, e);
        }
    }

    @Override
    public Map<String, JoinSampleStatistics> getJoinSamples(
            Set<String> joinKeys, long timeoutInMillis) {
        try (Jedis jedis = jedisPool.getResource()) {
            String[] keys = joinKeys.stream()
                .map(k -> JOIN_SAMPLE_PREFIX + k)
                .toArray(String[]::new);

            List<String> values = jedis.mget(keys);

            Map<String, JoinSampleStatistics> result = new HashMap<>();
            int i = 0;
            for (String joinKey : joinKeys) {
                String value = values.get(i++);
                if (value != null) {
                    result.put(joinKey, objectMapper.readValue(value, JoinSampleStatistics.class));
                }
            }
            return result;
        } catch (Exception e) {
            log.warn("Failed to batch get join samples", e);
            return Collections.emptyMap();
        }
    }
}
```

#### 4. Integration with Stats Calculation

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

        if (!isHboJoinSamplesEnabled(session)) {
            return Optional.empty();
        }

        // Only support base table joins for now
        Optional<QualifiedObjectName> leftTable = extractBaseTable(node.getLeft());
        Optional<QualifiedObjectName> rightTable = extractBaseTable(node.getRight());

        if (leftTable.isEmpty() || rightTable.isEmpty()) {
            return Optional.empty();
        }

        // Generate canonical key
        List<String> leftKeys = extractJoinColumns(node.getCriteria(), true);
        List<String> rightKeys = extractJoinColumns(node.getCriteria(), false);

        Pair<String, Boolean> keyPair = CanonicalJoinKeyGenerator.generateKey(
            leftTable.get(), leftKeys, rightTable.get(), rightKeys);

        // Lookup in HBO
        Optional<JoinSampleStatistics> sample = hboProvider.getJoinSample(keyPair.getLeft());

        if (sample.isEmpty()) {
            return Optional.empty();  // Fall back to traditional estimation
        }

        JoinSampleStatistics stats = sample.get();
        if (keyPair.getRight()) {
            stats = stats.swap();  // Swap if key was reversed
        }

        // Check staleness
        if (isStale(stats, leftTable.get(), rightTable.get())) {
            return Optional.empty();  // Stale, fall back
        }

        // Compute output cardinality using historical fanout
        PlanNodeStatsEstimate leftStats = statsProvider.getStats(node.getLeft());
        double outputRows = leftStats.getOutputRowCount() * stats.getLeftToRightFanout();

        return Optional.of(PlanNodeStatsEstimate.builder()
            .setOutputRowCount(outputRows)
            .build());
    }

    private boolean isStale(JoinSampleStatistics stats,
                           QualifiedObjectName leftTable,
                           QualifiedObjectName rightTable) {
        // Check if tables have been modified since stats were captured
        // This could query metastore for last modification time
        return false; // TODO: implement staleness check
    }
}
```

### Session Properties

```java
// SystemSessionProperties.java
public static final String USE_HBO_JOIN_SAMPLES = "use_hbo_join_samples";
public static final String TRACK_HBO_JOIN_SAMPLES = "track_hbo_join_samples";

propertyMetadataBuilder(USE_HBO_JOIN_SAMPLES)
    .description("Use historical join samples for cardinality estimation")
    .booleanValue()
    .defaultValue(false)  // Off by default during rollout
    .build();

propertyMetadataBuilder(TRACK_HBO_JOIN_SAMPLES)
    .description("Record join execution statistics for future optimization")
    .booleanValue()
    .defaultValue(false)  // Off by default
    .build();
```

### Redis Key Schema

```
Key:   hbo:join_sample:{canonical_key}
Value: {
  "lr": <float>,           // left-to-right fanout
  "rl": <float>,           // right-to-left fanout
  "ts": <epoch_millis>,    // sample timestamp
  "lv": <long>,            // left table version (optional)
  "rv": <long>,            // right table version (optional)
  "cnt": <int>,            // observation count
  "var": <float>           // variance across observations
}

# Example
hbo:join_sample:customer c_custkey   orders o_custkey  ->
  {"lr":10.5,"rl":0.95,"ts":1704067200000,"cnt":5,"var":0.02}
```

## Implementation Plan

### Phase 1: Capture and Store (2-3 weeks)

1. Add `JoinExecutionStats` to track join statistics during execution
2. Aggregate stats at query completion
3. Extend `HistoryBasedPlanStatisticsProvider` with join sample methods
4. Implement Redis storage for join samples

**Deliverable**: Queries store join statistics in Redis after execution.

### Phase 2: Use in Optimizer (1-2 weeks)

1. Implement `CanonicalJoinKeyGenerator`
2. Add `HistoryBasedJoinStatsRule` to stats calculation
3. Add session properties for feature gating

**Deliverable**: Second+ executions of join queries use HBO statistics.

### Phase 3: Validation and Tuning (1-2 weeks)

1. Add logging/metrics for HBO join stats usage
2. Implement staleness detection
3. Tune TTL and merge strategy
4. A/B test on real workloads

**Deliverable**: Production-ready feature with observability.

## Success Metrics

### Correctness

- Query results unchanged (this is statistics only, no execution changes)
- HBO stats retrieved match stored values

### Performance

| Metric | Target |
|--------|--------|
| Second query improvement | 10-50% for join-heavy queries with misestimates |
| HBO lookup latency | < 10ms (Redis mget) |
| Storage overhead | < 1KB per join pair |

### Observability

```
Metrics:
- hbo.join_samples.stored - Number of join stats stored
- hbo.join_samples.retrieved - Number of join stats retrieved
- hbo.join_samples.used - Number of times HBO stats used in optimization
- hbo.join_samples.stale - Number of stale entries encountered
- hbo.join_samples.estimate_improvement - Ratio of actual vs estimated cardinality
```

## Comparison with Axiom

| Aspect | Axiom | This Design |
|--------|-------|-------------|
| Storage | In-memory + file | Redis via HBO |
| Capture point | During planning (pilot query) | During execution |
| Cluster scope | Single process | Cluster-wide |
| Invalidation | None | Table version tracking |
| TTL | Session-scoped | Configurable (default 1 week) |
| First query benefit | Yes (pilot) | No |
| Second query benefit | Yes | Yes |

## Future Work

### Integration with AEF

Once HBO join statistics are working, they integrate naturally with AEF:

1. **HBO provides baseline confidence**: If HBO has stats, optimizer knows join selectivity with high confidence
2. **AEF fills gaps**: For new table pairs or stale stats, AEF measures during execution
3. **AEF updates HBO**: Measurements feed back to HBO for future queries

### Hash Samples for New Table Pairs

Current design only helps when we've seen the exact table pair before. Future enhancement: store hash samples per table to enable sample-to-sample matching for new combinations.

### Predicate-Qualified Statistics

Current design ignores WHERE clause predicates. Future enhancement: track statistics per (table pair, predicate hash) to handle filtered joins.

---

*Document Version: 0.1*
*Last Updated: January 2025*
