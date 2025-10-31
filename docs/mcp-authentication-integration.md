# MCP Server Authentication: Integration with Presto

## Critical Security Concern

Authentication is one of the most critical aspects of MCP integration. Research from 2025 reveals that **most MCP servers deployed lack any authentication**, creating serious security vulnerabilities.

---

## How MCP Authentication Works

### MCP OAuth 2.1 Protocol

The Model Context Protocol uses a **subset of OAuth 2.1** for authorization:

```
┌─────────────┐                    ┌─────────────────┐                    ┌──────────────────┐
│  AI Client  │                    │   MCP Server    │                    │ Authorization    │
│  (Claude)   │                    │  (Presto)       │                    │ Server (IdP)     │
└──────┬──────┘                    └────────┬────────┘                    └────────┬─────────┘
       │                                    │                                      │
       │ 1. POST /v1/protocol/mcp          │                                      │
       │    (no token or expired token)    │                                      │
       ├──────────────────────────────────>│                                      │
       │                                    │                                      │
       │ 2. HTTP 401 Unauthorized           │                                      │
       │    WWW-Authenticate: Bearer        │                                      │
       │    resource_metadata=https://...   │                                      │
       │<──────────────────────────────────┤                                      │
       │                                    │                                      │
       │ 3. GET resource_metadata URL       │                                      │
       │    (Protected Resource Metadata)   │                                      │
       ├──────────────────────────────────>│                                      │
       │                                    │                                      │
       │ 4. PRM document with:              │                                      │
       │    - authorization_endpoint         │                                      │
       │    - token_endpoint                │                                      │
       │    - scopes                        │                                      │
       │<──────────────────────────────────┤                                      │
       │                                    │                                      │
       │ 5. OAuth flow with IdP                                                    │
       ├───────────────────────────────────────────────────────────────────────> │
       │                                                                           │
       │ 6. Authorization code + token exchange                                    │
       │<──────────────────────────────────────────────────────────────────────── │
       │                                                                           │
       │ 7. POST /v1/protocol/mcp                                                  │
       │    Authorization: Bearer <access_token>                                   │
       ├──────────────────────────────────>│                                      │
       │                                    │                                      │
       │ 8. Validate token & process        │                                      │
       │<──────────────────────────────────┤                                      │
```

### MCP Authentication Headers

**Client Request:**
```http
POST /v1/protocol/mcp HTTP/1.1
Host: presto-gateway.example.com
Authorization: Bearer eyJhbGciOiJSUzI1NiIsInR5cCI6IkpXVCJ9...
Content-Type: application/json

{"jsonrpc":"2.0", "method":"tools/call", ...}
```

**Server Challenge (401):**
```http
HTTP/1.1 401 Unauthorized
WWW-Authenticate: Bearer realm="presto-mcp",
                  resource_metadata="https://presto-gateway.example.com/.well-known/prm",
                  scope="query:execute schema:read"
```

**Protected Resource Metadata (PRM) Document:**
```json
{
  "resource": "https://presto-gateway.example.com/v1/protocol/mcp",
  "authorization_servers": [{
    "authorization_endpoint": "https://idp.example.com/oauth/authorize",
    "token_endpoint": "https://idp.example.com/oauth/token",
    "scopes_supported": [
      "query:execute",
      "query:cancel",
      "schema:read",
      "schema:write"
    ]
  }]
}
```

---

## How Presto Authentication Works

### Presto Authentication Types

Presto supports multiple authentication mechanisms:

| Type | Description | Use Case | Token Format |
|------|-------------|----------|--------------|
| **JWT** | JSON Web Tokens | API access, service-to-service | `Authorization: Bearer <JWT>` |
| **OAuth2** | OAuth 2.0/2.1 | Web UI, delegated access | `Authorization: Bearer <access_token>` |
| **Kerberos** | SPNEGO/Kerberos | Enterprise SSO | `Authorization: Negotiate <ticket>` |
| **Certificate** | Client certificates (mTLS) | Machine-to-machine | TLS client cert |
| **Password** | Basic authentication | Development, simple deployments | `Authorization: Basic <base64>` |
| **Custom** | Plugin-provided | Custom requirements | Plugin-defined |

### Presto Authentication Flow

```java
// presto-main/src/main/java/com/facebook/presto/server/security/AuthenticationFilter.java

HTTP Request
    ↓
AuthenticationFilter (servlet filter)
    ↓
Try each configured Authenticator:
    1. CertificateAuthenticator
    2. KerberosAuthenticator
    3. PasswordAuthenticator
    4. JsonWebTokenAuthenticator ← Most common for API
    5. OAuth2Authenticator
    6. CustomPrestoAuthenticator
    ↓
If authenticated:
    - Set Principal on request
    - Set AuthorizedIdentity attribute
    - Continue to endpoint
Else:
    - Return 401 Unauthorized
    - Include WWW-Authenticate challenge
```

### Presto Header Extraction

**Key headers Presto uses:**
```http
X-Presto-User: username
X-Presto-Catalog: hive
X-Presto-Schema: default
X-Presto-Session: key1=value1,key2=value2
X-Presto-Transaction-Id: txn_123
Authorization: Bearer <token>
```

**Presto's JWT authenticator:**
- Extracts token from `Authorization: Bearer <jwt>` header
- Validates JWT signature against configured key
- Extracts user identity from JWT claims
- Sets Principal on request for downstream authorization

---

## Integration Architecture

### Option 1: Gateway Handles MCP Auth, Passes to Presto

**Architecture:**
```
┌─────────────┐
│  AI Client  │
└──────┬──────┘
       │ Authorization: Bearer <mcp-token>
       ↓
┌──────────────────────────┐
│  MCP Gateway             │
│  ┌────────────────────┐  │
│  │ MCP Auth Handler   │  │ ← Validates MCP OAuth token
│  │ - Verify token     │  │
│  │ - Extract user     │  │
│  │ - Map to Presto    │  │
│  └─────────┬──────────┘  │
│            ↓              │
│  ┌────────────────────┐  │
│  │ Presto Client      │  │
│  │ - Add Presto auth  │  │ ← Converts to Presto auth
│  │ - Call /v1/stmt    │  │
│  └─────────┬──────────┘  │
└────────────┼─────────────┘
             │ Authorization: Bearer <presto-jwt>
             │ X-Presto-User: extracted-user
             ↓
┌────────────────────────┐
│  Presto Coordinator    │
│  + JWT Authenticator   │ ← Validates Presto JWT
└────────────────────────┘
```

**Implementation:**

```java
public class MCPGateway {
    private final MCPTokenValidator mcpTokenValidator;
    private final PrestoJwtGenerator prestoJwtGenerator;
    private final PrestoClient prestoClient;

    public Response handleMCPRequest(HttpServletRequest request) {
        // 1. Extract MCP OAuth token from Authorization header
        String mcpToken = extractBearerToken(request);

        if (mcpToken == null) {
            // Return MCP OAuth challenge
            return Response.status(401)
                .header("WWW-Authenticate", buildMCPChallenge())
                .build();
        }

        try {
            // 2. Validate MCP token
            MCPTokenClaims mcpClaims = mcpTokenValidator.validate(mcpToken);
            String user = mcpClaims.getSubject();
            List<String> scopes = mcpClaims.getScopes();

            // 3. Check scopes for required permissions
            if (!scopes.contains("query:execute")) {
                return Response.status(403)
                    .entity("Insufficient scopes")
                    .build();
            }

            // 4. Generate Presto JWT for this user
            String prestoJwt = prestoJwtGenerator.generateToken(user);

            // 5. Make Presto request with Presto auth
            PrestoRequest prestoReq = PrestoRequest.builder()
                .sql(extractSql(request))
                .user(user)
                .catalog(mcpClaims.getCatalog())
                .schema(mcpClaims.getSchema())
                .accessToken(prestoJwt)  // Presto JWT
                .build();

            QueryResults results = prestoClient.execute(prestoReq);

            // 6. Return results in MCP format
            return Response.ok(convertToMCP(results)).build();

        } catch (TokenValidationException e) {
            return Response.status(401)
                .header("WWW-Authenticate", buildMCPChallenge())
                .entity("Invalid token: " + e.getMessage())
                .build();
        }
    }

    private String buildMCPChallenge() {
        return String.format(
            "Bearer realm=\"presto-mcp\", " +
            "resource_metadata=\"%s/.well-known/prm\", " +
            "scope=\"query:execute schema:read\"",
            gatewayBaseUrl
        );
    }
}
```

**Token Validation:**

```java
public class MCPTokenValidator {
    private final JwtParser jwtParser;
    private final String expectedAudience;

    public MCPTokenClaims validate(String token) throws TokenValidationException {
        try {
            Jws<Claims> jws = jwtParser.parseClaimsJws(token);
            Claims claims = jws.getBody();

            // Validate audience (should be this gateway)
            if (!expectedAudience.equals(claims.getAudience())) {
                throw new TokenValidationException("Invalid audience");
            }

            // Extract claims
            String subject = claims.getSubject();
            List<String> scopes = claims.get("scope", List.class);
            String catalog = claims.get("catalog", String.class);
            String schema = claims.get("schema", String.class);

            return new MCPTokenClaims(subject, scopes, catalog, schema);

        } catch (JwtException e) {
            throw new TokenValidationException("Token validation failed", e);
        }
    }
}
```

**Presto JWT Generation:**

```java
public class PrestoJwtGenerator {
    private final Key signingKey;
    private final String issuer;

    public String generateToken(String user) {
        return Jwts.builder()
            .setSubject(user)
            .setIssuer(issuer)
            .setIssuedAt(new Date())
            .setExpiration(new Date(System.currentTimeMillis() + 3600000)) // 1 hour
            .signWith(signingKey, SignatureAlgorithm.RS256)
            .compact();
    }
}
```

### Option 2: Pass-Through Authentication

**Architecture:**
```
┌─────────────┐
│  AI Client  │
└──────┬──────┘
       │ Authorization: Bearer <jwt>
       ↓
┌──────────────────────────┐
│  MCP Gateway             │
│  - Pass token through    │ ← Just forwards token
│  - No validation         │
└───────┬──────────────────┘
        │ Authorization: Bearer <jwt>
        │ X-Presto-User: user (from token)
        ↓
┌────────────────────────┐
│  Presto Coordinator    │
│  + JWT Authenticator   │ ← Validates token
└────────────────────────┘
```

**When this works:**
- MCP clients use JWTs compatible with Presto's JWT format
- Same signing key and claims structure
- No scope translation needed

**Implementation:**

```java
public class MCPGateway {
    private final PrestoClient prestoClient;

    public Response handleMCPRequest(HttpServletRequest request) {
        // Just extract and pass through
        String token = extractBearerToken(request);

        // Extract user from token (don't validate, Presto will)
        String user = extractUserFromToken(token);

        PrestoRequest prestoReq = PrestoRequest.builder()
            .sql(extractSql(request))
            .user(user)
            .accessToken(token)  // Pass through directly
            .build();

        QueryResults results = prestoClient.execute(prestoReq);
        return Response.ok(convertToMCP(results)).build();
    }
}
```

**Problems with pass-through:**
- ❌ MCP token format may not match Presto JWT
- ❌ Different signing keys
- ❌ Different claim structures
- ❌ Can't enforce MCP-specific scopes

---

## Presto Configuration for MCP

### JWT Configuration

**File:** `etc/config.properties`
```properties
# Enable JWT authentication
http-server.authentication.type=JWT

# JWT signing key (public key for verification)
http-server.authentication.jwt.key-file=/etc/presto/jwt-public-key.pem

# Optional: principal field in JWT
http-server.authentication.jwt.principal-field=sub
```

**JWT Public Key:**
```bash
# Generate key pair
openssl genrsa -out jwt-private-key.pem 2048
openssl rsa -in jwt-private-key.pem -pubout -out jwt-public-key.pem

# Gateway uses private key to sign
# Presto uses public key to verify
```

### OAuth2 Configuration (Alternative)

**File:** `etc/config.properties`
```properties
# Enable OAuth2 authentication
http-server.authentication.type=OAUTH2

# OAuth2 provider configuration
http-server.authentication.oauth2.issuer=https://idp.example.com
http-server.authentication.oauth2.auth-url=https://idp.example.com/oauth/authorize
http-server.authentication.oauth2.token-url=https://idp.example.com/oauth/token
http-server.authentication.oauth2.jwks-url=https://idp.example.com/.well-known/jwks.json
http-server.authentication.oauth2.client-id=presto-cluster
http-server.authentication.oauth2.client-secret=secret123

# User info endpoint
http-server.authentication.oauth2.userinfo-url=https://idp.example.com/oauth/userinfo
```

---

## Recommended Integration Pattern

### Pattern: MCP OAuth → Gateway Translation → Presto JWT

**Why:**
1. ✅ **Separation of concerns:** Gateway handles MCP OAuth, Presto handles Presto auth
2. ✅ **Flexibility:** Can support different IdPs for MCP vs Presto
3. ✅ **Security:** Gateway validates MCP tokens before calling Presto
4. ✅ **Scope enforcement:** Can enforce MCP-specific scopes
5. ✅ **User mapping:** Can map external users to Presto users

**Architecture:**

```
┌─────────────────────────────────────────────────────────────────┐
│                      Auth Flow                                   │
└─────────────────────────────────────────────────────────────────┘

AI Client                     MCP Gateway                  Presto
    │                              │                          │
    │ 1. POST (no token)           │                          │
    ├─────────────────────────────>│                          │
    │                              │                          │
    │ 2. 401 + WWW-Authenticate    │                          │
    │<─────────────────────────────┤                          │
    │                              │                          │
    │ 3. OAuth flow with IdP       │                          │
    │ (User authorizes)            │                          │
    │                              │                          │
    │ 4. POST + Bearer <mcp-token> │                          │
    ├─────────────────────────────>│                          │
    │                              │                          │
    │                              │ 5. Validate MCP token   │
    │                              │    Extract user         │
    │                              │    Generate Presto JWT  │
    │                              │                          │
    │                              │ 6. POST + Bearer <presto-jwt>
    │                              │    X-Presto-User: user  │
    │                              ├─────────────────────────>│
    │                              │                          │
    │                              │                          │ 7. Validate JWT
    │                              │                          │    Execute query
    │                              │                          │
    │                              │ 8. QueryResults          │
    │                              │<─────────────────────────┤
    │                              │                          │
    │ 9. MCP response              │                          │
    │<─────────────────────────────┤                          │
```

### Implementation Components

**1. MCP Gateway Configuration:**

```yaml
# gateway-config.yml
mcp:
  # MCP OAuth configuration
  oauth:
    issuer: https://mcp-idp.example.com
    jwks-url: https://mcp-idp.example.com/.well-known/jwks.json
    audience: https://presto-gateway.example.com
    required-scopes:
      - query:execute

  # Presto JWT generation
  presto:
    jwt-signing-key: /etc/gateway/presto-jwt-private-key.pem
    jwt-issuer: presto-mcp-gateway
    jwt-expiration: 3600  # 1 hour

  # User mapping (optional)
  user-mapping:
    enabled: true
    ldap-url: ldap://ldap.example.com
    user-dn-pattern: uid={0},ou=users,dc=example,dc=com
```

**2. Gateway Deployment:**

```yaml
# kubernetes deployment
apiVersion: apps/v1
kind: Deployment
metadata:
  name: presto-mcp-gateway
spec:
  replicas: 3
  template:
    spec:
      containers:
      - name: gateway
        image: presto-mcp-gateway:latest
        env:
        - name: PRESTO_COORDINATOR_URL
          value: "http://presto-coordinator:8080"
        - name: MCP_OAUTH_JWKS_URL
          value: "https://mcp-idp.example.com/.well-known/jwks.json"
        - name: PRESTO_JWT_SIGNING_KEY_FILE
          value: "/etc/gateway/presto-jwt-private-key.pem"
        volumeMounts:
        - name: jwt-key
          mountPath: /etc/gateway
          readOnly: true
      volumes:
      - name: jwt-key
        secret:
          secretName: presto-jwt-key
```

**3. Presto Configuration:**

```properties
# etc/config.properties
http-server.authentication.type=JWT
http-server.authentication.jwt.key-file=/etc/presto/gateway-jwt-public-key.pem
http-server.authentication.jwt.principal-field=sub
```

---

## Security Best Practices

### 1. Token Validation

**Always validate:**
- ✅ Token signature
- ✅ Token expiration
- ✅ Token audience (should be this gateway)
- ✅ Token issuer (trusted IdP)
- ✅ Required scopes present

### 2. Token Storage

**Never:**
- ❌ Store tokens in logs
- ❌ Return tokens in error messages
- ❌ Cache tokens (let client manage)

### 3. Scope Enforcement

**Define clear scopes:**
```
query:execute     - Can execute queries
query:cancel      - Can cancel queries
schema:read       - Can read schema metadata
schema:write      - Can modify schema (DDL)
catalog:admin     - Can manage catalogs
```

**Enforce in gateway:**
```java
public void checkScopes(List<String> tokenScopes, String operation) {
    Map<String, String> requiredScopes = Map.of(
        "tools/call:query_presto", "query:execute",
        "tools/call:cancel_query", "query:cancel",
        "tools/call:list_catalogs", "schema:read"
    );

    String required = requiredScopes.get(operation);
    if (required != null && !tokenScopes.contains(required)) {
        throw new InsufficientScopesException("Requires scope: " + required);
    }
}
```

### 4. Rate Limiting

**Per user/token:**
```java
@Inject
private RateLimiter rateLimiter;

public Response handleRequest(String user) {
    if (!rateLimiter.tryAcquire(user)) {
        return Response.status(429)
            .entity("Rate limit exceeded")
            .header("Retry-After", "60")
            .build();
    }
    // Process request
}
```

### 5. Audit Logging

**Log authentication events:**
```java
auditLog.info("MCP auth: user={}, scopes={}, ip={}, success={}",
    user, scopes, clientIp, success);
```

---

## Common Pitfalls

### 1. Using Same Token for MCP and Presto

**Problem:**
```
AI Client → Bearer <idp-token> → Gateway → Bearer <idp-token> → Presto
                                             ↑
                                 Presto can't validate this!
```

**Solution:** Gateway must translate to Presto-compatible JWT

### 2. No Scope Validation

**Problem:**
```java
// BAD: Just check token is valid
if (validateToken(token)) {
    executeQuery(sql);  // Any valid token can do anything!
}
```

**Solution:**
```java
// GOOD: Check scopes
MCPTokenClaims claims = validateToken(token);
if (!claims.hasScope("query:execute")) {
    throw new ForbiddenException("Insufficient scopes");
}
executeQuery(sql);
```

### 3. Exposing Internal Errors

**Problem:**
```java
// BAD
catch (JwtException e) {
    return Response.status(401)
        .entity("JWT validation failed: " + e.getMessage())  // Leaks info
        .build();
}
```

**Solution:**
```java
// GOOD
catch (JwtException e) {
    logger.warn("JWT validation failed for user {}: {}", user, e.getMessage());
    return Response.status(401)
        .header("WWW-Authenticate", buildMCPChallenge())
        .entity("Authentication required")  // Generic message
        .build();
}
```

---

## Testing Authentication

### Unit Tests

```java
@Test
public void testMCPTokenValidation() {
    String token = generateValidMCPToken("user123", List.of("query:execute"));
    MCPTokenClaims claims = validator.validate(token);

    assertEquals("user123", claims.getSubject());
    assertTrue(claims.getScopes().contains("query:execute"));
}

@Test
public void testInsufficientScopes() {
    String token = generateValidMCPToken("user123", List.of("schema:read"));

    assertThrows(ForbiddenException.class, () -> {
        checkScopes(parseToken(token).getScopes(), "query:execute");
    });
}
```

### Integration Tests

```bash
# 1. Get token from IdP
TOKEN=$(curl -X POST https://idp.example.com/oauth/token \
  -d 'grant_type=client_credentials' \
  -d 'client_id=test-client' \
  -d 'client_secret=test-secret' \
  -d 'scope=query:execute' | jq -r '.access_token')

# 2. Call MCP gateway
curl -X POST https://gateway.example.com/v1/protocol/mcp \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","method":"tools/call","params":{"name":"query_presto","arguments":{"sql":"SELECT 1"}}}'

# 3. Verify works without token fails
curl -X POST https://gateway.example.com/v1/protocol/mcp \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","method":"tools/call","params":{"name":"query_presto","arguments":{"sql":"SELECT 1"}}}'
# Should return 401
```

---

## Conclusion

### Recommended Approach

**For production deployments:**

1. ✅ **Use OAuth 2.1 for MCP authentication**
   - Standard protocol
   - Wide tooling support
   - Proper scope management

2. ✅ **Gateway translates to Presto JWT**
   - Clean separation
   - Gateway validates MCP tokens
   - Presto validates Presto JWTs
   - Different keys/issuers

3. ✅ **Enforce scope-based authorization**
   - Define clear scopes
   - Check scopes in gateway
   - Audit scope usage

4. ✅ **Use existing IdP infrastructure**
   - Don't build custom auth
   - Leverage enterprise SSO
   - Okta, Auth0, Azure AD, etc.

### Security Checklist

- [ ] MCP OAuth tokens validated at gateway
- [ ] Scopes checked for each operation
- [ ] Gateway generates Presto JWTs with limited lifetime
- [ ] Presto configured with gateway's public key
- [ ] Rate limiting implemented
- [ ] Audit logging enabled
- [ ] Tokens never logged or returned in errors
- [ ] HTTPS/TLS enforced
- [ ] Regular key rotation policy
- [ ] Token expiration monitored

---

**Document Version:** 1.0
**Date:** 2025-10-31
**Related:** `docs/mcp-load-balancing-analysis.md`, `docs/mcp-integration-design.md`
