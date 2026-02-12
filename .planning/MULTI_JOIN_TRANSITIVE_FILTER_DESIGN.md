# Multi-Join Transitive Dynamic Filter Design

## Problem Statement

In a multi-join query with DPP enabled, the dynamic filter collects values from the **unfiltered** dimension table instead of the **transitively-filtered** result.

### Example Query

```sql
SELECT f.order_id, f.amount, c.customer_name
FROM fact_orders f
JOIN dim_customers c ON f.customer_id = c.customer_id
JOIN dim_active_regions r ON c.region = r.region_name
ORDER BY f.order_id
```

### Data Setup

- `dim_active_regions`: Only 'WEST' region
- `dim_customers`: Customers 1-3 are WEST, 4-10 are EAST
- `fact_orders`: 10 orders per customer, partitioned by customer_id

### Expected Behavior

1. Filter 797 (region) collects 'WEST' from dim_active_regions
2. Join with dim_customers produces only WEST customers (1, 2, 3)
3. Filter 798 (customer_id) collects {1, 2, 3} from the filtered result
4. fact_orders is pruned to only customer_id partitions 1, 2, 3

### Actual Behavior

1. Filter 797 (region) collects 'WEST' ✓
2. Filter 798 (customer_id) collects {1-10} ✗ (all customers, not just WEST)
3. No pruning of fact_orders partitions

### Root Cause

The `DynamicFilterSourceOperator` for filter 798 is collecting values from `dim_customers` **before** the join with `dim_active_regions`, not from the filtered result.

## Analysis

### Current Plan Structure

Based on debug logs:

```
Fragment A (root=ProjectNode 985):
  ProjectNode
    JoinNode 4 (f.customer_id = c.customer_id) [Filter 798]
      Probe: TableScan(fact_orders)
      Build: RemoteSourceNode [from Fragment B]

Fragment B (root=JoinNode 8):
  JoinNode 8 (c.region = r.region_name) [Filter 797]
    Probe: TableScan(dim_customers)
    Build: RemoteSourceNode [from Fragment C]

Fragment C:
  TableScan(dim_active_regions)
```

### DynamicFilterSourceOperator Placement

In `LocalExecutionPlanner.visitJoin()`:

1. Build pipeline is created by visiting the build node
2. `DynamicFilterSourceOperator` is added to wrap the build pipeline output
3. For JoinNode 4, the build side is `RemoteSourceNode` (data from Fragment B)

The `RemoteSourceNode` should receive the **output** of JoinNode 8 (filtered customers 1-3), not raw dim_customers.

### Why Filter 798 Sees All Customers

Two possible causes:

**Hypothesis 1: Join Order Issue**
The optimizer chose join order `(f JOIN c) JOIN r` instead of `f JOIN (c JOIN r)`:
- First join: fact_orders with dim_customers (unfiltered)
- Filter 798 collects from unfiltered dim_customers
- Then join with dim_active_regions

**Hypothesis 2: Fragment Boundary Issue**
The plan splits at the wrong point:
- Fragment B outputs dim_customers directly (before join with dim_active_regions)
- Fragment A does both joins
- Filter 798 collects from the unfiltered input

## Proposed Solutions

### Solution 1: Optimizer Join Reordering (Recommended)

Modify the optimizer to prefer join orders that maximize DPP effectiveness.

**Approach:**
1. In `JoinReorderingOptimizer` or a new rule, detect when a multi-join has chained DPP opportunities
2. Prefer ordering where:
   - Smaller/more-selective dimension tables are joined first
   - Dynamic filters collect from already-filtered intermediate results

**Changes:**
- Add new cost factor for "DPP chain effectiveness" in `CostComparator`
- Modify `ReorderJoins` rule to consider DPP benefits

**Pros:** Clean solution, benefits all multi-join queries automatically
**Cons:** Complex cost model changes, potential plan regressions

### Solution 2: Dynamic Filter Propagation Rule

Add an optimizer rule that propagates dynamic filters through join chains.

**Approach:**
1. After `PredicatePushDown` creates dynamic filters, add a new rule
2. For each dynamic filter on column C:
   - Trace C through the plan
   - If C passes through another join, propagate the filter through that join
   - Create a "derived" filter constraint

**Example:**
```
Original: fact_orders JOIN (dim_customers JOIN dim_active_regions)
          Filter 798 on customer_id, Filter 797 on region

After propagation:
          Filter 798 on customer_id WHERE region IN (dynamic filter 797)
```

**Changes:**
- New rule: `PropagateDynamicFiltersThroughJoins`
- Modify `CoordinatorDynamicFilter` to support "dependent filters"

**Pros:** Doesn't require join reordering
**Cons:** Complex filter dependency tracking

### Solution 3: Fragment Boundary Adjustment

Ensure dynamic filters are collected at the correct fragment boundary.

**Approach:**
1. In `SplitSourceFactory` or `SectionExecutionFactory`, verify that DynamicFilterSourceOperator collects from the correct pipeline stage
2. If a filter's source column passes through a join in the same fragment, ensure the operator is placed AFTER the join

**Changes:**
- Modify `LocalExecutionPlanner.visitJoin()` to track filter placement
- Add validation that filters collect from post-join output

**Pros:** Minimal optimizer changes
**Cons:** May not cover all cases

### Solution 4: Delayed Filter Collection Point

Collect dynamic filters at the **consumer** stage rather than the producer stage.

**Approach:**
1. Instead of collecting filter 798 in Fragment B's DynamicFilterSourceOperator
2. Collect it at the RemoteSourceNode in Fragment A, after data arrives from Fragment B
3. This guarantees the data has already been filtered by upstream joins

**Changes:**
- New operator: `RemoteSourceDynamicFilterOperator`
- Modify `DynamicFilterService` to support remote-source-based collection

**Pros:** Guarantees correct data is collected
**Cons:** Increases latency (filter available later)

## Recommended Approach

Start with **Solution 3 (Fragment Boundary Adjustment)** as it's the most targeted fix:

### Implementation Plan

1. **Investigate the actual plan structure**
   - Add EXPLAIN (TYPE DISTRIBUTED) output to the test
   - Confirm which fragment produces which output
   - Verify where DynamicFilterSourceOperator is placed

2. **Fix operator placement in LocalExecutionPlanner**
   - In `visitJoin()`, track whether the build pipeline contains joins
   - Ensure DynamicFilterSourceOperator wraps the FINAL output, not intermediate

3. **Add test cases**
   - Multi-join with chained DPP
   - Verify filter values contain only transitively-filtered data

### Debugging Commands

```java
// Add to test to see distributed plan
String explain = (String) queryRunner.execute(session,
    "EXPLAIN (TYPE DISTRIBUTED) " + query).getOnlyValue();
System.out.println(explain);
```

```java
// Add logging to LocalExecutionPlanner.visitJoin()
log.info("visitJoin nodeId=%s buildSourceType=%s dynamicFilters=%s",
    node.getId(),
    node.getBuild().getClass().getSimpleName(),
    node.getDynamicFilters());
```

## Files to Investigate

1. `LocalExecutionPlanner.java` - Where DynamicFilterSourceOperator is created
2. `PredicatePushDown.java` - Where dynamic filters are planned
3. `ReorderJoins.java` - Join ordering optimization
4. `PlanFragmenter.java` - How plan is split into fragments

## Success Criteria

1. Filter 798 collects only {1, 2, 3} (WEST customers)
2. fact_orders partitions are pruned from 10 → 3
3. `testMultiJoinDynamicPartitionPruning` passes fully

## Related Files

- `.planning/MULTI_JOIN_BUG_INVESTIGATION.md` - Original investigation
- `memory/MEMORY.md` - Project notes including filter completion fix