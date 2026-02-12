# Dynamic Partition Pruning

## What This Is

Distributed dynamic partition pruning for Presto that extracts filters from hash join build sides on workers, distributes them to the coordinator, and applies them during split generation to prune partitions and files before reading data. This reduces unnecessary I/O in star schema joins where most fact table rows will be filtered out by selective dimension joins.

## Core Value

Prove that coordinator-collected dynamic filters can significantly reduce I/O by pruning partitions and files during split scheduling, validating the architecture for subsequent C++/Velox implementation.

## Requirements

### Validated

(None yet — ship to validate)

### Active

**Prototype (Java/Iceberg):**
- [ ] DynamicFilter SPI interface with future-based predicate access
- [ ] ConnectorSplitManager extension to receive DynamicFilter
- [ ] Session properties for enablement and configuration
- [ ] Coordinator infrastructure for filter collection and merging
- [ ] Worker-to-coordinator filter push via HTTP
- [ ] Iceberg connector partition/file pruning using dynamic filter
- [ ] Metrics for files considered/pruned/scheduled-before-filter
- [ ] Graceful degradation on filter timeout

**Full Implementation (C++/Velox):**
- [ ] Filter extraction on C++ workers via HashBuild operator
- [ ] HTTP endpoint for coordinator to fetch filters from workers
- [ ] Hive and Delta connector support
- [ ] Phase 2: Worker-side row-group and row-level filtering
- [ ] Cost-based optimizer rule (AddDynamicFilterRule)

### Out of Scope

- Row-level filtering in prototype — deferred to Phase 2 after architecture validated
- Cost-based optimizer rule in prototype — use session property for enablement
- Cross-query filter caching — filters are query-scoped per RFC
- Non-equijoin support — only hash equijoins supported per RFC
- Java worker support in production — prototype only; production targets C++/Velox
- Bloom filter integration — future work for high-cardinality joins

## Context

**RFC**: `RFC-DYNAMIC-PARTITION-PRUNING.md` contains the full design including:
- Two-phase rollout (coordinator-side pruning, then worker-side filtering)
- TupleDomain-based filter with discrete values or min/max range
- Version-based incremental filter collection protocol
- SPI extensions for ConnectorTableLayout and ConnectorSplitManager

**Prototype Plan Draft**: `DYNAMIC_PARTITION_PRUNING_PROTOTYPE_PLAN.md` outlines:
- 7-phase implementation targeting Java workers
- Reuses existing DynamicFilterSourceOperator for filter collection
- Targets Iceberg connector for partition/file pruning validation

**Existing Infrastructure**:
- `LocalDynamicFilter` — pattern for filter accumulation and merging
- `TupleDomain.columnWiseUnion()` — existing merge logic for partitioned joins
- `DynamicFilterSourceOperator` — already collects build-side filter values
- `JoinNode.getDynamicFilters()` — existing filter ID to column mapping

## Constraints

- **SPI Compatibility**: New SPI methods must have default implementations; no breaking changes to existing connectors
- **Pattern Consistency**: Follow existing Presto patterns (LocalDynamicFilter, HttpRemoteTask, etc.)
- **Opt-in Behavior**: Feature disabled by default; conservative defaults for wait times
- **Graceful Degradation**: Filter timeout must not fail queries; proceed without pruning benefit

## Key Decisions

| Decision | Rationale | Outcome |
|----------|-----------|---------|
| Java prototype before C++ | Faster iteration to validate design; lower risk | — Pending |
| Iceberg only for prototype | Has manifest statistics; cleanest integration point | — Pending |
| Session property enablement | Skip cost model complexity; focus on core mechanism | — Pending |
| TupleDomain over bloom filter | Enables partition/file pruning; bloom only helps row-level | — Pending |

---
*Last updated: 2026-01-08 after initialization*