# MCP Hybrid Approach: Gateway + Coordinator Plugin

## Option 3: Detailed Analysis

This document provides an in-depth analysis of the hybrid approach, where MCP gateways communicate with Presto coordinators using custom internal APIs provided by a coordinator plugin, rather than the standard REST client protocol.

---

## Architecture Overview

### High-Level Design

```
┌───────────────────┐
│   AI Agents       │
│  (Claude, etc.)   │
└─────────┬─────────┘
          │ MCP Protocol (JSON-RPC over HTTP)
          ↓
┌─────────────────────────────┐
│   MCP Gateway Cluster       │ ← Stateless, load balanced
│   - Protocol translation    │
│   - Adaptive buffering/SSE  │
│   - Uses internal APIs      │
└─────────┬───────────────────┘
          │ Internal API (gRPC or custom REST)
          ↓
┌─────────────────────────────┐
│   Presto Coordinator        │
│   + Query Access Plugin     │ ← Provides internal APIs
│   - Exposes query APIs      │
│   - Manages query state     │
└─────────────────────────────┘
```

### Two-Component System

**Component 1: MCP Gateway (External Service)**
- Lightweight service deployed separately
- Handles MCP protocol (JSON-RPC)
- Translates MCP → Internal API calls
- Manages adaptive buffering/SSE streaming
- Stateless (can be load balanced)

**Component 2: Coordinator Plugin (Internal Extension)**
- Installed on Presto coordinators
- Exposes internal APIs for query operations
- More efficient than public REST API
- Provides direct access to query manager
- Avoids overhead of REST protocol

---

## Detailed Architecture

### Component Interaction

```
┌──────────────────────────────────────────────────────────────┐
│                      MCP Gateway                              │
│                                                               │
│  ┌─────────────────┐      ┌──────────────────────────┐      │
│  │ MCP Handler     │      │  Presto Client           │      │
│  │ (JSON-RPC)      │─────▶│  (Internal API Client)   │      │
│  └─────────────────┘      └────────┬─────────────────┘      │
│                                     │                         │
└─────────────────────────────────────┼─────────────────────────┘
                                      │
                         Internal API (gRPC/REST)
                         - submitQuery(sql, session)
                         - pollResults(queryId, token, size)
                         - cancelQuery(queryId)
                         - getQueryInfo(queryId)
                                      │
┌─────────────────────────────────────┼─────────────────────────┐
│                      Presto Coordinator                        │
│                                     ↓                          │
│  ┌──────────────────────────────────────────────────────┐    │
│  │              Query Access Plugin                      │    │
│  │                                                       │    │
│  │  ┌──────────────────┐      ┌────────────────────┐   │    │
│  │  │ Internal API     │      │ Query Manager      │   │    │
│  │  │ Endpoint         │─────▶│ Facade             │   │    │
│  │  │ (gRPC/REST)      │      └────────┬───────────┘   │    │
│  │  └──────────────────┘               │               │    │
│  │                                      │               │    │
│  └──────────────────────────────────────┼───────────────┘    │
│                                         │                     │
│  ┌──────────────────────────────────────▼───────────────┐   │
│  │           Core Presto Components                      │   │
│  │  - SqlQueryManager                                    │   │
│  │  - DispatchManager                                    │   │
│  │  - LocalQueryProvider                                 │   │
│  │  - Query (execution state)                            │   │
│  └───────────────────────────────────────────────────────┘   │
└────────────────────────────────────────────────────────────────┘
```

---

## Implementation Details

### Gateway Implementation

**File:** `presto-mcp-gateway/src/main/java/com/facebook/presto/mcp/PrestoInternalClient.java`

```java
public class PrestoInternalClient {
    private final ManagedChannel channel;
    private final QueryServiceGrpc.QueryServiceBlockingStub queryStub;

    public PrestoInternalClient(String coordinatorHost, int internalPort) {
        // Connect via gRPC to internal API
        this.channel = ManagedChannelBuilder
            .forAddress(coordinatorHost, internalPort)
            .usePlaintext()
            .build();
        this.queryStub = QueryServiceGrpc.newBlockingStub(channel);
    }

    public QuerySubmitResponse submitQuery(String sql, SessionContext session) {
        // Call internal API directly
        QuerySubmitRequest request = QuerySubmitRequest.newBuilder()
            .setSql(sql)
            .setCatalog(session.getCatalog())
            .setSchema(session.getSchema())
            .setUser(session.getUser())
            .putAllSessionProperties(session.getProperties())
            .build();

        return queryStub.submitQuery(request);
    }

    public QueryResultBatch pollResults(String queryId, long token, long maxBytes) {
        // Poll using internal API (more efficient than REST)
        PollResultsRequest request = PollResultsRequest.newBuilder()
            .setQueryId(queryId)
            .setToken(token)
            .setMaxBytes(maxBytes)
            .build();

        PollResultsResponse response = queryStub.pollResults(request);

        return QueryResultBatch.builder()
            .queryId(queryId)
            .columns(response.getColumnsList())
            .rows(convertRows(response.getRowsList()))
            .hasMore(response.getHasMore())
            .nextToken(response.getNextToken())
            .build();
    }

    // Adaptive buffering logic (same as Option A)
    public Object executeQuery(String sql, SessionContext session) {
        QuerySubmitResponse submit = submitQuery(sql, session);
        String queryId = submit.getQueryId();

        List<List<Object>> buffer = new ArrayList<>();
        long bytesBuffered = 0;
        long token = 0;

        while (bytesBuffered < STREAMING_THRESHOLD) {
            QueryResultBatch batch = pollResults(queryId, token, 16 * 1024 * 1024);

            buffer.addAll(batch.getRows());
            bytesBuffered += estimateSize(batch);

            if (!batch.hasMore()) {
                // Completed within threshold
                return JsonRpcResponse.success(Map.of(
                    "queryId", queryId,
                    "rows", buffer,
                    "status", "completed"
                ));
            }

            token = batch.getNextToken();
        }

        // Exceeded threshold - switch to SSE
        return streamResults(buffer, queryId, token);
    }
}
```

### Coordinator Plugin Implementation

**File:** `presto-query-access-plugin/src/main/java/com/facebook/presto/queryaccess/InternalQueryService.java`

#### Option 3a: gRPC API

```java
// gRPC service definition
service QueryService {
    rpc SubmitQuery(QuerySubmitRequest) returns (QuerySubmitResponse);
    rpc PollResults(PollResultsRequest) returns (PollResultsResponse);
    rpc CancelQuery(CancelQueryRequest) returns (CancelQueryResponse);
    rpc GetQueryInfo(GetQueryInfoRequest) returns (GetQueryInfoResponse);
}

// Implementation
@Singleton
public class InternalQueryService extends QueryServiceGrpc.QueryServiceImplBase {
    private final SqlQueryManager queryManager;
    private final LocalQueryProvider queryProvider;
    private final DispatchManager dispatchManager;

    @Inject
    public InternalQueryService(
            SqlQueryManager queryManager,
            LocalQueryProvider queryProvider,
            DispatchManager dispatchManager) {
        this.queryManager = queryManager;
        this.queryProvider = queryProvider;
        this.dispatchManager = dispatchManager;
    }

    @Override
    public void submitQuery(
            QuerySubmitRequest request,
            StreamObserver<QuerySubmitResponse> responseObserver) {
        try {
            // Create session context
            SessionContext session = SessionContext.builder()
                .setUser(request.getUser())
                .setCatalog(request.getCatalog())
                .setSchema(request.getSchema())
                .setSessionProperties(request.getSessionPropertiesMap())
                .build();

            // Submit query using DispatchManager
            QueryId queryId = dispatchManager.createQueryId();
            dispatchManager.createQuery(
                queryId,
                request.getSql(),
                session,
                ImmutableMap.of()
            );

            // Return response
            QuerySubmitResponse response = QuerySubmitResponse.newBuilder()
                .setQueryId(queryId.toString())
                .setStatus("SUBMITTED")
                .build();

            responseObserver.onNext(response);
            responseObserver.onCompleted();

        } catch (Exception e) {
            responseObserver.onError(Status.INTERNAL
                .withDescription(e.getMessage())
                .asRuntimeException());
        }
    }

    @Override
    public void pollResults(
            PollResultsRequest request,
            StreamObserver<PollResultsResponse> responseObserver) {
        try {
            QueryId queryId = QueryId.valueOf(request.getQueryId());
            Query query = queryProvider.getQuery(queryId);

            if (query == null) {
                responseObserver.onError(Status.NOT_FOUND
                    .withDescription("Query not found: " + queryId)
                    .asRuntimeException());
                return;
            }

            // Poll for results
            long token = request.getToken();
            DataSize targetSize = DataSize.valueOf(request.getMaxBytes() + "B");

            ListenableFuture<QueryResults> futureResults =
                query.waitForResults(token, null, "http", Duration.valueOf("30s"), targetSize, false);

            // Block and wait (or make this async)
            QueryResults results = futureResults.get(30, TimeUnit.SECONDS);

            // Convert to proto response
            PollResultsResponse.Builder response = PollResultsResponse.newBuilder()
                .setQueryId(queryId.toString());

            // Add columns
            if (results.getColumns() != null) {
                results.getColumns().forEach(col ->
                    response.addColumns(Column.newBuilder()
                        .setName(col.getName())
                        .setType(col.getType())
                        .build())
                );
            }

            // Add rows
            if (results.getData() != null) {
                results.getData().forEach(row ->
                    response.addRows(convertRow(row))
                );
            }

            // Set pagination info
            response.setHasMore(results.getNextUri() != null);
            if (results.getNextUri() != null) {
                response.setNextToken(token + 1);
            }

            responseObserver.onNext(response.build());
            responseObserver.onCompleted();

        } catch (TimeoutException e) {
            responseObserver.onError(Status.DEADLINE_EXCEEDED
                .withDescription("Polling timeout")
                .asRuntimeException());
        } catch (Exception e) {
            responseObserver.onError(Status.INTERNAL
                .withDescription(e.getMessage())
                .asRuntimeException());
        }
    }
}
```

**Plugin Registration:**

```java
public class QueryAccessPlugin implements Plugin {
    @Override
    public Iterable<ServerFactory> getServerFactories() {
        return ImmutableList.of(new GrpcServerFactory());
    }

    private static class GrpcServerFactory implements ServerFactory {
        @Override
        public Server createServer(Injector injector) {
            InternalQueryService queryService = injector.getInstance(InternalQueryService.class);

            return ServerBuilder.forPort(8081)  // Internal port
                .addService(queryService)
                .build();
        }
    }
}
```

#### Option 3b: Custom REST API

Alternatively, use a custom REST API instead of gRPC:

```java
@Path("/v1/internal/query")
public class InternalQueryResource {
    private final SqlQueryManager queryManager;
    private final LocalQueryProvider queryProvider;

    @POST
    @Path("/submit")
    @Consumes(APPLICATION_JSON)
    @Produces(APPLICATION_JSON)
    public Response submitQuery(QuerySubmitRequest request) {
        // Similar to gRPC implementation
        QueryId queryId = dispatchManager.createQueryId();
        dispatchManager.createQuery(queryId, request.getSql(), session, ImmutableMap.of());

        return Response.ok(Map.of(
            "queryId", queryId.toString(),
            "status", "SUBMITTED"
        )).build();
    }

    @POST
    @Path("/poll")
    @Consumes(APPLICATION_JSON)
    @Produces(APPLICATION_JSON)
    public Response pollResults(PollResultsRequest request) {
        // Poll and return results
        Query query = queryProvider.getQuery(QueryId.valueOf(request.getQueryId()));
        QueryResults results = query.waitForResults(...).get();

        return Response.ok(Map.of(
            "queryId", request.getQueryId(),
            "columns", results.getColumns(),
            "rows", results.getData(),
            "hasMore", results.getNextUri() != null,
            "nextToken", token + 1
        )).build();
    }
}
```

---

## Advantages of Hybrid Approach

### 1. **More Efficient Than Public REST**

**Public REST API:**
```
Gateway → POST /v1/statement
           ↓
        Parse HTTP headers
        Extract session properties
        Authenticate
        Create Query object
        Serialize response to JSON
        Add response headers
           ↓
Gateway ← JSON response + many headers
```

**Internal API (gRPC):**
```
Gateway → gRPC submitQuery(sql, session)
           ↓
        Direct method call
        Binary protobuf serialization
        No HTTP overhead
           ↓
Gateway ← Protobuf response
```

**Efficiency Gains:**
- ✅ No HTTP header parsing/generation
- ✅ Binary serialization (protobuf) vs JSON
- ✅ Direct access to query manager (fewer layers)
- ✅ Reduced serialization overhead (~20-30% less CPU)

### 2. **Cleaner API Surface**

Internal API can be purpose-built for gateway use:

```protobuf
// Focused on gateway needs, not general client use
service QueryService {
    // Streamlined submit (no need for all REST features)
    rpc SubmitQuery(QuerySubmitRequest) returns (QuerySubmitResponse);

    // Batch polling optimized for gateway buffering
    rpc PollResultsBatch(PollBatchRequest) returns (stream PollResultsResponse);

    // Optimized metadata queries
    rpc GetCatalogs(Empty) returns (CatalogsResponse);
}
```

vs public REST which must support:
- Complex header-based session management
- Backward compatibility constraints
- Browser-friendly responses
- Cookie-like header semantics

### 3. **Better Type Safety**

gRPC provides strongly-typed contracts:

```protobuf
message QuerySubmitRequest {
    string sql = 1;
    string catalog = 2;
    string schema = 3;
    string user = 4;
    map<string, string> session_properties = 5;
}

message PollResultsResponse {
    string query_id = 1;
    repeated Column columns = 2;
    repeated Row rows = 3;
    bool has_more = 4;
    int64 next_token = 5;
}
```

Changes to API are compile-time checked, reducing integration bugs.

### 4. **Potential for Streaming Optimizations**

gRPC supports bidirectional streaming:

```protobuf
// Could potentially stream results directly
service QueryService {
    rpc StreamResults(StreamResultsRequest) returns (stream ResultBatch);
}
```

This could enable:
- Gateway requests stream
- Coordinator pushes results as available
- No polling overhead
- Lower latency

---

## Disadvantages of Hybrid Approach

### 1. **Increased Complexity**

**Components to maintain:**
- MCP Gateway service (new)
- Coordinator plugin (new)
- Internal API contract (new)
- gRPC infrastructure (new)

**vs Option A (Gateway only):**
- MCP Gateway service (new)
- Uses existing Presto client library (already maintained)

**Complexity comparison:**
```
Option A: Gateway (uses public REST)
  └─ 1 new component

Option C: Gateway + Plugin (uses internal API)
  ├─ Gateway (new)
  ├─ Plugin (new)
  └─ API contract (new)
```

### 2. **Versioning Challenges**

**Problem:** Gateway and plugin must stay in sync

```
Gateway v1.2 → calls new API method
                ↓
Plugin v1.1 ← doesn't have new method
                ↓
              Error!
```

**Solutions:**
- Version the internal API (protobuf supports this)
- Deploy gateway and plugin together
- Use feature flags for compatibility

**vs Option A:**
- Public REST API is versioned and stable
- Gateway just uses client library
- No version skew issues

### 3. **Operational Overhead**

**Deployment:**
```
Option A:
  1. Deploy gateway
  2. Point to existing Presto coordinators
  Done.

Option C:
  1. Build plugin
  2. Deploy plugin to ALL coordinators
  3. Restart coordinators (or reload plugin)
  4. Deploy gateway
  5. Configure gateway to use internal port
  Done.
```

**Updates:**
```
Option A:
  - Update gateway
  - Rolling restart gateways
  No coordinator changes needed

Option C:
  - Update plugin AND gateway (coordinated)
  - Deploy plugin to coordinators
  - Restart/reload coordinators
  - Update gateways
  More coordination required
```

### 4. **Security Considerations**

Internal API creates new attack surface:

```
┌──────────────────────┐
│  Public Internet     │
└──────────┬───────────┘
           │
    ┌──────▼────────┐
    │  Firewall     │
    └──────┬────────┘
           │
    ┌──────▼────────┐
    │  MCP Gateway  │  ← Public-facing
    └──────┬────────┘
           │ Internal API (port 8081)
    ┌──────▼────────┐
    │  Coordinator  │
    │  + Plugin     │
    └───────────────┘
```

**Security concerns:**
- Internal API port must be secured
- Can't expose to public internet
- Gateway must authenticate to internal API
- Need mTLS or similar for gateway ↔ coordinator

**vs Option A:**
- Uses existing public REST API
- Already secured and hardened
- No new ports or attack vectors

### 5. **Marginal Performance Gains**

**Reality check:** Is the efficiency gain worth it?

**Typical query execution:**
```
Query processing time: 5 seconds
REST overhead: ~5-10ms
gRPC savings: ~2-3ms

Total improvement: 0.05% faster
```

For most queries, the serialization overhead is **negligible** compared to:
- Query planning time (100ms-1s)
- Query execution time (seconds-minutes)
- Network latency (1-10ms)

**When it matters:**
- Very high query volume (>10,000/sec)
- Tiny queries (<100ms execution)
- Extreme latency sensitivity

Most agentic workloads don't fit this profile.

---

## Cost-Benefit Analysis

### Quantifying the Tradeoffs

| Aspect | Option A (Gateway + Public REST) | Option C (Gateway + Plugin + Internal API) | Delta |
|--------|----------------------------------|-------------------------------------------|-------|
| **Components** | 1 (Gateway) | 3 (Gateway + Plugin + API) | +2 components |
| **Deployment steps** | 2 (build gateway, deploy) | 5 (build both, deploy both, restart) | +3 steps |
| **Latency per query** | ~5-10ms serialization | ~2-3ms serialization | -5ms (0.1-1% faster) |
| **Throughput** | 1000+ qps/gateway | 1200+ qps/gateway | +20% throughput |
| **Versioning** | Stable (client library) | Must coordinate versions | More complex |
| **Security** | Existing REST API | New internal API | New attack surface |
| **Operational** | Simple (1 service) | Complex (2 services) | Higher ops burden |

### When the Hybrid Approach Makes Sense

**Justification threshold:**

✅ **Consider hybrid if:**
- Extremely high query volume (>5,000 qps)
- Very latency-sensitive (<50ms p99)
- Mostly small queries (<1 second execution)
- Have mature microservices operations
- Can invest in versioning/deployment coordination

❌ **Avoid hybrid if:**
- Query volume is moderate (<1,000 qps)
- Queries are longer-running (>1 second typical)
- Prefer operational simplicity
- Small ops team
- Just starting with gateway approach

---

## Recommendation: Start with Option A

### Evolutionary Architecture

```
Phase 1: Gateway + Public REST (Option A)
  - Prove concept works
  - Gather performance data
  - Understand actual bottlenecks
  ↓
Phase 2: Measure and Analyze
  - Is serialization a real bottleneck?
  - What's the actual latency breakdown?
  - Do we need more throughput?
  ↓
Phase 3: Optimize If Needed
  - If REST is bottleneck → Consider hybrid
  - If not bottleneck → Stick with Option A
```

### Premature Optimization?

**The hybrid approach optimizes something that's rarely the bottleneck:**

```
Typical query latency breakdown:
┌────────────────────────────────────────────┐
│ Query planning: 100-500ms         (70%)    │
├────────────────────────────────────────────┤
│ Query execution: 1-10s+           (25%)    │
├────────────────────────────────────────────┤
│ Network + serialization: 5-20ms   (4%)     │
├────────────────────────────────────────────┤
│ MCP protocol: 1-5ms                (1%)    │
└────────────────────────────────────────────┘

Hybrid approach saves: ~5ms out of seconds
```

**Better optimizations:**
- Query result caching
- Query plan caching
- Compression (already supported)
- Binary format (already supported)

---

## Implementation Effort Comparison

### Option A: Gateway Only

**Effort:** 11-15 weeks

- Gateway service: 8 weeks
- Testing: 2 weeks
- Documentation: 1 week

**Team:** 2 engineers

### Option C: Gateway + Plugin

**Effort:** 18-24 weeks

- Gateway service: 8 weeks
- Plugin service: 6 weeks
- Internal API design: 2 weeks
- Integration testing: 3 weeks
- Version management: 2 weeks
- Documentation: 2 weeks

**Team:** 3 engineers (gateway, plugin, devops)

**Additional ongoing cost:**
- Coordination overhead
- Version management
- Two-component deployments

---

## Conclusion

The hybrid approach (Gateway + Plugin with internal API) provides **marginal efficiency gains** at the cost of **significant complexity**.

**Verdict:** ❌ Not recommended for most use cases

**Reasoning:**
1. **Small gains:** 20-30% serialization efficiency = <0.1% end-to-end improvement
2. **High cost:** 2x components, complex versioning, more ops burden
3. **Premature optimization:** Serialization is rarely the bottleneck
4. **Evolutionary approach:** Can add later if proven necessary

**Better strategy:**
1. Start with Option A (Gateway + Public REST)
2. Measure actual bottlenecks
3. Optimize based on data, not assumptions
4. Only add complexity if justified by metrics

**The YAGNI principle applies:** You Ain't Gonna Need It (unless proven otherwise).

---

**Document Version:** 1.0
**Date:** 2025-10-31
**Related:** `docs/mcp-load-balancing-analysis.md`, `docs/mcp-integration-design.md`
