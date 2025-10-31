# MCP Pass-Through Authentication: Configuration Mechanics

## The Key Question

**If using pass-through, does the gateway do "actual authentication" or just forward tokens?**

**Answer:** The gateway does **minimal authentication** (enough to extract user identity and return challenges), but **Presto does the full cryptographic validation**.

---

## What Each Component Does

### Gateway Responsibilities (Minimal)

```java
public class MCPGateway {

    public Response handleRequest(HttpServletRequest request) {
        String token = extractBearerToken(request);

        // 1. No token? Return OAuth challenge
        if (token == null) {
            return buildOAuthChallenge();  // ← Gateway's job
        }

        // 2. Parse token to extract user (no signature validation!)
        String user;
        try {
            user = parseTokenWithoutValidation(token);  // ← Just parse JWT
        } catch (Exception e) {
            return buildOAuthChallenge();
        }

        // 3. Pass token and user to Presto
        return prestoClient.execute(
            sql,
            user,
            token  // ← Same token, Presto will validate
        );
    }

    private String parseTokenWithoutValidation(String token) {
        // Parse JWT WITHOUT validating signature
        // This is fast - just base64 decode and JSON parse
        String[] parts = token.split("\\.");
        String payload = new String(Base64.getUrlDecoder().decode(parts[1]));
        JsonNode claims = objectMapper.readTree(payload);
        return claims.get("sub").asText();
    }
}
```

**Gateway does:**
- ✅ Return OAuth challenges (401 + WWW-Authenticate)
- ✅ Parse token to extract user identity
- ✅ Optionally: Quick expiration check
- ❌ **Does NOT validate signature** (Presto will)
- ❌ **Does NOT validate issuer** (Presto will)

### Presto Responsibilities (Full Validation)

```java
// presto-main/src/main/java/com/facebook/presto/server/security/JsonWebTokenAuthenticator.java

public class JsonWebTokenAuthenticator implements Authenticator {

    @Override
    public Principal authenticate(HttpServletRequest request) {
        String token = extractBearerToken(request);

        // Full cryptographic validation
        Jws<Claims> jws = jwtParser.parseClaimsJws(token);  // ← Validates signature!
        Claims claims = jws.getBody();

        // Validate issuer
        if (!expectedIssuer.equals(claims.getIssuer())) {
            throw new AuthenticationException("Invalid issuer");
        }

        // Validate audience
        if (!expectedAudience.equals(claims.getAudience())) {
            throw new AuthenticationException("Invalid audience");
        }

        // Validate expiration
        if (claims.getExpiration().before(new Date())) {
            throw new AuthenticationException("Token expired");
        }

        // Extract principal
        String user = claims.getSubject();
        return new BasicPrincipal(user);
    }
}
```

**Presto does:**
- ✅ **Validate signature** (cryptographic verification)
- ✅ **Validate issuer** (must match config)
- ✅ **Validate audience** (must match config)
- ✅ **Validate expiration** (not expired)
- ✅ **Extract principal** (for authorization)

---

## Configuration

### Gateway Configuration

**File:** `gateway-config.yml`

```yaml
mcp:
  oauth:
    # OAuth provider details
    issuer: https://example.okta.com

    # Where clients get tokens (for WWW-Authenticate header)
    authorization-endpoint: https://example.okta.com/oauth2/v1/authorize
    token-endpoint: https://example.okta.com/oauth2/v1/token

    # Gateway validation settings
    validation:
      parse-only: true        # ← Just parse, don't validate signature
      check-expiration: true  # ← Optional: quick expiration check

    # Pass-through mode
    pass-through: true        # ← Don't generate new token

  presto:
    url: http://presto-coordinator:8080
```

**What `pass-through: true` means:**
```java
if (config.isPassThrough()) {
    // Use original token
    prestoClient.setAccessToken(originalToken);
} else {
    // Generate new Presto JWT
    String prestoJwt = jwtGenerator.generate(user);
    prestoClient.setAccessToken(prestoJwt);
}
```

### Presto Configuration

**File:** `etc/config.properties`

```properties
# Enable JWT authentication
http-server.authentication.type=JWT

# JWKS endpoint (where Presto gets public keys)
http-server.authentication.jwt.key-file=https://example.okta.com/oauth2/v1/keys

# Token validation
http-server.authentication.jwt.issuer=https://example.okta.com
http-server.authentication.jwt.audience=presto-api
http-server.authentication.jwt.principal-field=sub
```

**Presto does full validation against these settings.**

---

## Authentication Flow

### Step-by-Step

```
┌─────────────┐
│  AI Client  │
└──────┬──────┘
       │
       │ 1. POST /v1/protocol/mcp (no Authorization header)
       ↓
┌──────────────────────┐
│  MCP Gateway         │
│                      │
│  No token present    │ ← Gateway checks
│                      │
│  Return 401:         │
│  WWW-Authenticate:   │ ← Gateway provides OAuth info
│    Bearer            │
│    resource_metadata │
└──────┬───────────────┘
       │
       │ 2. HTTP 401 Unauthorized
       │    WWW-Authenticate: Bearer realm="...",
       │                      resource_metadata="https://gateway/.well-known/prm"
       ↓
┌─────────────┐
│  AI Client  │
│              │
│  Fetches PRM │ ← Client learns about OAuth endpoints
│  Does OAuth  │
│  flow        │
└──────┬───────┘
       │
       │ 3. OAuth flow with IdP
       ↓
┌──────────────┐
│  Okta (IdP)  │
└──────┬───────┘
       │
       │ 4. Access token (JWT)
       ↓
┌─────────────┐
│  AI Client  │
└──────┬──────┘
       │
       │ 5. POST /v1/protocol/mcp
       │    Authorization: Bearer eyJhbGc...
       ↓
┌──────────────────────────────────┐
│  MCP Gateway                     │
│                                  │
│  Token present                   │
│                                  │
│  Parse token (no validation):   │ ← Just parse
│    parts = token.split('.')     │
│    payload = base64Decode(parts[1])
│    user = JSON.parse(payload).sub
│                                  │
│  Optional quick check:           │
│    exp = JSON.parse(payload).exp │
│    if (exp < now) return 401     │
└───────┬──────────────────────────┘
        │
        │ 6. POST /v1/statement
        │    Authorization: Bearer eyJhbGc...  ← Same token!
        │    X-Presto-User: user@example.com
        ↓
┌────────────────────────────────────┐
│  Presto Coordinator                │
│                                    │
│  AuthenticationFilter              │
│                                    │
│  JWT Authenticator:                │ ← Full validation
│    1. Fetch public key from JWKS   │
│    2. Verify signature             │ ← Cryptographic
│    3. Validate issuer              │
│    4. Validate audience            │
│    5. Validate expiration          │
│    6. Extract principal            │
│                                    │
│  If valid: Process query           │
│  If invalid: Return 401            │
└────────────────────────────────────┘
```

---

## Why Gateway Still Needs OAuth Config

Even in pass-through mode, the gateway needs OAuth configuration to:

### 1. Return OAuth Challenges

When client has no token:
```http
HTTP/1.1 401 Unauthorized
WWW-Authenticate: Bearer realm="presto-mcp",
                  resource_metadata="https://gateway.example.com/.well-known/prm"
```

Gateway needs to know where to point clients.

### 2. Serve Protected Resource Metadata (PRM)

```java
@Path("/.well-known/prm")
public class PRMResource {
    @GET
    public Response getPRM() {
        return Response.ok(Map.of(
            "resource", gatewayBaseUrl + "/v1/protocol/mcp",
            "authorization_servers", List.of(Map.of(
                "issuer", config.getOAuth().getIssuer(),  // ← From config
                "authorization_endpoint", config.getOAuth().getAuthzEndpoint(),
                "token_endpoint", config.getOAuth().getTokenEndpoint()
            ))
        )).build();
    }
}
```

### 3. Extract User Identity

Gateway needs to parse token to get user for `X-Presto-User` header:
```java
String user = parseToken(token).getSubject();

PrestoRequest request = PrestoRequest.builder()
    .user(user)  // ← Need to extract this
    .accessToken(token)
    .build();
```

---

## Implementation: Minimal Gateway Validation

```java
public class MCPGateway {
    private final OAuthConfig oauthConfig;
    private final PrestoClient prestoClient;
    private final ObjectMapper objectMapper;

    public Response handleMCPRequest(HttpServletRequest request) {
        // 1. Extract token
        String token = extractBearerToken(request);

        if (token == null) {
            // No token - return challenge
            return buildOAuthChallenge();
        }

        try {
            // 2. Parse token WITHOUT signature validation
            //    (Just base64 decode + JSON parse - very fast)
            TokenInfo tokenInfo = parseTokenWithoutValidation(token);

            // 3. Optional: Quick expiration check
            if (oauthConfig.isCheckExpiration() && tokenInfo.isExpired()) {
                logger.debug("Token expired, returning challenge");
                return buildOAuthChallenge();
            }

            // 4. Pass through to Presto with same token
            PrestoRequest prestoReq = PrestoRequest.builder()
                .sql(extractSql(request))
                .user(tokenInfo.getUser())
                .catalog(tokenInfo.getCatalog())
                .schema(tokenInfo.getSchema())
                .accessToken(token)  // Same token!
                .build();

            QueryResults results = prestoClient.execute(prestoReq);
            return Response.ok(convertToMCP(results)).build();

        } catch (TokenParseException e) {
            // Malformed token
            logger.warn("Token parse failed: {}", e.getMessage());
            return buildOAuthChallenge();
        }
    }

    private TokenInfo parseTokenWithoutValidation(String token) throws TokenParseException {
        try {
            // Split JWT: header.payload.signature
            String[] parts = token.split("\\.");
            if (parts.length != 3) {
                throw new TokenParseException("Invalid JWT format");
            }

            // Decode payload (base64)
            byte[] payloadBytes = Base64.getUrlDecoder().decode(parts[1]);
            String payloadJson = new String(payloadBytes, StandardCharsets.UTF_8);

            // Parse JSON
            JsonNode claims = objectMapper.readTree(payloadJson);

            // Extract claims
            String user = claims.get("sub").asText();
            long exp = claims.get("exp").asLong();
            String catalog = claims.has("catalog") ? claims.get("catalog").asText() : null;
            String schema = claims.has("schema") ? claims.get("schema").asText() : null;

            return new TokenInfo(user, exp, catalog, schema);

        } catch (Exception e) {
            throw new TokenParseException("Failed to parse token", e);
        }
    }

    private Response buildOAuthChallenge() {
        String challenge = String.format(
            "Bearer realm=\"presto-mcp\", " +
            "resource_metadata=\"%s/.well-known/prm\"",
            oauthConfig.getGatewayBaseUrl()
        );

        return Response.status(401)
            .header("WWW-Authenticate", challenge)
            .entity("Authentication required")
            .build();
    }
}

@Data
class TokenInfo {
    private final String user;
    private final long expiration;
    private final String catalog;
    private final String schema;

    public boolean isExpired() {
        return expiration < System.currentTimeMillis() / 1000;
    }
}
```

---

## Configuration-Only Change?

**Mostly yes, but not entirely.**

### What Changes with Configuration

```yaml
# gateway-config.yml

# Mode 1: Translation (complex)
mcp:
  oauth:
    validation:
      parse-only: false      # Full validation
  pass-through: false        # Generate new token

# Mode 2: Pass-through (simple)
mcp:
  oauth:
    validation:
      parse-only: true       # Just parse
  pass-through: true         # Use same token
```

### What Needs Code

The gateway code needs **both code paths**:

```java
if (config.isPassThrough()) {
    // Pass-through mode
    TokenInfo info = parseTokenWithoutValidation(token);
    prestoClient.execute(sql, info.getUser(), token);  // Same token
} else {
    // Translation mode
    MCPTokenClaims claims = validateTokenFully(token);  // Full validation
    String prestoJwt = generatePrestoJwt(claims.getUser());
    prestoClient.execute(sql, claims.getUser(), prestoJwt);  // New token
}
```

But the pass-through path is **much simpler** (just parsing, no crypto).

---

## Summary: Division of Responsibilities

### Gateway (Minimal)
```
✅ Return OAuth challenges (WWW-Authenticate)
✅ Serve PRM document (/.well-known/prm)
✅ Parse token to extract user
✅ Optional: Check expiration (quick)
❌ Does NOT validate signature
❌ Does NOT validate issuer
❌ Does NOT do full OAuth validation
```

### Presto (Full)
```
✅ Validate signature (cryptographic)
✅ Validate issuer
✅ Validate audience
✅ Validate expiration
✅ Extract principal
✅ Authorize queries
```

### Configuration Keys

**Gateway:**
```yaml
mcp.oauth.pass-through: true  # ← Key setting!
mcp.oauth.validation.parse-only: true
```

**Presto:**
```properties
http-server.authentication.type=JWT
http-server.authentication.jwt.key-file=https://okta.com/keys
http-server.authentication.jwt.issuer=https://okta.com
http-server.authentication.jwt.audience=presto-api
```

**Key requirement:** Issuer and audience must match between gateway OAuth config and Presto JWT config.

---

## Conceptual Model

Think of it like TSA:

```
Gateway = TSA Pre-Check
  - Quick check: "Do you have a boarding pass?"
  - Extract info: "Gate 42, Seat 12A"
  - Pass through to gate

Presto = Gate Agent
  - Full verification: Validate boarding pass signature
  - Check: Is this the right flight?
  - Check: Is boarding pass expired?
  - Check: Is passenger who they claim to be?
  - Allow/deny boarding
```

Gateway does **just enough** to route the request properly.
Presto does the **real security validation**.

---

**Document Version:** 1.0
**Date:** 2025-10-31
**Related:** `docs/mcp-shared-auth-infrastructure.md`
