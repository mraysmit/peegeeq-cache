# peegee-cache Test Coverage and TDD Approach

This document is the normative testing standard for `peegeeq-cache`. Requirements stated with
**must**, **must not**, or **mandated** apply to all new and modified tests unless an exception is
defined explicitly here.

## 1. TDD discipline

Every testable behavior follows strict red-green-refactor:

1. **Write a failing test** that asserts the design contract
2. **Run it** — confirm compilation failure or assertion failure
3. **Implement the minimum code** to make it pass
4. **Run it** — confirm green
5. **Refactor** only after the test is green

No implementation code is written before a failing test exists for that behavior. No test is written after the fact and called TDD.

### What tests must verify

Tests verify **design decisions**, not language mechanics. A test earns its place by asserting something that:

- could break if a developer makes a wrong change
- encodes a contract that callers depend on
- guards a semantic invariant that isn't enforced by the type system alone

Tests that verify `extends` works, that `super()` passes arguments through, or that a getter returns what the constructor received are not useful and should not be written.

### Suspicion protocol

- Read test logs after every run — do not trust exit code alone
- Treat suspiciously fast or empty-success runs as invalid
- Use `mvn -X` when behavior is unclear
- Confirm the expected test class names appear in Surefire output

## 2. Test classification

### Pure unit tests

For logic with **zero database behavior**:

- Record validation (null/blank rejection, invariant enforcement)
- Key parsing and normalization
- Enum behavior
- Exception hierarchy contracts
- Stateless transformations
- Configuration validation

**Framework:** JUnit Jupiter 5.x only. No Testcontainers, no Vert.x test context.

### Integration tests (Testcontainers)

For anything that touches **real PostgreSQL semantics**:

- Schema migrations
- SQL statement correctness (get/set/delete/TTL/counters/locks)
- Constraint enforcement (check constraints, uniqueness, foreign keys)
- Expiry-aware queries (database clock `NOW()`)
- Lock acquire/renew/release with owner token checks
- Counter increment/decrement atomicity
- Transaction semantics (expired-row pre-delete for NX)

**Framework:** JUnit Jupiter 5.x + Testcontainers 2.0.2 (`PostgreSQLContainer`); the default image is PostgreSQL 18.3-alpine and the full suite is parameterized for the PostgreSQL 15–18 matrix.

**Mocking is prohibited.** No mocked connections, pools, repositories, or SQL execution. No H2/HSQLDB substitutes.

### Vert.x integration tests

For behavior that depends on **Vert.x runtime semantics**:

- Service implementations (`PgCacheService`, `PgCounterService`, `PgLockService`)
- Future composition and error propagation
- Pub/sub listener lifecycle
- Sweeper scheduling and shutdown
- Bootstrap start/stop ordering

**Framework:** JUnit Jupiter 5.x + `VertxExtension` + Testcontainers. All tests use Vert.x facilities — no `CompletableFuture.join()`, no raw thread blocking on the event loop.

## 3. Current test inventory

Last verified: 2026-08-17 with `mvn verify` after a clean full-reactor baseline.

| Module | Tests | Scope |
|---|---:|---|
| `peegee-cache-api` | 34 | Keys, values, and exception contracts |
| `peegee-cache-core` | 14 | Validation, in-memory metrics, telemetry isolation, and async observation |
| `peegee-cache-pg` | 194 | Consolidated baseline bootstrap, migration-runner safety, repositories, services, native SQL, adversarial pub/sub, recovery, contention, and safe log identifier formatting |
| `peegee-cache-runtime` | 23 | Lifecycle, external/managed schema policy, custom schemas, complete operation telemetry, default TTL, physical expiry sweeping, and sequential/concurrent recurring failure-episode suppression |
| `peegee-cache-observability` | 4 | Micrometer export, OpenTelemetry spans, and complete/partial-schema PostgreSQL readiness |
| `peegee-cache-test-support` | 4 | Latency percentile/throughput calculation, configurable PostgreSQL matrix image selection, and published-module logging dependency policy |
| `peegee-cache-benchmarks` | 13 | Benchmark configuration, typed results, self-contained HTML evidence generation, exact pool headroom, runtime layout, timeout diagnostics, and real-PostgreSQL pool-headroom/sweeper regression |
| **Total** | **286** | Full reactor green on PostgreSQL 18.3; the 269-test pre-fix reactor and the added pool regression are both validated on PostgreSQL 15–18 |

### peegee-cache-api (34 tests)

| Test class | Tests | What it verifies |
|---|---|---|
| `CacheKeyTest` | 8 | Null rejection (`NullPointerException`), blank rejection (`IllegalArgumentException`), equality, `asQualifiedKey()` format |
| `CacheValueTest` | 13 | Typed factory methods, accessor type guards, compact constructor payload exclusivity, null rejection |
| `LockKeyTest` | 7 | Null rejection (`NullPointerException`), blank rejection (`IllegalArgumentException`), equality, `asQualifiedKey()` format |
| `ExceptionHierarchyTest` | 6 | Catchability contract (`catch CacheException` catches all subtypes), unchecked status, `CacheStoreException` rejects null cause, `LockNotHeldException` independent catchability |

### Initial peegee-cache-pg bootstrap slice (27 tests)

| Test class | Tests | What it verifies |
|---|---|---|
| `BootstrapSmokeTest` | 27 | Schema creation, table columns/types, primary keys, check constraints (value type, payload exclusivity, lease sanity), sequence monotonicity, all 5 indexes, constraint enforcement (rejects invalid data, accepts valid data) |

## 4. Phase 3 test plan — Repository and SQL statement catalogue

Each repository method gets a Testcontainers integration test against real PostgreSQL. Tests are written first, run red, then implementation proceeds.

### PgCacheRepository

| Behavior | Test approach |
|---|---|
| `get` returns empty for missing key | Insert nothing, assert `Future<Optional.empty()>` |
| `get` returns value for live key | Insert row, get, assert value/type match |
| `get` returns empty for expired key | Insert row with past `expires_at`, assert empty |
| `set` inserts new entry | Set, then raw SQL select to verify row |
| `set` with `SET_ALWAYS` overwrites existing | Set twice, verify second value persists |
| `set` with `SET_IF_ABSENT` skips existing live key | Set, set-NX again, verify original value unchanged |
| `set` with `SET_IF_ABSENT` succeeds over expired key | Insert expired row, set-NX, verify new value (expired-row pre-delete) |
| `set` with `SET_IF_EXISTS` skips missing key | Set-XX on missing key, verify no row created |
| `set` with `returnPreviousValue` returns old value | Set value, set again with flag, assert previous returned |
| `delete` removes entry | Set, delete, verify gone |
| `delete` on missing key is no-op | Delete non-existent key, assert no error |
| `getTtl` returns persistent for no-expiry key | Set without TTL, assert `TtlState.PERSISTENT` |
| `getTtl` returns remaining millis for expiring key | Set with TTL, assert positive millis |
| `getTtl` returns missing for absent key | Assert `TtlState.MISSING` |
| `expire` sets TTL on persistent key | Set without TTL, expire, verify `expires_at` set |
| `persist` removes TTL | Set with TTL, persist, verify `expires_at` is null |
| `touch` updates `updated_at` without changing value | Set, sleep/advance, touch, verify timestamp changed and value unchanged |

### PgCounterRepository

| Behavior | Test approach |
|---|---|
| `increment` creates counter if missing | Increment non-existent key, assert value = delta |
| `increment` adds to existing counter | Set to 5, increment by 3, assert 8 |
| `increment` with `createIfMissing=false` on missing key | Assert returns indication of no-op / zero |
| `decrement` subtracts from counter | Set to 10, decrement by 3, assert 7 |
| `get` on missing counter | Assert 0 or empty |
| `get` on expired counter | Insert with past expiry, assert treated as absent |
| `set` overwrites counter value | Set to 5, set to 20, assert 20 |
| `delete` removes counter | Create, delete, verify gone |
| Counter TTL modes (`ON_CREATE` vs `ON_EVERY_UPDATE`) | Create with TTL, increment, verify expiry behavior per mode |

### PgLockRepository

| Behavior | Test approach |
|---|---|
| `acquire` on free lock succeeds | Acquire, assert held with correct owner token |
| `acquire` on held lock by different owner fails | Acquire as A, acquire as B, assert B fails |
| `acquire` on expired lock succeeds | Acquire with short lease, wait, acquire as different owner, assert success |
| `acquire` returns fencing token when requested | Acquire with fencing, assert token is monotonically increasing |
| `renew` by owner extends lease | Acquire, renew, assert `lease_expires_at` extended |
| `renew` by non-owner fails | Acquire as A, renew as B, assert failure |
| `release` by owner succeeds | Acquire, release, assert lock row gone or cleared |
| `release` by non-owner throws `LockNotHeldException` | Acquire as A, release as B, assert exception |
| `isHeldBy` returns true for current owner | Acquire as A, assert `isHeldBy(A)` true |
| `isHeldBy` returns false after expiry | Acquire with short lease, wait, assert false |
| Fencing token is monotonically increasing | Acquire/release/acquire cycle, assert second token > first |

## 5. Test infrastructure

### System property configuration

> **Mandated: inject configuration into tests; do not mutate JVM system properties.**
>
> `System.setProperty` and `System.clearProperty` change process-wide state. A parallel test can
> observe a partially updated configuration, a value belonging to another test, or a cleanup that
> occurs while it is still running. Save-and-restore boilerplate reduces leakage after a test but
> does not remove that race window.

All unit, integration, Vert.x, REST, and browser tests must construct configuration locally and
pass it directly to the code under test. Use the narrowest suitable value type: an immutable
configuration record, a builder result, `Properties`, a `Map<String, String>`, or a property lookup
function. Production code may read system properties once at the process boundary, but parsing and
validation must be exposed separately so tests can supply an isolated property source.

```java
Properties testProperties = new Properties();
testProperties.setProperty("feature.enabled", "true");

ComponentOptions options = ComponentOptions.from(testProperties::getProperty);
Component component = new Component(options);
```

The same rule applies to repository-specific test controls:

- pass the PostgreSQL image directly to `PostgreSQLContainer` and database settings through
  `PgConnectOptions` or the component's configuration object;
- pass runtime and bootstrap settings through their configuration records/builders;
- pass browser launch, observation, scenario-selection, report, and artifact settings through
  explicit option objects or property-source parameters;
- do not use `System.setProperty`/`System.clearProperty` in test setup, teardown, fixtures, or
  individual test methods to configure the subject under test.

Maven and CI may provide system properties when starting a test JVM, such as PostgreSQL matrix or
Playwright execution selectors. Those values must remain stable for the lifetime of that JVM and
must be copied into immutable configuration before concurrent test work begins. A test must not
change them after startup.

### Browser viewport scope

> **Mandated: the PeeGeeQ Cache management UI is desktop-only.**
>
> Browser tests must not create mobile or tablet product requirements by exercising unsupported
> viewport classes.

Playwright product tests use the supported `1440x900` desktop viewport. A test may use another
desktop viewport only when a documented desktop requirement needs it, and its width must be at
least 1280 CSS pixels. Do not add phone/tablet device profiles, touch emulation, mobile user agents,
narrow-viewport matrices, mobile breakpoint assertions, or mobile/tablet screenshot baselines.
Desktop zoom and accessibility checks remain valid, but the browser viewport itself must stay
within the supported desktop range. Graceful behavior outside that range is not a product contract
and receives no scenario credit.

### Browser screenshot evidence

Every reportable `PW-*` scenario must retain a PNG viewport capture and a focused element/panel
capture from the real browser before fixture cleanup. Follow the sibling PeeGeeQ management UI's
every-test capture policy and the Utilities UI's paired element/viewport attachment convention.
Use the supported 1440x900 viewport, disable animations during capture, and wait on visible state.
Before automatic target selection, wait for finite animations to settle so a closing dialog is not
selected as the result surface; continuous animations must not block capture. Do not use fixed sleeps.
The focused target is an explicit scenario locator or the visible dialog, alert, region, or main
surface. Additional checkpoints may capture intermediate states before a workflow dismisses them.

Capture passing and failing scenarios. Publish the complete passing catalogue to the UI module's
flat `docs/screenshots/` directory with descriptive feature/behavior filenames and a visible gallery,
following the sibling UI documentation screenshot convention. Scenario IDs are internal traceability,
not the user-facing screenshot organization. Raw diagnostic captures may retain a distinct run
directory and scenario ID plus sequence; link both images to their owning scenario in the portable HTML report. Passing
acceptance must fail if either image is missing, unreadable, assigned to another scenario, or has
an unsupported viewport. A failed scenario that cannot reach a capturable page must be reported as
failed with missing visual evidence, never represented by an unrelated or synthetic screenshot.

Capture the development UI exactly as the browser renders it. Do not add screenshot masks,
redaction overlays, text-suppression styles, replacement values, or canary-based pixel filtering.
This applies to every control and value, including passwords, editors, revealed values, and tokens.
Do not clear form values or mutate application state to produce evidence. The application's own
password inputs and explicit reveal/hide behavior remain unchanged; screenshot capture must neither
hide visible content nor reveal content the application has not displayed. This explicit user
requirement supersedes the earlier blanket screenshot-masking rule.
Screenshot infrastructure tests use real Chromium and a local HTTP fixture to verify PNG dimensions,
pixel equality with native browser screenshots, unchanged field values, and file-write failure propagation. They receive no product-scenario
credit. Generated screenshots and reports remain ignored output, including the published documentation
PNGs and generated gallery. Preserve source-run identity when republishing; do not claim that copying
existing verified captures constitutes a new browser run. Focused runs must not replace the full gallery.

### Browser fixture lifecycle

> **Mandated: reuse one registered setup for ordinary Playwright scenarios.**
>
> Browser-test isolation must not replay expensive product prerequisites before every assertion.

The complete Playwright run owns exactly one Playwright instance and one Chromium process. Every
scenario receives a fresh browser context; no scenario may launch or close a browser process.
Ordinary database-backed scenarios also share one PostgreSQL container, one management-server
application, one authenticated local session, and one registered and connected setup, with a
deterministic reset of mutable cache rows, counters, locks, and browser storage. Process-local
subscriptions must be stopped after their owning scenario. Fixture traffic must never visibly test
and register the same setup before every scenario, and the shared fixture must assert from durable
audit evidence that exactly one setup registration occurred. A source-policy gate must reject
`Playwright.create()` or browser launch calls outside the suite owner.

An isolated management application, session, or setup is allowed only when the behavior under test changes
global lifecycle state: empty-registry and setup registration, connect/detach/forget, authentication
session deletion or expiry, advertised-capability variants, trusted-proxy identity variants, or
deterministic server shutdown. These tests still use the suite Chromium process with fresh browser
contexts. They must select the isolated server fixture explicitly; ordinary
read, mutation, accessibility, privacy, navigation, and monitoring scenarios must use the shared
setup. Executable fixture-policy tests must fail if a lifecycle-mutating operation is accidentally
routed through the shared setup.

The only exception is a test whose subject is the thin system-property adapter itself. Such a test
must:

1. lock the JVM system-properties resource for its entire execution so it cannot run concurrently
   with another property-mutating test;
2. save every affected property's exact original state and restore it in `finally` or guaranteed
   teardown;
3. change the complete property set atomically from the test's perspective, never leaving a
   partially configured object visible; and
4. keep all behavioral parsing and validation tests on an injected property source.

This rule is required for all new and modified tests. Existing global-property tests are migration
debt and must be converted when touched; cleanup alone is not considered compliance.

### Management UI component and client tests

> **Mandated: no test doubles in `peegee-cache-management-ui`.**
>
> Vitest tests must exercise the real store, RTK Query middleware, router, Zustand stores, Zod
> schemas, and `src/api` clients. `vi.mock`, `vi.stubGlobal`, `vi.fn`, `vi.spyOn`, fetch or module
> replacement, and hand-built fakes of any client, port, transport, or store interface are
> prohibited. Pages must not accept client or port objects as props.

Every HTTP, SSE, or WebSocket response a component test needs is served by a purpose-built
`node:http` server on an ephemeral loopback port (`test/support/loopback-server.ts`). Every fixture
body that server returns is produced by the production Zod schema's `parse`, so a literal DTO that
bypasses runtime validation cannot exist. Sensitive reveal paths are tested the same way; their
values must be shown to be absent from stores, storage, URLs, and the DOM after cleanup.

> **Mandated: Ant Design and Recharts only.**
>
> UI controls are Ant Design 5 components; charts are Recharts. Hand-written tables, forms, dialogs,
> drawers, selects, tags, notifications, statistics, or charts are prohibited outside
> `src/components/common`, which may only compose Ant Design primitives.

The following `test/quality` guard tests run in the default Vitest gate and must stay green:
`component-library.guard.test.ts`, `no-test-fakes.guard.test.ts`,
`no-direct-transport.guard.test.ts`, `zod-fixture.guard.test.ts`, and
`sensitive-dto.guard.test.ts`. `@vitest/coverage-v8` enforces at least 80 percent branch coverage
on `src/api/**` and `src/state/**`. The Java Playwright catalogue continues to locate elements by
role and label; `data-testid` attributes are not introduced.

### Testcontainers setup

PostgreSQL via Testcontainers 2.0.2 and the standard `PostgreSQLContainer` integration. `peegeeq.test.postgres.image` selects the matrix image; the default remains `postgres:18.3-alpine`.

Container lifecycle is normally managed per test class using `@BeforeAll` / `@AfterAll`. The
Playwright suite is the deliberate exception: its JUnit root resource owns one PostgreSQL container
for the complete browser run and closes it only after all browser classes. Tests apply the bundled
bootstrap SQL through `BootstrapSqlRenderer` before exercising the database contract.

### Module install order

Before running `peegee-cache-pg` tests:
```
mvn install -N -q
mvn install -pl peegee-cache-api,peegee-cache-core -q
```

### Vert.x test pattern (Phase 4+)

```java
@ExtendWith(VertxExtension.class)
class PgCacheServiceTest {
    @Test
    void getReturnsValueForLiveKey(Vertx vertx, VertxTestContext ctx) {
        // Testcontainers PostgreSQL + Vert.x PgPool
        // All assertions via ctx.verify(() -> { ... })
        // ctx.completeNow() on success
    }
}
```

No `CompletableFuture.join()`, no `Thread.sleep()` for timing, no raw thread blocking on the event loop.

## 6. What is not tested

- Language mechanics (constructors pass arguments, `extends` creates subtypes)
- Getter return values on trivially correct records
- Maven build configuration
- Design document prose

Tests exist to guard contracts, catch regressions, and verify design decisions — not to inflate coverage numbers.
