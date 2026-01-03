# Iceberg-Based Join Statistics for Presto

## Design Document

**Status**: Draft
**Authors**: Tim Meehan
**Date**: January 2025
**Version**: 0.3

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

## 9. Size and Accuracy

### 9.1 Sketch Sizes

| Component | Configuration | Size |
|-----------|---------------|------|
| Theta Sketch | k=16384 | ~128 KB |
| Frequency Sketch | maxMapSize=1024 | ~8 KB |
| Frequency Sketch | maxMapSize=8192 | ~64 KB |
| Metadata | timestamps, row count | ~100 bytes |
| **Total per column** | (with maxMapSize=1024) | **~136 KB** |

### 9.2 Storage Budget

| Scenario | Columns | Total Size |
|----------|---------|------------|
| Small warehouse | 100 hot columns | 14 MB |
| Medium warehouse | 500 hot columns | 68 MB |
| Large warehouse | 2000 hot columns | 272 MB |

*Note: Much smaller than original CMS estimate due to LongsSketch efficiency*

### 9.3 Accuracy

| Metric | Theta Error | Frequency Error | Combined |
|--------|-------------|-----------------|----------|
| NDV | ~1% | N/A | ~1% |
| Containment | ~2% | N/A | ~2% |
| Frequency (heavy hitters) | N/A | ε ≈ 0.34% (maxMapSize=1024) | ~0.34% |
| Frequency (low-freq keys) | N/A | Falls back to avg fanout | Variable |
| Fanout | ~2% | ~1-2% | ~3-4% |

---

## 10. Implementation Phases

### Phase 1: Foundation ⬅️ **Start Here**
- [x] Theta sketch already in Puffin
- [ ] Expose raw Theta sketch via new reader method
- [ ] Add Frequency aggregation function (`sketch_frequency`) using DataSketches `LongsSketch`
- [ ] Add frequency blob type (`apache-datasketches-fi-longs-v1`) to Puffin writer/reader
- [ ] Wire frequency sketch into ANALYZE

### Phase 2: Query Log Integration
- [ ] Build query log analyzer for join column frequency
- [ ] Store frequency data in system table
- [ ] Track which columns participate in joins

### Phase 3: Optimizer Integration
- [ ] Fetch sketches in optimizer rules
- [ ] Use for cost estimation (not reordering yet)
- [ ] Add session property to enable/disable

### Phase 4: Auto-ANALYZE
- [ ] Background job using frequency data
- [ ] Automatic staleness refresh
- [ ] Priority queue based on query frequency

### Phase 5: Join Ordering (Optional)
- [ ] Intermediate sketch computation
- [ ] Limited reordering (2-3 joins)
- [ ] Extensive testing for correctness

---

## 11. Open Questions

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

## 12. References

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

*Document Version: 0.3*
*Last Updated: January 2025*
