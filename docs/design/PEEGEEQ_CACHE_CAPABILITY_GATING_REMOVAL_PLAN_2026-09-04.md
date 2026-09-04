# PeeGeeQ Cache Capability Gating Removal Plan (2026-09-04)

## 1. Decision

Remove the management capability gating mechanism end to end. Role checks remain the only authorization gate. Per-setup limits and the Pub/Sub flag move to the setup details response, because the UI needs them as data.

## 2. Why

The mechanism advertises, per setup, which management operations the browser may offer. It is dead and dangerous.

- **It computes a constant.** `ManagementServerApplication` always opens the audit journal and fails startup if it cannot. The audit sink passed to `PostgresSetupRuntimeFactory` is therefore never null, so the read-only branch at `PostgresSetupRuntimeFactory.java:141` is reachable only from tests. Every production setup advertises all thirteen enum values, every time. The six REST-only booleans on `ManagedSetupRuntime` all return `true` in the only implementation.
- **The server does not enforce what it advertises.** No request handler calls `AdminCapabilities.supports()`. The only two callers of the capability set build JSON. A client that ignores the advertised list is not refused. No test asserts that an operation advertised as unavailable is refused over HTTP.
- **The same list is hand-copied in three places.** The Java enum, a nineteen-boolean positional record in `SetupCapabilities`, and a strict Zod schema in `setup-schemas.ts`. Nothing checks they agree. A missing or extra JSON field causes the browser to reject the response and `ManagementShell` then silently deselects the setup, so the console becomes unusable with no message.
- **It negotiates between two halves of one artifact.** The UI is packaged inside the REST server (`peegee-cache-rest/pom.xml:30`). Client and server cannot be at different versions.
- **Session features are constant too.** `setupRegistration` and `sensitiveReveal` are hardcoded `true` in both `LocalSessionRoutes` and `TrustedProxySessionRoutes`. The UI checks values that cannot be false.
- **The typed exception is not mapped.** `ManagementCapabilityException` has no branch in `ManagementProblem` and reaches the 500 fallback.
- **`BULK_DELETE`** is never produced by any implementation.

## 3. Scope

### In scope

- `ManagementCapability`, `AdminCapabilities`, `ManagementCapabilityException`, `UnsupportedManagementService`, `ManagementService.capabilities()`, and the interface default methods that fail with a capability exception.
- `SetupCapabilities`, the `GET /api/v1/setups/{setupId}/capabilities` route, the `capabilities` block on the connection-test response, the `capabilitySourceFilter` test hook, and the six `supports*()` methods on `ManagedSetupRuntime`.
- The read-only constructors of `PostgresManagedSetupRuntime` and `PgManagementService`, and the null-repository guards they exist for.
- The UI capability snapshot in the scope store, the `gated()` route wrapper, the `CapabilityPending` and `CapabilityUnavailable` components, the `can*` props derived from capabilities, the capabilities query and cache tag, and the capability list on the Setups page.
- Session features `setupRegistration` and `sensitiveReveal` in REST, OpenAPI, and UI.
- The eighteen `PW-CAPABILITY-*` browser scenarios and the scenario count assertion.
- Design documents and the OpenAPI specification.

### Out of scope, do not delete

- `BackendCapabilityRoutes`, `backend-capability-client.ts`, `backend-capability-schemas.ts`, and `AdvancedOperationsPage`. Despite the name, these are the real batch, scan, metrics, and owner-lock endpoints. Only their `canBatch`, `canScan`, `canMetrics`, and `canOwnLocks` props go.
- `ManagementLimits`. It is configuration and is used by `ManagementServerConfiguration`. Only its use inside `AdminCapabilities` goes.
- Role checks. There are twenty in the REST layer and they stay.
- Audit sink, fingerprinter, and `ManagementActionContext`. These are unrelated to gating.

## 4. Data that must survive

| Value | Current source | New source |
|---|---|---|
| `pubSubEnabled` | Setup details `runtime.pubSubEnabled` and capability flag `pubSub` | Setup details only, already present at `SetupReadRoutes.java:98` |
| `pubSubChannelMaxBytes` | `SetupCapabilities.withRuntimeConfiguration` computes `63 - prefixBytes - 2` | New `SetupLimits` record computed from `SetupRuntimeConfiguration` and exposed on setup details |
| `pubSubPayloadMaxBytes` | `SetupCapabilities.Limits` constant `7_500` | `SetupLimits` |
| `maximumValueBytes` | `ManagementLimits.defaults()` via `AdminCapabilities` | `SetupLimits` from `ManagementServerConfiguration.limits()` |

`PubSubRoutes.java:304` and `:393` validate publish requests against these limits. That enforcement is real and must be repointed at `SetupLimits`, not deleted.

## 5. File inventory

### Java main (17 files)

| File | Action |
|---|---|
| `peegee-cache-api/.../management/ManagementCapability.java` | Delete |
| `peegee-cache-api/.../management/AdminCapabilities.java` | Delete |
| `peegee-cache-api/.../management/ManagementCapabilityException.java` | Delete |
| `peegee-cache-api/.../management/UnsupportedManagementService.java` | Delete |
| `peegee-cache-api/.../management/ManagementService.java` | Remove `capabilities()`. Make `overview()`, `databaseMonitoring()`, `namespace()` abstract. Rewrite Javadoc. |
| `peegee-cache-api/.../PeeGeeCache.java` | Make `management()` abstract. Remove default. |
| `peegee-cache-pg/.../PgPeeGeeCache.java` | Delete the six-argument constructor. Remove `UnsupportedManagementService` import. |
| `peegee-cache-pg/.../management/PgManagementService.java` | Delete the read-only constructor, `INSPECTION_CAPABILITIES`, `READ_ONLY_CAPABILITIES`, `capabilities` field, `capabilities()`, `unavailable()`, all thirteen `mutationRepository == null` guards, all four `bulkDeletes == null` guards. Make `mutationRepository`, `auditSink`, `auditFingerprinter`, `auditClock`, `auditEventIdSupplier`, `bulkDeletes` non-null. |
| `peegee-cache-rest/.../server/SetupCapabilities.java` | Delete. Create `SetupLimits` record with the three limits and the channel-byte formula. |
| `peegee-cache-rest/.../server/SetupRegistry.java` | Delete `capabilities(setupId)`, `capabilities(runtime, config)`, `capabilitySourceFilter` field and constructor parameter. Add `limits(setupId)` returning `SetupLimits`. Remove `supported.limits()` at line 127. |
| `peegee-cache-rest/.../server/SetupReadRoutes.java` | Delete the capabilities route handler at line 57 and `capabilities(SetupCapabilities)` at line 121. Add `limits` object to `details()` at line 78. |
| `peegee-cache-rest/.../server/SetupMutationRoutes.java` | Delete `putFeatures` and the `capabilities` object at line 415. Keep the `limits` object at 417 to 419, sourced from `SetupLimits`. |
| `peegee-cache-rest/.../server/SetupConnectionTest.java` | Remove `SetupCapabilities.Features capabilities`. Change `limits` type to `SetupLimits`. |
| `peegee-cache-rest/.../server/PubSubRoutes.java` | Replace `registry.capabilities(setupId).limits()` at lines 304 and 393 with `registry.limits(setupId)`. |
| `peegee-cache-rest/.../server/ManagedSetupRuntime.java` | Delete the six `supports*()` default methods. |
| `peegee-cache-rest/.../server/PostgresManagedSetupRuntime.java` | Delete the read-only constructor at line 44. Delete the six `supports*()` overrides at lines 164 to 191. |
| `peegee-cache-rest/.../server/PostgresSetupRuntimeFactory.java` | Delete `administrationEnabled` logic at lines 78 to 85. Require non-null audit sink, fingerprinter, clock, and event id supplier. Delete the ternary at line 141 and call the full constructor unconditionally. |
| `peegee-cache-rest/.../server/ManagementServerApplication.java` | Delete `capabilitySourceFilter` parameter from both overloads and the `UnaryOperator.identity()` at line 71. |
| `peegee-cache-rest/.../server/ManagementRouteTemplate.java` | Delete the capabilities route at line 21. |
| `peegee-cache-rest/.../server/ManagementM7RouteInventory.java` | Delete `getSetupCapabilities` at line 29. |
| `peegee-cache-rest/.../server/LocalSessionRoutes.java` | Delete `features` object at lines 135 to 136. |
| `peegee-cache-rest/.../server/TrustedProxySessionRoutes.java` | Delete `features` object at lines 76 to 77. |

### OpenAPI (1 file)

`peegee-cache-rest/src/main/openapi/peegeeq-cache-management-v1.yaml`

- Delete `getSetupCapabilities` from the operation list at line 19.
- Delete the path at line 239.
- Delete the response at line 1700 and the schema at line 2133.
- On the connection-test schema at line 2174, remove `capabilities` from `required` and from properties. Change `limits` to reference a new `SetupLimits` schema.
- Add `limits` to the setup details schema referencing `SetupLimits`.
- Delete `features` from the session schema at lines 2033 to 2039.

### Java test (18 files)

| File | Action |
|---|---|
| `peegee-cache-api/.../ManagementServiceFallbackTest.java` | Delete |
| `peegee-cache-api/.../ManagementMetadataModelsTest.java` | Remove `AdminCapabilities` cases |
| `peegee-cache-pg/.../PgPeeGeeCacheTest.java` | Pass a real management service to the seven-argument constructor |
| `peegee-cache-pg/.../PgManagementMutationRepositoryTest.java` | Remove read-only and capability-exception cases |
| `peegee-cache-rest/.../SetupCapabilitiesTest.java` | Delete. Add `SetupLimitsTest` for the channel-byte formula. |
| `peegee-cache-rest/.../ManagementCapabilityBrowserIT.java` | Delete |
| `peegee-cache-rest/.../ManagementBrowserSelection.java` | Remove the three references at lines 71, 97, 136 |
| `peegee-cache-rest/.../ManagementBrowserSelectionTest.java` | Remove capability scenario assertions |
| `peegee-cache-rest/.../ManagementBrowserCoverageTest.java` | Change `CURRENT_SCENARIO_COUNT` from 557 to 539 |
| `peegee-cache-rest/.../ManagementConsolePostgresFixture.java` | Delete `capabilitySourceFilter` parameter at lines 150, 159, 230, 272, 287 |
| `peegee-cache-rest/.../PostgresSetupRuntimeFactoryTest.java` | Remove read-only runtime cases. Add one case that a null audit sink is rejected at construction. |
| `peegee-cache-rest/.../SetupReadRoutesTest.java` | Remove capabilities endpoint cases. Add details `limits` assertions. |
| `peegee-cache-rest/.../openapi/ManagementOpenApiContractTest.java` | Remove entries at lines 525 and 588 |
| `peegee-cache-rest/.../openapi/BackendFunctionalityInventoryTest.java` | Remove entry at line 74 |
| `peegee-cache-rest/.../ManagementBrowserOperationTrace.java` | Remove capabilities operation from the trace list |
| `peegee-cache-rest/.../ManagementConsoleProductJourneysIT.java` | Remove capability panel and session feature assertions |
| `peegee-cache-rest/.../ManagementShellBrowserIT.java` | Same |
| `peegee-cache-rest/.../ManagementSetupBrowserIT.java` | Same |
| `peegee-cache-rest/.../ManagementMonitoringBrowserIT.java` | Same |
| `peegee-cache-rest/.../ManagementLiveTransportBrowserIT.java` | Same |

### UI source (17 files)

| File | Action |
|---|---|
| `src/api/setup-schemas.ts` | Delete `capabilityFlagsSchema`, `capabilityLimitsSchema`, `setupCapabilitiesSchema`, `SetupCapabilities`. Add `setupLimitsSchema`. Add `limits` to `setupDetailsSchema`. Remove `capabilities` from the connection-test schema. |
| `src/api/openapi-contract.ts` | Delete `SetupCapabilitiesContract`. Regenerate from the updated YAML. |
| `src/api/protocol-schemas.ts` | Delete `features` from `currentSessionSchema` at lines 26 to 29 |
| `src/api/setup-client.ts` | Delete `capabilities()` at lines 27 and 60 |
| `src/api/operation-manifest.ts` | Delete `getSetupCapabilities` at line 56 |
| `src/store/api/setupsApi.ts` | Delete `getSetupCapabilities` at line 28. Delete the `Capabilities` invalidation at line 45. |
| `src/store/api/managementApi.ts` | Delete `Capabilities` tag at line 17 |
| `src/state/scope-store.ts` | Delete `capabilities` field. Change `select` to `(setupId) => void`. |
| `src/components/common/SetupScopeBar.tsx` | Delete `useLazyGetSetupCapabilitiesQuery`. Commit scope immediately on selection. |
| `src/app/ManagementShell.tsx` | Delete `capabilityLookup` at lines 105 to 113, `gated()` at 135, `CapabilityPending` and `CapabilityUnavailable` at 356 to 373. Render routes directly. Set `canOperate`, `canReveal`, `canBulkDelete` to `isOperator`. Delete `canInspectExpired`, `canBatch`, `canMetrics`, `canScan`, `canOwnLocks`. Source `maximumChannelBytes` and `maximumPayloadBytes` from setup details. Delete `session.features` reads. |
| `src/features/settings/SettingsPage.tsx` | Replace `capabilities` prop with `limits` from setup details |
| `src/features/setups/SetupsPage.tsx` | Delete the capability list at lines 517 to 526 and `humanizeCapability`. Keep the limits list. Delete `session.features.setupRegistration` at lines 93 and 283. |
| `src/features/entries/EntryDetailsPage.tsx` | Change the message at line 227 to "Operator permission is required." |
| `src/features/advanced/AdvancedOperationsPage.tsx` | Delete `canBatch`, `canMetrics`, `canScan`, `canOwnLocks` props and their conditionals |
| `src/features/pubsub/PubSubPage.tsx` | No change. Props are supplied by the shell. |
| `src/store/clients.ts` | No change. `backendCapability` is a real client. |
| `src/styles/foundation.css` | Delete `.capability-list` rules at lines 762, 770, 835 |

### UI test (18 files)

All eighteen files under `peegee-cache-management-ui/test/` that match `capabilit`, `sensitiveReveal`, or `setupRegistration` need their fixtures updated. `protocol-schemas.test.ts`, `setup-client.test.ts`, `scope-store.test.ts`, `setups-page.test.tsx`, `settings-page.test.tsx`, and `app.test.tsx` lose whole cases. The others lose capability fields from fixture objects.

### Documents (13 files)

| File | Action |
|---|---|
| `docs/design/PEEGEEQ_CACHE_MANAGEMENT_API.md` | Rewrite section 7.9. Remove `AdminCapabilities` and `ManagementCapability` from the Java contract in section 16. |
| `docs/design/PEEGEEQ_CACHE_MANAGEMENT_UI_DESIGN.md` | Rewrite section 10.2. Delete the capability response example. Correct line 695. Delete "capability-based page/action visibility" at line 789. Update the scenario counts at line 29. |
| `docs/design/PEEGEEQ_CACHE_MANAGEMENT_OPERATION_MANIFEST.md` | Delete `getSetupCapabilities` |
| `docs/design/PEEGEEQ_CACHE_PLAYWRIGHT_IMPLEMENTATION_PLAN.md` | Delete the `PW-CAPABILITY` block. Update totals. |
| `docs/design/PEEGEEQ_CACHE_FUNCTIONALITY_COVERAGE_MATRIX.md` | Delete the `ManagementService.capabilities` row. Sections 5 to 7 use "capability" in a different sense and stay. |
| `docs/design/PEEGEEQ_CACHE_MANAGEMENT_API_IMPLEMENTATION_PLAN.md` | Mark capability tasks as removed |
| `docs/design/PEEGEEQ_CACHE_MANAGEMENT_UI_IMPLEMENTATION_PLAN.md` | Same |
| `docs/design/PEEGEEQ_CACHE_MANAGEMENT_BUILD_DECISION.md` | Same |
| `docs/design/PEEGEEQ_CACHE_IMPLEMENTATION_PLAN.md` | Same |
| `docs/design/PEEGEEQ_CACHE_DESIGN.md` | Remove the management capability paragraph |
| `docs/design/PEEGEEQ_CACHE_UI_U11_HANDOVER_2026-09-03.md` | Add a note pointing to this plan |
| `docs/design/UI mockups/peegeeq-cache-management-ui-mockups.html` | Remove the capabilities panel mockup |
| `docs/guidelines/PEEGEEQ_CACHE_TEST_COVERAGE_AND_TDD_APPROACH.md` | Remove the one reference |

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

### Phase 2. UI removal

The UI stops calling the capabilities endpoint.

1. `scope-store.ts`: drop the capability snapshot.
2. `SetupScopeBar.tsx`: commit scope on selection without a fetch.
3. `ManagementShell.tsx`: delete `gated()`, the two placeholder components, the capability query, and the `session.features` reads. Derive `can*` props from `isOperator`. Read limits from the setup details query.
4. `SettingsPage.tsx`, `SetupsPage.tsx`, `EntryDetailsPage.tsx`, `AdvancedOperationsPage.tsx`: as listed in section 5.
5. `setup-client.ts`, `setupsApi.ts`, `managementApi.ts`, `operation-manifest.ts`, `setup-schemas.ts`, `protocol-schemas.ts`, `foundation.css`: as listed in section 5.
6. Update the eighteen test files.

```powershell
cd peegee-cache-management-ui
npx tsc --noEmit 2>&1 | Tee-Object -FilePath ..\logs\gating-removal-phase2-tsc.log
npm run lint 2>&1 | Tee-Object -FilePath ..\logs\gating-removal-phase2-lint.log
npm run test -- --run 2>&1 | Tee-Object -FilePath ..\logs\gating-removal-phase2-test.log
grep -rn "capabilit\|sensitiveReveal\|setupRegistration" src test | grep -v "backend-capability\|BackendCapability\|backendCapability"
```

Exit: type check, lint, and tests green. The grep returns nothing.

### Phase 3. REST removal

1. Delete the capabilities route, `SetupCapabilities`, `putFeatures`, the `capabilities` block on the connection test, the `capabilitySourceFilter` plumbing, the route inventory entries, and the session `features` object.
2. Repoint `PubSubRoutes` at `SetupRegistry.limits()`.
3. Update OpenAPI as listed in section 5.
4. Delete `SetupCapabilitiesTest` and `ManagementCapabilityBrowserIT`. Update `ManagementBrowserSelection`, `ManagementBrowserSelectionTest`, `ManagementBrowserCoverageTest`, `ManagementConsolePostgresFixture`, `ManagementBrowserOperationTrace`, the two OpenAPI tests, and `SetupReadRoutesTest`.
5. Update the five browser ITs that assert on the capability panel or session features.

```powershell
mvn clean install -DskipTests -pl :peegee-cache-rest -am 2>&1 | Tee-Object -FilePath logs\gating-removal-phase3-build.log
mvn test -pl peegee-cache-rest -Dtest="SetupReadRoutesTest,SetupLimitsTest,ManagementOpenApiContractTest,BackendFunctionalityInventoryTest,ManagementBrowserSelectionTest,ManagementBrowserCoverageTest" 2>&1 | Tee-Object -FilePath logs\gating-removal-phase3-test.log
```

Exit: coverage test reports 539 scenarios. All listed classes green.

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

### Phase 5. PG and API removal

1. `PgManagementService`: delete the read-only constructor and every null guard listed in section 5. Delete `unavailable()`.
2. `PgPeeGeeCache`: delete the six-argument constructor. Update callers.
3. `ManagementService`: delete `capabilities()`. Make the three defaults abstract.
4. `PeeGeeCache`: make `management()` abstract.
5. Delete the four API types.
6. Update `ManagementMetadataModelsTest`, `PgPeeGeeCacheTest`, `PgManagementMutationRepositoryTest`. Delete `ManagementServiceFallbackTest`.

```powershell
mvn clean install -DskipTests -pl :peegee-cache-rest -am 2>&1 | Tee-Object -FilePath logs\gating-removal-phase5-build.log
mvn test -pl peegee-cache-api,peegee-cache-pg 2>&1 | Tee-Object -FilePath logs\gating-removal-phase5-test.log
grep -rn "ManagementCapability\b\|AdminCapabilities\|ManagementCapabilityException\|UnsupportedManagementService\|SetupCapabilities\|capabilitySourceFilter\|supportsPubSub\|supportsValueScan\|sensitiveReveal\|setupRegistration" --include=*.java --include=*.yaml peegee-cache-api peegee-cache-pg peegee-cache-rest
```

Exit: the reactor builds. API and PG module tests green. The grep returns nothing.

### Phase 6. Documents

Apply section 5. Every count that names 557 scenarios or 18 degraded capability paths changes to 539 and 0.

Exit: `grep -rli "capabilit" docs` returns only the coverage matrix (sections 5 to 7, unrelated meaning) and this plan.

### Phase 7. Full gate

```powershell
mvn clean install -DskipTests 2>&1 | Tee-Object -FilePath logs\gating-removal-phase7-build.log
mvn verify -pl peegee-cache-rest -Pbrowser 2>&1 | Tee-Object -FilePath logs\gating-removal-phase7-browser.log
```

Exit: 539 browser scenarios pass against real PostgreSQL. Per-class `Tests run:` counts reported.

## 7. Behaviour after removal

- A viewer sees every page and no write control.
- An operator sees every page and every write control.
- A request the server cannot fulfil fails with the existing problem codes. There is no advertised list to drift from.
- A setup whose pool is lost reports through health, as today.
- Adding an operation touches the route, the client, and the page. Nothing else.

## 8. Not checked

- Whether any external consumer calls `GET /setups/{setupId}/capabilities`. The UI is the only known client.
- Whether `ManagementServerConfiguration.limits().maximumValueBytes()` matches `ManagementLimits.defaults()` in every deployment profile. Phase 1 makes configuration the source; confirm the value on the first deployment.
- Runtime verification of the 500 fallback for `ManagementCapabilityException`. The mapping was read, not executed. It becomes irrelevant after Phase 5.
