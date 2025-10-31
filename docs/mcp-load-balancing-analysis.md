# MCP Integration: Load Balancing Analysis

## Critical Architectural Concern

When integrating MCP into Presto, a fundamental question arises: **Can we leverage Presto's existing load balancing infrastructure, or do we bypass it by introducing a new protocol?**

This document analyzes the load balancing implications and revises the architectural recommendation based on this concern.

---

## Presto's Load Balancing Architecture

### How Presto's Client Protocol Enables Load Balancing

Presto's REST API (`/v1/statement`) is designed for distributed coordination:

```
┌──────────────────────────────────────────────────────────────┐
│                    Client Request Flow                        │
└──────────────────────────────────────────────────────────────┘

1. POST /v1/statement (via load balancer)
   → Any coordinator can handle initial request

2. Response: {
     "id": "20231201_123456_00000_abc12",
     "nextUri": "http://coordinator-a:8080/v1/statement/executing/20231201_123456_00000_abc12/0"
   }
   → nextUri explicitly routes to specific coordinator

3. Client follows nextUri chain:
   GET http://coordinator-a:8080/.../0
   GET http://coordinator-a:8080/.../1
   GET http://coordinator-a:8080/.../2
   → All subsequent requests routed to same coordinator
   → Query state lives on that coordinator

4. Cross-cluster retry:
   - Router can add retryUrl parameter
   - Failover to backup cluster if query fails
   - Sophisticated retry mechanism built-in
```

### Key Characteristics

| Feature | Implementation | Benefit |
|---------|----------------|---------|
| **Coordinator Affinity** | `nextUri` encodes coordinator address | Query state localized |
| **Stateless Initial Request** | First POST can hit any coordinator | Load distribution |
| **Session State** | Passed via headers (`X-Presto-Catalog`, etc.) | Mostly stateless |
| **Explicit Routing** | Each response contains next URI | No sticky sessions needed |
| **Failover** | `retryUrl` mechanism | High availability |
| **Flexible Polling** | Client controls poll frequency | Backpressure handling |

**Source:** `presto-docs/src/main/sphinx/develop/client-protocol.rst`

---

## The Problem with MCP in Coordinator

### Scenario: High-Volume Agentic Workload

Consider an AI agent making dozens of queries:

```
AI Agent Session:
  Query 1: SELECT * FROM small_table     → 1MB result
  Query 2: SELECT * FROM large_table     → 150MB result
  Query 3: SELECT * FROM medium_table    → 8MB result
  Query 4: SELECT * FROM huge_table      → 500MB result
  ... (dozens more queries)
```

### With Coordinator Plugin + Load Balancer

**Attempt 1: No Sticky Sessions**
```
Query 1 → LB → Coordinator A (buffered, 1MB, completes)
Query 2 → LB → Coordinator B (starts buffering, hits 10MB, switches to SSE)
         ↓
         SSE connection held open to Coordinator B
         ↓
         AI agent continues receiving stream from Coordinator B...

Query 3 → LB → Coordinator C (load balanced to different coordinator)
         ↓
         But Query 2 still streaming from Coordinator B!
```

**Issues:**
- ❌ Query 2's SSE stream pins connection to Coordinator B
- ❌ Subsequent queries load balanced elsewhere (can't use `nextUri` routing)
- ❌ If Coordinator B fails mid-stream, Query 2 is lost (no recovery mechanism)
- ❌ Can't leverage Presto's cross-cluster retry
- ⚠️ Works, but doesn't use Presto's infrastructure

**Attempt 2: Sticky Sessions (by AI agent ID/IP)**
```
All queries from AI Agent 1 → Coordinator A
All queries from AI Agent 2 → Coordinator B
All queries from AI Agent 3 → Coordinator C
```

**Issues:**
- ❌ All queries from one agent pinned to single coordinator
- ❌ Uneven load distribution (some agents query more than others)
- ❌ Coordinator becomes bottleneck for heavy agent
- ❌ Coordinator failure loses all active queries for that agent
- ❌ Can't distribute single agent's workload across cluster

### Comparison with Native Presto Protocol

| Aspect | Presto Protocol | MCP in Coordinator |
|--------|-----------------|-------------------|
| **Initial Request** | Can hit any coordinator | Can hit any coordinator ✅ |
| **Subsequent Requests** | Follow `nextUri` to specific coordinator | Load balanced or sticky session |
| **Query State** | Localized via `nextUri` | Pinned to coordinator (SSE) or session |
| **Load Distribution** | Optimal (explicit routing) | Suboptimal (sticky or random) |
| **Failover** | `retryUrl` mechanism | No equivalent ❌ |
| **Recovery** | Can reconnect via `nextUri` | SSE lost if connection drops ❌ |
| **Backpressure** | Client controls polling | SSE server-push (less control) |

---

## Architecture Options Revisited

### Option A: Separate Gateway (RECOMMENDED ✅)

**Architecture:**
```
┌───────────────┐
│  AI Agents    │
│  (Claude, etc)│
└───────┬───────┘
        │ MCP Protocol (JSON-RPC)
        ↓
┌───────────────────────────┐
│  MCP Gateway Cluster      │ ← Stateless, horizontally scalable
│  (Load Balanced)          │    Can deploy 3-5 gateways
└───────┬───────────────────┘
        │ Presto Client Protocol (nextUri)
        ↓
┌───────────────────────────┐
│  Presto Coordinator       │ ← Uses existing routing
│  Cluster (Load Balanced)  │    Existing infrastructure
└───────────────────────────┘
```

**Implementation:**
```java
public class MCPGateway {
    private final PrestoClient prestoClient;

    public Object handleToolCall(String toolName, Map<String, Object> args, SessionContext ctx) {
        if ("query_presto".equals(toolName)) {
            String sql = (String) args.get("sql");

            // Submit query using Presto client protocol
            QueryResults initial = prestoClient.submitQuery(sql, ctx);

            // Start buffering results
            List<List<Object>> buffer = new ArrayList<>();
            long bytesBuffered = 0;
            String nextUri = initial.getNextUri();

            while (nextUri != null && bytesBuffered < STREAMING_THRESHOLD) {
                // Follow nextUri chain (Presto handles coordinator routing)
                QueryResults batch = prestoClient.pollUri(nextUri);
                buffer.addAll(batch.getData());
                bytesBuffered += estimateSize(batch);
                nextUri = batch.getNextUri();
            }

            if (nextUri == null) {
                // Query completed within threshold - return buffered results
                return JsonRpcResponse.success(Map.of(
                    "queryId", initial.getId(),
                    "rows", buffer,
                    "status", "completed"
                ));
            } else {
                // Exceeded threshold - switch to SSE streaming
                return streamResults(buffer, nextUri, prestoClient);
            }
        }
    }

    private Object streamResults(List<List<Object>> buffered, String nextUri, PrestoClient client) {
        // Return SSE response
        // Gateway streams by continuously polling nextUri
        // Gateway maintains connection to Presto coordinator via nextUri chain
        // If gateway fails, new gateway can reconnect using nextUri
        return SseStreamingResponse.create(buffered, nextUri, client);
    }
}
```

**Benefits:**
- ✅ **Gateways are stateless**: Can be load balanced, no session affinity needed
- ✅ **Uses Presto's routing**: Follows `nextUri` chains, leverages existing infrastructure
- ✅ **Failover capable**: Gateway failure doesn't lose query state (nextUri persists)
- ✅ **Optimal load distribution**: Presto coordinators handle routing
- ✅ **Can leverage retry**: Could implement cross-cluster retry at gateway level
- ✅ **Coordinators unmodified**: No changes to core Presto
- ✅ **Independent scaling**: Scale gateways separately from coordinators

**Tradeoffs:**
- ❌ **Additional network hop**: Gateway → Coordinator (typically <1ms on same network)
- ❌ **Operational overhead**: Another service to deploy, monitor, upgrade
- ⚠️ **Slight latency**: Extra hop adds latency (mitigated by co-location)

**Deployment:**
```yaml
# Kubernetes example
apiVersion: apps/v1
kind: Deployment
metadata:
  name: presto-mcp-gateway
spec:
  replicas: 3  # Scale based on load
  selector:
    matchLabels:
      app: presto-mcp-gateway
  template:
    spec:
      containers:
      - name: gateway
        image: presto-mcp-gateway:latest
        env:
        - name: PRESTO_COORDINATOR_URL
          value: "http://presto-coordinator:8080"
        - name: STREAMING_THRESHOLD
          value: "10MB"
---
apiVersion: v1
kind: Service
metadata:
  name: presto-mcp-gateway
spec:
  type: LoadBalancer  # Gateway can be load balanced
  ports:
  - port: 8081
    targetPort: 8081
  selector:
    app: presto-mcp-gateway
```

### Option B: Coordinator Plugin with Sticky Sessions

**Architecture:**
```
┌───────────────┐
│  AI Agents    │
└───────┬───────┘
        │ MCP Protocol
        ↓
┌────────────────────────────┐
│  Load Balancer             │
│  (Sticky by source IP/ID)  │
└───────┬────────────────────┘
        ↓
┌───────────────────────────┐
│  Presto Coordinator       │
│  + MCP Plugin             │
└───────────────────────────┘
```

**Benefits:**
- ✅ **No extra service**: MCP built into coordinator
- ✅ **Simple deployment**: Just install plugin

**Tradeoffs:**
- ❌ **Sticky sessions required**: All queries from agent → same coordinator
- ❌ **Poor load distribution**: Uneven query patterns create hotspots
- ❌ **Single point of failure**: Coordinator failure loses all agent's queries
- ❌ **Doesn't use Presto routing**: Bypasses `nextUri` infrastructure
- ❌ **No failover**: Can't leverage cross-cluster retry
- ❌ **Scaling challenges**: Can't distribute single agent's load

**When acceptable:**
- Low query volume (not high-throughput agentic workload)
- Queries are small (mostly buffered, little SSE streaming)
- Single coordinator sufficient for workload
- Sticky sessions already in use for other reasons

### Option C: Hybrid (Gateway + Plugin)

Deploy gateways that use plugin-provided internal APIs instead of REST.

**Benefits:**
- ⚠️ Slightly more efficient than pure REST
- ⚠️ Middle ground approach

**Tradeoffs:**
- ❌ **Complexity**: Two components to maintain
- ❌ **Still requires gateway**: Doesn't avoid operational overhead
- ⚠️ **Marginal gains**: REST overhead is typically negligible

**Verdict:** Not worth the added complexity. Choose either A or B.

---

## Recommendation: Separate Gateway

For **high-volume agentic workloads**, the separate gateway approach is strongly recommended:

### Decision Matrix

| Requirement | Gateway | Plugin + Sticky | Plugin Only |
|-------------|---------|-----------------|-------------|
| Load balancing gateways | ✅ Yes | ❌ No | ❌ No |
| Use Presto routing | ✅ Yes | ❌ No | ❌ No |
| Failover support | ✅ Yes | ❌ Limited | ❌ No |
| High query volume | ✅ Excellent | ⚠️ OK | ❌ Poor |
| Operational simplicity | ⚠️ Medium | ✅ High | ✅ High |
| Presto unmodified | ✅ Yes | ❌ No | ❌ No |

### When to Choose Gateway

- ✅ High-volume agentic workloads (>1000 queries/hour)
- ✅ Multiple AI agents querying concurrently
- ✅ Large result sets requiring streaming
- ✅ Need failover/high availability
- ✅ Want to leverage Presto's routing infrastructure
- ✅ Can tolerate extra network hop

### When Plugin Acceptable

- ✅ Low-medium query volume (<100 queries/hour)
- ✅ Mostly small queries (<10MB results)
- ✅ Single or few AI agents
- ✅ Sticky sessions acceptable
- ✅ Simplified deployment more important than optimal load balancing

---

## Gateway Implementation Plan

### Phase 1: Basic Gateway (4-6 weeks)

1. **Core Gateway Service**
   - HTTP server with MCP protocol handler
   - Presto client integration (follows nextUri)
   - JSON-RPC request/response handling
   - Basic query tool (buffered mode only)

2. **Infrastructure**
   - Docker container
   - Health check endpoints
   - Basic metrics (query count, latency)
   - Configuration (Presto URL, thresholds)

3. **Testing**
   - Unit tests for protocol translation
   - Integration tests with Presto test cluster
   - Load tests with multiple concurrent queries

### Phase 2: Adaptive Streaming (3-4 weeks)

1. **Streaming Logic**
   - Implement 10MB threshold detection
   - SSE response generation
   - Buffer management
   - Connection lifecycle

2. **Error Handling**
   - Presto errors → JSON-RPC errors
   - Connection failures
   - Timeout handling
   - Graceful degradation

### Phase 3: Production Features (4-5 weeks)

1. **Observability**
   - Prometheus metrics
   - Distributed tracing
   - Structured logging
   - Query performance tracking

2. **Advanced Features**
   - Schema exploration tools
   - Query cancellation
   - Authentication integration
   - Rate limiting

3. **Operations**
   - Kubernetes manifests
   - Helm chart
   - Monitoring dashboards
   - Runbooks

**Total Estimated Effort:** 11-15 weeks (3-4 months)

---

## Conclusion

The load balancing concern fundamentally changes the architectural recommendation:

**Original Recommendation:** Coordinator Plugin
**Revised Recommendation:** **Separate Gateway**

The separate gateway approach allows MCP integration to leverage Presto's sophisticated routing infrastructure (`nextUri` chains) while maintaining stateless, horizontally scalable gateways. For high-volume agentic workloads, this is the superior architecture.

The plugin approach remains viable for low-volume deployments where operational simplicity outweighs optimal load distribution.

---

**Document Version:** 1.1
**Date:** 2025-10-31
**Updated:** Load balancing analysis and revised recommendation
