# GPU Fallback Strategies for Presto

## Status: Draft v0.1
## Authors: Auto-generated design exploration
## Date: 2026-02-06

---

## 1. Problem Statement

Presto's native execution engine (Prestissimo/Velox) has experimental support for GPU
acceleration via NVIDIA cuDF. Today, when cuDF is enabled, GPU-accelerated operators
replace their CPU counterparts at build time. If a query hits an unsupported operation
or runs out of GPU memory (VRAM), the query **fails entirely** — there is no mechanism
to gracefully fall back to CPU execution.

This is the central question: **How can we fall back from GPU to CPU mid-query without
failing the entire query?**

---

## 2. How Polars Solves This

Polars (via the RAPIDS cuDF integration) has tackled this problem using a
**tightly-coupled, IR-based approach** with an **inspector-executor design**. Their
architecture offers several lessons.

### 2.1 Tightly-Coupled IR Integration

Rather than reimplementing the Polars API on GPU (a "loosely-coupled" approach), cuDF
hooks in **after** the Polars IR (Intermediate Representation) is constructed and
optimized. The user writes normal Polars code; only the physical execution differs.

```
User Code → DSL → Optimized IR → [Inspector] → GPU or CPU Execution
```

### 2.2 Static (Pre-Execution) Fallback

The IR contains full information about every operation: datatypes, options, and
semantics. The **inspector** phase traverses the IR and determines — **before any
execution begins** — whether each operation can run on GPU. The translation layer
encodes, for each operation, which set of datatypes and options it supports.

If an unsupported operation is found, the inspector **stops traversal** and returns the
unmodified IR to the CPU engine. This adds only a few milliseconds of overhead. This is
Polars' primary fallback mechanism today.

**Key property**: The decision is made statically, without executing anything on the GPU.
This means zero wasted GPU work on unsupported queries.

### 2.3 Subgraph-Level Hybrid Execution (Planned)

Polars has noted that their architecture *can* be extended to partial GPU execution:
rather than an all-or-nothing decision, the inspector could mark individual subgraphs
of the IR as GPU-executable, while leaving unsupported subgraphs for CPU. This would
allow queries like:

```
GPU: Scan → Filter → Aggregate
CPU: Window Function (unsupported on GPU)
GPU: Final Join
```

This is not yet implemented but is explicitly called out as a future direction.

### 2.4 OOM Fallback (Closed as Won't Fix)

For GPU out-of-memory errors, Polars investigated two strategies:

1. **Full query rewind**: Re-execute the entire query on CPU from scratch
2. **Partial query rewind**: Rewind only the failing subgraph (requires hybrid execution)

Ultimately, this was **closed as won't fix** because:
- **UVM (Unified Virtual Memory)** now handles most cases where data exceeds VRAM by
  transparently spilling to CPU memory
- A forthcoming **streaming engine** will handle cases where single columns exceed GPU
  memory

### 2.5 Summary of Polars Approach

| Mechanism | Status | Scope |
|-----------|--------|-------|
| Static IR inspection (unsupported ops) | Implemented | Full query fallback |
| Subgraph hybrid GPU/CPU execution | Planned | Operator-level fallback |
| OOM → full rewind to CPU | Investigated, closed | Full query fallback |
| OOM → partial rewind | Investigated, closed | Subgraph-level fallback |
| UVM for >VRAM data | Implemented | Memory overflow |
| Streaming engine | In development | Memory overflow |

---

## 3. What Presto Already Has

### 3.1 Current cuDF Integration

Presto's GPU support (`PRESTO_ENABLE_CUDF`) works at build time:
- cuDF-accelerated Hive connectors **replace** standard connectors
- `velox::cudf_velox::registerCudf()` registers GPU operator implementations
- GPU is disabled by default; enabled via `CudfConfig`
- There is no runtime fallback — if a registered GPU operator fails, the query fails

### 3.2 Adaptive Exchange Framework (Prior Design)

The [Adaptive Exchange Framework](ADAPTIVE_EXCHANGE_FRAMEWORK.md) introduces a powerful
primitive: **buffer + section boundary creates observation without commitment, with
replay capability**. Key concepts:

- Exchanges buffer initial rows (~100K) and collect runtime statistics
- "Hold" signals cascade upstream to create global synchronization points
- The coordinator can reoptimize the query plan based on observed data
- Buffers enable **replay** — work can be redone with a different strategy

This framework was designed for join reordering, but the primitives are directly
applicable to GPU fallback.

---

## 4. Proposed GPU Fallback Strategies for Presto

We propose a layered approach, from simplest to most sophisticated.

### Strategy 1: Static Plan Inspection (Polars-Style)

**Complexity**: Low
**Scope**: Full query fallback before execution

Before executing a query fragment on a worker, inspect the Velox plan tree and determine
whether all operators are supported by the cuDF backend. If any operator is unsupported,
execute the entire fragment on CPU.

```
Coordinator sends plan → Worker receives plan
                         → [GPU Inspector] checks all operators
                         → If all supported: execute on GPU
                         → If any unsupported: execute on CPU
```

**Implementation sketch**:
- Add a `CudfPlanValidator` that walks the Velox `PlanNode` tree
- For each node, check against a registry of GPU-supported operators, datatypes, and
  function signatures
- This runs at fragment startup, before any data processing
- Cost: negligible (plan tree walk)

**Limitations**: All-or-nothing at the fragment level. A single unsupported function in
a large plan forces the entire fragment to CPU.

### Strategy 2: Operator-Level GPU/CPU Hybrid Execution

**Complexity**: Medium
**Scope**: Per-operator GPU/CPU decisions

Rather than all-or-nothing, partition the plan into GPU-executable and CPU-executable
subgraphs. Insert data transfer operators at GPU↔CPU boundaries.

```
TableScan [GPU] → Filter [GPU] → data transfer → Window [CPU] → data transfer → HashJoin [GPU]
```

**Implementation sketch**:
- During plan compilation, annotate each `PlanNode` with a `device` attribute (GPU/CPU)
- When adjacent operators have different devices, insert a `DeviceTransfer` operator
  that handles GPU↔CPU memory copies (via `cudf::to_arrow` / `from_arrow` or similar)
- Velox's `Driver` pipeline would need awareness of device placement

**Key challenge**: Data transfer between GPU and CPU memory is expensive. The optimizer
needs a cost model to decide when GPU acceleration outweighs transfer overhead. A filter
that reduces 1B rows to 1K rows is a great GPU→CPU boundary; a 1:1 mapping is not.

**Potential cost model heuristic**:
- Prefer GPU for: scans with pushdown filters, aggregations, hash joins
- Prefer CPU for: unsupported functions, low-cardinality operations, operations on
  small data
- Prefer boundaries where: data volume changes dramatically (post-filter, post-aggregate)

### Strategy 3: Adaptive GPU Fallback via Exchange Buffers

**Complexity**: High
**Scope**: Runtime fallback with replay

This leverages the Adaptive Exchange Framework's core primitive — **buffered execution
with replay capability** — to enable runtime GPU-to-CPU fallback.

```
                    ┌─────────────┐
                    │  Exchange    │
                    │  Buffer     │  ← Buffers initial rows
                    │  (~100K)    │
                    └──────┬──────┘
                           │
                    ┌──────▼──────┐
                    │  GPU Exec   │  ← Try GPU execution
                    │  Attempt    │
                    └──────┬──────┘
                           │
                  ┌────────┼────────┐
                  │ Success         │ Failure (OOM, error)
                  │                 │
                  ▼                 ▼
           Continue on GPU    Replay buffer on CPU
                              + switch remaining
                              execution to CPU
```

**How it works**:

1. **Buffer phase**: The exchange buffers the first N rows (configurable, ~100K default)
   before passing them downstream. This is the same mechanism used for adaptive join
   reordering.

2. **GPU trial execution**: Downstream operators execute on GPU using the buffered data.
   During this phase, we monitor for:
   - OOM errors (CUDA allocation failures)
   - Unsupported operation errors that weren't caught statically
   - Correctness issues (NaN handling differences, precision mismatches)

3. **Decision point**: After processing the buffer:
   - **Success**: Continue GPU execution for remaining data. The buffer is released.
   - **Failure**: Discard GPU results, **replay the buffer through CPU operators**, and
     switch the remaining pipeline to CPU execution.

4. **Cascade signals**: If a GPU failure is detected, the exchange can send "hold"
   signals upstream (exactly as in adaptive join reordering) to pause data production
   while the CPU pipeline is initialized.

**Why this is powerful for Presto specifically**:

- Presto already has exchanges as natural boundaries between pipeline stages
- The Adaptive Exchange Framework already defines the buffer + replay + hold signal
  primitives
- Unlike Polars (single-machine, in-memory), Presto is distributed — a GPU failure on
  one worker shouldn't kill the entire query
- The buffered approach means we don't need perfect static analysis; we can **try GPU
  and fall back** with bounded wasted work

**Key design decisions**:

- **Buffer size**: Must be large enough to trigger GPU OOM on small-memory GPUs if it's
  going to happen, but small enough to limit wasted work. ~100K rows or configurable
  memory limit.
- **Fallback granularity**: Per-pipeline-driver (finest), per-worker, or per-stage.
  Per-pipeline-driver is most flexible but most complex.
- **Worker heterogeneity**: In a mixed GPU/CPU cluster, the coordinator could route
  GPU-friendly fragments to GPU workers and CPU-only fragments to CPU workers, using
  the same soft-affinity scheduling mechanisms.

### Strategy 4: Coordinator-Driven GPU-Aware Scheduling

**Complexity**: Medium (complements other strategies)
**Scope**: Query-level and fragment-level

The Presto coordinator already makes fragment placement decisions. We can extend this
with GPU awareness:

1. **Fragment classification**: At planning time, classify each fragment as:
   - `GPU_REQUIRED` — only makes sense on GPU (e.g., ML inference operators)
   - `GPU_PREFERRED` — supported on GPU and likely faster (large aggregations, joins)
   - `GPU_NEUTRAL` — similar perf on GPU/CPU (I/O-bound scans)
   - `CPU_ONLY` — not supported on GPU

2. **Worker capability advertisement**: Workers report their capabilities (GPU model,
   VRAM, current utilization) to the coordinator via heartbeat.

3. **GPU-aware placement**: The coordinator routes fragments to appropriate workers:
   - `GPU_PREFERRED` fragments → GPU workers (with CPU fallback if GPU workers are busy)
   - `CPU_ONLY` fragments → any worker
   - When GPU workers are saturated, `GPU_PREFERRED` fragments fall back to CPU workers
     with a warning logged

4. **Integration with adaptive exchanges**: If a `GPU_PREFERRED` fragment fails on GPU
   mid-execution, the adaptive exchange buffer enables replay on CPU (Strategy 3),
   and the coordinator updates its model for future scheduling.

---

## 5. Comparison with Polars

| Dimension | Polars | Proposed Presto Approach |
|-----------|--------|------------------------|
| Architecture | Single machine, in-memory | Distributed, multi-stage |
| Static fallback | IR inspection, full query | Plan inspection, per-fragment |
| Hybrid execution | Planned (subgraph) | Strategy 2 (operator-level) |
| OOM handling | UVM + closed as won't fix | Strategy 3 (buffer + replay) |
| Memory overflow | UVM, streaming engine | Spill to disk (existing), buffer replay |
| Scheduling | N/A (single machine) | Strategy 4 (coordinator-driven) |

**Presto's key advantage**: The distributed architecture with exchanges provides natural
"checkpoints" for observation and fallback — something Polars' single-machine model
lacks. The Adaptive Exchange Framework's buffer/replay/hold primitives are a direct
enabler for runtime GPU fallback that has no analogue in Polars.

**Polars' key advantage**: Simpler execution model makes static analysis sufficient for
most cases. The tightly-coupled IR approach ensures semantic equivalence between GPU
and CPU paths with minimal engineering effort.

---

## 6. Recommended Implementation Order

### Phase 1: Static Inspection + Full Fragment Fallback
- Implement `CudfPlanValidator` in Velox
- Per-fragment GPU/CPU decision before execution
- No runtime overhead, no architectural changes
- Catches ~90% of cases (unsupported functions/types)

### Phase 2: Coordinator GPU-Aware Scheduling
- Worker capability reporting
- Fragment classification in planner
- GPU-aware soft-affinity scheduling
- Enables heterogeneous clusters (some GPU, some CPU-only workers)

### Phase 3: Adaptive GPU Fallback via Exchange Buffers
- Leverage Adaptive Exchange Framework primitives
- Buffer → try GPU → replay on CPU if failed
- Handles OOM and runtime failures gracefully
- Most complex but handles the long tail of failures

### Phase 4: Operator-Level Hybrid Execution
- Annotate plan nodes with device preference
- Insert DeviceTransfer operators at boundaries
- Cost-model-driven placement decisions
- Maximum GPU utilization for partially-supported queries

---

## 7. Open Questions

1. **UVM for Velox/Presto**: Should we investigate CUDA Unified Virtual Memory (as
   Polars did) to transparently handle >VRAM datasets before building complex fallback
   mechanisms?

2. **Velox operator registration model**: Currently cuDF operators replace CPU operators
   globally. Can we maintain both registrations and select at runtime per-operator?

3. **Data format at GPU↔CPU boundaries**: Arrow columnar format is the natural transfer
   medium. What is the overhead of Velox vectors ↔ Arrow ↔ cuDF columns conversion at
   each boundary?

4. **Mixed-device worker**: Should a single worker support both GPU and CPU execution
   simultaneously (different pipelines on different devices), or should device assignment
   be per-worker?

5. **Testing strategy**: How do we test GPU fallback paths in CI without GPU hardware?
   Mock cuDF backends? CPU-simulated GPU execution?

6. **Interaction with spilling**: Velox already supports spill-to-disk for memory
   pressure. How does GPU memory pressure interact with the spilling framework?

---

## 8. References

- [Adaptive Exchange Framework for Presto](ADAPTIVE_EXCHANGE_FRAMEWORK.md) — Buffer +
  replay + hold signal primitives
- [Polars GPU Engine Release](https://pola.rs/posts/gpu-engine-release/) — Tightly-coupled
  IR approach, inspector-executor design, static CPU fallback
- [Polars GPU Support Docs](https://docs.pola.rs/user-guide/gpu-support/) — Current GPU
  engine capabilities and limitations
- [cuDF Issue #16835](https://github.com/rapidsai/cudf/issues/16835) — OOM fallback
  discussion (closed: UVM + streaming engine instead)
- [Polars UVM Blog Post](https://pola.rs/posts/uvm-larger-than-ram-gpu/) — Unified
  Virtual Memory for >VRAM workloads
- [RAPIDS cuDF Polars Engine](https://rapids.ai/polars-gpu-engine/) — Architecture overview
- Presto cuDF integration: `presto-native-execution/presto_cpp/main/PrestoServer.cpp`
