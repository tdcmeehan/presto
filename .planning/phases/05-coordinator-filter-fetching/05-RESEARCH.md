# Phase 5: Coordinator Filter Fetching - Research

**Researched:** 2026-01-09
**Status:** Complete

## Research Questions

### 1. How does the coordinator currently poll workers for status updates?

**Finding:** The coordinator uses `ContinuousTaskStatusFetcherWithEventLoop` to poll worker tasks for status updates. Key patterns:

- **Long-polling with headers**: Uses `PRESTO_CURRENT_STATE` and `PRESTO_MAX_WAIT` headers for efficient polling
- **Endpoint**: `GET /v1/task/{taskId}/status`
- **Event loop execution**: All callbacks run on a dedicated `taskEventLoop` for thread safety
- **Error tracking**: Uses `RequestErrorTracker` for backoff/retry logic
- **State change listeners**: `StateMachine<TaskStatus>` notifies listeners on status changes

The fetcher is created in `HttpRemoteTaskWithEventLoop` constructor (line 447-459) and started when `start()` is called.

**Key file:** `presto-main/src/main/java/com/facebook/presto/server/remotetask/ContinuousTaskStatusFetcherWithEventLoop.java`

### 2. How does TaskStatus get updated and processed?

**Finding:** When `ContinuousTaskStatusFetcherWithEventLoop.success()` is called:

1. Updates stats and error tracker
2. Calls `updateTaskStatus(newValue)` which uses `StateMachine.setIf()` to conditionally update
3. Validates task instance ID matches (prevents REMOTE_TASK_MISMATCH)
4. Checks version to prevent downgrade
5. State change listeners are notified via `StateMachine`

In `HttpRemoteTaskWithEventLoop.initialize()` (line 485-496), a state change listener is added:
```java
taskStatusFetcher.addStateChangeListener(newStatus -> {
    if (state.isDone()) {
        cleanUpTask();
    } else {
        updateTaskStats();
        updateSplitQueueSpace();
    }
});
```

**Key integration point:** This listener is where we can detect `outputsVersion` changes and trigger filter fetching.

### 3. Where should filter fetching be integrated?

**Options considered:**

1. **Extend ContinuousTaskStatusFetcher** - Add filter fetching logic directly to status fetcher
   - Pro: Single polling loop
   - Con: Mixes concerns, complex to manage two response types

2. **New DynamicFilterFetcher class** (Recommended)
   - Pro: Clean separation of concerns, follows existing patterns
   - Con: Additional HTTP request per task when filters are available
   - Similar pattern to `TaskInfoFetcherWithEventLoop`

3. **Add to TaskStatus response**
   - Pro: No extra HTTP call
   - Con: Bloats TaskStatus, breaks protocol compatibility

**Recommendation:** Create a new `DynamicFilterFetcher` class that:
- Is triggered by `outputsVersion` changes detected in `taskStatusFetcher.addStateChangeListener()`
- Uses the existing `GET /v1/task/{taskId}/outputs/filter/{version}` endpoint
- Passes filters to `DynamicFilterService` for merging

### 4. How should DynamicFilterService be accessed from HttpRemoteTask?

**Finding:** `HttpRemoteTaskFactory` already has injection infrastructure. It receives:
- `QueryManager`
- `MetadataManager`
- `HandleResolver`

**Options:**
1. **Inject DynamicFilterService into HttpRemoteTaskFactory** (Recommended)
   - Add constructor parameter
   - Pass to `createHttpRemoteTaskWithEventLoop()`
   - Cleanest approach following existing patterns

2. **Lookup via QueryManager**
   - QueryManager has access to query context
   - More indirect, but avoids new constructor parameter

### 5. What's the flow for filter merging?

**Current infrastructure (from Phase 3/4):**

1. **Worker side:** `SqlTask.addDynamicFilter(filterId, TupleDomain<String>)` stores filters
2. **Worker endpoint:** `GET /v1/task/{taskId}/outputs/filter/{version}` returns `Map<String, TupleDomain<String>>`
3. **Coordinator:** `DynamicFilterService.getFilter(queryId, filterId)` returns `Optional<CoordinatorDynamicFilter>`
4. **Merging:** `CoordinatorDynamicFilter.addPartition(TupleDomain<ColumnHandle>)` accumulates filters

**Gap identified:** The worker stores `TupleDomain<String>` (column names as strings), but `CoordinatorDynamicFilter.addPartition()` expects `TupleDomain<ColumnHandle>`. Need a conversion step.

**Solution:** The conversion from `String` to `ColumnHandle` should happen in the coordinator when receiving filters. This requires:
- Access to the query's metadata context to resolve column names to handles
- Or: Store filter IDs alongside the TupleDomain on both sides

### 6. Version tracking across multiple tasks

**Consideration:** A single query may have multiple tasks on different workers, each producing dynamic filters.

**Approach:**
- Track `lastSeenOutputsVersion` per task in `DynamicFilterFetcher`
- Only fetch when `newStatus.getOutputsVersion() > lastSeenOutputsVersion`
- Pass `lastSeenOutputsVersion` to the endpoint to get incremental updates

## Architecture Recommendation

```
HttpRemoteTaskWithEventLoop
├── taskStatusFetcher (ContinuousTaskStatusFetcherWithEventLoop)
│   └── StateChangeListener → detects outputsVersion change
├── taskInfoFetcher (TaskInfoFetcherWithEventLoop)
└── dynamicFilterFetcher (NEW: DynamicFilterFetcher)
    ├── Triggered by outputsVersion change
    ├── Fetches from GET /v1/task/{taskId}/outputs/filter/{version}
    └── Passes to DynamicFilterService.getFilter(queryId, filterId).addPartition()
```

## Key Files to Modify

1. **New file:** `DynamicFilterFetcher.java`
   - Location: `presto-main/src/main/java/com/facebook/presto/server/remotetask/`
   - Pattern: Similar to `TaskInfoFetcherWithEventLoop`
   - Responsibilities: Fetch filters, version tracking, pass to service

2. **Modify:** `HttpRemoteTaskWithEventLoop.java`
   - Add `DynamicFilterFetcher` field
   - Initialize in constructor
   - Add state change listener to detect `outputsVersion` changes
   - Start/stop with task lifecycle

3. **Modify:** `HttpRemoteTaskFactory.java`
   - Inject `DynamicFilterService`
   - Pass to `createHttpRemoteTaskWithEventLoop()`

4. **Modify:** `RemoteTaskFactory.java` interface
   - May need to add `DynamicFilterService` parameter (or use different injection approach)

## Open Questions for Planning

1. **Column handle resolution:** How to convert `TupleDomain<String>` to `TupleDomain<ColumnHandle>`?
   - Option A: Worker stores both filter ID and column name → coordinator maps via metadata
   - Option B: Change worker to store `TupleDomain<ColumnHandle>` (requires handle serialization)
   - **Recommendation:** Keep String on wire protocol, resolve to ColumnHandle in coordinator using session/metadata context

2. **Expected partitions:** `CoordinatorDynamicFilter` requires `expectedPartitions` count. How is this known?
   - Answer: Comes from the number of build-side tasks for the join
   - Need to track which tasks are expected to produce filters

3. **Error handling:** What if a task fails before producing its filter?
   - Should mark filter as "complete with partial data" or timeout
   - May need to extend `CoordinatorDynamicFilter` to handle this case

## Research Summary

The integration approach is clear:
1. Create `DynamicFilterFetcher` following `TaskInfoFetcherWithEventLoop` pattern
2. Trigger on `outputsVersion` changes detected via existing state change listener
3. Inject `DynamicFilterService` through factory
4. Handle column handle conversion in coordinator

The main complexity is the column name to `ColumnHandle` conversion, which can be deferred to Phase 6 (Scheduler Wiring) since that phase deals with connecting filters to connectors.

---

*Phase: 05-coordinator-filter-fetching*
*Research completed: 2026-01-09*
