---
phase: 04-worker-filter-storage-and-endpoint
plan: 01
subsystem: execution
tags: [TaskStatus, SqlTask, TaskResource, TupleDomain, dynamic-filters, JAX-RS]

# Dependency graph
requires:
  - phase: 03
    provides: CoordinatorDynamicFilter and DynamicFilterService for filter collection
provides:
  - TaskStatus.outputsVersion field for version tracking
  - SqlTask dynamic filter storage with ConcurrentHashMap
  - GET /v1/task/{taskId}/outputs/filter/{version} endpoint
affects: [phase-05-coordinator-filter-fetching]

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "AtomicLong for version tracking"
    - "ConcurrentHashMap for thread-safe filter storage"
    - "JAX-RS GET endpoint with path parameters"

key-files:
  created: []
  modified:
    - presto-main-base/src/main/java/com/facebook/presto/execution/TaskStatus.java
    - presto-main-base/src/main/java/com/facebook/presto/execution/SqlTask.java
    - presto-main-base/src/main/java/com/facebook/presto/execution/TaskManager.java
    - presto-main-base/src/main/java/com/facebook/presto/execution/SqlTaskManager.java
    - presto-main/src/main/java/com/facebook/presto/server/TaskResource.java

key-decisions:
  - "Combined Task 1 and Task 2 into single commit due to interdependency"
  - "Used ThriftField(22) for outputsVersion (next available after 21)"

patterns-established:
  - "Version-based incremental filter fetching pattern"

issues-created: []

# Metrics
duration: 8min
completed: 2026-01-09
---

# Phase 4 Plan 01: Worker Filter Storage and Endpoint Summary

**TaskStatus outputsVersion field, SqlTask ConcurrentHashMap filter storage, and GET /v1/task/{taskId}/outputs/filter/{version} endpoint for coordinator polling**

## Performance

- **Duration:** 8 min
- **Started:** 2026-01-09T02:15:00Z
- **Completed:** 2026-01-09T02:23:00Z
- **Tasks:** 3
- **Files modified:** 5

## Accomplishments

- Added `outputsVersion` field to TaskStatus with JSON/Thrift serialization (ThriftField 22)
- Implemented dynamic filter storage in SqlTask using ConcurrentHashMap with AtomicLong version tracking
- Added `addDynamicFilter()`, `getDynamicFiltersSince()`, and `getOutputsVersion()` methods to SqlTask
- Added `getDynamicFilters()` method to TaskManager interface with implementation in SqlTaskManager
- Created GET endpoint at `/v1/task/{taskId}/outputs/filter/{version}` for coordinator to fetch filters

## Task Commits

Each task was committed atomically:

1. **Task 1+2: Add outputsVersion and dynamic filter storage** - `505589262af` (feat)
   - Combined due to interdependency: TaskStatus field requires SqlTask to provide value
2. **Task 3: Add GET endpoint for dynamic filters** - `a519b156b9a` (feat)

## Files Created/Modified

- `presto-main-base/src/main/java/com/facebook/presto/execution/TaskStatus.java` - Added outputsVersion field with JSON/Thrift annotations
- `presto-main-base/src/main/java/com/facebook/presto/execution/SqlTask.java` - Added ConcurrentHashMap storage and version tracking
- `presto-main-base/src/main/java/com/facebook/presto/execution/TaskManager.java` - Added getDynamicFilters interface method
- `presto-main-base/src/main/java/com/facebook/presto/execution/SqlTaskManager.java` - Implemented getDynamicFilters
- `presto-main/src/main/java/com/facebook/presto/server/TaskResource.java` - Added GET endpoint

## Decisions Made

- **Combined Task 1 and Task 2 commits:** TaskStatus's new constructor parameter requires SqlTask to pass it. These changes don't compile independently, so they were committed together.
- **ThriftField(22) for outputsVersion:** Used next available ThriftField number after existing field 21 (runningPartitionedSplitsWeight).

## Deviations from Plan

### Minor Deviation

**1. Combined Task 1 and Task 2 into single commit**
- **Found during:** Task 1 verification
- **Issue:** TaskStatus constructor change requires SqlTask.createTaskStatus() to provide the new parameter
- **Resolution:** Committed both tasks together as they are interdependent
- **Impact:** No functional impact, both tasks implemented as specified

---

**Total deviations:** 1 (commit strategy, not functional)
**Impact on plan:** None - all functionality implemented as specified

## Issues Encountered

None

## Next Phase Readiness

- Worker infrastructure complete for filter storage and exposure
- Ready for Phase 5: Coordinator Filter Fetching
- Coordinator can now poll workers via GET /v1/task/{taskId}/outputs/filter/{version}
- TaskStatus.outputsVersion enables version-based change detection

---
*Phase: 04-worker-filter-storage-and-endpoint*
*Completed: 2026-01-09*
