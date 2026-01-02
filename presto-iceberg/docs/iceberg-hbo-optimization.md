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

### Two-Level Matching with Connector-Driven Selection

#### Phase 1: Version-Agnostic Lookup

Compute a version-agnostic hash that excludes the table version from the plan canonical form:

```
Original hash: hash(plan_structure + table_id + snapshot_id + predicates)
Version-agnostic hash: hash(plan_structure + table_id + predicates)
```

This allows retrieving all historical statistics entries across multiple table versions with
a single lookup.

#### Phase 2: Connector-Driven Selection

The engine passes ALL candidates to the connector. The connector owns all validation logic:

- Version/ancestry validation (is this an ancestor snapshot?)
- Drift checking (has data changed too much?)
- Branch awareness (reject stats from different branches)
- Tag handling (reject stats for old tagged versions querying recent data)
- Compensation calculation (adjust stats based on row count changes)

The engine does NOT pre-filter candidates. This consolidates all drift/version logic in one place.

### SPI Changes

#### ConnectorMetadata Extension

```java
/**
 * Select the best historical statistics match for the current table version.
 * Connector handles all version semantics (ancestry, drift, branches, tags).
 *
 * The engine passes ALL candidates from version-agnostic lookup without
 * pre-filtering. The connector owns all validation and drift checking.
 *
 * @param session The current session
 * @param tableHandle The table handle for the current query (includes current version)
 * @param candidates All historical statistics entries from version-agnostic lookup
 * @return Best match with optional compensation, or empty if none suitable
 */
default Optional<HistoricalStatisticsMatch> selectHistoricalStatistics(
    ConnectorSession session,
    ConnectorTableHandle tableHandle,
    List<HistoricalStatisticsEntry> candidates)
{
    // Default: exact match only (preserves current behavior for non-versioned connectors)
    return candidates.stream()
        .filter(e -> e.getTableHandle().equals(tableHandle))
        .findFirst()
        .map(e -> new HistoricalStatisticsMatch(e, Optional.empty()));
}

/**
 * Returns a version-agnostic identifier for the table, used for HBO hash computation.
 * Two handles for the same table at different versions should return equal identifiers.
 *
 * @param tableHandle The table handle (may include version info)
 * @return Identifier excluding version, or the handle itself if not versioned
 */
default Object getTableIdentifier(ConnectorTableHandle tableHandle)
{
    return tableHandle;  // Default: use full handle (current behavior)
}
```

#### Supporting Classes

```java
/**
 * An entry from HBO storage representing statistics captured at a specific table version.
 */
public class HistoricalStatisticsEntry {
    private final ConnectorTableHandle tableHandle;  // Includes version info
    private final PlanStatistics statistics;

    public ConnectorTableHandle getTableHandle() { return tableHandle; }
    public PlanStatistics getStatistics() { return statistics; }
}

/**
 * Result of connector's historical statistics selection.
 */
public class HistoricalStatisticsMatch {
    private final HistoricalStatisticsEntry entry;
    private final Optional<Double> rowCountCompensation;  // e.g., 1.15 for 15% more rows

    public HistoricalStatisticsEntry getEntry() { return entry; }
    public Optional<Double> getRowCountCompensation() { return rowCountCompensation; }
}
```

### Engine Flow

```
1. CANONICALIZATION
   - Call connector.getTableIdentifier(handle) for each table
   - Iceberg returns table UUID (excludes snapshot ID)
   - Build canonical plan hash using version-agnostic identifiers

2. HBO LOOKUP
   - Query HBO store with version-agnostic hash
   - Returns List<HistoricalStatisticsEntry> (all versions)

3. CONNECTOR SELECTION
   - Call connector.selectHistoricalStatistics(currentHandle, allCandidates)
   - Connector validates ancestry, drift, branches, tags
   - Connector returns best match or empty

4. APPLY STATISTICS
   - If match returned, use stats with optional compensation
   - If empty, no HBO stats for this node (fall back to connector stats)
```

### Iceberg Implementation

```java
@Override
public Object getTableIdentifier(ConnectorTableHandle tableHandle)
{
    IcebergTableHandle handle = (IcebergTableHandle) tableHandle;
    // Return identifier without snapshot - table UUID or schema.table path
    return handle.getSchemaTableName();
}

@Override
public Optional<HistoricalStatisticsMatch> selectHistoricalStatistics(
    ConnectorSession session,
    ConnectorTableHandle tableHandle,
    List<HistoricalStatisticsEntry> candidates)
{
    IcebergTableHandle current = (IcebergTableHandle) tableHandle;
    long currentSnapshotId = current.getSnapshotId().orElse(-1);

    Table table = loadTable(current);
    Snapshot currentSnapshot = table.snapshot(currentSnapshotId);

    // Build ancestor set for current snapshot
    Set<Long> ancestorIds = new HashSet<>();
    for (Snapshot s = currentSnapshot; s != null;
         s = s.parentId() != null ? table.snapshot(s.parentId()) : null) {
        ancestorIds.add(s.snapshotId());
    }

    // Find best ancestor match
    return candidates.stream()
        .filter(e -> {
            IcebergTableHandle h = (IcebergTableHandle) e.getTableHandle();
            long snapshotId = h.getSnapshotId().orElse(-1);
            // Only accept ancestors (handles branches, old tags correctly)
            return ancestorIds.contains(snapshotId);
        })
        .min(Comparator.comparingLong(e -> {
            // Prefer closest ancestor by snapshot distance
            IcebergTableHandle h = (IcebergTableHandle) e.getTableHandle();
            return snapshotDistance(table, currentSnapshotId, h.getSnapshotId().orElse(-1));
        }))
        .map(matched -> {
            // Compute compensation from manifest row counts
            double compensation = computeRowCountCompensation(
                table,
                ((IcebergTableHandle) matched.getTableHandle()).getSnapshotId().orElse(-1),
                currentSnapshotId);
            return new HistoricalStatisticsMatch(matched, Optional.of(compensation));
        });
}

private double computeRowCountCompensation(Table table, long fromSnapshot, long toSnapshot)
{
    Snapshot from = table.snapshot(fromSnapshot);
    Snapshot to = table.snapshot(toSnapshot);

    long fromRows = Long.parseLong(from.summary().get("total-records"));
    long toRows = Long.parseLong(to.summary().get("total-records"));

    return (double) toRows / fromRows;
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

- `selectHistoricalStatistics()` default returns exact match only
- `getTableIdentifier()` default returns full handle
- Behavior identical to current implementation
- No changes required

### Versioned Connectors (Iceberg, Delta, Hudi)

- Opt-in by implementing both methods
- Connector owns all version semantics
- Can be as simple or sophisticated as needed

### Migration

- Existing HBO statistics remain valid
- Version-agnostic hashes are new keys; old versioned hashes still work
- Gradual adoption as connectors implement the new interface

## Implementation Plan

### Phase 1: Confidence Level Fix

1. Update `TableStatisticsMaker` to return `FACT` for manifest-derived statistics
2. Add session property to control behavior during rollout
3. Verify TableScan stats prefer manifests over HBO

### Phase 2: SPI Extension

1. Add `HistoricalStatisticsEntry` and `HistoricalStatisticsMatch` to SPI
2. Add `selectHistoricalStatistics()` and `getTableIdentifier()` to `ConnectorMetadata`
3. Update HBO to compute version-agnostic hashes using `getTableIdentifier()`
4. Update HBO lookup to pass all candidates to connector
5. Remove engine-side row count similarity check when connector implements selection

### Phase 3: Iceberg Implementation

1. Implement `getTableIdentifier()` - return table UUID/path without snapshot
2. Implement `selectHistoricalStatistics()`:
   - Build ancestor chain from current snapshot
   - Filter candidates to ancestors only
   - Rank by snapshot distance
   - Compute row count compensation
3. Add unit tests for branch and tag scenarios

### Phase 4: Observability

1. Add metrics for match types (exact, ancestor, none)
2. Add metrics for compensation factors applied
3. Add debug logging for troubleshooting

## Metrics and Observability

### New Metrics

| Metric | Description |
|--------|-------------|
| `hbo.version_match.exact` | Count of exact version matches |
| `hbo.version_match.ancestor` | Count of ancestor version matches |
| `hbo.version_match.none` | Count of no matches (connector returned empty) |
| `hbo.compensation.factor` | Distribution of compensation factors applied |
| `hbo.confidence.override` | Count of connector stats overriding HBO |

### Logging

```
DEBUG: HBO statistics selection for table iceberg.db.events:
  Current snapshot: 102
  Candidates: [snapshot_100, snapshot_95, snapshot_80]
  Selected: snapshot_100 (ancestor, distance=2)
  Compensation: rowCount *= 1.15
```

## References

- [Presto HBO Implementation](../presto-main-base/src/main/java/com/facebook/presto/cost/HistoryBasedPlanStatisticsCalculator.java)
- [Iceberg Table Spec - Snapshots](https://iceberg.apache.org/spec/#snapshots)
- [Iceberg Manifest Files](https://iceberg.apache.org/spec/#manifest-files)
- [Plan Canonicalization Strategies](../presto-common/src/main/java/com/facebook/presto/common/plan/PlanCanonicalizationStrategy.java)
