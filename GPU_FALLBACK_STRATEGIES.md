# GPU Execution Strategies for Presto

## Status: Draft v0.2
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

### 2.3 GPU Data Ingestion

Modern GPU data paths eliminate CPU bottlenecks:

- **GPUDirect Storage (GDS)**: DMA from NVMe directly to VRAM, bypassing CPU bounce
  buffers. ~26 GB/s on PCIe Gen4, ~64 GB/s on Gen5.
- **GPU-native decompression**: cuDF decompresses Parquet/ORC pages on GPU (NVComp).
  Blackwell adds hardware decompression engines on-die.
- **GPU-native decode**: Dictionary, RLE, DELTA decoding all run as CUDA kernels.
  Microkernel architecture (specialized per data type) maximizes GPU occupancy.
- **HBM bandwidth**: Once in VRAM, scan throughput is 2-3.35 TB/s (H100/H200/B100).

The CPU's only role in the scan path is reading Parquet metadata (kilobytes) for
partition/page pruning decisions.

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

### 3.4 Dynamic Filtering RFC (In Progress)

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

### 3.5 Intermediate Aggregations (Off by Default)

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

### 3.6 Adaptive Exchange Framework (Prior Design)

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

**Complexity**: Medium-High | **Impact**: 10-100x smaller hash tables

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

**GPU-specific benefits**: Bloom filter construction and probing are embarrassingly
parallel — among the fastest operations on GPU. Bloom filters (~150MB) always fit in
VRAM. The row-level BF probing happens on GPU after decode, at billions of probes/sec.
CPU-side page metadata can also be checked against BFs for I/O elimination before GDS
transfers data to VRAM.

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

Transfer Sections (RPT — bloom filter passes):
  ┌──────────────────────────────────────────────────────────────┐
  │ Section 0: Scan leaves → build BFs (GPU-parallel)           │
  │ Section 1: Scan root + apply BFs → build BF (GPU-parallel)  │
  │ Section 2: Backward pass → tighter BFs                      │
  │                                                              │
  │ BFs distributed via Dynamic Filtering infrastructure         │
  │ Each section: scan + BF build only, no hash tables           │
  │ BF probing on GPU: billions of probes/sec                    │
  └──────────────────────────────────────────────────────────────┘

Main Query Section (joins + aggregations on reduced data):
  ┌──────────────────────────────────────────────────────────────┐
  │ Scan with BFs + partition/page pruning:                      │
  │   CPU: metadata pruning (page skip via BF + min/max)         │
  │   GDS: surviving pages → VRAM directly                       │
  │   GPU: decompress → decode → BF probe → filter → project    │
  │                                                              │
  │ Hash Join (build tables 10-100x smaller after RPT):          │
  │   Fits in VRAM → standard GPU hash join                      │
  │   Doesn't fit → Grace hash join on GPU (partition to host)   │
  │   Skewed → salted join via NVLink overpartitioning            │
  │                                                              │
  │ Aggregation (cascading partial agg):                         │
  │   PARTIAL agg per split (GPU) → NVLink shuffle               │
  │   INTERMEDIATE agg per partition (GPU) → NVLink shuffle      │
  │   FINAL agg (GPU)                                            │
  │   OOM safety: sort-based streaming agg fallback              │
  │                                                              │
  │ Safety net: exchange buffer replay → CPU fallback             │
  └──────────────────────────────────────────────────────────────┘
```

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

### Phase 2: RPT Transfer Sections
- Extend Dynamic Filtering with bloom filter support (`filterType: "bloomFilter"`)
- Implement transfer schedule generation (LargestRoot algorithm) in planner
- Model RPT phases as sections with `BloomFilterBuildStage`
- Use existing filter distribution infrastructure for BF propagation
- **Result**: Hash tables shrink 10-100x; most joins fit in VRAM

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

### GPU Data Path
- [GPUDirect Storage](https://developer.nvidia.com/blog/gpudirect-storage/)
- [Boosting Ingest with GDS and cuDF](https://developer.nvidia.com/blog/boosting-data-ingest-throughput-with-gpudirect-storage-and-rapids-cudf/)
- [RAPIDS Blackwell HW Decompression](https://developer.nvidia.com/blog/rapids-brings-zero-code-change-acceleration-io-performance-gains-and-out-of-core-xgboost/)
- [Scaling GPU Databases Beyond VRAM (VLDB'25)](https://www.vldb.org/pvldb/vol18/p4518-li.pdf)

### Query Optimization
- [RPT: Debunking the Myth of Join Ordering (SIGMOD'25)](https://arxiv.org/abs/2502.15181)
- [Predicate Transfer (CIDR'24)](https://www.cidrdb.org/cidr2024/papers/p22-yang.pdf)
- [Parachute: Single-Pass Bi-Directional Info Passing (VLDB'25)](https://arxiv.org/abs/2506.13670)
- [Including Bloom Filters in Bottom-up Optimization](https://arxiv.org/pdf/2505.02994)

### Polars / cuDF
- [Polars GPU Engine Release](https://pola.rs/posts/gpu-engine-release/)
- [cuDF OOM Fallback Discussion](https://github.com/rapidsai/cudf/issues/16835)
- [Polars UVM for >VRAM Data](https://pola.rs/posts/uvm-larger-than-ram-gpu/)
