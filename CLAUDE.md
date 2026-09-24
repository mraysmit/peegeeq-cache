# PeeGeeQ Cache: agent working rules

**Last reconciled:** 24 September 2026 against `3ed1162`.

Read these before implementing anything in this repository:

- `docs/guidelines/PEEGEEQ_CACHE_DEV_GUIDELINES.md` (production-code rules for this repository)
- `docs/guidelines/PEEGEEQ_CACHE_TEST_COVERAGE_AND_TDD_APPROACH.md`
- `docs/guidelines/PEEGEEQ_CACHE_TEST_COMMANDS.md` (the exact Maven and npm commands, log naming, and reporting rules)

## Mandatory verification workflow

- **One phase at a time.** Make the change, rebuild and verify that phase, then stop and report.
- **Rebuild before verification.** After every Java or Maven implementation change, run `mvn clean install -DskipTests -pl :<changed-module> -am` through `Tee-Object` before downstream testing. This rebuilds the affected reactor slice and installs upstream artifacts. `-DskipTests` is permitted only for this build step and still compiles tests. Add `-Dmaven.antrun.skip=true` when the REST module is in the slice and no browser run is intended.
- **Run targeted tests.** Run the smallest relevant scope, a method, class, or single module. Pipe every Maven and npm run through `Tee-Object` into `logs\<description>-<YYYYMMDD>.log`, read the saved log, and report the exact scope and the per-class `Tests run:` counts. `BUILD SUCCESS` alone is not verification. Never use `-q`.
- **Do not overstate scope.** A scoped green proves only that scope. The complete `mvn clean verify` (all modules, the Maven-run UI suite, the complete browser catalogue, whose size is `peegeeq.playwright.expectedScenarios` in `peegee-cache-rest/pom.xml`) is an owner-run or explicitly requested gate.
- **Docker first.** PostgreSQL-backed tests use Testcontainers; start Docker Desktop before running tests in `peegee-cache-pg`, `peegee-cache-runtime`, `peegee-cache-observability`, `peegee-cache-rest` (the PostgreSQL-backed tests and every browser `*IT`) or `peegee-cache-benchmarks`.
- **No mocking.** No Mockito, no mocked database connections, no mocked repositories. Testcontainers PostgreSQL for every database test; loopback servers with schema-produced bodies for UI tests.
- **No error swallowing.** Every catch block surfaces the error. Silent catches are a defect.
- **Mirror existing patterns exactly.** Read the surrounding code and the module's existing tests first; do not invent a new pattern.
- **Never state runtime behaviour as fact from static reading.** If behaviour needs verifying, run it and quote the log, or say that it was not verified.
- **Keep the scenario count in step.** `ManagementBrowserCoverageTest.CURRENT_SCENARIO_COUNT` and `peegeeq.playwright.expectedScenarios` in `peegee-cache-rest/pom.xml` change together, and the coverage matrix §3 is updated in the same change.
