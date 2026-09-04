# PeeGeeQ Cache Management UI — U11 Reference-Parity Migration Handover (3 September 2026)

**Author:** Claude (Cowork session), on behalf of Mark A Ray-Smith
**Repository:** `peegeeq-cache`, module `peegee-cache-management-ui` (plus Java Playwright suite in `peegee-cache-rest`)
**Governing documents:** `docs/design/PEEGEEQ_CACHE_MANAGEMENT_UI_IMPLEMENTATION_PLAN.md` (phase U11), `docs/design/PEEGEEQ_CACHE_MANAGEMENT_UI_DESIGN.md` §3.1/§8, `docs/guidelines/PEEGEEQ_CACHE_TEST_COVERAGE_AND_TDD_APPROACH.md` §5

## 1. Why this work exists

A review on 3 September 2026 compared this module against the reference `peegeeq-management-ui`. The console delivered in U1-U10 implemented every designed page, but not with the mandated stack: `antd`, `@reduxjs/toolkit`, `react-redux` and `recharts` were declared and never imported; every table, form, dialog, chart and control was hand-written CSS/JSX; pages received `*ClientPort` objects as props so tests could inject hand-built fakes; two tests replaced `fetch`; the static gates promised in plan §6.1 were mostly absent; and the plan's status line claimed a U11 that did not exist.

**Decision (Mark, 3 September 2026): full parity with `peegeeq-management-ui`. Clone its patterns, including its resolved dependency versions. Hand-rolled controls and test doubles of any kind are prohibited. Do not deviate from the reference's patterns or stop to ask about them.** The "document the deviation" option was explicitly rejected.

## 2. What is done

### U11.0 — directives and guard gates (RED recorded)
- Mandates written into the UI design doc §3.1/§8.1, the testing standard §5, the plan §4.1/§5/§6, and the guidelines index. Design-doc route `/cache-setups` corrected to `/setups`.
- `test/quality/` added: `source-scan.ts` (comment-masking scanner) and five guards — `component-library` (antd/Recharts only, no raw control markup, no unused declared deps, `Modal.tsx` must be gone), `no-test-fakes` (no `vi.mock/stubGlobal/fn/spyOn/useFakeTimers`, no `*ClientPort` fakes, no client props on pages), `no-direct-transport` (transport only in `src/api`; no axios; no `fetchBaseQuery`), `zod-fixture` (every served body from `*Schema.parse` or `invalidBody(...)`; every page test uses `test/support/loopback-server`), `sensitive-dto` (reveal types/secrets never in stores, RTK slices, persistence, URL builders, logging).
- `@vitest/coverage-v8` pinned with 80 percent branch thresholds on `src/api/**` and `src/state/**`; `axe-core` removed from the UI package; `restoreMocks` removed; Maven `frontend-test` and `npm run verify` run `test:coverage`; README documents the guards.
- RED evidence recorded in the plan: 9 guard assertions fail on the U10 tree; `no-direct-transport` and `sensitive-dto` already green; `src/api` branches 79.09 percent (< 80).

### U11.1 — Redux store and RTK Query foundation (GREEN)
- `src/store/`: `store.ts` (`createManagementStore(clients)`, thunk `extraArgument` = typed clients), `api/managementApi.ts` (one `createApi`, reducer path `managementApi`, shared tag universe), `api/apiBase.ts` (base query delegating to `SessionClient.requestJson`, serialisable `ManagementQueryError`), per-family `injectEndpoints` modules `setupsApi`, `inspectionApi`, `entriesApi`, `resourcesApi`, `pubSubApi` (every endpoint is a `queryFn` calling the existing typed client, so Zod parsing/CSRF/preconditions stay in one place), `clients.ts`, `clients-context.ts` + `ManagementProvider.tsx` (`useManagementClients()` for reveal paths only), `index.ts`.
- Reveal operations and the backend-capability client are deliberately not RTK endpoints (sensitive-dto guard enforces).
- `App` creates one store per session, wraps the shell in `ManagementProvider`, and `resetApiState()` on every session teardown.
- `test/support/loopback-server.ts`: real `node:http` fixture with `respond.json/problem/noContent/raw/sse`, request recording, `use()` handler swap, `invalidBody()`.
- `test/store.test.ts` 6/6 (cache, hook-from-cache, problem→error, contract violation→502, invalidation + CSRF header on wire, no reveal endpoints and no password/CSRF in Redux state).

### U11.2 — shell (GREEN in UI module; Java Playwright gate not yet run)
- `ManagementShell` = antd `ConfigProvider` (light/dark algorithm from preferences) + `Layout`; dark `Sider` (`aria-label="Primary navigation"`, custom collapse `Button`) with `<nav aria-label="Management sections">` + `Menu` of react-router `Link`s; `Layout.Header` with `role="group"` status cluster (`ConnectionStatus` = antd `Badge`, `Tag` role badge, theme/notification/session `Button`s with icons); `Layout.Content id="main-content"`; session problem as antd `Alert`; capability lookup via `useGetSetupCapabilitiesQuery`; live-connection/notification state in `src/state/live-store.ts` (Zustand).
- Notifications are an antd **`Drawer`** exactly as in the reference `Header.tsx` (title, `extra` Clear button, `Empty`, `List`), with `aria-label="Notifications"` on the Drawer and an inner `<aside aria-label="Notifications">` holding the `Close notifications` button that the Java suite addresses.
- `App` login/connecting/unavailable gates on antd `Card/Form/Input.Password/Button/Spin/Alert`; `ErrorBoundary` on antd `Result`; `components/common/ConnectionStatus.tsx`; shell rules removed from `foundation.css`; `test/setup.ts` gained `matchMedia`/`ResizeObserver` shims (browser API polyfills, same as the reference's `vitest.setup.ts`).
- jsdom axe scan of the shell: zero violations closed and open.

### Dependency alignment to the reference's resolved versions (done and synced)
- `antd` 5.12.8 → **5.26.5**, `@ant-design/icons` **5.6.1** (declared explicitly), `react`/`react-dom` 18.2.0 → **18.3.1**, `@types/react` **18.3.23**, `@types/react-dom` **18.3.7**. This is what `peegeeq-management-ui/package-lock.json` resolves to. It is what allows the accessible `Drawer` (rc-drawer 7.3.0 forwards `aria-*`).
- Retained as deliberate, documented advances beyond the reference (plan §4.1): Node 22.22.2/npm 10.9.4, ESLint 10, jsdom 30, TypeScript 5.7.2, Vite 6.4.3, Vitest 3.2.7, react-router-dom 7.18.2 (security bump).

## 3. Current gate numbers (sandbox, after U11.2 + dependency alignment)
- `npm run quality` (tsc + eslint `--max-warnings 0`): clean.
- `vite build`: green (bundle ~1.19 MB / 368 kB gzip with antd).
- Vitest: 37 files / 152 tests; 9 failures, all in `test/quality` and all expected until later slices (component-library: 15 feature-page files without antd, `Modal.tsx` present, `recharts` declared but unused; no-test-fakes: `vi.stubGlobal`/`vi.fn` in three legacy tests, fake classes in nine page tests, `*ClientPort` seams in `src/api` and `client:` props on pages; zod-fixture: 12 page tests not yet on the loopback fixture, 13 legacy client-test bodies not from `Schema.parse`). Re-verified after the Drawer switch and dependency alignment.
- Coverage: `src/api/**` branches 77.3 percent (threshold 80; closes in U11.8).
- **Java Playwright (550 scenarios in `peegee-cache-rest`): NOT run since U11.2.** Must be run on Windows: `mvn -pl :peegee-cache-management-ui,:peegee-cache-rest verify` after a one-time `npm ci` in the UI module.

## 4. Sync state between the sandbox and the checkout

Work is done in a cloud sandbox copy at `/root/work/peegee-cache-management-ui` and synced back as tarballs into `peegee-cache-management-ui/target/`, then extracted over the checkout.

**At the end of this session the checkout at `C:\Users\markr\dev\java\corejava\peegeeq-cache` matches the sandbox.** Change sets applied, in order: `u11-0-changes.tgz` (guards, coverage, pom, lockfile), `u11-1-changes.tgz` (store, loopback fixture, App wiring), `u11-2-changes.tgz` (antd shell, first cut with a Sider notifications panel), `u11-2b-drawer-and-deps.tgz` (reference `Drawer` pattern, antd 5.26.5 / icons 5.6.1 / React 18.3.1 lockfile, `live-store` `clearNotifications`/`reset` split, CSS clean-up). The plan's U11.2 evidence block and §4.1 record the Drawer pattern and the dependency alignment.

Before the next build on Windows run `npm ci` once in the UI module (the lockfile changed twice). The transfer tarballs in `peegee-cache-management-ui/target/` are build output and safe to delete; the mount would not let this session delete them.

Residual state a reader should know about:
- `ManagementShell` still accepts an optional, unused `sessionClient` prop and still hands `client=` props (from `useManagementClients()`) to every page. Both go away as the pages migrate (U11.3-U11.7) and the seams are deleted (U11.8); the `no-test-fakes` guard stays RED until then.
- `src/components/Modal.tsx` and the control-level rules in `foundation.css` are still used by the un-migrated pages; the `component-library` guard lists them until U11.9.
- `test/app.test.tsx` renders the shell inside `ManagementProvider` but not yet against the loopback fixture; the `zod-fixture` guard lists it with the other page tests until U11.8.

## 5. Next slice in progress: U11.3 Overview and Monitoring
Reference pieces to clone (read from `peegeeq-management-ui/src`): `components/common/StatCard.tsx` (Card + Statistic), `SetupScopeBar.tsx` (Row/Col + Select), `FilterBar.tsx`, `ConfirmDialog.tsx` (`Modal.confirm`), `pages/Overview.tsx` (Row/Col of StatCards, Card + Table, Recharts `ResponsiveContainer/AreaChart`, Alert, Reload button).

Planned implementation:
- `src/components/common/StatCard.tsx` with `className="metric-card"`; `SetupScopeBar.tsx` driven by `useListSetupsQuery` + `useLazyGetSetupCapabilitiesQuery` writing into the Zustand scope store (label the select "Setup scope").
- `OverviewPage` on `useGetOverviewQuery`, `useGetDatabaseMonitoringQuery`, `useGetRuntimeMonitoringQuery`, `useGetActivityQuery` with `pollingInterval` from `preferences.refreshSeconds`; trend points kept in local state; `SessionTrendChart` → Recharts `AreaChart` in a `Card`, wrapper keeps `aria-label="Current-session cache row trend"` and the "N snapshots" text.
- `OverviewMonitoring` → `Card` + `Descriptions` + `Table`; `MonitoringPage` → same hooks + existing `MetricsSseTransport`, calling `refetch()` on SSE events.
- Keep `<section aria-labelledby>` wrappers around Cards so the Java `section(page, heading)` helpers keep working.

**Java locator changes required in the same change set** (`peegee-cache-rest/src/test/java/dev/mars/peegeeq/cache/rest/server`):
- `ManagementOverviewBrowserIT.metric()`: `article.metric-card` → `.metric-card`; the value assertion `card.locator("strong")` → `card.locator(".ant-statistic-content")`.
- `ManagementConsoleProductJourneysIT.metric()` already uses `.metric-card` (fine); its `assertViewportSurfacesContained` selector list references `.console__sidebar` which no longer exists (harmless, but update to `.ant-layout-sider`).
- `ManagementMonitoringBrowserIT.detail/detailRow/assertDetailValue`: `dl > div` / `dt` / `dd` → antd `Descriptions` (`.ant-descriptions-row`, `.ant-descriptions-item-label`, `.ant-descriptions-item-content`). Only this file uses `dl/dt/dd`.
- `ManagementOverviewBrowserIT` `section(...).containsText("SweeperEnabled")`-style assertions concatenate label+value; verify they still hold with `Descriptions` (th/td adjacency) — otherwise assert label and value separately.

Remaining slices after U11.3: U11.4 setups/namespaces (`Table/Form/Modal/Tag`, `ConfirmDialog`, `FilterBar`), U11.5 entries, U11.6 counters/locks/advanced (`InputNumber stringMode` for 64-bit), U11.7 pub/sub/settings, U11.8 page tests onto the loopback fixture + delete `*ClientPort` seams + close coverage, U11.9 delete `foundation.css` control rules and `Modal.tsx`, record totals, full reactor + PostgreSQL 15-18 matrix.

## 6. Browser-suite locator contract to preserve (derived from the Java ITs)
- Navigation: `nav[aria-label="Management sections"]` with exact-name links `Overview, Setups, Namespaces, Keys, Counters, Locks, Pub/Sub, Monitoring, Advanced, Settings`; page headings incl. `Key Browser`; keyboard focus + Enter on links must navigate.
- Header: `[aria-label="Session and connection status"]` containing exact texts `Connected` / `Live` / `Live stale` / `Connecting`, user name, `Operator` / `Viewer`; buttons `Use dark theme` / `Use light theme`, `Open notifications` / `Close notifications` with `aria-expanded`, `End local session`; `[data-theme=dark]` when dark.
- Notifications: `complementary` landmark named exactly `Notifications` containing `Close notifications`.
- Login: heading `Connect to management console`, label `Bootstrap token`, button `Connect`, text "Enter the one-time bootstrap token printed by the local management server.", `role=alert` containing problem codes; no `navigation` landmark on the login page.
- Overview: `.metric-card` filtered by label (`Cache schema storage` contains `KiB`), `getByLabel("Snapshot observed at")` `<time>`, `getByLabel("Current-session cache row trend")` containing "N snapshots", `Refresh overview` button, `section` ancestors of headings `Expiry and cleanup`, `Entry value types`, `Namespace overview`, `Database monitoring`, `Management runtime`, `Recent activity`, `getByRole(ROW)` filtered by namespace name, text "Values are database-wide unless a panel is explicitly labelled management-server-local."
- Monitoring: `Live metrics connected` exact text, `Refresh monitoring` button, detail rows by label (see locator change above).
- Accessibility IT: axe scan on every route at 1440x900, `region` named `Counters results` (focusable, horizontally scrollable at 200 percent zoom), dialog `Manage count` with focus trap/Escape/restore, `Manage lease`, and many named dialogs/buttons listed in the plan.
- No `data-testid` attributes are introduced; the Java suite locates by role and label only.

## 7. How the gates are run from a Cowork session
- The linked-computer shell is a Linux VM with the Windows `node_modules` mounted (rollup win32 binary) and no Docker — it cannot run Vitest or the Playwright ITs.
- Sandbox recipe: from the checkout, `tar` the module (`package.json`, `package-lock.json`, `.npmrc`, `tsconfig.json`, `vite.config.ts`, `vitest.config.ts`, `eslint.config.js`, `index.html`, `pom.xml`, `src`, `test`, `target/generated-sources/openapi/management.ts`) plus `peegee-cache-rest/src/main/openapi/peegeeq-cache-management-v1.yaml` into `peegee-cache-management-ui/target/`, stage it, extract under `/root/work`, run `npx -y npm@10.9.4 ci` (the `engines` pin is exact), then `npx -y npm@10.9.4 run quality|test:coverage|build`.
- Sync back: `tar` the changed files → `SendUserFile` → `device_commit_files` into `target/` → extract into a scratch dir and `cat` each file over the checkout (the mount refuses `unlink`/rename).
- The Java Playwright suite is Windows-only for now (Docker + JDK 25/26): `mvn -pl :peegee-cache-management-ui,:peegee-cache-rest verify`.
- The working tree has pre-existing CRLF/LF noise; use `git diff --ignore-cr-at-eol` to see real changes.

## 8. Open items for Mark
1. `npm ci` in `peegee-cache-management-ui`, then run the Windows Failsafe gate for U11.2: `mvn -pl :peegee-cache-management-ui,:peegee-cache-rest verify`. Report failing scenario ids and assertion text, if any; the shell was built to the locator contract in §6, so 550/550 is the expectation.
2. Delete `peegee-cache-management-ui/target/*.tgz`.
3. Optional: commit U11.0-U11.2 as one change set (a commit message was drafted in the session) before U11.3 starts editing Java ITs.
