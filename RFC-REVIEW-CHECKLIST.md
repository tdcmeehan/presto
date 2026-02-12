# Code Review: Dynamic Partition Pruning Implementation

<!-- Reviewed using review-pr skill -->

## High-Level Overview

This change implements Phase 1 of the Dynamic Partition Pruning (DPP) RFC: coordinator-side split-level pruning for distributed joins. When a fact table joins a filtered dimension table, the build-side hash values are collected from workers, merged at the coordinator, and pushed into the Iceberg connector's split generation to prune partitions and files before they reach workers. The feature is opt-in via `distributed_dynamic_filter_strategy=ALWAYS` and is mutually exclusive with the existing local dynamic filtering.

## In-Depth Overview

The implementation spans six layers:

1. **SPI** (`DynamicFilter`, `ConnectorSplitManager`): New `DynamicFilter` interface with `getCurrentConstraint()`, `getConstraint()` future, `startTimeout()`, and `isComplete()`. A new `getSplits()` overload passes the filter to connectors. No breaking changes — all new methods have defaults.

2. **Optimizer rules** (`AddDynamicFilterRule`, `AddDynamicFilterToSemiJoinRule`): Populate `JoinNode.dynamicFilters` with plan-level filter IDs after join reordering. Gated by the new strategy session property.

3. **Coordinator infrastructure** (`JoinDynamicFilter`, `TableScanDynamicFilter`, `DynamicFilterService`): `JoinDynamicFilter` collects TupleDomains from workers using columnWiseUnion with all-or-nothing safety. `TableScanDynamicFilter` wraps multiple `JoinDynamicFilter` instances for star schema queries, translating column names to `ColumnHandle`s. `DynamicFilterService` is the central registry keyed by `(QueryId, filterId)`.

4. **Worker-side collection** (`DynamicFilterSourceOperator`, `SqlTask`, `TaskContext`): The existing `DynamicFilterSourceOperator` is extended with a composed consumer that feeds both local dynamic filtering and coordinator collection. `SqlTask` stores per-filter TupleDomains with versioning for long-polling. `CoordinatorDynamicFilterAggregator` merges driver contributions within a task.

5. **HTTP transport** (`TaskResource`, `DynamicFilterFetcher`, `DynamicFilterResponse`): Long-poll `GET /v1/task/{taskId}/dynamicFilters?since=N` with `X-Presto-Max-Wait`. Fire-and-forget `DELETE ?through=N` frees worker memory. Response includes `operatorCompleted` and `completedFilterIds` for multi-join correctness.

6. **Iceberg connector** (`IcebergSplitManager`, `IcebergSplitSource`): The split source defers scan initialization until the dynamic filter resolves, then pushes the constraint as an Iceberg expression for manifest/file-level pruning.

## Change Flow Diagram

```
    Build-side Workers                    Coordinator                         Probe-side Split Source
    ──────────────────                    ───────────────                     ──────────────────────

    HashBuild completes
           │
    DynamicFilterSourceOperator
    extracts TupleDomain
           │
    CoordinatorDynamicFilterAggregator
    merges driver contributions
           │
    SqlTask.storeDynamicFilters()
    (versioned storage + long-poll notify)
           │                              DynamicFilterFetcher
           │◄─────────────────────────── GET /v1/task/{id}/dynamicFilters
           │                                     │
           │                              JoinDynamicFilter
           │                              .addPartitionByFilterId()
           │                              (columnWiseUnion + all-or-nothing)
           │                                     │
           │                              When all partitions received:
           │                              fullyResolved = true
           │                              future.complete(merged)
           │                                     │
           │                              TableScanDynamicFilter              IcebergSplitSource
           │                              getCurrentConstraint() ──────────► .getNextBatch()
           │                              (intersect all JoinDFs,             waits on constraint future
           │                               translate to ColumnHandle)         then pushes into Iceberg scan
           │                                                                  for manifest pruning
    DynamicFilterFetcher
    DELETE /v1/task/{id}/dynamicFilters
    (free worker memory)
```

## Summary

This is a well-architected implementation that closely follows the RFC. The layering is clean, the all-or-nothing safety invariant is sound, and the multi-join correctness fix is well-reasoned. The SPI changes are minimal and backward-compatible. The main areas for improvement are around naming consistency with the RFC, dead code cleanup, and some opportunities for simplification.

## Highlights

- **All-or-nothing safety**: The `fullyResolved` volatile + `getCurrentConstraintByColumnName()` returning `all()` until complete is a correct and simple design that prevents partial filter application.
- **Multi-join correctness**: Tracking `completedFilterIds` per task to ensure `TupleDomain.none()` is only applied to filters the task is responsible for is a subtle but critical fix.
- **Graceful degradation**: Fatal/failed HTTP responses stop the fetcher rather than killing the query — exactly what the RFC specifies.
- **Progressive resolution** via `TableScanDynamicFilter` intersection is elegant — each filter independently flips from `all()` to its constraint, and the intersection monotonically tightens.
- **Clean SPI**: The `DynamicFilter` interface is minimal, has sensible defaults, and doesn't leak coordinator internals.
- **Deferred scan initialization** in `IcebergSplitSource` — waiting for the filter before calling `planFiles()` — is the right approach for maximizing Iceberg manifest pruning.
- **`CoordinatorDynamicFilterAggregator`** cleanly solves the multi-driver-per-task aggregation problem.

## Issues Found

### Critical

#### 2. Session property naming diverges from RFC

**Files**: `SystemSessionProperties.java`, `FeaturesConfig.java`

The RFC specifies `distributed-dynamic-filter.enabled` / `distributed_dynamic_filter_enabled` (boolean), but the implementation uses `distributed-dynamic-filter.strategy` / `distributed_dynamic_filter_strategy` (enum `DISABLED`/`ALWAYS`). While the strategy enum is arguably more extensible, this creates a discrepancy. If you intend to keep the enum approach, update the RFC. If you want RFC consistency, revert to a boolean. Either way, the naming should be consistent between the two documents.

#### 3. `.mvn/maven.config` change leaks local build config

Adding `-Dmaven.repo.local=./.m2/repository` is a local development convenience that should not be committed upstream — it forces all builds to use a per-repo local Maven cache, which is unusual and will surprise other developers.

### Suggestions

#### ~~4. `IcebergUtil.partitionMatchesPredicate()` appears to be dead code~~ RESOLVED

Moved `partitionMatchesPredicate()`, `partitionMatchesDynamicFilter()`, and `convertPartitionValue()` from `IcebergUtil.java` to `TestIcebergPartitionPredicate.java` as private helper methods.

#### 5. `SplitSourceFactory.FilterToScanMatcher` only translates through `ProjectNode` — NOT AN ISSUE

Investigated: `PushProjectionThroughExchange` ensures projections are in their final positions before `FilterToScanMatcher` runs. The `visitPlan` fallback correctly passes context unchanged for non-renaming nodes. Safe by design.

#### ~~6. `DynamicFilterFetcher.stop()` doesn't cancel pending requests~~ RESOLVED

Added `abort()` method to `DynamicFilterFetcher` that cancels pending HTTP futures. Called from `HttpRemoteTaskWithEventLoop.failTask()` before `abort(failedTaskStatus)`. `stop()` retained for graceful shutdown.

#### ~~7. `SqlTask.DynamicFilterResult` should be a top-level class~~ RESOLVED

Extracted to `DynamicFilterResult.java` in `com.facebook.presto.execution` package. Updated all references across modules.

#### ~~8. `TableScanDynamicFilter.translateToColumnHandle` verifies on every call~~ RESOLVED

Moved column name validation to constructor. `translateToColumnHandle()` now just calls `transform()` without runtime checks.

#### 9. Combine `PlanNodeSearcher` traversals — DEFERRED

Valid but minor optimization. Plan trees are small (dozens to hundreds of nodes). Current two-pass approach is clear and readable. Not worth the added complexity.

#### ~~10. `DynamicFilterService` cleanup leak risk~~ RESOLVED — was a real memory leak

`removeFiltersForQuery()` was never called in production code. Added cleanup to `SqlQueryExecution`'s terminal state change listener.

#### 11. Double-access pattern in `IcebergSplitSource.getNextBatch()` — NOT AN ISSUE

The double-access is intentional and beneficial. `getCurrentConstraint()` catches late filter completions after timeout. The `fullyResolved` flag ensures partial constraints are never exposed — `getCurrentConstraint()` returns `all()` unless fully resolved.

#### 12. Default timeout diverges from RFC — BY DESIGN

2s default makes the feature immediately useful when enabled. RFC documentation issue, not a code issue.

### Nits

#### ~~13. Whitespace issue in `AddDynamicFilterRule.java`~~ RESOLVED

#### 14. Import ordering

Several files have non-standard import ordering (e.g., `ServerMainModule.java` has `DynamicFilterService` import mixed into the resource manager imports). The checkstyle plugin should catch this, but worth running `./mvnw checkstyle:check` on the affected modules.

#### ~~15. Fully-qualified constructor in `PrestoSparkRddFactory.java`~~ RESOLVED

#### 16. Debug logging is verbose

`SectionExecutionFactory`, `SourcePartitionedScheduler`, and `DynamicFilterFetcher` have extensive debug logging that is appropriate for a prototype but should be pruned before merging to production. In particular, `SourcePartitionedScheduler` logs on every `schedule()` call which could be very frequent.

#### ~~17. Double blank line in `SqlTask.java`~~ RESOLVED

## Questions

1. **RFC says `distributed-dynamic-filter.enabled` (boolean), but implementation has `distributed-dynamic-filter.strategy` (enum)**. Is the strategy enum intentional for supporting future modes like `AUTO` (cost-based)? If so, the RFC should be updated. If `ALWAYS` is the only non-disabled mode planned, a boolean is simpler.

2. **The RFC mentions `getColumnsWithRangeStatistics()` on `ConnectorTableLayout`** for the optimizer to decide whether a filter is worth generating. The implementation uses `ALWAYS` strategy and doesn't check column statistics. Is this deferred to a follow-up?

3. **`IcebergSplitSource` doesn't use `partitionMatchesPredicate` or `partitionMatchesDynamicFilter`** from `IcebergUtil`. Are these used in tests only, or is there a runtime path that was missed?

4. **Timeout behavior**: The RFC says `0s` is the default (no waiting). The implementation defaults to `2s`. Which is intended?

## Testing Recommendations

- Run `./mvnw checkstyle:check -pl presto-main-base,presto-main,presto-spi,presto-common,presto-iceberg -Dair.check.skip-all=false` to verify style compliance.
- Verify the `PrestoSparkTaskManager` stub methods don't cause issues when DPP is enabled in a Spark context (currently they return empty/false, but the `SplitSourceFactory` in Spark creates a `DynamicFilterService()` which won't have any filters registered).
- Consider adding a negative test: enable DPP but with no equi-join criteria to verify no filter is created.
- Consider a test for query cancellation mid-filter-collection to verify cleanup.