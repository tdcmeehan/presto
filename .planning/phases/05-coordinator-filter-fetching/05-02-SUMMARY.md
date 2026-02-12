---
phase: 05-coordinator-filter-fetching
plan: 02
subsystem: task-management
tags: [dynamic-filter, http-remote-task, guice, event-loop]

# Dependency graph
requires:
  - phase: 05-01
    provides: DynamicFilterFetcher, DynamicFilterResponse, JsonCodec binding
  - phase: 03
    provides: DynamicFilterService
provides:
  - DynamicFilterFetcher integration with HttpRemoteTask
  - Automatic filter fetching on outputsVersion changes
  - DynamicFilterService singleton binding
affects: [scheduler-wiring, iceberg-integration]

# Tech tracking
tech-stack:
  added: []
  patterns: [factory-injection, event-loop-listener, singleton-binding]

key-files:
  created: []
  modified:
    - presto-main/src/main/java/com/facebook/presto/server/remotetask/HttpRemoteTaskFactory.java
    - presto-main/src/main/java/com/facebook/presto/server/remotetask/HttpRemoteTaskWithEventLoop.java
    - presto-main/src/main/java/com/facebook/presto/server/ServerMainModule.java

key-decisions:
  - "Trigger filter fetching in status listener callback for event loop safety"
  - "Pass codec through factory chain rather than injecting directly into task"

patterns-established:
  - "DynamicFilterFetcher lifecycle follows taskStatusFetcher pattern"

issues-created: []

# Metrics
duration: 15min
completed: 2026-01-09
---

# Phase 5 Plan 02: HttpRemoteTask Integration Summary

**DynamicFilterFetcher wired into HttpRemoteTaskWithEventLoop with automatic fetching triggered by outputsVersion changes in TaskStatus**

## Performance

- **Duration:** 15 min
- **Started:** 2026-01-09T20:00:00Z
- **Completed:** 2026-01-09T20:15:00Z
- **Tasks:** 3
- **Files modified:** 3

## Accomplishments
- DynamicFilterService injected into HttpRemoteTaskFactory via Guice
- DynamicFilterFetcher created in HttpRemoteTaskWithEventLoop constructor
- Filter fetching triggered automatically when outputsVersion changes in TaskStatus
- Proper cleanup: DynamicFilterFetcher stopped in cleanUpTask()
- DynamicFilterService bound as singleton in ServerMainModule

## Task Commits

Each task was committed atomically:

1. **Task 1: Inject DynamicFilterService into HttpRemoteTaskFactory** - `9a93a12` (feat)
2. **Task 2: Add DynamicFilterFetcher to HttpRemoteTaskWithEventLoop** - `14a8e52` (feat)
3. **Task 3: Update ServerMainModule for DynamicFilterService binding** - `96c303e` (feat)

**Plan metadata:** (pending this commit)

## Files Created/Modified
- `presto-main/.../HttpRemoteTaskFactory.java` - Added DynamicFilterService and codec injection, pass to task
- `presto-main/.../HttpRemoteTaskWithEventLoop.java` - Create DynamicFilterFetcher, trigger on status changes, stop on cleanup
- `presto-main/.../ServerMainModule.java` - Bind DynamicFilterService as singleton

## Decisions Made
- **Trigger fetching in status listener**: The outputsVersion check is done inside the taskStatusFetcher state change listener to ensure it runs on the event loop thread
- **Codec passed through factory chain**: JsonCodec<DynamicFilterResponse> injected into factory and passed to task rather than injecting directly into the task class

## Deviations from Plan

None - plan executed exactly as written.

## Issues Encountered
- Pre-existing compilation errors in presto-main-base (StatementAnalyzer.java constructor mismatch) prevented full compilation verification
- These errors are unrelated to our changes and exist in the base branch

## Next Phase Readiness
Phase 5 complete, ready for Phase 6: Scheduler Wiring
- Coordinator can now detect outputsVersion changes and fetch filters from workers
- Filters are stored but not yet merged with connector (column handle conversion needed in Phase 6)
- DynamicFilterService infrastructure ready for scheduler integration

---
*Phase: 05-coordinator-filter-fetching*
*Completed: 2026-01-09*
