# Iceberg-Based Join Statistics for Presto

## Design Document

**Status**: Draft
**Authors**: Tim Meehan
**Date**: January 2025
**Version**: 0.2

---

## 1. Executive Summary

This document describes an approach to accurate join cardinality estimation using **connector-native sketches** stored in Iceberg table metadata. The key insight is that join fanout can be computed from two composable sketches:

1. **Theta Sketch** - Which keys exist (**Already implemented in Presto/Iceberg**)
2. **Count-Min Sketch (CMS)** - Frequency of each key (new addition)

By storing these sketches per column in Iceberg metadata, we can compute pairwise join statistics at query planning time without runtime overhead.

### Why This Approach

| Challenge | Solution |
|-----------|----------|
| Unknown join selectivity | Theta intersection estimates matching keys |
| Unknown join fanout | CMS frequency product estimates output rows |
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

### 2.2 Count-Min Sketch: Needs Implementation 🔨

CMS is not currently implemented. Here's the plan based on the existing Theta/KLL pattern.

---

## 3. CMS Implementation Plan

### 3.1 Library Options

| Library | Package | Pros | Cons |
|---------|---------|------|------|
| **stream-lib** | `com.clearspring.analytics:stream:2.9.5` | Battle-tested, used by Spark | External dependency |
| **Apache DataSketches** | `org.apache.datasketches:datasketches-java` | Already in classpath, consistent API | Has `ItemsSketch`/`LongsSketch` (different algo than CMS) |
| **Custom** | N/A | Full control | Maintenance burden |

**Recommendation**: Use **stream-lib** for CMS. It's proven, compact, and Apache Spark uses it for the same purpose. DataSketches frequency sketches (`ItemsSketch`, `LongsSketch`) use a different algorithm that's not directly compatible with our use case (they track top-K heavy hitters rather than point-query all keys).

### 3.2 New Components

Following the established pattern from Theta/KLL sketches:

#### A. Aggregation Function

**File**: `presto-main-base/src/main/java/com/facebook/presto/operator/aggregation/sketch/cms/CmsSketchAggregationFunction.java`

```java
@AggregationFunction(value = "sketch_cms", isCalledOnNullInput = true)
@Description("calculates a count-min sketch for frequency estimation")
public class CmsSketchAggregationFunction {

    @InputFunction
    @TypeParameter("T")
    public static void input(
            @TypeParameter("T") Type type,
            @AggregationState CmsSketchAggregationState state,
            @SqlType("T") @BlockPosition Block block,
            @BlockIndex int position) {
        if (block.isNull(position)) {
            return;
        }
        CountMinSketch sketch = state.getSketch();
        long hash = computeHash(type, block, position);
        sketch.add(hash, 1);
    }

    @CombineFunction
    public static void merge(
            @AggregationState CmsSketchAggregationState state,
            @AggregationState CmsSketchAggregationState otherState) {
        // stream-lib CMS supports merging
        state.getSketch().merge(otherState.getSketch());
    }

    @OutputFunction(StandardTypes.VARBINARY)
    public static void output(
            @AggregationState CmsSketchAggregationState state,
            BlockBuilder out) {
        byte[] bytes = CountMinSketch.serialize(state.getSketch());
        VARBINARY.writeSlice(out, Slices.wrappedBuffer(bytes));
    }
}
```

#### B. State Classes

**Files**:
- `CmsSketchAggregationState.java` - Interface
- `CmsSketchStateFactory.java` - Creates CMS with configurable epsilon/delta
- `CmsSketchStateSerializer.java` - Serialize/deserialize for distributed execution

#### C. Scalar Functions (Optional)

**File**: `presto-main-base/src/main/java/com/facebook/presto/operator/scalar/CmsSketchFunctions.java`

```java
@ScalarFunction("cms_estimate")
@Description("Estimates the frequency of a value in a CMS sketch")
@SqlType(StandardTypes.BIGINT)
public static long estimateCount(
        @SqlType(StandardTypes.VARBINARY) Slice sketch,
        @SqlType(StandardTypes.BIGINT) long value) {
    CountMinSketch cms = CountMinSketch.deserialize(sketch.getBytes());
    return cms.estimateCount(value);
}

@ScalarFunction("cms_merge")
@Description("Merges two CMS sketches")
@SqlType(StandardTypes.VARBINARY)
public static Slice merge(
        @SqlType(StandardTypes.VARBINARY) Slice left,
        @SqlType(StandardTypes.VARBINARY) Slice right) {
    CountMinSketch cmsLeft = CountMinSketch.deserialize(left.getBytes());
    CountMinSketch cmsRight = CountMinSketch.deserialize(right.getBytes());
    cmsLeft.merge(cmsRight);
    return Slices.wrappedBuffer(CountMinSketch.serialize(cmsLeft));
}
```

### 3.3 Puffin Integration

**File**: `presto-iceberg/src/main/java/com/facebook/presto/iceberg/TableStatisticsMaker.java`

#### New Constants

```java
private static final String ICEBERG_CMS_SKETCH_BLOB_TYPE_ID = "presto-cms-sketch-bytes-v1";
private static final String ICEBERG_CMS_TOTAL_COUNT_KEY = "total_count";
```

#### Register Generators/Readers

```java
// Add to puffinStatWriters map (line 148-152)
.put(FREQUENCY_SKETCH, TableStatisticsMaker::generateCmsBlob)

// Add to puffinStatReaders map (line 154-158)
.put(ICEBERG_CMS_SKETCH_BLOB_TYPE_ID, TableStatisticsMaker::readCmsBlob)
```

#### Generator Function

```java
private static Blob generateCmsBlob(ColumnStatisticMetadata metadata, Block value,
        Table icebergTable, Snapshot snapshot, TypeManager typeManager) {
    int id = getField(metadata, icebergTable, snapshot).fieldId();
    ByteBuffer raw = VARBINARY.getSlice(value, 0).toByteBuffer();

    // Deserialize to get total count for metadata
    CountMinSketch cms = CountMinSketch.deserialize(raw.array());

    return new Blob(
            ICEBERG_CMS_SKETCH_BLOB_TYPE_ID,
            ImmutableList.of(id),
            snapshot.snapshotId(),
            snapshot.sequenceNumber(),
            raw,
            null,
            ImmutableMap.of(ICEBERG_CMS_TOTAL_COUNT_KEY, Long.toString(cms.size())));
}
```

#### Reader Function

```java
private static void readCmsBlob(BlobMetadata metadata, ByteBuffer blob,
        ColumnStatistics.Builder statistics, Table icebergTable, TypeManager typeManager) {
    // Store the raw CMS in a new field in ColumnStatistics
    // Or create a separate cache for join sketches
    CountMinSketch cms = CountMinSketch.deserialize(blob.array());
    statistics.setFrequencySketch(Optional.of(cms));
}
```

### 3.4 ANALYZE Integration

**File**: `TableStatisticsMaker.java:669-695` - `getSupportedColumnStatistics()`

```java
// Add CMS collection for join-eligible types
if (isNumericType(type) || type.equals(DATE) || isVarcharType(type) ||
        type.equals(TIMESTAMP) || type.equals(TIMESTAMP_WITH_TIME_ZONE)) {
    // Existing Theta for NDV
    supportedStatistics.add(NUMBER_OF_DISTINCT_VALUES.getColumnStatisticMetadataWithCustomFunction(
            columnName, format("RETURN sketch_theta(%s)", formatIdentifier(columnName)),
            ImmutableList.of(columnName)));

    // NEW: CMS for frequency
    supportedStatistics.add(FREQUENCY_SKETCH.getColumnStatisticMetadataWithCustomFunction(
            columnName,
            format("RETURN sketch_cms(%s)", formatIdentifier(columnName)),
            ImmutableList.of(columnName)));
}
```

### 3.5 Configuration

**File**: `IcebergSessionProperties.java`

```java
public static final String CMS_EPSILON = "cms_epsilon";
public static final String CMS_DELTA = "cms_delta";

// epsilon controls accuracy, delta controls confidence
// epsilon=0.0001, delta=0.99 → ~1MB sketch, 99% confidence of ±0.01% error
```

---

## 4. Join Estimation Algorithm

With both Theta and CMS available, join estimation becomes:

```java
public JoinEstimate computeJoinEstimate(
        CompactSketch leftTheta,
        CountMinSketch leftCms,
        long leftRowCount,
        CompactSketch rightTheta,
        CountMinSketch rightCms,
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

    // 3. Estimate output using CMS frequencies
    //    For each retained hash in intersection, multiply frequencies
    long sampleOutput = 0;
    long[] hashes = intersectionSketch.getCache();

    for (long hash : hashes) {
        long freqL = leftCms.estimateCount(hash);
        long freqR = rightCms.estimateCount(hash);
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
| CMS for frequencies | ❌ Not implemented | Add as new statistic type |
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

### 6.2 Count-Min Sketch (New Addition)

**Purpose**: Estimate frequency of each key (rows per key).

**Properties**:
- Fixed size: ~256 KB - 1 MB (configurable via epsilon/delta)
- Mergeable: Element-wise addition
- Point-queryable: Given key, estimate its count
- One-sided error: Always overestimates (never underestimates)

**Proposed SQL**:
```sql
SELECT sketch_cms(customer_id) FROM orders
```

### 6.3 Combined Join Column Sketch

```java
public class JoinColumnSketch {
    private final CompactSketch thetaSketch;   // Key existence + hashes
    private final CountMinSketch cmsSketch;     // Key frequencies
    private final long rowCount;                // Total rows
    private final Instant collectedAt;          // For staleness
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
- `type`: Blob type ID (e.g., `apache-datasketches-theta-v1`, `presto-cms-sketch-bytes-v1`)
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
| CMS | ε=0.0001, δ=0.99 | ~1 MB |
| Metadata | timestamps, row count | ~100 bytes |
| **Total per column** | | **~1.1 MB** |

### 9.2 Storage Budget

| Scenario | Columns | Total Size |
|----------|---------|------------|
| Small warehouse | 100 hot columns | 110 MB |
| Medium warehouse | 500 hot columns | 550 MB |
| Large warehouse | 2000 hot columns | 2.2 GB |

### 9.3 Accuracy

| Metric | Theta Error | CMS Error | Combined |
|--------|-------------|-----------|----------|
| NDV | ~1% | N/A | ~1% |
| Containment | ~2% | N/A | ~2% |
| Frequency | N/A | ~0.01% overestimate | ~0.01% |
| Fanout | ~2% | ~1% | ~3% |

---

## 10. Implementation Phases

### Phase 1: Foundation ⬅️ **Start Here**
- [x] Theta sketch already in Puffin
- [ ] Expose raw Theta sketch via new reader method
- [ ] Add CMS aggregation function (`sketch_cms`)
- [ ] Add CMS blob type to Puffin writer/reader
- [ ] Wire CMS into ANALYZE

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

1. **CMS library choice**: stream-lib vs custom implementation?

2. **Incremental updates**: Can sketches be updated incrementally on data append, or always full rebuild?

3. **Multi-column joins**: How to handle composite join keys `(a.x, a.y) = (b.x, b.y)`?
   - Option: Concatenate column values before hashing

4. **Cross-catalog joins**: What if one table is Iceberg and another is Hive?
   - Option: Only estimate when both sides have sketches

5. **Staleness threshold**: When are sketches too old to use?
   - Option: Session property, default 7 days

---

## 12. References

1. [Apache DataSketches](https://datasketches.apache.org/)
2. [Iceberg Table Spec](https://iceberg.apache.org/spec/)
3. [Iceberg Puffin Spec](https://iceberg.apache.org/puffin-spec/)
4. [stream-lib CMS](https://github.com/addthis/stream-lib)
5. Count-Min Sketch: Cormode & Muthukrishnan, 2005
6. [Theta Sketch Framework](https://datasketches.apache.org/docs/Theta/ThetaSketchFramework.html)

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

*Document Version: 0.2*
*Last Updated: January 2025*
