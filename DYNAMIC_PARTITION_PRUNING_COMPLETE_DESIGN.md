# Dynamic Partition Pruning - Complete Design Document

## Table of Contents
1. [Executive Summary](#1-executive-summary)
2. [Problem Statement](#2-problem-statement)
3. [Design Principles](#3-design-principles)
4. [System Architecture](#4-system-architecture)
5. [Filter Representation](#5-filter-representation)
6. [Serialization Protocol](#6-serialization-protocol)
7. [Component Design](#7-component-design)
8. [Execution Flow](#8-execution-flow)
9. [Performance Optimizations](#9-performance-optimizations)
10. [Implementation Plan](#10-implementation-plan)
11. [Testing Strategy](#11-testing-strategy)
12. [Configuration](#12-configuration)
13. [Risk Analysis](#13-risk-analysis)
14. [Performance Benchmarks](#14-performance-benchmarks)

---

## 1. Executive Summary

Dynamic Partition Pruning (DPP) is a runtime optimization technique that extracts filter predicates from hash join build sides and applies them to reduce data scanned on probe sides. This design presents a comprehensive approach for implementing DPP in Presto, **incorporating proven patterns from Trino's production implementation**, with the following key characteristics:

- **Version-based long-polling protocol** (inspired by Trino's DynamicFiltersFetcher)
- **Conservative 0s default timeout** (matching Trino's opt-in approach)
- **TupleDomain-based filters** with discrete values or ranges (reusing existing infrastructure)
- **C++-optimized design** for Velox native execution with Java coordinator
- **Queue-depth aware scheduling** for adaptive split generation
- **Multi-level pruning** from partitions down to individual rows

Expected impact:
- **60-90% reduction** in data scanned for selective joins (partition/file pruning)
- **50-80% improvement** in query latency
- **< 5ms overhead** from filter generation and distribution
- **< 100KB memory** per filter (≤10K values) for filter management
- **Zero latency impact** with default settings (opt-in model)

**Design Philosophy**: Learn from Trino's production experience while adapting for Presto's C++ worker architecture.

---

## 2. Problem Statement

### Current Limitations

Presto currently performs partition pruning only at query planning time using static predicates. This approach misses significant optimization opportunities:

1. **Static-only pruning**: Cannot leverage runtime information from join build sides
2. **Missed opportunities**: Star schema queries with filtered dimension tables scan unnecessary fact table partitions
3. **Cross-system joins**: Cannot optimize when build-side statistics are unavailable at planning time
4. **Resource waste**: Large analytical queries scan data that will ultimately be filtered out by joins

### Opportunity

By extracting filter predicates during hash join execution and applying them to table scans, we can:
- Eliminate unnecessary partition scans based on actual runtime data
- Reduce network I/O, CPU usage, and overall query latency
- Improve resource utilization in multi-tenant environments
- Enable more aggressive join reordering strategies

### Use Cases

1. **Business Intelligence Dashboards**: Date/region filtered queries
2. **Star Schema Analytics**: Fact tables joined with filtered dimensions
3. **Ad-hoc Exploration**: Interactive queries with selective predicates
4. **ETL Pipelines**: Large-scale transformations with filtering joins

---

## 3. Design Principles

### 3.1 Simplicity First
- **Single protocol**: One HTTP-based protocol for C++ workers
- **Unified encoding**: Consistent JSON format throughout
- **Consistent behavior**: Simplified for C++-only deployment

### 3.2 Unified Broadcast and Partitioned Join Handling
- **Both join types benefit**: Broadcast and partitioned joins use same optimizer rule and scheduler waiting
- **Broadcast joins**: All workers produce identical filter, coordinator uses any one (no merge needed)
- **Partitioned joins**: Coordinator collects and merges partial filters using UNION semantics
- **Consistent mechanism**: Single protocol and code path for both, simplifying implementation

### 3.3 C++-Only Design
- **Velox-native**: Design exclusively for C++ execution
- **No Java workers**: Simplified architecture without Java compatibility concerns
- **Performance focus**: Optimized for C++ performance characteristics

### 3.4 Leverage Existing Infrastructure
- **Use Thrift**: Both Java and C++ already have Thrift dependencies
- **Reuse patterns**: Follow existing Presto architectural patterns
- **Minimal changes**: Integrate with existing components where possible

### 3.5 Adaptive Behavior
- **Progressive application**: Apply filters as they become available
- **Automatic degradation**: Fall back gracefully under memory pressure
- **Cost-based decisions**: Enable/disable based on expected benefit

---

## 4. System Architecture

### 4.1 High-Level Overview

```
┌─────────────────────────────────────────────────────────────────────┐
│                      Coordinator (Java)                             │
│  ┌─────────────────┐  ┌──────────────────┐  ┌──────────────────┐ │
│  │ Query Planner   │  │ Filter Registry  │  │ Filter Manager   │ │
│  │ (DPP Rules)     │  │ (Global State)   │  │ (Distribution)   │ │
│  └─────────────────┘  └──────────────────┘  └──────────────────┘ │
└─────────────────────────────────┬───────────────────────────────────┘
                                  │ HTTP + JSON (TupleDomain)
                                  │
                    ┌──────────────▼────────────┐
                    │    C++ Worker (Velox)    │
                    │ ┌──────────────────┐     │
                    │ │ Velox HashBuild  │     │
                    │ │ Filter Extract   │     │
                    │ └──────────────────┘     │
                    │         ↓                 │
                    │   JSON Serialize          │
                    │   (TupleDomain)           │
                    │         ↓                 │
                    │    HTTP Submit            │
                    │ ┌──────────────────┐     │
                    │ │ Velox TableScan  │     │
                    │ │ Filter Apply     │     │
                    │ └──────────────────┘     │
                    └───────────────────────────┘
```

### 4.2 Communication Architecture

```
Coordinator Filter Manager
    ├── Filter Collection
    │   ├── Poll TaskStatus for new filter IDs (lightweight)
    │   ├── Fetch filter data via HTTP GET endpoints
    │   └── Store filters using LocalDynamicFilter for merging
    │
    ├── Filter Merging
    │   ├── LocalDynamicFilter instances per filter ID
    │   ├── Automatic merging via addPartition()
    │   └── Completion detection via isComplete()
    │
    └── Filter Distribution
        ├── JSON serialization (TupleDomain using existing codec)
        ├── HTTP PUT to target workers
        ├── Target resolution using plan metadata
        └── Async distribution for parallelism
```

### 4.3 Eval-Specific Protocol Isolation

Since dynamic filtering is primarily an eval (C++ worker) feature, the communication protocol changes are designed to be **functionally invisible** to Java-only clusters while being **technically universal** in the schema.

#### Protocol Changes Required

The implementation requires two protocol additions (following Trino's proven design):

1. **TaskStatus Enhancement**: Add `dynamicFiltersVersion` field (simple long, inspired by Trino)
2. **HTTP Endpoints**: Add `/v1/task/{taskId}/dynamicfilters` long-polling endpoint

#### Isolation Strategy

The TaskStatus field is marked as optional in Thrift, with Java workers returning default value (0) and C++ workers incrementing the version when filters are created[^taskstatus-changes]. The coordinator only fetches filters when it detects a version increase[^coordinator-version-check].

**Key Isolation Properties**:
- **Java Workers**: Return version 0, no filtering overhead
- **C++ Workers**: Increment version when filters created, enabling coordinator fetch
- **No Configuration Needed**: Behavior is automatic based on worker type
- **Efficient**: Single round-trip per version change

#### HTTP Endpoint Isolation

The dynamic filter endpoint uses **long-polling** and is registered universally but returns empty results for Java workers[^http-endpoint-impl]. This approach provides efficiency for C++ workers while being invisible to Java workers.

#### Backward Compatibility

The protocol changes maintain backward compatibility through optional fields and automatic behavior[^backward-compat-table]. Mixed clusters with both Java and C++ workers operate correctly[^mixed-cluster-verification]. An optional native sidecar plugin config is available for advanced control but not required[^native-sidecar-config].

**Summary**: Protocol changes are technically universal (schema/endpoints everywhere) but functionally isolated (only C++ workers populate/use them), with zero behavior change for Java-only clusters.

---

**Section 4.3 Footnotes:**

[^taskstatus-changes]: TaskStatus field implementation:
    ```java
    // In presto-main-base/src/main/java/com/facebook/presto/execution/TaskStatus.java
    @ThriftStruct
    public class TaskStatus {
        // Existing fields...
        private final long taskInstanceIdLeastSignificantBits;
        private final long taskInstanceIdMostSignificantBits;
        private final TaskState state;

        // NEW: Version number for dynamic filter updates
        private final long dynamicFiltersVersion;

        @JsonProperty
        @JsonInclude(JsonInclude.Include.NON_NULL)
        @ThriftField(value = 22, isOptional = true, requiredness = Requiredness.OPTIONAL)
        public long getDynamicFiltersVersion() {
            return dynamicFiltersVersion;
        }
    }
    ```

    C++ worker increments version:
    ```cpp
    protocol::TaskStatus status;
    status.dynamicFiltersVersion = currentDynamicFiltersVersion_;
    ```

[^coordinator-version-check]: Coordinator version-based fetch logic:
    ```java
    public void processTaskStatus(TaskId taskId, TaskStatus status) {
        long newVersion = status.getDynamicFiltersVersion();
        long currentVersion = getCurrentVersion(taskId);

        // Java workers: version always 0, block skipped
        // C++ workers: version increases when filters added
        if (newVersion > currentVersion) {
            fetchDynamicFilters(taskId, currentVersion);
        }
    }
    ```

[^http-endpoint-impl]: HTTP endpoint implementation with long-polling:
    ```java
    @Path("/v1/task/{taskId}")
    public class TaskResource {
        @GET
        @Path("/dynamicfilters")
        public Response getDynamicFilters(
                @PathParam("taskId") TaskId taskId,
                @HeaderParam("X-Presto-Current-Version") long currentVersion,
                @HeaderParam("X-Presto-Max-Wait") Duration maxWait) {

            // Java workers: returns empty immediately
            if (!hasDynamicFilters(taskId)) {
                return Response.ok(new VersionedDynamicFilters(0L, ImmutableMap.of()))
                    .type("application/json")
                    .build();
            }

            // C++ workers: wait for filters or timeout
            VersionedDynamicFilters filters = waitForDynamicFiltersOrTimeout(
                taskId, currentVersion, maxWait);

            return Response.ok(filters).type("application/json").build();
        }
    }

    public class VersionedDynamicFilters {
        private final long version;
        private final Map<DynamicFilterId, Domain> dynamicFilters;
        // Returns only NEW filters since currentVersion
    }
    ```

[^backward-compat-table]: Backward compatibility matrix:
    | Concern | Impact | Mitigation |
    |---------|--------|------------|
    | **Schema Changes** | TaskStatus has new field | Field is optional, default 0 |
    | **JSON Protocol** | One additional long field | Minimal overhead (8 bytes) |
    | **Thrift Protocol** | New field ID | Marked optional, backward compatible |
    | **HTTP Endpoints** | New route registered | Returns empty for Java workers |
    | **Mixed Clusters** | Java + C++ coexist | Each worker type behaves independently |

[^mixed-cluster-verification]: Mixed cluster verification examples:
    ```java
    // Java worker TaskStatus
    {"taskId": "java-task-1", "state": "RUNNING", "dynamicFiltersVersion": 0}

    // C++ worker TaskStatus (before filters)
    {"taskId": "cpp-task-1", "state": "RUNNING", "dynamicFiltersVersion": 0}

    // C++ worker TaskStatus (after filter creation)
    {"taskId": "cpp-task-1", "state": "RUNNING", "dynamicFiltersVersion": 1}

    // Coordinator processing:
    coordinator.processTaskStatus(javaTaskStatus);   // Version 0, no fetch
    coordinator.processTaskStatus(cppInitialStatus); // Version 0, no fetch
    coordinator.processTaskStatus(cppUpdatedStatus); // Version 1 > 0, fetch!
    ```

[^native-sidecar-config]: Optional native sidecar configuration:
    ```java
    // In presto-native-sidecar-plugin (optional, not required)
    public class NativeDynamicFilterConfig {
        private boolean dynamicFilterEnabled = true;

        @Config("native.dynamic-filter.enabled")
        public NativeDynamicFilterConfig setDynamicFilterEnabled(boolean enabled) {
            this.dynamicFilterEnabled = enabled;
            return this;
        }
    }
    ```
    This is not necessary for basic isolation - system works automatically.

---

## 5. Filter Representation

### 5.1 TupleDomain-Based Filters

Dynamic filters use Presto's existing **TupleDomain** structure for runtime filtering. TupleDomain is already used throughout Presto for predicate representation and supports both discrete values and ranges, making it ideal for dynamic partition pruning.

```java
// Existing Presto structure - no new abstraction needed
TupleDomain<ColumnHandle> filter = TupleDomain.withColumnDomains(
    ImmutableMap.of(columnHandle, domain)
);
```

### 5.2 Filter Structure Based on Cardinality

The system uses **TupleDomain with adaptive representation** based on cardinality:

**For Low Cardinality (≤10K distinct values)**:
```java
// Perfect pruning with discrete values
Domain domain = Domain.multipleValues(type, distinctValues);
TupleDomain<ColumnHandle> filter = TupleDomain.withColumnDomains(
    ImmutableMap.of(columnHandle, domain)
);
```

**For High Cardinality (>10K distinct values)**:
```java
// Range-based pruning with min/max
Domain domain = Domain.create(
    ValueSet.ofRanges(Range.range(type, min, true, max, true)),
    false  // nullAllowed
);
TupleDomain<ColumnHandle> filter = TupleDomain.withColumnDomains(
    ImmutableMap.of(columnHandle, domain)
);
```

**Why TupleDomain?**

| Benefit | Description |
|---------|-------------|
| **Reuses existing infrastructure** | TupleDomain already has JSON serialization, merging, and predicate logic |
| **Perfect pruning for common case** | Most star schema joins have ≤10K distinct values |
| **Bounded memory** | Configurable limit (default 10K) prevents memory explosion |
| **Simple** | No new abstractions, serialization formats, or merge logic needed |
| **Multi-level pruning** | Works at partition, file, row group, and row levels |

**Pruning Strategy by Level**:

1. **Partition/File Pruning** (metadata level):
   - Discrete values: Test intersection with partition/file min/max → Perfect pruning
   - Range: Test range overlap → Skip non-overlapping partitions/files

2. **Row Group Pruning** (Parquet/ORC level):
   - Same strategy as partition/file pruning
   - Discrete values enable perfect row group skipping

3. **Row Filtering** (execution level):
   - Discrete values: Use Velox BigintValues filter (hash table, SIMD-accelerated)
   - Range: Use Velox BigintRange filter (min/max, SIMD-accelerated)

**Memory Efficiency by Cardinality**:

| Cardinality | Representation | Size | Pruning Power |
|-------------|----------------|------|---------------|
| 1K values | Discrete values | ~8KB | Perfect at all levels (0% false positives) |
| 10K values | Discrete values | ~80KB | Perfect at all levels (0% false positives) |
| 50K values | Range (min/max) | ~16 bytes | Range-based pruning at all levels |
| 1M values | Range (min/max) | ~16 bytes | Range-based pruning at all levels |

**Network Impact** (100 workers):
- Low cardinality (1K): 8KB × 100 = 800KB
- Medium cardinality (10K): 80KB × 100 = 8MB
- High cardinality (50K+): 16B × 100 = 1.6KB (range only)

**Verdict**: TupleDomain provides perfect pruning for the common case (low cardinality joins) while maintaining minimal memory footprint.

### 5.3 TupleDomain Filter Construction

Filters are built incrementally during hash table construction using a simple builder pattern[^tuple-domain-filter-builder]:

[^tuple-domain-filter-builder]: **TupleDomain Filter Builder Implementation**

    **C++ Implementation (Velox HashBuild)**:
    ```cpp
    // In Velox HashBuild operator during hash table construction
    class TupleDomainBuilder {
      int64_t min_ = std::numeric_limits<int64_t>::max();
      int64_t max_ = std::numeric_limits<int64_t>::min();
      folly::F14FastSet<int64_t> distinctValues_;
      static constexpr int kDistinctValuesLimit = 10'000;

      void addValue(int64_t value) {
        // Always track min/max
        min_ = std::min(min_, value);
        max_ = std::max(max_, value);

        // Collect distinct values up to limit
        if (distinctValues_.size() < kDistinctValuesLimit) {
          distinctValues_.insert(value);
        }
      }

      TupleDomain build() {
        if (distinctValues_.size() < kDistinctValuesLimit) {
          // Create TupleDomain with discrete values (perfect pruning)
          return TupleDomain::withColumnDomains({
            {columnHandle, Domain::multipleValues(distinctValues_)}
          });
        } else {
          // Create TupleDomain with range (range-based pruning)
          return TupleDomain::withColumnDomains({
            {columnHandle, Domain::create(Range::range(min_, max_))}
          });
        }
      }
    };
    ```

    **Java Implementation (for coordinator operations)**:
    ```java
    public class TupleDomainFilterBuilder {
        private long min = Long.MAX_VALUE;
        private long max = Long.MIN_VALUE;
        private final Set<Long> distinctValues = new HashSet<>();
        private static final int DISTINCT_VALUES_LIMIT = 10_000;

        public void addValue(long value) {
            min = Math.min(min, value);
            max = Math.max(max, value);

            if (distinctValues.size() < DISTINCT_VALUES_LIMIT) {
                distinctValues.add(value);
            }
        }

        public TupleDomain<ColumnHandle> build(ColumnHandle columnHandle, Type type) {
            if (distinctValues.size() < DISTINCT_VALUES_LIMIT) {
                // Create Domain with discrete values
                Domain domain = Domain.multipleValues(type,
                    distinctValues.stream()
                        .map(v -> (Object) v)
                        .collect(toImmutableList()));

                return TupleDomain.withColumnDomains(
                    ImmutableMap.of(columnHandle, domain));
            } else {
                // Create Domain with range
                Domain domain = Domain.create(
                    ValueSet.ofRanges(Range.range(type, min, true, max, true)),
                    false);

                return TupleDomain.withColumnDomains(
                    ImmutableMap.of(columnHandle, domain));
            }
        }
    }
    ```

    **Merge operation** (coordinator combines filters from multiple tasks):

    Uses existing `TupleDomain.intersect()` method - no custom logic needed!

    ```java
    // LocalDynamicFilter already handles this
    TupleDomain<ColumnHandle> merged = filter1.intersect(filter2);

    // Examples:
    // Discrete values: {1, 2, 5, 7}.intersect({2, 5, 8, 9}) → {2, 5}
    // Ranges: [10, 100].intersect([20, 90]) → [20, 90]
    // Mixed: {15, 25, 35, 45}.intersect([20, 40]) → {25, 35}
    ```

**Key Design Decisions**:

1. **Single-pass construction**: Min/max and distinct values collected during hash table scan
2. **Adaptive representation**: Switches to range when distinct values limit exceeded
3. **Reuse existing infrastructure**: TupleDomain handles serialization, merging, and predicate logic
4. **No custom merge logic**: LocalDynamicFilter uses TupleDomain.intersect()

---

## 6. Serialization Protocol

### 6.0 TupleDomain JSON Serialization

Dynamic filters use Presto's existing **TupleDomain JSON serialization**. This approach reuses existing infrastructure and requires no custom serialization logic.

**Key Advantages**:
- **Reuses existing code**: TupleDomain already has JsonCodec support in Presto
- **Simple**: No custom binary format to maintain
- **Debuggable**: Human-readable JSON for troubleshooting
- **Cross-language compatible**: JSON parsers available in both Java and C++

### 6.1 JSON Format Structure

TupleDomain is serialized using Presto's existing JsonCodec infrastructure:

**For Discrete Values (≤10K)**:
```json
{
  "columnDomains": {
    "customer_id": {
      "values": {
        "type": "discrete",
        "values": [1, 2, 5, 7, 12, 25, ...]
      },
      "nullAllowed": false
    }
  }
}
```

**For Range (>10K)**:
```json
{
  "columnDomains": {
    "customer_id": {
      "values": {
        "type": "range",
        "ranges": [
          {
            "low": {"value": 1, "bound": "EXACTLY"},
            "high": {"value": 100000, "bound": "EXACTLY"}
          }
        ]
      },
      "nullAllowed": false
    }
  }
}
```

### 6.2 Size Examples

**Discrete Values**:

| Cardinality | JSON Size (approx) |
|-------------|-------------------|
| 1K values | ~8KB |
| 10K values | ~80KB |

**Range**:

| Cardinality | JSON Size (approx) |
|-------------|-------------------|
| Any (50K, 1M, etc.) | <1KB |

**Key Characteristics**:

1. **Compact for ranges**: High-cardinality filters use only min/max → <1KB
2. **Reasonable for discrete values**: 10K limit keeps size bounded to ~80KB
3. **Self-describing**: JSON includes type information
4. **Debuggable**: Human-readable format simplifies troubleshooting

### 6.3 Serialization Implementation

Serialization uses Presto's existing JsonCodec infrastructure[^tuple-domain-serialization].

### 6.4 Performance Characteristics

| Aspect | JSON Format | Notes |
|--------|-------------|-------|
| **Size (1K values)** | ~8KB | Discrete values |
| **Size (10K values)** | ~80KB | Discrete values |
| **Size (50K+ values)** | <1KB | Range only |
| **Serialize Time** | ~5ms | JsonCodec overhead |
| **Deserialize Time** | ~5ms | JsonCodec overhead |
| **Network Time (1Gbps)** | ~5ms | For 10K values |
| **Total Overhead** | ~15ms | Acceptable for filter distribution |

**Conclusion**: JSON format is simple, debuggable, and provides adequate performance. The overhead (~15ms) is negligible compared to I/O savings (seconds) from partition pruning.

---

**Section 6 Footnotes:**

[^tuple-domain-serialization]: **TupleDomain Serialization Implementation**

    **Java Implementation:**
    ```java
    // Use existing JsonCodec infrastructure
    private static final JsonCodec<TupleDomain<ColumnHandle>> TUPLE_DOMAIN_CODEC =
        JsonCodec.jsonCodec(new TypeReference<TupleDomain<ColumnHandle>>() {});

    public String serializeTupleDomain(TupleDomain<ColumnHandle> filter) {
        return TUPLE_DOMAIN_CODEC.toJson(filter);
    }

    public TupleDomain<ColumnHandle> deserializeTupleDomain(String json) {
        return TUPLE_DOMAIN_CODEC.fromJson(json);
    }
    ```

    **C++ Implementation:**
    ```cpp
    // C++ side - parse JSON to TupleDomain equivalent structure
    #include <folly/json.h>

    TupleDomain parseTupleDomainFromJson(const std::string& jsonStr) {
        auto json = folly::parseJson(jsonStr);

        // Extract column domains
        auto columnDomains = json["columnDomains"];

        for (auto& [columnName, domainJson] : columnDomains.items()) {
            auto values = domainJson["values"];
            std::string type = values["type"].asString();

            if (type == "discrete") {
                // Parse discrete values
                auto valueArray = values["values"];
                std::vector<int64_t> distinctValues;
                for (auto& val : valueArray) {
                    distinctValues.push_back(val.asInt());
                }
                // Create BigintValues filter
                return createDiscreteFilter(columnName, distinctValues);
            } else if (type == "range") {
                // Parse range
                auto range = values["ranges"][0];
                int64_t min = range["low"]["value"].asInt();
                int64_t max = range["high"]["value"].asInt();
                // Create BigintRange filter
                return createRangeFilter(columnName, min, max);
            }
        }
    }
    ```

    **JSON Example** (1000 discrete values):
    ```json
    {
      "columnDomains": {
        "customer_id": {
          "values": {
            "type": "discrete",
            "values": [1, 2, 5, 7, 10, 12, ..., 9995]
          },
          "nullAllowed": false
        }
      }
    }

    // Size: ~8KB for 1000 values
    ```

---

## 7. Component Design

### 7.0 Integration with Existing Infrastructure

The design leverages Presto's existing dynamic filtering infrastructure while extending it for distributed operation and C++ compatibility.

#### Existing Components to Reuse:

1. **Filter ID Generation**: Use existing infrastructure from `JoinNode.dynamicFilters` compatible with both Java and Velox
2. **Velox HashBuild**: Already has local dynamic filtering support; we add distributed filter export[^velox-hashbuild-extension]

**What Velox Already Has:**
- Filter creation from hash table data
- Local filter pushdown within single process
- `HashBuild::pushDownFilter()` and `Task::addDynamicFilter()` methods

**What We Add:**
- TupleDomain JSON serialization for distribution
- HTTP-based coordinator retrieval
- Distributed filter collection wiring

#### New Unified Components:

1. **JSON Serialization**: TupleDomain serialization using existing JsonCodec[^tuple-domain-serialization]

2. **Filter Collection and Merging**: Coordinator runtime component using LocalDynamicFilter[^filter-collection-impl]

   The DynamicFilterCoordinator sits in the SqlQueryExecution/SqlStageExecution layer, running during query execution. It manages LocalDynamicFilter instances for merging, processes TaskStatus updates for filter detection, and integrates with the StageScheduler for partition counting.

### 7.1 Filter Extraction

#### TupleDomain Filter Extraction During Hash Table Build

**When:** During query execution, as the HashBuild operator processes rows

**Where:** In the C++ worker process building the hash table (Velox)

Filter statistics are collected during execution, tracking distinct values (up to 10K limit) and min/max bounds. The system adaptively creates either discrete value or range-based TupleDomain filters[^filter-stats-collector].

After the hash table build completes, TupleDomain filters are created and serialized to JSON for distribution[^dynamic-filtering-hashbuild].

[^filter-stats-collector]: **TupleDomain Filter Statistics Collector**
    ```cpp
    // This runs DURING EXECUTION as rows are being added to the hash table
    class TupleDomainFilterCollector {
        int64_t min_ = std::numeric_limits<int64_t>::max();
        int64_t max_ = std::numeric_limits<int64_t>::min();
        folly::F14FastSet<int64_t> distinctValues_;
        static constexpr int kDistinctValuesLimit = 10'000;

        void addValue(int64_t value) {
            // Always track min/max for range filters
            min_ = std::min(min_, value);
            max_ = std::max(max_, value);

            // Collect distinct values up to limit
            if (distinctValues_.size() < kDistinctValuesLimit) {
                distinctValues_.insert(value);
            }
        }

        // Called at the end of hash table build
        bool shouldUseDiscreteValues() {
            return distinctValues_.size() < kDistinctValuesLimit;
        }
    };
    ```

[^dynamic-filtering-hashbuild]: **DynamicFilteringHashBuild Implementation**
    ```cpp
    class DynamicFilteringHashBuild : public velox::exec::HashBuild {
    protected:
        void finishHashBuild() override {
            HashBuild::finishHashBuild();

            // Create TupleDomain from hash table
            auto tupleDomain = extractTupleDomainFromHashTable(
                filterId_,
                joinColumns_,
                hashTable_);

            // Serialize to JSON using existing codec
            nlohmann::json j;
            facebook::presto::protocol::to_json(j, tupleDomain);
            std::string jsonFilter = j.dump();

            // Store filter for coordinator to collect via TaskStatus
            taskContext_->addDynamicFilter(filterId_, jsonFilter);

            // Increment version to signal coordinator
            taskContext_->incrementDynamicFiltersVersion();
        }

        TupleDomain extractTupleDomainFromHashTable(
                const std::string& filterId,
                const std::vector<core::TypedExprPtr>& joinColumns,
                const HashTable& hashTable) {

            // Collect statistics from hash table
            TupleDomainFilterCollector collector;
            for (const auto& row : hashTable) {
                int64_t value = row.getValue(joinColumnIndex_);
                collector.addValue(value);
            }

            // Build TupleDomain based on cardinality
            if (collector.shouldUseDiscreteValues()) {
                // Create discrete values domain (perfect pruning)
                return TupleDomain::withColumnDomains({
                    {columnHandle, Domain::multipleValues(collector.distinctValues_)}
                });
            } else {
                // Create range domain (range-based pruning)
                return TupleDomain::withColumnDomains({
                    {columnHandle, Domain::create(Range::range(collector.min_, collector.max_))}
                });
            }
        }
    };
    ```

### 7.2 Filter Collection and Distribution

The design uses lightweight identifiers in TaskStatus with dedicated endpoints for filter data.

#### Filter Identification

Each filter has a unique ID combining source information, partition ID, and version number[^filter-descriptor].

#### Lightweight Collection via TaskStatus

TaskStatus is enhanced to include filter IDs (not the actual filter data), while workers store predicates locally for on-demand retrieval[^taskstatus-predicate-store].

#### Dedicated Filter Endpoints

New worker endpoints handle filter retrieval and distribution operations[^runtime-predicate-endpoints].

[^filter-descriptor]: **DynamicFilterDescriptor Implementation**
    ```java
    // Each filter has a unique ID combining source and filter information
    public class DynamicFilterDescriptor {
        private final String filterId;        // From JoinNode.dynamicFilters
        private final PlanNodeId sourceNodeId; // Build-side plan node
        private final PlanNodeId targetNodeId; // Probe-side plan node
        private final int partitionId;        // Worker partition that created it
        private final long version;           // Monotonic version number

        // Unique identifier for retrieval
        public String getUniqueId() {
            return String.format("%s_%s_%d_v%d",
                filterId, sourceNodeId, partitionId, version);
        }
    }
    ```

[^taskstatus-predicate-store]: **TaskStatus and Worker Storage**
    ```java
    // Enhanced TaskStatus with predicate notifications (lightweight)
    public class TaskStatus {
        // Existing fields
        private final TaskId taskId;
        private final TaskState state;
        private final DateTime lastHeartbeat;

        // NEW: Just IDs of available predicates (not the data)
        private final Set<String> availablePredicateIds;

        public Set<String> getAvailablePredicateIds() {
            return availablePredicateIds;
        }
    }

    // Worker-side predicate storage
    public class WorkerPredicateStore {
        private final Map<String, SerializedPredicate> predicates = new ConcurrentHashMap<>();

        public void addPredicate(String uniqueId, SerializedPredicate predicate) {
            predicates.put(uniqueId, predicate);
        }

        public SerializedPredicate getPredicate(String uniqueId) {
            return predicates.get(uniqueId);
        }
    }
    ```

[^runtime-predicate-endpoints]: **Dynamic Filter Resource Endpoints**
    ```java
    // NEW Worker endpoints for filter operations
    @Path("/v1/task/{taskId}/dynamicfilters")
    public class DynamicFilterResource {

        // Coordinator calls this to fetch TupleDomain filters (JSON)
        @GET
        public Response getDynamicFilters(
                @PathParam("taskId") TaskId taskId,
                @HeaderParam("X-Presto-Current-Version") long currentVersion,
                @HeaderParam("X-Presto-Max-Wait") Duration maxWait) {

            // Wait for filters or timeout (long-polling)
            VersionedDynamicFilters filters = waitForDynamicFiltersOrTimeout(
                taskId, currentVersion, maxWait);

            if (filters == null || filters.getDynamicFilters().isEmpty()) {
                return Response.ok(new VersionedDynamicFilters(currentVersion, ImmutableMap.of()))
                    .type("application/json")
                    .build();
            }

            // Return JSON TupleDomain filters
            return Response.ok(filters)
                .type("application/json")
                .build();
        }

        // Coordinator calls this to push TupleDomain filters to workers (JSON)
        @PUT
        @Path("/{filterId}")
        public Response pushDynamicFilter(
                @PathParam("taskId") TaskId taskId,
                @PathParam("filterId") String filterId,
                TupleDomain<ColumnHandle> filter,  // JSON TupleDomain
                @HeaderParam("X-Target-Nodes") String targetNodeIds) {

            // Parse target plan node IDs
            Set<PlanNodeId> targets = parseTargetNodes(targetNodeIds);

            // Store and apply TupleDomain filter to specified plan nodes
            dynamicFilterApplicator.applyFilter(taskId, filterId, filter, targets);

            return Response.ok().build();
        }
    }

    public class VersionedDynamicFilters {
        private final long version;
        private final Map<String, TupleDomain<ColumnHandle>> dynamicFilters;

        // Returns only NEW filters since currentVersion
        public VersionedDynamicFilters(long version, Map<String, TupleDomain<ColumnHandle>> filters) {
            this.version = version;
            this.dynamicFilters = ImmutableMap.copyOf(filters);
        }

        public long getVersion() { return version; }
        public Map<String, TupleDomain<ColumnHandle>> getDynamicFilters() { return dynamicFilters; }
    }
    ```

#### Filter Target Resolution

The `DynamicFilterTargetResolver` manages filter routing by maintaining mappings between filter IDs, source nodes, and target nodes. These mappings are established during query planning and used by the coordinator to determine broadcast targets[^filter-target-resolution].

#### Coordinator Filter Manager

The `DynamicFilterCoordinator` orchestrates filter lifecycle: detecting new filters via TaskStatus updates, fetching filter data asynchronously, and broadcasting to target workers using the target resolver[^coordinator-filter-manager].

[^filter-target-resolution]: **Filter Target Resolution Implementation**
    ```java
    // How to determine where filters should be applied
    public class DynamicFilterTargetResolver {

        // Built during planning phase
        private final Map<String, FilterTargetMapping> filterMappings;

        public static class FilterTargetMapping {
            private final String filterId;
            private final PlanNodeId sourceNode;  // Where filter is created
            private final Set<PlanNodeId> targetNodes; // Where filter is consumed
            private final Set<TaskId> targetTasks;     // Which tasks have target nodes
        }

        // Called during planning to establish mappings
        public void registerFilterMapping(
                String filterId,
                PlanNodeId sourceNode,
                Set<PlanNodeId> targetNodes) {

            // Store mapping of filter ID to target plan nodes
            // This is distributed to all workers with the plan
            filterMappings.put(filterId,
                new FilterTargetMapping(filterId, sourceNode, targetNodes));
        }

        // Called by coordinator to determine broadcast targets
        public Set<TaskId> getTargetTasks(String filterId) {
            FilterTargetMapping mapping = filterMappings.get(filterId);
            return mapping != null ? mapping.targetTasks : ImmutableSet.of();
        }
    }
    ```

[^coordinator-filter-manager]: **Coordinator Filter Manager Implementation**
    ```java
    public class DynamicFilterCoordinator {
        // Map of filter ID to LocalDynamicFilter instance (handles merging)
        private final ConcurrentHashMap<String, LocalDynamicFilter> filters;
        private final DynamicFilterTargetResolver targetResolver;

        // Process TaskStatus updates (called frequently)
        public void processTaskStatus(TaskId taskId, TaskStatus status) {
            long newVersion = status.getDynamicFiltersVersion();
            long currentVersion = getCurrentVersion(taskId);

            // Version-based detection (Trino pattern)
            if (newVersion > currentVersion) {
                // New filters available, fetch asynchronously
                fetchFiltersAsync(taskId, currentVersion);
            }
        }

        private void fetchFiltersAsync(TaskId taskId, long currentVersion) {
            executor.submit(() -> {
                try {
                    // Long-poll for filter data (JSON TupleDomain)
                    VersionedDynamicFilters response = httpClient.get(
                        "/v1/task/" + taskId + "/dynamicfilters",
                        ImmutableMap.of(
                            "X-Presto-Current-Version", String.valueOf(currentVersion),
                            "X-Presto-Max-Wait", "1s"));

                    // Process each filter
                    for (Map.Entry<String, TupleDomain<ColumnHandle>> entry :
                            response.getDynamicFilters().entrySet()) {
                        String filterId = entry.getKey();
                        TupleDomain<ColumnHandle> filterDomain = entry.getValue();

                        // Get or create LocalDynamicFilter for merging
                        LocalDynamicFilter localFilter = filters.computeIfAbsent(
                            filterId,
                            id -> new LocalDynamicFilter(
                                getExpectedPartitions(filterId)));

                        // Add this worker's filter partition - LocalDynamicFilter handles merging
                        // using TupleDomain.intersect() internally
                        localFilter.addPartition(filterDomain);

                        // If complete, broadcast merged filter
                        if (localFilter.isComplete()) {
                            broadcastFilter(filterId, localFilter.getCurrentPredicate());
                        }
                    }

                    // Update version
                    updateVersion(taskId, response.getVersion());
                } catch (Exception e) {
                    log.error("Failed to fetch filters from " + taskId, e);
                }
            });
        }

        private void broadcastFilter(String filterId, TupleDomain<ColumnHandle> filter) {
            // Get tasks that need this filter
            Set<TaskId> targetTasks = targetResolver.getTargetTasks(filterId);

            // Get plan nodes within those tasks that consume the filter
            String targetNodeIds = targetResolver.getTargetNodeIds(filterId);

            // PUT JSON TupleDomain to each target worker
            for (TaskId targetTask : targetTasks) {
                httpClient.put(
                    "/v1/task/" + targetTask + "/dynamicfilters/" + filterId,
                    filter,  // Serialized as JSON by HTTP client
                    ImmutableMap.of("X-Target-Nodes", targetNodeIds));
            }
        }

        private int getExpectedPartitions(String filterId) {
            // Get from the build stage's partition count
            StageId buildStageId = targetResolver.getBuildStageId(filterId);
            return stageScheduler.getStagePartitions(buildStageId);
        }
    }
    ```

### 7.3 Filter Distribution to Connectors

The engine provides filters to connectors through a supplier interface, allowing connectors to apply filters without the engine knowing connector-specific details:

```java
// Engine-side interface (in presto-spi) - Using CompletableFuture pattern
public interface DynamicFilter {
    /**
     * Returns a future that completes when the filter is updated.
     * The future completes immediately if filter cannot be narrowed further.
     */
    CompletableFuture<?> isBlocked();
    
    /**
     * Returns true if filter might still be narrowed down.
     */
    boolean isAwaitable();
    
    /**
     * Returns true if filter is finalized and won't change.
     */
    boolean isComplete();
    
    /**
     * Get current filter predicate. May be called multiple times
     * as filter improves progressively.
     */
    TupleDomain<ColumnHandle> getCurrentPredicate();
    
    /**
     * Columns covered by this dynamic filter.
     */
    Set<ColumnHandle> getColumnsCovered();
}

// Engine provides implementation
public class DynamicFilterImpl implements DynamicFilter {
    private final Map<String, ColumnHandle> filterIdToColumn;
    private final AtomicReference<TupleDomain<ColumnHandle>> currentPredicate;
    private final CompletableFuture<Void> future = new CompletableFuture<>();
    private volatile boolean complete = false;
    
    @Override
    public CompletableFuture<?> isBlocked() {
        if (complete) {
            return CompletableFuture.completedFuture(null);
        }
        return future.copy();  // Return a copy so caller can't complete our future
    }
    
    @Override
    public boolean isAwaitable() {
        return !complete && !future.isDone();
    }
    
    @Override
    public boolean isComplete() {
        return complete;
    }
    
    @Override
    public TupleDomain<ColumnHandle> getCurrentPredicate() {
        return currentPredicate.get();
    }
    
    // Called by coordinator when new filter arrives
    void updateFilter(TupleDomain<String> newFilter) {
        // Update current predicate
        TupleDomain<ColumnHandle> updated = translateToColumnHandles(newFilter);
        currentPredicate.set(updated);
        
        // Complete the future to wake up waiters
        if (!future.isDone()) {
            future.complete(null);
        }
        
        // Create new future for next update
        if (!complete) {
            future = new CompletableFuture<>();
        }
    }
    
    void markComplete() {
        complete = true;
        if (!future.isDone()) {
            future.complete(null);
        }
    }
}

// Connectors receive supplier through enhanced SPI
public interface ConnectorSplitManager {
    default ConnectorSplitSource getSplits(
            ConnectorTransactionHandle transaction,
            ConnectorSession session,
            ConnectorTableLayoutHandle layout,
            SplitSchedulingStrategy splitSchedulingStrategy,
            DynamicFilter dynamicFilter) {  // NEW parameter
        
        // Default implementation ignores dynamic filters
        // for backward compatibility
        return getSplits(transaction, session, layout, splitSchedulingStrategy);
    }
}
```

### 7.4 Multi-Level Filter Application with TupleDomain

TupleDomain filters are applied at multiple levels in the query execution stack, from partition pruning down to row-level filtering. The pruning strategy depends on whether the filter contains discrete values or ranges.

#### Level 1: Partition Pruning (Metadata Level)

Dynamic filters are applied to partition statistics through the ConnectorMetadata interface[^partition-pruning-impl].

**TupleDomain Partition Pruning Logic**:
```java
boolean testPartition(PartitionStatistics stats, TupleDomain<ColumnHandle> filter) {
  if (filter.isNone()) {
    return false;  // Empty filter - skip all partitions
  }

  if (filter.isAll()) {
    return true;  // No constraints - include all partitions
  }

  // Extract the domain for the partition column
  Domain domain = filter.getDomains().get().get(partitionColumnHandle);
  if (domain == null) {
    return true;  // Filter doesn't apply to this column
  }

  // Test partition range against filter domain
  // This works for both discrete values and ranges
  boolean partitionMatches = domain.overlaps(
      Domain.create(
          ValueSet.ofRanges(Range.range(
              type, stats.getMin(), true, stats.getMax(), true)),
          false));

  return partitionMatches;
}
```

**Result**:
- **Discrete values (≤10K)**: Perfect partition skipping - 0% false positives
- **Range (>10K)**: Range-based pruning - conservative overlap test

#### Level 2: File Pruning (Split Scheduling Level)

The engine uses a backward-compatible SPI extension strategy[^file-pruning-backward-compat]. Connectors like Hive/Iceberg test file statistics against the TupleDomain filter.

**TupleDomain File Pruning Logic**:
```java
boolean testFile(FileStatistics stats, TupleDomain<ColumnHandle> filter) {
  if (filter.isNone()) {
    return false;  // Empty filter - skip all files
  }

  // Extract domain for file statistics column
  Domain domain = filter.getDomains().get().get(columnHandle);
  if (domain == null) {
    return true;  // Filter doesn't constrain this column
  }

  // Test file min/max range against filter domain
  // TupleDomain automatically handles discrete values vs ranges
  boolean fileMatches = domain.overlaps(
      Domain.create(
          ValueSet.ofRanges(Range.range(
              type, stats.getMin(), true, stats.getMax(), true)),
          false));

  return fileMatches;
}
```

**Result**: Same pruning effectiveness as partition level - perfect for discrete values, range-based for high cardinality.

**Key Advantage**: File/row group metadata stores only min/max, but TupleDomain with discrete values still enables perfect pruning by testing if any value in the set falls within the file's range.

#### Level 3: Row Group Pruning (Parquet/ORC Level)

Same logic as file pruning - uses Velox's `testFilter()` interface which accepts TupleDomain.

**Implementation**: Velox's SubfieldFilters already support TupleDomain - no modifications needed.

#### Level 4: Row-Level Filtering (Execution Level)

Velox converts TupleDomain filters to optimized Filter objects for row-level evaluation[^velox-filter-conversion].

**Velox Filter Conversion**:
- **Discrete values (≤10K)**: Converts to `BigintValues` filter (hash table, SIMD-accelerated)
- **Range (>10K)**: Converts to `BigintRange` filter (min/max comparisons, SIMD-accelerated)

**Performance Characteristics**:

| Cardinality | Filter Type | Pruning Power | Row Filtering Speed |
|-------------|-------------|---------------|---------------------|
| ≤10K | BigintValues (hash table) | Perfect (0% FP) | 3-4x SIMD speedup |
| >10K | BigintRange (min/max) | Range-based | 4-8x SIMD speedup |

**Key Architecture Benefits**:
- **Reuses existing infrastructure**: TupleDomain and Velox Filter already work together
- **Zero false positives** for low cardinality - hash table lookup
- **Simple and maintainable**: No custom filter types needed
- **SIMD accelerated**: Velox's BigintValues and BigintRange already use SIMD
- **Automatic optimization**: Velox chooses best Filter implementation

---

**Section 7 Footnotes:**

[^velox-filter-conversion]: **Velox Filter Conversion from TupleDomain**

    Velox converts TupleDomain filters to optimized Filter objects:

    ```cpp
    // Velox already has infrastructure for converting TupleDomain to Filter objects
    // This happens in velox/exec/FilterProject.cpp

    std::unique_ptr<velox::common::Filter> convertTupleDomainToFilter(
        const TupleDomain& tupleDomain,
        const Type& type) {

      // Extract domain for the column
      auto domain = tupleDomain.getDomains().at(columnHandle);

      // Check if domain contains discrete values
      if (domain.getValues().isSingleValue()) {
        // Single value filter
        return std::make_unique<velox::common::BigintValuesUsingHashTable>(
            std::vector<int64_t>{domain.getValues().getSingleValue()},
            false);
      }

      if (domain.getValues().isDiscreteSet()) {
        // Multiple discrete values - use hash table (SIMD-accelerated)
        auto values = domain.getValues().getDiscreteSet();
        return std::make_unique<velox::common::BigintValuesUsingHashTable>(
            values, false);
      }

      // Range filter - use min/max (SIMD-accelerated)
      auto ranges = domain.getValues().getRanges();
      if (ranges.isSingleRange()) {
        auto range = ranges.getSingleRange();
        return std::make_unique<velox::common::BigintRange>(
            range.getLow(), range.getHigh(), false);
      }

      // Multiple ranges - use multi-range filter
      return std::make_unique<velox::common::BigintMultiRange>(
          ranges.getOrderedRanges(), false);
    }
    ```

    **Performance Characteristics**:

    | TupleDomain Type | Velox Filter | Performance |
    |------------------|--------------|-------------|
    | Discrete values (≤10K) | BigintValuesUsingHashTable | 3-4x SIMD speedup, 0% false positives |
    | Range (>10K) | BigintRange | 4-8x SIMD speedup, range-based pruning |
    | Multiple ranges | BigintMultiRange | 4-8x SIMD speedup, multi-range pruning |

[^velox-hashbuild-extension]: Velox HashBuild extension for distributed filtering:
    ```cpp
    class HashBuild : public Operator {
        void noMoreInput() override {
            // EXISTING: Velox already creates and pushes filters locally
            if (joinNode_->isRightSemiFilterJoin()) {
                pushDownFilter();
            }

            // NEW: Add distributed filter export
            if (hasDistributedDynamicFilters()) {
                auto tupleDomain = extractFilterAsTupleDomain();

                nlohmann::json j;
                facebook::presto::protocol::to_json(j, tupleDomain);
                std::string jsonFilter = j.dump();

                operatorCtx_->task()->setDynamicFilter(filterId_, jsonFilter);
            }
        }
    };
    ```

[^filter-collection-impl]: **DynamicFilterCoordinator Implementation**
    ```java
    // This would be a new component in presto-main, integrated with SqlQueryExecution
    // Located in: com.facebook.presto.execution.DynamicFilterCoordinator
    public class DynamicFilterCoordinator {
        // Map of filter ID to LocalDynamicFilter instance (handles merging with TupleDomain.intersect())
        private final Map<String, LocalDynamicFilter> filters = new ConcurrentHashMap<>();
        // Map of filter ID to DynamicFilter instances waiting for updates
        private final Map<String, List<DynamicFilterImpl>> waitingFilters = new ConcurrentHashMap<>();
        private final SqlQueryExecution queryExecution;
        private final StageScheduler stageScheduler;

        // Create a DynamicFilter for a table scan operator
        public DynamicFilter createDynamicFilter(
                Set<String> filterIds,
                Map<String, ColumnHandle> filterIdToColumn) {

            DynamicFilterImpl filter = new DynamicFilterImpl(filterIdToColumn);

            // Register this filter to receive updates
            for (String filterId : filterIds) {
                waitingFilters.computeIfAbsent(filterId, k -> new CopyOnWriteArrayList<>())
                    .add(filter);
            }

            return filter;
        }

        // Called by SqlStageExecution when it receives TaskStatus updates
        public void processTaskStatus(TaskId taskId, TaskStatus status) {
            long newVersion = status.getDynamicFiltersVersion();
            long currentVersion = getCurrentVersion(taskId);

            // Version-based detection (Trino pattern)
            if (newVersion > currentVersion) {
                // New filters available, fetch asynchronously
                fetchFiltersAsync(taskId, currentVersion);
            }
        }

        // Called when filter data is fetched from a worker (JSON TupleDomain)
        public void addRemoteFilter(String filterId, TupleDomain<ColumnHandle> filterDomain) {
            // Get or create the LocalDynamicFilter for this filter ID
            LocalDynamicFilter localFilter = filters.computeIfAbsent(
                filterId,
                id -> new LocalDynamicFilter(getExpectedPartitions(filterId)));

            // Add this worker's filter partition
            // LocalDynamicFilter automatically merges using TupleDomain.intersect()
            localFilter.addPartition(filterDomain);

            // Get current merged filter
            TupleDomain<ColumnHandle> mergedFilter = localFilter.getCurrentPredicate();

            // Notify waiting DynamicFilter instances
            notifyWaitingFilters(filterId, mergedFilter);

            // LocalDynamicFilter.isComplete() tells us when all partitions are collected
            if (localFilter.isComplete()) {
                // Mark filters as complete
                markFiltersComplete(filterId);

                // Distribute final merged filter (JSON TupleDomain)
                distributeToTargetStages(filterId, mergedFilter);
            }
        }

        private void notifyWaitingFilters(String filterId, TupleDomain<ColumnHandle> update) {
            List<DynamicFilterImpl> filters = waitingFilters.get(filterId);
            if (filters != null) {
                for (DynamicFilterImpl filter : filters) {
                    filter.updateFilter(update);
                }
            }
        }

        private void markFiltersComplete(String filterId) {
            List<DynamicFilterImpl> filters = waitingFilters.remove(filterId);
            if (filters != null) {
                for (DynamicFilterImpl filter : filters) {
                    filter.markComplete();
                }
            }
        }

        private int getExpectedPartitions(String filterId) {
            // Get from the build stage's partition count
            StageId buildStageId = filterRegistry.getBuildStageId(filterId);
            return stageScheduler.getStagePartitions(buildStageId);
        }

        private void distributeToTargetStages(String filterId, TupleDomain<ColumnHandle> filter) {
            // Find probe-side stages that need this filter
            Set<StageId> targetStages = filterRegistry.getTargetStages(filterId);

            for (StageId stageId : targetStages) {
                // Push JSON TupleDomain to all tasks in the target stage
                SqlStageExecution stage = queryExecution.getStage(stageId);
                stage.addDynamicFilter(filterId, filter);
            }
        }
    }
    ```

    **Integration Points:**
    1. **SqlQueryExecution** - Creates and manages the DynamicFilterCoordinator
    2. **SqlStageExecution** - Forwards TaskStatus updates to the coordinator
    3. **RemoteTask** - Fetches filter data when coordinator requests it
    4. **StageScheduler** - Provides partition counts for filter completion detection
    5. **LocalDynamicFilter** - Automatically merges TupleDomain filters using intersect()

---

## 8. Execution Flow

### 8.1 Query Planning
1. Identify eligible joins for dynamic filtering
2. Add `DynamicFilterSource` nodes above hash builders
3. Add `DynamicFilterConsumer` nodes above table scans
4. Assign unique filter IDs

### 8.2 Filter Communication Flow

**Version-based long-polling (inspired by Trino):**

```
Coordinator                    Build Worker (C++)          Probe Worker (C++)
     |                               |                            |
     | 1. GET /v1/task/{id}/status   |                            |
     |------------------------------>|                            |
     |                               |                            |
     | 2. TaskStatus                 |                            |
     |    dynamicFiltersVersion=1    |                            |
     |<------------------------------|                            |
     |                               |                            |
     | 3. GET /v1/task/{id}/         |                            |
     |    dynamicfilters             |                            |
     |    X-Presto-Current-Version:0 |                            |
     |    X-Presto-Max-Wait:1s       |                            |
     |------------------------------>|                            |
     |                               |                            |
     | 4. VersionedDynamicFilters    |                            |
     |    version=1                  |                            |
     |    filters={df_123: binary}   |                            |
     |<------------------------------|                            |
     |                               |                            |
     | 5. Distribute via             |                            |
     |    DynamicFilterService       |                            |
     |    (in-memory for Java,       |                            |
     |     or callback for C++)      |                            |
     |---------------------------------------------------->|       |
     |                               |                            |
     | 6. Filter applied at scan     |                            |
```

**Key Advantages** (from Trino's design):
- **Single round-trip**: Version change → fetch filters (not: version → IDs → fetch each)
- **Long-polling**: Coordinator waits up to maxWait, reducing overhead
- **Incremental**: Only returns NEW filters since currentVersion
- **Simple**: Just a version number in TaskStatus (8 bytes, not a Set of IDs)

### 8.3 Build Phase (Filter Creation and Storage)
1. Worker builds hash table while collecting statistics
2. Worker determines optimal filter type based on cardinality
3. Worker extracts filter from hash table
4. Worker stores filter locally and increments version number[^build-phase-version]
5. Worker reports new version in TaskStatus[^build-phase-status]

[^build-phase-version]: **Build Phase - Version Increment (C++)**
    ```cpp
    // C++ worker side (Velox)
    void HashBuild::finishHashBuild() {
        // Extract filter from hash table
        auto filter = extractFilterFromHashTable();

        // Store in task's dynamic filter collection
        task_->addDynamicFilter(filterId_, filter);

        // Increment version to signal coordinator
        task_->incrementDynamicFiltersVersion();
    }
    ```

[^build-phase-status]: **Build Phase - TaskStatus Reporting**
    ```java
    // Worker side
    public TaskStatus getTaskStatus() {
        long currentVersion = dynamicFiltersCollector.getVersion();
        return new TaskStatus.Builder()
            .setDynamicFiltersVersion(currentVersion)
            // ... other status fields
            .build();
    }
    ```

### 8.4 Collection Phase (Coordinator Fetches Filters via Long-Polling)
1. Coordinator polls TaskStatus frequently (existing mechanism)
2. Detects version increase in `dynamicFiltersVersion`
3. Long-polls for filter updates using Trino's DynamicFiltersFetcher pattern[^collection-phase-longpoll]

**Long-Polling Benefits**:
- Worker can wait for filters to become available (up to maxWait)
- Reduces polling overhead when filters take time to build
- Single fetch gets all new filters since last version

[^collection-phase-longpoll]: **Collection Phase - Long-Polling Implementation**
    ```java
    // Coordinator side (DynamicFiltersFetcher pattern from Trino)
    public void onVersionChange(TaskId taskId, long newVersion) {
        long currentVersion = getCurrentVersion(taskId);

        if (newVersion <= currentVersion) {
            return; // Already have this version
        }

        // Long-poll for filter data with current version
        Request request = prepareGet()
            .setUri("/v1/task/" + taskId + "/dynamicfilters")
            .setHeader("X-Presto-Current-Version", String.valueOf(currentVersion))
            .setHeader("X-Presto-Max-Wait", "1s")  // Wait up to 1 second
            .build();

        CompletableFuture<VersionedDynamicFilters> response =
            httpClient.executeAsync(request, jsonCodec);

        response.thenAccept(filters -> {
            // Process and distribute new filters
            processCollectedFilters(taskId, filters);
            // Update our version
            updateVersion(taskId, filters.getVersion());
        });
    }
    ```

### 8.5 Distribution Phase (Coordinator Distributes Filters)
1. Coordinator collects filters from build-side workers
2. DynamicFilterService merges filters from all partitions
3. Distributes via DynamicFilterService following Trino's pattern[^distribution-phase-service]

**Distribution Mechanism**:
- **For C++ workers**: Consumers registered via callback, filters pushed when ready
- **For Java workers**: In-memory via `createDynamicFilter()` reactive interface
- **Automatic merge**: Filters from multiple partitions automatically intersected

[^distribution-phase-service]: **Distribution Phase - DynamicFilterService**
    ```java
    // Coordinator side (similar to Trino's DynamicFilterService)
    public void processCollectedFilters(TaskId taskId, VersionedDynamicFilters filters) {
        // Add filters to DynamicFilterService
        for (Map.Entry<DynamicFilterId, Domain> entry : filters.getDynamicFilters().entrySet()) {
            dynamicFilterService.addTaskDynamicFilters(
                taskId,
                ImmutableMap.of(entry.getKey(), entry.getValue()));
        }

        // DynamicFilterService automatically:
        // 1. Merges filters from multiple tasks (for partitioned joins)
        // 2. Notifies registered consumers (probe-side workers)
        // 3. Updates DynamicFilter.isBlocked() futures
    }
    ```

### 8.6 Application
1. Table scans receive filters from coordinator via pull
2. Apply progressively as filters arrive:
   - Skip partitions (metadata level)
   - Skip files (split level)
   - Skip row groups (file format level)
   - Filter rows (execution level)

### 8.7 Adaptive Split Scheduling

The scheduling strategy leverages a simple but effective approach: **optimizer marks scans that benefit from dynamic filters, scheduler delays accordingly**.

#### 8.7.1 Separation of Concerns

**Optimizer (Planning Time)**:
- Cost-based decision: Will dynamic filter save I/O?
- Marks `TableScanNode` with dynamic filter IDs if beneficial
- No complex rules - pure cost/benefit analysis

**Scheduler (Execution Time)**:
- Simple check: Does scan have dynamic filter marker?
- If yes → delay scheduling, wait for filter or timeout
- If no → schedule immediately

**Connector (Runtime)**:
- Receives filter when available
- Decides whether it can use filter for pruning
- Applies filter to partitions/files if possible

#### 8.7.2 Optimizer-Driven Marking

During query planning, the optimizer identifies beneficial dynamic filters through a cost-based analysis[^optimizer-rule-impl]. The key decision logic involves two checks:

**Check 1: Can filter prune at storage level?**

The optimizer must verify that dynamic filters can eliminate partitions/files, not just filter rows. This is determined by checking if join columns match storage organization columns (partitions, buckets) exposed through `ConnectorTableLayout`[^check-storage-pruning].

**Check 2: Is selectivity high enough?**

Only apply dynamic filtering when the build side is selective enough (< 50% of probe side rows) to justify the coordination overhead[^check-selectivity].

**Storage-Level Pruning Check Implementation:**

The core algorithm determines if waiting for a dynamic filter will enable partition/file-level pruning:

    /**
     * Determines if dynamic filter can prune at partition/file level.
     * This is the key decision: only worth waiting if we can skip I/O.
     *
     * Uses existing ConnectorTableLayout properties to determine if filter
     * columns match storage-level organization (partitions/buckets).
     */
    private boolean filterCanPrunePartitions(
            JoinNode join,
            TableScanNode scan,
            Context context) {

        // Step 1: Extract filter columns from join condition
        Set<ColumnHandle> filterColumns = extractFilterColumns(join, scan);

        if (filterColumns.isEmpty()) {
            return false;  // No column mapping found
        }

        // Step 2: Get the table layout (already chosen during layout selection)
        TableHandle tableHandle = scan.getTable();
        if (!tableHandle.getLayout().isPresent()) {
            return false;  // No layout selected yet
        }

        // Use existing SPI to get full layout with all properties
        ConnectorMetadata metadata = context.getMetadata()
            .getMetadataResolver(tableHandle.getCatalogName())
            .getMetadata(tableHandle.getTransaction());

        ConnectorTableLayout layout = metadata.getTableLayout(
            context.getSession().toConnectorSession(tableHandle.getCatalogName()),
            tableHandle.getLayout().get());

        // Step 3: Collect all storage-level organization columns from layout
        Set<ColumnHandle> storageColumns = new HashSet<>();

        // Partition columns (e.g., Hive partitions, Iceberg partition transforms)
        // For JDBC/Memory connectors, this is Optional.empty()
        layout.getDiscretePredicates()
            .map(DiscretePredicates::getColumns)
            .ifPresent(storageColumns::addAll);

        // Bucketing/distribution columns (e.g., Hive bucketed tables)
        layout.getTablePartitioning()
            .map(ConnectorTablePartitioning::getPartitioningColumns)
            .ifPresent(storageColumns::addAll);

        // Stream partitioning columns (other forms of physical organization)
        layout.getStreamPartitioningColumns()
            .ifPresent(storageColumns::addAll);

        // Step 4: Check for overlap
        // If filter columns intersect with storage columns, pruning is possible
        return filterColumns.stream()
            .anyMatch(storageColumns::contains);
    }
```

The algorithm extracts filter columns from join conditions and traces them back through the plan tree to identify their source columns in the table scan[^extract-filter-columns]. It then checks for overlap with storage organization columns.

**Key Insights**:

1. **Leverages Existing Layout Properties**: Filter applicability determined by existing SPI constructs
   - **No new SPI methods needed** - uses `metadata.getTableLayout()` which all connectors implement
   - **DiscretePredicates.getColumns()**: Partition columns (Hive partitions, Iceberg transforms, Delta partitions)
   - **TablePartitioning.getPartitioningColumns()**: Bucket/distribution columns (Hive bucketed tables)
   - **StreamPartitioningColumns**: Other physical organization guarantees

2. **Works for All Connector Types Automatically**:
   - **Hive**: Returns partition columns in `discretePredicates`, bucket columns in `tablePartitioning` → Filter matches → Returns `true`
   - **Iceberg**: Returns partition transform columns in `discretePredicates` → Filter matches → Returns `true`
   - **Delta Lake**: Returns partition columns in `discretePredicates` → Filter matches → Returns `true`
   - **JDBC/Memory**: All layout properties are `Optional.empty()` → No storage columns → Returns `false` (correct behavior!)
   - **Cassandra**: Returns partition keys → Filter matches → Returns `true` only if filter hits partition key

3. **Why This Matters**:
   - Filter on partition column → Skip entire partitions (huge I/O savings) → **Worth waiting**
   - Filter on bucket column → Skip buckets without scanning files → **Worth waiting**
   - Filter on non-storage column → Only row-level filtering → **Not worth waiting**
   - No storage organization (JDBC) → Can't prune at storage level → **Not worth waiting**

4. **Column Tracing**: Handles simple projections and passes through filters, but stops at complex expressions[^column-tracing-details]

5. **Elegant Simplicity**: The **absence** of layout properties automatically means "no storage-level organization" - no connector-type checking needed

Example scenarios demonstrating when dynamic filtering is beneficial are provided in the footnotes[^pruning-examples].

#### 8.7.3 Scheduler Implementation

The scheduler in `SourcePartitionedScheduler` makes a trivial decision:

```java
// In SourcePartitionedScheduler.schedule() around line 220-223

if (scheduleGroup.pendingSplits.isEmpty()) {
    if (scheduleGroup.nextSplitBatchFuture == null) {

        // NEW: Check if this scan should wait for dynamic filters
        if (shouldWaitForDynamicFilter(scheduleGroup)) {
            // Wait for dynamic filter OR timeout OR queue drain
            scheduleGroup.nextSplitBatchFuture =
                scheduleWithDynamicFilterWait(scheduleGroup, lifespan);
        }
        else {
            // IMMEDIATE - fetch splits now
            scheduleGroup.nextSplitBatchFuture = splitSource.getNextBatch(
                scheduleGroup.partitionHandle,
                lifespan,
                splitBatchSize);
        }
    }
}

private boolean shouldWaitForDynamicFilter(ScheduleGroup scheduleGroup) {
    // Simple check: Does the TableScanNode have dynamic filters?
    TableScanNode scan = getTableScanNode(scheduleGroup.planNodeId);
    return scan != null && scan.hasDynamicFilters();
}

private CompletableFuture<SplitBatch> scheduleWithDynamicFilterWait(
        ScheduleGroup scheduleGroup,
        Lifespan lifespan) {

    // Get dynamic filter from coordinator
    DynamicFilter dynamicFilter = getDynamicFilterFor(scheduleGroup);

    if (!dynamicFilter.isAwaitable()) {
        // Filter already complete or not expected - fetch immediately
        return splitSource.getNextBatch(
            scheduleGroup.partitionHandle, lifespan, splitBatchSize);
    }

    // Create futures for wait conditions
    CompletableFuture<?> filterReady = dynamicFilter.isBlocked();
    CompletableFuture<?> timeout = createTimeoutFuture(
        Duration.ofMillis(500)); // Configurable
    CompletableFuture<?> queueDrained = createQueueDrainFuture();

    // Wait for FIRST of: filter ready, timeout, or queue drain
    return CompletableFuture.anyOf(filterReady, timeout, queueDrained)
        .thenCompose(v -> splitSource.getNextBatch(
            scheduleGroup.partitionHandle, lifespan, splitBatchSize));
}
```

#### 8.7.4 Queue-Depth Awareness

To prevent over-scheduling, the scheduler tracks queue depth across all tasks:

```java
private CompletableFuture<?> createQueueDrainFuture() {
    // Get total queued splits across all tasks
    long totalQueuedWeight = stage.getAllTasks().stream()
        .mapToLong(task -> task.getTaskStatus().getQueuedPartitionedSplitsWeight())
        .sum();

    // Calculate threshold (e.g., 70% of total capacity)
    long capacity = maxPendingSplitsWeightPerTask * stage.getAllTasks().size();
    long threshold = (long) (capacity * 0.7);

    if (totalQueuedWeight < threshold) {
        // Queue has space - return completed future
        return CompletableFuture.completedFuture(null);
    }

    // Queue full - wait for it to drain below threshold
    return toWhenHasSplitQueueSpaceFuture(
        stage.getAllTasks(),
        (long) (threshold * 0.5));  // Wake when queue drops to 35%
}
```

**Why This Matters**:
- If probe-side queue is full, scheduling more splits just wastes memory
- Better to wait for EITHER filter (reduces future work) OR queue drain (space for work)
- Self-regulating: adapts to actual execution speed

#### 8.7.5 Connector Usage

Connectors receive the dynamic filter and decide how to use it:

```java
// Inside connector's split source (e.g., HiveSplitSource)
@Override
public CompletableFuture<ConnectorSplitBatch> getNextBatch(
        ConnectorPartitionHandle handle, int maxSize) {

    // Engine delayed calling this, so filter may be available
    TupleDomain<ColumnHandle> filter = dynamicFilter.getCurrentPredicate();

    // Connector decides: Can I use this filter?
    List<Partition> partitions = listPartitions(handle);

    if (canPrunePartitions(filter)) {
        // Filter matches partition columns - prune!
        partitions = partitions.stream()
            .filter(p -> matches(p, filter))
            .collect(toList());
    }
    else if (canPruneFiles(filter)) {
        // Filter matches file statistics - prune files
        List<HiveSplit> splits = partitions.stream()
            .flatMap(p -> listFiles(p).stream())
            .filter(f -> matchesFileStats(f, filter))
            .map(f -> createSplit(f))
            .collect(toList());

        return CompletableFuture.completedFuture(
            new ConnectorSplitBatch(splits, isLastBatch));
    }
    else {
        // Filter doesn't help - ignore it
        // We waited for nothing, but that's OK (only 500ms)
    }

    return generateSplits(partitions, maxSize);
}
```

#### 8.7.6 Configuration

```properties
# Enable dynamic filtering
dynamic-filter.enabled=true

# Maximum time to wait for filter before scheduling splits
dynamic-filter.max-wait-time=500ms

# Queue fullness threshold (0.0 to 1.0)
# When queue exceeds this fraction, don't schedule more splits
dynamic-filter.queue-threshold=0.7

# Session-level overrides
set session dynamic_filter_max_wait_time='1s';
set session dynamic_filter_queue_threshold='0.8';
```

#### 8.7.7 Why This Design Is Simple

**Single Decision Point**: One boolean check in `SourcePartitionedScheduler`

**No New SPI**: Uses existing `getTableLayout()` method and well-established layout properties

**No Connector Changes**: Connectors already handle `DynamicFilter` parameter (Section 7.4) and already populate layout properties correctly

**No Complex Rules**: Optimizer decides if filter helps (cost-based), scheduler just checks marker

**Safe Default**: Worst case is 500ms idle while hash table builds anyway

**Works for All Connectors**: JDBC/Memory return empty layout properties, so check correctly returns false

**Asymmetric Costs**:
- Cost of false positive (delay when filter won't help): 500ms idle
- Cost of false negative (don't delay when filter helps): 90% wasted I/O
- Better to err on side of waiting

#### 8.7.8 Key Insights

1. **Probe always waits for build**: Hash join requires complete hash table before probing, so delaying split scheduling doesn't block execution

2. **Queue-depth prevents over-scheduling**: If queue is full, splits just sit in memory. Better to wait for queue drain or filter arrival.

3. **Optimizer knows best**: Cost-based planning already determines if filter is worth it. Scheduler just follows instructions.

4. **Connector is final arbiter**: Even if we wait, connector decides at runtime whether filter actually helps with pruning.

#### 8.7.9 Example Timeline

```
T0: Query starts
T1: Optimizer marks fact_table scan with dynamic filter
T2: Scheduler sees marker, delays getNextBatch()
T3: Build side starts, processes dimension table
T4: Scheduler waits... (queue empty, so waits for filter or timeout)
T5: Hash build completes (450ms), filter extracted
T6: Filter arrives at scheduler
T7: Scheduler calls splitSource.getNextBatch()
T8: Connector uses filter to prune 95% of partitions
T9: Only 5% of splits scheduled, massive I/O saved

Alternative timeline (filter doesn't help):
T0-T7: Same as above
T8: Connector checks filter, can't prune (wrong columns)
T9: Connector ignores filter, generates all splits
T10: Cost: 500ms delay, but hash table was building anyway
```

#### 8.7.10 Future Enhancements

Once we have production experience:
- **Progressive scheduling**: Schedule small initial batch, then wait
- **Adaptive timeout**: Learn optimal wait time per query pattern
- **Multi-filter coordination**: Optimize when multiple filters expected
- **Filter quality hints**: Optimizer provides selectivity estimate to scheduler

But for V1: **"If optimizer says wait, we wait"** - one boolean check.

---

**Section 8.7 Footnotes:**

[^optimizer-rule-impl]: Full optimizer rule implementation:
    ```java
    // In optimizer rule (e.g., AddDynamicFiltersRule)
    // Works for both broadcast and partitioned joins
    public class AddDynamicFiltersRule implements Rule<JoinNode> {
        @Override
        public Result apply(JoinNode join, Captures captures, Context context) {
            // Find probe-side table scan (traverses through any intermediate nodes)
            TableScanNode probeScan = findTableScanRecursive(join.getProbe());
            if (probeScan == null) {
                return Result.empty();  // No table scan in probe side
            }

            // Check if filter can prune at storage level
            // This is the ONLY check - applies to both broadcast and partitioned
            if (!canPrunePartitionsOrFiles(join, probeScan, context)) {
                return Result.empty();
            }

            // Generate filter ID
            String filterId = generateFilterId(join, buildColumn);

            // Mark join with dynamic filter metadata
            JoinNode newJoin = join.withDynamicFilters(
                ImmutableMap.of(filterId, buildColumn));

            // Mark table scan as consuming this filter
            TableScanNode newScan = probeScan.withDynamicFilters(
                ImmutableSet.of(filterId));

            return Result.ofPlanNode(replaceInTree(newJoin, probeScan, newScan));
        }

        /**
         * Find TableScanNode, traversing through any single-source nodes.
         * Handles ProjectNode, FilterNode, AggregationNode, WindowNode, etc.
         */
        private TableScanNode findTableScanRecursive(PlanNode node) {
            if (node instanceof TableScanNode) {
                return (TableScanNode) node;
            }
            if (node.getSources().size() == 1) {
                return findTableScanRecursive(node.getSources().get(0));
            }
            return null;  // Multi-source or leaf node
        }
    }
    ```

[^simplified-decision]: Simplified decision rule - no selectivity check needed:

    **Why no selectivity check?**
    - Statistics are often stale or inaccurate
    - Worst case is bounded by timeout (500ms)
    - Filter is built anyway (no extra work)
    - If it doesn't help, we only waited 500ms
    - If it does help, we save 1-2 seconds of I/O

    **The rule**: If filter can prune at storage level, apply it. Let timeout prevent unbounded waiting.

[^extract-filter-columns]: Column extraction and tracing implementation:
    ```java
    /**
     * Extracts column handles that will be filtered from join condition.
     * For: probe.customer_id = build.customer_id
     * Returns: {customer_id ColumnHandle from probe table}
     */
    private Set<ColumnHandle> extractFilterColumns(
            JoinNode join,
            TableScanNode probeScan) {

        Set<ColumnHandle> filterColumns = new HashSet<>();

        for (JoinNode.EquiJoinClause clause : join.getCriteria()) {
            // Left side is probe (what we're filtering)
            VariableReferenceExpression probeVariable = clause.getLeft();

            // Find the ColumnHandle by tracing back through the plan
            Optional<ColumnHandle> column = findSourceColumn(
                probeVariable,
                probeScan,
                join.getLeft());

            column.ifPresent(filterColumns::add);
        }

        return filterColumns;
    }

    /**
     * Traces a variable back to its source ColumnHandle.
     * Handles ProjectNode, FilterNode, etc. between join and scan.
     */
    private Optional<ColumnHandle> findSourceColumn(
            VariableReferenceExpression variable,
            TableScanNode targetScan,
            PlanNode currentNode) {

        // Base case: reached the table scan
        if (currentNode instanceof TableScanNode) {
            TableScanNode scan = (TableScanNode) currentNode;
            if (scan.getId().equals(targetScan.getId())) {
                return Optional.ofNullable(scan.getAssignments().get(variable));
            }
            return Optional.empty();
        }

        // Recursive case: trace through ProjectNode
        if (currentNode instanceof ProjectNode) {
            ProjectNode project = (ProjectNode) currentNode;
            RowExpression expression = project.getAssignments().get(variable);

            // If it's just a variable reference, follow it
            if (expression instanceof VariableReferenceExpression) {
                return findSourceColumn(
                    (VariableReferenceExpression) expression,
                    targetScan,
                    project.getSource());
            }

            // Complex expression - can't prune
            return Optional.empty();
        }

        // Recursive case: pass through FilterNode, other nodes
        if (currentNode.getSources().size() == 1) {
            return findSourceColumn(variable, targetScan, currentNode.getSources().get(0));
        }

        // Can't trace through multi-source nodes
        return Optional.empty();
    }
    ```

[^column-tracing-details]: Column tracing behavior details:
    - **Simple projections**: `SELECT customer_id AS cust_id` - follows the rename through ProjectNode
    - **Complex expressions**: `SELECT customer_id * 2` - stops tracing, can't prune on transformed column
    - **Filter passthrough**: `SELECT * FROM t WHERE x > 10` - filters don't change column mappings, tracing continues

[^pruning-examples]: Example scenarios for dynamic filter applicability:
    ```sql
    -- WORTH WAITING: customer_id is a partition column
    SELECT * FROM orders o  -- partitioned by customer_id
    JOIN customers c ON o.customer_id = c.id
    WHERE c.region = 'US';
    -- Filter on customer_id can skip entire partitions

    -- NOT WORTH WAITING: order_amount is not a partition column
    SELECT * FROM orders o  -- partitioned by date
    JOIN customers c ON o.order_amount = c.threshold;
    -- Filter on order_amount only helps at row level

    -- WORTH WAITING: Even with ProjectNode in between
    SELECT * FROM orders o  -- partitioned by customer_id
    JOIN (
        SELECT id, region FROM customers WHERE active = true
    ) c ON o.customer_id = c.id;
    -- Can trace customer_id through the ProjectNode

    -- NOT WORTH WAITING: JDBC table (no partitions)
    SELECT * FROM postgres.orders o
    JOIN postgres.customers c ON o.customer_id = c.id;
    -- Layout returns Optional.empty() for all properties
    -- filterCanPrunePartitions() returns false
    ```

---

### 8.8 Execution Timeline with Delayed Scheduling

This timeline shows how adaptive split scheduling coordinates with filter creation:

```
Time →
T0:   Query starts
      - Optimizer has marked probe-side table scan with dynamic filter
      - Scheduler checks marker and prepares to delay split generation

T1:   Hash build stage starts on dimension table (build side)
      - Build side splits scheduled immediately (no waiting)
      - Workers start building hash table

T2:   Probe side scheduler checks conditions
      - Queue empty? Yes
      - Filter awaitable? Yes
      - Decision: WAIT for filter OR timeout OR queue drain

T3:   Build side processes data (450ms elapsed)
      - Hash table construction in progress
      - Probe side still waiting (within 500ms timeout)

T4:   Hash build completes, filter extracted
      - Worker serializes filter as TupleDomain JSON
      - Worker stores filter and increments dynamicFiltersVersion

T5:   Coordinator detects version change (470ms elapsed)
      - Long-polls /v1/task/{id}/dynamicfilters with currentVersion=0
      - Receives VersionedDynamicFilters (version=1, filters=...)
      - DynamicFilterService distributes to probe-side consumers

T6:   Probe-side scheduler wakes up
      - DynamicFilter.isBlocked() completes
      - Calls splitSource.getNextBatch() with filter available

T7:   Connector generates filtered splits
      - Uses filter to prune partitions/files
      - 95% of splits eliminated
      - Returns only 5% of original splits

T8:   Probe side executes with minimal I/O
      - Only processes data that will actually join
      - Query completes much faster

Total delay: 470ms (hash table was building anyway)
I/O saved: 95% of probe-side table

Alternative timeline (timeout case):
T0-T2: Same as above
T3:   Scheduler waiting... (500ms timeout expires)
T4:   Scheduler calls splitSource.getNextBatch() without filter
T5:   Connector generates all splits (no pruning)
T6:   Hash build completes shortly after, filter arrives
T7:   Filter applied at row-level during scan (not partition/file level)
T8:   Query completes (slower than optimal, but no worse than without DPP)

Total delay: 500ms
I/O saved: ~20-30% (row-level filtering only, no partition pruning)
```

**Key Observations:**

1. **Scheduler delays calling getNextBatch()**: Split generation doesn't start until filter arrives or timeout
2. **No wasted splits**: We never generate splits that will be immediately pruned
3. **Safe timeout**: If filter takes too long, we proceed anyway (conservative approach)
4. **Queue-aware**: If queue fills up while waiting, we stop waiting and generate splits

---

## 9. Performance Optimizations

### 9.1 SIMD Filter Evaluation with Velox

CPU vector instructions (AVX2/AVX512) provide accelerated filter evaluation. Velox's existing Filter infrastructure already supports SIMD for both BigintValues (hash table) and BigintRange filters[^velox-simd-impl].

**SIMD Architecture**:
- **AVX2 (256-bit registers)**: Process 4 × int64_t values per batch
- **AVX512 (512-bit registers)**: Process 8 × int64_t values per batch
- **xsimd library**: Cross-platform SIMD abstraction used by Velox
- **Automatic usage**: Velox's Filter infrastructure uses SIMD through virtual dispatch

**Performance by Filter Type**:

| Filter Type | SIMD Benefit | Why? |
|-------------|--------------|------|
| **BigintValues** (≤10K values) | **3-4x** speedup | True SIMD hash table lookups |
| **BigintRange** (>10K values) | **4-8x** speedup | Perfect SIMD: simple comparisons |

**Performance Impact** (100M row table scan):

**Scenario A: Low Cardinality (1K values) - BigintValues (Hash Table)**:
```
Filter overhead:
- Scalar: 100M × 10 cycles = 1B cycles = 333ms at 3GHz
- SIMD (AVX2): 100M × 3 cycles = 300M cycles = 100ms at 3GHz
- Saved: 233ms in filter evaluation

I/O savings (perfect pruning):
- Skip 75% of partitions/files → 1.5 seconds saved

Total benefit: 1.73 seconds
```

**Scenario B: High Cardinality (50K values) - BigintRange**:
```
Filter overhead:
- Scalar: 100M × 4 cycles = 400M cycles = 133ms at 3GHz
- SIMD (AVX2): 100M × 1 cycle = 100M cycles = 33ms at 3GHz
- Saved: 100ms in filter evaluation

I/O savings (range-based pruning):
- Skip 30% of partitions/files (conservative) → 600ms saved

Total benefit: 700ms
```

**Key Insight**: The I/O reduction (600ms-1.5s) is **6-15x more important** than SIMD optimization (33-233ms). The primary value is in partition/file pruning, not row filtering speed. SIMD is a bonus that comes for free from Velox.

### 9.2 Batching and Compression

HTTP-level batching and compression reduce network overhead when fetching multiple filters[^batching-compression-impl]. Large predicates (>1KB) are automatically compressed, balancing CPU cost against network savings.

### 9.3 Memory Management

Strict memory budgets with automatic degradation prevent OOM conditions[^memory-management-impl]. When memory limits are exceeded, the system degrades gracefully from exact-value to range filters.

### 9.4 Conservative Timeout-Based Waiting

Following **Trino's proven approach**, we use a **0s default** (opt-in strategy)[^timeout-waiting-impl]. The scheduler implementation checks if filters are awaitable, respects configured timeouts, and falls back immediately if timeout is zero.

**Why 0s Default (Trino's Approach)**:

1. **No latency surprises**: Existing workloads see no behavior change
2. **Explicit opt-in**: Users enable when they know it helps their workload
3. **Production proven**: Trino uses this default successfully
4. **Risk-free**: Can't make queries slower by default

**Example Timeout Tuning (Opt-In)**:

```properties
# Default - no waiting (match Trino)
dynamic-filter.max-wait-time=0s

# Opt-in for star schema workloads
dynamic-filter.max-wait-time=1s

# Conservative for mixed workloads
dynamic-filter.max-wait-time=500ms

# Aggressive for ETL with large dimension tables
dynamic-filter.max-wait-time=2s

# Per-session override for specific queries
SET SESSION dynamic_filter_max_wait_time='1s';
```

**Future Enhancement - Cost-Based Timeout:**

For V2, we could add per-query timeout calculation based on estimated build time:

```java
// Future: Calculate timeout based on estimated build time
private Duration calculateAdaptiveTimeout(JoinNode join, StatsProvider stats) {
    PlanNodeStatsEstimate buildStats = stats.getStats(join.getRight());

    // Estimate build time from row count and complexity
    long estimatedBuildTimeMs = estimateBuildTime(buildStats);

    // Wait for 80% of estimated build time (with bounds)
    return Duration.ofMillis(Math.min(
        Math.max((long)(estimatedBuildTimeMs * 0.8), 100),  // Min 100ms
        2000));  // Max 2s
}
```

See Section 8.7.10 for more future enhancements.

---

**Section 9 Footnotes:**

[^velox-simd-impl]: **Velox SIMD Filter Implementation**

    Velox already has SIMD-optimized filters for both BigintValues and BigintRange:

    ```cpp
    // velox/type/Filter.h - Velox's existing SIMD infrastructure

    // BigintValues filter (for discrete values ≤10K)
    // Uses hash table with SIMD lookups
    class BigintValuesUsingHashTable : public Filter {
      // Velox implements SIMD batch processing internally
      // Processes 4-8 values in parallel with AVX2/AVX512
      xsimd::batch_bool<int64_t> testValues(
          xsimd::batch<int64_t> values) const override {
        // Hash table lookup with SIMD
        // 3-4x speedup vs scalar
        return testValuesInHashTable(values, hashTable_);
      }
    };

    // BigintRange filter (for ranges when >10K values)
    // Uses simple min/max comparisons
    class BigintRange : public Filter {
      // Velox implements SIMD batch processing internally
      // Processes 4-8 values in parallel with AVX2/AVX512
      xsimd::batch_bool<int64_t> testValues(
          xsimd::batch<int64_t> values) const override {
        // Simple SIMD comparison: min <= values <= max
        // 4-8x speedup vs scalar (perfect SIMD)
        return (values >= xsimd::broadcast<int64_t>(min_)) &
               (values <= xsimd::broadcast<int64_t>(max_));
      }
    };
    ```

    **Key Points**:
    - Velox already has production-quality SIMD filters
    - No new code needed - TupleDomain automatically converts to these filters
    - BigintValues: 3-4x SIMD speedup for discrete values
    - BigintRange: 4-8x SIMD speedup for ranges (perfect vectorization)
    - Both filters are in velox/type/Filter.h and heavily battle-tested

[^batching-compression-impl]: HTTP-level batching and compression implementation details are extensive. Key features: batch endpoint for fetching multiple predicates, automatic compression for large predicates (>1KB), decompression on coordinator side, individual predicate IDs maintained in batch responses.

[^memory-management-impl]: Memory management with automatic degradation:
    ```cpp
    class FilterMemoryManager {
        bool tryAllocate(size_t bytes) {
            if (usedBytes_ + bytes > maxBytes_) {
                // Degrade to range filter
                return false;
            }
            usedBytes_ += bytes;
            return true;
        }
    };
    ```

[^timeout-waiting-impl]: Conservative timeout-based waiting follows Trino's 0s default. The scheduler checks `dynamicFilter.isAwaitable()`, gets timeout from configuration, returns immediately if timeout is zero, otherwise waits for first of: filter ready, timeout, or queue drain. See Section 8.7.3 for full scheduler implementation.

---

## 10. Implementation Plan

### Phase 1: Foundation (Weeks 1-2)
- [ ] Add dynamicFiltersVersion field to TaskStatus (Thrift)
- [ ] Implement TupleDomain filter extraction in Velox HashBuild
- [ ] Create HTTP endpoints for dynamic filter fetching (long-polling)
- [ ] Add coordinator DynamicFilterCoordinator using LocalDynamicFilter
- [ ] Basic configuration framework

### Phase 2: C++ Integration (Weeks 3-4)
- [ ] Implement TupleDomain JSON serialization in C++
- [ ] Add HTTP client for filter submission in Velox
- [ ] Integrate TupleDomain parsing with Velox TableScan
- [ ] Convert TupleDomain to Velox BigintValues/BigintRange filters
- [ ] C++ unit tests for TupleDomain serialization

### Phase 3: Connector Integration (Weeks 5-6)
- [ ] Velox Hive connector partition pruning with TupleDomain
- [ ] Velox Parquet row group filtering with TupleDomain
- [ ] Velox ORC stripe filtering with TupleDomain
- [ ] Integration tests with C++ workers
- [ ] Performance validation

### Phase 4: Optimizations (Weeks 7-8)
- [ ] Verify Velox SIMD filters work correctly (already implemented)
- [ ] Progressive filter application in connectors
- [ ] Cost-based waiting logic in scheduler
- [ ] Memory management for discrete value collection
- [ ] Performance tuning

### Phase 5: Production Hardening (Weeks 9-10)
- [ ] Comprehensive monitoring
- [ ] Performance tuning
- [ ] Documentation
- [ ] Load testing
- [ ] Migration guide

---

## 11. Testing Strategy

### 11.1 Unit Tests
- TupleDomain filter extraction correctness (discrete values vs ranges)
- JSON serialization round-trip (Java ↔ C++)
- LocalDynamicFilter merging logic (TupleDomain.intersect())
- Memory management for discrete value collection
- Cardinality threshold enforcement (10K limit)

### 11.2 Integration Tests
- End-to-end filter flow with version-based long-polling
- Multi-worker coordination with LocalDynamicFilter
- Connector integration (Hive, Iceberg partitions)
- Progressive application with partial filters
- TupleDomain conversion to Velox BigintValues/BigintRange

### 11.3 Performance Tests
- TPC-H/TPC-DS benchmarks (partition pruning effectiveness)
- Micro-benchmarks for TupleDomain serialization
- Scalability tests (100+ nodes)
- Memory pressure tests (discrete value limit)
- SIMD filter performance (Velox BigintValues/BigintRange)

### 11.4 TupleDomain-Specific Tests

Tests must verify TupleDomain semantics are preserved across JSON serialization and filter merging[^tupledomain-test-impl].

[^tupledomain-test-impl]: **TupleDomain Test Implementation**
    ```cpp
    // Test JSON serialization round-trip in C++
    void testTupleDomainJsonRoundTrip() {
        // Create TupleDomain with discrete values
        auto discreteFilter = createDiscreteTupleDomain({1, 5, 10, 42});

        // Serialize to JSON
        nlohmann::json j;
        facebook::presto::protocol::to_json(j, discreteFilter);
        std::string json = j.dump();

        // Deserialize back
        auto deserialized = facebook::presto::protocol::from_json<TupleDomain>(nlohmann::json::parse(json));

        // Verify equality
        ASSERT_EQ(discreteFilter, deserialized);
    }

    // Test TupleDomain intersection (merging)
    void testTupleDomainIntersection() {
        auto filter1 = createDiscreteTupleDomain({1, 2, 5, 7, 10});
        auto filter2 = createDiscreteTupleDomain({2, 5, 8, 10, 12});

        // Merge via intersection
        auto merged = filter1.intersect(filter2);

        // Verify result contains only common values
        ASSERT_EQ(merged, createDiscreteTupleDomain({2, 5, 10}));
    }

    // Test range intersection
    void testRangeIntersection() {
        auto range1 = createRangeTupleDomain(10, 100);
        auto range2 = createRangeTupleDomain(20, 90);

        auto merged = range1.intersect(range2);

        ASSERT_EQ(merged, createRangeTupleDomain(20, 90));
    }
    ```

---

## 12. Configuration

Following **Trino's production-proven defaults**:

```properties
# Core settings (inspired by Trino)
dynamic-filter.enabled=true
dynamic-filter.max-size=10MB
dynamic-filter.max-wait-time=0s  # Conservative default (match Trino)

# Filter generation (inspired by Trino's small/large distinction)
dynamic-filter.max-distinct-values-per-operator=10000
dynamic-filter.enable-range-filters=true

# Long-polling settings (inspired by Trino)
dynamic-filter.refresh-max-wait=1s  # Long-polling wait time

# Application
dynamic-filter.enable-partition-pruning=true
dynamic-filter.enable-row-level-filtering=true
dynamic-filter.min-selectivity-threshold=0.5

# Performance
dynamic-filter.simd.enabled=true
dynamic-filter.memory-limit=100MB
```

**Session Properties** (allowing per-query tuning):

```sql
-- Enable waiting for specific star schema queries
SET SESSION dynamic_filter_max_wait_time='1s';

-- Aggressive for ETL workloads
SET SESSION dynamic_filter_max_wait_time='2s';

-- Disable for interactive dashboards
SET SESSION dynamic_filter_max_wait_time='0s';
```

**Comparison with Trino**:

| Property | Presto (Our Design) | Trino |
|----------|---------------------|-------|
| `dynamic-filter.enabled` | true | true |
| `dynamic-filter.max-wait-time` | 0s (match Trino) | 0s (per-connector) |
| Max distinct values | 10K | 1K-10K (small/large) |
| Max size per filter | 10MB | 5MB (small/large) |
| Long-polling | Yes | Yes |

---

## 13. Risk Analysis

### 13.1 Technical Risks

| Risk | Impact | Mitigation |
|------|--------|------------|
| Memory pressure from discrete values | OOM | 10K cardinality limit, automatic degradation to range |
| JSON serialization overhead | Latency | Compact JSON, HTTP compression |
| Filter ineffectiveness | No benefit | Cost-based enablement, optimizer checks |
| Cardinality threshold tuning | Suboptimal performance | Monitor and adjust 10K threshold based on workload |
| LocalDynamicFilter merge performance | Slow queries | TupleDomain.intersect() is already optimized |

### 13.2 Operational Risks

| Risk | Impact | Mitigation |
|------|--------|------------|
| Complex debugging | Hard to troubleshoot | Comprehensive logging, tracing, filter version tracking |
| Configuration complexity | Misconfiguration | Sensible defaults (0s timeout), documentation |
| Performance regression | Slower queries | Kill switch, monitoring, opt-in default |
| High cardinality degradation | Range pruning only | Expected behavior, document clearly |

---

## 14. Performance Benchmarks

### 14.1 Expected Improvements

**TPC-H SF1000:**

| Query | Baseline | With DPP | Improvement | Data Reduction |
|-------|----------|----------|-------------|----------------|
| Q3 | 45s | 8s | 82% | 88% |
| Q5 | 120s | 18s | 85% | 92% |
| Q7 | 89s | 15s | 83% | 89% |
| Q9 | 200s | 35s | 82% | 90% |

**Real Workload:**

| Metric | Before | After | Change |
|--------|--------|-------|--------|
| P50 Latency | 10s | 3s | -70% |
| P99 Latency | 60s | 20s | -67% |
| CPU Usage | 85% | 45% | -47% |
| Network I/O | 10GB/s | 3GB/s | -70% |
| Bytes Scanned | 1TB | 150GB | -85% |

### 14.2 Overhead Analysis

| Component | Overhead |
|-----------|----------|
| Filter extraction (TupleDomain) | < 100ms |
| JSON serialization | < 10ms |
| HTTP transmission | < 5ms |
| Filter application (Velox SIMD) | < 1μs per row |
| **Total overhead** | **< 2% of join time** |

**Note**: Primary benefit comes from partition/file pruning (60-90% I/O reduction), not row-level filtering speed.

---

## Appendix A: C++-Only Implementation Benefits

### Simplified Architecture
By focusing exclusively on C++ workers with Velox:

1. **Single Worker Type**: No need to maintain compatibility between Java and C++ implementations
2. **Native Performance**: Full utilization of Velox's optimized execution engine
3. **Unified Memory Management**: C++ memory management throughout the execution path
4. **SIMD Optimizations**: Direct access to CPU vector instructions

### Key Design Decisions

| Component | C++-Only Approach | Benefit |
|-----------|------------------|----------|
| **Filter Extraction** | Velox HashBuild native | Optimal memory usage, TupleDomain building |
| **Serialization** | JSON TupleDomain | Simple, debuggable, reuses existing codec |
| **Communication** | HTTP with JSON | Unified protocol, human-readable |
| **Filter Application** | Velox BigintValues/BigintRange | Deep integration with SIMD-accelerated filters |
| **Memory Management** | C++ allocators | Predictable, efficient, 10K value limit |

### Velox-Native Optimizations

1. **Memory-Efficient Collection in C++**:

   Velox provides efficient data structures for collecting distinct values using folly::F14FastSet with automatic memory tracking and adaptive filter creation[^appendix-velox-collector].

2. **SIMD-Accelerated Filter Application**:

   Velox automatically uses SIMD instructions for filter evaluation[^appendix-simd-filter].

[^appendix-velox-collector]: **VeloxFilterCollector Implementation**
    ```cpp
    class VeloxFilterCollector {
        folly::F14FastSet<int64_t> distinctValues_;
        int64_t minValue_ = std::numeric_limits<int64_t>::max();
        int64_t maxValue_ = std::numeric_limits<int64_t>::min();
        size_t memoryUsage_ = 0;

        void addValue(int64_t value) {
            if (memoryUsage_ < kMaxMemory) {
                distinctValues_.insert(value);
                memoryUsage_ = distinctValues_.getAllocatedMemorySize();
            }
            minValue_ = std::min(minValue_, value);
            maxValue_ = std::max(maxValue_, value);
        }

        std::shared_ptr<common::Filter> createFilter() {
            if (distinctValues_.size() < kMaxDistinctValues) {
                // Create exact values filter
                return common::createBigintValues(distinctValues_, false);
            } else {
                // Create range filter
                return std::make_shared<common::BigintRange>(
                    minValue_, maxValue_, false);
            }
        }
    };
    ```

### Implementation Strategy for C++ Clusters

1. **Leverage Velox Infrastructure**:
   - Use existing Velox HashBuild filter extraction
   - Integrate with Velox's SubfieldFilters
   - Utilize Velox's optimized data structures

2. **Simple Communication**:
   - JSON serialization for compatibility
   - HTTP for reliable transfer
   - TaskStatus for lightweight notifications

3. **Performance Focus**:
   - SIMD operations throughout
   - Zero-copy where possible
   - Minimal serialization overhead

### Performance Benefits

| Metric | C++-Only Design | Notes |
|--------|----------------|--------|
| **Filter Creation** | ~50ms | Velox TupleDomain building |
| **Network Round-trips** | 1 (long-polling fetch) | Version-based, Trino pattern |
| **Serialization** | JSON TupleDomain | ~10ms, reuses existing codec |
| **Filter Application** | < 1μs per row | Velox BigintValues/BigintRange SIMD |
| **Memory Usage** | ≤80KB per filter | 10K value limit enforced |

### Implementation Phases

1. **Phase 1 - Core C++ Implementation**:
   - Velox HashBuild TupleDomain extraction
   - JSON TupleDomain serialization (existing codec)
   - HTTP long-polling endpoints
   - Coordinator LocalDynamicFilter integration

2. **Phase 2 - Optimizations**:
   - Verify Velox SIMD filters work (already exist)
   - Memory pooling for discrete value collection
   - Batch filter distribution with compression
   - Performance tuning

3. **Phase 3 - Advanced Features**:
   - Adaptive cardinality threshold
   - Statistics-based optimization
   - Filter effectiveness tracking

[^appendix-simd-filter]: **SIMD-Accelerated Filter Application with Velox**

    **Velox Already Has SIMD Filters**

    Velox's existing BigintValues and BigintRange filters already support SIMD:

    ```cpp
    // Velox already implements SIMD for filters in velox/type/Filter.h
    // No custom implementation needed!

    // BigintValues filter uses SIMD hash table lookups
    class BigintValuesUsingHashTable : public Filter {
      xsimd::batch_bool<int64_t> testValues(
          xsimd::batch<int64_t> values) const override {
        // SIMD hash table lookup - 3-4x speedup
        return testValuesInHashTable(values, hashTable_);
      }
    };

    // BigintRange filter uses SIMD comparisons
    class BigintRange : public Filter {
      xsimd::batch_bool<int64_t> testValues(
          xsimd::batch<int64_t> values) const override {
        // Simple SIMD range check - 4-8x speedup
        return (values >= min_broadcast) & (values <= max_broadcast);
      }
    };
    ```

    **Key Point**: TupleDomain automatically converts to these optimized Velox filters. No custom SIMD code needed - Velox already handles it!

## Appendix B: Lessons from Trino

Our design incorporates several proven patterns from Trino's production-tested implementation:

### B.1 Protocol Design (Adopted from Trino)

**Version-Based Communication**:
- TaskStatus contains single `long dynamicFiltersVersion` field
- Coordinator fetches filters when version increases
- Simpler than ID-based notification + fetch approach

**Long-Polling Endpoint**:
- `/v1/task/{taskId}/dynamicfilters` with `X-Presto-Current-Version` header
- Worker waits up to `X-Presto-Max-Wait` for filters
- Reduces polling overhead significantly

**Incremental Updates**:
- Response contains only NEW filters since `currentVersion`
- Coordinator merges incrementally
- Efficient for queries with many filters

### B.2 Conservative Defaults (Adopted from Trino)

**0s Wait Time Default**:
- No risk of introducing latency to existing workloads
- Users opt-in when they know it helps
- Production-proven approach

**Per-Connector Configuration** (Trino's approach):
- Trino: `hive.dynamic-filtering.wait-timeout`
- Our approach: Global `dynamic-filter.max-wait-time` + session override
- Trade-off: Simpler vs more granular control

### B.3 Filter Size Limits (Inspired by Trino)

Trino uses sophisticated small/large distinction:
```
Small filters (broadcast):
- Max 1K distinct values per driver
- Max 100KB per driver
- Max 1MB per operator

Large filters (with enable-large-dynamic-filters):
- Max 10K distinct values per driver
- Max 2MB per driver
- Max 5MB per operator
```

Our simpler approach:
- Single threshold: 10K distinct values
- Single size limit: 10MB per filter
- Can adopt Trino's granularity if needed

### B.4 Key Differences from Trino

| Aspect | Trino | Our Design | Reason |
|--------|-------|------------|--------|
| **Language** | Java-only | Java + C++ | Presto has C++ workers |
| **Distribution** | In-memory callbacks | DynamicFilterService + callbacks | Cross-language support |
| **Waiting** | Per-connector | Engine-level | Simpler, consistent |
| **Deadlock Prevention** | Lazy filters + collecting tasks | Queue-depth awareness | Different approach |
| **Retry Support** | Explicit attemptId | To be added | Future work |

### B.5 What We Keep from Original Design

**Queue-Depth Awareness** (Not in Trino):
```java
if (queueFullness > 0.7) {
    return CompletableFuture.completedFuture(null);  // Stop waiting
}
```
Prevents over-scheduling when probe side queue is full.

**Scheduler-Level Waiting** (Different from Trino):
- Trino: Each connector implements wait logic
- Us: Single implementation in `SourcePartitionedScheduler`
- Trade-off: Less flexibility, more consistency

**C++ Worker Support** (Presto-specific):
- JSON TupleDomain serialization for cross-language compatibility
- Reuses existing TupleDomain codec (no custom format needed)
- Explicit version increment from C++ workers

### B.6 Acknowledgments

This design was significantly improved by studying Trino's implementation:
- `DynamicFilterService.java` - Filter collection and distribution
- `DynamicFiltersFetcher.java` - Long-polling pattern
- `BackgroundHiveSplitLoader.java` - Connector-level waiting
- `DynamicFiltersCollector.java` - Version-based incremental updates

The Trino team's production experience informed many of our design choices, particularly around conservative defaults and protocol simplicity.

---

## Appendix C: Future Enhancements

1. **Adaptive Cardinality Threshold** - Dynamically adjust 10K threshold based on workload
2. **Multi-Column Filters** - Support compound filter predicates
3. **GPU Acceleration** - Offload filter evaluation to GPU for massive datasets
4. **Query Retry Support** - Add explicit attemptId tracking (like Trino)
5. **Multi-Stage Filtering** - Progressive refinement across multiple joins
6. **Granular Size Limits** - Adopt Trino's small/large/partitioned distinctions