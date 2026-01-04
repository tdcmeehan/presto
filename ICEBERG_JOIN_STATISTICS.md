# Iceberg-Based Join Statistics for Presto

## Design Document

**Status**: Draft
**Authors**: Tim Meehan
**Date**: January 2025
**Version**: 0.7

---

## 1. Executive Summary

This document describes an approach to accurate join cardinality estimation using **connector-native sketches** stored in Iceberg table metadata. The key insight is that join fanout can be computed from two composable sketches:

1. **Theta Sketch** - Which keys exist (**Already implemented in Presto/Iceberg**)
2. **Frequency Sketch** - Frequency of each key (new addition, using DataSketches `LongsSketch`)

By storing these sketches per column in Iceberg metadata, we can compute pairwise join statistics at query planning time without runtime overhead.

### Why This Approach

| Challenge | Solution |
|-----------|----------|
| Unknown join selectivity | Theta intersection estimates matching keys |
| Unknown join fanout | Frequency sketch estimates output rows |
| Storage overhead | Connector-native, no separate infrastructure |
| Collection overhead | Periodic ANALYZE, not per-query |
| Staleness | Tied to table metadata versioning |

---

## 2. Implementation Status

### 2.1 Theta Sketch: Ready to Use ✅

**Good news**: Theta sketches are already fully implemented in Presto for Iceberg tables and can be used for join estimation with minimal changes.

#### Current Implementation

| Component | Location | Status |
|-----------|----------|--------|
| Aggregation Function | `ThetaSketchAggregationFunction.java` | ✅ Implemented |
| SQL Function | `sketch_theta(column)` | ✅ Available |
| Puffin Storage | Blob type `apache-datasketches-theta-v1` | ✅ Implemented |
| ANALYZE Integration | `TableStatisticsMaker.java` | ✅ Works |
| Statistics Reading | `readNDVBlob()` | ✅ Works |

#### Key Code Paths

**Collection (ANALYZE)**:
```
ANALYZE TABLE orders
  → IcebergAbstractMetadata.getStatisticsCollectionMetadata()
  → TableStatisticsMaker.getSupportedColumnStatistics()
  → SQL: "RETURN sketch_theta(column_name)"
  → ThetaSketchAggregationFunction.input/merge/output()
  → TableStatisticsMaker.writeTableStatistics()
  → Puffin file with blob type "apache-datasketches-theta-v1"
```

**Storage**: `presto-iceberg/src/main/java/com/facebook/presto/iceberg/TableStatisticsMaker.java:128-132`
```java
private static final String ICEBERG_THETA_SKETCH_BLOB_TYPE_ID = "apache-datasketches-theta-v1";
```

**Blob Generation**: `TableStatisticsMaker.java:413-426`
```java
private static Blob generateNDVBlob(ColumnStatisticMetadata metadata, Block value,
        Table icebergTable, Snapshot snapshot, TypeManager typeManager) {
    int id = getField(metadata, icebergTable, snapshot).fieldId();
    ByteBuffer raw = VARBINARY.getSlice(value, 0).toByteBuffer();
    CompactSketch sketch = CompactSketch.wrap(Memory.wrap(raw, ByteOrder.nativeOrder()));
    return new Blob(
            ICEBERG_THETA_SKETCH_BLOB_TYPE_ID,
            ImmutableList.of(id),
            snapshot.snapshotId(),
            snapshot.sequenceNumber(),
            raw,  // ← Full sketch binary, not just NDV estimate
            null,
            ImmutableMap.of("ndv", Long.toString((long) sketch.getEstimate())));
}
```

#### What We Get for Free

1. **Full Theta Sketch Binary**: The Puffin blob stores the complete `CompactSketch` bytes, not just the NDV estimate
2. **Set Operations**: Can perform intersection via `SetOperation.builder().buildIntersection()`
3. **Retained Hashes**: Can enumerate via `sketch.getCache()` for CMS frequency lookups
4. **Per-column Storage**: Already stored per field ID in Puffin files

#### What Needs to Change

1. **Expose Raw Sketch**: Current `readNDVBlob()` only extracts the NDV property. Need to also deserialize the full sketch:

```java
// New method needed in TableStatisticsMaker or new JoinStatisticsProvider
public static CompactSketch readThetaSketch(BlobMetadata metadata, ByteBuffer blob) {
    return CompactSketch.wrap(Memory.wrap(blob, ByteOrder.nativeOrder()));
}
```

2. **Cache Sketches**: Add caching for deserialized sketches alongside the existing `StatisticsFileCache`

3. **Optimizer Integration**: Wire sketch retrieval into cost estimation rules

### 2.2 Frequency Sketch: Needs Implementation 🔨

Frequency estimation is not currently implemented. We'll use **Apache DataSketches `LongsSketch`** for cross-platform compatibility.

---

## 3. Frequency Sketch Implementation Plan

### 3.1 Why DataSketches LongsSketch (Not Count-Min Sketch)

| Criteria | stream-lib CMS | DataSketches LongsSketch |
|----------|----------------|--------------------------|
| **Cross-platform** | ❌ Java only | ✅ Java, C++, Python binary compatible |
| **Already in classpath** | ❌ New dependency | ✅ Already used for Theta/KLL |
| **Error bounds** | Upper only (overestimates) | Upper AND lower bounds |
| **Algorithm** | Hash-based counters | Heavy hitters / frequent items |
| **Native execution** | ❌ Not portable | ✅ Compatible with presto-native |

**Decision**: Use **DataSketches `LongsSketch`** for frequency estimation.

From the [DataSketches docs](https://datasketches.apache.org/docs/Architecture/LargeScale.html):
> "By design, a sketch that is available in one language that is also available in a different language will be 'binary compatible' via serialization."

The `LongsSketch` is optimized for `long` keys (join keys are typically integers or hashed strings) and has a smaller serialization footprint than generic `ItemsSketch<T>`.

### 3.2 LongsSketch Characteristics

**Heavy Hitters Algorithm**:
- Tracks frequently occurring items with guaranteed error bounds
- For any item: `(Upper Bound - Lower Bound) ≤ W × ε`, where W = total count, ε = 3.5/maxMapSize
- If fewer than 0.75 × maxMapSize distinct items, frequencies are **exact**

**Trade-off for Join Estimation**:
- ✅ Excellent for high-frequency keys (which dominate join output anyway)
- ⚠️ May have wider bounds for low-frequency keys
- ✅ For low-frequency keys, can fall back to average fanout: `row_count / NDV`

### 3.3 New Components

Following the established pattern from Theta/KLL sketches:

#### A. Aggregation Function

**File**: `presto-main-base/src/main/java/com/facebook/presto/operator/aggregation/sketch/frequency/FrequencySketchAggregationFunction.java`

```java
@AggregationFunction(value = "sketch_frequency", isCalledOnNullInput = true)
@Description("calculates a frequency sketch for estimating item occurrence counts")
public class FrequencySketchAggregationFunction {

    @InputFunction
    @TypeParameter("T")
    public static void input(
            @TypeParameter("T") Type type,
            @AggregationState FrequencySketchAggregationState state,
            @SqlType("T") @BlockPosition Block block,
            @BlockIndex int position) {
        if (block.isNull(position)) {
            return;
        }
        LongsSketch sketch = state.getSketch();
        long hash = computeHash(type, block, position);  // Same hashing as Theta
        sketch.update(hash);  // Default weight = 1
    }

    @CombineFunction
    public static void merge(
            @AggregationState FrequencySketchAggregationState state,
            @AggregationState FrequencySketchAggregationState otherState) {
        state.getSketch().merge(otherState.getSketch());
    }

    @OutputFunction(StandardTypes.VARBINARY)
    public static void output(
            @AggregationState FrequencySketchAggregationState state,
            BlockBuilder out) {
        byte[] bytes = state.getSketch().toByteArray();
        VARBINARY.writeSlice(out, Slices.wrappedBuffer(bytes));
    }
}
```

#### B. State Classes

**Files**:
- `FrequencySketchAggregationState.java` - Interface wrapping `LongsSketch`
- `FrequencySketchStateFactory.java` - Creates sketch with configurable `maxMapSize`
- `FrequencySketchStateSerializer.java` - Uses DataSketches native serialization

```java
// State factory - configurable accuracy
public class FrequencySketchStateFactory {
    // maxMapSize controls accuracy vs memory trade-off
    // Default: 1024 → ~8KB sketch, ε ≈ 0.34%
    // Higher: 8192 → ~64KB sketch, ε ≈ 0.04%
    private static final int DEFAULT_MAX_MAP_SIZE = 1024;

    public LongsSketch createSketch(int maxMapSize) {
        return new LongsSketch(maxMapSize);
    }
}
```

#### C. Scalar Functions

**File**: `presto-main-base/src/main/java/com/facebook/presto/operator/scalar/FrequencySketchFunctions.java`

```java
@ScalarFunction("frequency_estimate")
@Description("Estimates the frequency of a value, returns (lower_bound, estimate, upper_bound)")
@SqlType("row(lower bigint, estimate bigint, upper bigint)")
public static Block estimateFrequency(
        @SqlType(StandardTypes.VARBINARY) Slice sketchBytes,
        @SqlType(StandardTypes.BIGINT) long item) {
    LongsSketch sketch = LongsSketch.getInstance(Memory.wrap(sketchBytes.getBytes()));
    Row row = sketch.getRow(item);  // Returns frequency bounds
    // Return structured result with lower, estimate, upper bounds
}

@ScalarFunction("frequency_heavy_hitters")
@Description("Returns items with frequency above threshold")
@SqlType("array(row(item bigint, lower bigint, upper bigint))")
public static Block getHeavyHitters(
        @SqlType(StandardTypes.VARBINARY) Slice sketchBytes,
        @SqlType(StandardTypes.BIGINT) long threshold) {
    LongsSketch sketch = LongsSketch.getInstance(Memory.wrap(sketchBytes.getBytes()));
    Row[] rows = sketch.getFrequentItems(threshold, ErrorType.NO_FALSE_NEGATIVES);
    // Return array of heavy hitters
}
```

### 3.4 Puffin Integration

**File**: `presto-iceberg/src/main/java/com/facebook/presto/iceberg/TableStatisticsMaker.java`

#### New Constants

```java
// Use DataSketches-style naming for potential future standardization
private static final String ICEBERG_FREQUENCY_SKETCH_BLOB_TYPE_ID = "apache-datasketches-fi-longs-v1";
private static final String ICEBERG_FREQUENCY_TOTAL_COUNT_KEY = "total_count";
private static final String ICEBERG_FREQUENCY_NUM_ACTIVE_KEY = "num_active_items";
```

#### Register Generators/Readers

```java
// Add to puffinStatWriters map (line 148-152)
.put(FREQUENCY_SKETCH, TableStatisticsMaker::generateFrequencyBlob)

// Add to puffinStatReaders map (line 154-158)
.put(ICEBERG_FREQUENCY_SKETCH_BLOB_TYPE_ID, TableStatisticsMaker::readFrequencyBlob)
```

#### Generator Function

```java
private static Blob generateFrequencyBlob(ColumnStatisticMetadata metadata, Block value,
        Table icebergTable, Snapshot snapshot, TypeManager typeManager) {
    int id = getField(metadata, icebergTable, snapshot).fieldId();
    ByteBuffer raw = VARBINARY.getSlice(value, 0).toByteBuffer();

    // Deserialize to extract metadata
    LongsSketch sketch = LongsSketch.getInstance(Memory.wrap(raw, ByteOrder.LITTLE_ENDIAN));

    return new Blob(
            ICEBERG_FREQUENCY_SKETCH_BLOB_TYPE_ID,
            ImmutableList.of(id),
            snapshot.snapshotId(),
            snapshot.sequenceNumber(),
            raw,
            null,
            ImmutableMap.of(
                ICEBERG_FREQUENCY_TOTAL_COUNT_KEY, Long.toString(sketch.getStreamLength()),
                ICEBERG_FREQUENCY_NUM_ACTIVE_KEY, Long.toString(sketch.getNumActiveItems())));
}
```

#### Reader Function

```java
private static void readFrequencyBlob(BlobMetadata metadata, ByteBuffer blob,
        ColumnStatistics.Builder statistics, Table icebergTable, TypeManager typeManager) {
    LongsSketch sketch = LongsSketch.getInstance(Memory.wrap(blob, ByteOrder.LITTLE_ENDIAN));
    statistics.setFrequencySketch(Optional.of(sketch));
}
```

### 3.5 ANALYZE Integration

**File**: `TableStatisticsMaker.java:669-695` - `getSupportedColumnStatistics()`

```java
// Add frequency collection for join-eligible types
if (isNumericType(type) || type.equals(DATE) || isVarcharType(type) ||
        type.equals(TIMESTAMP) || type.equals(TIMESTAMP_WITH_TIME_ZONE)) {
    // Existing Theta for NDV
    supportedStatistics.add(NUMBER_OF_DISTINCT_VALUES.getColumnStatisticMetadataWithCustomFunction(
            columnName, format("RETURN sketch_theta(%s)", formatIdentifier(columnName)),
            ImmutableList.of(columnName)));

    // NEW: Frequency sketch for fanout estimation
    supportedStatistics.add(FREQUENCY_SKETCH.getColumnStatisticMetadataWithCustomFunction(
            columnName,
            format("RETURN sketch_frequency(%s)", formatIdentifier(columnName)),
            ImmutableList.of(columnName)));
}
```

### 3.6 Configuration

**File**: `IcebergSessionProperties.java`

```java
public static final String FREQUENCY_SKETCH_MAX_MAP_SIZE = "frequency_sketch_max_map_size";

// maxMapSize controls accuracy vs memory trade-off
// 1024 (default) → ~8KB sketch, handles up to ~768 distinct heavy hitters exactly
// 8192           → ~64KB sketch, handles up to ~6144 distinct heavy hitters exactly
```

---

## 4. Join Estimation Algorithm

With both Theta and Frequency sketches available, join estimation becomes:

```java
public JoinEstimate computeJoinEstimate(
        CompactSketch leftTheta,
        LongsSketch leftFreq,
        long leftRowCount,
        CompactSketch rightTheta,
        LongsSketch rightFreq,
        long rightRowCount) {

    // 1. Compute key intersection using Theta sketches
    Intersection intersection = SetOperation.builder().buildIntersection();
    intersection.intersect(leftTheta);
    intersection.intersect(rightTheta);
    CompactSketch intersectionSketch = intersection.getResult();

    double matchingKeys = intersectionSketch.getEstimate();
    double leftNDV = leftTheta.getEstimate();
    double rightNDV = rightTheta.getEstimate();

    // 2. Compute containment
    double containmentLR = matchingKeys / leftNDV;  // L keys in R
    double containmentRL = matchingKeys / rightNDV; // R keys in L

    // 3. Estimate output using frequency sketches
    //    For each retained hash in intersection, multiply frequencies
    long sampleOutput = 0;
    long[] hashes = intersectionSketch.getCache();

    // Fallback fanout for keys not tracked by frequency sketch
    double avgFanoutL = (double) leftRowCount / leftNDV;
    double avgFanoutR = (double) rightRowCount / rightNDV;

    for (long hash : hashes) {
        // LongsSketch.getEstimate returns 0 for untracked items
        long freqL = leftFreq.getEstimate(hash);
        long freqR = rightFreq.getEstimate(hash);

        // Fall back to average if frequency unknown (low-frequency key)
        if (freqL == 0) freqL = (long) avgFanoutL;
        if (freqR == 0) freqR = (long) avgFanoutR;

        sampleOutput += freqL * freqR;
    }

    // 4. Scale by theta (accounts for sampling in sketch)
    double thetaL = leftTheta.getTheta();
    double thetaR = rightTheta.getTheta();
    double scaleFactor = 1.0 / (thetaL * thetaR);
    double totalOutput = sampleOutput * scaleFactor;

    // 5. Compute bidirectional fanout
    double lrFanout = totalOutput / leftRowCount;
    double rlFanout = totalOutput / rightRowCount;

    return new JoinEstimate(totalOutput, lrFanout, rlFanout, containmentLR, containmentRL);
}
```

**Note**: The `LongsSketch` heavy-hitter algorithm naturally prioritizes high-frequency keys, which typically dominate join output. For low-frequency keys (not tracked), we fall back to the average fanout derived from `row_count / NDV`.

---

## 5. Background

### 5.1 The Join Estimation Problem

Join cardinality estimation requires knowing:

```
|A ⋈ B| = |A| × containment(A→B) × fanout(B)
```

Where:
- **containment(A→B)**: Fraction of A's keys that exist in B
- **fanout(B)**: Average B rows per matching key

Traditional NDV-based estimation assumes uniform distribution and fails on skewed data.

### 5.2 Iceberg's Existing Statistics

Iceberg already maintains per-column statistics in manifest files and Puffin files:

**Manifest-level stats**:
```java
public interface FieldSummary {
    boolean containsNull();
    Boolean containsNan();
    ByteBuffer lowerBound();
    ByteBuffer upperBound();
    Long valueCount();
    Long nullValueCount();
}
```

**Puffin blob types** (from [Iceberg Puffin Spec](https://iceberg.apache.org/puffin-spec/)):
- `apache-datasketches-theta-v1` - Theta sketch for NDV
- `deletion-vector-v1` - Deletion vectors

**Presto extensions** (custom blob types):
- `presto-sum-data-size-bytes-v1` - Column data size
- `presto-kll-sketch-bytes-v1` - KLL histogram for quantiles

### 5.3 What's Missing for Join Estimation

| Needed | Current Status | Solution |
|--------|---------------|----------|
| Theta sketch binary access | ✅ Stored in Puffin | Expose via new reader |
| Theta intersection | ✅ DataSketches API | Use `SetOperation.buildIntersection()` |
| Frequency sketch | ❌ Not implemented | Add DataSketches `LongsSketch` as new statistic type |
| Optimizer integration | ❌ Not wired | New cost estimation rule |

---

## 6. Data Structures

### 6.1 Theta Sketch (Already in Iceberg)

**Purpose**: Estimate distinct key count and set intersection.

**Properties**:
- Fixed size: ~16-128 KB (k=16384 default)
- Mergeable: Union operation
- Supports intersection: Estimates |A ∩ B|
- Stores actual key hashes (can enumerate retained hashes)

**Presto SQL**:
```sql
SELECT sketch_theta(customer_id) FROM orders
```

### 6.2 Frequency Sketch (New Addition)

**Purpose**: Estimate frequency of each key (rows per key).

**Implementation**: Apache DataSketches `LongsSketch` (heavy hitters algorithm)

**Properties**:
- Fixed size: ~8-64 KB (configurable via maxMapSize)
- Mergeable: Yes, via `merge()` operation
- Point-queryable: Given key hash, estimate its count
- Error bounds: Provides BOTH upper and lower bounds
- Cross-platform: Binary compatible across Java, C++, Python

**Proposed SQL**:
```sql
SELECT sketch_frequency(customer_id) FROM orders
```

### 6.3 Combined Join Column Sketch

```java
public class JoinColumnSketch {
    private final CompactSketch thetaSketch;   // Key existence + hashes
    private final LongsSketch freqSketch;      // Key frequencies (DataSketches)
    private final long rowCount;               // Total rows
    private final Instant collectedAt;         // For staleness
}
```

---

## 7. Storage in Iceberg

### 7.1 Puffin Files (Recommended)

Sketches are stored in Puffin files, which Presto already uses for statistics:

```
table-location/
├── metadata/
│   ├── v1.metadata.json
│   └── {queryId}-{uuid}.stats    ← Puffin file with sketch blobs
└── data/
    └── ...
```

Each Puffin blob contains:
- `type`: Blob type ID (e.g., `apache-datasketches-theta-v1`, `apache-datasketches-fi-longs-v1`)
- `fields`: List of column IDs
- `snapshot-id`, `sequence-number`: For versioning
- `properties`: Metadata (e.g., NDV estimate, total count)
- Binary payload: Serialized sketch

### 7.2 Statistics File Management

Presto already handles:
- Finding closest statistics file for a snapshot (`getClosestStatisticsFileForSnapshot`)
- Caching statistics per column (`StatisticsFileCache`)
- Scaling stats based on row count changes

---

## 8. ANALYZE Command

### 8.1 Current Syntax

```sql
ANALYZE schema.table_name;
ANALYZE schema.table_name WITH (columns = ARRAY['col1', 'col2']);
```

### 8.2 Proposed Extension

```sql
-- Analyze with join sketches (collects both Theta and CMS)
ANALYZE TABLE orders FOR COLUMNS (id, cust_id) WITH SKETCHES;

-- Analyze with sampling (faster for large tables)
ANALYZE TABLE orders FOR COLUMNS (id, cust_id)
    WITH SKETCHES
    SAMPLE 10 PERCENT;
```

---

## 9. Statistics Lifecycle and Cross-Engine Behavior

### 9.1 How Statistics Files Are Stored

Statistics files are stored **separately from snapshots** in the table metadata:

```json
{
  "format-version": 2,
  "table-uuid": "...",
  "current-snapshot-id": 123456,
  "snapshots": [ ... ],
  "statistics": [
    {
      "snapshot-id": 123450,
      "statistics-path": "s3://bucket/table/metadata/abc-123.stats",
      "file-size-in-bytes": 16384,
      "file-footer-size-in-bytes": 1024,
      "blob-metadata": [ ... ]
    }
  ]
}
```

Key points:
- Statistics files are in a **separate list** from snapshots
- Each statistics file is **associated with a snapshot ID** but not embedded in the snapshot
- Multiple statistics files can exist for different snapshots

### 9.2 Cross-Engine Writes: Statistics Are NOT Clobbered ✅

When Spark (or another engine that doesn't write statistics) updates the table:

```
Before Spark write:
  - Snapshot 100 (current)
  - Statistics file for snapshot 100 ← Presto wrote this

After Spark INSERT:
  - Snapshot 100
  - Snapshot 101 (current, new)  ← Spark created this
  - Statistics file for snapshot 100 ← STILL EXISTS!
```

**Why statistics survive:**
1. Statistics files are stored in the `statistics` list at the table metadata level
2. Spark's write creates a new snapshot but doesn't modify the `statistics` list
3. The Iceberg spec states: *"Statistics are informational. A reader can choose to ignore statistics information."*
4. Engines that don't support statistics simply **ignore** them, they don't delete them

### 9.3 How Presto Handles Stale Statistics

When the current snapshot doesn't have statistics, Presto finds the **closest** statistics file:

```java
// From TableStatisticsMaker.getClosestStatisticsFileForSnapshot()
return icebergTable.statisticsFiles()
    .stream()
    .min((first, second) -> {
        // 1. Compare by timestamp difference
        long firstDiff = abs(target.timestampMillis() - firstSnap.timestampMillis());
        long secondDiff = abs(target.timestampMillis() - secondSnap.timestampMillis());

        // 2. Weight by row count difference (configurable)
        if (targetTotalRecords.isPresent() && firstTotalRecords.isPresent()) {
            double weight = getStatisticSnapshotRecordDifferenceWeight(session);
            firstDiff += (long) (weight * abs(firstTotalRecords.get() - targetTotal));
        }
        return Long.compare(firstDiff, secondDiff);
    });
```

**Selection criteria:**
1. **Timestamp proximity**: Prefer statistics from snapshots closer in time
2. **Row count similarity**: Weight by how different the row counts are (configurable via session property)
3. **Scaling**: NDV values are scaled by `current_rows / stats_rows` ratio

### 9.4 Statistics Staleness Scenarios

| Scenario | Statistics Behavior | Accuracy Impact |
|----------|---------------------|-----------------|
| **Spark INSERT** | Uses older stats, scaled by row ratio | Good if distribution unchanged |
| **Spark UPDATE** | Uses older stats | May be stale if keys changed |
| **Spark DELETE** | Uses older stats, scaled down | Good for uniform deletes |
| **Schema evolution** | Column ID mismatch → no stats for new columns | Falls back to defaults |
| **Snapshot expiration** | Old stats files may be cleaned up | Need to re-ANALYZE |

### 9.5 Statistics Cleanup

Statistics files can be removed by:

1. **`expire_snapshots`**: Cleans up statistics files for expired snapshots
   ```sql
   CALL iceberg.system.expire_snapshots('db.table', TIMESTAMP '2024-01-01 00:00:00')
   ```

2. **`remove_orphan_files`**: Does NOT delete valid statistics files (they're in the valid file list)

3. **Manual removal**: Via Iceberg API
   ```java
   UpdateStatistics statsUpdate = table.updateStatistics();
   table.statisticsFiles().stream()
       .map(StatisticsFile::snapshotId)
       .forEach(statsUpdate::removeStatistics);
   statsUpdate.commit();
   ```

### 9.6 Recommendations for Production

1. **Re-ANALYZE periodically**: After significant data changes (>20% row count change)
2. **Re-ANALYZE after major updates**: If join key distributions change
3. **Monitor staleness**: Track `collectedAt` timestamp vs current snapshot
4. **Configure weight**: Tune `statistic_snapshot_record_difference_weight` for your workload
5. **Coordinate with Spark jobs**: Schedule ANALYZE after major Spark ETL jobs

### 9.7 Future: Incremental Statistics Updates

Potential improvement: Instead of full re-ANALYZE, merge statistics from new data:

```java
// Hypothetical incremental update
LongsSketch existingSketch = loadFromPuffin(oldStatsFile);
LongsSketch newDataSketch = computeForNewFiles(appendedFiles);
existingSketch.merge(newDataSketch);  // LongsSketch supports merge!
writeToPuffin(existingSketch, newSnapshotId);
```

This would require tracking which files contributed to existing statistics.

---

## 10. Size and Accuracy

### 10.1 Sketch Sizes

| Component | Configuration | Size |
|-----------|---------------|------|
| Theta Sketch | k=16384 | ~128 KB |
| Frequency Sketch | maxMapSize=1024 | ~8 KB |
| Frequency Sketch | maxMapSize=8192 | ~64 KB |
| Metadata | timestamps, row count | ~100 bytes |
| **Total per column** | (with maxMapSize=1024) | **~136 KB** |

### 10.2 Storage Budget

| Scenario | Columns | Total Size |
|----------|---------|------------|
| Small warehouse | 100 hot columns | 14 MB |
| Medium warehouse | 500 hot columns | 68 MB |
| Large warehouse | 2000 hot columns | 272 MB |

*Note: Much smaller than original CMS estimate due to LongsSketch efficiency*

### 10.3 Accuracy

| Metric | Theta Error | Frequency Error | Combined |
|--------|-------------|-----------------|----------|
| NDV | ~1% | N/A | ~1% |
| Containment | ~2% | N/A | ~2% |
| Frequency (heavy hitters) | N/A | ε ≈ 0.34% (maxMapSize=1024) | ~0.34% |
| Frequency (low-freq keys) | N/A | Falls back to avg fanout | Variable |
| Fanout | ~2% | ~1-2% | ~3-4% |

---

## 11. Query Log Analysis and Regret Metrics

### 11.1 Why Not Just Frequency?

Simple frequency-based column selection is naive:

```
Column A: 1000 queries/day, each takes 100ms  → 100 sec/day total
Column B: 10 queries/day, each takes 1 hour   → 10 hours/day total

Frequency says: Analyze A first (1000 > 10)
Impact says:    Analyze B first (100× more wall time)
```

We need a metric that captures the **cost of poor optimization**, not just usage frequency.

### 11.2 Defining "Regret"

**Regret** = The additional resources consumed due to suboptimal query planning caused by missing or inaccurate statistics.

```
Regret = (Actual_Cost - Optimal_Cost) × Frequency
```

Since we can't know `Optimal_Cost`, we approximate it using **estimation error** as a proxy for plan quality:

```
Regret ≈ Estimation_Error_Factor × Resource_Consumption × Frequency
```

### 11.3 Available Metrics from Presto

Presto already collects both estimated and actual statistics:

| Metric | Estimated (Plan Time) | Actual (Runtime) | Source |
|--------|----------------------|------------------|--------|
| Row count | `PlanNodeStatsEstimate.outputRowCount` | `planNodeOutputPositions` | `OperatorStats` |
| Join build keys | `JoinNodeStatsEstimate.joinBuildKeyCount` | `joinBuildKeyCount` | `OperatorStats` |
| Join probe keys | `JoinNodeStatsEstimate.joinProbeKeyCount` | `joinProbeKeyCount` | `OperatorStats` |
| CPU time | - | `cpuTime` | `OperatorStats` |
| Wall time | - | `wallTime` | `QueryStats` |
| Memory | - | `peakUserMemory` | `OperatorStats` |

These are available via:
- `EXPLAIN ANALYZE` output
- `QueryCompletedEvent` in event listeners
- HBO infrastructure (`HistoryBasedPlanStatisticsProvider`)

### 11.4 Regret Formulas

#### Option A: Simple Error × Cost (Configurable)

```java
public class JoinRegretCalculator {
    enum CostMetric { WALL_TIME, CPU_TIME, MEMORY_PEAK }

    public double computeRegret(
            JoinOperatorStats stats,
            long estimatedRows,
            long actualRows,
            CostMetric metric) {

        // Symmetric error ratio: handles both over and underestimates
        double errorFactor = Math.max(
            (double) actualRows / estimatedRows,
            (double) estimatedRows / actualRows);

        double cost = switch (metric) {
            case WALL_TIME -> stats.getWallTimeMillis();
            case CPU_TIME -> stats.getCpuTimeMillis();
            case MEMORY_PEAK -> stats.getPeakMemoryBytes() / 1_000_000.0; // MB
        };

        return errorFactor * cost;
    }
}
```

#### Option B: Directional Regret (Underestimates Are Worse)

Underestimates cause more damage because they lead to:
- Wrong join order (small table used as probe instead of build)
- Insufficient memory allocation → spilling
- Poor parallelism decisions

```java
public double computeDirectionalRegret(
        long estimatedRows,
        long actualRows,
        double cpuTimeMs) {

    double errorRatio = (double) actualRows / estimatedRows;

    if (errorRatio > 1.0) {
        // Underestimate: actual > estimated (BAD)
        // Penalty grows quadratically with error magnitude
        return Math.pow(errorRatio, 2) * cpuTimeMs;
    } else {
        // Overestimate: estimated > actual (less bad)
        // Linear penalty only
        return (1.0 / errorRatio) * cpuTimeMs * 0.5;
    }
}
```

#### Option C: Resource-Normalized Regret

Normalize by expected cost to make regret comparable across different query sizes:

```java
public double computeNormalizedRegret(
        long estimatedRows,
        long actualRows,
        double actualCpuTimeMs,
        double expectedCpuTimeMs) {

    double errorFactor = Math.max(
        (double) actualRows / estimatedRows,
        (double) estimatedRows / actualRows);

    // How much more CPU than expected?
    double costOverrun = actualCpuTimeMs / expectedCpuTimeMs;

    // Regret is high when: (1) estimation was wrong AND (2) query was expensive
    return errorFactor * costOverrun;
}
```

### 11.5 Aggregating Regret by Column

To prioritize which columns to ANALYZE, aggregate regret per join column:

```java
public class ColumnRegretTracker {
    // Table → Column → Aggregated Regret
    private final Map<SchemaTableName, Map<String, ColumnRegret>> regretByColumn;

    public void recordJoinRegret(
            SchemaTableName table,
            String columnName,
            double regret,
            Instant queryTime) {
        regretByColumn
            .computeIfAbsent(table, k -> new ConcurrentHashMap<>())
            .computeIfAbsent(columnName, k -> new ColumnRegret())
            .add(regret, queryTime);
    }

    public List<ColumnPriority> getAnalyzePriorities(Duration window) {
        return regretByColumn.entrySet().stream()
            .flatMap(tableEntry -> tableEntry.getValue().entrySet().stream()
                .map(colEntry -> new ColumnPriority(
                    tableEntry.getKey(),
                    colEntry.getKey(),
                    colEntry.getValue().getTotalRegret(window),
                    colEntry.getValue().getQueryCount(window))))
            .sorted(Comparator.comparing(ColumnPriority::totalRegret).reversed())
            .collect(toList());
    }
}

public class ColumnRegret {
    private final List<RegretEntry> entries = new ArrayList<>();

    public void add(double regret, Instant time) {
        entries.add(new RegretEntry(regret, time));
    }

    public double getTotalRegret(Duration window) {
        Instant cutoff = Instant.now().minus(window);
        return entries.stream()
            .filter(e -> e.time().isAfter(cutoff))
            .mapToDouble(RegretEntry::regret)
            .sum();
    }
}
```

### 11.6 Data Collection Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│                      Query Execution                            │
├─────────────────────────────────────────────────────────────────┤
│  1. Plan generated with estimates                               │
│  2. Query executed, actual stats collected                      │
│  3. QueryCompletedEvent fired                                   │
└─────────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────────┐
│                 JoinRegretEventListener                         │
├─────────────────────────────────────────────────────────────────┤
│  For each JoinNode in query plan:                               │
│    - Extract estimated vs actual row counts                     │
│    - Identify join columns (table, column name)                 │
│    - Compute regret = f(error, cpu_time|wall_time|memory)       │
│    - Record to ColumnRegretTracker                              │
└─────────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────────┐
│                   System Table / Storage                        │
├─────────────────────────────────────────────────────────────────┤
│  system.runtime.join_column_regret                              │
│  ┌──────────────┬────────────┬─────────────┬─────────────────┐  │
│  │ table_name   │ column     │ total_regret│ query_count     │  │
│  ├──────────────┼────────────┼─────────────┼─────────────────┤  │
│  │ orders       │ customer_id│ 1,234,567   │ 5,432           │  │
│  │ lineitem     │ order_id   │ 987,654     │ 3,210           │  │
│  │ ...          │ ...        │ ...         │ ...             │  │
│  └──────────────┴────────────┴─────────────┴─────────────────┘  │
└─────────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────────┐
│                   Auto-ANALYZE Scheduler                        │
├─────────────────────────────────────────────────────────────────┤
│  SELECT table_name, column_name                                 │
│  FROM system.runtime.join_column_regret                         │
│  WHERE total_regret > threshold                                 │
│  ORDER BY total_regret DESC                                     │
│  LIMIT 10;                                                      │
│                                                                 │
│  → Trigger ANALYZE for top regret columns                       │
└─────────────────────────────────────────────────────────────────┘
```

### 11.7 Configuration Options

```java
// In IcebergSessionProperties or global config

// Which cost metric to use for regret calculation
public static final String REGRET_COST_METRIC = "join_regret_cost_metric";
// Options: WALL_TIME, CPU_TIME, MEMORY_PEAK
// Default: CPU_TIME

// Penalty multiplier for underestimates vs overestimates
public static final String REGRET_UNDERESTIMATE_PENALTY = "join_regret_underestimate_penalty";
// Default: 2.0 (underestimates penalized 2x)

// Time window for aggregating regret
public static final String REGRET_WINDOW_HOURS = "join_regret_window_hours";
// Default: 168 (7 days)

// Minimum regret threshold to trigger auto-ANALYZE
public static final String REGRET_ANALYZE_THRESHOLD = "join_regret_analyze_threshold";
// Default: 1000000 (1M cpu-ms equivalent)
```

### 11.8 Example: Identifying High-Regret Columns

```sql
-- Query the regret tracking system table
SELECT
    table_name,
    column_name,
    total_regret_cpu_ms,
    query_count,
    avg_estimation_error,
    last_analyzed,
    CASE
        WHEN last_analyzed IS NULL THEN 'NEVER'
        WHEN last_analyzed < current_timestamp - INTERVAL '7' DAY THEN 'STALE'
        ELSE 'FRESH'
    END as stats_status
FROM system.runtime.join_column_regret
WHERE total_regret_cpu_ms > 100000  -- 100 CPU-seconds
ORDER BY total_regret_cpu_ms DESC
LIMIT 20;

-- Example output:
-- ┌─────────────────┬─────────────┬───────────────────┬─────────────┬─────────────────────┬──────────────────────┬──────────────┐
-- │ table_name      │ column_name │ total_regret_cpu_ms│ query_count │ avg_estimation_error│ last_analyzed        │ stats_status │
-- ├─────────────────┼─────────────┼───────────────────┼─────────────┼─────────────────────┼──────────────────────┼──────────────┤
-- │ sales.orders    │ customer_id │ 45,678,901        │ 1,234       │ 47.3                │ 2024-12-15 10:00:00  │ STALE        │
-- │ sales.lineitem  │ order_id    │ 23,456,789        │ 2,345       │ 12.8                │ NULL                 │ NEVER        │
-- │ analytics.events│ user_id     │ 12,345,678        │ 567         │ 156.2               │ 2024-12-28 14:30:00  │ FRESH        │
-- └─────────────────┴─────────────┴───────────────────┴─────────────┴─────────────────────┴──────────────────────┴──────────────┘
```

### 11.9 Stateless Prioritization with ANALYZE Status

The regret-based approach in sections 11.2-11.8 has a statefulness problem: once you ANALYZE a column, its regret drops, requiring you to track historical regret to maintain prioritization. A simpler approach embeds the **ANALYZE status** directly in query logs, making prioritization completely stateless.

#### Statistics Status Categories

| Status | Definition | Weight |
|--------|------------|--------|
| `NEVER` | No statistics file exists for this column | 10.0 |
| `PARTIAL` | Statistics exist but are missing frequency sketch | 5.0 |
| `STALE` | Statistics snapshot differs from current snapshot by >N rows or >M days | 2.0 |
| `FRESH` | Statistics from current or very recent snapshot | 0.1 |

#### Status Determination at Query Time

```java
public enum StatsStatus { NEVER, PARTIAL, STALE, FRESH }

public class JoinColumnStatsStatus {
    private static final Duration STALENESS_THRESHOLD = Duration.ofDays(7);
    private static final double ROW_CHANGE_THRESHOLD = 0.2; // 20%

    public StatsStatus determineStatus(
            SchemaTableName table,
            String columnName,
            Table icebergTable,
            Snapshot currentSnapshot) {

        // 1. Find statistics file for this table
        Optional<StatisticsFile> statsFile = getClosestStatisticsFile(
                icebergTable, currentSnapshot);
        if (statsFile.isEmpty()) {
            return StatsStatus.NEVER;
        }

        // 2. Check if column has required blobs (Theta + Frequency)
        int columnId = getColumnId(icebergTable, columnName);
        boolean hasTheta = hasBlobForColumn(statsFile.get(),
                "apache-datasketches-theta-v1", columnId);
        boolean hasFrequency = hasBlobForColumn(statsFile.get(),
                "apache-datasketches-fi-longs-v1", columnId);

        if (!hasTheta || !hasFrequency) {
            return StatsStatus.PARTIAL;
        }

        // 3. Check staleness
        Snapshot statsSnapshot = icebergTable.snapshot(statsFile.get().snapshotId());
        Duration age = Duration.between(
                Instant.ofEpochMilli(statsSnapshot.timestampMillis()),
                Instant.ofEpochMilli(currentSnapshot.timestampMillis()));

        double rowChange = Math.abs(
                (double) (currentSnapshot.summary().get("total-records") -
                         statsSnapshot.summary().get("total-records")) /
                statsSnapshot.summary().get("total-records"));

        if (age.compareTo(STALENESS_THRESHOLD) > 0 || rowChange > ROW_CHANGE_THRESHOLD) {
            return StatsStatus.STALE;
        }

        return StatsStatus.FRESH;
    }
}
```

#### Stateless Priority Metric

With status embedded in query logs, priority becomes a simple aggregation:

```
Priority(column) = Σ(cpu_time × status_weight) for queries in window
```

This is **self-correcting**:
- If you ANALYZE a column, its status changes from `NEVER`/`STALE` to `FRESH`
- Future queries have `status_weight = 0.1` instead of `10.0`
- Priority automatically drops by 100x without any state tracking
- If data changes and stats become stale, weight increases again automatically

#### Query Log Schema with Status

```sql
-- Extended query log / system table
CREATE TABLE system.runtime.join_column_metrics (
    query_id         VARCHAR,
    query_time       TIMESTAMP,
    catalog_name     VARCHAR,
    schema_name      VARCHAR,
    table_name       VARCHAR,
    column_name      VARCHAR,
    stats_status     VARCHAR,    -- NEVER, PARTIAL, STALE, FRESH
    stats_snapshot_id BIGINT,    -- Snapshot ID of statistics used (NULL if NEVER)
    current_snapshot_id BIGINT,  -- Current table snapshot
    cpu_time_ms      BIGINT,
    wall_time_ms     BIGINT,
    estimated_rows   BIGINT,
    actual_rows      BIGINT,
    estimation_error DOUBLE      -- actual / estimated
);
```

#### Priority Calculation Query

```sql
-- Stateless priority calculation - no regret accumulation needed
SELECT
    table_name,
    column_name,
    SUM(cpu_time_ms * CASE stats_status
        WHEN 'NEVER' THEN 10.0
        WHEN 'PARTIAL' THEN 5.0
        WHEN 'STALE' THEN 2.0
        WHEN 'FRESH' THEN 0.1
    END) as priority_score,
    COUNT(*) as query_count,
    COUNT(CASE WHEN stats_status = 'NEVER' THEN 1 END) as never_count,
    COUNT(CASE WHEN stats_status = 'STALE' THEN 1 END) as stale_count,
    AVG(estimation_error) as avg_error
FROM system.runtime.join_column_metrics
WHERE query_time > current_timestamp - INTERVAL '7' DAY
GROUP BY table_name, column_name
ORDER BY priority_score DESC
LIMIT 20;

-- Example output:
-- ┌─────────────────┬─────────────┬────────────────┬─────────────┬─────────────┬─────────────┬───────────┐
-- │ table_name      │ column_name │ priority_score │ query_count │ never_count │ stale_count │ avg_error │
-- ├─────────────────┼─────────────┼────────────────┼─────────────┼─────────────┼─────────────┼───────────┤
-- │ sales.lineitem  │ order_id    │ 45,678,901     │ 2,345       │ 2,345       │ 0           │ 12.8      │
-- │ sales.orders    │ customer_id │ 23,456,789     │ 1,234       │ 0           │ 1,234       │ 47.3      │
-- │ analytics.events│ user_id     │ 1,234,567      │ 567         │ 0           │ 0           │ 1.2       │
-- └─────────────────┴─────────────┴────────────────┴─────────────┴─────────────┴─────────────┴───────────┘
```

#### Auto-ANALYZE Integration

```java
public class StatelessAutoAnalyzeScheduler {

    public List<AnalyzeTask> getPriorityColumns(Duration window, int limit) {
        // Simple query - no state management needed
        return queryRunner.execute("""
            SELECT catalog_name, schema_name, table_name, column_name,
                   SUM(cpu_time_ms * status_weight) as priority
            FROM (
                SELECT *,
                    CASE stats_status
                        WHEN 'NEVER' THEN 10.0
                        WHEN 'PARTIAL' THEN 5.0
                        WHEN 'STALE' THEN 2.0
                        ELSE 0.1
                    END as status_weight
                FROM system.runtime.join_column_metrics
                WHERE query_time > current_timestamp - ?
            )
            GROUP BY catalog_name, schema_name, table_name, column_name
            HAVING SUM(cpu_time_ms * status_weight) > ?
            ORDER BY priority DESC
            LIMIT ?
            """, window, PRIORITY_THRESHOLD, limit);
    }

    public void runAutoAnalyze() {
        for (AnalyzeTask task : getPriorityColumns(Duration.ofDays(7), 10)) {
            // After ANALYZE, future queries will have FRESH status
            // Priority automatically drops without any state update
            analyzeColumn(task);
        }
    }
}
```

#### Advantages of Stateless Approach

| Aspect | Stateful Regret | Stateless Status |
|--------|-----------------|------------------|
| **State management** | Must track regret history | Query logs are ephemeral |
| **Self-correction** | Need to clear regret after ANALYZE | Automatic via status change |
| **Distributed execution** | Regret state needs coordination | No coordination needed |
| **Recovery after restart** | Must persist regret state | Just replay recent logs |
| **Staleness detection** | Inferred from regret spikes | Explicit in each record |
| **Debugging** | "Why is regret high?" | "Status is STALE because X" |

### 11.10 Integration with HBO

The stateless approach integrates cleanly with Presto's existing History-Based Optimization:

```java
// Extend HistoryBasedPlanStatisticsProvider to emit status with each query
public interface StatsStatusAwareProvider
        extends HistoryBasedPlanStatisticsProvider {

    // Emit join column metrics with status at query completion
    void recordJoinColumnMetrics(
            PlanNodeId joinNodeId,
            JoinColumns columns,
            StatsStatus status,         // Status at query time
            long statsSnapshotId,       // Which stats were used
            long currentSnapshotId,     // Current table snapshot
            long estimatedRows,
            long actualRows,
            Duration cpuTime);

    // Get columns ranked by priority (stateless calculation)
    List<ColumnPriority> getAnalyzePriorities(
            Duration window,
            int limit);
}
```

### 11.11 Surfacing Connector-Specific Status Through SPI

The statistics status (NEVER/PARTIAL/STALE/FRESH) requires connector-specific knowledge—Iceberg knows about Puffin files and blob types, but query logging happens in presto-main. Here's how to bridge this gap through the SPI layer.

#### Challenge

```
┌─────────────────────────────────────────────────────────────────────────┐
│ presto-main (query logging)                                             │
│   - Needs to record stats_status for join columns                       │
│   - Connector-agnostic                                                  │
└─────────────────────────────────────────────────────────────────────────┘
                                    │
                                    ▼ How?
┌─────────────────────────────────────────────────────────────────────────┐
│ presto-spi (connector interface)                                        │
│   - TableStatistics, ColumnStatistics                                   │
│   - Already has ConfidenceLevel (LOW, HIGH, FACT)                       │
└─────────────────────────────────────────────────────────────────────────┘
                                    │
                                    ▼
┌─────────────────────────────────────────────────────────────────────────┐
│ presto-iceberg (connector implementation)                               │
│   - Knows about Puffin files, blob types, snapshot IDs                  │
│   - Can determine NEVER/PARTIAL/STALE/FRESH                            │
└─────────────────────────────────────────────────────────────────────────┘
```

#### Option A: Extend ColumnStatistics with Metadata (Recommended)

Add per-column statistics metadata to the existing SPI types:

```java
// New SPI class: presto-spi/.../statistics/ColumnStatisticsMetadata.java
public class ColumnStatisticsMetadata {
    public enum StatisticsStatus {
        NEVER,      // No statistics collected for this column
        PARTIAL,    // Some statistics exist but not all (e.g., NDV but no frequency)
        STALE,      // Statistics exist but are outdated
        FRESH       // Statistics are current
    }

    private final StatisticsStatus status;
    private final Optional<Long> statisticsSnapshotId;  // For tracking which stats were used
    private final Optional<Instant> collectedAt;        // When stats were collected
    private final Set<String> availableSketchTypes;     // e.g., {"theta", "frequency"}

    // Constructor, getters, builder...
}

// Extend ColumnStatistics to include metadata
public final class ColumnStatistics {
    // Existing fields...
    private final Estimate nullsFraction;
    private final Estimate distinctValuesCount;
    private final Optional<ConnectorHistogram> histogram;

    // NEW: Optional metadata about statistics quality
    private final Optional<ColumnStatisticsMetadata> metadata;

    @JsonProperty
    public Optional<ColumnStatisticsMetadata> getMetadata() {
        return metadata;
    }
}
```

**Iceberg Implementation:**

```java
// In TableStatisticsMaker.java - when building ColumnStatistics
private ColumnStatistics buildColumnStatistics(
        ConnectorSession session,
        Table icebergTable,
        Snapshot currentSnapshot,
        IcebergColumnHandle column,
        Optional<StatisticsFile> statsFile) {

    ColumnStatistics.Builder builder = ColumnStatistics.builder();

    // Existing logic for NDV, range, etc...
    builder.setDistinctValuesCount(...);
    builder.setRange(...);

    // NEW: Compute and attach metadata
    ColumnStatisticsMetadata metadata = computeColumnMetadata(
            column, statsFile, currentSnapshot);
    builder.setMetadata(Optional.of(metadata));

    return builder.build();
}

private ColumnStatisticsMetadata computeColumnMetadata(
        IcebergColumnHandle column,
        Optional<StatisticsFile> statsFile,
        Snapshot currentSnapshot) {

    if (statsFile.isEmpty()) {
        return new ColumnStatisticsMetadata(
                StatisticsStatus.NEVER,
                Optional.empty(),
                Optional.empty(),
                ImmutableSet.of());
    }

    int columnId = column.getId();
    Set<String> availableTypes = new HashSet<>();

    boolean hasTheta = hasBlobForColumn(statsFile.get(),
            "apache-datasketches-theta-v1", columnId);
    if (hasTheta) availableTypes.add("theta");

    boolean hasFrequency = hasBlobForColumn(statsFile.get(),
            "apache-datasketches-fi-longs-v1", columnId);
    if (hasFrequency) availableTypes.add("frequency");

    if (availableTypes.isEmpty()) {
        return new ColumnStatisticsMetadata(
                StatisticsStatus.NEVER, ...);
    }

    if (!hasTheta || !hasFrequency) {
        return new ColumnStatisticsMetadata(
                StatisticsStatus.PARTIAL, ...);
    }

    // Check staleness
    Snapshot statsSnapshot = icebergTable.snapshot(statsFile.get().snapshotId());
    if (isStale(statsSnapshot, currentSnapshot)) {
        return new ColumnStatisticsMetadata(
                StatisticsStatus.STALE,
                Optional.of(statsFile.get().snapshotId()),
                Optional.of(Instant.ofEpochMilli(statsSnapshot.timestampMillis())),
                availableTypes);
    }

    return new ColumnStatisticsMetadata(
            StatisticsStatus.FRESH, ...);
}
```

**Query Logging (presto-main):**

```java
// In JoinRegretEventListener or similar
public void recordJoinMetrics(
        JoinNode joinNode,
        TableStatistics leftStats,
        TableStatistics rightStats,
        OperatorStats runtimeStats) {

    for (JoinColumn column : joinNode.getJoinColumns()) {
        ColumnStatistics colStats = leftStats.getColumnStatistics()
                .get(column.getHandle());

        // Extract status from SPI - no connector-specific code needed
        StatisticsStatus status = colStats.getMetadata()
                .map(ColumnStatisticsMetadata::getStatus)
                .orElse(StatisticsStatus.NEVER);

        Optional<Long> statsSnapshotId = colStats.getMetadata()
                .flatMap(ColumnStatisticsMetadata::getStatisticsSnapshotId);

        emitMetric(JoinColumnMetric.builder()
                .table(column.getTable())
                .column(column.getName())
                .statsStatus(status.name())
                .statsSnapshotId(statsSnapshotId.orElse(null))
                .cpuTimeMs(runtimeStats.getCpuTime().toMillis())
                .estimatedRows(joinNode.getEstimatedRows())
                .actualRows(runtimeStats.getOutputPositions())
                .build());
    }
}
```

#### Option B: Use Existing ConfidenceLevel (Simpler, Less Granular)

Map statistics status to existing `ConfidenceLevel`:

| Status | ConfidenceLevel | Meaning |
|--------|-----------------|---------|
| NEVER | LOW | No reliable statistics |
| PARTIAL | LOW | Some stats missing |
| STALE | HIGH | Stats exist but may be outdated |
| FRESH | FACT | Stats are current and trustworthy |

```java
// Iceberg sets confidence based on statistics status
TableStatistics.Builder builder = TableStatistics.builder();
if (statsFile.isEmpty()) {
    builder.setConfidenceLevel(ConfidenceLevel.LOW);
} else if (isStale(statsFile)) {
    builder.setConfidenceLevel(ConfidenceLevel.HIGH);
} else {
    builder.setConfidenceLevel(ConfidenceLevel.FACT);
}
```

**Limitations:**
- No per-column granularity (table-level only)
- Can't distinguish NEVER vs PARTIAL
- Can't include snapshot IDs

#### Option C: New SPI Method for Statistics Metadata

Add a dedicated method to `ConnectorMetadata`:

```java
// In ConnectorMetadata.java
default Map<ColumnHandle, ColumnStatisticsMetadata> getColumnStatisticsMetadata(
        ConnectorSession session,
        ConnectorTableHandle tableHandle,
        List<ColumnHandle> columnHandles) {
    // Default: no metadata available
    return ImmutableMap.of();
}
```

**Advantages:**
- No changes to existing `TableStatistics`/`ColumnStatistics`
- Explicit opt-in for connectors that support it
- Can be called separately from `getTableStatistics()`

**Disadvantages:**
- Two round-trips to connector (stats + metadata)
- Metadata may become inconsistent with stats

#### Recommendation: Option A

Option A (extending `ColumnStatistics`) is recommended because:

1. **Atomic**: Stats and metadata are returned together, always consistent
2. **Per-column**: Each column can have different status (FRESH for `id`, STALE for `name`)
3. **Extensible**: `ColumnStatisticsMetadata` can grow with new fields
4. **Backward compatible**: `Optional<ColumnStatisticsMetadata>` defaults to empty
5. **Connector-agnostic**: presto-main works with any connector that populates metadata

#### Implementation Checklist for Option A

Phase 2 should include:

- [ ] Add `ColumnStatisticsMetadata` class to presto-spi
- [ ] Add `Optional<ColumnStatisticsMetadata> metadata` field to `ColumnStatistics`
- [ ] Implement `computeColumnMetadata()` in Iceberg's `TableStatisticsMaker`
- [ ] Update `getTableStatistics()` to populate metadata for each column
- [ ] Update query event listener to extract status from `ColumnStatistics.getMetadata()`
- [ ] Emit status to `system.runtime.join_column_metrics` system table

---

## 12. Implementation Phases

### Phase 1: Foundation ⬅️ **Start Here**
- [x] Theta sketch already in Puffin
- [ ] Expose raw Theta sketch via new reader method
- [ ] Add Frequency aggregation function (`sketch_frequency`) using DataSketches `LongsSketch`
- [ ] Add frequency blob type (`apache-datasketches-fi-longs-v1`) to Puffin writer/reader
- [ ] Wire frequency sketch into ANALYZE

### Phase 2: Stateless Query Analysis
- [ ] Add `ColumnStatisticsMetadata` class to presto-spi with `StatisticsStatus` enum
- [ ] Extend `ColumnStatistics` with `Optional<ColumnStatisticsMetadata> metadata`
- [ ] Implement `computeColumnMetadata()` in Iceberg's `TableStatisticsMaker`
- [ ] Create `system.runtime.join_column_metrics` system table with status field
- [ ] Emit join column metrics at query completion (status, cpu_time, estimation_error)
- [ ] Implement stateless priority calculation: `Σ(cpu_time × status_weight)`

### Phase 3: Optimizer Integration
- [ ] Fetch sketches in optimizer rules
- [ ] Use for cost estimation (not reordering yet)
- [ ] Add session property to enable/disable

### Phase 4: Auto-ANALYZE
- [ ] Background job triggered by priority threshold
- [ ] Priority queue based on `Σ(cpu_time × status_weight)` (stateless)
- [ ] Automatic staleness detection via status field (STALE/FRESH)
- [ ] Self-correcting: priority drops after ANALYZE without state update

### Phase 5: Join Ordering (Optional)
- [ ] Intermediate sketch computation
- [ ] Limited reordering (2-3 joins)
- [ ] Extensive testing for correctness

---

## 13. Open Questions

1. ~~**Frequency library choice**: stream-lib CMS vs DataSketches?~~
   - **RESOLVED**: Use DataSketches `LongsSketch` for cross-platform binary compatibility (Java/C++/Python)

2. **Incremental updates**: Can sketches be updated incrementally on data append, or always full rebuild?
   - LongsSketch supports `merge()` - could potentially merge new data sketch with existing

3. **Multi-column joins**: How to handle composite join keys `(a.x, a.y) = (b.x, b.y)`?
   - Option: Concatenate column values before hashing (same approach as Theta)

4. **Cross-catalog joins**: What if one table is Iceberg and another is Hive?
   - Option: Only estimate when both sides have sketches

5. **Staleness threshold**: When are sketches too old to use?
   - Option: Session property, default 7 days

6. **Heavy hitter threshold**: What frequency threshold makes a key "heavy"?
   - LongsSketch auto-manages this based on maxMapSize
   - Low-frequency keys fall back to average fanout estimation

---

## 14. References

1. [Apache DataSketches](https://datasketches.apache.org/)
2. [DataSketches Frequent Items Overview](https://datasketches.apache.org/docs/Frequency/FrequentItemsOverview.html)
3. [DataSketches Cross-Language Compatibility](https://datasketches.apache.org/docs/Architecture/LargeScale.html)
4. [Iceberg Table Spec](https://iceberg.apache.org/spec/)
5. [Iceberg Puffin Spec](https://iceberg.apache.org/puffin-spec/)
6. [Theta Sketch Framework](https://datasketches.apache.org/docs/Theta/ThetaSketchFramework.html)
7. [LongsSketch Java Source](https://github.com/apache/datasketches-java/blob/master/src/main/java/org/apache/datasketches/frequencies/LongsSketch.java)

---

## Appendix A: Existing Code References

| Component | File | Key Lines |
|-----------|------|-----------|
| Theta Aggregation | `presto-main-base/.../sketch/theta/ThetaSketchAggregationFunction.java` | 37-93 |
| KLL Aggregation | `presto-main-base/.../sketch/kll/KllSketchWithKAggregationFunction.java` | 37-100+ |
| Puffin Blob Writers | `presto-iceberg/.../TableStatisticsMaker.java` | 148-152 |
| Puffin Blob Readers | `presto-iceberg/.../TableStatisticsMaker.java` | 154-158 |
| NDV Blob Generator | `presto-iceberg/.../TableStatisticsMaker.java` | 413-426 |
| Statistics Collection | `presto-iceberg/.../TableStatisticsMaker.java` | 669-695 |
| Statistics Cache | `presto-iceberg/.../statistics/StatisticsFileCache.java` | 24-67 |

---

*Document Version: 0.7*
*Last Updated: January 2025*
