---
phase: 03-coordinator-filter-infrastructure
plan: 02
subsystem: coordinator
tags: [dynamic-filter, service, registry, concurrent-map]

# Dependency graph
requires:
  - phase: 03-01
    provides: CoordinatorDynamicFilter class for accumulating filter constraints
provides:
  - DynamicFilterService for managing filter lifecycle across queries
  - Query-scoped filter registration and lookup
  - Thread-safe filter cleanup on query completion
affects: [scheduler-wiring, worker-communication]

# Tech tracking
tech-stack:
  added: []
  patterns: [ConcurrentMap nested storage, Optional lookups, ImmutableMap defensive copies]

key-files:
  created:
    - presto-main-base/src/main/java/com/facebook/presto/execution/scheduler/DynamicFilterService.java
  modified: []

key-decisions:
  - "Nested ConcurrentMap structure: outer keyed by QueryId, inner by filterId"
  - "No Guice dependencies yet - plain POJO for easier testing"
  - "Caller-managed cleanup via removeFiltersForQuery() - no automatic expiration"

patterns-established:
  - "Query-scoped service pattern: ConcurrentMap<QueryId, ConcurrentMap<K, V>>"
  - "Null-safe lookup pattern: return Optional.empty() or empty map for missing query"

issues-created: []

# Metrics
duration: 3min
completed: 2026-01-08
---

# Phase 3 Plan 2: DynamicFilterService Summary

**Thread-safe service for managing CoordinatorDynamicFilter lifecycle with query-scoped registration, lookup, and cleanup**

## Performance

- **Duration:** 3 min
- **Started:** 2026-01-08T18:54:58Z
- **Completed:** 2026-01-08T18:58:24Z
- **Tasks:** 2
- **Files modified:** 1

## Accomplishments

- Created DynamicFilterService as central coordinator registry for dynamic filters
- Implemented registerFilter/getFilter/removeFiltersForQuery for full lifecycle management
- Added hasFilter() for quick existence checks and getAllFiltersForQuery() for debugging
- All methods are thread-safe and null-safe

## Task Commits

Each task was committed atomically:

1. **Task 1: Create DynamicFilterService class** - `0ecf0af4f26` (feat)
2. **Task 2: Add helper methods** - `13d49e5c361` (feat)

**Plan metadata:** (to be committed with this summary)

## Files Created/Modified

- `presto-main-base/src/main/java/com/facebook/presto/execution/scheduler/DynamicFilterService.java` - Query-scoped filter registry service

## Decisions Made

- Used nested ConcurrentMap structure (QueryId -> filterId -> filter) following QueryTracker pattern
- No Guice @Inject yet - will be added when wired into scheduler (keeps service testable as plain POJO)
- Cleanup is caller's responsibility via removeFiltersForQuery() - no automatic expiration or background cleanup

## Deviations from Plan

None - plan executed exactly as written.

## Issues Encountered

None

## Next Phase Readiness

- DynamicFilterService ready for unit testing in Phase 9
- Ready for Guice integration when wiring into scheduler (Phase 6)
- All methods return Optional or defensive copies for safe concurrent access

---
*Phase: 03-coordinator-filter-infrastructure*
*Completed: 2026-01-08*
