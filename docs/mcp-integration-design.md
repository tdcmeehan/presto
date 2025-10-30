# MCP Server Integration Design for Presto

## Executive Summary

This document analyzes the integration of Model Context Protocol (MCP) server capabilities into Presto, evaluating architectural approaches and addressing the fundamental challenge of bridging Presto's streaming polling model with MCP's request-response protocol.

**Recommendation:** Implement as a **Coordinator Plugin** with optional core enhancements for streaming support.

---

## 1. Background

### 1.1 Presto's Query Execution Model

Presto uses a **token-based polling model** for result delivery:

1. Client submits query via POST `/v1/statement`
2. Server returns initial `QueryResults` with a `nextUri`
3. Client polls `nextUri` (e.g., `/v1/statement/executing/{queryId}/{token}`)
4. Server returns data batch (1MB-128MB) plus new `nextUri` with incremented token
5. Polling continues until query completes (no `nextUri` in final response)

**Key Characteristics:**
- **Client-driven:** Client controls polling frequency and batch size
- **Incremental:** Results stream as they become available
- **Stateful:** Server maintains query state across poll requests
- **Async-capable:** Uses JAX-RS `AsyncResponse` with Guava `ListenableFuture`
- **Dual formats:** JSON (deserialized rows) or Binary (base64 `SerializedPage`)

**Reference:** `presto-main/src/main/java/com/facebook/presto/server/protocol/QueuedStatementResource.java`

### 1.2 Model Context Protocol (MCP)

MCP is an open protocol enabling AI assistants to interact with external tools and data sources via JSON-RPC 2.0.

**Protocol Characteristics:**
- **Transport:** HTTP, Server-Sent Events (SSE), or stdio
- **Message Format:** JSON-RPC 2.0
- **Pattern:** Request-response (client sends request, server sends complete response)
- **Tools:** Discovered via `tools/list`, invoked via `tools/call`
- **Resources:** Listed via `resources/list`, read via `resources/read`
- **Pagination:** Cursor-based for list operations (not data streaming)

**Key Methods:**
```json
// Initialize connection
{"jsonrpc": "2.0", "id": 1, "method": "initialize", "params": {...}}

// List available tools
{"jsonrpc": "2.0", "id": 2, "method": "tools/list"}

// Invoke a tool
{"jsonrpc": "2.0", "id": 3, "method": "tools/call",
 "params": {"name": "query_presto", "arguments": {"sql": "SELECT ..."}}}

// Response with result
{"jsonrpc": "2.0", "id": 3, "result": {"content": [...]}}
```

---

## 2. Integration Challenge: Streaming vs Request-Response

### 2.1 The Fundamental Mismatch

**Presto's Model:**
```
Client → POST query
Server → {nextUri: "/token/0"}
Client → GET /token/0
Server → {data: [...], nextUri: "/token/1"}
Client → GET /token/1
Server → {data: [...], nextUri: "/token/2"}
...
Client → GET /token/N
Server → {data: [...], nextUri: null}  // Done
```

**MCP's Model:**
```
Client → {"method": "tools/call", "params": {"name": "query", "arguments": {"sql": "..."}}}
Server → {"result": {"content": [...]}}  // Complete response expected
```

### 2.2 Impedance Mismatch Analysis

| Aspect | Presto | MCP | Compatibility |
|--------|--------|-----|---------------|
| **Invocation** | HTTP POST + polling | JSON-RPC tools/call | Compatible (can adapt) |
| **Data Return** | Incremental batches | Single response | **Incompatible** |
| **Client Control** | Client-driven polling | Server-driven response | Moderate conflict |
| **Completion Signal** | No nextUri | Response sent | Compatible |
| **Async Support** | Native (AsyncResponse) | Transport-dependent | Compatible |
| **Error Handling** | Per-poll errors | JSON-RPC error object | Compatible |

### 2.3 Resolution Strategies

Three approaches to resolve the streaming mismatch:

#### Option A: Buffer and Wait (Simple, Limited)
- MCP tool blocks until query completes
- Buffers all results in memory
- Returns complete dataset in single JSON-RPC response

**Pros:** Simplest implementation, pure MCP compliance
**Cons:**
- Memory exhaustion on large queries
- Timeout issues for long queries
- Poor user experience (no progress feedback)
- Defeats Presto's streaming advantage

**Use Case:** Small analytical queries only

#### Option B: Pagination via Resources (MCP-Native)
- Query invocation returns query ID
- Client uses `resources/read` with cursor to fetch batches
- Leverages MCP's cursor-based pagination

**Pros:** MCP-native pattern, memory efficient
**Cons:**
- Requires multiple round-trips (not truly streaming)
- Cursor semantics differ from Presto's token model
- Client must implement pagination logic

**Use Case:** Moderate datasets where batch fetching is acceptable

#### Option C: SSE Streaming (Advanced, Best UX)
- MCP tool returns initial response with query ID
- Server uses MCP's SSE transport for progressive updates
- Client receives data events as query executes

**Pros:** True streaming, best user experience, memory efficient
**Cons:**
- Requires SSE transport support
- More complex implementation
- Not all MCP clients support SSE

**Use Case:** Interactive AI applications, large datasets

---

## 3. Architecture Options

### 3.1 Option 1: Separate Component (Gateway/Proxy)

Deploy standalone MCP server that proxies to Presto.

```
AI Client <--MCP--> MCP Gateway <--HTTP--> Presto Coordinator
```

**Implementation:**
```java
// Separate service (e.g., presto-mcp-gateway)
public class PrestoMCPGateway {
    private final PrestoClient prestoClient;

    @MCPTool(name = "query_presto")
    public MCPResponse executeQuery(String sql, Map<String, String> sessionProperties) {
        // Submit query to Presto
        QueryResults initial = prestoClient.submitQuery(sql);

        // Strategy: Buffer and wait (for simplicity)
        List<List<Object>> allRows = new ArrayList<>();
        QueryResults current = initial;

        while (current.getNextUri() != null) {
            current = prestoClient.poll(current.getNextUri());
            allRows.addAll(current.getData());
        }

        return MCPResponse.success(allRows);
    }
}
```

**Pros:**
- Clean separation of concerns
- Independent deployment and scaling
- No changes to Presto core
- Easy to version and evolve independently
- Can support multiple Presto clusters

**Cons:**
- Additional network hop (latency)
- Duplication of connection management
- Must replicate authentication/authorization logic
- Operational overhead (another service to manage)
- Harder to leverage internal Presto optimizations

**Verdict:** ✅ Good for pilot/PoC, multi-cluster scenarios

---

### 3.2 Option 2: Coordinator Plugin

Implement as a Presto plugin using existing extension points.

```
AI Client <--MCP--> [Presto Coordinator + MCP Plugin]
```

**Implementation:**
```java
// presto-mcp-plugin/src/main/java/com/facebook/presto/mcp/MCPPlugin.java
public class MCPPlugin implements Plugin {
    @Override
    public Iterable<EventListenerFactory> getEventListenerFactories() {
        // Could use event listeners to track query progress
        return ImmutableList.of(new MCPEventListenerFactory());
    }

    // Custom extension point (would need to be added to core)
    @Override
    public Iterable<EndpointFactory> getEndpointFactories() {
        return ImmutableList.of(new MCPEndpointFactory());
    }
}

// Custom JAX-RS resource (similar to QueuedStatementResource)
@Path("/v1/mcp")
public class MCPResource {
    @Inject
    private LocalQueryProvider queryProvider;

    @Inject
    private DispatchManager dispatchManager;

    @POST
    @Consumes(APPLICATION_JSON)
    @Produces(APPLICATION_JSON)
    public Response handleJSONRPC(String requestBody) {
        JsonRpcRequest request = parseRequest(requestBody);

        if ("tools/call".equals(request.getMethod())) {
            String toolName = request.getParams().get("name");

            if ("query_presto".equals(toolName)) {
                return executePrestoQuery(request);
            }
        }

        return jsonRpcError(request.getId(), "Method not found");
    }

    private Response executePrestoQuery(JsonRpcRequest request) {
        String sql = request.getParams().get("arguments").get("sql");

        // Option B: Return query ID + support pagination via resources
        QueryId queryId = dispatchManager.createQuery(...);

        return jsonRpcSuccess(request.getId(), Map.of(
            "queryId", queryId.toString(),
            "status", "submitted",
            "message", "Use resources/read with cursor to fetch results"
        ));
    }

    @POST
    @Path("/resources/read")
    public Response readResource(JsonRpcRequest request) {
        String uri = request.getParams().get("uri"); // e.g., "presto://query/{queryId}"
        String cursor = request.getParams().get("cursor");

        // Parse queryId from URI
        QueryId queryId = extractQueryId(uri);

        // Get query from provider
        Query query = queryProvider.getQuery(queryId);

        // Fetch next batch (map cursor to token)
        long token = cursor != null ? parseCursor(cursor) : 0L;
        QueryResults results = query.waitForResults(token, ...);

        // Convert to MCP resource response
        String nextCursor = results.getNextUri() != null ?
            String.valueOf(token + 1) : null;

        return jsonRpcSuccess(request.getId(), Map.of(
            "contents", convertToMCPContent(results),
            "cursor", nextCursor
        ));
    }
}
```

**Plugin Loading:**
```java
// Existing mechanism in presto-main-base/src/main/java/com/facebook/presto/server/PluginManager.java
// Plugins are discovered from etc/catalog/ or lib/plugin/ directories
// MCP plugin would be installed as: lib/plugin/presto-mcp/
```

**Pros:**
- Leverages existing plugin architecture
- Access to internal Presto APIs (QueryManager, DispatchManager)
- No separate service to deploy
- Can reuse Presto's authentication/authorization
- Natural fit for optional features
- Minimal core changes (only if adding new extension points)

**Cons:**
- Plugin API may lack necessary extension points (e.g., custom JAX-RS resources)
- May require core enhancements to expose needed APIs
- Tied to Presto release cycle
- Limited ability to scale independently

**Required Core Changes:**
1. Add `EndpointFactory` interface to allow plugins to register JAX-RS resources
2. Update `CoordinatorModule` to discover and bind plugin-provided endpoints
3. Possibly expose `LocalQueryProvider` or `Query` APIs for plugin access

**Verdict:** ✅✅ **RECOMMENDED** - Best balance of integration and modularity

---

### 3.3 Option 3: Core Integration

Build MCP support directly into Presto core.

```
AI Client <--MCP--> [Presto Coordinator with native MCP support]
```

**Implementation:**
```java
// presto-main/src/main/java/com/facebook/presto/server/protocol/MCPStatementResource.java
@Path("/v1/mcp")
public class MCPStatementResource {
    // Similar to QueuedStatementResource but with JSON-RPC protocol
    // Full access to all internal APIs
}

// presto-main/src/main/java/com/facebook/presto/server/CoordinatorModule.java
public class CoordinatorModule extends AbstractConfigurationAwareModule {
    protected void setup(Binder binder) {
        // ...
        httpServerBinder(binder).bindResource("/", MCPStatementResource.class);

        // Add MCP-specific services
        binder.bind(MCPProtocolManager.class).in(Scopes.SINGLETON);
    }
}
```

**Pros:**
- Deepest integration, optimal performance
- Full access to internal APIs without restrictions
- Can implement advanced streaming strategies (Option C: SSE)
- Unified authentication and lifecycle management

**Cons:**
- Increases core complexity
- Harder to maintain and evolve
- Couples MCP evolution to Presto releases
- Bloats core with protocol-specific code
- May not be used by majority of users

**Verdict:** ❌ Not recommended unless MCP becomes a primary interface

---

## 4. Recommended Architecture: Coordinator Plugin with Core Extensions

### 4.1 High-Level Design

```
┌─────────────────────────────────────────────────────────────┐
│                     Presto Coordinator                       │
│                                                              │
│  ┌────────────────────────────────────────────────────────┐ │
│  │                    MCP Plugin                          │ │
│  │                                                        │ │
│  │  ┌──────────────┐      ┌─────────────────────────┐   │ │
│  │  │ MCPResource  │      │  MCPQueryManager        │   │ │
│  │  │  (JAX-RS)    │─────▶│  - Query execution     │   │ │
│  │  │              │      │  - Result pagination    │   │ │
│  │  │  /v1/mcp     │      │  - Cursor management    │   │ │
│  │  └──────────────┘      └─────────────────────────┘   │ │
│  │         │                          │                  │ │
│  │         │                          │                  │ │
│  └─────────┼──────────────────────────┼──────────────────┘ │
│            │                          │                    │
│            ▼                          ▼                    │
│  ┌──────────────────┐      ┌──────────────────────┐       │
│  │ EndpointRegistry │      │  DispatchManager     │       │
│  │ (NEW)            │      │  LocalQueryProvider  │       │
│  └──────────────────┘      └──────────────────────┘       │
│                                                            │
└────────────────────────────────────────────────────────────┘
                           │
                           │ MCP Protocol (JSON-RPC)
                           │
                    ┌──────▼──────┐
                    │  AI Client  │
                    │  (Claude,   │
                    │   etc.)     │
                    └─────────────┘
```

### 4.2 Core Enhancements Required

#### Enhancement 1: Pluggable Endpoint Registry

**File:** `presto-spi/src/main/java/com/facebook/presto/spi/EndpointFactory.java` (NEW)

```java
package com.facebook.presto.spi;

/**
 * Factory for creating custom JAX-RS endpoints.
 * Plugins can implement this to register protocol handlers.
 */
public interface EndpointFactory {
    /**
     * Returns the JAX-RS resource class to register.
     */
    Class<?> getResourceClass();

    /**
     * Returns the base path for this endpoint (optional).
     * If not provided, uses @Path annotation on resource class.
     */
    default Optional<String> getBasePath() {
        return Optional.empty();
    }
}
```

**File:** `presto-spi/src/main/java/com/facebook/presto/spi/Plugin.java` (MODIFY)

```java
public interface Plugin {
    // Existing methods...

    /**
     * Returns custom endpoint factories for registering protocol handlers.
     * Added in version X.Y for MCP and other protocol support.
     */
    default Iterable<EndpointFactory> getEndpointFactories() {
        return ImmutableList.of();
    }
}
```

**File:** `presto-main/src/main/java/com/facebook/presto/server/CoordinatorModule.java` (MODIFY)

```java
public class CoordinatorModule extends AbstractConfigurationAwareModule {
    protected void setup(Binder binder) {
        // ... existing bindings ...

        // NEW: Bind endpoint registry
        binder.bind(PluginEndpointRegistry.class).in(Scopes.SINGLETON);
    }
}

// NEW class
public class PluginEndpointRegistry {
    @Inject
    public PluginEndpointRegistry(
            PluginManager pluginManager,
            HttpServerBinder httpServerBinder) {

        // Register endpoints from plugins
        for (EndpointFactory factory : pluginManager.getEndpointFactories()) {
            httpServerBinder.bindResource("/", factory.getResourceClass());
        }
    }
}
```

#### Enhancement 2: Query Access API for Plugins

**File:** `presto-main/src/main/java/com/facebook/presto/server/protocol/QueryAccessProvider.java` (NEW)

```java
package com.facebook.presto.server.protocol;

/**
 * Provides plugin access to query execution and results.
 * Abstracts internal Query implementation details.
 */
public interface QueryAccessProvider {
    /**
     * Submits a query and returns its ID.
     */
    QueryId submitQuery(
        SessionContext sessionContext,
        String sql,
        Map<String, String> preparedStatements);

    /**
     * Gets query information including status and stats.
     */
    QueryInfo getQueryInfo(QueryId queryId);

    /**
     * Polls for next batch of results.
     *
     * @param token Result token (0-indexed)
     * @param targetResultSize Maximum bytes to return
     * @param binaryResults Whether to return binary format
     * @return Future with query results batch
     */
    ListenableFuture<QueryResultBatch> getResults(
        QueryId queryId,
        long token,
        DataSize targetResultSize,
        boolean binaryResults);

    /**
     * Cancels a running query.
     */
    void cancelQuery(QueryId queryId);
}

public class QueryResultBatch {
    private final List<Column> columns;
    private final List<List<Object>> rows; // or List<String> binaryPages
    private final boolean hasMore;
    private final long nextToken;
    private final QueryInfo queryInfo;

    // Getters...
}
```

### 4.3 MCP Plugin Implementation

#### Module Structure
```
presto-mcp-plugin/
├── pom.xml
└── src/main/java/com/facebook/presto/mcp/
    ├── MCPPlugin.java
    ├── MCPResource.java (JAX-RS endpoint)
    ├── MCPQueryManager.java
    ├── MCPToolRegistry.java
    ├── protocol/
    │   ├── JsonRpcRequest.java
    │   ├── JsonRpcResponse.java
    │   └── MCPTransport.java
    └── tools/
        ├── QueryTool.java
        ├── SchemaTool.java
        └── CatalogTool.java
```

#### Core Implementation

**File:** `presto-mcp-plugin/src/main/java/com/facebook/presto/mcp/MCPPlugin.java`

```java
public class MCPPlugin implements Plugin {
    @Override
    public Iterable<EndpointFactory> getEndpointFactories() {
        return ImmutableList.of(new MCPEndpointFactory());
    }

    private static class MCPEndpointFactory implements EndpointFactory {
        @Override
        public Class<?> getResourceClass() {
            return MCPResource.class;
        }
    }
}
```

**File:** `presto-mcp-plugin/src/main/java/com/facebook/presto/mcp/MCPResource.java`

```java
@Path("/v1/mcp")
public class MCPResource {
    private final MCPToolRegistry toolRegistry;
    private final MCPQueryManager queryManager;

    @Inject
    public MCPResource(
            QueryAccessProvider queryAccessProvider,
            MetadataManager metadataManager) {
        this.queryManager = new MCPQueryManager(queryAccessProvider);
        this.toolRegistry = new MCPToolRegistry(queryManager, metadataManager);
    }

    @POST
    @Consumes(APPLICATION_JSON)
    @Produces(APPLICATION_JSON)
    public Response handleJsonRpc(String body) {
        try {
            JsonRpcRequest request = JsonRpcRequest.parse(body);
            JsonRpcResponse response = dispatch(request);
            return Response.ok(response.toJson()).build();
        } catch (JsonRpcException e) {
            return Response.ok(e.toErrorResponse().toJson()).build();
        }
    }

    private JsonRpcResponse dispatch(JsonRpcRequest request) {
        switch (request.getMethod()) {
            case "initialize":
                return handleInitialize(request);
            case "tools/list":
                return handleToolsList(request);
            case "tools/call":
                return handleToolCall(request);
            case "resources/list":
                return handleResourcesList(request);
            case "resources/read":
                return handleResourceRead(request);
            default:
                throw JsonRpcException.methodNotFound(request.getId());
        }
    }

    private JsonRpcResponse handleToolCall(JsonRpcRequest request) {
        String toolName = request.getParams().getString("name");
        Map<String, Object> arguments = request.getParams().getMap("arguments");

        MCPTool tool = toolRegistry.getTool(toolName)
            .orElseThrow(() -> JsonRpcException.invalidParams("Unknown tool: " + toolName));

        Object result = tool.execute(arguments);

        return JsonRpcResponse.success(request.getId(), result);
    }

    private JsonRpcResponse handleResourceRead(JsonRpcRequest request) {
        String uri = request.getParams().getString("uri");
        String cursor = request.getParams().getString("cursor");

        // Parse URI: presto://query/{queryId}
        if (uri.startsWith("presto://query/")) {
            String queryIdStr = uri.substring("presto://query/".length());
            return queryManager.fetchResults(queryIdStr, cursor);
        }

        throw JsonRpcException.invalidParams("Unknown resource URI: " + uri);
    }
}
```

**File:** `presto-mcp-plugin/src/main/java/com/facebook/presto/mcp/MCPQueryManager.java`

```java
public class MCPQueryManager {
    private final QueryAccessProvider queryAccessProvider;
    private final ConcurrentMap<String, QueryContext> activeQueries = new ConcurrentHashMap<>();

    public JsonRpcResponse executeQuery(
            String sql,
            Map<String, String> sessionProperties,
            String requestId) {

        // Submit query
        SessionContext session = createSessionContext(sessionProperties);
        QueryId queryId = queryAccessProvider.submitQuery(session, sql, ImmutableMap.of());

        // Store query context
        activeQueries.put(queryId.toString(), new QueryContext(queryId, session));

        // Return query handle (Strategy B: Pagination via Resources)
        return JsonRpcResponse.success(requestId, ImmutableMap.of(
            "type", "query",
            "queryId", queryId.toString(),
            "resourceUri", "presto://query/" + queryId,
            "status", "submitted",
            "message", "Use resources/read to fetch results with cursor-based pagination"
        ));
    }

    public JsonRpcResponse fetchResults(String queryIdStr, String cursor) {
        QueryId queryId = QueryId.valueOf(queryIdStr);
        QueryContext context = activeQueries.get(queryIdStr);

        if (context == null) {
            throw JsonRpcException.invalidParams("Query not found: " + queryIdStr);
        }

        // Map cursor to token
        long token = cursor != null ? Long.parseLong(cursor) : 0L;

        // Fetch results
        try {
            QueryResultBatch batch = queryAccessProvider.getResults(
                queryId,
                token,
                DataSize.valueOf("16MB"), // Configurable
                false // JSON format for MCP
            ).get(30, TimeUnit.SECONDS);

            // Convert to MCP resource content
            List<Map<String, Object>> content = convertToMCPContent(batch);

            // Build response
            Map<String, Object> result = new HashMap<>();
            result.put("contents", content);

            if (batch.hasMore()) {
                result.put("cursor", String.valueOf(batch.getNextToken()));
            }

            // Add metadata
            result.put("mimeType", "application/json");
            result.put("uri", "presto://query/" + queryIdStr);

            return JsonRpcResponse.success(null, result);

        } catch (TimeoutException e) {
            throw JsonRpcException.internalError("Query timeout");
        } catch (Exception e) {
            throw JsonRpcException.internalError("Query execution failed: " + e.getMessage());
        }
    }

    private List<Map<String, Object>> convertToMCPContent(QueryResultBatch batch) {
        // Convert Presto rows to MCP content format
        List<Map<String, Object>> content = new ArrayList<>();

        for (List<Object> row : batch.getRows()) {
            Map<String, Object> rowMap = new LinkedHashMap<>();
            for (int i = 0; i < batch.getColumns().size(); i++) {
                rowMap.put(batch.getColumns().get(i).getName(), row.get(i));
            }
            content.add(rowMap);
        }

        return content;
    }
}
```

### 4.4 Streaming Strategy: Option B (Recommended)

Use **MCP Resources with Cursor-Based Pagination** for result streaming:

**Query Execution Flow:**

```
1. AI calls tool: tools/call with name="query_presto", arguments={"sql": "SELECT ..."}

   Response: {
     "result": {
       "queryId": "20231201_123456_00000_abc12",
       "resourceUri": "presto://query/20231201_123456_00000_abc12",
       "status": "submitted"
     }
   }

2. AI reads resource: resources/read with uri="presto://query/...", cursor=null

   Response: {
     "result": {
       "contents": [
         {"col1": "value1", "col2": 123},
         {"col1": "value2", "col2": 456},
         ...
       ],
       "cursor": "1",  // Next token
       "mimeType": "application/json"
     }
   }

3. AI continues: resources/read with cursor="1"

   Response: {
     "result": {
       "contents": [...],
       "cursor": "2"
     }
   }

4. Final batch: resources/read with cursor="N"

   Response: {
     "result": {
       "contents": [...],
       // No cursor = query complete
     }
   }
```

**Advantages:**
- MCP-native pattern (cursor pagination is specified in MCP)
- Memory efficient (only buffering current batch)
- Timeout-safe (incremental progress)
- Aligns with Presto's token model

**Limitations:**
- Not truly streaming (requires multiple requests)
- AI agent must implement pagination loop
- Higher latency than SSE streaming

### 4.5 Configuration

**File:** `etc/catalog/mcp.properties`

```properties
# Enable MCP plugin
plugin.enabled=true

# Result batch size for pagination
mcp.result.batch-size=16MB

# Query timeout per batch fetch
mcp.query.timeout=30s

# Maximum concurrent MCP queries
mcp.query.max-concurrent=100

# Enable binary results (optional optimization)
mcp.binary-results=false
```

---

## 5. Addressing Streaming Compatibility

### 5.1 Strategy Comparison

| Strategy | Memory | Latency | Timeout Risk | MCP Compliance | Implementation |
|----------|--------|---------|--------------|----------------|----------------|
| **A: Buffer All** | ❌ High | ⚠️ High | ❌ High | ✅ Perfect | ✅ Simple |
| **B: Resource Pagination** | ✅ Low | ⚠️ Medium | ✅ Low | ✅ Good | ⚠️ Moderate |
| **C: SSE Streaming** | ✅ Low | ✅ Low | ✅ Low | ⚠️ Advanced | ❌ Complex |

### 5.2 Recommended Approach: Hybrid

Implement **Strategy B** (Resource Pagination) as default, with optional **Strategy C** (SSE) for advanced clients:

**Default Mode (Resource Pagination):**
```
tools/call → queryId
resources/read (cursor=null) → batch 1 + cursor
resources/read (cursor=1) → batch 2 + cursor
...
resources/read (cursor=N) → final batch (no cursor)
```

**Advanced Mode (SSE Streaming):**
```
tools/call → queryId + SSE endpoint URL
Client connects to SSE: /v1/mcp/stream/{queryId}
Server pushes events:
  event: data
  data: {"rows": [...]}

  event: data
  data: {"rows": [...]}

  event: complete
  data: {"status": "finished"}
```

**Implementation Note:**
SSE mode would require an additional endpoint in `MCPResource`:

```java
@GET
@Path("/stream/{queryId}")
@Produces(MediaType.SERVER_SENT_EVENTS)
public void streamResults(
        @PathParam("queryId") String queryId,
        @Context SseEventSink eventSink,
        @Context Sse sse) {

    QueryId qid = QueryId.valueOf(queryId);
    long token = 0;

    try {
        while (true) {
            QueryResultBatch batch = queryAccessProvider.getResults(
                qid, token, DataSize.valueOf("1MB"), false
            ).get();

            // Send data event
            OutboundSseEvent event = sse.newEventBuilder()
                .name("data")
                .data(convertToJson(batch.getRows()))
                .build();
            eventSink.send(event);

            if (!batch.hasMore()) {
                // Send completion event
                OutboundSseEvent completeEvent = sse.newEventBuilder()
                    .name("complete")
                    .data("{\"status\":\"finished\"}")
                    .build();
                eventSink.send(completeEvent);
                break;
            }

            token = batch.getNextToken();
        }
    } finally {
        eventSink.close();
    }
}
```

---

## 6. Proof of Concept Roadmap

### Phase 1: Core Extensions (2-3 weeks)
- [ ] Add `EndpointFactory` interface to Plugin SPI
- [ ] Implement `PluginEndpointRegistry` in CoordinatorModule
- [ ] Create `QueryAccessProvider` API and implementation
- [ ] Add unit tests for new APIs

### Phase 2: Basic MCP Plugin (3-4 weeks)
- [ ] Create presto-mcp-plugin module structure
- [ ] Implement JSON-RPC protocol handling
- [ ] Implement `tools/list` and `tools/call` for basic query execution
- [ ] Implement Strategy B (Resource Pagination)
- [ ] Add integration tests with mock MCP client

### Phase 3: Enhanced Tools (2-3 weeks)
- [ ] Add schema exploration tools (list catalogs, schemas, tables)
- [ ] Add query planning tool (EXPLAIN)
- [ ] Add metadata tools (column types, stats)
- [ ] Implement error handling and query cancellation

### Phase 4: Production Hardening (2-3 weeks)
- [ ] Add authentication/authorization integration
- [ ] Implement rate limiting and resource quotas
- [ ] Add monitoring and metrics (query count, latency, errors)
- [ ] Performance testing and optimization
- [ ] Documentation and deployment guide

### Phase 5: Advanced Streaming (Optional, 3-4 weeks)
- [ ] Implement SSE streaming endpoint
- [ ] Add client detection for SSE capability
- [ ] Benchmark performance vs. pagination
- [ ] Add configuration for streaming strategy selection

**Total Estimated Effort:** 12-17 weeks (3-4 months)

---

## 7. Alternative Considerations

### 7.1 GraphQL Instead of MCP?

GraphQL could provide similar capabilities with built-in streaming via subscriptions:

```graphql
subscription QueryResults {
  executeQuery(sql: "SELECT ...") {
    rows {
      columnName
      value
    }
    hasMore
    cursor
  }
}
```

**Comparison:**

| Feature | MCP | GraphQL |
|---------|-----|---------|
| AI Integration | ✅ Native | ⚠️ Custom |
| Streaming | SSE | Subscriptions |
| Tooling | Growing | Mature |
| Complexity | Lower | Higher |
| Standard | Emerging | Established |

**Verdict:** MCP is better suited for AI integration, but GraphQL could be evaluated for broader API needs.

### 7.2 gRPC Streaming?

gRPC supports bidirectional streaming and could be efficient:

```protobuf
service PrestoQuery {
  rpc ExecuteQuery(QueryRequest) returns (stream QueryResult);
}
```

**Pros:** Efficient binary protocol, true streaming, type-safe
**Cons:** Not MCP-compatible, requires code generation, AI tools expect JSON

**Verdict:** Not suitable for MCP integration, but could be separate feature.

---

## 8. Security Considerations

### 8.1 Authentication

**Options:**
1. **Inherit from Presto:** Use existing session context and authentication
2. **API Keys:** MCP-specific API keys for AI clients
3. **OAuth 2.0:** Token-based auth for delegated access

**Recommendation:** Use Presto's existing authentication (Kerberos, LDAP, OAuth) via session context.

### 8.2 Authorization

**Concerns:**
- AI agents may access sensitive data
- Need to enforce column/row-level security
- Query resource limits (CPU, memory)

**Implementation:**
```java
SessionContext createSessionContext(Map<String, String> properties) {
    // Extract user from MCP request (e.g., from auth headers)
    String user = extractUser();

    // Create session with security context
    return new SessionContext(
        new Identity(user, Optional.empty()),
        Optional.empty(),
        ClientInfo.empty(),
        properties,
        ImmutableMap.of(),
        Optional.empty(),
        // ... other session parameters
    );
}
```

### 8.3 Rate Limiting

Prevent abuse by limiting:
- Queries per minute per user
- Concurrent queries per user
- Total result size per query

Use `QueryBlockingRateLimiter` (existing in Presto).

---

## 9. Monitoring and Observability

### 9.1 Metrics

**Key Metrics to Track:**
- `mcp.queries.submitted` (counter)
- `mcp.queries.completed` (counter)
- `mcp.queries.failed` (counter)
- `mcp.query.duration` (histogram)
- `mcp.result.bytes` (histogram)
- `mcp.pagination.requests` (counter)

**Implementation:**
```java
@Inject
private MBeanExporter mbeanExporter;

public class MCPMetrics {
    @Managed
    public long getTotalQueries() { ... }

    @Managed
    public double getAverageQueryDurationMs() { ... }
}

// Register in plugin initialization
mbeanExporter.export("com.facebook.presto.mcp:name=MCPMetrics", new MCPMetrics());
```

### 9.2 Logging

**Log Key Events:**
- Query submission (with SQL, user, session properties)
- Pagination requests (query ID, cursor/token)
- Query completion (with stats)
- Errors (with stack traces)

**Log Format:**
```
[MCP] Query submitted: queryId=20231201_123456_00000_abc12, user=ai-agent, sql="SELECT ..."
[MCP] Results fetched: queryId=..., token=5, rows=1000, bytes=512KB
[MCP] Query completed: queryId=..., duration=5.2s, totalRows=50000
```

---

## 10. Conclusion

### 10.1 Recommendation Summary

**Architecture:** Coordinator Plugin with Core Extensions

**Rationale:**
1. **Modularity:** Plugin keeps MCP code separate from core
2. **Integration:** Core extensions provide necessary access to query APIs
3. **Flexibility:** Can evolve independently while leveraging Presto infrastructure
4. **Performance:** Direct access to internal APIs (no proxy overhead)
5. **Deployability:** Optional feature that doesn't impact non-MCP users

### 10.2 Streaming Resolution

**Approach:** Resource Pagination (Strategy B) with optional SSE (Strategy C)

**Rationale:**
1. **MCP-Native:** Cursor-based pagination is part of MCP spec
2. **Memory Efficient:** No full buffering required
3. **Timeout-Safe:** Incremental progress prevents long-running query timeouts
4. **Pragmatic:** Balances implementation complexity with functionality
5. **Extensible:** SSE can be added later for enhanced streaming

### 10.3 Next Steps

1. **Validate Approach:** Review design with Presto team and stakeholders
2. **Prototype Core Extensions:** Implement `EndpointFactory` and `QueryAccessProvider`
3. **Build PoC:** Create minimal MCP plugin with single query tool
4. **Evaluate Streaming:** Test pagination performance with real AI agents
5. **Iterate:** Gather feedback and refine implementation

### 10.4 Success Criteria

- [ ] AI agents can query Presto via MCP protocol
- [ ] Large result sets stream incrementally without memory issues
- [ ] Query timeout behavior is acceptable (<30s per batch)
- [ ] Plugin loads without modifying core (beyond agreed extensions)
- [ ] Performance overhead is <5% vs. native Presto protocol
- [ ] Authentication and authorization work correctly

---

## Appendices

### Appendix A: MCP Tool Catalog

Proposed tools for MCP plugin:

1. **query_presto**
   - Execute arbitrary SQL query
   - Returns query ID and resource URI
   - Supports session properties

2. **list_catalogs**
   - Returns available catalogs
   - No pagination needed (small result set)

3. **list_schemas**
   - Returns schemas in a catalog
   - Input: catalog name

4. **list_tables**
   - Returns tables in a schema
   - Input: catalog, schema
   - Supports pattern matching

5. **describe_table**
   - Returns table schema (columns, types)
   - Input: fully-qualified table name

6. **explain_query**
   - Returns query plan
   - Input: SQL query
   - Output: logical/distributed plan

7. **cancel_query**
   - Cancels a running query
   - Input: query ID

### Appendix B: Reference Files

Key Presto files analyzed:

1. **Query Execution:**
   - `presto-main/src/main/java/com/facebook/presto/server/protocol/QueuedStatementResource.java`
   - `presto-main/src/main/java/com/facebook/presto/server/protocol/Query.java`
   - `presto-main/src/main/java/com/facebook/presto/server/protocol/QueryResourceUtil.java`

2. **Result Streaming:**
   - `presto-main-base/src/main/java/com/facebook/presto/operator/ExchangeClient.java`
   - `presto-spi/src/main/java/com/facebook/presto/spi/page/PagesSerde.java`
   - `presto-spi/src/main/java/com/facebook/presto/spi/page/SerializedPage.java`

3. **Plugin System:**
   - `presto-spi/src/main/java/com/facebook/presto/spi/Plugin.java`
   - `presto-spi/src/main/java/com/facebook/presto/spi/CoordinatorPlugin.java`
   - `presto-main-base/src/main/java/com/facebook/presto/server/PluginManager.java`

4. **Coordinator Architecture:**
   - `presto-main/src/main/java/com/facebook/presto/server/CoordinatorModule.java`

### Appendix C: MCP Resources

- Specification: https://modelcontextprotocol.io/specification/latest
- GitHub: https://github.com/modelcontextprotocol
- Python SDK: https://github.com/modelcontextprotocol/python-sdk
- Pagination Spec: https://modelcontextprotocol.io/specification/2025-03-26/server/utilities/pagination

---

**Document Version:** 1.0
**Date:** 2025-10-30
**Author:** Claude (AI Assistant)
