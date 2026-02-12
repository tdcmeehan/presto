---
phase: 06-scheduler-wiring
plan: 01
subsystem: scheduler
tags: [dynamic-filter, split-manager, scheduler, presto-main]

# Dependency graph
requires:
  - phase: 03
    provides: CoordinatorDynamicFilter and DynamicFilterService
  - phase: 01
    provides: DynamicFilter SPI interface
provides:
  - DynamicFilter parameter in SplitSourceProvider chain
  - DynamicFilterService injection into split scheduling
  - Filter lookup in visitTableScan during split generation
affects: [iceberg-integration, hive-integration]

# Tech tracking
tech-stack:
  added: []
  patterns:
    - SPI default method pattern for backward compatibility
    - Service injection through factory chain

key-files:
  created: []
  modified:
    - presto-main-base/src/main/java/com/facebook/presto/split/SplitSourceProvider.java
    - presto-main-base/src/main/java/com/facebook/presto/split/CloseableSplitSourceProvider.java
    - presto-main-base/src/main/java/com/facebook/presto/split/SplitManager.java
    - presto-main-base/src/main/java/com/facebook/presto/sql/planner/SplitSourceFactory.java
    - presto-main-base/src/main/java/com/facebook/presto/execution/SqlQueryExecution.java

key-decisions:
  - "Use node ID as filter ID for prototype simplicity"
  - "Filter lookup returns EMPTY when not found (graceful degradation)"
  - "Default implementation delegates to 4-param overload for backward compatibility"

patterns-established:
  - "Default method pattern for adding parameters to SPI interfaces"
  - "Service injection through execution factory chain"

issues-created: []

# Metrics
duration: 8min
completed: 2026-01-09
---

# Phase 6 Plan 1: Scheduler Wiring Summary

**DynamicFilter wired through SplitSourceProvider chain to ConnectorSplitManager, with DynamicFilterService lookup in visitTableScan**

## Performance

- **Duration:** 8 min
- **Started:** 2026-01-09T15:20:00Z
- **Completed:** 2026-01-09T15:28:00Z
- **Tasks:** 3
- **Files modified:** 5

## Accomplishments

- Added 5-param getSplits overload with DynamicFilter to SplitSourceProvider, CloseableSplitSourceProvider, and SplitManager
- Wired DynamicFilterService into SplitSourceFactory via SqlQueryExecution factory chain
- Implemented filter lookup in visitTableScan that respects session property
- Added debug logging showing filter lookup results for observability

## Task Commits

Each task was committed atomically:

1. **Task 1: Add DynamicFilter parameter to SplitSourceProvider chain** - `5edd43c10b1` (feat)
2. **Task 2: Wire DynamicFilterService into SplitSourceFactory** - `3c7940e312e` (feat)
3. **Task 3: Verify full compilation and logging** - (verification only, no code changes)

**Plan metadata:** TBD (this commit)

## Files Created/Modified

- `presto-main-base/.../SplitSourceProvider.java` - Added 5-param getSplits overload with default implementation
- `presto-main-base/.../CloseableSplitSourceProvider.java` - Added delegating override for 5-param method
- `presto-main-base/.../SplitManager.java` - Added getSplits overload that passes DynamicFilter to connector
- `presto-main-base/.../SplitSourceFactory.java` - Inject DynamicFilterService, lookup filter in visitTableScan
- `presto-main-base/.../SqlQueryExecution.java` - Pass DynamicFilterService through factory chain

## Decisions Made

- Used node ID as filter ID for prototype simplicity (production would correlate with JoinNode)
- Filter lookup returns DynamicFilter.EMPTY when not found (graceful degradation - allows queries to run without filters)
- Added default implementation to SplitSourceProvider for backward compatibility with existing implementations

## Deviations from Plan

None - plan executed exactly as written.

## Issues Encountered

None

## Next Phase Readiness

Phase 6 complete. Ready for Phase 7: Iceberg Integration.
- DynamicFilter is now passed through the entire split scheduling chain
- Connectors receive DynamicFilter in getSplits() 5-param overload
- When session property enabled, SplitSourceFactory looks up filter from DynamicFilterService
- Debug logging provides visibility into filter propagation

---
*Phase: 06-scheduler-wiring*
*Completed: 2026-01-09*
