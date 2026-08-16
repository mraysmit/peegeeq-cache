# PeeGeeQ Cache Management API

**Status:** Approved API design

**Date:** August 2026

**Version:** 1.0 draft

**Base path:** `/api/v1`

## 1. Purpose

This document defines the HTTP, Server-Sent Events, WebSocket, security, error, concurrency, and Java service contracts required by the PeeGeeQ Cache Management UI.

It is the implementation contract for:

- `peegee-cache-rest`, the Vert.x management server;
- `peegee-cache-management-ui`, the React client;
- the PostgreSQL implementation of management inspection and guarded administration;
- integration and browser tests.

The associated product and screen design is in [PEEGEEQ_CACHE_MANAGEMENT_UI_DESIGN.md](PEEGEEQ_CACHE_MANAGEMENT_UI_DESIGN.md). The interactive screen designs are in [mockups/peegeeq-cache-management-ui-mockups.html](mockups/peegeeq-cache-management-ui-mockups.html).

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

The decoded namespace must contain 1–128 UTF-8 bytes. A decoded key must contain 1–1024 UTF-8 bytes. Invalid Base64 URL input, invalid UTF-8, empty identifiers, or identifiers over the management limit return `400 INVALID_IDENTIFIER`.

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

Cursors are scoped to the endpoint, setup, namespace, sort, and filter set. Reusing a cursor with different parameters returns `400 CURSOR_SCOPE_MISMATCH`. Cursors are not bookmarks and do not promise a historical snapshot.

### 3.7 Sorting

V1 supports only documented bounded sorts. Unknown fields or directions return `400 INVALID_SORT`.

Sort query syntax:

```text
sort=key:asc
```

The default keyset order is identifier ascending. Database-wide counts may use `entryCount:desc` where documented.

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

The server defaults to loopback binding. In proxy mode it accepts identity only from configured proxy addresses.

Default trusted headers:

```http
X-PeeGeeQ-User: alex.chen
X-PeeGeeQ-Roles: operator,viewer
```

The proxy must remove client-supplied copies before adding authoritative values. A direct or untrusted request containing these headers returns `401 UNTRUSTED_IDENTITY_SOURCE`.

### 4.2 Roles

| Role | Permissions |
|---|---|
| `viewer` | Setup metadata, health, capabilities, namespace/entry/counter/lock metadata, database/runtime monitoring, activity, and pub/sub metadata subscription |
| `operator` | Viewer permissions plus setup session management, sensitive reveal, entry/counter mutations, forced lock release, bulk operations, and pub/sub publish |

At least one recognized role is required. Missing identity returns `401`. An authenticated user without the required role returns `403`.

### 4.3 Current session

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
  "features": {
    "setupRegistration": true,
    "sensitiveReveal": true
  }
}
```

The response contains no raw proxy headers or group claims.

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
| `401` | `AUTHENTICATION_REQUIRED`, `UNTRUSTED_IDENTITY_SOURCE` |
| `403` | `ROLE_REQUIRED`, `SETUP_ACTION_FORBIDDEN`, `REVEAL_FORBIDDEN` |
| `404` | `SETUP_NOT_FOUND`, `ENTRY_NOT_FOUND`, `COUNTER_NOT_FOUND`, `LOCK_NOT_FOUND`, `SUBSCRIPTION_NOT_FOUND`, `MESSAGE_NOT_FOUND` |
| `409` | `SETUP_ALREADY_EXISTS`, `SETUP_STATE_CONFLICT`, `CAPABILITY_UNAVAILABLE`, `SET_MODE_NOT_APPLIED`, `BULK_SCOPE_CONFLICT` |
| `410` | `BULK_PREVIEW_EXPIRED`, `BULK_PREVIEW_USED`, `SUBSCRIPTION_EXPIRED`, `MESSAGE_EXPIRED` |
| `412` | `VERSION_MISMATCH` |
| `413` | `REQUEST_TOO_LARGE`, `PAYLOAD_TOO_LARGE` |
| `415` | `UNSUPPORTED_MEDIA_TYPE` |
| `422` | `JSON_VALUE_INVALID`, `VALUE_TYPE_MISMATCH` |
| `428` | `PRECONDITION_REQUIRED` |
| `429` | `RATE_LIMITED` |
| `500` | `INTERNAL_ERROR` |
| `503` | `SETUP_UNAVAILABLE`, `DATABASE_UNAVAILABLE`, `RUNTIME_STOPPED`, `PUBSUB_UNAVAILABLE` |

Vert.x failures are handled once by the route error layer. No asynchronous failure is ignored or converted to a successful response.

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
  "poolMaxSize": 10
}
```

Response `200`:

```json
{
  "databaseReachable": true,
  "schemaState": "READY",
  "migrationVersion": "2",
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
  "poolMaxSize": 10
}
```

Response `201`: `SetupSummary` with `source: UI_SESSION` and `state: CONNECTED`.

Registration validates reachability and schema readiness before publishing the setup. A failed registration closes all temporary resources and leaves no registry entry.

### 7.4 Setup details

`GET /api/v1/setups/{setupId}`

Role: viewer

Response contains `SetupSummary`, runtime configuration safe for display, migration version, expiry sweeper configuration, pool limits, and timestamps. It excludes usernames and passwords.

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

Capabilities are explicit booleans matching the test response. The UI hides unsupported destinations and disables unsupported actions, while the server still rejects direct calls with `409 CAPABILITY_UNAVAILABLE`.

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

`refreshTtlMillis` may be null to retain the current expiry. Each operation requires `If-Match`, increments or observes version according to the cache operation contract, and returns updated metadata with a new ETag.

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

Response returns the resulting counter item and ETag.

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

A negative delta decrements. Zero is rejected. `createIfMissing` defaults to false for management safety. TTL mode follows `CounterTtlMode`; `REPLACE` requires positive `ttlMillis`. Response returns the resulting counter item. The UI never automatically retries this operation.

### 10.5 Counter TTL and deletion

`POST .../counters/{encodedKey}/ttl` with `{ "ttlMillis": 60000 }`.

`POST .../counters/{encodedKey}/persist` with no body.

`DELETE .../counters/{encodedKey}` requires exact `If-Match`.

Each returns updated metadata or `204` for delete.

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

`POST .../locks/{encodedKey}/owner/reveal`

Role: operator

An optional request may provide a 3–240 character reason. Response uses `no-store` and contains owner token, current version, reveal time, and auto-hide duration. It emits a sanitized audit event.

### 11.4 Force release

`POST .../locks/{encodedKey}/force-release`

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

The channel is 1–128 UTF-8 bytes. `bufferLimit` defaults to 200 and is capped at 500.

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
data: {"messageId":"01K2VD...","channel":"cache-invalidation","contentType":"application/json","payloadBytes":142,"receivedAt":"2026-08-16T10:42:17.000Z","payloadState":"MASKED"}

```

The server sends a `ready` event first and a comment heartbeat every 15 seconds. `Last-Event-ID` resumes from the bounded session buffer. If the requested event is no longer available, the stream sends `reset` with the oldest available identifier.

### 12.3 Reveal retained payload

`POST /api/v1/setups/{setupId}/pubsub/subscriptions/{subscriptionId}/messages/{messageId}/payload/reveal`

Role: owning operator

An optional request may provide a 3–240 character reveal reason. Response contains the payload string, content type, received time, encoding (`UTF8`), and `no-store` headers. A message removed from the bounded buffer returns `410 MESSAGE_EXPIRED`.

### 12.4 Stop subscription

`DELETE /api/v1/setups/{setupId}/pubsub/subscriptions/{subscriptionId}`

Role: owning viewer/operator

Response `204`. It closes LISTEN resources, stream clients, and the message buffer.

Disconnected sessions remain resumable for five minutes. A session expires after one hour even while used and must then be recreated.

### 12.5 Publish

`POST /api/v1/setups/{setupId}/pubsub/publish`

Role: operator

```json
{
  "channel": "cache-invalidation",
  "contentType": "application/json",
  "payload": "{\"namespace\":\"customer-profile\",\"key\":\"customer:849203\"}"
}
```

The UTF-8 encoded payload must not exceed the setup's configured pub/sub payload limit. Response `200`:

```json
{
  "deliveredListenerCount": 3,
  "publishedAt": "2026-08-16T10:44:00.000Z"
}
```

The count is a point-in-time PostgreSQL result and is not a delivery guarantee. The client does not retry automatically.

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

Response begins with `scope: MANAGEMENT_RUNTIME` and includes:

- runtime started state;
- pool active, idle, pending, and maximum;
- active management operations;
- active console pub/sub subscriptions;
- connected SSE and WebSocket clients;
- management-process operation counts/timers;
- expiry sweeper state when owned by this runtime.

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

The bounded activity API is a convenience view from the current management process. Structured server logs are the authoritative audit output.

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
- setup, namespace, resource type, and resource identifier;
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

### 15.2 Reveal rate and expiry

Reveal endpoints are subject to configurable per-user limits. A revealed response is never cached by the server or browser. The client clears it on timeout, navigation, setup/namespace change, and document visibility timeout.

### 15.3 Logging middleware

Request logging records method, route template, status, duration, actor, setup, correlation identifier, and safe error code. It logs route templates rather than raw encoded identifiers where possible and never logs request or response bodies for sensitive or mutation routes.

## 16. Java API contract

The existing `CacheService`, `CounterService`, `LockService`, `ScanService`, `PubSubService`, and `AdminService` remain unchanged. Application behavior is not expanded or weakened to satisfy browser administration.

A new typed `ManagementService` provides database inspection, sensitive reads, and concurrency-safe administrative mutations:

```java
AdminCapabilities capabilities();

Future<AdminPage<NamespaceStats>> namespaces(NamespaceQuery query);

Future<ManagementEntryMetadata> entry(CacheKey key, boolean includeExpired);

Future<CacheValue> revealEntry(CacheKey key);

Future<CacheSetResult> setEntry(ManagementCacheSetRequest request);

Future<Boolean> expireEntry(VersionedEntryTtlRequest request);

Future<Boolean> persistEntry(VersionedCacheKeyRequest request);

Future<TouchResult> touchEntry(VersionedEntryTouchRequest request);

Future<Boolean> deleteEntry(VersionedEntryDeleteRequest request);

Future<AdminPage<CounterEntry>> counters(CounterQuery query);

Future<CounterEntry> counter(CacheKey key);

Future<CounterEntry> setCounter(ManagementCounterSetRequest request);

Future<CounterEntry> adjustCounter(ManagementCounterAdjustRequest request);

Future<Boolean> expireCounter(VersionedCounterTtlRequest request);

Future<Boolean> persistCounter(VersionedCacheKeyRequest request);

Future<Boolean> deleteCounter(VersionedCounterDeleteRequest request);

Future<AdminPage<LockState>> locks(LockQuery query);

Future<String> revealLockOwner(LockKey key);

Future<Boolean> forceReleaseLock(ForceReleaseLockRequest request);

Future<DatabaseStats> databaseStats();

Future<ExpiryStats> expiryStats();

Future<BulkDeletePreview> previewEntryDelete(EntryDeleteFilter filter);

Future<BulkDeleteResult> executeEntryDelete(ConfirmedEntryDelete request);

Future<BulkDeletePreview> previewCounterDelete(CounterDeleteSelection selection);

Future<BulkDeleteResult> executeCounterDelete(ConfirmedCounterDelete request);
```

`ManagementCacheSetRequest` carries `CacheKey`, `CacheValue`, `SetMode`, expected version, `EntryTtlMode`, and optional TTL. `EntryTtlMode` contains `PRESERVE_EXISTING`, `USE_DEFAULT`, `REPLACE`, and `REMOVE`. Management counter requests use the existing `CounterTtlMode` values and carry an expected version where the REST contract requires `If-Match`.

The `PeeGeeCache` interface gains a backward-compatible default `management()` accessor returning an unsupported service. PostgreSQL returns the complete implementation. `capabilities()` reports support before invocation, and unsupported methods return a failed `Future` with a typed unsupported-capability exception.

REST-specific services remain outside the cache library API:

```java
interface CacheSetupRegistry {
    Future<SetupConnectionTest> test(SetupConnectionRequest request);
    Future<SetupHandle> register(SetupRegistrationRequest request);
    Future<SetupHandle> connect(String setupId);
    Future<Void> detach(String setupId);
    Future<Void> forget(String setupId);
    Future<List<SetupSummary>> list();
    Future<SetupHandle> requireConnected(String setupId);
}

interface ManagementEventService {
    void publish(ManagementEvent event);
    List<ManagementEvent> recent(ActivityQuery query);
}
```

`SetupHandle` exposes typed cache services and safe setup metadata but not credentials. REST handlers never receive a raw password after registration.

## 17. Screen-to-API coverage

| Screen or interaction | API coverage |
|---|---|
| Header identity and role | `GET /session` |
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

- every route's authentication and role requirements;
- request validation and problem response schema;
- decimal-string encoding for every 64-bit value;
- Base64 URL identifier round trips and invalid inputs;
- cursor/filter scoping and page boundaries;
- ETag generation, required preconditions, and stale-version behavior;
- complete redaction of values, payloads, owner tokens, and credentials;
- reveal no-store headers and audit emission;
- setup cleanup on failure, detach, forget, and shutdown;
- exact cache/counter/lock behavior against PostgreSQL;
- bulk preview scope, actor binding, expiry, single use, conflicts, and partial failure;
- SSE framing, identifiers, heartbeats, resume, reset, and cleanup;
- WebSocket envelopes, resume, reset, liveness, and shutdown;
- serialization of every documented example DTO.

### 18.2 Frontend contract tests

- Generate or validate TypeScript DTOs against the OpenAPI representation of this contract.
- Parse REST, SSE, and WebSocket payloads with Zod.
- Reject unknown enum values at the boundary and show a typed compatibility error.
- Assert that sensitive DTOs never enter RTK Query cache or Zustand.
- Assert that mutation requests are not automatically retried.
- Assert complete screen-to-endpoint coverage.

### 18.3 End-to-end acceptance

Playwright runs the real REST server and PostgreSQL container and proves setup lifecycle, browsing, reveal, mutation, concurrency, bulk operations, pub/sub, monitoring, permissions, reconnect behavior, and cleanup. Tests inspect browser storage, URLs, responses, and sanitized logs for forbidden sensitive data.

## 19. OpenAPI implementation requirement

Before endpoint implementation begins, encode this contract as OpenAPI 3.1 under:

```text
peegee-cache-rest/src/main/openapi/peegeeq-cache-management-v1.yaml
```

The OpenAPI document is generated or maintained as the machine-readable companion to this design. CI must validate it, compare implemented routes with declared operations, and prevent undocumented response DTOs or error codes.

SSE and WebSocket message schemas belong in OpenAPI component schemas with descriptive transport extensions, even though OpenAPI does not fully model their connection lifecycle.

## 20. Compatibility policy

- `/api/v1` is additive within V1.
- New optional response fields may be added.
- Existing fields, enum meanings, status codes, and authorization requirements are not silently changed.
- New enum values require tolerant server/client rollout or a minor API capability gate.
- Removing or changing a field, changing its representation, or weakening a security invariant requires `/api/v2`.
- `serverVersion` and `apiVersion` are exposed by `/session` and WebSocket `connection.ready`.
- The UI checks API compatibility before enabling mutations.
