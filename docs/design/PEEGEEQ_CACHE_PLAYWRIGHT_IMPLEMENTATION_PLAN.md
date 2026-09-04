# PeeGeeQ Cache Playwright Implementation Plan

Status: **550-SCENARIO DESKTOP-ONLY IMPLEMENTATION — POSTGRESQL 18.3 CUMULATIVE GATE VERIFIED**

Required minimum: **540 distinct Playwright browser scenarios**

Current implemented catalogue: **550 Java Playwright scenarios implementing 17 named browser journeys and 13 parameterized scenario catalogues**

Implementation evidence as of 3 September 2026:

- all 19 baseline browser scenarios have stable `PW-<AREA>-NNN` identifiers and machine-readable requirement, area, risk, action, result, operation, evidence, and cleanup metadata;
- `ManagementBrowserCoverageTest` enforces the exact current scenario count, unique scenario and journey ownership, all 60 OpenAPI operation owners, and evidence requirements for operations, mutations, sensitive state, and cleanup;
- negative accountability canaries prove that incomplete metadata, unknown operations, missing operation evidence, mutation without a PostgreSQL/audit oracle, and sensitive operations without leakage evidence fail validation;
- the source-policy gate rejects Playwright request interception, response substitution, synthetic page content, and synthetic init-script patterns, with a negative canary for every prohibited construct;
- a typed self-contained HTML evidence-report skeleton provides readable UTC dates, environment and per-scenario structure, atomic single-file output, HTML escaping, and registered-sensitive-canary rejection;
- the complete browser suite uses exactly one Playwright instance and one Chromium process with a fresh browser context per scenario. Ordinary PostgreSQL-backed scenarios additionally share one PostgreSQL 18.3 container, one management server, one authenticated local session, and one registered setup; they receive deterministic mutable-data reset, process-local subscription cleanup, and an audit-backed exactly-once registration assertion, while lifecycle-mutating scenarios isolate only their server/session/setup state; and
- the complete current 19-scenario headless browser baseline has passed on PostgreSQL 18.3 with zero failures, errors, or skips; together with the ten P0 harness/accountability tests, the focused acceptance run executed 29 tests in 56.942 seconds; and
- the first Wave P1 shell tranche adds 21 distinct packaged-server scenarios (`PW-SHELL-002` through `PW-SHELL-022`), and its complete focused gate passed 21/21 with zero failures, errors, or skips in 45.77 seconds, bringing the accountable cumulative catalogue to 40 scenarios; and
- the Wave P1 authentication tranche adds 40 distinct local-token and session-security scenarios (`PW-AUTH-007` through `PW-AUTH-046`), with runtime operation tracing and isolated browser contexts; its focused gate passed 40/40 in 19.50 seconds; and
- the complete Wave P1 cumulative gate passed exactly 80/80 real browser scenarios with zero failures, errors, or skips in 1 minute 55 seconds against the packaged UI, real server transports, and PostgreSQL 18.3;
- the Wave P2 setup tranche adds 56 distinct setup discovery, validation, registration, health, capability, and refresh scenarios (`PW-SETUP-002` through `PW-SETUP-057`); its focused gate passed 56/56 with zero failures, errors, or skips in 1 minute 42.6 seconds;
- the Wave P2 overview and namespace tranches add 46 distinct database-truth, filtering, cursor, export, detail, and refresh scenarios (`PW-OVERVIEW-002` through `PW-OVERVIEW-024` and `PW-NAMESPACE-001` through `PW-NAMESPACE-023`); their combined focused gate passed 46/46 with zero failures, errors, or skips in 1 minute 33 seconds; and
- the complete Wave P2 cumulative gate passed exactly 182/182 real browser scenarios with zero failures, errors, or skips in 4 minutes 42 seconds against the packaged UI, real server transports, and PostgreSQL 18.3.
- Wave P3 adds 130 independently reported entry scenarios: 54 inspection, formatting, reveal, and sensitive-state cleanup cases (`PW-ENTRY-003` through `PW-ENTRY-056`), 50 creation, mutation, TTL, version, conflict, and deletion cases (`PW-ENTRY-057` through `PW-ENTRY-106`), and 26 bulk preview, confirmation, execution, conflict, replay, and leakage cases (`PW-ENTRY-107` through `PW-ENTRY-132`); and
- the complete Wave P3 cumulative gate passed exactly 312/312 real browser scenarios with zero failures, errors, or skips in 9 minutes 20 seconds against the packaged UI, real server transports, and PostgreSQL 18.3.
- Wave P4 adds 88 independently reported resource-administration scenarios: 44 exact signed-counter, mutation, TTL, conflict, deletion, and bulk cases (`PW-COUNTER-002` through `PW-COUNTER-045`) and 44 lock observation, masking, reveal-cleanup, fencing, conflict, and force-release cases (`PW-LOCK-002` through `PW-LOCK-045`); the red browser cases drove a missing counter-creation TTL control and robust document-boundary modal Escape handling into the product UI; and
- the complete Wave P4 cumulative gate passed exactly 400/400 real browser scenarios with zero failures, errors, or skips in 12 minutes 9 seconds; the rebuilt UI also passed type checking, lint, all 112 Vitest tests, and production packaging.
- Wave P5 adds 82 independently reported scenarios: 30 Pub/Sub contract and lifecycle cases (`PW-PUBSUB-002` through `PW-PUBSUB-031`), 20 simultaneous SSE/WebSocket lifecycle and browser offline/recovery cases (`PW-LIVE-001` through `PW-LIVE-020`), and 32 monitoring/settings cases (`PW-MONITOR-002` through `PW-MONITOR-033`); the focused gates passed 30/30 in 61.76 seconds, 20/20 in 42.70 seconds, and 32/32 in 62.30 seconds respectively;
- the Wave P5 red live-transport cases exposed missing browser `offline`/`online` handling in both the metrics SSE and monitoring WebSocket clients; the production transports now enter `STALE`, abort/close active transports, reconnect on `online`, preserve bounded retry behavior, and remove listeners during cleanup; the rebuilt UI passed type checking, lint, all 112 Vitest tests, and production packaging;
- Wave P6 now contains 48 independently reported scenarios: 14 desktop accessibility, keyboard, focus-trap, Escape, zoom, and focus-restoration cases (`PW-ACCESS-001` through `PW-ACCESS-014`), 14 cross-surface privacy cases (`PW-PRIVACY-001` through `PW-PRIVACY-014`), 12 packaged-hosting and browser-security cases (`PW-PACKAGE-001` through `PW-PACKAGE-012`), and 8 deterministic multi-transport shutdown cases (`PW-SHUTDOWN-001` through `PW-SHUTDOWN-008`); the ten unsupported mobile/narrow-viewport cases have been removed; and
- the initial complete cumulative gate passed exactly **540/540** real Java Playwright browser scenarios with zero failures, zero errors, and zero skips in **16 minutes 38 seconds** against the packaged UI, real HTTP/SSE/WebSocket transports, PostgreSQL 18.3, and the zero-leak fixture shutdown assertion;
- the 31 August remediation pass made saved refresh, concealment, timezone, byte-unit, role, and capability settings effective; added focusable named overflow regions; replaced repeated/static cases with real boundary and long-content behavior; added a native-HTTP SSE resume test; and strengthened mutation, privacy, conflict, and transport assertions;
- every PostgreSQL-backed fixture now rejects unexpected failed HTTP responses, missing declared operations, undeclared audited operations, missing durable audit actions, browser errors, and resource leaks. Counter, lock, entry, and bulk scenarios additionally assert authoritative PostgreSQL outcomes;
- the earlier 540-scenario post-remediation milestone passed **540/540** catalogue scenarios with zero failures, errors, or skips and 18 minutes 52 seconds of aggregate scenario time. That historical reactor run passed **526 Surefire** and **542 Failsafe** tests, while its rebuilt UI gate passed **122 Vitest** tests, type checking, lint, and production packaging; and
- the 12 PostgreSQL product journeys completed in 56.77 seconds in the post-remediation cumulative run, with one PostgreSQL container start and a separate schema migration, management server, Playwright browser context, operation trace, and cleanup cycle for every scenario.
- the correctness-remediation extension adds 19 non-inflated scenarios: 11 independently advertised capability-degradation cases, five real trusted-proxy viewer workflows, counter cursor pagination, and exact/over-limit Pub/Sub payload boundaries;
- setup capability responses now derive from the connected runtime's real `AdminCapabilities` instead of advertising universal support, and the expired-entry filter is removed when expiry inspection is unavailable;
- PostgreSQL-backed scenarios now reject both missing and undeclared feature operations, while failed HTTP responses must match the exact expected status and canonical route rather than only an expected count; and
- actual JUnit browser outcomes now produce one atomic self-contained `playwright-evidence.html` report with environment details and scenario metadata; PostgreSQL-backed failures additionally capture a DOM-sanitized full-page screenshot; and
- the historical post-canary PostgreSQL 18.3 cumulative gate on 31 August 2026 passed **559/559** scenarios before the unsupported mobile coverage was removed. The active desktop-only catalogue contains 550 scenarios, including `PW-BACKEND-001` for complete facade parity; its fresh 3 September 2026 cumulative gate passed **550/550** scenarios, plus all three runnable-artifact/evidence Failsafe checks, in the green 35-minute-49-second clean reactor; and
- the 3 September 2026 fixture-lifecycle remediation removed authentication and setup-registration calls from ordinary test bodies, moved preparation into the shared fixture, added an audit-backed exactly-once registration guard, and centralized every scenario on one suite-owned Playwright/Chromium process. A source-policy gate rejects any additional Playwright or Chromium launch site; and
- focused packaged PostgreSQL gates pass `PW-BACKEND-001` for existence, batch get/set, value scan, exact metrics, and owner-lock acquire/renew/ownership/release, and `PW-COUNTER-001` for create-if-missing signed adjustment with TTL and committed-state verification; and
- report generation registers runtime bootstrap tokens, the fixture database password, and seeded revealed values as sensitive canaries, removes stale evidence before every browser test plan, and fails Maven verification unless a fresh canary-clean report is produced.

Post-implementation release-validation work remains:

- add a reviewed trace-sanitization format before enabling Playwright trace archives; sanitized screenshots and real JUnit-to-HTML reporting are implemented;
- complete failure-path canaries for database, durable-audit, sensitive-surface, and resource-cleanup oracles;
- execute the verified desktop-only 550-scenario suite on PostgreSQL 15.17, 16.13, and 17.11; PostgreSQL 18.3 is green.

## 1. Purpose

This document is the execution authority for expanding the PeeGeeQ Cache management console's real-browser coverage from the current acceptance backbone to at least 500 meaningful Playwright scenarios. The committed target is 540 distinct scenarios.

The expansion must validate the real packaged product. It must not recreate the removed route-intercepted TypeScript matrix or inflate the count with repeated routes that only display `No setup selected`.

The management UI remains implemented and releasable at the current U0-U10 boundary. This plan is a follow-on assurance programme that deepens browser-level verification of that completed functionality.

## 2. Governing documents

Implementation must remain consistent with:

1. [PEEGEEQ_CACHE_MANAGEMENT_API.md](PEEGEEQ_CACHE_MANAGEMENT_API.md), the authoritative management contract;
2. [PEEGEEQ_CACHE_MANAGEMENT_UI_DESIGN.md](PEEGEEQ_CACHE_MANAGEMENT_UI_DESIGN.md), the authoritative UI behavior and security design;
3. [PEEGEEQ_CACHE_MANAGEMENT_UI_IMPLEMENTATION_PLAN.md](PEEGEEQ_CACHE_MANAGEMENT_UI_IMPLEMENTATION_PLAN.md), the completed U0-U10 implementation record;
4. [PEEGEEQ_CACHE_MANAGEMENT_API_IMPLEMENTATION_PLAN.md](PEEGEEQ_CACHE_MANAGEMENT_API_IMPLEMENTATION_PLAN.md), the backend implementation and evidence record; and
5. the repository-wide prohibition on Mockito and substitute mocking frameworks.

Where these documents disagree, the management API contract owns server behavior and the management UI design owns browser behavior. This plan owns only the browser-test expansion sequence and its completion evidence.

## 3. Non-negotiable test rules

Every scenario counted toward the 540 target must:

- load the Maven-packaged production UI from the real Java management server;
- use real HTTP, SSE, and WebSocket transports;
- use real PostgreSQL whenever the workflow reads or mutates cache state;
- avoid Playwright request interception, including `page.route`, `route.fulfill`, `route.abort`, and equivalent response substitution;
- avoid Vite development mode, synthetic HTML injection, and component-only rendering;
- begin with an explicitly prepared setup and meaningful database state unless the scenario specifically owns an empty, disconnected, or unauthenticated state;
- perform a user-observable browser action and assert the resulting visible state;
- assert the expected management operation from observed browser traffic, or explicitly prove that client-side authorization correctly prevented the request;
- assert PostgreSQL state and durable audit evidence for mutations;
- create an isolated browser context and clean up transports, subscriptions, sessions, setup runtimes, sensitive DOM state, and database state;
- have one stable scenario identifier mapped to a requirement, risk, owning test, and expected oracle; and
- remain useful when executed alone and in a random class order.

Browser-engine, PostgreSQL-version, desktop viewport, role, and data parameters do not create additional scenario credit unless they exercise a genuinely different behavior and expected outcome. Mobile and tablet viewports are outside the product boundary and are prohibited.

## 4. Explicitly prohibited count inflation

The following do not count as distinct scenarios:

- repeating the same route across many roles when the only assertion is a shared shell or empty state;
- repeating one assertion across desktop viewport sizes without a route-specific desktop requirement;
- multiplying the count by Chromium, Firefox, WebKit, or PostgreSQL version;
- parameter rows that differ only in labels or harmless seed values;
- tests that assert only HTTP `200`, a heading, or page visibility;
- direct API tests that do not drive the browser UI;
- tests that bypass authentication, authorization, CSRF, or setup policy through fixture-only product hooks;
- tests whose only outcome is `No setup selected`, except for the small number of scenarios that explicitly own that state;
- disabled, skipped, quarantined, or automatically retried tests; and
- duplicate scenarios with different names.

## 5. Target coverage inventory

| Area | Distinct scenarios |
|---|---:|
| Authentication, sessions, CSRF, roles, and expiry | 46 |
| Application shell, routing, scope, navigation, and capability degradation | 45 |
| Setup lifecycle, target policy, and viewer setup controls | 57 |
| Overview, namespaces, pagination, and export | 46 |
| Entry inspection, reveal, formatting, cleanup, and viewer behavior | 55 |
| Entry mutation, concurrency, TTL, and bulk operations | 76 |
| Counters, exact numeric behavior, viewer behavior, and cursor pagination | 46 |
| Locks, ownership, fencing, conflicts, and viewer behavior | 45 |
| Pub/Sub, payload boundaries, viewer behavior, and live transport lifecycle | 51 |
| Monitoring, activity, notifications, and settings | 34 |
| Backend facade parity | 1 |
| Desktop accessibility, privacy, packaging, and shutdown | 48 |
| **Total** | **550** |

The existing 19 Playwright tests are included in this total after each is assigned a compliant scenario identifier and satisfies the stronger evidence contract.

## 6. Scenario accountability model

A committed scenario catalogue must be introduced before the test count expands. Each record must contain:

- stable scenario ID;
- short behavior name;
- source requirement and document section;
- owning feature area;
- risk classification;
- authentication mode, role, and capability prerequisites;
- required setup and PostgreSQL seed state;
- browser actions;
- expected visible result;
- expected HTTP, SSE, and WebSocket operations;
- PostgreSQL and audit oracle, where applicable;
- sensitive-state assertions;
- cleanup obligations; and
- owning Java test method.

Scenario IDs use the form `PW-<AREA>-NNN`, for example `PW-ENTRY-017` and `PW-PUBSUB-031`. IDs are never reused after removal; retired IDs remain documented with their rationale.

`ManagementBrowserCoverageTest` must be expanded so the build fails when:

- fewer than 540 active unique scenarios exist at completion;
- an active scenario has no owning Playwright test;
- IDs or owners are duplicated;
- a scenario lacks its requirement, risk, action, result, or cleanup metadata;
- any of the 50 OpenAPI operations lacks deliberate browser ownership;
- a test declares an operation that is not observed at runtime;
- a mutation scenario lacks a PostgreSQL or durable-audit oracle;
- a sensitive scenario lacks explicit leakage assertions; or
- prohibited interception or synthetic-browser patterns appear in the browser suite.

The minimum-count gate is introduced only after the corresponding implementation wave lands. Intermediate gates use the exact cumulative target for the active wave so unfinished planned scenarios cannot be hidden behind placeholders.

## 7. Browser fixture architecture

### 7.1 Runtime topology

The suite remains owned by `peegee-cache-rest` and Java Playwright. It runs against:

- the installed `peegee-cache-management-ui` production JAR;
- the actual `ManagementServerApplication` or reviewed running-server assembly;
- real loopback HTTP/SSE/WebSocket listeners;
- real PostgreSQL Testcontainers with TLS where product acceptance requires it; and
- the real schema, repositories, setup runtime, audit sink, authentication, authorization, CSRF, target policy, and resource accounting.

### 7.2 Isolation and reuse

Performance must improve without weakening isolation:

- use exactly one Playwright instance and one Chromium process for the complete suite;
- prohibit browser launch or close ownership in individual test classes;
- use one PostgreSQL container for the complete Playwright suite;
- reuse one management server, authenticated local session, browser process, audit sink, and registered setup for ordinary scenarios;
- reset schema data and relevant sequences before each scenario;
- create a fresh browser context per scenario;
- never share a `Page` between scenarios;
- stop process-local subscriptions after their owning scenario;
- assert from durable audit evidence that the shared setup was registered exactly once;
- retain dedicated server/session/setup environments for setup registration/connect/detach/forget, local-token session deletion or expiry, advertised-capability variants, trusted-proxy identity variants, startup, and server shutdown while continuing to reuse the suite browser process; and
- serialize scenarios that intentionally alter shared server state.

### 7.3 Fixture services

The fixture must provide purpose-built helpers for:

- deterministic setup registration and capability selection through the UI;
- database seeding and read-only oracle queries;
- durable audit intent/outcome inspection;
- observed HTTP, SSE, and WebSocket operation tracing;
- deterministic clocks for expiry, preview-token, and session-lifetime tests;
- real network offline/online control through the browser context;
- setup detach/reconnect and server stop/start fault actions;
- console, page-error, failed-response, and resource-gauge capture;
- per-scenario screenshot and trace capture on failure;
- sensitive-value canary registration and cross-surface scanning; and
- cleanup verification after both success and failure.

Fixture helpers may arrange state and inspect authoritative results. They must not fabricate product responses or call product mutations in place of the user action being tested.

## 8. Strict TDD workflow

Each implementation wave follows this sequence:

1. Add scenario catalogue entries and the accountability assertions for the wave.
2. Add the smallest focused Playwright scenario set before changing product code.
3. Run the focused tests and record genuine failures.
4. Classify each failure as a product defect, missing observable behavior, fixture defect, or already-correct behavior.
5. Change production code only for a demonstrated product failure.
6. Make the focused scenarios green.
7. Run the owning frontend unit/protocol tests.
8. Run the complete Playwright suite accumulated so far.
9. Run the complete Maven reactor against PostgreSQL 18.
10. Update the scenario catalogue and implementation evidence in the same change set.

Coverage-only tests may be green immediately when the existing product behavior is correct. Product behavior must not be deliberately broken merely to manufacture a red result. Harness accountability and oracle self-tests must nevertheless prove that missing requests, wrong database outcomes, leaked canaries, and failed cleanup are detected.

No wave advances with skips, automatic retries, unresolved browser errors, resource leaks, or a reduced existing assertion.

## 9. Ordered implementation waves

### Wave P0: Accountability and harness foundation

Target remains 19 scenarios while the current suite is catalogued.

Deliverables:

- scenario catalogue and schema;
- stable scenario IDs on all existing browser tests;
- operation, database, audit, leakage, and cleanup evidence types;
- prohibited-pattern source gate;
- worker-scoped PostgreSQL fixture;
- isolated browser-context lifecycle;
- failure artifacts and consolidated HTML report skeleton; and
- focused harness self-tests proving missing evidence fails the build.

Exit gate:

- all 19 existing Playwright tests remain green;
- all 17 named journeys remain owned;
- every current scenario has complete metadata;
- no product request is intercepted; and
- intentional fixture canaries prove each new oracle can fail.

### Wave P1: Authentication and shell

Cumulative target: **80 scenarios**.

Status: **COMPLETE** — the exact 80-scenario accountability gate and complete runtime gate are green. The runtime evidence is 80 tests, zero failures, zero errors, zero skips, and 1 minute 55 seconds on PostgreSQL 18.3.

Authentication coverage includes:

- valid, invalid, consumed, malformed, and expired bootstrap tokens;
- token removal from inputs, DOM, URL, history, console, and storage;
- cookie attributes and rotation;
- CSRF and Origin enforcement;
- idle and absolute session expiry;
- local logout success, failure, retry, and authoritative `401` behavior;
- trusted-proxy identity and role rotation;
- viewer/operator transitions and direct forbidden requests;
- cross-origin and same-site rejection; and
- authenticated and unauthenticated deep links.

Shell coverage includes:

- every primary navigation destination with a prepared setup;
- browser back/forward history;
- encoded identifiers;
- setup and namespace scope changes;
- capability-driven navigation;
- unknown routes and asset-like misses;
- theme and harmless preference persistence; and
- error containment without credential leakage.

### Wave P2: Setup, Overview, and namespaces

Cumulative target: **182 scenarios**.

Status: **COMPLETE** — the exact 182-scenario accountability gate and complete runtime gate are green. The cumulative runtime evidence is 182 tests, zero failures, zero errors, zero skips, and 4 minutes 42 seconds on PostgreSQL 18.3.

Setup coverage includes:

- test, register, connect, detach, reconnect, and forget;
- invalid host, port, database, schema, username, SSL mode, trust profile, and pool size;
- target-policy allow and deny decisions;
- DNS/address pinning and TLS failures;
- duplicate setup identifiers;
- health and capability changes;
- concurrent actions;
- credential cleanup; and
- setup-switch cancellation and stale-response isolation.

Overview and namespace coverage includes:

- database-truth totals;
- unavailable versus zero values;
- monitoring failure isolation;
- stale and recovered snapshots;
- forward and backward cursor navigation;
- database mutation between cursor requests;
- Unicode and encoded namespace names;
- export content, filename, MIME type, and secret exclusion;
- refresh and trend behavior; and
- viewer/operator differences.

### Wave P3: Entry inspection and administration

Cumulative target: **312 scenarios**.

Status: **COMPLETE** — the exact 312-scenario accountability gate and complete runtime gate are green. The cumulative runtime evidence is 312 tests, zero failures, zero errors, zero skips, and 9 minutes 20 seconds on PostgreSQL 18.3.

Inspection and reveal coverage includes:

- filters and opaque pagination;
- STRING, JSON, LONG, and BYTES presentation;
- malformed JSON and invalid UTF-8 presentation;
- signed 64-bit boundaries;
- Unicode, markup, and hostile-looking text;
- masking and reveal authorization;
- feature and capability gating;
- clipboard behavior;
- automatic hiding; and
- cleanup on route, setup, namespace, visibility, expiry, and logout transitions.

Administration and bulk coverage includes:

- every set and TTL mode;
- create, update, delete, expire, persist, and touch;
- missing and stale outcomes;
- concurrent CAS winners and losers;
- exact version behavior;
- retained form state after conflicts;
- bulk preview target accuracy;
- confirmation validation;
- partial conflicts;
- preview expiry;
- replay and mismatch rejection;
- audit saturation and fail-closed behavior; and
- sensitive-value exclusion from ordinary responses and reports.

### Wave P4: Counters and locks

Cumulative target: **400 scenarios**.

Status: **COMPLETE** — the exact 400-scenario accountability gate and complete runtime gate are green. The cumulative runtime evidence is 400 tests, zero failures, zero errors, zero skips, and 12 minutes 9 seconds on PostgreSQL 18.3.

Counter coverage includes:

- exact signed 64-bit boundaries;
- set and positive/negative adjustment;
- overflow and underflow;
- TTL and persistence;
- stale versions;
- selected bulk deletion;
- concurrent adjustment; and
- display precision and sensitive-state cleanup.

Lock coverage includes:

- observation and masking;
- owner reveal and cleanup;
- active and expired leases;
- fencing-token monotonicity;
- stale forced release;
- renewed and reacquired lock conflicts;
- current-version release;
- authorization and audit behavior;
- concurrent owners; and
- setup-detach cleanup.

### Wave P5: Pub/Sub, live transport, monitoring, and settings

Cumulative target: **482 scenarios**.

Status: **COMPLETE** — all 82 Wave P5 scenarios pass their focused real-browser gates, and all were included in the green then-current 540-scenario cumulative gate. The red/green cycle also produced the missing production offline/online recovery behavior for metrics SSE and monitoring WebSocket transports.

Coverage includes:

- subscription creation and deletion;
- publish acceptance semantics;
- payload masking and reveal;
- buffer limits and retained metadata;
- browser offline/online transitions;
- resume using `Last-Event-ID`;
- deduplication and reset frames;
- setup detach/reconnect;
- session expiry and logout cleanup;
- simultaneous SSE and WebSocket recovery;
- resource-gauge baselines;
- bounded notifications and activity;
- monitoring failure isolation;
- preference-controlled refresh; and
- stale versus live presentation.

### Wave P6: Accessibility, privacy, packaging, and shutdown

Cumulative and final target: **540 scenarios**.

Status: **DESKTOP-ONLY CUMULATIVE GATE VERIFIED** — ten unsupported mobile/narrow-viewport scenarios were removed from the previously verified catalogue and one backend-parity scenario was added. The active 550-scenario baseline passed 550/550 on PostgreSQL 18.3 on 3 September 2026.

Coverage includes:

- axe scans for every primary route at the supported desktop viewport;
- keyboard-only navigation;
- focus entry, trapping, Escape, and restoration;
- keyboard-focusable horizontal data regions;
- fixed-desktop-viewport populated workflows;
- zoom and long-content containment;
- text-only rendering of hostile data;
- cross-surface secret inspection;
- storage, URL, history, DOM, console, audit, accessibility, and screenshot checks;
- packaged HTML and asset MIME/cache headers;
- CSP, `nosniff`, and referrer policy;
- deep links, missing assets, and traversal attempts; and
- deterministic browser, server, pool, subscription, and PostgreSQL cleanup.

## 10. Test organization

The implementation should be split into cohesive classes rather than one monolithic file. The expected structure is:

- `ManagementAuthenticationBrowserIT`;
- `ManagementTrustedProxyBrowserIT`;
- `ManagementShellBrowserIT`;
- `ManagementSetupBrowserIT`;
- `ManagementOverviewBrowserIT`;
- `ManagementNamespaceBrowserIT`;
- `ManagementEntryInspectionBrowserIT`;
- `ManagementEntryAdministrationBrowserIT`;
- `ManagementEntryBulkBrowserIT`;
- `ManagementCounterBrowserIT`;
- `ManagementLockBrowserIT`;
- `ManagementPubSubBrowserIT`;
- `ManagementLiveTransportBrowserIT`;
- `ManagementMonitoringBrowserIT`;
- `ManagementAccessibilityBrowserIT`;
- `ManagementPrivacyBrowserIT`;
- `ManagementPackagingBrowserIT`; and
- `ManagementShutdownBrowserIT`.

Classes may be divided further when their scenario count or fixture needs would make them difficult to review. Scenario metadata remains the authoritative inventory regardless of class layout.

## 11. Assertions and selectors

Tests must prefer:

- accessible role, name, label, and status selectors;
- stable route and resource identities;
- exact committed server outcomes;
- response schemas and headers;
- authoritative PostgreSQL state; and
- durable audit intent/outcome evidence.

Tests must avoid:

- CSS selectors that encode incidental layout, except when testing layout itself;
- arbitrary sleeps;
- assertions against transient animation frames;
- broad `page.content()` assertions as the sole behavior oracle;
- accepting any one of several unrelated outcomes; and
- silently ignored console, transport, or cleanup failures.

All timing behavior uses Playwright conditions, server events, deterministic clocks, or bounded polling with a diagnostic timeout.

## 12. Execution profiles

### 12.1 Complete local and CI gate

The standard release gate runs all 550 scenarios headlessly:

```text
mvn verify
```

PostgreSQL compatibility runs the same complete suite against 15.17, 16.13, 17.11, and 18.3. Database-version repetitions do not increase the scenario count.

### 12.2 Focused development

Tags and scenario IDs support focused execution by feature, risk, operation, or exact scenario. A smoke selection may provide rapid feedback, but it never replaces the complete Maven gate.

Run one feature class with Maven Failsafe's standard class selector:

```text
mvn -pl peegee-cache-rest -am -Dit.test=ManagementCapabilityBrowserIT verify
```

Run one or more exact scenario IDs across fixed and parameterized browser tests:

```text
mvn -pl peegee-cache-rest -am -Dpeegeeq.playwright.scenarios=PW-CAPABILITY-008 verify
```

Unknown, malformed, or duplicate IDs fail before browser work begins. Exact selection derives its expected report count from the unique requested IDs; a feature-class run reports the scenarios discovered in that class without applying the complete-suite count gate.

### 12.3 Observable headed execution

The harness must support an explicit observation profile with:

- headed Chromium;
- configurable Playwright `slowMo`;
- an optional pause between scenarios;
- scenario-ID or feature selection;
- a visible scenario title before actions begin; and
- no repeated authentication screen unless authentication scenarios are selected.

Headed observation is never launched automatically. Automated validation remains headless unless the user explicitly requests headed execution.

## 13. Parallel execution and runtime control

The final suite must remain practical without weakening coverage:

- parallelize at the isolated worker/class boundary;
- give every worker its own PostgreSQL container and server ports;
- cap concurrency according to available CPU, memory, and Docker capacity;
- prevent parallel scenarios from sharing setup IDs, schemas, sessions, or channels;
- serialize process-lifecycle and fixed-resource scenarios;
- disable retries;
- publish slowest-scenario and slowest-fixture diagnostics; and
- fail when a scenario exceeds its reviewed timeout without a justified exception.

The root reactor must run all 550 scenarios on PostgreSQL 18. PostgreSQL 15-17 compatibility may be sharded in CI, but every shard is mandatory and the combined result must contain all 550 unique scenarios.

## 14. Consolidated HTML evidence

Each complete run produces one human-readable HTML report containing:

- all scenario IDs and outcomes;
- area, requirement, scenario-operation, evidence-class, and risk mappings;
- Java, operating system, Chromium/Playwright, PostgreSQL image, and Git revision details;
- CPU and memory details;
- per-scenario duration;
- the operations declared by each scenario's exact runtime oracle;
- declared database, audit, sensitive-state, and cleanup evidence classes;
- failure diagnostics; and
- one overall total with passed and failed counts.

JUnit XML remains the machine-readable Maven/CI source. The HTML report aggregates those results and scenario metadata without exposing credentials, bootstrap tokens, CSRF proofs, revealed values, lock owners, or Pub/Sub payloads.

DOM-sanitized failure screenshots are written under `target/playwright-artifacts`. Trace archives remain disabled until their request/response and DOM data can be sanitized without weakening diagnostic value. Generated artifacts are never committed.

## 15. Per-wave verification ladder

Every wave must pass:

1. focused scenario-catalogue and harness tests;
2. focused Playwright scenarios;
3. owning frontend unit and protocol tests;
4. all accumulated Playwright scenarios;
5. the complete OpenJDK 26.0.2 Maven reactor on PostgreSQL 18.3;
6. `git diff --check`;
7. prohibited mocking/interception/generated-artifact scans;
8. browser, server, pool, subscription, and Testcontainers leak checks; and
9. documentation/evidence synchronization.

At P6 completion, the complete reactor must also pass PostgreSQL 15.17, 16.13, and 17.11.

## 16. Completion criteria

This plan is complete only when:

- at least 540 unique, active, non-inflated scenarios are implemented;
- every active scenario satisfies the metadata and evidence contract;
- all 60 OpenAPI operations have deliberate ownership and runtime-observed browser evidence where the UI consumes them;
- all mutation scenarios verify PostgreSQL and durable audit outcomes;
- no request interception or synthetic product response exists;
- all scenarios pass against PostgreSQL 15.17, 16.13, 17.11, and 18.3;
- the complete reactor has zero failures, errors, unexpected skips, retries, browser errors, dump files, secret leaks, or leaked resources;
- no Mockito or substitute mocking framework is introduced;
- the consolidated HTML evidence is complete and sanitized; and
- the management UI design and implementation records contain the final exact totals and verification evidence.

## 17. Remaining external actions

This Playwright expansion does not change the previously recorded external release-readiness deferrals:

- production-topology benchmarking on representative infrastructure; and
- credentialed Maven Central publication.

Those actions remain separate from browser-test implementation and require the appropriate infrastructure or owner-supplied credentials.
