# Project State

## Project Reference

See: .planning/PROJECT.md (updated 2026-01-08)

**Core value:** Prove that coordinator-collected dynamic filters can significantly reduce I/O by pruning partitions and files during split scheduling
**Current focus:** Phase 10 — Integration Testing — COMPLETE

## Current Position

Phase: 10 of 16 (Integration Testing)
Plan: 1 of 1 in current phase
Status: ✅ **COMPLETE** — All 3 DPP integration tests pass
Last activity: 2026-01-22 — Fixed filter merge bug, all tests passing

Progress: ██████████ 80%

## Test Results

**All 3 DPP integration tests pass:**
```
Tests run: 3, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

**Test cases:**
1. `testDynamicPartitionPruningMetrics` - ✅ PASS
2. `testDynamicPartitionPruningResultCorrectness` - ✅ PASS
3. `testDynamicPartitionPruningDisabled` - ✅ PASS

## Resolved Issues

### Session 5 Fixes (Final)

**Critical Bug Fix - SqlTask filter merge:**
- **Problem**: Multiple drivers on same task were OVERWRITING each other's filter data using `put()`
- **Solution**: Changed to `merge()` with `TupleDomain.columnWiseUnion` in SqlTask.java:636-656
- **Result**: Filter now correctly collects all customer IDs (ranges:10) from all workers

**Async Split Source Pattern:**
- IcebergSplitSource now returns incomplete CompletableFuture when filter not ready
- Scheduler can continue scheduling other stages (including build-side) while waiting
- Uses `getCurrentConstraintByColumnName()` instead of future value to get latest merged constraint

**Collection Window Mechanism:**
- CoordinatorDynamicFilter waits 1000ms after first non-empty partition before completing
- Allows more partitions to arrive before completing the future
- Continues accepting partitions even after future completion for subsequent batches

### Previous Session Fixes (Sessions 2-4)

- ClassLoaderSafeConnectorSplitManager 5-param getSplits override ✓
- DynamicFilterService putIfAbsent for filter registration ✓
- Probe-side variable tracking in SplitSourceFactory ✓
- SqlTask outputsVersion propagation ✓
- DynamicFilterFetcher HTTP polling ✓
- CoordinatorDynamicFilter filter collection ✓
- expectedPartitions changed from 1 to 4 (matches DistributedQueryRunner parallelism)

## Performance Metrics

**Velocity:**
- Total plans completed: 14
- Average duration: 9 min
- Total execution time: 1.8 hours

**By Phase:**

| Phase | Plans | Total | Avg/Plan |
|-------|-------|-------|----------|
| 01-spi-foundation | 1 | 6 min | 6 min |
| 02-session-properties | 1 | 8 min | 8 min |
| 03-coordinator-filter-infrastructure | 3 | 10 min | 3 min |
| 04-worker-filter-storage-and-endpoint | 1 | 8 min | 8 min |
| 05-coordinator-filter-fetching | 2 | 23 min | 12 min |
| 06-scheduler-wiring | 1 | 8 min | 8 min |
| 07-iceberg-integration | 1 | 8 min | 8 min |
| 08-metrics | 1 | 5 min | 5 min |
| 09-unit-testing | 3 | 42 min | 14 min |

**Recent Trend:**
- Last 5 plans: 8 min, 5 min, 30 min, 10 min, 2 min
- Trend: ↓

## Accumulated Context

### Decisions

Decisions are logged in PROJECT.md Key Decisions table.
Recent decisions affecting current work:

- Java prototype before C++ implementation
- Iceberg connector only (skip Hive/Delta)
- Session property enablement (skip cost-based optimizer in prototype)
- CompletableFuture for DynamicFilter (presto-spi pattern)
- com.facebook.airlift.units.Duration for timeout (presto-spi dependency)
- Default enabled = false (conservative opt-in)
- Default max wait time = 2s (conservative timeout)
- Duration properties use VARCHAR type with Duration.valueOf parser
- DynamicFilterResponse wrapper for cleaner JsonCodec binding
- TupleDomain<String> to TupleDomain<ColumnHandle> conversion deferred to Phase 6
- Trigger filter fetching in status listener callback for event loop safety
- Pass codec through factory chain rather than injecting directly into task
- Use node ID as filter ID for prototype (production would correlate with JoinNode)
- Filter lookup returns EMPTY when not found (graceful degradation)
- Wait for DynamicFilter in both SplitManager and SplitSource for flexibility
- Graceful degradation: proceed without filtering on timeout/failure
- Record metrics in SplitSource.close() for final aggregated counts
- Use RuntimeStats from ConnectorSession for session-scoped metric aggregation
- Real IcebergColumnHandle and HivePartitionKey instances for testing (no mocks)
- Bootstrap pattern for JsonCodec setup with BlockJsonSerde for TupleDomain tests
- Register filters in SqlQueryExecution before scheduling (not in LocalExecutionPlanner)
- Create coordinator-only operator factory for distributed DPP when LocalDynamicFilter unavailable
- String-keyed TupleDomain storage in CoordinatorDynamicFilter to avoid premature ColumnHandle conversion

### Deferred Issues

- ~~**TaskStatus outputsVersion not reflecting stored filters**~~: RESOLVED - outputsVersion IS propagating correctly.
- ~~**Stage scheduling prevents filter arrival**~~: RESOLVED - Async split source pattern allows scheduler to continue while waiting for filter.
- ~~**Multiple drivers overwriting filter data**~~: RESOLVED - Changed `put()` to `merge()` with `columnWiseUnion` in SqlTask.

### Blockers/Concerns

- None — Phase 10 integration testing complete with all tests passing.

## Session Continuity

Last session: 2026-01-22
Stopped at: Phase 10 COMPLETE — All integration tests passing
Resume file: .planning/phases/10-integration-testing/10-01-SUMMARY.md

**Session 5 accomplishments** (2026-01-22):
- **CRITICAL FIX**: Fixed SqlTask filter merge bug
  - Multiple drivers on same task were overwriting each other's filter data using `put()`
  - Changed to `merge()` with `TupleDomain.columnWiseUnion`
  - Filter now correctly collects all customer IDs from all workers
- Implemented async split source pattern in IcebergSplitSource
  - Returns incomplete future when filter not ready, allowing scheduler to continue
  - Uses `getCurrentConstraintByColumnName()` for latest merged constraint
- Added collection window mechanism in CoordinatorDynamicFilter
  - Waits 1000ms after first non-empty partition before completing
  - Continues accepting partitions after completion for subsequent batches
- Changed expectedPartitions from 1 to 4 (matches DistributedQueryRunner parallelism)
- **ALL 3 DPP INTEGRATION TESTS NOW PASS**:
  - testDynamicPartitionPruningMetrics ✅
  - testDynamicPartitionPruningResultCorrectness ✅
  - testDynamicPartitionPruningDisabled ✅

**Session 4 accomplishments**:
- Added instance-level tracing (System.identityHashCode) to SqlTask
- Confirmed outputsVersion IS propagating correctly (Session 3 hypothesis was WRONG)
- Verified DynamicFilterFetcher IS being created and triggered
- Verified CoordinatorDynamicFilter IS receiving filters and completing
- **Identified TRUE root cause**: Stage scheduling issue
  - Build-side Stage 1 not scheduled until AFTER split enumeration timeout
  - Filter arrives ~0.2s after timeout, regardless of timeout duration (60s, 90s, 180s)
- Reduced test data sizes in TestDynamicPartitionPruning.java for faster iteration

**Session 3 accomplishments**:
- Fixed probe-side variable lookup bug in `registerDynamicFiltersFromPlan()`
  - Was using build-side variable from `JoinNode.getDynamicFilters()`
  - Now correctly uses probe-side variable from `EquiJoinClause.getLeft()`
- Verified session property IS working (resolved Session 2 concern)
- Confirmed filter now applied to correct table (fact_orders, not dim_customers)

**Session 2 accomplishments**:
- Fixed ClassLoaderSafeConnectorSplitManager 5-param getSplits override
- Fixed DynamicFilterService putIfAbsent for filter registration
- Added probe-side variable tracking (probeVariables map in DynamicFilterService)
- Modified SplitSourceFactory to only apply filter to probe-side TableScans

**Next steps**:
1. Move to Phase 11 — E2E testing with real workloads
2. Consider production hardening (remove trace logging, optimize collection window)
3. Verify pruning actually reduces I/O (not just correctness)
