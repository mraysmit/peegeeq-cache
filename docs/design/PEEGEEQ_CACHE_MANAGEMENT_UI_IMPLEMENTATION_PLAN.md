# PeeGeeQ Cache Management UI Implementation Plan

**Status:** Phase 8.3 COMPLETE; U0-U11 complete with packaged Chromium/PostgreSQL acceptance, verified backend-facade parity, and PostgreSQL 15-18 compatibility evidence
**Date:** 30 August 2026
**Delivery method:** strict test-driven development
**Target:** production React management console served by `peegee-cache-rest` at `/ui/*`

## 1. Purpose

This document is the execution authority for Phase 8.3 of the PeeGeeQ Cache roadmap. It turns the approved management UI design into ordered, test-first slices with objective entry gates, red/green evidence, module ownership, and completion criteria.

The follow-on expansion from the completed real-browser acceptance backbone to the active 550-scenario desktop-only Playwright catalogue is tracked separately in [PEEGEEQ_CACHE_PLAYWRIGHT_IMPLEMENTATION_PLAN.md](PEEGEEQ_CACHE_PLAYWRIGHT_IMPLEMENTATION_PLAN.md). That assurance plan does not reopen the completed U0-U10 product implementation boundary.

The management backend is already complete. This plan does not reopen backend phases M0-M10 or weaken their security, audit, lifecycle, compatibility, packaging, and observability guarantees. Backend changes are allowed only when a production-console integration test exposes a missing browser-facing behavior, such as static asset delivery. Every such change begins with a focused failing Java or full-browser test and retains the complete backend regression suite.

## 2. Authority and current baseline

The following documents are authoritative, in order:

1. [PEEGEEQ_CACHE_MANAGEMENT_API.md](PEEGEEQ_CACHE_MANAGEMENT_API.md) for REST, SSE, WebSocket, security, error, and DTO behavior;
2. `peegee-cache-rest/src/main/openapi/peegeeq-cache-management-v1.yaml` for the machine-readable HTTP contract;
3. [PEEGEEQ_CACHE_MANAGEMENT_UI_DESIGN.md](PEEGEEQ_CACHE_MANAGEMENT_UI_DESIGN.md) for product behavior, information architecture, security, and visual design;
4. [PEEGEEQ_CACHE_MANAGEMENT_BUILD_DECISION.md](PEEGEEQ_CACHE_MANAGEMENT_BUILD_DECISION.md) for module ownership and the single Maven release entry point;
5. this plan for implementation order and evidence gates;
6. [PEEGEEQ_CACHE_IMPLEMENTATION_PLAN.md](PEEGEEQ_CACHE_IMPLEMENTATION_PLAN.md) for project-wide status.

The execution baseline is:

- management backend phases M0-M11 are complete;
- the OpenAPI contract contains 50 stable operation identifiers covering REST, SSE, and WebSocket surfaces;
- PostgreSQL 15.17, 16.13, 17.11, and 18.3 complete-reactor verification is green;
- the backend-owned non-production browser harness proves cookie, CSRF, Origin, Fetch Metadata, no-store, storage-exclusion, and static-route isolation behavior;
- `peegee-cache-management-ui` publishes the production U1 React shell as a Maven JAR containing Vite's entry point and fingerprinted asset graph;
- `peegee-cache-rest` consumes that UI artifact directly, safely serves exact assets and SPA routes, and no longer owns a duplicate fallback `ui/index.html`;
- the sibling `peegeeq-management-ui` remains the interaction and visual reference, but its dependency ranges and accidental implementation structure are not copied blindly;
- OpenJDK 26.0.2 is the current verification JDK while emitted Java bytecode remains Java 21.

## 3. Scope

### 3.1 Included

- reproducible Node/npm/Vite lifecycle owned by `peegee-cache-management-ui` and invoked by the root Maven reactor;
- generated TypeScript compile-time types and runtime Zod validation derived from the stable OpenAPI contract;
- application shell, routing, theme, session bootstrap, role and capability gates, setup and namespace scope, notifications, and connection state;
- Overview, Cache Setups, Namespaces, Key Browser, Key Details, Counters, Locks, Pub/Sub, Monitoring, Settings, and recent activity;
- guarded entry, counter, lock, setup, bulk-delete, and pub/sub operations already exposed by the backend;
- sensitive-state isolation for bootstrap tokens, passwords, entry values, lock owners, and pub/sub payloads;
- real REST, SSE, and WebSocket client behavior, including bounded reconnect and stale-state presentation;
- desktop keyboard, semantic, contrast, reduced-motion, and screen-reader behavior;
- production static asset packaging and serving from the runnable REST artifact;
- frontend unit/component tests, protocol-level fixture tests, real-backend browser acceptance, and static safety checks;
- operational documentation for development, packaging, deployment, and troubleshooting.

### 3.2 Excluded

- changes to cache semantics or application-facing cache APIs;
- new backend management operations not present in the approved API contract;
- persistent management users, credentials, or UI-entered setup storage;
- database or schema deletion, bulk lock release, lock acquisition/renewal, a SQL console, or Redis-compatible controls;
- application-wide hit-rate or latency claims;
- central telemetry querying, durable UI activity history, or retained pub/sub history;
- production-topology performance qualification and credentialed Maven Central publication, which remain separate release-readiness actions.

## 4. Fixed implementation decisions

### 4.1 Toolchain and dependency policy

- Node is pinned to `v22.22.2` and npm to `10.9.4`. This deliberately advances within the approved Node 22 LTS major from the older reference patch so supported ESLint 10 and jsdom 30 can be used without engine overrides.
- `frontend-maven-plugin` installs the pinned toolchain inside the module build; a developer's global Node installation is not a release prerequisite.
- `package-lock.json` is committed and Maven always uses `npm ci`, never `npm install`.
- Direct and development dependency versions are exact in `package.json`; transitive resolution is locked by `package-lock.json`.
- The initial major stack is React 18, TypeScript, Vite 6, Ant Design 5, React Router, Redux Toolkit/RTK Query, Zustand, Zod, Recharts, Vitest, Testing Library, and Playwright.
- Dependency upgrades are separate reviewed changes. A feature slice cannot silently update the frontend toolchain.
- npm lifecycle scripts cannot download executable content during normal test or package phases after `npm ci` has completed.

### 4.2 Contract consumption

- The authoritative OpenAPI file remains under `peegee-cache-rest/src/main/openapi`; it is not copied into frontend source.
- `openapi-typescript` generates compile-time types into `peegee-cache-management-ui/target/generated-sources/openapi` during the Maven build.
- Generated files are build output and are not committed.
- A committed operation manifest in the UI module classifies all 59 operation identifiers by transport, role, sensitivity, and owning feature slice.
- A contract test fails when an OpenAPI operation is added, removed, renamed, or reclassified without an explicit UI decision.
- Zod schemas validate every REST response, problem response, SSE event, and WebSocket message at runtime. Compile-time generation alone is insufficient.
- Protocol incompatibility produces a bounded, sanitized `CONTRACT_MISMATCH` client state; it never renders unvalidated data.
- Identifiers use one tested unpadded Base64 URL UTF-8 codec shared by all routes. Raw identifiers and sensitive values never enter URLs.

### 4.3 State ownership

| State | Owner | Persistence |
|---|---|---|
| REST request data and status | RTK Query | Memory only |
| Selected setup and namespace | Zustand scope store | Session storage allowlist only |
| SSE/WebSocket connection state | Zustand connection store | Memory only |
| Notification drawer | Zustand notification store | Memory only, bounded |
| Preferences | Zustand preferences store | Allowlisted non-sensitive values only |
| Entry values, lock owners, pub/sub payloads | Detail component | Memory only; actively cleared |
| Setup password and local bootstrap token | Form component | Memory only; cleared after use |
| Session cookie | Browser, server-issued `HttpOnly` cookie | Never read by JavaScript |
| CSRF proof | Session bootstrap service | Memory only |

There are no page-level HTTP calls. REST uses one RTK Query base layer; SSE and WebSocket use dedicated transport adapters that share session, schema validation, lifecycle, and error policy.

### 4.4 Build and artifact boundary

The root Maven reactor remains the only release build entry point.

- `initialize`: install pinned Node and npm;
- `generate-resources`: run `npm ci`, validate the OpenAPI operation manifest, and generate TypeScript contract types;
- `test`: run type checking, linting, static safety checks, Vitest, and Testing Library tests;
- `prepare-package`: run the deterministic Vite production build with base path `/ui/`;
- `package`: create the UI JAR containing `ui/index.html`, hashed `ui/assets/*`, and only required public files;
- `integration-test`: no browser simulation is run in the UI module; Vitest owns component and protocol behavior;
- `verify`: run artifact checks, then let the downstream REST module run Java Playwright against the packaged application and real server transports.

`peegee-cache-rest` declares the UI artifact as a dependency. The shaded runnable JAR merges the UI resources without writing generated output into either module's source tree. The backend fallback page is removed once the production artifact is active, preventing duplicate `ui/index.html` resources.

Static serving must distinguish:

- `/ui` and `/ui/`: application entry point;
- `/ui/assets/<hashed-name>`: exact packaged asset with correct content type and immutable caching;
- valid client-side application routes: no-store application entry point for SPA routing;
- missing asset-like paths: `404`, never the application entry point;
- `/api/*` and `/ws/*`: never intercepted by UI fallback;
- traversal, encoded traversal, malformed percent encoding, non-GET methods, and unexpected classpath paths: rejected.

The application entry point uses `Cache-Control: no-store`; fingerprinted assets use `public, max-age=31536000, immutable`. All responses retain `nosniff`, restrictive referrer policy, and the reviewed Content Security Policy.

## 5. Strict TDD protocol

Every executable behavior follows this loop:

1. select one observable behavior from the current phase inventory;
2. add the narrowest test at the lowest layer that proves the behavior without replacing required integration evidence;
3. run the focused command and confirm RED for the intended missing behavior;
4. record the failing test, command, and reason in the phase evidence section before implementation;
5. implement the smallest production change that can satisfy the test;
6. rerun the focused test and confirm GREEN;
7. refactor only while the focused test remains green;
8. run the owning frontend or Java module gate;
9. run the browser slice when the behavior crosses the browser/server boundary;
10. run the full root `mvn verify` gate before a phase status changes.

A valid RED test must fail an assertion about missing or incorrect observable behavior. Compilation failure caused by an intentionally absent production type is acceptable only for the first test of a new boundary. Dependency download, browser installation, Docker availability, port collision, timeout, or fixture startup failures are infrastructure failures and do not count as RED evidence.

Prohibited shortcuts:

- Mockito or any substitute mocking framework;
- `vi.mock`, fetch replacement, module replacement, or framework-generated service mocks used to simulate the management protocol;
- disabled, focused-only, order-dependent, retry-to-pass, or timing-sleep tests;
- broad snapshots that approve behavior without semantic assertions;
- hard-coded successful DTOs that bypass serialization and runtime validation;
- optimistic success state before the server confirms a mutation;
- empty catches or ignored Promise/Future failures;
- weakening an assertion merely to turn RED into GREEN.

## 6. Test architecture

### 6.1 Static and contract gates

Static tests verify:

- all 59 OpenAPI operation identifiers are classified exactly once;
- generated contract types are reproducible from the authoritative OpenAPI file;
- every network response/event crosses a Zod parser;
- sensitive DTOs cannot be assigned to Redux/Zustand stores, persistence middleware, URL builders, notification models, analytics, or logging helpers;
- mutation operations are classified as CSRF protected and operator-only where the API requires it;
- no direct page-level `fetch`, Axios, `EventSource`, or `WebSocket` construction exists;
- no Mockito, substitute mocking framework, empty catch, ignored Promise, test exclusion, or committed generated/build output exists;
- production dependency licenses and known-vulnerability policy pass the configured gate.

### 6.2 Frontend unit and component tests

Vitest and Testing Library use real routers, stores, reducers, RTK Query middleware, Zod schemas, and browser APIs where jsdom implements them. Tests exercise visible behavior, accessibility roles, keyboard input, focus transitions, state cleanup, and protocol parsing.

For HTTP/SSE/WebSocket states that cannot be produced by a component alone, tests use a lightweight purpose-built server listening on an ephemeral loopback port. The fixture performs actual serialization, cookies, headers, chunked SSE framing, WebSocket frames, malformed responses, disconnects, and cleanup. It is not a function or module mock and does not replace real-backend acceptance.

### 6.3 Full-browser acceptance

The downstream `peegee-cache-rest` Failsafe phase owns the `ManagementConsole*IT` classes because the REST artifact can only embed the already-packaged upstream UI artifact. This avoids a Maven dependency cycle. The suite is implemented with Java Playwright; the UI module has no second TypeScript Playwright runner.

Each independent Java Playwright journey:

- starts a real PostgreSQL Testcontainer using the matrix-selectable image property;
- boots the actual cache schema;
- starts the actual management server on an ephemeral loopback port;
- loads the compiled UI from the actual server, not Vite dev mode;
- gives every product journey an isolated browser context, server, database, audit file, and PostgreSQL container;
- captures browser console errors, page errors, failed network requests, server logs, audit output, storage, and URLs;
- shuts down contexts, server, pools, subscriptions, and container deterministically.

Trusted-proxy and local-token modes are separate test classes. Trusted-proxy tests send authoritative headers to a real server configured with an explicit loopback trusted-peer CIDR and exercise the real trusted-session authenticator; production TLS termination remains deployment acceptance.

### 6.4 Evidence retention

Phase evidence records:

- focused RED command and intended failure;
- focused GREEN command;
- frontend module test totals;
- Playwright journey totals when applicable;
- root reactor totals and PostgreSQL image;
- leak scan result for browser storage, URLs, console, ordinary logs, audit output, accessibility snapshots, and screenshots;
- any accepted non-blocking warning with rationale.

Failure diagnostics and screenshots belong under module `target/` directories. They are not committed. Stable test names, Failsafe XML, and the implementation plan provide the durable evidence index. The suite does not claim video, trace, or visual-baseline evidence that it does not generate.

## 7. Phase map

### U0: Reproducible frontend and contract foundation

**Status:** COMPLETE — RED/GREEN and all exit gates satisfied 24 August 2026

RED evidence:

- command: pinned npm 10.2.4 `run test:run` under Node 22.12.0;
- intended result: the four U0 test suites cannot resolve the deliberately absent application shell, identifier codec, protocol schemas, and operation manifest;
- observed result: Vitest exits 1 with missing-production-module failures. OpenAPI generation succeeds independently, proving the failure is the intended missing behavior rather than toolchain or contract-input failure;
- an earlier sandbox-denied Vite configuration load is classified as infrastructure noise and is not counted as RED evidence.

GREEN evidence:

- pinned toolchain: Node 22.22.2 and npm 10.9.4 installed and invoked by `frontend-maven-plugin` 1.15.1; the initial RED run used the older reference patch before the dependency-engine gate justified this same-major upgrade;
- dependency gate: committed npm lockfile; `npm audit --audit-level=moderate` reports zero vulnerabilities after upgrading the initially vulnerable Vite, Vitest, React Router, and YAML patch versions;
- contract gate: `openapi-typescript` 7.9.1 generates build-only types from the authoritative OpenAPI file, and the committed manifest classifies all 59 operation identifiers exactly once with matching security and transport declarations;
- focused frontend gate: 4 Vitest files and 24 tests pass for the minimal shell, canonical UTF-8 identifier codec, strict current-session/problem validation, and operation manifest;
- quality gate: TypeScript strict checking and ESLint pass with zero warnings;
- production build: Vite 6.4.3 emits `ui/index.html` plus fingerprinted JavaScript and CSS, with no source maps;
- artifact gate: the UI JAR contains only the production webroot and Maven metadata; generated OpenAPI sources, Node/npm, dependencies, tests, and source maps are absent;
- complete reactor: all 11 modules pass on PostgreSQL 18.3 under OpenJDK 26.0.2 in 3:39; 521 Surefire and 3 Failsafe Java tests plus 24 frontend tests pass with zero failures, errors, or skips;
- durable Maven evidence: `logs/u0-full-verify.log` records npm, frontend, module, browser/runnable, PostgreSQL, and reactor results.

RED inventory:

- root validation rejects the missing pinned frontend toolchain and lockfile;
- contract inventory rejects an empty UI operation manifest against the 50 OpenAPI operations;
- UI artifact test rejects the current empty JAR;
- generated DTO compilation test rejects the missing generated contract boundary;
- safety scan rejects a deliberately introduced unvalidated protocol fixture response.

Implementation:

- add exact `package.json`, `package-lock.json`, TypeScript, Vite, ESLint, Vitest, and frontend Maven configuration;
- add deterministic OpenAPI generation and the 50-operation classified manifest;
- establish source/test directory ownership and module scripts;
- add Zod problem/session envelope validation and the shared identifier codec as the first vertical contract slice;
- produce a minimal production artifact at `/ui/` without product pages;
- document local frontend commands while keeping Maven authoritative.

Exit gate:

- a clean checkout can run `mvn -pl :peegee-cache-management-ui test package` without global Node/npm;
- generation is deterministic, all 59 operations are classified, type/lint/test gates pass, and the UI JAR contains only the expected minimal resources;
- full root `mvn verify` remains green.

### U1: Static hosting, session bootstrap, and application shell

**Status:** COMPLETE — RED/GREEN and all exit gates satisfied 25 August 2026

RED evidence:

- backend command: `mvn -pl peegee-cache-rest -am "-Dtest=ManagementUiHostingTest" "-Dsurefire.failIfNoSpecifiedTests=false" test`;
- intended backend failures: the existing static route omitted `Cache-Control: no-store`, and a same-origin trusted-proxy session bootstrap without an `Origin` header was rejected with `403` instead of succeeding;
- frontend command: `npm run test:run` in `peegee-cache-management-ui`;
- intended frontend failure: the new shell and real session-client tests could not resolve the deliberately absent `ManagementShell` and `session-client` production modules while the existing frontend suites remained green;
- browser installation TLS failure during the first Playwright attempt was an environment failure and is not counted as RED evidence.

GREEN evidence:

- frontend quality: strict TypeScript and ESLint pass with zero warnings; 5 Vitest files and 27 tests pass;
- backend boundary: `ManagementUiHostingTest` passes 2 tests against a real Vert.x listener for packaged asset MIME/cache behavior, SPA deep links, CSP/security headers, missing/traversal rejection, and trusted-proxy bootstrap without an `Origin` header;
- browser acceptance: the production packaged console passes independent local-token and trusted-proxy journeys; the backend security harness was adapted to execute its protocol probe through Playwright rather than CSP-blocked inline page script while retaining cookie, storage, token, no-store, and exact-Origin assertions;
- browser/artifact gate: 5 Failsafe tests pass, comprising the backend security harness, both U1 session journeys, and 2 runnable-artifact checks;
- clean packaging prerequisite: `mvn -pl peegee-cache-rest -am clean install -DskipTests` succeeds before the verification ladder;
- complete reactor: all 11 modules pass on PostgreSQL 18.3 under OpenJDK 26.0.1 in 5:06; 510 Surefire tests, 5 Failsafe tests, and 27 frontend tests pass with zero failures, errors, or skips, and no Surefire/Failsafe dump files are present;
- final local verification transcript: `logs/u1-full-verify-final.log` records the ignored workspace evidence; authoritative durable evidence remains the stable test names and this plan.

Verification diagnosis and portability correction:

- the first complete-reactor attempts exposed a deterministic Windows checkout defect, not an intermittent test: `postgres-tls-init.sh` contained CRLF bytes and Alpine reported `/bin/sh^M: bad interpreter`, causing Testcontainers exit code `126` in `VertxPinnedDatabaseConnectorTest` and `PostgresSetupRuntimeFactoryTest`;
- `.gitattributes` now enforces `*.sh text eol=lf`; the checked fixture contains LF bytes, and the two TLS fixtures pass together from a clean reactor with 3 tests and zero failures, errors, or skips;
- a later complete-reactor attempt exposed the legacy browser harness's reliance on inline script after loading a page now protected by U1 CSP; the harness now performs the same real-browser protocol assertions through Playwright evaluation and passes from a clean reactor without weakening CSP.

Post-review browser hardening on 25 August 2026:

- expanded the packaged-console gate from 2 to 6 Playwright journeys covering invalid and replayed bootstrap tokens, encoded-query deep links, failed logout with visible retry, bounded client/session expiry, and trusted-proxy identity/role changes with cookie rotation;
- corrected failed logout handling so a `5xx` response retains the authenticated shell and in-memory CSRF proof until termination can be retried; an authoritative `401` still clears local state;
- marked reveal operations as CSRF-protected in the UI operation manifest, limited encoded-traversal inspection to the raw path rather than SPA query data, and retained browser-security headers on UI error responses;
- moved the Vite artifact build to `process-classes` so a clean reactor stopped at `test` provides `/ui/index.html` to downstream REST tests instead of failing or reading stale output;
- `mvn -pl peegee-cache-rest -am verify` with the focused hosting/lifecycle and complete Failsafe selections passes 8 Surefire tests, all 9 browser/artifact Failsafe tests, and 28 frontend tests with zero failures, errors, or skips.

Browser-evidence reset on 30 August 2026:

- the former TypeScript route-intercepted matrix was removed because counting mocked routes did not establish product behavior;
- browser automation is now owned by `peegee-cache-rest` in Java and exercises the packaged UI, real HTTP/SSE/WebSocket transports, and real PostgreSQL without Playwright request interception;
- `ManagementBrowserCoverageTest` makes 17 named journeys and all 59 OpenAPI operations an executable accountability contract, including a single accountable owner for each journey and operation; runtime tracing additionally fails when a product journey does not actually emit a request or WebSocket opening for an operation it declares;
- the current catalogue contains 550 independently reported Java Playwright scenarios, including 13 isolated product journeys against real TLS PostgreSQL; three additional Failsafe checks validate runnable-artifact and evidence integrity.

RED inventory:

- hashed JavaScript/CSS requests currently receive HTML instead of the asset and correct MIME type;
- missing asset-like paths currently receive the SPA entry point instead of `404`;
- traversal, cache policy, CSP, and SPA deep-link assertions precede server changes;
- session/bootstrap tests precede authentication UI and in-memory CSRF handling;
- route/menu/accessibility tests precede the shell.

Implementation:

- package and serve the Vite asset graph safely from the UI dependency, with exact asset lookup, MIME types, immutable asset caching, no-store SPA responses, CSP, and traversal/missing-asset rejection;
- implement runtime-validated session bootstrap for trusted-proxy and local-token modes, same-origin safe-read handling, local logout, and bounded session-expiry scheduling;
- implement the desktop shell, theme, sidebar, header, route table, error boundary, role model, connection indicator, notification drawer, and sanitized client diagnostics;
- keep the bootstrap token and CSRF proof memory-only, clear bootstrap input after exchange, and keep both browser storage areas empty;
- retain the backend-owned browser harness as an independent backend security gate, updated only to remain executable under the production CSP.

Exit gate:

- packaged asset, MIME, cache, CSP, traversal, SPA fallback, `/api`, and `/ws` isolation tests pass;
- both session modes reach an authenticated shell against the real server;
- logout/session expiry clears sensitive and scoped state;
- no browser console, page, storage, URL, or server-log leak is present.

### U2: Setup lifecycle, scope, and capability gating

**Status:** COMPLETE

First functional slice delivered on 25 August 2026:

- replaced the Setups placeholder with contract-validated discovery, empty/loading/error states, setup metadata and health summaries, details-on-demand, and explicit active-setup scope selection;
- added complete operator forms and actions for TLS-only connection testing, registration, registered-setup testing, connect, detach, and forget, with confirmations, serialized actions, viewer/feature gating, RFC 9457 diagnostics, and password cleanup after submission failure or modal closure;
- extended the authenticated client with a memory-only CSRF request boundary and strict runtime validation for setup lists, summaries, details, and connection tests, plus contract validators ready for health and capability presentation;
- added focused unit/protocol tests and real packaged-server setup workflows; the superseded route-intercepted count is no longer accepted as evidence.

Health, capability, and scope TDD increment on 25 August 2026:

- RED proved the missing behavior through `client.health is not a function`, absence of the `Database health` details region, visibility of unsupported navigation, a missing scope-storage module, and zero persisted allowlist entries after scope selection;
- GREEN adds strict health/capability response parsing, combined details/health/capability discovery, explicit health timestamps and capability limits, and hides destinations unsupported by the selected setup while retaining server-side authorization;
- a Zustand setup scope store persists only `{"setupId":"<canonical-id>"}` under the versioned session-storage allowlist, rejects malformed/expanded/noncanonical state, revalidates capabilities after reload, and clears both memory and persistence through the existing session cleanup path;
- the complete npm verification covers the focused setup/scope unit and protocol behavior;
- the authoritative `mvn -pl peegee-cache-management-ui verify` lifecycle independently repeats `npm ci`, OpenAPI generation, typecheck, lint, production build, Vitest, and packaged-artifact validation. Browser acceptance remains downstream because only the REST module can exercise the packaged server boundary.

Namespace invalidation and real-database acceptance increment on 25 August 2026:

- RED first produced six focused failures because namespace persistence and `selectNamespace` did not exist, then the first real-browser lifecycle run exposed Chromium rejecting the unescaped setup-ID pattern under Unicode Sets rules;
- GREEN extends the strict session-storage allowlist to either `{"setupId":"<canonical-id>"}` or `{"setupId":"<canonical-id>","namespace":"<validated-namespace>"}`, retains namespace across same-setup capability revalidation, and invalidates it whenever the setup changes, detaches, is forgotten, or the session ends;
- a component regression proves a persisted arbitrary namespace survives reload revalidation but is removed when another setup becomes active;
- `ManagementConsoleProductJourneysIT` runs the independent setup and scope journeys against TLS PostgreSQL, the real cache schema, and the packaged console while proving password, bootstrap-token, and scope cleanup;
- the browser-discovered setup-ID constraint defect is fixed with a Unicode-Sets-compatible escaped hyphen and a focused component regression assertion;
- the earlier checkpoint is superseded by the final validation record in U10; route-intercepted test totals are intentionally excluded.

RED inventory covers setup list/detail, test/register/connect/detach/forget, password cleanup, target-policy errors, viewer/operator differences, capability navigation, and scope reset.

Implementation:

- implement Cache Setups and setup forms using all setup operations;
- implement setup/namespace scope stores with an explicit session-storage allowlist;
- implement health/capability discovery, disconnected and empty states, and action gating;
- serialize concurrent user actions in the UI while retaining server authority.

Exit gate:

- a real operator can test, register, inspect, detach, reconnect, and forget an in-memory setup;
- a viewer cannot invoke operator actions through either visible controls or direct browser requests;
- passwords are absent from stores, storage, notifications, URLs, logs, traces, and retained form state;
- changing or removing a setup clears invalid namespace and sensitive state.

### U3: Overview and namespace inspection

**Status:** COMPLETE — all Overview, namespace inspection, observability, and concurrent-mutation exit gates satisfied 29 August 2026

Overview and namespace strict-TDD increment on 26 August 2026:

- RED first failed because the inspection client, strict inspection schemas, shared display-time boundary, and functional Overview and Namespaces pages did not exist;
- GREEN adds strict runtime validation for Overview, namespace list/detail, and export responses; exact decimal-string/`BigInt` count rendering; database-wide labels; privilege-aware unavailable values; visibly timestamped stale data; and validated recovery;
- Namespaces now supports submitted prefix/status/sort filters, an opaque forward/back cursor stack without client-side cursor reconstruction, server-produced JSON export validated before download, namespace details tabs, and namespace scope selection only after a validated detail response;
- one display-time formatter owns UTC-by-default and browser-local presentation for setup, Overview, and namespace timestamps;
- `ManagementConsoleProductJourneysIT` seeds real typed entries, counters, and locks, then verifies the packaged console's database-wide Overview totals, namespace details, scope persistence, and cleanup through Chromium;
- the follow-on RED tests failed because the client had no database-monitoring, runtime-monitoring, or activity methods and the Overview lacked the corresponding panels and trend history;
- GREEN adds strict OpenAPI-derived validation for database and management-runtime monitoring plus bounded activity, permission-aware storage/row/connection values, pool/audit/sweeper/operation telemetry, and independently stale supplementary panels that never displace a valid database snapshot;
- the current-session cache-row trend retains at most 30 validated snapshots, preserves exact decimal-string values through `BigInt`, and is never persisted; recent management-local activity refreshes every 15 seconds, remains bounded, and never renders the raw resource identifier;
- server repository tests mutate PostgreSQL between cursor requests and verify exact forward/back boundaries without duplicate or fabricated rows; the packaged browser journey separately verifies opaque cursor navigation against database truth;
- the earlier checkpoint is superseded by the final 30 August validation record in U10.

RED inventory covers database versus console-runtime labels, permission-aware unavailable values, cursor navigation, scope transitions, timestamps, stale markers, export, and empty/error states.

Implementation:

- implement Overview cards, charts, namespace summary, expiry/storage/connection panels, and recent activity shell;
- implement Namespaces list, prefix filter, cursor stack, export, and Namespace Details tabs;
- use UTC ISO input with a single display-time preference formatter;
- label every displayed metric as database-wide or management-server-local.

Exit gate:

- displayed counts agree with real PostgreSQL fixtures;
- unavailable privileged statistics never render as zero;
- cursor back/forward behavior survives concurrent database changes without duplicate or fabricated rows;
- stale data remains visibly timestamped during interruption and becomes fresh only after validated recovery data.

### U4: Entry browsing, reveal, and formatting

**Status:** COMPLETE — strict RED/GREEN evidence, full regression, and packaged TLS PostgreSQL acceptance are green as of 30 August 2026

Entry inspection strict-TDD increment on 29 August 2026:

- RED first failed because the inspection client exposed no entry list/detail/reveal methods, strict value schemas, Key Browser, Key Details, value formatters, or reveal lifecycle;
- GREEN adds metadata-only entry list/detail validation, setup-plus-namespace scoping, prefix/value-type/TTL filters, exact decimal-string rendering, and an opaque forward/back cursor stack;
- arbitrary UTF-8 namespace and key identifiers use server-produced Base64url route segments, while ordinary URLs never contain raw identifiers or values;
- the OpenAPI `CacheValue` discriminator now maps the wire values `STRING`, `JSON`, `LONG`, and `BYTES` explicitly, keeping generated TypeScript aligned with the server contract;
- sensitive reveal is available only when the session role, session feature, and selected-setup capability all permit it; reveal uses CSRF-protected `POST`, client-side `no-store`, and rejects a successful response unless `Cache-Control: no-store` and `Pragma: no-cache` are both present;
- revealed values live only in Key Details component state and are cleared by explicit Hide, timeout, document visibility loss, route change, setup change, capability loss, and unmount; copy is an explicit user action;
- STRING text/escaped text, JSON tree/formatted/raw validation, exact LONG decimal, and BYTES hexadecimal/Base64/strict UTF-8-attempt/size views render through React text nodes only and never use HTML injection;
- focused protocol/component tests cover cache-header rejection, strict response unions, values injected into metadata, cursor consistency, arbitrary identifiers, precision, invalid JSON/Base64/UTF-8, markup payloads, viewer gating, copy, and every cleanup trigger;
- component tests prove operator and viewer rendering, CSRF/reason transmission, encoded routes, explicit hide, and automatic cleanup;
- independent packaged Chromium journeys verify metadata-only detail, safe STRING/JSON/LONG/BYTES rendering, operator reveal, and cleanup against real seeded values in TLS PostgreSQL.

RED inventory covers metadata-only lists, filters, cursor history, arbitrary identifier routing, STRING/JSON/LONG/BYTES formatters, invalid UTF-8, reveal authorization, no-store, copy, hide, timeout, visibility, route, and scope cleanup.

Implementation:

- implement Key Browser and metadata-only Key Details;
- implement shared safe text, escaped text, JSON tree/text, decimal, hexadecimal, Base64, UTF-8 attempt, and byte-size formatters;
- implement operator reveal into detail-component memory only;
- implement explicit copy/hide behavior and all mandatory cleanup triggers.

Exit gate:

- metadata requests and ordinary UI state never contain entry values;
- arbitrary UTF-8 identifiers round-trip through routes without raw identifier exposure;
- reveal data is removed on every specified trigger and absent from Redux, Zustand, storage, URLs, notifications, console, and ordinary logs;
- formatter output is text-only and cannot inject markup.

### U5: Entry mutation and bulk deletion

**Status:** COMPLETE — application, strict client/component tests, and independent packaged-server PostgreSQL/audit browser acceptance are green

Strict-TDD implementation increment completed 30 August 2026:

- entry create/edit supports absent, present, upsert, and observed-version CAS modes without discarding form state on a conflict;
- TTL, persist, touch, exact-version delete, bulk preview, typed confirmation, expiry, single-use execution, and partial-conflict summaries use validated authoritative responses;
- the browser suite covers committed-result presentation, stale conflict recovery, exact preview targets, token expiry/replay/mismatch rejection, CSRF, and sensitive-state exclusion;
- the PostgreSQL management repository and route suites independently prove atomic mutations, concurrency failures, and durable fail-closed audit outcomes.

RED inventory covers create/upsert/absent/present/version modes, ETags, validation, CAS conflict, TTL, persist, touch, individual delete, preview scope, typed confirmation, expiry, reuse, filter mismatch, partial conflict, and uncertain audit outcomes.

Implementation:

- implement entry editor and server-confirmed mutation result presentation;
- default existing-entry edits to the observed version and preserve user input on conflict;
- implement TTL/persist/touch/delete controls with refreshed authoritative metadata;
- implement bulk preview/execute with exact scope, phrase, expiry countdown, one-time token, and result summary.

Exit gate:

- every entry mutation is proven against real PostgreSQL and durable audit behavior;
- stale versions never overwrite or delete newer state;
- no mutation shows success before a validated server response;
- expired, reused, or mismatched bulk tokens cannot execute through the UI or direct browser requests.

### U6: Counters and locks

**Status:** COMPLETE — application, precision/cleanup tests, and independent packaged-server PostgreSQL concurrency acceptance are green

Strict-TDD implementation increment completed 30 August 2026:

- counters preserve signed 64-bit decimal strings through list, exact set, signed adjustment, TTL, persist, delete, and selected bulk deletion;
- locks keep owner values masked by default, reveal only into short-lived component state, and require the freshly loaded version plus exact key confirmation before forced release;
- shared accessible dialogs provide initial focus, two-way focus trapping, Escape dismissal, and trigger-focus restoration;
- component and Playwright workflows cover precision, committed results, authorization, reveal cleanup, stale preconditions, and safe release confirmation, while real-PostgreSQL repository/route tests cover concurrency behavior.

RED inventory covers 64-bit decimal-string handling, overflow errors, exact set, signed adjustment, TTL/persist/delete, selected bulk delete, lock metadata, owner reveal cleanup, exact-version forced release, stale renewed/reacquired locks, and forbidden viewer actions.

Implementation:

- implement Counters list/details and all approved counter operations without JavaScript number precision loss;
- implement Locks list/details, operator-only owner reveal, exact key confirmation, and version-checked forced release;
- present committed result values and fencing/version metadata from the server.

Exit gate:

- counter values round-trip at signed 64-bit boundaries;
- stale lock release leaves the current lock untouched;
- lock owners obey the same sensitive-state and audit guarantees as entry values;
- real-database concurrency, failure, authorization, and cleanup browser tests pass.

### U7: Pub/Sub and live transports

**Status:** COMPLETE — Pub/Sub and both live client transports have strict unit and packaged-server publish/receive/recovery/cleanup acceptance evidence

Strict-TDD implementation increment completed 30 August 2026:

- Pub/Sub implements bounded non-durable subscription sessions, UTF-8 channel/payload byte limits, masked metadata history, explicit payload reveal, accepted-not-delivered publish wording, and deterministic stop/cleanup;
- metrics SSE and monitoring WebSocket clients strictly validate event payloads, deduplicate resumable identifiers, use bounded exponential backoff with jitter, surface reset/terminal states, and tear down on scope/session/page boundaries;
- the monitoring socket is opened on demand for the notification drawer rather than during idle shell use;
- the server health event was corrected to emit numeric `latencyMillis`, and focused frontend/backend contract tests reject extra, malformed, and cross-scope live payloads.
- the packaged Chromium journey exposed that same-origin Fetch may omit `Origin` on an SSE GET; the strict-TDD correction accepts that omission only with `Sec-Fetch-Site: same-origin`, retains the exact-origin rule whenever `Origin` is supplied, and keeps WebSocket origin validation mandatory.

RED inventory covers channel validation, byte limits, nullable content type, accepted-not-delivered semantics, subscription lifecycle, SSE parsing, heartbeats, monotonic IDs, bounded reconnect, terminal events, non-durable labeling, payload reveal cleanup, WebSocket activity, and teardown.

Implementation:

- implement Pub/Sub subscription sessions, metadata history, explicit payload reveal, publish, and stop;
- implement metrics SSE and monitoring WebSocket adapters with schema validation and bounded exponential backoff with jitter;
- deduplicate resumable events by opaque/monotonic identifier where the protocol permits;
- close every live transport on logout, scope change, setup detach, page disposal, and terminal event.

Exit gate:

- real PostgreSQL publish/receive and disconnect/recovery journeys pass;
- publish is never automatically retried and `accepted` is never described as delivery;
- payloads remain absent from notification and persistent state;
- active client/server subscription gauges return to baseline after every journey.

### U8: Monitoring, activity, settings, and operational states

**Status:** COMPLETE — monitoring, activity, settings, operational states, live metrics, and packaged-server browser acceptance are green

Strict-TDD implementation increment completed 30 August 2026:

- Monitoring separates database truth from runtime state, consumes live metrics snapshots, preserves stale timestamps, and bounds current-session trends and activity;
- Settings reports identity, roles, versions, endpoint, selected setup, REST/SSE/WebSocket state, capabilities, and the automatic bounded reconnect policy;
- only allowlisted theme, timezone, byte-unit, refresh-interval, and sensitive auto-hide preferences persist; the client auto-hide value is capped by the server capability;
- component and packaged-server browser tests cover live state, sanitized bounded notifications, harmless persistence, stale/recovery presentation, deduplication, and contract failure isolation.

RED inventory covers database/runtime separation, permission-limited values, connection pool values, expiry sweeper state, activity bounds, sanitized notifications, preferences allowlist, refresh/reconnect controls, version display, and health/capability links.

Implementation:

- complete Monitoring database and runtime sections, charts, and live state;
- complete bounded recent activity and notification presentation;
- complete Settings with identity, role, versions, endpoint, transport state, and non-sensitive preferences;
- provide consistent loading, empty, stale, reconnecting, forbidden, not-found, conflict, rate-limit, not-ready, and contract-mismatch states.

Exit gate:

- every field has an explicit scope and unavailable data is distinguished from zero;
- preferences persist only allowlisted non-sensitive display choices;
- activity and notification limits remain bounded under a sustained event fixture;
- interruption and recovery never mislabel stale data as fresh.

### U9: Desktop accessibility, privacy, and threat hardening

**Status:** COMPLETE — route-level desktop accessibility, privacy, injection, keyboard, and visual acceptance plus the packaged-server cross-surface leakage pass are implemented

Strict-TDD implementation increment completed 30 August 2026:

- automated axe scans cover all primary routes at the supported 1440x900 desktop viewport and the populated destructive-dialog workflow;
- keyboard tests cover landmarks, names, route activation, keyboard-focusable horizontal data regions, modal focus entry/trapping/Escape/restoration, and notification state;
- primary routes and the populated counter workflow have containment checks at the supported 1440x900 desktop viewport;
- privacy tests keep bootstrap/CSRF/revealed entry, lock, Pub/Sub, and live-event data out of persistent storage, URLs, notifications, and the DOM after cleanup; server/user strings remain text-only.

RED inventory covers keyboard-only journeys, focus restoration/trapping, landmarks, headings, names/descriptions, live regions, table semantics, contrast, reduced motion, desktop zoom, unsafe text rendering, storage leakage, CSP, and browser history/cache behavior.

Implementation:

- close WCAG 2.2 AA issues in the approved user journeys;
- add automated accessibility scans and semantic assertions to packaged full-browser tests;
- add fixed-viewport visual regression for the shell and destructive dialogs in the controlled Chromium environment;
- complete the privacy and threat checklist for authentication, sensitive reveal, setup credentials, bulk actions, live transports, and client diagnostics.

Exit gate:

- required journeys work with keyboard alone at the supported desktop viewport;
- automated accessibility scans have no serious or critical violations and documented lower-severity findings are resolved or explicitly accepted;
- no secret or raw sensitive content appears in storage, URLs, browser/server logs, reports, screenshots, notifications, or accessibility text;
- reviewed CSP and text-only rendering prevent script/markup injection from server-controlled or user-controlled strings.

### U10: Production packaging and final acceptance

**Status:** COMPLETE — deterministic packaging, consolidated packaged-server U2-U9 journeys, leakage verification, and the post-change PostgreSQL 15-18 matrix are green

Acceptance increment completed 30 August 2026:

- Maven installs the pinned Node/npm toolchain, reports zero dependency vulnerabilities, regenerates the OpenAPI types, type-checks, lints, tests, and creates a source-map-free fingerprinted Vite asset graph;
- the UI JAR and shaded runnable artifact checks are green with a single SLF4J provider and no duplicate fallback UI;
- the clean 2 September 2026 `mvn -o clean verify` gate is green across all 11 modules under OpenJDK 25 and PostgreSQL 18.3: 799 Surefire tests, 553 Failsafe tests, and 129 Vitest tests, all with zero failures, errors, or skips;
- 550 Java Playwright scenarios implement 17 independently named journey owners; the separate Surefire accountability contract covers all 59 operations and runtime tracing verifies declared operations from observed browser traffic. Thirteen isolated `ManagementConsoleProductJourneysIT` cases drive the packaged production asset through Chromium against real TLS PostgreSQL, while three non-scenario Failsafe checks validate the runnable artifact and evidence report;
- the product journeys cover trusted-proxy identity/role rotation and bounded expiry; setup/scope; real namespace cursor round trips and exported JSON content; typed entry read/reveal/clipboard/CAS/TTL/persist/touch; bulk stale conflict, deterministic expiry, deletion, and replay rejection; exact 64-bit counter behavior; lock conflict recovery; Pub/Sub offline retention/resume/reveal/stop; live interruption/recovery/deduplication; desktop axe and viewport containment; cross-surface leakage; packaged response headers; and deterministic shutdown;
- the complete 11-module reactor is green against PostgreSQL 15.17, 16.13, 17.11, and 18.3 after the browser-suite replacement.

Post-completion assurance update, 2 September 2026:

- saved refresh, concealment, timezone, byte-unit, role, and granular capability behavior is now applied and covered rather than merely persisted or statically rendered;
- browser diagnostics reject unexpected failed responses, missing/undeclared operations, missing durable audit actions, browser errors, and leaked resources; mutation journeys also assert committed PostgreSQL state;
- the active desktop-only catalogue contains exactly 550 independently identified Playwright scenarios; the prior 559-scenario cumulative result predates removal of ten unsupported mobile/narrow-viewport cases and the addition of `PW-BACKEND-001`;
- the desktop-only catalogue passed its fresh PostgreSQL 18.3 cumulative gate 550/550 on 2 September 2026; and
- the PostgreSQL 15-18 evidence above remains the U10 pre-expansion matrix.

RED inventory covers missing production resources, wrong asset base, duplicate fallback resources, source maps, development endpoints, non-deterministic output, cache headers, runnable startup, deep links, all required full-browser journeys, and cleanup.

Implementation:

- finalize deterministic Vite output and Maven UI JAR assembly;
- ensure the shaded runnable contains the production UI, one SLF4J provider, OpenAPI, and no test fixtures, development configuration, source maps, credentials, or bootstrap material;
- update management operations and release packaging documentation;
- run the complete frontend, Java, browser, PostgreSQL compatibility, and leakage verification ladder.

Exit gate:

- all acceptance criteria in the UI design pass against the packaged runnable artifact;
- the root reactor passes with no failures, errors, skips, dump files, browser errors, or leaked resources;
- complete-reactor PostgreSQL 15-18 verification remains green;
- documentation records exact test totals, toolchain, artifact evidence, known non-blocking warnings, and remaining external release-readiness actions;
- Phase 8.3 is marked COMPLETE only after this evidence is recorded.

## 8. Required full-browser journeys

The final suite contains independent, named journeys for:

1. trusted-proxy session bootstrap, identity/role rotation, bounded expiry, and seamless proxy revalidation; local logout is intentionally absent because the trusted proxy/IdP controls that session;
2. single-use local-token exchange, replay rejection, storage exclusion, and logout;
3. setup test/register/detach/reconnect/forget with target-policy failures and password leakage checks;
4. setup/namespace scope switching and capability-driven navigation;
5. Overview and namespace database-truth validation;
6. entry create, browse, reveal, format, copy/hide, edit with CAS, expire, persist, touch, and delete;
7. entry bulk preview, exact confirmation, stale conflict, expiry, and replay rejection;
8. counter exact set, signed adjustment, TTL, persist, selected bulk delete, and 64-bit boundaries;
9. real lock observation, owner reveal, stale forced-release rejection, and current-version release;
10. pub/sub subscribe, publish, receive, payload reveal, disconnect, resume, stop, and teardown;
11. metrics SSE and monitoring WebSocket interruption, stale-state presentation, recovery, and deduplication;
12. viewer/operator server-enforced boundaries using direct browser requests as well as visible controls;
13. desktop keyboard, focus, zoom, and automated accessibility acceptance;
14. packaged deep-link/static-asset behavior and security headers;
15. cross-surface leak inspection of responses, cookies visible to JavaScript, storage, history, URLs, DOM after cleanup, accessibility tree, console, server logs, audit output, and screenshots;
16. deterministic shutdown proving browser contexts, transports, server, pools, subscriptions, and PostgreSQL container return to baseline.

## 9. Phase tracking

| Phase | Status | Evidence required to advance |
|---|---|---|
| U0 Foundation | COMPLETE | Pinned Maven-owned toolchain, zero-vulnerability lockfile, 50-operation contract gate, 24 frontend tests, minimal source-map-free UI JAR, and complete PostgreSQL 18.3 reactor green |
| U1 Shell and hosting | COMPLETE | Production UI JAR, exact static/SPA policy, both session modes, authenticated shell, real-server session/expiry/hosting journeys, and complete reactor green |
| U2 Setups and scope | COMPLETE | Functional real-PostgreSQL setup lifecycle, health/capability presentation, capability navigation, strict setup/namespace scope allowlist and invalidation, focused unit/protocol tests, and independent packaged-server browser acceptance green |
| U3 Overview/namespaces | COMPLETE | Strict Overview, database/runtime monitoring, bounded activity, namespace contracts/pages, database-wide counts, permission-aware values, stale/recovery states, opaque-cursor navigation, validated export, and real TLS PostgreSQL browser acceptance green |
| U4 Entry read/reveal | COMPLETE | Strict metadata/value contracts; filtered opaque-cursor browser; encoded arbitrary identifiers; safe STRING/JSON/LONG/BYTES views; role/feature/capability-gated no-store reveal; complete regression and packaged TLS PostgreSQL console acceptance green |
| U5 Entry administration | COMPLETE | CAS/TTL/persist/touch/delete/bulk UI, strict component/client tests, PostgreSQL/audit backend evidence, and independent packaged-browser administration are green |
| U6 Counters/locks | COMPLETE | Precision-safe counter and guarded lock UI, cleanup/focus tests, PostgreSQL concurrency evidence, and independent packaged-browser administration are green |
| U7 Pub/Sub/live | COMPLETE | Bounded Pub/Sub/SSE/WebSocket clients, strict event schemas, lifecycle tests, server resource/recovery evidence, and packaged real publish/receive/recovery are green |
| U8 Monitoring/settings | COMPLETE | Scoped monitoring, live metrics, bounded activity, harmless preferences, operational states, and packaged live/settings acceptance are green |
| U9 Hardening | COMPLETE | Desktop axe, keyboard/focus/zoom, privacy, injection, screenshot, and packaged cross-surface leakage acceptance are implemented |
| U10 Final acceptance | COMPLETE | Deterministic artifacts; 17 named browser journeys with executable ownership/runtime evidence for all 59 operations; active 550-scenario desktop-only catalogue; and the pre-expansion PostgreSQL 15.17, 16.13, 17.11, and 18.3 matrix are retained |

Status changes occur only in the same change set as their evidence. `IN PROGRESS` means at least one valid RED test exists for the phase. `COMPLETE` means every exit criterion and owning regression gate is green. Planning or production code alone cannot close a phase.

## 10. Completion definition

Phase 8.3 is complete only when:

- the production console implements every in-scope screen and workflow in the approved design;
- all 59 OpenAPI operations are deliberately classified and every consumed response/event is runtime validated;
- the server remains the authority for authentication, authorization, Origin, CSRF, target policy, rate limits, concurrency, audit, and mutation outcomes;
- sensitive values are isolated to short-lived component memory and absent from every prohibited surface;
- REST, SSE, and WebSocket failure and recovery behavior is visible, bounded, and leak-free;
- desktop accessibility acceptance is green;
- the root Maven build reproducibly installs the frontend toolchain, tests, builds, packages, and verifies the console;
- the shaded Java 21 runnable serves the complete UI from `/ui/*` under OpenJDK 26.0.2;
- the complete reactor and PostgreSQL 15-18 matrix remain green;
- no Mockito or substitute mocking framework has been introduced;
- the authoritative design, implementation plan, operations guide, and project roadmap all report the same evidence-backed status.
