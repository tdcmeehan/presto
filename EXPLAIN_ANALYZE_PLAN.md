# EXPLAIN ANALYZE: Inline Dynamic Filter Domain Display

## Goal

Show collected dynamic filter domains inline in EXPLAIN ANALYZE output at JoinNode locations, so users can see what values each dynamic filter actually collected during query execution.

**Expected output:**
```
- InnerJoin[...][customer_id_0 = customer_id]
    Distribution: PARTITIONED
    dynamicFilterAssignments = {customer_id_0 -> 549}
    collectedDynamicFilterDomains = {549: {customer_id = [1, 2, 3]}, complete}
```

## Design Decisions

1. **Display at JoinNode** (not TableScan) — For DPP cross-fragment joins, probe-side `DynamicFilterPlaceholder` expressions are stripped by `RemoveUnsupportedDynamicFilters`, so we can't show at FilterNode/TableScan. The JoinNode always has `getDynamicFilters()` entries.

2. **Snapshot map, not service reference** — Build a `Map<String, DynamicFilterDomainStats>` snapshot in `ExplainAnalyzeOperator.getOutput()` rather than threading `DynamicFilterService` to PlanPrinter. Keeps PlanPrinter decoupled from service lifecycle.

3. **Thread `DynamicFilterService` via `ExplainAnalyzeContext`** — It's already a coordinator-only context object injected into `LocalExecutionPlanner`.

## Changes

### 1. New class: `DynamicFilterDomainStats`
**File**: `presto-main-base/.../sql/planner/planPrinter/DynamicFilterDomainStats.java` (NEW)

Simple immutable POJO carrying a snapshot of one dynamic filter's collected state:
- `String filterId`
- `TupleDomain<String> collectedDomain` (from `CoordinatorDynamicFilter.getCurrentConstraintByColumnName()`)
- `boolean complete` (from `CoordinatorDynamicFilter.isComplete()`)

### 2. Add `DynamicFilterService` to `ExplainAnalyzeContext`
**File**: `presto-main-base/.../execution/ExplainAnalyzeContext.java`

Add `Optional<DynamicFilterService>` field with a two-arg constructor. Keep the existing single-arg constructor for backward compatibility (passes `Optional.empty()`).

```java
private final Optional<DynamicFilterService> dynamicFilterService;

@Inject
public ExplainAnalyzeContext(
        QueryPerformanceFetcher queryPerformanceFetcher,
        DynamicFilterService dynamicFilterService)
{
    this(queryPerformanceFetcher, Optional.of(dynamicFilterService));
}

public ExplainAnalyzeContext(QueryPerformanceFetcher queryPerformanceFetcher)
{
    this(queryPerformanceFetcher, Optional.empty());
}

private ExplainAnalyzeContext(
        QueryPerformanceFetcher queryPerformanceFetcher,
        Optional<DynamicFilterService> dynamicFilterService) { ... }
```

No Guice wiring changes needed: `DynamicFilterService` is already bound as a singleton in `ServerMainModule` (line 710). Guice will inject both dependencies.

**Spark compatibility**: Update `PrestoSparkModule` (line 527) to use the single-arg constructor (it already does — `new ExplainAnalyzeContext(queryPerformanceFetcher)`).

### 3. Thread through `ExplainAnalyzeOperator`
**File**: `presto-main-base/.../operator/ExplainAnalyzeOperator.java`

Add `Optional<DynamicFilterService> dynamicFilterService` to both `ExplainAnalyzeOperatorFactory` and `ExplainAnalyzeOperator`.

In `getOutput()`, build the domain snapshot and pass to new overload:
```java
Map<String, DynamicFilterDomainStats> dynamicFilterDomains = ImmutableMap.of();
if (dynamicFilterService.isPresent()) {
    QueryId queryId = operatorContext.getDriverContext().getTaskId().getQueryId();
    ImmutableMap.Builder<String, DynamicFilterDomainStats> builder = ImmutableMap.builder();
    for (Map.Entry<String, CoordinatorDynamicFilter> entry :
            dynamicFilterService.get().getAllFiltersForQuery(queryId).entrySet()) {
        CoordinatorDynamicFilter filter = entry.getValue();
        builder.put(entry.getKey(), new DynamicFilterDomainStats(
                entry.getKey(),
                filter.getCurrentConstraintByColumnName(),
                filter.isComplete()));
    }
    dynamicFilterDomains = builder.build();
}
plan = textDistributedPlan(..., dynamicFilterDomains);
```

### 4. Pass from `LocalExecutionPlanner` to operator factory
**File**: `presto-main-base/.../sql/planner/LocalExecutionPlanner.java`

In `visitExplainAnalyze()` (line 1007), pass the `DynamicFilterService` from `ExplainAnalyzeContext`:
```java
OperatorFactory operatorFactory = new ExplainAnalyzeOperatorFactory(
        context.getNextOperatorId(),
        node.getId(),
        analyzeContext.getQueryPerformanceFetcher(),
        metadata.getFunctionAndTypeManager(),
        node.isVerbose(),
        node.getFormat(),
        analyzeContext.getDynamicFilterService());
```

### 5. Thread through `PlanPrinter` method chain
**File**: `presto-main-base/.../sql/planner/planPrinter/PlanPrinter.java`

**a)** Add `Map<String, DynamicFilterDomainStats> dynamicFilterDomains` field to `PlanPrinter` class (default `ImmutableMap.of()` in existing constructor).

**b)** New private constructor overload that accepts the map:
```java
private PlanPrinter(...existing params..., Map<String, DynamicFilterDomainStats> dynamicFilterDomains)
```
Existing constructor delegates with `ImmutableMap.of()`.

**c)** New `textDistributedPlan` overload (public static):
```java
public static String textDistributedPlan(
        StageInfo outputStageInfo,
        FunctionAndTypeManager functionAndTypeManager,
        Session session,
        boolean verbose,
        Map<String, DynamicFilterDomainStats> dynamicFilterDomains)
```
Existing 4-arg overload delegates with `ImmutableMap.of()`.

**d)** Update private `formatFragment` to accept `Map<String, DynamicFilterDomainStats>`:
```java
private static String formatFragment(..., Map<String, DynamicFilterDomainStats> dynamicFilterDomains)
```
Existing callers pass `ImmutableMap.of()`.

**e)** New `textLogicalPlan` overload that accepts the map, called from `formatFragment`:
```java
public static String textLogicalPlan(...existing params..., Map<String, DynamicFilterDomainStats> dynamicFilterDomains)
```

**f)** `Visitor` inner class gets `dynamicFilterDomains` field (set from `PlanPrinter`'s field in constructor at line 220).

### 6. Display in `visitJoin` and `visitSemiJoin`
**File**: `presto-main-base/.../sql/planner/planPrinter/PlanPrinter.java`

In `visitJoin()` (after line 557) and `visitSemiJoin()` (after line 593), append collected domains:

```java
if (!node.getDynamicFilters().isEmpty()) {
    nodeOutput.appendDetails(getDynamicFilterAssignments(node));
    // NEW: show collected domains in EXPLAIN ANALYZE
    if (!dynamicFilterDomains.isEmpty()) {
        appendCollectedDynamicFilterDomains(nodeOutput, node);
    }
}
```

New helper method in `Visitor`:
```java
private void appendCollectedDynamicFilterDomains(NodeRepresentation nodeOutput, AbstractJoinNode node)
{
    StringBuilder sb = new StringBuilder("collectedDynamicFilterDomains = {");
    boolean first = true;
    for (Map.Entry<String, VariableReferenceExpression> entry : node.getDynamicFilters().entrySet()) {
        String filterId = entry.getKey();
        DynamicFilterDomainStats domainStats = dynamicFilterDomains.get(filterId);
        if (domainStats == null) {
            continue;
        }
        if (!first) { sb.append(", "); }
        first = false;
        sb.append(filterId).append(": ");
        sb.append(formatCollectedDomain(domainStats.getCollectedDomain()));
        sb.append(domainStats.isComplete() ? ", complete" : ", pending");
    }
    sb.append("}");
    if (!first) {  // Only append if we had at least one domain
        nodeOutput.appendDetailsLine("%s", sb.toString());
    }
}
```

### 7. `formatCollectedDomain` helper
**File**: `presto-main-base/.../sql/planner/planPrinter/PlanPrinter.java`

Uses the existing `formatDomain(Domain)` method (line 1588) which already handles ranges, discrete values, and null:

```java
private String formatCollectedDomain(TupleDomain<String> tupleDomain)
{
    if (tupleDomain.isAll()) {
        return "{ALL}";
    }
    if (tupleDomain.isNone()) {
        return "{NONE}";
    }
    return tupleDomain.getDomains().get().entrySet().stream()
            .map(e -> e.getKey() + " = " + formatDomain(e.getValue().simplify()))
            .collect(Collectors.joining(", ", "{", "}"));
}
```

`Domain.simplify()` collapses large IN-lists (>32 values) to range spans for readability.

## Files Changed Summary

| File | Change |
|------|--------|
| `presto-main-base/.../planPrinter/DynamicFilterDomainStats.java` | **NEW** — snapshot POJO |
| `presto-main-base/.../execution/ExplainAnalyzeContext.java` | Add `Optional<DynamicFilterService>` field |
| `presto-main-base/.../operator/ExplainAnalyzeOperator.java` | Thread service, build snapshot in `getOutput()` |
| `presto-main-base/.../sql/planner/LocalExecutionPlanner.java` | Pass service from context to operator factory |
| `presto-main-base/.../sql/planner/planPrinter/PlanPrinter.java` | Thread domain map through method chain, display in `visitJoin`/`visitSemiJoin` |
| `presto-spark-base/.../spark/PrestoSparkModule.java` | No change needed (single-arg constructor still works) |

## Verification

```bash
# Compile
./mvnw test-compile -pl presto-main-base -Dair.check.skip-all=true

# Existing unit tests still pass
./mvnw test -pl presto-main-base -Dtest=TestCoordinatorDynamicFilter -Dair.check.skip-all=true
./mvnw test -pl presto-main-base -Dtest=TestDynamicFilterService -Dair.check.skip-all=true

# Integration test - verify domain display in EXPLAIN ANALYZE
./mvnw test -pl presto-iceberg -Dtest=TestDynamicPartitionPruning -Dair.check.skip-all=true

# Verify RemoveUnsupportedDynamicFilters tests
./mvnw test -pl presto-main-base -Dtest=TestRemoveUnsupportedDynamicFilters -Dair.check.skip-all=true
```

For manual verification, add an assertion in `TestDynamicPartitionPruning` that runs `EXPLAIN ANALYZE` on an existing test query and asserts the output contains `collectedDynamicFilterDomains`.