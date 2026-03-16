Query Results Cache — Design
=============================

Overview
--------

Caches completed SELECT query results so semantically equivalent future queries skip execution entirely. Built on two existing subsystems:

- **HBO canonicalization** — deterministic plan hash as cache key.
- **TempStorage** — pluggable storage (local disk, S3) for serialized result pages.

Intercepts at ``SqlQueryExecution.start()`` after optimization, before scheduling. On cache hit, no scheduler, no fragments, no operators — pages are read from ``TempStorage`` and fed directly to the client via the existing ``OutputBuffer`` → ``ExchangeClient`` → HTTP response pipeline.

No plan modifications. No new ``PlanNode``, ``Operator``, or optimizer. Same principle as the Fragment Result Cache (intercept at boundary, leave plan alone), applied at whole-query level on the coordinator instead of per-split on workers.

::

    SQL → Parse → Analyze → Optimize → Compute plan hash
       ├── HIT:  Read pages from TempStorage → OutputBuffer → Client
       └── MISS: Fragment → Schedule → Execute → Capture pages → Store

Goals
~~~~~

- Eliminate redundant execution of identical SELECTs.
- Pluggable metadata store (in-memory, Redis, etc.) and result storage (TempStorage SPI).
- Input-table-aware invalidation via HBO statistics comparison.
- No plan-level changes.

Non-goals (v1): partial reuse / predicate stitching, DML caching, cross-coordinator sharing, non-deterministic function caching.

Why not FRC for this?
~~~~~~~~~~~~~~~~~~~~~

FRC caches per-split results on worker-local disk. The value depends on the same split hitting the same worker, with the cache hit served from local I/O. With S3-backed storage, FRC would replace one S3 read (source table) with another (cache), at per-split granularity (millions of PUTs/GETs per query), with no locality benefit. The query results cache operates at whole-query granularity on the coordinator — one cache entry per query, amortizing the storage cost.


Cache Key
---------

SHA-256 of the canonicalized query plan, computed via HBO's ``CanonicalPlanGenerator``.

Canonicalization normalizes variable names to positional (``_col_0``, ...), sorts predicates/join criteria, strips source locations, and delegates to ``ConnectorTableLayoutHandle.getIdentifier()`` for connector-specific normalization. Serialized to JSON with deterministic key ordering, then hashed.

**Full key**: ``(canonical_plan_hash, canonicalization_strategy)``

Uses ``CONNECTOR`` strategy (error level 1) by default — supports all node types, preserves filter constants, connector-aware table normalization. Configurable per-session.

Input table statistics are stored in the cache entry for validation, not in the key.


SPI
---

``QueryResultsCacheProvider`` (presto-spi):

.. code-block:: java

    public interface QueryResultsCacheProvider {
        String getName();
        Optional<QueryResultsCacheEntry> getCachedResult(String cacheKey);
        void putCachedResult(String cacheKey, QueryResultsCacheEntry entry);
        void invalidate(String cacheKey);
        List<QueryResultsCacheEntry> removeExpiredEntries();
    }

``QueryResultsCacheEntry`` (presto-spi):

.. code-block:: java

    public class QueryResultsCacheEntry {
        List<byte[]> serializedHandles;          // TempStorage handles
        String tempStorageName;
        List<String> columnNames;
        List<String> columnTypeSignatures;
        long creationTimeMillis;
        long expirationTimeMillis;
        List<PlanStatistics> inputTableStatistics;
        long totalRows;
        long totalBytes;
        Optional<byte[]> encryptionKey;          // DEK
    }

Default implementation: ``InMemoryQueryResultsCacheProvider`` — Guava ``Cache`` with LRU + TTL. Plugin registration follows the ``HistoryBasedPlanStatisticsProvider`` pattern.


Read Path
---------

In ``SqlQueryExecution.start()``, after ``createLogicalPlanAndOptimize()``:

1. Compute canonical plan hash.
2. Look up ``QueryResultsCacheProvider.getCachedResult(key)``.
3. Validate: schema match (column names + types vs current ``OutputNode``), expiration check, input table stats comparison (HBO threshold, default 10%).
4. On hit: ``serveCachedResult()`` — read ``SerializedPage`` objects from ``TempStorage``, enqueue into ``OutputBuffer``, transition to finishing. Client sees no difference from normal execution.
5. On miss: register query ID + cache key for population on completion, proceed with normal scheduling.

Non-deterministic functions (``rand()``, ``now()``, ``uuid()``) are detected during hash computation; cache is not consulted.


Write Path
----------

``QueryResultsCacheWriter`` hooks into query lifecycle via ``addFinalQueryInfoListener`` (same pattern as ``HistoryBasedPlanStatisticsTracker``).

On successful SELECT completion:

1. Check result size ≤ ``max-result-size``.
2. Collect ``TempStorageHandle`` references + input table stats.
3. Build ``QueryResultsCacheEntry``, store via ``putCachedResult()``.

**Page capture** — two modes:

- **Spooling enabled** (preferred): ``SpoolingOutputBuffer`` already writes pages to ``TempStorage``. Add ``retainHandlesForCache`` flag to prevent deletion on client ack. Zero-copy — no additional I/O.
- **Spooling disabled** (fallback): Listener on coordinator's ``ExchangeClient`` accumulates pages, writes to ``TempStorage`` via ``PagesSerdeUtil.writePages()`` after completion (same pattern as ``FileFragmentResultCacheManager.cachePages()``).

Integration point: ``SqlQueryManager.createQuery()``, alongside existing HBO tracking.


Invalidation
------------

- **TTL**: Default 1 hour, configurable per-session. Checked on read.
- **Data-change detection**: Input table stats (``rowCount``, ``outputSize``) compared against cached stats using HBO's threshold (default 10%). Both metrics must be similar for all input tables. Whole-table granularity (not per-partition).
- **Manual bypass**: ``query_results_cache_bypass = true`` — skip read, still populate. ``query_results_cache_invalidate = true`` — skip read, overwrite entry.
- **Storage cleanup**: Background thread calls ``removeExpiredEntries()``, deletes associated ``TempStorage`` files.


Encryption
----------

Pages encrypted via existing ``AesSpillCipher`` / ``PagesSerde`` pipeline (AES-256-CTR, fresh 256-bit key per entry, new IV per operation).

- **Write**: Create ``AesSpillCipher``, serialize pages with cipher-aware ``PagesSerde``, store DEK in ``QueryResultsCacheEntry.encryptionKey``.
- **Read**: Reconstruct cipher from stored DEK, decrypt during deserialization, ``cipher.destroy()`` after.

v1 key management: DEK stored in cache metadata (Option A). Acceptable — attacker with metadata store access already sees query text/schemas; encryption protects against storage-only attacks (S3 bucket misconfiguration). Envelope encryption with KMS (Option B) deferred to v2. Storage-layer encryption (SSE-S3/SSE-KMS) recommended as complementary layer.


Configuration
-------------

**Server properties:**

================================================  =============  ==========================================
Property                                          Default        Description
================================================  =============  ==========================================
``query-results-cache.enabled``                   ``false``      Master switch
``query-results-cache.ttl``                       ``1h``         Default TTL
``query-results-cache.max-result-size``            ``100MB``      Max cacheable result size
``query-results-cache.temp-storage``               ``local``      TempStorage backend name
``query-results-cache.canonicalization-strategy``  ``CONNECTOR``  HBO strategy
``query-results-cache.input-stats-threshold``      ``0.1``        Stats comparison threshold
``query-results-cache.max-cache-entries``           ``1000``       Max entries
``query-results-cache.cleanup-interval``           ``5m``         Expired entry cleanup interval
``query-results-cache.encryption-enabled``         ``true``       Encrypt cached pages
================================================  =============  ==========================================

**Session properties:** ``query_results_cache_enabled``, ``query_results_cache_ttl``, ``query_results_cache_bypass``, ``query_results_cache_invalidate``, ``query_results_cache_canonicalization_strategy``.


Security
--------

- **Shared cache entries**: Cache entries are shared across users. Access control is enforced during the analysis phase (before the cache is consulted), so unauthorized users cannot reach the cache lookup.
- **Non-deterministic functions**: rejected at cache key computation — cache never consulted.


Implementation Plan
-------------------

**New files:**

- ``presto-spi``: ``QueryResultsCacheProvider.java``, ``QueryResultsCacheEntry.java``
- ``presto-main-base``: ``QueryResultsCacheManager.java``, ``QueryResultsCacheWriter.java``, ``QueryResultsCacheConfig.java``, ``InMemoryQueryResultsCacheProvider.java``

**Modified files:** ``SqlQueryExecution.java`` (read path), ``SpoolingOutputBuffer.java`` (handle retention), ``SystemSessionProperties.java``, ``ServerMainModule.java``, ``SqlQueryManager.java``, ``Plugin.java``, ``PluginManager.java``.

No plan-level changes: no new ``PlanNode``, no ``PlanVisitor`` change, no ``LocalExecutionPlanner`` change, no ``PlanOptimizers`` registration.

**Phases:** (1) SPI + metadata store → (2) Write path → (3) Read path → (4) Encryption → (5) Invalidation + cleanup → (6) Testing.


Future Work
------------

- **Predicate stitching**: Partial cache reuse for partition-decomposable queries when only some partitions change. Requires per-partition stats.
- **Cross-coordinator sharing**: Redis-based ``QueryResultsCacheProvider``.
- **Adaptive caching**: Track hit rates per query pattern, skip caching for one-off queries.
