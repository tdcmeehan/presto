# Dynamic Partition Pruning (DPP) Code Review Guide

## Overview

This document provides a comprehensive guide for reviewing the Dynamic Partition Pruning prototype implementation. DPP reduces I/O by using runtime filter information from join build sides to prune unnecessary partitions and files during split scheduling.

---

## Architecture Diagram

```
┌─────────────────────────────────────────────────────────────────────────────────┐
│                              COORDINATOR                                        │
│                                                                                 │
│  ┌──────────────────────┐     ┌─────────────────────────────────────────────┐   │
│  │  SqlQueryExecution   │     │         DynamicFilterService                │   │
│  │                      │     │  ┌─────────────────────────────────────┐    │   │
│  │ registerDynamicFilters     │  │ filters: Map<QueryId, Map<filterId, │    │   │
│  │ FromPlan()───────────┼────►│  │          CoordinatorDynamicFilter>> │    │   │
│  │                      │     │  └─────────────────────────────────────┘    │   │
│  └──────────────────────┘     │                    ▲                        │   │
│                               │                    │ getFilter()            │   │
│                               └────────────────────┼────────────────────────┘   │
│                                                    │                            │
│  ┌──────────────────────────────────────────────────────────────────────────┐   │
│  │                    HttpRemoteTaskWithEventLoop                           │   │
│  │  ┌─────────────────────────────────────────────────────────────────────┐ │   │
│  │  │                     DynamicFilterFetcher                            │ │   │
│  │  │                                                                     │ │   │
│  │  │  fetchFilters(outputsVersion) ──► HTTP GET /v1/task/{id}/outputs/   │ │   │
│  │  │                                         filter/{version}            │ │   │
│  │  │                                              │                      │ │   │
│  │  │  success(response) ◄─────────────────────────┘                      │ │   │
│  │  │       │                                                             │ │   │
│  │  │       ▼                                                             │ │   │
│  │  │  coordinatorFilter.addPartitionByColumnName(domain)                 │ │   │
│  │  └─────────────────────────────────────────────────────────────────────┘ │   │
│  └──────────────────────────────────────────────────────────────────────────┘   │
│                                                                                 │
│  ┌───────────────────────────────────────────────────────────────────────────┐  │
│  │                         SplitSourceFactory                                │  │
│  │                                                                           │  │
│  │  createSplitSources() ──► IcebergSplitManager.getSplits(dynamicFilter)    │  │
│  │                                       │                                   │  │
│  │                                       ▼                                   │  │
│  │                              IcebergSplitSource                           │  │
│  │                     (waits on filter, prunes partitions)                  │  │
│  └───────────────────────────────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────────────────────┐
│                                 WORKER                                          │
│                                                                                 │
│  ┌──────────────────────────────────────────────────────────────────────────┐   │
│  │                         LocalExecutionPlanner                            │   │
│  │                                                                          │   │
│  │  planHashJoin() creates DynamicFilterSourceOperatorFactory with          │   │
│  │  composedConsumer = localConsumer + coordinatorConsumer                  │   │
│  └──────────────────────────────────────────────────────────────────────────┘   │
│                                      │                                          │
│                                      ▼                                          │
│  ┌──────────────────────────────────────────────────────────────────────────┐   │
│  │                   DynamicFilterSourceOperator                            │   │
│  │                                                                          │   │
│  │  finish() ──► composedConsumer.accept(tupleDomain)                       │   │
│  │                        │                                                 │   │
│  │                        ▼                                                 │   │
│  │              SqlTask.storeDynamicFilters()                               │   │
│  │                        │                                                 │   │
│  │                        ▼                                                 │   │
│  │              dynamicFilters.merge() with columnWiseUnion                 │   │
│  │              outputsVersion.incrementAndGet()                            │   │
│  └──────────────────────────────────────────────────────────────────────────┘   │
│                                      │                                          │
│                                      ▼                                          │
│  ┌──────────────────────────────────────────────────────────────────────────┐   │
│  │                           TaskResource                                   │   │
│  │                                                                          │   │
│  │  GET /v1/task/{taskId}/outputs/filter/{sinceVersion}                     │   │
│  │       │                                                                  │   │
│  │       ▼                                                                  │   │
│  │  taskManager.getDynamicFilters(taskId, sinceVersion)                     │   │
│  │       │                                                                  │   │
│  │       ▼                                                                  │   │
│  │  return DynamicFilterResponse(version, filters)                          │   │
│  └──────────────────────────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────────────────────────┘
```

---

## Data Flow Sequence Diagram

```
┌────────────┐ ┌───────────────┐ ┌─────────────┐ ┌──────────────┐ ┌─────────────────┐ ┌─────────────────┐
│SqlQuery    │ │DynamicFilter  │ │HttpRemote   │ │SqlTask       │ │DynamicFilter    │ │IcebergSplit     │
│Execution   │ │Service        │ │Task         │ │(Worker)      │ │SourceOperator   │ │Source           │
└─────┬──────┘ └───────┬───────┘ └──────┬──────┘ └──────┬───────┘ └────────┬────────┘ └────────┬────────┘
      │                │                │               │                  │                   │
      │ 1. Register    │                │               │                  │                   │
      │────────────────►                │               │                  │                   │
      │ filters        │                │               │                  │                   │
      │                │                │               │                  │                   │
      │                │                │               │  2. Build-side   │                   │
      │                │                │               │  processes data  │                   │
      │                │                │               │◄─────────────────┤                   │
      │                │                │               │                  │                   │
      │                │                │               │  3. finish()     │                   │
      │                │                │               │  calls consumer  │                   │
      │                │                │               │◄─────────────────┤                   │
      │                │                │               │                  │                   │
      │                │                │               │ 4. storeDynamic  │                   │
      │                │                │               │    Filters()     │                   │
      │                │                │               │ (merge+incr ver) │                   │
      │                │                │               │                  │                   │
      │                │                │  5. Poll      │                  │                   │
      │                │                │  TaskStatus   │                  │                   │
      │                │                │──────────────►│                  │                   │
      │                │                │               │                  │                   │
      │                │                │  6. Detect    │                  │                   │
      │                │                │  outputsVer   │                  │                   │
      │                │                │  changed      │                  │                   │
      │                │                │               │                  │                   │
      │                │                │  7. HTTP GET  │                  │                   │
      │                │                │  /outputs/    │                  │                   │
      │                │                │  filter/{ver} │                  │                   │
      │                │                │──────────────►│                  │                   │
      │                │                │               │                  │                   │
      │                │                │  8. Return    │                  │                   │
      │                │                │  filters      │                  │                   │
      │                │                │◄──────────────│                  │                   │
      │                │                │               │                  │                   │
      │                │  9. addPartition              │                  │                   │
      │                │  ByColumnName()│               │                  │                   │
      │                │◄───────────────│               │                  │                   │
      │                │                │               │                  │                   │
      │                │ 10. Complete   │               │                  │                   │
      │                │ future after   │               │                  │                   │
      │                │ collection     │               │                  │                   │
      │                │ window         │               │                  │                   │
      │                │                │               │                  │                   │
      │                │                │               │                  │     11. getNext  │
      │                │                │               │                  │     Batch()      │
      │                │                │               │                  │                  │
      │                │ 12. Wait on getConstraintByColumnName()           │◄─────────────────┤
      │                │◄──────────────────────────────────────────────────────────────────────│
      │                │                │               │                  │                   │
      │                │ 13. Return constraint         │                  │                   │
      │                │───────────────────────────────────────────────────────────────────────►
      │                │                │               │                  │                   │
      │                │                │               │                  │     14. Apply    │
      │                │                │               │                  │     partition    │
      │                │                │               │                  │     filter,      │
      │                │                │               │                  │     prune splits │
      │                │                │               │                  │                   │
```

---

## Component Summary

### 1. SPI Layer (`presto-spi`)

| File | Purpose |
|------|---------|
| `DynamicFilter.java` | New interface defining dynamic filter contract for connectors |
| `ConnectorSplitManager.java` | Added overload accepting `DynamicFilter` parameter |
| `ClassLoaderSafeConnectorSplitManager.java` | Wrapper delegating to `DynamicFilter`-aware method |

**Key DynamicFilter interface methods:**
- `getConstraint()` - Future for ColumnHandle-keyed constraint
- `getConstraintByColumnName()` - Future for String-keyed constraint
- `getCurrentConstraintByColumnName()` - Non-blocking current constraint
- `getWaitTimeout()` - How long connectors should wait
- `isComplete()` - Check if filter is ready

### 2. Coordinator Services (`presto-main-base`)

| File | Purpose |
|------|---------|
| `DynamicFilterService.java` | Registry for CoordinatorDynamicFilter instances by (QueryId, filterId) |
| `CoordinatorDynamicFilter.java` | Accumulates filter partitions from workers, implements DynamicFilter SPI |
| `SqlQueryExecution.java` | Registers filters from plan before scheduling |
| `SplitSourceFactory.java` | Wires DynamicFilter from registry to connectors |

**CoordinatorDynamicFilter key features:**
- `addPartitionByColumnName(TupleDomain<String>)` - Receives filter from worker
- Collection window (1000ms) to wait for multiple partitions
- `columnWiseUnion` merging for filter combination
- Dual storage: ColumnHandle-keyed and String-keyed

### 3. Worker-Side Filter Collection (`presto-main-base`)

| File | Purpose |
|------|---------|
| `LocalExecutionPlanner.java` | Creates `composedConsumer` combining local + coordinator consumers |
| `TaskContext.java` | Holds `dynamicFilterConsumer` for routing to SqlTask |
| `SqlTask.java` | `storeDynamicFilters()` with merge() and version tracking |
| `SqlTaskExecutionFactory.java` | Wires consumer from TaskContext to operators |

**Consumer chain:**
```
DynamicFilterSourceOperator.finish()
    ↓
composedConsumer.accept(tupleDomain)
    ↓
├── localConsumer (for same-worker filtering)
└── coordinatorConsumer → SqlTask.storeDynamicFilters()
```

### 4. HTTP Transport (`presto-main`)

| File | Purpose |
|------|---------|
| `TaskResource.java` | Exposes `GET /outputs/filter/{version}` endpoint |
| `DynamicFilterFetcher.java` | Polls workers for filters, routes to CoordinatorDynamicFilter |
| `DynamicFilterResponse.java` | JSON response wrapper with version and filter map |
| `HttpRemoteTaskWithEventLoop.java` | Owns DynamicFilterFetcher instance |
| `HttpRemoteTaskFactory.java` | Creates tasks with DynamicFilterService |

### 5. Connector Integration (`presto-iceberg`)

| File | Purpose |
|------|---------|
| `IcebergSplitManager.java` | Overload accepting DynamicFilter, passes to SplitSource |
| `IcebergSplitSource.java` | Waits for filter, applies partition pruning |
| `IcebergUtil.java` | `partitionMatchesPredicateByName()` for String-keyed filtering |

**IcebergSplitSource async pattern:**
```java
if (!dynamicFilterResolved) {
    return dynamicFilter.getConstraintByColumnName()
        .thenApply(constraint -> {
            // Apply filter and enumerate splits
            return enumerateSplitBatch(maxSize);
        });
}
```

### 6. Configuration (`presto-main-base`)

| File | Purpose |
|------|---------|
| `SystemSessionProperties.java` | `dynamic_partition_pruning_enabled`, `dynamic_partition_pruning_max_wait_time` |
| `FeaturesConfig.java` | Default configuration values |

### 7. Metrics (`presto-common`)

| File | Purpose |
|------|---------|
| `RuntimeMetricName.java` | `DYNAMIC_FILTER_SPLITS_EXAMINED`, `DYNAMIC_FILTER_SPLITS_PRUNED`, `DYNAMIC_FILTER_WAIT_TIME_NANOS` |

---

## Code Review Checklist

### Phase 1: SPI Review
- [ ] **DynamicFilter.java** - Is the interface complete and well-documented?
- [ ] **ConnectorSplitManager.java** - Is the new overload backward-compatible?
- [ ] **ClassLoaderSafeConnectorSplitManager.java** - Does it properly delegate?

### Phase 2: Coordinator Services Review
- [ ] **DynamicFilterService.java**
  - Thread safety (ConcurrentHashMap usage)
  - Proper cleanup on query completion
  - Probe variable tracking
- [ ] **CoordinatorDynamicFilter.java**
  - Collection window mechanism
  - Proper merge semantics (columnWiseUnion)
  - Future completion timing
  - Thread safety (synchronized methods)

### Phase 3: Worker-Side Review
- [ ] **LocalExecutionPlanner.java**
  - Consumer composition logic
  - Coordinator-only operator factory fallback
- [ ] **SqlTask.java**
  - `storeDynamicFilters()` merge logic
  - Version incrementing
  - Thread safety
- [ ] **TaskContext.java**
  - Consumer wiring

### Phase 4: HTTP Transport Review
- [ ] **DynamicFilterFetcher.java**
  - Error handling (graceful degradation)
  - Version-based incremental fetching
  - Filter key translation (filterId → column name)
- [ ] **TaskResource.java**
  - Endpoint correctness
  - Response format
- [ ] **DynamicFilterResponse.java**
  - JSON serialization

### Phase 5: Connector Integration Review
- [ ] **IcebergSplitSource.java**
  - Async pattern correctness
  - Proper filter application
  - Metrics recording
- [ ] **IcebergUtil.java**
  - `partitionMatchesPredicateByName()` logic
  - Type conversion handling

### Phase 6: Configuration & Metrics Review
- [ ] Session properties properly wired
- [ ] Metrics correctly recorded
- [ ] Default values appropriate

### Phase 7: Test Review
- [ ] **TestCoordinatorDynamicFilter.java** - Unit tests for filter accumulation
- [ ] **TestDynamicFilterService.java** - Service registry tests
- [ ] **TestDynamicPartitionPruning.java** - Integration tests
- [ ] **TestDynamicFilterResponse.java** - Serialization tests

---

## Key Design Decisions

### 1. String-Keyed vs ColumnHandle-Keyed Filters
Workers produce filters keyed by `filterId`. Connectors need filters keyed by column name. The prototype uses String-keyed storage to avoid premature ColumnHandle conversion, with translation happening in `DynamicFilterFetcher`.

### 2. Collection Window
Rather than completing immediately when first partition arrives, `CoordinatorDynamicFilter` waits 1000ms to collect more partitions. This improves filter quality at the cost of slight latency.

### 3. Async Split Generation
`IcebergSplitSource` returns a `CompletableFuture` that completes when the filter arrives, allowing the scheduler to continue scheduling build-side tasks while probe-side waits.

### 4. Graceful Degradation
All filter-related failures result in proceeding without filtering (returning `TupleDomain.all()`), ensuring correctness is never compromised.

### 5. Merge Semantics
Uses `TupleDomain.columnWiseUnion()` - if worker A has `customer_id IN (1,2)` and worker B has `customer_id IN (3,4)`, the merged filter is `customer_id IN (1,2,3,4)`.

---

## Files Changed Summary

```
49 files changed, 3462 insertions(+), 54 deletions(-)
```

### By Module:
- **presto-spi**: 3 files (DynamicFilter SPI)
- **presto-main-base**: 18 files (coordinator services, worker collection)
- **presto-main**: 10 files (HTTP transport)
- **presto-iceberg**: 5 files (connector integration)
- **presto-common**: 1 file (metrics)
- **presto-spark-base**: 4 files (compatibility fixes)

---

## Testing the Implementation

### Run Integration Tests
```bash
./mvnw test -pl presto-iceberg -Dtest=TestDynamicPartitionPruning
```

### Run Unit Tests
```bash
./mvnw test -pl presto-main-base -Dtest=TestCoordinatorDynamicFilter
./mvnw test -pl presto-main-base -Dtest=TestDynamicFilterService
./mvnw test -pl presto-main -Dtest=TestDynamicFilterResponse
```

### Enable DPP in a Query
```sql
SET SESSION dynamic_partition_pruning_enabled = true;
SET SESSION dynamic_partition_pruning_max_wait_time = '5s';

SELECT f.*
FROM fact_orders f
JOIN dim_customers c ON f.customer_id = c.customer_id
WHERE c.region = 'WEST';
```

---

## Questions for Review

1. **Collection Window Duration**: Is 1000ms appropriate? Should it be configurable?
2. **Expected Partitions**: Currently hardcoded to 4. Should this be dynamic based on cluster size?
3. **Error Handling**: Is graceful degradation sufficient, or should we surface warnings?
4. **Metrics**: Are the current metrics sufficient for observability?
5. **Probe-Side Detection**: Is the current logic for determining which TableScan should receive filters robust?
