=============================
Query Results Cache — Design
=============================

.. contents:: Table of Contents
   :depth: 3
   :local:

Overview
========

The query results cache stores the output of completed SELECT queries so that
semantically equivalent future queries can skip execution entirely and serve
results directly from storage.

The design reuses two existing Presto subsystems:

- **HBO canonicalization** — produces a deterministic hash of the query plan,
  used as the cache key.
- **Spooling output buffers + TempStorage** — already serialize result pages to
  pluggable storage (local disk, S3), used as the cache storage.

The new component — the **QueryResultsCacheManager** — coordinates lookup,
validation, population, and invalidation.


Goals and Non-Goals
===================

Goals
-----

- Eliminate redundant execution of identical SELECT queries.
- Zero-copy cache population by retaining spooled output files that are already
  written during normal query execution.
- Pluggable metadata store so deployments can choose in-memory, Redis, or other
  backends.
- Pluggable result storage via the existing ``TempStorage`` SPI.
- Input-table-aware invalidation: cache entries are invalidated when underlying
  table data changes beyond a configurable threshold.

Non-Goals (v1)
--------------

- Partial result reuse (predicate stitching). See `Future Work`_.
- Caching of DML (INSERT, CTAS) results.
- Cross-coordinator cache sharing (requires an external metadata store, which
  is supported by the SPI but not built in v1).
- Caching of queries with non-deterministic functions (``rand()``, ``now()``).


Architecture
============

::

                                                       ┌─── Cache MISS ────┐
                                                       │                   │
    SQL query                                          v                   │
       │                                        Normal execution           │
       v                                        (with spooling)            │
    Parse → Analyze → Optimize                         │                   │
       │                                               v                   │
       └──> QueryResultsCacheOptimizer ──┐      Plan fragmenter            │
                    │                    │      AddExchanges               │
               Cache HIT                │      Distribute                  │
                    │                    │      Execute                     │
                    v                    │             │                    │
            CachedResultNode             │             v                   │
            (single fragment,            │      SpoolingOutputBuffer        │
             coordinator only)           │      (retainHandles=true)       │
                    │                    │             │                    │
                    v                    │             v                   │
            CachedResultOperator         │      QueryResultsCacheWriter    │
            reads from TempStorage       │      stores handles in cache    │
                    │                    │                                  │
                    v                    v                                  │
               Serve to client    Serve to client                          │


Cache Key
=========

The cache key is a SHA-256 hash of the **canonicalized query plan**, computed
using HBO's existing ``CanonicalPlanGenerator`` infrastructure.

Plan canonicalization
---------------------

The ``CanonicalPlanGenerator`` (invoked via ``CachingPlanCanonicalInfoProvider``)
traverses the plan tree and produces a ``CanonicalPlan`` by:

1. Renaming all ``VariableReferenceExpression`` to positional names
   (``_col_0``, ``_col_1``, ...).
2. Sorting join criteria, filter predicates, and IN-lists.
3. Removing source location metadata.
4. Calling ``ConnectorTableLayoutHandle.getIdentifier()`` for
   connector-specific normalization (e.g., the Hive connector removes
   partition-column constants so that ``ds='2024-01-01'`` and
   ``ds='2024-01-02'`` produce different identifiers only when the partition
   column value is semantically significant).

The canonical plan is serialized to JSON (with deterministic key ordering via
Jackson's ``ORDER_MAP_ENTRIES_BY_KEYS`` and ``SORT_PROPERTIES_ALPHABETICALLY``)
and then hashed:

.. code-block:: java

    // In CachingPlanCanonicalInfoProvider
    String canonicalPlanString = canonicalPlan.toString(objectMapper);
    String hash = sha256().hashString(canonicalPlanString, UTF_8).toString();

Strategy choice
---------------

HBO defines four canonicalization strategies with increasing coverage
(decreasing accuracy):

==============================  ===========  =======================================
Strategy                        Error Level  Behavior
==============================  ===========  =======================================
``DEFAULT``                     0            Minimal. Only basic nodes (scan, filter,
                                             project, agg, unnest).
``CONNECTOR``                   1            Extends DEFAULT. Supports all node types.
                                             Connector-specific table normalization.
``IGNORE_SAFE_CONSTANTS``       2            Extends CONNECTOR. Removes constants
                                             from ProjectNode.
``IGNORE_SCAN_CONSTANTS``       3            Extends IGNORE_SAFE_CONSTANTS. Also
                                             removes non-partition-column predicates.
==============================  ===========  =======================================

The query results cache uses **``CONNECTOR``** (error level 1) by default. This
is the right balance:

- Two queries with different partition predicates produce different hashes
  (correct — they scan different data).
- Two queries that differ only in irrelevant metadata (source locations,
  variable names) produce the same hash.
- All plan node types are supported (unlike ``DEFAULT``).
- Constants in filter predicates are preserved (unlike ``IGNORE_SAFE_CONSTANTS``
  and ``IGNORE_SCAN_CONSTANTS``), so ``WHERE id > 1`` and ``WHERE id > 1000``
  are correctly treated as different queries.

The strategy is configurable via the ``query_results_cache_canonicalization_strategy``
session property.

What gets hashed
----------------

The hash is computed over the **OutputNode** — the root of every SELECT plan.
This includes the entire plan subtree: all joins, filters, aggregations,
projections, and table scans. Two queries that produce equivalent plans after
optimization will have the same hash, regardless of superficial SQL differences
like whitespace, alias names, or predicate order.

Full cache key
--------------

The full cache key is: ``(canonical_plan_hash, canonicalization_strategy)``.

Input table statistics are **not** part of the cache key. Instead, they are
stored as metadata in the cache entry and used for **validation** at lookup time
(see `Invalidation`_).


Cache Storage
=============

What gets stored
----------------

When spooling is enabled, the ``SpoolingOutputBuffer`` already writes
``SerializedPage`` objects to ``TempStorage`` during query execution. These are
fully encoded result pages ready to be served to clients. The query results
cache reuses these exact files — no additional serialization is needed.

The retention problem
---------------------

Currently, ``SpoolingOutputBuffer.acknowledge()`` deletes spooled files as the
client reads them. By the time a query completes and results are consumed, the
spooled data is gone.

Solution: retainHandles flag
----------------------------

Add a ``retainHandlesForCache`` flag to ``SpoolingOutputBuffer``. When set:

- ``HandleInfo.removeFile()`` becomes a no-op (files are not deleted on
  acknowledge).
- ``getRetainedHandles()`` exposes the list of ``TempStorageHandle`` objects
  after query completion.

This is the simplest approach: no double-write, no extra I/O. The exact same
files written during normal execution become the cache storage.

.. code-block:: java

    public class SpoolingOutputBuffer implements OutputBuffer {

        // NEW: when true, don't delete files on acknowledge
        private final AtomicBoolean retainHandlesForCache = new AtomicBoolean(false);

        // NEW: export handles for caching after query completes
        public synchronized List<RetainedHandleInfo> getRetainedHandles() {
            return handleInfoQueue.stream()
                .map(h -> new RetainedHandleInfo(h.getHandleFuture(), h.getBytes(), h.getPageCount()))
                .collect(toImmutableList());
        }

        private class HandleInfo {
            public void removeFile() {
                if (retainHandlesForCache.get()) {
                    return; // keep files for cache
                }
                // ... existing deletion logic
            }
        }
    }

When the flag is set, file cleanup responsibility transfers to the
``QueryResultsCacheManager``, which deletes the files when the cache entry
expires or is explicitly invalidated.


Cache Metadata Store (SPI)
==========================

A new SPI interface stores the mapping from plan hash to cached result
metadata.

QueryResultsCacheProvider
-------------------------

.. code-block:: java

    // presto-spi
    public interface QueryResultsCacheProvider {
        String getName();

        /**
         * Look up cached results by canonical plan hash.
         * Returns empty if no entry exists or the entry is expired.
         */
        Optional<QueryResultsCacheEntry> getCachedResult(String planHash);

        /**
         * Store a cache entry after successful query completion.
         */
        void putCachedResult(String planHash, QueryResultsCacheEntry entry);

        /**
         * Explicitly invalidate a cache entry (e.g., due to data change).
         */
        void invalidate(String planHash);

        /**
         * Remove expired entries and clean up associated storage.
         * Called periodically by the cache manager.
         */
        List<QueryResultsCacheEntry> removeExpiredEntries();
    }

QueryResultsCacheEntry
----------------------

.. code-block:: java

    // presto-spi
    public class QueryResultsCacheEntry {
        private final List<byte[]> serializedHandles;     // TempStorage handle bytes
        private final String tempStorageName;              // Which TempStorage impl
        private final List<String> columnNames;            // Output column names
        private final List<String> columnTypeSignatures;   // Type signatures
        private final long creationTimeMillis;
        private final long expirationTimeMillis;
        private final List<PlanStatistics> inputTableStatistics; // For invalidation
        private final long totalRows;
        private final long totalBytes;
    }

Default implementation
----------------------

**``InMemoryQueryResultsCacheProvider``** — backed by a Guava ``Cache`` with
LRU eviction and TTL-based expiration. Suitable for single-coordinator
deployments and testing. Follows the same pattern as
``FileFragmentResultCacheManager``.

Plugin registration
-------------------

Follows the same pattern as ``HistoryBasedPlanStatisticsProvider``:

.. code-block:: java

    // In Plugin.java
    default Iterable<QueryResultsCacheProvider> getQueryResultsCacheProviders() {
        return emptyList();
    }

This allows external providers (Redis, distributed caches) to be plugged in
without modifying Presto core.


Read Path
=========

QueryResultsCacheOptimizer
--------------------------

A new ``PlanOptimizer`` that intercepts the plan **after all other
optimizations** but **before AddExchanges**. If the cache has a valid entry,
the plan is rewritten to skip execution entirely.

.. code-block:: java

    public class QueryResultsCacheOptimizer implements PlanOptimizer {
        @Override
        public PlanOptimizerResult optimize(
                PlanNode plan,
                Session session,
                TypeProvider types,
                VariableAllocator variableAllocator,
                PlanNodeIdAllocator idAllocator,
                WarningCollector warningCollector) {

            if (!isQueryResultsCacheEnabled(session)) {
                return PlanOptimizerResult.optimizerResult(plan, false);
            }
            if (!(plan instanceof OutputNode)) {
                return PlanOptimizerResult.optimizerResult(plan, false);
            }

            OutputNode outputNode = (OutputNode) plan;

            // 1. Reject non-deterministic plans
            if (containsNonDeterministicFunctions(outputNode)) {
                return PlanOptimizerResult.optimizerResult(plan, false);
            }

            // 2. Compute canonical plan hash
            PlanCanonicalizationStrategy strategy = getCacheStrategy(session);
            Optional<String> planHash = planCanonicalInfoProvider.hash(
                session, outputNode.getSource(), strategy, false);
            if (!planHash.isPresent()) {
                return PlanOptimizerResult.optimizerResult(plan, false);
            }

            // 3. Check cache
            Optional<QueryResultsCacheEntry> entry =
                cacheManager.getCachedResult(planHash.get());
            if (!entry.isPresent()) {
                // Register this query for cache population on completion
                cacheManager.registerForCaching(session.getQueryId(), planHash.get());
                return PlanOptimizerResult.optimizerResult(plan, false);
            }

            // 4. Validate entry
            if (!validateCacheEntry(session, outputNode, entry.get())) {
                return PlanOptimizerResult.optimizerResult(plan, false);
            }

            // 5. Replace source with CachedResultNode
            CachedResultNode cachedNode = new CachedResultNode(
                outputNode.getSourceLocation(),
                idAllocator.getNextId(),
                outputNode.getOutputVariables(),
                entry.get().getSerializedHandles(),
                entry.get().getTempStorageName());

            OutputNode newOutput = new OutputNode(
                outputNode.getSourceLocation(),
                outputNode.getId(),
                outputNode.getStatsEquivalentPlanNode(),
                cachedNode,
                outputNode.getColumnNames(),
                outputNode.getOutputVariables());

            return PlanOptimizerResult.optimizerResult(newOutput, true);
        }
    }

Validation checks
~~~~~~~~~~~~~~~~~

Before serving a cached result, the optimizer validates:

1. **Schema match**: The cached entry's column names and type signatures must
   match the current OutputNode's output variables.
2. **Expiration**: ``System.currentTimeMillis() < entry.expirationTimeMillis``.
3. **Input table statistics**: Current input table stats (obtained via
   ``CachingPlanCanonicalInfoProvider.getInputTableStatistics()``) must be
   "similar" to the stats recorded in the cache entry — using the same
   threshold-based comparison as HBO (default 10%).
4. **Non-determinism**: Plans containing functions like ``rand()``, ``now()``,
   ``uuid()`` are never served from cache.

Position in optimizer chain
~~~~~~~~~~~~~~~~~~~~~~~~~~~

In ``PlanOptimizers.java``, the ``QueryResultsCacheOptimizer`` is registered
**after** the second ``HistoricalStatisticsEquivalentPlanMarkingOptimizer``
and **before** ``AddExchanges``::

    // After ReorderJoins and second HBO marking pass:
    builder.add(new StatsRecordingPlanOptimizer(optimizerStats,
        new HistoricalStatisticsEquivalentPlanMarkingOptimizer(statsCalculator)));

    // NEW: Query results cache check
    builder.add(new QueryResultsCacheOptimizer(cacheManager, planCanonicalInfoProvider));

    // Then existing exchange planning:
    builder.add(new ReplicateSemiJoinInDelete());
    builder.add(new IterativeOptimizer(..., DetermineJoinDistributionType, ...));

If the cache hits, the resulting plan has a single leaf ``CachedResultNode`` —
no exchanges are needed. ``AddExchanges`` will handle the trivial single-fragment
plan correctly.


CachedResultNode
----------------

A new leaf ``PlanNode`` representing pre-computed results stored in
``TempStorage``.

.. code-block:: java

    // presto-spi
    @Immutable
    public class CachedResultNode extends PlanNode {
        private final List<VariableReferenceExpression> outputVariables;
        private final List<byte[]> serializedHandles;  // Serialized TempStorageHandles
        private final String tempStorageName;

        @Override
        public List<PlanNode> getSources() {
            return emptyList();  // Leaf node — no children
        }

        @Override
        public <R, C> R accept(PlanVisitor<R, C> visitor, C context) {
            return visitor.visitCachedResult(this, context);
        }

        @Override
        public PlanNode replaceChildren(List<PlanNode> newChildren) {
            checkArgument(newChildren.isEmpty(), "newChildren is not empty");
            return this;
        }

        @Override
        public PlanNode assignStatsEquivalentPlanNode(
                Optional<PlanNode> statsEquivalentPlanNode) {
            return new CachedResultNode(
                getSourceLocation(), getId(), statsEquivalentPlanNode,
                outputVariables, serializedHandles, tempStorageName);
        }
    }

Since this is a leaf node with no children, the plan fragmenter places it in
a single fragment running on the coordinator. No distributed execution needed.

Add ``visitCachedResult`` to ``PlanVisitor`` (with default falling through to
``visitPlan``).


CachedResultOperator
--------------------

In ``LocalExecutionPlanner``, add handling for ``CachedResultNode``:

.. code-block:: java

    @Override
    public PhysicalOperation visitCachedResult(
            CachedResultNode node, LocalExecutionPlanContext context) {
        OperatorFactory factory = new CachedResultOperatorFactory(
            context.getNextOperatorId(),
            node.getId(),
            tempStorageManager.getTempStorage(node.getTempStorageName()),
            node.getSerializedHandles(),
            tempDataOperationContext);
        return new PhysicalOperation(factory, makeLayout(node), context,
            UNGROUPED_EXECUTION);
    }

The operator:

1. Deserializes ``TempStorageHandle`` references via
   ``TempStorage.deserialize()``.
2. Opens each handle via ``TempStorage.open()`` — returns an ``InputStream``.
3. Reads ``SerializedPage`` objects using existing
   ``PagesSerdeUtil.readSerializedPages()``.
4. Outputs pages to the pipeline.

This is essentially the same read logic already in
``SpoolingOutputBuffer.getPagesFromStorage()``, extracted into an operator.


Write Path
==========

QueryResultsCacheWriter
-----------------------

A new component that captures query results on completion and stores them in
the cache. It hooks into the query lifecycle the same way
``HistoryBasedPlanStatisticsTracker`` does.

.. code-block:: java

    public class QueryResultsCacheWriter {
        public void onQueryCompletion(QueryExecution queryExecution) {
            queryExecution.addFinalQueryInfoListener(this::cacheResults);
        }

        private void cacheResults(QueryInfo queryInfo) {
            Session session = queryInfo.getSession().toSession(sessionPropertyManager);

            // 1. Check prerequisites
            if (!isQueryResultsCacheEnabled(session)) return;
            if (queryInfo.getFailureInfo() != null) return;  // Only cache successful queries
            if (!queryInfo.getQueryType().equals(Optional.of(QueryType.SELECT))) return;
            if (!isSpoolingOutputBufferEnabled(session)) return;

            // 2. Check if this query was registered for caching during optimization
            Optional<String> planHash = cacheManager.getRegisteredHash(queryInfo.getQueryId());
            if (!planHash.isPresent()) return;

            // 3. Check result size limit
            long resultBytes = queryInfo.getQueryStats().getOutputDataSize().toBytes();
            if (resultBytes > getQueryResultsCacheMaxResultSize(session)) return;

            // 4. Collect TempStorageHandles from the spooling output buffer
            //    (accessed via the output stage's root task)
            List<TempStorageHandle> handles = getRetainedHandles(queryInfo);
            if (handles.isEmpty()) return;

            // 5. Serialize handles
            List<byte[]> serializedHandles = handles.stream()
                .map(h -> tempStorage.serializeHandle(h))
                .collect(toImmutableList());

            // 6. Collect input table statistics for future validation
            List<PlanStatistics> inputStats = planCanonicalInfoProvider
                .getInputTableStatistics(session, plan.getRoot(), strategy, true)
                .orElse(ImmutableList.of());

            // 7. Build and store cache entry
            QueryResultsCacheEntry entry = new QueryResultsCacheEntry(
                serializedHandles,
                tempStorageName,
                columnNames,
                columnTypeSignatures,
                System.currentTimeMillis(),
                System.currentTimeMillis() + cacheTtlMillis,
                inputStats,
                totalRows,
                resultBytes);

            cacheProvider.putCachedResult(planHash.get(), entry);
        }
    }

Integration in SqlQueryManager
~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~

In ``SqlQueryManager.createQuery()``, alongside the existing HBO tracking:

.. code-block:: java

    // Existing:
    historyBasedPlanStatisticsTracker.updateStatistics(queryExecution);

    // NEW:
    queryResultsCacheWriter.onQueryCompletion(queryExecution);

Getting handles from the buffer
~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~

The coordinator's output stage root task owns the ``SpoolingOutputBuffer``.
The access path:

1. At planning time, if caching is enabled for this query, set
   ``retainHandlesForCache = true`` on the ``SpoolingOutputBuffer`` once it is
   created. This is done via a callback on the output stage's task creation.
2. At query completion, the ``QueryResultsCacheWriter`` accesses the retained
   handles through the ``QueryInfo``'s output stage, which references the
   ``SqlTask`` and its ``OutputBuffer``.
3. Each ``TempStorageHandle`` is serialized via
   ``TempStorage.serializeHandle()`` for durable storage in the cache entry.


Invalidation
============

TTL-based
---------

Each ``QueryResultsCacheEntry`` has an ``expirationTimeMillis``. The
``getCachedResult()`` method checks this before returning. Default TTL is 1
hour, configurable per-session.

Data-change detection
---------------------

At cache write time, the ``QueryResultsCacheWriter`` stores the
``List<PlanStatistics>`` for all input tables — the same per-table aggregate
``(rowCount, outputSize)`` stats that HBO uses.

At cache read time, the ``QueryResultsCacheOptimizer`` recomputes the current
input table statistics and compares them against the cached stats using the
same threshold as HBO:

.. code-block:: java

    boolean similar = stats1 >= (1 - threshold) * stats2
                   && stats1 <= (1 + threshold) * stats2;

Both ``rowCount`` **and** ``outputSize`` must be similar for **all** input
tables. The default threshold is 10% (``0.1``), matching HBO's
``hbo.history-matching-threshold``.

This means:

- If a table has significantly more/fewer rows than when the result was cached,
  the entry is treated as stale and a cache miss occurs.
- Small changes (within 10%) are tolerated. This is appropriate for data
  warehouse workloads where tables grow gradually.

Note on granularity
~~~~~~~~~~~~~~~~~~~

HBO tracks input table statistics at **whole-table granularity** — a single
``(rowCount, outputSize)`` pair per leaf ``TableScanNode``, not per-partition.
The ``Constraint<ColumnHandle>`` is passed to ``metadata.getTableStatistics()``
so the connector may apply partition pruning when computing these aggregate
stats, but individual partition statistics are not tracked.

This means small data changes within a single partition may not exceed the
threshold and could result in stale cache hits. For workloads where this matters,
a shorter TTL is recommended.

Manual invalidation
-------------------

- **Session property**: ``SET SESSION query_results_cache_bypass = true`` skips
  cache read but still populates the cache (useful for forced refresh).
- **Session property**: ``SET SESSION query_results_cache_invalidate = true``
  skips cache read **and** overwrites the existing entry.

Storage cleanup
---------------

When a cache entry expires or is invalidated, the associated ``TempStorage``
files must be deleted. The ``QueryResultsCacheManager`` runs a periodic
background task that:

1. Calls ``QueryResultsCacheProvider.removeExpiredEntries()`` to collect expired
   entries.
2. For each expired entry, deserializes the ``TempStorageHandle`` objects and
   calls ``TempStorage.remove()`` for each.

This is analogous to ``FileFragmentResultCacheManager``'s
``CacheRemovalListener``, which deletes files on eviction.


Configuration
=============

Server-level properties
-----------------------

=================================================  =========  =============================================
Property                                           Default    Description
=================================================  =========  =============================================
``query-results-cache.enabled``                    ``false``  Master switch
``query-results-cache.ttl``                        ``1h``     Default cache entry TTL
``query-results-cache.max-result-size``            ``100MB``  Max result size eligible for caching
``query-results-cache.temp-storage``               ``local``  TempStorage name for cached results
``query-results-cache.canonicalization-strategy``   ``CONNECTOR``  HBO strategy for hashing
``query-results-cache.input-stats-threshold``      ``0.1``    Threshold for input stats comparison
``query-results-cache.max-cache-entries``           ``1000``   Max entries in the cache
``query-results-cache.cleanup-interval``           ``5m``     How often to clean up expired entries
``query-results-cache.encryption-enabled``         ``true``   Encrypt cached result pages (AES-256-CTR)
=================================================  =========  =============================================

Session properties
------------------

=============================================  ==================================================
Property                                       Description
=============================================  ==================================================
``query_results_cache_enabled``                Per-session enable/disable
``query_results_cache_ttl``                    Per-session TTL override
``query_results_cache_bypass``                 Skip cache read (still populates on completion)
``query_results_cache_invalidate``             Skip cache read AND overwrite existing entry
``query_results_cache_canonicalization_strategy``  Per-session strategy override
=============================================  ==================================================


Comparison with Fragment Result Cache
=====================================

Presto already has a **fragment result cache**
(``FileFragmentResultCacheManager``) that caches the output of individual plan
fragments (typically table scans) keyed by ``(serializedPlan, splitIdentifier)``.

=========================  ===========================  ===============================
Aspect                     Fragment Result Cache        Query Results Cache
=========================  ===========================  ===============================
**Granularity**            Per-fragment, per-split      Entire query result
**Cache key**              ``(plan JSON, split ID)``    ``(canonical plan hash)``
**What's cached**          ``List<Page>`` on local disk ``SerializedPage`` in TempStorage
**Where it runs**          Worker nodes                 Coordinator
**Bypass?**                Skips a single scan          Skips the entire query
**Hit rate**               High for repeated scans      High for repeated queries
**Invalidation**           TTL + LRU eviction           TTL + input stats comparison
=========================  ===========================  ===============================

The two caches are **complementary**:

- Fragment result cache helps when a query shares scan fragments with previous
  queries but differs in its overall plan.
- Query results cache helps when the entire query is repeated.

Both can be enabled simultaneously.


Implementation Plan
===================

New files
---------

===========================  ==============================================================
Module                       File
===========================  ==============================================================
``presto-spi``               ``spi/QueryResultsCacheProvider.java`` — SPI interface
``presto-spi``               ``spi/QueryResultsCacheEntry.java`` — Cache entry data class
``presto-spi``               ``spi/plan/CachedResultNode.java`` — New PlanNode
``presto-main-base``         ``execution/QueryResultsCacheManager.java`` — Central manager
``presto-main-base``         ``execution/QueryResultsCacheWriter.java`` — Write path
``presto-main-base``         ``execution/QueryResultsCacheConfig.java`` — Airlift config
``presto-main-base``         ``sql/planner/optimizations/QueryResultsCacheOptimizer.java``
``presto-main-base``         ``operator/CachedResultOperator.java`` — Operator + factory
``presto-main-base``         ``testing/InMemoryQueryResultsCacheProvider.java``
===========================  ==============================================================

Modified files
--------------

========================================================  ==========================================
File                                                      Change
========================================================  ==========================================
``PlanVisitor.java``                                      Add ``visitCachedResult()``
``SpoolingOutputBuffer.java``                             Add ``retainHandlesForCache`` flag,
                                                          ``getRetainedHandles()``, and
                                                          ``Optional<SpillCipher>`` for encryption
``SystemSessionProperties.java``                          Add cache session properties
``FeaturesConfig.java`` (or new config)                   Add cache config properties
``PlanOptimizers.java``                                   Register ``QueryResultsCacheOptimizer``
``LocalExecutionPlanner.java``                            Handle ``CachedResultNode``
``ServerMainModule.java``                                 Wire Guice bindings
``SqlQueryManager.java``                                  Hook ``QueryResultsCacheWriter``
``Plugin.java``                                           Add ``getQueryResultsCacheProviders()``
``PluginManager.java``                                    Register cache provider plugins
========================================================  ==========================================

Implementation phases
---------------------

1. **Phase 1 — SPI + metadata store**: ``QueryResultsCacheProvider``,
   ``QueryResultsCacheEntry``, ``InMemoryQueryResultsCacheProvider``,
   ``QueryResultsCacheConfig``.

2. **Phase 2 — Write path**: ``SpoolingOutputBuffer`` changes (retainHandles),
   ``QueryResultsCacheWriter``, integration in ``SqlQueryManager``.

3. **Phase 3 — Read path**: ``CachedResultNode``, ``PlanVisitor`` update,
   ``QueryResultsCacheOptimizer``, ``CachedResultOperator``,
   ``LocalExecutionPlanner`` update, ``PlanOptimizers`` registration.

4. **Phase 4 — Invalidation + cleanup**: Background cleanup thread, input stats
   validation, session properties for bypass/invalidate.

5. **Phase 5 — Testing**: Unit tests for each component. Integration test:
   run query, verify cache population, run same query, verify cache hit.


Security Considerations
=======================

Cache entries must respect access control. Two approaches:

1. **User-scoped cache keys**: Include the session identity (user + groups) in
   the cache key. Cache entries are only served to the same user. Simple but
   low hit rate in multi-user environments.

2. **Access-check on read**: Cache entries are shared across users, but before
   serving a cached result, the optimizer verifies that the requesting user has
   SELECT access on all tables referenced in the original plan. This preserves
   row-level security (if any) only if row filters are identical across users.

**Recommended for v1**: User-scoped cache keys (option 1). It is safe by
default and easy to implement — just include a hash of the user identity in
the cache key. Option 2 can be added later as an optimization for workloads
where cross-user sharing is desired.

Non-deterministic functions
---------------------------

Queries containing non-deterministic functions (``rand()``, ``now()``,
``uuid()``, ``current_timestamp``) must never be cached. The
``QueryResultsCacheOptimizer`` checks for these by traversing the plan and
examining each ``CallExpression`` against a list of known non-deterministic
functions.


Encryption
==========

Cached result files live in ``TempStorage`` for extended periods (hours, unlike
spill files which are ephemeral). They must be protected against unauthorized
access at the storage layer.

Current state: spooling has no encryption
-----------------------------------------

Today, the ``SpoolingOutputBuffer`` writes raw ``SerializedPage`` bytes to
``TempStorage`` with **no encryption**. The existing encryption infrastructure
— ``AesSpillCipher`` (AES-256-CTR) and ``PagesSerde`` with ``SpillCipher``
support — is wired only for spill-to-disk (hash joins, sorts exceeding memory),
not for spooling output buffers.

=======================  ==============================  ============================
Aspect                   Spill (joins/sorts)             Spooling Output Buffer
=======================  ==============================  ============================
Encryption               AES-256-CTR via ``SpillCipher`` None
Config                   ``spill-encryption-enabled``    No equivalent
Key lifetime             Per-spiller, destroyed on close N/A
Pages written by         ``PagesSerde`` with cipher      ``PageDataOutput`` — raw
=======================  ==============================  ============================

Existing encryption primitives
------------------------------

Presto already has a complete page-level encryption pipeline:

1. **``SpillCipher``** interface
   (``presto-spi/.../spi/spiller/SpillCipher.java``):
   ``encrypt(byte[])`` and ``decrypt(byte[])`` with ``destroy()`` for key
   lifecycle.

2. **``AesSpillCipher``** implementation
   (``presto-main-base/.../spiller/AesSpillCipher.java``):
   AES-256-CTR with a fresh 256-bit key per cipher instance and a new IV per
   encryption operation.

3. **``PagesSerde``** (``presto-spi/.../spi/page/PagesSerde.java``):
   ``serialize()`` applies compression then encryption (if cipher present);
   ``deserialize()`` applies decryption then decompression. The ``ENCRYPTED``
   bit in ``PageCodecMarker`` tracks whether a ``SerializedPage`` is encrypted.

4. **``PageCodecMarker``** (``presto-spi/.../spi/page/PageCodecMarker.java``):
   Bit-flag enum with ``COMPRESSED`` (bit 1), ``ENCRYPTED`` (bit 2),
   ``CHECKSUMMED`` (bit 3).

The data flow for encrypted spill is::

    Write: Page → serialize → compress → encrypt → set ENCRYPTED marker → write
    Read:  read → check ENCRYPTED marker → decrypt → decompress → Page

Threat model
------------

Three threats to address:

**1. Storage-level access** — An attacker reads ``TempStorage`` files directly
(filesystem access for local storage, bucket access for S3).

*Mitigation*: Encrypt pages before writing, using the existing
``AesSpillCipher`` / ``PagesSerde`` pipeline.

**2. Key management** — Where does the encryption key live, and how long?

For spill, each ``AesSpillCipher`` generates a fresh key in memory and destroys
it when the spiller closes. The key never leaves the JVM and the same JVM that
writes also reads. For the results cache, data outlives the query (and
potentially the JVM), so the key must be persisted alongside the cache entry.

*Mitigation*: Store the data encryption key (DEK) in the cache metadata entry.
See `Key management options`_ below.

**3. Handle guessability** — An attacker constructs a ``TempStorageHandle``
path and reads cached results without going through Presto.

*Mitigation*: ``LocalTempStorage`` already uses ``randomUUID()`` for filenames.
Combined with encryption, knowing the path is insufficient — the data is
ciphertext without the key.

Encryption design
-----------------

Wire ``SpillCipher`` into the spooling + cache path
~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~

.. code-block:: java

    public class SpoolingOutputBuffer implements OutputBuffer {

        // NEW: optional cipher for encrypting spooled pages
        private final Optional<SpillCipher> spillCipher;

        private synchronized void flush() {
            // Instead of raw PageDataOutput, use cipher-aware serialization
            PagesSerde serde = pagesSerdeFactory.createPagesSerdeForSpill(spillCipher);
            List<DataOutput> dataOutputs = pages.stream()
                .map(page -> new PageDataOutput(serde.serialize(page)))
                .collect(toImmutableList());

            ListenableFuture<TempStorageHandle> handleFuture = executor.submit(() -> {
                TempDataSink dataSink = tempStorage.create(tempDataOperationContext);
                dataSink.write(dataOutputs);
                return dataSink.commit();
            });
            // ... rest of existing flush logic
        }
    }

This also fixes the pre-existing gap where spooled output data is unencrypted,
independent of the cache feature.

Store the DEK in the cache entry
~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~

.. code-block:: java

    public class QueryResultsCacheEntry {
        // ... existing fields ...
        private final Optional<byte[]> encryptionKey;  // 256-bit AES DEK
    }

The ``QueryResultsCacheWriter`` extracts the key from the ``SpillCipher``
before it is destroyed:

.. code-block:: java

    // In QueryResultsCacheWriter.cacheResults():
    Optional<byte[]> encryptionKey = Optional.empty();
    if (spillCipher.isPresent()) {
        encryptionKey = Optional.of(spillCipher.get().getKey());
        // Do NOT destroy the cipher yet — the key is transferred to the cache entry
    }

The ``CachedResultOperator`` reconstructs the cipher from the stored key for
decryption:

.. code-block:: java

    // In CachedResultOperator:
    SpillCipher cipher = new AesSpillCipher(entry.getEncryptionKey().get());
    PagesSerde serde = pagesSerdeFactory.createPagesSerdeForSpill(Optional.of(cipher));

    try (SliceInput input = ...) {
        Iterator<SerializedPage> pages = readSerializedPages(input);
        // Pages are decrypted during deserialization via the serde
    }
    finally {
        cipher.destroy();  // Nullify key from memory
    }

.. _`Key management options`:

Key management options
~~~~~~~~~~~~~~~~~~~~~~

Three options with different security/complexity tradeoffs:

**Option A: DEK stored in cache metadata store (recommended for v1)**

The 256-bit AES key is stored as a field in ``QueryResultsCacheEntry``,
persisted in the ``QueryResultsCacheProvider`` (in-memory map, Redis, etc.).

- *Pro*: Simple. No external dependencies. Matches existing spill model.
- *Con*: If the metadata store is compromised, the attacker has both key and
  data location. Security depends entirely on the metadata store's access
  control.
- *Assessment*: Acceptable for v1. An attacker with access to the metadata
  store can already see query text, schemas, and table names. The DEK being
  co-located does not significantly worsen the threat model. Encryption still
  protects against storage-only attacks (e.g., S3 bucket misconfiguration).

**Option B: Envelope encryption with external KMS (future)**

Generate a fresh DEK per cache entry. Encrypt the DEK with a KMS master key.
Store only the wrapped (encrypted) DEK in the cache entry. On read, call KMS
to unwrap the DEK, then decrypt pages.

- *Pro*: Proper key management. Compromise of metadata store alone is
  insufficient — attacker also needs KMS access.
- *Con*: Adds KMS dependency and latency on cache reads.
- *Assessment*: Right for security-sensitive deployments. Implement as a v2
  enhancement, potentially as a pluggable ``CacheKeyManager`` SPI.

**Option C: Storage-layer encryption (defense in depth)**

Use S3 server-side encryption (SSE-S3, SSE-KMS) or encrypted filesystems
(LUKS, dm-crypt) for local disk. This is orthogonal to Presto-level encryption
and can be enabled independently.

- *Pro*: Zero code in Presto. Transparent.
- *Con*: SSE-S3 decrypts transparently on read, so does not protect against
  an attacker with S3 API access. SSE-KMS helps but requires IAM policy.
- *Assessment*: Recommended as a **complementary** layer, not a replacement
  for Presto-level encryption.

**v1 recommendation**: Option A + Option C. Presto-level AES-256-CTR encryption
with the DEK in the metadata store, plus storage-layer encryption as defense in
depth. Option B (KMS) as a future enhancement.

Key lifecycle
~~~~~~~~~~~~~

1. **Generation**: When a query is registered for caching, a new
   ``AesSpillCipher`` is created with a fresh 256-bit key. The cipher is
   passed to the ``SpoolingOutputBuffer`` for encrypting pages during flush.

2. **Persistence**: After the query completes, the key bytes are extracted
   from the cipher and stored in the ``QueryResultsCacheEntry``.

3. **Read**: On cache hit, the ``CachedResultOperator`` reconstructs an
   ``AesSpillCipher`` from the stored key, uses it for decryption, then
   calls ``cipher.destroy()`` to nullify the key in memory.

4. **Expiration**: When the cache entry is evicted or invalidated, the entry
   (including the key) is removed from the metadata store. The associated
   ``TempStorage`` files (which are ciphertext) are deleted.

Configuration
~~~~~~~~~~~~~

====================================================  =========  =====================================
Property                                              Default    Description
====================================================  =========  =====================================
``query-results-cache.encryption-enabled``            ``true``   Encrypt cached result pages
====================================================  =========  =====================================

Default is **on**. Unlike spill encryption (which trades CPU for security on
ephemeral data), cached data lives for hours and the performance cost of
AES-256-CTR is negligible relative to I/O.

Security summary
~~~~~~~~~~~~~~~~

=========================  ================================================  =============
Layer                      Protection                                        Status
=========================  ================================================  =============
Page-level encryption      AES-256-CTR via existing ``SpillCipher``/          Wire into
                           ``PagesSerde``                                     spooling path
Key storage                DEK stored in ``QueryResultsCacheEntry``           New field
Key management             In-process generation, metadata store storage      v1
                           KMS envelope encryption                            Future (v2)
Storage-layer encryption   S3-SSE or encrypted disk                           Orthogonal
Handle guessability        UUID-based filenames                               Already exists
Access control             User-scoped cache keys                             Already in
                                                                              design
Key lifecycle              ``cipher.destroy()`` nullifies in-memory key       Follow spill
                                                                              pattern
=========================  ================================================  =============


.. _`Future Work`:

Future Work
===========

Predicate stitching for partial cache reuse
-------------------------------------------

A cached query result is conceptually an **anonymous materialized view**. When
underlying data changes in only a subset of partitions, it may be possible to
serve the cached result for unchanged partitions and recompute only the changed
portions — analogous to the predicate stitching mechanism being developed for
explicit materialized views (see prestodb/presto#26728).

This requires:

- Per-partition input table statistics (HBO currently tracks only whole-table
  aggregates).
- Stitchability analysis (only scan-only or partition-decomposable aggregation
  queries can be stitched).
- Integration with the ``MaterializedViewScanNode`` dual-path structure.

This is a substantial extension and is deferred to a future version.

Cross-coordinator cache sharing
-------------------------------

The ``QueryResultsCacheProvider`` SPI supports external backends (Redis, etc.)
that can be shared across coordinators. Building and testing a Redis-based
provider is future work.

Adaptive caching
----------------

Not all queries benefit from caching. Future work could track cache hit rates
per query pattern and adaptively decide which results to cache, avoiding storage
waste on one-off analytical queries.
