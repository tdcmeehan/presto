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

### Core Principles

1. **HBO for current snapshot only** - Time-travel queries skip HBO entirely
2. **Version-agnostic hashing** - Remove snapshot ID from plan hash for better hit rate
3. **Manifest stats for TableScan** - Use FACT confidence so connector stats win

### SPI Changes

Two simple methods added to `ConnectorMetadata`:

```java
interface ConnectorMetadata {

    /**
     * Returns a version-agnostic identifier for the table, used for HBO hash computation.
     * Two handles for the same table at different versions should return equal identifiers.
     *
     * This enables HBO hits across snapshot changes for current-snapshot queries.
     *
     * @param tableHandle The table handle (may include version info)
     * @return Identifier excluding version, or the handle itself if not versioned
     */
    default Object getTableIdentifier(ConnectorTableHandle tableHandle) {
        return tableHandle;  // Default: use full handle (current behavior)
    }

    /**
     * Returns true if the table handle refers to the current/latest version.
     *
     * HBO only persists and retrieves statistics for current-version queries.
     * Time-travel queries (explicit snapshot, tag, timestamp) skip HBO and
     * rely on connector-provided statistics instead.
     *
     * @param tableHandle The table handle to check
     * @return true if this is the current version, false for time-travel queries
     */
    default boolean isCurrentVersion(ConnectorTableHandle tableHandle) {
        return true;  // Default: assume current (non-versioned connectors)
    }
}
```

### Engine Flow

```
1. CHECK VERSION
   - Call connector.isCurrentVersion(handle) for each table
   - If ANY table is not current version → skip HBO entirely for this query

2. CANONICALIZATION (if current version)
   - Call connector.getTableIdentifier(handle) for each table
   - Iceberg returns table UUID (excludes snapshot ID)
   - Build canonical plan hash using version-agnostic identifiers

3. HBO LOOKUP
   - Query HBO store with version-agnostic hash
   - Hit rate improved: snapshot_100 and snapshot_101 share same hash

4. HBO WRITE (after query execution)
   - Only if isCurrentVersion() was true
   - Persist stats with version-agnostic hash
   - Overwrites previous entry (no accumulation needed)
```

### Example Flow

```
Day 1: Query on snapshot_100 (latest)
  ├─ isCurrentVersion() → true
  ├─ Hash: hash(plan + table_uuid)     ← no snapshot ID
  ├─ HBO lookup: MISS
  ├─ Execute query
  └─ HBO write: persist stats

Day 2: Query on snapshot_101 (latest)
  ├─ isCurrentVersion() → true
  ├─ Hash: hash(plan + table_uuid)     ← same hash!
  ├─ HBO lookup: HIT (day 1 stats)
  ├─ Execute query
  └─ HBO write: update stats

Day 2: Time-travel query FOR VERSION AS OF 'snapshot_100'
  ├─ isCurrentVersion() → false
  ├─ HBO: skipped entirely
  └─ Uses manifest stats (FACT confidence)
```

### Iceberg Implementation

```java
@Override
public Object getTableIdentifier(ConnectorTableHandle tableHandle) {
    IcebergTableHandle handle = (IcebergTableHandle) tableHandle;
    // Return identifier without snapshot - table UUID or schema.table path
    return handle.getSchemaTableName();
}

@Override
public boolean isCurrentVersion(ConnectorTableHandle tableHandle) {
    IcebergTableHandle handle = (IcebergTableHandle) tableHandle;

    // No explicit snapshot = querying current
    if (handle.getSnapshotId().isEmpty()) {
        return true;
    }

    // Explicit snapshot - check if it matches current
    Table table = loadTable(handle);
    Snapshot current = table.currentSnapshot();
    return current != null
        && current.snapshotId() == handle.getSnapshotId().get();
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
    result.setConfidenceLevel(FACT);
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

- `getTableIdentifier()` default returns full handle (current behavior)
- `isCurrentVersion()` default returns true (HBO always enabled)
- Behavior identical to current implementation
- No changes required

### Versioned Connectors (Iceberg, Delta, Hudi)

- Implement `getTableIdentifier()` to exclude version from hash
- Implement `isCurrentVersion()` to detect time-travel queries
- HBO automatically works better with no other changes

### Migration

- Existing HBO statistics remain valid
- New version-agnostic hashes will gradually replace old entries
- No explicit migration needed

## Implementation Plan

### Phase 1: Confidence Level Fix

1. Update `TableStatisticsMaker` to return `FACT` for manifest-derived statistics
2. Add session property to control behavior during rollout
3. Verify TableScan stats prefer manifests over HBO

### Phase 2: SPI Extension

1. Add `getTableIdentifier()` to `ConnectorMetadata` with default implementation
2. Add `isCurrentVersion()` to `ConnectorMetadata` with default implementation
3. Update HBO canonicalization to use `getTableIdentifier()`
4. Update HBO read/write to check `isCurrentVersion()`

### Phase 3: Iceberg Implementation

1. Implement `getTableIdentifier()` - return schema.table (no snapshot)
2. Implement `isCurrentVersion()` - check if snapshot matches current
3. Add unit tests for time-travel scenarios

## Metrics and Observability

### New Metrics

| Metric | Description |
|--------|-------------|
| `hbo.skipped.time_travel` | Count of queries skipping HBO due to time-travel |
| `hbo.hit.cross_version` | Count of HBO hits where stats came from different snapshot |
| `hbo.confidence.override` | Count of connector stats overriding HBO |

### Logging

```
DEBUG: HBO for table iceberg.db.events:
  isCurrentVersion: true
  tableIdentifier: iceberg.db.events (version-agnostic)
  HBO lookup: HIT

DEBUG: HBO skipped for table iceberg.db.events:
  isCurrentVersion: false (time-travel query)
  Using connector stats (FACT confidence)
```

## Summary

This design achieves version-aware HBO with minimal changes:

| Aspect | Approach |
|--------|----------|
| Storage model | Unchanged (no accumulation) |
| Hash computation | Version-agnostic via `getTableIdentifier()` |
| Time-travel queries | Skip HBO via `isCurrentVersion()` |
| TableScan accuracy | Manifest FACT confidence wins |
| Join/Agg accuracy | HBO provides historical stats |
| Backwards compatibility | Full (defaults preserve current behavior) |

## References

- [Presto HBO Implementation](../presto-main-base/src/main/java/com/facebook/presto/cost/HistoryBasedPlanStatisticsCalculator.java)
- [Iceberg Table Spec - Snapshots](https://iceberg.apache.org/spec/#snapshots)
- [Iceberg Manifest Files](https://iceberg.apache.org/spec/#manifest-files)
- [Plan Canonicalization Strategies](../presto-common/src/main/java/com/facebook/presto/common/plan/PlanCanonicalizationStrategy.java)
