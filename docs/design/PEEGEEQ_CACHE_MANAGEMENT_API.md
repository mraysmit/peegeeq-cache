# PeeGeeQ Cache Management API

**Author:** Mark A Ray-Smith Cityline Ltd

**Date:** 17 August 2026
**Version:** 1.0 draft

**Base path:** `/api/v1`

## 1. Purpose

This document defines the HTTP, Server-Sent Events, WebSocket, security, error, concurrency, and Java service contracts required by the PeeGeeQ Cache Management UI.

It is the implementation contract for:

- `peegee-cache-rest`, the Vert.x management server;
- `peegee-cache-management-ui`, the React client;
- the PostgreSQL implementation of management inspection and guarded administration;
- integration and browser tests.

Phase 8.2 completed its non-executable M0 contract/build gate and is now in M1. No management runtime behavior or route implementation exists yet. The browser console remains separately tracked as Phase 8.3. Section 19 defines module ownership and evidence gates; executable work begins with red OpenAPI contract tests.

The associated product and screen design is in [PEEGEEQ_CACHE_MANAGEMENT_UI_DESIGN.md](PEEGEEQ_CACHE_MANAGEMENT_UI_DESIGN.md). The interactive screen designs are in [UI mockups/peegeeq-cache-management-ui-mockups.html](UI%20mockups/peegeeq-cache-management-ui-mockups.html).

The reviewed exact route inventory is [PEEGEEQ_CACHE_MANAGEMENT_OPERATION_MANIFEST.md](PEEGEEQ_CACHE_MANAGEMENT_OPERATION_MANIFEST.md). The accepted reactor topology and server configuration/secret-reference shapes are in [PEEGEEQ_CACHE_MANAGEMENT_BUILD_DECISION.md](PEEGEEQ_CACHE_MANAGEMENT_BUILD_DECISION.md).

## 2. Contract principles

### 2.1 Database truth and runtime truth are separate

Database-wide responses describe the selected PostgreSQL cache schema. Runtime responses describe only the management server process. The API must not label process-local hit, operation, or latency metrics as application-wide cache metrics.

### 2.2 Metadata endpoints never return sensitive values

List and ordinary detail responses never contain:

- cache values;
- pub/sub payloads;
- lock owner tokens;
- database passwords;
- authorization headers.

Sensitive data is available only through dedicated operator-only reveal operations. Reveal responses use `Cache-Control: no-store` and emit sanitized audit events.

### 2.3 Every mutation is explicit

The server does not infer a mutation from a read, stream connection, or page navigation. Bulk operations use preview and execute phases. Versioned resources use preconditions. The client never automatically retries a mutation.

### 2.4 The REST layer depends on typed services

REST handlers use PeeGeeQ Cache API interfaces and REST-owned setup/session services. They do not cast to PostgreSQL implementations or expose database rows directly.

### 2.5 The browser is not a trusted enforcement point

Role-based visibility in React improves usability, but the management server independently authenticates and authorizes every request and stream.

## 3. Protocol conventions

### 3.1 Media types

| Use | Media type |
|---|---|
| REST request and response | `application/json; charset=utf-8` |
| Problem response | `application/problem+json; charset=utf-8` |
| Server-Sent Events | `text/event-stream; charset=utf-8` |
| WebSocket messages | UTF-8 JSON text frames |
| Namespace export | `text/csv; charset=utf-8` or `application/json` |

Requests with an unsupported `Content-Type` return `415`. Requests that cannot accept the endpoint's response type return `406`.

### 3.2 Naming and time

- JSON property names use camelCase.
- Enum values use upper snake case.
- Timestamps use ISO-8601 UTC strings with a `Z` suffix.
- Durations crossing the boundary use non-negative integer milliseconds.
- PostgreSQL/Java 64-bit integers are represented as decimal strings to avoid JavaScript precision loss.
- Human-formatted dates, durations, byte sizes, and counts are produced by the UI, not the API.

Example:

```json
{
  "version": "14",
  "fencingToken": "48201",
  "entryCount": "18642",
  "ttlMillis": 1421000,
  "updatedAt": "2026-08-16T10:42:06.183Z"
}
```

### 3.3 Encoded identifiers

Setup identifiers use the route-safe pattern `[A-Za-z0-9][A-Za-z0-9_-]{0,63}` and appear directly in paths.

Namespaces, cache keys, counter keys, and lock keys are arbitrary UTF-8 identifiers. In path segments they use unpadded Base64 URL encoding of the original UTF-8 bytes:

```text
encodedIdentifier = base64url(utf8(identifier)), without '=' padding
```

The decoded namespace must contain 1–128 UTF-8 bytes. A decoded key must contain 1–1024 UTF-8 bytes. NUL is prohibited because PostgreSQL `text` cannot represent it. Invalid Base64 URL input, invalid UTF-8, empty identifiers, NUL, or identifiers over the management limit return `400 INVALID_IDENTIFIER`.

Responses contain both the original identifier and its encoded form where the client needs to construct a link:

```json
{
  "key": "customer:849203",
  "encodedKey": "Y3VzdG9tZXI6ODQ5MjAz"
}
```

Vert.x registers fixed paths before parameterized paths. In particular, `setups/actions/test`, `namespaces/export`, and every `bulk-delete` route are registered before `{setupId}`, `{encodedNamespace}`, or `{encodedKey}` routes that could otherwise capture the fixed segment.

### 3.4 Request size limits

- ordinary JSON request: 1 MiB;
- setup registration/test request: 64 KiB;
- decoded cache value submitted through the console: 10 MiB;
- pub/sub payload: the selected setup's configured limit, normally 7,500 bytes;
- CSV/JSON namespace export response: 10,000 namespaces.

The server counts decoded binary value bytes and UTF-8 text bytes, not Base64 character count. A limit violation returns `413` before the service operation runs.

### 3.5 Correlation

The client may send `X-Request-ID` using a UUID or another 1–128 character printable identifier. The server always returns `X-Correlation-ID`.

If `X-Request-ID` is absent or invalid, the server creates a correlation identifier. Audit events and sanitized server logs include the same identifier.

### 3.6 Pagination

Cursor-paginated endpoints accept:

- `limit`: default `50`, minimum `1`, maximum `200`;
- `cursor`: opaque value returned by the preceding response.

Response envelope:

```json
{
  "items": [],
  "nextCursor": "opaque-or-null",
  "hasMore": false
}
```

Cursors are scoped to the endpoint, setup, namespace, sort, and complete normalized filter set. Reusing a cursor with different parameters returns `400 CURSOR_SCOPE_MISMATCH`. Cursors are not bookmarks and do not promise a historical snapshot.

A cursor is either a cryptographically random reference to server-held state or a tamper-evident value authenticated with a server-held key. Client-provided cursor contents are never trusted as SQL fragments or unchecked query parameters. Cursors expire after 15 minutes; an expired cursor returns `400 INVALID_CURSOR`.

### 3.7 Sorting

V1 supports only documented bounded sorts. Unknown fields or directions return `400 INVALID_SORT`.

Sort query syntax:

```text
sort=key:asc
```

The default keyset order is identifier ascending using a deterministic PostgreSQL `C` collation. Every sort has a unique secondary key. In particular, `entryCount:desc` is ordered by entry count descending and namespace ascending. Cursor fields contain the complete composite sort position.

`prefix` always means a case-sensitive literal prefix. `%`, `_`, and `\` in caller input are escaped before a PostgreSQL `LIKE` predicate is built; the management API does not expose wildcard-pattern syntax.

### 3.8 Entity tags and preconditions

Entry, counter, and lock detail responses return an entity tag derived from their version:

```http
ETag: "v14"
```

Version-checked mutation or deletion accepts:

```http
If-Match: "v14"
```

A missing required precondition returns `428 PRECONDITION_REQUIRED`. A stale precondition returns `412 VERSION_MISMATCH` and does not change the resource.

### 3.9 Mutation retries

The UI may automatically retry only safe reads after connection failure. It does not automatically retry:

- cache set, expire, persist, touch, or delete;
- counter set, increment, decrement, expire, persist, or delete;
- forced lock release;
- pub/sub publish;
- setup lifecycle operations;
- bulk execution.

After an uncertain mutation result, the UI reloads the resource and presents the observed state. Pub/sub delivery remains non-durable and at-most-best-effort; retrying a publish manually can produce a duplicate notification.

## 4. Authentication and authorization

### 4.1 Trusted proxy mode

Production deployments use trusted proxy mode. The server accepts identity headers only when the immediate peer address matches a configured trusted proxy CIDR.

Default trusted headers:

```http
X-PeeGeeQ-User: alex.chen
X-PeeGeeQ-Roles: operator,viewer
```

The proxy must remove client-supplied copies before adding authoritative values. A direct or untrusted request containing these headers returns `401 UNTRUSTED_IDENTITY_SOURCE`.

The server rejects duplicate identity headers. The user value must be 1–128 printable UTF-8 characters after trimming. Roles are comma-separated, trimmed, case-normalized, limited to 16 values and 256 total bytes, and matched only against the configured role allowlist. Invalid identity syntax returns `401 INVALID_IDENTITY` rather than being partially accepted.

### 4.2 Local token mode

Loopback binding is a network restriction, not authentication. A server without trusted proxy mode enabled therefore uses `LOCAL_TOKEN`; there is no anonymous mode.

At startup the server creates a cryptographically random 256-bit, single-use bootstrap token, writes it once directly to the controlling terminal or to an owner-readable configured file without passing through the logging system, and retains only its digest. The token is accepted only over a loopback connection by:

`POST /api/v1/session/local`

Request:

```json
{ "token": "one-time-bootstrap-token" }
```

Successful exchange atomically consumes the bootstrap token, creates an in-memory local session for the fixed `local-operator` identity with `operator` and `viewer` roles, and returns the current-session representation. Replaying a consumed token returns `401 INVALID_BOOTSTRAP_TOKEN`. After logout, expiry, or loss of the only local session, a new login requires server restart or an explicit controlling-process token-regeneration operation; there is no HTTP token-regeneration endpoint.

The session cookie is named `PGQMGMTSESSION` and is `HttpOnly`, `SameSite=Strict`, path-scoped to `/`, and `Secure` whenever HTTPS is used. Local and trusted-proxy management sessions default to a 30-minute idle lifetime and an eight-hour absolute lifetime. Deployment configuration may shorten either lifetime but cannot disable expiry or extend the absolute lifetime beyond 24 hours. Session identifiers rotate after authentication and privilege changes. The bootstrap token and session identifiers are never logged or persisted by the UI. `DELETE /api/v1/session/local` requires the current CSRF proof and invalidates the session.

`LOCAL_TOKEN` refuses non-loopback binding. Startup fails unless exactly one supported authentication mode is configured.

### 4.3 Browser-origin and CSRF policy

The management UI is same-origin by default and CORS is disabled. `LOCAL_TOKEN` always requires a same-origin UI. Trusted proxy mode may explicitly allow cross-origin access through an exact HTTPS origin allowlist; wildcard origins and reflected origins are prohibited when credentials are enabled.

An authenticated `GET /api/v1/session` creates or refreshes the bounded trusted-proxy management session and binds it to the normalized actor, effective roles, authentication mode, and trusted identity source. A later identity or role mismatch invalidates that session. The response returns its random CSRF token and the server sets the same `PGQMGMTSESSION` cookie attributes defined for local-token mode.

Every state-changing REST request except the initial `POST /api/v1/session/local` bootstrap exchange must carry the session's matching `X-PeeGeeQ-CSRF` header and an allowed `Origin`. The bootstrap exchange cannot yet possess CSRF proof, so it instead requires a loopback peer, the exact same-origin UI origin, `application/json`, the single-use bootstrap token, and its dedicated actor/source rate limit; CORS is never enabled for it. Missing or invalid proof on protected requests returns `403 CSRF_VALIDATION_FAILED`. SSE and WebSocket handshakes validate authentication, management session, and `Origin`; WebSocket authentication is never accepted solely from query parameters.

The content-security policy, frame-ancestors policy, referrer policy, and MIME-sniffing protections are emitted by the management server or its trusted proxy. Sensitive identifiers, tokens, and credentials never appear in URLs.

### 4.4 Setup target and TLS policy

UI-session setup registration is disabled unless the server has an explicit outbound database target policy. The policy contains allowed DNS suffixes and/or CIDRs, allowed ports, and whether loopback, private, link-local, and public addresses are permitted. Every resolved address must satisfy the policy on initial test, registration, pool creation, and reconnect. Redirects are not followed.

Resolution and connection form one enforcement operation: the server resolves through its policy-aware resolver, validates the complete answer set, selects an allowed address, and makes the PostgreSQL connection to that exact pinned address. The original configured hostname remains the TLS server name and certificate-verification name. Each new physical connection or reconnect repeats resolution and validation; an address not present in the validated answer set is never used. If the PostgreSQL client cannot preserve pinned-address connection together with original-hostname verification, that target mode is rejected as unsupported rather than falling back to an independently resolved hostname. This prevents the driver from creating a DNS time-of-check/time-of-use gap.

TLS trust is selected by a server-configured `trustProfileId`; requests cannot submit filesystem paths, arbitrary trust stores, or client private keys. `VERIFY_FULL` performs certificate-chain and hostname verification against that profile. Passwords and UI-session credentials remain only in bounded-lifetime in-memory secret holders, are cleared on forget/shutdown, and are never copied into setup summaries or events.

### 4.5 Roles

| Role | Permissions |
|---|---|
| `viewer` | Setup metadata, health, capabilities, namespace/entry/counter/lock metadata, database/runtime monitoring, activity, and pub/sub metadata subscription |
| `operator` | Viewer permissions plus setup session management, sensitive reveal, entry/counter mutations, forced lock release, bulk operations, and pub/sub publish |

At least one recognized role is required. Missing identity returns `401`. An authenticated user without the required role returns `403`.

### 4.6 Current session

`GET /api/v1/session`

Role: authenticated

Response `200`:

```json
{
  "user": "alex.chen",
  "roles": ["operator", "viewer"],
  "serverVersion": "0.1.0",
  "apiVersion": "v1",
  "authenticationMode": "TRUSTED_PROXY",
  "csrfToken": "session-scoped-random-token",
  "sessionIdleExpiresAt": "2026-08-16T11:12:18.000Z",
  "sessionExpiresAt": "2026-08-16T18:42:18.000Z",
  "features": {
    "setupRegistration": true,
    "sensitiveReveal": true
  }
}
```

The response contains no raw proxy headers or group claims and uses `Cache-Control: no-store`. The UI keeps `csrfToken` only in memory and sends it through `X-PeeGeeQ-CSRF` on state-changing requests. Session-expiry fields are UTC instants and are refreshed only within the configured idle/absolute lifetime rules.

## 5. Error contract

Errors use `application/problem+json`:

```json
{
  "type": "https://peegeeq.dev/problems/version-mismatch",
  "title": "Resource version changed",
  "status": 412,
  "code": "VERSION_MISMATCH",
  "detail": "The cache entry changed after it was loaded.",
  "instance": "/api/v1/setups/prod-eu/namespaces/.../entries/...",
  "correlationId": "3dc1d62a-1617-4dd6-a219-c2c38a355817",
  "fieldErrors": []
}
```

`detail` is safe for an operator to see and never includes SQL, stack traces, credentials, values, payloads, or owner tokens.

### 5.1 Status mapping

| Status | Typical codes |
|---|---|
| `400` | `VALIDATION_FAILED`, `INVALID_IDENTIFIER`, `INVALID_CURSOR`, `CURSOR_SCOPE_MISMATCH`, `INVALID_SORT`, `CONFIRMATION_MISMATCH` |
| `401` | `AUTHENTICATION_REQUIRED`, `UNTRUSTED_IDENTITY_SOURCE`, `INVALID_IDENTITY`, `INVALID_BOOTSTRAP_TOKEN`, `SESSION_EXPIRED` |
| `403` | `ROLE_REQUIRED`, `SETUP_ACTION_FORBIDDEN`, `REVEAL_FORBIDDEN`, `CSRF_VALIDATION_FAILED`, `TARGET_FORBIDDEN` |
| `404` | `SETUP_NOT_FOUND`, `ENTRY_NOT_FOUND`, `COUNTER_NOT_FOUND`, `LOCK_NOT_FOUND`, `SUBSCRIPTION_NOT_FOUND`, `MESSAGE_NOT_FOUND` |
| `409` | `SETUP_ALREADY_EXISTS`, `SETUP_STATE_CONFLICT`, `CAPABILITY_UNAVAILABLE`, `SET_MODE_NOT_APPLIED`, `BULK_SCOPE_CONFLICT` |
| `410` | `BULK_PREVIEW_EXPIRED`, `BULK_PREVIEW_USED`, `SUBSCRIPTION_EXPIRED`, `MESSAGE_EXPIRED` |
| `412` | `VERSION_MISMATCH` |
| `413` | `REQUEST_TOO_LARGE`, `PAYLOAD_TOO_LARGE` |
| `415` | `UNSUPPORTED_MEDIA_TYPE` |
| `422` | `JSON_VALUE_INVALID`, `VALUE_TYPE_MISMATCH` |
| `428` | `PRECONDITION_REQUIRED` |
| `429` | `RATE_LIMITED`, `SUBSCRIPTION_LIMIT_REACHED` |
| `500` | `INTERNAL_ERROR` |
| `503` | `SETUP_UNAVAILABLE`, `DATABASE_UNAVAILABLE`, `RUNTIME_STOPPED`, `PUBSUB_UNAVAILABLE`, `AUDIT_UNAVAILABLE`, `AUDIT_OUTCOME_UNAVAILABLE` |

Vert.x failures are handled once by the route error layer. No asynchronous failure is ignored or converted to a successful response.

### 5.2 Atomic mutation outcome mapping

The management service returns a typed outcome from the same PostgreSQL statement or transaction that applies a versioned mutation:

- `APPLIED`, with the resulting representation and version where the resource remains present;
- `NOT_FOUND`;
- `VERSION_MISMATCH`;
- `CONDITION_NOT_MET` for valid create/set conditions that were not satisfied.

The REST layer maps those outcomes to the statuses above. It never performs a follow-up read merely to distinguish missing state from a stale version or to discover the resulting ETag. Revealed values and owner tokens are likewise returned with the version observed by the same database query.

## 6. Common models

### 6.1 Setup summary

```json
{
  "setupId": "prod-eu",
  "displayName": "Production EU",
  "host": "pg-primary.internal",
  "port": 5432,
  "database": "app",
  "schema": "peegee_cache",
  "sslMode": "VERIFY_FULL",
  "source": "CONFIGURED",
  "state": "CONNECTED",
  "schemaState": "READY",
  "lastHealth": {
    "status": "UP",
    "latencyMillis": 18,
    "checkedAt": "2026-08-16T10:42:18.000Z"
  }
}
```

Allowed source values: `CONFIGURED`, `UI_SESSION`.

Allowed setup states: `CONNECTING`, `CONNECTED`, `DETACHING`, `DETACHED`, `UNHEALTHY`.

Passwords and password-presence indicators are never returned.

### 6.2 Cache value request/reveal

Cache values are tagged objects:

```json
{ "type": "STRING", "text": "Avery Chen" }
```

```json
{ "type": "JSON", "text": "{\"customerId\":\"849203\"}" }
```

```json
{ "type": "LONG", "decimal": "9223372036854775807" }
```

```json
{ "type": "BYTES", "base64": "AAECAwQ=" }
```

JSON is transported as UTF-8 text so storage bytes and formatting remain explicit. The server validates JSON syntax before accepting a `JSON` value. Decimal strings must fit a signed Java `long`. Base64 must be canonical and decode within the configured value-size limit.

### 6.3 TTL state

```json
{
  "state": "EXPIRING",
  "ttlMillis": 1421000,
  "expiresAt": "2026-08-16T11:05:59.000Z"
}
```

States:

- `PERSISTENT`: `ttlMillis` and `expiresAt` are null;
- `EXPIRING`: both fields are present;
- `EXPIRED`: `ttlMillis` is `0` and `expiresAt` is present.

## 7. Setup lifecycle API

### 7.1 List setups

`GET /api/v1/setups`

Role: viewer

Response `200`:

```json
{
  "items": [
    {
      "setupId": "prod-eu",
      "displayName": "Production EU",
      "host": "pg-primary.internal",
      "port": 5432,
      "database": "app",
      "schema": "peegee_cache",
      "sslMode": "VERIFY_FULL",
      "source": "CONFIGURED",
      "state": "CONNECTED",
      "schemaState": "READY",
      "lastHealth": null
    }
  ]
}
```

### 7.2 Test unregistered connection

`POST /api/v1/setups/actions/test`

Role: operator

Request:

```json
{
  "host": "127.0.0.1",
  "port": 5432,
  "database": "dev",
  "schema": "peegee_cache",
  "username": "cache_admin",
  "password": "secret",
  "sslMode": "DISABLE",
  "trustProfileId": null,
  "poolMaxSize": 10
}
```

Response `200`:

```json
{
  "databaseReachable": true,
  "schemaState": "READY",
  "migrationVersion": "1",
  "latencyMillis": 21,
  "capabilities": {
    "namespaceInspection": true,
    "expiredEntryInspection": true,
    "counterInspection": true,
    "lockInspection": true,
    "forcedLockRelease": true,
    "bulkEntryDelete": true,
    "bulkCounterDelete": true,
    "pubSub": true,
    "databaseStatistics": true,
    "sensitiveValueReveal": true
  },
  "limits": {
    "pubSubChannelMaxBytes": 49,
    "pubSubPayloadMaxBytes": 7500,
    "maximumValueBytes": 10485760
  }
}
```

The server closes the temporary connection after the response. Passwords are never included in logs or validation details.

### 7.3 Register and connect setup

`POST /api/v1/setups`

Role: operator

Request includes the connection fields above plus:

```json
{
  "setupId": "local-dev",
  "displayName": "Local development",
  "host": "127.0.0.1",
  "port": 5432,
  "database": "dev",
  "schema": "peegee_cache",
  "username": "cache_admin",
  "password": "secret",
  "sslMode": "DISABLE",
  "trustProfileId": null,
  "poolMaxSize": 10
}
```

Response `201`: `SetupSummary` with `source: UI_SESSION` and `state: CONNECTED`.

Registration validates the target policy, TLS trust profile, reachability, and schema readiness before publishing the setup. A failed registration closes all temporary resources, clears submitted secret material, and leaves no registry entry.

### 7.4 Setup details

`GET /api/v1/setups/{setupId}`

Role: viewer

Response `200`:

```json
{
  "setup": {},
  "migrationVersion": "1",
  "runtime": {
    "defaultTtlMillis": null,
    "expirySweeperEnabled": true,
    "expirySweepIntervalMillis": 30000,
    "expirySweepBatchSize": 1000,
    "poolMaxSize": 10
  },
  "registeredAt": "2026-08-16T10:40:00.000Z",
  "connectedAt": "2026-08-16T10:40:01.000Z"
}
```

`setup` is the complete `SetupSummary`. Runtime fields are safe display configuration only. The response excludes usernames, passwords, secret references, trust-store paths, tokens, and pool connection strings.

### 7.5 Connect detached setup

`POST /api/v1/setups/{setupId}/connect`

Role: operator

Response `200`: connected `SetupSummary`.

Only a setup whose configuration remains available can reconnect. Configured setups reload credentials from their secret source. A UI-session setup can reconnect only while its in-memory credentials remain present.

### 7.6 Test registered setup

`POST /api/v1/setups/{setupId}/test`

Role: operator

Tests the stored configuration without changing a currently connected runtime. Response matches the unregistered test result.

### 7.7 Detach setup

`POST /api/v1/setups/{setupId}/detach`

Role: operator

Response `204`.

Detach closes the runtime, pool, pub/sub sessions, SSE streams, and related WebSocket scope. It never drops tables, schemas, or databases.

### 7.8 Forget UI-session setup

`DELETE /api/v1/setups/{setupId}`

Role: operator

Response `204`.

The operation detaches and removes a `UI_SESSION` setup and clears its in-memory credentials. A `CONFIGURED` setup returns `403 SETUP_ACTION_FORBIDDEN`.

### 7.9 Health and capabilities

`GET /api/v1/setups/{setupId}/health`

`GET /api/v1/setups/{setupId}/capabilities`

Role: viewer

Health response:

```json
{
  "status": "UP",
  "schemaReady": true,
  "latencyMillis": 18,
  "checkedAt": "2026-08-16T10:42:18.000Z",
  "detail": "Database reachable and schema ready"
}
```

Capabilities are explicit booleans matching the test response and are accompanied by effective setup-specific limits. The UI hides unsupported destinations and disables unsupported actions, while the server still rejects direct calls with `409 CAPABILITY_UNAVAILABLE`.

## 8. Overview and namespace API

### 8.1 Overview

`GET /api/v1/setups/{setupId}/overview`

Role: viewer

Response:

```json
{
  "scope": "DATABASE",
  "observedAt": "2026-08-16T10:42:18.000Z",
  "health": {
    "status": "UP",
    "schemaReady": true,
    "latencyMillis": 18
  },
  "totals": {
    "namespaceCount": "8",
    "liveEntryCount": "18642",
    "liveCounterCount": "324",
    "activeLockCount": "7",
    "expiredEntryCount": "93",
    "schemaBytes": "40475034"
  },
  "expiry": {
    "oldestExpiredRowLagMillis": 38000,
    "sweeperEnabled": true,
    "lastSweepAt": "2026-08-16T10:42:00.000Z",
    "lastSweepDeletedRows": "417"
  },
  "valueTypeCounts": {
    "STRING": "5332",
    "JSON": "10218",
    "LONG": "1731",
    "BYTES": "1361"
  },
  "topNamespaces": []
}
```

Overview does not return time-series history. The UI builds a bounded chart from periodic snapshots received during the current console session.

### 8.2 List namespaces

`GET /api/v1/setups/{setupId}/namespaces`

Role: viewer

Query parameters:

- `prefix`;
- `status`: `ALL`, `HEALTHY`, `EXPIRED_BACKLOG`, `ACTIVE_LOCKS`;
- `sort`: `namespace:asc` or `entryCount:desc`;
- `cursor`, `limit`.

Namespace item:

```json
{
  "namespace": "customer-profile",
  "encodedNamespace": "Y3VzdG9tZXItcHJvZmlsZQ",
  "liveEntryCount": "8921",
  "liveCounterCount": "18",
  "activeLockCount": "2",
  "expiringEntryCount": "6404",
  "expiredEntryCount": "0",
  "estimatedStorageBytes": "16567501",
  "observedAt": "2026-08-16T10:42:18.000Z"
}
```

### 8.3 Namespace details

`GET /api/v1/setups/{setupId}/namespaces/{encodedNamespace}`

Role: viewer

Returns one namespace item plus TTL distribution and value-type counts. An empty but explicitly requested namespace returns zero counts rather than `404`; namespaces are logical and do not require registration.

### 8.4 Export namespace metadata

`GET /api/v1/setups/{setupId}/namespaces/export`

Role: viewer

Query parameters match namespace listing except cursor and limit. Maximum export is 10,000 namespaces. The `Accept` header selects CSV or JSON.

The export contains metadata only. It never contains keys, values, owner tokens, credentials, or audit records.

## 9. Cache entry API

### 9.1 List entries

`GET /api/v1/setups/{setupId}/namespaces/{encodedNamespace}/entries`

Role: viewer

Query parameters:

- `prefix`;
- `valueType`: `STRING`, `JSON`, `LONG`, `BYTES`;
- `ttlState`: `ALL_LIVE`, `PERSISTENT`, `EXPIRING`, `INCLUDE_EXPIRED`;
- `cursor`, `limit`;
- `sort`: V1 supports only `key:asc`.

Entry metadata:

```json
{
  "namespace": "customer-profile",
  "encodedNamespace": "Y3VzdG9tZXItcHJvZmlsZQ",
  "key": "customer:849203",
  "encodedKey": "Y3VzdG9tZXI6ODQ5MjAz",
  "valueType": "JSON",
  "sizeBytes": "1843",
  "version": "14",
  "createdAt": "2026-08-16T09:18:44.000Z",
  "updatedAt": "2026-08-16T10:42:06.183Z",
  "ttl": {
    "state": "EXPIRING",
    "ttlMillis": 1421000,
    "expiresAt": "2026-08-16T11:05:59.000Z"
  }
}
```

The response never contains a value, value preview, hash, `hitCount`, or `lastAccessedAt`.

### 9.2 Entry metadata

`GET /api/v1/setups/{setupId}/namespaces/{encodedNamespace}/entries/{encodedKey}`

Role: viewer

Response `200`: entry metadata and `ETag: "v14"`.

Query `includeExpired=true` is operator-independent but requires the `expiredEntryInspection` capability. Default is live entries only.

### 9.3 Reveal value

`POST /api/v1/setups/{setupId}/namespaces/{encodedNamespace}/entries/{encodedKey}/value/reveal`

Role: operator

Optional request:

```json
{
  "reason": "Investigating customer profile refresh"
}
```

When supplied, `reason` is trimmed, 3–240 characters, and included in sanitized audit metadata. An omitted reason is recorded as `INTERACTIVE_CONSOLE_REVEAL`.

Response `200`:

```json
{
  "key": "customer:849203",
  "version": "14",
  "value": {
    "type": "JSON",
    "text": "{\"customerId\":\"849203\"}"
  },
  "revealedAt": "2026-08-16T10:43:12.000Z",
  "autoHideAfterMillis": 60000
}
```

Headers:

```http
Cache-Control: no-store, no-cache, must-revalidate
Pragma: no-cache
```

### 9.4 Create or update entry

`PUT /api/v1/setups/{setupId}/namespaces/{encodedNamespace}/entries/{encodedKey}`

Role: operator

Request:

```json
{
  "value": {
    "type": "JSON",
    "text": "{\"customerId\":\"849203\"}"
  },
  "ttlMode": "PRESERVE_EXISTING",
  "ttlMillis": null,
  "setMode": "ONLY_IF_VERSION_MATCHES"
}
```

Entry TTL modes:

| TTL mode | Behavior |
|---|---|
| `PRESERVE_EXISTING` | Retain the current expiry; valid only for an existing entry |
| `USE_DEFAULT` | Apply the setup runtime's default TTL, or persist when no default exists |
| `REPLACE` | Replace expiry using required positive `ttlMillis` |
| `REMOVE` | Store the entry persistently; `ttlMillis` must be null |

The management service applies the value, set condition, expected version, and TTL mode atomically. It never implements `PRESERVE_EXISTING` as a read followed by a separate write.

Modes:

| Mode | Required precondition |
|---|---|
| `UPSERT` | none |
| `ONLY_IF_ABSENT` | `If-None-Match: *` |
| `ONLY_IF_PRESENT` | `If-Match: *` |
| `ONLY_IF_VERSION_MATCHES` | `If-Match: "v{version}"` |

Response `200` for update or `201` for creation:

```json
{
  "applied": true,
  "created": false,
  "version": "15",
  "updatedAt": "2026-08-16T10:44:00.000Z",
  "ttl": {
    "state": "EXPIRING",
    "ttlMillis": 1800000,
    "expiresAt": "2026-08-16T11:14:00.000Z"
  }
}
```

A condition that is valid but not met returns `409 SET_MODE_NOT_APPLIED`. The response never returns the previous value.

### 9.5 Delete entry

`DELETE /api/v1/setups/{setupId}/namespaces/{encodedNamespace}/entries/{encodedKey}`

Role: operator

Requires exact `If-Match`. Response `204`. Missing entry returns `404`; stale version returns `412`.

### 9.6 TTL, persistence, and touch

`POST /api/v1/setups/{setupId}/namespaces/{encodedNamespace}/entries/{encodedKey}/ttl`

```json
{ "ttlMillis": 1800000 }
```

`POST /api/v1/setups/{setupId}/namespaces/{encodedNamespace}/entries/{encodedKey}/persist`

No body.

`POST /api/v1/setups/{setupId}/namespaces/{encodedNamespace}/entries/{encodedKey}/touch`

```json
{ "refreshTtlMillis": 1800000 }
```

`refreshTtlMillis` may be null to retain the current expiry. Each operation requires exact `If-Match`. Expire and persist increment the entry version and return the resulting metadata and ETag. Touch updates `lastAccessedAt`, `updatedAt`, and optionally the expiry without changing the entry version; a successful touch therefore returns updated metadata with the same ETag. Each version check and update is atomic.

### 9.7 Bulk entry deletion preview

`POST /api/v1/setups/{setupId}/namespaces/{encodedNamespace}/entries/bulk-delete/preview`

Role: operator

Request supports exactly one selection mode.

Explicit selection:

```json
{
  "selection": {
    "type": "EXPLICIT",
    "targets": [
      { "key": "customer:849203", "version": "14" },
      { "key": "customer:849204", "version": "5" }
    ]
  }
}
```

Filter selection:

```json
{
  "selection": {
    "type": "FILTER",
    "prefix": "customer:inactive:",
    "valueType": "JSON",
    "ttlState": "INCLUDE_EXPIRED"
  }
}
```

Explicit selection allows 1–1,000 targets. Filter selection resolves at most 10,000 targets. A larger result returns `409 BULK_SCOPE_CONFLICT` and requires a narrower filter.

Response `200`:

```json
{
  "previewToken": "opaque-single-use-token",
  "expiresAt": "2026-08-16T10:49:00.000Z",
  "setupId": "prod-eu",
  "namespace": "customer-profile",
  "resolvedCount": "2",
  "totalBytes": "3378",
  "sampleKeys": ["customer:849203", "customer:849204"],
  "confirmationPhrase": "DELETE customer-profile"
}
```

The server stores the exact `(key, version)` target set with the token. Only a digest of the random token is stored. The token is scoped to actor, setup, and namespace and expires after five minutes.

### 9.8 Execute bulk entry deletion

`POST /api/v1/setups/{setupId}/namespaces/{encodedNamespace}/entries/bulk-delete/execute`

Role: operator

Request:

```json
{
  "previewToken": "opaque-single-use-token",
  "confirmationPhrase": "DELETE customer-profile"
}
```

Response `200`:

```json
{
  "processedCount": "2",
  "deletedCount": "1",
  "conflictCount": "1",
  "missingCount": "0",
  "failedCount": "0",
  "conflicts": [
    { "key": "customer:849204", "reason": "VERSION_CHANGED" }
  ]
}
```

Changed entries are not deleted. The token becomes used before execution begins and cannot be replayed, including after partial failure.

## 10. Counter API

### 10.1 List counters

`GET /api/v1/setups/{setupId}/counters`

Role: viewer

Query parameters: `namespace`, `prefix`, `ttlState`, `cursor`, `limit`, `sort=key:asc`.

Counter item:

```json
{
  "namespace": "pricing",
  "encodedNamespace": "cHJpY2luZw",
  "key": "quote-sequence",
  "encodedKey": "cXVvdGUtc2VxdWVuY2U",
  "value": "184293",
  "version": "184293",
  "createdAt": "2026-08-01T00:00:00.000Z",
  "updatedAt": "2026-08-16T10:42:16.000Z",
  "ttl": { "state": "PERSISTENT", "ttlMillis": null, "expiresAt": null }
}
```

Counter values are numeric operational state and are not treated as masked cache payloads.

### 10.2 Counter details

`GET /api/v1/setups/{setupId}/namespaces/{encodedNamespace}/counters/{encodedKey}`

Role: viewer

Response includes the counter item and ETag.

### 10.3 Set counter

`PUT /api/v1/setups/{setupId}/namespaces/{encodedNamespace}/counters/{encodedKey}`

Role: operator

Request:

```json
{
  "value": "184293",
  "ttlMode": "PRESERVE_EXISTING",
  "ttlMillis": null
}
```

TTL modes match `CounterTtlMode`: `PRESERVE_EXISTING`, `REPLACE`, and `REMOVE`. `REPLACE` requires positive `ttlMillis`; the other modes require null. `PRESERVE_EXISTING` on creation produces a persistent counter. If updating an observed counter, `If-Match` is required. Creation uses `If-None-Match: *`.

Creation returns `201`; update returns `200`. Both return the resulting counter item and ETag. A valid wildcard condition that is not met returns `409 SET_MODE_NOT_APPLIED`; an exact stale version returns `412 VERSION_MISMATCH`; a missing row for an exact update returns `404 COUNTER_NOT_FOUND`.

### 10.4 Increment or decrement

`POST /api/v1/setups/{setupId}/namespaces/{encodedNamespace}/counters/{encodedKey}/increment`

Role: operator

```json
{
  "delta": "10",
  "createIfMissing": false,
  "ttlMode": "PRESERVE_EXISTING",
  "ttlMillis": null
}
```

A negative delta decrements. Zero is rejected. `createIfMissing` defaults to false for management safety. TTL mode follows `CounterTtlMode`; `REPLACE` requires positive `ttlMillis`. Response `200` returns the resulting counter item and ETag. The UI never automatically retries this operation.

An adjustment of an existing counter requires exact `If-Match`; a stale version returns `412`. A missing existing-only adjustment returns `404 COUNTER_NOT_FOUND`. When `createIfMissing=true`, creation requires `If-None-Match: *`; a wildcard condition that is not met returns `409 SET_MODE_NOT_APPLIED`. The delta, creation condition, expected version, TTL mode, resulting value, and resulting version are evaluated atomically.

### 10.5 Counter TTL and deletion

`POST /api/v1/setups/{setupId}/namespaces/{encodedNamespace}/counters/{encodedKey}/ttl` with `{ "ttlMillis": 60000 }`.

`POST /api/v1/setups/{setupId}/namespaces/{encodedNamespace}/counters/{encodedKey}/persist` with no body.

`DELETE /api/v1/setups/{setupId}/namespaces/{encodedNamespace}/counters/{encodedKey}` requires exact `If-Match`.

Role: operator.

TTL and persist return `200` with the resulting counter item and ETag. Delete returns `204`. Each operation distinguishes `404 COUNTER_NOT_FOUND` from `412 VERSION_MISMATCH` atomically.

### 10.6 Selected counter deletion

`POST /api/v1/setups/{setupId}/counters/bulk-delete/preview`

`POST /api/v1/setups/{setupId}/counters/bulk-delete/execute`

Role: operator

V1 accepts only explicit `(namespace, key, version)` targets, maximum 1,000. It uses the same five-minute, actor-scoped, single-use preview rules as entry deletion. Confirmation phrase is `DELETE {count} COUNTERS`. There is no filter-wide counter deletion in V1.

## 11. Lock API

### 11.1 List active locks

`GET /api/v1/setups/{setupId}/locks`

Role: viewer

Query parameters: `namespace`, `prefix`, `leaseState=ACTIVE|EXPIRING_SOON`, `cursor`, `limit`.

Lock metadata:

```json
{
  "namespace": "customer-profile",
  "encodedNamespace": "Y3VzdG9tZXItcHJvZmlsZQ",
  "key": "rebuild:eu",
  "encodedKey": "cmVidWlsZDpldQ",
  "fencingToken": "48201",
  "version": "3",
  "createdAt": "2026-08-16T10:40:00.000Z",
  "updatedAt": "2026-08-16T10:42:14.000Z",
  "leaseExpiresAt": "2026-08-16T10:43:00.000Z",
  "leaseRemainingMillis": 42000,
  "owner": { "state": "MASKED" }
}
```

The list contains active locks only and never contains an owner token.

### 11.2 Lock details

`GET /api/v1/setups/{setupId}/namespaces/{encodedNamespace}/locks/{encodedKey}`

Role: viewer

Response includes lock metadata and ETag.

### 11.3 Reveal owner

`POST /api/v1/setups/{setupId}/namespaces/{encodedNamespace}/locks/{encodedKey}/owner/reveal`

Role: operator

An optional request may provide a 3–240 character reason. Response uses `no-store` and contains owner token, current version, reveal time, and auto-hide duration. It emits a sanitized audit event.

### 11.4 Force release

`POST /api/v1/setups/{setupId}/namespaces/{encodedNamespace}/locks/{encodedKey}/force-release`

Role: operator

Requires exact `If-Match` and request:

```json
{
  "confirmationKey": "rebuild:eu",
  "reason": "Worker was terminated and cannot release its lease"
}
```

The confirmation must exactly match the decoded key. `reason` is optional and, when supplied, must contain 3–240 characters. The PostgreSQL operation deletes only the row whose namespace, key, and version match. Response `204`; stale version returns `412`.

The management API does not expose lock acquire or renew in V1.

## 12. Pub/Sub API

### 12.1 Create console subscription

`POST /api/v1/setups/{setupId}/pubsub/subscriptions`

Role: viewer

Request:

```json
{
  "channel": "cache-invalidation",
  "bufferLimit": 200
}
```

The fully qualified PostgreSQL channel is `{configuredPrefix}__{channel}` and must not exceed PostgreSQL's 63-byte identifier limit. The caller-supplied channel must therefore contain 1 through `63 - utf8Bytes(configuredPrefix) - 2` UTF-8 bytes and must not contain NUL. A configured prefix that leaves no byte for a channel is rejected during setup validation. The effective maximum is returned by the setup capabilities endpoint. `bufferLimit` defaults to 200 and is capped at 500.

Response `201`:

```json
{
  "subscriptionId": "01K2VD2M4M8P9G7D3A2S5F6H1J",
  "channel": "cache-invalidation",
  "streamPath": "/api/v1/setups/prod-eu/pubsub/subscriptions/01K2VD2M4M8P9G7D3A2S5F6H1J/stream",
  "bufferLimit": 200,
  "createdAt": "2026-08-16T10:42:00.000Z",
  "expiresAt": "2026-08-16T11:42:00.000Z"
}
```

The subscription belongs to the authenticated actor and setup. Another actor receives `404` rather than learning it exists.

### 12.2 Stream messages

`GET /api/v1/setups/{setupId}/pubsub/subscriptions/{subscriptionId}/stream`

Role: owning viewer/operator

The SSE stream sends message metadata only:

```text
id: 71
event: pubsub.message
data: {"messageId":"01K2VD...","channel":"cache-invalidation","contentType":null,"payloadBytes":142,"receivedAt":"2026-08-16T10:42:17.000Z","payloadState":"MASKED"}

```

The server sends a `ready` event first and a comment heartbeat every 15 seconds. `Last-Event-ID` resumes from the bounded session buffer. If the requested event is no longer available, the stream sends `reset` with the oldest available identifier.

Native PostgreSQL `NOTIFY` carries only channel and payload. It does not carry `contentType`; therefore V1 reports `contentType: null` for received messages and does not infer a type from payload contents. A future typed envelope requires an explicit capability and wire-version decision so native subscribers are not silently broken.

### 12.3 Reveal retained payload

`POST /api/v1/setups/{setupId}/pubsub/subscriptions/{subscriptionId}/messages/{messageId}/payload/reveal`

Role: owning operator

An optional request may provide a 3–240 character reveal reason. Response contains the payload string, nullable content type, received time, encoding (`UTF8`), and `no-store` headers. A message removed from the bounded buffer returns `410 MESSAGE_EXPIRED`.

### 12.4 Stop subscription

`DELETE /api/v1/setups/{setupId}/pubsub/subscriptions/{subscriptionId}`

Role: owning viewer/operator

Response `204`. It closes LISTEN resources, stream clients, and the message buffer.

Disconnected sessions remain resumable for five minutes. A session expires after one hour even while used and must then be recreated.

Resource limits are configurable with conservative defaults: at most 5 active console subscriptions per actor, 100 per setup, and 500 per management process. Each subscription evicts oldest messages when either its entry limit or its 1 MiB retained-payload budget is reached; the process-wide retained-payload budget defaults to 64 MiB. A slow SSE client never blocks the PostgreSQL notification handler. Crossing a subscription quota returns `429 SUBSCRIPTION_LIMIT_REACHED`; buffer eviction is reported through the existing `reset` behavior.

### 12.5 Publish

`POST /api/v1/setups/{setupId}/pubsub/publish`

Role: operator

```json
{
  "channel": "cache-invalidation",
  "payload": "{\"namespace\":\"customer-profile\",\"key\":\"customer:849203\"}"
}
```

The UTF-8 encoded payload must not exceed the setup's configured pub/sub payload limit. Response `200`:

```json
{
  "accepted": true,
  "publishedAt": "2026-08-16T10:44:00.000Z"
}
```

`accepted` means PostgreSQL accepted `pg_notify`; it is not a listener count or delivery guarantee. The existing cache pub/sub service resolves to `1` on successful publication, but the REST contract does not misrepresent that implementation sentinel as a recipient count. The client does not retry automatically.

## 13. Monitoring and activity API

### 13.1 Database monitoring

`GET /api/v1/setups/{setupId}/monitoring/database`

Role: viewer

Response sections:

- health and schema readiness;
- cache table/index/schema bytes;
- live and expired row counts;
- dead tuples and last vacuum/autovacuum;
- database and cache-tagged connection counts;
- expiry backlog and oldest-row lag.

Permission-dependent field:

```json
{
  "databaseConnections": {
    "availability": "UNAVAILABLE",
    "reason": "INSUFFICIENT_DATABASE_PRIVILEGE",
    "value": null
  }
}
```

Unavailable information is never represented as zero.

### 13.2 Management runtime monitoring

`GET /api/v1/setups/{setupId}/monitoring/runtime`

Role: viewer

Response `200`:

```json
{
  "scope": "MANAGEMENT_RUNTIME",
  "observedAt": "2026-08-16T10:42:18.000Z",
  "started": true,
  "pool": {
    "active": "2",
    "idle": "8",
    "pending": "0",
    "maximum": "10"
  },
  "activeOperations": "1",
  "pubSubSubscriptions": "3",
  "sseClients": "4",
  "webSocketClients": "1",
  "retainedPayloadBytes": "8192",
  "auditQueue": {
    "depth": "0",
    "capacity": "4096",
    "acceptingMutations": true
  },
  "expirySweeper": {
    "ownedByRuntime": true,
    "running": true,
    "lastSweepAt": "2026-08-16T10:42:00.000Z"
  },
  "operations": []
}
```

`operations` contains bounded operation-name/status/count/error-count/latency aggregates for this management process only. It contains no actor, setup, namespace, key, channel, cursor, value, payload, owner, credential, or other unbounded telemetry dimension. Pool fields use an availability wrapper when the selected Vert.x client cannot expose a value; unavailable values are never encoded as zero.

### 13.3 Metrics SSE

`GET /api/v1/setups/{setupId}/sse/metrics`

Role: viewer

Events:

- `ready`;
- `overview.snapshot` every 15 seconds;
- `runtime.snapshot` every 15 seconds;
- `health.changed` immediately;
- `reset` when resume history is unavailable.

The server sends comment heartbeat every 15 seconds and supports `Last-Event-ID` over a bounded five-minute event buffer.

### 13.4 Recent activity

`GET /api/v1/setups/{setupId}/activity`

Role: viewer

Query: `after`, `limit` default 50/max 200, optional `namespace`, `action`, and `outcome`.

Activity event:

```json
{
  "eventId": "01K2VD6DA7G4EJ4CQG3ST6JN5M",
  "occurredAt": "2026-08-16T10:44:00.000Z",
  "actor": "alex.chen",
  "action": "ENTRY_VALUE_REVEALED",
  "outcome": "SUCCEEDED",
  "setupId": "prod-eu",
  "namespace": "customer-profile",
  "resource": {
    "type": "CACHE_ENTRY",
    "identifier": "customer:849203"
  },
  "summary": "Cache entry value revealed",
  "correlationId": "3dc1d62a-1617-4dd6-a219-c2c38a355817"
}
```

The bounded activity API is a convenience view from the current management process. It may return raw identifiers to an authorized viewer because it is setup-scoped, but those raw identifiers are not copied into structured server logs. The protected structured audit stream is the authoritative audit output.

## 14. Monitoring WebSocket

Connection:

```text
/ws/monitoring?setupId=prod-eu&afterEventId=01K2VD...
```

The HTTP upgrade passes through the same proxy authentication and viewer authorization. Origin checks apply.

### 14.1 Envelope

```json
{
  "eventId": "01K2VD6DA7G4EJ4CQG3ST6JN5M",
  "type": "activity.created",
  "occurredAt": "2026-08-16T10:44:00.000Z",
  "setupId": "prod-eu",
  "data": {}
}
```

Types:

- `connection.ready`;
- `setup.state.changed`;
- `health.changed`;
- `activity.created`;
- `resource.changed`;
- `stream.reset`;
- `server.shutting_down`.

`resource.changed` data contains resource type, namespace, identifier, action, resulting version when applicable, actor, outcome, and correlation identifier. It never contains values, payloads, owner tokens, or credentials.

V1 emits `resource.changed` only for mutations executed through this management server. Writes made by application processes, native SQL callers, or another management-server instance are not represented as real-time resource events. Periodic database snapshots remain the database-wide truth until an explicit cross-process change-capture capability is designed.

### 14.2 Resume and liveness

- Server sends WebSocket ping every 20 seconds and closes clients that do not answer within 10 seconds.
- The browser reconnects with bounded exponential backoff and jitter.
- `afterEventId` resumes from a bounded five-minute event buffer.
- If unavailable, the server sends `stream.reset`; the UI invalidates relevant RTK Query tags and reloads snapshots.
- Detaching a setup sends `setup.state.changed` followed by a normal close with a typed reason.

## 15. Redaction and audit

### 15.1 Structured audit fields

Every sensitive reveal and mutation writes:

- event/correlation identifier;
- UTC timestamp;
- actor and effective role;
- action and outcome;
- setup and resource type;
- bounded, versioned HMAC-SHA-256 fingerprints of namespaces, keys, channels, prefixes, cursors, and other user-controlled identifiers, truncated to at least 128 bits and produced with a server-held audit-fingerprint key;
- expected and resulting version when applicable;
- non-sensitive reason;
- sanitized failure code;
- request source address as observed after trusted proxy processing.

Never audit:

- database password or username;
- cache value or derived preview/hash;
- pub/sub payload;
- lock owner token;
- raw identity/authorization headers;
- SQL text containing request data;
- stack trace in the structured event.

Raw identifiers may appear in the authorized in-memory activity response and WebSocket event, but never in the authoritative structured audit stream. Audit fingerprints contain a non-secret key identifier so a controlled key rotation can be distinguished, but they never contain the HMAC key. The existing unkeyed `SafeLogValue.identifier` helper remains suitable only for correlating identifiers already classified as non-secret in ordinary operational logs; it is not the management audit fingerprinter. A separately secured audit sink may retain raw identifiers only through an explicit deployment policy, independent access controls, retention rules, and tests; it is not the default logging behavior.

Security audit emission is distinct from optional metrics and traces. Before a reveal or mutation begins, the configured audit sink must durably accept the bounded intent and reserve capacity for exactly one terminal outcome; otherwise the request fails closed with `503 AUDIT_UNAVAILABLE`. A successful reservation guarantees that the terminal outcome cannot later be rejected because the queue is full.

After the database operation, the server completes the reservation with `SUCCEEDED`, `REJECTED`, `FAILED`, or `UNKNOWN`. Intent and outcome share one immutable event identifier, and completion is idempotent. A clean shutdown drains completed outcomes before closing the sink. On restart, recovery converts every durable intent without a terminal outcome into `UNKNOWN` before accepting new mutations. An unexpected persistence failure after the database may have committed produces an uncertain `503 AUDIT_OUTCOME_UNAVAILABLE`, marks management mutation readiness down, and blocks further reveals/mutations until the audit sink recovers; the client follows the uncertain-mutation reload rule from section 3.9. Metrics or tracing exporter failures remain isolated from product behavior and do not satisfy this audit requirement.

### 15.2 Reveal rate and expiry

Reveal endpoints are subject to configurable per-user limits. Setup tests, connection attempts, bulk previews, pub/sub publishing, and subscription creation also have actor and source-address rate limits. A revealed response is never cached by the server or browser. The client clears it on timeout, navigation, setup/namespace change, and document visibility timeout.

### 15.3 Logging middleware

Request logging records method, route template, status, duration, actor, setup, correlation identifier, and safe error code. It logs route templates rather than raw encoded identifiers where possible and never logs request or response bodies for sensitive or mutation routes.

## 16. Java API contract

The existing `CacheService`, `CounterService`, `LockService`, `ScanService`, `PubSubService`, and `AdminService` remain unchanged. Application behavior is not expanded or weakened to satisfy browser administration.

A new typed `ManagementService` provides database inspection, sensitive reads, and concurrency-safe administrative mutations:

```java
AdminCapabilities capabilities();

Future<AdminPage<NamespaceStats>> namespaces(NamespaceQuery query);

Future<ManagementEntryMetadata> entry(CacheKey key, boolean includeExpired);

Future<RevealedEntryValue> revealEntry(
    RevealEntryRequest request, ManagementActionContext context);

Future<ManagementSetResult> setEntry(
    ManagementCacheSetRequest request, ManagementActionContext context);

Future<VersionedMutationResult<ManagementEntryMetadata>> expireEntry(
    VersionedEntryTtlRequest request, ManagementActionContext context);

Future<VersionedMutationResult<ManagementEntryMetadata>> persistEntry(
    VersionedCacheKeyRequest request, ManagementActionContext context);

Future<VersionedMutationResult<ManagementEntryMetadata>> touchEntry(
    VersionedEntryTouchRequest request, ManagementActionContext context);

Future<VersionedMutationResult<Void>> deleteEntry(
    VersionedEntryDeleteRequest request, ManagementActionContext context);

Future<AdminPage<CounterEntry>> counters(CounterQuery query);

Future<CounterEntry> counter(CacheKey key);

Future<VersionedMutationResult<CounterEntry>> setCounter(
    ManagementCounterSetRequest request, ManagementActionContext context);

Future<VersionedMutationResult<CounterEntry>> adjustCounter(
    ManagementCounterAdjustRequest request, ManagementActionContext context);

Future<VersionedMutationResult<CounterEntry>> expireCounter(
    VersionedCounterTtlRequest request, ManagementActionContext context);

Future<VersionedMutationResult<CounterEntry>> persistCounter(
    VersionedCacheKeyRequest request, ManagementActionContext context);

Future<VersionedMutationResult<Void>> deleteCounter(
    VersionedCounterDeleteRequest request, ManagementActionContext context);

Future<AdminPage<LockState>> locks(LockQuery query);

Future<RevealedLockOwner> revealLockOwner(
    RevealLockOwnerRequest request, ManagementActionContext context);

Future<VersionedMutationResult<Void>> forceReleaseLock(
    ForceReleaseLockRequest request, ManagementActionContext context);

Future<DatabaseStats> databaseStats();

Future<ExpiryStats> expiryStats();

Future<BulkDeletePreview> previewEntryDelete(
    EntryDeleteFilter filter, ManagementActionContext context);

Future<BulkDeleteResult> executeEntryDelete(
    ConfirmedEntryDelete request, ManagementActionContext context);

Future<BulkDeletePreview> previewCounterDelete(
    CounterDeleteSelection selection, ManagementActionContext context);

Future<BulkDeleteResult> executeCounterDelete(
    ConfirmedCounterDelete request, ManagementActionContext context);
```

`ManagementCacheSetRequest` carries `CacheKey`, `CacheValue`, `SetMode`, expected version, `EntryTtlMode`, and optional TTL. `EntryTtlMode` contains `PRESERVE_EXISTING`, `USE_DEFAULT`, `REPLACE`, and `REMOVE`. Management counter requests use the existing `CounterTtlMode` values and carry an expected version where the REST contract requires `If-Match`.

`VersionedMutationResult<T>` carries a `ManagementMutationOutcome` (`APPLIED`, `NOT_FOUND`, `VERSION_MISMATCH`, or `CONDITION_NOT_MET`), the resulting version when one exists, and the resulting representation for operations that leave a resource present. `ManagementSetResult` additionally records whether the entry was created. PostgreSQL produces each outcome and resulting version atomically; the REST layer does not infer them through a second query.

`RevealedEntryValue` contains key, value, version, and reveal time. `RevealedLockOwner` contains lock key, owner token, version, and reveal time. Their value and version come from one database snapshot.

`ManagementActionContext` contains the authenticated actor, bounded effective roles, correlation identifier, and sanitized source address. Reveal request types also carry the optional bounded reason. REST authentication middleware constructs the context; callers cannot populate it from request JSON. Embedded non-REST callers must provide an authenticated system or user identity. Sensitive reveals, mutations, and actor-bound bulk operations cannot be invoked without this context.

The `PeeGeeCache` interface gains a backward-compatible default `management()` accessor returning an unsupported service. PostgreSQL returns the complete implementation. `capabilities()` reports support before invocation, and unsupported methods return a failed `Future` with a typed unsupported-capability exception.

The PostgreSQL management implementation receives a required `ManagementAuditSink` and enforces durable audit reservation before every context-requiring operation, including calls made without REST:

```java
interface ManagementAuditSink {
    Future<ManagementAuditReservation> reserveIntent(ManagementAuditIntent event);
    Future<Void> complete(
        ManagementAuditReservation reservation,
        ManagementAuditOutcome outcome);
}
```

REST-specific services remain outside the cache library API:

```java
interface CacheSetupRegistry {
    Future<SetupConnectionTest> test(
        SetupConnectionRequest request, ManagementActionContext context);
    Future<SetupHandle> register(
        SetupRegistrationRequest request, ManagementActionContext context);
    Future<SetupHandle> connect(
        String setupId, ManagementActionContext context);
    Future<Void> detach(
        String setupId, ManagementActionContext context);
    Future<Void> forget(
        String setupId, ManagementActionContext context);
    Future<List<SetupSummary>> list();
    Future<SetupHandle> requireConnected(String setupId);
}

interface ManagementEventService {
    void publish(ManagementEvent event);
    List<ManagementEvent> recent(ActivityQuery query);
}
```

`ManagementAuditReservation` contains only an opaque reservation identifier, audit event identifier, and non-secret sink generation. Reserving an intent durably records it and reserves terminal-outcome capacity. `complete` is idempotent for the same terminal outcome and rejects a conflicting second completion. REST-owned mutations, including setup lifecycle and pub/sub resource operations, use the same reservation protocol through an audit coordinator.

`ManagementEventService` drives bounded UI activity and is not the authoritative audit sink. `ManagementAuditSink` implements the fail-closed reservation, recovery, and terminal-outcome behavior from section 15. `SetupHandle` exposes typed cache services and safe setup metadata but not credentials. REST handlers never receive a raw password after registration.

## 17. Screen-to-API coverage

| Screen or interaction | API coverage |
|---|---|
| Header identity and role | `GET /session` |
| Local-token sign-in/sign-out | `POST /session/local`, `DELETE /session/local` |
| Setup selector | `GET /setups` |
| Register/test setup dialog | `POST /setups/actions/test`, `POST /setups` |
| Connect detached setup | `POST /setups/{id}/connect` |
| Detach/forget setup | `POST /setups/{id}/detach`, `DELETE /setups/{id}` |
| Setup details/capabilities | `GET /setups/{id}`, `/health`, `/capabilities` |
| Overview cards and namespace summary | `GET /setups/{id}/overview`, `/namespaces` |
| Overview live updates | `GET /setups/{id}/sse/metrics`, `/ws/monitoring` |
| Namespace table and export | `GET /namespaces`, `GET /namespaces/export` |
| Key Browser | `GET /namespaces/{ns}/entries` |
| Key Details | entry metadata, reveal, PUT, DELETE, TTL, persist, touch |
| Selected/filter entry deletion | bulk entry preview and execute |
| Counters | list/detail, set, increment, TTL, persist, delete |
| Selected counter deletion | bulk counter preview and execute |
| Locks | list/detail, owner reveal, force release |
| Pub/Sub subscription | create, SSE stream, payload reveal, delete |
| Pub/Sub publish | `POST /pubsub/publish` |
| Monitoring | database/runtime endpoints and metrics SSE |
| Notification drawer | monitoring WebSocket |
| Recent activity | `GET /activity` and `activity.created` events |
| Settings connectivity | `/session`, setup health, capabilities, live connection state |
| Display preferences | Browser-local; no server API |

Every control in the approved mockups is covered. V1 intentionally has no API for database/schema drop, namespace-wide multi-resource purge, bulk lock release, lock acquisition/renewal, or persistent user preferences.

## 18. Contract verification

### 18.1 Backend contract tests

Use JUnit, Vert.x test support, and real PostgreSQL Testcontainers. Mockito and substitute mocking frameworks are prohibited.

Tests cover:

- every route's local-token/trusted-proxy authentication and role requirements, including duplicate or malformed identity headers;
- same-origin, configured CORS, CSRF, SSE-origin, and WebSocket-origin enforcement;
- setup target policy across initial resolution, exact-address connection, pool creation, and reconnect, including disallowed CIDRs, DNS rebinding, ports, original-hostname TLS verification, and trust profiles;
- request validation and problem response schema;
- decimal-string encoding for every 64-bit value;
- Base64 URL identifier round trips, NUL rejection, literal prefix handling, and invalid inputs;
- cursor integrity, expiry, complete filter scoping, deterministic composite sorts, and page boundaries;
- ETag generation, required preconditions, stale-version behavior, and atomic typed outcome mapping without diagnostic follow-up reads;
- complete redaction of values, payloads, owner tokens, and credentials;
- reveal no-store headers, value-and-version snapshot consistency, durable audit reservation/outcome recovery, and keyed identifier fingerprinting;
- setup cleanup on failure, detach, forget, and shutdown;
- exact cache/counter/lock behavior against PostgreSQL;
- bulk preview scope, actor binding, expiry, single use, conflicts, and partial failure;
- PostgreSQL-compatible pub/sub channel limits, nullable content type, publication acceptance semantics, and payload limits;
- SSE framing, identifiers, heartbeats, resume, reset, slow-client isolation, quotas, byte-budget eviction, and cleanup;
- WebSocket envelopes, resume, reset, liveness, management-process event scope, and shutdown;
- serialization of every documented example DTO.

### 18.2 Frontend contract tests

- Generate or validate TypeScript DTOs against the OpenAPI representation of this contract.
- Parse REST, SSE, and WebSocket payloads with Zod.
- Reject unknown enum values at the boundary and show a typed compatibility error.
- Assert that sensitive DTOs never enter RTK Query cache or Zustand.
- Assert that mutation requests are not automatically retried.
- Assert that CSRF proof and allowed-origin handling are applied to every mutation while secrets remain out of browser persistence.
- Assert that nullable pub/sub content type and stream resets are rendered explicitly rather than guessed or hidden.
- Assert complete screen-to-endpoint coverage.

### 18.3 End-to-end acceptance

The backend plan owns a minimal non-production browser harness served only from test resources. Playwright runs that harness against the real REST server and PostgreSQL container to prove both authentication modes, session-cookie attributes/expiry, the local-bootstrap exception, CSRF/origin enforcement, no-store behavior, browser storage exclusion, and static-route isolation. The harness is not packaged in the runnable artifact and is not presented as the production console.

Phase 8.3 owns the production React console and its full-browser journeys for setup lifecycle and target policy, browsing, reveal, mutation, concurrency, bulk operations, pub/sub, monitoring, permissions, reconnect behavior, quotas, accessibility, and cleanup. Both suites inspect browser storage, URLs, responses, structured audit output, and ordinary logs for forbidden sensitive data and raw user-controlled identifiers.

## 19. Implementation state and module ownership

Status: **IN PROGRESS AT M1**. This document defines the reviewed contract; it does not claim that the management system is implemented or override the authoritative implementation plan. `PEEGEEQ_CACHE_IMPLEMENTATION_PLAN.md` registers the backend as Phase 8.2 and the browser console as separate Phase 8.3 work. M0 closed the contract and build topology without production classes; M1 starts the machine-readable OpenAPI and pure protocol boundary through strict red/green tests.

Planned ownership is:

- `peegee-cache-api`: management service, immutable request/result models, typed mutation outcomes, action context, capabilities, and audit-sink contract;
- `peegee-cache-pg`: PostgreSQL management queries and atomic version-checked mutations, with no REST dependency;
- `peegee-cache-rest`: Vert.x HTTP/SSE/WebSocket server, setup registry, authentication, authorization, CSRF/origin enforcement, target policy, rate limits, OpenAPI integration, and safe serialization;
- `peegee-cache-management-ui`: React client, generated/validated DTOs, local-only display preferences, sensitive-state isolation, and accessible operator workflows;
- `peegee-cache-observability`: reuse of the existing telemetry and logging standards; management lifecycle, HTTP, stream, audit-queue, and resource-saturation signals are mandatory production scope, not optional extras;
- `peegee-cache-test-support`: reusable real-PostgreSQL, server, authentication, SSE, and WebSocket fixtures where they avoid duplication without replacing end-to-end coverage.

Required implementation order:

1. complete Phase M0 contract closure and approve the Maven module graph;
2. commit and validate the OpenAPI 3.1 contract and transport extensions;
3. implement typed management API models and failing unit tests;
4. implement PostgreSQL inspection and mutation behavior through strict Testcontainers TDD;
5. implement authentication, audit, setup policy, and REST handlers;
6. implement bounded SSE/WebSocket infrastructure and observability;
7. pass backend protocol and backend-owned browser-harness acceptance gates before changing the backend status;
8. implement the production UI under the separately tracked Phase 8.3 plan against generated/validated DTOs.

## 20. OpenAPI implementation requirement

Before endpoint implementation begins, encode this contract as OpenAPI 3.1 under:

```text
peegee-cache-rest/src/main/openapi/peegeeq-cache-management-v1.yaml
```

The OpenAPI document is generated or maintained as the machine-readable companion to this design. CI must validate it, compare implemented routes with declared operations, and prevent undocumented response DTOs or error codes.

SSE and WebSocket message schemas belong in OpenAPI component schemas with descriptive transport extensions, even though OpenAPI does not fully model their connection lifecycle.

### 20.1 Contract-closure gate

Before the first OpenAPI file is accepted, Phase M0 produces the reviewed [management operation manifest](PEEGEEQ_CACHE_MANAGEMENT_OPERATION_MANIFEST.md) for every REST route. Each manifest row contains:

- exact method and complete path, with no abbreviated `...` form;
- unique operation identifier and owning service method;
- authentication mode, minimum role, management-session, Origin, and CSRF requirements, including the sole local-bootstrap exception;
- path, query, header, cookie, content-type, accept, and request-size rules;
- complete request schema and every success response schema/status/header;
- endpoint-specific problem codes, capability checks, rate/resource limits, audit requirement, and retry/idempotency behavior;
- whether the route can expose sensitive data and its required cache/redaction policy.

The same closure pass replaces remaining prose-only aggregate models with component schemas, explicitly fixes touch at a stable version, and resolves wildcard-precondition outcomes. An OpenAPI completeness test compares this reviewed manifest with the document before route implementation; passing YAML syntax alone is insufficient.

## 21. Compatibility policy

- `/api/v1` is additive within V1.
- New optional response fields may be added.
- Existing fields, enum meanings, status codes, and authorization requirements are not silently changed.
- New enum values require tolerant server/client rollout or a minor API capability gate.
- Removing or changing a field, changing its representation, or weakening a security invariant requires `/api/v2`.
- `serverVersion` and `apiVersion` are exposed by `/session` and WebSocket `connection.ready`.
- The UI checks API compatibility before enabling mutations.
