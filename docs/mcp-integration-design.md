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

**Important Note:** MCP's cursor-based pagination applies **only to list operations** (tools/list, resources/list, prompts/list), NOT to the data returned by tools or resources themselves. This significantly constrains streaming options.

Four approaches to resolve the streaming mismatch:

#### Option A: Buffer and Wait (Simple, Limited)
- MCP tool blocks until query completes
- Buffers all results in memory
- Returns complete dataset in single JSON-RPC response

**Pros:** Simplest implementation, pure MCP compliance, no transport dependencies
**Cons:**
- Memory exhaustion on large queries (GB+ results)
- Timeout issues for long queries (>30s)
- Poor user experience (no progress feedback)
- Defeats Presto's streaming advantage

**Use Case:** Small to medium queries (<10MB results, <30s execution)

#### Option B: SSE Streaming (MCP-Native, Recommended)
- MCP tool initiates query and begins streaming via Server-Sent Events
- Server pushes data events progressively as query executes
- Client receives incremental results in real-time

**MCP Transport:**
When using HTTP with SSE transport, responses can be streamed as multiple SSE events:
```
POST /v1/mcp (tools/call with query_presto)
→ Response with Content-Type: text/event-stream

event: data
data: {"rows": [...]}

event: data
data: {"rows": [...]}

event: complete
data: {"status": "finished", "totalRows": 50000}
```

**Pros:** True streaming, best user experience, memory efficient, MCP-compliant
**Cons:**
- Requires SSE transport support (not all MCP clients)
- More complex implementation
- Must handle SSE connection management

**Use Case:** Large datasets, long-running queries, interactive AI applications

#### Option C: Client-Side Pagination via SQL (Pragmatic)
- AI agent makes multiple tool calls with LIMIT/OFFSET
- Each call returns a manageable batch
- No special server-side support needed

**Example:**
```
tools/call: SELECT * FROM table ORDER BY id LIMIT 10000 OFFSET 0
tools/call: SELECT * FROM table ORDER BY id LIMIT 10000 OFFSET 10000
tools/call: SELECT * FROM table ORDER BY id LIMIT 10000 OFFSET 20000
```

**Pros:** Simple, no special streaming infrastructure, works with any transport
**Cons:**
- Inefficient (re-executes query each time)
- Expensive for Presto (multiple query plans)
- Not transparent to AI agent
- Requires stable sort for correctness

**Use Case:** Fallback when SSE not available, AI agent with pagination awareness

#### Option D: Chunked Resources (Workaround)
- Tool creates multiple resources representing query pages
- Each resource URI represents a chunk: `presto://query/{id}/chunk/{n}`
- Client reads resources sequentially

**Example:**
```
tools/call query_presto → {
  "queryId": "...",
  "chunks": [
    "presto://query/123/chunk/0",
    "presto://query/123/chunk/1",
    ...
  ]
}

resources/read "presto://query/123/chunk/0" → {rows: [...]}
resources/read "presto://query/123/chunk/1" → {rows: [...]}
```

**Pros:** Works within MCP resource model, no SSE required
**Cons:**
- Awkward semantics (pagination disguised as resources)
- Must buffer chunks server-side
- Multiple round-trips
- Not truly MCP-idiomatic

**Use Case:** Edge cases where SSE unavailable but buffering infeasible

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

        // Option A: Buffer and wait (simple implementation)
        QueryId queryId = dispatchManager.createQuery(...);

        // For SSE streaming, would return immediately and stream via SSE
        // For buffered approach, wait for completion
        List<List<Object>> allRows = bufferQueryResults(queryId);

        return jsonRpcSuccess(request.getId(), Map.of(
            "queryId", queryId.toString(),
            "rows", allRows,
            "status", "completed"
        ));
    }

    // SSE streaming would be implemented separately
    @GET
    @Path("/stream/{queryId}")
    @Produces(MediaType.SERVER_SENT_EVENTS)
    public void streamQuery(
            @PathParam("queryId") String queryIdStr,
            @Context SseEventSink eventSink,
            @Context Sse sse) {
        // Stream results as SSE events
        // See section 4.4 for full implementation
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

#### Enhancement 1: Plugin Gateway Resource (Instead of Dynamic Endpoint Registration)

**Problem:** Presto loads plugins AFTER the Guice injector is created, so plugins cannot register JAX-RS resources dynamically during module configuration.

**Solution:** Register a single gateway resource in core that delegates to plugin-provided handlers.

**File:** `presto-spi/src/main/java/com/facebook/presto/spi/ProtocolHandlerFactory.java` (NEW)

```java
package com.facebook.presto.spi;

/**
 * Factory for creating protocol handlers that can process custom protocol requests.
 * Plugins can implement this to add support for protocols like MCP, GraphQL, etc.
 */
public interface ProtocolHandlerFactory {
    /**
     * Returns the protocol name (e.g., "mcp", "graphql")
     */
    String getProtocolName();

    /**
     * Creates a protocol handler instance.
     */
    ProtocolHandler create();
}

/**
 * Handles requests for a specific protocol.
 */
public interface ProtocolHandler {
    /**
     * Handles a protocol request.
     * @param requestBody The raw request body
     * @param headers Request headers
     * @param sessionContext User session context
     * @return Response object (will be serialized to JSON)
     */
    Object handleRequest(
        String requestBody,
        Map<String, String> headers,
        SessionContext sessionContext);
}
```

**File:** `presto-spi/src/main/java/com/facebook/presto/spi/Plugin.java` (MODIFY)

```java
public interface Plugin {
    // Existing methods...

    /**
     * Returns protocol handler factories for custom protocols.
     * Added in version X.Y for MCP and other protocol support.
     */
    default Iterable<ProtocolHandlerFactory> getProtocolHandlerFactories() {
        return ImmutableList.of();
    }
}
```

**File:** `presto-main/src/main/java/com/facebook/presto/server/protocol/PluginProtocolManager.java` (NEW)

```java
package com.facebook.presto.server.protocol;

/**
 * Manages protocol handlers from plugins.
 * Instantiated during Guice setup, updated when plugins are loaded.
 */
public class PluginProtocolManager {
    private final Map<String, ProtocolHandler> handlers = new ConcurrentHashMap<>();

    /**
     * Registers a protocol handler.
     * Called by PluginManager after plugins are loaded.
     */
    public void registerHandler(String protocolName, ProtocolHandler handler) {
        handlers.put(protocolName.toLowerCase(), handler);
    }

    /**
     * Gets a protocol handler by name.
     */
    public Optional<ProtocolHandler> getHandler(String protocolName) {
        return Optional.ofNullable(handlers.get(protocolName.toLowerCase()));
    }

    /**
     * Returns all registered protocol names.
     */
    public Set<String> getProtocolNames() {
        return ImmutableSet.copyOf(handlers.keySet());
    }
}
```

**File:** `presto-main/src/main/java/com/facebook/presto/server/protocol/PluginProtocolResource.java` (NEW)

```java
package com.facebook.presto.server.protocol;

import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.*;

/**
 * Gateway JAX-RS resource that delegates to plugin-provided protocol handlers.
 * Registered during module setup, handlers added later when plugins load.
 */
@Path("/v1/protocol/{protocolName}")
public class PluginProtocolResource {
    private final PluginProtocolManager protocolManager;

    @Inject
    public PluginProtocolResource(PluginProtocolManager protocolManager) {
        this.protocolManager = protocolManager;
    }

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response handleRequest(
            @PathParam("protocolName") String protocolName,
            @Context HttpHeaders headers,
            @Context SecurityContext securityContext,
            String requestBody) {

        // Get protocol handler
        ProtocolHandler handler = protocolManager.getHandler(protocolName)
            .orElseThrow(() -> new WebApplicationException(
                "Unknown protocol: " + protocolName,
                Response.Status.NOT_FOUND));

        // Extract session context
        SessionContext sessionContext = extractSessionContext(headers, securityContext);

        // Delegate to handler
        try {
            Object result = handler.handleRequest(
                requestBody,
                extractHeaders(headers),
                sessionContext);

            return Response.ok(result).build();
        }
        catch (Exception e) {
            return Response.serverError()
                .entity(Map.of("error", e.getMessage()))
                .build();
        }
    }

    @GET
    public Response listProtocols() {
        return Response.ok(Map.of(
            "protocols", protocolManager.getProtocolNames()
        )).build();
    }

    private SessionContext extractSessionContext(HttpHeaders headers, SecurityContext securityContext) {
        // Extract session properties from headers (catalog, schema, user, etc.)
        // Similar to QueuedStatementResource
        return new SessionContext(...);
    }

    private Map<String, String> extractHeaders(HttpHeaders headers) {
        Map<String, String> headerMap = new HashMap<>();
        headers.getRequestHeaders().forEach((name, values) -> {
            if (!values.isEmpty()) {
                headerMap.put(name, values.get(0));
            }
        });
        return headerMap;
    }
}
```

**File:** `presto-main/src/main/java/com/facebook/presto/server/CoordinatorModule.java` (MODIFY)

```java
public class CoordinatorModule extends AbstractConfigurationAwareModule {
    protected void setup(Binder binder) {
        // ... existing bindings ...

        // NEW: Plugin protocol support
        binder.bind(PluginProtocolManager.class).in(Scopes.SINGLETON);
        jaxrsBinder(binder).bind(PluginProtocolResource.class);
    }
}
```

**File:** `presto-main-base/src/main/java/com/facebook/presto/server/PluginManager.java` (MODIFY)

```java
public class PluginManager {
    private final PluginProtocolManager protocolManager;

    @Inject
    public PluginManager(
            // ... existing parameters ...
            PluginProtocolManager protocolManager) {
        // ... existing initialization ...
        this.protocolManager = protocolManager;
    }

    public void loadPlugins() {
        // ... existing plugin loading ...

        for (Plugin plugin : plugins) {
            // ... existing plugin initialization ...

            // NEW: Register protocol handlers
            for (ProtocolHandlerFactory factory : plugin.getProtocolHandlerFactories()) {
                ProtocolHandler handler = factory.create();
                protocolManager.registerHandler(factory.getProtocolName(), handler);
                log.info("Registered protocol handler: %s", factory.getProtocolName());
            }
        }
    }
}
```

**Advantages:**
- Works with existing plugin loading architecture
- Single JAX-RS resource registered at module setup time
- Handlers added dynamically when plugins load
- Clean delegation pattern
- Multiple protocols can coexist (MCP, GraphQL, etc.)

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
    ├── MCPProtocolHandler.java (implements ProtocolHandler)
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
    public Iterable<ProtocolHandlerFactory> getProtocolHandlerFactories() {
        return ImmutableList.of(new MCPProtocolHandlerFactory());
    }

    private static class MCPProtocolHandlerFactory implements ProtocolHandlerFactory {
        @Override
        public String getProtocolName() {
            return "mcp";
        }

        @Override
        public ProtocolHandler create() {
            return new MCPProtocolHandler();
        }
    }
}
```

**File:** `presto-mcp-plugin/src/main/java/com/facebook/presto/mcp/MCPProtocolHandler.java`

```java
public class MCPProtocolHandler implements ProtocolHandler {
    private MCPToolRegistry toolRegistry;
    private MCPQueryManager queryManager;
    private boolean initialized = false;

    /**
     * Initialize with injected dependencies.
     * Called by PluginManager after creation.
     */
    public void initialize(
            QueryAccessProvider queryAccessProvider,
            Metadata metadata) {
        this.queryManager = new MCPQueryManager(queryAccessProvider);
        this.toolRegistry = new MCPToolRegistry(queryManager, metadata);
        this.initialized = true;
    }

    @Override
    public Object handleRequest(
            String requestBody,
            Map<String, String> headers,
            SessionContext sessionContext) {

        if (!initialized) {
            throw new IllegalStateException("MCPProtocolHandler not initialized");
        }

        try {
            JsonRpcRequest request = JsonRpcRequest.parse(requestBody);
            return dispatch(request, sessionContext);
        }
        catch (JsonRpcException e) {
            return e.toErrorResponse();
        }
        catch (Exception e) {
            return JsonRpcResponse.error(null, -32603, "Internal error: " + e.getMessage());
        }
    }

    private Object dispatch(JsonRpcRequest request, SessionContext sessionContext) {
        switch (request.getMethod()) {
            case "initialize":
                return handleInitialize(request);

            case "tools/list":
                return handleToolsList(request);

            case "tools/call":
                return handleToolCall(request, sessionContext);

            case "resources/list":
                return handleResourcesList(request);

            case "resources/read":
                return handleResourceRead(request, sessionContext);

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

    public JsonRpcResponse executeQueryBuffered(
            String sql,
            Map<String, String> sessionProperties,
            String requestId) {

        // Submit query
        SessionContext session = createSessionContext(sessionProperties);
        QueryId queryId = queryAccessProvider.submitQuery(session, sql, ImmutableMap.of());

        // Store query context
        activeQueries.put(queryId.toString(), new QueryContext(queryId, session));

        // Option A: Buffer all results (simple but limited)
        List<List<Object>> allRows = new ArrayList<>();
        long token = 0;

        try {
            while (true) {
                QueryResultBatch batch = queryAccessProvider.getResults(
                    queryId, token, DataSize.valueOf("16MB"), false
                ).get(30, TimeUnit.SECONDS);

                allRows.addAll(batch.getRows());

                if (!batch.hasMore()) {
                    break;
                }
                token = batch.getNextToken();
            }

            return JsonRpcResponse.success(requestId, ImmutableMap.of(
                "queryId", queryId.toString(),
                "rows", allRows,
                "status", "completed"
            ));

        } catch (Exception e) {
            throw JsonRpcException.internalError("Query execution failed: " + e.getMessage());
        }
    }

    public void executeQueryStreaming(
            String sql,
            Map<String, String> sessionProperties,
            SseEventSink eventSink,
            Sse sse) {

        // Submit query
        SessionContext session = createSessionContext(sessionProperties);
        QueryId queryId = queryAccessProvider.submitQuery(session, sql, ImmutableMap.of());

        // Option B: SSE Streaming (recommended for large results)
        long token = 0;

        try {
            while (true) {
                QueryResultBatch batch = queryAccessProvider.getResults(
                    queryId, token, DataSize.valueOf("1MB"), false
                ).get(30, TimeUnit.SECONDS);

                // Stream data event
                OutboundSseEvent dataEvent = sse.newEventBuilder()
                    .name("data")
                    .data(Map.of("rows", batch.getRows()))
                    .build();
                eventSink.send(dataEvent);

                if (!batch.hasMore()) {
                    // Send completion event
                    OutboundSseEvent completeEvent = sse.newEventBuilder()
                        .name("complete")
                        .data(Map.of("status", "finished", "queryId", queryId.toString()))
                        .build();
                    eventSink.send(completeEvent);
                    break;
                }

                token = batch.getNextToken();
            }
        } catch (Exception e) {
            // Send error event
            OutboundSseEvent errorEvent = sse.newEventBuilder()
                .name("error")
                .data(Map.of("error", e.getMessage()))
                .build();
            eventSink.send(errorEvent);
        } finally {
            eventSink.close();
        }
    }
}
```

### 4.4 Streaming Strategy: Adaptive Hybrid (Recommended)

Implement a **single tool that automatically adapts** based on actual result size:

#### Adaptive Buffering with SSE Fallback

**Strategy:**
1. Start buffering results as query executes
2. If buffered data exceeds threshold (e.g., 10MB), switch to SSE streaming
3. Send buffered data as first SSE event, continue streaming rest
4. If query completes under threshold, return normal JSON-RPC response

**Small Query Flow (Buffered Response):**
```
AI calls tool: tools/call with name="query_presto", arguments={"sql": "SELECT * FROM small_table"}

Server: Buffers results... query completes at 5MB total

Response (normal JSON-RPC): {
  "jsonrpc": "2.0",
  "id": 123,
  "result": {
    "queryId": "20231201_123456_00000_abc12",
    "rows": [
      {"col1": "value1", "col2": 123},
      {"col1": "value2", "col2": 456},
      ...
    ],
    "status": "completed",
    "totalRows": 50000
  }
}
```

**Large Query Flow (Automatic SSE Switch):**
```
AI calls tool: tools/call with name="query_presto", arguments={"sql": "SELECT * FROM large_table"}

Server: Buffers results... hits 10MB threshold, switches to SSE

Response: HTTP 200 with Content-Type: text/event-stream

event: data
data: {"rows": [...buffered data so far...], "queryId": "...", "switchedToStreaming": true}

event: data
data: {"rows": [...next batch...]}

event: data
data: {"rows": [...next batch...]}

event: complete
data: {"status": "finished", "totalRows": 500000}
```

**Advantages:**
- **Single tool interface** - AI agent doesn't need to guess query size
- **Automatic optimization** - small queries get simple response, large queries stream
- **No waste** - buffering is useful (sent as first event if we switch to SSE)
- **Graceful degradation** - clients without SSE support see an error explaining the issue
- **Transparent** - AI agent gets data either way

**Implementation Details:**

```java
@POST
@Path("/rpc")
public Response handleToolCall(JsonRpcRequest request, @Suspended AsyncResponse asyncResponse) {
    String toolName = request.getParams().getString("name");

    if ("query_presto".equals(toolName)) {
        String sql = request.getParams().getString("arguments.sql");

        // Submit query
        QueryId queryId = submitQuery(sql, ...);

        // Buffer results with size tracking
        List<Map<String, Object>> bufferedRows = new ArrayList<>();
        long bufferedBytes = 0;
        long token = 0;
        boolean switched = false;

        while (true) {
            QueryResultBatch batch = pollResults(queryId, token);

            // Check if we should switch to SSE
            if (!switched && bufferedBytes + batch.getSizeBytes() > STREAMING_THRESHOLD) {
                // Switch to SSE streaming
                return switchToSSE(asyncResponse, queryId, bufferedRows, batch);
            }

            // Continue buffering
            bufferedRows.addAll(convertRows(batch));
            bufferedBytes += batch.getSizeBytes();

            if (!batch.hasMore()) {
                // Query completed, return buffered response
                return jsonRpcSuccess(request.getId(), Map.of(
                    "queryId", queryId.toString(),
                    "rows", bufferedRows,
                    "status", "completed"
                ));
            }

            token = batch.getNextToken();
        }
    }
}

private Response switchToSSE(
        AsyncResponse asyncResponse,
        QueryId queryId,
        List<Map<String, Object>> bufferedRows,
        QueryResultBatch nextBatch) {

    // Check if client supports SSE
    if (!clientSupportsSSE(asyncResponse)) {
        return jsonRpcError("Query results too large for buffering. " +
            "Client must support Server-Sent Events for large queries.");
    }

    // Switch to SSE streaming
    SseEventSink eventSink = getSseEventSink(asyncResponse);
    Sse sse = getSse(asyncResponse);

    // Send buffered data as first event
    OutboundSseEvent firstEvent = sse.newEventBuilder()
        .name("data")
        .data(Map.of(
            "rows", bufferedRows,
            "queryId", queryId.toString(),
            "switchedToStreaming", true
        ))
        .build();
    eventSink.send(firstEvent);

    // Send current batch
    OutboundSseEvent secondEvent = sse.newEventBuilder()
        .name("data")
        .data(Map.of("rows", convertRows(nextBatch)))
        .build();
    eventSink.send(secondEvent);

    // Continue streaming rest of query
    streamRemainingResults(eventSink, sse, queryId, nextBatch.getNextToken());

    return Response.ok().build();
}
```

**Configuration:**

```properties
# Size threshold for switching to SSE (default: 10MB)
mcp.streaming-threshold=10MB

# Whether to allow SSE fallback (default: true)
mcp.sse-enabled=true

# Initial buffer size (default: 1MB)
mcp.initial-buffer-size=1MB
```

**Error Handling:**

If client doesn't support SSE and query exceeds threshold:
```json
{
  "jsonrpc": "2.0",
  "id": 123,
  "error": {
    "code": -32000,
    "message": "Query result size (estimated 150MB) exceeds buffering limit (10MB). Client must support Server-Sent Events (text/event-stream) for large result sets.",
    "data": {
      "queryId": "20231201_123456_00000_abc12",
      "estimatedSize": "150MB",
      "bufferingLimit": "10MB",
      "requiresSSE": true
    }
  }
}
```

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

| Strategy | Memory | Latency | Timeout Risk | MCP Compliance | Implementation | UX |
|----------|--------|---------|--------------|----------------|----------------|-----|
| **A: Buffer All** | ❌ High | ⚠️ High | ❌ High | ✅ Perfect | ✅ Simple | ⚠️ Fails on large queries |
| **B: SSE Always** | ✅ Low | ✅ Low | ✅ Low | ✅ Good | ⚠️ Moderate | ⚠️ Overkill for small queries |
| **C: Adaptive (Buffer→SSE)** | ✅ Low | ✅ Low | ✅ Low | ✅ Good | ⚠️ Moderate | ✅ Best of both |
| **D: Two Tools** | ✅ Low | ✅ Low | ✅ Low | ✅ Good | ⚠️ Moderate | ❌ Agent must choose |
| **E: Client SQL Pagination** | ✅ Low | ❌ High | ✅ Low | ✅ Perfect | ✅ Simple | ❌ Inefficient |
| **F: Chunked Resources** | ⚠️ Medium | ⚠️ Medium | ✅ Low | ⚠️ Workaround | ❌ Complex | ❌ Awkward |

**Key Insight:** MCP's cursor pagination is ONLY for list operations (tools/list, resources/list), NOT for data returned by tools/resources.

### 5.2 Recommended Approach: Adaptive Buffering

Implement **a single tool that automatically switches** based on actual result size:

**Adaptive Mode (query_presto)** - One tool for all queries
```
tools/call query_presto {"sql": "..."}

Small query → {"result": {"rows": [...]}} (JSON-RPC response)
Large query → Content-Type: text/event-stream (automatic SSE switch)
```

**Advantages:**
- **Best UX** - AI agent doesn't need to predict query size
- **Automatic optimization** - small queries stay simple, large queries stream
- **Efficient** - buffering work is reused (sent as first SSE event)
- **Graceful** - clear error if client can't handle SSE for large results
- **Single interface** - simpler API surface

**How it works:**
1. Start buffering results (threshold: 10MB configurable)
2. Small queries (<10MB): Return complete JSON-RPC response
3. Large queries (≥10MB): Switch to SSE mid-flight, send buffered data first
4. No waste: Buffering effort becomes first SSE event

### 5.3 SSE Implementation Pattern

SSE streaming aligns perfectly with MCP's transport layer and Presto's polling model:

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
- [ ] Implement buffered query execution (query_presto tool)
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
- `mcp.queries.submitted` (counter by mode: buffered/streaming)
- `mcp.queries.completed` (counter by mode)
- `mcp.queries.failed` (counter by mode)
- `mcp.query.duration` (histogram)
- `mcp.result.bytes` (histogram)
- `mcp.sse.batches.sent` (counter for streaming mode)

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
- Query submission (with SQL, user, session properties, mode)
- SSE streaming events (query ID, batch number, rows sent)
- Query completion (with stats)
- Errors (with stack traces)

**Log Format:**
```
[MCP] Query submitted: queryId=20231201_123456_00000_abc12, user=ai-agent, mode=buffered, sql="SELECT ..."
[MCP] SSE batch sent: queryId=..., batchNum=5, rows=1000, bytes=512KB
[MCP] Query completed: queryId=..., duration=5.2s, totalRows=50000, mode=streaming
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

**Approach:** Adaptive Buffering with Automatic SSE Streaming

**Rationale:**
1. **MCP-Compliant:** Uses SSE (part of MCP spec) when needed; cursor pagination only for list operations
2. **Best UX:** Single tool interface - AI agent doesn't predict query size
3. **Memory Efficient:** Buffers small queries, streams large queries automatically
4. **Timeout-Safe:** SSE provides incremental progress for long queries
5. **Pragmatic:** Start buffering, switch to SSE if threshold exceeded
6. **Efficient:** No wasted work - buffered data becomes first SSE event

**How It Works:**
- All queries use the same `query_presto` tool
- Server buffers results up to configurable threshold (default 10MB)
- If query completes under threshold: Return normal JSON-RPC response
- If query exceeds threshold: Switch to SSE, send buffered data as first event
- Transparent to AI agent which mode is used

**Important Correction:** Initial design incorrectly assumed MCP resource pagination could be used for query results. MCP's cursor-based pagination is **only for list operations** (tools/list, resources/list), not for the data returned by tools themselves. The correct approach is adaptive buffering with automatic SSE streaming for large results.

### 10.3 Next Steps

1. **Validate Approach:** Review design with Presto team and stakeholders
2. **Prototype Core Extensions:** Implement `EndpointFactory` and `QueryAccessProvider`
3. **Build PoC:** Create minimal MCP plugin with buffered query tool
4. **Evaluate Streaming:** Test buffered mode, then add SSE streaming for large queries
5. **Iterate:** Gather feedback and refine implementation

### 10.4 Success Criteria

- [ ] AI agents can query Presto via MCP protocol using single `query_presto` tool
- [ ] Small queries (<10MB) return complete results without streaming overhead
- [ ] Large queries automatically switch to SSE streaming without memory exhaustion
- [ ] Threshold-based switching is transparent to AI agent
- [ ] Query timeout behavior is acceptable (buffered <30s, streaming incremental)
- [ ] Plugin loads without modifying core (beyond agreed extensions)
- [ ] Performance overhead is <5% vs. native Presto protocol
- [ ] Authentication and authorization work correctly
- [ ] Graceful error handling when client doesn't support SSE for large queries

---

## Appendices

### Appendix A: MCP Tool Catalog

Proposed tools for MCP plugin:

1. **query_presto**
   - Execute SQL query with adaptive buffering/streaming
   - Automatically buffers small results (<10MB) or streams large results via SSE
   - Single tool interface - AI agent doesn't need to choose
   - Supports session properties (catalog, schema, user, etc.)
   - Arguments:
     - `sql` (required): SQL query to execute
     - `catalog` (optional): Catalog to use
     - `schema` (optional): Schema to use
     - `sessionProperties` (optional): Map of session properties

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
   - Returns query plan (logical and distributed)
   - Input: SQL query
   - Does not execute query

7. **cancel_query**
   - Cancels a running query
   - Input: query ID
   - Returns cancellation status

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
