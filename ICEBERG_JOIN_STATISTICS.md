# Iceberg-Based Join Statistics for Presto

## Design Document

**Status**: Draft
**Authors**: Tim Meehan
**Date**: January 2025
**Version**: 0.1

---

## 1. Executive Summary

This document describes an approach to accurate join cardinality estimation using **connector-native sketches** stored in Iceberg table metadata. The key insight is that join fanout can be computed from two composable sketches:

1. **Theta Sketch** - Which keys exist (Iceberg already supports this)
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

## 2. Background

### 2.1 The Join Estimation Problem

Join cardinality estimation requires knowing:

```
|A ⋈ B| = |A| × containment(A→B) × fanout(B)
```

Where:
- **containment(A→B)**: Fraction of A's keys that exist in B
- **fanout(B)**: Average B rows per matching key

Traditional NDV-based estimation assumes uniform distribution and fails on skewed data.

### 2.2 Iceberg's Existing Statistics

Iceberg already maintains per-column statistics in manifest files:

```java
// Current Iceberg column stats
public interface FieldSummary {
    boolean containsNull();
    Boolean containsNan();
    ByteBuffer lowerBound();
    ByteBuffer upperBound();
    Long valueCount();
    Long nullValueCount();
}
```

Iceberg also has Theta sketch support in `iceberg-core` for NDV estimation, used for:
- Partition statistics
- Data file statistics
- Delete file statistics

### 2.3 What's Missing

Iceberg has Theta sketches for NDV but lacks:
1. **Column-level Theta sketches for join keys** - Needed for containment estimation
2. **Count-Min Sketch for frequencies** - Needed for fanout estimation

---

## 3. Data Structures

### 3.1 Theta Sketch (Existing in Iceberg)

**Purpose**: Estimate distinct key count and set intersection.

**Properties**:
- Fixed size: ~16 KB (configurable)
- Mergeable: Union operation
- Supports intersection: Estimates |A ∩ B|
- Stores actual key hashes (can enumerate retained hashes)

```java
// Apache DataSketches Theta Sketch
UpdateSketch theta = UpdateSketch.builder().build();
for (Row row : table) {
    theta.update(row.getJoinKey());
}

// Estimate NDV
double ndv = theta.getEstimate();

// Intersection with another sketch
Intersection intersection = SetOperation.builder().buildIntersection();
intersection.intersect(thetaA);
intersection.intersect(thetaB);
double matchingKeys = intersection.getResult().getEstimate();
```

### 3.2 Count-Min Sketch (New Addition)

**Purpose**: Estimate frequency of each key (rows per key).

**Properties**:
- Fixed size: ~256 KB (4 rows × 65536 counters × 4 bytes)
- Mergeable: Element-wise addition
- Point-queryable: Given key, estimate its count
- One-sided error: Always overestimates (never underestimates)

```java
// Count-Min Sketch (e.g., stream-lib or custom)
CountMinSketch cms = new CountMinSketch(0.001, 0.99, 1);  // epsilon, delta, seed
for (Row row : table) {
    cms.add(hash(row.getJoinKey()), 1);
}

// Query frequency
long freq = cms.estimateCount(hash(someKey));
```

### 3.3 Combined Join Column Sketch

```java
public class JoinColumnSketch {
    private final CompactSketch thetaSketch;   // Key existence + hashes
    private final CountMinSketch cmsSketch;     // Key frequencies
    private final long rowCount;                // Total rows
    private final Instant collectedAt;          // For staleness

    // Serialization for storage in Iceberg metadata
    public byte[] serialize() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(thetaSketch.toByteArray());
        out.write(cmsSketch.serialize());
        out.write(Longs.toByteArray(rowCount));
        out.write(Longs.toByteArray(collectedAt.toEpochMilli()));
        return out.toByteArray();
    }

    public static JoinColumnSketch deserialize(byte[] bytes) { ... }
}
```

---

## 4. Storage in Iceberg

### 4.1 Table Properties

Store sketches in Iceberg table properties:

```sql
-- After ANALYZE
ALTER TABLE orders SET TBLPROPERTIES (
    'join-sketch.id.theta' = '<base64-encoded-theta>',
    'join-sketch.id.cms' = '<base64-encoded-cms>',
    'join-sketch.id.row-count' = '1000000',
    'join-sketch.id.collected-at' = '2025-01-03T10:00:00Z',

    'join-sketch.cust_id.theta' = '<base64-encoded-theta>',
    'join-sketch.cust_id.cms' = '<base64-encoded-cms>',
    'join-sketch.cust_id.row-count' = '1000000',
    'join-sketch.cust_id.collected-at' = '2025-01-03T10:00:00Z'
);
```

### 4.2 Alternative: Metadata File

Store sketches in a separate metadata file referenced from table properties:

```
table-location/
├── metadata/
│   ├── v1.metadata.json
│   ├── v2.metadata.json
│   └── join-sketches/
│       └── sketch-v1.bin    ← All column sketches
└── data/
    └── ...
```

Table properties reference the sketch file:

```json
{
  "table-uuid": "...",
  "properties": {
    "join-sketch-file": "join-sketches/sketch-v1.bin"
  }
}
```

**Pros**: Keeps metadata.json small, sketches can be large
**Cons**: Extra file to manage

### 4.3 API Extension

```java
// Extension to Iceberg Table interface
public interface JoinStatisticsProvider {
    Optional<JoinColumnSketch> getJoinColumnSketch(String columnName);

    void setJoinColumnSketch(String columnName, JoinColumnSketch sketch);
}

// Implementation in IcebergTable
public class IcebergJoinStatisticsProvider implements JoinStatisticsProvider {
    private final Table table;

    @Override
    public Optional<JoinColumnSketch> getJoinColumnSketch(String columnName) {
        String prefix = "join-sketch." + columnName;
        String thetaBase64 = table.properties().get(prefix + ".theta");
        String cmsBase64 = table.properties().get(prefix + ".cms");

        if (thetaBase64 == null || cmsBase64 == null) {
            return Optional.empty();
        }

        return Optional.of(new JoinColumnSketch(
            CompactSketch.wrap(Memory.wrap(Base64.decode(thetaBase64))),
            CountMinSketch.deserialize(Base64.decode(cmsBase64)),
            Long.parseLong(table.properties().get(prefix + ".row-count")),
            Instant.parse(table.properties().get(prefix + ".collected-at"))
        ));
    }
}
```

---

## 5. ANALYZE Command

### 5.1 Syntax

```sql
-- Analyze specific columns
ANALYZE TABLE orders FOR COLUMNS (id, cust_id) WITH SKETCHES;

-- Analyze with sampling (faster, approximate)
ANALYZE TABLE orders FOR COLUMNS (id, cust_id)
    WITH SKETCHES
    SAMPLE 10 PERCENT;

-- Analyze all columns used in joins (requires frequency data)
ANALYZE TABLE orders FOR JOIN COLUMNS WITH SKETCHES;
```

### 5.2 Implementation

```java
public class AnalyzeTableWithSketches {

    public void execute(AnalyzeTableStatement stmt) {
        TableHandle table = metadata.getTableHandle(stmt.getTable());
        List<ColumnHandle> columns = resolveColumns(stmt.getColumns());
        double sampleRate = stmt.getSamplePercent().orElse(100.0) / 100.0;

        // Build sketches via scan
        Map<ColumnHandle, JoinColumnSketch> sketches =
            collectSketches(table, columns, sampleRate);

        // Store in Iceberg metadata
        icebergConnector.updateJoinSketches(table, sketches);
    }

    private Map<ColumnHandle, JoinColumnSketch> collectSketches(
            TableHandle table,
            List<ColumnHandle> columns,
            double sampleRate) {

        // Initialize sketches
        Map<ColumnHandle, UpdateSketch> thetas = new HashMap<>();
        Map<ColumnHandle, CountMinSketch> cmses = new HashMap<>();
        Map<ColumnHandle, Long> rowCounts = new HashMap<>();

        for (ColumnHandle col : columns) {
            thetas.put(col, UpdateSketch.builder().setNominalEntries(16384).build());
            cmses.put(col, new CountMinSketch(0.0001, 0.99, 42));
            rowCounts.put(col, 0L);
        }

        // Scan table (with sampling if requested)
        try (RecordCursor cursor = openScan(table, columns, sampleRate)) {
            while (cursor.advanceNextPosition()) {
                for (int i = 0; i < columns.size(); i++) {
                    ColumnHandle col = columns.get(i);
                    long hash = hashValue(cursor, i);

                    thetas.get(col).update(hash);
                    cmses.get(col).add(hash, 1);
                    rowCounts.merge(col, 1L, Long::sum);
                }
            }
        }

        // Scale row counts if sampled
        if (sampleRate < 1.0) {
            for (ColumnHandle col : columns) {
                rowCounts.put(col, (long)(rowCounts.get(col) / sampleRate));
            }
        }

        // Build result
        return columns.stream().collect(toMap(
            col -> col,
            col -> new JoinColumnSketch(
                thetas.get(col).compact(),
                cmses.get(col),
                rowCounts.get(col),
                Instant.now())
        ));
    }
}
```

---

## 6. Join Column Frequency Tracking

To know which columns to analyze, we need to track join column usage.

### 6.1 Option A: Query Log Analysis (Offline)

Periodically analyze Presto query logs:

```java
public class JoinColumnFrequencyAnalyzer {

    public Map<QualifiedColumnName, Long> analyzeQueryLogs(
            Instant from, Instant to) {

        Map<QualifiedColumnName, Long> frequencies = new HashMap<>();

        for (QueryLog log : queryLogStore.getQueries(from, to)) {
            // Parse the plan JSON
            PlanNode plan = parsePlan(log.getPlanJson());

            // Extract join columns
            plan.accept(new PlanVisitor<Void, Void>() {
                @Override
                public Void visitJoin(JoinNode node, Void context) {
                    for (EquiJoinClause clause : node.getCriteria()) {
                        QualifiedColumnName left = resolve(clause.getLeft());
                        QualifiedColumnName right = resolve(clause.getRight());

                        frequencies.merge(left, 1L, Long::sum);
                        frequencies.merge(right, 1L, Long::sum);
                    }
                    return super.visitJoin(node, context);
                }
            }, null);
        }

        return frequencies;
    }
}
```

**Storage**: Write results to a file or table:

```sql
CREATE TABLE system.join_column_frequency (
    catalog VARCHAR,
    schema VARCHAR,
    table_name VARCHAR,
    column_name VARCHAR,
    join_count BIGINT,
    last_updated TIMESTAMP
);
```

### 6.2 Option B: HBO Counters (Online)

Lightweight counters in HBO (Redis or other backend):

```java
public class HBOJoinColumnTracker {
    private final RedisClient redis;

    public void recordJoinColumn(QualifiedColumnName column) {
        // Simple atomic increment
        redis.incr("join_freq:" + column.toString());
    }

    public Map<QualifiedColumnName, Long> getTopJoinColumns(int n) {
        // Scan keys, sort by count
        // Or use Redis sorted set for efficient top-N
    }
}

// Called from query execution
class SqlQueryExecution {
    void onQueryCompleted() {
        for (JoinNode join : extractJoins(plan)) {
            for (EquiJoinClause clause : join.getCriteria()) {
                hboTracker.recordJoinColumn(resolve(clause.getLeft()));
                hboTracker.recordJoinColumn(resolve(clause.getRight()));
            }
        }
    }
}
```

### 6.3 Recommendation

Use **Option A (Query Log Analysis)** for simplicity:
- No runtime overhead
- Batch processing is easier to manage
- Can reprocess history if logic changes
- Works with existing logging infrastructure

---

## 7. Auto-ANALYZE Job

Periodic job that analyzes hot columns:

```java
public class AutoAnalyzeJob implements Runnable {
    private final JoinColumnFrequencyAnalyzer frequencyAnalyzer;
    private final Duration staleness = Duration.ofDays(1);
    private final int topN = 500;

    @Override
    public void run() {
        // Get hot join columns from query logs
        Map<QualifiedColumnName, Long> frequencies =
            frequencyAnalyzer.analyzeQueryLogs(
                Instant.now().minus(Duration.ofDays(7)),
                Instant.now());

        // Sort by frequency, take top N
        List<QualifiedColumnName> hotColumns = frequencies.entrySet().stream()
            .sorted(Map.Entry.<QualifiedColumnName, Long>comparingByValue().reversed())
            .limit(topN)
            .map(Map.Entry::getKey)
            .collect(toList());

        // Group by table
        Map<TableName, List<String>> columnsByTable = hotColumns.stream()
            .collect(groupingBy(
                QualifiedColumnName::getTable,
                mapping(QualifiedColumnName::getColumn, toList())));

        // Analyze each table
        for (var entry : columnsByTable.entrySet()) {
            TableName table = entry.getKey();
            List<String> columns = entry.getValue();

            // Check if sketches are stale
            if (needsRefresh(table, columns)) {
                String sql = format(
                    "ANALYZE TABLE %s FOR COLUMNS (%s) WITH SKETCHES SAMPLE 10 PERCENT",
                    table,
                    String.join(", ", columns));

                executeAnalyze(sql);
            }
        }
    }

    private boolean needsRefresh(TableName table, List<String> columns) {
        Table icebergTable = catalog.loadTable(table);

        for (String column : columns) {
            Optional<JoinColumnSketch> sketch =
                icebergTable.getJoinColumnSketch(column);

            if (sketch.isEmpty() ||
                sketch.get().collectedAt().isBefore(Instant.now().minus(staleness))) {
                return true;
            }
        }
        return false;
    }
}
```

---

## 8. Query Optimizer Integration

### 8.1 Fetching Sketches at Plan Time

```java
public class IcebergJoinStatsRule implements Rule<JoinNode> {

    @Override
    public Result apply(JoinNode join, Captures captures, Context context) {
        for (EquiJoinClause clause : join.getCriteria()) {
            // Resolve to Iceberg tables
            Optional<IcebergTable> leftTable = resolveIcebergTable(clause.getLeft());
            Optional<IcebergTable> rightTable = resolveIcebergTable(clause.getRight());

            if (leftTable.isEmpty() || rightTable.isEmpty()) {
                continue;  // Not Iceberg tables
            }

            // Fetch sketches
            Optional<JoinColumnSketch> leftSketch =
                leftTable.get().getJoinColumnSketch(clause.getLeft().getName());
            Optional<JoinColumnSketch> rightSketch =
                rightTable.get().getJoinColumnSketch(clause.getRight().getName());

            if (leftSketch.isPresent() && rightSketch.isPresent()) {
                JoinEstimate estimate = computeJoinEstimate(
                    leftSketch.get(), rightSketch.get());

                // Update stats provider
                context.getStatsProvider().setJoinStats(join.getId(), estimate);
            }
        }

        return Result.empty();
    }
}
```

### 8.2 Computing Join Estimate from Sketches

```java
public class SketchBasedJoinEstimator {

    public JoinEstimate computeJoinEstimate(
            JoinColumnSketch left,
            JoinColumnSketch right) {

        // 1. Compute key intersection using Theta sketches
        Intersection intersection = SetOperation.builder().buildIntersection();
        intersection.intersect(left.getThetaSketch());
        intersection.intersect(right.getThetaSketch());
        CompactSketch intersectionSketch = intersection.getResult();

        double matchingKeys = intersectionSketch.getEstimate();
        double leftNDV = left.getThetaSketch().getEstimate();
        double rightNDV = right.getThetaSketch().getEstimate();

        // 2. Compute containment
        double containmentLR = matchingKeys / leftNDV;   // L keys in R
        double containmentRL = matchingKeys / rightNDV;  // R keys in L

        // 3. Estimate output using CMS frequencies
        //    For each key in intersection, multiply frequencies
        long sampleOutput = 0;
        double thetaL = left.getThetaSketch().getTheta();
        double thetaR = right.getThetaSketch().getTheta();

        // Get retained hashes from intersection
        long[] hashes = getRetainedHashes(intersectionSketch);

        for (long hash : hashes) {
            long freqL = left.getCmsSketch().estimateCount(hash);
            long freqR = right.getCmsSketch().estimateCount(hash);
            sampleOutput += freqL * freqR;
        }

        // 4. Scale by theta (sample rate)
        double scaleFactor = 1.0 / (thetaL * thetaR);
        double totalOutput = sampleOutput * scaleFactor;

        // 5. Compute bidirectional fanout
        double lrFanout = totalOutput / left.getRowCount();
        double rlFanout = totalOutput / right.getRowCount();

        return new JoinEstimate(
            totalOutput,
            lrFanout,
            rlFanout,
            containmentLR,
            containmentRL);
    }

    private long[] getRetainedHashes(CompactSketch sketch) {
        // Extract the actual hash values retained in the sketch
        // This is available via sketch.getRetainedEntries()
        return sketch.getCache();  // Internal array of retained hashes
    }
}
```

### 8.3 Intermediate Result Sketches (For Join Reordering)

```java
public JoinColumnSketch computeIntermediateSketch(
        JoinColumnSketch left,
        JoinColumnSketch right) {

    // 1. Intersection gives output keys
    Intersection intersection = SetOperation.builder().buildIntersection();
    intersection.intersect(left.getThetaSketch());
    intersection.intersect(right.getThetaSketch());
    CompactSketch outputTheta = intersection.getResult();

    // 2. Build CMS for intermediate frequencies
    CountMinSketch outputCms = new CountMinSketch(0.0001, 0.99, 42);

    for (long hash : getRetainedHashes(outputTheta)) {
        long freqL = left.getCmsSketch().estimateCount(hash);
        long freqR = right.getCmsSketch().estimateCount(hash);
        outputCms.add(hash, freqL * freqR);
    }

    // 3. Estimated row count
    JoinEstimate est = computeJoinEstimate(left, right);

    return new JoinColumnSketch(
        outputTheta,
        outputCms,
        (long) est.getOutputCardinality(),
        Instant.now());
}
```

This enables comparing join orders:

```java
// Compare (A ⋈ B) ⋈ C vs (A ⋈ C) ⋈ B
JoinColumnSketch ab = computeIntermediateSketch(sketchA, sketchB);
JoinEstimate abcEst = computeJoinEstimate(ab, sketchC);

JoinColumnSketch ac = computeIntermediateSketch(sketchA, sketchC);
JoinEstimate acbEst = computeJoinEstimate(ac, sketchB);

// Pick order with smaller intermediate cardinality
```

---

## 9. Size and Accuracy

### 9.1 Sketch Sizes

| Component | Configuration | Size |
|-----------|---------------|------|
| Theta Sketch | k=16384 | 128 KB |
| CMS | 4 × 65536 × 4 bytes | 1 MB |
| Metadata | timestamps, row count | ~100 bytes |
| **Total per column** | | **~1.1 MB** |

### 9.2 Storage Budget

| Scenario | Columns | Total Size |
|----------|---------|------------|
| Small warehouse | 100 hot columns | 110 MB |
| Medium warehouse | 500 hot columns | 550 MB |
| Large warehouse | 2000 hot columns | 2.2 GB |

Note: This is stored in Iceberg metadata, distributed across tables.

### 9.3 Accuracy

| Metric | Theta Error | CMS Error | Combined |
|--------|-------------|-----------|----------|
| NDV | ~1% | N/A | ~1% |
| Containment | ~2% | N/A | ~2% |
| Frequency | N/A | ~0.01% overestimate | ~0.01% |
| Fanout | ~2% | ~1% | ~3% |

### 9.4 Limitations

1. **Sample shrinkage**: Theta intersection reduces retained hashes
   - Deep join trees lose statistical power
   - Recommend limiting reordering to 2-3 joins

2. **Independence assumption**: Assumes filter selectivity independent of join
   - Correlated filters may cause errors
   - Documented as known limitation

3. **Staleness**: Sketches may be outdated
   - Mitigated by periodic ANALYZE
   - Can skip optimization if sketches too old

---

## 10. Implementation Phases

### Phase 1: Foundation
- Add CMS to Iceberg metadata alongside Theta
- Implement ANALYZE TABLE ... WITH SKETCHES
- Storage in table properties

### Phase 2: Query Log Integration
- Build query log analyzer for join column frequency
- Store frequency data in system table

### Phase 3: Optimizer Integration
- Fetch sketches in optimizer rules
- Use for cost estimation (not reordering yet)

### Phase 4: Auto-ANALYZE
- Background job using frequency data
- Automatic staleness refresh

### Phase 5: Join Ordering (Optional)
- Intermediate sketch computation
- Limited reordering (2-3 joins)
- Extensive testing for correctness

---

## 11. Open Questions

1. **CMS in Iceberg**: Should CMS be a first-class Iceberg feature, or Presto-specific metadata?

2. **Sketch format**: Use Apache DataSketches serialization, or custom format?

3. **Incremental updates**: Can sketches be updated incrementally on data append, or always full rebuild?

4. **Multi-column joins**: How to handle composite join keys (a.x, a.y) = (b.x, b.y)?

5. **Cross-catalog joins**: What if one table is Iceberg and another is Hive?

---

## 12. References

1. Apache DataSketches: https://datasketches.apache.org/
2. Iceberg Table Spec: https://iceberg.apache.org/spec/
3. Count-Min Sketch: Cormode & Muthukrishnan, 2005
4. Theta Sketch: https://datasketches.apache.org/docs/Theta/ThetaSketchFramework.html

---

*Document Version: 0.1*
*Last Updated: January 2025*
