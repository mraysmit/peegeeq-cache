# PeeGeeQ Cache Capability Gating Removal Plan (2026-09-04)

> **Archived 11 September 2026.** Implementation and verification completed on 10 September 2026. This document is retained as the historical decision and execution record; it is not an active plan.

Revised 2026-09-10 after a line-by-line verification against the working tree. The revision adds the library runtime module, the Maven scenario property, `SetupRegistryTest`, the strict-schema phase ordering, and corrects file names, line numbers, and counts. The decision in section 1 is unchanged except for the last sentence.

## 1. Decision

Remove the management capability gating mechanism end to end. Role checks remain the only authorization gate. Per-setup limits and the Pub/Sub flag move to the setup details response, because the UI needs them as data. `PeeGeeCache.management()` is removed from the library facade because no production code reads it.

## 2. Why

The mechanism advertises, per setup, which management operations the browser may offer. It is dead and dangerous.

- **It computes a constant.** `ManagementServerApplication` always opens the audit journal and only assembles the server inside the success continuation of that open. The audit sink passed to `PostgresSetupRuntimeFactory` is therefore never null, so the read-only branch at `PostgresSetupRuntimeFactory.java:141` is reachable only from tests. Every production setup advertises all thirteen enum values, every time. The six REST-only booleans on `ManagedSetupRuntime` all return `true` in `PostgresManagedSetupRuntime`, the only production implementation. The interface defaults return `false`, and only test fakes inherit them.
- **The server does not enforce what it advertises.** No request handler calls `AdminCapabilities.supports()`. The only callers are the fourteen inside `SetupCapabilities.from`, which build JSON. A client that ignores the advertised list is not refused. No test asserts that an operation advertised as unavailable is refused over HTTP.
- **The same list is hand-copied in three places.** The Java enum, a nineteen-boolean positional record in `SetupCapabilities`, and a strict Zod schema in `setup-schemas.ts`. Nothing checks they agree. A missing or extra JSON field causes the browser to reject the response and `ManagementShell` then clears the stored setup with no message, so the console becomes unusable silently.
- **It negotiates between two halves of one artifact.** The UI is a Maven dependency of the REST server (`peegee-cache-rest/pom.xml`, the first dependency). Client and server cannot be at different versions.
- **Session features are constant too.** `setupRegistration` and `sensitiveReveal` are hardcoded `true` in both `LocalSessionRoutes` and `TrustedProxySessionRoutes`. The UI checks values that cannot be false.
- **The typed exception is not mapped.** `ManagementCapabilityException` has no branch in `ManagementProblem` and reaches the 500 fallback.
- **`BULK_DELETE`** is never produced by any implementation. `PgManagementService` adds only `ENTRY_BULK_DELETE` and `COUNTER_BULK_DELETE`.
- **The library facade accessor has no production caller.** The REST layer constructs `PgManagementService` inside `PostgresManagedSetupRuntime` and reaches it through `ManagedSetupRuntime.management()`. `PeeGeeCache.management()` is read by exactly two tests. The library runtime (`PgPeeGeeCacheManager`) always supplies `UnsupportedManagementService` because it has no audit sink, fingerprinter, or mutation repository to build anything else.

## 3. Scope

### In scope

- `ManagementCapability`, `AdminCapabilities`, `ManagementCapabilityException`, `UnsupportedManagementService`, `ManagementService.capabilities()`, and the interface default methods that fail with a capability exception.
- `PeeGeeCache.management()` and the seven-argument `PgPeeGeeCache` constructor that carries a management service.
- `SetupCapabilities`, the `GET /api/v1/setups/{setupId}/capabilities` route, the `capabilities` block on the connection-test response, the `capabilitySourceFilter` test hook, and the six `supports*()` methods on `ManagedSetupRuntime`.
- The read-only constructors of `PostgresManagedSetupRuntime` and `PgManagementService`, and the null-repository guards they exist for.
- The UI capability snapshot in the scope store, the `gated()` route wrapper, the `CapabilityPending` and `CapabilityUnavailable` components, the `can*` props derived from capabilities, the capabilities query and cache tag, and the capability list and capability fetch on the Setups page.
- Session features `setupRegistration` and `sensitiveReveal` in REST, OpenAPI, and UI.
- The eighteen `PW-CAPABILITY-*` browser scenarios, the scenario count assertion, and the Maven `peegeeq.playwright.expectedScenarios` property.
- Design documents and the OpenAPI specification.

### Out of scope, do not delete

- `BackendCapabilityRoutes`, `backend-capability-client.ts`, `backend-capability-schemas.ts`, and `AdvancedOperationsPage`. Despite the name, these are the real batch, scan, metrics, and owner-lock endpoints. Only their `canBatch`, `canScan`, `canMetrics`, and `canOwnLocks` props go.
- `ManagementLimits`. It is configuration and is used by `ManagementServerConfiguration`. Only its use inside `AdminCapabilities` and `PgManagementService` goes.
- `ManagementService` itself and its implementation `PgManagementService`. Only the capability surface inside them goes.
- The six-argument `PgPeeGeeCache` constructor. It is the one `PgPeeGeeCacheManager` in `peegee-cache-runtime` calls, and it becomes the only constructor.
- Role checks. There are twenty in the REST layer and they stay.
- Audit sink, fingerprinter, and `ManagementActionContext`. These are unrelated to gating.

## 4. Data that must survive

| Value | Current source | New source |
|---|---|---|
| `pubSubEnabled` | Setup details `runtime.pubSubEnabled` and capability flag `pubSub` | Setup details only, already written by `SetupReadRoutes.details()` |
| `pubSubChannelMaxBytes` | `SetupCapabilities.withRuntimeConfiguration` computes `63 - prefixBytes - 2` | New `SetupLimits` record computed from `SetupRuntimeConfiguration` and exposed on setup details |
| `pubSubPayloadMaxBytes` | `SetupCapabilities.Limits` constant `7_500` | `SetupLimits` |
| `maximumValueBytes` | `ManagementLimits.defaults()` via `AdminCapabilities` | `SetupLimits` from `ManagementServerConfiguration.limits()` |
| `migrationVersion` on the Settings page | Capability snapshot | Setup details, which already carries it |

`PubSubRoutes.java:304` and `:393` validate publish requests against the two Pub/Sub limits. That enforcement is real and must be repointed at `SetupLimits`, not deleted. `maximumValueBytes` is advertised only. Nothing in the PG or REST main code enforces it, and this plan does not add enforcement.

## 5. File inventory

### Build (1 file)

| File | Action |
|---|---|
| `peegee-cache-rest/pom.xml` | Change `peegeeq.playwright.expectedScenarios` at line 26 from 557 to 539 |

### Java main (23 files)

| File | Action |
|---|---|
| `peegee-cache-api/.../management/ManagementCapability.java` | Delete |
| `peegee-cache-api/.../management/AdminCapabilities.java` | Delete |
| `peegee-cache-api/.../management/ManagementCapabilityException.java` | Delete |
| `peegee-cache-api/.../management/UnsupportedManagementService.java` | Delete |
| `peegee-cache-api/.../management/ManagementService.java` | Remove `capabilities()`. Make `overview()`, `databaseMonitoring()`, `namespace()` abstract. Rewrite Javadoc. |
| `peegee-cache-api/.../PeeGeeCache.java` | Delete `management()` and its default. Remove the `UnsupportedManagementService` import. |
| `peegee-cache-pg/.../PgPeeGeeCache.java` | Delete the seven-argument constructor, the `managementService` field, and `management()`. Keep the six-argument constructor and drop its delegation. Remove the `ManagementService` and `UnsupportedManagementService` imports. |
| `peegee-cache-runtime/.../bootstrap/PgPeeGeeCacheManager.java` | No change. Listed because line 116 is the only production caller of the surviving constructor. |
| `peegee-cache-pg/.../management/PgManagementService.java` | Delete the read-only constructor at line 44, `INSPECTION_CAPABILITIES`, `READ_ONLY_CAPABILITIES`, `capabilities` field, `capabilities()`, `unavailable()`, all thirteen `mutationRepository == null` guards, all four `bulkDeletes == null` guards. Make `mutationRepository`, `auditSink`, `auditFingerprinter`, `auditClock`, `auditEventIdSupplier`, `bulkDeletes` non-null. |
| `peegee-cache-rest/.../server/SetupCapabilities.java` | Delete. Create `SetupLimits` record with the three limits and the channel-byte formula. |
| `peegee-cache-rest/.../server/SetupRegistry.java` | Delete `capabilities(setupId)` at line 259, `capabilities(runtime, config)` at line 270, the `capabilitySourceFilter` field and constructor parameter, and the `UnaryOperator.identity()` default in the two-argument constructor at line 43. Add `limits(setupId)` returning `SetupLimits`. Replace `supported.features()` and `supported.limits()` in `connectionTest()` at lines 126 to 127. |
| `peegee-cache-rest/.../server/SetupReadRoutes.java` | Delete the capabilities route handler at line 57 and `capabilities(SetupCapabilities)` at line 121. Add `limits` object to `details()` at line 78. |
| `peegee-cache-rest/.../server/SetupMutationRoutes.java` | Delete `putFeatures` and the `capabilities` object at line 415. Keep the `limits` object at 416 to 419, sourced from `SetupLimits`. |
| `peegee-cache-rest/.../server/SetupConnectionTest.java` | Remove `SetupCapabilities.Features capabilities`. Change `limits` type to `SetupLimits`. |
| `peegee-cache-rest/.../server/PubSubRoutes.java` | Replace `registry.capabilities(setupId).limits()` at lines 304 and 393 with `registry.limits(setupId)`. |
| `peegee-cache-rest/.../server/ManagedSetupRuntime.java` | Delete the six `supports*()` default methods at lines 44 to 66. |
| `peegee-cache-rest/.../server/PostgresManagedSetupRuntime.java` | Delete the read-only constructor at line 42. Delete the six `supports*()` overrides at lines 163 to 191. |
| `peegee-cache-rest/.../server/PostgresSetupRuntimeFactory.java` | Delete `administrationEnabled` logic at lines 78 to 85. Require non-null audit sink, fingerprinter, clock, and event id supplier. Delete the ternary at line 141 and call the full constructor unconditionally. |
| `peegee-cache-rest/.../server/ManagementServerApplication.java` | Delete `capabilitySourceFilter` parameter from both overloads and the `UnaryOperator.identity()` at line 71. |
| `peegee-cache-rest/.../server/ManagementRouteTemplate.java` | Delete the capabilities route at line 21. |
| `peegee-cache-rest/.../server/ManagementM7RouteInventory.java` | Delete `getSetupCapabilities` at line 29. |
| `peegee-cache-rest/.../server/LocalSessionRoutes.java` | Delete `features` object at lines 134 to 136. |
| `peegee-cache-rest/.../server/TrustedProxySessionRoutes.java` | Delete `features` object at lines 75 to 77. |

### OpenAPI (1 file)

`peegee-cache-rest/src/main/openapi/peegeeq-cache-management-v1.yaml`

- Delete `getSetupCapabilities` from the operation list at line 19.
- Delete the path at line 239.
- Delete the response at line 1700 and the schema at line 2133.
- On the connection-test schema at line 2171, remove `capabilities` from `required` and from properties. Change `limits` at line 2181 to reference a new `SetupLimits` schema.
- Add `limits` to the setup details schema at line 2182 referencing `SetupLimits`.
- Delete `features` from the session schema at lines 2033 to 2039.

### Java test (21 files)

| File | Action |
|---|---|
| `peegee-cache-api/.../ManagementServiceFallbackTest.java` | Delete |
| `peegee-cache-api/.../ManagementMetadataModelsTest.java` | Remove the `AdminCapabilities` case at line 190. Keep the `ManagementLimits` validation case at line 196. |
| `peegee-cache-pg/.../PgPeeGeeCacheTest.java` | Delete the `MANAGEMENT` constant at line 50, the seventh constructor argument at line 54, and the `management()` assertion at line 63 |
| `peegee-cache-pg/.../PgManagementMutationRepositoryTest.java` | Remove read-only and capability-exception cases. Delete the `capabilities().supports(...)` assertions inside live mutation cases at lines 1043 and 1176. |
| `peegee-cache-rest/.../SetupCapabilitiesTest.java` | Delete. Add `SetupLimitsTest` for the channel-byte formula. |
| `peegee-cache-rest/.../SetupRegistryTest.java` | Delete the twelve `result.capabilities()` assertions at lines 45 to 56 in `connectionTestReportsCapabilitiesFromTheTemporaryRuntime`. Rename the case to assert `limits()` instead. |
| `peegee-cache-rest/.../ManagementCapabilityBrowserIT.java` | Delete |
| `peegee-cache-rest/.../ManagementBrowserSelection.java` | Remove the three references at lines 71, 97, 136 |
| `peegee-cache-rest/.../ManagementBrowserSelectionTest.java` | Remove capability scenario assertions |
| `peegee-cache-rest/.../ManagementBrowserCoverageTest.java` | Change `CURRENT_SCENARIO_COUNT` at line 18 from 557 to 539 |
| `peegee-cache-rest/.../ManagementConsolePostgresFixture.java` | Delete `capabilitySourceFilter` parameter at lines 150, 159, 230, 272, 287 |
| `peegee-cache-rest/.../PostgresSetupRuntimeFactoryTest.java` | Remove read-only runtime cases. Add one case that a null audit sink is rejected at construction. |
| `peegee-cache-rest/.../SetupReadRoutesTest.java` | Remove capabilities endpoint cases. Add details `limits` assertions. |
| `peegee-cache-rest/.../openapi/ManagementOpenApiContractTest.java` | Remove entries at lines 525 and 588 |
| `peegee-cache-rest/.../openapi/BackendFunctionalityInventoryTest.java` | Remove the `ManagementService.capabilities` entry at line 74 |
| `peegee-cache-rest/.../ManagementBrowserOperationTrace.java` | Remove the capabilities entry at line 31 |
| `peegee-cache-rest/.../ManagementConsoleProductJourneysIT.java` | Remove capability panel and session feature assertions |
| `peegee-cache-rest/.../ManagementShellBrowserIT.java` | Same |
| `peegee-cache-rest/.../ManagementSetupBrowserIT.java` | Same |
| `peegee-cache-rest/.../ManagementMonitoringBrowserIT.java` | Same |
| `peegee-cache-rest/.../ManagementLiveTransportBrowserIT.java` | Same |

`SetupMutationRoutesTest`, `BackendCapabilityRoutesTest`, and `PubSubRoutesTest` carry inline `ManagedSetupRuntime` fakes. None of them override `supports*()` or reference capabilities, so they compile unchanged.

### UI source (17 files)

| File | Action |
|---|---|
| `src/api/setup-schemas.ts` | Delete `capabilityFlagsSchema`, `capabilityLimitsSchema`, `setupCapabilitiesSchema`, `SetupCapabilities`. Add `setupLimitsSchema`. Add `limits` to `setupDetailsSchema`. Remove `capabilities` from the connection-test schema at line 78 (Phase 3, see section 6). |
| `src/api/openapi-contract.ts` | Delete `SetupCapabilitiesContract`. Regenerate from the updated YAML. |
| `src/api/protocol-schemas.ts` | Delete `features` from `currentSessionSchema` at lines 26 to 29 (Phase 3, see section 6) |
| `src/api/setup-client.ts` | Delete `capabilities()` at lines 27 and 60 |
| `src/api/operation-manifest.ts` | Delete `getSetupCapabilities` at line 56. No Java test reads this manifest, so the deletion is independent of the YAML. |
| `src/store/api/setupsApi.ts` | Delete `getSetupCapabilities` at line 28. Delete the `Capabilities` invalidation at line 45. |
| `src/store/api/managementApi.ts` | Delete `Capabilities` tag at line 17 |
| `src/state/scope-store.ts` | Delete `capabilities` field at lines 13 and 24. Change `select` at line 14 to `(setupId) => void`. |
| `src/components/common/SetupScopeBar.tsx` | Delete `useLazyGetSetupCapabilitiesQuery` at lines 5 and 24 and the fetch at lines 35 to 38. Commit scope immediately on selection. |
| `src/app/ManagementShell.tsx` | Delete `capabilityForPath` at line 52, the `selectedCapabilities` read at line 77, `capabilityLookup` at lines 101 to 109, the `capabilities` parameter of `selectSetup` at line 117, `gated()` at 131, and `CapabilityPending` and `CapabilityUnavailable` at 365 to 380. Render routes directly. Set `canOperate`, `canReveal`, `canBulkDelete` to `isOperator`. Delete `canInspectExpired`, `canBatch`, `canMetrics`, `canScan`, `canOwnLocks`. Source `maximumChannelBytes` and `maximumPayloadBytes` from setup details. Delete `session.features` reads. |
| `src/features/settings/SettingsPage.tsx` | Replace the `capabilities` prop at line 21 with `limits` and `migrationVersion` from setup details. Lines 74 to 81 render both. |
| `src/features/setups/SetupsPage.tsx` | Delete the `capabilities` parameter of `onSelectSetup` at line 33, the `capabilities` member of the details state at line 96, the `loadCapabilities` call in the `Promise.all` at lines 133 to 138, the `capabilities` prop of `SetupDetailsView` at lines 435 and 473 to 476, the capability list at lines 508 to 520, and `humanizeCapability`. Keep the limits descriptions at lines 521 to 525, sourced from setup details. Delete `session.features.setupRegistration` at lines 108 and 263. |
| `src/features/entries/EntryDetailsPage.tsx` | Change the message at line 278 to "Operator permission is required." |
| `src/features/advanced/AdvancedOperationsPage.tsx` | Delete `canBatch`, `canMetrics`, `canScan`, `canOwnLocks` props and their conditionals |
| `src/features/pubsub/PubSubPage.tsx` | No change. Props are supplied by the shell. |
| `src/store/clients.ts` | No change. `backendCapability` is a real client. |
| `src/styles/foundation.css` | Delete `.capability-list` rules at lines 762, 770, 835 |

### UI test (22 files)

Whole cases are lost in `protocol-schemas.test.ts`, `setup-client.test.ts`, `scope-store.test.ts`, `setups-page.test.tsx`, `settings-page.test.tsx`, and `app.test.tsx`. The rest lose capability or session-feature fields from fixture objects:

`advanced-operations-page.test.tsx`, `backend-capability-client.test.ts` (session fixture at line 23 only), `counter-lock-pages.test.tsx`, `entries-page.test.tsx`, `entry-administration-client.test.ts`, `inspection-client.test.ts`, `monitoring-page.test.tsx`, `namespaces-page.test.tsx`, `overview-page.test.tsx`, `pubsub-live.test.ts`, `pubsub-page.test.tsx`, `resource-client.test.ts`, `session-client.test.ts`, `store.test.ts`, `support/entry-fixture.ts`, `support/resource-fixture.ts`.

### Documents (14 files)

| File | Action |
|---|---|
| `docs/design/PEEGEEQ_CACHE_MANAGEMENT_API.md` | Rewrite section 7.9. Remove `AdminCapabilities` and `ManagementCapability` from the Java contract in section 16. Remove the session `features` fields. |
| `docs/design/PEEGEEQ_CACHE_MANAGEMENT_UI_DESIGN.md` | Rewrite section 10.2. Delete the capability response example. Correct line 695. Delete "capability-based page/action visibility" at line 789. Update the scenario counts at line 26. |
| `docs/design/PEEGEEQ_CACHE_MANAGEMENT_OPERATION_MANIFEST.md` | Delete `getSetupCapabilities` and the session `features` fields |
| `docs/design/PEEGEEQ_CACHE_PLAYWRIGHT_IMPLEMENTATION_PLAN.md` | Delete the `PW-CAPABILITY` block. Update totals. |
| `docs/design/PEEGEEQ_CACHE_FUNCTIONALITY_COVERAGE_MATRIX.md` | Delete the `ManagementService.capabilities` row. Update the 557 and "18 independently degraded capability paths" figures at lines 62 to 70. Sections 5 to 7 use "capability" in a different sense and stay. |
| `docs/design/PEEGEEQ_CACHE_MANAGEMENT_API_IMPLEMENTATION_PLAN.md` | Mark capability tasks as removed. Update lines 942, 989, 998. |
| `docs/design/PEEGEEQ_CACHE_MANAGEMENT_UI_IMPLEMENTATION_PLAN.md` | Same. Update lines 3, 12, 307, 585. |
| `docs/design/PEEGEEQ_CACHE_MANAGEMENT_BUILD_DECISION.md` | Same |
| `docs/design/PEEGEEQ_CACHE_IMPLEMENTATION_PLAN.md` | Same. Update lines 587, 612, 746, 774. |
| `docs/design/PEEGEEQ_CACHE_DESIGN.md` | Remove the management capability paragraph |
| `docs/design/PEEGEEQ_CACHE_UI_U11_HANDOVER_2026-09-04.md` | Correct line 90, which says the scope store holds the capability snapshot. Add a note pointing to this plan. |
| `docs/design/PEEGEEQ_CACHE_CODE_REVIEW_2026-09-10.md` | Delete the "Capability Gating & Stubs" bullet in section 2A, which describes `UnsupportedManagementService` as a strength. |
| `docs/design/UI mockups/peegeeq-cache-management-ui-mockups.html` | Remove the capabilities panel mockup |
| `docs/guidelines/PEEGEEQ_CACHE_TEST_COVERAGE_AND_TDD_APPROACH.md` | Remove the one reference |

`docs/design/PEEGEEQ_CACHE_PRODUCTION_BENCHMARK_PLAN.md` matches the word "capability" at lines 92, 253, and 315 in the benchmark-inventory sense. It stays.

## 6. Phases

Each phase ends with a rebuild, a targeted test run, and a report of per-class `Tests run:` counts. Do not begin the next phase until the current one is green.

### Phase 0. Baseline

Record the current counts so the end state can be compared.

```powershell
mvn -q test -pl peegee-cache-rest -Dtest=ManagementBrowserCoverageTest 2>&1 | Tee-Object -FilePath logs\gating-removal-phase0.log
cd peegee-cache-management-ui; npm run test -- --run 2>&1 | Tee-Object -FilePath ..\logs\gating-removal-phase0-ui.log
```

Exit: coverage test reports 557 scenarios. UI test count recorded.

### Phase 1. Additive REST change

Add `SetupLimits` and expose it on setup details. Nothing is removed yet, so the UI keeps working.

1. Create `SetupLimits(int pubSubChannelMaxBytes, int pubSubPayloadMaxBytes, int maximumValueBytes)` in `peegee-cache-rest`. Move the `63 - prefixBytes - 2` formula and the `Limits` validation from `SetupCapabilities` into a static factory `SetupLimits.from(SetupRuntimeConfiguration, ManagementLimits)`.
2. Add `SetupRegistry.limits(setupId)`.
3. Write `limits` into `SetupReadRoutes.details()`.
4. Add `SetupLimits` schema to OpenAPI and reference it from the setup details schema.
5. Add `limits` to `setupDetailsSchema` in the UI. Regenerate `openapi-contract.ts`.

```powershell
mvn clean install -DskipTests -pl :peegee-cache-rest -am 2>&1 | Tee-Object -FilePath logs\gating-removal-phase1-build.log
mvn test -pl peegee-cache-rest -Dtest=SetupReadRoutesTest,ManagementOpenApiContractTest 2>&1 | Tee-Object -FilePath logs\gating-removal-phase1-test.log
```

Exit: details response carries `limits`. Contract test green.

### Phase 2. UI stops consuming capabilities

The UI stops calling the capabilities endpoint and stops reading session features. Two schema fields stay until Phase 3: `capabilities` on the connection-test schema and `features` on the session schema. Both schemas are `z.strictObject`, and the Phase 2 server still sends both fields. Deleting them here would make a Phase 2 UI reject every session bootstrap and every connection test from a Phase 2 server. They are ignored by code after this phase and deleted with the REST fields in Phase 3.

1. `scope-store.ts`: drop the capability snapshot.
2. `SetupScopeBar.tsx`: commit scope on selection without a fetch.
3. `ManagementShell.tsx`: delete `gated()`, the two placeholder components, the capability query, and the `session.features` reads. Derive `can*` props from `isOperator`. Read limits from the setup details query.
4. `SettingsPage.tsx`, `SetupsPage.tsx`, `EntryDetailsPage.tsx`, `AdvancedOperationsPage.tsx`: as listed in section 5.
5. `setup-client.ts`, `setupsApi.ts`, `managementApi.ts`, `operation-manifest.ts`, `foundation.css`: as listed in section 5.
6. `setup-schemas.ts`: delete `setupCapabilitiesSchema` and `SetupCapabilities`. Keep `capabilityFlagsSchema` and `capabilityLimitsSchema` only as long as the connection-test schema still needs them.
7. Update the test files that lose whole cases. Fixtures that only carry the two surviving fields are untouched until Phase 3.

```powershell
cd peegee-cache-management-ui
npx tsc --noEmit 2>&1 | Tee-Object -FilePath ..\logs\gating-removal-phase2-tsc.log
npm run lint 2>&1 | Tee-Object -FilePath ..\logs\gating-removal-phase2-lint.log
npm run test -- --run 2>&1 | Tee-Object -FilePath ..\logs\gating-removal-phase2-test.log
grep -rn "getSetupCapabilities\|GetSetupCapabilities\|gated(\|CapabilityPending\|CapabilityUnavailable\|session.features\|selectedCapabilities\|humanizeCapability\|capability-list" src test
```

Exit: type check, lint, and tests green. The grep returns nothing. A Phase 2 UI served by a Phase 2 server still logs in and runs a connection test.

### Phase 3. REST removal and UI schema clean-up

1. Delete the capabilities route, `SetupCapabilities`, `putFeatures`, the `capabilities` block on the connection test, the `capabilitySourceFilter` plumbing, the route inventory entries, and the session `features` object.
2. Repoint `PubSubRoutes` at `SetupRegistry.limits()`.
3. Update OpenAPI as listed in section 5.
4. Delete `SetupCapabilitiesTest` and `ManagementCapabilityBrowserIT`. Update `ManagementBrowserSelection`, `ManagementBrowserSelectionTest`, `ManagementBrowserCoverageTest`, `ManagementConsolePostgresFixture`, `ManagementBrowserOperationTrace`, `SetupRegistryTest`, the two OpenAPI tests, and `SetupReadRoutesTest`.
5. Update the five browser ITs that assert on the capability panel or session features.
6. Change `peegeeq.playwright.expectedScenarios` in `peegee-cache-rest/pom.xml` to 539.
7. UI: regenerate `openapi-contract.ts`. Delete `capabilities` from the connection-test schema, `features` from the session schema, and the two helper schemas. Remove the fields from every fixture in section 5. Rerun the Phase 2 UI gate plus the full grep.

```powershell
mvn clean install -DskipTests -pl :peegee-cache-rest -am 2>&1 | Tee-Object -FilePath logs\gating-removal-phase3-build.log
mvn test -pl peegee-cache-rest -Dtest="SetupReadRoutesTest,SetupRegistryTest,SetupLimitsTest,ManagementOpenApiContractTest,BackendFunctionalityInventoryTest,ManagementBrowserSelectionTest,ManagementBrowserCoverageTest" 2>&1 | Tee-Object -FilePath logs\gating-removal-phase3-test.log
cd peegee-cache-management-ui
npx tsc --noEmit 2>&1 | Tee-Object -FilePath ..\logs\gating-removal-phase3-tsc.log
npm run lint 2>&1 | Tee-Object -FilePath ..\logs\gating-removal-phase3-lint.log
npm run test -- --run 2>&1 | Tee-Object -FilePath ..\logs\gating-removal-phase3-test-ui.log
grep -rn "capabilit\|sensitiveReveal\|setupRegistration" src test | grep -v "backend-capability\|BackendCapability\|backendCapability\|backend capability"
```

Exit: coverage test reports 539 scenarios. All listed classes green. UI gate green. The grep returns nothing.

### Phase 4. Runtime and factory removal

1. Delete the read-only constructor and the six `supports*()` overrides in `PostgresManagedSetupRuntime`.
2. Delete the six defaults on `ManagedSetupRuntime`.
3. In `PostgresSetupRuntimeFactory`, require all four audit collaborators non-null and call the full constructor unconditionally.
4. Update `PostgresSetupRuntimeFactoryTest`.

```powershell
mvn clean install -DskipTests -pl :peegee-cache-rest -am 2>&1 | Tee-Object -FilePath logs\gating-removal-phase4-build.log
mvn test -pl peegee-cache-rest -Dtest=PostgresSetupRuntimeFactoryTest 2>&1 | Tee-Object -FilePath logs\gating-removal-phase4-test.log
```

Exit: factory test green, including the new null-audit-sink rejection case.

### Phase 5. PG, runtime, and API removal

1. `PgManagementService`: delete the read-only constructor and every null guard listed in section 5. Delete `unavailable()`.
2. `PgPeeGeeCache`: delete the seven-argument constructor, the `managementService` field, and `management()`. The six-argument constructor stays and `PgPeeGeeCacheManager` in `peegee-cache-runtime` compiles unchanged.
3. `PeeGeeCache`: delete `management()`.
4. `ManagementService`: delete `capabilities()`. Make the three defaults abstract.
5. Delete the four API types.
6. Update `ManagementMetadataModelsTest`, `PgPeeGeeCacheTest`, `PgManagementMutationRepositoryTest`. Delete `ManagementServiceFallbackTest`.

```powershell
mvn clean install -DskipTests 2>&1 | Tee-Object -FilePath logs\gating-removal-phase5-build.log
mvn test -pl peegee-cache-api,peegee-cache-pg,peegee-cache-runtime 2>&1 | Tee-Object -FilePath logs\gating-removal-phase5-test.log
grep -rn "ManagementCapability\b\|AdminCapabilities\|ManagementCapabilityException\|UnsupportedManagementService\|SetupCapabilities\|capabilitySourceFilter\|supportsPubSub\|supportsValueScan\|sensitiveReveal\|setupRegistration\|expectedScenarios>557" --include=*.java --include=*.yaml --include=pom.xml peegee-cache-api peegee-cache-pg peegee-cache-runtime peegee-cache-rest
```

Exit: the full reactor builds, including `peegee-cache-examples`, `peegee-cache-benchmarks`, and `peegee-cache-test-support`, none of which reference the management facade. API, PG, and runtime module tests green. The grep returns nothing.

### Phase 6. Documents

Apply section 5. Every count that names 557 scenarios or 18 degraded capability paths changes to 539 and 0.

Exit: `grep -rli "capabilit" docs` returns only the coverage matrix (sections 5 to 7, unrelated meaning), the production benchmark plan (unrelated meaning), and this plan.

### Phase 7. Full gate

```powershell
mvn clean install -DskipTests 2>&1 | Tee-Object -FilePath logs\gating-removal-phase7-build.log
mvn verify -pl peegee-cache-rest -Pbrowser 2>&1 | Tee-Object -FilePath logs\gating-removal-phase7-browser.log
```

Exit: 539 browser scenarios pass against real PostgreSQL, matching `peegeeq.playwright.expectedScenarios`. Per-class `Tests run:` counts reported.

## 7. Behaviour after removal

- A viewer sees every page and no write control.
- An operator sees every page and every write control.
- The expired-entry status filter, batch panels, value scan, core metrics, and owner lock operations are always offered. They were only ever hidden by test-injected capability filters.
- A request the server cannot fulfil fails with the existing problem codes. There is no advertised list to drift from.
- A setup whose pool is lost reports through health, as today.
- Library consumers of `PeeGeeCache` lose a `management()` accessor that only ever returned an unsupported stub.
- Adding an operation touches the route, the client, and the page. Nothing else.

## 8. Not checked

- Whether any external consumer calls `GET /setups/{setupId}/capabilities` or `PeeGeeCache.management()`. The UI is the only known client of the first. Nothing in this repository outside two tests calls the second.
- Whether `ManagementServerConfiguration.limits().maximumValueBytes()` matches `ManagementLimits.defaults()` in every deployment profile. Phase 1 makes configuration the source; confirm the value on the first deployment.
- Runtime verification of the 500 fallback for `ManagementCapabilityException`. The mapping was read, not executed. It becomes irrelevant after Phase 5.

## 9. Execution record (10 September 2026)

Executed on the Windows workstation against the working tree at `ff2a1c7`. Every deviation from sections 5 and 6 is listed here.

### Phase 0

- Coverage test green at 557 scenarios. The plan's Maven command used `-q`, which hides the Surefire summary; the count was read from the Surefire report.
- The UI script is `npm run test:run`, not `npm run test`.
- The UI suite ran 170 tests with 4 failures before any change: the metrics-stream case in `monitoring-page.test.tsx`, the SSE lifecycle case in `pubsub-live.test.ts`, and two stream cases in `pubsub-page.test.tsx`. All four fail deterministically under the system Node 24, are unrelated to gating, and stayed identical in every later system-Node run. They are the baseline, not a regression. The complete reactor verify run afterwards (`logs/capability-gating-removal-verify-20260910.log`) executed the same suite through Maven under the module's pinned Node 22.22.2 and passed 168 of 168, so the failures are a Node 24 artifact of running Vitest outside Maven. Investigated afterwards: undici 7 (bundled from Node 24) rejects the jsdom `AbortSignal` that the SSE transport passes to `fetch`, because vitest's jsdom environment replaces the global `AbortController`/`AbortSignal` while `fetch` stays the runtime's. Fixed independently of the Node version by `test/support/jsdom-runtime-fetch-environment.ts`, which restores the runtime pair after jsdom setup; `vitest.config.ts` points at it. Logs: `logs/ui-sse-node24-20260910.log` (failing), `logs/ui-sse-fix-node24-20260910.log` (passing), `logs/ui-coverage-node24-20260910.log` and `logs/ui-coverage-node22-20260910.log` (full suites).

### Phase 1

- `SetupCapabilities.Limits` was replaced by `SetupLimits` here rather than in Phase 3, so `SetupConnectionTest`, `PubSubRoutes`, and the OpenAPI connection-test schema already referenced the new type and Phase 3 became a pure deletion.
- `SetupRegistry` gained a `ManagementLimits` constructor parameter; `ManagementServerApplication` passes `configuration.limits()`. Existing overloads default to `ManagementLimits.defaults()`.
- `mvn clean install -DskipTests` fails in the REST module's `verify-playwright-evidence` Ant check when no browser run has produced a report. Compilation had already succeeded. Intermediate phases used `-Dmaven.antrun.skip=true`; Phase 7 runs the real check.
- Gate: 38 Java tests green across the five listed classes; UI type-check and lint green; UI suite 166 of 170 with the four baseline failures.

### Phase 2

- Additional to section 5: the `canInspectExpired` prop was removed from `EntriesPage` itself, not only from the shell; the `selectingSetupId` state in `SetupsPage` went with the capability fetch; the shell reads limits through `useGetSetupDetailsQuery`.
- `operation-manifest.test.ts` compares the UI manifest with the OpenAPI operation list, which still contained `getSetupCapabilities` until Phase 3. That one test was red between Phases 2 and 3 and now expects 59 operations.
- Gate: type-check, lint, and the residual grep clean; UI suite 163 of 168 (the four baseline failures plus the manifest test above).

### Phase 3

- Additional to section 5: `ManagementBrowserSelectionTest` now selects from the counter catalogue; `ManagementBrowserRunConfigTest` used `PW-CAPABILITY-008` as a sample ID and now uses `PW-COUNTER-008`; `ManagementBrowserCoverageTest` also dropped the IT class and catalogue entries; the journey `scope-and-capabilities` became `scope-restoration` and owns `getNamespace` only, because `getSetup` already belongs to `setup-lifecycle` and the coverage test requires one owner per operation.
- The shell now fetches setup details on every selection, so `getSetup` replaced `getSetupCapabilities` in the fixture's allowed background traffic and in every scenario operation list that had declared it. `PW-SETUP-054` was rewritten to assert the runtime Pub/Sub row of the details dialog, keeping the scenario count at 539.
- The `PostgresSetupRuntimeFactoryTest` capability assertions were repointed at limits here because they blocked the Phase 3 compile.
- `BackendFunctionalityInventoryTest` cannot pass in Phase 3: it reflects over `ManagementService`, which keeps `capabilities()` until Phase 5, and maps that method to an operation the OpenAPI document no longer has. It was deferred to the Phase 5 gate.
- The IDE's Eclipse compiler had written classes with "Unresolved compilation problem" bodies into `target/test-classes`; `mvn clean test` cleared them.
- Gate: 45 Java tests green across 13 classes; UI type-check, lint, and the full grep clean; UI suite 164 of 168 with the four baseline failures.

### Phase 4

- One factory-test case used the four-argument constructor; it now supplies the audit collaborators. `rejectsMissingAuditCollaboratorsAtConstruction` was added.
- Docker Desktop was installed but stopped, which fails every Testcontainers test. It was started for Phases 4, 5, and 7.
- Gate: `PostgresSetupRuntimeFactoryTest` 4 of 4 against PostgreSQL.

### Phase 5

- `ManagedSetupRuntime.management()` had defaulted to the unsupported stub. It now throws `SetupRegistryException(409, SETUP_MANAGEMENT_UNAVAILABLE)`, matching the interface's `pubSub()` and `cache()` defaults. Test fakes that omit it are unaffected.
- `PgManagementReadRepositoryTest` used the read-only constructor and was missing from section 5. It now builds the full service with an inline audit sink.
- `BackendFunctionalityInventoryTest` denominator changed from 62 to 61. The `ManagementMetadataModelsTest` capability case became a `ManagementLimits` case.
- Gate: full 11-module reactor builds; `peegee-cache-api` 59 tests, `peegee-cache-pg` and `peegee-cache-runtime` 48 test classes green against PostgreSQL, factory test 4 of 4, inventory test 1 of 1; the section 6 grep returns nothing.

### Phase 6

- Fourteen documents were edited. Each affected design document opens with a dated note. Present-tense statements now say 539 scenarios, 59 operations, and 61 inventory methods. Dated acceptance records (the 2 and 5 September runs) keep the figures they were measured with; they are history, not current claims.
- The `CAPABILITY_UNAVAILABLE` problem code was removed from the API document and the manifest. `ManagementProblem` never produced it.
- The section 6 exit grep still matches the documents that hold historical narrative and the two that use the word in an unrelated sense. The dated note is the reader's guide; the narrative was not rewritten.

### Phase 7

- No `browser` Maven profile exists. The gate is `mvn verify -pl peegee-cache-rest`, which runs the REST Surefire tests, the Failsafe browser catalogue against Docker PostgreSQL, and the evidence check against `peegeeq.playwright.expectedScenarios` (539).
- Result: green in 24 minutes on Docker PostgreSQL. Surefire 179 tests, Failsafe 545 tests (539 browser scenarios plus one observation, two runnable-artifact, and three screenshot infrastructure checks), zero failures, errors, or skips; the evidence report was produced and the 539-scenario count gate passed. Log: `logs/gating-removal-phase7-browser.log`.

### Complete reactor verify (added after Phase 7)

- `mvn --batch-mode --no-transfer-progress clean verify` across all eleven modules, finished 2026-09-10T17:04:12+01:00 in 29:58, log `logs/capability-gating-removal-verify-20260910.log`: `BUILD SUCCESS`, every module `SUCCESS`, zero failures, errors, or skips. Per module: api 59, core 20, test-support 4, pg 243, runtime 48, observability 5, management-ui 36 files / 168 Vitest tests under the pinned Node 22.22.2 with coverage thresholds and production build green, rest Surefire 179 and Failsafe 545 (539 scenarios plus six infrastructure checks) with the evidence check passed, benchmarks 102.
