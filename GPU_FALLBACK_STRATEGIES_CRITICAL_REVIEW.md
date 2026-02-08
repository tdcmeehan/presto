# GPU Execution Strategies — Critical Review

## Status: Open issues against GPU_FALLBACK_STRATEGIES.md v0.3
## Date: 2026-02-08

---

## Issue 1: RPT Only Works for Acyclic Query Graphs

**Severity**: Fundamental scope limitation

The Yannakakis algorithm — and RPT's approximation of it — only guarantees full
reduction for **acyclic queries**. The doc never states this constraint.

Many real-world queries have cyclic join graphs: self-joins, triangle patterns
(e.g., `A JOIN B ON a.x=b.x JOIN C ON b.y=c.y AND a.z=c.z`), graph queries. For
cyclic queries, RPT's LargestRoot algorithm can still generate a transfer schedule,
but the full-reduction guarantee no longer holds — some non-surviving rows may pass
through the bloom filters.

**Impact**: The doc's claims about hash table reduction depend on full reduction.
Without it, the reduction ratio is less predictable and potentially much smaller.

**Resolution needed**: State the acyclicity requirement explicitly. Discuss what
happens for cyclic queries and whether partial reduction is still valuable.

---

## Issue 2: The "10-100x" Hash Table Reduction Claim Is Unsupported

**Severity**: Misleading quantification

The doc repeats "10-100x" six times but never derives or justifies it. The actual
reduction is highly workload-dependent:

- **TPC-H Q5** (`region = 'ASIA'`): filters to ~20% of nations → ~5x reduction
- **TPC-H Q3** (broad date filter): maybe 2-3x reduction
- **Star schema with selective dim filters** (e.g., `country = 'Luxembourg'`):
  potentially 100x+ reduction
- **Low-selectivity joins** (most keys match): ~1x — no meaningful reduction

The 100x figure requires very selective dimension predicates. It's the best case,
not the typical case.

**Impact**: Overstating the reduction ratio inflates RPT's apparent value and
understates the scenarios where RPT overhead isn't justified.

**Resolution needed**: Replace "10-100x" with workload-qualified ranges. Provide
concrete TPC-H/TPC-DS examples with actual selectivities and reduction ratios.

---

## Issue 3: RPT Doesn't Work for Complex Subqueries

**Severity**: Significant scope limitation

RPT operates on **base table scans only**. When join inputs are subqueries —
aggregations, nested joins, CTEs, window functions — RPT cannot push bloom filters
into them. The subquery output is an opaque leaf.

Example that RPT can't help:
```sql
SELECT *
FROM fact f
JOIN (SELECT customer_id, SUM(amount) AS total
     FROM orders GROUP BY customer_id
     HAVING SUM(amount) > 1000) high_value
ON f.customer_id = high_value.customer_id
```

RPT can build a BF from `fact.customer_id`, but it can't push that into the
aggregation subquery — the subquery must run to completion first.

**Impact**: The doc's star schema examples all use simple base table scans, making
RPT look more broadly applicable than it is. Many analytical queries have subquery
join inputs (derived tables, aggregated dimensions, CTEs).

**Resolution needed**: Document the limitation explicitly. Identify the query
patterns where RPT applies (star/snowflake with base table joins) vs. where it
doesn't (subquery join inputs). Consider whether RPT sections could be placed
after subquery materialization.

---

## Issue 4: AsyncDataCache Contention Under Concurrent Workload

**Severity**: Optimistic assumption in cost model

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

The cost model accounts for scan I/O time but doesn't include coordinator overhead
between sections. Initial concern was 50-200ms per section transition.

**Code analysis reveals the overhead is much lower than feared:**

1. **All stages are pre-created upfront.** `SqlQueryScheduler` constructor calls
   `createStageExecutions()` recursively for ALL sections, creating ALL
   `StageExecutionAndScheduler` objects at query start time. Section transition
   is just a map lookup via `getStageExecutions()`, not stage creation.

2. **Split sources are `LazySplitSource`.** `SplitSourceFactory.visitTableScan()`
   wraps every source in `LazySplitSource` — a lazy proxy that only calls the
   connector's `getSplits()` on first `getNextBatch()`. Construction is free.

3. **Section readiness check is pure state inspection.** `isReadyForExecution()`
   just checks `stageExecution.getState() != PLANNED` and child section states.
   No I/O, no allocation.

**Actual section transition path:**
```
Section N root stage → FINISHED
  → stateChangeListener calls startScheduling()       (~0ms, callback)
  → executor.submit(schedule)                          (~1ms, queue)
  → getSectionsReadyForExecution()                     (~0ms, state checks)
  → getStageExecutions()                               (~0ms, map lookup)
  → schedule loop: stageScheduler.schedule()
    → splitSource.getNextBatch()
      → LazySplitSource.getDelegate() triggers connector (~10-100ms)
      → This is the real latency: partition/file listing
```

**Remaining concern: connector split enumeration latency.** The first
`getNextBatch()` for each new section hits the connector (Hive metastore,
Iceberg manifest reads). For large tables this can be 10-100ms. But this is
**addressable** through eager pre-enumeration:

**Mitigation: eager split pre-enumeration for RPT sections.**
Since we know at planning time which tables are scanned in Section N+1, the
`LazySplitSource` can be triggered eagerly during Section N's execution:

```
Section 0 executing (scanning dims):
  In parallel: kick off Section 1's LazySplitSource.getDelegate()
               → Hive/Iceberg enumerates fact table splits in background
               → Splits buffered, ready for immediate use

Section 0 completes → BFs/TDs arrive at coordinator:
  → Coordinator prunes pre-enumerated splits using TDs (fast, in-memory)
  → Section 1 schedule: getNextBatch() returns immediately
```

Further optimization: **pre-create tasks on workers** using split-to-worker
affinity from the previous section. `HttpRemoteTaskWithEventLoop.addSplits()`
supports adding splits to existing tasks. Pattern:

```
During Section 0:
  Pre-create tasks on workers with empty split sets (plan fragment only)
  Workers initialize pipeline, allocate memory
When Section 0 completes:
  Coordinator prunes splits → addSplits() to pre-created tasks
  Workers begin scan immediately (no task startup overhead)
```

This requires extending `SourcePartitionedScheduler` to support pre-creation
(currently it couples task creation with split assignment), or using
`FixedSourcePartitionedScheduler`'s pre-assignment pattern.

**Revised overhead estimate with pre-enumeration:**
```
Without pre-enumeration:  ~10-100ms per section transition (connector latency)
With pre-enumeration:     ~1-5ms per section transition (state check + pruning)
For 3 RPT sections:       ~3-15ms total (negligible vs. scan time)
```

**Resolution**: The original 50-200ms estimate was wrong — coordinator overhead
is near-zero because stages are pre-created. Connector split enumeration is the
real cost, and it's addressable through eager pre-enumeration during the previous
section. This should be noted as an optimization in the main design doc rather
than treated as a blocking issue.

---

## Issue 6: GPU-CPU Exchange Data Path Is Hand-Waved

**Severity**: Missing implementation detail

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

The doc says RPT reuses RFC-0022's Dynamic Filtering infrastructure. But they have
fundamentally different lifecycles:

- **RFC-0022 dynamic filters**: Generated *during* query execution. Hash join
  build side completes → filter extracted → pushed to probe side. The filter
  emerges as a side effect of normal execution.
- **RPT bloom filters**: Generated *before* query execution in dedicated transfer
  sections. The filters are the primary output of these sections, not a side
  effect.

Specific mismatches:
- RFC-0022's `DynamicFilterFetcher` long-polls for filters that arrive during
  execution. RPT's BFs are complete before the main query starts — no long-polling
  needed.
- RFC-0022's transitive propagation works through join equality chains in the
  running query. RPT's propagation follows a transfer schedule computed by
  LargestRoot, independent of the main query's join order.
- RFC-0022's section gating waits for build-side completion. RPT's section gating
  waits for BF-build completion — similar but orchestrated differently.

The filter serialization format and coordinator-side application logic are likely
reusable. The orchestration, timing, and distribution mechanisms need new work.

**Impact**: "Extend Dynamic Filtering with bloom filter support" understates the
implementation effort. The plumbing is reusable; the orchestration is new.

**Resolution needed**: Separate clearly which RFC-0022 components are reused
(serialization, coordinator application, TupleDomain merging) vs. which are new
(transfer section orchestration, BF-build stage type, transfer schedule generation).

---

## Issue 8: NVLink Assumption May Not Hold for Commodity GPUs

**Severity**: Economic thesis undermined

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

If a worker fails midway through an RPT section (e.g., Section 1, forward fact
scan), the partial bloom filter built on that worker is lost. The coordinator
must handle this failure, but the options are unclear:

- **Retry the entire section**: Safe but expensive — all workers' work in that
  section is discarded. Other workers' BFs were fine.
- **Retry just the failed worker's splits**: The BF is an aggregation across all
  splits. You need to merge the surviving workers' partial BFs with the retried
  worker's partial BF. This requires BFs to be merge-friendly (they are — BFs
  support bitwise OR merge), but the coordinator needs new logic for partial
  section retry.
- **Skip the failed BF**: Proceed without that table's BF. The main query runs
  with less filtering — safe but suboptimal.

Traditional query execution can reassign individual splits on failure. RPT
introduces section-level coupling — the BF is only useful when all splits have
contributed.

**Impact**: RPT adds a new failure mode that increases blast radius from
per-split to per-section. For long-running RPT passes on large tables, this
could be a reliability concern.

**Resolution needed**: Design the failure handling strategy. BF merge-on-retry
is likely the right approach, but needs explicit design.

---

## Issue 10: HBO Can't Help When You Need It Most

**Severity**: Amortization story weaker than presented

HBO matches queries when input table sizes are within 10% of historical runs.
But fact tables in production often grow daily — new partitions arrive, tables
grow by gigabytes or terabytes. If the table grows 15% between runs, HBO's match
fails and RPT reverts to conservative mode.

This creates an inversion:
- **Stable data** (HBO matches): RPT decisions are well-informed. But stable data
  also means simple table stats are reliable — the optimizer's cardinality
  estimates are more likely correct, reducing RPT's value.
- **Rapidly changing data** (HBO fails to match): RPT reverts to conservative
  overhead. This is exactly when safety-net RPT is most needed (because optimizer
  stats are stale), but also when HBO can't help target it.

**Impact**: The HBO amortization is most effective where it's least needed, and
least effective where it's most needed.

**Resolution needed**: Consider relaxing the HBO matching threshold for RPT
decisions (e.g., 30% tolerance for RPT vs. 10% for cardinality estimates — RPT
decisions are more robust to size changes than cardinality overrides). Or use
relative metrics (join selectivity ratio) rather than absolute sizes for matching.

---

## Issue 11: Salted Joins Replicate the Build Side

**Severity**: Memory pressure interaction

Section 4.3 says "Replicate build side N ways, salt probe side partition key."
If N=8 GPUs and the build side is 10GB after RPT reduction, replication creates
80GB total build-side data distributed across 8 GPUs — each GPU holds its full
10GB copy.

This means salted joins don't reduce per-GPU memory for the build side. They
only reduce per-GPU probe-side data. If the build side is what's causing OOM,
salted joins don't help — they need the build side to already fit on each GPU.

For builds that don't fit: you'd need partitioned joins (hash-partition both
sides by join key, then salt within each partition), which is more complex than
presented.

**Impact**: The doc implies salted joins solve per-GPU memory pressure. They
only solve probe-side pressure. Build-side pressure requires different
techniques (Grace hash join, RPT reduction).

**Resolution needed**: Clarify that salted joins help probe-side parallelism
and skew, not build-side memory pressure. Distinguish between salted joins
(build replication) and partitioned joins (build partitioning) more carefully.

---

## Issue 12: Cost Model Ignores Parquet Metadata Overhead

**Severity**: ~~Optimistic cost model~~ **Resolved — not additional I/O**

The original concern was that RPT's key-column-only reads would produce many
small I/O requests (one per row group per file) with high metadata overhead,
especially on remote storage like S3.

**This concern is invalid: the key column I/O is not additional work.**

The main query phase would read those exact same key column pages as part of
reading the full row groups. RPT just reads them *earlier* in a separate pass.
Total I/O volume is identical:

```
Without RPT:
  Main query: 50 RGs × 128MB = 6.4GB per file (includes key column)

With RPT + AsyncDataCache:
  Forward pass: 50 RGs × 6MB (key column) = 300MB from storage
  Main query:   50 RGs × 122MB (non-key columns) = 6.1GB from storage
                (key column pages cached from forward pass)
  Total: 300MB + 6.1GB = 6.4GB — identical to without RPT
```

RPT front-loads 300MB of I/O that would have happened anyway. The BENEFIT is
that those 300MB build a bloom filter that prunes the main query:

```
With 90% BF selectivity:  300MB + 10% × 6.4GB = 940MB total (6.8x savings)
With 50% BF selectivity:  300MB + 50% × 6.4GB = 3.5GB total (1.8x savings)
With  0% BF selectivity:  300MB + 6.4GB        = 6.7GB total (~5% overhead)
```

Even in the worst case (BF prunes nothing), the overhead is just ~5% — the
cost of reading the key column in a separate pass rather than as part of the
full row group reads. This overhead comes from extra S3 requests (50 separate
6MB GETs for the key column vs. being coalesced into 50 larger full-RG reads),
not from additional data volume.

**Velox infrastructure that makes this work:**

1. **`AsyncDataCache`** (enabled by default): Caches key column pages from the
   forward pass. Main query hits cache for those pages — no re-read.
   Keyed by `{fileNum, offset}`. Source: `velox/common/caching/AsyncDataCache.h`

2. **`FileHandleCache`**: LRU cache of open file handles. Forward pass opens
   files, backward pass and main query reuse cached handles.
   Source: `velox/connectors/hive/FileHandle.h`

3. **I/O Coalescing** (`CachedBufferedInput`): Within each row group, key
   column pages are contiguous and coalesced into one read.
   `maxCoalesceDistance` = 512KB, `loadQuantum` = 8MB.
   Do NOT increase `maxCoalesceDistance` to coalesce across RGs — that would
   read through ~122MB of non-key columns between RGs, defeating column pruning.
   Source: `velox/dwio/common/CachedBufferedInput.cpp`

4. **Row group prefetching**: `prefetchRowGroups` = 1 by default. Hides I/O
   latency behind compute for sequential access within a file.

**Resolution**: Issue is resolved. RPT's forward pass reads data that would be
read anyway — it's not extra I/O, it's an investment. The only overhead is ~5%
more S3 requests in the worst case (no pruning). With any meaningful BF
selectivity, RPT reduces total I/O. The Parquet metadata concern (footers,
page indexes) is handled by `AsyncDataCache` and `FileHandleCache` across
RPT sections.
