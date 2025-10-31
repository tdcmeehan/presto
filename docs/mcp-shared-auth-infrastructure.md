# MCP Authentication: Shared Infrastructure with Presto

## The Simplified Approach

**Key Insight:** If the MCP gateway and Presto are both configured to use the **same authentication infrastructure**, you can pass tokens through directly without translation.

---

## Scenario: Shared Authentication Infrastructure

### What "Shared Infrastructure" Means

Both the MCP gateway and Presto coordinators are configured with:

```
┌──────────────────────────────────────────────┐
│     Shared Authentication Infrastructure      │
├──────────────────────────────────────────────┤
│                                              │
│  OAuth2/OIDC Provider (e.g., Okta)          │
│  - Issues tokens for both MCP and Presto    │
│  - Same signing keys (JWKS endpoint)        │
│  - Same token format (JWT)                  │
│  - Same claims structure                    │
│  - Same audience/issuer configuration       │
│                                              │
└──────────────────────────────────────────────┘
           ↓                        ↓
    ┌─────────────┐         ┌──────────────┐
    │ MCP Gateway │         │   Presto     │
    │             │         │ Coordinator  │
    │ JWT Config: │         │ JWT Config:  │
    │ - Issuer    │         │ - Issuer     │ ← Same values
    │ - JWKS URL  │         │ - JWKS URL   │ ← Same values
    │ - Audience  │         │ - Audience   │ ← Compatible
    └─────────────┘         └──────────────┘
```

### Pass-Through Architecture

```
┌─────────────┐
│  AI Client  │
└──────┬──────┘
       │
       │ 1. OAuth flow with shared IdP
       │    (Okta, Auth0, Azure AD, etc.)
       ↓
┌──────────────┐
│  IdP (Okta)  │
└──────┬───────┘
       │
       │ 2. Access token (JWT)
       ↓
┌─────────────┐
│  AI Client  │
└──────┬──────┘
       │
       │ 3. POST /v1/protocol/mcp
       │    Authorization: Bearer <jwt>
       ↓
┌──────────────────────────────┐
│  MCP Gateway                 │
│                              │
│  1. Extract token            │
│  2. Minimal validation       │ ← Just check not expired, extract user
│  3. Pass through to Presto   │ ← No translation!
└───────┬──────────────────────┘
        │
        │ 4. POST /v1/statement
        │    Authorization: Bearer <jwt>
        │    X-Presto-User: user
        ↓
┌───────────────────────────┐
│  Presto Coordinator       │
│                           │
│  AuthenticationFilter     │ ← Validates JWT fully
│  - JWT Authenticator      │
│  - Validates signature    │
│  - Checks expiration      │
│  - Extracts Principal     │
└───────────────────────────┘
```

---

## Implementation

### Configuration

#### IdP Configuration (e.g., Okta)

**Create OAuth2 Application:**
```json
{
  "name": "Presto MCP Integration",
  "client_id": "presto-mcp-client",
  "client_secret": "...",
  "redirect_uris": [
    "https://mcp-gateway.example.com/oauth/callback"
  ],
  "grant_types": [
    "authorization_code",
    "refresh_token"
  ],
  "token_endpoint_auth_method": "client_secret_post"
}
```

**Configure token claims:**
```json
{
  "iss": "https://example.okta.com",
  "sub": "user@example.com",
  "aud": "presto-api",
  "exp": 1234567890,
  "iat": 1234564290,
  "scope": "query:execute schema:read"
}
```

#### Gateway Configuration

**File:** `gateway-config.yml`

```yaml
mcp:
  # OAuth2 configuration
  oauth:
    # Provider details
    issuer: https://example.okta.com
    jwks-url: https://example.okta.com/oauth2/v1/keys

    # Token validation
    audience: presto-api  # What we expect in 'aud' claim

    # Minimal validation (Presto will do full validation)
    validate:
      signature: false  # Presto will validate
      expiration: true  # Quick check to avoid forwarding expired tokens
      audience: true    # Check audience matches

  # Presto connection
  presto:
    coordinator-url: http://presto-coordinator:8080
    pass-through-auth: true  # Pass token through without translation
```

#### Presto Configuration

**File:** `etc/config.properties`

```properties
# JWT authentication
http-server.authentication.type=JWT

# Use same IdP as gateway
http-server.authentication.jwt.key-file=https://example.okta.com/oauth2/v1/keys
# OR download public keys:
# http-server.authentication.jwt.key-file=/etc/presto/okta-public-keys.pem

# Token validation
http-server.authentication.jwt.principal-field=sub
http-server.authentication.jwt.issuer=https://example.okta.com
http-server.authentication.jwt.audience=presto-api
```

**Alternatively, use OAuth2 authenticator:**

```properties
# OAuth2 authentication (more features)
http-server.authentication.type=OAUTH2

# Provider configuration
http-server.authentication.oauth2.issuer=https://example.okta.com
http-server.authentication.oauth2.jwks-url=https://example.okta.com/oauth2/v1/keys
http-server.authentication.oauth2.client-id=presto-api
http-server.authentication.oauth2.audience=presto-api

# Optional: User info endpoint for additional claims
http-server.authentication.oauth2.userinfo-url=https://example.okta.com/oauth2/v1/userinfo
```

### Gateway Implementation

**Simple pass-through with minimal validation:**

```java
public class MCPGateway {
    private final JwtParser jwtParser;
    private final PrestoClient prestoClient;
    private final String expectedAudience;

    public Response handleMCPRequest(HttpServletRequest request) {
        // 1. Extract token from Authorization header
        String token = extractBearerToken(request);

        if (token == null) {
            return buildOAuthChallenge();
        }

        try {
            // 2. Minimal validation (Presto will do full validation)
            TokenInfo tokenInfo = quickValidate(token);

            // 3. Pass through to Presto with same token
            PrestoRequest prestoReq = PrestoRequest.builder()
                .sql(extractSql(request))
                .user(tokenInfo.getUser())
                .catalog(tokenInfo.getCatalog())
                .schema(tokenInfo.getSchema())
                .accessToken(token)  // Same token, no translation!
                .build();

            QueryResults results = prestoClient.execute(prestoReq);

            // 4. Return results in MCP format
            return Response.ok(convertToMCP(results)).build();

        } catch (TokenValidationException e) {
            logger.warn("Token validation failed: {}", e.getMessage());
            return buildOAuthChallenge();
        }
    }

    private TokenInfo quickValidate(String token) throws TokenValidationException {
        try {
            // Parse without full validation (Presto will validate signature)
            Jws<Claims> jws = jwtParser.parseClaimsJws(token);
            Claims claims = jws.getBody();

            // Quick checks
            if (claims.getExpiration().before(new Date())) {
                throw new TokenValidationException("Token expired");
            }

            if (!expectedAudience.equals(claims.getAudience())) {
                throw new TokenValidationException("Invalid audience");
            }

            // Extract user info
            String user = claims.getSubject();
            String catalog = claims.get("catalog", String.class);
            String schema = claims.get("schema", String.class);

            return new TokenInfo(user, catalog, schema);

        } catch (JwtException e) {
            throw new TokenValidationException("Invalid token", e);
        }
    }

    private Response buildOAuthChallenge() {
        return Response.status(401)
            .header("WWW-Authenticate", String.format(
                "Bearer realm=\"presto-mcp\", " +
                "resource_metadata=\"%s/.well-known/prm\", " +
                "scope=\"query:execute schema:read\"",
                gatewayBaseUrl
            ))
            .build();
    }
}
```

### Protected Resource Metadata (PRM) Document

**Gateway exposes PRM document for OAuth discovery:**

```java
@Path("/.well-known/prm")
public class ProtectedResourceMetadataResource {
    @GET
    @Produces(APPLICATION_JSON)
    public Response getPRM() {
        return Response.ok(Map.of(
            "resource", gatewayBaseUrl + "/v1/protocol/mcp",
            "authorization_servers", List.of(Map.of(
                "issuer", "https://example.okta.com",
                "authorization_endpoint", "https://example.okta.com/oauth2/v1/authorize",
                "token_endpoint", "https://example.okta.com/oauth2/v1/token",
                "jwks_uri", "https://example.okta.com/oauth2/v1/keys",
                "scopes_supported", List.of(
                    "query:execute",
                    "query:cancel",
                    "schema:read",
                    "schema:write"
                )
            ))
        )).build();
    }
}
```

---

## Advantages of Shared Infrastructure

### 1. **Simplicity**

**Without translation:**
```java
// Gateway code
String token = extractBearerToken(request);
quickValidate(token);  // Just expiration + audience
prestoClient.execute(sql, user, token);  // Pass through!
```

**With translation:**
```java
// Gateway code
String mcpToken = extractBearerToken(request);
MCPTokenClaims mcpClaims = validateMcpToken(mcpToken);  // Full validation
String prestoJwt = generatePrestoJwt(mcpClaims.getUser());  // Generate new token
prestoClient.execute(sql, user, prestoJwt);  // Use new token
```

### 2. **Performance**

**Token operations saved:**
```
With translation:
  1. Gateway validates MCP token (cryptographic signature check)
  2. Gateway generates Presto JWT (cryptographic signing)
  3. Presto validates Presto JWT (cryptographic signature check)
  Total: 3 crypto operations

With pass-through:
  1. Gateway quick check (no signature validation)
  2. Presto validates JWT (cryptographic signature check)
  Total: 1 crypto operation

Savings: 66% fewer crypto operations
```

### 3. **Reduced Key Management**

**Keys needed:**
```
With translation:
  - IdP's public keys (for MCP validation)
  - Gateway's private key (for Presto JWT generation)
  - Gateway's public key (for Presto validation)
  Total: 3 keys/key sets

With pass-through:
  - IdP's public keys (for Presto validation)
  Total: 1 key set

Fewer keys = less complexity, fewer rotation issues
```

### 4. **Single Source of Truth**

```
With translation:
  User info in 2 places:
  - MCP token claims
  - Presto JWT claims

  Risk: Mismatch or information loss

With pass-through:
  User info in 1 place:
  - Original JWT claims

  No information loss, no translation errors
```

### 5. **Easier Debugging**

```
With translation:
  Problem: Query authorized but fails in Presto

  Debug path:
  1. Check MCP token (is it valid?)
  2. Check gateway translation (did it preserve info correctly?)
  3. Check Presto JWT (is it valid?)
  4. Check Presto authorization (does Presto accept user?)

  Multiple failure points!

With pass-through:
  Problem: Query authorized but fails in Presto

  Debug path:
  1. Check JWT (is it valid for Presto?)
  2. Check Presto authorization (does Presto accept user?)

  Simpler debugging!
```

### 6. **Consistent Token Lifecycle**

```
With translation:
  - MCP token expires in 1 hour
  - Gateway generates Presto JWT valid for 1 hour
  - Client refreshes MCP token, gets new one
  - New Presto JWT generated

  Complexity: Two expiration timelines

With pass-through:
  - Token expires in 1 hour
  - Client refreshes token with IdP
  - Same token used throughout

  Simple: One expiration timeline
```

---

## Requirements for Pass-Through

### Must Have

1. **Same IdP**
   ```
   Both gateway and Presto configured with:
   - Same issuer (iss claim)
   - Same JWKS URL
   ```

2. **Compatible Token Format**
   ```json
   {
     "iss": "https://example.okta.com",
     "sub": "user@example.com",        ← Presto principal
     "aud": "presto-api",               ← Presto expects this
     "exp": 1234567890,
     "iat": 1234564290,
     "scope": "query:execute schema:read"
   }
   ```

3. **Matching Audience Configuration**
   ```properties
   # Gateway expects
   mcp.oauth.audience=presto-api

   # Presto expects
   http-server.authentication.jwt.audience=presto-api

   Must match!
   ```

4. **Principal Field Agreement**
   ```properties
   # Presto config
   http-server.authentication.jwt.principal-field=sub

   # Token must have 'sub' claim with username
   ```

### Nice to Have

1. **Custom Claims for Presto Context**
   ```json
   {
     "sub": "user@example.com",
     "catalog": "hive",           ← Can set default catalog
     "schema": "default",         ← Can set default schema
     "scope": "query:execute"     ← Can check scopes
   }
   ```

2. **Groups/Roles Claims**
   ```json
   {
     "sub": "user@example.com",
     "groups": ["analysts", "data-scientists"],
     "roles": ["query-executor"]
   }
   ```
   (Presto can use these for authorization)

---

## When Pass-Through Works Best

### ✅ Use Pass-Through When:

1. **You control both gateway and Presto configuration**
   - Can configure same IdP for both
   - Can ensure compatible token formats

2. **Using modern IdP (Okta, Auth0, Azure AD, Keycloak)**
   - Supports OAuth2/OIDC
   - Provides JWKS endpoint
   - Standard JWT format

3. **New deployment**
   - Designing from scratch
   - Can choose authentication strategy

4. **Single security domain**
   - Gateway and Presto in same trust boundary
   - Same compliance requirements
   - Same user directory

### ❌ Translation Still Needed When:

1. **Different IdPs**
   ```
   MCP clients: Azure AD (external users)
   Presto: Okta (internal users)
   Need translation to map between systems
   ```

2. **Token format incompatible**
   ```
   MCP tokens: Custom format
   Presto: Standard JWT
   Must translate
   ```

3. **User mapping required**
   ```
   MCP user: external@partner.com
   Presto user: external_partner
   Need to map identities
   ```

4. **Different security domains**
   ```
   Gateway: DMZ (public-facing)
   Presto: Internal network
   Security policy requires separate auth
   ```

5. **Legacy Presto deployment**
   ```
   Presto: Already using Kerberos
   MCP: OAuth2 tokens
   Can't change Presto auth easily
   ```

---

## Implementation Comparison

### Pass-Through Implementation (Shared Auth)

**Gateway:**
```java
public class MCPGateway {
    private final PrestoClient prestoClient;

    public Response handleRequest(String token, String sql) {
        // Quick validation only
        validateNotExpired(token);
        String user = extractUser(token);

        // Pass through
        return prestoClient.execute(sql, user, token);
    }
}
```

**Presto Client:**
```java
public class PrestoClient {
    public QueryResults execute(String sql, String user, String accessToken) {
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(prestoUrl + "/v1/statement"))
            .header("Authorization", "Bearer " + accessToken)  // Same token
            .header("X-Presto-User", user)
            .POST(BodyPublishers.ofString(sql))
            .build();

        return httpClient.send(request, BodyHandlers.ofString());
    }
}
```

**Lines of code:** ~50

### Translation Implementation (Different Auth)

**Gateway:**
```java
public class MCPGateway {
    private final MCPTokenValidator mcpValidator;
    private final PrestoJwtGenerator prestoJwtGen;
    private final PrestoClient prestoClient;

    public Response handleRequest(String mcpToken, String sql) {
        // Full MCP token validation
        MCPTokenClaims mcpClaims = mcpValidator.validate(mcpToken);

        // Check scopes
        if (!mcpClaims.hasScope("query:execute")) {
            throw new ForbiddenException();
        }

        // Generate Presto JWT
        String prestoJwt = prestoJwtGen.generateToken(
            mcpClaims.getUser(),
            mcpClaims.getCatalog(),
            mcpClaims.getSchema()
        );

        // Call Presto
        return prestoClient.execute(sql, mcpClaims.getUser(), prestoJwt);
    }
}
```

**Token Validator:**
```java
public class MCPTokenValidator {
    private final JwtParser parser;
    // + JWKS fetching
    // + Key caching
    // + Signature validation
    // + Claims validation
}
```

**JWT Generator:**
```java
public class PrestoJwtGenerator {
    private final Key signingKey;
    // + Key loading
    // + JWT creation
    // + Signing
}
```

**Lines of code:** ~300+

---

## Recommended Default: Pass-Through

### Why Start with Pass-Through

For **new deployments**, use pass-through as the default:

```
Phase 1: Design with shared auth infrastructure
  - Choose modern IdP (Okta, Auth0, etc.)
  - Configure gateway to use IdP
  - Configure Presto to use same IdP
  - Token format compatible by design

Phase 2: Implement pass-through gateway
  - Simple validation (expiration, audience)
  - Forward token to Presto
  - Presto does full validation

Phase 3: Add translation only if needed
  - Different IdP required?
  - User mapping needed?
  - Add translation layer
```

### Configuration Template

**IdP (Okta) Application:**
```json
{
  "name": "Presto MCP",
  "client_id": "presto-mcp",
  "redirect_uri": "https://gateway.example.com/oauth/callback",
  "grant_types": ["authorization_code", "refresh_token"],

  "custom_claims": {
    "catalog": "${user.catalog}",
    "schema": "${user.schema}",
    "groups": "${user.groups}"
  }
}
```

**Gateway Config:**
```yaml
mcp:
  oauth:
    issuer: https://example.okta.com
    jwks-url: https://example.okta.com/oauth2/v1/keys
    audience: presto-api
    pass-through: true  # Key setting!

  presto:
    url: http://presto:8080
```

**Presto Config:**
```properties
http-server.authentication.type=JWT
http-server.authentication.jwt.key-file=https://example.okta.com/oauth2/v1/keys
http-server.authentication.jwt.issuer=https://example.okta.com
http-server.authentication.jwt.audience=presto-api
http-server.authentication.jwt.principal-field=sub
```

---

## Migration Path: Translation → Pass-Through

If you already have translation, you can migrate:

### Phase 1: Deploy Shared IdP for New Clients
```
Existing:
  MCP clients → Azure AD → Gateway (translation) → Presto

New:
  MCP clients → Okta → Gateway (pass-through) → Presto

Keep both working during migration
```

### Phase 2: Migrate Presto to Shared IdP
```properties
# Support multiple authenticators during migration
http-server.authentication.type=JWT,OAUTH2

# Old (Azure AD via translation)
http-server.authentication.jwt.key-file=/etc/presto/gateway-public-key.pem

# New (Okta direct)
http-server.authentication.oauth2.issuer=https://example.okta.com
http-server.authentication.oauth2.jwks-url=https://example.okta.com/oauth2/v1/keys
```

### Phase 3: Migrate Clients
```
Move clients one by one:
  Client 1: Switch to Okta → pass-through works
  Client 2: Switch to Okta → pass-through works
  ...
  Client N: Switch to Okta → pass-through works
```

### Phase 4: Remove Translation
```
When all clients migrated:
  - Remove translation code from gateway
  - Remove gateway JWT signing key
  - Simplify to pass-through only
```

---

## Summary

### Answer: Yes, You Can and Should Use the Same Token!

**If gateway and Presto share authentication infrastructure:**

✅ **Do:** Pass token through without translation
- Simpler implementation
- Better performance
- Easier debugging
- Fewer keys to manage
- Single source of truth

❌ **Don't:** Translate unnecessarily
- Adds complexity
- Adds latency
- More failure points
- More key management

### Decision Tree

```
Can you configure gateway and Presto with same IdP?
├─ Yes
│  └─ Are token formats compatible?
│     ├─ Yes
│     │  └─ ✅ Use pass-through (RECOMMENDED)
│     └─ No
│        └─ Can you make them compatible?
│           ├─ Yes
│           │  └─ ✅ Make compatible, use pass-through
│           └─ No
│              └─ ⚠️ Use translation
└─ No
   └─ ⚠️ Use translation
```

### Recommendation

**For new deployments:**
Design with shared authentication infrastructure and use pass-through from day one.

**For existing deployments:**
Evaluate migration to shared infrastructure. Benefits likely outweigh migration costs.

---

**Document Version:** 1.0
**Date:** 2025-10-31
**Related:** `docs/mcp-authentication-integration.md`, `docs/mcp-load-balancing-analysis.md`
