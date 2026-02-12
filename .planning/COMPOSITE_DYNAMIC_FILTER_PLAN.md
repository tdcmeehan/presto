# Composite Dynamic Filter Support

## Prior Art: Trino's Implementation

Trino supports composite filters with progressive resolution. Their architecture:

- **Heavy state tracking**: `DynamicFilterCollectionContext` per filter ID (tracks partitions, completion, timing)
- **Central storage**: All contexts stored in `DynamicFilterContext` map
- **Lightweight DynamicFilter**: Queries the context for each filter ID, intersects results

Our architecture is equivalent:
- **Heavy state tracking**: `CoordinatorDynamicFilter` per filter ID (same role as Trino's context)
- **Central storage**: All filters stored in `DynamicFilterService` map
- **Lightweight wrapper**: `CompositeDynamicFilter` delegates to child filters, intersects results

Both architectures require heavy state tracking somewhere - the difference is organizational, not fundamental. Our wrapper approach is architecturally equivalent to Trino's and requires minimal refactoring.

## Problem Statement

Currently, when a fact table joins with multiple dimension tables (star schema), only ONE dynamic filter is applied to the fact table scan. This severely limits the effectiveness of Dynamic Partition Pruning for star schema queries.

### Current Behavior

```sql
SELECT f.order_id, f.amount, c.name, p.name, r.name
FROM fact_orders f
JOIN dim_customers c ON f.customer_id = c.id    -- Creates filter 797
JOIN dim_products p ON f.product_id = p.id      -- Creates filter 798
JOIN dim_regions r ON f.region_id = r.id        -- Creates filter 799
WHERE c.segment = 'PREMIUM'
AND p.category = 'ELECTRONICS'
AND r.country = 'USA'
```

**Expected**: All three filters (797, 798, 799) applied to `fact_orders` scan
**Actual**: Only ONE filter applied (whichever is found first in iteration)

### Root Cause

In `SplitSourceFactory.visitTableScan()` (lines 239-251):

```java
for (Map.Entry<String, CoordinatorDynamicFilter> entry : allFilters.entrySet()) {
 CoordinatorDynamicFilter candidateFilter = entry.getValue();
 String probeColumn = candidateFilter.getColumnName();
 if (!probeColumn.isEmpty() && outputVariableNames.contains(probeColumn)) {
     dynamicFilter = candidateFilter;
     break;  // ← BUG: Stops after first match
 }
}
```

## Proposed Solution

Create a `CompositeDynamicFilter` that wraps multiple `CoordinatorDynamicFilter` instances and composes their constraints.

### Design Principles

1. **Intersection Semantics**: All filters must pass for a partition/split to be included
2. **Independent Completion**: Each underlying filter completes independently
3. **Timeout Handling**: Use the minimum timeout across all filters
4. **Progressive Resolution**: Connectors can choose to use partial results as filters complete

### Progressive Resolution (Key Design Decision)

The composite filter supports **progressive resolution** - connectors don't have to wait for all filters to complete before getting useful constraints.

**How it works:**
- Each `CoordinatorDynamicFilter` returns `TupleDomain.all()` until it's fully resolved (existing safety)
- `CompositeDynamicFilter.getCurrentConstraintByColumnName()` intersects all current constraints
- Since `intersect(X, all()) = X`, incomplete filters contribute nothing (identity)
- As each filter completes, the constraint gets progressively tighter

**Example timeline:**
```
T=0:   Filter 797 incomplete, Filter 798 incomplete, Filter 799 incomplete
    getCurrentConstraint() → all() ∩ all() ∩ all() = all()

T=50ms: Filter 797 COMPLETE (customer_id IN [1,2,3]), others incomplete
    getCurrentConstraint() → {customer_id: [1,2,3]} ∩ all() ∩ all()
                           = {customer_id: [1,2,3]}

T=80ms: Filter 797 complete, Filter 798 COMPLETE (product_id IN [10,11]), 799 incomplete
    getCurrentConstraint() → {customer_id: [1,2,3]} ∩ {product_id: [10,11]} ∩ all()
                           = {customer_id: [1,2,3], product_id: [10,11]}

T=100ms: All filters complete
    getCurrentConstraint() → full intersection of all three
```

**Connector behavior options:**
1. **Wait for all**: Call `getConstraintByColumnName().get()` to block until complete
2. **Progressive**: Poll `getCurrentConstraintByColumnName()` and apply whatever is available
3. **Hybrid**: Wait up to timeout, then use whatever is available

## Implementation Plan

### Phase 1: Create CompositeDynamicFilter Class

**File**: `presto-main-base/src/main/java/com/facebook/presto/execution/scheduler/CompositeDynamicFilter.java`

```java
@ThreadSafe
public class CompositeDynamicFilter implements DynamicFilter {
 private final List<CoordinatorDynamicFilter> filters;
 private final Set<ColumnHandle> columnsCovered;
 private final Duration waitTimeout;

 public CompositeDynamicFilter(List<CoordinatorDynamicFilter> filters) {
     this.filters = ImmutableList.copyOf(filters);
     this.columnsCovered = filters.stream()
         .flatMap(f -> f.getColumnsCovered().stream())
         .collect(toImmutableSet());
     // Use minimum timeout - don't wait longer than the shortest
     this.waitTimeout = filters.stream()
         .map(DynamicFilter::getWaitTimeout)
         .min(Comparator.comparing(Duration::toMillis))
         .orElse(new Duration(0, MILLISECONDS));
 }

 @Override
 public boolean isComplete() {
     // Complete only when ALL filters are complete
     return filters.stream().allMatch(DynamicFilter::isComplete);
 }

 @Override
 public TupleDomain<String> getCurrentConstraintByColumnName() {
     // Progressive resolution: intersect all available constraints
     // Each incomplete filter returns all() (identity for intersection)
     // As filters complete, the constraint gets progressively tighter
     return filters.stream()
         .map(DynamicFilter::getCurrentConstraintByColumnName)
         .reduce(TupleDomain::intersect)
         .orElse(TupleDomain.all());
 }

 @Override
 public CompletableFuture<TupleDomain<String>> getConstraintByColumnName() {
     // Wait for all filters, then intersect
     List<CompletableFuture<TupleDomain<String>>> futures = filters.stream()
         .map(DynamicFilter::getConstraintByColumnName)
         .collect(toList());
     return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
         .thenApply(v -> futures.stream()
             .map(CompletableFuture::join)
             .reduce(TupleDomain::intersect)
             .orElse(TupleDomain.all()));
 }

 @Override
 public void startTimeout() {
     filters.forEach(DynamicFilter::startTimeout);
 }

 @Override
 public String getFilterId() {
     // Return comma-separated list of filter IDs for metrics
     return filters.stream()
         .map(DynamicFilter::getFilterId)
         .collect(joining(","));
 }
}
```

### Phase 2: Update SplitSourceFactory.visitTableScan()

**File**: `presto-main-base/src/main/java/com/facebook/presto/sql/planner/SplitSourceFactory.java`

Change from:
```java
for (Map.Entry<String, CoordinatorDynamicFilter> entry : allFilters.entrySet()) {
 // ...
 if (!probeColumn.isEmpty() && outputVariableNames.contains(probeColumn)) {
     dynamicFilter = candidateFilter;
     break;
 }
}
```

To:
```java
List<CoordinatorDynamicFilter> matchingFilters = new ArrayList<>();
for (Map.Entry<String, CoordinatorDynamicFilter> entry : allFilters.entrySet()) {
 CoordinatorDynamicFilter candidateFilter = entry.getValue();
 String probeColumn = candidateFilter.getColumnName();
 if (!probeColumn.isEmpty() && outputVariableNames.contains(probeColumn)) {
     matchingFilters.add(candidateFilter);
     log.debug("visitTableScan MATCH: node=%s filterId=%s probeVar=%s",
         node.getId(), entry.getKey(), probeColumn);
 }
}

if (!matchingFilters.isEmpty()) {
 dynamicFilter = matchingFilters.size() == 1
     ? matchingFilters.get(0)
     : new CompositeDynamicFilter(matchingFilters);
 log.debug("visitTableScan: node=%s applying %d filters",
     node.getId(), matchingFilters.size());
}
```

### Phase 3: Update IcebergSplitSource for Multi-Filter Metrics

**File**: `presto-iceberg/src/main/java/com/facebook/presto/iceberg/IcebergSplitSource.java`

The current per-filter metrics use `dynamicFilter.getFilterId()`. With composite filters, this returns a comma-separated list. Options:

**Option A**: Emit aggregate metrics only (simpler)
```java
// Single metric for the composite filter
rs.addMetricValue(DYNAMIC_FILTER_SPLITS_PRUNED, NONE, prunedCount);
```

**Option B**: Emit per-filter metrics (more detailed)
```java
// If composite, emit for each constituent filter
if (dynamicFilter instanceof CompositeDynamicFilter) {
 for (CoordinatorDynamicFilter f : ((CompositeDynamicFilter) dynamicFilter).getFilters()) {
     // Track which filter pruned which splits (more complex)
 }
}
```

**Recommendation**: Start with Option A, add Option B later if needed.

### Phase 4: Add Integration Test

**File**: `presto-iceberg/src/test/java/com/facebook/presto/iceberg/TestDynamicPartitionPruning.java`

```java
@Test
public void testStarSchemaMultipleFilters()
{
 // Setup: fact table partitioned by customer_id AND product_id
 // dim_customers: 10 customers, 3 are PREMIUM
 // dim_products: 20 products, 5 are ELECTRONICS

 String query =
     "SELECT f.order_id, c.name, p.name " +
     "FROM fact_orders f " +
     "JOIN dim_customers c ON f.customer_id = c.id " +
     "JOIN dim_products p ON f.product_id = p.id " +
     "WHERE c.segment = 'PREMIUM' AND p.category = 'ELECTRONICS'";

 // Without composite filter: would scan all 200 partitions (10 * 20)
 // With composite filter: should scan only 15 partitions (3 * 5)

 QueryStats stats = runQueryAndGetStats(query);
 assertThat(stats.getSplitsExamined()).isLessThan(200);
 // Ideally exactly 15, but depends on partition layout
}
```

## Edge Cases

### 1. Filters Complete at Different Times

Some dimension tables are small and filters complete quickly; others are large and take longer.

**Handling**: Progressive resolution - `getCurrentConstraintByColumnName()` returns the intersection of all currently-available constraints. Incomplete filters contribute `all()` (identity for intersection). Connectors can start pruning as soon as the first filter completes.

### 2. One Filter Times Out

If one filter in the composite times out, the entire composite effectively has no constraint for that column.

**Handling**: Timeout results in `TupleDomain.all()` for that filter. Intersection with `all()` preserves other filters' constraints. Only the timed-out column loses filtering.

### 3. Overlapping Columns

Two filters could theoretically apply to the same column (unusual but possible with self-joins or complex queries).

**Handling**: `TupleDomain.intersect()` handles this correctly by intersecting the domains for that column.

### 4. Empty Result After Intersection

If filter constraints are mutually exclusive (e.g., customer_id IN (1,2,3) AND customer_id IN (4,5,6)), intersection produces `none()`.

**Handling**: `TupleDomain.intersect()` produces `none()`, which correctly prunes ALL splits.

## Metrics

### New Metrics to Add

| Metric | Description |
|--------|-------------|
| `DYNAMIC_FILTER_COUNT` | Number of filters in composite (1 for single) |
| `DYNAMIC_FILTER_COMPOSITE` | 1 if composite, 0 if single |

### Existing Metrics (work with composite)

| Metric | Behavior with Composite |
|--------|------------------------|
| `SPLITS_EXAMINED` | Total splits examined |
| `SPLITS_PRUNED` | Total splits pruned by composite |
| `WAIT_TIME` | Time waiting for composite to complete |

## Testing Plan

1. **Unit Test**: `TestCompositeDynamicFilter`
- Test `isComplete()` with various completion states
- Test `getCurrentConstraintByColumnName()` intersection
- Test timeout handling

2. **Integration Test**: `TestDynamicPartitionPruning#testStarSchemaMultipleFilters`
- Verify multiple filters are applied
- Verify correct split pruning

3. **Manual Test**: Star schema TPC-H style query
- Verify EXPLAIN shows multiple dynamic filter assignments
- Verify RuntimeStats show composite filter metrics

## Risks and Mitigations

| Risk | Mitigation |
|------|------------|
| Performance overhead of multiple filter checks | Bloom filters (future) would be O(1) per filter |
| Memory usage with many filters | Filters share coordinator memory; no per-filter duplication |
| Debugging complexity | Clear logging of composite filter composition |

## Future Enhancements

1. **Bloom Filter Support**: Convert `TupleDomain` to Bloom filters for faster row-level checks
2. **Filter Ordering**: Apply most selective filter first based on cardinality estimates
3. **LIP Integration**: Use composite filters for full LIP-style row-level filtering in PageProcessor

## Dependencies

- `CoordinatorDynamicFilter` (existing)
- `DynamicFilter` SPI (existing)
- `DynamicFilterService` (existing)

## Estimated Effort

| Phase | Effort |
|-------|--------|
| Phase 1: CompositeDynamicFilter class | 2-3 hours |
| Phase 2: SplitSourceFactory update | 30 minutes |
| Phase 3: Metrics update | 1 hour |
| Phase 4: Integration test | 1-2 hours |
| **Total** | **5-7 hours** |

## Success Criteria

1. Star schema queries with N dimension joins apply N filters to fact table
2. Split pruning effectiveness scales with number of selective dimension filters
3. No regression in single-filter performance
4. All existing tests pass