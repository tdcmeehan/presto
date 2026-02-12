# Cost-Based DPP Decisions Using HBO Statistics

## Context

`AddDynamicFilterRule` currently has a `COST_BASED` value in the `DistributedDynamicFilterStrategy` enum, but it does nothing — the `apply()` method adds dynamic filters to every equi-join clause identically whether the strategy is `ALWAYS` or `COST_BASED`. The `isCostBased(session)` override returns true for `COST_BASED`, but this only affects optimizer reporting, not behavior. The enum value is dead weight and should be removed now, then re-added later with actual HBO-backed logic.

## Two-Phase Plan

### Phase 1 (Immediate): Remove non-functional `COST_BASED` enum value ✅ DONE

Remove the `COST_BASED` value from `DistributedDynamicFilterStrategy` since it currently behaves identically to `ALWAYS`. This avoids misleading users into thinking cost-based decisions are happening.

**Files to change:**

1. **`FeaturesConfig.java`** (`presto-main-base/.../sql/analyzer/FeaturesConfig.java`)
   - Remove `COST_BASED` from the `DistributedDynamicFilterStrategy` enum (line ~455), leaving only `DISABLED` and `ALWAYS`

2. **`AddDynamicFilterRule.java`** (`presto-main-base/.../iterative/rule/AddDynamicFilterRule.java`)
   - Remove the `isCostBased()` override (lines 71-75)
   - Remove the import of `COST_BASED`

3. **`AddDynamicFilterToSemiJoinRule.java`** (`presto-main-base/.../iterative/rule/AddDynamicFilterToSemiJoinRule.java`)
   - Remove the `isCostBased()` override (lines 61-64)
   - Remove the import of `COST_BASED`

4. **`SystemSessionProperties.java`** (`presto-main-base/.../SystemSessionProperties.java`)
   - Remove references to `COST_BASED` in the `DISTRIBUTED_DYNAMIC_FILTER_STRATEGY` property description if present

5. **`TestFeaturesConfig.java`** — Update if any test references `COST_BASED`

### Phase 2 (Future): Implement HBO-backed cost-based DPP

Re-add `COST_BASED` to the enum with actual logic that uses `context.getStatsProvider()` (HBO-backed) to make per-join decisions.

#### Key Design Decision: Stats-Required Semantics

When stats are **unknown** (NaN), `COST_BASED` mode **skips** creating dynamic filters. This gives three clearly distinct modes:

| Mode | Behavior |
|---|---|
| **ALWAYS** | Always add DPP filters (no stats needed) |
| **COST_BASED** | Only add DPP when stats positively indicate benefit (requires stats) |
| **DISABLED** | Never add DPP |

This makes `COST_BASED` effectively "HBO-gated DPP" — filters only activate for queries where HBO (or CBO) has enough confidence in the statistics. Safe for production rollout.

#### Approach: Two-Level Threshold Heuristic

Following the `DetermineJoinDistributionType` pattern:

1. **Stats availability gate**: If probe-side or build-side stats are unknown (NaN), skip creating filters.
2. **Probe-side size check** (table-level): If probe output size < configurable min threshold, skip ALL filters for this join. A small table doesn't benefit from split pruning.
3. **Build-side NDV check** (per-clause): If build-side join key NDV > configurable max threshold, skip that clause's filter. The filter domain would be too broad to effectively prune partitions.

#### Existing Infrastructure (already in place)

- **`Rule.Context`** provides `getStatsProvider()` and `getCostProvider()` (`Rule.java:54-73`)
- **`IterativeOptimizer`** wraps StatsCalculator with `CachingStatsProvider`; HBO stats flow automatically via `HistoryBasedPlanStatisticsCalculator` decorator
- **`PlanNodeStatsEstimate`** offers `getOutputRowCount()`, `getOutputSizeInBytes(node)`, `getVariableStatistics(var).getDistinctValuesCount()`, `getSourceInfo().getSourceInfoName()`
- **`Rule` interface** has `isCostBased(session)` and `getStatsSource()` for optimizer reporting
- **`DetermineJoinDistributionType`** is the canonical pattern to follow (threshold-based, statsSource tracking, fallback handling)

#### Files to Change

##### 1. `FeaturesConfig.java` — Re-add `COST_BASED` to enum + 2 new config properties
**Path**: `presto-main-base/src/main/java/com/facebook/presto/sql/analyzer/FeaturesConfig.java`

Re-add `COST_BASED` to `DistributedDynamicFilterStrategy` enum.

Add fields:
- `distributedDynamicFilterMinProbeSize` (DataSize, default 100MB)
- `distributedDynamicFilterMaxBuildNdv` (long, default 1,000,000)

With `@Config("distributed-dynamic-filter.min-probe-size")` and `@Config("distributed-dynamic-filter.max-build-ndv")` annotated getters/setters:
```java
public DataSize getDistributedDynamicFilterMinProbeSize()
{
    return distributedDynamicFilterMinProbeSize;
}

@Config("distributed-dynamic-filter.min-probe-size")
@ConfigDescription("Minimum estimated probe-side output size to create a distributed dynamic filter (cost-based mode)")
public FeaturesConfig setDistributedDynamicFilterMinProbeSize(DataSize distributedDynamicFilterMinProbeSize)
{
    this.distributedDynamicFilterMinProbeSize = distributedDynamicFilterMinProbeSize;
    return this;
}

public long getDistributedDynamicFilterMaxBuildNdv()
{
    return distributedDynamicFilterMaxBuildNdv;
}

@Config("distributed-dynamic-filter.max-build-ndv")
@ConfigDescription("Maximum estimated build-side NDV for the join key to create a distributed dynamic filter (cost-based mode)")
public FeaturesConfig setDistributedDynamicFilterMaxBuildNdv(long distributedDynamicFilterMaxBuildNdv)
{
    this.distributedDynamicFilterMaxBuildNdv = distributedDynamicFilterMaxBuildNdv;
    return this;
}
```

##### 2. `SystemSessionProperties.java` — Add 2 new session properties
**Path**: `presto-main-base/src/main/java/com/facebook/presto/SystemSessionProperties.java`

Add constants:
```java
public static final String DISTRIBUTED_DYNAMIC_FILTER_MIN_PROBE_SIZE = "distributed_dynamic_filter_min_probe_size";
public static final String DISTRIBUTED_DYNAMIC_FILTER_MAX_BUILD_NDV = "distributed_dynamic_filter_max_build_ndv";
```

Add property definitions and static accessors `getDistributedDynamicFilterMinProbeSize()`, `getDistributedDynamicFilterMaxBuildNdv()`.

##### 3. `AddDynamicFilterRule.java` — Implement cost-based logic
**Path**: `presto-main-base/src/main/java/com/facebook/presto/sql/planner/iterative/rule/AddDynamicFilterRule.java`

Add `statsSource` field + `getStatsSource()` override. Replace `apply()`:

```java
@Override
public Result apply(JoinNode node, Captures captures, Context context)
{
    Session session = context.getSession();

    if (!isCostBased(session)) {
        // ALWAYS mode: add filters to every equi-join clause
        return addAllFilters(node, context);
    }

    // COST_BASED mode: use stats to decide
    PlanNode probeSide = node.getLeft();
    PlanNodeStatsEstimate probeStats = context.getStatsProvider().getStats(probeSide);
    statsSource = probeStats.getSourceInfo().getSourceInfoName();

    // Gate: require known probe-side stats
    double probeSizeBytes = probeStats.getOutputSizeInBytes(probeSide);
    if (isNaN(probeSizeBytes)) {
        return Result.empty();
    }

    // Check probe-side size threshold
    if (probeSizeBytes < getDistributedDynamicFilterMinProbeSize(session).toBytes()) {
        return Result.empty();
    }

    // Per-clause evaluation
    PlanNode buildSide = node.getRight();
    PlanNodeStatsEstimate buildStats = context.getStatsProvider().getStats(buildSide);
    long maxBuildNdv = getDistributedDynamicFilterMaxBuildNdv(session);

    Map<String, VariableReferenceExpression> dynamicFilters = new LinkedHashMap<>();
    for (EquiJoinClause clause : node.getCriteria()) {
        VariableStatsEstimate varStats = buildStats.getVariableStatistics(clause.getRight());
        double ndv = varStats.getDistinctValuesCount();
        // Require known NDV; skip if unknown or too high
        if (isNaN(ndv) || ndv > maxBuildNdv) {
            continue;
        }
        String filterId = context.getIdAllocator().getNextId().toString();
        dynamicFilters.put(filterId, clause.getRight());
    }

    if (dynamicFilters.isEmpty()) {
        return Result.empty();
    }

    return Result.ofPlanNode(new JoinNode(/* ... same constructor args with dynamicFilters ... */));
}
```

##### 4. `AddDynamicFilterToSemiJoinRule.java` — Same pattern for semi-joins
Same two-level check (probe size + build NDV), simpler since semi-joins have exactly one clause.

##### 5. `TestFeaturesConfig.java` — Add new config properties to test

##### 6. `TestAddDynamicFilterRule.java` — New unit test file
Follow `TestDetermineJoinDistributionType` pattern. Key test cases:
- ALWAYS strategy adds filters regardless of stats
- COST_BASED with unknown stats → skips filters (no stats = no DPP)
- COST_BASED with small probe (below threshold) → skips all filters
- COST_BASED with large probe + low build NDV → creates filters
- COST_BASED with large probe + high build NDV → skips that clause
- Multi-clause with mixed NDV → keeps low-NDV, skips high-NDV
- COST_BASED with large probe + unknown build NDV → skips
- Custom threshold overrides via session properties

##### 7. `TestAddDynamicFilterToSemiJoinRule.java` — New unit test file

#### Edge Case Behavior

| Scenario | COST_BASED Behavior |
|---|---|
| Both stats unknown (NaN) | Skip — no evidence of benefit |
| Known small probe (< threshold) | Skip all filters |
| Known large probe + known low NDV | Create filter |
| Known large probe + unknown NDV | Skip — no per-column stats |
| Known large probe + known high NDV | Skip that clause |
| Unknown probe + any build stats | Skip — no probe size evidence |
| Multi-clause, mixed NDV | Per-clause: keep low-NDV, skip high-NDV |
| ALWAYS strategy (any stats) | All filters, ignore stats |

#### Build & Verification

```bash
# Build affected module
./mvnw clean install -DskipTests -Dair.check.skip-all=true -pl presto-main-base

# Run new unit tests
./mvnw test -pl presto-main-base -Dtest=TestAddDynamicFilterRule -Dair.check.skip-all=true
./mvnw test -pl presto-main-base -Dtest=TestAddDynamicFilterToSemiJoinRule -Dair.check.skip-all=true
./mvnw test -pl presto-main-base -Dtest=TestFeaturesConfig -Dair.check.skip-all=true

# Verify existing DPP tests still pass (they use ALWAYS strategy)
./mvnw test -pl presto-iceberg -Dtest=TestDynamicPartitionPruning -Dair.check.skip-all=true
```

#### Reference Files
- `DetermineJoinDistributionType.java` — canonical pattern for cost-based rule decisions
- `Rule.java` — `Context` interface with `getStatsProvider()`
- `PlanNodeStatsEstimate.java` — stats data structure
- `VariableStatsEstimate.java` — per-column stats (NDV, nullsFraction, etc.)
- `TestDetermineJoinDistributionType.java` — test pattern to follow