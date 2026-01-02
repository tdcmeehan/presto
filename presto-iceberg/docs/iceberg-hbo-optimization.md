# Version-Aware History Based Optimizer for Iceberg

## Overview

This document describes enhancements to Presto's History Based Optimizer (HBO) to leverage
Iceberg's table versioning capabilities for improved query optimization accuracy.

## Background

### History Based Optimizer (HBO)

HBO improves query planning by learning from historical query executions. It:

1. **Canonicalizes query plans** - Normalizes plans to identify structurally similar queries
2. **Stores execution statistics** - Records actual row counts, output sizes, join cardinalities
3. **Applies historical stats** - Uses past statistics to improve cost estimation for similar queries

HBO uses multiple canonicalization strategies with decreasing specificity:

| Strategy | Description | Use Case |
|----------|-------------|----------|
| `CONNECTOR` | Removes partition column constants | Matches queries across partition values |
| `IGNORE_SAFE_CONSTANTS` | Also removes projection constants | Broader matching |
| `IGNORE_SCAN_CONSTANTS` | Removes all predicate constants from scan | Maximum reuse |

### Current Limitations

The current HBO implementation is connector-agnostic and treats table versions as part of the
plan identity. For Iceberg tables:

1. **Cache misses on version changes** - Each snapshot produces a different plan hash, causing
   HBO to miss even when the table has minor changes
2. **No lineage awareness** - HBO doesn't understand that snapshot N+1 may be a direct
   descendant of snapshot N with only incremental changes
3. **Underutilized manifest statistics** - Iceberg provides exact statistics from manifests,
   but they compete equally with HBO's historical estimates

### Iceberg Table Versioning

Iceberg tables have explicit versioning through snapshots:

- Each write operation creates a new snapshot with a unique ID
- Snapshots form a directed acyclic graph (DAG) with parent-child relationships
- Snapshots are immutable - same snapshot ID guarantees identical data
- Manifest files contain exact statistics (row counts, min/max bounds, null counts)

## Problem Statement

Consider a daily ETL pipeline that appends data to an Iceberg table:

```
Day 1: snapshot_100 (1M rows) → HBO learns statistics
Day 2: snapshot_101 (1.1M rows) → HBO cache miss (different hash)
Day 3: snapshot_102 (1.2M rows) → HBO cache miss (different hash)
```

Each day, HBO treats the table as completely new, losing valuable historical statistics even
though the data changes are incremental (~10% growth).

Additionally, for TableScan nodes, Iceberg's manifest statistics are exact for the current
snapshot, but HBO may override them with potentially stale historical estimates.

## Design

### Two-Level Matching with Version-Aware Selection

#### Phase 1: Version-Agnostic Lookup

Compute a version-agnostic hash that excludes the table version from the plan canonical form:

```
Original hash: hash(plan_structure + table_id + snapshot_id + predicates)
Version-agnostic hash: hash(plan_structure + table_id + predicates)
```

This allows retrieving all historical statistics entries across multiple table versions with
a single lookup.

#### Phase 2: Version-Aware Selection

The connector determines the best matching version from available historical entries:

1. **Exact match** - If statistics exist for the current snapshot, use directly
2. **Ancestor match** - If statistics exist for an ancestor snapshot, the connector can:
   - Use the historical stats as-is (conservative)
   - Compute compensation factors from manifest deltas (precise)
3. **No match** - Fall back to connector-provided statistics

### SPI Changes

#### ConnectorMetadata Extension

```java
/**
 * Selects the best historical version match for HBO statistics.
 *
 * @param session The current session
 * @param tableHandle The current table handle (includes current version)
 * @param availableVersions List of versions with available HBO statistics
 * @return The best matching version with optional compensation metadata,
 *         or empty if no suitable match exists
 */
default Optional<HistoricalVersionMatch> selectHistoricalVersion(
    ConnectorSession session,
    ConnectorTableHandle tableHandle,
    List<ConnectorTableVersion> availableVersions)
{
    // Default implementation: no version-aware matching
    // Non-versioned connectors (Hive) return empty, preserving current behavior
    return Optional.empty();
}
```

#### Supporting Classes

```java
/**
 * Represents a table version identifier that connectors can use for matching.
 */
public interface ConnectorTableVersion {
    /**
     * Opaque version identifier (e.g., snapshot ID for Iceberg)
     */
    String getVersionId();

    /**
     * When this version was created
     */
    Instant getTimestamp();

    /**
     * Summary statistics from HBO for this version
     */
    PlanStatistics getStatistics();
}

/**
 * Result of version matching with optional compensation.
 */
public class HistoricalVersionMatch {
    private final ConnectorTableVersion matchedVersion;
    private final Optional<StatisticsCompensation> compensation;

    // Compensation allows adjusting historical stats based on version delta
    // e.g., if matched version had 1M rows and current has 1.1M,
    // compensation factor = 1.1
}
```

### Iceberg Implementation

```java
@Override
public Optional<HistoricalVersionMatch> selectHistoricalVersion(
    ConnectorSession session,
    ConnectorTableHandle tableHandle,
    List<ConnectorTableVersion> availableVersions)
{
    IcebergTableHandle icebergHandle = (IcebergTableHandle) tableHandle;
    long currentSnapshotId = icebergHandle.getSnapshotId().orElse(-1);

    Table table = loadTable(icebergHandle);
    Snapshot currentSnapshot = table.snapshot(currentSnapshotId);

    // Build ancestor chain for the current snapshot
    Set<Long> ancestorIds = new HashSet<>();
    for (Snapshot s = currentSnapshot; s != null; s = s.parentId() != null ?
            table.snapshot(s.parentId()) : null) {
        ancestorIds.add(s.snapshotId());
    }

    // Find the closest ancestor with statistics
    return availableVersions.stream()
        .filter(v -> ancestorIds.contains(Long.parseLong(v.getVersionId())))
        .min(Comparator.comparingLong(v ->
            Math.abs(currentSnapshot.timestampMillis() - v.getTimestamp().toEpochMilli())))
        .map(matched -> {
            // Optionally compute compensation from manifest deltas
            Optional<StatisticsCompensation> compensation =
                computeCompensation(table, matched, currentSnapshot);
            return new HistoricalVersionMatch(matched, compensation);
        });
}

private Optional<StatisticsCompensation> computeCompensation(
    Table table,
    ConnectorTableVersion matched,
    Snapshot current)
{
    long matchedSnapshotId = Long.parseLong(matched.getVersionId());
    Snapshot matchedSnapshot = table.snapshot(matchedSnapshotId);

    // Use snapshot summaries to compute delta
    long matchedRows = Long.parseLong(
        matchedSnapshot.summary().get("total-records"));
    long currentRows = Long.parseLong(
        current.summary().get("total-records"));

    double rowCountFactor = (double) currentRows / matchedRows;

    return Optional.of(new StatisticsCompensation(rowCountFactor));
}
```

### Confidence Level Enhancement

#### Current Behavior

Both HBO and Iceberg connector return `HIGH` confidence:

| Source | Confidence | Ordinal |
|--------|------------|---------|
| HBO historical stats | HIGH | 1 |
| Iceberg manifest stats | HIGH | 1 |

The comparison uses `>=`, so HBO wins on ties - even when manifest statistics are more accurate.

#### Enhanced Behavior

Iceberg should return `FACT` confidence when statistics are derived from current snapshot manifests:

```java
// In TableStatisticsMaker.java
private TableStatistics makeTableStatistics(...) {
    // ...
    TableStatistics.Builder result = TableStatistics.builder();
    result.setRowCount(Estimate.of(recordCount));

    // Manifest-derived stats are ground truth for current snapshot
    if (isCurrentSnapshotStats) {
        result.setConfidenceLevel(FACT);
    }
    // ...
}
```

#### Confidence Level Semantics

| Level | Ordinal | Meaning | Example |
|-------|---------|---------|---------|
| LOW | 0 | Rough estimate | Heuristic-based guess |
| HIGH | 1 | Good estimate | HBO historical, sampled stats |
| FACT | 2 | Ground truth | Manifest exact counts |

#### Result by Plan Node Type

| Node Type | Winner | Rationale |
|-----------|--------|-----------|
| TableScan | Connector (FACT) | Manifests have exact current stats |
| JoinNode | HBO (HIGH) | Connector has no join cardinality |
| AggregationNode | HBO (HIGH) | Connector has no aggregation stats |
| FilterNode | HBO (HIGH) | Selectivity from historical execution |

### Statistics Comparison

#### What HBO Captures (for all nodes)

```java
PlanStatistics {
    rowCount: long,           // Actual output rows
    outputSize: long,         // Actual output bytes
    confidence: double,       // Always 1.0
    joinNodeStatistics: {     // For JoinNode only
        nullJoinBuildKeyCount: long,
        nullJoinProbeKeyCount: long,
        joinBuildKeyCount: long,
        joinProbeKeyCount: long
    },
    partialAggregationStatistics: {  // For AggregationNode only
        inputRowCount: long,
        outputRowCount: long
    }
}
```

#### What Iceberg Manifests Provide

| Metric | Granularity | Accuracy |
|--------|-------------|----------|
| Row count | Per-file, aggregated | Exact |
| File size | Per-file, aggregated | Exact |
| Min/max bounds | Per-column, per-file | Exact |
| Null counts | Per-column, per-file | Exact |
| NDV (via puffin) | Per-column | Estimated (theta sketch) |
| Histograms (via puffin) | Per-column | Estimated (KLL sketch) |

#### Comparison for TableScan

| Metric | HBO | Manifests | Better |
|--------|-----|-----------|--------|
| Row count | Historical (may be stale) | Exact for current | Manifests |
| Output size | Historical | Exact | Manifests |
| Column bounds | Not captured | Exact | Manifests |
| Null fraction | Not captured | Exact | Manifests |
| Predicate selectivity | Learned from history | Must estimate | HBO |

## Backwards Compatibility

### Non-Versioned Connectors (Hive, etc.)

- `selectHistoricalVersion()` returns `Optional.empty()` by default
- Falls back to existing hash-based exact matching
- No behavioral changes

### Versioned Connectors (Iceberg, Delta)

- Opt-in to version-aware matching by implementing `selectHistoricalVersion()`
- Can choose level of sophistication:
  - Simple: Return closest ancestor by timestamp
  - Advanced: Include compensation factors from manifest deltas

### Migration

- Existing HBO statistics remain valid
- New version-agnostic hashes computed alongside existing hashes
- Gradual adoption as connectors implement the new interface

## Implementation Plan

### Phase 1: Confidence Level Fix

1. Update `TableStatisticsMaker` to return `FACT` for manifest-derived statistics
2. Add session property to control behavior during rollout
3. Verify TableScan stats prefer manifests over HBO

### Phase 2: SPI Extension

1. Add `ConnectorTableVersion` and `HistoricalVersionMatch` to SPI
2. Add `selectHistoricalVersion()` to `ConnectorMetadata` with default implementation
3. Update HBO lookup to use version-agnostic hashes

### Phase 3: Iceberg Implementation

1. Implement `selectHistoricalVersion()` in Iceberg connector
2. Add ancestor detection using snapshot parent chain
3. Implement optional compensation using snapshot summaries

### Phase 4: Advanced Compensation

1. Use manifest file deltas for precise row count adjustment
2. Consider partition-level statistics for filtered queries
3. Add metrics and logging for match quality

## Metrics and Observability

### New Metrics

| Metric | Description |
|--------|-------------|
| `hbo.version_match.exact` | Count of exact version matches |
| `hbo.version_match.ancestor` | Count of ancestor version matches |
| `hbo.version_match.none` | Count of no matches |
| `hbo.compensation.factor` | Distribution of compensation factors applied |
| `hbo.confidence.override` | Count of connector stats overriding HBO |

### Logging

```
DEBUG: HBO version match for table iceberg.db.events:
  Current version: snapshot_102
  Matched version: snapshot_100 (ancestor, 2 generations)
  Compensation: rowCount *= 1.15
  Match quality: 0.92
```

## References

- [Presto HBO Implementation](../presto-main-base/src/main/java/com/facebook/presto/cost/HistoryBasedPlanStatisticsCalculator.java)
- [Iceberg Table Spec - Snapshots](https://iceberg.apache.org/spec/#snapshots)
- [Iceberg Manifest Files](https://iceberg.apache.org/spec/#manifest-files)
- [Plan Canonicalization Strategies](../presto-common/src/main/java/com/facebook/presto/common/plan/PlanCanonicalizationStrategy.java)
