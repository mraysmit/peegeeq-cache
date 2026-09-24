# PeeGeeQ Cache Development Guidelines

**Author**: Mark A Ray-Smith Cityline Ltd.
**Last reconciled:** 24 September 2026 against `3ed1162`.

These are the production-code rules for this repository. Read them with the three documents that sit beside them:

- the root [`CLAUDE.md`](../../CLAUDE.md): the mandatory verification workflow (rebuild, targeted tests, `Tee-Object` logs, scope reporting);
- [`PEEGEEQ_CACHE_TEST_COMMANDS.md`](PEEGEEQ_CACHE_TEST_COMMANDS.md): the exact Maven and npm commands and how to report a run;
- [`PEEGEEQ_CACHE_TEST_COVERAGE_AND_TDD_APPROACH.md`](PEEGEEQ_CACHE_TEST_COVERAGE_AND_TDD_APPROACH.md): the testing standard, including the management UI rules in its §5.

Where they overlap, `CLAUDE.md` and `TEST_COMMANDS.md` own the workflow and commands, the TDD approach owns test design, and this document owns production-code rules. The guide imported from the sibling PeeGeeQ queue repository in March 2026 is archived at [`docs/design/archive/PEEGEEQ_SIBLING_REPO_IMPORTED_GUIDELINES_2026-03.md`](../design/archive/PEEGEEQ_SIBLING_REPO_IMPORTED_GUIDELINES_2026-03.md); it is reference material only and several of its rules do not apply here.

## 1. Module layering

The reactor has ten modules under `peegee-cache-parent`. The dependency direction below is taken from the module poms (compile scope); a new dependency must not point the other way.

| Module | Kind | Depends on |
|---|---|---|
| `peegee-cache-api` | library | nothing in the reactor |
| `peegee-cache-core` | library | api |
| `peegee-cache-test-support` | library (test fixtures) | api, core |
| `peegee-cache-pg` | library | api, core |
| `peegee-cache-runtime` | library | api, core, pg |
| `peegee-cache-observability` | library | api, core |
| `peegee-cache-management-ui` | application (React console packaged as static assets under `ui/`) | nothing in the reactor |
| `peegee-cache-rest` | application (management server and runnable jar) | management-ui, api, runtime |
| `peegee-cache-benchmarks` | application (opt-in benchmark runners) | runtime, observability, test-support |
| `peegee-cache-examples` | application (runnable examples) | runtime, observability |

The library modules are the six that `LoggingDependencyContractTest` (in `peegee-cache-test-support`) checks. They must not select a logging provider outside test scope, create a `Vertx` instance, or block an event-loop thread (§2), and the five production libraries (api, core, pg, runtime, observability) must not read environment variables or system properties. `peegee-cache-test-support` is the one library that reads a system property: `PostgreSQLTestConstants` reads `peegeeq.test.postgres.image` once to select the test image. Application modules may do all of these, at their process entry points only.

## 2. Asynchronous model

- The project uses Vert.x 5 (`vertx.version` in the root pom). Every public library operation returns `io.vertx.core.Future<T>`. Compose with `compose`, `map`, `recover`, `onSuccess` and `onFailure`. Do not return `CompletableFuture` from a library API or convert a `Future` to one in library code; no library module does today.
- Never block an event-loop thread. Blocking waits (`CountDownLatch.await`, `Future.await`) are confined to process entry points (`ManagementServerMain`, the example and benchmark mains) and to explicitly blocking lifecycle methods. `PgPeeGeeCacheManager.close()` is one of those: it waits up to 10 seconds for `stopReactive()` and must not be called from an event-loop thread; reactive callers use `stopReactive()`.
- Database I/O goes through the reactive Vert.x SQL client. No production module uses JDBC.

## 3. Resource ownership

- `Vertx` and `Pool` are injected. Library code never creates a `Vertx` instance and never closes a `Vertx` or `Pool` it was given. The manager borrows the caller's `Vertx` and `Pool`; the caller closes both after stopping the manager (see the README quick start).
- A component that creates a resource owns it and closes it. Its close path returns a `Future<Void>` that completes only after every owned resource has closed; aggregate several closes with `Future.all(...)` and surface every failure.

## 4. Configuration

- Configuration is typed and explicit: records such as `PeeGeeCacheConfig`, `PgCacheStoreConfig`, `WriteBehindConfig` and `PeeGeeCacheBootstrapOptions`, with `defaults()` factories where a default is meaningful. Use `Duration` for time values in Java configuration and map to Vert.x setters at the boundary.
- Validate in the constructor or factory and fail fast with an exception that names the field. Invalid configuration is never silently corrected.
- The production library modules (api, core, pg, runtime, observability) do not call `System.getProperty` or `System.getenv`. The management server reads `PEEGEEQ_MANAGEMENT_*` environment variables in `ManagementServerMain` only (documented in [`PEEGEEQ_CACHE_MANAGEMENT_OPERATIONS.md`](../PEEGEEQ_CACHE_MANAGEMENT_OPERATIONS.md)); benchmark and example runners read their properties in their mains.
- Log configuration only in sanitised form. Passwords, tokens and secret references are never logged.

## 5. SQL and schema

- The schema is owned by the bundled migration `peegee-cache-pg/src/main/resources/db/bootstrap/V001__create_peegee_cache_schema.sql`, applied externally or by `SchemaBootstrapMode.APPLY` through `PgSchemaMigrator`. The supported native SQL surface (the functions and the `live_entries`, `live_counters` and `active_locks` views) is defined in [`PEEGEEQ_CACHE_NATIVE_SQL_API.md`](../design/PEEGEEQ_CACHE_NATIVE_SQL_API.md). A schema change after a release is a new versioned migration plus an update to that document.
- The schema name is configurable. Render schema-qualified SQL through `BootstrapSqlRenderer`; do not hard-code `peegee_cache` in Java SQL strings.
- Data access uses parameterised `preparedQuery(...)` with `Tuple` arguments. Never build SQL by concatenating keys, namespaces, channels or other caller input.
- Multi-statement scripts (the bootstrap and migrations) run through `query(sql).execute()`, which uses PostgreSQL's simple-query protocol and executes every statement in the script. `PgTestSupport` and `PgSchemaMigrator` both apply the complete `V001` this way, and every PostgreSQL-backed test then relies on functions and views defined late in that file. Never pass a multi-statement script to `preparedQuery`: the extended protocol accepts one statement. The sibling repository's "multi-statement SQL bug" postmortem is about that distinction; it does not mean `query()` drops statements.

## 6. Errors

- No error swallowing. Every `catch` block and every `onFailure`/`recover` handler either propagates the failure, fails the enclosing `Future` or test context, or logs it at WARN or ERROR with its cause. A shutdown or periodic path that continues after a failure must still log that failure with its cause; `PgPeeGeeCacheManager.close()` is the model.
- The management API's error contract is [`PEEGEEQ_CACHE_MANAGEMENT_API.md`](../design/PEEGEEQ_CACHE_MANAGEMENT_API.md) §5.
- Tests fail honestly. A failing test is fixed or the change is reverted; it is never disabled or excluded to make a run green.

## 7. Logging and telemetry

- Follow [`PEEGEEQ_CACHE_LOGGING.md`](../PEEGEEQ_CACHE_LOGGING.md): SLF4J structured logging with event names such as `cache.manager.stop_failed`, identifiers passed through `SafeLogValue` (`peegee-cache-pg`), per-operation detail at TRACE only.
- Telemetry goes through the `CacheTelemetry` SPI and `CompositeCacheTelemetry` (`peegee-cache-core`), with `MicrometerCacheTelemetry` and `OpenTelemetryCacheTelemetry` in `peegee-cache-observability`. Metric attributes are bounded enums; they never contain cache keys, namespaces, channels or payloads (see [`PEEGEEQ_CACHE_OPERATIONS.md`](../PEEGEEQ_CACHE_OPERATIONS.md)).

## 8. Tests

The testing standard is the TDD approach document. The rules that most often matter while writing code:

- No mocking library is used anywhere in the reactor, and none may be added. PostgreSQL-backed tests use a real Testcontainers database through `PgTestSupport` and `SharedPostgresContainerManager` in `peegee-cache-test-support`. The image defaults to `postgres:18.3-alpine` (`PostgreSQLTestConstants.DEFAULT_POSTGRES_IMAGE`) and is overridden with `-Dpeegeeq.test.postgres.image=...` for the PostgreSQL 15–18 matrix.
- Modules whose tests need Docker: `peegee-cache-pg`, `peegee-cache-runtime`, `peegee-cache-observability`, `peegee-cache-rest` and `peegee-cache-benchmarks`. Running the examples also needs Docker.
- Asynchronous Java tests use `VertxExtension` and `VertxTestContext`. Do not add `Thread.sleep` or unbounded blocking waits; use Vert.x timers or test-context checkpoints. The three existing sleeps in `peegee-cache-rest` tests (`ManagementRunnableArtifactIT`, `PostgresSetupRuntimeFactoryTest`) are known debt.
- UI tests follow TDD approach §5: Ant Design and Recharts only, loopback-server fixtures produced by Zod schemas, no test doubles, and the `test/quality` guards.
- `-DskipTests` is used only for the rebuild step described in `CLAUDE.md`, never to get past a failure.

## 9. Management REST API and console

- The OpenAPI document `peegee-cache-rest/src/main/openapi/peegeeq-cache-management-v1.yaml` is the executable contract, checked by `ManagementOpenApiContractTest`. The design narrative is [`PEEGEEQ_CACHE_MANAGEMENT_API.md`](../design/PEEGEEQ_CACHE_MANAGEMENT_API.md); per-operation traceability and the only authoritative counts are in [`PEEGEEQ_CACHE_FUNCTIONALITY_COVERAGE_MATRIX.md`](../design/PEEGEEQ_CACHE_FUNCTIONALITY_COVERAGE_MATRIX.md).
- Every operation declares an `x-security-profile`: `VIEW` (viewer), `OPERATE` (operator mutation), `REVEAL` (operator reveal, audited, `Cache-Control: no-store`), `VIEW_MUTATE` (viewer-owned local-session and subscription changes), and the `SESSION`, `LOCAL_BOOTSTRAP`, `SSE` and `WS` profiles.
- Adding or removing a browser scenario changes `ManagementBrowserCoverageTest.CURRENT_SCENARIO_COUNT`, `peegeeq.playwright.expectedScenarios` in `peegee-cache-rest/pom.xml`, and the count in the coverage matrix §3, together.

## 10. Documentation

- Counts (operations, methods, scenarios, tests) live in code: the OpenAPI document, the `ManagementService` interface, the scenario-count pair above, and saved test logs. The coverage matrix §3 is the only document that restates the management counts; other documents link to it rather than repeating a number.
- Every current document carries a `Last reconciled: <date> against <commit>` line under its title. Advance it only after checking the body against the code, not when adding a banner.
- Superseded plans, logs and handovers move to `docs/design/archive/` with an "Archived <date>" banner that names the successor. Current documents cite archived ones as historical records, never as authorities.
