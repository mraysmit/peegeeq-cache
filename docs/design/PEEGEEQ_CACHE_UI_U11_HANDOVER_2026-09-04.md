# PeeGeeQ Cache Management UI — U11 Reference-Parity Migration Handover (4 September 2026)

**Author:** Claude (Cowork session), on behalf of Mark A Ray-Smith; updated by Codex after the Windows verification pass
**Repository:** `peegeeq-cache` — module `peegee-cache-management-ui`, plus the Java Playwright suite in `peegee-cache-rest/src/test/java/dev/mars/peegeeq/cache/rest/server`
**Checkout:** `C:\Users\markr\dev\java\corejava\peegeeq-cache` (branch `master`, HEAD `78fdabf` "UI U11.5-U11.9: complete reference-parity migration in the UI module")
**Reference implementation:** `C:\Users\markr\dev\java\corejava\peegeeq\peegeeq-management-ui`
**Governing documents:** `docs/design/PEEGEEQ_CACHE_MANAGEMENT_UI_IMPLEMENTATION_PLAN.md` (phase U11, §4.1 pinning rule, evidence blocks U11.0-U11.9, §9 status table, §10 completion definition); `docs/design/PEEGEEQ_CACHE_MANAGEMENT_UI_DESIGN.md` §3.1 (antd/Recharts mandate) and §8.1/§8.2 (state ownership, sensitive data); `docs/guidelines/PEEGEEQ_CACHE_TEST_COVERAGE_AND_TDD_APPROACH.md` §5 (management UI component and client tests)

---

## 0. Executive summary

Every slice of the U11 migration (U11.0 through U11.9) is implemented. The console now follows the reference project's patterns and dependency versions end to end: antd 5 and Recharts for every control and chart, RTK Query for every read and committed mutation, Zustand for live/session state, Zod at every contract boundary, and a test layer that renders real pages against a real loopback HTTP server with schema-produced fixtures and no page/client test doubles.

Final UI-module numbers (cloud sandbox, `npm run quality && npm run test:coverage && npm run build`):

| Gate | Result |
|---|---|
| Vitest | 36 files / 169 tests, all passing at the U11.9 gate; one reconnect regression test added during Windows verification |
| `test/quality` guards | 5 files / 17 assertions, all GREEN |
| Branch coverage thresholds | `src/api/**` 83.58% (≥ 80), `src/state/**` 92.31% (≥ 80) |
| Branch coverage, other folders | `src/store` 95.30%, `src/app` 89.76%, `src/features` 82.85%, `src/presentation` 90.32%, `src/components` 77.27%; module total 85.16% |
| `tsc --noEmit` | clean |
| `eslint . --max-warnings 0` | clean |
| focused reconnect regression | `test/monitoring-live.test.ts`: 4/4 passing |
| `vite build` | green on Windows after the reconnect correction (one chunk-size advisory from antd) |
| jsdom axe (temporary probes, every page state touched in U11.5-U11.7) | 0 violations |

The browser coverage contract contains 557 evidence scenarios. A complete Maven Failsafe execution reports 560 tests because `ManagementPlaywrightObservationIT` (1) and `ManagementRunnableArtifactIT` (2) are infrastructure verification tests outside that catalogue. The Windows verification work and exact commands are recorded in §7 and §9. The full reactor and PostgreSQL 15-18 matrix still remain after the browser gate.

Everything described here is already in the checkout, verified by md5 after each transfer; the transfer bundles have been deleted.

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

- `scope-store.ts` / `scope-storage.ts` — selected setup, its capability snapshot, selected namespace (allowlisted persistence).
- `live-store.ts` — monitoring `connectionState`, notification drawer state and bounded envelope buffer, the per-setup Overview `trend` (`recordSnapshot`, the reference `updateChartData` pattern), and, new in U11.7, the Pub/Sub slice: `pubSubConnection`, `pubSubMessages`, `setPubSubConnection`, `receivePubSubMessage(message, bufferLimit)`, `clearPubSubMessages`, `stopPubSub`. Metadata only (`payloadState: 'MASKED'`); payloads never enter it.
- `preferences.ts` — the allowlisted display preferences and `PREFERENCES_CHANGED_EVENT`.

### 2.4 Shell and common components

- `src/app/App.tsx` — session gate (antd `Card`/`Form`/`Input.Password`/`Spin`/`Alert`), store per session client, `resetApiState` on teardown; `ManagementShell` no longer receives a `sessionClient` prop (deleted in U11.7).
- `src/app/ManagementShell.tsx` — antd `ConfigProvider` (theme algorithm + **`virtual={false}`**, see §6.1), `Layout`/`Sider` (`aria-label="Primary navigation"`), `Menu` of router `Link`s inside `<nav aria-label="Management sections">`, header status group, notifications `Drawer` with the inner `<aside aria-label="Notifications">`, capability-gated routes; pages receive only capability flags and scope, never clients.
- `src/components/common/` — `ConnectionStatus`, `StatCard` (`.metric-card`, antd `Statistic`), `SetupScopeBar` (`useListSetupsQuery` + lazy capabilities → Zustand scope), `ValueSelect` (§6.1). `ConfirmDialog.tsx` and `FilterBar.tsx` were unused and are deleted (§4.9).

### 2.5 Pages (`src/features`)

| Page | Reads | Mutations | Sensitive path |
|---|---|---|---|
| `overview/OverviewPage`, `OverviewMonitoring`, `SessionTrendChart` | `useGetOverviewQuery` etc.; Recharts trend from the live store | — | — |
| `monitoring/MonitoringPage` | database/runtime/activity queries; metrics SSE via `clients.metricsStream` upserting `inspectionApi` cache | — | — |
| `setups/SetupsPage` | list, lazy details/health/capabilities | register, testConnection, testRegistered, connect, detach, forget | password never leaves component state |
| `namespaces/NamespacesPage`, `NamespaceDetailsPage` | namespaces, namespace details, export | — | — |
| `entries/EntriesPage` | `useGetEntriesQuery` keyed by setup/namespace/filters/cursor | setEntry (create), preview/execute bulk delete | — |
| `entries/EntryDetailsPage` | `useGetEntryQuery` (`includeExpired`) | setEntry, expire, persist, touch, delete | reveal on `clients.inspection.revealEntryValue`, component memory, auto-hide, `visibilitychange`, route/setup change |
| `counters/CountersPage` | `useGetCountersQuery` | set, adjust, expire, persist, delete, preview/execute bulk delete | — |
| `locks/LocksPage` | `useGetLocksQuery`, `useLazyGetLockQuery` (cache bypassed) | forceRelease | owner reveal on `clients.resource.revealLockOwner` |
| `advanced/AdvancedOperationsPage` | none through RTK Query | none through RTK Query | everything on `clients.backendCapability` (no-store); results cleared on `visibilitychange`, unmount, and "Clear sensitive state" |
| `pubsub/PubSubPage` | live SSE via `clients.pubSubStream` → live store | createSubscription, publish, deleteSubscription | payload reveal on `clients.pubSub.revealPayload` |
| `settings/SettingsPage` | session + capability props | none (localStorage allowlist only) | — |

---

## 3. Test layer as delivered

### 3.1 Shared fixtures (`test/support`)

- `loopback-server.ts` — `startLoopbackServer(handler)` starts a real `node:http` server on `127.0.0.1:0`. `LoopbackRequest` exposes `method`, `url` (raw, with query), `path`, `query` (`URLSearchParams`), `headers`, `body` (JSON-parsed when possible), `rawBody`, `onClose(listener)`. `LoopbackRespond` exposes `json(status, body, headers?)` (adds a session `set-cookie`), `noContent(headers?)`, `problem(status, code, detail, { title?, correlationId?, fieldErrors? })` (RFC 9457 body produced by `managementProblemSchema.parse`), `raw(status, headers, body)`, `sse(frames, { keepOpen? })`. `server.requests` records everything; `server.use(handler)` swaps the handler mid-test; `invalidBody(body)` is the identity function that declares a negative fixture; `route(method, path | RegExp, request)`. **`close()` destroys open responses, calls `server.close()`, then `server.closeAllConnections()`** — without the last call, undici's keep-alive pool and a reconnecting SSE client hold the listener open past the test (this was the cause of an `afterEach` hook timeout while converting the Pub/Sub page).
- `render.tsx` — `renderWithProviders(ui, { store, initialEntries? })` installs `MemoryRouter` → `ConfigProvider` (`theme.token.motion: false`, `virtual={false}`) → `ManagementProvider` as the Testing Library `wrapper`, so `rerender(...)` keeps them. `chooseOption(user, combobox, label)` opens an antd Select and clicks the option by visible label.
- `entry-fixture.ts` — `startEntryFixture(initial = entryMetadata)` → `{ server, store, sessionClient, state, requests(predicate), close }`. Serves session, empty setup list, the entry list (cursor `entry-cursor-2` → key `next-page`), entry GET, reveal POST (no-store headers), PUT set entry (version+1, or `state.setEntryProblem`), POST ttl/persist/touch, DELETE, bulk-delete preview/execute. Fixtures: `entryMetadata` (namespace `客户/订单`, key `café/東京/🔒?x=1`, version `9007199254740993` — encodings derived from the production codec) and `ordersMetadata` (`orders`/`order:1`, version 3).
- `resource-fixture.ts` — `startResourceFixture()` with mutable `state.counters`/`state.locks`, so committed mutations are visible to the next read exactly like PostgreSQL truth: counters list with namespace/prefix filters, counter GET/PUT/increment/ttl/persist/DELETE with `If-Match`/`If-None-Match` checks and signed 64-bit overflow → `COUNTER_OVERFLOW`, bulk preview/execute, locks list/GET, owner reveal (no-store), force-release with version check, and every backend-capability route (exists, batch-get/set/delete, scan, cache-metrics, acquire/renew/release/ownership) with no-store headers.

### 3.2 Inventory (36 files / 169 tests)

Page and shell tests (all through `renderWithProviders` over a loopback server):

- `app.test.tsx` (5): navigation/identity/role/connection/logout button; theme + notifications without exposing the CSRF token, preference persisted; capability-gated route (`Counters unavailable`, no `/counters` request); restored scope re-reading `/capabilities` once; **the real `App` under `/ui`**: 401 → bootstrap gate, rejected token → `BOOTSTRAP_TOKEN_INVALID` with the field cleared, accepted token exchanged on the wire, shell rendered without the token or CSRF proof in the DOM, `End local session` → `DELETE /api/v1/session/local` with `X-PeeGeeQ-CSRF`.
- `overview-page.test.tsx` (3), `monitoring-page.test.tsx` (1), `setups-page.test.tsx` (6), `namespaces-page.test.tsx` (4) — U11.3/U11.4, unchanged except that `overview-page` now awaits each independently loaded section (`findBy*`).
- `entries-page.test.tsx` (3): capability-gated `Include expired` absent from the open listbox and the query string `ttlState=ALL_LIVE&sort=key:asc&limit=50`; setup and namespace scope required before any `/entries` request; metadata without values, 64-bit version formatted, filters on the wire (`prefix`, `valueType`, `ttlState`), cursor stack `[null, null, 'entry-cursor-2']` (going back is served from the RTK Query cache and never fabricates a cursor).
- `entry-details-page.test.tsx` (3): viewer sees `Value hidden` and no reveal; reveal POSTs to the exact encoded route with `{ reason }` and the CSRF header, the value is absent from the URL, Web Storage, and Redux state, explicit copy and hide; auto-hide after the server window (25 ms), hide on `visibilitychange`, hide on route/setup change (3 reveals counted).
- `entry-administration-pages.test.tsx` (9): viewer surfaces with no non-GET request; single-entry administration with bulk delete disabled; create with `If-None-Match: *` and a JSON contract value; CAS default with `If-Match: "v3"`, committed version re-read into `Descriptions`; upsert without precondition and `REPLACE` TTL only when chosen; `VERSION_MISMATCH` preserving input and refetching metadata; TTL/touch/persist/delete with `If-Match` and the exact bodies; bulk preview of exact-version targets and execution only after the typed phrase; matching-filter preview sending the applied server filter, cancelled without execution.
- `counter-lock-pages.test.tsx` (9): viewer read-only and owner masked; bulk-delete and forced-release degraded independently; live trimmed filters clearing the selection; `COUNTER_OVERFLOW` alert then a committed `-2` adjustment (`If-Match: "v3"`) re-read into the table and delete with the observed version; create by exact value (`If-None-Match: *`, minimum signed 64-bit) appearing in the list; create by signed adjustment (`createIfMissing`, `REPLACE` TTL); bulk preview/execute with `DELETE 1 COUNTERS`; lock dialog re-reading before display and before release (`If-Match: "v5"` after the fixture advanced the version), owner reveal hidden on explicit hide and `visibilitychange`, focus on `Confirm lock key`; stale release → `VERSION_MISMATCH` without releasing.
- `advanced-operations-page.test.tsx` (4): existence/scan/batch-get/batch-set/batch-delete/metrics with the typed contract bodies, results kept out of Redux; malformed batch input rejected locally before any request; capability-gated panels withheld; owner-token lock lifecycle (`acquire`, `renew`, `ownership`, `release`) with the token absent from Redux and cleared by "Clear sensitive state".
- `pubsub-page.test.tsx` (4): viewer subscription with the message arriving through the real SSE stream into the live store (no publish/reveal); non-durable labelling, masked metadata, reveal held only in component memory, hide on explicit hide/`visibilitychange`/stop, stop deleting the subscription and closing the stream (`closed === opened === 1`); publish with CSRF header, described as acceptance not delivery, payload cleared; UTF-8 byte limits for channel, buffer entries, and publish channel enforced before transport.
- `settings-page.test.tsx` (2): every preference driven through antd Selects, the exact allowlisted record in `localStorage`, five change events, no request; rendering without a setup/capabilities.

Client and contract tests (loopback, schema-produced bodies, `server.use` per case): `session-client` (8, incl. 401 → CSRF dropped, unreadable body, non-problem failure, cacheable sensitive response), `setup-client` (4), `inspection-client` (15), `entry-administration-client` (9, incl. precondition validation and `VERSION_MISMATCH`), `resource-client` (4), `backend-capability-client` (4), `pubsub-live` (4, incl. SSE lifecycle and `Accept` header), `pubsub-live-http` (1, resume with `Last-Event-ID`), `monitoring-live` (3), `store` (6), `protocol-schemas` (8), `operation-manifest` (3), `identifier-codec` (12), `preferences` (3), `scope-store` (4), `scope-storage` (4), `display-bytes` (2), `display-time` (2), `entry-formatters` (3).

Guards: `component-library.guard` (5), `no-test-fakes.guard` (3), `no-direct-transport.guard` (3), `sensitive-dto.guard` (4), `zod-fixture.guard` (2) — §5.

### 3.3 Testing conventions learned in this session (keep following them)

- antd `Modal` in jsdom: after `await screen.findByRole('dialog', { name })`, assert content with `await waitFor(() => expect(within(dialog).getByText(...)).toBeVisible())` — the first frame is rc-motion's `appear-prepare`.
- antd `Checkbox` inputs have `opacity: 0`: assert `toBeInTheDocument()`/`toBeChecked()`, never `toBeVisible()`.
- antd `Select` options: `chooseOption(user, screen.getByLabelText('Set mode'), 'Always / upsert')`; assert the selection with `screen.getByLabelText(...).closest('.ant-select')` `toHaveTextContent(label)`.
- Real `App` tests must `window.history.pushState({}, '', '/ui/')` first (`BrowserRouter basename="/ui"`).
- SSE fixtures: keep per-fixture counter objects (a response destroyed by the previous `close()` emits its `close` event asynchronously and would otherwise be counted against the next test).
- Bodies for the zod guard: `let body: unknown = xSchema.parse(...)` reassigned only with `*Schema.parse(...)` or `invalidBody(...)`, or `server.use((request, respond) => respond.json(200, xSchema.parse({...}), HEADERS))` per case.

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

`ManagementBrowserCoverageTest` and the REST POM define 557 reportable evidence scenarios. The complete Failsafe run contains 560 JUnit tests: the 557 scenarios plus one Playwright observation test and two runnable-artifact tests. Keep both numbers when recording evidence; they describe different layers and are not contradictory.

### 7.2 Browser-contract adaptations

- **`AntSelect.java`** clicks the visible antd selector container (the native search input is read-only), selects options through their reviewed `data-value`, exposes option values/labels in display order, and reads the selected value from `.ant-select-selection-item [data-value]`.
- Overview and monitoring locators use antd `Statistic` and `Descriptions`; setup details use `Descriptions`; entry metadata uses its accessible region; namespace, entry, capability, monitoring/settings, and shell scenarios select values through `AntSelect`.
- Boundary pagination addresses the exact `Next page` button. Product journeys wait for loading indicators to clear before accessibility scans, verify modal focus containment/restoration, and wait for the modal root's rc-motion animation before running axe.
- The accessibility gate uses exactly `axe-core 4.12.1`; the light/dark design tokens, default/primary buttons, drawer mask, and portal-rendered surfaces have explicit contrast-safe behavior. Axe failures now include URL, target HTML, and measured failure summaries rather than only selectors.

### 7.3 Deterministic lifecycle and readiness corrections

- `FetchSseTransport` detaches the active `AbortController` before aborting it on `offline`. This prevents the aborted stream's `finally` block from suppressing an `online` reconnect that starts synchronously. The same ordering is applied to validated SSE streams.
- `test/monitoring-live.test.ts` reproduces the immediate offline/online ordering against a real loopback SSE endpoint; no transport stub or fake timer is used.
- Setup connection assertions allow 15 seconds because the production connection attempt has an explicit 10-second backend timeout. Live reconnect assertions use the same bounded browser window.
- `ManagementConsolePostgresFixture` waits for the password modal to detach before checking the secret canary, uses exact visible setup-scope text rather than substring title matches, and lets each scenario declare expected or explicitly allowed failed responses for diagnostics.
- Monitoring refresh verification is cache-aware: it requires the scheduled overview request without assuming a cached initial read must issue another HTTP request.
- Lock header verification waits for exactly eight rendered header cells before comparing their text sequence. This closes the observed empty-list race without delaying successful renders.

### 7.4 Verification evidence

| Gate | Result |
|---|---|
| UI `npm run quality` | PASS (`tsc --noEmit`; ESLint with zero warnings) |
| focused monitoring transport test | PASS, 4/4 against the loopback server |
| production UI build | PASS; rebuilt UI JAR installed and REST runnable artifact repackaged |
| affected Playwright set | live transport 20/20, setup 56/56, shutdown 8/8; repaired product-journey and lock boundaries pass focused reruns |
| complete Playwright/Failsafe run | In progress at the time of this edit; replace this row with the terminal Maven summary before commit |

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

## 9. Verification commands and remaining gates

Executed on Windows with `JAVA_HOME=C:\Users\markr\.jdks\openjdk-25`:

1. UI quality: `peegee-cache-management-ui\node\node.exe peegee-cache-management-ui\node\node_modules\npm\bin\npm-cli.js run quality`.
2. Focused transport regression: the same managed Node/npm entry point with `exec vitest -- run test/monitoring-live.test.ts`.
3. UI production bundle: the same managed Node/npm entry point with `run build`.
4. Install and package: `mvn -pl :peegee-cache-management-ui -DskipTests org.apache.maven.plugins:maven-jar-plugin:3.5.0:jar org.apache.maven.plugins:maven-install-plugin:3.1.4:install`, then `mvn -pl :peegee-cache-rest -DskipTests package`.
5. Complete browser gate: `mvn -pl :peegee-cache-rest failsafe:integration-test failsafe:verify`. Reports land in `peegee-cache-rest/target/failsafe-reports`; screenshots/traces and the HTML evidence report land under `peegee-cache-rest/target`.

After the browser gate, run `npm run test:coverage`, the complete Maven reactor, and the PostgreSQL 15-18 matrix. Move the U11 row in the implementation plan to `COMPLETE` only when those owning gates are recorded.

---

## 10. Workflow notes for the next session

- Use the UI module's managed `node\node.exe` and npm CLI. The workstation also has Node 24 on `PATH`; mixing it with the Maven-managed Node 22 installation obscures dependency diagnostics.
- If `node_modules` is incomplete, restore it once with the managed runtime's `npm-cli.js ci`, wait for that process to finish, and then run gates. Do not start overlapping installs.
- The checkout mixes CRLF and LF. Do not normalize files incidentally; use `git diff --ignore-cr-at-eol` when reviewing semantic changes.
- The final full browser log for this pass is `peegee-cache-rest/target/playwright-full-final.log` (generated evidence, not a source file).

---

## 11. Suggested commit message

```
feat(management-ui): complete U11 parity and harden browser verification

Complete the reference-parity migration across the management console.
Build every page on antd and Recharts, route reads and committed
mutations through RTK Query, and keep live/session state in Zustand.
Keep entry values, lock owners, Pub/Sub payloads, and advanced-operation
results on no-store clients and in short-lived component memory.

Replace injectable client ports and hand-built page fixtures with real
loopback HTTP/SSE tests whose response bodies cross the production Zod
schemas. Remove the legacy modal, unused common controls, and native
control CSS now owned by antd. Pin axe-core so browser accessibility
evidence is evaluated against a reproducible ruleset.

Fix the SSE offline/online ordering race by detaching an active abort
controller before aborting the old stream, allowing an immediate online
event to install the replacement connection. Cover the ordering with a
real loopback stream and browser lifecycle events without transport
stubs or fake timers.

Adapt Playwright to the rendered antd contracts: select reviewed values
through AntSelect, wait for observable table/modal/connection states,
align connection bounds with the backend timeout, preserve modal focus
and restore behavior, make monitoring assertions cache-aware, and add
URL/HTML/contrast details to axe failures. Extend fixture diagnostics to
distinguish expected and allowed failed responses and use exact setup
scope matches.

Consolidate the U11 handover into the 4 September document and remove
the superseded 3 September snapshot.

Verification:
- managed npm quality gate (TypeScript + ESLint): pass
- monitoring live transport regression: 4/4 pass
- production Vite build, UI JAR install, REST package: pass
- focused live/setup/shutdown browser set: 84/84 pass
- repaired product-journey and lock readiness scenarios: pass
- complete Failsafe execution: replace with terminal 560-test summary

Remaining owning gates: full UI coverage, complete Maven reactor, and
PostgreSQL 15-18 compatibility matrix.
```

---

## 12. Open questions and risks

- **Owning gates remain.** Do not mark U11 complete until the full UI coverage gate, complete Maven reactor, and PostgreSQL 15-18 matrix are recorded.
- **Scenario accounting.** The merged POM and executable coverage contract require 557 evidence scenarios; the complete Failsafe execution contains 560 tests because three infrastructure tests are outside that catalogue (§7.1).
- **Coverage margin.** `src/api` is at 83.58% against an 80% threshold; adding untested branches to a client without a loopback case will trip the gate — that is intended.
- **`components` folder coverage (77%)** is not thresholded; `ConnectionStatus`/`StatCard`/`SetupScopeBar`/`ValueSelect` are exercised through page tests only.
- **Bundle size advisory.** Vite reports a single JavaScript chunk above 500 kB after minification. This is an optimization opportunity, not a correctness failure.
