---
phase: 01-spi-foundation
plan: 01
subsystem: spi
tags: [dynamic-filter, connector-spi, tuple-domain, split-manager]

# Dependency graph
requires: []
provides:
  - DynamicFilter interface for coordinator-side partition pruning
  - ConnectorSplitManager.getSplits() overload accepting DynamicFilter
affects: [scheduler-wiring, iceberg-integration, coordinator-filter-infrastructure]

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "Default method SPI extension pattern for backward compatibility"
    - "CompletableFuture for async filter constraint delivery"

key-files:
  created:
    - presto-spi/src/main/java/com/facebook/presto/spi/connector/DynamicFilter.java
  modified:
    - presto-spi/src/main/java/com/facebook/presto/spi/connector/ConnectorSplitManager.java

key-decisions:
  - "Used CompletableFuture instead of ListenableFuture (standard in presto-spi)"
  - "Used com.facebook.airlift.units.Duration (presto-spi dependency)"

patterns-established:
  - "SPI default method pattern: new getSplits overload delegates to existing method"

issues-created: []

# Metrics
duration: 6min
completed: 2026-01-08
---

# Phase 1 Plan 1: SPI Foundation Summary

**DynamicFilter interface and ConnectorSplitManager extension for coordinator-side dynamic partition pruning**

## Performance

- **Duration:** 6 min
- **Started:** 2026-01-08T17:56:12Z
- **Completed:** 2026-01-08T18:02:24Z
- **Tasks:** 3
- **Files modified:** 2

## Accomplishments

- Created `DynamicFilter` interface with methods for columns covered, constraint future, wait timeout, and completion status
- Added `DynamicFilter.EMPTY` constant for disabled/not-applicable cases
- Extended `ConnectorSplitManager` with new `getSplits()` overload accepting `DynamicFilter` parameter
- Default implementation ensures backward compatibility - existing connectors unchanged

## Task Commits

Each task was committed atomically:

1. **Task 1: Create DynamicFilter interface** - `2a6f3eda922` (feat)
2. **Task 2: Add getSplits overload to ConnectorSplitManager** - `14f0326544e` (feat)
3. **Task 3: Verify existing connectors compile** - no commit (verification only)

## Files Created/Modified

- `presto-spi/src/main/java/com/facebook/presto/spi/connector/DynamicFilter.java` - New interface for dynamic filter constraints
- `presto-spi/src/main/java/com/facebook/presto/spi/connector/ConnectorSplitManager.java` - Added default method with DynamicFilter parameter

## Decisions Made

- **Used CompletableFuture instead of ListenableFuture:** Guava is only a test dependency in presto-spi, so we use java.util.concurrent.CompletableFuture which is consistent with other SPI interfaces (ConnectorSplitSource, ConnectorMetadata, etc.)
- **Used com.facebook.airlift.units.Duration:** This is the Duration class available in presto-spi dependencies, not io.airlift.units.Duration

## Deviations from Plan

None - plan executed exactly as written.

## Issues Encountered

- **Initial import errors:** First implementation used Guava's ListenableFuture and io.airlift.units.Duration which are not available as compile dependencies in presto-spi. Fixed by switching to CompletableFuture and com.facebook.airlift.units.Duration.

## Next Phase Readiness

- SPI foundation complete, ready for Phase 2 (Session Properties)
- DynamicFilter interface provides the contract for filter passing
- ConnectorSplitManager provides the extension point for connectors

---
*Phase: 01-spi-foundation*
*Completed: 2026-01-08*
