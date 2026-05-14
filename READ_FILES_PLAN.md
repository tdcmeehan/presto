# `read_files` — Implementation Plan

## Goal

Add a polymorphic table function `read_files(location, format, ...)` that lets
users query a path of data files directly, without a pre-registered table, with
the schema inferred from the files themselves. CTAS over the function is the
primary intended use, enabling one-statement table creation from a storage
path:

```sql
CREATE TABLE my_table
WITH (format = 'PARQUET')
AS SELECT * FROM TABLE(hive.system.read_files(
  location => 's3://bucket/path/',
  format   => 'parquet'
));
```

This is Spark / Databricks `read_files` shape, not Snowflake `INFER_SCHEMA` +
`USING TEMPLATE` shape. The polymorphic-table-function feature in Presto's
analyzer (`ReturnTypeSpecification.GenericTable`) makes the analyzed schema
visible to the surrounding query, so CTAS picks it up with no additional
language surface.

A secondary `infer_schema(...)` PTF returning metadata rows is optional and
deferred — it provides Snowflake-style inspection but is not on the path to
creating a table.

## Scope

### In scope for v1

- Formats: **Parquet**, **ORC**, **DWRF**
- Storage: any filesystem reachable through the binding Hive catalog
  (S3, GCS, ABFS, HDFS, local)
- Output: Presto types (native Hive-flavored)
- Execution: Java workers **and** C++ (native) workers
- CTAS, `SELECT`, and `INSERT INTO ... SELECT FROM TABLE(read_files(...))`
- Sampling cap: `max_file_count` argument bounds the analyze-time footer reads

### Deferred to v2

- **Avro**: footer (header) extraction works on Java workers via
  `PrestoAvroSerDe`; Velox has no Avro reader, so native-worker execution
  requires either an Avro reader in Velox or a Java-only fallback path.
- **CSV** and **JSON**: schema must be inferred from sampled rows, not
  extracted from a footer. Net-new type-inference engine. Velox `TEXT`
  reader is not RFC 4180 CSV.
- **Iceberg-typed output** mode (analogue of Snowflake's `KIND => 'ICEBERG'`)
- **Tree-walking multi-table discovery** (analogue of Starburst Schema
  Discovery)
- **Schema-evolution tracking across runs** (Databricks Auto Loader shape)

### Explicitly out of scope

- `USING TEMPLATE` language feature — not needed given polymorphic PTFs
- A new wire-format plan node (`TableFunctionNode`) — the PTF is rewritten
  to `TableScanNode` before scheduling
- A Velox-side PTF runtime

## Architecture

```
   user SQL
      │
      ▼
┌──────────────────── coordinator (Java) ────────────────────┐
│                                                             │
│  parser ── TABLE(read_files(...))                           │
│      │                                                       │
│      ▼                                                       │
│  analyzer ── StatementAnalyzer.visitTableFunctionInvocation │
│      │       calls ReadFilesFunction.analyze(args)          │
│      │       which:                                          │
│      │         1. lists files (capped by max_file_count)    │
│      │         2. reads footers / Avro headers              │
│      │         3. unifies per-file schemas                  │
│      │         4. returns Descriptor + ReadFilesHandle      │
│      │                                                       │
│      ▼                                                       │
│  planner ── rewrite TableFunctionNode → TableScanNode       │
│             with synthetic HiveTableHandle,                 │
│             HiveColumnHandles (one per inferred column),    │
│             HiveSplits (one per file in v1)                 │
│                                                              │
└──────────────────────────────┬───────────────────────────────┘
                               │  Thrift / JSON plan + splits
                               ▼
┌──────────────────── worker (Java or C++) ────────────────────┐
│  Normal Hive table scan:                                      │
│    - Java: HivePageSourceProvider → existing format readers   │
│    - C++:  HivePrestoToVeloxConnector → Velox Parquet/ORC     │
│                                                                │
│  Returns Page/RowVector with the inferred schema's columns    │
└────────────────────────────────────────────────────────────────┘
```

Key property: **the C++ worker never sees `read_files` as a concept.** It
receives a `TableScanNode` shaped identically to a scan against a registered
external table. All novelty is on the Java side.

## Java implementation

### 1. PTF class

Location: `presto-hive/src/main/java/com/facebook/presto/hive/functions/ReadFilesFunction.java`
(new file; exposed via the Hive plugin so it inherits the catalog's filesystem
config and credentials)

Structure (sketch):

```java
public class ReadFilesFunction extends AbstractConnectorTableFunction {
    public ReadFilesFunction(HdfsEnvironment hdfs,
                             ParquetMetadataSource parquetMetadata,
                             OrcFileTailSource orcFileTail,
                             TypeManager typeManager) {
        super("system", "read_files",
            List.of(
              ScalarArgumentSpecification.builder().name("LOCATION").type(VARCHAR).build(),
              ScalarArgumentSpecification.builder().name("FORMAT").type(VARCHAR).build(),
              ScalarArgumentSpecification.builder().name("MAX_FILE_COUNT")
                  .type(INTEGER).defaultValue(100L).build()),
            GENERIC_TABLE);
    }

    @Override
    public TableFunctionAnalysis analyze(ConnectorSession session,
                                         ConnectorTransactionHandle tx,
                                         Map<String, Argument> args) {
        String location = stringArg(args, "LOCATION");
        HiveStorageFormat format = parseFormat(stringArg(args, "FORMAT"));
        int maxFiles = intArg(args, "MAX_FILE_COUNT");

        List<Path> files = listAndCap(location, maxFiles);
        if (files.isEmpty()) {
            throw new PrestoException(INVALID_FUNCTION_ARGUMENT,
                "No files found at location: " + location);
        }

        List<ColumnSchema> inferred = SchemaInferenceDispatch.inferSchema(format, files);
        Descriptor descriptor = toDescriptor(inferred);

        return TableFunctionAnalysis.builder()
            .returnedType(descriptor)
            .handle(new ReadFilesHandle(location, format, files, inferred))
            .build();
    }
}
```

### 2. Schema extraction per format

Location: `presto-hive/src/main/java/com/facebook/presto/hive/functions/schema/`

One class per format implementing a small SPI:

```java
interface FileSchemaExtractor {
    ColumnSchema[] extract(Path file, HdfsEnvironment hdfs) throws IOException;
}
```

Implementations:

| Format  | Class                    | Reuses                                                 |
|---------|--------------------------|--------------------------------------------------------|
| Parquet | `ParquetSchemaExtractor` | `MetadataReader.readParquetMetadata()` from `presto-parquet` |
| ORC     | `OrcSchemaExtractor`     | `OrcFileTail` from `presto-orc`                        |
| DWRF    | `DwrfSchemaExtractor`    | same as ORC, different `OrcEncoding`                   |

Each implementation reads only the footer/tail — no data blocks.

### 3. Schema unification

Location: `presto-hive/src/main/java/com/facebook/presto/hive/functions/schema/SchemaMerger.java`

Rules (start strict, document for change later):

1. **Column key**: by name, case-insensitive (matching Hive default).
2. **Column presence**: column present in some files but not others → nullable
   in the merged schema.
3. **Type promotion**: pairwise widening — `INTEGER + BIGINT → BIGINT`,
   `INTEGER + DOUBLE → DOUBLE`, `DECIMAL(p1,s1) + DECIMAL(p2,s2) →
   DECIMAL(max(p1-s1,p2-s2)+max(s1,s2), max(s1,s2))` when valid, otherwise
   error.
4. **Conflict**: incompatible types (e.g., `VARCHAR + BIGINT`) → fail with
   a clear error naming the column and offending files. Do **not** fall
   back to `VARCHAR` silently in v1 — explicit is better than surprising.
5. **Nested types**: recurse into `ROW`, `ARRAY`, `MAP`. Same rules apply
   element-wise.
6. **Ordering**: take ordinals from the first file; new columns from later
   files append.

### 4. Planner rewrite — PTF → TableScanNode

This is the load-bearing piece on the Java side. Two possible approaches,
depending on what Presto's PTF SPI already supports:

**Option A — connector `applyTableFunction` (preferred if it exists).**
The Hive connector's `ConnectorMetadata.applyTableFunction()` consumes the
PTF and returns a `TableFunctionApplicationResult` containing a
`ConnectorTableHandle` (a synthetic `HiveTableHandle`). The planner then
materializes a regular `TableScanNode`. No new optimizer rule needed.

**Option B — optimizer rule.** Add a `RewriteTableFunctionToScan` rule
under `presto-main-base/src/main/java/com/facebook/presto/sql/planner/iterative/rule/`
that pattern-matches `TableFunctionNode(read_files, handle=ReadFilesHandle)`
and emits a `TableScanNode` with:

- `HiveTableHandle` constructed with synthetic
  `SchemaTableName("system", "read_files_<queryId>")`
- `HiveColumnHandle` per inferred column (type, name, ordinal,
  `ColumnType.REGULAR`)
- `tableLayoutHandle` carrying the file list

**Decision gate:** check whether `applyTableFunction` exists in
`presto-spi/.../ConnectorMetadata.java`. If yes, Option A. If no, Option B —
and consider adding `applyTableFunction` as a separate engine PR.

### 5. Split generation

Location: existing Hive `HiveSplitManager` path, with one new branch.

For v1, `ReadFilesHandle.files` is materialized at analyze time. Split
generation:

- One `HiveSplit` per file (no internal byte-range splitting). Set
  `storageFormat` from the PTF arg. Path, length, modification time from
  the listing. No partition keys.
- Schema for each split's `SchemaTableName` comes from the synthetic table
  handle.

v1.1 optimization (cheap if needed): byte-range split files larger than
`hive.max-split-size` using existing logic — `HiveSplitSource` already
handles this for normal tables.

### 6. File listing

Reuses `HdfsEnvironment` + `ExtendedFileSystem` already wired for Hive.
Listing is recursive within the given location. Cap to `max_file_count`
**before** doing any footer reads.

Hidden files (`_*`, `.*`) are excluded — same convention as Hive table
scans.

## C++ worker implementation

The C++ worker requires **no new components**. The native-execution protocol
(`presto_cpp/presto_protocol/core/presto_protocol_core.yml`) has no
`TableFunctionNode`, so the rewrite to `TableScanNode` on the coordinator is
mandatory, not optional.

What is needed is **verification** that the synthetic-table path exercises
the existing infrastructure correctly:

### V1. Synthetic `HiveTableHandle` flow

File: `presto_cpp/main/connectors/hive/HivePrestoToVeloxConnector.cpp`

Verify `toVeloxTableHandle()` handles a table handle whose `SchemaTableName`
does not correspond to a metastore-registered table. The likely outcome is
that it works as-is — the handle is just a parameter bag — but confirm with
a test that drives the path end-to-end.

### V2. Format dispatch

File: same. The `toFileFormat()` mapping already covers PARQUET, DWRF, and
TEXTFILE. Confirm ORC routes through correctly. AVRO/JSON/CSV deliberately
throw `VELOX_UNSUPPORTED` — leave that behavior; the v1 PTF rejects those
formats on the coordinator anyway.

### V3. Name-based column projection

Velox's Parquet/ORC readers project by column name. The
`HiveColumnHandle.name` carries through. Verify with a test where the
file's physical column order differs from the synthetic handle's order.

### V4. Schema-evolution behavior

When files in the same `read_files` invocation have different schemas:

- Column missing from a file → Velox null-fills via `ScanSpec`. Verify.
- Column type widened in the unified schema (file has INT, scan expects
  BIGINT) → Velox supports common widenings. Verify per-type with the
  Parquet and ORC readers. Document any gaps and either fix them in Velox
  or restrict the merger.

### V5. Credentials

The PTF binds to a catalog (e.g., `hive`). The worker inherits that
catalog's filesystem config. Confirm S3/GCS/ABFS work for paths outside
any registered table location (they should — credentials are per-catalog,
not per-table).

### V6. Tests

Add under `presto_cpp/main/tests/`:

- `ReadFilesPlanE2ETest.cpp`: feed a hand-built `TableScanNode` (mimicking
  what the rewritten PTF produces) and verify rows return correctly for
  Parquet and ORC inputs.
- Schema-evolution cases: heterogeneous files in one scan.
- S3 path with synthetic handle, gated by existing S3 test infra.

No new C++ production code is anticipated. If V1–V4 surface bugs, the
fixes are localized (relax an assumption in
`HivePrestoToVeloxConnector`, add a missing widening in Velox).

## Sequencing

### Phase 0 — Foundations (parallel, ~1 sprint)

- Confirm `applyTableFunction` SPI exists; if not, scope adding it.
- Stand up the `FileSchemaExtractor` interface and Parquet implementation
  with unit tests against fixture files.
- Verify polymorphic-PTF e2e with a trivial dummy PTF (no I/O) end-to-end
  on a Java cluster, just to confirm the analyze→plan→scan→CTAS chain
  works at all.

### Phase 1 — Java single-format MVP (~1 sprint)

- `ReadFilesFunction` for Parquet only
- Schema merger (single-file: trivial; multi-file: stricter case)
- Planner rewrite path
- CTAS works on a Java cluster

### Phase 2 — Multi-format and merge (~1 sprint)

- ORC and DWRF extractors
- Multi-file schema merge with type promotion
- Avro extractor (Java-only execution path) — optional in v1; defer if
  the native-execution gap is a blocker

### Phase 3 — Native execution validation (~0.5 sprint)

- Run Phase 1+2 against a C++ worker cluster
- Tests V1–V6 above
- Fix any synthetic-handle gaps in `HivePrestoToVeloxConnector`

### Phase 4 — Hardening (~0.5 sprint)

- Listing performance (large directories, paginated S3 LIST)
- Error messages: bad path, no files matched, schema conflict, unsupported
  format
- Argument validation (negative `max_file_count`, unknown format string)
- Docs

**Total v1: ~3–4 engineer-sprints**, dominated by the Java side.

## Open questions

1. **`applyTableFunction` SPI presence.** Determines Option A vs. B above.
   Worth resolving before Phase 1.

2. **Catalog binding.** `hive.system.read_files(...)` (per-catalog,
   inherits config) vs. a global `system.read_files(...)`. Recommend
   per-catalog for v1 — it sidesteps the "where do credentials come from"
   question entirely. The same function can be registered under multiple
   catalogs.

3. **Schema-merge strictness.** v1 plan is strict (fail on conflict). Some
   users will want permissive (fall back to VARCHAR, like Starburst).
   Decide whether to expose a `schema_merge_mode` argument in v1 or wait
   for demand.

4. **Iceberg-typed output.** Snowflake's `KIND => 'ICEBERG'` is genuinely
   useful when the target is an Iceberg table. Adding a `target` argument
   (`'native' | 'iceberg'`) is small — the translation library
   (`ParquetSchemaUtil.convert`) exists. Could fit in v1 if scope allows.

5. **Listing semantics.** Recursive by default, or require a glob pattern?
   Snowflake uses pattern matching. Recommend recursive by default with an
   optional `pattern` argument (regex over relative path).

6. **Concurrency cap on analyze.** Footer reads in `analyze()` are I/O
   bound. Use a small thread pool (configurable, default e.g. 16) inside
   the PTF rather than serial reads, but cap it so the coordinator isn't
   overwhelmed by concurrent `read_files` invocations.

## Risks

- **`applyTableFunction` missing**: shifts effort from "implement a PTF" to
  "extend the connector SPI." Adds ~1 sprint.
- **Velox schema-evolution gaps**: widening behavior may not match what the
  merger produces. Mitigation: align the merger's promotion rules to what
  Velox already supports, document the policy.
- **Synthetic `HiveTableHandle` assumptions**: code somewhere in the Hive
  connector may assume the schema/table exist in a metastore. Most likely
  surfaced in stats lookup, audit logging, or access control. Tracker
  needed during Phase 1.
- **Credentials for arbitrary paths**: a catalog's S3 IAM role may not have
  access to an arbitrary user-supplied path. This is a deployment concern,
  not a code concern, but the error message must be actionable.

## Out of scope (explicit)

- `INSERT INTO` *into* a path via `read_files` — that's `write_files`, a
  separate feature.
- Streaming / continuous file arrival — Auto Loader's territory, not v1.
- Schema-evolution tracking across multiple invocations — would require
  persistent state, deliberately avoided.
- Per-file pushdown of inferred predicates — standard Hive predicate
  pushdown applies as-is.
- Tree-walking multi-table discovery — Starburst's territory, separate
  feature.
