# Dynamic Partition Pruning - Code Review

<!-- Reviewed using review-pr skill -->

## High-Level Overview

This change implements distributed dynamic partition pruning (DPP) for Presto, enabling the coordinator to collect filter constraints from build-side workers and push them into split scheduling so that connectors (starting with Iceberg) can prune partitions and files that don't match the join condition. The feature is gated behind a new session property (`distributed_dynamic_filter_strategy`) and is mutually exclusive with the existing local/within-fragment dynamic filtering.

The approach follows a coordinator-mediated pattern: build-side tasks produce `TupleDomain` filter values via `DynamicFilterSourceOperator`, ship them to the coordinator through a new long-polling HTTP endpoint, and the coordinator aggregates them in `JoinDynamicFilter` instances. When all partitions have reported, the `TableScanDynamicFilter` (which implements the `DynamicFilter` SPI) translates column names to `ColumnHandle`s and passes the constraint to the connector's `getSplits()` method for split-level pruning.

## In-Depth Overview

### SPI Layer (`presto-spi`)
- **`DynamicFilter` interface**: Redesigned from a future-based API (`getConstraint()` returning `CompletableFuture<TupleDomain<ColumnHandle>>`) to a simpler peek/await model. New methods: `peekFilter()`, `awaitReady()`, `isComplete()`, `getWaitTimeout()`. Removed: `getColumnsCovered()`, `getConstraint()`, `isBlocked()`.
- **`ConnectorSplitManager`**: New overload of `getSplits()` accepting a `DynamicFilter` parameter with a default implementation delegating to the existing 4-parameter method.
- **`ClassLoaderSafeConnectorSplitManager`**: Propagates the new overload through the classloader boundary.

### Common Layer (`presto-common`)
- **`RuntimeMetricName`**: 10 new metric constants for tracking dynamic filter behavior (splits examined/pruned, wait time, collection time, distinct values, etc.).

### Coordinator-Side Filter Management (`presto-main-base`)
- **`JoinDynamicFilter`** (renamed from `CoordinatorDynamicFilter`): Internal class that accumulates TupleDomains from workers. No longer implements the `DynamicFilter` SPI directly. Stores data keyed by filter ID, translates to column name at the boundary. Enforces all-or-nothing safety: `peekFilterByColumnName()` returns `all()` until fully resolved. Caches merged constraint.
- **`TableScanDynamicFilter`** (renamed from `CompositeDynamicFilter`): The sole `DynamicFilter` SPI implementation. Wraps one or more `JoinDynamicFilter`s, intersects their constraints, and translates column names to `ColumnHandle`s using a mapping from `TableScanNode.getAssignments()`.
- **`DynamicFilterService`**: Central registry mapping `(QueryId, filterId)` to `JoinDynamicFilter` instances. New: `scanToFilterIds` mapping for precise filter-to-scan matching by `PlanNodeId`.

### Planner Integration
- **`AddDynamicFilterRule` / `AddDynamicFilterToSemiJoinRule`**: New optimizer rules that populate `JoinNode.dynamicFilters` for distributed DPP. Run after `ReorderJoins`.
- **`RemoveUnsupportedDynamicFilters`**: When DPP is enabled, retains all dynamic filter entries (no probe-side consumption requirement since filters are coordinator-side).
- **`DynamicFiltersChecker`**: Skips probe-side consumption verification when DPP is enabled.
- **`SplitSourceFactory`**: Registers filters from all fragments, builds scan-to-filter mappings (handling `ProjectNode` renames), and creates `TableScanDynamicFilter` instances at table scan time.

### Worker-Side Collection
- **`DynamicFilterSourceOperator`**: Enhanced with per-filter metrics (distinct values, collection time, min/max fallback tracking). Emits per-channel metrics with bracket notation.
- **`LocalExecutionPlanner`**: New `CoordinatorDynamicFilterAggregator` class merges per-driver contributions before forwarding to `SqlTask`. Composes local + coordinator consumers when both are available. New `createCoordinatorDynamicFilterSourceOperatorFactory()` for distributed-only DPP.

### Task-Level Filter Storage
- **`SqlTask`**: Stores dynamic filters in `ConcurrentHashMap<String, TupleDomain<String>>` with versioning for incremental retrieval. Supports long-polling via `SettableFuture`. Tracks registered filter IDs and operator completion.
- **`TaskManager` interface**: 5 new methods for dynamic filter operations.
- **`SqlTaskManager`**, **`PrestoSparkTaskManager`**: Implement the new interface methods.

### HTTP Transport
- **`TaskResource`**: New `GET /v1/task/{taskId}/dynamicFilters` endpoint (async, long-polling) and `DELETE` endpoint for memory cleanup.
- **`DynamicFilterFetcher`**: Redesigned from version-triggered fetch to continuous long-polling. Sends `PRESTO_MAX_WAIT` header. Handles operator completion, sends fire-and-forget DELETE for cleanup. Graceful degradation on errors.
- **`DynamicFilterResponse`**: Extended with `operatorCompleted` boolean and `completedFilterIds` set for multi-join correctness.

### Configuration
- **`FeaturesConfig`**: New `DistributedDynamicFilterStrategy` enum (`DISABLED`, `ALWAYS`) and `distributed-dynamic-filter.max-wait-time` config.
- **`SystemSessionProperties`**: New `distributed_dynamic_filter_strategy` and `distributed_dynamic_filter_max_wait_time` session properties. Runtime validation throws `INVALID_SESSION_PROPERTY` if both built-in DF and distributed DF are enabled.

## Change Flow Diagram

```
Build-Side Worker                    Coordinator                         Probe-Side Worker
──────────────────                  ─────────────                       ──────────────────

DynamicFilterSourceOperator         SplitSourceFactory                  IcebergSplitSource
  ↓ collect values                    ↓ registerDynamicFilters()          ↓ awaitReady()
  ↓ per-driver aggregation          DynamicFilterService                  ↓ (blocks until
CoordinatorDynamicFilterAggregator    ↓ registerFilter()                     filter ready
  ↓ merged TupleDomain<String>        ↓ registerScanFilterMapping()          or timeout)
SqlTask.storeDynamicFilters()                                             ↓ peekFilter()
  ↓ versioned storage                                                     ↓ prune splits
  ↓
  ↓ GET /dynamicFilters?since=N    TaskResource
  ←──────────────────────────────  (long-poll, async)
  ↓                                  ↓
  ↓ DynamicFilterResponse           DynamicFilterFetcher
  ───────────────────────────────→  ↓ addPartitionByFilterId()
  ↓                                JoinDynamicFilter
  ↓ DELETE /dynamicFilters?through   ↓ (accumulate, merge)
  ←──────────────────────────────    ↓ fullyResolved = true
                                   TableScanDynamicFilter
                                     ↓ peekFilter() → TupleDomain<CH>
                                     ────────────────────────────────→ (unblocks connector)
```

## Summary

This is a well-structured, feature-complete implementation of distributed dynamic partition pruning. The architecture is sound — separating the internal `JoinDynamicFilter` from the SPI-facing `TableScanDynamicFilter`, using all-or-nothing safety semantics, and supporting star schema queries via intersection — are all good design choices. The multi-join correctness fix (tracking which tasks own which filters) is essential and well-implemented.

The code quality is generally high with good separation of concerns, appropriate thread-safety annotations, and comprehensive test coverage. The main areas for improvement are around leftover debug artifacts, a few thread-safety concerns, some SPI design questions, and the `.mvn/maven.config` change that should not be committed.

## Highlights

- **All-or-nothing safety**: `JoinDynamicFilter` returns `TupleDomain.all()` until fully resolved, preventing partial-filter data from incorrectly pruning valid splits. This is a crucial correctness property.
- **Multi-join correctness**: The `completedFilterIds` tracking in `DynamicFilterResponse` and the per-task filter ID registration solve the real multi-join problem where different tasks own different filters.
- **Graceful degradation**: The `DynamicFilterFetcher` logs errors but never fails the query. On persistent failure, it stops polling and the filter times out to `all()`. This follows the RFC's guidance.
- **Clean SPI design**: The redesigned `DynamicFilter` interface with `peekFilter()`/`awaitReady()` is simpler and more intuitive than the previous future-based API.
- **Progressive resolution**: `TableScanDynamicFilter` supports progressive tightening for star schema queries — each filter independently flips from `all()` to its constraint.
- **Cached merged constraint**: `JoinDynamicFilter` now caches the merged `TupleDomain` instead of recomputing `columnWiseUnion` on every `peekFilterByColumnName()` call.
- **Test coverage**: Good unit test coverage for `JoinDynamicFilter`, `TableScanDynamicFilter`, `DynamicFilterFetcher`, and integration tests in `TestDynamicPartitionPruning`.

## Issues Found

### Critical

1. **`.mvn/maven.config` changes local Maven repo path**
   - File: `.mvn/maven.config`
   - The diff adds `-Dmaven.repo.local=./.m2/repository`, which overrides the Maven local repository to a project-relative directory. This is a local development convenience that should NOT be committed. It would break CI builds and confuse other contributors.
   - **Action**: Revert this change before merging.

2. **`SqlTask.getDynamicFilters()` has a TOCTOU race between version check and filter retrieval**
   - File: `presto-main-base/src/main/java/com/facebook/presto/execution/SqlTask.java`
   - The method checks `dynamicFilterOutputsVersion.get()` then calls `getDynamicFiltersSince(sinceVersion)`, but new filters could be stored between these two calls. The `Futures.transform` callback reads `dynamicFilterOperatorCompleted.get()` and `getRegisteredDynamicFilterIds()` at a different point in time than when the filters were read.
   - While there's a TOCTOU mitigation for the future setup, the transform callback's reads of `operatorCompleted` and `registeredFilterIds` are not atomic with the filter retrieval. In practice, this likely manifests as an extra round-trip (the fetcher sees a version bump, polls again, and gets the filters), but it's worth documenting or tightening.
   - **Suggestion**: Consider capturing `operatorCompleted` and `registeredFilterIds` inside the same critical section as the filter snapshot, or document why the current behavior is acceptable.

3. **`DynamicFilter` SPI removes `getColumnsCovered()` — backwards compatibility concern**
   - File: `presto-spi/src/main/java/com/facebook/presto/spi/connector/DynamicFilter.java`
   - The `getColumnsCovered()` method was removed entirely. If any third-party connector implemented or called this method, this is a breaking SPI change. SPI changes in Presto require extra scrutiny.
   - **Suggestion**: Since the old `DynamicFilter` SPI was recently added and likely has no external consumers yet (especially since `getConstraint()` returned `CompletableFuture<TupleDomain<ColumnHandle>>` which was also changed), this is probably acceptable. But confirm that no other connectors in the repo use the old API.

### Suggestions

4. **Excessive `log.debug()` calls throughout production code**
   - Files: `SectionExecutionFactory.java`, `SourcePartitionedScheduler.java`, `SqlTask.java`, `DynamicFilterFetcher.java`, `SplitSourceFactory.java`
   - Many of these read like development-time trace logging rather than production diagnostics. For example, `SqlTask.storeDynamicFilters` logs `isNone=%s` and `SectionExecutionFactory.setExpectedPartitionsForFilters` logs every filter and join node.
   - **Suggestion**: Reduce to the most essential debug statements. Consider removing per-filter/per-partition debug logs or gating them behind `log.isDebugEnabled()` checks where string formatting is expensive (e.g., domain `toString()` in `DynamicFilterFetcher.success()`).

5. **`CoordinatorDynamicFilterAggregator` in `LocalExecutionPlanner` uses `verify()` which throws `VerifyError`**
   - File: `presto-main-base/src/main/java/com/facebook/presto/sql/planner/LocalExecutionPlanner.java`
   - `verify(partitions.size() < partitionCount, ...)` will throw a `VerifyError` (extends `AssertionError`) if triggered. In production, this would crash the task. If the driver count changes dynamically (e.g., due to task recovery or scaling), a more defensive approach would log a warning and forward the partial result.
   - **Suggestion**: Consider whether this invariant can actually be violated in production. If yes, make it a `checkState` or log-and-skip. If no, the `verify()` is fine.

6. **`DynamicFilterFetcher` passes `taskStatusRefreshMaxWait` as `maxWait` to the dynamic filter endpoint**
   - File: `presto-main/src/main/java/com/facebook/presto/server/remotetask/HttpRemoteTaskWithEventLoop.java`
   - The `maxWait` parameter for `DynamicFilterFetcher` is `taskStatusRefreshMaxWait` — this is the task status polling interval, not necessarily the right timeout for dynamic filter long-polling. A dedicated config or using `distributedDynamicFilterMaxWaitTime` might be more appropriate.
   - **Suggestion**: Consider whether a dedicated `maxWait` config for filter fetching is needed, or document why reusing the task status interval is acceptable.

7. **`SplitSourceFactory.registerDynamicFilters()` traverses plan tree multiple times**
   - File: `presto-main-base/src/main/java/com/facebook/presto/sql/planner/SplitSourceFactory.java`
   - The method does three separate `PlanNodeSearcher.searchFrom()` calls (JoinNodes, SemiJoinNodes, then `matchFiltersToScans`). This is O(n) per traversal. For very large plans this could be noticeable, though in practice plan trees are small.
   - **Suggestion**: Consider a single visitor that handles all three cases in one pass. Not urgent — just a potential optimization for plans with hundreds of nodes.

8. **`FilterToScanMatcher.visitProject()` only handles direct variable renames**
   - File: `presto-main-base/src/main/java/com/facebook/presto/sql/planner/SplitSourceFactory.java`
   - The `visitProject` only translates column names through `VariableReferenceExpression` assignments. If a `ProjectNode` applies an expression (e.g., `CAST(x AS bigint)` or `x + 1`) to the filter column, the mapping is lost and the filter won't be matched to the scan.
   - **Suggestion**: This is likely fine for the common case (PushProjectionThroughExchange creates simple renames), but add a comment documenting this limitation. Expressions that modify the column value would invalidate the dynamic filter anyway.

9. **`SplitManager.getSplits()` removed `startTime` variable without replacing it**
   - File: `presto-main-base/src/main/java/com/facebook/presto/split/SplitManager.java`
   - The original `getSplits()` had `long startTime = System.nanoTime()`. The refactored version removes it. If this was used for metrics elsewhere, it may need to be preserved.
   - **Suggestion**: Verify whether `startTime` was used for metrics collection. If it was dead code, the removal is fine.

10. **`SqlTask` accumulates filter data without bound**
    - File: `presto-main-base/src/main/java/com/facebook/presto/execution/SqlTask.java`
    - The `dynamicFilters` and `dynamicFilterVersions` maps grow as filters are produced. While `removeDynamicFiltersThrough()` exists, it's only called when the coordinator sends a DELETE. If the coordinator fails to send the DELETE (e.g., network issue), filters accumulate indefinitely for the task's lifetime.
    - **Suggestion**: Consider adding a bounded capacity or TTL. In practice, the task lifetime bounds this, but worth noting.

11. **`TableScanDynamicFilter.awaitReady()` reads `peekFilterByColumnName()` instead of using future results**
    - File: `presto-main-base/src/main/java/com/facebook/presto/execution/scheduler/TableScanDynamicFilter.java`
    - In `awaitReady()`, after all futures complete, the method reads `peekFilterByColumnName()` from each filter rather than using the future's resolved value. This is correct (since the filter IS resolved at that point), but it's a subtle indirection — the future values are discarded.
    - **Suggestion**: Add a comment explaining why `peekFilterByColumnName()` is used instead of the future results (because the filter may have timed out and the peek gives the all-or-nothing safe value).

12. **`DynamicFilterFetcher.stop()` doesn't cancel pending requests**
    - File: `presto-main/src/main/java/com/facebook/presto/server/remotetask/DynamicFilterFetcher.java`
    - The `stop()` method sets `running = false` but doesn't cancel the pending HTTP request. The comment explains this is intentional (to receive final filters), but it means the HTTP connection stays open until the server responds or the connection times out. `abort()` exists for emergency cleanup.
    - **Suggestion**: This design is reasonable, but ensure that `stop()` is eventually followed by connection cleanup (e.g., task completion closes the HTTP client).

13. **Import ordering inconsistency**
    - Files: `SqlTask.java`, `SqlTaskExecutionFactory.java`, `SplitSourceFactory.java`, `DynamicFilterSourceOperator.java`
    - Several files have `com.facebook.presto.common.predicate.TupleDomain` imported between unrelated groups or after `com.google.common` imports, violating Presto's import ordering convention (framework imports should be grouped).
    - Also in `SqlTask.java`: blank line between `java.net.URI` and `java.util.List` imports.
    - **Suggestion**: Run the formatter or manually fix import ordering.

### Nits

14. **`DynamicFilterResponse.completedFilterIds` accepts null**
    - File: `presto-main/src/main/java/com/facebook/presto/server/remotetask/DynamicFilterResponse.java`
    - The constructor has `completedFilterIds == null ? ImmutableSet.of() : ImmutableSet.copyOf(completedFilterIds)`. Other Presto classes use `requireNonNull` and let the caller provide an empty set. Using `requireNonNull` would be more consistent with Presto conventions.

15. **`JoinDynamicFilter.createDisabled()` is a static factory on a non-SPI class**
    - File: `presto-main-base/src/main/java/com/facebook/presto/execution/scheduler/JoinDynamicFilter.java`
    - This method returns `DynamicFilter.EMPTY`. Now that `JoinDynamicFilter` doesn't implement `DynamicFilter`, having a `createDisabled()` method here is confusing. It should be called from `DynamicFilter.EMPTY` directly.

16. **Missing newline at end of `TableScanDynamicFilter.java`**
    - The old `CompositeDynamicFilter.java` was missing a trailing newline. The renamed file now has one — good.

17. **`DynamicFilterStats` is referenced but its implementation not shown in the diff**
    - File: `presto-main-base/src/main/java/com/facebook/presto/execution/scheduler/DynamicFilterStats.java`
    - Listed as a new file (`A`) in git status. The diff only shows changes to existing files, so I can't review the implementation. Ensure it follows Presto patterns for JMX-exported stats (using `@Managed` annotations, `CounterStat`, `DistributionStat`, etc.).

18. **`DynamicFilterResult` is a new class not shown in the diff**
    - File: `presto-main-base/src/main/java/com/facebook/presto/execution/DynamicFilterResult.java`
    - Listed as untracked (`??`) in git status. Ensure it's a simple value class with proper `requireNonNull` validation and immutable collections.

## Questions

1. **Why `DistributedDynamicFilterStrategy` enum instead of a boolean?** The enum currently has only `DISABLED` and `ALWAYS`. Is there a planned third option (e.g., `AUTOMATIC` based on cost estimation)? If so, the enum is forward-looking. If not, a boolean `distributed_dynamic_filter_enabled` would be simpler.

2. **Why not reuse the existing `enable_dynamic_filtering` session property with an additional mode?** The mutual exclusivity check between `enable_dynamic_filtering` and `distributed_dynamic_filter_strategy` adds complexity. Could these be unified into a single enum with `DISABLED`, `LOCAL`, `DISTRIBUTED` values?

3. **What is the expected behavior when a connector doesn't override the new `getSplits()` overload?** The default implementation ignores the `DynamicFilter` parameter and delegates to the 4-parameter method. This is correct but means the filter was collected for nothing. Should there be a log warning or metric when a filter is created but the connector doesn't consume it?

4. **Is there a mechanism to propagate `DynamicFilter` SPI changes to native execution (Velox)?** The `CoordinatorDynamicFilterAggregator` Javadoc references `PrestoToVeloxQueryPlan.cpp`. Are there corresponding native-side changes needed?

5. **`SectionExecutionFactory` has a TODO about bucketed execution**. The comment says "must be fixed if DPP is extended to connectors like Hive". Is this tracked as a follow-up issue?

## Testing Recommendations

1. **Negative test for mutual exclusivity**: Verify that enabling both `enable_dynamic_filtering` and `distributed_dynamic_filter_strategy=ALWAYS` in the same session throws `INVALID_SESSION_PROPERTY`.

2. **Test filter timeout behavior end-to-end**: The 1ms timeout test exists, but also test that the query returns correct results (no pruning, but no errors) when the filter times out with a realistic timeout and slow build side.

3. **Test connector that doesn't support DynamicFilter**: Verify that when a connector doesn't override the new `getSplits()` overload, the query still runs correctly (the filter is silently ignored).

4. **Test `FilterToScanMatcher` with non-trivial ProjectNode expressions**: Verify that when a `ProjectNode` has an expression (not a simple rename) on the filter column, the filter correctly falls through without matching.

5. **Test `DynamicFilterFetcher` abort during active long-poll**: Verify that calling `abort()` while a long-poll request is in flight properly cancels the connection and doesn't leak resources.

6. **Load test with many concurrent queries**: The `DynamicFilterService` uses `ConcurrentHashMap` for the outer and inner maps. With many concurrent queries, verify there are no contention hotspots. The `removeFiltersForQuery()` call on query completion should be sufficient cleanup.
