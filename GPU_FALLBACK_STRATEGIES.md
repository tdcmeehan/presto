# GPU Execution Strategies for Presto

## Status: Draft v0.3
## Authors: Auto-generated design exploration
## Date: 2026-02-07

---

## 1. Problem Statement

Presto's native execution engine (Prestissimo/Velox) has experimental support for GPU
acceleration via NVIDIA cuDF. Today, when cuDF is enabled, GPU-accelerated operators
replace their CPU counterparts at build time. If a query hits an unsupported operation
or runs out of GPU memory (VRAM), the query **fails entirely** — there is no mechanism
to gracefully fall back to CPU execution.

GPU VRAM is also dramatically smaller than CPU RAM (40-80GB vs 512GB-2TB), so operators
like hash joins and aggregations that build large intermediate state frequently risk OOM.

This document explores a holistic GPU execution strategy for Presto that addresses:
1. **Fallback**: How to gracefully fall back from GPU to CPU when operations are unsupported
2. **Memory bounding**: How to keep GPU working sets within VRAM limits
3. **Data ingestion**: How to get data to the GPU fast enough
4. **Optimization**: How to maximize GPU throughput for supported operations

---

## 2. Prior Art

### 2.1 How Polars Handles GPU Fallback

Polars uses an **inspector-executor design** with RAPIDS cuDF:

- **Static IR inspection** (implemented): After the query IR is optimized, cuDF inspects
  every node before execution. If any operation is unsupported, the full query falls back
  to CPU. Cost: a few milliseconds. No cost-based decision — purely binary
  (can GPU run this? yes/no).
- **OOM fallback** (investigated, closed as won't fix): Explored full-query rewind and
  partial-query rewind. Closed because UVM handles most memory overflow cases.
- **Subgraph hybrid execution** (planned): Mark individual subgraphs as GPU vs CPU.
  Not yet implemented.

**Key takeaway**: Polars' approach is viable for single-machine, in-memory execution but
lacks the distributed infrastructure Presto has for more sophisticated strategies.

### 2.2 Robust Predicate Transfer (RPT)

RPT ([SIGMOD'25](https://arxiv.org/abs/2502.15181)) approximates the Yannakakis
algorithm using bloom filters. Before any joins execute, forward and backward bloom
filter passes over base tables "fully reduce" all tables — filtering out rows that
won't survive the joins.

```
Forward pass (leaves → root):
  Scan dim_a → BF_a, Scan dim_b → BF_b, Scan dim_c → BF_c
  Scan fact + apply BF_a, BF_b, BF_c → BF_fact

Backward pass (root → leaves):
  BF_fact → re-filter dim_a, dim_b, dim_c

After: every base table is "fully reduced" — only surviving rows remain.
Then: join the reduced tables (hash tables are dramatically smaller).
```

**GPU relevance**: The transfer phase uses only bloom filters (~150MB each, always fit in
VRAM) and parallel scans (GPU-native). After reduction, hash tables shrink 10-100x,
making them fit in VRAM without OOM fallback.

### 2.3 GPU Data Ingestion — The Parquet Reality

**The ideal** (custom columnar format): GPUDirect Storage (GDS) DMA from NVMe directly
to VRAM at ~22 GB/s, bypassing CPU entirely. Once in VRAM, HBM bandwidth is 2-3.35 TB/s
(H100/H200/B100).

**The Parquet problem**: Real-world data lives in Parquet, whose hierarchical metadata
is interleaved with data, requires sequential interpretation to decode, and prevents
GPU threads from reading consecutive addresses in parallel. cuDF's Parquet reader
achieves ~10x less than theoretical I/O throughput because GPU kernels stall on
decompress/decode of metadata-heavy page structures.

**The pragmatic architecture** (CPU prefetch/decode): CPU threads handle what they're
good at — sequential metadata parsing, decompression (Snappy/LZ4/ZSTD), and page
decoding (DICT, RLE, DELTA). Decoded Arrow columnar batches are transferred to GPU
via PCIe/NVLink-C2C. The GPU handles parallel compute: filtering, joining, aggregating.

```
CPU (sequential, metadata-friendly):     GPU (parallel, compute-friendly):
  Read Parquet metadata
  Row group pruning (TupleDomain range)
  Read + decompress + decode pages
  Assemble Arrow batches
       ──── PCIe/NVLink-C2C ────→         BF probe (row-level)
                                           Filter, Project
                                           Hash Join, Aggregation
  Double-buffer: prepare N+1              Process N
```

The `CudfHiveConnector` in Velox implements this pattern — it accepts splits,
uses the cuDF Parquet reader to produce `cudf::table` objects wrapped in `CudfVector`,
and feeds them into the GPU-side operator pipeline.

---

## 3. What Presto Already Has

### 3.1 Current cuDF Integration

Presto's GPU support (`PRESTO_ENABLE_CUDF`) works at build time:
- cuDF Hive connectors replace standard connectors
  (`presto-native-execution/presto_cpp/main/connectors/Registration.cpp`)
- `velox::cudf_velox::registerCudf()` registers GPU operators
  (`presto-native-execution/presto_cpp/main/PrestoServer.cpp`)
- GPU operators available: TableScan, HashJoin, HashAggregation, FilterProject, OrderBy
  (via Velox `DriverAdapter` rewriting)
- GPU disabled by default; enabled via `CudfConfig`

### 3.2 NativePlanChecker (Sidecar-Based Plan Validation)

The `NativePlanChecker` (`presto-native-sidecar-plugin`) validates whether plan
fragments can execute on native workers:
- Serializes each `SimplePlanFragment` to JSON and POSTs to a native sidecar process
  at `/v1/velox/plan`
- The sidecar runs the actual `PrestoToVeloxQueryPlan` conversion — catching
  unsupported functions, types, expressions, and operator combinations
- Runs per-fragment (via `PlanCheckerProvider.getFragmentPlanCheckers()`)
- Skips coordinator-only fragments and internal system connector scans
- Loaded via the `PlanCheckerProviderManager` plugin SPI from config directory
- Today: failure throws `PrestoException`, killing the query

### 3.3 Sections for Staged Execution

Presto "sections" (`StreamingPlanSection`) group stages for ordered execution:
- **Child sections must complete before parent sections start** — the scheduler's
  `isReadyForExecution()` enforces this
- **Stages within a section run concurrently** — via `AllAtOnceExecutionSchedule`
- **Section boundaries are defined by `REMOTE_MATERIALIZED` exchanges**
- The scheduler already orchestrates section ordering without modification needed

### 3.4 Affinity Scheduling (Soft Affinity)

Presto has built-in affinity scheduling via `NodeSelectionStrategy` on splits:
- **`SOFT_AFFINITY`**: Split prefers specific nodes but runs elsewhere if they're
  dead or overloaded. `SimpleNodeSelector` tries preferred nodes first, then appends
  random fallback nodes. If a preferred node is `DEAD`, it's skipped and
  `NodeSelectionStats.preferredNonAliveNodeSkippedCount` is incremented.
- **`HARD_AFFINITY`**: Split must run on specific nodes — fails if unavailable.
- **`NO_PREFERENCE`**: Round-robin / least-busy assignment.

Key for RPT: connectors return `SOFT_AFFINITY` with the same preferred nodes for
each split across sections. Same workers handle the same splits (cache locality)
with automatic fallback if a worker fails between sections. No new scheduler
infrastructure needed.

### 3.5 Dynamic Filtering RFC (In Progress)

[RFC-0022](https://github.com/prestodb/rfcs/pull/54) proposes distributed dynamic
partition pruning:
- Filters extracted from hash join build sides on workers
- Collected at coordinator, merged via `CoordinatorDynamicFilter`
- Applied at four levels: partition, file, row group, row
- Progressive resolution — filters arrive incrementally
- **Transitive filter propagation** through equality chains across joins
- Extensible serialization format with `filterType` envelope (supports future bloom
  filter extension)
- Uses sections to gate build completion before probe split scheduling

### 3.6 Intermediate Aggregations (Off by Default)

`AddIntermediateAggregations` rule (`enable_intermediate_aggregations` session property,
default `false`) inserts extra INTERMEDIATE aggregation stages:

```
Before:                          After:
  FINAL Agg                        FINAL Agg
    RemoteExchange (GATHER)          LocalExchange (GATHER)
      PARTIAL Agg                      INTERMEDIATE Agg
                                         LocalExchange (ARBITRARY)
                                           RemoteExchange (GATHER)
                                             INTERMEDIATE Agg
                                               LocalExchange (GATHER)
                                                 PARTIAL Agg
```

This adds cascading partial aggregation — reducing data volume at each stage. Currently
only applies to un-grouped aggregations without ORDER BY. For GPU, this is critical:
each aggregation stage operates on a smaller working set.

### 3.7 Adaptive Exchange Framework (Prior Design)

The [Adaptive Exchange Framework](ADAPTIVE_EXCHANGE_FRAMEWORK.md) introduces:
- Exchanges buffer initial rows (~100K) and collect runtime statistics
- "Hold" signals cascade upstream to create global synchronization points
- The coordinator can reoptimize the query plan based on observed data
- Buffers enable replay — work can be redone with a different strategy

---

## 4. Integrated GPU Execution Strategy

The following strategies compose into a layered system. Each layer independently
improves GPU execution; together they form a comprehensive solution.

### 4.1 Static Plan Inspection via NativePlanChecker (V0 — Polars-Style)

**Complexity**: Low | **Impact**: Prevents unsupported-op failures | **Builds on**: existing infrastructure

Presto already has a `NativePlanChecker` in the `presto-native-sidecar-plugin` that
validates whether a plan fragment can run on Prestissimo/Velox. It works by sending
each fragment to a **native sidecar process** via `POST /v1/velox/plan`, which
attempts the real `PrestoToVeloxQueryPlan` conversion. If conversion fails (unsupported
function, type, expression), the sidecar returns a detailed error.

Today, a failed plan check throws `PrestoException` and kills the query. For GPU
fallback, we repurpose this mechanism:

```
Coordinator                          Native Sidecar (with cuDF)
    │                                        │
    │  POST /v1/velox/plan?device=gpu        │
    │  {serialized PlanFragment}  ──────────→│
    │                                        │  Attempt plan conversion
    │                                        │  WITH cuDF DriverAdapter rewriting
    │                                        │
    │  200 OK                     ←──────────│  Fragment is GPU-capable
    │  OR                                    │
    │  Error + details            ←──────────│  Fragment not GPU-capable
    │                                        │  (e.g., unsupported function X)
    │                                        │
    │  Based on result:                      │
    │  GPU-capable → schedule on GPU workers │
    │  Not GPU    → schedule on CPU workers  │
    │                                        │
    │  Query never fails.                    │
```

**Why this approach is superior to a static registry of GPU-supported operations**:

1. **Uses the real conversion code** — if cuDF's `DriverAdapter` can rewrite it, the
   sidecar says yes. No registry to maintain, no drift between what's actually
   supported and what a list claims.

2. **Catches complex interactions** — a function might be supported for `BIGINT` but
   not `DECIMAL(38,18)`. Type-dependent support, expression nesting, operator
   combinations — the real converter handles all of this.

3. **Per-fragment granularity** — different fragments in the same query can get
   different GPU/CPU decisions. A scan+filter+aggregate fragment runs on GPU while
   a window function fragment runs on CPU.

4. **Auto-evolves** — as cuDF support expands (more functions, types, operators), the
   fallback automatically knows about it. No code changes on the coordinator when new
   GPU operators are added to Velox.

**Existing infrastructure reused**:
- `NativePlanChecker` (`presto-native-sidecar-plugin/...nativechecker/NativePlanChecker.java`):
  Serializes `SimplePlanFragment`, sends to sidecar, processes response
- `NativePlanCheckerProvider`: Fragment-level plan checker loaded via plugin SPI
- `PlanCheckerProviderManager`: Loads plan checkers from config directory at startup
- `PlanChecker.validatePlanFragment()`: Invocation point — runs after plan optimization,
  before scheduling
- Sidecar process: Already deployed alongside Prestissimo workers, already has the
  `/v1/velox/plan` endpoint

**Implementation delta**:

1. **Sidecar endpoint change**: Add `device` query parameter to `/v1/velox/plan`.
   When `device=gpu`, attempt plan conversion with cuDF `DriverAdapter` enabled.
   When `device=cpu` (or absent), use standard Velox conversion (today's behavior).

2. **Change failure semantics**: Instead of throwing `PrestoException` on GPU plan
   check failure, annotate the fragment with a device capability:
   ```java
   public enum DeviceCapability { GPU_CAPABLE, CPU_ONLY }
   ```
   The `NativePlanChecker` returns this annotation rather than throwing.

3. **Fragment routing in scheduler**: `SqlQueryScheduler` uses the annotation when
   creating `SectionExecution` — GPU-capable fragments are scheduled on GPU workers,
   CPU-only fragments on CPU workers (or GPU workers running CPU fallback path).

4. **Dual-mode workers** (optional, Phase 2): Workers that have both GPU and CPU
   execution paths available, selected per-fragment based on the plan check result.
   Initially, heterogeneous clusters (some GPU-only, some CPU-only workers) are simpler.

**Cost**: One HTTP round-trip per fragment to the sidecar during planning. Plan
fragments are typically small (kilobytes of JSON). The sidecar plan conversion is
fast (milliseconds). For a query with 5-10 fragments, this adds ~10-50ms to planning
time — negligible for analytical queries.

### 4.2 RPT via Sections (Bloom Filter Pre-Reduction)

**Complexity**: Medium-High | **Impact**: 10-100x smaller hash tables | **Applies to**: CPU and GPU

RPT is not a GPU-specific technique — it is a **general query execution optimization**
that benefits both CPU and GPU execution. The GPU VRAM bounding effect is a bonus on
top of its primary value:

- **Join operator efficiency (superlinear improvement)**: RPT pre-reduction doesn't
  just reduce the *number* of rows the join processes — it makes the join faster *per
  row*. Hash table build and probe cost is superlinear due to collision chains: a hash
  table at 75% load has ~4x more cache misses per probe than one at 25% load. A 10x
  smaller table isn't just 10x less work — it's 10x fewer rows each processed
  significantly faster. Both build and probe sides benefit: fewer build rows → smaller
  table → fewer collisions; fewer probe rows → fewer lookups → less output to
  materialize downstream.
- **CPU cache locality**: A hash table that shrinks from 40GB to 400MB moves from
  thrashing main memory (~50 GB/s random access, much lower effective bandwidth due to
  pointer chasing) to fitting in L3 cache (256-384MB on modern servers, hundreds of
  GB/s effective bandwidth). The throughput difference for random-access hash probes is
  10-50x faster *per probe*, compounding with the row count reduction.
- **Eliminates hash join spilling**: Presto's hash join spill-to-disk becomes
  unnecessary for most queries when RPT reduces build sides by 10-100x.
- **Pipeline-wide data reduction**: Every operator downstream — filter, project,
  join probe, aggregation, shuffle — processes fewer rows. The savings compound
  multiplicatively across a multi-join query.
- **Join order robustness**: The paper's primary contribution. Bad cardinality
  estimates → bad join order → 100x+ execution time blowup. With RPT, the worst
  join order is only 1.6x slower than the best. This matters for any execution engine.
- **GPU join efficiency**: On GPU, the effect is even more pronounced. GPU hash joins
  need the hash table in HBM (High Bandwidth Memory). A compact table stays hot in the
  GPU's L2 cache (40-50MB on H100), enabling thousands of threads to probe
  simultaneously without contention. When the table exceeds L2, each probe becomes a
  random HBM access — still fast (3.35 TB/s) but far slower than L2-resident probes.
  RPT reduction can mean the difference between L2-resident and HBM-thrashing for many
  analytical joins. Additionally, a table that fits entirely in VRAM avoids UVM page
  faulting or Grace partitioning overhead entirely.

The RPT paper's DuckDB evaluation (CPU-only) showed **1.5x geometric mean speedup**
across TPC-H, JOB, and TPC-DS — with no GPU involved. Combined with Velox's data
cache (which makes the rescan cost negligible, see below), RPT is worth implementing
for Presto regardless of GPU plans.

Use Presto's section model to implement RPT transfer phases. The transfer schedule
maps naturally onto sections:

```
Section 0 (forward — leaves):          ← child section, runs first
  ┌─────────────────────────────────────────────────┐
  │ Stage A: Scan dim_a → build BF_a                │
  │ Stage B: Scan dim_b → build BF_b                │ concurrent
  │ Stage C: Scan dim_c WHERE region='US' → BF_c    │
  └─────────────────────────────────────────────────┘

Section 1 (forward — root):            ← depends on Section 0
  ┌─────────────────────────────────────────────────┐
  │ Stage F: Scan fact + apply BF_a, BF_b, BF_c    │
  │          → build BF_fact                         │
  └─────────────────────────────────────────────────┘

Section 2 (backward — leaves):         ← depends on Section 1
  ┌─────────────────────────────────────────────────┐
  │ Stage A': Scan dim_a + BF_fact → BF_a'          │
  │ Stage B': Scan dim_b + BF_fact → BF_b'          │ concurrent
  │ Stage C': Scan dim_c + BF_fact → BF_c'          │
  └─────────────────────────────────────────────────┘

Section 3 (main query):                ← depends on Section 2
  ┌─────────────────────────────────────────────────┐
  │ Normal join execution with all BFs applied       │
  │ Hash tables 10-100x smaller → fit in VRAM        │
  └─────────────────────────────────────────────────┘
```

**Why sections work**: The existing scheduler already enforces child-before-parent
ordering. Transfer sections are lightweight scan-only stages — no hash tables, no
shuffle (bloom filters are aggregated at the coordinator). For star schemas (depth 1),
only 3 transfer sections are needed.

**Section transition overhead is near-zero.** All `StageExecutionAndScheduler` objects
are created upfront in the `SqlQueryScheduler` constructor for ALL sections. Split
sources are wrapped in `LazySplitSource` — a lazy proxy that defers connector calls
until first `getNextBatch()`. Section readiness is a pure state check. The only
latency at transition is connector split enumeration (~10-100ms), which can be hidden
by **eagerly triggering** the next section's `LazySplitSource` during the current
section's execution. With eager pre-enumeration, transition overhead is ~1-5ms.

**RPT transfer sections run on CPU, not GPU**. The BF-build pass is pure CPU work:
read Parquet metadata, decompress, decode the **join key column only** (column pruning),
hash keys into a bloom filter. This is critical for two reasons:

1. **Column pruning drastically reduces I/O**: The BF-build pass only needs the join
   key column, not the full row. For a typical fact table row (~150 bytes across 16
   columns), the join key is ~8 bytes — roughly 5% of the data. Parquet's columnar
   layout makes this addressable: only the key column's pages are read, all other
   column chunks are skipped.

2. **CPU has abundant parallel decode throughput**: On a 128-core server (e.g., 2x AMD
   EPYC 7763), single-core Parquet decode runs at ~1-2 GB/s. For key-only decode + BF
   hash, effective throughput is ~2-4 GB/s per core. With 128 cores: 256-512 GB/s
   aggregate CPU decode throughput — far exceeding typical storage read rates
   (~170 GB/s from NVMe). The BF-build pass is **storage-bound, not CPU-bound**.

**Cost model** (reference hardware: 2x EPYC 7763, 170 GB/s NVMe, 8x A100 80GB):

```
RPT BF-build pass on 400GB fact table (SF-1000 lineitem):
  Key column data: 400GB × 5% = ~20GB
  Storage read: 20GB / 170 GB/s = 0.12s
  CPU decode + hash: 128 cores × 3 GB/s = 384 GB/s (not the bottleneck)
  Time per table: ~0.12s

Star schema (1 fact + 3 dims), forward + backward:
  Forward dims (small, concurrent):   ~0.1s
  Forward fact (key-only scan):       ~0.12s
  Backward dims (BF-filtered, small): ~0.05s
  Total RPT latency:                  ~0.3s

Main query scan (full columns, with DF row-group pruning):
  Effective data after pruning: ~100GB
  Storage read: 100GB / 170 GB/s = 0.6s
  CPU full decode: storage-bound = 0.6s
  GPU join + agg: ~0.5s (8x A100 at full throughput)
  Total main query: ~1.1s

Total with RPT:    ~1.4s (0.3s RPT + 1.1s main)
Total without RPT: ~1.1s main + potential GPU OOM on hash build
```

**Important: RPT's forward pass is NOT additional I/O.** The main query would
read those same key column pages as part of reading the full row groups. RPT
reads them earlier in a separate pass, and `AsyncDataCache` ensures they are
not re-read during the main query. Total I/O volume is identical:

```
Without RPT:  main query reads 400GB (includes key column)
With RPT:     forward reads 20GB (key column) + main reads 380GB (non-key, cached key)
              = 400GB total — identical

With 90% BF selectivity:  20GB + 10% × 400GB = 60GB  (6.7x I/O savings)
With 50% BF selectivity:  20GB + 50% × 400GB = 220GB (1.8x I/O savings)
Worst case (0% pruning):  20GB + 400GB = 420GB        (~5% overhead from extra requests)
```

The 0.3s RPT latency is real wall-clock time (the query takes 0.3s longer to
start producing results), but it is not wasted I/O — it is an investment that
typically reduces the main query's I/O substantially. For longer queries (>10s),
the latency overhead drops below 3%, while the I/O savings grow with table size.

**Multi-tier storage cost model**: The 0.3s overhead above assumes local NVMe. Most
Presto deployments read from remote storage (S3, GCS, HDFS). RPT overhead is higher
on remote storage — but RPT's **value is also higher**, because the I/O savings are
amplified by the slower bandwidth.

RPT's key-column-only reads generate one S3 byte-range GET per row group per file
(~2-4MB each for int64 keys). Velox's `CachedBufferedInput` coalesces reads within
512KB (`maxCoalesceDistance`), but row groups are ~128MB apart — so each key column
chunk is a separate request. For 5000 row groups across 10 workers = 500 requests
per worker at 32 concurrent:

```
RPT forward pass overhead (400GB fact, 10 workers, 500 RG reads/worker):

                     Local NVMe   HDFS          S3 (CPU inst)   S3 (GPU inst)
Per-worker BW:       17 GB/s      3 GB/s        1.2 GB/s        12-50 GB/s
Request latency:     0.05 ms      3 ms          30 ms           30 ms
Forward pass:        ~0.12s       ~0.8s         ~2.2s           ~0.5s
Total RPT overhead:  ~0.3s        ~1.5s         ~4s             ~1s
```

But RPT's savings scale with storage slowness. With 90% BF selectivity, the main
query reads 60GB instead of 400GB. The savings dwarf the overhead on slow storage:

```
End-to-end query time (400GB fact, 90% BF selectivity):

                     Local NVMe   HDFS          S3 (CPU inst)   S3 (GPU inst)
Without RPT:         2.4s         13s           33s             8s
RPT overhead:        0.3s         1.5s          4s              1s
Main query w/ RPT:   0.4s         2s            5s              1.2s
Total with RPT:      0.7s         3.5s          9s              2.2s
Net speedup:         3.4x         3.7x          3.7x            3.6x
```

RPT provides roughly the **same speedup ratio** regardless of storage tier — the
overhead and savings both scale linearly with storage latency. The "skip RPT for
short queries" threshold is storage-aware: ~2s on local NVMe, ~10s on HDFS, ~30s
on S3. But most analytical queries on 400GB tables already exceed these thresholds.

Note: GPU instances (p4d.24xlarge, p5.48xlarge) have 400-3200 Gbps network bandwidth
to S3 — far higher than typical CPU instances (10-25 Gbps). This makes S3 reads on
GPU clusters comparable to HDFS on CPU clusters, partially offsetting the S3 latency
overhead. The S3 request latency (30ms) becomes the bottleneck, not bandwidth.

**Why RPT isn't broadly adopted — and how Presto can avoid the pitfall**: The RPT
paper's DuckDB implementation avoids the rescan penalty entirely by **buffering scanned
data in memory**. The `CreateBF` operator acts as both sink and source: during the Sink
phase it buffers all data chunks in thread-local memory; during subsequent pipeline
phases, `GetData` serves the buffered chunks directly without re-reading from disk. This
is natural for DuckDB (single-node, in-memory) but doesn't directly translate to a
distributed system where data lives on remote storage.

This is a key reason RPT hasn't been adopted more broadly — systems without in-memory
buffering or effective caching pay the full storage cost 2-3x (forward scan, backward
scan, main query scan). Presto can avoid this penalty by leveraging **Velox's existing
data cache infrastructure** with split-to-worker affinity:

**Velox's I/O and caching infrastructure (already deployed)**:
- **I/O Coalescing (`CachedBufferedInput`)**: Velox merges nearby reads within
  `maxCoalesceDistance` (512KB default) into single I/O requests. Within each Parquet
  row group, key column pages are contiguous and get coalesced. Across row groups,
  pages are ~128MB apart — correctly kept as separate reads (coalescing across RGs
  would read through non-key columns, defeating column pruning).
  `loadQuantum` = 8MB, `maxCoalesceBytes` = 128MB.
- **`FileHandleCache`** (LRU, keyed by filename): Caches open file handles. Forward
  pass opens files; backward pass and main query reuse cached handles — no re-open
  overhead (avoids S3 HEAD / `open()` syscall). Metrics tracked for monitoring.
- **`AsyncDataCache`** (RAM, **enabled by default**): Caches Parquet pages/column
  chunks keyed by `{fileNum, offset}`. Forward pass reads land in cache. Backward
  pass and main query hit cache at memory bandwidth. Parquet footers are also cached.
- **`SsdCache`** (local NVMe, 16 shards for parallel I/O): Overflow tier for data
  that doesn't fit in RAM cache. Still ~10-20x faster than remote storage (S3/HDFS).
- **Row group prefetching**: `prefetchRowGroups` = 1 (default). While processing RG
  N, Velox pre-fetches RG N+1 via `scheduleRowGroups()`, hiding I/O latency.
- Configuration: `async-data-cache-enabled=true` (default), `async-cache-ssd-gb`,
  `async-cache-ssd-path`, plus TTL and eviction policies.

**Split-to-worker affinity across RPT sections**: Presto already has affinity
scheduling via `NodeSelectionStrategy.SOFT_AFFINITY` in `SimpleNodeSelector`. When
a connector returns splits with `SOFT_AFFINITY` and preferred nodes, the scheduler
tries those nodes first and automatically falls back to random nodes if preferred
nodes are dead or overloaded. For RPT, the connector returns the same preferred
nodes for each split across all sections. No new scheduler infrastructure needed —
the existing mechanism handles both affinity and fault tolerance:

```
Worker W1 processes splits S1..S50 across all RPT sections:
  Section 0 (forward):  read key column from S1..S50 → AsyncDataCache warm
  Section 1 (forward):  scan fact S1..S50 with BF_dim → record surviving RGs
  Section 2 (backward): read key column from S1..S50 → cache hit (memory speed)
  Section 3 (main):     read full columns from S1..S50 → key columns cached

If W1 fails between sections:
  SOFT_AFFINITY falls back to W2 → cold cache miss, but query continues.
  Stats tracked via NodeSelectionStats.preferredNonAliveNodeSkippedCount.
```

**Amortizing RPT overhead via idle-time prefetch**: RPT's fundamental cost is the
extra scan passes (Sections 0-2) before the main query. During the backward pass
(Section 2), fact-table workers are idle — dim workers are scanning, but fact workers
have nothing to do. This idle time is pure overhead on the wall clock. Prefetch
converts that idle time into useful I/O: fact workers prefetch surviving row groups'
non-key columns into `AsyncDataCache`, so Section 3 finds warm pages instead of cold
reads. The RPT overhead doesn't disappear, but it becomes partially self-amortizing —
the time that RPT "costs" is spent doing work that the main query would otherwise
have to do itself.

```
Section 1 (forward — fact):
  Workers scan key columns, apply BF_dim → BF_fact
  Workers record: "RGs {12, 37, 42, 89, ...} survived" (10% of total)

Section 2 (backward — dims):
  Dim workers: scanning dim tables (active)
  Fact workers: IDLE — trigger background prefetch:
    For each surviving RG:
      CachedBufferedInput::prefetch(Region{rg.offset, rg.length})
      → schedules non-key column reads on background executor
      → AsyncDataCache fills at storage bandwidth
      → shouldPreload() prevents over-filling cache (50% cap)

Section 3 (main query):
  Fact workers: non-key columns already warm in AsyncDataCache
    → cache hits at ~200 GB/s memory bandwidth
    → cold storage reads reduced or eliminated
```

**Cost model — RPT overhead amortization** (same reference hardware):

```
Without caching (every scan hits storage):
  RPT overhead:    0.32s (forward + backward, cold)
  Main query scan: 0.60s (full columns, cold)
  Total I/O:       0.92s

With SOFT_AFFINITY + AsyncDataCache (no prefetch):
  RPT overhead:    0.23s (backward is cache hit, ~0.01s)
  Main query scan: 0.50s (key columns cached, non-key columns cold)
  Total I/O:       0.73s
  RPT net cost:    0.23s overhead, saves nothing on main query non-key reads

With SOFT_AFFINITY + AsyncDataCache + idle-time prefetch:
  RPT overhead:    0.23s (same wall time, but fact workers prefetching)
  Main query scan: 0.05s (ALL columns warm in cache — memory speed)
  Total I/O:       0.28s
  RPT net cost:    0.23s overhead, but main query drops by 0.45s
                   → RPT overhead is more than paid back
```

The key insight: the 0.23s of RPT overhead is still on the wall clock, but it's no
longer idle time. The backward pass duration that used to be pure cost now does double
duty — dim workers build backward BFs while fact workers warm the cache. RPT's cost
effectively pays for itself by preloading Section 3's data.

**Limitations — prefetch window vs data volume**: For the prefetch to fully complete,
the surviving data must be readable within the backward scan duration. With 90%
selectivity: surviving data = 10% × 400GB = 40GB.

- **NVMe** (170 GB/s): 40GB reads in 0.24s — fits easily within the backward pass
  window. RPT overhead is fully amortized.
- **S3** (10 GB/s aggregate): 40GB takes ~4s, but the backward dim scan only takes
  ~1.5s. Prefetch doesn't finish before Section 3 starts. Partial amortization —
  some pages are warm, others are still cold or in-flight. The prefetch continues as
  background I/O into Section 3.
- **HDFS** (20-40 GB/s): middle ground, likely partial amortization depending on
  cluster bandwidth.

On S3, the partial amortization is still valuable because S3 request latency (30ms
per GET) is the costliest part of RPT overhead on remote storage — even getting half
the GETs in-flight during idle time meaningfully reduces the main query's cold-read
penalty.

Key column data is small (~20GB for a 400GB fact table). This fits comfortably in
the `AsyncDataCache` on any modern server with 128GB+ RAM allocated to the cache.
The backward pass becomes a memory-speed operation. More importantly, the main query
scan also benefits: key columns are already warm, so only non-key columns (the bulk
of the data, but read only once) hit storage.

**When to skip RPT**: The planner should estimate RPT cost vs. benefit:
- If `join_build_estimated_size < VRAM_budget`: skip RPT (hash table fits anyway)
- If query is scan-dominant with trivial joins: skip RPT (overhead > benefit)
- If join selectivity is low (most keys match): skip RPT (BF won't filter much)
- If query estimated time < RPT overhead threshold: skip RPT. Static thresholds
  are storage-aware: ~2s for local NVMe, ~10s for HDFS, ~30s for S3. But HBO
  provides a better mechanism: after the first run, HBO records actual RPT overhead
  vs actual savings. Subsequent runs use measured cost-benefit rather than static
  thresholds. This adapts implicitly to storage tier, table size, and cluster
  configuration without explicit storage detection.

**RPT vs. perfect optimization**: With perfect statistics and an optimal join order, RPT's
extra scans are pure overhead — the optimizer already minimizes intermediate result sizes.
However, even optimal join ordering doesn't provide *full reduction*: intermediate results
after early joins still contain rows that won't survive later joins. RPT eliminates those
rows before any join executes, approximating Yannakakis's guarantee that no intermediate
result exceeds the final output size. In practice, the question is moot — cardinality
estimation errors compound multiplicatively across joins (3x error per join → 81x for 4
joins), so "perfect stats" is rarely achieved.

**HBO-informed RPT (History-Based Optimizer integration)**: Presto's HBO collects actual
runtime statistics per plan node — row counts, data sizes, and critically,
`JoinNodeStatistics` (actual build/probe key counts and NULL key counts per join). These
are stored keyed by a canonical plan hash (SHA-256 of normalized plan JSON) and matched
to future queries within a configurable input-size tolerance (default 10%). This enables
**precise, per-join RPT decisions**:

```
First run (no HBO data):
  Apply RPT conservatively — safety net for unknown selectivities.
  HBO collects: actual join build sizes, join selectivities, row counts.

Subsequent runs (HBO available):
  For each join, HBO provides actual stats from previous execution:
    actual_build_size = 2GB   → skip BF for this join (fits in L3/VRAM)
    actual_build_size = 50GB  → apply BF (would blow L3 cache or VRAM)
    actual_selectivity = 95%  → BF very valuable, definitely apply
    actual_selectivity = 5%   → BF barely helps, skip to save scan cost

  Result: selective RPT — BF passes only for joins where they matter.
  Overhead drops from "BF for every join" to "BF for the 1-2 joins that need it."
```

HBO transforms RPT from an always-on safety net into a **surgically targeted
optimization**:

- **Skip/apply decision uses actuals, not estimates**: `join_build_ACTUAL_size`
  (from HBO) replaces `join_build_estimated_size` — a reliable threshold check
  instead of a guess.
- **Per-join selectivity gating**: HBO's `JoinNodeStatistics` reveals which joins
  have high selectivity (BF valuable) vs. low selectivity (BF wasteful). Only the
  high-selectivity joins get BF transfer passes.
- **First-run amortization**: The first execution of a new query pattern pays the
  full RPT overhead (conservative, all joins). Every subsequent execution benefits
  from HBO data and applies RPT selectively. For recurring analytical workloads
  (dashboards, reports), the amortization is near-immediate.
- **Data drift detection**: If HBO's input-size matching (10% tolerance) fails —
  meaning the underlying data has changed significantly — the planner reverts to
  conservative RPT. This automatically re-triggers the safety net when conditions
  change.
- **Implicit storage-tier adaptation**: HBO measures actual wall-clock RPT overhead
  and actual query savings — these implicitly capture storage characteristics without
  the planner needing to know whether it's reading from NVMe, HDFS, or S3. A query
  on a small S3-backed table where RPT overhead (dominated by S3 request latency) was
  a net loss gets recorded as such; the next run skips RPT. The same query shape on
  NVMe where RPT was a net win keeps RPT. This replaces the static storage-aware skip
  threshold with an empirical one — no configuration needed, adapts to actual I/O
  behavior. Importantly, the request-count overhead that drives S3 RPT cost is stable
  across data growth (row group count changes slowly), so HBO's 10% size tolerance
  rarely invalidates the RPT skip/apply decision even when it fails for cardinality
  estimates.

Key HBO infrastructure reused:
- `HistoryBasedPlanStatisticsCalculator`: provides actual stats during planning
- `JoinNodeStatistics`: actual build/probe key counts per join
- `CanonicalPlanGenerator`: plan fingerprinting for cross-query matching
- `HistoryBasedPlanStatisticsTracker.updateStatistics()`: collects runtime stats
  post-execution (add RPT-specific metrics: BF reduction ratio, BF build time)
- `RedisPlanStatisticsProvider`: persistent storage for HBO data across restarts

**Integration with Dynamic Filtering RFC**: RFC-0022 already provides:
- Filter collection infrastructure (`DynamicFilterFetcher`, long-polling)
- Filter merging (`CoordinatorDynamicFilter`, `TupleDomain.columnWiseUnion()`)
- Transitive filter propagation through equality chains
- Extensible `filterType` envelope (add `"filterType": "bloomFilter"` for RPT)
- Section-gated split scheduling

The RPT transfer sections extend this infrastructure by:
- Adding a new `BloomFilterBuildStage` type (scan + build BF, no data output)
- Using the existing filter distribution mechanism to propagate BFs between sections
- Generating the transfer schedule via the LargestRoot algorithm in the planner

**Extending RPT to cyclic queries and complex subqueries via materialized CTEs**:
RPT's Yannakakis-based algorithm requires an acyclic join graph. Additionally, RPT can
only push bloom filters into base table scans — not into complex subquery join inputs
(aggregations, nested joins, window functions). Presto's materialized CTE infrastructure
addresses both limitations:

- **Cycle breaking (acyclic requirement)**: RPT's `LargestRoot` algorithm computes a
  spanning tree of the join graph. Any join edge not in the spanning tree is a "back edge"
  that creates a cycle. By materializing the join at each back edge as a CTE, the cyclic
  graph decomposes into acyclic sections. Each section's join graph is acyclic, so RPT
  applies independently within each section.

  ```
  Cyclic: A ⋈ B ⋈ C ⋈ A (triangle)
  → Materialize A ⋈ B as CTE → temp table T_AB
  → Section 1: A ⋈ B (acyclic) — RPT reduces both
  → Section 2: T_AB ⋈ C (acyclic) — RPT reduces both
  ```

- **Complex subquery join inputs**: When a join input is not a base table scan (e.g.,
  `fact ⋈ (SELECT id, SUM(x) FROM orders GROUP BY id)`), materializing the subquery
  as a CTE converts it to a `TableScanNode` on a temporary table. RPT can then push BFs
  into this scan — column pruning on the temp table's key column, BF filtering on the
  scan output. The existing `CteProjectionAndPredicatePushDown` optimizer can further
  push RPT-derived `TupleDomain` ranges back into the CTE producer, potentially reducing
  the aggregation work itself.

The mechanism leverages the existing CTE pipeline:
`LogicalCteOptimizer` → `PhysicalCteOptimizer` (converts to `TableWriteNode` /
`TableScanNode`) → `SequenceNode` (section boundaries) → `CTEMaterializationTracker`
(coordinates completion). New work: (1) cycle detection during RPT planning, (2)
automatic subquery-to-CTE promotion for non-scannable join inputs, (3) cost-based
gating to avoid materializing small subqueries where the overhead exceeds the RPT benefit.

**GPU-specific benefits**: Bloom filter construction and probing are embarrassingly
parallel — among the fastest operations on GPU. Bloom filters (~150MB) always fit in
VRAM. The row-level BF probing happens on GPU after decode, at billions of probes/sec.

**RPT join type support**: RPT's BF filtering is only safe on the **non-preserved side**
of a join. Filtering the preserved side of an outer join would incorrectly eliminate rows
that should produce NULL-padded output. This yields the following support matrix:

| Join Type | Filter Left w/ BF_right | Filter Right w/ BF_left | RPT Value |
|---|---|---|---|
| **INNER** | Yes | Yes | Full — bidirectional reduction |
| **LEFT OUTER** | **No** (left preserved) | Yes | Partial — right (build) side only |
| **RIGHT OUTER** | Yes | **No** (right preserved) | Partial — left (probe) side only |
| **FULL OUTER** | **No** | **No** | None — RPT cannot help |
| **SEMI** (EXISTS) | Yes | Yes | Full — bidirectional |
| **ANTI** (NOT EXISTS) | No (BF can't identify definite non-matches) | Yes | Minimal |
| **CROSS** | N/A (no join keys) | N/A | None |

This is the same constraint Presto's existing dynamic filters follow —
`PredicatePushDown.createDynamicFilters()` only generates dynamic filters for `INNER`
and `RIGHT` joins (line 699), because only those allow filtering the probe (left) side.

**LEFT JOIN is the critical limitation for analytics.** Star schema queries often use
`fact LEFT JOIN dim` to preserve all fact rows. RPT can still filter the dim (build)
side using BF_fact — reducing the hash table — but **cannot filter the fact (probe)
side** using selective dimension BFs. The most valuable direction (using
`dim.region = 'US'` to prune 80% of the fact table) is blocked.

**Mitigations**:

1. **Outer-to-inner conversion** (already implemented): `PredicatePushDown.
   tryNormalizeToOuterToInnerJoin()` converts LEFT/RIGHT/FULL joins to INNER when a
   downstream predicate makes the NULL-preserving behavior impossible (e.g.,
   `WHERE dim.column IS NOT NULL`, `WHERE dim.column > 5`). This is very common in
   practice — most analytical LEFT JOINs have such predicates. After conversion, RPT
   has full bidirectional support.

2. **Build-side-only reduction**: Even for true LEFT JOINs where conversion isn't
   possible, RPT's backward pass (BF_fact → filter dims) still reduces the hash table
   build side. This provides the superlinear join efficiency benefits (fewer collisions,
   cache residency) and eliminates spilling. The fact table isn't reduced, but the join
   operator itself is faster.

3. **RPT-aware join type annotation**: The RPT planner should annotate each join with
   its BF filtering capability. For INNER/SEMI: bidirectional BF passes. For LEFT/RIGHT:
   unidirectional BF pass (non-preserved side only). For FULL/CROSS: skip RPT for that
   join entirely. This avoids wasting BF-build effort on directions that can't be used.

**Interaction with Dynamic Filtering (RFC-0022)**: RPT bloom filters and dynamic filter
`TupleDomain` ranges serve **complementary roles** at different pipeline levels:

| Filter Type | Row Group/Page Skip (I/O elimination) | Row-Level Filter (post-decode) |
|---|---|---|
| TupleDomain range (min/max) | Yes — range overlap with row group stats | Coarse (only outside range) |
| TupleDomain discrete (≤10K values) | Yes — set overlap with range | Yes — exact membership |
| Bloom filter (RPT) | **No** — BFs only support point queries | Yes — any cardinality |

Bloom filters answer "is key X in the set?" but cannot answer "does range [min,max]
overlap with the set?" — so they cannot prune row groups using Parquet min/max
statistics. For the common case of a dimension table with >10K join keys:
- RFC-0022's `TupleDomain` provides the range for **I/O elimination** (row group skip)
- RPT's bloom filter provides fine-grained **row-level filtering** after decode

Both are needed. The range filter eliminates I/O; the bloom filter eliminates rows.

**Progressive split pruning across RPT sections**: Each RPT section's BF-build pass
scans the join key column. With negligible extra overhead (tracking a running min/max),
it can also produce a **`TupleDomain` summary** (min/max range, and discrete value set
if cardinality ≤ threshold). The coordinator uses these to prune subsequent sections'
splits at multiple levels before any data is read:

```
RPT Section 0 (forward — dims):
  Scan dim_c WHERE region='US'
  Output: BF_c (row-level) + TD_c (min=100, max=5000) + Discrete_c ({102, 205, ...})

RPT Section 1 (forward — fact):
  Coordinator receives TD_c, BF_c, Discrete_c BEFORE assigning fact splits:

  Level 1 — Partition pruning (coordinator):
    Hive partitions / Iceberg partition specs checked against TD_c.
    Skip partitions where key range has no overlap.

  Level 2 — File/manifest pruning (coordinator, Iceberg):
    Iceberg manifest per-file column stats checked against TD_c.
    Skip files where key min/max has no overlap.
    e.g., 1000 files → 200 survive.

  Level 3 — Row group pruning (worker, CPU):
    Parquet row group min/max checked against TD_c.
    Skip row groups with no overlap.
    e.g., 5000 RGs → 800 survive.

  Level 4 — Row-level filtering (worker, GPU):
    Decode surviving row groups.
    Probe BF_c against each key. Eliminate non-matching rows.

  Workers scan 800 RGs (not 5000), build BF_fact + TD_fact.

RPT Section 2 (backward — dims):
  Coordinator prunes dim splits using TD_fact:
    dim_a: 100 files → 30 survive.
  Workers scan surviving files → tighter BFs.

Section 3 (main query):
  Coordinator has ALL accumulated filters from ALL passes.
  Maximum pruning at every level for every table.
  Minimal data read → CPU decode → GPU/CPU compute.
```

Each level is a coarser-to-finer sieve. Levels 1-3 use `TupleDomain` ranges (not BFs
— BFs can't do range checks). Level 4 uses BFs. The coordinator never assigns splits
that can't match, so workers never read eliminated files. This is where the dominant
I/O savings come from — not from row-level BF filtering, but from **never reading
entire files and row groups in the first place**.

The BF-build pass already scans the key column — collecting min/max for `TupleDomain`
output adds one comparison per key per column. RFC-0022's filter distribution
infrastructure already supports `TupleDomain` serialization and coordinator-side
application via `CoordinatorDynamicFilter` and split-level pruning.

#### 4.2.8 Parquet Bloom Filters and RPT

Parquet files can contain per-row-group, per-column bloom filters (spec 2.10+).
We investigated whether these could replace or accelerate RPT scan phases.

**Conclusion: not a significant optimization for RPT.** Two approaches fail:

1. **OR-merging Parquet BFs to skip a scan phase**: Parquet BFs are sized
   per-row-group. Merging across R row groups saturates FPR to 99%+ after ~10
   row groups (the merged BF is completely full). Additionally, merged BFs contain
   unfiltered keys — useless when the RPT scan applies predicates (the common case).

2. **Row-group pruning via Parquet BFs**: Technically sound but marginal. Velox
   already reads only the narrow join key column first (columnar projection), applies
   the RPT BF, then reads remaining columns for survivors. Parquet BF pruning would
   only avoid reading that one narrow column for skipped row groups (~2-8 MB per
   row group for int64 keys). The overhead of the BF check itself approaches this.

RPT's I/O savings come from Levels 1-3 (TupleDomain range pruning at partition, file,
and row-group granularity) and Level 4's efficiency from columnar projection. Parquet
BFs are not on the critical path. See `GPU_ALGEBRAIC_MEMORY_TECHNIQUES.md` Section 8
for the detailed analysis (FPR saturation math, cost comparison, predicate interaction).

### 4.3 NVLink-Aware Overpartitioning

**Complexity**: Medium | **Impact**: Eliminates skew, bounds per-GPU memory

Within an NVLink domain (e.g., 8 GPUs in a DGX node), shuffle bandwidth is ~900 GB/s
per link. This inverts traditional "minimize shuffle" optimization:

| Interconnect | Bandwidth | Shuffle Cost |
|---|---|---|
| 100GbE network | ~12 GB/s | Expensive (traditional) |
| NVLink 4.0 | ~900 GB/s | Essentially free |

**Strategy**: Overpartition by default within NVLink domains. Salt partition keys to
distribute data evenly across GPUs:

- **Salted aggregation**: `hash(group_key, salt % N_GPUs)` → partial agg per GPU →
  reshuffle by real key (free via NVLink) → final agg
- **Salted joins**: Replicate build side N ways, salt probe side partition key →
  each GPU gets ~1/N of probe data
- **Preemptive salting**: Don't wait for OOM — salt proactively based on cardinality
  estimates. Cost of unnecessary salting on NVLink ≈ 0.

**Topology awareness**: Overpartition freely within an NVLink island. Across node
boundaries (network), revert to traditional shuffle economics.

**Fallback hierarchy with NVLink**:
```
1. GPU OOM → reshuffle with higher salt factor across more GPUs (free via NVLink)
2. Still OOM → GPU + UVM spill to CPU memory
3. Still failing → CPU fallback via exchange buffer replay (last resort)
```

### 4.4 GPU Memory-Bounded Operators

**Complexity**: Medium-High | **Impact**: Prevents OOM for joins and aggregations

#### 4.4.1 Grace Hash Join on GPU

When the build side doesn't fit in VRAM, partition it:

```
Phase 1 — Partition (GPU, streaming):
  Stream build side → hash into P partitions → write to host memory (~6GB each)
Phase 2 — Build + Probe (per partition):
  Load partition 0 → build hash table → probe → emit → evict
  Load partition 1 → repeat...
Peak VRAM: one partition's hash table
```

GPU excels at Phase 1 (hashing and scattering is massively parallel). Writing
partitions to host memory over PCIe/NVLink-C2C is fast enough for this pattern.

#### 4.4.2 Sort-Based Streaming Aggregation

For high-cardinality GROUP BY where hash tables blow VRAM:

```
Step 1: GPU radix sort by group key (O(n), 1-4 GB/s — a GPU strength)
Step 2: Stream sorted data through aggregation (O(1) memory per group)
```

For data larger than VRAM, use external sort: sort chunks in VRAM, write sorted runs
to host memory, k-way merge on GPU, feed merged stream into aggregation.

#### 4.4.3 Hybrid Hash + Sort Aggregation

For power-law distributions (most real data):

```
Phase 1: Fixed-size hash table (e.g., 4GB, ~40M slots)
         Common groups aggregated in-place (fast, GPU-native)
         Rare groups spill to partition buffer
Phase 2: Sort spilled rows by group key (external sort if needed)
         Streaming aggregation on sorted data
         Merge with Phase 1 results
```

### 4.5 Cascading Partial Aggregation

**Complexity**: Low (already exists) | **Impact**: Reduces data volume at each stage

Enable `AddIntermediateAggregations` (`enable_intermediate_aggregations = true`) to
insert extra INTERMEDIATE aggregation stages. Each stage reduces cardinality before
the next shuffle. With NVLink, the extra shuffles between stages are free.

For grouped aggregations (which `AddIntermediateAggregations` doesn't currently handle),
a similar pattern applies: partial agg per-split → shuffle → partial agg per-partition
→ shuffle → final agg. Each stage operates on a smaller working set.

Combined with salted partitioning, this ensures each GPU's partial aggregation hash
table stays within VRAM bounds.

### 4.6 Coordinator-Driven GPU-Aware Scheduling

**Complexity**: Medium | **Impact**: Optimal fragment placement

Extend the coordinator with GPU awareness:

1. **Fragment classification**: `GPU_PREFERRED`, `GPU_NEUTRAL`, `CPU_ONLY`
2. **Worker capability advertisement**: GPU model, VRAM, utilization via heartbeat
3. **GPU-aware placement**: Route fragments to appropriate workers
4. **Topology awareness**: Prefer co-locating communicating fragments within NVLink
   domains to maximize shuffle bandwidth

### 4.7 Adaptive GPU Fallback via Exchange Buffers

**Complexity**: High | **Impact**: Runtime safety net for unexpected failures

Leverages the Adaptive Exchange Framework for runtime GPU→CPU fallback:

```
Exchange buffers ~100K rows → try GPU execution →
  Success: continue on GPU
  Failure: replay buffer on CPU, switch remaining pipeline to CPU
```

With NVLink and RPT in place, this becomes a rare last-resort fallback rather than
the primary mechanism. Most OOM scenarios are handled by overpartitioning (4.3) and
memory-bounded operators (4.4).

---

## 5. The Complete GPU Query Pipeline

Putting it all together, a GPU-accelerated query in Presto flows through:

```
Planning:
  ┌──────────────────────────────────────────────────────────────┐
  │ 1. Standard Presto optimization (join reorder, predicate    │
  │    pushdown, etc.)                                          │
  │ 2. Generate RPT transfer schedule (LargestRoot algorithm)   │
  │ 3. Classify fragments: GPU_PREFERRED / CPU_ONLY             │
  │ 4. Insert intermediate aggregations + salted partitioning   │
  │ 5. Generate transfer sections + main query sections         │
  └──────────────────────────────────────────────────────────────┘

Transfer Sections (RPT — CPU-based bloom filter + TupleDomain passes):
  ┌──────────────────────────────────────────────────────────────┐
  │ Section 0: Scan leaves → build BFs + TupleDomains            │
  │   CPU: key-column-only scan (column pruning: ~5% of data)    │
  │   Output: BF (row-level) + TD (min/max) + discrete values    │
  │                                                              │
  │ Section 1: Scan root with progressive split pruning          │
  │   Coordinator receives TD + discrete from Section 0:         │
  │     → Prune partitions (Hive/Iceberg partition specs)        │
  │     → Prune files (Iceberg manifest column stats)            │
  │     → Skip eliminated splits entirely (no I/O)               │
  │   Workers scan surviving splits only:                        │
  │     → Row group pruning via TD range (min/max check)         │
  │     → Key-only decode + BF build on surviving RGs            │
  │   Output: BF_fact + TD_fact                                  │
  │                                                              │
  │ Section 2: Backward pass with progressive pruning            │
  │   Coordinator prunes dim splits using TD_fact                │
  │   Workers scan only surviving files → tighter BFs + TDs      │
  │                                                              │
  │ Total overhead: ~0.3s (star schema SF-1000)                  │
  │ BFs + TDs distributed via Dynamic Filtering infrastructure   │
  └──────────────────────────────────────────────────────────────┘

Main Query Section (joins + aggregations on maximally reduced data):
  ┌──────────────────────────────────────────────────────────────┐
  │ Coordinator has ALL filters from ALL RPT passes:             │
  │   → Maximum partition/file/manifest pruning for every table  │
  │   → Only assigns splits that survived all filter levels      │
  │                                                              │
  │ Scan (CPU prefetch/decode pipeline):                         │
  │   CPU: row group pruning via accumulated TDs                 │
  │   CPU: read + decompress + decode surviving pages → Arrow    │
  │                                                              │
  │ Compute (CPU or GPU — per-fragment decision):                │
  │   GPU path (join-heavy/agg-heavy queries):                   │
  │     PCIe/NVLink-C2C: decoded Arrow batches → GPU VRAM        │
  │     GPU: BF probe on join keys (row-level, RPT)              │
  │     GPU: filter, project, hash join, aggregation             │
  │   CPU path (scan-dominant/simple queries):                   │
  │     CPU: BF probe, filter, project, join, aggregation        │
  │     (no PCIe transfer overhead)                              │
  │                                                              │
  │ Per-fragment device decision (NativePlanChecker V0):          │
  │   estimated_gpu_compute_savings > pcie_transfer_cost → GPU   │
  │   otherwise → CPU                                            │
  │                                                              │
  │ Safety net: exchange buffer replay → CPU fallback             │
  └──────────────────────────────────────────────────────────────┘
```

### 5.1 Final Phase: CPU vs GPU Decision

After RPT reduction and progressive split pruning, the data reaching the final phase
is a small fraction of the original tables. Whether this final phase should execute on
CPU or GPU depends on the query's compute intensity:

**GPU wins** when post-scan compute dominates:
- Multi-way joins with large build sides (even after RPT, 4-way join is complex)
- Heavy aggregations with many groups
- Complex filter/project expressions evaluated per-row
- Queries where GPU parallelism on join probe / aggregation / sorting matters

**CPU wins** when data volume is already small:
- Simple scans with trivial aggregation (TPC-H Q1 style)
- Single join with small build side that already fits after RPT
- Queries where PCIe transfer overhead exceeds GPU compute savings

**The decision is per-fragment** via the NativePlanChecker V0 mechanism. Fragments with
complex joins + aggregations → GPU. Fragments with simple scans → CPU. Both can coexist
in the same query, connected by exchanges.

**Cost heuristic**:
```
pcie_cost = reduced_data_size / pcie_bandwidth
gpu_savings = (cpu_compute_time - gpu_compute_time)

If gpu_savings > pcie_cost → execute on GPU
Otherwise → execute on CPU
```

For the reference hardware (8x A100, 128 CPU cores, 200 GB/s aggregate PCIe):
- Join build + probe on 5GB: CPU ~2s, GPU ~0.1s → savings = 1.9s, transfer = 0.2s → **GPU**
- Simple COUNT(*) on 5GB: CPU ~0.3s, GPU ~0.05s → savings = 0.25s, transfer = 0.2s → **marginal, CPU ok**
- 5-way join on 20GB reduced: CPU ~30s, GPU ~1.5s → savings = 28.5s, transfer = 0.8s → **GPU by 35x**

---

## 6. Implementation Phases

### Phase 1: V0 GPU Fallback via NativePlanChecker (Lowest Effort)
- Extend sidecar `/v1/velox/plan` endpoint with `device=gpu` mode that attempts
  cuDF `DriverAdapter` plan rewriting
- Change `NativePlanChecker` to return a `DeviceCapability` annotation instead of
  throwing `PrestoException` on GPU check failure
- Add fragment-level device routing in `SqlQueryScheduler` (GPU-capable → GPU workers,
  CPU-only → CPU workers)
- Land Dynamic Filtering RFC (RFC-0022) for coordinator-side partition pruning
- Enable `enable_intermediate_aggregations` for GPU workloads
- **Result**: Queries with unsupported GPU operations gracefully fall back to CPU
  per-fragment. No query ever fails due to GPU limitations. As cuDF support expands,
  more fragments automatically route to GPU with zero coordinator changes.

### Phase 2: RPT Transfer Sections (Benefits CPU and GPU)
- Extend Dynamic Filtering with bloom filter support (`filterType: "bloomFilter"`)
- Implement transfer schedule generation (LargestRoot algorithm) in planner
- Model RPT phases as sections with `BloomFilterBuildStage`
- Use existing filter distribution infrastructure for BF propagation
- Split-to-worker affinity across sections for Velox `AsyncDataCache` hits
- HBO integration: use `JoinNodeStatistics` actuals to gate RPT per-join, track BF
  reduction ratios for future planning, skip RPT when HBO confirms joins are small
- **Result**: Hash tables shrink 10-100x. On CPU: eliminates hash join spilling,
  improves cache locality, provides join order robustness (worst case 1.6x of best).
  On GPU: most joins fit in VRAM. Benefits both execution paths — this phase is
  valuable independent of GPU adoption. HBO amortizes overhead after first run.

### Phase 3: NVLink-Aware Overpartitioning + Salted Aggregation
- Add NVLink topology detection to worker capability reporting
- Implement salted partition keys in the planner for aggregation and joins
- Extend `AddIntermediateAggregations` to grouped aggregations with salt
- Topology-aware scheduling: prefer co-location within NVLink domains
- **Result**: Skew eliminated; per-GPU memory bounded by construction

### Phase 4: Memory-Bounded GPU Operators
- Grace hash join on GPU (partition to host memory, process per-partition)
- Sort-based streaming aggregation for high-cardinality GROUP BY
- GPU→host memory spill (PCIe/NVLink-C2C, 64-900 GB/s)
- **Result**: Handles the long tail of OOM cases without CPU fallback

### Phase 5: Adaptive Runtime Fallback
- Leverage Adaptive Exchange Framework for buffer + replay + hold signals
- Runtime GPU→CPU fallback for unexpected failures
- Coordinator learns from failures for future scheduling decisions
- **Result**: Complete safety net; no query ever fails due to GPU limitations

---

## 6. Commodity GPU Economics

### 6.1 The Secondary Market Thesis

As AI training clusters upgrade from one GPU generation to the next (H100 → B200 →
next), older GPUs enter the secondary market at dramatically lower prices. A fleet of
ex-training H100s becomes available at a fraction of their original cost. This changes
the economic calculus for GPU-accelerated analytics:

- **Supply**: Retired AI training clusters release tens of thousands of GPUs per cycle
- **Price**: 2-3 year old GPUs at 20-30% of original cost, still with full HBM and
  NVLink capabilities
- **Capability**: These aren't weak GPUs — an H100 with 80GB HBM3 and 3.35 TB/s memory
  bandwidth is massively overpowered for analytics compared to what it was designed for

The implication: GPU compute stops being a scarce resource to optimize carefully and
becomes commodity infrastructure to deploy broadly.

### 6.2 Impact on the Architecture

**What changes with commodity GPUs:**

1. **Final phase default shifts to GPU.** The Section 5.1 cost heuristic
   (`gpu_savings > pcie_cost`) becomes almost universally true when GPU cycles are
   cheap. The threshold for "GPU-worthy" fragments drops from join-heavy/agg-heavy
   queries to nearly all compute — even simple filter/project benefits from GPU
   parallelism when GPUs are abundant and cheap.

2. **NVLink overpartitioning becomes the standard, not the exception.** With
   commodity GPUs, provisioning 8 GPUs per node is the default. Overpartitioning
   across all 8 GPUs via NVLink is the standard execution model, not an optimization.

3. **Memory-bounded operators (Phase 4) become less critical.** With 8x 80GB = 640GB
   of aggregate GPU VRAM per node (plus UVM spill to host memory), many workloads
   that needed Grace hash join or external sort simply fit. RPT reduction + 640GB
   VRAM handles the vast majority of analytical joins.

4. **Adaptive fallback (Phase 5) becomes even rarer.** GPU coverage approaches 100%
   as cuDF support matures. The remaining CPU-only fragments are truly exotic
   operations (specific UDFs, unsupported types), not common query patterns.

**What does NOT change:**

1. **RPT BF-build passes still run on CPU.** Even with commodity GPUs, the BF-build
   phase should run on CPU for three reasons:

   - **GPUs are idle during transfer sections anyway.** Transfer phases produce only
     bloom filters and TupleDomains — no heavy compute for GPUs to accelerate. Using
     idle CPU cores costs nothing.

   - **The bottleneck is storage I/O, not compute.** BF-build reads ~5% of data (key
     columns via Parquet column pruning). With 170 GB/s NVMe and 128 CPU cores
     providing 384 GB/s aggregate decode, the pass is storage-bound. GPU parallelism
     can't speed up a storage-bound pipeline.

   - **Parquet metadata is structurally CPU-friendly.** The hierarchical
     metadata/page structure requires sequential interpretation regardless of GPU
     availability. Even commodity GPUs achieve ~10x less than theoretical on Parquet
     decode. The data format hasn't changed, just GPU pricing.

   The one scenario where GPU BF-build makes sense: **custom columnar formats**
   (GPU-native storage) where GDS can DMA directly to VRAM. In that case, GPU
   builds BFs at HBM bandwidth (3.35 TB/s) — orders of magnitude faster than any
   CPU approach. This is a future optimization when the ecosystem moves beyond
   Parquet.

2. **Static fallback via NativePlanChecker (Phase 1) remains essential.** Even
   commodity GPUs don't support every SQL operation. Per-fragment GPU/CPU routing
   is still needed for correctness.

3. **Progressive split pruning across RPT sections remains the dominant I/O
   optimization.** GPU compute speed doesn't change the amount of data read from
   storage. Pruning partitions, files, and row groups before reading remains the
   biggest performance lever regardless of compute backend.

### 6.3 Revised Default Execution Model

With commodity GPUs, the default shifts from "CPU by default, GPU where it helps"
to "GPU by default, CPU where required":

```
Planning (unchanged):
  Fragment classification via NativePlanChecker:
    GPU-capable fragments → GPU (the common case with commodity GPUs)
    CPU-only fragments    → CPU (exotic ops, unsupported types)

RPT Transfer Sections (unchanged — CPU):
  CPU key-column scans → BF + TD construction
  Progressive split pruning at coordinator
  (GPUs idle — no work for them in transfer phases)

Main Query Execution (GPU by default):
  CPU: Parquet decode + Arrow batch assembly (CPU-friendly)
  GPU: Everything else (filter, project, join, agg, sort, window)
  NVLink: Free shuffle between GPUs in the same node
  UVM: Transparent spill to host memory for >VRAM working sets
```

This is the end state: CPU does what it's best at (I/O, metadata, decode), GPU
does what it's best at (parallel compute), and RPT ensures data volumes are
minimized before either touches it.

---

## 7. Open Questions

1. **UVM for Velox**: Should we adopt CUDA Unified Virtual Memory (as Polars did) as a
   simpler alternative to Grace partitioning for >VRAM datasets?

2. **Dual operator registration**: Currently cuDF operators replace CPU operators globally.
   Can we maintain both and select at runtime per-fragment?

3. **Bloom filter size/FPR tradeoffs**: What bloom filter parameters (size, hash count)
   give the best VRAM reduction per byte of bloom filter for typical workloads?

4. **SPJ (Storage Partitioned Joins)**: If data is pre-bucketed on join keys (Hive
   bucketing, Iceberg partition specs), each bucket can be processed independently with
   bounded memory. How does this interact with RPT? (SPJ may make RPT unnecessary for
   co-bucketed tables.)

5. **Transfer section overhead**: For short queries (< 5s), do the extra scan passes
   from RPT transfer sections add unacceptable latency? Should there be a cost threshold
   below which RPT is skipped?

6. **Cross-node GPU shuffle**: With NVLink only available within a node, how should the
   planner handle multi-node GPU clusters? RDMA GPU-direct between nodes?

7. **GPU-native storage formats**: When data moves beyond Parquet to GPU-native columnar
   formats (e.g., GDS-friendly layouts), should RPT BF-build passes move from CPU to GPU?
   This would enable BF construction at HBM bandwidth (3.35 TB/s) instead of storage
   bandwidth (170 GB/s), but requires the entire storage stack to change.

8. **Commodity GPU fleet management**: How should Presto manage heterogeneous GPU fleets
   (mix of H100, A100, older generations) with different VRAM sizes and compute capabilities?
   Should the cost heuristic adapt per-worker based on GPU generation?

9. **RPT for LEFT JOINs — probe-side I/O reduction via plan decomposition**: RPT cannot
   filter the preserved (probe) side of a LEFT JOIN — every fact row must appear in the
   output. For GPU, this is less critical than it sounds: the probe side streams through
   in batches and never needs to fit in VRAM, while the build side (hash table) does. RPT's
   backward pass already reduces the build side for LEFT JOINs, which is the primary GPU
   win (VRAM feasibility).

   However, for CPU-bound queries and I/O-bound workloads, probe-side reduction would
   provide significant additional value (fewer hash lookups, less downstream data). A
   potential approach is **plan-level decomposition** inspired by relational algebra:

   ```
   fact LEFT JOIN dim
   ≡ (fact INNER JOIN dim) UNION ALL (fact ANTI JOIN dim → NULL-pad)
   ```

   The INNER JOIN portion gets full bidirectional RPT — including I/O pruning on fact
   (row group and file skipping via TupleDomain). The ANTI JOIN portion handles the
   NULL-preserved rows. With `AsyncDataCache`, the two scans share cached pages, so
   total storage I/O is approximately the same as a single scan:

   ```
   INNER scan: reads 20% of fact row groups from storage → cached
   ANTI scan:  20% cached (hit), reads remaining 80% from storage
   Total storage I/O: 100% — same as single scan

   But: INNER portion processes 80% less data through the hash join
        ANTI portion just emits rows with NULLs (no hash probe needed)
   ```

   **HBO-informed decision**: HBO's `JoinNodeStatistics` tracks the historical unmatched
   rate (probe rows with no build match). This determines whether decomposition is
   worthwhile:
   - High unmatched rate (>20%): large ANTI portion emitted cheaply, INNER portion
     significantly reduced — decomposition is valuable
   - Very low unmatched rate (<1%): nearly all rows go through INNER anyway — the ANTI
     portion is negligible, but so is the benefit of decomposition
   - Zero unmatched rate across multiple runs: the LEFT JOIN is behaviorally INNER — the
     outer-to-inner conversion in `PredicatePushDown` may have missed it (no static
     predicate to trigger conversion). HBO could flag this for RPT purposes.

   This is a future optimization — the build-side reduction from the backward pass (which
   works today for LEFT JOINs) handles the critical GPU VRAM constraint. The decomposition
   adds CPU/I/O efficiency on top.

10. **Idle-time prefetch — amortizing RPT overhead**: RPT's extra scan passes (Sections
    0-2) create idle time on fact workers during the backward pass. Prefetch converts that
    idle time into useful I/O, so the cost of RPT partially pays for itself. See Section
    4.2 for the cost model showing this amortization.

    The recommended orchestration is **worker-autonomous background prefetch**: the planner
    embeds a `prefetchColumns` descriptor (Section 3's projected column set minus Section
    1's key columns) in the Section 1 BF-build task fragment. When a worker completes its
    Section 1 task and has identified surviving row groups, it autonomously fires background
    prefetch for those RGs' non-key columns via `CachedBufferedInput::prefetch()`. No
    coordinator signal, no new RPT section — fire-and-forget on a background executor.

    ```
    Section 1 (BF-build task completes on worker):
      Worker knows: surviving RGs = {12, 37, 42, 89, ...}
      Worker knows: prefetchColumns = {col_a, col_b, col_c}  (from fragment metadata)
      Worker fires: for each (RG, col) pair:
        CachedBufferedInput::prefetch(Region{rg_col_offset, rg_col_length})
      Worker returns: BF to coordinator (normal path — prefetch is fire-and-forget)

    Section 2 (backward pass — concurrent with prefetch):
      Dim workers: scanning dim tables (active)
      Fact workers: background prefetch I/O continues asynchronously
        → RPT's idle time is doing useful work instead of nothing

    Section 3 (main query — benefits from amortized prefetch):
      Fact scans find three categories of pages:
        1. Prefetch-completed pages → cache hit (RPT overhead fully amortized)
        2. In-flight pages → partial latency (partially amortized)
        3. Not-yet-started pages → cold read (not amortized — normal path)
    ```

    **Why worker-autonomous (not coordinator-orchestrated)**: A coordinator-managed prefetch
    stage in Section 2 would block section completion — the coordinator can't advance to
    Section 3 until all prefetch tasks report complete, defeating the purpose. Worker-
    autonomous prefetch is fire-and-forget: Section 3 starts as soon as backward BFs are
    ready regardless of prefetch progress.

    **Over-fetch tradeoff**: Uses Section 1's surviving RG set, which may be a superset of
    Section 3's final set (backward pass could further refine dims). For star schemas the
    difference is minimal — backward pass refines dimension tables, not fact. For snowflake
    schemas, some wasted prefetch I/O is possible but bounded (prefetch set is already 90%+
    reduced from the full table).

    **Requires**: Mapping from (rowGroupId, columnName) → file byte region for targeted
    prefetch. Today `scheduleRowGroups()` does sequential prefetch (current + N ahead);
    this extends it to arbitrary RG sets based on RPT survival information.

    **Priority**: Phase 2 — only worth building after RPT is working end-to-end and
    real workload profiling shows that main query scan I/O is the actual bottleneck.
    SOFT_AFFINITY + AsyncDataCache (no prefetch) may be sufficient in practice.

---

## 8. References

### Presto / Velox
- [Adaptive Exchange Framework](ADAPTIVE_EXCHANGE_FRAMEWORK.md)
- [Dynamic Filtering RFC (RFC-0022)](https://github.com/prestodb/rfcs/pull/54)
- [Velox cuDF Backend](https://github.com/facebookincubator/velox/tree/main/velox/experimental/cudf)
- [Extending Velox with cuDF](https://velox-lib.io/blog/extending-velox-with-cudf/)
- [GPU-Native Velox and cuDF (IBM/NVIDIA)](https://developer.nvidia.com/blog/accelerating-large-scale-data-analytics-with-gpu-native-velox-and-nvidia-cudf/)
- `NativePlanChecker`: `presto-native-sidecar-plugin/.../nativechecker/NativePlanChecker.java`
- `NativePlanCheckerProvider`: `presto-native-sidecar-plugin/.../nativechecker/NativePlanCheckerProvider.java`
- `PlanCheckerProviderManager`: `presto-main-base/.../sanity/PlanCheckerProviderManager.java`
- `AddIntermediateAggregations`: `presto-main-base/.../iterative/rule/AddIntermediateAggregations.java`
- `AsyncDataCache` config: `presto-native-execution/presto_cpp/main/common/Configs.h` (lines 406-468)
- `SsdCache` setup: `presto-native-execution/presto_cpp/main/PrestoServer.cpp` (`setupSsdCache()`)
- `CachedBufferedInput` (I/O coalescing): `velox/dwio/common/CachedBufferedInput.cpp`
- `ReaderOptions` (loadQuantum, maxCoalesceDistance): `velox/common/io/Options.h`
- `FileHandleCache`: `velox/connectors/hive/FileHandle.h`
- `LazySplitSource`: `presto-main-base/.../planner/LazySplitSource.java`
- `SimpleNodeSelector`: `presto-main-base/.../scheduler/nodeSelection/SimpleNodeSelector.java`
- `NodeSelectionStrategy`: `presto-spi/.../schedule/NodeSelectionStrategy.java` (SOFT_AFFINITY, HARD_AFFINITY, NO_PREFERENCE)
- `NodeSelectionStats`: `presto-main-base/.../scheduler/nodeSelection/NodeSelectionStats.java`
- `LogicalCteOptimizer`: `presto-main-base/.../optimizations/LogicalCteOptimizer.java`
- `PhysicalCteOptimizer`: `presto-main-base/.../optimizations/PhysicalCteOptimizer.java`
- `CteProjectionAndPredicatePushDown`: `presto-main-base/.../optimizations/CteProjectionAndPredicatePushDown.java`
- `CTEMaterializationTracker`: `presto-main-base/.../scheduler/CTEMaterializationTracker.java`
- `SequenceNode`: `presto-main-base/.../plan/SequenceNode.java`
- Alluxio cache: `presto-cache/src/main/java/com/facebook/presto/cache/alluxio/AlluxioCacheConfig.java`
- `HistoryBasedPlanStatisticsCalculator`: `presto-main-base/.../cost/HistoryBasedPlanStatisticsCalculator.java`
- `JoinNodeStatistics`: `presto-spi/.../statistics/JoinNodeStatistics.java`
- `CanonicalPlanGenerator`: `presto-main-base/.../planner/CanonicalPlanGenerator.java`
- `HistoryBasedPlanStatisticsTracker`: `presto-main-base/.../cost/HistoryBasedPlanStatisticsTracker.java`

### GPU Data Path
- [GPUDirect Storage](https://developer.nvidia.com/blog/gpudirect-storage/)
- [Boosting Ingest with GDS and cuDF](https://developer.nvidia.com/blog/boosting-data-ingest-throughput-with-gpudirect-storage-and-rapids-cudf/)
- [RAPIDS Blackwell HW Decompression](https://developer.nvidia.com/blog/rapids-brings-zero-code-change-acceleration-io-performance-gains-and-out-of-core-xgboost/)
- [Scaling GPU Databases Beyond VRAM (VLDB'25)](https://www.vldb.org/pvldb/vol18/p4518-li.pdf)

### Query Optimization
- [RPT: Debunking the Myth of Join Ordering (SIGMOD'25)](https://arxiv.org/abs/2502.15181)
- [RPT Source Code (DuckDB-based)](https://github.com/embryo-labs/Robust-Predicate-Transfer)
- [Predicate Transfer (CIDR'24)](https://www.cidrdb.org/cidr2024/papers/p22-yang.pdf)
- [Parachute: Single-Pass Bi-Directional Info Passing (VLDB'25)](https://arxiv.org/abs/2506.13670)
- [Including Bloom Filters in Bottom-up Optimization](https://arxiv.org/pdf/2505.02994)

### Polars / cuDF
- [Polars GPU Engine Release](https://pola.rs/posts/gpu-engine-release/)
- [cuDF OOM Fallback Discussion](https://github.com/rapidsai/cudf/issues/16835)
- [Polars UVM for >VRAM Data](https://pola.rs/posts/uvm-larger-than-ram-gpu/)
