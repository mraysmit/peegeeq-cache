# PeeGeeQ Cache Management UI — U11 Reference-Parity Migration Handover (4 September 2026)

> **Capability gating removed (10 September 2026).** The per-setup capability advertisement (`GET /api/v1/setups/{setupId}/capabilities`, `SetupCapabilities`, `AdminCapabilities`, `ManagementCapability`, the session `features` block, and the UI capability gates) was removed by [the capability gating removal plan](archive/PEEGEEQ_CACHE_CAPABILITY_GATING_REMOVAL_PLAN_2026-09-04.md). Role checks are the only authorization gate, effective byte limits are carried by setup details, and the browser catalogue is 539 scenarios (the 18 `PW-CAPABILITY-*` degradation cases are gone). Scenario and operation counts quoted in dated evidence below (557 scenarios, 60 operations, 62 inventory methods) describe the runs that produced them and are not restated.

**Author:** Claude (Cowork session), on behalf of Mark A Ray-Smith; updated by Codex after the Windows verification and evidence review
**Evidence review:** 5 September 2026
**Repository:** `peegeeq-cache` — module `peegee-cache-management-ui`, plus the Java Playwright suite in `peegee-cache-rest/src/test/java/dev/mars/peegeeq/cache/rest/server`
**Checkout:** `C:\Users\mraysmit\dev\idea-projects\peegeeq-cache` (branch `master`, base HEAD `3305c30` "feat(management-ui): complete U11 parity and harden browser verification", plus the uncommitted 5 September remediation and evidence-documentation working tree described here)
**Reference implementation:** `C:\Users\mraysmit\dev\idea-projects\peegeeq\peegeeq-management-ui`
**Governing documents:** `docs/design/PEEGEEQ_CACHE_MANAGEMENT_UI_IMPLEMENTATION_PLAN.md` (phase U11, §4.1 pinning rule, evidence blocks U11.0-U11.9, §9 status table, §10 completion definition); `docs/design/PEEGEEQ_CACHE_MANAGEMENT_UI_DESIGN.md` §3.1 (antd/Recharts mandate) and §8.1/§8.2 (state ownership, sensitive data); `docs/guidelines/PEEGEEQ_CACHE_TEST_COVERAGE_AND_TDD_APPROACH.md` §5 (management UI component and client tests)

---

## 0. Executive summary

**Screenshot appearance correction (5 September):** the user explicitly requires no capture-time
masking anywhere. The blanket masks, text-suppression style, and canary-based pixel filtering have
been removed; captures now reproduce the actual development UI. No replacement values or separate
redacted mode is introduced. Native password inputs and application reveal/hide behavior are unchanged.
The regression compares viewport/focused images with native Chromium pixels. Focused verification
passes 17 supporting tests and three screenshot infrastructure tests. Complete regeneration passed
all 11 modules in 32:34 at 19:30:28 +08:00: 574 Java tests, 170 UI tests, and 563 browser/infrastructure
tests, with zero failures/errors/skips. All 557 scenarios were recaptured without screenshot masks;
the current report, flat gallery, and guide use this fresh run. See the no-masking correction in §18
of the Playwright plan and `target/screenshot-unmasked-reactor-postgresql-18.3.log`. The earlier P7
results below describe the historical masked run, not the corrected images.

**Subsequent screenshot work (P7, 5 September):** the user requested screenshots for every scenario
following the sibling management and Utilities UI conventions. Paired viewport/focused captures,
scenario attachments in the self-contained HTML report, and missing/invalid-image acceptance gates
are implemented and verified. The full PostgreSQL 18.3 reactor passed in 32:27 at 16:01:23 +08:00:
568 Java unit tests, 170 UI tests, and 563 browser/infrastructure tests, all green. The portable report
contains all 557 scenarios and 1,122 embedded PNGs, with no missing pairs. See §18 of
[the Playwright implementation plan](PEEGEEQ_CACHE_PLAYWRIGHT_IMPLEMENTATION_PLAN.md).
The 560-test matrix below is the completed pre-P7 baseline. The three new screenshot infrastructure
checks bring a full run to 563 tests while keeping exactly 557 product scenarios.

**Screenshot location correction:** use [the documentation screenshot gallery](../../peegee-cache-management-ui/docs/screenshots/index.html),
not the internal scenario-ID diagnostic directories. All 1,122 verified PNGs are published flat under
`peegee-cache-management-ui/docs/screenshots/` with descriptive feature/behavior filenames, matching
the sibling UI documentation convention. The 19-test publication gate passes; the captures retain
their original full-run provenance. See the P7 documentation-layout correction in the linked plan.

Every slice of the U11 migration (U11.0 through U11.9) is implemented. The console now follows the reference project's patterns and dependency versions end to end: antd 5 and Recharts for every control and chart, RTK Query for every read and committed mutation, Zustand for live/session state, Zod at every contract boundary, and a test layer that renders real pages against a real loopback HTTP server with schema-produced fixtures and no page/client test doubles.

Current-working-tree UI-module evidence (Windows, exact managed Node 22.22.2 `npm run verify`, 5 September 2026):

| Gate | Result |
|---|---|
| Vitest | 36 files / 170 tests, all passing; includes real-loopback first-handshake failure and reconnect coverage for Pub/Sub and Monitoring |
| `test/quality` guards | 5 files / 17 assertions, all GREEN |
| Coverage | statements/lines 94.18% (5559/5902), branches 86.11% (1569/1822), functions 84.77% (590/696) |
| Threshold folders | `src/api/**`: 97.08% lines/statements, 94.04% functions, 87.01% branches; `src/state/**`: 98.60% lines/statements, 84.62% functions, 92.31% branches |
| `tsc --noEmit` | clean |
| `eslint . --max-warnings 0` | clean |
| focused reconnect regression | `test/monitoring-live.test.ts`: 4/4 passing |
| `vite build` | green on Windows after the reconnect correction (one non-blocking chunk-size advisory) |
| jsdom axe (temporary probes, every page state touched in U11.5-U11.7) | 0 violations |

The browser coverage contract contains 557 evidence scenarios. The pre-P7 Maven Failsafe execution reported 560 tests because `ManagementPlaywrightObservationIT` (1) and `ManagementRunnableArtifactIT` (2) are infrastructure verification tests outside that catalogue. The 5 September U11 baseline passed all 560 with zero failures, errors, or skips, generated a fresh canary-clean 557/557 evidence report, and passed complete 11-module reactors on PostgreSQL 15.17, 16.13, 17.11, and 18.3. Exact commands, timings, logs, and warning classifications are recorded in §7 and §9. U11 is complete; P7 adds three screenshot infrastructure tests, as recorded above.

The reference-parity implementation source is committed at `3305c30`. The 5 September test/readiness corrections and documentation updates are uncommitted working-tree changes layered on that base; generated verification artifacts remain ignored and are not part of the source change set. Transfer bundles are not retained. Acceptance evidence is identified by its 5 September timestamp and final-log name, not by older generated reports.

---

## 1. Decision record (unchanged, restated for completeness)

Mark, 3 September 2026: full parity with `peegeeq-management-ui` — "clone the excellent design patterns in peegeeq", including its resolved dependency versions. Hand-rolled controls and test fakes/doubles of any kind are prohibited in the project directives. The "document the deviation" option was explicitly rejected. Process instruction: no questions about approach ("everything you need is in that peegeeq project"), and no pausing between slices to report or ask ("ok just proceed with the task defined").

The mandate is enforced in code by the five `test/quality` guard files (§5), which run in the default Vitest gate and in Maven's `frontend-test` execution.

---

## 2. Architecture as delivered

### 2.1 Dependency pins (`package.json`, exact versions, matching the reference's resolved tree)

Production: `antd 5.26.5`, `@ant-design/icons 5.6.1`, `react 18.3.1`, `react-dom 18.3.1`, `@reduxjs/toolkit 2.10.1`, `react-redux 9.2.0`, `react-router-dom 7.18.2`, `recharts 3.1.2`, `zod 4.2.1`, `zustand 5.0.8`.
Dev: `vitest 3.2.7`, `@vitest/coverage-v8 3.2.7`, `@testing-library/react 16.3.0`, `@testing-library/user-event 14.6.1`, `@testing-library/jest-dom 6.6.3`, `jsdom 30.0.1`, `typescript 5.7.2`, `vite 6.4.3`, `eslint 10.9.0`, `eslint-plugin-react-hooks 7.1.1`, `eslint-plugin-react-refresh 0.5.4`, `@types/react 18.3.23`, `@types/react-dom 18.3.7`, `openapi-typescript 7.9.1`.
`axe-core 4.12.1` is pinned exactly as a development dependency so the Java product-journey and accessibility audits use a reproducible ruleset. The `engines` pin requires `npm 10.9.4` exactly. `npm ls --depth=0` is consistent.

### 2.2 Store (`src/store`)

- `managementApi.ts` — the single `createApi` (reducer path `managementApi`, tag types Setup/Overview/Monitoring/Activity/Namespace/Entry/Counter/Lock/Subscription plus `setupDerivedTags`, `entryTag`, `namespaceTag`, `counterTag`, `lockTag` helpers).
- `apiBase.ts` — base query delegating to `SessionClient.requestJson` (never `fetchBaseQuery`), `delegate()`/`clientsOf(api)` for `queryFn` endpoints, `ManagementQueryError` + `isManagementQueryError` + `toQueryError` mapping `ManagementClientError` to a serialisable error.
- `setupsApi.ts`, `inspectionApi.ts`, `entriesApi.ts`, `resourcesApi.ts`, `pubSubApi.ts` — `injectEndpoints` families; every endpoint is a `queryFn` delegating to the typed `src/api` client, so URL building, preconditions (`If-Match`/`If-None-Match`), CSRF, no-store handling, and the Zod parse remain in one place. Mutations invalidate the affected entity tag, the family list tag, and the setup-derived reads (Overview, monitoring, activity, namespace) so the console never shows optimistic state.
- `store.ts` (`createManagementStore(clients)` with `extraArgument`), `clients.ts` (`createManagementClients(sessionClient)` — `session`, `setup`, `inspection`, `entryAdministration`, `resource`, `pubSub`, `backendCapability`, `metricsStream`, `pubSubStream`, `origin`), `clients-context.ts`, `ManagementProvider.tsx`, `index.ts` (`useManagementClients()`).
- Reveal operations (entry value, lock owner, Pub/Sub payload) and the entire backend-capability client are **not** in RTK Query by design; `sensitive-dto.guard` proves no sensitive DTO or field name reaches `src/state` or `src/store`.

### 2.3 Zustand (`src/state`)

- `scope-store.ts` / `scope-storage.ts` — selected setup and selected namespace (allowlisted persistence). The capability snapshot was removed on 10 September 2026.
- `live-store.ts` — monitoring `connectionState`, notification drawer state and bounded envelope buffer, the per-setup Overview `trend` (`recordSnapshot`, the reference `updateChartData` pattern), and, new in U11.7, the Pub/Sub slice: `pubSubConnection`, `pubSubMessages`, `setPubSubConnection`, `receivePubSubMessage(message, bufferLimit)`, `clearPubSubMessages`, `stopPubSub`. Metadata only (`payloadState: 'MASKED'`); payloads never enter it.
- `preferences.ts` — the allowlisted display preferences and `PREFERENCES_CHANGED_EVENT`.

### 2.4 Shell and common components

- `src/app/App.tsx` — session gate (antd `Card`/`Form`/`Input.Password`/`Spin`/`Alert`), store per session client, `resetApiState` on teardown; `ManagementShell` no longer receives a `sessionClient` prop (deleted in U11.7).
- `src/app/ManagementShell.tsx` — antd `ConfigProvider` (theme algorithm + **`virtual={false}`**, see §6.1), `Layout`/`Sider` (`aria-label="Primary navigation"`), `Menu` of router `Link`s inside `<nav aria-label="Management sections">`, header status group, notifications `Drawer` with the inner `<aside aria-label="Notifications">`, routes rendered directly (capability gating removed 10 September 2026); pages receive only role flags, setup-details limits, and scope, never clients.
- `src/components/common/` — `ConnectionStatus`, `StatCard` (`.metric-card`, antd `Statistic`), `SetupScopeBar` (`useListSetupsQuery` → Zustand scope, committed immediately), `ValueSelect` (§6.1). `ConfirmDialog.tsx` and `FilterBar.tsx` were unused and are deleted (§4.5).

### 2.5 Pages (`src/features`)

| Page | Reads | Mutations | Sensitive path |
|---|---|---|---|
| `overview/OverviewPage`, `OverviewMonitoring`, `SessionTrendChart` | `useGetOverviewQuery` etc.; Recharts trend from the live store | — | — |
| `monitoring/MonitoringPage` | database/runtime/activity queries; metrics SSE via `clients.metricsStream` upserting `inspectionApi` cache | — | — |
| `setups/SetupsPage` | list, lazy details/health | register, testConnection, testRegistered, connect, detach, forget | password never leaves component state |
| `namespaces/NamespacesPage`, `NamespaceDetailsPage` | namespaces, namespace details, export | — | — |
| `entries/EntriesPage` | `useGetEntriesQuery` keyed by setup/namespace/filters/cursor | setEntry (create), preview/execute bulk delete | — |
| `entries/EntryDetailsPage` | `useGetEntryQuery` (`includeExpired`) | setEntry, expire, persist, touch, delete | reveal on `clients.inspection.revealEntryValue`, component memory, auto-hide, `visibilitychange`, route/setup change |
| `counters/CountersPage` | `useGetCountersQuery` | set, adjust, expire, persist, delete, preview/execute bulk delete | — |
| `locks/LocksPage` | `useGetLocksQuery`, `useLazyGetLockQuery` (cache bypassed) | forceRelease | owner reveal on `clients.resource.revealLockOwner` |
| `advanced/AdvancedOperationsPage` | none through RTK Query | none through RTK Query | everything on `clients.backendCapability` (no-store); results cleared on `visibilitychange`, unmount, and "Clear sensitive state" |
| `pubsub/PubSubPage` | live SSE via `clients.pubSubStream` → live store | createSubscription, publish, deleteSubscription | payload reveal on `clients.pubSub.revealPayload` |
| `settings/SettingsPage` | session + setup-details limits props | none (localStorage allowlist only) | — |

---

## 3. Test layer as delivered

### 3.1 Shared fixtures (`test/support`)

- `loopback-server.ts` — `startLoopbackServer(handler)` starts a real `node:http` server on `127.0.0.1:0`. `LoopbackRequest` exposes `method`, `url` (raw, with query), `path`, `query` (`URLSearchParams`), `headers`, `body` (JSON-parsed when possible), `rawBody`, `onClose(listener)`. `LoopbackRespond` exposes `json(status, body, headers?)` (adds a session `set-cookie`), `noContent(headers?)`, `problem(status, code, detail, { title?, correlationId?, fieldErrors? })` (RFC 9457 body produced by `managementProblemSchema.parse`), `raw(status, headers, body)`, `sse(frames, { keepOpen? })`. `server.requests` records everything; `server.use(handler)` swaps the handler mid-test; `invalidBody(body)` is the identity function that declares a negative fixture; `route(method, path | RegExp, request)`. **`close()` destroys open responses, calls `server.close()`, then `server.closeAllConnections()`** — without the last call, undici's keep-alive pool and a reconnecting SSE client hold the listener open past the test (this was the cause of an `afterEach` hook timeout while converting the Pub/Sub page).
- `render.tsx` — `renderWithProviders(ui, { store, initialEntries? })` installs `MemoryRouter` → `ConfigProvider` (`theme.token.motion: false`, `virtual={false}`) → `ManagementProvider` as the Testing Library `wrapper`, so `rerender(...)` keeps them. `chooseOption(user, combobox, label)` opens an antd Select and clicks the option by visible label.
- `entry-fixture.ts` — `startEntryFixture(initial = entryMetadata)` → `{ server, store, sessionClient, state, requests(predicate), close }`. Serves session, empty setup list, the entry list (cursor `entry-cursor-2` → key `next-page`), entry GET, reveal POST (no-store headers), PUT set entry (version+1, or `state.setEntryProblem`), POST ttl/persist/touch, DELETE, bulk-delete preview/execute. Fixtures: `entryMetadata` (namespace `客户/订单`, key `café/東京/🔒?x=1`, version `9007199254740993` — encodings derived from the production codec) and `ordersMetadata` (`orders`/`order:1`, version 3).
- `resource-fixture.ts` — `startResourceFixture()` with mutable `state.counters`/`state.locks`, so committed mutations are visible to the next read exactly like PostgreSQL truth: counters list with namespace/prefix filters, counter GET/PUT/increment/ttl/persist/DELETE with `If-Match`/`If-None-Match` checks and signed 64-bit overflow → `COUNTER_OVERFLOW`, bulk preview/execute, locks list/GET, owner reveal (no-store), force-release with version check, and every backend-capability route (exists, batch-get/set/delete, scan, cache-metrics, acquire/renew/release/ownership) with no-store headers.

### 3.2 Current source inventory (36 files / 168 tests after 10 September 2026; 170 at handover)

Page and shell tests (all through `renderWithProviders` over a loopback server):

- `app.test.tsx` (4): navigation/identity/role/connection/logout button; theme + notifications without exposing the CSRF token, preference persisted; every section reachable once a setup is selected, with the shell reading that setup's details (and so its limits) exactly once; **the real `App` under `/ui`**: 401 → bootstrap gate, rejected token → `BOOTSTRAP_TOKEN_INVALID` with the field cleared, accepted token exchanged on the wire, shell rendered without the token or CSRF proof in the DOM, `End local session` → `DELETE /api/v1/session/local` with `X-PeeGeeQ-CSRF`.
- `overview-page.test.tsx` (3), `monitoring-page.test.tsx` (1), `setups-page.test.tsx` (6), `namespaces-page.test.tsx` (4) — U11.3/U11.4, unchanged except that `overview-page` now awaits each independently loaded section (`findBy*`).
- `entries-page.test.tsx` (2): setup and namespace scope required before any `/entries` request; metadata without values, 64-bit version formatted, filters on the wire (`prefix`, `valueType`, `ttlState`), cursor stack `[null, null, 'entry-cursor-2']` (going back is served from the RTK Query cache and never fabricates a cursor).
- `entry-details-page.test.tsx` (3): viewer sees `Value hidden` and no reveal; reveal POSTs to the exact encoded route with `{ reason }` and the CSRF header, the value is absent from the URL, Web Storage, and Redux state, explicit copy and hide; auto-hide after the server window (25 ms), hide on `visibilitychange`, hide on route/setup change (3 reveals counted).
- `entry-administration-pages.test.tsx` (9): viewer surfaces with no non-GET request; single-entry administration with bulk delete disabled; create with `If-None-Match: *` and a JSON contract value; CAS default with `If-Match: "v3"`, committed version re-read into `Descriptions`; upsert without precondition and `REPLACE` TTL only when chosen; `VERSION_MISMATCH` preserving input and refetching metadata; TTL/touch/persist/delete with `If-Match` and the exact bodies; bulk preview of exact-version targets and execution only after the typed phrase; matching-filter preview sending the applied server filter, cancelled without execution.
- `counter-lock-pages.test.tsx` (9): viewer read-only and owner masked; bulk-delete and forced-release degraded independently; live trimmed filters clearing the selection; `COUNTER_OVERFLOW` alert then a committed `-2` adjustment (`If-Match: "v3"`) re-read into the table and delete with the observed version; create by exact value (`If-None-Match: *`, minimum signed 64-bit) appearing in the list; create by signed adjustment (`createIfMissing`, `REPLACE` TTL); bulk preview/execute with `DELETE 1 COUNTERS`; lock dialog re-reading before display and before release (`If-Match: "v5"` after the fixture advanced the version), owner reveal hidden on explicit hide and `visibilitychange`, focus on `Confirm lock key`; stale release → `VERSION_MISMATCH` without releasing.
- `advanced-operations-page.test.tsx` (4): existence/scan/batch-get/batch-set/batch-delete/metrics with the typed contract bodies, results kept out of Redux; malformed batch input rejected locally before any request; operator and reveal panels withheld from viewers while metrics stay available; owner-token lock lifecycle (`acquire`, `renew`, `ownership`, `release`) with the token absent from Redux and cleared by "Clear sensitive state".
- `pubsub-page.test.tsx` (4): viewer subscription with the message arriving through the real SSE stream into the live store (no publish/reveal); non-durable labelling, masked metadata, reveal held only in component memory, hide on explicit hide/`visibilitychange`/stop, stop deleting the subscription and closing the stream (`closed === opened === 1`); publish with CSRF header, described as acceptance not delivery, payload cleared; UTF-8 byte limits for channel, buffer entries, and publish channel enforced before transport.
- `settings-page.test.tsx` (2): every preference driven through antd Selects, the exact allowlisted record in `localStorage`, five change events, no request; rendering without a setup or effective limits.

Client and contract tests (loopback, schema-produced bodies, `server.use` per case): `session-client` (8, incl. 401 → CSRF dropped, unreadable body, non-problem failure, cacheable sensitive response), `setup-client` (4), `inspection-client` (15), `entry-administration-client` (9, incl. precondition validation and `VERSION_MISMATCH`), `resource-client` (4), `backend-capability-client` (4), `pubsub-live` (4, incl. SSE lifecycle and `Accept` header), `pubsub-live-http` (1, resume with `Last-Event-ID`), `monitoring-live` (4, including immediate offline/online reconnect), `store` (6), `protocol-schemas` (8), `operation-manifest` (3), `identifier-codec` (12), `preferences` (3), `scope-store` (4), `scope-storage` (4), `display-bytes` (2), `display-time` (2), `entry-formatters` (3).

Guards: `component-library.guard` (5), `no-test-fakes.guard` (3), `no-direct-transport.guard` (3), `sensitive-dto.guard` (4), `zod-fixture.guard` (2) — §5.

### 3.3 Testing conventions learned in this session (keep following them)

- antd `Modal` in jsdom: after `await screen.findByRole('dialog', { name })`, assert content with `await waitFor(() => expect(within(dialog).getByText(...)).toBeVisible())` — the first frame is rc-motion's `appear-prepare`.
- antd `Checkbox` inputs have `opacity: 0`: assert `toBeInTheDocument()`/`toBeChecked()`, never `toBeVisible()`.
- antd `Select` options: `chooseOption(user, screen.getByLabelText('Set mode'), 'Always / upsert')`; assert the selection with `screen.getByLabelText(...).closest('.ant-select')` `toHaveTextContent(label)`.
- Real `App` tests must `window.history.pushState({}, '', '/ui/')` first (`BrowserRouter basename="/ui"`).
- SSE fixtures: keep per-fixture counter objects (a response destroyed by the previous `close()` emits its `close` event asynchronously and would otherwise be counted against the next test).
- Bodies for the zod guard: `let body: unknown = xSchema.parse(...)` reassigned only with `*Schema.parse(...)` or `invalidBody(...)`, or `server.use((request, respond) => respond.json(200, xSchema.parse({...}), HEADERS))` per case.
- The global component-test ceiling remains 15 seconds. Only the two complete multi-operation workflows (`advanced-operations-page` backend workflow and `setups-page` TLS registration workflow) use a bounded 30-second ceiling because focused execution normally takes 7–9 seconds and full four-worker coverage adds measurable instrumentation contention; no assertion, real-loopback request, or cleanup check was removed.

---

## 4. What this session did, slice by slice

### 4.1 U11.5 — Entries (GREEN)

Source: `src/features/entries/entry-filters.ts` (new: option lists `ENTRY_VALUE_TYPE_FILTER_OPTIONS`, `ENTRY_TTL_STATE_OPTIONS`, `ENTRY_VALUE_TYPE_OPTIONS`, `ENTRY_SET_MODE_OPTIONS`, `ENTRY_TTL_MODE_OPTIONS`; `EntryInputError`, `cacheValueFor`, `positiveInteger`, `formatTtl`), `EntriesPage.tsx` (antd `Card`/`Form`/`Input`/`Checkbox`/`Table` with `rowKey="encodedKey"`/`Tag`/`Modal`/`Alert`/`Empty`; ids `entry-prefix`, `entry-value-type`, `entry-ttl-state`, `new-entry-key`, `new-entry-type`, `new-entry-ttl`, `new-entry-value`, `bulk-entry-confirmation`; dialogs "Create entry" and "Confirm bulk entry deletion"), `EntryDetailsPage.tsx` (`Descriptions aria-label="Entry metadata"`, edit form ids `entry-edit-type`, `entry-set-mode`, `entry-edit-value`, `entry-ttl-mode`, `entry-edit-ttl`, TTL form `entry-ttl-millis`/`entry-refresh-ttl`, delete dialog "Delete {key}?" with `delete-entry-confirmation`, value panel with `reveal-reason`), `EntryValueFormatter.tsx` (antd `Button` with `aria-pressed`, `Tag` for JSON validity), `src/components/common/ValueSelect.tsx` (new), `ManagementShell.tsx` (no `client=` for entry routes).
Bug found by the loopback tests that the fakes had hidden: the old table overwrote the entry `key` with `encodedKey` for React keys, so the visible link text was the encoding; fixed with `rowKey`. The old fixtures also carried a wrong hand-typed `encodedNamespace` for `客户/订单`; the fixture now derives encodings from the codec.
Tests: 15 (see §3.2). Java: §7.

### 4.2 U11.6 — Counters, locks, advanced (GREEN)

`CountersPage.tsx`: `InputNumber<string>` with `stringMode` and `controls={false}` for "Exact decimal value" and "Signed adjustment" so the signed 64-bit decimal string reaches `counterSetBodySchema`/`counterAdjustBodySchema` without becoming a JavaScript number; live filters (`namespace`, `prefix`, trimmed) that reset the cursor and selection; `Previous page`/`Next page` (`nav aria-label="Counter pages"`); editor dialog "Create counter"/"Manage {key}" (ids `counter-create-namespace`, `counter-create-key`, `counter-exact-value`, `counter-create-ttl`, `counter-adjustment`, `counter-ttl`, `counter-delete-confirm`); bulk dialog "Confirm counter deletion" with the phrase in the only `<strong>`; status lines `Counter created/set/adjusted/TTL set/persisted: {value} · version {n}`, `Counter {key} deleted at observed version {n}.`, `Deleted X of Y counters.`.
`LocksPage.tsx`: `useLazyGetLockQuery` with `trigger(ref, false)` before opening "Manage {key}" and again before "Release current lock version?" (which focuses `Confirm lock key` through `afterOpenChange`); owner reveal through `clients.resource.revealLockOwner`; `destroyOnHidden` so a closed dialog leaves the DOM (`hasCount(0)` in the Java suite).
`AdvancedOperationsPage.tsx`: antd `Card` panels with the exact headings the capability scenarios assert (`Existence and scan`, `Batch get`, `Batch set`, `Cross-namespace batch delete`, `Exact core metrics`, `Owner lock lifecycle`), `Input.Password` for the owner token with no visibility toggle, `Descriptions` for metrics (`Cache Sets` etc.), `<section aria-label="{title}">` result regions with `<pre>` JSON, `<p><strong>Exists:</strong> Yes</p>` and `<p><strong>Lock result:</strong> …</p>` shapes kept for the journey's `getByText(...).locator("..")` assertions.
`resourcesApi.ts` exports `useLazyGetLockQuery`; `ManagementShell.tsx` passes no clients to these three routes.
Tests: 13. No Java locator change was necessary.

### 4.3 U11.7 — Pub/Sub and settings (GREEN)

`PubSubPage.tsx`: antd `Card`/`Form`/`Input`/`Input.TextArea`/`Table`/`Tag`/`Alert`; mutations through `pubSubApi`; the SSE transport connected through `clients.pubSubStream.connect(`${clients.origin}${subscription.streamPath}`, …)` writing to the live store; region names `Subscription: {channel}` and `Retained Pub/Sub messages`; the connection line is plain text `Non-durable · {state} · bounded to {n} messages` because `ManagementConsoleProductJourneysIT` asserts it exactly (CONNECTED and STALE); `<Tag>Masked</Tag>` in the payload column; `Reveal payload {id}` buttons; revealed payload as `<pre className="value-content">`.
`SettingsPage.tsx` + `settings-options.ts`: `Descriptions` cards (Connection / Transport state / Effective limits) and a `Form` of `ValueSelect`s with ids `setting-theme`, `setting-timezone`, `setting-bytes`, `setting-refresh`, `setting-auto-hide`; numeric preferences carried as their string contract values (`'15' | '30' | '60'`, `'30' | '60' | '120'`) and parsed back through the allowlisting `savePreferences`.
`ManagementShell.tsx`: last `client=` prop removed, `useManagementClients` import dropped, `sessionClient` prop deleted (and from `App.tsx`).
Tests: 6. Java: §7.

### 4.4 U11.8 — Test-layer conversion (GREEN)

- `test/app.test.tsx` rewritten on the loopback (5 tests, §3.2).
- `test/monitoring-live.test.ts`, `test/pubsub-live.test.ts`: `vi.stubGlobal('fetch')` replaced by a loopback stream the server ends after the handshake (`respond.sse([])`), observing `CONNECTING → CONNECTED → STALE` and the `Accept: text/event-stream` header. The old "opens credentialed SSE in CORS mode" assertion inspected the `fetch` init object through a stub; that property is not observable over HTTP from Node and is covered by the browser suite, which sees the real `Origin`/cookie on the stream request.
- `test/preferences.test.ts`: `vi.fn` replaced by an observed event list; a defaults test added.
- `test/backend-capability-client.test.ts`, `entry-administration-client.test.ts`, `inspection-client.test.ts`, `session-client.test.ts`, `setup-client.test.ts`: private `createServer` fixtures with hand-written JSON replaced by the loopback with `*Schema.parse` bodies and `invalidBody(...)` negatives; every previous assertion kept (request paths and query strings are asserted on `request.url`/`request.path`), and new cases added for the paths that were unreachable before (CAS without observed version, invalid body before transport, `VERSION_MISMATCH` with correlation id, 401 dropping the CSRF proof, `RESPONSE_BODY_INVALID`, `HTTP_REQUEST_FAILED`, `SESSION_STATE_MISSING`, `SENSITIVE_RESPONSE_CACHEABLE`, health/capabilities paths).
- `src/api`: all `*ClientPort`, `*StreamPort`, and `MonitoringSocketPort` interfaces and their `implements` clauses deleted (`backend-capability-client`, `entry-administration-client`, `inspection-client` ×5 + the `EntryDetailsClientPort` alias, `pubsub-client`, `resource-client` ×2, `setup-client`, `live-transport` ×2, `monitoring-live`).
- Coverage after conversion: `src/api` 83.58% branches (was 78.6%), `src/state` 92.31%.

### 4.5 U11.9 — Cleanup (GREEN)

- Deleted: `src/components/Modal.tsx`, `test/modal.test.tsx`, `src/components/common/ConfirmDialog.tsx`, `src/components/common/FilterBar.tsx` (the last two were introduced in U11.4 but never consumed: `SetupsPage` keeps a controlled antd `Modal` so each confirmation dialog carries its exact accessible name — `Modal.confirm` cannot guarantee that — and the namespace filter is an inline antd `Form` in a `Card`).
- `src/styles/foundation.css` 838 → 455 lines. Removed: native `button`/`input`/`select` resets and focus rings, `.button*`, `.field*`, `.modal`/`.modal-backdrop`/`.modal--compact`/`.modal__heading`, `.data-table*`, `.tabs*`, `.badge*`, `.empty-state`, `.callout`/`.notice`, `.muted`, `.metric-grid`, `.overview-panels`, `.panel-problem`, the hand-drawn `.trend-chart*`/`.trend-legend*`, `.console__sidebar`/`.nav-link`, and the `dl`-era `.details-list*`/`.compact-details*` rules (the `Descriptions` components no longer carry those class hooks). Kept: `:root` theme, resets, `.session-gate`/`.session-card`, `.console*` (incl. `[data-theme="dark"]`), `.workspace*`, `.scope*`, `.table-scroll`, `.metric-card`/`.overview-panel`, `.observed-at`/`.trend-latest`/`.live-status`, `.filter-bar*`, `.pagination`, `.modal__actions`, `.form-grid`, `.details-section`, `.value-panel`/`.masked-value`/`.reveal-form`/`.value-formatter__controls`/`.value-content`, `.json-tree`, `.validation`/`.value-size`, `.capability-list`, `.sr-only`, and the 760 px responsive block.
- `npm ls --depth=0` consistent; the `every declared production dependency is imported by src/` guard green.

### 4.6 Guard and fixture corrections made along the way

- `zod-fixture.guard`: accepts `./support/*-fixture` as the loopback import; ignores the transport's own `outgoing.end(JSON.stringify(payload))` in `loopback-server.ts` (every `respond.json` argument is still checked at the call site); binds identifiers only from single-line declarations (a multi-line type annotation previously let `let server: LoopbackServer;` swallow the next declaration); checks only the first argument of `respond.json(status, body, headers)` via a top-level-comma splitter.
- `test/overview-page.test.tsx`: awaits each independently loaded section to close a load-dependent readiness race seen once in a full run.
- Loopback `close()` → `closeAllConnections()` (§3.1).
- `renderWithProviders` as a `wrapper` + `virtual={false}` (§3.1).

---

## 5. The guard gates (all GREEN)

| File | Assertions |
|---|---|
| `component-library.guard` | every component under `src/features`, `src/app`, `src/components/common` imports antd; no raw `<table>/<form>/<input>/<select>/<textarea>/<button>/<svg>` in component files; `src/components/Modal.tsx` no longer exists; every declared production dependency is imported by `src/`; the mandated stack is declared |
| `no-test-fakes.guard` | no vitest mocking/stubbing/spying/fake-timer API in `test/`; no hand-built fakes of ports/clients/transports; no `*ClientPort` seam in `src/` and no page receives a client as a prop |
| `no-direct-transport.guard` | no `fetch`/`EventSource`/`WebSocket`/XHR outside `src/api`; no axios; the RTK Query base query delegates to the session-aware client |
| `sensitive-dto.guard` | `src/state`/`src/store` never reference a sensitive DTO type, schema, or secret field; no RTK Query slice exposes a reveal endpoint; files that persist, build URLs, or log never touch a sensitive DTO; `src/api` clients never persist one |
| `zod-fixture.guard` | every JSON body served by a test comes from a `*Schema.parse` call (or `invalidBody`); every component test is served by the loopback fixture |

Run them alone with `npx vitest run test/quality`.

---

## 6. Design decisions recorded in the plan (with rationale)

### 6.1 `virtual={false}` and `ValueSelect` (U11.5)

With virtual scrolling (antd's default) rc-select renders the visible options without any ARIA role and exposes `role="option"` only on a hidden three-item accessibility list around the active option. Assistive technology and role-based locators therefore could not see `Replace TTL` (index 2) or `Include expired` (index 3). Every console select is a short, fixed list, so the shell's `ConfigProvider` sets `virtual={false}` — the antd configuration in which every rendered option carries `role="option"` — and the test wrapper mirrors it. `ValueSelect` wraps antd `Select` and stamps the server value on a `data-value` attribute of every option (`optionRender`) and of the rendered selection (`labelRender`), so browser scenarios keep addressing options by the reviewed server value exactly as they did with native `<select>` elements. Used by: entry value type/TTL state/create type/set mode/TTL mode, namespace status/sort, setup schema bootstrap, and all five settings preferences.

### 6.2 Reveals and the advanced page stay off RTK Query

RTK Query caches every endpoint result in Redux; a reveal there is a leak by construction. The three reveals and the whole backend-capability surface therefore go through `useManagementClients()` to the no-store clients, live in component state, and are cleared on auto-hide, `visibilitychange`, route/setup change, stop, and unmount. `sensitive-dto.guard` enforces it.

### 6.3 Fresh reads before destructive lock actions

`LocksPage` re-reads the lock with `useLazyGetLockQuery` (cache bypassed) before showing the manage dialog and again before showing the release dialog, so the version displayed and the `If-Match` sent are the ones PostgreSQL holds at that moment; a stale release surfaces `VERSION_MISMATCH` and releases nothing.

### 6.4 Controlled `Modal` rather than `Modal.confirm`

Every confirmation dialog must carry its exact accessible name (`Register setup`, `Detach {name}?`, `Delete {key}?`, `Confirm counter deletion`, `Release current lock version?`, …) because the Java suite addresses dialogs with `getByRole(DIALOG, name)`. A controlled antd `Modal` with the heading as its only title content guarantees that; `Modal.confirm` does not, so `ConfirmDialog.tsx` was removed rather than adopted.

### 6.5 Exact-text contracts preserved on purpose

Several product-journey assertions match text exactly; the antd rewrite keeps those DOM shapes: `Non-durable · CONNECTED · bounded to 20 messages`, `Exists: Yes`, `Lock result:`, `Cache Sets`, `{n} / {max} UTF-8 bytes`, `{n} raw · {m} / {max} wire bytes`, `No retained message metadata.`, `No counters matched.`, `Masked`, column header sequences (`Selection, Namespace, Key, Value, Version, Updated, TTL, Actions`; `Namespace, Key, Fencing token, Version, Lease expires, Remaining, Owner, Actions`), and `td` index positions.

---

## 7. Windows Playwright verification and hardening

### 7.1 Catalogue accounting

`ManagementBrowserCoverageTest` and the REST POM define 539 reportable evidence scenarios since 10 September 2026 (557 at handover; the 18 `PW-CAPABILITY-*` cases were removed with capability gating, see §14). With P7, a complete Failsafe run is expected to contain 545 JUnit tests: the 539 scenarios plus one Playwright observation test, two runnable-artifact tests, and three screenshot infrastructure tests. The pre-P7 U11 matrix below contains 560 tests. Keep catalogue and execution totals separate; they describe different layers and are not contradictory. Record those numbers as current evidence only when report timestamps and the Maven summary correspond to the handed-over source state.

### 7.2 Browser-contract adaptations

- **`AntSelect.java`** clicks the visible antd selector container (the native search input is read-only), selects options through their reviewed `data-value`, exposes option values/labels in display order, and reads the selected value from `.ant-select-selection-item [data-value]`.
- Overview and monitoring locators use antd `Statistic` and `Descriptions`; setup details use `Descriptions`; entry metadata uses its accessible region; namespace, entry, monitoring/settings, and shell scenarios select values through `AntSelect`.
- Boundary pagination addresses the exact `Next page` button. Product journeys wait for loading indicators to clear before accessibility scans, verify modal focus containment/restoration, and wait for the modal root's rc-motion animation before running axe.
- The accessibility gate uses exactly `axe-core 4.12.1`; the light/dark design tokens, default/primary buttons, drawer mask, and portal-rendered surfaces have explicit contrast-safe behavior. Axe failures now include URL, target HTML, and measured failure summaries rather than only selectors.

### 7.3 Deterministic lifecycle and readiness corrections

- `FetchSseTransport` detaches the active `AbortController` before aborting it on `offline`. This prevents the aborted stream's `finally` block from suppressing an `online` reconnect that starts synchronously. The same ordering is applied to validated SSE streams.
- `test/monitoring-live.test.ts` reproduces the immediate offline/online ordering against a real loopback SSE endpoint; no transport stub or fake timer is used.
- Setup connection and post-registration scope assertions allow 15 seconds because the production connection attempt has an explicit 10-second backend timeout. Live reconnect assertions use the same bounded browser window.
- `ManagementConsolePostgresFixture` waits for the password modal to detach before checking the secret canary, uses exact visible setup-scope text rather than substring title matches, and lets each scenario declare expected or explicitly allowed failed responses for diagnostics. `ManagementConsoleTrustedProxyIT` also uses the exact `Setups` heading so the empty-state heading cannot satisfy navigation readiness.
- Monitoring refresh verification is cache-aware: it requires the scheduled overview request without assuming a cached initial read must issue another HTTP request.
- Counter and lock header verification waits for exactly eight rendered header cells before comparing their text sequence. This closes the observed empty-list race without delaying successful renders.
- Entry reveal cleanup waits for the observable `Hide value` state before dispatching `visibilitychange`; entry TTL browser text accepts one to three fractional digits because the live countdown may render immediately after a whole-second boundary.
- The two complete multi-operation component workflows use a 30-second per-test ceiling while all other component tests retain the global 15-second ceiling (§3.3).

### 7.4 Verification evidence

| Gate | Result |
|---|---|
| UI `npm run quality` | PASS (`tsc --noEmit`; ESLint with zero warnings) |
| focused monitoring transport test | PASS, 4/4 against the loopback server |
| production UI build | PASS; rebuilt UI JAR installed and REST runnable artifact repackaged |
| affected Playwright set | trusted-proxy + entry inspection 56/56, entry administration 50/50, counters 44/44, setup focused regression 1/1; all repeated inside the complete gates |
| complete Playwright/Failsafe run | PASS: 27 XML reports, 560 tests, zero failures/errors/skips; fresh HTML evidence 557/557 passed and canary-clean |
| PostgreSQL 15.17 complete reactor | PASS, all 11 modules, 22:23; UI 170/170 and Failsafe 560/560 |
| PostgreSQL 16.13 complete reactor | PASS, all 11 modules, 20:21; UI 170/170 and Failsafe 560/560 |
| PostgreSQL 17.11 complete reactor | PASS, all 11 modules, 20:38; UI 170/170 and Failsafe 560/560 |
| PostgreSQL 18.3 complete reactor | PASS, all 11 modules, 21:21; UI 170/170 and Failsafe 560/560 |
| final log/leakage/banned-pattern review | PASS: no error/failure signatures, dumps, crash artifacts, sensitive canaries, Mockito/substitute framework, UI test doubles, port seams, or prohibited direct transports |

---

## 8. Merge resolution (completed 4 September 2026)

Merge commit `78fdabf6f7db70a987180e38c63d15c18b1b5330` completed the merge of `1ff6abe4c7ca0afb65e9b8ae00eaa06910484484` ("docs(management): record verified capability remediation gate") into `b6128e5`. The eight formerly unmerged files and seventeen conflict hunks were resolved on 4 September 2026; the index has no unmerged entries and no conflict markers remain:

| File | Hunks | What the two sides disagree on |
| --- | --- | --- |
| `peegee-cache-rest/src/test/java/dev/mars/peegeeq/cache/rest/server/ManagementConsolePostgresFixture.java` | 3 (lines 37, 153, 244) | (1) `import java.util.UUID;` (HEAD) vs `import java.util.function.UnaryOperator;` (theirs); (2) the `run(...)` overload delegating to `runIsolated(…, advertisedCapabilities, Map.of(), false, journey)` (HEAD) vs `run(…, capabilitySourceFilter, Map.of(), journey)` (theirs); (3) HEAD's `SharedEnvironment implements AutoCloseable` class vs theirs' inline server start with `capabilitySourceFilter`. **This is the only conflict that blocks compilation.** |
| `docs/design/PEEGEEQ_CACHE_MANAGEMENT_UI_IMPLEMENTATION_PLAN.md` | 4 (lines 299, 583, 598, 781) | The U10 evidence narrative: 17 journeys / 60 operations / 550 scenarios, 801 Surefire, 553 Failsafe on PostgreSQL 18.3 (HEAD) vs 59 operations / 557 scenarios (theirs) — in the coverage-test paragraph, the 3 September reactor evidence, the catalogue-count paragraph, and the §9 U10 row. The U11 evidence blocks and status lines written by this session are outside all four hunks. |
| `docs/design/PEEGEEQ_CACHE_IMPLEMENTATION_PLAN.md` | 3 (lines 587, 750, 782) | The same 550-vs-557 / 60-vs-59 evidence figures in the management status line, the current-evidence paragraph, and the Phase 8.3 closure paragraph. |
| `docs/design/PEEGEEQ_CACHE_MANAGEMENT_API_IMPLEMENTATION_PLAN.md` | 2 (lines 942, 1002) | The observability status line and the "Real-browser and runnable-artifact Failsafe gate" row (550/550 + 3/3 in HEAD). |
| `docs/design/PEEGEEQ_CACHE_PLAYWRIGHT_IMPLEMENTATION_PLAN.md` | 2 (lines 40, 408) | The 559 → 550 desktop-only catalogue history vs the 557 count. |
| `docs/design/PEEGEEQ_CACHE_FUNCTIONALITY_COVERAGE_MATRIX.md` | 1 (line 62) | "The 60 management operations …" (HEAD) vs 59. |
| `docs/design/PEEGEEQ_CACHE_MANAGEMENT_API.md` | 1 (line 1657) | The `M0-M11 BACKEND AND U0-U11 PRODUCTION UI COMPLETE` status paragraph. |
| `docs/design/PEEGEEQ_CACHE_MANAGEMENT_UI_DESIGN.md` | 1 (line 26) | The production-boundary paragraph (desktop-only console statement). |

The resolution uses the executable sources as authority: the OpenAPI document contains 60 operation IDs, while `ManagementBrowserCoverageTest` and the REST POM declare 557 scenarios. The fixture preserves the suite-shared environment and threads `UnaryOperator<SetupCapabilities.Source>` through isolated capability-test server startup. Validation is green for `mvn -o -pl :peegee-cache-rest -am -DskipTests test-compile` and `mvn -o -pl :peegee-cache-rest -Dtest=ManagementBrowserCoverageTest test` (2 tests, zero failures/errors/skips).

---

## 9. Verification commands and completed gates

Executed on Windows with the module-managed Node 22.22.2 runtime:

1. From `peegee-cache-management-ui`, prepend the pinned executable directory before invoking npm: `$uiNodeDir = (Resolve-Path '.\node').Path; $env:Path = "$uiNodeDir;$env:Path"`.
2. Complete UI gate: `& '.\node\node.exe' '.\node\node_modules\npm\bin\npm-cli.js' run verify` — GREEN, 36 files / 170 tests with the coverage and build evidence above.
3. Standalone UI Maven gate: `mvn --batch-mode --no-transfer-progress -pl :peegee-cache-management-ui verify` — GREEN, 36/36 files and 170/170 tests with exact managed Node/npm, thresholds, production artifact, and zero dependency vulnerabilities.
4. Complete browser gate: `mvn -pl :peegee-cache-rest failsafe:integration-test failsafe:verify` — GREEN, 560/560; reports under `peegee-cache-rest/target/failsafe-reports`, fresh HTML evidence under `peegee-cache-rest/target/playwright-evidence.html`.
5. Complete matrix command for each version: `mvn --batch-mode --no-transfer-progress verify -Dpeegeeq.test.postgres.image=postgres:<version>-alpine` — GREEN for `15.17`, `16.13`, `17.11`, and `18.3` with timings in §7.4.
6. Final evidence review scans the four acceptance logs, all 27 current Failsafe XML reports, generated HTML evidence, dump/crash artifacts, and prohibited Java/TypeScript patterns. All checks are GREEN; the implementation plan's U11 row is `COMPLETE`.

---

## 10. Workflow notes for the next session

- Use the UI module's managed `node\node.exe` and npm CLI, and prepend the module's `node` directory to `PATH` before launching npm. The npm CLI sets `NODE`, but Windows command shims invoke bare `node`; without the `PATH` correction they resolved workstation Node 24.11.1 instead of the pinned Node 22.22.2. The `preverify` script now fails fast on that mismatch.
- If `node_modules` is incomplete, restore it once with the managed runtime's `npm-cli.js ci`, wait for that process to finish, and then run gates. Do not start overlapping installs.
- The checkout mixes CRLF and LF. Do not normalize files incidentally; use `git diff --ignore-cr-at-eol` when reviewing semantic changes.
- The clean focused browser log is retained at `peegee-cache-rest/target/playwright-full-final.log`. Final matrix logs are under `target/u11-verification/reactor-postgresql-{15.17,16.13,17.11,18.3}.log`; separately named `.failed-*` logs record exploratory failures and are not acceptance evidence. These generated files are not committed source.
- A module-only REST run can resolve stale locally installed snapshot dependencies. Prefer a full reactor, or install the current upstream modules first, before treating a module-only result as authoritative.

---

## 11. Commit record

Commit `3305c30069152d64a0bc1adb5c560e368e3b8a5c` (`feat(management-ui): complete U11 parity and harden browser verification`) is the base U11 parity implementation reviewed by this work.

The uncommitted 5 September working tree adds the deterministic readiness/test-budget corrections listed in §7.3 and synchronizes the evidence documents. It completes the owning UI, browser, full-reactor, PostgreSQL 15-18, log, leakage, and banned-pattern gates. Review or commit this working tree as one remediation/evidence change set; do not attribute these post-commit results to the unchanged `3305c30` tree.

---

## 12. Remaining non-blocking risks

- **Owning gates are complete.** The working-tree UI, browser/Failsafe, full-reactor, and PostgreSQL 15-18 gates are recorded in §7.4; U11 is complete.
- **Scenario accounting.** The POM and executable coverage contract require 539 evidence scenarios (557 before 10 September 2026); a completed Failsafe execution contains 545 tests because six infrastructure tests are outside that catalogue (§7.1).
- **Coverage margin.** `src/api` branch coverage is 87.01% against an 80% threshold; adding untested branches to a client without a loopback case can still trip the gate — that is intended.
- **`components` folder coverage (77%)** is not thresholded; `ConnectionStatus`/`StatCard`/`SetupScopeBar`/`ValueSelect` are exercised through page tests only.
- **Bundle size advisory.** Vite reports a single JavaScript chunk above 500 kB after minification. This is an optimization opportunity, not a correctness failure.
- **Toolchain and packaging advisories.** JDK 25 reports Maven/Guice `sun.misc.Unsafe` deprecation; Maven Shade reports known duplicate metadata/classes; npm reports a transitive `glob` deprecation; jsdom/Recharts report non-browser layout limitations. None produced a gate failure, vulnerability, dump, leak, or missing artifact.

## 13. Subsequent benchmark work (6 September 2026)

The [parameterised performance plan](PEEGEEQ_CACHE_PRODUCTION_BENCHMARK_PLAN.md) now governs the
separate performance/degradation analysis work. B0 is in progress: the first timeframe and
cumulative interval-accounting contracts have 11 passing focused tests after a recorded RED run.
Experiment/matrix specification and live recording are still pending; the legacy benchmark runner
and UI/browser behavior are unchanged. See that plan's implementation-evidence section and
`target/benchmark-b0-{red,green,verify}.log` for the scope and verification records.
The benchmark/dependency eight-module verification also passed: 409 tests in 59 Surefire reports,
zero failures/errors/skips, including 27 benchmark tests and existing PostgreSQL 18.3 integration
tests. No fresh full UI/browser reactor or four-version matrix is claimed for these pure contracts.

The subsequent B0 slice adds typed load parameters, bounded lazy parameter-matrix expansion,
versioned experiment/run descriptors, and repetition/fork budgets. It has 13 additional tests,
bringing focused B0 coverage to 24, including an assertion-RED/GREEN duplicate signed-zero case.
See section 13 of the performance plan and `target/benchmark-b0-matrix-*.log`. These are planning
contracts only: no load scheduler, live recorder, external campaign or fork launcher is wired yet.
The subsequent eight-module benchmark/dependency verification passed 422 tests in 62 fresh XML
reports, zero failures/errors/skips, including 40 benchmark-module tests. Its log is
`target/benchmark-b0-matrix-verify.log`; expected fault-test diagnostics were reviewed.

The next B0 slice establishes one authoritative JSON checkpoint file per actual execution, with
configuration, interval counters/rates, metrics, diagnostics and unfinished/failed/completed status.
Seven test-first JSON cases bring focused B0 coverage to 31. Latency collection, analysis and the
JSON-derived HTML view remain pending. See performance-plan section 14 and `target/benchmark-json-*.log`;
the legacy executable benchmark and browser/UI results are unchanged.
The JSON slice's eight-module verification passed 429 tests across 63 fresh XML reports, zero
failures/errors/skips, including 47 benchmark-module tests. Logs and expected fault diagnostics
were reviewed; see `target/benchmark-json-verify.log`.

The next B1 slice adds bounded latency distributions and synchronised interval recording, with
outcome/sample-count validation in the per-run JSON. Ten new pure tests cover distributions and
rollover/concurrency; a supplemental real-PostgreSQL test verifies 40 cache workflows in two JSON
measurement intervals. See performance-plan section 15 and `target/benchmark-recorder-*.log`.
Downstream bounded checkpoint orchestration, overhead calibration and the load scheduler remain
pending; no completed B1 phase or production performance claim is made.
Recorder/dependency verification passed 440 tests across 66 fresh XML reports, zero failures/errors/
skips, including 58 benchmark tests. See `target/benchmark-recorder-verify.log`; logs and expected
fault diagnostics were reviewed.

The next B1 slice adds `BenchmarkCheckpointWriter` (delta histories, bounded retained state, streamed
atomic single-JSON replacement) and `BenchmarkCheckpointPipeline` (Vert.x worker ownership, explicit
count/byte admission limits, fail-stop propagation and drain). Eight test-first cases and another
real-PostgreSQL recorder/pipeline integration case bring the benchmark module to 67 tests. The
eight-module verification passed 449 tests across 68 fresh XML reports, zero failures/errors/skips,
at 14:38:23 +08:00; all eight focused tests passed again after tightening the capacity assertion.
See performance-plan §16 and `target/benchmark-checkpoint-*.log` / `target/benchmark-pipeline-*.log`.
The latest logs retain only the already-classified tooling/schema and intentional fault diagnostics.

A separate local recorder-cost probe retained all 27 raw windows in
`target/benchmark-calibration-probe/d2b558f9-e7bb-409a-8c11-64ddb8310a26.json`. This is one-JVM,
single-thread diagnostic evidence, not a reusable calibration runner or deployment benchmark.
B1 remains in progress: automatic cadence, restart/recovery, long-run disk/heap behavior and repeatable
fork/concurrency calibration remain. Incremental checkpoint memory is bounded but each publication
still copies/hashes growing history; long-soak write cost is not solved. The legacy runner, UI and
screenshots are unchanged. Continue from performance-plan §11, not from earlier historical B0 notes.

The next B1 slice implements `BenchmarkCheckpointSession`: count/time publication of recorded
intervals, one active plus one bounded staged batch, resolved cadence policy in the run JSON, and
explicit draining finalisation. `BenchmarkCheckpointRecovery` performs read-only streamed structural/
metadata inspection; UNFINALISED requires owner review, not an assumption that the process crashed.
It does not repair, seize ownership or resume an interrupted execution. See performance-plan §17.
Six initial session tests and four recovery tests followed recorded compilation-RED/GREEN cycles;
supplemental terminal-byte-boundary and real-PostgreSQL session checks bring the module to 79 tests.
A suspicious ten-second test-fixture timeout was found during log review, corrected and made an
explicit failure condition before acceptance; the corrected six-test fixture run took 0.807 seconds.

`target/benchmark-cadence-verify.log` records all eight selected modules passing, 461 tests across
70 fresh reports, zero failures/errors/skips, at 14:47:59 +08:00. The post-review checkpoint/PostgreSQL
rerun passed all 22 tests. Logs retain only the classified tooling/schema and deliberate fault-test
diagnostics. B1 stays in progress: repeatable concurrent/fork/long-run calibration, constrained-heap
and disk-cost verification, recorder rollover and workload scheduling remain next. Automatic
restart/resume remains unsupported. No legacy benchmark, UI, screenshot or publication change.

The next B1 slice adds a maintained `benchmark-calibration` Maven profile and Java configuration,
runner and entry point. Each invocation is a fresh JVM/fork with explicit concurrency, histogram
layout, warm-up/measurement/window durations, checkpoint frequency, heap and file-size budget.
It retains all paired baseline/recorded observations, per-task timing/allocation, heap/GC and actual
checkpoint write costs in one JSON file. Outcomes and latency samples are synthetic recorder inputs,
never cache/database measurements. The old disposable probe is not treated as the new implementation.

Eight calibration tests now include real heap-limited child JVMs, failure evidence, exit codes and the
Maven launch contract. Test-first boundary assertions corrected doubled-window overflow and missing
unpublished-data diagnostics. Fork tests exposed a genuine shutdown/exit defect; a context-free Vert.x
completion bridge now reports the run outcome after cleanup, and tests reject terminated-executor
output or silent exit 0. No failure is suppressed. See performance-plan §18 and
`target/benchmark-calibration-{red,boundary-red,boundary-green,fork-red,shutdown-red,shutdown-green,profile-red,final-green}.log`.

`target/benchmark-calibration-verify.log` records all eight selected modules passing: 469 tests in
74 fresh Surefire reports, zero failures/errors/skips, including 87 benchmark tests and PostgreSQL
18.3 integration, completed at 15:01:15 +08:00. The budget-child IOException is deliberate failure-path
evidence, separate from a failing test. The runbook documents the opt-in command and repeated-fork
workflow; the production benchmark/capture command, UI/screenshots and publication status are unchanged.

After verification, three independent 32 MiB JVM invocations of the new Maven profile completed with
four concurrent recorder tasks, 1,024 buckets, 620 paired windows and 62 running-checkpoint records
per run. Each produced a ~37.6 MB JSON file larger than its maximum Java heap; all counts/distributions
reconcile and no work remains outstanding. Artifacts are under `benchmark-results/calibration-b1-20260906/`,
with exact identities and derived figures in performance-plan §18. Logs are
`target/benchmark-calibration-endurance-fork-{0,1,2}.log`; the last invocation finished at 15:05:30 +08:00.
Early write medians of 31.6–37.7 ms increased to 180.1–200.0 ms in the last ten writes. This is a
measured checkpoint-cost growth finding, not a hidden fixed-overhead assumption or production limit.
Continue with product workload scheduling/rollover while retaining explicit publication-pressure
accounting; the final long-soak persistence strategy and automated restart/resume remain open.

### B2 scheduling/accounting slice — implemented and accepted (6 September)

Read the testing standard, consolidated coding/lifecycle guidance and main implementation-plan
principles before continuing. The first B2 engine is `BenchmarkWorkloadScheduler`: injected clock,
closed-loop/rate-controlled demand, exact rational scheduling, bounded queue/physical concurrency,
explicit generator misses, queue expiry, logical timeouts retaining physical slots, late/duplicate
callback accounting, actual-boundary recorder samples and JSON-compatible diagnostics. No production
timer/cancellation/phase adapter or campaign command has been added. The new PostgreSQL test driver
is deliberately test-owned and is not presented as production orchestration. UI/screenshots unchanged.

Strict TDD logs are under `target/benchmark-scheduler-*`. The initial eight scheduler tests ran RED
before the engine, then GREEN with six recorder tests. Later RED/GREEN cycles added diagnostics and
corrected closed-loop concurrency being restricted by a rate-only catch-up cap. The final focused
unit result is **18 tests, zero failures/errors/skips** (12 scheduler, six recorder), in
`target/benchmark-scheduler-closed-loop-green.log`. Review performance-plan §19 for the intermediate
failed metrics implementation and shell-exit caveat; neither is claimed as an acceptance run.

`BenchmarkSchedulerIntegrationTest` adds three invocations: real SET/GET workflows under
both load models, incremental single-JSON publication, and real slow PostgreSQL queries proving
logical timeout does not release physical capacity. All three now execute successfully.
`target/benchmark-scheduler-integration.log` reports the 18 pure tests passing and one PostgreSQL
class setup error because Docker was stopped/unavailable. A CLI startup timed out. Starting the
installed Docker Desktop application exposed a backend crash while accessing/removing its local
`sailor-ingest.sock` runtime entry. Read-only inspection found a zero-length reparse point. Docker
data/settings were not reset, upgraded or deleted; repair is outside this implementation slice.

The user authorised local Docker repair. Docker was stopped and only affected runtime sockets and the
secrets-engine runtime directory were moved to timestamped `.stale-*` backups; images, volumes,
settings and project files were not deleted. A first successful focused run was followed by Docker
Desktop auto-updating 4.88.0 to 4.89.0 during the initial full gate, restarting the backend and
removing the live Docker pipe. That interrupted Maven run was stopped and is diagnostic only. After
one post-update cleanup and a single controlled start, engine 29.7.2 remained healthy.

Final evidence: `target/benchmark-scheduler-integration-green.log` has **21 focused tests**, zero
failures/errors/skips (12 scheduler, six recorder, three real PostgreSQL). The acceptance log
`target/benchmark-scheduler-verify-final.log` has **484 tests in 76 fresh Surefire reports**, zero
failures/errors/skips, including 102 benchmark tests on PostgreSQL 18.3; it completed at 15:55:48
+08:00. Log review found only established/intentional failure-path output. The failed setup log and
auto-update-interrupted `target/benchmark-scheduler-verify.log` are not acceptance evidence.
One PostgreSQL container stranded in CREATED state by that interrupted current run was identified by
its Testcontainers session labels and removed; unrelated older stopped containers were not touched.

**Next:** implement managed execution, independent timers, bounded stop/drain, phase-aware rollover
and explicit publication-pressure accounting. Current benchmark source banned-pattern and whitespace
scans are clean; no broader PostgreSQL matrix, UI run or deployment-capacity result is claimed.

### Pre-Jenkins repository acceptance — complete (6 September)

The repository-owned Jenkins pipeline and operations guide have been added for the separate
`PeeGeeQ-Cache` job. The live job is configured from `*/master`, has no automatic trigger and had
not been run at this checkpoint. The sibling `PeeGeeQ` job was not changed. The first Jenkins run
must use the committed remote revision; it must not be represented as evidence before that run
finishes and its published artifacts are reviewed.

The first complete local reactor attempt passed every Java, UI and browser scenario but correctly
failed its evidence gate when Windows refused to overwrite an unchanged documentation PNG that was
open through a viewer's memory mapping. A test-first repair reproduced that exact filesystem error.
Gallery publication now compares verified bytes and leaves an unchanged image in place, retaining
the same descriptive flat filename and the original unmodified pixels. Changed images are still
written normally and any genuine publication error remains fatal. No screenshot is masked,
substituted, renamed by scenario identifier or exempted from evidence validation.

The final fresh acceptance command was `mvn --batch-mode --no-transfer-progress verify`. It completed
at 19:52:17 +08:00 in 33:06 with all eleven reactor modules successful. The UI gate passed 36 files
and 170 tests. Java Surefire/Failsafe evidence contains 158 reports and 1,237 tests with zero
failures, errors or skips; REST unit tests report 177 and the browser/integration suite reports 563.
Both the 192,785,943-byte self-contained Playwright report and the feature-organised screenshot
gallery were published successfully. The final log contains no build/test failure line or
credential-like leakage match. Changed source contains no prohibited mocking framework, disabled
test, blocking sleep, global-property mutation or whitespace error. Expected existing headless-DOM,
JDK deprecation, schema-idempotency and deliberate fault-path diagnostics remain classified output.

Commit `8996af9` was pushed to `origin/master` and Jenkins build `PeeGeeQ-Cache #1` checked out that
exact revision. Jenkins did not expose declarative parameter values on the job's first execution,
so the environment stage observed an unset `RUN_MODE`. Its log-capture pipeline also failed to
propagate that error and allowed the tests-skipped rebuild to start. Build #1 is therefore diagnostic
RED evidence only, regardless of its eventual Jenkins result. The follow-up repair gives first-run
execution explicit `verify`, PostgreSQL-image and topology defaults and captures preflight output
without masking the failing command's status. A subsequent build is required for Jenkins acceptance.

Jenkins build #2 checked out the first repair, selected `verify` and passed the full environment
contract. Its preparatory `clean install -DskipTests` then reached the REST module's mandatory
Playwright evidence check without having executed the evidence-producing tests. The resulting
failure occurred before the reactor-verification stage and is a second valid RED diagnostic, not a
product-test failure. The preparatory phase is corrected to `clean package -DskipTests`; Maven's
full `verify` remains the selected acceptance stage. Post-build JUnit publication now tolerates no
reports only after an earlier failure, avoiding a secondary publication exception while preserving
the non-empty report requirement for successful verification and compatibility builds.

Build #3 did not allocate a workspace. Jenkins' controller-side Groovy parser rejected the JUnit
guard because a continued expression began its next line with `&&`; the newer local Groovy parser
had accepted the same syntax. The operator is moved to the preceding line. This is parser RED
evidence, and the next launch requires Jenkins-controller validation of the complete declarative
pipeline rather than relying on the local compiler alone.

Build #4 passed controller parsing, checkout, the full Jenkins worker contract and preparatory
packaging, then entered the real reactor verification. It found one Linux-visible UI failure:
169/170 tests passed, but setup detach displayed “Primary cache was detached” before the
authoritative setup-list reconciliation completed, leaving the old `CONNECTED` row and `Detach`
action visible until timeout. The production action had relied on asynchronous RTK invalidation.

A deterministic loopback test now holds the post-detach list response and asserts that no success
notice is exposed while the row is stale. It failed before the implementation change and passes
after `SetupsPage` awaits the authoritative list refetch before clearing scope and announcing
completion. The complete pinned Node 22.22.2 UI verification passes 36 files and all 170 tests,
including generated-client validation, type checking, zero-warning lint, coverage (94.18% statements
and lines, 86.05% branches, 84.77% functions) and the production Vite build. Build #4 is retained as
useful Linux RED evidence; it is not an accepted Jenkins result.

Jenkins build #5 checked out that repair and passed the complete 170-test frontend boundary,
including the formerly failing setup-detach case. The Java browser suites then failed uniformly at
browser startup because the worker does not provide branded Google Chrome at
`/opt/google/chrome/chrome`; no affected browser scenario reached its product assertions. This is a
CI runtime mismatch: the sibling `peegeeq` pipeline installs Playwright-managed Chromium, while the
cache launcher had unconditionally selected the `chrome` channel. The TDD repair adds a validated
browser-distribution setting with `chrome` retained as the developer default, `chromium` selecting
Playwright's managed binary, and all other values rejected. The Jenkins pipeline resolves the
pinned Java Playwright version, installs its Chromium runtime after packaging, and explicitly uses
that distribution for verification and PostgreSQL compatibility. Focused configuration tests pass
7/7. Build #5 remains diagnostic RED evidence and a later full Jenkins run is required for
acceptance.

## 14. Capability gating removal (10 September 2026)

Executed from [the capability gating removal plan](archive/PEEGEEQ_CACHE_CAPABILITY_GATING_REMOVAL_PLAN_2026-09-04.md); its §9 holds the phase-by-phase execution record and every deviation. This section records what the next UI session needs to know.

### 14.1 What changed in the console

- **No capability negotiation.** `GET /api/v1/setups/{setupId}/capabilities`, the `SetupCapabilities` schema, the session `features` block (`setupRegistration`, `sensitiveReveal`), and the `Capabilities` RTK Query tag are gone. Every management section is reachable once a setup is selected; every write control is gated by `isOperator` alone. The server decides each request by role, as before.
- **Effective limits come from setup details.** `SetupDetails` now carries `limits` (`pubSubChannelMaxBytes`, `pubSubPayloadMaxBytes`, `maximumValueBytes`), validated by `setupLimitsSchema` in `src/api/setup-schemas.ts`. `ManagementShell` reads them through `useGetSetupDetailsQuery` for the selected setup and passes them to `PubSubPage` (`maximumChannelBytes`, `maximumPayloadBytes`) and `SettingsPage` (`limits`, `migrationVersion`). The connection-test response keeps `limits` and lost `capabilities`.
- **Scope is committed immediately.** `scope-store.select(setupId)` no longer takes a snapshot; `SetupScopeBar` commits on change without a fetch; the shell no longer clears the stored setup on a failed lookup.
- **Props removed.** `canInspectExpired` (Entries), `canBatch`/`canScan`/`canMetrics`/`canOwnLocks` (Advanced), `capabilities` (Settings). `EntryDetailsPage`'s viewer message reads "Operator permission is required."; the Setups details dialog shows an "Effective limits" section instead of a capability list; `.capability-list` CSS is gone.
- **Files touched.** `setup-schemas.ts`, `protocol-schemas.ts`, `setup-client.ts`, `operation-manifest.ts` (59 operations), `setupsApi.ts`, `managementApi.ts`, `scope-store.ts`, `SetupScopeBar.tsx`, `ManagementShell.tsx`, `SettingsPage.tsx`, `SetupsPage.tsx`, `EntryDetailsPage.tsx`, `EntriesPage.tsx`, `AdvancedOperationsPage.tsx`, `foundation.css`, and 22 test files (the session fixtures lost `features`; `scope-store`, `app`, `setup-client`, `setups-page`, `settings-page`, `advanced-operations-page`, `entries-page`, `counter-lock-pages` lost or renamed cases). The suite is 36 files / 168 tests.

### 14.2 What changed in the Java browser layer

- `ManagementCapabilityBrowserIT` and its 18 `PW-CAPABILITY-*` scenarios are deleted; `CURRENT_SCENARIO_COUNT` and `peegeeq.playwright.expectedScenarios` are both 539. The two values still change together.
- The fixture's allowed background traffic (`FIXTURE_OPERATIONS`) lists `getSetup` instead of `getSetupCapabilities`, because the shell now fetches setup details on every selection. Scenario operation lists that declared `getSetupCapabilities` declare `getSetup`.
- The `scope-and-capabilities` journey is `scope-restoration` and owns `getNamespace` only (`getSetup` belongs to `setup-lifecycle`; the coverage test allows one owner per operation). `PW-SETUP-054` asserts the runtime Pub/Sub row of the details dialog; `PW-SETUP-048` looks for the `Effective limits` heading.
- The `UnaryOperator<SetupCapabilities.Source>` capability-source filter threaded through `ManagementConsolePostgresFixture` and `ManagementServerApplication` (the U11 merge item in §8) no longer exists.

### 14.3 Verification and the Node finding

- Per-phase gates and the complete browser gate are recorded in the plan's §9 with their logs under `logs/gating-removal-phase*.log`. The REST `mvn verify` passed Surefire 179 and Failsafe 545 (539 scenarios plus six infrastructure checks) on Docker PostgreSQL.
- Running Vitest with the system Node 24 showed four deterministic failures in the SSE-stream cases of `monitoring-page`, `pubsub-live`, and `pubsub-page`, while the Maven-run suite under the pinned Node 22.22.2 passed 168 of 168. Root cause: undici 7 (Node 24's bundled `fetch`) requires `RequestInit.signal` to be the runtime's `AbortSignal`, and vitest's jsdom environment replaces the global `AbortController`/`AbortSignal` with jsdom's while `fetch` stays the runtime's, so `FetchSseTransport`'s requests failed before reaching the loopback server; undici 6 (Node 22) was lenient. Pinning Node is not a fix (any pinned release ages into deprecation or a CVE), so the environment now restores the runtime pair: `test/support/jsdom-runtime-fetch-environment.ts` wraps the built-in jsdom environment and `vitest.config.ts` uses it. The suite passes on Node 24 and Node 22. Also note `node\node.exe ...npm-cli.js run <script>` does not pin the toolchain, because npm resolves `vitest` through `PATH`; prepend `node\` to `PATH` when the pinned version matters.
- The complete `mvn clean verify` of all eleven modules is logged at `logs/capability-gating-removal-verify-20260910.log`; see the last paragraph of this section for its result.

### 14.4 Workflow documents added

- `docs/guidelines/PEEGEEQ_CACHE_TEST_COMMANDS.md`: the exact Maven and npm commands for this reactor, `Tee-Object` into `logs\<description>-<YYYYMMDD>.log`, rebuild-before-verify, the REST evidence-check caveat (`-Dmaven.antrun.skip=true` on rebuilds without a browser run), Docker requirements, and how to report a run from the saved log.
- `CLAUDE.md` at the repository root: the mandatory verification workflow (one phase at a time, rebuild before verification, targeted tests through `Tee-Object`, no overstated scope, Docker first, no mocking, no error swallowing).
- Every affected design document carries a dated "Capability gating removed" note; historical acceptance figures (557 scenarios, 60 operations, 62 inventory methods) remain as history.

Complete reactor verify result (`mvn --batch-mode --no-transfer-progress clean verify`, finished 2026-09-10T17:04:12+01:00 in 29:58, `logs/capability-gating-removal-verify-20260910.log`): all eleven modules `SUCCESS`, `BUILD SUCCESS`, zero failures, errors, or skips.

| Module | Result |
|---|---|
| `peegee-cache-api` | 59 tests |
| `peegee-cache-core` | 20 tests |
| `peegee-cache-test-support` | 4 tests |
| `peegee-cache-pg` | 243 tests on Docker PostgreSQL |
| `peegee-cache-runtime` | 48 tests on Docker PostgreSQL |
| `peegee-cache-observability` | 5 tests |
| `peegee-cache-management-ui` | Vitest 36 files / 168 tests under the pinned Node 22.22.2, coverage thresholds and production build green |
| `peegee-cache-rest` | Surefire 179; Failsafe 545 (539 browser scenarios plus six infrastructure checks); evidence check passed |
| `peegee-cache-benchmarks` | 102 tests |
| `peegee-cache-examples` | no tests |
