# Algebraic Techniques for Trading Execution Time for GPU Memory

## Status: Research notes
## Date: 2026-02-08
## Context: Companion to GPU_FALLBACK_STRATEGIES.md

---

## Motivation

GPU VRAM is limited (40-80GB per GPU). When intermediate results exceed VRAM, the query
fails or falls back to slower paths (UVM page faulting, Grace partitioning, CPU fallback).
Relational algebra provides transformations that produce equivalent results using less
peak memory at the cost of more compute. On GPU clusters with NVLink (where reshuffling
is nearly free) and abundant parallel compute, these tradeoffs are often favorable.

The main design doc (GPU_FALLBACK_STRATEGIES.md) already covers:
- RPT / semi-join reduction via bloom filters (Section 4.2)
- Grace hash join on GPU (Section 4.4.1)
- Sort-based streaming aggregation (Section 4.4.2)
- Hybrid hash + sort aggregation (Section 4.4.3)
- Cascading partial aggregation (Section 4.5)
- NVLink-aware overpartitioning / salted joins (Section 4.3)

This document catalogs additional techniques not yet covered.

---

## 1. Eager Aggregation (Push GROUP BY Below Joins)

**Algebraic rule**: When a query aggregates over the result of a join, and the grouping
key includes (or functionally determines) the join key, the aggregation can be pushed
below the join. Even when full pushdown isn't possible, a partial pre-aggregation on
the join key is always safe for decomposable aggregates.

**Example**:
```sql
SELECT A.region, SUM(B.amount)
FROM A JOIN B ON A.key = B.key
GROUP BY A.region
```

```
Standard plan:              Eager aggregation:
  FINAL AGG                   FINAL AGG
    JOIN                        JOIN
      SCAN A                      SCAN A
      SCAN B                      PARTIAL AGG (GROUP BY B.key)
                                    SCAN B
```

If B has 1B rows but only 10M distinct join keys, the partial aggregation below the
join reduces B from 1B to 10M rows before the hash table is built. The join's build
or probe side shrinks by 100x.

**Decomposable aggregates** (safe for partial pre-aggregation):
- `SUM` → partial sums, final sum
- `COUNT` → partial counts, final sum
- `MIN` / `MAX` → partial min/max, final min/max
- `AVG` → partial (sum, count), final sum/sum
- `COUNT(DISTINCT)` → NOT decomposable (requires full key set)

**Memory saved**: Join hash table shrinks from raw row count to distinct-key count.
For power-law distributions (common in real data), this can be 10-1000x reduction.

**Extra compute**: One additional hash aggregation pass before the join. The aggregation
hash table is bounded by the join key cardinality (typically much smaller than the raw
table).

**GPU relevance**: The pre-aggregation hash table (keyed by join key cardinality) is
much smaller than the raw data. If it fits in VRAM, the aggregation runs at GPU speed.
The subsequent join then operates on drastically fewer rows.

**Presto status**: Presto's optimizer does not currently push aggregations below joins
automatically. `AddIntermediateAggregations` adds extra aggregation stages above the
join, not below. This would require a new optimizer rule that:
1. Detects aggregation above a join
2. Checks if grouping keys include or determine the join key
3. Inserts a partial pre-aggregation on the join key below the join
4. Adjusts the final aggregation to merge partial results

---

## 2. Sort-Merge Join (Bounded-Memory Alternative to Hash Join)

**Algebraic equivalence**: Hash join and sort-merge join produce identical results for
equi-joins. They differ in resource profile.

```
Hash join:        O(|build_side|) memory,  O(n) compute
Sort-merge join:  O(sort_buffer) memory,   O(n log n) compute
```

**How it works**:
```
Phase 1 — Sort both sides by join key:
  GPU radix sort: 1-4 GB/s throughput (one of GPU's fastest operations)
  For data > VRAM: external sort — sort chunks in VRAM, write sorted runs
  to host memory, k-way merge

Phase 2 — Merge:
  Two pointers walk through sorted sides
  Memory: O(1) — one page from each side
  Output matching pairs

Peak VRAM: max(sort_buffer_size, merge_buffer_size)
  sort_buffer_size is configurable (e.g., 4GB)
  merge_buffer_size is trivial (two pages)
```

**When sort-merge wins over hash join**:
- Build side doesn't fit in VRAM and Grace partitioning has too many partitions
- Both sides are already sorted (e.g., from a prior ORDER BY or sorted scan)
- Join produces very large output (merge streams naturally; hash join materializes)

**When hash join wins**:
- Build side fits in VRAM — O(n) vs O(n log n) compute
- Probe side is much larger than build side — hash join reads probe once

**GPU relevance**: GPU radix sort is extremely fast — it's a natural GPU operation
(parallel scatter into buckets). The sort is extra compute, but on GPU hardware with
abundant parallel throughput, the penalty is modest. The key benefit is **bounded VRAM**:
sort-merge never needs more than the sort buffer, regardless of input size.

**Presto status**: Presto uses hash join exclusively for equi-joins. Sort-merge join
would require a new join operator in Velox. The planner would choose between hash join
and sort-merge based on estimated build side size vs. available VRAM.

---

## 3. Multi-Pass (Double-Pipelined) Hash Join

**Algebraic equivalence**: Processing the build side in chunks and probing the full
probe side against each chunk produces the same result as a single-pass hash join.

```
Build side: 80GB (doesn't fit in 40GB VRAM)

Pass 1:
  Load build rows 0..N/2 → build 40GB hash table in VRAM
  Stream ALL probe rows → probe hash table → output matches for chunk 1
  Discard hash table

Pass 2:
  Load build rows N/2..N → build 40GB hash table in VRAM
  Stream ALL probe rows → probe hash table → output matches for chunk 2
  Discard hash table

Result = Pass 1 output ∪ Pass 2 output (complete, correct)
```

**Memory**: VRAM = max(chunk_size) — always bounded by the chunk size, not the full
build side.

**Extra compute**: K passes over the probe side (where K = ceil(build_size / VRAM)).
Each pass re-streams the probe data.

**Comparison with Grace hash join**:
- Grace: partitions BOTH sides by hash, processes one partition pair at a time.
  Requires writing partitions to host memory. If partitions are skewed, may need
  recursive re-partitioning.
- Multi-pass: partitions only the BUILD side into sequential chunks (no hashing needed
  for partitioning). Re-reads the PROBE side K times. No partition skew problem.
- Grace wins when K is large (many passes). Multi-pass wins when K is small (2-3) and
  the probe side streams cheaply.

**GPU relevance with NVLink**: If the probe side resides in host memory (after CPU
Parquet decode), re-streaming it over NVLink (~900 GB/s intra-node) or PCIe (64 GB/s)
is relatively cheap. For K=2 (build side is 2x VRAM), the overhead is one extra probe
transfer — often acceptable vs. the complexity of Grace partitioning.

**Presto status**: Not implemented. Would require extending the hash join operator in
Velox to support chunked build with probe replay. The coordinator would need to signal
the chunk count based on estimated build size vs. VRAM budget.

---

## 4. Join Reordering for Peak Memory Minimization

**Standard join ordering** minimizes total intermediate result size (total compute work).
For GPU, the binding constraint is **peak VRAM at any point during execution**, not total
work. These are different optimization objectives.

**Example**: Three-way join A(10GB) ⋈ B(50GB) ⋈ C(2GB)

```
Min-work order (standard optimizer):
  (A ⋈ C) ⋈ B
  Peak memory: hash table for B = 50GB  (may not fit in VRAM)

Min-peak-memory order:
  (B ⋈ C) ⋈ A
  Step 1: hash table for C = 2GB  (fits easily)
          B ⋈ C produces intermediate result, say 5GB after C filters B
  Step 2: hash table for intermediate = 5GB  (fits easily)
  Peak memory: max(2GB, 5GB) = 5GB

Or: (A ⋈ B) ⋈ C
  Step 1: hash table for A = 10GB (fits)
          A ⋈ B produces intermediate, say 8GB
  Step 2: hash table for C = 2GB (fits)
  Peak memory: max(10GB, 2GB) = 10GB
```

The min-peak-memory order avoids ever having 50GB in a hash table.

**Implementation**: Extend the join enumerator's dynamic programming with a memory
dimension. Instead of DP state = (set of joined tables, cost), use:
  DP state = (set of joined tables, cost, peak_memory)

Prune plans where peak_memory exceeds VRAM budget. Among feasible plans, minimize cost.

**Interaction with RPT**: RPT reduces table sizes BEFORE join ordering decisions matter.
With RPT, most tables shrink enough that any join order fits in VRAM. Memory-minimizing
join ordering is the fallback for cases where RPT reduction is insufficient.

**Presto status**: Presto's join enumerator (`JoinEnumerator`, `CostBasedJoinReorder`)
optimizes for estimated cost, not peak memory. Adding a VRAM budget constraint would
require extending the DP state space. The cost model already estimates intermediate
sizes; the missing piece is tracking peak memory across the join sequence.

---

## 5. Factorized Execution (Avoid Materializing Large Intermediates)

**The problem**: Multi-way joins can produce intermediate results much larger than their
inputs. A ⋈ B might produce |A| × |B| rows in the worst case (if every A row matches
every B row). Even average cases can have significant fanout.

**Factorized representations** keep relations separate and combine lazily:
```
Standard execution of A ⋈ B ⋈ C:
  Step 1: A ⋈ B → intermediate (possibly |A| × |B| rows)
  Step 2: intermediate ⋈ C → final result
  Peak memory: size of largest intermediate

Factorized execution:
  Maintain A, B, C as separate indexed relations
  For each tuple a ∈ A:
    Find matching B tuples: B_a = {b ∈ B | b.key = a.key}
    For each b ∈ B_a:
      Find matching C tuples: C_b = {c ∈ C | c.key = b.key}
      Emit (a, b, c)
  Memory: O(|A| + |B| + |C|) — no intermediate materialization
```

**Worst-case optimal joins** (Leapfrog Trie Join, Generic Join) extend this idea to
guarantee that the total work is proportional to the input + output size, never to
intermediate results. For cyclic queries (which RPT can't fully handle), this provides
memory-bounded execution.

**GPU relevance**: Factorized execution avoids VRAM blowup from intermediate
materialization. The memory footprint is bounded by the sum of input sizes, not their
product. This is especially valuable for high-fanout joins (1:N or N:M).

**Tradeoffs**:
- More complex execution engine (index-based lookup instead of hash table probe)
- May be slower for low-fanout joins where hash join is optimal
- Not a standard operator in any major distributed SQL engine today

**Presto status**: Not implemented. This would be a significant research effort. More
practically, the combination of RPT (reduce inputs) + Grace hash join (bound per-partition
memory) + memory-minimizing join order addresses most cases without factorized execution.

---

## 6. LEFT JOIN Plan Decomposition

**Algebraic identity**:
```
A LEFT JOIN B ON A.key = B.key
≡ (A INNER JOIN B ON A.key = B.key)
  UNION ALL
  (A ANTI JOIN B ON A.key = B.key → pad B columns with NULLs)
```

**Why this matters for RPT**: RPT cannot filter the preserved (left) side of a LEFT
JOIN — doing so would lose rows that should produce NULL-padded output. But the INNER
JOIN portion supports full bidirectional RPT, including I/O pruning on the fact table.

For GPU specifically, this decomposition is less critical than it sounds: the probe
side (fact) streams through in batches and never needs to fit in VRAM. The build side
(hash table) is what must fit, and RPT's backward pass already reduces it for LEFT
JOINs. The decomposition adds I/O and CPU efficiency on top of the VRAM-critical
build-side reduction.

See GPU_FALLBACK_STRATEGIES.md Open Question #9 for full details including HBO
integration and caching analysis.

---

## 7. Theta-Join to Equi-Join Decomposition

**Problem**: Theta joins (non-equality conditions like `A.x > B.y`) require nested-loop
or band join execution — neither is GPU-friendly. They also produce large intermediates.

**Decomposition**: Convert to equi-join on a bucketed version of the key + post-filter:
```sql
-- Original theta join:
SELECT * FROM A, B WHERE A.timestamp BETWEEN B.start AND B.end

-- Decomposed:
SELECT * FROM A JOIN B
  ON A.time_bucket = B.time_bucket   -- equi-join (GPU-friendly hash join)
  WHERE A.timestamp BETWEEN B.start AND B.end  -- post-filter
```

Where `time_bucket = floor(timestamp / bucket_size)`.

**Memory profile**: The equi-join hash table is keyed by buckets (bounded cardinality),
not raw values. The post-filter is stateless. Trade: some false-positive matches pass
through the equi-join and are filtered out, adding compute but bounding memory.

**GPU relevance**: Converts a GPU-hostile operation (nested loop) into a GPU-native one
(hash join + filter). The equi-join runs at full GPU throughput; the post-filter is
embarrassingly parallel.

**Presto status**: Presto supports spatial joins (`SpatialJoinNode`) which use a similar
bucketing approach. General theta-join bucketing would be a new optimizer rule.

---

## Summary: Applicability and Priority

| Technique | Memory Saved | Extra Compute | Complexity |
|---|---|---|---|
| **Eager aggregation** | Join input: raw rows → distinct keys | Pre-aggregation pass | Low — optimizer rule |
| **Sort-merge join** | O(sort_buffer) vs O(build_side) | O(n log n) vs O(n) | Medium — new operator |
| **Multi-pass hash join** | 1/N of build side per pass | N probe-side passes | Medium — operator extension |
| **Memory-min join order** | Lower peak VRAM across plan | Possibly more total work | Medium — DP extension |
| **Factorized execution** | O(sum of inputs) vs O(product) | Lazy materialization | High — research |
| **LEFT JOIN decomposition** | Unlock RPT I/O pruning on probe | INNER + ANTI scans | Medium — optimizer rule |
| **Theta-join bucketing** | Bounded hash table (bucket count) | False-positive filtering | Medium — optimizer rule |

**Recommended priority for Presto GPU execution**:
1. **Eager aggregation** — highest ROI, well-understood, directly reduces VRAM pressure
2. **Memory-minimizing join order** — extends existing join enumeration, no new operators
3. **Multi-pass hash join** — simple operator extension, handles 2-3x VRAM overflow with NVLink
4. **Sort-merge join** — bounded-memory fallback for extreme cases
5. **LEFT JOIN decomposition** — niche but important for LEFT JOIN-heavy workloads
6. **Theta-join bucketing** — converts GPU-hostile operations to GPU-native
7. **Factorized execution** — long-term research, high implementation cost

---

## 8. Parquet Bloom Filters and RPT (Investigated — Not Viable)

**Question**: Parquet files can contain per-row-group, per-column bloom filters (spec
2.10+). Can these pre-built BFs replace or accelerate RPT scan phases?

### Approach 1: OR-Merge Parquet BFs to Skip a Scan Phase

Instead of scanning a dimension table to build BF_dim, read Parquet BFs from file
metadata, OR-merge across row groups, and use the result as BF_dim.

**Why it fails — FPR saturation**: Parquet BFs are sized per-row-group. Each BF uses
m ≈ 10n bits for n keys at 1% FPR (k=7 hash functions). Merging R row groups stuffs
R×n keys into m bits:

```
Merged FPR ≈ (1 - e^(-k·N/m))^k = (1 - e^(-0.7·R))^7

R = 1:    FPR ≈ 1%      (by design)
R = 2:    FPR ≈ 12%     (marginal)
R = 5:    FPR ≈ 90%     (useless)
R = 10:   FPR ≈ 99.3%   (fully saturated)
R = 100:  FPR ≈ 100%    (all bits set)
```

A typical table has tens to hundreds of row groups → merged BF is completely full.

**Additional failure mode — unfiltered keys**: Even if saturation were solved, Parquet
BFs contain ALL keys in each row group. RPT's value comes from predicates narrowing the
key set (e.g., `dim WHERE region = 'US'` selects 2M of 10M customers). The Parquet BF
would contain all 10M keys, bypassing the predicate filtering that makes RPT useful.

**Also**: BFs are not enumerable. You can't intersect two BFs to check for overlap unless
they share the same size and hash functions (bitwise AND), which Parquet BFs across
different tables won't.

### Approach 2: Row-Group Pruning via Parquet BFs

During the RPT forward pass, probe concrete dim keys against each fact row group's
Parquet BF. If no dim keys match → skip the row group entirely. This adds a pruning
level between Level 3 (min/max range) and Level 4 (row-level BF filtering).

**Why it's marginal**: Velox uses columnar projection. Level 4 already reads only the
join key column first, applies the RPT BF, then reads remaining columns for survivors.
The Parquet BF check would only save reading the join key column's data pages — a narrow
column in the typical case:

```
Cost per row group (1M rows, int64 join key):

  Parquet BF check:                          Just read the column + BF-filter:
    Read BF from footer:  ~4 KB + seek         Read key data pages:  ~2-8 MB
    Probe 1000 dim keys:  ~50 μs CPU           Decompress + decode:  ~200 μs
    Total: 4 KB + 50 μs                        BF-probe each row:    ~500 μs
                                                Total: 2-8 MB + 700 μs

  Savings from skipping: 2-8 MB + 650 μs per row group
```

The savings require high skip rate (>80%), large key columns (strings, not integers), or
expensive decompression (ZSTD). For typical star schemas with integer foreign keys,
Level 4 is already efficient enough.

**Predicate interaction**: Parquet BFs on the target table (fact) still work regardless
of predicates on either table — they answer "is key X in this row group?" But the
marginal savings vs columnar projection make this not worth the complexity.

### Velox Status

Velox has a complete `BlockSplitBloomFilter` implementation
(`velox/dwio/parquet/common/BloomFilter.h`) and Thrift metadata support
(`bloom_filter_offset` in `ColumnMetaData`). Reader integration is missing —
`ParquetData::filterRowGroups()` only uses column statistics, and
`ColumnChunkMetaDataPtr` doesn't expose the BF offset. Given the marginal benefit
analysis, this is low priority for the GPU execution strategy.

---

## References

- Yan & Larson, "Eager Aggregation and Lazy Aggregation" (VLDB 1995)
- Neumann & Radke, "Adaptive Optimization of Very Large Join Queries" (VLDB 2018)
- Ngo et al., "Worst-Case Optimal Join Algorithms" (PODS 2012)
- Veldhuizen, "Leapfrog Triejoin: A Simple, Worst-Case Optimal Join Algorithm" (ICDT 2014)
- Graefe, "Sort-Merge-Join: An Idea Whose Time Has(h) Passed?" (ICDE 1994)
- GPU_FALLBACK_STRATEGIES.md (companion design document)
- GPU_FALLBACK_STRATEGIES_CRITICAL_REVIEW.md (open issues)
