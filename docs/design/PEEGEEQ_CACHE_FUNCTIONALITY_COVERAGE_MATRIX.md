# PeeGeeQ Cache Backend-to-REST-to-UI Functionality Coverage Matrix

> **Capability gating removed (10 September 2026).** The per-setup capability advertisement (`GET /api/v1/setups/{setupId}/capabilities`, `SetupCapabilities`, `AdminCapabilities`, `ManagementCapability`, the session `features` block, and the UI capability gates) was removed by [the capability gating removal plan](archive/PEEGEEQ_CACHE_CAPABILITY_GATING_REMOVAL_PLAN_2026-09-04.md). Role checks are the only authorization gate, effective byte limits are carried by setup details, and the browser catalogue is 539 scenarios (the 18 `PW-CAPABILITY-*` degradation cases are gone). Scenario and operation counts quoted in dated evidence below (557 scenarios, 60 operations, 62 inventory methods) describe the runs that produced them and are not restated.

**Status:** COMPLETE — 100% VERIFIED

**Product boundary:** Desktop management console only

**Objective:** Prove that 100% of the externally useful PeeGeeQ Cache backend functionality is exposed through the management REST API and is operable through the production management UI.

## 1. Purpose

This is the authoritative traceability matrix for backend functionality coverage. It starts with the public backend service contracts, not with the REST operation inventory. The 60-operation OpenAPI contract is checked against that independent backend inventory; neither inventory is accepted as proof in isolation.

A backend capability is `COMPLETE` only when all of the following are present:

1. a public backend contract and production implementation;
2. a semantically complete REST, SSE, or WebSocket contract in OpenAPI;
3. production server routing and mapping;
4. a production UI client call with runtime response validation;
5. a reachable desktop UI workflow;
6. backend, REST-contract, UI-component/client, and browser evidence appropriate to the capability.

An endpoint, a generated client method, a test-only harness, or an operation-manifest row alone is not sufficient.

## 2. Status and evidence rules

| Status | Meaning |
|---|---|
| `COMPLETE` | Full backend semantics are available through REST and a reachable desktop UI workflow, with executable evidence. |
| `PARTIAL` | A path exists, but one or more backend semantics, options, result fields, or UI actions are unavailable. |
| `REST ONLY` | REST exposure exists, but the production UI has no reachable workflow for it. |
| `MISSING` | No suitable management REST operation and no production UI workflow exist. |
| `INFRASTRUCTURE` | A non-user-facing object/runtime accessor that must be tracked but should not be serialized into an interactive management operation. |
| `DECISION REQUIRED` | The capability may be deploy-time-only, but that boundary has not been explicitly approved and tested. |

Evidence codes used below:

| Code | Required evidence |
|---|---|
| `B` | Backend implementation/integration test against PostgreSQL where applicable. |
| `O` | OpenAPI and reviewed operation-manifest contract test. |
| `R` | Production REST route/protocol test. |
| `U` | Production UI client/component/page test. |
| `P` | Packaged desktop Playwright product journey with observed operation and committed-state assertions for mutations. |

`COMPLETE` normally requires `B/O/R/U/P`. A read-only aggregate may use repository evidence instead of a direct core-service test. `INFRASTRUCTURE` rows use lifecycle tests rather than browser evidence.

## 3. Current result

The implementation has **100% method-level backend functionality coverage**, and the final cumulative PostgreSQL 18.3 reactor gate is green.

Strictly counting the 32 public data-service methods in `CacheService`, `CounterService`, `LockService`, `PubSubService`/`Subscription`, `ScanService`, and `AdminService`:

| Result | Count | Percentage |
|---|---:|---:|
| `COMPLETE` | 32 | 100% |
| `PARTIAL` | 0 | 0% |
| `MISSING` | 0 | 0% |
| **Total** | **32** | **100%** |

The percentages above are method-level traceability, not a weighted score. `BackendFunctionalityInventoryTest` independently reflects all seven public data-service contracts plus `ManagementService`, asserts the exact 61-method combined inventory (32 data-service and 29 management methods), and fails if any public method lacks a reviewed OpenAPI mapping.

The 59 OpenAPI management operations are separately checked for production UI ownership, browser-journey ownership, runtime request observation, and mutation/audit/sensitive-state evidence. The merged desktop-only browser catalogue declares 539 scenarios, 17 named operation-owning journeys, and 13 isolated packaged Chromium/PostgreSQL journeys.

The capability-remediation parent passed 557/557 packaged desktop-browser scenarios plus 3/3 runnable-artifact/evidence checks on PostgreSQL 18.3 on 2 September 2026, and the fixture-lifecycle parent subsequently passed its then-current 550-scenario catalogue on 3 September. The merged U11 working tree completed the fresh cumulative gate on 5 September: 557/557 reportable browser scenarios plus all three infrastructure checks, with complete 11-module reactors green on PostgreSQL 15.17, 16.13, 17.11, and 18.3.

## 4. Source authorities

P7 screenshot acceptance on 5 September adds visual evidence without changing the 557-scenario
catalogue: all scenarios have viewport/focused captures, with 1,122 PNGs embedded in the portable
report. The fresh PostgreSQL 18.3 reactor passed 563 Failsafe tests (557 scenarios plus six
infrastructure checks). The preceding PostgreSQL 15–18 matrix is the pre-P7 baseline; see §18 of
[the Playwright implementation plan](PEEGEEQ_CACHE_PLAYWRIGHT_IMPLEMENTATION_PLAN.md).

The matrix is derived from these implementation sources:

- `peegee-cache-api/.../PeeGeeCache.java` and the public service interfaces under `peegee-cache-api/.../api`;
- runtime/configuration contracts under `peegee-cache-runtime/.../runtime`;
- PostgreSQL implementations under `peegee-cache-pg`;
- `ManagementService` and management models under `peegee-cache-api`;
- `peegee-cache-rest/src/main/openapi/peegeeq-cache-management-v1.yaml`;
- production routes under `peegee-cache-rest`;
- production clients, pages, and routing under `peegee-cache-management-ui/src`;
- Java, Vitest, and packaged Playwright tests in their respective modules.

When the implementation and a planning/status document disagree, source plus executable evidence wins and the planning/status document must be corrected.

## 5. Core cache capability matrix

| ID | Backend capability | Backend semantics that must survive | REST/OpenAPI mapping | Desktop UI workflow | Evidence presently available | Status | Gap/action |
|---|---|---|---|---|---|---|---|
| CACHE-01 | `CacheService.get(CacheKey)` | Return typed value and metadata or absence | `getEntry` plus privileged `revealEntryValue` | Key details metadata; explicit reveal/copy/hide value action | `PgCacheServiceTest`; entry read/reveal route tests; inspection client/page tests; entry browser journey | `COMPLETE` | Management intentionally separates metadata from sensitive value reveal. |
| CACHE-02 | `getMany(List<CacheKey>)` | Preserve requested-key correlation; represent individual hits/misses and typed values | `batchGetEntries` | Advanced operations multi-key batch with per-key hit/miss and typed value results | Backend service tests; `BackendCapabilityRoutesTest`; strict client/page tests; `PW-BACKEND-001` mixed hit/miss packaged journey | `COMPLETE` | Bounded to 1,000 keys; privileged values are no-store and cleared on scope/unmount. |
| CACHE-03 | `set(CacheSetRequest)` | Value type, TTL mode, write mode/CAS, and `returnPreviousValue` | `setEntry` for guarded single administration; `batchSetEntries` for the complete facade request/result including previous value | Key editor plus Advanced operations batch-set workflow with all set modes, optional TTL, expected version, and previous-value result | Backend tests; both route contracts; strict clients/pages; entry and backend-parity packaged journeys | `COMPLETE` | A one-item `batchSetEntries` request exposes every public `set` option without weakening the safer management key editor. |
| CACHE-04 | `setMany(List<CacheSetRequest>)` | Per-item typed values/options and per-item outcomes; bounded atomicity/error contract | `batchSetEntries` | Advanced operations accepts the exact JSON array so each item independently carries typed value, TTL, mode, expected version, and previous-value choice | Backend tests; route mixed-result tests; strict client/page tests with distinct per-item options; two committed PostgreSQL results in `PW-BACKEND-001` | `COMPLETE` | The facade's per-item request/result contract is preserved; request is bounded to 1,000 items. |
| CACHE-05 | `delete(CacheKey)` | Delete one key and report outcome | `deleteEntry` with exact version precondition | Key details delete action with confirmation/conflict recovery | Backend, route, UI, and browser mutation tests | `COMPLETE` | REST adds a safer exact-version precondition without removing the operation. |
| CACHE-06 | `deleteMany(List<CacheKey>)` | Bounded cross-namespace deletion of multiple exact keys and exact deleted count | `batchDeleteEntries`; the separate management preview/execute operations remain available for filter-driven administration | Advanced operations cross-namespace exact-key batch delete | `PgCacheServiceTest`; `BackendCapabilityRoutesTest`; strict client/page tests; `PW-BACKEND-001` verifies two namespaces and committed deletion | `COMPLETE` | The direct facade operation is bounded to 1,000 unique keys and durably audited. |
| CACHE-07 | `exists(CacheKey)` | Dedicated existence result without value transfer | `checkEntryExists` | Advanced operations existence check | Backend tests; metadata-safe route test; strict client/page tests; `PW-BACKEND-001` | `COMPLETE` | Transfers only the boolean result, never the cached value. |
| CACHE-08 | `ttl(CacheKey)` | Distinguish missing, persistent, and expiring entries and return remaining TTL | Entry metadata in `getEntry`/`listEntries` | Key details and list TTL presentation | Backend, inspection, UI, browser tests | `COMPLETE` | Keep exact missing/persistent/expiring semantics contract-tested. |
| CACHE-09 | `expire(CacheKey, Duration)` | Apply positive TTL to an existing entry | `expireEntry` | Key TTL action | Backend, route, UI, browser tests | `COMPLETE` | Exact-version management precondition is additive safety. |
| CACHE-10 | `persist(CacheKey)` | Remove expiry from an existing entry | `persistEntry` | Persist action | Backend, route, UI, browser tests | `COMPLETE` | — |
| CACHE-11 | `touch(CacheKey, Duration)` | Refresh TTL without changing the value/version; default/explicit TTL behavior | `touchEntry` | Touch action with default or explicit TTL | Backend, route, UI, browser tests | `COMPLETE` | Preserve the no-version-increment contract. |

## 6. Counter capability matrix

| ID | Backend capability | Backend semantics that must survive | REST/OpenAPI mapping | Desktop UI workflow | Evidence presently available | Status | Gap/action |
|---|---|---|---|---|---|---|---|
| COUNTER-01 | `increment(CacheKey)` | Atomic `+1`, including documented missing-counter behavior | `adjustCounter` with delta `1` and `createIfMissing` | Existing adjustment and create-by-adjustment with optional creation TTL | Backend/route overflow and creation tests; page test; `PW-COUNTER-001` packaged creation/committed-state journey | `COMPLETE` | Exact decimal-string transport preserves signed 64-bit values. |
| COUNTER-02 | `incrementBy(CacheKey,long)` | Atomic positive delta, overflow behavior, missing-counter behavior | `adjustCounter` with positive signed delta | Same complete signed-adjustment workflow | Same as COUNTER-01, including parameterized positive and overflow browser cases | `COMPLETE` | — |
| COUNTER-03 | `decrement(CacheKey)` | Atomic `-1`, overflow behavior, missing-counter behavior | `adjustCounter` with delta `-1` | Same complete signed-adjustment workflow | Same as COUNTER-01, including negative packaged evidence | `COMPLETE` | — |
| COUNTER-04 | `decrementBy(CacheKey,long)` | Atomic decrement by requested magnitude, overflow behavior, missing-counter behavior | `adjustCounter` with negative signed delta | Same complete signed-adjustment workflow | Same as COUNTER-01; `PW-COUNTER-001` creates `-7` with TTL and verifies PostgreSQL | `COMPLETE` | The UI accepts a signed delta directly, avoiding magnitude/sign translation ambiguity. |
| COUNTER-05 | `getValue(CacheKey)` | Exact signed 64-bit value or absence | `getCounter` | Counter details | `PgCounterServiceTest`; route/client/page tests; exact-64-bit browser journey | `COMPLETE` | Decimal-string transport prevents JavaScript precision loss. |
| COUNTER-06 | `setValue(CacheKey,long,CounterOptions)` | Exact signed 64-bit value, create/update mode, TTL options | `setCounter` | Create/set counter with exact value and TTL actions | Backend, REST, UI, browser tests | `COMPLETE` | Persist is a separate UI action but covers TTL removal. |
| COUNTER-07 | `ttl(CacheKey)` | Missing/persistent/expiring distinction and remaining TTL | Counter metadata in `getCounter`/`listCounters` | Counter list/details TTL | Backend, REST, UI, browser tests | `COMPLETE` | — |
| COUNTER-08 | `expire(CacheKey,Duration)` | Apply positive TTL atomically | `expireCounter` | Counter TTL action | Backend, REST, UI, browser tests | `COMPLETE` | — |
| COUNTER-09 | `persist(CacheKey)` | Remove expiry atomically | `persistCounter` | Persist action | Backend, REST, UI, browser tests | `COMPLETE` | — |
| COUNTER-10 | `delete(CacheKey)` | Delete and report outcome | `deleteCounter`; bulk equivalent also exists | Individual delete and bulk delete | Backend, REST, UI, browser tests | `COMPLETE` | — |

## 7. Lock capability matrix

| ID | Backend capability | Backend semantics that must survive | REST/OpenAPI mapping | Desktop UI workflow | Evidence presently available | Status | Gap/action |
|---|---|---|---|---|---|---|---|
| LOCK-01 | `acquire(LockAcquireRequest)` | Owner token, lease, acquisition result, monotonically increasing fencing token | `acquireLock` | Advanced operations owner-token lock lifecycle | `PgLockServiceTest`; route protocol/audit tests; strict client/page tests; `PW-BACKEND-001` | `COMPLETE` | Owner token is request-body only, no-store, never ordinary metadata, and cleared after release/scope/unmount. |
| LOCK-02 | `renew(LockRenewRequest)` | Owner-authenticated renewal, lease update, fencing/version semantics | `renewLock` | Renew action within the acquired owner-token lifecycle | Same full evidence chain as LOCK-01 | `COMPLETE` | — |
| LOCK-03 | `release(LockReleaseRequest)` | Owner-authenticated normal release | `releaseLock`; administrative `forceReleaseLock` remains separate | Normal owner release in Advanced operations; force release remains in Locks | Backend normal/force tests; both route/UI paths; `PW-BACKEND-001` verifies release in PostgreSQL | `COMPLETE` | Normal release is not conflated with force release. |
| LOCK-04 | `isHeldBy(LockKey,String)` | Check ownership without revealing owner token | `checkLockOwnership` | Ownership check in Advanced operations | Backend, route non-disclosure, strict client/page, and packaged journey evidence | `COMPLETE` | Returns only `heldByOwner`; it does not return the stored owner token. |
| LOCK-05 | `currentLock(LockKey)` | Current lease/fencing/version metadata and optional privileged owner reveal | `getLock`; privileged `revealLockOwner` | Lock list/details and explicit owner reveal | Backend, REST, UI, browser lock tests | `COMPLETE` | Administrative `forceReleaseLock` is an additional management capability. |

## 8. Pub/Sub, scan, and administration matrix

| ID | Backend capability | Backend semantics that must survive | REST/OpenAPI mapping | Desktop UI workflow | Evidence presently available | Status | Gap/action |
|---|---|---|---|---|---|---|---|
| PUBSUB-01 | `publish(PublishRequest)` | Channel, nullable content type, payload, publication acceptance | `publishPubSubMessage` | Publish form with optional content type; retained metadata and reveal display it | Versioned-codec unit tests; real PostgreSQL repository/service tests; REST integration; strict UI tests; typed pub/sub packaged journey | `COMPLETE` | Plain payloads remain native-compatible. Typed payloads use the reserved `__PGQ_CACHE_TYPED_V1__:` envelope; the receiver decodes it and preserves both public record fields. |
| PUBSUB-02 | `subscribe(channel,consumer)` | Subscribe to a channel and receive messages | `createPubSubSubscription` + `streamPubSubMessages` SSE + privileged `revealPubSubPayload` | Start subscription, live metadata list, and explicit payload reveal | Backend, REST/SSE, UI live-client/page, browser tests | `COMPLETE` | Transport adds bounded retention/resume and sensitive-payload reveal semantics. |
| PUBSUB-03 | `Subscription.unsubscribe()` | Deterministic unsubscribe and resource cleanup | `deletePubSubSubscription` | Stop subscription; page/scope/session cleanup | Backend, REST, UI, browser resource-leak evidence | `COMPLETE` | `Subscription.channel()` is result metadata, not a separate product operation. |
| SCAN-01 | `scan(ScanRequest)` | Namespace/prefix, opaque cursor, limit, include-expired, `includeValues` | `scanEntries` for full facade semantics; `listEntries` for metadata administration | Advanced operations value scan plus existing Keys/Namespace metadata lists | `PgScanServiceTest`; route cursor/value tests; strict client/page tests; `PW-BACKEND-001` | `COMPLETE` | Privileged value scan is bounded to 200, no-store, and cleared on scope/unmount. |
| ADMIN-01 | `entryStats(namespace)` | Exact namespace cache-entry, counter, and active-lock counts | `getNamespace`, `getOverview`, `getDatabaseMonitoring` | Overview and namespace details | `PgAdminServiceTest`; management repository; REST/UI/browser aggregate tests | `COMPLETE` | Expiry and value-type distributions are separate management aggregates, not fields returned by `EntryStats`. |
| ADMIN-02 | `metrics()` | Exact `MetricsSnapshot` operation counters | `getCacheMetrics` | Advanced operations exact cache metrics plus management Monitoring panels | Backend metrics tests; exact-field/64-bit route test; strict client/page test; `PW-BACKEND-001` | `COMPLETE` | Every snapshot counter is transported as a decimal string. Latency and failure data belong to management runtime monitoring, not `MetricsSnapshot`. |

## 9. ManagementService traceability

These are management-specific capabilities layered over the core services. Their completeness does not erase the core gaps above.

| Management method | OpenAPI operation(s) | Production desktop workflow | Required evidence | Status |
|---|---|---|---|---|
| `overview` | `getOverview` | Overview page | `B/O/R/U/P` | `COMPLETE` |
| `databaseMonitoring` | `getDatabaseMonitoring` | Overview/Monitoring database panels | `B/O/R/U/P` | `COMPLETE` |
| `namespaces` | `listNamespaces`, `exportNamespaces` | Namespaces list, filters, pagination, export | `B/O/R/U/P` | `COMPLETE` |
| `namespace` | `getNamespace` | Namespace details | `B/O/R/U/P` | `COMPLETE` |
| `entries` | `listEntries` | Keys and namespace entry lists | `B/O/R/U/P` | `COMPLETE` for management metadata inspection; see SCAN-01 for core scan parity |
| `entry` | `getEntry` | Key details | `B/O/R/U/P` | `COMPLETE` |
| `revealEntry` | `revealEntryValue` | Explicit time-bounded value reveal/copy/hide | `B/O/R/U/P` | `COMPLETE` |
| `setEntry` | `setEntry` | Create/edit entry | `B/O/R/U/P` | `COMPLETE`; complete facade option parity is supplied by `batchSetEntries` |
| `expireEntry` | `expireEntry` | Entry TTL action | `B/O/R/U/P` | `COMPLETE` |
| `persistEntry` | `persistEntry` | Entry persist action | `B/O/R/U/P` | `COMPLETE` |
| `touchEntry` | `touchEntry` | Entry touch action | `B/O/R/U/P` | `COMPLETE` |
| `deleteEntry` | `deleteEntry` | Entry delete action | `B/O/R/U/P` | `COMPLETE` |
| `counters` | `listCounters` | Counters list | `B/O/R/U/P` | `COMPLETE` |
| `counter` | `getCounter` | Counter details | `B/O/R/U/P` | `COMPLETE` |
| `setCounter` | `setCounter` | Create/set counter | `B/O/R/U/P` | `COMPLETE` |
| `adjustCounter` | `adjustCounter` | Existing and create-if-missing signed adjustment with creation TTL | `B/O/R/U/P` | `COMPLETE` |
| `expireCounter` | `expireCounter` | Counter TTL action | `B/O/R/U/P` | `COMPLETE` |
| `persistCounter` | `persistCounter` | Counter persist action | `B/O/R/U/P` | `COMPLETE` |
| `deleteCounter` | `deleteCounter` | Counter delete action | `B/O/R/U/P` | `COMPLETE` |
| `locks` | `listLocks` | Locks list | `B/O/R/U/P` | `COMPLETE` for inspection only |
| `lock` | `getLock` | Lock details | `B/O/R/U/P` | `COMPLETE` for inspection only |
| `revealLockOwner` | `revealLockOwner` | Explicit privileged owner reveal | `B/O/R/U/P` | `COMPLETE` |
| `forceReleaseLock` | `forceReleaseLock` | Guarded administrative force release | `B/O/R/U/P` | `COMPLETE`; not a substitute for owner release |
| `databaseStats` | Exact `databaseStats` snapshot nested in `getOverview` | Overview database bytes, schema bytes, availability, and observation time | `B/O/R/U/P` | `COMPLETE` |
| `expiryStats` | Exact `expiryStats` snapshot nested in `getOverview` | Overview expired entry/counter counts, lag availability, and observation time | `B/O/R/U/P` | `COMPLETE` |
| `previewEntryDelete` | `previewEntryBulkDelete` | Entry bulk-delete preview | `B/O/R/U/P` | `COMPLETE` |
| `executeEntryDelete` | `executeEntryBulkDelete` | Confirmed entry bulk delete | `B/O/R/U/P` | `COMPLETE` |
| `previewCounterDelete` | `previewCounterBulkDelete` | Counter bulk-delete preview | `B/O/R/U/P` | `COMPLETE` |
| `executeCounterDelete` | `executeCounterBulkDelete` | Confirmed counter bulk delete | `B/O/R/U/P` | `COMPLETE` |

The following operations expose the remaining public facade methods directly. They are deliberately separate from `ManagementService` because they preserve the core batch, value-scan, metrics-snapshot, and owner-lock contracts.

| Public facade capability | OpenAPI operation | Production desktop workflow | Required evidence | Status |
|---|---|---|---|---|
| `CacheService.exists` | `checkEntryExists` | Advanced operations existence check | `B/O/R/U/P` | `COMPLETE` |
| `CacheService.getMany` | `batchGetEntries` | Advanced operations batch get | `B/O/R/U/P` | `COMPLETE` |
| `CacheService.set` / `setMany` complete semantics | `batchSetEntries` | Advanced operations batch set | `B/O/R/U/P` | `COMPLETE` |
| `CacheService.deleteMany` complete semantics | `batchDeleteEntries` | Advanced operations cross-namespace batch delete | `B/O/R/U/P` | `COMPLETE` |
| `ScanService.scan` complete semantics | `scanEntries` | Advanced operations value scan | `B/O/R/U/P` | `COMPLETE` |
| `AdminService.metrics` | `getCacheMetrics` | Advanced operations exact metrics | `B/O/R/U/P` | `COMPLETE` |
| `LockService.acquire` | `acquireLock` | Advanced operations owner-lock lifecycle | `B/O/R/U/P` | `COMPLETE` |
| `LockService.renew` | `renewLock` | Advanced operations owner-lock lifecycle | `B/O/R/U/P` | `COMPLETE` |
| `LockService.release` | `releaseLock` | Advanced operations owner-lock lifecycle | `B/O/R/U/P` | `COMPLETE` |
| `LockService.isHeldBy` | `checkLockOwnership` | Advanced operations owner-lock lifecycle | `B/O/R/U/P` | `COMPLETE` |

## 10. REST host and console-operation traceability

These operations make the management application usable but are not substitutes for cache-service capability coverage.

| Surface | OpenAPI operations | Production desktop workflow | Status |
|---|---|---|---|
| Session | `getSession`, `exchangeLocalToken`, `deleteLocalSession` | Login/bootstrap, shell identity, logout/expiry cleanup | `COMPLETE` |
| Setup registry | `listSetups`, `registerSetup`, `getSetup`, `forgetSetup` | Setups list, registration, details, forget | `COMPLETE` |
| Setup validation/lifecycle | `testUnregisteredSetup`, `connectSetup`, `testRegisteredSetup`, `detachSetup` | Test form/setup, connect, retest, detach | `COMPLETE` |
| Setup state | `getSetupHealth` | Setup health/details and effective limits | `COMPLETE` |
| Runtime monitoring | `getRuntimeMonitoring`, `streamMetrics`, `listActivity`, `monitoringWebSocket` | Monitoring, activity, live state, shell notifications | `COMPLETE` |

The 60-operation accountability contract proves that every declared OpenAPI operation has a production owner and is observed in its claimed browser journey. `BackendFunctionalityInventoryTest` supplies the independent reverse check from all 32 public data-service methods and all 30 `ManagementService` methods into those operations.

## 11. Runtime, configuration, and lifecycle matrix

| ID | Backend/runtime capability | Current management exposure | Status | Required action |
|---|---|---|---|---|
| RUNTIME-01 | Create/register a runtime and PostgreSQL pool | Setup registration with connection, schema, trust, SSL, and pool maximum | `COMPLETE` | Retain secret redaction and target policy. |
| RUNTIME-02 | Start/connect runtime | `connectSetup` | `COMPLETE` | Lifecycle and readiness tests remain required. |
| RUNTIME-03 | Stop/detach runtime | `detachSetup` | `COMPLETE` | Verify live transport and resource cleanup. |
| RUNTIME-04 | Started/readiness state | Setup health/details and runtime monitoring | `COMPLETE` | — |
| RUNTIME-05 | Close/forget registered runtime | `forgetSetup` after lifecycle guards | `COMPLETE` | — |
| RUNTIME-06 | `vertx()` and `pool()` object accessors | Not serialized | `INFRASTRUCTURE` | Keep as internal object access; prove lifecycle indirectly. |
| CONFIG-01 | `defaultTtl` | Registration/test contract, setup details, and runtime behavior controls | `COMPLETE` | Real PostgreSQL test proves an omitted per-entry TTL receives the configured default. |
| CONFIG-02 | Expiry sweeper enabled/interval/batch size | Registration/test contract, setup details, and runtime behavior controls | `COMPLETE` | Factory logs and integration tests prove configured scheduling values. |
| CONFIG-03 | Write-behind enabled/flush interval/max buffer/flush batch/retries/shutdown drain | Registration/test contract, setup details, and runtime behavior controls | `COMPLETE` | Configuration mapping, combination validation, route round trip, and UI tests cover every value. Runtime backlog/failure metrics remain monitoring data, not configuration exposure. |
| CONFIG-04 | PostgreSQL schema | Setup registration form/contract | `COMPLETE` | Keep validation and canonical display. |
| CONFIG-05 | Pub/Sub channel prefix | Registration/test contract, setup details, and runtime behavior controls | `COMPLETE` | The validated prefix is passed to the production runtime, and the advertised usable channel-byte limit is derived from the effective prefix plus PostgreSQL's 63-byte identifier ceiling. |
| CONFIG-06 | Pub/Sub connection enablement | Explicit registration control and setup details `runtime.pubSubEnabled` | `COMPLETE` | Real PostgreSQL integration proves disabled mode starts without a listener and reports `pubSubEnabled=false` in setup details. |
| CONFIG-07 | Schema bootstrap mode | Explicit `EXTERNAL`/`APPLY` registration control and setup details | `COMPLETE` | Factory tests cover `EXTERNAL`; the packaged setup journey registers with `APPLY` and verifies migration/startup; a real-PostgreSQL regression retests the registered APPLY runtime and proves shutdown leaves zero setup application connections. |
| CONFIG-08 | Telemetry adapter | Explicit `NOOP` mode plus built-in exact management metrics | `COMPLETE` | `NOOP` is the only telemetry adapter shipped by the public runtime; the enum and UI intentionally expose exactly that supported set. `getCacheMetrics` preserves `MetricsSnapshot` semantics independently. |
| CONFIG-09 | Pool maximum size | Setup registration and setup details | `COMPLETE` | — |

## 12. Closed gap register

| Former gap | Closure | Executable proof |
|---|---|---|
| `GAP-LOCK-001`–`004` | Added acquire, renew, normal release, and non-disclosing ownership check across REST and Advanced operations UI. | Backend lock suite, `BackendCapabilityRoutesTest`, strict UI tests, `PW-BACKEND-001`. |
| `GAP-CACHE-001`–`004` | Added batch get, complete batch/single set semantics including previous value, and dedicated existence. | Backend cache suite, inventory/route/OpenAPI gates, strict UI tests, `PW-BACKEND-001`. |
| `GAP-COUNTER-001` | Added create-if-missing signed adjustment with optional creation TTL. | Route tests, page tests, and `PW-COUNTER-001` committed PostgreSQL assertion. |
| `GAP-SCAN-001` | Added bounded privileged value scan preserving every `ScanRequest` option/result. | Backend scan suite, route/client/page tests, `PW-BACKEND-001`. |
| `GAP-ADMIN-001` | Added exact `MetricsSnapshot` endpoint and UI panel. | Exact-field/precision route and UI tests plus `PW-BACKEND-001`. |
| `GAP-PARITY-001`–`005` | Corrected the false-complete mappings for cross-namespace `deleteMany`, per-item `setMany`, Pub/Sub `contentType`, namespace TTL-state counts, and exact database/expiry aggregate fields. | Strengthened 62-method inventory, exact OpenAPI schema assertions, route/client/component tests, real PostgreSQL Pub/Sub tests, and `PW-BACKEND-001`. |
| `GAP-RUNTIME-001`–`005` | Added complete runtime configuration contract, factory wiring, setup UI/details, prefix-aware limit derivation, and supported telemetry mode. | Configuration mapping/validation tests, real PostgreSQL effect/retest/cleanup tests, setup UI tests, and packaged setup journey. |

## 13. Required closure gates

The objective can be marked complete only when all of these gates pass:

1. **Backend inventory gate:** an executable source/reflection contract enumerates every public operation on the facade services and fails when an operation is added without a matrix mapping.
2. **Backend-to-OpenAPI gate:** every non-`INFRASTRUCTURE` operation maps to one or more exact OpenAPI `operationId` values and explicitly lists every option/result semantic.
3. **OpenAPI-to-server gate:** every mapped operation has production route/protocol tests, authorization, validation, limits, audit rules for mutations/reveals, and safe error behavior.
4. **Server-to-client gate:** every operation used by the UI is generated or explicitly implemented in a production client and validates all responses/events at runtime.
5. **Client-to-workflow gate:** every backend capability has a reachable desktop route/action; hidden test controls and test-only harnesses do not count.
6. **Workflow-to-browser gate:** each capability has an independently named packaged-application journey that observes its real request/transport and, for mutations, verifies committed PostgreSQL state.
7. **Semantic parity gate:** all request options, result variants, precision, TTL, conditional, concurrency, security, and lifecycle semantics are either exposed or documented as an explicitly approved product invariant. Silent omission is a failure.
8. **Zero-gap gate:** no row remains `PARTIAL`, `REST ONLY`, `MISSING`, or `DECISION REQUIRED`.
9. **Documentation gate:** implementation plans, operation manifest, API/UI designs, operations guide, and this matrix report the same evidence-backed status.

## 14. Maintenance rules

- Any public backend method, option, enum value, or result-field addition must update this matrix in the same change.
- Any REST operation addition/removal must update the operation manifest, OpenAPI accountability test, UI owner registry, and this matrix.
- A status may move to `COMPLETE` only in the same change that adds the required executable evidence.
- A passing test may not justify `COMPLETE` if it exercises a mock, request interception, test-only route, or unreachable UI control instead of the packaged production workflow.
- Desktop presentation and accessibility are quality constraints on the required workflows; they are not additional backend functionality and do not add rows to this matrix.
- Counts in Section 3 must be regenerated whenever a core row changes status. Runtime/configuration is reported separately.
