# PeeGeeQ Cache Management API Implementation Plan

**Status:** Phase M0 complete; Phase M1 in progress

**Date:** 17 August 2026

**Delivery method:** Strict test-driven development

## 1. Purpose

This document turns the reviewed contract in [PEEGEEQ_CACHE_MANAGEMENT_API.md](PEEGEEQ_CACHE_MANAGEMENT_API.md) into an executable implementation sequence for the management backend.

It covers:

- the typed management API added to `peegee-cache-api`;
- the real PostgreSQL implementation in `peegee-cache-pg`;
- the Vert.x HTTP, SSE, WebSocket, authentication, audit, and setup-lifecycle server in `peegee-cache-rest`;
- backend fixtures in `peegee-cache-test-support`;
- OpenAPI and browser-facing compatibility gates consumed by `peegee-cache-management-ui`.

It does not mark any management feature as implemented. Status changes require the evidence defined in this plan.

## 2. Authority and prerequisite documents

Implementation uses these sources in descending order of authority:

1. [PEEGEEQ_CACHE_MANAGEMENT_API.md](PEEGEEQ_CACHE_MANAGEMENT_API.md) for HTTP, security, streaming, error, concurrency, Java service, and compatibility contracts;
2. this document for implementation order, tests, and exit gates;
3. [PEEGEEQ_CACHE_MANAGEMENT_UI_DESIGN.md](PEEGEEQ_CACHE_MANAGEMENT_UI_DESIGN.md) for product behavior and screen requirements;
4. [PEEGEEQ_CACHE_IMPLEMENTATION_PLAN.md](PEEGEEQ_CACHE_IMPLEMENTATION_PLAN.md) for whole-project status and release sequencing;
5. [PEEGEEQ_CACHE_TEST_COVERAGE_AND_TDD_APPROACH.md](../guidelines/PEEGEEQ_CACHE_TEST_COVERAGE_AND_TDD_APPROACH.md) and the repository engineering rules for test discipline.

The management UI design predates the latest API review. Phase M0 must synchronize its authentication, audit, Java signature, pub/sub, and link text before implementation uses it. When documents disagree before that synchronization, the management API contract wins.

## 3. Scope boundaries

### 3.1 Included

- metadata-only namespace, entry, counter, and lock inspection;
- atomic version-checked entry, counter, and forced-lock mutations;
- sensitive entry-value, lock-owner, and retained-payload reveal;
- setup test/register/connect/detach/forget lifecycle;
- database and management-runtime monitoring;
- actor-bound bulk preview and execution;
- PostgreSQL-compatible pub/sub publish and bounded console subscriptions;
- REST, SSE, and WebSocket transports;
- trusted-proxy and local-token authentication;
- server-side authorization, CSRF/origin enforcement, setup target policy, TLS trust selection, and rate limits;
- fail-closed security audit acceptance and safe structured logging;
- OpenAPI 3.1, contract tests, packaging, and operational documentation;
- mandatory observability for lifecycle, requests, failures, streams, audit pressure, and resource saturation.

### 3.2 Excluded

- implementation of the production React screens beyond the generated/validated client boundary, backend static-hosting contract, and a backend-owned non-production browser acceptance harness;
- database/schema drop, namespace-wide mixed-resource purge, bulk lock release, or lock acquisition/renewal;
- persistent UI-entered credentials or management users;
- Redis protocol, SQL workbench, or arbitrary query execution;
- durable pub/sub history or cross-process resource-change capture;
- production deployment and external identity-provider configuration.

Production frontend implementation is tracked separately as authoritative Phase 8.3 and requires its own detailed TDD plan after the API reaches a stable OpenAPI milestone. This backend plan owns a minimal non-production browser harness under test sources solely to verify cookies, CSRF/origin behavior, cache directives, storage exclusion, and static-resource routing without depending on the production UI.

## 4. Module and dependency design

### 4.1 Module graph

```text
peegee-cache-management-ui
        │ generated client / compiled webroot
        ▼
peegee-cache-rest
        ├── peegee-cache-api
        ├── peegee-cache-runtime
        ├── peegee-cache-observability
        └── peegee-cache-test-support (test scope)
                    │
                    ▼
peegee-cache-runtime ──► peegee-cache-pg ──► peegee-cache-core ──► peegee-cache-api
```

No dependency points from the cache library modules into REST or UI code.

### 4.2 Ownership

| Module | New responsibility |
|---|---|
| `peegee-cache-api` | `ManagementService`, immutable queries/results, typed mutation outcomes, reveal DTOs, action context, capabilities, and audit-sink SPI |
| `peegee-cache-core` | Pure management validation or safe transformation helpers only when shared below the PostgreSQL/REST boundary |
| `peegee-cache-pg` | Parameterized inspection SQL, atomic outcome-producing mutations, row mapping, and PostgreSQL management service |
| `peegee-cache-runtime` | Expose the PostgreSQL management service through `PeeGeeCache`; no HTTP/security ownership |
| `peegee-cache-observability` | Management metrics/tracing adapters and readiness integration where they extend the existing production contract |
| `peegee-cache-test-support` | Reusable real-server, PostgreSQL, proxy-auth, SSE, and WebSocket fixtures without application behavior |
| `peegee-cache-rest` | OpenAPI, server lifecycle, setup registry, authentication/authorization, CSRF/origin and target policy, rate limits, audit queue, routes, streams, serialization, and static UI hosting |
| `peegee-cache-management-ui` | OpenAPI consumer and compiled static resources; full screen work is outside this backend plan |

### 4.3 Build constraints

- Build JDK: 21 through 26; validation is run under `C:\Users\mraysmit\.jdks\openjdk-26.0.2` locally.
- Published bytecode target remains Java 21.
- Public async contracts use Vert.x 5.x `Future`.
- Library modules depend on `slf4j-api` only; runnable REST code supplies one documented provider.
- No credentials, trust stores, tokens, or machine-specific paths are committed.
- PostgreSQL 18.3 is the default integration image; final database work runs on PostgreSQL 15–18.

## 5. Non-negotiable TDD protocol

Every testable behavior follows this exact loop:

1. add one focused test for one observable contract;
2. run the narrowest command that executes it;
3. confirm it fails for the expected missing behavior, not a broken fixture or unrelated compilation error;
4. record the red command and failure reason in the working evidence;
5. implement the minimum production code needed for that test;
6. rerun the focused test and inspect the test name, assertion count, logs, and result;
7. run the affected module suite;
8. refactor only while all affected tests are green;
9. run the next wider reactor gate before completing the slice.

Rules:

- no production behavior is written before its failing test;
- do not create a batch of tests and then implement them all at once;
- do not weaken, delete, disable, quarantine, or exclude a failing test to advance a phase;
- Mockito and every substitute mocking framework are prohibited;
- database behavior uses real PostgreSQL Testcontainers, never mocked pools/connections or an in-memory substitute;
- HTTP behavior uses a running Vert.x server and protocol client, not direct handler invocation alone;
- SSE and WebSocket behavior is verified at the framing/connection boundary;
- purpose-built fakes are limited to deterministic pure boundaries such as `Clock`, token entropy, DNS resolution, or audit-sink availability, and are paired with real integration coverage for the adapter;
- asynchronous failures must be observed; no empty catch blocks, ignored futures, event-loop blocking, or `Thread.sleep()` timing tests;
- logs are written beneath `logs/`, inspected after each wider run, and checked for secret or raw-identifier leakage;
- a suspiciously fast or empty-success run is invalid until the expected test classes and counts are confirmed.

### 5.1 Definition of a valid red test

A red test is valid only when:

- the intended test method executed or failed to compile because the next public type deliberately does not exist;
- the failure message demonstrates the missing contract;
- the fixture, container, port allocation, and test discovery are healthy;
- no earlier unrelated failure prevents reaching the assertion.

### 5.2 Test classification

| Behavior | Required test boundary |
|---|---|
| Records, validation, codecs, ETags, cursor signing, DTO serialization | JUnit pure unit test |
| SQL, database clock, pagination, visibility, atomic outcomes, concurrency | JUnit + real PostgreSQL Testcontainers |
| Runtime/setup lifecycle and Vert.x composition | Vert.x integration test + real PostgreSQL where applicable |
| Authentication, authorization, CSRF, CORS, request limits, REST mapping | Running Vert.x HTTP server + real protocol client |
| DNS/target policy | Pure resolver-policy tests plus connection integration tests |
| SSE/WebSocket framing, resume, reset, backpressure, shutdown | Running server + protocol-level client |
| Browser storage, cookie, origin, and no-store behavior | Playwright against real server |
| OpenAPI completeness and implementation drift | OpenAPI parser/validator plus route inventory contract test |

## 6. Test infrastructure established before feature work

The first test harness must provide:

- ephemeral ports for HTTP and PostgreSQL;
- a real PostgreSQL container using the existing shared support;
- deterministic server startup/shutdown with leak checks;
- a purpose-built trusted-proxy request fixture that cannot bypass server authorization;
- a local-token session fixture with cookie and CSRF handling;
- raw HTTP/SSE/WebSocket clients for transport assertions;
- bounded test clocks/token sources/resolvers only at their explicit pure boundaries;
- log capture beneath `logs/` with forbidden-value scanning;
- OpenAPI parsing and route inventory comparison;
- test data builders that never conceal SQL or lifecycle behavior.

Fixtures remain in the lowest reusable module that does not create a production dependency cycle. A fixture is not allowed to reproduce production authorization, cursor, mutation, or serialization logic independently.

## 7. Phase map

| Phase | Status | Primary deliverable |
|---|---|---|
| M0 | COMPLETE | Contract synchronization and build decision record |
| M1 | IN PROGRESS | Module skeleton, OpenAPI baseline, and protocol primitives |
| M2 | NOT STARTED | Typed Java management contract |
| M3 | NOT STARTED | PostgreSQL inspection/read model |
| M4 | NOT STARTED | Atomic PostgreSQL mutation and reveal model |
| M5 | NOT STARTED | Audit, authentication, CSRF/origin, target policy, and rate-limit primitives |
| M6 | NOT STARTED | Setup registry and REST server lifecycle |
| M7 | NOT STARTED | Session, setup, health, capability, and read-only REST endpoints |
| M8 | NOT STARTED | Reveal, mutation, and bulk REST endpoints |
| M9 | NOT STARTED | Pub/sub, SSE, and WebSocket transports |
| M10 | NOT STARTED | Monitoring, observability, packaging, compatibility, and final acceptance |

Only one phase may be `IN PROGRESS`. A phase remains incomplete if any required test is missing, skipped, flaky, or passing only through a relaxed assertion.

## 8. Phase M0 — Contract and build synchronization

Objective: remove contradictory inputs before code or OpenAPI generation.

Evidence artifacts:

- [PEEGEEQ_CACHE_MANAGEMENT_BUILD_DECISION.md](PEEGEEQ_CACHE_MANAGEMENT_BUILD_DECISION.md) records the accepted sibling-module Maven topology and REST configuration/secret-reference invariants.
- [PEEGEEQ_CACHE_MANAGEMENT_OPERATION_MANIFEST.md](PEEGEEQ_CACHE_MANAGEMENT_OPERATION_MANIFEST.md) closes all 50 exact V1 operations and their security, schema, status, header, error, capability, limit, audit, and retry contracts.

Tasks:

1. update `PEEGEEQ_CACHE_MANAGEMENT_UI_DESIGN.md` to match the reviewed API for bounded management sessions, single-use local-token authentication, the bootstrap CSRF exception, typed mutation outcomes, reserved/durable audit outcomes, keyed audit fingerprints, V001 migration state, PostgreSQL pub/sub limits, nullable content type, and publication acceptance;
2. correct its management UI mockup link;
3. add `peegee-cache-rest` and `peegee-cache-management-ui` module/build decisions to the parent architecture without implementing behavior;
4. decide whether the UI is a Maven child module or a frontend build invoked by `peegee-cache-rest`, while preserving one reproducible root build;
5. document REST runtime configuration records and secret-reference shapes without embedding environment defaults in library code;
6. produce a complete reviewed operation manifest containing the exact method/path, security rules, schemas, statuses, headers, errors, capabilities, limits, audit behavior, and retry semantics for every route;
7. replace prose-only aggregate models and ambiguous mutation behavior, including touch version stability and wildcard-precondition outcomes, before OpenAPI generation;
8. verify that the authoritative project plan continues to track backend Phase 8.2 and separate browser-console Phase 8.3 as deferred/not started.

Verification:

- all local design links resolve;
- API/UI endpoint inventories, session behavior, audit semantics, and security terminology agree;
- every REST operation has a complete manifest row and no normative path uses an abbreviated form;
- `mvn validate` succeeds with the approved module and root-build topology applied;
- no management implementation class exists yet.

Exit criteria: there is one non-contradictory contract and an accepted module/build shape.

Status: **COMPLETE** — the UI design is synchronized with the API authority; all 50 exact operations are closed in the reviewed manifest; configuration and secret-reference shapes and the two-child-module build topology are accepted; both empty module boundaries are present in the 11-project root reactor; all local Markdown links under `docs/design` resolve; `mvn validate` succeeds for all 11 projects; and neither management module contains a Java implementation class.

## 9. Phase M1 — OpenAPI baseline and protocol primitives

Objective: make the machine-readable boundary and pure protocol rules executable before route implementation.

### M1.1 OpenAPI skeleton

First red tests:

- `ManagementOpenApiContractTest.loadsOpenApi31Document`;
- `ManagementOpenApiContractTest.matchesReviewedOperationManifest`;
- `ManagementOpenApiContractTest.declaresSecurityAndProblemResponsesForEveryProtectedOperation`;
- `ManagementOpenApiContractTest.declaresSseAndWebSocketComponentSchemas`.

Minimum implementation: create `peegee-cache-rest/src/main/openapi/peegeeq-cache-management-v1.yaml` from the reviewed M0 operation manifest, containing operation IDs, complete schemas, security requirements including the local-bootstrap exception, documented response statuses/headers, and transport extensions. No handler implementation is added in this slice.

### M1.2 Identifier codec

First red tests, one at a time:

- arbitrary UTF-8 round trip through canonical unpadded Base64 URL encoding;
- reject malformed Base64 URL, non-canonical padding, invalid UTF-8, empty input, NUL, namespace over 128 bytes, and key over 1,024 bytes;
- preserve `/`, `%`, `+`, `:`, whitespace, and multibyte characters as identifier data.

Minimum implementation: one shared codec used later by routes and generated links.

### M1.3 ETag and precondition codec

First red tests:

- parse and render exact `"v{decimal}"` tags;
- recognize `If-Match: *` and `If-None-Match: *` only where allowed;
- reject weak tags, multiple tags, overflow, negative versions, malformed quoting, and conflicting headers;
- map missing required headers to `PRECONDITION_REQUIRED`.

### M1.4 Cursor contract

First red tests:

- signed cursor round trip with endpoint/setup/namespace/filter/sort scope;
- reject tampering, expiry, wrong scope, unknown version, and malformed payload;
- retain complete composite positions for `entryCount:desc,namespace:asc` and identifier ordering;
- prove cursor data never becomes SQL text.

Minimum implementation: versioned cursor codec using authenticated server-held key material and injected `Clock`.

### M1.5 Common wire rules

First red tests:

- decimal-string serialization for every Java/PostgreSQL 64-bit value;
- UTC `Z` timestamp serialization;
- RFC 9457-style problem body fields and safe detail redaction;
- strict media type, request size, JSON syntax, and correlation-ID behavior;
- literal prefix escaping for `%`, `_`, and `\`.

Phase gate:

- all M1 focused tests green;
- OpenAPI validation green;
- `peegee-cache-rest` module test suite green;
- no database or route behavior implemented prematurely.

Current M1.1 evidence (in progress):

- `loadsOpenApi31Document` red: the packaged OpenAPI resource was absent; green after adding the minimal `3.1.0` document at the required resource path.
- `matchesReviewedOperationManifest` red: actual operation inventory was empty; green after declaring the exact 50 method/path/operation-ID tuples.
- `declaresSecurityAndProblemResponsesForEveryProtectedOperation` red first on the missing problem component and then exposed unsupported YAML merge-key inheritance; green only after every operation explicitly declared its effective security profile, OpenAPI security requirement or bootstrap exception, and reusable problem response.
- `declaresSseAndWebSocketComponentSchemas` red: transport schemas were absent; green after declaring pub/sub SSE, metrics SSE, monitoring WebSocket, nullable pub/sub content type, and reset schemas/extensions.
- `mvn -pl peegee-cache-rest test` passes 4 tests with zero failures/errors/skips; `mvn validate` passes all 11 reactor projects; no route or management Java implementation exists.
- M1.1 remains open until every operation has its complete request/parameter/success-response/header schema and a standards-aware OpenAPI validation test. M1.2 identifier-codec work must not begin before that closure.

## 10. Phase M2 — Typed Java management contract

Objective: add a complete API surface capable of representing the HTTP concurrency and audit contract without follow-up inference.

### M2.1 Immutable model validation

Write failing unit tests before each model family:

- `AdminPage`, queries, bounded limits, allowed sorts, and normalized filters;
- `ManagementActionContext` actor, roles, correlation ID, and source validation;
- `ManagementMutationOutcome` and `VersionedMutationResult<T>` invariant combinations;
- `ManagementSetResult` created/applied/version invariants;
- `RevealedEntryValue` and `RevealedLockOwner` value/version snapshot requirements;
- entry TTL modes and request invariants;
- counter set/adjust and exact-version requirements;
- bulk preview/confirmation and force-release request invariants;
- permission-aware monitoring result sections;
- capabilities and effective setup limits.

### M2.2 Service and fallback

First red tests:

- `PeeGeeCache.management()` remains binary/source compatible through a default method;
- the default service reports unsupported capabilities;
- unsupported operations return failed Vert.x futures with the typed capability exception;
- every sensitive or mutation method requires `ManagementActionContext`;
- no metadata DTO can hold cache values, payloads, owner tokens, credentials, or raw secret material.

### M2.3 Audit SPI

First red tests:

- intent/outcome DTOs contain bounded operation names and versioned, at-least-128-bit HMAC-SHA-256 identifier fingerprints;
- raw namespace, key, channel, prefix, cursor, value, payload, owner, password, and authorization data cannot be serialized into the default audit DTO;
- an intent reservation carries only opaque reservation/event identifiers and non-secret sink generation;
- terminal completion is idempotent for the same outcome and rejects a conflicting second outcome;
- audit sink failures remain distinguishable from optional telemetry failures.

The audit fingerprinter receives a server-held rotation key through a secret reference. The existing unkeyed operational-log fingerprint helper is not reused for management audit events.

Phase gate:

- `peegee-cache-api` tests green;
- public API Javadoc describes privileged management use and async failures;
- existing API consumers compile unchanged;
- full reactor tests green before PostgreSQL implementation starts.

## 11. Phase M3 — PostgreSQL inspection and read model

Objective: implement authoritative metadata reads with deterministic pagination and no sensitive-field leakage.

Every item starts as a failing Testcontainers test and uses the actual V001 schema.

### M3.1 Namespace inspection

Test order:

1. empty logical namespace returns zero counts;
2. live/expired entry, counter, and active-lock counts match raw SQL;
3. value-type and TTL distributions match raw SQL;
4. literal prefix treats `%`, `_`, and `\` as data;
5. namespace ascending pagination has no duplicate/omitted rows;
6. `entryCount:desc,namespace:asc` resolves ties deterministically;
7. a scoped cursor is rejected after filters/sort/setup change;
8. concurrent inserts/deletes do not violate documented non-snapshot keyset behavior.

### M3.2 Entry metadata

Test order:

1. live metadata contains encoded identifiers, type, size, version, timestamps, and TTL but no value columns;
2. default read hides expired rows;
3. `includeExpired` exposes metadata only and maps the expired TTL state correctly;
4. missing versus expired results are distinct where the contract requires;
5. multi-byte identifiers and maximum management lengths round trip;
6. serializers and logs remain value-free under adversarial payloads.

### M3.3 Counter and lock metadata

Test order:

1. counters expose decimal value/version and TTL state;
2. active locks exclude expired leases;
3. lock metadata never exposes owner token;
4. fencing token and version serialize without precision loss;
5. pagination and literal prefixes follow M1 rules.

### M3.4 Database and expiry statistics

Test order:

1. sizes/counts agree with PostgreSQL;
2. unavailable privileged statistics return `UNAVAILABLE`, never zero;
3. expiry backlog and oldest lag use database time;
4. custom schema identifiers are safely rendered through existing schema validation;
5. partial/missing schema produces typed capability/readiness failures.

Phase gate:

- all read tests green on PostgreSQL 18.3;
- SQL is parameterized except validated schema identifiers;
- query plans for bounded lists use intended indexes;
- metadata DTO leakage scan green;
- `peegee-cache-pg` and full reactor suites green.

## 12. Phase M4 — Atomic mutation and reveal model

Objective: produce every HTTP-visible mutation outcome and resulting version in the same statement or transaction that observes/applies the change.

### M4.1 Entry reveal and set

Test order:

1. reveal returns value and version from one database snapshot;
2. reveal of missing/expired entry returns the correct typed failure;
3. `UPSERT`, `ONLY_IF_ABSENT`, `ONLY_IF_PRESENT`, and exact-version modes return typed outcomes;
4. `PRESERVE_EXISTING`, `USE_DEFAULT`, `REPLACE`, and `REMOVE` TTL modes are atomic with value mutation;
5. concurrent version updates yield one winner and `VERSION_MISMATCH` for stale requests;
6. result metadata/ETag version equals the committed row version.

### M4.2 Entry TTL, persist, touch, and delete

For each operation first prove:

- `APPLIED` includes the resulting representation/version when the row remains;
- `NOT_FOUND` and `VERSION_MISMATCH` are distinct without a diagnostic follow-up read;
- stale operations leave the row unchanged;
- delete reports the version-checked outcome atomically;
- expire and persist increment the version, while touch atomically checks but preserves the version and returns the same ETag with updated access/expiry metadata.

### M4.3 Counter operations

Test order:

1. create requires `If-None-Match: *` semantics;
2. set of an existing counter requires exact version;
3. signed adjustment, expected version, TTL mode, resulting value, and resulting version are atomic;
4. stale concurrent adjustment returns `VERSION_MISMATCH` and does not apply;
5. expire, persist, and delete distinguish missing from stale versions;
6. overflow/failure behavior is typed and leaves state unchanged.

### M4.4 Lock reveal and forced release

Test order:

1. reveal returns owner and version from one snapshot;
2. metadata paths remain owner-free;
3. forced release requires exact version and confirmation key;
4. stale release cannot delete a renewed/reacquired lock;
5. missing and stale outcomes remain distinguishable.

### M4.5 Audit gate integration

Use a small purpose-built audit sink boundary to prove before route work:

1. audit reservation rejection prevents reveal/mutation from reaching PostgreSQL;
2. a durable reservation precedes the database operation and reserves exactly one terminal-outcome slot;
3. queue saturation is rejected before the database operation, never after it commits;
4. committed and rejected outcomes complete the reservation with safe codes and no secret;
5. duplicate completion with the same outcome is idempotent while a conflicting completion fails;
6. clean shutdown drains completed outcomes and rejects new reservations;
7. restart recovery converts an incomplete durable intent to `UNKNOWN` before accepting mutations;
8. an injected post-commit persistence fault yields uncertain `AUDIT_OUTCOME_UNAVAILABLE`, makes mutation readiness down, and prevents further mutations until recovery;
9. telemetry exporter failure does not replace the security audit policy.

Phase gate:

- mutation race tests repeat reliably;
- no operation uses read-then-write to implement a promised atomic contract;
- database and logs contain no leaked secret in failure paths;
- PostgreSQL 15–18 matrix passes for `peegee-cache-pg` before REST mutations begin.

## 13. Phase M5 — Security and policy primitives

Objective: complete independently testable security components before protected routes exist.

### M5.1 Trusted proxy authentication

First red tests:

- accept normalized identity only from configured peer CIDR;
- reject direct/untrusted, missing, duplicate, oversized, malformed, or unknown-role headers;
- prove body/query roles are ignored;
- sanitize actor/source data in logs.

### M5.2 Local token authentication

First red tests:

- generate 256-bit token and retain only digest;
- accept exchange only from loopback and only in `LOCAL_TOKEN` mode;
- atomically consume the token, reject replay, and require restart or controlling-process regeneration after the only local session is lost;
- create and rotate the `PGQMGMTSESSION` cookie with required flags, 30-minute default idle expiry, eight-hour default absolute expiry, and enforced 24-hour maximum;
- expire sessions on idle/absolute deadlines and invalidate session/secrets on logout/shutdown;
- prove token/session identifiers never enter logs or browser persistence fixtures.

### M5.3 Origin, CORS, and CSRF

First red tests:

- same-origin default and disabled CORS;
- exact HTTPS allowlist only in trusted-proxy mode;
- reject wildcard/reflected credentialed origins;
- create/refresh a trusted-proxy management session through authenticated `GET /session`, bind it to normalized identity/roles/source, and invalidate it on identity change;
- require session-bound CSRF on every state-changing route except initial local bootstrap;
- require the bootstrap exception to be loopback, exact same-origin, JSON-only, token-authenticated, rate-limited, and CORS-disabled;
- validate SSE/WebSocket origin and prohibit query-string authentication.

### M5.4 Setup target and TLS policy

First red tests:

- reject host, port, or any resolved address outside policy;
- revalidate on test, registration, physical pool connection, and reconnect;
- connect to the exact validated address while preserving the configured hostname for TLS SNI and certificate verification;
- use a flipping resolver plus a real PostgreSQL/TLS endpoint to prove the driver cannot re-resolve to a forbidden address between validation and connect;
- block link-local/cloud-metadata ranges by default;
- require server-known trust profile and reject path/trust material from requests;
- verify `VERIFY_FULL` hostname and chain behavior through a real TLS PostgreSQL fixture.

### M5.5 Rate and resource limits

First red tests:

- actor/source limits for reveal, setup test/connect, bulk preview, publish, and subscription creation;
- bounded maps evict expired identities without unbounded cardinality;
- limit errors map to typed `429` problems;
- metrics use bounded dimensions only.

Phase gate:

- focused security suites green;
- negative/adversarial cases outnumber happy-path cases where appropriate;
- threat model reviewed against the API contract;
- no protected route has been implemented ahead of its middleware.

## 14. Phase M6 — Setup registry and server lifecycle

Objective: own pools, managers, credentials, and server resources deterministically.

### M6.1 Configuration

First red tests:

- valid loopback/local-token and trusted-proxy configurations;
- reject contradictory auth modes, unsafe binds, invalid CIDRs/origins/limits/session lifetimes, missing target policy, unknown trust profiles, missing audit journal, and missing audit-fingerprint secret reference;
- secret references remain distinct from display metadata.

### M6.2 Registry lifecycle with real PostgreSQL

Test order:

1. test connection closes every temporary resource;
2. failed registration leaves no registry entry or credential copy;
3. successful registration publishes only after health/schema readiness;
4. same-setup connect/detach is serialized;
5. configured setup reloads secrets through its provider;
6. UI-session setup reconnects only while its bounded secret holder is alive;
7. detach closes manager, pool, pub/sub, SSE, and WebSocket scope without changing database objects;
8. forget is restricted to UI-session setups and clears secret material;
9. server shutdown closes all setups and rejects new work.

### M6.3 Server lifecycle

First red tests:

- start binds configured address once;
- failed startup unwinds partial resources;
- stop is deterministic and idempotent;
- static resources and SPA fallback cannot capture `/api` or `/ws` routes;
- readiness reflects started state and required dependencies.

Phase gate:

- lifecycle and leak tests green under repeated start/stop;
- real connections are absent after detach/shutdown;
- test logs contain no credentials or tokens;
- REST module suite and full reactor green.

## 15. Phase M7 — Session, setup lifecycle, health, capability, and read routes

Objective: implement safe reads and the separately guarded setup-lifecycle mutations as vertical HTTP slices using the running server.

For each route, follow this order:

1. authentication failure;
2. role failure;
3. management-session and Origin validation;
4. CSRF validation for every state-changing route other than the explicit local-bootstrap exception;
5. path/query/header/body/content-negotiation/size validation;
6. rate/resource-limit and capability behavior;
7. audit reservation/outcome behavior when the route is a mutation;
8. successful response and headers;
9. typed service failure mapping and resource cleanup;
10. OpenAPI response conformance;
11. structured request log and telemetry assertions.

Read/session route slices:

- local session bootstrap/logout and `GET /session`, with the bootstrap exception tested independently;
- setup list and details;
- health and capabilities with effective limits and migration version `1`;
- overview and namespace list/detail/export;
- entry metadata list/detail;
- counter and lock list/detail;
- database/runtime monitoring and activity.

Setup-lifecycle mutation slices, one endpoint at a time:

- test unregistered setup;
- register and connect setup;
- connect detached setup;
- test registered setup;
- detach setup;
- forget UI-session setup.

Every setup-lifecycle slice proves operator authorization, CSRF, actor/source rate limiting where specified, target/TLS policy enforcement, durable audit reservation and terminal outcome, uncertain audit-outcome behavior, secret erasure, and cleanup after success or failure. Setup tests that do not publish registry state are still privileged outbound operations and receive the same CSRF, rate-limit, audit, and target-policy treatment.

Required adversarial tests:

- fixed paths cannot be captured by encoded identifier routes;
- NUL/oversized/non-canonical identifiers return `INVALID_IDENTIFIER`;
- unsupported `Content-Type`/`Accept` and oversized requests map correctly;
- all 64-bit values are strings while durations remain bounded integer milliseconds;
- metadata responses contain no values, payloads, owners, credentials, hashes, or accidental previews;
- unavailable permission-dependent monitoring values never become zero.

Phase gate:

- every read operation in OpenAPI has one implemented route and contract test;
- every setup-lifecycle operation has its independent mutation/security contract test;
- route inventory equals the OpenAPI M7 inventory;
- viewer cannot reach operator-only setup actions;
- all database-backed routes pass against real PostgreSQL;
- setup lifecycle mutations cannot execute when audit reservation, CSRF, Origin, target policy, or rate gates fail.

## 16. Phase M8 — Reveal, mutation, and bulk routes

Objective: expose guarded administration without weakening atomicity or audit requirements.

### M8.1 Reveals

Test entry value and lock owner separately:

- viewer forbidden;
- operator with missing/invalid CSRF forbidden;
- audit sink unavailable fails before reveal;
- success includes `no-store`, `Pragma`, version, reveal time, and bounded auto-hide duration;
- optional reason validation and safe fingerprinted audit event;
- response serializers never leak into activity, notification, or ordinary request logs;
- rate limit and expired/missing behavior.

### M8.2 Entry mutations

One endpoint at a time: set, TTL, persist, touch, delete.

Tests prove exact `If-Match`/`If-None-Match` rules, CSRF, no automatic server retry, typed outcome/status mapping, resulting ETag, uncertain-client-result behavior, audit intent/outcome ordering, and absence of old values from responses.

### M8.3 Counter mutations

One endpoint at a time: set, signed adjustment, TTL, persist, delete.

Tests prove exact version preconditions, atomic value/version/TTL results, zero-delta rejection, overflow behavior, and no mutation retry.

### M8.4 Forced lock release

Tests prove exact version, exact decoded confirmation key, optional reason bounds, stale-renew/reacquire safety, and no acquire/renew route exposure.

### M8.5 Bulk preview and execute

First red tests:

- exactly one selection mode and bounded target count;
- preview stores exact `(key, version)` set and only token digest;
- token is random, actor/setup/namespace scoped, five-minute, and single use;
- execute marks used before database work and cannot replay after partial failure;
- changed/missing entries are reported and not deleted;
- confirmation phrase is exact;
- restart invalidates tokens;
- entry and counter workflows cannot cross scopes;
- failure summaries contain no values or unsafe exception messages.

Phase gate:

- every M8 entry/counter/lock/bulk mutation and reveal OpenAPI operation has red/green protocol evidence;
- no route performs a diagnostic follow-up read to manufacture outcome state;
- audit fail-closed and secret scans green;
- full REST, PostgreSQL, and reactor suites green.

## 17. Phase M9 — Pub/sub, SSE, and WebSocket

Objective: implement bounded live transports without implying delivery or database-wide change capture.

### M9.1 Publication

First red tests:

- effective channel maximum derives from `{prefix}__{channel}` and 63-byte PostgreSQL limit;
- NUL, overlength channel, and oversized UTF-8 payload rejected before SQL;
- success returns `accepted: true`, never a listener count;
- received `contentType` is null in V1;
- client/server do not automatically retry publication;
- role, management session, Origin, CSRF, rate limit, audit reservation/outcome, and safe-log behavior are enforced before publishing.

### M9.2 Subscription ownership and buffers

First red tests:

- subscription belongs to actor and setup; another actor receives `404`;
- per-actor/setup/process quotas and byte budgets are enforced;
- entry/byte eviction removes oldest and emits reset semantics;
- slow client cannot block PostgreSQL notification dispatch;
- disconnected session resumes for five minutes and expires after one hour;
- create/delete operations enforce management session, Origin, CSRF, quotas, and durable audit outcomes;
- detach/forget/shutdown clears listener, buffer, and clients.

### M9.3 Retained payload reveal

First red tests:

- reveal cannot execute before an actor-owned subscription and retained message exist;
- another actor receives `404` and cannot infer subscription/message existence;
- viewer, missing/invalid CSRF, unavailable audit reservation, and rate-limit cases fail before buffer access;
- success returns the payload and nullable content type with `no-store`, `Pragma`, reveal time, encoding, and bounded auto-hide metadata;
- evicted messages return `410 MESSAGE_EXPIRED` and never trigger a PostgreSQL reread;
- payload never enters activity, WebSocket notifications, telemetry, audit, or ordinary logs.

### M9.4 Pub/sub SSE

First red tests:

- correct `text/event-stream` headers, initial `ready`, monotonic IDs, event/data framing, and heartbeat;
- `Last-Event-ID` resumes available data;
- unavailable history sends `reset` with oldest available ID;
- stream carries metadata only and reveal remains separate;
- write failure and disconnect cleanup are observed exactly once.

### M9.5 Metrics SSE and monitoring WebSocket

First red tests:

- 15-second snapshot/heartbeat scheduling through injected test time;
- authenticated/authorized/origin-checked upgrades;
- typed envelope and bounded five-minute resume;
- ping/pong timeout and bounded reconnect contract;
- setup detach terminal event and normal typed close;
- `resource.changed` covers only this management process;
- external SQL/application writes never generate fabricated resource events;
- shutdown sends terminal event and releases clients.

Phase gate:

- transport tests use real sockets and actual framing;
- buffer/concurrency stress tests are deterministic and bounded;
- publication, subscription lifecycle, and payload reveal have complete CSRF/audit/rate-limit evidence;
- stream telemetry and safe logging green;
- no payload or raw identifier leaks to ordinary logs.

## 18. Phase M10 — Monitoring, observability, packaging, and acceptance

Objective: finish the backend as an operable product component rather than a collection of routes.

### M10.1 Mandatory observability

First red contract tests require bounded signals for:

- server/setup lifecycle and readiness;
- HTTP route template, method, status, duration, active requests, and safe error code;
- authentication/authorization/CSRF/target-policy outcomes without high-cardinality identity tags;
- audit journal/queue depth, reservation capacity, rejected intent, incomplete-intent recovery, outcome persistence failures, and mutation-readiness state;
- active setups, pools, subscriptions, SSE/WS clients, buffer bytes, evictions, and resets;
- PostgreSQL operation timing/failure through existing management operations;
- graceful shutdown and leaked-resource detection.

Telemetry exporter failures remain isolated from cache operations; security audit acceptance retains its separately tested fail-closed policy.

### M10.2 Packaging and operations

Tests and build checks prove:

- one runnable REST artifact starts under Java 26 while containing Java 21 bytecode;
- exactly one SLF4J provider is present at runtime and none leaks through published libraries;
- OpenAPI and static assets are packaged at documented paths;
- `/ui/*` SPA fallback does not shadow API/WebSocket routes;
- configuration, proxy, local-token, target-policy, TLS, rate-limit, audit, shutdown, and troubleshooting runbooks are complete;
- the backend-owned non-production browser harness is packaged only in test resources and cannot enter the runnable artifact;
- generated artifacts, logs, credentials, and environment-specific files remain ignored appropriately.

### M10.3 Final verification ladder

Run in this order and inspect every result:

1. focused tests for the final slice;
2. `mvn test` for every affected module;
3. full root `mvn clean verify` under OpenJDK 26.0.2;
4. PostgreSQL 15–18 compatibility matrix for the complete reactor;
5. OpenAPI validation and route/response drift checks;
6. REST protocol acceptance suite against real PostgreSQL;
7. Playwright security/storage/cookie/origin/no-store journeys through the backend-owned non-production browser harness;
8. packaged-artifact startup, health, shutdown, and log scan;
9. dependency and license checks required by the release build;
10. final `git diff --check` and generated-artifact inventory.

Phase gate: every command succeeds with zero failures, errors, unexpected skips, secret leaks, raw logged identifiers, or unresolved high-severity threat-model findings.

## 19. Cross-cutting behavior matrix

Each route must have explicit evidence for applicable columns before completion:

| Route class | Authn | Role | Origin/CSRF | Validation | Size | Success | Not found | Conflict/stale | Rate/resource limit | Audit | Telemetry | OpenAPI |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| Session/setup reads | ✓ | ✓ | session/origin | ✓ | ✓ | ✓ | ✓ | as applicable | ✓ | safe logs | ✓ | ✓ |
| Local bootstrap | token/loopback | operator session | exact origin; CSRF exception | ✓ | ✓ | ✓ | n/a | replay/expired | ✓ | auth event | ✓ | ✓ |
| Setup lifecycle mutations | ✓ | operator | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | required | ✓ | ✓ |
| Metadata reads | ✓ | ✓ | n/a | ✓ | n/a | ✓ | ✓ | n/a | ✓ | safe logs | ✓ | ✓ |
| Reveals | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | required | ✓ | ✓ |
| Single mutations | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | required | ✓ | ✓ |
| Bulk preview/execute | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | required | ✓ | ✓ |
| Pub/sub publish/subscription/reveal | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | required | ✓ | ✓ |
| SSE/WebSocket | ✓ | ✓ | origin | ✓ | n/a | ✓ | setup missing | reset | ✓ | safe lifecycle | ✓ | extensions |

The matrix is a coverage checklist, not a substitute for behavior-focused tests.

## 20. Evidence and status reporting

Each completed phase records:

- the exact red test and expected failure for each slice;
- focused and module-level green commands;
- actual tests/failures/errors/skips by module;
- PostgreSQL image for database tests;
- security/log scan results;
- new production classes and schemas/routes introduced;
- deferred items with rationale;
- confirmation that no Mockito or substitute framework was introduced.

Update rules:

1. update this plan and the authoritative implementation-plan tracking row in the same change that advances a phase;
2. do not mark a phase complete from code inspection alone;
3. do not count a test that did not execute;
4. keep the lower status when evidence is incomplete;
5. record external actions separately from repository completion.

## 21. Completion criteria

The management API backend is complete only when:

1. M0–M10 are complete with recorded red/green evidence;
2. OpenAPI declares every implemented REST operation and SSE/WebSocket schema, and route inventory has no drift;
3. every versioned mutation returns an atomic typed outcome and correct resulting version without a follow-up diagnostic read;
4. all database behavior passes on PostgreSQL 15–18;
5. metadata, logs, telemetry, activity, streams, and errors satisfy redaction/fingerprinting rules;
6. local-token and trusted-proxy sessions, the sole bootstrap CSRF exception, all other CSRF/origin checks, pinned-address setup target policy, and TLS trust are verified at real protocol boundaries;
7. reveals and mutations fail closed when a durable audit reservation cannot be accepted, terminal outcome capacity is reserved before database work, and incomplete intents recover as `UNKNOWN`;
8. setup, pool, listener, timer, SSE, WebSocket, buffer, and secret resources close deterministically;
9. observability covers the complete management runtime and is not treated as optional;
10. full Maven, REST acceptance, backend-owned browser-harness, packaging, and compatibility gates pass with no skipped required tests;
11. the implementation plan, API design, UI design, operations guidance, and observed test inventory are synchronized;
12. implementation status is changed from `NOT STARTED` only with concrete evidence.

The production React console is delivered under separately tracked Phase 8.3 and is not a hidden prerequisite for declaring this backend complete. Production deployment, production-topology performance validation, and credentialed public publication remain separate external readiness actions.
