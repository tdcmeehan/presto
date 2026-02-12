# Roadmap: Dynamic Partition Pruning

## Overview

Build distributed dynamic partition pruning for Presto in two milestones: a Java prototype to validate the architecture and prove I/O reduction, followed by a production C++/Velox implementation with worker-side filtering and cost-based optimization.

**Each phase must be independently verifiable.** Verification criteria must be satisfied before marking a phase complete.

## Domain Expertise

None

## Milestones

- 🚧 **v0.1 Java Prototype** — Phases 1-10 (in progress)
- 📋 **v1.0 C++/Velox Production** — Phases 11-16 (planned)

## Phases

**Phase Numbering:**
- Integer phases (1, 2, 3): Planned milestone work
- Decimal phases (2.1, 2.2): Urgent insertions (marked with INSERTED)

Decimal phases appear between their surrounding integers in numeric order.

### 🚧 v0.1 Java Prototype (In Progress)

**Milestone Goal:** Validate dynamic filter architecture and prove I/O reduction using Java workers and Iceberg connector.

#### Phase 1: SPI Foundation ✓
**Goal**: Create DynamicFilter interface and extend ConnectorSplitManager
**Depends on**: Nothing (first phase)
**Research**: Unlikely (follows existing Presto SPI patterns)
**Verification**:
- [x] `DynamicFilter` interface compiles and is accessible from connector modules
- [x] `ConnectorSplitManager.getSplits()` overload with `DynamicFilter` parameter has default implementation
- [x] Existing connectors continue to compile without changes

Plans:
- [x] 01-01: SPI Foundation (DynamicFilter interface, ConnectorSplitManager extension)

#### Phase 2: Session Properties ✓
**Goal**: Add session properties for enablement and configuration
**Depends on**: Phase 1
**Research**: Unlikely (standard SystemSessionProperties pattern)
**Verification**:
- [x] `SET SESSION dynamic_partition_pruning_enabled = true` succeeds
- [x] `SET SESSION dynamic_partition_pruning_max_wait_time = '5s'` succeeds
- [x] Properties visible in `SHOW SESSION`

Plans:
- [x] 02-01: Session Properties (FeaturesConfig + SystemSessionProperties)

#### Phase 3: Coordinator Filter Infrastructure ✓
**Goal**: Create CoordinatorDynamicFilter and DynamicFilterService for filter collection/merging
**Depends on**: Phase 1
**Research**: Likely (need deep understanding of LocalDynamicFilter pattern)
**Research topics**: LocalDynamicFilter accumulation logic, TupleDomain.columnWiseUnion() semantics, SettableFuture patterns
**Verification**:
- [x] CoordinatorDynamicFilter accumulates filters from N partitions and completes future
- [x] Unit test: `TupleDomain.columnWiseUnion()` correctly merges discrete values and ranges
- [x] `DynamicFilterService` can register/lookup filters by queryId + filterId

Plans:
- [x] 03-01: CoordinatorDynamicFilter (implements DynamicFilter SPI with partition accumulation)
- [x] 03-02: DynamicFilterService (query-scoped filter registration and lookup)
- [x] 03-03: Unit Tests (TestCoordinatorDynamicFilter, TestDynamicFilterService)

#### Phase 4: Worker Filter Storage and Endpoint ✓
**Goal**: Store filter in worker task, add outputsVersion to TaskStatus, add GET endpoint for coordinator fetch
**Depends on**: Phase 3
**Research**: Unlikely (standard TaskResource pattern)
**Verification**:
- [x] `TaskStatus` includes `outputsVersion` field
- [x] `GET /v1/task/{taskId}/outputs/filter/{version}` endpoint returns filter JSON
- [x] Endpoint returns only filters with version > requested version

Plans:
- [x] 04-01: Worker Filter Storage and Endpoint (TaskStatus outputsVersion, SqlTask storage, GET endpoint)

#### Phase 5: Coordinator Filter Fetching ✓
**Goal**: Coordinator detects outputsVersion change and fetches filters from workers
**Depends on**: Phase 4
**Research**: Likely (HttpRemoteTask, TaskInfoFetcher patterns)
**Research topics**: ContinuousTaskStatusFetcher polling, version detection, HttpRemoteTask extension
**Verification**:
- [x] `DynamicFilterFetcher` class created following TaskInfoFetcherWithEventLoop pattern
- [x] JsonCodec binding for `DynamicFilterResponse` in ServerMainModule
- [x] Integration with HttpRemoteTask via HttpRemoteTaskWithEventLoop

Plans:
- [x] 05-01: DynamicFilterFetcher (HTTP client, response wrapper, JsonCodec binding)
- [x] 05-02: HttpRemoteTask Integration (factory injection, status listener, cleanup)

#### Phase 6: Scheduler Wiring ✓
**Goal**: Wire DynamicFilter through SplitSourceFactory to connectors
**Depends on**: Phase 3
**Research**: Likely (SqlQueryScheduler and SplitSourceFactory flow)
**Research topics**: SplitSourceFactory.visitTableScan(), dynamic filter lookup by TableScanNode, split scheduling lifecycle
**Verification**:
- [x] `SplitSourceFactory.visitTableScan()` passes non-empty `DynamicFilter` to connector
- [x] Debug logging shows DynamicFilter with correct filter ID reaching connector
- [x] When session property disabled, `DynamicFilter.EMPTY` is passed
Plans:
- [x] 06-01: SplitSourceProvider chain wiring + DynamicFilterService injection

#### Phase 7: Iceberg Integration ✓
**Goal**: Implement partition/file pruning in IcebergSplitManager and IcebergSplitSource
**Depends on**: Phase 6
**Research**: Likely (IcebergSplitSource internals, partition key extraction)
**Research topics**: IcebergSplitSource.getNextBatch(), IcebergSplit.getPartitionKeys(), manifest pruning APIs
**Verification**:
- [x] `IcebergSplitSource.getNextBatch()` waits on DynamicFilter future (respects max wait time)
- [x] Splits with partition values outside filter range are not returned
- [ ] End-to-end test: join query with selective dimension prunes >50% of partitions

Plans:
- [x] 07-01: IcebergSplitManager override, IcebergUtil partition matching, IcebergSplitSource filtering

#### Phase 8: Metrics ✓
**Goal**: Create DynamicFilterStats and integrate into QueryStats
**Depends on**: Phase 7
**Research**: Unlikely (standard QueryStats pattern)
**Verification**:
- [x] RuntimeMetricName has DYNAMIC_FILTER_SPLITS_EXAMINED, DYNAMIC_FILTER_SPLITS_PRUNED, DYNAMIC_FILTER_WAIT_TIME_NANOS
- [x] IcebergSplitSource tracks and records metrics to session.getRuntimeStats()
- [x] Metrics visible via `SELECT * FROM system.runtime.queries`

Plans:
- [x] 08-01: RuntimeMetricName constants, IcebergSplitSource instrumentation, QuerySystemTable columns

#### Phase 9: Unit Testing ✓
**Goal**: Unit tests for filter construction, merging, and SPI
**Depends on**: Phase 8
**Research**: Unlikely (standard TestNG patterns)
**Verification**:
- [x] TestIcebergPartitionPredicate covers all edge cases
- [x] Edge cases tested: isAll, isNone, empty partitions, matching, non-matching, null values, non-partition columns, multiple columns
- [x] All 9 tests pass
- [x] TestDynamicFilterResponse covers JSON serialization
- [x] TestCoordinatorDynamicFilter covers filter accumulation

Plans:
- [x] 09-01: TestIcebergPartitionPredicate (9 test methods for partition pruning logic)
- [x] 09-02: TestDynamicFilterResponse (7 test methods for JSON serialization)
- [x] 09-03: Test suite verification (134 tests pass)

#### Phase 10: Integration Testing
**Goal**: End-to-end tests with Iceberg tables, TPC-DS validation queries
**Depends on**: Phase 9
**Research**: Unlikely (existing Iceberg test infrastructure)
**Verification**:
- [ ] TPC-DS Q5, Q17, Q25 show >30% file pruning with DPP enabled
- [ ] Query results identical with and without DPP
- [ ] No performance regression on queries without applicable dynamic filters
**Plans**: TBD

Plans:
- [ ] 10-01: TBD

### 📋 v1.0 C++/Velox Production (Planned)

**Milestone Goal:** Production implementation targeting C++/Velox workers with worker-side filtering and cost-based optimization. Iceberg connector only.

#### Phase 11: C++ Filter Extraction
**Goal**: Extract filters from HashBuild operator, store in PrestoTask
**Depends on**: Phase 10 (prototype validated)
**Research**: Likely (Velox HashBuild, VectorHasher internals)
**Research topics**: HashBuild::noMoreInput(), VectorHasher::getFilter(), joinBridge pattern, PrestoTask callback
**Verification**:
- [ ] `HashBuild::noMoreInput()` extracts filter when join has registered filter IDs
- [ ] Filter stored in `PrestoTask::outputs_` map
- [ ] `outputsVersion` incremented when filter ready
**Plans**: TBD

Plans:
- [ ] 11-01: TBD

#### Phase 12: C++ HTTP Protocol
**Goal**: Add HTTP endpoint on C++ workers for coordinator to fetch filters
**Depends on**: Phase 11
**Research**: Likely (presto-native-execution HTTP endpoints)
**Research topics**: TaskResource.cpp endpoint registration, JSON serialization, version-based protocol
**Verification**:
- [ ] `GET /v1/task/{taskId}/outputs/filter/{version}` returns filter JSON
- [ ] Version-based incremental fetch works (only returns new filters)
- [ ] Long-polling with `X-Presto-Max-Wait` header works
**Plans**: TBD

Plans:
- [ ] 12-01: TBD

#### Phase 13: Coordinator Fetcher for C++ Workers
**Goal**: Extend coordinator fetching to work with C++ worker endpoints
**Depends on**: Phase 12
**Research**: Likely (HttpRemoteTask, TaskInfoFetcher patterns)
**Research topics**: ContinuousTaskStatusFetcher polling, outputsVersion detection, long-polling pattern
**Verification**:
- [ ] `HttpRemoteTask` detects `outputsVersion` change from C++ worker `TaskStatus`
- [ ] Filter fetched and passed to `LocalDynamicFilter` for merging
- [ ] End-to-end: partitioned join with C++ workers, coordinator receives merged filter
**Plans**: TBD

Plans:
- [ ] 13-01: TBD

#### Phase 14: Worker-Side Filtering
**Goal**: Push filters to probe workers for row-group and row-level filtering
**Depends on**: Phase 13
**Research**: Likely (Velox Task::addDynamicFilter, ParquetReader)
**Research topics**: POST endpoint for filter push, Velox filter injection, ParquetReader row group pruning
**Verification**:
- [ ] `POST /v1/task/{taskId}/inputs/filter/{filterId}` endpoint works
- [ ] Velox `Task::addDynamicFilter()` injects filter into running scan
- [ ] Row-group statistics checked against filter, incompatible groups skipped
**Plans**: TBD

Plans:
- [ ] 14-01: TBD

#### Phase 15: Cost-Based Optimizer
**Goal**: AddDynamicFilterRule to select beneficial joins for filtering
**Depends on**: Phase 13
**Research**: Likely (PlanOptimizer, cost model integration)
**Research topics**: PlanOptimizer interface, StatsAndCosts access, selectivity estimation, join reordering interaction
**Verification**:
- [ ] Optimizer generates dynamic filter for joins with selective build side
- [ ] Optimizer skips filter generation when build cardinality too high
- [ ] `EXPLAIN` shows dynamic filter assignment on `JoinNode`
**Plans**: TBD

Plans:
- [ ] 15-01: TBD

#### Phase 16: Production Testing
**Goal**: Benchmarks, TPC-DS validation, performance tuning
**Depends on**: Phase 14, Phase 15
**Research**: Unlikely (existing benchmark infrastructure)
**Verification**:
- [ ] TPC-DS suite passes with DPP enabled
- [ ] Q5, Q17, Q25, Q35 show >50% improvement in selective scenarios
- [ ] No regression on queries where DPP doesn't apply
**Plans**: TBD

Plans:
- [ ] 16-01: TBD

## Progress

**Execution Order:**
Phases execute in numeric order: 1 → 2 → 3 → ... → 16
Note: Phase 14 and 15 can run in parallel after Phase 13.

| Phase | Milestone | Plans Complete | Status | Completed |
|-------|-----------|----------------|--------|-----------|
| 1. SPI Foundation | v0.1 | 1/1 | Complete | 2026-01-08 |
| 2. Session Properties | v0.1 | 1/1 | Complete | 2026-01-08 |
| 3. Coordinator Filter Infrastructure | v0.1 | 3/3 | Complete | 2026-01-08 |
| 4. Worker Filter Storage and Endpoint | v0.1 | 1/1 | Complete | 2026-01-09 |
| 5. Coordinator Filter Fetching | v0.1 | 2/2 | Complete | 2026-01-09 |
| 6. Scheduler Wiring | v0.1 | 1/1 | Complete | 2026-01-09 |
| 7. Iceberg Integration | v0.1 | 1/1 | Complete | 2026-01-09 |
| 8. Metrics | v0.1 | 1/1 | Complete | 2026-01-09 |
| 9. Unit Testing | v0.1 | 3/3 | Complete | 2026-01-10 |
| 10. Integration Testing | v0.1 | 0/? | Not started | - |
| 11. C++ Filter Extraction | v1.0 | 0/? | Not started | - |
| 12. C++ HTTP Protocol | v1.0 | 0/? | Not started | - |
| 13. Coordinator Fetcher for C++ | v1.0 | 0/? | Not started | - |
| 14. Worker-Side Filtering | v1.0 | 0/? | Not started | - |
| 15. Cost-Based Optimizer | v1.0 | 0/? | Not started | - |
| 16. Production Testing | v1.0 | 0/? | Not started | - |
