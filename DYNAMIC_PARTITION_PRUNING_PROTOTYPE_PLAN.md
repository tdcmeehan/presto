# Dynamic Partition Pruning Prototype - Implementation Plan

## Objective
Build a quick prototype to validate I/O reduction effectiveness of dynamic partition pruning, targeting Java workers and Iceberg connector only.

## Scope
- **Target**: Java workers (reuse existing `DynamicFilterSourceOperator`)
- **Connector**: Iceberg only
- **Join types**: Both broadcast and partitioned joins
- **Enablement**: Session property (skip cost-based optimizer rule)
- **Metrics**: Files pruned vs files considered (via QueryStats JSON)

---

## Architecture Overview

```
COORDINATOR                                    WORKER(S)
+------------------------------------+         +---------------------------+
| SqlQueryScheduler                  |         | DynamicFilterSourceOperator|
|   |                                |         |   (collects build values)  |
|   v                                |         +-------------+-------------+
| CoordinatorDynamicFilterManager    |                       |
|   |                                |   HTTP POST filter    |
|   +-> CoordinatorDynamicFilter     | <---------------------+
|   |   (accumulates from workers)   |   (per worker partition)
|   |   uses TupleDomain.columnWiseUnion()
|   |                                |
|   v                                |
| SplitSourceFactory                 |
|   |                                |
|   +-> passes DynamicFilter         |
|   |                                |
|   v                                |
| IcebergSplitManager                |
|   +-> IcebergSplitSource           |
|       (waits on future,            |
|        prunes splits)              |
+------------------------------------+

Broadcast Join: Single worker has complete filter, completes immediately
Partitioned Join: Coordinator waits for ALL workers, merges with columnWiseUnion()
```

---

## Implementation Checklist

### Phase 1: SPI Layer

- [ ] **Step 1: Create DynamicFilter SPI Interface**
  - File: `presto-spi/src/main/java/com/facebook/presto/spi/DynamicFilter.java` (NEW)
  - Methods:
    - `CompletableFuture<TupleDomain<ColumnHandle>> getFilterFuture()`
    - `TupleDomain<ColumnHandle> getCurrentPredicate()`
    - `boolean isComplete()`
    - `boolean isBlocking()`
    - `DynamicFilter EMPTY` constant

- [ ] **Step 2: Extend ConnectorSplitManager**
  - File: `presto-spi/src/main/java/com/facebook/presto/spi/connector/ConnectorSplitManager.java`
  - Add default method with DynamicFilter parameter:
    ```java
    default ConnectorSplitSource getSplits(
            ConnectorTransactionHandle transactionHandle,
            ConnectorSession session,
            ConnectorTableLayoutHandle layout,
            SplitSchedulingContext splitSchedulingContext,
            DynamicFilter dynamicFilter)
    ```

### Phase 2: Session Properties

- [ ] **Step 3: Add Session Properties**
  - File: `presto-main-base/src/main/java/com/facebook/presto/SystemSessionProperties.java`
  - Add properties:
    - `dynamic_partition_pruning_enabled` (boolean, default false)
    - `dynamic_partition_pruning_max_wait_time` (duration, default 5s)
  - Add getter methods

### Phase 3: Coordinator Infrastructure

- [ ] **Step 4a: Create CoordinatorDynamicFilter**
  - File: `presto-main-base/src/main/java/com/facebook/presto/sql/planner/CoordinatorDynamicFilter.java` (NEW)
  - Implementation:
    - Wraps `SettableFuture<TupleDomain<ColumnHandle>>`
    - Tracks expected partition count (number of build workers)
    - `addPartition(TupleDomain)` method (similar to `LocalDynamicFilter`)
    - Uses `TupleDomain.columnWiseUnion()` for merging
    - Completes future when all partitions received
    - Implements `DynamicFilter` interface

- [ ] **Step 4b: Create DynamicFilterService**
  - File: `presto-main/src/main/java/com/facebook/presto/server/remotetask/DynamicFilterService.java` (NEW)
  - Coordinator-side service:
    - Tracks active dynamic filters by queryId + filterId
    - Receives filter updates from workers via HTTP
    - Routes to appropriate `CoordinatorDynamicFilter` for merging

- [ ] **Step 4c: Add HTTP Endpoint for Worker Filter Push**
  - File: `presto-main/src/main/java/com/facebook/presto/server/TaskResource.java`
  - Add endpoint:
    ```java
    @POST
    @Path("{taskId}/dynamicFilter/{filterId}")
    public Response submitDynamicFilter(...)
    ```

- [ ] **Step 4d: Modify DynamicFilterSourceOperator to Push Filter**
  - File: `presto-main-base/src/main/java/com/facebook/presto/operator/DynamicFilterSourceOperator.java`
  - After filter collected (in `finish()` method):
    - POST filter to `/v1/task/{taskId}/dynamicFilter/{filterId}`
    - Use existing `HttpClient` infrastructur

### Phase 4: Wiring

- [ ] **Step 5: Wire DynamicFilter through SplitSourceFactory**
  - File: `presto-main-base/src/main/java/com/facebook/presto/sql/planner/SplitSourceFactory.java`
  - Modify `visitTableScan()` (lines 149-164):
    - Look up dynamic filter for TableScanNode
    - Pass `DynamicFilter` to `splitSourceProvider.getSplits()`

### Phase 5: Iceberg Connector Implementation

- [ ] **Step 6: Modify IcebergSplitManager**
  - File: `presto-iceberg/src/main/java/com/facebook/presto/iceberg/IcebergSplitManager.java`
  - Override new `getSplits()` with DynamicFilter parameter
  - Pass dynamic filter to `IcebergSplitSource` constructor

- [ ] **Step 7: Modify IcebergSplitSource (Core Logic)**
  - File: `presto-iceberg/src/main/java/com/facebook/presto/iceberg/IcebergSplitSource.java`
  - Add constructor params: `DynamicFilter dynamicFilter`, `Duration maxWaitTime`
  - Add fields for metrics: `filesConsidered`, `filesPruned`, `filesScheduledBeforeFilter`
  - Modify `getNextBatch()` (line 87):
    - Wait for dynamic filter with timeout
    - Apply filter when checking splits
  - Add `splitMatchesDynamicFilter()` method:
    - Extract partition key values from `IcebergSplit.getPartitionKeys()`
    - Check against `TupleDomain` domains

### Phase 6: Metrics

- [ ] **Step 8a: Create DynamicFilterStats**
  - File: `presto-main-base/src/main/java/com/facebook/presto/operator/DynamicFilterStats.java` (NEW)
  - Fields: `filesConsidered`, `filesPruned`, `filesScheduledBeforeFilter`, `filterWaitTimeMs`

- [ ] **Step 8b: Add to QueryStats**
  - File: `presto-main-base/src/main/java/com/facebook/presto/execution/QueryStats.java`
  - Add `DynamicFilterStats` field
  - Include in JSON serialization

### Phase 7: Testing

- [ ] **Step 9a: Unit Tests**
  - File: `presto-iceberg/src/test/java/com/facebook/presto/iceberg/TestIcebergDynamicPartitionPruning.java` (NEW)
  - Test cases:
    - Filter prunes correct partitions
    - Timeout behavior (filter doesn't arrive in time)
    - Metrics counting

- [ ] **Step 9b: Integration Tests**
  - Test with real Iceberg tables
  - Verify query results match with/without DPP
  - Verify metrics in QueryStats JSON

---

## Files Summary

| File | Action | Status |
|------|--------|--------|
| `presto-spi/.../DynamicFilter.java` | CREATE | [ ] |
| `presto-spi/.../ConnectorSplitManager.java` | MODIFY | [ ] |
| `presto-main-base/.../SystemSessionProperties.java` | MODIFY | [ ] |
| `presto-main-base/.../CoordinatorDynamicFilter.java` | CREATE | [ ] |
| `presto-main/src/.../DynamicFilterService.java` | CREATE | [ ] |
| `presto-main/src/.../TaskResource.java` | MODIFY | [ ] |
| `presto-main-base/.../DynamicFilterSourceOperator.java` | MODIFY | [ ] |
| `presto-main-base/.../SplitSourceFactory.java` | MODIFY | [ ] |
| `presto-iceberg/.../IcebergSplitManager.java` | MODIFY | [ ] |
| `presto-iceberg/.../IcebergSplitSource.java` | MODIFY | [ ] |
| `presto-main-base/.../DynamicFilterStats.java` | CREATE | [ ] |
| `presto-main-base/.../QueryStats.java` | MODIFY | [ ] |

---

## Key Code References (Verified)

| Component | Location | Notes |
|-----------|----------|-------|
| `LocalDynamicFilter` | `presto-main-base/.../sql/planner/LocalDynamicFilter.java` | Pattern to follow for merging |
| `TupleDomain.columnWiseUnion()` | `presto-common/.../predicate/TupleDomain.java:297-361` | Existing merge logic |
| `DynamicFilterSourceOperator` | `presto-main-base/.../operator/DynamicFilterSourceOperator.java` | Already collects build-side filter |
| `IcebergSplitSource` | `presto-iceberg/.../IcebergSplitSource.java` | 148 lines, clean integration point |
| `JoinNode.getDynamicFilters()` | `presto-spi/.../plan/JoinNode.java:313-317` | Existing filter ID mapping |

---

## Success Criteria

- [ ] **Correctness**: Query results match without DPP enabled
- [ ] **I/O Reduction**: TPC-DS Q5, Q17 show >30% file pruning on Iceberg tables
- [ ] **Graceful Degradation**: Filter timeout doesn't fail query

---

## Test Query Example

```sql
-- Create partitioned Iceberg table
CREATE TABLE orders (id BIGINT, order_date DATE, amount DECIMAL)
WITH (partitioning = ARRAY['order_date']);

-- Insert data into multiple partitions
INSERT INTO orders VALUES (1, DATE '2024-01-01', 100.00), ...

-- Query with join that should trigger DPP
SET SESSION dynamic_partition_pruning_enabled = true;
SELECT * FROM orders o
JOIN (SELECT DISTINCT order_date FROM dimension WHERE region = 'US') d
ON o.order_date = d.order_date;

-- Verify in QueryStats: filesPruned > 0
```

---

## Expected Metrics Output

```json
{
  "dynamicFilterStats": {
    "filesConsidered": 1000,
    "filesPruned": 850,
    "filesScheduledBeforeFilter": 50,
    "filterWaitTimeMs": 120,
    "pruningRatio": 0.85
  }
}
```

---

## Notes / Open Issues

-

---

## Changelog

| Date | Change |
|------|--------|
| 2026-01-07 | Initial plan created |