# Multi-Join Dynamic Partition Pruning Bug Investigation

## Summary

Three-way join query with DPP returns incorrect results (20 or 0 rows instead of 30). The bug is in how `expectedPartitions` is set for dynamic filters in multi-join queries.

## Reproduction

```sql
-- Setup: dim_active_regions has only 'WEST' region
-- dim_customers has customers 1-3 as WEST, 4-10 as EAST
-- fact_orders has 10 orders per customer (100 total), partitioned by customer_id

SELECT f.order_id, f.amount, c.customer_name
FROM fact_orders f
JOIN dim_customers c ON f.customer_id = c.customer_id
JOIN dim_active_regions r ON c.region = r.region_name
ORDER BY f.order_id

-- Expected: 30 rows (3 WEST customers × 10 orders)
-- With DPP: 0-20 rows (varies by run)
-- Without DPP: 30 rows (correct)
```

Test: `TestDynamicPartitionPruning#testMultiJoinDynamicPartitionPruning`

## Root Cause Analysis

### The Problem

Two dynamic filters are created:
- **Filter 797** (`region`): From `dim_customers JOIN dim_active_regions` — filters `dim_customers.region`
- **Filter 798** (`customer_id`): From `fact_orders JOIN dim_customers` — filters `fact_orders.customer_id`

Both filters get `setExpectedPartitions(4)` even though they're produced by different stages with different task counts.

### Evidence from Logs

```
setExpectedPartitionsForFilters: rootNodeId=985 rootClass=ProjectNode taskCount=4
setExpectedPartitionsForFilters: filterId=798 taskCount=4 joinNodeId=4

setExpectedPartitionsForFilters: rootNodeId=8 rootClass=JoinNode taskCount=4
setExpectedPartitionsForFilters: filterId=797 taskCount=4 joinNodeId=8
```

But the actual task contributions differ:
- Filter 797 receives data from **1 task** (stage 2): `20260205_211304_00041_zp2k9.2.0.1.0`
- Filter 798 receives data from **4 tasks** (stage 3): `3.0.0.0`, `3.0.1.0`, `3.0.2.0`, `3.0.3.0`

### What Goes Wrong

1. Filter 798 expects 4 partitions, receives 4 → completes correctly
2. Filter 797 expects 4 partitions, receives 1 → should NOT complete
3. But `isComplete=true` for both filters when pushed!

The filter is completing early because of the `operatorCompleted` handling in `DynamicFilterFetcher`:

```java
// DynamicFilterFetcher.java lines 207-216
else if (response.isOperatorCompleted() && operatorCompletionHandled.compareAndSet(false, true)) {
    // When a task completes with no filter data, add TupleDomain.none() to ALL filters
    for (CoordinatorDynamicFilter filter : dynamicFilterService.getAllFiltersForQuery(queryId).values()) {
        filter.addPartitionByFilterId(TupleDomain.none());  // BUG: affects ALL filters!
    }
}
```

When tasks in one stage complete without producing filter data (because they don't have a DynamicFilterSourceOperator for that filter), this code adds `TupleDomain.none()` to ALL filters — not just the filters that task was responsible for.

### The Data Corruption

The `customer_id` filter ends up with wrong values:
```
Pushing DF column=customer_id ranges=[[9..9], [10..10]]  // EAST customers!
```

But WEST customers are 1, 2, 3. The filter has data for customers 9 and 10 because:
1. The filter completes early (before all real data arrives)
2. It returns partial data from tasks that happened to report first
3. The all-or-nothing safety we implemented checks `fullyResolved`, but `fullyResolved` is being set incorrectly

## Bug Chain

1. **`setExpectedPartitionsForFilters`** searches for JoinNodes in a fragment's plan tree
2. A fragment may contain multiple JoinNodes (if the optimizer didn't split them into separate stages)
3. All JoinNodes found get the SAME `expectedPartitions` count (the current fragment's task count)
4. But different JoinNodes' filters are produced by DIFFERENT child stages with different task counts
5. **Result**: Filter A expects N partitions but its producer stage has M tasks (M ≠ N)

Additionally:

6. **`DynamicFilterFetcher.operatorCompleted`** adds `TupleDomain.none()` to ALL filters when ANY task's operator completes
7. This causes filters to receive extra "empty" partitions they shouldn't
8. Combined with wrong expectedPartitions, filters complete early with partial/wrong data

## Files Involved

- `SectionExecutionFactory.setExpectedPartitionsForFilters()` — sets wrong expectedPartitions
- `DynamicFilterFetcher.success()` — operatorCompleted affects all filters
- `CoordinatorDynamicFilter` — the all-or-nothing safety is correct but relies on correct expectedPartitions

## Proposed Fixes

### Option 1: Track filter-to-stage mapping

Maintain a mapping of `filterId → stageId` when filters are registered. When setting expectedPartitions, only set for filters that belong to the current stage.

### Option 2: Task-level filter ID tracking

Have each task report which filter IDs it's responsible for. The `operatorCompleted` path should only add partitions to those specific filters.

### Option 3: Fix the search scope

Change `PlanNodeSearcher.searchFrom(root)` in `setExpectedPartitionsForFilters` to NOT traverse into JoinNodes that will be processed by child stages. This requires understanding the fragment boundary.

## Current State

- Debug logging added to trace filter ID, expectedPartitions, and domain values
- All-or-nothing safety (`fullyResolved`) is implemented but relies on correct expectedPartitions
- Test `testMultiJoinDynamicPartitionPruning` reproduces the bug

## To Resume

1. Run `./mvnw test -pl presto-iceberg -Dtest=TestDynamicPartitionPruning#testMultiJoinDynamicPartitionPruning -Dair.check.skip-all=true`
2. Check logs for `setExpectedPartitionsForFilters`, `DynamicFilterFetcher`, and `Pushing DF` messages
3. Focus on fixing `setExpectedPartitionsForFilters` to use the correct task count per filter
4. Also fix `DynamicFilterFetcher.operatorCompleted` to only affect relevant filters

## Debug Logging Locations

- `SectionExecutionFactory.setExpectedPartitionsForFilters()` — logs rootNodeId, taskCount, filterId
- `CoordinatorDynamicFilter.setExpectedPartitions()` — logs filterId, expected, currentSize
- `DynamicFilterFetcher.success()` — logs taskId, filterId, domain details
- `IcebergSplitSource.initializeScanWithDynamicFilter()` — logs isComplete, filterId, column, ranges