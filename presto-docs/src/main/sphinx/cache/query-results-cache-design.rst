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
- **TempStorage** — pluggable storage (local disk, S3) for serialized result
  pages.

The design is modeled on the **Fragment Result Cache (FRC)**, which caches
per-fragment results at the execution boundary without modifying the query
plan. The query results cache applies the same principle at the whole-query
level: intercept at the execution boundary (``SqlQueryExecution.start()``),
not in the optimizer chain.


Goals and Non-Goals
===================

Goals
-----

- Eliminate redundant execution of identical SELECT queries.
- Pluggable metadata store so deployments can choose in-memory, Redis, or other
  backends.
- Pluggable result storage via the existing ``TempStorage`` SPI.
- Input-table-aware invalidation: cache entries are invalidated when underlying
  table data changes beyond a configurable threshold.
- No plan modifications — caching is transparent to the optimizer, fragmenter,
  and execution engine.

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

    SQL query
       │
       v
    Parse → Analyze → Optimize → Compute canonical plan hash
       │
       ├── Cache HIT ──────────────────────────────┐
       │   (skip scheduling, skip execution)        │
       │                                            v
       │                              Read pages from TempStorage
       │                              Feed into output buffer
       │                              Serve to client
       │
       └── Cache MISS ─────────────────────────────┐
            (normal execution)                      │
                                                    v
            Plan fragmenter → AddExchanges → Distribute → Execute
                                                    │
                                                    v
                                              Query completes
                                              QueryResultsCacheWriter
                                              collects output pages,
                                              writes to TempStorage,
                                              stores cache entry

The key insight, borrowed from the Fragment Result Cache, is: **don't modify
the plan to support caching — intercept at the execution boundary**. On a
cache hit, the coordinator reads pages directly from ``TempStorage`` and feeds
them to the client without creating a scheduler, distributing fragments, or
running any operators.


Lesson from the Fragment Result Cache
=====================================

Presto already has a **fragment result cache** (FRC) that caches per-fragment
results. Studying how it works informs this design.

How FRC works
-------------

FRC operates inside the ``Driver`` at the operator pipeline boundary:

1. **Read path**: When a new split is assigned, the ``Driver`` checks the cache
   (``FragmentResultCacheManager.get(hashedPlan, split)``). On a cache hit,
   cached pages are fed directly to the output operator, bypassing the entire
   operator pipeline (``Driver.java:422-433``).

2. **Write path**: On a cache miss, as pages flow from the second-to-last
   operator to the output operator, the ``Driver`` intercepts and accumulates
   them (``Driver.java:452-454``). After the pipeline completes, the pages are
   written to the cache (``Driver.java:489-498``).

Key properties of FRC:

- **No plan modification**. The plan is identical whether FRC hits or misses.
  The ``Driver`` short-circuits at runtime.
- **Split-granular**. Cache key is ``(hashedCanonicalPlan, SplitIdentifier)``.
- **Worker-local**. Pages never cross the network for caching.
- **No new PlanNode, no new Operator**. It is purely a ``Driver``-level concern.

Why this is better than plan rewriting
--------------------------------------

An earlier draft of this design proposed a ``CachedResultNode`` PlanNode, a
``CachedResultOperator``, and a ``QueryResultsCacheOptimizer`` that rewrites
the plan on cache hit. This had several problems:

- **Large surface area**: Every plan validator, optimizer, and visitor must
  handle the new node type.
- **Coupling to SpoolingOutputBuffer**: The write path depended on retaining
  handles from the spooling output buffer, tangling cache lifecycle with
  output buffer lifecycle.
- **Fragile write path**: Reaching into the output stage's task after query
  completion to extract buffer state is timing-dependent.

The FRC approach — intercept at the boundary, leave the plan alone — is
simpler and more robust.

Comparison table
----------------

=========================  ===========================  ===============================
Aspect                     Fragment Result Cache        Query Results Cache
=========================  ===========================  ===============================
**Level**                  Per-fragment, per-split      Entire query result
**Intercept point**        ``Driver`` (worker)          ``SqlQueryExecution`` (coord.)
**Cache key**              ``(plan hash, split ID)``    ``(canonical plan hash, user)``
**Plan changes**           None                         None
**New operators**          None                         None
**What's cached**          ``List<Page>`` on local disk ``SerializedPage`` in TempStorage
**Where it runs**          Worker nodes                 Coordinator
**Bypass**                 Skips operator pipeline      Skips entire query execution
**Invalidation**           TTL + LRU eviction           TTL + input stats comparison
=========================  ===========================  ===============================

Both caches are **complementary** and can be enabled simultaneously.


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

The hash is computed over the **OutputNode's source** — the root of the query
plan subtree below the output. This includes all joins, filters, aggregations,
projections, and table scans. Two queries that produce equivalent plans after
optimization will have the same hash, regardless of superficial SQL differences
like whitespace, alias names, or predicate order.

Full cache key
--------------

The full cache key is:
``(canonical_plan_hash, canonicalization_strategy, user_identity_hash)``.

Input table statistics are **not** part of the cache key. Instead, they are
stored as metadata in the cache entry and used for **validation** at lookup time
(see `Invalidation`_).

Including the user identity hash ensures cache entries respect access control
(see `Security Considerations`_).


Cache Storage
=============

What gets stored
----------------

Query result pages are written to ``TempStorage`` as ``SerializedPage`` objects
using ``PagesSerdeUtil.writePages()``. This is the same serialization format
used by the fragment result cache, the spill subsystem, and shuffle exchanges.

The ``QueryResultsCacheWriter`` writes pages to ``TempStorage`` directly,
decoupled from the output buffer. This is the same pattern as
``FileFragmentResultCacheManager``, which writes ``List<Page>`` to disk files
independently of the operator pipeline.


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
        Optional<QueryResultsCacheEntry> getCachedResult(String cacheKey);

        /**
         * Store a cache entry after successful query completion.
         */
        void putCachedResult(String cacheKey, QueryResultsCacheEntry entry);

        /**
         * Explicitly invalidate a cache entry (e.g., due to data change).
         */
        void invalidate(String cacheKey);

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
        private final Optional<byte[]> encryptionKey;     // DEK for encrypted entries
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

Cache check in SqlQueryExecution.start()
-----------------------------------------

The cache check happens **after optimization** (we need the optimized plan
to compute the canonical hash) but **before scheduling**. On a cache hit,
scheduling and execution are skipped entirely.

.. code-block:: java

    // In SqlQueryExecution.start()
    @Override
    public void start() {
        try {
            if (!stateMachine.transitionToPlanning()) {
                return;
            }

            PlanRoot plan = createLogicalPlanAndOptimize();

            // NEW: Check the query results cache
            if (isQueryResultsCacheEnabled(getSession())) {
                Optional<String> cacheKey = computeCacheKey(plan);
                if (cacheKey.isPresent()) {
                    Optional<QueryResultsCacheEntry> cached =
                        cacheManager.getCachedResult(cacheKey.get());
                    if (cached.isPresent() && isValid(getSession(), plan, cached.get())) {
                        serveCachedResult(plan, cached.get());
                        return;
                    }
                    // Cache miss: register for population on completion
                    cacheManager.registerForPopulation(
                        getSession().getQueryId(), cacheKey.get(), plan);
                }
            }

            // Normal path: schedule and execute
            metadata.beginQuery(getSession(), plan.getConnectors());
            createQueryScheduler(plan);
            // ... rest of existing start() logic
        }
    }

Serving cached results
~~~~~~~~~~~~~~~~~~~~~~

On a cache hit, the coordinator must feed pages to the client without
creating a scheduler. The flow mirrors what ``createQueryScheduler()`` does
to set up the client output path, but reads pages from ``TempStorage``
instead of from a distributed execution:

.. code-block:: java

    private void serveCachedResult(PlanRoot plan, QueryResultsCacheEntry entry) {
        SubPlan outputStagePlan = plan.getRoot();
        OutputNode outputNode = (OutputNode) outputStagePlan.getFragment().getRoot();

        // 1. Set output column metadata (same as normal path, line 669)
        stateMachine.setColumns(
            outputNode.getColumnNames(),
            outputStagePlan.getFragment().getTypes());

        // 2. Create a local output buffer to feed pages to the client
        //    The Query object's ExchangeClient will read from this buffer
        //    via the standard result-fetching protocol
        OutputBuffers outputBuffers = createInitialEmptyOutputBuffers(SINGLE_DISTRIBUTION)
            .withBuffer(OUTPUT_BUFFER_ID, BROADCAST_PARTITION_ID)
            .withNoMoreBufferIds();

        // 3. Read pages from TempStorage and enqueue into the buffer
        executor.submit(() -> {
            try {
                TempStorage storage = tempStorageManager.getTempStorage(
                    entry.getTempStorageName());
                PagesSerde serde = createPagesSerde(entry);

                for (byte[] handleBytes : entry.getSerializedHandles()) {
                    TempStorageHandle handle = storage.deserialize(handleBytes);
                    try (SliceInput input = new InputStreamSliceInput(
                            storage.open(tempDataOperationContext, handle))) {
                        Iterator<SerializedPage> pages = readSerializedPages(input);
                        while (pages.hasNext()) {
                            outputBuffer.enqueue(Lifespan.taskWide(),
                                ImmutableList.of(pages.next()));
                        }
                    }
                }
                outputBuffer.setNoMorePages();
                stateMachine.transitionToFinishing();
            }
            catch (Exception e) {
                // Cache read failure: fall back to normal execution
                // or fail the query, depending on configuration
                fail(e);
            }
        });
    }

This approach reuses the existing ``OutputBuffer`` → ``ExchangeClient`` →
``Query`` → HTTP response pipeline that the client already expects. The client
sees no difference between a cache hit and a normal execution.

Validation checks
~~~~~~~~~~~~~~~~~

Before serving a cached result, ``isValid()`` checks:

1. **Schema match**: The cached entry's column names and type signatures must
   match the current ``OutputNode``'s output variables. This guards against
   schema evolution (e.g., a column was added to a table).
2. **Expiration**: ``System.currentTimeMillis() < entry.expirationTimeMillis``.
3. **Input table statistics**: Current input table stats (obtained via
   ``CachingPlanCanonicalInfoProvider.getInputTableStatistics()``) must be
   "similar" to the stats recorded in the cache entry — using the same
   threshold-based comparison as HBO (default 10%).
4. **Non-determinism**: Plans containing functions like ``rand()``, ``now()``,
   ``uuid()`` are rejected during ``computeCacheKey()`` (the hash is not
   computed, so the cache is never consulted).


Write Path
==========

QueryResultsCacheWriter
-----------------------

A new component that captures query results on completion and writes them to
``TempStorage`` for future reuse. It hooks into the query lifecycle the same
way ``HistoryBasedPlanStatisticsTracker`` does: via
``addFinalQueryInfoListener``.

The write path follows the same pattern as ``FileFragmentResultCacheManager``:
pages are serialized via ``PagesSerdeUtil.writePages()`` to a storage sink,
independent of the output buffer.

.. code-block:: java

    public class QueryResultsCacheWriter {

        public void onQueryCompletion(QueryExecution queryExecution) {
            queryExecution.addFinalQueryInfoListener(this::cacheResults);
        }

        private void cacheResults(QueryInfo queryInfo) {
            Session session = queryInfo.getSession().toSession(sessionPropertyManager);

            // 1. Check prerequisites
            if (!isQueryResultsCacheEnabled(session)) return;
            if (queryInfo.getFailureInfo() != null) return;
            if (!queryInfo.getQueryType().equals(Optional.of(QueryType.SELECT))) return;

            // 2. Was this query registered for caching during start()?
            Optional<CachePopulationContext> ctx =
                cacheManager.getPopulationContext(queryInfo.getQueryId());
            if (!ctx.isPresent()) return;

            // 3. Check result size limit
            long resultBytes = queryInfo.getQueryStats().getOutputDataSize().toBytes();
            if (resultBytes > getQueryResultsCacheMaxResultSize(session)) return;

            // 4. Collect input table statistics for future validation
            List<PlanStatistics> inputStats = ctx.get().getInputTableStatistics();

            // 5. Build and store cache entry
            QueryResultsCacheEntry entry = new QueryResultsCacheEntry(
                ctx.get().getSerializedHandles(),
                tempStorageName,
                ctx.get().getColumnNames(),
                ctx.get().getColumnTypeSignatures(),
                System.currentTimeMillis(),
                System.currentTimeMillis() + cacheTtlMillis,
                inputStats,
                queryInfo.getQueryStats().getOutputPositions(),
                resultBytes,
                ctx.get().getEncryptionKey());

            cacheProvider.putCachedResult(ctx.get().getCacheKey(), entry);
        }
    }

How pages are captured for the cache
~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~

The write path must capture output pages without interfering with normal
execution. There are two approaches, depending on whether spooling is enabled:

**When spooling is enabled** (recommended):

The ``SpoolingOutputBuffer`` already writes ``SerializedPage`` objects to
``TempStorage``. We add a ``retainHandlesForCache`` flag so that files are not
deleted when the client acknowledges them. After query completion, the
``QueryResultsCacheWriter`` collects the ``TempStorageHandle`` references and
stores them in the cache entry. This is zero-copy — no additional I/O.

.. code-block:: java

    public class SpoolingOutputBuffer implements OutputBuffer {

        private final AtomicBoolean retainHandlesForCache = new AtomicBoolean(false);

        public synchronized List<TempStorageHandle> getRetainedHandles() { ... }

        private class HandleInfo {
            public void removeFile() {
                if (retainHandlesForCache.get()) {
                    return; // keep files for cache
                }
                // ... existing deletion logic
            }
        }
    }

**When spooling is NOT enabled** (general case):

Register a page listener on the coordinator's ``ExchangeClient`` that
accumulates pages as they flow from the output stage to the client. After the
query completes, write the accumulated pages to ``TempStorage`` using
``PagesSerdeUtil.writePages()`` — the same approach FRC uses in
``FileFragmentResultCacheManager.cachePages()``.

.. code-block:: java

    // FRC-style page capture and write
    private void writePagesToCacheStorage(
            List<SerializedPage> pages,
            TempStorage storage,
            Optional<SpillCipher> cipher) {
        TempDataSink sink = storage.create(tempDataOperationContext);
        PagesSerde serde = pagesSerdeFactory.createPagesSerdeForSpill(cipher);
        List<DataOutput> outputs = pages.stream()
            .map(page -> new PageDataOutput(
                cipher.isPresent() ? serde.serialize(page) : page))
            .collect(toImmutableList());
        sink.write(outputs);
        TempStorageHandle handle = sink.commit();
        // Store handle in cache entry
    }

The spooling path is preferred because it avoids buffering all result pages
in memory on the coordinator. The non-spooling path is a fallback for
deployments that don't use spooling.

Integration in SqlQueryManager
~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~

In ``SqlQueryManager.createQuery()``, alongside the existing HBO tracking:

.. code-block:: java

    // Existing (line 324):
    historyBasedPlanStatisticsTracker.updateStatistics(queryExecution);

    // NEW:
    queryResultsCacheWriter.onQueryCompletion(queryExecution);


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

At cache read time, the cache check in ``SqlQueryExecution.start()`` recomputes
the current input table statistics and compares them against the cached stats
using the same threshold as HBO:

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


Encryption
==========

Cached result files live in ``TempStorage`` for extended periods (hours, unlike
spill files which are ephemeral). They must be protected against unauthorized
access at the storage layer.

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

The data flow for encrypted pages is::

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

Encryption in the write path
-----------------------------

When writing pages to the cache, the ``QueryResultsCacheWriter`` creates an
``AesSpillCipher`` and uses a cipher-aware ``PagesSerde`` for serialization:

.. code-block:: java

    // In QueryResultsCacheWriter
    SpillCipher cipher = new AesSpillCipher();
    PagesSerde serde = pagesSerdeFactory.createPagesSerdeForSpill(Optional.of(cipher));

    // Write encrypted pages to TempStorage
    TempDataSink sink = tempStorage.create(context);
    List<DataOutput> outputs = pages.stream()
        .map(page -> new PageDataOutput(serde.serialize(page)))
        .collect(toImmutableList());
    sink.write(outputs);
    TempStorageHandle handle = sink.commit();

    // Extract key for storage in cache entry
    byte[] keyBytes = cipher.getKey();
    // ... store in QueryResultsCacheEntry.encryptionKey

When spooling with ``retainHandlesForCache``, the cipher must be passed to the
``SpoolingOutputBuffer`` so pages are encrypted during flush.

Encryption in the read path
----------------------------

On a cache hit, ``serveCachedResult()`` reconstructs the cipher from the stored
key and uses it for decryption:

.. code-block:: java

    // In SqlQueryExecution.serveCachedResult()
    SpillCipher cipher = new AesSpillCipher(entry.getEncryptionKey().get());
    PagesSerde serde = pagesSerdeFactory.createPagesSerdeForSpill(Optional.of(cipher));

    try (SliceInput input = new InputStreamSliceInput(
            tempStorage.open(context, handle))) {
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

1. **Generation**: When the ``QueryResultsCacheWriter`` writes results, a new
   ``AesSpillCipher`` is created with a fresh 256-bit key.

2. **Persistence**: After pages are written, the key bytes are extracted
   and stored in the ``QueryResultsCacheEntry``.

3. **Read**: On cache hit, ``serveCachedResult()`` reconstructs an
   ``AesSpillCipher`` from the stored key, uses it for decryption, then
   calls ``cipher.destroy()`` to nullify the key in memory.

4. **Expiration**: When the cache entry is evicted or invalidated, the entry
   (including the key) is removed from the metadata store. The associated
   ``TempStorage`` files (which are ciphertext) are deleted.


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


Security Considerations
=======================

Cache entries must respect access control. Two approaches:

1. **User-scoped cache keys**: Include the session identity (user + groups) in
   the cache key. Cache entries are only served to the same user. Simple but
   low hit rate in multi-user environments.

2. **Access-check on read**: Cache entries are shared across users, but before
   serving a cached result, verify that the requesting user has SELECT access
   on all tables referenced in the original plan. This preserves row-level
   security only if row filters are identical across users.

**Recommended for v1**: User-scoped cache keys (option 1). It is safe by
default and easy to implement — include a hash of the user identity in the
cache key. Option 2 can be added later as an optimization for workloads
where cross-user sharing is desired.

Non-deterministic functions
---------------------------

Queries containing non-deterministic functions (``rand()``, ``now()``,
``uuid()``, ``current_timestamp``) must never be cached. The cache key
computation step in ``SqlQueryExecution.start()`` checks for these by
traversing the plan and examining each ``CallExpression`` against a list of
known non-deterministic functions. If any are found, the hash is not computed
and the cache is not consulted.


Implementation Plan
===================

New files
---------

===========================  ==============================================================
Module                       File
===========================  ==============================================================
``presto-spi``               ``spi/QueryResultsCacheProvider.java`` — SPI interface
``presto-spi``               ``spi/QueryResultsCacheEntry.java`` — Cache entry data class
``presto-main-base``         ``execution/QueryResultsCacheManager.java`` — Central manager
``presto-main-base``         ``execution/QueryResultsCacheWriter.java`` — Write path
``presto-main-base``         ``execution/QueryResultsCacheConfig.java`` — Airlift config
``presto-main-base``         ``testing/InMemoryQueryResultsCacheProvider.java``
===========================  ==============================================================

Modified files
--------------

========================================================  ==========================================
File                                                      Change
========================================================  ==========================================
``SqlQueryExecution.java``                                Cache check in ``start()``, cache-hit
                                                          serving via ``serveCachedResult()``
``SpoolingOutputBuffer.java``                             Add ``retainHandlesForCache`` flag and
                                                          ``getRetainedHandles()`` (spooling write
                                                          path only)
``SystemSessionProperties.java``                          Add cache session properties
``FeaturesConfig.java`` (or new config)                   Add cache config properties
``ServerMainModule.java``                                 Wire Guice bindings
``SqlQueryManager.java``                                  Hook ``QueryResultsCacheWriter``
``Plugin.java``                                           Add ``getQueryResultsCacheProviders()``
``PluginManager.java``                                    Register cache provider plugins
========================================================  ==========================================

Note the absence of plan-level changes: no new ``PlanNode``, no
``PlanVisitor`` change, no ``LocalExecutionPlanner`` change, no
``PlanOptimizers`` registration.

Implementation phases
---------------------

1. **Phase 1 — SPI + metadata store**: ``QueryResultsCacheProvider``,
   ``QueryResultsCacheEntry``, ``InMemoryQueryResultsCacheProvider``,
   ``QueryResultsCacheConfig``.

2. **Phase 2 — Write path**: ``QueryResultsCacheWriter``, page capture via
   ``SpoolingOutputBuffer`` handle retention (or ``ExchangeClient`` listener),
   integration in ``SqlQueryManager``.

3. **Phase 3 — Read path**: Cache check in ``SqlQueryExecution.start()``,
   ``serveCachedResult()`` with ``TempStorage`` read and output buffer feed.

4. **Phase 4 — Encryption**: Wire ``AesSpillCipher`` into write and read paths,
   store DEK in cache entry.

5. **Phase 5 — Invalidation + cleanup**: Background cleanup thread, input stats
   validation, session properties for bypass/invalidate.

6. **Phase 6 — Testing**: Unit tests for each component. Integration test:
   run query, verify cache population, run same query, verify cache hit with
   no scheduler creation.


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
