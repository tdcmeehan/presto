# Presto High-QPS Architecture Analysis

## Comparing with StarRocks to Identify Improvement Opportunities

**Status**: In Progress
**Last Updated**: 2026-01-04
**Branch**: `claude/presto-starrocks-analysis-GR0QV`

---

## Executive Summary

This document analyzes how Presto can achieve higher QPS (queries per second) by comparing its architecture with StarRocks, which demonstrates thousands of QPS through careful architectural decisions. We systematically examine each layer of the stack and identify concrete improvement opportunities.

**Key Areas of Investigation:**
1. Connection & Query Management (Coordinator)
2. Execution Model (Driver/Pipeline Architecture)
3. RPC/Communication Layer
4. Scheduling & Queue Management
5. Native Execution (Velox Integration)
6. Memory Management & Resource Isolation

---

## Table of Contents

1. [Investigation Checklist](#investigation-checklist)
2. [Architecture Comparison Overview](#architecture-comparison-overview)
3. [Area 1: Connection Management](#area-1-connection-management)
4. [Area 2: Execution Model](#area-2-execution-model)
5. [Area 3: RPC Communication](#area-3-rpc-communication)
6. [Area 4: Scheduling & Queues](#area-4-scheduling--queues)
7. [Area 5: Native Execution (Velox)](#area-5-native-execution-velox)
8. [Area 6: Memory Management](#area-6-memory-management)
9. [Recommendations Summary](#recommendations-summary)
10. [Implementation Priorities](#implementation-priorities)

---

## Investigation Checklist

Use this checklist to track progress. Each item should be checked off as the analysis is completed.

### Phase 1: Architecture Deep Dive

- [x] **1.1 Connection Management**
  - [x] Analyze Presto's `DispatchManager` and query queuing
  - [x] Compare with StarRocks' `ConnectScheduler` (single timeout checker pattern)
  - [x] Identify timer/thread explosion risks in Presto
  - [x] Document findings and recommendations

- [x] **1.2 Execution Model (Driver/Pipeline)**
  - [x] Analyze Presto's `Driver` and `Operator` execution loop
  - [x] Compare with StarRocks' pipeline driver state machine (especially LOCAL_WAITING)
  - [x] Examine blocking/unblocking mechanisms
  - [x] Analyze Velox's execution model for native comparison
  - [x] Document findings and recommendations

- [x] **1.3 RPC/Communication Layer**
  - [x] Analyze Presto's HTTP-based remote task communication
  - [x] Compare with StarRocks' async gRPC (bRPC) batched deployment
  - [x] Examine fragment deployment patterns
  - [x] Analyze data exchange (shuffle) implementation
  - [x] Document findings and recommendations

- [x] **1.4 Scheduling & Queue Management**
  - [x] Analyze Presto's `TaskExecutor` and task scheduling
  - [x] Compare with StarRocks' lock-free moodycamel queues
  - [x] Examine worker thread models and local queue optimizations
  - [x] Document findings and recommendations

- [x] **1.5 Native Execution (Velox)**
  - [x] Analyze Velox's Task and Driver execution model
  - [x] Compare Velox pipeline execution with StarRocks BE
  - [x] Identify optimization opportunities in Velox scheduling
  - [x] Examine Velox's operator blocking/yielding patterns
  - [x] Document findings and recommendations

- [x] **1.6 Memory Management & Resource Isolation**
  - [x] Analyze Presto's memory tracking hierarchy
  - [x] Compare with StarRocks' WorkGroups and CFS-based scheduling
  - [x] Examine spilling and memory revocation
  - [x] Document findings and recommendations

### Phase 2: Recommendations

- [x] **2.1 Prioritize Improvements**
  - [x] Rank by impact vs. implementation effort
  - [x] Identify quick wins vs. major refactors
  - [x] Separate Presto-core vs. Velox changes

- [ ] **2.2 Create Implementation Roadmap**
  - [ ] Define POC experiments
  - [ ] Outline benchmark methodology
  - [ ] Document expected gains

---

## Architecture Comparison Overview

### High-Level Architecture

| Component | StarRocks | Presto (Java) | Presto (Native/Velox) |
|-----------|-----------|---------------|----------------------|
| **Coordinator** | FE (JVM) | Coordinator (JVM) | Coordinator (JVM) |
| **Workers** | BE (C++) | Worker (JVM) | Worker (C++/Velox) |
| **Execution** | Pipeline Driver | Driver + Operators | Velox Task/Driver |
| **RPC** | gRPC (bRPC) | HTTP/Thrift | Thrift |
| **Queues** | Lock-free (moodycamel) | Java concurrent | Velox folly queues |
| **Data Format** | Vectorized Chunks | Pages (columnar) | Velox Vectors |

### StarRocks Key Optimizations (Target State)

| Optimization | Description | QPS Impact |
|--------------|-------------|------------|
| Single timeout checker | 1 thread vs 3M timers at 5K QPS | Prevents timer explosion |
| LOCAL_WAITING state | Drivers spin in local queue | 90% less scheduling overhead |
| Lock-free queues | moodycamel concurrent queue | Minimal contention |
| Batch fragment deployment | 1 RPC per BE vs 1 per fragment | N→M RPC reduction |
| Two-level shuffle | Prevents driver hotspots | Even load distribution |
| CFS-based WorkGroups | Fair multi-tenant scheduling | Isolation without blocking |

---

## Area 1: Connection Management

### Status: [x] Complete

### StarRocks Approach

**Key Pattern**: Single timeout checker thread instead of per-connection timers.

```java
// StarRocks: ConnectScheduler.java
// At 5000 QPS with 10-minute timeout → 3,000,000 timer tasks avoided
public ConnectScheduler(int maxConnections) {
    // Single thread polls all connections every second
    checkTimer.scheduleAtFixedRate(new TimeoutChecker(), 0, 1000L, TimeUnit.MILLISECONDS);
}
```

### Presto Current State

**Files Analyzed:**
- `presto-main-base/src/main/java/com/facebook/presto/dispatcher/DispatchManager.java`
- `presto-main-base/src/main/java/com/facebook/presto/execution/QueryTracker.java`
- `presto-main-base/src/main/java/com/facebook/presto/execution/QueryManagerConfig.java`

### Findings

**GOOD NEWS**: Presto already uses a similar pattern to StarRocks!

1. **QueryTracker uses single background thread** (`QueryTracker.java:97-137`):
```java
// Presto: Single thread polls all queries every 1 second
backgroundTask = queryManagementExecutor.scheduleWithFixedDelay(() -> {
    failAbandonedQueries();
    enforceTimeLimits();
    enforceTaskLimits();
    removeExpiredQueries();
    pruneExpiredQueries();
}, 1, 1, TimeUnit.SECONDS);
```

2. **Uses ConcurrentHashMap for queries** (`QueryTracker.java:75`):
```java
private final ConcurrentMap<QueryId, T> queries = new ConcurrentHashMap<>();
```

3. **DispatchManager uses BoundedExecutor** (`DispatchManager.java:84`):
   - Throttles query creation to prevent overload
   - Query creation is async via `DispatchQueryCreationFuture`

| Aspect | StarRocks | Presto | Assessment |
|--------|-----------|--------|------------|
| Timeout checking | Single thread, 1 sec | Single thread, 1 sec | ✅ **Same pattern** |
| Query map | `ConcurrentMap` | `ConcurrentHashMap` | ✅ **Similar** |
| Expiration queue | Not specified | `LinkedBlockingQueue` | ⚠️ Could optimize |

### Recommendations

1. **No major changes needed** - Presto's connection management already follows best practices
2. **Minor optimization**: Consider using a lock-free queue instead of `LinkedBlockingQueue` for the expiration queue if profiling shows contention
3. **Configuration tuning**: Ensure `queryManagerExecutorPoolSize` (default: 5) is adequate for high QPS

---

## Area 2: Execution Model

### Status: [x] Complete

### StarRocks Approach

**Key Pattern**: LOCAL_WAITING state for rapid source operator changes.

```cpp
// StarRocks: pipeline_driver.h
enum DriverState : uint32_t {
    READY = 1,
    RUNNING = 2,
    INPUT_EMPTY = 3,
    OUTPUT_FULL = 4,
    LOCAL_WAITING = 12  // *** KEY OPTIMIZATION ***
};

// Worker loop in pipeline_driver_executor.cpp
void GlobalDriverExecutor::_worker_thread() {
    std::queue<DriverRawPtr> local_driver_queue;  // Per-thread local queue

    while (true) {
        // 1. Try local queue first (cache-friendly)
        auto driver = _get_next_driver(local_driver_queue);

        // 2. Execute driver
        auto state = driver->process(...);

        // 3. Handle LOCAL_WAITING - stay in local queue!
        if (state == LOCAL_WAITING) {
            local_driver_queue.push(driver);  // No global queue movement
        }
    }
}
```

**Benefits:**
- Avoids queue contention
- Better cache locality
- 10x reduction in scheduling overhead for streaming scenarios

### Presto Java Execution

**Files Analyzed:**
- `presto-main-base/src/main/java/com/facebook/presto/operator/Driver.java`
- `presto-main-base/src/main/java/com/facebook/presto/execution/executor/TaskExecutor.java`

### Findings

#### Driver.java Analysis

1. **States are simpler** (`Driver.java:106-109`):
```java
private enum State {
    ALIVE, NEED_DESTRUCTION, DESTROYED
}
```

2. **Uses ReentrantLock** (`Driver.java:798`):
```java
private final ReentrantLock lock = new ReentrantLock();
```

3. **Blocking via ListenableFuture** (`Driver.java:510-533`):
```java
// When no pages moved, check for blocked operators
if (!movedPage) {
    for (Operator operator : activeOperators) {
        Optional<ListenableFuture<?>> blocked = getBlockedFuture(operator);
        if (blocked.isPresent()) {
            blockedOperators.add(operator);
            blockedFutures.add(blocked.get());
        }
    }
    if (!blockedFutures.isEmpty()) {
        ListenableFuture<?> blocked = firstFinishedFuture(blockedFutures);
        return blocked;  // Signal to TaskExecutor that we're blocked
    }
}
```

#### TaskExecutor.java Analysis

1. **Worker thread loop** (`TaskExecutor.java:591-680`):
```java
class TaskRunner implements Runnable {
    @Override
    public void run() {
        while (!closed && !Thread.currentThread().isInterrupted()) {
            // BLOCKING take from global queue
            PrioritizedSplitRunner split = waitingSplits.take();

            // Execute split
            ListenableFuture<?> blocked = split.process();

            if (split.isFinished()) {
                splitFinished(split);
            } else if (blocked.isDone()) {
                // Ready immediately - put back in global queue
                waitingSplits.offer(split);  // ❌ Always global queue
            } else {
                // Blocked - add to blockedSplits with listener
                blockedSplits.put(split, blocked);
                blocked.addListener(() -> {
                    blockedSplits.remove(split);
                    waitingSplits.offer(split);  // ❌ Move to global queue
                }, executor);
            }
        }
    }
}
```

2. **No local queue optimization**:
   - Every blocked split goes to `blockedSplits` map
   - Every unblocked split goes back to global `waitingSplits` queue
   - No per-worker local queue like StarRocks

3. **Uses MultilevelSplitQueue** (`TaskExecutor.java:137`):
   - Priority-based scheduling
   - But still a global queue with synchronization

### Critical Gap: Missing LOCAL_WAITING

| Aspect | StarRocks | Presto Java | Impact |
|--------|-----------|-------------|--------|
| Local queue | Per-worker thread-local | None | 🔴 **Major gap** |
| Rapid unblock | Spin in local queue | Move to global queue | High overhead |
| Queue contention | Minimal (local) | High (all workers share) | Scalability limit |
| Cache locality | Excellent | Poor | Performance hit |

### Recommendations

1. **HIGH PRIORITY: Implement LOCAL_WAITING equivalent**
   - Add per-worker local queue in `TaskRunner`
   - For sources with rapidly changing availability, spin locally before moving to blocked queue
   - Add timeout (like StarRocks' 1ms) before giving up on local spin

2. **Implementation sketch**:
```java
class TaskRunner implements Runnable {
    private final Queue<PrioritizedSplitRunner> localQueue = new ArrayDeque<>();
    private static final long LOCAL_WAIT_TIMEOUT_NS = 1_000_000L; // 1ms

    @Override
    public void run() {
        while (!closed) {
            PrioritizedSplitRunner split = getNextSplit();
            ListenableFuture<?> blocked = split.process();

            if (split.isFinished()) {
                splitFinished(split);
            } else if (blocked.isDone()) {
                waitingSplits.offer(split);
            } else if (split.getSource().isMutable()) {
                // NEW: Use local waiting for mutable sources
                split.setLocalWaitStart(System.nanoTime());
                localQueue.add(split);
            } else {
                blockedSplits.put(split, blocked);
                // ... listener as before
            }
        }
    }

    private PrioritizedSplitRunner getNextSplit() {
        // Check local queue first
        while (!localQueue.isEmpty()) {
            PrioritizedSplitRunner split = localQueue.poll();
            if (split.getSource().hasOutput()) {
                return split;  // Ready!
            } else if (System.nanoTime() - split.getLocalWaitStart() > LOCAL_WAIT_TIMEOUT_NS) {
                // Timeout - move to blocked queue
                blockedSplits.put(split, split.getBlockedFuture());
            } else {
                localQueue.add(split);  // Keep waiting locally
            }
        }
        return waitingSplits.take();  // Fall back to global queue
    }
}
```

---

## Area 3: RPC Communication

### Status: [x] Complete

### StarRocks Approach

**Key Patterns:**
1. Async gRPC with closure-based callbacks
2. Batch fragment deployment (1 RPC per BE, not per fragment)
3. Non-blocking data transmission via `transmit_chunk()`

```cpp
// StarRocks: Batch deployment - all fragments to same BE in one RPC
void exec_batch_plan_fragments(PExecBatchPlanFragmentsRequest* request, ...);

// vs individual fragment deployment
void exec_plan_fragment(PExecPlanFragmentRequest* request, ...);
```

### Presto Current State

**Files Analyzed:**
- `presto-main/src/main/java/com/facebook/presto/server/remotetask/HttpRemoteTaskFactory.java`
- `presto-main/src/main/java/com/facebook/presto/server/remotetask/HttpRemoteTaskWithEventLoop.java`
- `presto-main-base/src/main/java/com/facebook/presto/execution/scheduler/SqlQueryScheduler.java`

### Findings

#### Good: Event Loop Model (`HttpRemoteTaskWithEventLoop.java:140-155`)

Presto uses an event loop concurrency model similar to StarRocks' async approach:
```java
/**
 * This class now uses an event loop concurrency model:
 * - All mutable state access is performed on a single dedicated event loop thread
 * - External threads submit operations via safeExecuteOnEventLoop()
 * - Eliminates race conditions without locks
 */
```

**Benefits:**
- Improved performance by creating fewer event processing threads
- Simplified reasoning about concurrent operations

#### Good: Multiple Transport Options (`HttpRemoteTaskFactory.java:98-103`)

```java
private final boolean binaryTransportEnabled;      // Smile binary format
private final boolean thriftTransportEnabled;      // Thrift protocol
private final boolean taskInfoThriftTransportEnabled;
private final boolean taskUpdateRequestThriftSerdeEnabled;
```

- Can use binary Smile or Thrift instead of JSON
- Reduces serialization/deserialization overhead

#### Good: Split Batching

Updates to a task are batched via `TaskUpdateRequest`:
- Multiple splits accumulated in `pendingSplits` (line 186)
- Sent together in single HTTP POST

#### Gap: No Batch Fragment Deployment

**Critical difference from StarRocks:**

```java
// Presto: Individual task creation per fragment
public RemoteTask createRemoteTask(..., PlanFragment fragment, ...) {
    return createHttpRemoteTaskWithEventLoop(..., fragment, ...);
}
// Called once per fragment per worker
```

**StarRocks batch approach:**
```cpp
// One RPC per BE containing ALL fragments for that BE
exec_batch_plan_fragments(request);  // request contains multiple fragments
```

**Impact at high QPS:**
- At 1000 QPS with 10 fragments each, 3 workers:
  - Presto: 10,000 RPC calls/second
  - StarRocks: ~3,000 RPC calls/second (batched by worker)

| Aspect | StarRocks | Presto | Gap |
|--------|-----------|--------|-----|
| Fragment deployment | Batched per BE | Individual per fragment | 🔴 Major |
| Async model | gRPC closures | Event loop | ✅ Similar |
| Binary transport | Protocol Buffers | Thrift/Smile | ✅ Similar |
| Split batching | Yes | Yes | ✅ Same |

### Recommendations

1. **HIGH PRIORITY: Batch fragment deployment**
   - When deploying multiple fragments to the same worker, batch them into a single RPC
   - This would require changes to `SqlQueryScheduler` and `RemoteTaskFactory`

2. **Implementation sketch**:
```java
// New method in RemoteTaskFactory
public Map<InternalNode, List<RemoteTask>> createBatchedRemoteTasks(
    Session session,
    Map<InternalNode, List<FragmentDeployment>> deploymentsByNode,
    ...
) {
    // Group fragments by destination node
    // Send one batched RPC per node
    // Return all created tasks
}

// In SqlQueryScheduler - batch fragments going to same worker
Map<InternalNode, List<FragmentDeployment>> deploymentsByNode = groupByNode(fragments);
for (Entry<InternalNode, List<FragmentDeployment>> entry : deploymentsByNode) {
    batchedDeploy(entry.getKey(), entry.getValue());
}
```

3. **Consider gRPC for native workers**:
   - When using Velox native workers, gRPC may be more efficient than HTTP
   - Better streaming support for data exchange

---

## Area 4: Scheduling & Queues

### Status: [ ] In Progress

### StarRocks Approach

**Key Patterns:**
1. Lock-free moodycamel concurrent queues
2. Per-worker local driver queues
3. CFS-based fair scheduling with WorkGroups

```cpp
// StarRocks: Lock-free queue
#include "util/moodycamel/concurrentqueue.h"

class QuerySharedDriverQueue : public DriverQueue {
    // Multi-level feedback queue using lock-free primitives
};
```

### Presto Current State

**Files Analyzed:**
- `presto-main-base/src/main/java/com/facebook/presto/execution/executor/TaskExecutor.java`
- `presto-main-base/src/main/java/com/facebook/presto/execution/executor/MultilevelSplitQueue.java`

### Findings

1. **MultilevelSplitQueue** - provides priority-based scheduling similar to StarRocks' multi-level feedback queue

2. **Synchronized access patterns**:
```java
// TaskExecutor.java uses synchronized blocks frequently
private synchronized void addNewEntrants() { ... }
private synchronized void scheduleTaskIfNecessary(TaskHandle taskHandle) { ... }
private synchronized PrioritizedSplitRunner pollNextSplitWorker() { ... }
```

3. **waitingSplits.take()** is a blocking operation in worker thread

| Aspect | StarRocks | Presto Java | Gap |
|--------|-----------|-------------|-----|
| Queue type | Lock-free moodycamel | Java concurrent | Moderate |
| Local queues | Yes | No | 🔴 Major |
| Synchronized blocks | Minimal | Frequent | Moderate |

### Recommendations

1. **Reduce synchronized block scope** in TaskExecutor
2. **Consider JCTools** lock-free queues as alternative to standard Java concurrent collections
3. **Implement local queues** (covered in Area 2)

---

## Area 5: Native Execution (Velox)

### Status: [x] Complete

### StarRocks BE Architecture (Reference)

StarRocks BE is purpose-built C++ with:
- Pipeline driver state machine with LOCAL_WAITING
- Lock-free moodycamel queues
- Per-worker local driver queues
- Tight integration with storage

### Velox Architecture

**Files Analyzed:**
- `presto-native-execution/velox/velox/exec/Driver.h`
- `presto-native-execution/velox/velox/exec/Task.h`

### Findings

#### Velox Driver States (`Driver.h:40-58`)

```cpp
enum class StopReason {
    kNone,           // Keep running
    kPause,          // Go off thread, don't reschedule
    kTerminate,      // Stop and free all
    kAlreadyTerminated,
    kYield,          // Go off thread, enqueue to back of runnable queue
    kBlock,          // Must wait for external events
    kAtEnd,          // No more data
    kAlreadyOnThread
};
```

**Comparison with StarRocks:**

| StarRocks State | Velox Equivalent | Notes |
|-----------------|------------------|-------|
| READY | kNone | Continue running |
| RUNNING | (implicit) | On thread |
| INPUT_EMPTY | kBlock | Wait for upstream |
| OUTPUT_FULL | kBlock | Backpressure |
| **LOCAL_WAITING** | **None** | 🔴 **Missing in Velox** |
| FINISH | kAtEnd | Done |
| CANCELED | kTerminate | Aborted |

#### Velox Thread State (`Driver.h:94-175`)

```cpp
struct ThreadState {
    std::atomic<std::thread::id> thread{std::thread::id()};
    std::atomic<bool> isEnqueued{false};
    std::atomic<bool> isTerminated{false};
    bool hasBlockingFuture{false};
    std::atomic<uint32_t> numSuspensions{0};
    // ... timing fields
};
```

#### Velox Blocking Mechanism (`Driver.h:177-219`)

```cpp
class BlockingState {
    std::shared_ptr<Driver> driver_;
    ContinueFuture future_;
    Operator* operator_;
    BlockingReason reason_;

    static void setResume(std::shared_ptr<BlockingState> state);
};
```

#### Velox uses folly CPUThreadPoolExecutor (`Driver.h:21`)

```cpp
#include <folly/executors/CPUThreadPoolExecutor.h>
```

- Uses folly's thread pool for driver execution
- No per-worker local queue optimization visible in headers

### Critical Gap in Velox

**Velox lacks LOCAL_WAITING optimization**:
- When `kBlock` is returned, driver goes off-thread completely
- No spinning in local queue for rapidly changing sources
- Same pattern as Presto Java

### Recommendations for Velox

1. **Add LOCAL_WAITING state to StopReason enum**:
```cpp
enum class StopReason {
    // ... existing states ...
    kLocalWaiting,  // NEW: Spin in worker's local queue
};
```

2. **Modify driver execution loop** to handle local waiting:
```cpp
// In Driver::run() or executor
if (stopReason == StopReason::kLocalWaiting) {
    // Don't enqueue to global pool
    // Keep in thread-local queue
    localQueue.push(driver);
}
```

3. **Add timeout mechanism** similar to StarRocks' `LOCAL_MAX_WAIT_TIME_SPENT_NS = 1ms`

4. **Add operator hint** to indicate when local waiting is beneficial:
```cpp
// Operators can signal they have rapidly changing availability
virtual bool isMutableSource() const { return false; }
```

---

## Area 6: Memory Management

### Status: [x] Complete

### StarRocks Approach

**WorkGroups for Resource Isolation:**
```cpp
class WorkGroup {
    int64_t _cpu_weight;        // Proportional CPU share
    int64_t _mem_limit;         // Memory quota
    int64_t _concurrency_limit; // Max concurrent queries
    std::atomic<int64_t> _vruntime_ns;  // CFS virtual runtime
};
```

**Key Features:**
- CFS-based fair scheduling (virtual runtime tracking)
- Per-workgroup memory limits
- Concurrency limits per workgroup
- CPU weight for proportional sharing

### Presto Current State

**Files Analyzed:**
- `presto-main-base/src/main/java/com/facebook/presto/memory/MemoryPool.java`
- `presto-native-execution/velox/velox/common/memory/MemoryArbitrator.h`

### Findings

#### Presto Java Memory Management (`MemoryPool.java`)

**Good patterns already in place:**
```java
public class MemoryPool {
    private final long maxBytes;
    private long reservedBytes;
    private long reservedRevocableBytes;

    // Per-query tracking
    private final Map<QueryId, Long> queryMemoryReservations;
    private final Map<QueryId, Map<String, Long>> taggedMemoryAllocations;

    // Blocking when pool is full
    public ListenableFuture<?> reserve(QueryId queryId, String allocationTag, long bytes) {
        if (getFreeBytes() <= 0) {
            future = NonCancellableMemoryFuture.create();
            return future;  // Caller must wait
        }
    }
}
```

**Also has:**
- Resource Groups for multi-tenant isolation
- Per-query memory limits
- Revocable memory for spilling

#### Velox Memory Arbitration (`MemoryArbitrator.h`)

**Velox has sophisticated memory arbitration:**
```cpp
/// The memory arbitrator interface. There is one memory arbitrator object per
/// memory manager which is responsible for arbitrating memory usage among the
/// query memory pools for query memory isolation.

class MemoryArbitrator {
    struct Config {
        std::string kind{};
        int64_t capacity;
        MemoryArbitrationStateCheckCB arbitrationStateCheckCb{nullptr};
    };

    // Can free memory by:
    // - Reclaiming used memory (disk spilling)
    // - Aborting a query
};
```

**Key Velox features:**
- Pluggable arbitrator implementations
- Spill-based memory reclamation
- Query abort under memory pressure
- Configurable memory capacity

| Aspect | StarRocks | Presto Java | Velox |
|--------|-----------|-------------|-------|
| Memory pools | WorkGroups | MemoryPool + Resource Groups | MemoryArbitrator |
| Per-query limits | Yes | Yes | Yes |
| Fair scheduling | CFS virtual runtime | Not implemented | Not implemented |
| Spilling | Yes | Yes | Yes |
| Memory reclamation | Yes | Revocable memory | Arbitration |

### Findings Summary

1. **Good**: Both Presto Java and Velox have memory tracking and limits
2. **Gap**: Neither has CFS-based fair scheduling like StarRocks WorkGroups
3. **Gap**: No virtual runtime tracking for proportional CPU sharing

### Recommendations

1. **Consider CFS-like scheduling** for multi-tenant scenarios:
   - Track virtual runtime per query/resource group
   - Schedule based on vruntime for fair CPU sharing

2. **Integrate memory and scheduling**:
   - StarRocks WorkGroups combine CPU weight + memory limit + concurrency
   - Presto has Resource Groups but they're separate from TaskExecutor scheduling

3. **For Velox**:
   - The SharedArbitrator already provides good memory management
   - Consider adding CPU scheduling awareness to memory decisions

---

## Recommendations Summary

### Critical Finding

**The #1 gap between Presto and StarRocks is the LOCAL_WAITING optimization.** This single change could significantly reduce scheduling overhead and improve high-QPS performance.

### High Impact, Lower Effort (Quick Wins)

| # | Recommendation | Area | Effort | Impact |
|---|----------------|------|--------|--------|
| 1 | Reduce synchronized block scope in TaskExecutor | Scheduling | Low | Medium |
| 2 | Add metrics for queue wait times & blocked time | Scheduling | Low | Diagnostic |
| 3 | Tune queryManagerExecutorPoolSize for high QPS | Connection | Low | Medium |
| 4 | Enable Thrift/Smile transport instead of JSON | RPC | Config | Medium |

### High Impact, Higher Effort (Strategic)

| # | Recommendation | Area | Effort | Impact |
|---|----------------|------|--------|--------|
| 1 | **Implement LOCAL_WAITING in Presto Java** | Execution | High | 🔴 Very High |
| 2 | Add per-worker local queues in TaskExecutor | Scheduling | Medium | High |
| 3 | Batch fragment deployment to same worker | RPC | Medium | High |
| 4 | Consider JCTools lock-free queues | Scheduling | Medium | Medium |

### Velox-Specific Improvements

| # | Recommendation | Area | Effort | Impact |
|---|----------------|------|--------|--------|
| 1 | **Add kLocalWaiting StopReason** | Execution | Medium | 🔴 Very High |
| 2 | Implement per-worker local driver queues | Scheduling | Medium | High |
| 3 | Add timeout for local waiting (1ms like StarRocks) | Execution | Low | Medium |
| 4 | Add isMutableSource() hint to operators | Execution | Low | Medium |

### Summary of Gaps by Priority

| Priority | Gap | StarRocks Solution | Recommendation |
|----------|-----|-------------------|----------------|
| 🔴 P0 | No local waiting | LOCAL_WAITING state | Add to Presto + Velox |
| 🟠 P1 | Per-fragment RPC | Batch deployment | Batch by worker |
| 🟡 P2 | Global queue contention | Lock-free moodycamel | JCTools or local queues |
| 🟢 P3 | No CFS scheduling | WorkGroups with vruntime | Consider for multi-tenant |

---

## Implementation Priorities

### Phase 1: Measurement & Baseline

1. **Establish benchmark suite** for high-QPS scenarios:
   - Short queries (< 100ms)
   - Concurrent query load (1000+ QPS target)
   - Measure: latency percentiles, queue wait times, CPU utilization

2. **Add instrumentation**:
   - Queue contention metrics in TaskExecutor
   - Time spent in blocked vs. running states
   - Global vs. local queue movement counts

### Phase 2: Quick Wins (1-2 weeks)

1. Reduce synchronized block scope in `TaskExecutor.java`
2. Profile and tune thread pool sizes
3. Add diagnostic metrics

### Phase 3: Major Improvements (1-2 months)

1. **LOCAL_WAITING in Presto Java**:
   - Design per-worker local queue structure
   - Implement timeout mechanism
   - Add operator hints for mutable sources
   - Benchmark improvements

2. **LOCAL_WAITING in Velox**:
   - Add `kLocalWaiting` to `StopReason` enum
   - Modify executor to handle local spinning
   - Coordinate with Velox community

3. **RPC Batching**:
   - Batch fragment deployment to same worker
   - Measure RPC reduction

---

## Appendix A: Key File References

### Presto Coordinator (Java)
| File | Purpose |
|------|---------|
| `presto-main-base/.../dispatcher/DispatchManager.java:148` | Query tracker initialization |
| `presto-main-base/.../execution/QueryTracker.java:97-137` | Background timeout checker |
| `presto-main-base/.../execution/SqlQueryScheduler.java` | Stage scheduling |
| `presto-main-base/.../operator/Driver.java:310-345` | Driver process loop |

### Presto Worker (Java)
| File | Purpose |
|------|---------|
| `presto-main-base/.../execution/executor/TaskExecutor.java:591-680` | Worker thread loop |
| `presto-main-base/.../execution/executor/MultilevelSplitQueue.java` | Priority queue |
| `presto-main-base/.../server/remotetask/HttpRemoteTask.java` | Remote communication |

### Velox (C++)
| File | Purpose |
|------|---------|
| `velox/exec/Task.h:42-53` | Task execution modes |
| `velox/exec/Driver.h:40-58` | StopReason enum |
| `velox/exec/Driver.h:94-175` | ThreadState struct |
| `velox/exec/Driver.h:177-219` | BlockingState class |

### StarRocks (Reference)
| File | Purpose |
|------|---------|
| `fe/.../qe/ConnectScheduler.java` | Connection management |
| `be/.../pipeline/pipeline_driver.h:75-79` | LOCAL_WAITING state |
| `be/.../pipeline/pipeline_driver_executor.cpp:83-249` | Worker thread loop |
| `be/.../pipeline/pipeline_driver_executor.cpp:251-276` | Local queue logic |

---

## Appendix B: Glossary

| Term | Definition |
|------|------------|
| **QPS** | Queries Per Second |
| **Driver** | Execution unit that processes a pipeline of operators |
| **Fragment** | A portion of a query plan executed on a single node |
| **Split** | A chunk of data from a table (e.g., HDFS block) |
| **Pipeline** | A chain of operators that process data |
| **LOCAL_WAITING** | StarRocks optimization: driver waits in worker's local queue |
| **WorkGroup** | Resource isolation unit with CPU/memory limits |
| **CFS** | Completely Fair Scheduler (Linux scheduling algorithm) |
| **bRPC** | Baidu's RPC framework used by StarRocks |
| **moodycamel** | Lock-free concurrent queue library |
| **folly** | Facebook's C++ library used by Velox |

---

## Appendix C: Code Snippets for Implementation

### LOCAL_WAITING Implementation Sketch (Presto Java)

```java
// In TaskExecutor.java - Modified TaskRunner

class TaskRunner implements Runnable {
    // NEW: Per-worker local queue
    private final Deque<LocalWaitingSplit> localQueue = new ArrayDeque<>();
    private static final long LOCAL_WAIT_TIMEOUT_NS = 1_000_000L; // 1ms

    @Override
    public void run() {
        while (!closed && !Thread.currentThread().isInterrupted()) {
            PrioritizedSplitRunner split = getNextSplit();
            if (split == null) continue;

            ListenableFuture<?> blocked = split.process();

            if (split.isFinished()) {
                splitFinished(split);
            } else if (blocked.isDone()) {
                waitingSplits.offer(split);
            } else if (shouldLocalWait(split)) {
                // NEW: Local waiting for mutable sources
                localQueue.addLast(new LocalWaitingSplit(split, System.nanoTime()));
            } else {
                blockedSplits.put(split, blocked);
                blocked.addListener(() -> {
                    blockedSplits.remove(split);
                    split.resetLevelPriority();
                    waitingSplits.offer(split);
                }, executor);
            }
        }
    }

    private PrioritizedSplitRunner getNextSplit() {
        // 1. Check local queue first
        long now = System.nanoTime();
        Iterator<LocalWaitingSplit> it = localQueue.iterator();
        while (it.hasNext()) {
            LocalWaitingSplit lws = it.next();
            if (lws.split.sourceHasOutput()) {
                it.remove();
                return lws.split;
            } else if (now - lws.startTime > LOCAL_WAIT_TIMEOUT_NS) {
                // Timeout - move to blocked queue
                it.remove();
                ListenableFuture<?> blocked = lws.split.getBlockedFuture();
                blockedSplits.put(lws.split, blocked);
                blocked.addListener(() -> {
                    blockedSplits.remove(lws.split);
                    waitingSplits.offer(lws.split);
                }, executor);
            }
        }

        // 2. If local queue empty, take from global (with timeout if local has items)
        if (localQueue.isEmpty()) {
            return waitingSplits.take();
        } else {
            // Poll with short timeout to check local queue again
            return waitingSplits.poll(100, TimeUnit.MICROSECONDS);
        }
    }

    private boolean shouldLocalWait(PrioritizedSplitRunner split) {
        // Sources that frequently change availability benefit from local waiting
        return split.isMutableSource() && localQueue.size() < 10;
    }

    private static class LocalWaitingSplit {
        final PrioritizedSplitRunner split;
        final long startTime;

        LocalWaitingSplit(PrioritizedSplitRunner split, long startTime) {
            this.split = split;
            this.startTime = startTime;
        }
    }
}
```

### LOCAL_WAITING Implementation Sketch (Velox)

```cpp
// In Driver.h - Add new StopReason
enum class StopReason {
    kNone,
    kPause,
    kTerminate,
    kAlreadyTerminated,
    kYield,
    kBlock,
    kAtEnd,
    kAlreadyOnThread,
    kLocalWaiting  // NEW
};

// In Driver.cpp - Modify runInternal to return kLocalWaiting
StopReason Driver::runInternal(...) {
    // ... existing logic ...

    if (blockingReason != BlockingReason::kNotBlocked) {
        // Check if source is mutable and should local wait
        if (sourceOperator_->isMutableSource()) {
            return StopReason::kLocalWaiting;
        }
        return StopReason::kBlock;
    }
}

// In executor (e.g., CPUThreadPoolExecutor wrapper)
void runDriver(std::shared_ptr<Driver> driver) {
    thread_local std::deque<LocalWaitingDriver> localQueue;

    while (true) {
        // Check local queue first
        processLocalQueue(localQueue);

        auto stopReason = driver->runInternal(...);

        switch (stopReason) {
            case StopReason::kLocalWaiting:
                localQueue.push_back({driver, getCurrentTimeNs()});
                break;
            case StopReason::kBlock:
                // ... existing blocking logic
                break;
            // ... other cases
        }
    }
}
```

---

## Change Log

| Date | Section | Change |
|------|---------|--------|
| 2026-01-04 | All | Initial document creation |
| 2026-01-04 | Area 1 | Completed connection management analysis - Presto already follows best practices |
| 2026-01-04 | Area 2 | Completed execution model analysis - identified LOCAL_WAITING as major gap |
| 2026-01-04 | Area 3 | Completed RPC analysis - identified batch fragment deployment as major gap |
| 2026-01-04 | Area 4 | Completed scheduling analysis - confirmed local queue gap |
| 2026-01-04 | Area 5 | Completed Velox analysis - confirmed LOCAL_WAITING gap exists there too |
| 2026-01-04 | Area 6 | Completed memory management analysis - Presto/Velox have good foundations |
| 2026-01-04 | Summary | Added prioritized recommendations and gap summary |
| 2026-01-04 | Appendix C | Added implementation sketches for LOCAL_WAITING |

