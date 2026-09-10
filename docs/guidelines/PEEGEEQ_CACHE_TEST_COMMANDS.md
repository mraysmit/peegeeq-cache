# PeeGeeQ Cache Test Commands Quick Reference

**Platform:** Windows / PowerShell only. Always pipe with `Tee-Object`. Never use `Select-String` or `Select-Object -Last N` on the live Maven stream; read the saved log afterwards.
**Log location and naming:** `logs\<description>-<YYYYMMDD>.log` (the `logs/` folder is git-ignored and is where every saved run belongs).

> **Who runs what.** The agent runs scoped verification itself, a single class or a single module, after rebuilding the affected reactor slice. It reports the exact scope and the per-class `Tests run:` lines from the saved log. `BUILD SUCCESS` alone is not verification. The complete reactor verify (20 to 35 minutes with the browser catalogue) is an owner-run or explicitly requested gate, not a step in the edit-test loop.

---

## Reactor shape (read this first)

This reactor has no test-tag profiles. `mvn test` runs every Surefire test in the selected modules, and `mvn verify` additionally runs the Failsafe browser catalogue in `peegee-cache-rest`. Root profiles are `release-artifacts` and `central-release` only; neither filters tests.

| Module | Test mass | Needs Docker |
|---|---|---|
| `peegee-cache-api` | Model and contract unit tests | No |
| `peegee-cache-core` | Buffer and telemetry unit tests | No |
| `peegee-cache-test-support` | Shared Testcontainers PostgreSQL support | No tests |
| `peegee-cache-pg` | Repository, service, SQL and management tests on PostgreSQL | Yes |
| `peegee-cache-runtime` | Lifecycle, write-behind and expiry tests on PostgreSQL | Yes |
| `peegee-cache-observability` | Telemetry adapters | No |
| `peegee-cache-management-ui` | Vitest suite run by Maven under the pinned Node 22.22.2 / npm 10.9.4 toolchain | No |
| `peegee-cache-rest` | Surefire route, registry, contract and factory tests; Failsafe Playwright catalogue (539 scenarios plus infrastructure checks) | Yes, for the factory test and every `*IT` |
| `peegee-cache-benchmarks`, `peegee-cache-examples` | Small unit tests | No |

Docker Desktop must be running before any PostgreSQL-backed test. If `com.docker.service` is stopped, Testcontainers fails with "Could not find a valid Docker environment".

---

## COPY-PASTE COMMANDS (update the date suffix before running)

```powershell
# Rebuild one changed module and its upstream slice (required before targeted tests)
mvn clean install -DskipTests -Dmaven.antrun.skip=true -pl :peegee-cache-rest -am 2>&1 |
    Tee-Object -FilePath logs\rebuild-rest-20260910.log

# Full reactor rebuild without tests
mvn clean install -DskipTests -Dmaven.antrun.skip=true 2>&1 |
    Tee-Object -FilePath logs\rebuild-all-20260910.log

# One test class in one module
mvn test -pl peegee-cache-rest -Dtest=SetupReadRoutesTest 2>&1 |
    Tee-Object -FilePath logs\rest-setup-read-routes-20260910.log

# Several classes in one module
mvn test -pl peegee-cache-rest -Dtest="SetupReadRoutesTest,SetupRegistryTest,ManagementOpenApiContractTest" -Dsurefire.failIfNoSpecifiedTests=false 2>&1 |
    Tee-Object -FilePath logs\rest-setup-contract-20260910.log

# One method
mvn test -pl peegee-cache-pg -Dtest="PgManagementMutationRepositoryTest#auditFailureBlocksEveryCounterMutation" 2>&1 |
    Tee-Object -FilePath logs\pg-mutation-audit-20260910.log

# Whole module Surefire suites (PostgreSQL modules need Docker)
mvn test -pl peegee-cache-api 2>&1 | Tee-Object -FilePath logs\api-tests-20260910.log
mvn test -pl peegee-cache-pg,peegee-cache-runtime 2>&1 | Tee-Object -FilePath logs\pg-runtime-tests-20260910.log

# REST Surefire plus the complete Failsafe browser catalogue and the evidence check (about 25 minutes)
mvn verify -pl peegee-cache-rest 2>&1 | Tee-Object -FilePath logs\rest-verify-20260910.log

# One browser feature class
mvn verify -pl peegee-cache-rest -Dit.test=ManagementCounterBrowserIT 2>&1 |
    Tee-Object -FilePath logs\rest-counter-browser-20260910.log

# Exact browser scenario IDs (comma separated)
mvn verify -pl peegee-cache-rest -Dpeegeeq.playwright.scenarios=PW-COUNTER-008 2>&1 |
    Tee-Object -FilePath logs\rest-scenario-counter-008-20260910.log

# Complete reactor verify: every module, the Maven-run UI suite, and the browser catalogue (owner-run gate, 20 to 35 minutes)
mvn --batch-mode --no-transfer-progress clean verify 2>&1 |
    Tee-Object -FilePath logs\full-verify-20260910.log
```

**After the command finishes:**

```powershell
Get-Content logs\<name>.log -Tail 30
Select-String -Path logs\<name>.log -Pattern "Tests run:.*-- in|BUILD (SUCCESS|FAILURE)"
```

---

## REQUIRED: rebuild before targeted verification

Every Java or Maven implementation change must be rebuilt and installed before targeted tests run. Scope the rebuild to the changed module and its upstream reactor dependencies with `-pl :<module> -am`.

`-DskipTests` is allowed only for this rebuild/install prerequisite. It compiles test sources but does not execute them. Run the targeted verification immediately afterwards. Never use `-Dmaven.test.skip=true`, because it skips test compilation and can leave stale test artifacts undiscovered.

`-Dmaven.antrun.skip=true` is needed on any `install` or `verify` of `peegee-cache-rest` that does not run the browser catalogue. The module's `verify-playwright-evidence` Ant step fails when `target/playwright-evidence.html` is absent. Leave the flag off for the real browser gate so the evidence check runs.

Use `clean` for regression-safety runs. The IDE's Eclipse compiler and Maven's incremental compiler can both leave stale classes in `target/test-classes` (visible as "Unresolved compilation problem" or `NoClassDefFoundError` at runtime); `clean` removes that trap.

---

## RULE: scoped runs to iterate, the complete verify to gate

| Situation | Command |
|---|---|
| Writing a test, watching it fail, making it pass | The single test or class, scoped with `-pl` and `-Dtest=` |
| Iterating on a module you are changing | That module with `mvn test -pl` |
| Pre-change baseline | The same scoped classes or modules, run before the change |
| Browser behaviour you changed | `-Dit.test=<FeatureIT>` or `-Dpeegeeq.playwright.scenarios=<IDs>` |
| Explicit commit / push / release gate | `mvn clean verify`, owner-run or explicitly requested |

**What a scoped run is NOT.** It is evidence about the code you scoped it to and nothing else. Never describe a scoped run as "the suite passes" or "the build is green". Say what ran: *"`peegee-cache-rest` `SetupReadRoutesTest`: 1 passed"*. A scoped green establishes only the named scope.

The browser catalogue's scenario count is enforced twice: `ManagementBrowserCoverageTest.CURRENT_SCENARIO_COUNT` and the `peegeeq.playwright.expectedScenarios` property in `peegee-cache-rest/pom.xml`. Both must change together when scenarios are added or removed.

---

## UI module (from `peegee-cache-management-ui/`)

Maven runs these under the pinned Node toolchain. Running them with the system Node is fine for iteration; the suite is Node-version independent because `test/support/jsdom-runtime-fetch-environment.ts` keeps the runtime's `AbortController`/`AbortSignal` in the jsdom environment (undici 7, bundled from Node 24, rejects jsdom's signal in `fetch`). Note that `node
ode.exe ...npm-cli.js run <script>` does not pin the toolchain: npm resolves `vitest` through `PATH`, so prepend `node\` to `PATH` when the pinned version matters. The Maven-run result is the one that counts.

```powershell
cd peegee-cache-management-ui

# Regenerate the OpenAPI TypeScript types after any change to the YAML
npm run generate:openapi 2>&1 | Tee-Object -FilePath ..\logs\ui-openapi-20260910.log

# Type check and lint
npm run quality 2>&1 | Tee-Object -FilePath ..\logs\ui-quality-20260910.log

# Unit tests, one shot
npm run test:run 2>&1 | Tee-Object -FilePath ..\logs\ui-unit-tests-20260910.log

# Unit tests with coverage thresholds (what Maven runs)
npm run test:coverage 2>&1 | Tee-Object -FilePath ..\logs\ui-coverage-20260910.log

# Production build
npm run build 2>&1 | Tee-Object -FilePath ..\logs\ui-build-20260910.log

# Everything Maven does for this module
npm run verify 2>&1 | Tee-Object -FilePath ..\logs\ui-verify-20260910.log
```

| Script | What it does |
|---|---|
| `generate:openapi` | `openapi-typescript` from `peegee-cache-rest/src/main/openapi/peegeeq-cache-management-v1.yaml` into `target/generated-sources/openapi/management.ts` |
| `type-check` | `tsc --noEmit` |
| `lint` | `eslint . --max-warnings 0` |
| `quality` | type-check then lint |
| `test:run` | `vitest run` |
| `test:coverage` | `vitest run --coverage` (branch thresholds on `src/api` and `src/state`) |
| `build` | type-check then `vite build` |
| `verify` | generate, quality, coverage tests, build (preceded by the runtime check in `scripts/verify-runtime.mjs`) |

There is no `npm run test` script; use `test:run`.

---

## Reporting a run

Quote from the saved log, never from memory:

1. the exact command and scope;
2. every `Tests run: N, Failures: F, Errors: E, Skipped: S -- in <Class>` line for the scope;
3. the final `BUILD SUCCESS` or `BUILD FAILURE`;
4. for the browser gate, the Failsafe total (539 scenarios plus the observation, runnable-artifact and screenshot checks) and that the evidence check passed.
