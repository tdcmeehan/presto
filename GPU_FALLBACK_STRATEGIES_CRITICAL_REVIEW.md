# GPU Execution Strategies — Critical Review

## Status: Open issues against GPU_FALLBACK_STRATEGIES.md v0.3
## Date: 2026-02-08 (updated)

---

## Issue 1: RPT Only Works for Acyclic Query Graphs

**Severity**: Fundamental scope limitation
**Status**: Mitigated

The Yannakakis algorithm — and RPT's approximation of it — only guarantees full
reduction for **acyclic queries**. The doc never states this constraint.

Many real-world queries have cyclic join graphs: self-joins, triangle patterns
(e.g., `A JOIN B ON a.x=b.x JOIN C ON b.y=c.y AND a.z=c.z`), graph queries. For
cyclic queries, RPT's LargestRoot algorithm can still generate a transfer schedule,
but the full-reduction guarantee no longer holds — some non-surviving rows may pass
through the bloom filters.

**Impact**: The doc's claims about hash table reduction depend on full reduction.
Without it, the reduction ratio is less predictable and potentially much smaller.

**Resolution**: Mitigated via materialized CTEs as cycle breakers (Section 4.2).
RPT's `LargestRoot` identifies back edges → materialize as CTEs → each section's
join graph is acyclic. For queries where materialization cost exceeds benefit, fall
back to partial reduction. The mechanism leverages existing CTE infrastructure
(`LogicalCteOptimizer` → `PhysicalCteOptimizer` → `SequenceNode`). New work is
limited to cycle detection and synthetic CTE generation.

**Remaining gap**: The doc now describes the mechanism but doesn't acknowledge the
acyclicity requirement in the introductory RPT description (Section 2.2, lines 46-67).
A reader encountering RPT for the first time won't know about this limitation until
deep in Section 4.2. The constraint should be stated upfront.

---

## Issue 2: The "10-100x" Hash Table Reduction Claim Is Unsupported

**Severity**: Misleading quantification
**Status**: OPEN — worse than before

The doc now repeats "10-100x" in **more places** than the original version:
- Line 66: "hash tables shrink 10-100x"
- Line 277: "10-100x smaller hash tables"
- Line 297: "reduces build sides by 10-100x"
- Line 345: "Hash tables 10-100x smaller → fit in VRAM"
- Line 1094: "Hash tables shrink 10-100x"

None of these are qualified or derived. The actual reduction is highly
workload-dependent:

- **TPC-H Q5** (`region = 'ASIA'`): filters to ~20% of nations → ~5x reduction
- **TPC-H Q3** (broad date filter): maybe 2-3x reduction
- **Star schema with selective dim filters** (e.g., `country = 'Luxembourg'`):
  potentially 100x+ reduction
- **Low-selectivity joins** (most keys match): ~1x — no meaningful reduction

The 100x figure requires very selective dimension predicates. It's the best case,
not the typical case.

**Impact**: Overstating the reduction ratio inflates RPT's apparent value and
understates the scenarios where RPT overhead isn't justified. This is especially
problematic now that "10-100x" is used to justify "fits in VRAM" (line 345) —
if the actual reduction is 3-5x for a typical workload, 800GB × 0.2 = 160GB,
which does NOT fit in VRAM.

**Resolution needed**: Replace "10-100x" with workload-qualified ranges. Provide
concrete TPC-H/TPC-DS examples with actual selectivities and reduction ratios.
Consider presenting a table:

```
| Query Pattern               | Typical Selectivity | Expected Reduction |
| Star, selective dim filter  | 1-5%                | 20-100x            |
| Star, moderate dim filter   | 10-30%              | 3-10x              |
| Star, broad/no dim filter   | 50-100%             | 1-2x               |
| Snowflake, multi-hop filter | varies               | 5-50x (compounds)  |
```

---

## Issue 3: RPT Doesn't Work for Complex Subqueries

**Severity**: Significant scope limitation
**Status**: Mitigated

RPT operates on **base table scans only**. When join inputs are subqueries —
aggregations, nested joins, CTEs, window functions — RPT cannot push bloom filters
into them.

**Resolution**: Mitigated via materialized CTEs (Section 4.2). Complex subquery
join inputs are materialized as temporary tables via the existing CTE pipeline.
The temporary table becomes a `TableScanNode` that RPT can scan. New work: (1)
detecting non-scannable join inputs during RPT planning, (2) wrapping them in
synthetic CTEs, (3) cost-based gating.

**Remaining gap**: The cost-based gating threshold is hand-waved. The doc says
"< 100MB: not worth it" and "> 1GB: clearly beneficial" but doesn't derive these
thresholds. The CTE materialization cost (temp table write I/O) should appear in
the quantitative cost model alongside the RPT scan costs.

---

## Issue 4: AsyncDataCache Contention Under Concurrent Workload

**Severity**: Optimistic assumption in cost model
**Status**: OPEN

The caching analysis assumes a single query using the `AsyncDataCache` in
isolation. In a production Presto cluster with dozens of concurrent queries, the
cache is shared. Between RPT Section 0 and Section 2 (potentially hundreds of
milliseconds apart), other queries' table scans can evict the key column pages.

`AsyncDataCache` uses LRU eviction — it makes no distinction between "RPT data
that will be reused in 200ms" and "random scan data from another query." Under
heavy concurrent load, the "backward pass is essentially free" claim degrades to
"backward pass may or may not hit cache, depending on concurrent workload."

**Impact**: The cost model's caching benefit (0.01s vs 0.10s backward pass) may
not materialize in production. The worst case reverts to the "without caching"
cost model.

**Resolution needed**: Present both cached and uncached cost models as bounds,
not assume cached as the default. Consider whether RPT-pinned cache entries
(priority hints) could prevent eviction. Evaluate whether SsdCache tier provides
a reliable middle ground.

---

## Issue 5: Section Scheduling Overhead Is Zero in the Cost Model

**Severity**: ~~Missing cost component~~ **Mitigated — lower than initially estimated**
**Status**: Mitigated

Code analysis revealed that all stages are pre-created upfront, split sources are
`LazySplitSource` (deferred construction), and section readiness is a pure state
check. The real latency is connector split enumeration (~10-100ms), which is
addressable through eager pre-enumeration during the previous section's execution.

**Revised overhead**: ~1-5ms per section transition with pre-enumeration. For 3
RPT sections: ~3-15ms total (negligible vs. scan time).

**Remaining gap**: The eager pre-enumeration optimization is described in the
critical review but not in the main doc. It should be incorporated into Section 4.2.

---

## Issue 6: GPU-CPU Exchange Data Path Is Hand-Waved

**Severity**: Missing implementation detail
**Status**: OPEN

Section 4.1 claims "different fragments in the same query can get different
GPU/CPU decisions" connected by exchanges. But the doc never addresses how data
moves between a GPU fragment and a CPU fragment.

- GPU workers produce data in VRAM (`cudf::column` / `CudfVector`)
- CPU workers expect host memory (Velox `RowVector`)
- The exchange layer needs explicit device-to-host copy, format conversion
  (cudf → Arrow → Velox), and serialization

This isn't just a serialization concern — the exchange shuffle itself may need
to be device-aware. A GPU-to-CPU exchange must copy data from VRAM before
serializing, and a CPU-to-GPU exchange must deserialize into VRAM after receiving.

**Impact**: The per-fragment GPU/CPU decision boundary may be constrained by
exchange overhead that the cost heuristic in Section 5.1 doesn't account for.

**Resolution needed**: Design the device-aware exchange layer. Quantify the
GPU→host→serialize→network→deserialize→GPU round-trip cost. Factor exchange
overhead into the per-fragment decision.

---

## Issue 7: RPT + Dynamic Filtering RFC Lifecycle Mismatch

**Severity**: Integration complexity underestimated
**Status**: Partially addressed

The main doc now lists specific extension points (lines 562-572):
- New `BloomFilterBuildStage` type
- Using existing filter distribution for BF propagation
- Transfer schedule generation via LargestRoot

But the fundamental lifecycle mismatch is still unaddressed:

- **RFC-0022 dynamic filters**: Generated *during* query execution. Hash join
  build side completes → filter extracted → pushed to probe side.
- **RPT bloom filters**: Generated *before* query execution in dedicated transfer
  sections. Filters are complete before the main query starts.

Specific mismatches:
- RFC-0022's `DynamicFilterFetcher` long-polls for filters during execution. RPT's
  BFs are complete before the main query — no long-polling needed.
- RFC-0022's transitive propagation works through join equality chains in the
  running query. RPT's propagation follows a transfer schedule computed by
  LargestRoot, independent of join order.
- Can RPT BFs and RFC-0022 dynamic filters coexist on the same join? If RPT
  provides a BF from the transfer phase AND the join build side generates a DF
  during execution, how do they compose?

The filter serialization format and coordinator-side application logic are likely
reusable. The orchestration, timing, and distribution mechanisms need new work.

**Resolution needed**: Separate clearly which RFC-0022 components are reused
(serialization, coordinator application, TupleDomain merging) vs. which are new
(transfer section orchestration, BF-build stage type, BF collection before
main query starts, BF+DF composition on the same join).

---

## Issue 8: NVLink Assumption May Not Hold for Commodity GPUs

**Severity**: Economic thesis undermined
**Status**: OPEN

Section 6 assumes ex-training GPUs retain NVLink connectivity. Training clusters
use NVLink because they're purpose-built DGX/HGX chassis. When GPUs enter the
secondary market, many will be repackaged in standard PCIe server chassis
**without NVLink**.

Without NVLink:
- Intra-node GPU-GPU bandwidth drops from ~900 GB/s to ~64 GB/s (PCIe 5.0 x16,
  bidirectional, shared across 8 GPUs — effectively ~8 GB/s per GPU pair)
- "Essentially free" shuffle becomes ~100x more expensive
- Overpartitioning goes from "no-brainer" to "potentially harmful"
- The entire Section 4.3 strategy (salted aggregation, salted joins, preemptive
  salting) becomes counterproductive

**Impact**: The overpartitioning strategy is the primary mechanism for bounding
per-GPU memory. Without NVLink, this falls apart and the architecture must rely
more heavily on Grace hash join (Section 4.4) and RPT reduction.

**Resolution needed**: Present both NVLink and PCIe-only architectures. The
commodity GPU thesis should acknowledge that NVLink-equipped commodity GPUs are
a best case, not the default. The overpartitioning strategy needs a PCIe-aware
fallback.

---

## Issue 9: Fault Tolerance for RPT Sections Is Unaddressed

**Severity**: Reliability gap
**Status**: OPEN

If a worker fails midway through an RPT section, the partial bloom filter on that
worker is lost. The coordinator must handle this, but options are unclear:

- **Retry the entire section**: Safe but expensive.
- **Retry just the failed worker's splits**: BFs support bitwise OR merge, so
  the surviving workers' partial BFs can be combined with the retried worker's.
  Needs new coordinator logic for partial section retry.
- **Skip the failed BF**: Proceed without that table's BF — safe but suboptimal.

Traditional query execution can reassign individual splits on failure. RPT
introduces section-level coupling — the BF is only useful when all splits have
contributed.

**Impact**: RPT adds a new failure mode that increases blast radius from
per-split to per-section.

**Resolution needed**: Design the failure handling strategy. BF merge-on-retry
is likely the right approach, but needs explicit design.

---

## Issue 10: HBO Can't Help When You Need It Most

**Severity**: Amortization story weaker than presented
**Status**: OPEN

HBO matches queries when input table sizes are within 10% of historical runs.
Fact tables in production often grow daily. If the table grows 15% between runs,
HBO's match fails and RPT reverts to conservative mode.

This creates an inversion:
- **Stable data** (HBO matches): RPT decisions are well-informed. But stable data
  also means simple table stats are reliable — reducing RPT's value.
- **Rapidly changing data** (HBO fails): RPT reverts to conservative overhead.
  This is exactly when the safety net is most needed.

**Impact**: The HBO amortization is most effective where it's least needed.

**Resolution needed**: Consider relaxing the HBO matching threshold for RPT
decisions (e.g., 30% tolerance for RPT vs. 10% for cardinality estimates). Or use
relative metrics (join selectivity ratio) rather than absolute sizes for matching.

---

## Issue 11: Salted Joins Replicate the Build Side

**Severity**: Memory pressure interaction
**Status**: OPEN

Section 4.3 says "Replicate build side N ways, salt probe side partition key."
If N=8 GPUs and the build side is 10GB after RPT reduction, replication creates
80GB total build-side data — each GPU holds its full 10GB copy.

Salted joins don't reduce per-GPU memory for the build side. They only reduce
per-GPU probe-side data. If the build side is what's causing OOM, salted joins
don't help.

For builds that don't fit: you'd need partitioned joins (hash-partition both
sides by join key, then salt within each partition), which is more complex.

**Resolution needed**: Clarify that salted joins help probe-side parallelism
and skew, not build-side memory pressure. Distinguish between salted joins
(build replication) and partitioned joins (build partitioning).

---

## Issue 12: Cost Model Ignores Parquet Metadata Overhead

**Severity**: ~~Optimistic cost model~~ **Resolved — not additional I/O**
**Status**: Resolved

RPT's forward pass reads data that would be read anyway — total I/O volume is
identical. The only overhead is ~5% more S3 requests in the worst case. With any
meaningful BF selectivity, RPT reduces total I/O. The Parquet metadata concern
is handled by `AsyncDataCache` and `FileHandleCache` across RPT sections.

---

## Issue 13: Cost Model Assumes Local NVMe — Completely Invalid for S3/HDFS

**Severity**: Fundamental cost model gap
**Status**: NEW

The entire quantitative cost model (lines 378-402) is based on a specific hardware
configuration: "2x EPYC 7763, 170 GB/s NVMe, 8x A100 80GB." This is an on-premise
setup with local SSDs.

**Most Presto deployments read from remote storage** (S3, GCS, HDFS, ADLS). The
performance characteristics are radically different:

```
                    Local NVMe    S3 (per-worker)    HDFS (per-worker)
Sequential read:    170 GB/s      ~0.5-2 GB/s        ~1-5 GB/s
Request latency:    ~50 μs        ~10-50 ms           ~1-5 ms
Request cost:       free          $0.40/M GETs        free
```

The cost model says RPT forward pass reads 20GB of key columns in 0.12s. On S3
at 1 GB/s per worker with 10 workers, that's 20GB / (10 × 1 GB/s) = 2s — 17x
longer. More critically, RPT's key-column-only reads generate many small I/O
requests (one per row group per file), and S3 latency per request is 10-50ms.
For 5000 row groups across 100 files: 5000 GETs × 30ms = 150s sequentially.
Even with parallelism and coalescing, S3 request overhead dominates.

**Impact**: The "0.3s total RPT overhead" is specific to local NVMe. On S3, RPT
overhead could be 5-30s — still potentially worthwhile for long queries, but the
cost-benefit calculus changes dramatically. The "skip RPT for short queries" threshold
shifts from <5s to potentially <60s.

**Resolution needed**: Present cost models for at least three storage tiers: local
NVMe, HDFS, and S3. The RPT skip threshold should be storage-aware. The cost
heuristic should use actual storage bandwidth (measurable at runtime) rather than
assuming local NVMe.

---

## Issue 14: Parquet BF Section Is 115 Lines to Conclude "Not Worth It"

**Severity**: Structural bloat
**Status**: NEW

Section 4.2.8 (lines 729-844) spends ~115 lines analyzing Parquet bloom filters
and their interaction with RPT, including FPR saturation math, cost comparisons,
and predicate interaction tables. The conclusion: "Parquet BFs are not a significant
optimization for RPT" and "not on the critical path for the GPU execution strategy."

This is a research dead end documented at the same level of detail as the core
strategies. It buries the actual conclusion and makes the document harder to read.
A reader navigating Section 4.2 encounters this long diversion between the important
progressive split pruning section and Section 4.3.

**Impact**: The doc's signal-to-noise ratio is degraded. Key design decisions
(NVLink overpartitioning, Grace hash join) get the same page space as this dead end.

**Resolution needed**: Condense Section 4.2.8 to ~15-20 lines: state the question,
give the two reasons it doesn't work (FPR saturation on merge, marginal savings vs
columnar projection for row-group pruning), and the conclusion. Move the detailed
analysis to the research companion doc (`GPU_ALGEBRAIC_MEMORY_TECHNIQUES.md`) or
an appendix.

---

## Issue 15: Duplicate Section Numbering

**Severity**: Document structure bug
**Status**: NEW

The document has two sections numbered "6":
- Line 1071: `## 6. Implementation Phases`
- Line 1120: `## 6. Commodity GPU Economics`

The second should be Section 7, making Open Questions Section 8 and References
Section 9.

---

## Issue 16: Superlinear Join Efficiency Claims Are Uncited

**Severity**: Unsupported technical claim
**Status**: NEW

Lines 283-311 make specific micro-architectural claims:
- "a hash table at 75% load has ~4x more cache misses per probe than one at 25% load"
- "L3 cache (256-384MB on modern servers)"
- "GPU's L2 cache (40-50MB on H100)"
- "each remaining probe is faster due to fewer collisions"

These claims are directionally correct — hash table probe cost does increase
superlinearly with load factor, and cache residency does matter. But the specific
numbers (4x, 256-384MB, 40-50MB) are stated as facts without citations.

The H100 L2 cache is actually 50MB (correct), but the "4x more cache misses at
75% vs 25% load" depends heavily on the hash table implementation (open addressing
vs chaining, probe sequence), key distribution, and table size relative to cache.

**Impact**: The superlinear argument is important — it's the reason RPT's value
compounds beyond the raw row-count reduction. But unsupported numbers weaken it.
A skeptical reader may dismiss the entire argument.

**Resolution needed**: Either cite sources for the specific numbers or soften the
language to "significantly more" without specific multipliers. The L3/L2 cache
sizes are verifiable from spec sheets — cite them. The collision chain behavior
should reference hash table performance literature.

---

## Issue 17: CTE Materialization Cost Missing from Quantitative Cost Model

**Severity**: Incomplete cost model
**Status**: NEW

Section 4.2 proposes two uses of CTE materialization: cycle breaking and
subquery-to-CTE promotion. Both involve writing intermediate results to temporary
tables (via `TableWriteNode`) and reading them back (via `TableScanNode`). The
quantitative cost model (lines 378-402) does not include this cost.

For cycle-breaking CTEs on large join results or subquery-to-CTE promotion on
large aggregation outputs, the materialization cost could be substantial:

```
Example: subquery outputs 50M rows × 100 bytes = 5GB
  Write to temp table (local NVMe): 5GB / 10 GB/s = 0.5s
  Read back during RPT scan:        5GB × 5% (key column) / 170 GB/s = ~0.001s
  Total CTE overhead: ~0.5s — comparable to the RPT overhead itself
```

For S3-backed deployments, writing temp tables is even more expensive.

**Impact**: The cost model may underestimate total RPT overhead when CTEs are
needed. The "cost-based gating" threshold for when CTE materialization is
worthwhile needs to be quantified, not hand-waved.

**Resolution needed**: Add CTE materialization cost to the quantitative cost
model. Define the gating threshold: materialization is worthwhile when
`RPT_benefit > CTE_write_cost + CTE_read_cost`.

---

## Issue 18: What Happens When Reduced Hash Table Still Exceeds VRAM?

**Severity**: Gap in fallback chain
**Status**: NEW

Line 345 states: "Hash tables 10-100x smaller → fit in VRAM." But what if the
original hash table is 4TB (large fact-to-fact join, or low-selectivity star
schema)? Even 100x reduction gives 40GB — fits in one 80GB GPU. But:
- 10x reduction gives 400GB — does NOT fit in one GPU
- 3x reduction gives 1.3TB — does NOT fit in 8 GPUs combined (640GB)

The doc presents RPT and GPU memory-bounded operators (Grace hash join) as
separate phases (Phase 2 and Phase 4), but doesn't describe how they compose.
After RPT reduces the hash table from 4TB to 400GB, what happens next?

The intended answer is probably: Grace hash join partitions the 400GB across
multiple passes. But this interaction isn't stated. The doc's narrative implies
RPT makes everything fit, and Grace hash join is a separate fallback for when
RPT isn't available.

**Impact**: The overall strategy's coverage for large joins is unclear. The reader
needs to understand the composition: RPT reduces the problem, then Grace hash join
handles any remaining overflow.

**Resolution needed**: Explicitly state the composition chain:
1. RPT reduces build side (e.g., 4TB → 400GB)
2. NVLink overpartitioning distributes across GPUs (400GB / 8 = 50GB per GPU)
3. If per-GPU share still exceeds VRAM: Grace hash join within each GPU
4. If still failing: UVM / CPU fallback

This makes the layered defense clear rather than presenting each technique
as an independent solution.

---

## Summary

| Issue | Severity | Status | Topic |
|-------|----------|--------|-------|
| 1  | Scope limitation | Mitigated | RPT acyclicity requirement |
| 2  | Misleading | **OPEN** | "10-100x" unsupported, now in more places |
| 3  | Scope limitation | Mitigated | RPT + complex subqueries |
| 4  | Optimistic model | **OPEN** | AsyncDataCache concurrent contention |
| 5  | Missing cost | Mitigated | Section scheduling overhead |
| 6  | Missing detail | **OPEN** | GPU-CPU exchange data path |
| 7  | Underestimated | Partial | RPT + Dynamic Filtering lifecycle |
| 8  | Economic thesis | **OPEN** | NVLink may not exist on commodity GPUs |
| 9  | Reliability | **OPEN** | RPT section fault tolerance |
| 10 | Amortization | **OPEN** | HBO inversion problem |
| 11 | Memory pressure | **OPEN** | Salted joins replicate build side |
| 12 | Cost model | Resolved | Parquet metadata I/O |
| 13 | Cost model | **NEW** | S3/HDFS storage invalidates cost numbers |
| 14 | Structure | **NEW** | 115-line dead-end Parquet BF section |
| 15 | Structure | **NEW** | Duplicate section numbering |
| 16 | Unsupported claim | **NEW** | Superlinear efficiency uncited |
| 17 | Cost model | **NEW** | CTE materialization cost missing |
| 18 | Fallback chain | **NEW** | Reduced hash table still > VRAM |

**Open issues**: 12 (7 original + 5 new)
**Mitigated**: 4 (Issues 1, 3, 5, 7-partial)
**Resolved**: 1 (Issue 12)
**Highest priority**: Issues 2 (misleading claims), 13 (S3 cost model), 18 (fallback composition)
