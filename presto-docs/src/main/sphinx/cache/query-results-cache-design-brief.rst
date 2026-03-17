Query Results Cache — Design
=============================

Overview
--------

Caches completed SELECT query results so semantically equivalent future queries skip execution entirely. Built on two existing subsystems:

- **HBO canonicalization** — deterministic plan hash as cache key.
- **TempStorage** — pluggable storage (local disk, S3) for serialized result pages.

Intercepts at ``SqlQueryExecution.start()`` after optimization, before scheduling. On cache hit, pages are read from ``TempStorage`` and fed directly to the client via the existing ``OutputBuffer`` → ``ExchangeClient`` → HTTP response pipeline, bypassing scheduling and execution.

Follows the same pattern as the Fragment Result Cache — intercept at the execution boundary — applied at whole-query level on the coordinator instead of per-split on workers.

::

    SQL → Parse → Analyze → Optimize → Compute plan hash
       ├── HIT:  Read pages from TempStorage → OutputBuffer → Client
       └── MISS: Fragment → Schedule → Execute → Capture pages → Store

Goals
~~~~~

- Eliminate redundant execution of identical SELECTs.
- Single storage layer via TempStorage SPI for both metadata and result pages.
- Input-table-aware invalidation via HBO statistics comparison.
Non-goals (v1): partial reuse / predicate stitching, DML caching, non-deterministic function caching.


Cache Key
---------

SHA-256 of the canonicalized query plan, computed via HBO's ``CanonicalPlanGenerator``.

Canonicalization normalizes variable names to positional (``_col_0``, ...), sorts predicates/join criteria, strips source locations, and delegates to ``ConnectorTableLayoutHandle.getIdentifier()`` for connector-specific normalization. Serialized to JSON with deterministic key ordering, then hashed.

**Full key**: ``(canonical_plan_hash, canonicalization_strategy)``

Uses ``CONNECTOR`` strategy (error level 1) by default — supports all node types, preserves filter constants, connector-aware table normalization. Configurable per-session.

Input table statistics are stored in the cache entry for validation, not in the key.


SPI
---

**TempStorage addition** — add ``exists()`` to the ``TempStorage`` SPI:

.. code-block:: java

    public interface TempStorage {
        // ... existing methods ...
        boolean exists(TempDataOperationContext context, TempStorageHandle handle) throws IOException;
    }

Checks handle liveness without opening the full stream (local ``Files.exists()``, S3 ``HeadObject``).

**Storage layout** — metadata and pages stored together in TempStorage under a key-derived path:

::

    cache/<plan_hash>_<strategy>/metadata.json
    cache/<plan_hash>_<strategy>/page_0
    cache/<plan_hash>_<strategy>/page_1
    ...

``QueryResultsCacheEntry`` (presto-spi) — data class for the metadata file:

.. code-block:: java

    public class QueryResultsCacheEntry {
        List<String> columnNames;
        List<String> columnTypeSignatures;
        long creationTimeMillis;
        long expirationTimeMillis;
        List<PlanStatistics> inputTableStatistics;
        long totalRows;
        long totalBytes;
        int pageCount;
        Optional<byte[]> encryptionKey;          // DEK
    }

``QueryResultsCacheManager`` reads/writes directly via TempStorage. Cache existence is checked via ``TempStorage.exists()`` on the metadata handle. Cross-coordinator sharing is supported when using shared TempStorage (e.g., S3).


Read Path
---------

In ``SqlQueryExecution.start()``, after ``createLogicalPlanAndOptimize()``:

1. Compute canonical plan hash.
2. Check ``TempStorage.exists()`` on the metadata handle for the key.
3. On exists: read and deserialize ``QueryResultsCacheEntry`` from metadata file. Validate: schema match (column names + types vs current ``OutputNode``), expiration check, input table stats comparison (HBO threshold, default 10%).
4. On hit: ``serveCachedResult()`` — read ``SerializedPage`` objects from ``TempStorage``, enqueue into ``OutputBuffer``, transition to finishing. Client sees no difference from normal execution.
5. On miss: register query ID + cache key for population on completion, proceed with normal scheduling.

Non-deterministic functions (``rand()``, ``now()``, ``uuid()``) are detected during hash computation; cache is not consulted.


Write Path
----------

``QueryResultsCacheWriter`` hooks into query lifecycle via ``addFinalQueryInfoListener`` (same pattern as ``HistoryBasedPlanStatisticsTracker``).

**Page capture** — tee-write at the ``ExchangeClient``:

As pages arrive at the coordinator's ``ExchangeClient`` during normal execution, a cache-aware listener asynchronously writes each batch to ``TempStorage`` in the background. The client receives pages with normal latency — cache writes are off the critical path.

- A running byte counter tracks total result size. If it exceeds ``max-result-size``, the tee-write is abandoned and partial files are cleaned up.
- On successful SELECT completion, the ``QueryResultsCacheWriter`` collects the accumulated ``TempStorageHandle`` references + input table stats, builds a ``QueryResultsCacheEntry``, and stores metadata in the cache provider.
- If the query fails or is cancelled, partial cache writes are discarded.

Operates at the ``ExchangeClient`` level, independent of whether spooling is enabled. Same pattern as ``FileFragmentResultCacheManager.cachePages()``, but incremental rather than post-completion.

Integration point: ``SqlQueryManager.createQuery()``, alongside existing HBO tracking.


Invalidation
------------

- **TTL**: Default 1 hour, configurable per-session. Each ``QueryResultsCacheEntry`` stores ``expirationTimeMillis``, checked on read before serving.
- **Data-change detection**: Input table stats (``rowCount``, ``outputSize``) compared against cached stats using HBO's threshold (default 10%). Both metrics must be similar for all input tables. Whole-table granularity (not per-partition).
- **Manual bypass**: ``query_results_cache_bypass = true`` — skip read, still populate. ``query_results_cache_invalidate = true`` — skip read, overwrite entry.
- **Storage cleanup**: Strategy depends on the TempStorage backend:

  - **Local filesystem**: ``QueryResultsCacheManager`` runs a background cleanup thread (configurable via ``cleanup-interval``, default 5m) that scans the cache directory, reads metadata, checks expiration, and deletes expired entries.
  - **S3**: Delegates expiration to S3 object lifecycle policies. The cache write sets an expiration tag or prefix-based lifecycle rule matching the configured TTL. No background thread needed — S3 handles deletion.


Encryption
----------

Pages encrypted via existing ``AesSpillCipher`` / ``PagesSerde`` pipeline (AES-256-CTR, fresh 256-bit key per entry, new IV per operation).

- **Write**: Create ``AesSpillCipher``, serialize pages with cipher-aware ``PagesSerde``, store DEK in ``QueryResultsCacheEntry.encryptionKey``.
- **Read**: Reconstruct cipher from stored DEK, decrypt during deserialization, ``cipher.destroy()`` after.

v1 key management: DEK stored in the metadata file alongside page references. Envelope encryption with KMS deferred to v2. Storage-layer encryption (SSE-S3/SSE-KMS) can be used as a complementary layer.


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
``query-results-cache.cleanup-interval``           ``5m``         Expired entry cleanup (local only)
``query-results-cache.encryption-enabled``         ``true``       Encrypt cached pages
================================================  =============  ==========================================

**Session properties:** ``query_results_cache_enabled``, ``query_results_cache_ttl``, ``query_results_cache_bypass``, ``query_results_cache_invalidate``, ``query_results_cache_canonicalization_strategy``.


Security
--------

- **Shared cache entries**: Cache entries are shared across users. Access control is enforced during the analysis phase (before the cache is consulted), so unauthorized users cannot reach the cache lookup.
- **Non-deterministic functions**: Detected during cache key computation; cache is not consulted.


Implementation Plan
-------------------

**New files:**

- ``presto-spi``: ``QueryResultsCacheEntry.java``
- ``presto-main-base``: ``QueryResultsCacheManager.java``, ``QueryResultsCacheWriter.java``, ``QueryResultsCacheConfig.java``

**Modified files:** ``TempStorage.java`` (add ``exists()``), ``LocalTempStorage.java`` (implement ``exists()``), ``SqlQueryExecution.java`` (read path), ``ExchangeClient.java`` (tee-write listener for cache page capture), ``SystemSessionProperties.java``, ``ServerMainModule.java``, ``SqlQueryManager.java``.

**Phases:** (1) TempStorage SPI addition (``exists()``) + cache entry data class → (2) Write path → (3) Read path → (4) Encryption → (5) Invalidation + cleanup → (6) Testing.


Future Work
------------

- **Predicate stitching**: Partial cache reuse for partition-decomposable queries when only some partitions change. Requires per-partition stats.
- **Cross-coordinator sharing**: Already supported when using shared TempStorage (e.g., S3). May benefit from a distributed lock or leader election for cache population to avoid redundant writes.
- **Adaptive caching**: Track hit rates per query pattern, skip caching for one-off queries.
