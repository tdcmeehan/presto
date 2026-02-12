# Dynamic Partition Pruning: Implementation vs RFC

This document tracks the differences between the RFC (`RFC-DYNAMIC-PARTITION-PRUNING.md`) and the current prototype implementation.

## Implemented Flow

1. **Query planning**: `AddDynamicFilterRule` and `AddDynamicFilterToSemiJoinRule` run in the same `IterativeOptimizer` as `ReorderJoins`. They populate `JoinNode.dynamicFilters` (a map from filter ID to build-side variable) for every equi-join clause on INNER/RIGHT joins. Filter IDs come from `context.getIdAllocator().getNextId()`. Gated by `distributed_dynamic_filter_enabled` session property.

2. **Worker-side filter production**: `LocalExecutionPlanner` creates a `DynamicFilterSourceOperator` on build-side workers. When the hash build completes, it produces a `TupleDomain<String>` (keyed by filter ID). A `CoordinatorDynamicFilterAggregator` merges contributions from all drivers within a task, then forwards to `SqlTask.storeDynamicFilters()`, which stores it in a versioned concurrent map.

3. **Coordinator collection**: `DynamicFilterFetcher` (one per build-side remote task) long-polls `GET /v1/task/{taskId}/dynamicFilters?since=N`. On receiving filters, it passes them to `JoinDynamicFilter.addPartitionByFilterId()`, scoped to only the filter IDs that task is responsible for (via `completedFilterIds`). It then sends a fire-and-forget `DELETE ?through=N` to free worker memory.

4. **Merging**: `JoinDynamicFilter` accumulates partitions per filter ID and merges them with `TupleDomain.columnWiseUnion()`. Uses all-or-nothing safety: `getCurrentConstraintByColumnName()` returns `TupleDomain.all()` unless all expected partitions have been received. Timeout does not count as completion.

5. **Split scheduling**: `SplitSourceFactory` registers `JoinDynamicFilter` instances via `DynamicFilterService`, matches them to probe-side `TableScanNode`s by column name, and wraps matching filters in a `TableScanDynamicFilter` (intersection semantics with progressive resolution). `TableScanDynamicFilter` is the sole implementor of the `DynamicFilter` SPI — it translates from internal String-keyed `TupleDomain`s to `TupleDomain<ColumnHandle>` at the SPI boundary using column assignments from the `TableScanNode`. The `DynamicFilter` is passed to `SplitManager.getSplits()` -> `ConnectorSplitManager.getSplits()`.

6. **Connector-side pruning**: `IcebergSplitSource.getNextBatch()` waits on the `DynamicFilter` future, receives a `TupleDomain<ColumnHandle>`, casts the handles to `IcebergColumnHandle`, converts to an Iceberg expression, and pushes it into the table scan for partition/file pruning.

7. **Deadlock breaking**: `SourcePartitionedScheduler` detects when a split source is blocked waiting for a filter but no tasks are scheduled yet, and forces task creation to unblock the build pipeline.

## Differences from RFC

### 1. No Cost Model in Optimizer

**RFC**: `AddDynamicFilterRule` uses a cost model comparing estimated benefit (probe-side I/O reduction) against cost (filter collection overhead). Checks whether the join column is a partition column or in `getColumnsWithRangeStatistics()`, evaluates build/probe cardinality ratio, and skips filters when benefit is unlikely.

**Implementation**: Every equi-join clause gets a filter when enabled (`ALWAYS` strategy). There is no selectivity check, no cardinality ratio evaluation, and no per-join cost/benefit analysis. A future `COST_BASED` strategy backed by HBO statistics is planned (see `.planning/COST_BASED_DPP_WITH_HBO.md`).

### 2. No Transitive Filter Propagation

**RFC**: The optimizer propagates filters transitively through equality chains. For `A.x = B.x` and `B.x = C.x`, C's filter reaches A's table scan even when they are not directly joined.

**Implementation**: No transitive logic or equivalence set tracking. Relies on favorable join ordering instead — if the optimizer places the filtering dimension join on the build side first, the filtered values flow naturally. This matches Trino's approach.

### 3. No `getColumnsWithRangeStatistics()` SPI or Wait Hint Computation

**RFC**: `ConnectorTableLayout.getColumnsWithRangeStatistics()` lets connectors advertise which columns have statistics enabling efficient pruning. The scheduler uses this plus build/probe cardinality from `StatsAndCosts` to compute a recommended wait duration. A `getColumnsCovered()` method on `DynamicFilter` tells connectors which columns will be filtered before the filter completes, so they can decide whether waiting is worthwhile.

**Implementation**: Uses a single static timeout from `distributed_dynamic_filter_max_wait_time` (default 2s). No layout statistics SPI. `DynamicFilter.getWaitTimeout()` returns the configured value directly. `getColumnsCovered()` was considered but not added — in practice, `DynamicFilter.EMPTY` (used when DPP is disabled) is already complete with zero timeout, so the emptiness check would be unreachable. A meaningful version would need to be something richer like `getColumnsWithRangeStatistics()` that the connector can cross-reference against its own metadata.

### 4. No Phase 2 (Worker-Side Row-Group/Row-Level Filtering)

**RFC**: Phase 2 pushes merged filters to probe-side workers via `POST /v1/task/{taskId}/inputs/filter/{filterId}` for row-group and row-level filtering in Velox.

**Implementation**: This endpoint does not exist. Purely Phase 1: coordinator-side partition/file pruning during split generation. Consistent with the RFC's phased rollout plan.

### 5. Java Filter Production Instead of C++

**RFC**: C++ workers extract filters from `VectorHasher` in `HashBuild` and report them to `PrestoTask`.

**Implementation**: Uses Java-side `DynamicFilterSourceOperator` with a `CoordinatorDynamicFilterAggregator` that merges per-driver contributions within a task. The discrete values vs. range fallback logic in `DynamicFilterSourceOperator` is functionally similar to the RFC's algorithm but uses the existing Java implementation rather than C++ `VectorHasher`.

### 6. No Serialization Envelope

**RFC**: Filters use a typed envelope (`{ "filterType": "tupleDomain", "tupleDomain": { ... } }`) to allow future extension to bloom filters.

**Implementation**: `DynamicFilterResponse` uses a flat `Map<String, TupleDomain<String>>` without a type discriminator. Adding bloom filter support would require introducing the envelope format.

### 7. Stronger Completion Semantics

**RFC**: Timeout completes with `TupleDomain.all()` (no filtering).

**Implementation**: Same timeout behavior, but additionally enforces all-or-nothing safety at the read path: `JoinDynamicFilter.getCurrentConstraintByColumnName()` returns `TupleDomain.all()` unless `fullyResolved` is true (all expected partitions received). This means connectors never see partial filter data, even if some partitions have arrived before timeout.

### 8. Config Naming

**RFC**: `dynamic-filter.*` properties.

**Implementation**: `distributed-dynamic-filter.*` to distinguish from the existing built-in local dynamic filtering (`enable_dynamic_filtering`). The two modes are mutually exclusive — enabling both throws `INVALID_SESSION_PROPERTY`.

## Implementation Additions Beyond RFC

- **`DynamicFilter` SPI with `ColumnHandle` keys**: The SPI uses `TupleDomain<ColumnHandle>` (idiomatic Presto pattern). `JoinDynamicFilter` is an internal class that does NOT implement the SPI — it stores data keyed by filter ID and column name. `TableScanDynamicFilter` is the sole `DynamicFilter` implementor, translating String-keyed domains to ColumnHandle-keyed domains via `TupleDomain.transform()` using the `TableScanNode`'s column assignments.
- **`TableScanDynamicFilter`**: Wraps multiple `JoinDynamicFilter`s for star schema queries with intersection semantics and progressive resolution. RFC mentions composite filters but this specific design is new.
- **`completedFilterIds` tracking**: `DynamicFilterFetcher` only adds partitions to filters that the completing task is responsible for, preventing incorrect `TupleDomain.none()` injection in multi-join queries.
- **Per-filter metrics**: Bracket notation (`METRIC_NAME[filterId]`) for coordinator-side and worker-side metrics.
- **Mutual exclusivity enforcement**: Runtime validation prevents enabling both local and distributed dynamic filtering simultaneously.

## Summary

The prototype implements the full Phase 1 flow: plan-time filter creation via optimizer rules, collection from Java workers via long-polling, coordinator-side merging with all-or-nothing safety, composite filter support for star schemas, a clean `DynamicFilter` SPI using `TupleDomain<ColumnHandle>`, and partition/file pruning in Iceberg. It defers cost-based filter decisions, transitive propagation, layout statistics SPI, serialization envelopes, and Phase 2 worker-side filtering.
