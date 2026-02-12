# Plan: Extract Dynamic Filter Creation into AddDynamicFilterRule

## Goal
Refactor dynamic filter creation from `PredicatePushDown` into a new `AddDynamicFilterRule` that runs after `ReorderJoins`. Initially, the rule will "always add DFs" (no cost model), matching current behavior.

Also: rename the DPP property and make the two modes mutually exclusive.

## Key Design Decisions

### Two Dynamic Filtering Modes (Renamed for Clarity)
1. **Built-in DF** (`enable_dynamic_filtering`): Local, within-fragment, row-level filtering via probe-side FilterNode
2. **Distributed DF** (`distributed_dynamic_filter_enabled`): Cross-fragment, coordinator-side, split-level pruning

**Renaming**: `dynamic_filter_enabled` → `distributed_dynamic_filter_enabled`

**Mutual Exclusivity**: Add validation to throw if both are enabled simultaneously

### Approach: Distributed DF Only (Confirmed)
For this refactoring, focus on **Distributed DF mode only**:
- New rule populates `JoinNode.dynamicFilters` map
- No probe-side predicates needed (SplitSourceFactory reads from JoinNode directly)
- Keep built-in DF logic in PredicatePushDown unchanged for now

This keeps the refactoring smaller and testable. Built-in DF migration can be done later if desired.

---

## Part 1: Rename Property and Add Mutual Exclusivity

### Files to Modify for Renaming

**1. `SystemSessionProperties.java`** (`presto-main-base/.../SystemSessionProperties.java`)
```java
// Rename constant and method
public static final String DISTRIBUTED_DYNAMIC_FILTER_ENABLED = "distributed_dynamic_filter_enabled";

public static boolean isDistributedDynamicFilterEnabled(Session session) {
    return session.getSystemProperty(DISTRIBUTED_DYNAMIC_FILTER_ENABLED, Boolean.class);
}
```

**2. `FeaturesConfig.java`** (`presto-main-base/.../sql/analyzer/FeaturesConfig.java`)
```java
// Rename property and add mutual exclusivity validation
private boolean distributedDynamicFilterEnabled = false;

@Config("distributed-dynamic-filter.enabled")
public FeaturesConfig setDistributedDynamicFilterEnabled(boolean value) {
    this.distributedDynamicFilterEnabled = value;
    return this;
}

// Add validation
@PostConstruct
public void validate() {
    if (enableDynamicFiltering && distributedDynamicFilterEnabled) {
        throw new IllegalStateException(
            "Cannot enable both 'enable_dynamic_filtering' and 'distributed_dynamic_filter_enabled'. Choose one.");
    }
}
```

**3. Update all references** (grep for `isDynamicFilterEnabled` and `dynamic_filter_enabled`):
- `PredicatePushDown.java`
- `LocalExecutionPlanner.java`
- `SplitSourceFactory.java`
- `RemoveUnsupportedDynamicFilters.java`
- `DynamicFiltersChecker.java`
- `DynamicFilterService.java`
- `SectionExecutionFactory.java`
- Test files

---

## Part 2: Create AddDynamicFilterRule

## Files to Create

### 1. `AddDynamicFilterRule.java`
**Path**: `presto-main-base/src/main/java/com/facebook/presto/sql/planner/iterative/rule/AddDynamicFilterRule.java`

```java
public class AddDynamicFilterRule implements Rule<JoinNode> {

    private static final Pattern<JoinNode> PATTERN = join()
            .matching(node ->
                (node.getType() == INNER || node.getType() == RIGHT)
                && node.getDynamicFilters().isEmpty());

    @Override
    public boolean isEnabled(Session session) {
        return isDistributedDynamicFilterEnabled(session);  // Distributed DF only
    }

    @Override
    public Result apply(JoinNode node, Captures captures, Context context) {
        // Generate filter ID for each equi-join clause
        Map<String, VariableReferenceExpression> dynamicFilters = new LinkedHashMap<>();
        for (EquiJoinClause clause : node.getCriteria()) {
            String id = context.getIdAllocator().getNextId().toString();
            dynamicFilters.put(id, clause.getRight());  // build-side variable
        }

        if (dynamicFilters.isEmpty()) {
            return Result.empty();
        }

        return Result.ofPlanNode(new JoinNode(..., dynamicFilters));
    }
}
```

### 2. `AddDynamicFilterToSemiJoinRule.java`
**Path**: `presto-main-base/src/main/java/com/facebook/presto/sql/planner/iterative/rule/AddDynamicFilterToSemiJoinRule.java`

Similar pattern for SemiJoinNode (simpler - single join clause).

---

## Files to Modify

### 1. `PlanOptimizers.java`
**Path**: `presto-main-base/src/main/java/com/facebook/presto/sql/planner/PlanOptimizers.java`

Add new rules after ReorderJoins (~line 869):

```java
// After line 869 (HistoricalStatisticsEquivalentPlanMarkingOptimizer)
builder.add(new IterativeOptimizer(
        metadata,
        ruleStats,
        statsCalculator,
        estimatedExchangesCostCalculator,
        ImmutableSet.of(
                new AddDynamicFilterRule(metadata.getFunctionAndTypeManager()),
                new AddDynamicFilterToSemiJoinRule(metadata.getFunctionAndTypeManager()))));
```

### 2. `PredicatePushDown.java`
**Path**: `presto-main-base/src/main/java/com/facebook/presto/sql/planner/optimizations/PredicatePushDown.java`

Skip distributed DF filter creation if already populated by the rule:

```java
// Line 566-572: Modify to skip distributed DF if dynamicFilters already populated
boolean dynamicFilterEnabled = isEnableDynamicFiltering() || isDistributedDynamicFilterEnabled();
Map<String, VariableReferenceExpression> dynamicFilters = node.getDynamicFilters();
if (isEnableDynamicFiltering() && dynamicFilters.isEmpty()) {
    // Only create for built-in DF mode, distributed DF handled by AddDynamicFilterRule
    DynamicFiltersResult dynamicFiltersResult = createDynamicFilters(...);
    dynamicFilters = dynamicFiltersResult.getDynamicFilters();
    leftPredicate = logicalRowExpressions.combineConjuncts(leftPredicate, ...);
}
```

Similar change in `visitSemiJoin()` (~line 1640).

---

## Verification

### Tests to Run
```bash
# Unit tests for planning
./mvnw test -pl presto-main-base -Dtest=TestDynamicFilter -Dair.check.skip-all=true
./mvnw test -pl presto-main-base -Dtest=TestRemoveUnsupportedDynamicFilters -Dair.check.skip-all=true
./mvnw test -pl presto-main-base -Dtest=TestDynamicFiltersChecker -Dair.check.skip-all=true

# Integration tests
./mvnw test -pl presto-iceberg -Dtest=TestDynamicPartitionPruning -Dair.check.skip-all=true
./mvnw test -pl presto-iceberg -Dtest=TestDynamicPartitionPruningPlan -Dair.check.skip-all=true
```

### New Tests to Add
- `TestAddDynamicFilterRule.java` - Unit tests for the new rule
- Verify pattern matching (INNER/RIGHT joins only)
- Verify filter ID generation
- Verify dynamicFilters map populated correctly

---

## Sequencing

### Phase A: Rename and Add Mutual Exclusivity
1. Rename `dynamic_filter_enabled` → `distributed_dynamic_filter_enabled` in `FeaturesConfig.java`
2. Rename `DYNAMIC_FILTER_ENABLED` → `DISTRIBUTED_DYNAMIC_FILTER_ENABLED` in `SystemSessionProperties.java`
3. Rename method `isDynamicFilterEnabled` → `isDistributedDynamicFilterEnabled`
4. Add mutual exclusivity validation in `FeaturesConfig.validate()`
5. Update all call sites (grep for old names)
6. Run tests to verify

### Phase B: Create AddDynamicFilterRule
1. Create `AddDynamicFilterRule.java` with basic implementation
2. Create `AddDynamicFilterToSemiJoinRule.java`
3. Register rules in `PlanOptimizers.java` after ReorderJoins
4. Modify `PredicatePushDown.java` to skip distributed DF filter creation
5. Run existing tests to verify no regressions
6. Add unit tests for new rules

---

## Notes

**Optimizer flow**:
- PredicatePushDown runs BEFORE ReorderJoins (sees empty dynamicFilters for distributed DF)
- ReorderJoins may change join structure
- AddDynamicFilterRule runs AFTER ReorderJoins, populates dynamicFilters based on final join order
- For distributed DF mode, PredicatePushDown skips filter creation (rule handles it)
- For built-in DF mode, PredicatePushDown continues to handle it (unchanged)

**Property names after refactoring**:
- `enable_dynamic_filtering` / `isEnableDynamicFiltering()` - built-in, local DF (unchanged)
- `distributed_dynamic_filter_enabled` / `isDistributedDynamicFilterEnabled()` - coordinator, split-level DF

---

## Future Work (Cost Model)
Once this refactoring is validated, the rule can be enhanced to:
- Check selectivity estimates from `context.getStatsProvider()`
- Skip filters on high-cardinality joins
- Implement transitive propagation through equality chains