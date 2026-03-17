# Query Results Cache — Design

## Overview

Caches completed SELECT query results so semantically equivalent future queries skip execution entirely. Built on two existing subsystems:

- **HBO canonicalization** — deterministic plan hash as cache key.
- **TempStorage** — pluggable storage (local disk, S3) for serialized result pages.

Intercepts at `SqlQueryExecution.start()` after optimization, before scheduling. On cache hit, pages are read from `TempStorage` and fed directly to the client via the existing `OutputBuffer` → `ExchangeClient` → HTTP response pipeline, bypassing scheduling and execution.

Follows the same pattern as the Fragment Result Cache — intercept at the execution boundary — applied at whole-query level on the coordinator instead of per-split on workers.

```
SQL → Parse → Analyze → Optimize → Compute plan hash
   ├── HIT:  Read pages from TempStorage → OutputBuffer → Client
   └── MISS: Fragment → Schedule → Execute → Capture pages → Store
```

### Goals

- Eliminate redundant execution of identical SELECTs.
- Single storage layer via TempStorage SPI for both metadata and result pages.
- Input-table-aware invalidation via HBO statistics comparison.

Non-goals (v1): partial reuse / predicate stitching, DML caching, non-deterministic function caching.


## Cache Key

SHA-256 of the canonicalized query plan, computed via HBO's `CanonicalPlanGenerator`.

Uses a new `RESULT_CACHE` canonicalization strategy. Like `CONNECTOR`, it supports all plan node types and delegates to `ConnectorTableLayoutHandle.getIdentifier()` for connector-specific normalization. Unlike `CONNECTOR` and the other HBO strategies, it preserves all constants — filter predicates, projection constants, and scan predicates are never stripped. Two queries with different predicate values produce different cache keys.

Canonicalization normalizes variable names to positional (`_col_0`, ...), sorts predicates/join criteria, and strips source locations. Serialized to JSON with deterministic key ordering, then hashed.

**Full key**: `(canonical_plan_hash)`

Input table statistics are stored in the cache entry for validation, not in the key.

### Connector requirements

Each connector's `ConnectorTableLayoutHandle.getIdentifier()` must produce a stable identifier that:

- **Strips version metadata** (e.g., Iceberg `snapshotId`) so that the same table at different snapshots shares cache keys. Invalidation is handled by input table stats comparison, not by the key. (See [prestodb/presto#26897](https://github.com/prestodb/presto/issues/26897).)
- **Includes the catalog name** so that identically-named tables in different catalogs pointing to different data produce distinct cache keys. Users querying the same data through different catalog names pointing to the same underlying storage share cache entries.

**Iceberg**: Currently does not implement `getIdentifier()`, falling through to the default (`this`), which includes the `snapshotId`. The result cache requires Iceberg to implement `getIdentifier()` with: `(catalogName, schemaName, tableName)` — stripping `snapshotId` and `snapshotSpecified`.

**Hive**: Already implements `getIdentifier()`. The existing implementation strips partition key constants (for HBO). The `RESULT_CACHE` strategy preserves all constants at the canonicalization level, so the Hive `getIdentifier()` does not need modification — it returns `(schemaTableName, domainPredicate, remainingPredicate, constraint, bucketFilter)` with constant preservation governed by the strategy.


## SPI

**TempStorage additions**:

```java
public interface TempStorage {
    // ... existing methods ...
    boolean exists(TempDataOperationContext context, TempStorageHandle handle) throws IOException;
    boolean createIfNotExists(TempDataOperationContext context, TempStorageHandle handle, byte[] data) throws IOException;
}
```

`exists()` checks handle liveness without opening the full stream (local `Files.exists()`, S3 `HeadObject`).

`createIfNotExists()` atomically creates an object only if it does not already exist. Returns `true` if the object was created, `false` if it already existed. S3 implementation uses `PutObject` with `If-None-Match: *` header — S3 returns `412 Precondition Failed` if the key exists, which the implementation catches and returns `false`. Used for cache write deduplication (see below).

**StorageCapabilities addition** — add `AUTO_EXPIRATION` to the existing `StorageCapabilities` enum:

```java
public enum StorageCapabilities {
    REMOTELY_ACCESSIBLE,
    AUTO_EXPIRATION,  // storage handles TTL-based expiration natively
}
```

**TempDataOperationContext addition** — add `Optional<Duration> expireAfter` to the existing context object passed to `create()`. Backends that support `AUTO_EXPIRATION` use this to set native expiration (e.g., S3 object expiration header). Backends that do not support it ignore the field.

**Storage layout** — metadata and pages stored together in TempStorage under a key-derived path:

```
cache/<plan_hash>/metadata.json
cache/<plan_hash>/page_0
cache/<plan_hash>/page_1
...
```

`QueryResultsCacheEntry` (presto-spi) — data class for the metadata file:

```java
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
```

`QueryResultsCacheManager` reads/writes directly via TempStorage. Cache existence is checked via `TempStorage.exists()` on the metadata handle. Cross-coordinator sharing is supported when using shared TempStorage (e.g., S3).


## Read Path

In `SqlQueryExecution.start()`, after `createLogicalPlanAndOptimize()`:

1. Compute canonical plan hash.
2. Check `TempStorage.exists()` on the metadata handle for the key.
3. On exists: read and deserialize `QueryResultsCacheEntry` from metadata file. Validate: schema match (column names + types vs current `OutputNode`), expiration check, input table stats comparison (exact match required).
4. On hit: `serveCachedResult()` — read `SerializedPage` objects from `TempStorage`, enqueue into `OutputBuffer`, transition to finishing. Client sees no difference from normal execution.
5. On miss: register query ID + cache key for population on completion, proceed with normal scheduling.

Non-deterministic functions (`rand()`, `now()`, `uuid()`) are detected during hash computation; cache is not consulted.


## Write Path

`QueryResultsCacheWriter` hooks into query lifecycle via `addFinalQueryInfoListener` (same pattern as `HistoryBasedPlanStatisticsTracker`).

**Page capture** — tee-write at the `ExchangeClient`:

As pages arrive at the coordinator's `ExchangeClient` during normal execution, a cache-aware listener asynchronously writes each batch to `TempStorage` in the background. The client receives pages with normal latency — cache writes are off the critical path.

- A running byte counter tracks total result size. If it exceeds `max-result-size`, the tee-write is abandoned and partial files are cleaned up.
- On successful SELECT completion, the `QueryResultsCacheWriter` collects the accumulated `TempStorageHandle` references + input table stats, builds a `QueryResultsCacheEntry`, and stores metadata in the cache provider.
- If the query fails or is cancelled, partial cache writes are discarded.

Operates at the `ExchangeClient` level, independent of whether spooling is enabled. Same pattern as `FileFragmentResultCacheManager.cachePages()`, but incremental rather than post-completion.

Integration point: `SqlQueryManager.createQuery()`, alongside existing HBO tracking.


## Write Deduplication (S3)

When multiple coordinators experience a cache miss for the same plan hash simultaneously, only one should execute and populate the cache — others should wait for the result. This avoids redundant execution across coordinators.

Uses [S3 conditional writes](https://docs.aws.amazon.com/AmazonS3/latest/userguide/conditional-writes.html) (`If-None-Match: *` on `PutObject`) to implement lock-free writer election. No external coordination service required.

**Protocol**:

1. On cache miss, before scheduling, the coordinator calls `TempStorage.createIfNotExists()` on a lock object at `cache/<plan_hash>/lock`. The lock body contains `{"coordinatorId": "...", "creationTimeMillis": ...}`.
2. **Wins** (`createIfNotExists` returns `true`): This coordinator is the writer. Proceeds with normal scheduling, execution, and cache population. On completion (or failure), deletes the lock object.
3. **Loses** (`createIfNotExists` returns `false`): Another coordinator is already executing this query. The coordinator polls `TempStorage.exists()` on `cache/<plan_hash>/metadata.json` at a configurable interval (default 1s). When metadata appears, serves from cache. If polling exceeds a configurable timeout (default: equal to the query's execution time limit), falls through to normal execution.
4. **Crashed writer**: The lock object includes `creationTimeMillis`. A coordinator that loses the election checks the lock's age. If the lock is older than a configurable staleness threshold (default 10 minutes), it ignores the lock and falls through to normal execution, competing for a new lock via `createIfNotExists` after deleting the stale lock. S3 `Expires` header is also set on lock objects as a backstop for garbage collection.

**Fallback**: If `createIfNotExists` throws (e.g., non-S3 backend that doesn't support conditional writes), the coordinator falls through to normal execution and cache population. Duplicate writes to the same cache key are idempotent — the last writer wins, which is safe since results for the same plan hash are identical.

**Why lock-free**: S3's `If-None-Match` provides mutual exclusion at the storage layer — no need for ZooKeeper, DynamoDB, or any external lock manager. The lock is just an S3 object with a short TTL.

```
Coordinator A (cache miss)          Coordinator B (cache miss)
  │                                   │
  ├─ createIfNotExists(lock) → true   ├─ createIfNotExists(lock) → false
  │  (I'm the writer)                 │  (someone else is writing)
  │                                   │
  ├─ Schedule + Execute               ├─ Poll for metadata.json
  │                                   │     ↓
  ├─ Write pages + metadata           ├─ metadata appears → serve from cache
  │                                   │
  ├─ Delete lock                      │
  ▼                                   ▼
```


## Invalidation

- **TTL**: Default 1 hour, configurable per-session. Each `QueryResultsCacheEntry` stores `expirationTimeMillis`, checked on read before serving.
- **Data-change detection**: Input table stats (`rowCount`, `outputSize`) compared against cached stats. Both metrics must match exactly for all input tables — any change (even a single new row) invalidates the entry, since any data change could alter query results. Whole-table granularity (not per-partition).
- **Manual bypass**: `query_results_cache_bypass = true` — skip read, still populate. `query_results_cache_invalidate = true` — skip read, overwrite entry.
- **Storage cleanup**: Cache writes pass `expireAfter` via `TempDataOperationContext`. S3 sets native object expiration (with a configurable buffer to prevent deletion during in-flight reads). The read-path `expirationTimeMillis` check in the metadata is the source of truth for staleness; backend expiration is garbage collection only.

  Read-path correctness does not depend on the cleanup strategy. The `pageCount` field in `QueryResultsCacheEntry` defines the expected number of pages. The reader reads exactly `pageCount` pages by handle; if any page is missing, `TempStorage.open()` throws and the query fails. Results are never silently truncated.


## Encryption

Pages encrypted via existing `AesSpillCipher` / `PagesSerde` pipeline (AES-256-CTR, fresh 256-bit key per entry, new IV per operation).

- **Write**: Create `AesSpillCipher`, serialize pages with cipher-aware `PagesSerde`, store DEK in `QueryResultsCacheEntry.encryptionKey`.
- **Read**: Reconstruct cipher from stored DEK, decrypt during deserialization, `cipher.destroy()` after.

v1 key management: DEK stored in the metadata file alongside page references. Envelope encryption with KMS deferred to v2. Storage-layer encryption (SSE-S3/SSE-KMS) can be used as a complementary layer.


## Configuration

**Server properties:**

| Property | Default | Description |
|---|---|---|
| `query-results-cache.enabled` | `false` | Master switch |
| `query-results-cache.ttl` | `1h` | Default TTL |
| `query-results-cache.max-result-size` | `100MB` | Max cacheable result size |
| `query-results-cache.temp-storage` | `local` | TempStorage backend name |
| `query-results-cache.encryption-enabled` | `true` | Encrypt cached pages |
| `query-results-cache.write-dedup-enabled` | `true` | Enable write deduplication via conditional writes (S3 only) |
| `query-results-cache.write-dedup-poll-interval` | `1s` | How often waiting coordinators poll for metadata |
| `query-results-cache.write-dedup-lock-staleness-threshold` | `10m` | Age after which a lock is considered stale |

**Session properties:** `query_results_cache_enabled`, `query_results_cache_ttl`, `query_results_cache_bypass`, `query_results_cache_invalidate`.


## Security

- **Shared cache entries**: Cache entries are shared across users. Access control is enforced during the analysis phase (before the cache is consulted), so unauthorized users cannot reach the cache lookup.
- **Non-deterministic functions**: Detected during cache key computation; cache is not consulted.


## S3 TempStorage

A new `TempStorage` implementation backed by S3, enabling cross-coordinator cache sharing and native object expiration.

**Module**: New Maven module `presto-temp-storage-s3`, following the plugin pattern. Ships as a plugin; loaded via `etc/temp-storage/s3.properties`.

### Implementation

`S3TempStorage` implements `TempStorage`. `S3TempStorageFactory` implements `TempStorageFactory` with `getName()` returning `"s3"`.

**Capabilities**: `REMOTELY_ACCESSIBLE`, `AUTO_EXPIRATION`.

**Handle**: `S3TempStorageHandle` wraps an S3 key (string). `serializeHandle()` / `deserialize()` encode/decode the key as UTF-8 bytes. `getPathAsString()` returns the full `s3://bucket/key` URI.

**Operations**:

| Method | S3 API | Notes |
|---|---|---|
| `create()` | — | Returns an `S3TempDataSink` that buffers writes and uploads on `commit()` via `PutObject`. If `expireAfter` is set in the context, sets the `Expires` header on the object. |
| `open()` | `GetObject` | Returns the response `InputStream`. |
| `remove()` | `DeleteObject` | Best-effort; S3 deletes are eventually consistent but idempotent. |
| `exists()` | `HeadObject` | Returns `true` on 200, `false` on 404. |
| `createIfNotExists()` | `PutObject` with `If-None-Match: *` | Returns `true` on success, `false` on `412 Precondition Failed`. Used for cache write deduplication. |

**Key layout**: Objects are stored under a configurable prefix:

```
<key-prefix>/cache/<plan_hash>/metadata.json
<key-prefix>/cache/<plan_hash>/page_0
...
```

**Expiration buffer**: When `expireAfter` is provided via `TempDataOperationContext`, the factory adds a configurable buffer (default 1 hour, via `s3.expiration-buffer`) before setting the S3 `Expires` header. This prevents S3 lifecycle rules from deleting objects while a cache-hit read is in flight.

### Configuration

Properties file: `etc/temp-storage/s3.properties`

```properties
temp-storage-factory.name=s3
s3.bucket=my-presto-temp-storage
s3.key-prefix=presto/temp
s3.region=us-east-1
s3.endpoint=                          # optional, for S3-compatible stores
s3.expiration-buffer=1h               # buffer added to expireAfter before setting Expires header
```

Authentication uses the default AWS credential chain (environment variables, instance profile, etc.), consistent with existing Presto S3 integrations (e.g., `PrestoS3FileSystem`).


## Implementation Plan

**New files:**

- `presto-spi`: `QueryResultsCacheEntry.java`
- `presto-main-base`: `QueryResultsCacheManager.java`, `QueryResultsCacheWriter.java`, `QueryResultsCacheConfig.java`
- `presto-temp-storage-s3`: `S3TempStorage.java`, `S3TempStorageFactory.java`, `S3TempStorageHandle.java`, `S3TempDataSink.java`, `S3TempStorageConfig.java`

**Modified files:** `PlanCanonicalizationStrategy.java` (add `RESULT_CACHE`), `IcebergTableLayoutHandle.java` (implement `getIdentifier()`), `StorageCapabilities.java` (add `AUTO_EXPIRATION`), `TempDataOperationContext.java` (add `expireAfter`), `TempStorage.java` (add `exists()`, `createIfNotExists()`), `LocalTempStorage.java` (implement `exists()`, stub `createIfNotExists()`), `SqlQueryExecution.java` (read path + write dedup), `ExchangeClient.java` (tee-write listener for cache page capture), `SystemSessionProperties.java`, `ServerMainModule.java`, `SqlQueryManager.java`.

**Phases:** (1) TempStorage SPI addition (`exists()`) + cache entry data class → (2) Write path → (3) Read path → (4) Encryption → (5) Invalidation + cleanup → (6) S3 TempStorage plugin → (7) Testing.


## Future Work

- **Predicate stitching**: Partial cache reuse for partition-decomposable queries when only some partitions change. Requires per-partition stats.
- **Cross-coordinator sharing**: Already supported when using shared TempStorage (e.g., S3). Write deduplication via S3 conditional writes prevents redundant execution across coordinators.
- **Adaptive caching**: Track hit rates per query pattern, skip caching for one-off queries.
