# PeeGeeQ Cache Management V1 Operation Manifest

**Status:** Reviewed contract baseline for Phase 8.2 M0

**Date:** 20 August 2026

**Base path:** `/api/v1` except the monitoring WebSocket, which is rooted at `/ws`

This is the closed operation inventory used by the M1 OpenAPI completeness test. The authoritative behavioral detail remains [PEEGEEQ_CACHE_MANAGEMENT_API.md](PEEGEEQ_CACHE_MANAGEMENT_API.md). Every row below names an exact method/path, operation identifier, security profile, request and success schema, statuses/headers, capability/limit rules, audit behavior, retry policy, and endpoint-specific problem codes. No abbreviated path is normative.

## Shared rules referenced by every row

All REST responses carry `X-Correlation-ID`. JSON uses `application/json; charset=utf-8`; errors use `application/problem+json; charset=utf-8` and `ManagementProblem`. Unsupported request/response media types produce `415 UNSUPPORTED_MEDIA_TYPE` or `406 NOT_ACCEPTABLE`. Invalid JSON or fields produce `400 VALIDATION_FAILED`; size failure produces `413 REQUEST_TOO_LARGE` or `PAYLOAD_TOO_LARGE`; an unavailable setup/runtime/database produces the applicable `503 SETUP_UNAVAILABLE`, `RUNTIME_STOPPED`, or `DATABASE_UNAVAILABLE`. Authentication, authorization, session, origin, CSRF, rate, and audit codes are added by the selected profiles below. Unknown paths do not fall through to `/ui`.

Common validation applies before service invocation:

- setup IDs match `[A-Za-z0-9][A-Za-z0-9_-]{0,63}`;
- namespace/key path values are canonical unpadded Base64 URL UTF-8; decoded namespace is 1–128 bytes, decoded key is 1–1,024 bytes, and NUL is prohibited;
- ordinary JSON is at most 1 MiB, setup JSON at most 64 KiB, decoded values at most 10 MiB, and publication payloads use the setup-specific UTF-8 byte limit;
- page limits default to 50 and range from 1–200; cursors expire after 15 minutes and are bound to endpoint, setup, namespace, filters, and sort;
- all Java/PostgreSQL 64-bit values are decimal strings, timestamps are UTC `Z`, and durations are non-negative integer milliseconds;
- state-changing operations are never automatically retried; an uncertain result requires a state reload.

### Security profiles

| Profile | Complete requirement | Added problems |
|---|---|---|
| `SESSION` | Authenticated identity. Trusted-proxy mode may establish/refresh its bounded management session; local-token mode requires its existing cookie. Response is `no-store`. No CSRF because the operation is safe. | `401 AUTHENTICATION_REQUIRED`, `UNTRUSTED_IDENTITY_SOURCE`, `INVALID_IDENTITY`, `SESSION_EXPIRED` |
| `LOCAL_BOOTSTRAP` | `LOCAL_TOKEN` mode, loopback peer, exact same origin, CORS disabled, JSON body, unconsumed single-use token, dedicated actor/source limit. This is the sole CSRF exception. | `401 INVALID_BOOTSTRAP_TOKEN`; `403 CSRF_VALIDATION_FAILED`; `429 RATE_LIMITED` |
| `VIEW` | Authenticated bounded management session with `viewer` or `operator`; configured same-origin/exact-origin policy. | `401 AUTHENTICATION_REQUIRED`, `SESSION_EXPIRED`; `403 ROLE_REQUIRED` |
| `OPERATE` | `VIEW` plus `operator`, allowed `Origin`, matching `X-PeeGeeQ-CSRF`, and durable audit intent reservation before work. | `403 ROLE_REQUIRED`, `CSRF_VALIDATION_FAILED`; `503 AUDIT_UNAVAILABLE`, `AUDIT_OUTCOME_UNAVAILABLE` |
| `VIEW_MUTATE` | `VIEW` plus allowed `Origin`, matching CSRF, resource ownership where applicable, and durable audit reservation. Viewer is sufficient. | `403 CSRF_VALIDATION_FAILED`; `404` ownership-hiding code; `503 AUDIT_UNAVAILABLE`, `AUDIT_OUTCOME_UNAVAILABLE` |
| `REVEAL` | `OPERATE` plus reveal rate limit. Response has `Cache-Control: no-store, no-cache, must-revalidate` and `Pragma: no-cache`. | `403 REVEAL_FORBIDDEN`; `429 RATE_LIMITED` plus `OPERATE` problems |
| `SSE` | `VIEW`, owning resource where applicable, authenticated management session and allowed handshake `Origin`; query authentication prohibited. | `404` ownership-hiding code; `410` expiry where applicable; `429 SUBSCRIPTION_LIMIT_REACHED` |
| `WS` | `VIEW` during HTTP upgrade, allowed `Origin`, `setupId` required, optional bounded `afterEventId`; query authentication prohibited. | `404 SETUP_NOT_FOUND`; `429 RATE_LIMITED` |

`OPERATE`, `VIEW_MUTATE`, and `REVEAL` reservations complete exactly once as `SUCCEEDED`, `REJECTED`, `FAILED`, or `UNKNOWN`. A successful database operation whose terminal audit outcome cannot be persisted returns uncertain `503 AUDIT_OUTCOME_UNAVAILABLE`; clients do not retry it.

### Header profiles

| Code | Headers |
|---|---|
| `C` | `X-Correlation-ID` |
| `N` | `C` plus `Cache-Control: no-store` |
| `R` | `C` plus reveal `Cache-Control` and `Pragma` headers |
| `E` | `C` plus `ETag: "v{decimal}"` |
| `S` | `C`, `Content-Type: text/event-stream; charset=utf-8`, `Cache-Control: no-cache`, `Connection: keep-alive` |
| `W` | Standard WebSocket upgrade headers followed by typed JSON frames |

## Session and setup lifecycle

| Operation ID | Method and exact path | Security | Request → success schema | Status / headers | Capability and limits | Audit / retry | Specific problems |
|---|---|---|---|---|---|---|---|
| `getSession` | `GET /api/v1/session` | `SESSION` | none → `CurrentSession` | `200 / N`, may set/rotate `PGQMGMTSESSION` | session idle/absolute limits | no audit / safe read retry | profile problems |
| `exchangeLocalToken` | `POST /api/v1/session/local` | `LOCAL_BOOTSTRAP` | `LocalTokenExchangeRequest` → `CurrentSession` | `200 / N`, sets rotated `PGQMGMTSESSION` | one 256-bit token, once | authentication event / never | `INVALID_BOOTSTRAP_TOKEN`, `RATE_LIMITED` |
| `deleteLocalSession` | `DELETE /api/v1/session/local` | `VIEW_MUTATE`, local actor | none → none | `204 / C`, expires cookie | local session only | session event / never | `SESSION_EXPIRED` |
| `listSetups` | `GET /api/v1/setups` | `VIEW` | none → `SetupSummaryList` | `200 / C` | none | no audit / safe read retry | profile problems |
| `testUnregisteredSetup` | `POST /api/v1/setups/actions/test` | `OPERATE` | `SetupConnectionRequest` → `SetupConnectionTest` | `200 / C` | 64 KiB; target/TLS policy; setup-test actor/source rate | required `SETUP_TESTED` / never | `TARGET_FORBIDDEN`, `RATE_LIMITED`, `DATABASE_UNAVAILABLE` |
| `registerSetup` | `POST /api/v1/setups` | `OPERATE` | `SetupRegistrationRequest` → `SetupSummary` | `201 / C` | setup registration enabled; target/TLS policy; connection rate | required `SETUP_REGISTERED` / never | `SETUP_ALREADY_EXISTS`, `TARGET_FORBIDDEN`, `RATE_LIMITED` |
| `getSetup` | `GET /api/v1/setups/{setupId}` | `VIEW` | none → `SetupDetails` | `200 / C` | none | no audit / safe read retry | `SETUP_NOT_FOUND` |
| `connectSetup` | `POST /api/v1/setups/{setupId}/connect` | `OPERATE` | none → `SetupSummary` | `200 / C` | credentials still resolvable; target/TLS policy; connect rate | required `SETUP_CONNECTED` / never | `SETUP_NOT_FOUND`, `SETUP_STATE_CONFLICT`, `TARGET_FORBIDDEN`, `RATE_LIMITED` |
| `testRegisteredSetup` | `POST /api/v1/setups/{setupId}/test` | `OPERATE` | none → `SetupConnectionTest` | `200 / C` | target/TLS policy; setup-test rate | required `SETUP_TESTED` / never | `SETUP_NOT_FOUND`, `TARGET_FORBIDDEN`, `RATE_LIMITED` |
| `detachSetup` | `POST /api/v1/setups/{setupId}/detach` | `OPERATE` | none → none | `204 / C` | connected/detachable state | required `SETUP_DETACHED` / never | `SETUP_NOT_FOUND`, `SETUP_STATE_CONFLICT` |
| `forgetSetup` | `DELETE /api/v1/setups/{setupId}` | `OPERATE` | none → none | `204 / C` | `UI_SESSION` source only | required `SETUP_FORGOTTEN` / never | `SETUP_NOT_FOUND`, `SETUP_ACTION_FORBIDDEN`, `SETUP_STATE_CONFLICT` |
| `getSetupHealth` | `GET /api/v1/setups/{setupId}/health` | `VIEW` | none → `SetupHealth` | `200 / C` | none | no audit / safe read retry | `SETUP_NOT_FOUND` |
| `getSetupCapabilities` | `GET /api/v1/setups/{setupId}/capabilities` | `VIEW` | none → `SetupCapabilities` | `200 / C` | returns effective limits and migration version `1` | no audit / safe read retry | `SETUP_NOT_FOUND` |

## Overview, namespaces, and entries

| Operation ID | Method and exact path | Security | Request → success schema | Status / headers | Capability and limits | Audit / retry | Specific problems |
|---|---|---|---|---|---|---|---|
| `getOverview` | `GET /api/v1/setups/{setupId}/overview` | `VIEW` | none → `Overview` | `200 / C` | namespace inspection | no audit / safe read retry | `SETUP_NOT_FOUND`, `CAPABILITY_UNAVAILABLE` |
| `listNamespaces` | `GET /api/v1/setups/{setupId}/namespaces` | `VIEW` | `NamespaceQuery` query → `AdminPageNamespaceStats` | `200 / C` | namespace inspection; page 1–200 | no audit / safe read retry | cursor/sort problems, `CAPABILITY_UNAVAILABLE` |
| `exportNamespaces` | `GET /api/v1/setups/{setupId}/namespaces/export` | `VIEW` | `NamespaceExportQuery` + `Accept` → `NamespaceExport` | `200 / C`, CSV or JSON content type | namespace inspection; maximum 10,000 | no audit / safe read retry | `406 NOT_ACCEPTABLE`, `BULK_SCOPE_CONFLICT`, `CAPABILITY_UNAVAILABLE` |
| `getNamespace` | `GET /api/v1/setups/{setupId}/namespaces/{encodedNamespace}` | `VIEW` | none → `NamespaceDetails` | `200 / C` | namespace inspection; logical empty namespace returns zero model | no audit / safe read retry | `INVALID_IDENTIFIER`, `CAPABILITY_UNAVAILABLE` |
| `listEntries` | `GET /api/v1/setups/{setupId}/namespaces/{encodedNamespace}/entries` | `VIEW` | `EntryQuery` query → `AdminPageManagementEntryMetadata` | `200 / C` | namespace inspection; expired capability when requested; page 1–200 | no audit / safe read retry | identifier/cursor/sort problems, `CAPABILITY_UNAVAILABLE` |
| `getEntry` | `GET /api/v1/setups/{setupId}/namespaces/{encodedNamespace}/entries/{encodedKey}` | `VIEW` | `includeExpired` query → `ManagementEntryMetadata` | `200 / E` | expired capability when requested | no audit / safe read retry | `ENTRY_NOT_FOUND`, `INVALID_IDENTIFIER`, `CAPABILITY_UNAVAILABLE` |
| `revealEntryValue` | `POST /api/v1/setups/{setupId}/namespaces/{encodedNamespace}/entries/{encodedKey}/value/reveal` | `REVEAL` | optional `RevealReasonRequest` → `RevealedEntryValue` | `200 / R` | sensitive-value reveal; reason absent or 3–240 chars | required `ENTRY_VALUE_REVEALED` / never | `ENTRY_NOT_FOUND`, `CAPABILITY_UNAVAILABLE` |
| `setEntry` | `PUT /api/v1/setups/{setupId}/namespaces/{encodedNamespace}/entries/{encodedKey}` | `OPERATE` | `ManagementCacheSetBody` + mode-specific precondition → `ManagementSetResult` | `200` update or `201` create / `E` | decoded value ≤10 MiB; atomic TTL/set mode | required `ENTRY_SET` / never | `PRECONDITION_REQUIRED`, `VERSION_MISMATCH`, `SET_MODE_NOT_APPLIED`, `JSON_VALUE_INVALID`, `VALUE_TYPE_MISMATCH` |
| `deleteEntry` | `DELETE /api/v1/setups/{setupId}/namespaces/{encodedNamespace}/entries/{encodedKey}` | `OPERATE` | exact `If-Match` → none | `204 / C` | atomic exact version | required `ENTRY_DELETED` / never | `PRECONDITION_REQUIRED`, `ENTRY_NOT_FOUND`, `VERSION_MISMATCH` |
| `expireEntry` | `POST /api/v1/setups/{setupId}/namespaces/{encodedNamespace}/entries/{encodedKey}/ttl` | `OPERATE` | `EntryTtlRequest` + exact `If-Match` → `ManagementEntryMetadata` | `200 / E` | positive TTL; atomic exact version | required `ENTRY_TTL_SET` / never | `PRECONDITION_REQUIRED`, `ENTRY_NOT_FOUND`, `VERSION_MISMATCH` |
| `persistEntry` | `POST /api/v1/setups/{setupId}/namespaces/{encodedNamespace}/entries/{encodedKey}/persist` | `OPERATE` | exact `If-Match`, no body → `ManagementEntryMetadata` | `200 / E` | atomic exact version | required `ENTRY_PERSISTED` / never | `PRECONDITION_REQUIRED`, `ENTRY_NOT_FOUND`, `VERSION_MISMATCH` |
| `touchEntry` | `POST /api/v1/setups/{setupId}/namespaces/{encodedNamespace}/entries/{encodedKey}/touch` | `OPERATE` | `EntryTouchRequest` + exact `If-Match` → `ManagementEntryMetadata` | `200 / E`; ETag remains the matched version | null or positive refresh TTL; touch does not increment version | required `ENTRY_TOUCHED` / never | `PRECONDITION_REQUIRED`, `ENTRY_NOT_FOUND`, `VERSION_MISMATCH` |
| `previewEntryBulkDelete` | `POST /api/v1/setups/{setupId}/namespaces/{encodedNamespace}/entries/bulk-delete/preview` | `OPERATE` | `EntryDeleteSelection` → `BulkDeletePreview` | `200 / C` | bulk-entry capability; explicit 1–1,000 or filter ≤10,000; preview rate; five minutes | required `ENTRY_BULK_PREVIEWED` / never | `BULK_SCOPE_CONFLICT`, `RATE_LIMITED`, `CAPABILITY_UNAVAILABLE` |
| `executeEntryBulkDelete` | `POST /api/v1/setups/{setupId}/namespaces/{encodedNamespace}/entries/bulk-delete/execute` | `OPERATE` | `ConfirmedEntryDelete` → `BulkDeleteResult` | `200 / C` | actor/setup/namespace scoped; exact phrase; single use | required `ENTRY_BULK_DELETED` / never | `CONFIRMATION_MISMATCH`, `BULK_SCOPE_CONFLICT`, `BULK_PREVIEW_EXPIRED`, `BULK_PREVIEW_USED` |

## Counters and locks

| Operation ID | Method and exact path | Security | Request → success schema | Status / headers | Capability and limits | Audit / retry | Specific problems |
|---|---|---|---|---|---|---|---|
| `listCounters` | `GET /api/v1/setups/{setupId}/counters` | `VIEW` | `CounterQuery` query → `AdminPageCounterEntry` | `200 / C` | counter inspection; page 1–200 | no audit / safe read retry | cursor/sort problems, `CAPABILITY_UNAVAILABLE` |
| `getCounter` | `GET /api/v1/setups/{setupId}/namespaces/{encodedNamespace}/counters/{encodedKey}` | `VIEW` | none → `CounterEntry` | `200 / E` | counter inspection | no audit / safe read retry | `COUNTER_NOT_FOUND`, `CAPABILITY_UNAVAILABLE` |
| `setCounter` | `PUT /api/v1/setups/{setupId}/namespaces/{encodedNamespace}/counters/{encodedKey}` | `OPERATE` | `ManagementCounterSetBody` + exact/wildcard precondition → `CounterEntry` | `200` update or `201` create / `E` | counter inspection; atomic value/version/TTL | required `COUNTER_SET` / never | `PRECONDITION_REQUIRED`, `COUNTER_NOT_FOUND`, `VERSION_MISMATCH`, `SET_MODE_NOT_APPLIED` |
| `adjustCounter` | `POST /api/v1/setups/{setupId}/namespaces/{encodedNamespace}/counters/{encodedKey}/increment` | `OPERATE` | `ManagementCounterAdjustBody` + exact/wildcard precondition → `CounterEntry` | `200 / E` | non-zero signed delta; atomic overflow/version/TTL | required `COUNTER_ADJUSTED` / never | `PRECONDITION_REQUIRED`, `COUNTER_NOT_FOUND`, `VERSION_MISMATCH`, `SET_MODE_NOT_APPLIED`, `VALIDATION_FAILED` |
| `expireCounter` | `POST /api/v1/setups/{setupId}/namespaces/{encodedNamespace}/counters/{encodedKey}/ttl` | `OPERATE` | `CounterTtlRequest` + exact `If-Match` → `CounterEntry` | `200 / E` | positive TTL; atomic exact version | required `COUNTER_TTL_SET` / never | `PRECONDITION_REQUIRED`, `COUNTER_NOT_FOUND`, `VERSION_MISMATCH` |
| `persistCounter` | `POST /api/v1/setups/{setupId}/namespaces/{encodedNamespace}/counters/{encodedKey}/persist` | `OPERATE` | exact `If-Match`, no body → `CounterEntry` | `200 / E` | atomic exact version | required `COUNTER_PERSISTED` / never | `PRECONDITION_REQUIRED`, `COUNTER_NOT_FOUND`, `VERSION_MISMATCH` |
| `deleteCounter` | `DELETE /api/v1/setups/{setupId}/namespaces/{encodedNamespace}/counters/{encodedKey}` | `OPERATE` | exact `If-Match` → none | `204 / C` | atomic exact version | required `COUNTER_DELETED` / never | `PRECONDITION_REQUIRED`, `COUNTER_NOT_FOUND`, `VERSION_MISMATCH` |
| `previewCounterBulkDelete` | `POST /api/v1/setups/{setupId}/counters/bulk-delete/preview` | `OPERATE` | `CounterDeleteSelection` → `BulkDeletePreview` | `200 / C` | bulk-counter capability; explicit 1–1,000; preview rate; five minutes | required `COUNTER_BULK_PREVIEWED` / never | `BULK_SCOPE_CONFLICT`, `RATE_LIMITED`, `CAPABILITY_UNAVAILABLE` |
| `executeCounterBulkDelete` | `POST /api/v1/setups/{setupId}/counters/bulk-delete/execute` | `OPERATE` | `ConfirmedCounterDelete` → `BulkDeleteResult` | `200 / C` | actor/setup scoped; exact phrase; single use | required `COUNTER_BULK_DELETED` / never | `CONFIRMATION_MISMATCH`, `BULK_SCOPE_CONFLICT`, `BULK_PREVIEW_EXPIRED`, `BULK_PREVIEW_USED` |
| `listLocks` | `GET /api/v1/setups/{setupId}/locks` | `VIEW` | `LockQuery` query → `AdminPageLockState` | `200 / C` | lock inspection; active locks only; page 1–200 | no audit / safe read retry | cursor problems, `CAPABILITY_UNAVAILABLE` |
| `getLock` | `GET /api/v1/setups/{setupId}/namespaces/{encodedNamespace}/locks/{encodedKey}` | `VIEW` | none → `LockState` | `200 / E` | lock inspection | no audit / safe read retry | `LOCK_NOT_FOUND`, `CAPABILITY_UNAVAILABLE` |
| `revealLockOwner` | `POST /api/v1/setups/{setupId}/namespaces/{encodedNamespace}/locks/{encodedKey}/owner/reveal` | `REVEAL` | optional `RevealReasonRequest` → `RevealedLockOwner` | `200 / R` | sensitive reveal; reason absent or 3–240 chars | required `LOCK_OWNER_REVEALED` / never | `LOCK_NOT_FOUND`, `CAPABILITY_UNAVAILABLE` |
| `forceReleaseLock` | `POST /api/v1/setups/{setupId}/namespaces/{encodedNamespace}/locks/{encodedKey}/force-release` | `OPERATE` | `ForceReleaseLockBody` + exact `If-Match` → none | `204 / C` | forced-release capability; exact decoded confirmation; atomic version | required `LOCK_FORCE_RELEASED` / never | `PRECONDITION_REQUIRED`, `CONFIRMATION_MISMATCH`, `LOCK_NOT_FOUND`, `VERSION_MISMATCH`, `CAPABILITY_UNAVAILABLE` |

## Pub/sub, monitoring, and activity

| Operation ID | Method and exact path | Security | Request → success schema | Status / headers | Capability and limits | Audit / retry | Specific problems |
|---|---|---|---|---|---|---|---|
| `createPubSubSubscription` | `POST /api/v1/setups/{setupId}/pubsub/subscriptions` | `VIEW_MUTATE` | `CreateSubscriptionRequest` → `SubscriptionSummary` | `201 / C` | pub/sub; channel effective byte max; buffer 1–500; actor/setup/process quotas and rate | required `SUBSCRIPTION_CREATED` / never | `PUBSUB_UNAVAILABLE`, `SUBSCRIPTION_LIMIT_REACHED`, `RATE_LIMITED`, `CAPABILITY_UNAVAILABLE` |
| `streamPubSubMessages` | `GET /api/v1/setups/{setupId}/pubsub/subscriptions/{subscriptionId}/stream` | `SSE` | optional `Last-Event-ID` → `PubSubSseEvent` stream | `200 / S` | owner only; one-hour subscription; five-minute resume; bounded entries/bytes | safe stream lifecycle / bounded reconnect | `SUBSCRIPTION_NOT_FOUND`, `SUBSCRIPTION_EXPIRED`, `PUBSUB_UNAVAILABLE` |
| `revealPubSubPayload` | `POST /api/v1/setups/{setupId}/pubsub/subscriptions/{subscriptionId}/messages/{messageId}/payload/reveal` | `REVEAL`, owner | optional `RevealReasonRequest` → `RevealedPubSubPayload` | `200 / R` | retained owner buffer only; reveal rate; nullable content type | required `PUBSUB_PAYLOAD_REVEALED` / never | `SUBSCRIPTION_NOT_FOUND`, `MESSAGE_NOT_FOUND`, `MESSAGE_EXPIRED` |
| `deletePubSubSubscription` | `DELETE /api/v1/setups/{setupId}/pubsub/subscriptions/{subscriptionId}` | `VIEW_MUTATE`, owner | none → none | `204 / C` | owner only | required `SUBSCRIPTION_DELETED` / never | `SUBSCRIPTION_NOT_FOUND` |
| `publishPubSubMessage` | `POST /api/v1/setups/{setupId}/pubsub/publish` | `OPERATE` | `PublishRequest` → `PublishAccepted` | `200 / C` | pub/sub; channel byte max; setup payload byte max; publish rate | required `PUBSUB_PUBLISHED` / never | `PAYLOAD_TOO_LARGE`, `RATE_LIMITED`, `PUBSUB_UNAVAILABLE`, `CAPABILITY_UNAVAILABLE` |
| `getDatabaseMonitoring` | `GET /api/v1/setups/{setupId}/monitoring/database` | `VIEW` | none → `DatabaseMonitoring` | `200 / C` | database-statistics capability; permission fields use availability wrappers | no audit / safe read retry | `CAPABILITY_UNAVAILABLE` |
| `getRuntimeMonitoring` | `GET /api/v1/setups/{setupId}/monitoring/runtime` | `VIEW` | none → `RuntimeMonitoring` | `200 / C` | process-local bounded dimensions only | no audit / safe read retry | `SETUP_NOT_FOUND` |
| `streamMetrics` | `GET /api/v1/setups/{setupId}/sse/metrics` | `SSE` | optional `Last-Event-ID` → `MetricsSseEvent` stream | `200 / S` | 15-second snapshots/heartbeat; bounded five-minute resume | safe stream lifecycle / bounded reconnect | `SETUP_NOT_FOUND` |
| `listActivity` | `GET /api/v1/setups/{setupId}/activity` | `VIEW` | `ActivityQuery` query → `ActivityPage` | `200 / C` | limit default 50/max 200; process-local bounded history | no audit / safe read retry | `INVALID_CURSOR`, `SETUP_NOT_FOUND` |
| `monitoringWebSocket` | `GET /ws/monitoring` | `WS` | `setupId`, optional `afterEventId` query → `MonitoringWebSocketEnvelope` frames | `101 / W` | five-minute resume; ping 20s/pong 10s; process-local events only | safe stream lifecycle / bounded reconnect | `SETUP_NOT_FOUND`, `RATE_LIMITED` |

## Aggregate schema closure

M1 OpenAPI components must define, rather than leave as prose, every schema name used above. In particular:

- `Overview` contains `scope`, `observedAt`, `SetupHealthSummary`, decimal-string `OverviewTotals`, `ExpiryOverview`, decimal-string `valueTypeCounts`, and bounded `topNamespaces`;
- `NamespaceDetails` contains `NamespaceStats`, decimal-string value-type counts, and bounded TTL distribution buckets;
- `DatabaseMonitoring` contains health/readiness, decimal-string table/index/schema bytes and row/dead-tuple/connection counts, UTC vacuum timestamps, and `AvailableLongValue` wrappers whose unavailable state has a non-null reason and null value;
- `RuntimeMonitoring` contains `scope=MANAGEMENT_RUNTIME`, `observedAt`, lifecycle state, availability-wrapped decimal-string pool counts, active operations, subscription/SSE/WebSocket counts, retained bytes, `AuditQueueState`, `ExpirySweeperState`, and bounded operation aggregates;
- `ActivityPage` is a bounded page of `ActivityEvent`; raw identifiers may appear only in this authorized process-local response, never in audit/log DTOs;
- `PubSubSseEvent`, `MetricsSseEvent`, and `MonitoringWebSocketEnvelope` use closed type discriminators plus explicitly declared payload schemas; received pub/sub `contentType` is nullable;
- `ManagementProblem` requires `type`, `title`, `status`, `code`, safe `detail`, `instance`, `correlationId`, and `fieldErrors`.

Touch is explicitly version-stable: a successful touch returns updated metadata and the same ETag/version used by its exact `If-Match`. Wildcard set/counter preconditions that are syntactically valid but not satisfied map to atomic `CONDITION_NOT_MET` and HTTP `409 SET_MODE_NOT_APPLIED`; exact-version absence maps to `NOT_FOUND`/`404`, and exact-version mismatch maps to `VERSION_MISMATCH`/`412`. No route issues a diagnostic follow-up read to manufacture these outcomes.

## M1 acceptance use

`ManagementOpenApiContractTest.matchesReviewedOperationManifest` must compare all 50 operation IDs and exact method/path pairs in this document with OpenAPI. It must also verify each row's security profile, success status/schema, problem response, headers, and declared transport schemas before any handler is implemented.
