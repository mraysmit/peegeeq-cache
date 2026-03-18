# peegee-cache Implementation Plan

## 1. Purpose

This document turns the design in `docs/design/PEEGEEQ_CACHE_DESIGN.md` into an implementation sequence.

It is not a second design document. It is a delivery plan for building Phase 1 in a controlled order, with explicit boundaries between:

- `V1 Core`
- remaining `V1`
- `V2` and later work

The goal is to prevent the project from drifting into premature feature expansion before the core PostgreSQL-backed cache and coordination behavior is correct.

## 2. Delivery principles

The implementation should follow these rules:

- finish correctness-critical primitives before convenience features
- keep public API design ahead of implementation details, but not far ahead
- encode semantics in SQL and schema constraints, not only in Java code
- keep runtime lifecycle explicit and testable
- defer broader feature coverage until `V1 Core` semantics are stable under concurrency
- avoid building Redis-compatibility surfaces before the Java and SQL contracts are coherent
- keep the implementation and public async contract pure Vert.x 5.x unless a deliberate compatibility layer is designed later

## 2.1 Execution guidelines and coding principles

This implementation plan should be executed with the local guidance in `docs/guidelines/pgq-coding-principles.md` treated as the primary working standard.

Core principles summary:

- investigate first
- follow existing patterns in the repo and sibling PeeGeeQ code where relevant
- verify assumptions instead of trusting first impressions
- fix root causes instead of masking symptoms
- document actual intent and behavior honestly
- validate incrementally after each small change
- classify tests correctly as unit versus integration work
- fail honestly when there is a real defect
- read logs carefully instead of relying on superficial success signals
- use modern Vert.x 5.x composable Future patterns instead of callback-style flow

Critical additional rules for implementation work:

- work incrementally and test after each small incremental change
- scan test logs in detail; do not rely on exit code alone
- use Maven debug mode with `-X` when test behavior is unclear
- verify that test methods actually executed, not just that Maven reported success
- remember dependent PeeGeeQ modules may need to be installed locally first when that dependency exists
- do not guess; use the coding principles and the existing code patterns
- do not continue to the next step until the current tests are passing
- do not skip failing tests with exclusions, disables, or build flags

Practical repo-specific guidance:

- this work is being done on Windows 11
- there are already many examples of Vert.x 5.x usage patterns in the imported guidance set
- there are already many examples of Testcontainers setup patterns in the broader PeeGeeQ guidance and sibling codebase
- imported guidance files should be used as reference patterns, not as proof that identical code already exists in this repo

## 2.2 Strict TDD and test discipline

Phase 1 implementation should follow strict TDD as a working method, not as a loose preference.

That means:

- write or extend a failing test first for each new behavior or bug fix whenever the behavior is testable
- implement the minimum code required to make the test pass
- refactor only after the behavior is covered and passing
- keep each change small enough that failures can be attributed to one decision at a time

Test discipline rules:

- do not move to the next implementation step until the current tests for the changed behavior are passing
- read the test output in detail after every run
- treat suspiciously fast or empty-success test runs as invalid until actual test execution is confirmed
- use integration tests for database behavior instead of fabricating confidence with mocks

## 2.3 No mocking and mandatory Testcontainers for database work

For this project, mocking is prohibited for database-facing behavior.

Mandatory rules:

- do not mock PostgreSQL connections, pools, repositories, or SQL execution
- do not substitute H2, HSQLDB, or in-memory databases for PostgreSQL behavior
- do not skip database tests because Testcontainers is slower than mocks
- use Testcontainers whenever the code under test touches PostgreSQL semantics, schema, migrations, queries, locking, counters, expiry, or runtime database integration

Allowed scope for pure unit tests:

- validation helpers
- key parsing and normalization
- small pure utility logic with zero database behavior

Everything else that depends on real PostgreSQL semantics should be treated as integration work.

## 2.4 Configuration-driven construction

Phase 1 should also be configuration-driven from the start.

That means:

- runtime and store behavior should be supplied through explicit configuration records and bootstrap options
- libraries should accept validated configuration from callers rather than pulling hidden fallback values from system properties
- configuration defaults should be explicit, documented, and small enough to reason about
- no hardcoded schema names, channel names, or environment-specific paths in core modules
- configuration should map cleanly to Vert.x and PostgreSQL concepts such as pool sizing, timeouts, schema names, listener behavior, and sweeper behavior

Operational implication:

- if behavior matters in production, it should be representable in configuration rather than buried in implementation constants

## 2.5 Pure Vert.x 5.x baseline

The Phase 1 implementation should be explicitly Vert.x 5.x first and pure in its public surface.

That means:

- public async APIs return `io.vertx.core.Future<T>`
- runtime composition uses Vert.x 5.x `Future` chaining such as `.compose()`, `.map()`, `.recover()`, `.onSuccess()`, and `.onFailure()`
- database access uses Vert.x reactive PostgreSQL client APIs directly
- lifecycle and background work follow Vert.x-managed patterns rather than ad hoc thread ownership

Phase 1 should avoid mixing in alternative async contracts or framework-first abstractions in the main public API, including:

- `CompletableFuture` or `CompletionStage` as the primary public contract
- Reactor types
- Mutiny as the primary API surface
- Spring-specific abstractions in core modules

This does not forbid later adapters. It means adapters should sit on top of the Vert.x-native core rather than weakening the main contract during Phase 1.

## 3. Phase map

### Phase 0: Repository and build foundation

Objective:

- establish the module skeleton and build layout so feature work lands in the right place

Scope:

- multi-module Maven parent
- module boundaries for `api`, `core`, `pg`, `runtime`, `observability`, `test-support`, and `examples`
- baseline Java and Vert.x version alignment

Exit criteria:

- root build validates
- all intended Phase 1 modules exist
- package layout matches the design document

Status: **COMPLETE**

- 7 modules created: `api`, `core`, `pg`, `runtime`, `observability`, `test-support`, `examples`
- Java 21 + Vert.x 5.0.8 baseline aligned
- root build validates

### Phase 1: API skeleton

Objective:

- define the public Java contract for `V1 Core`

Scope:

- `PeeGeeCache`
- `CacheService`
- `CounterService`
- `LockService`
- `ScanService`
- `PubSubService`
- core records and enums such as `CacheKey`, `CacheValue`, `CacheEntry`, `TtlResult`, `CounterOptions`, `CounterTtlMode`, `LockState`, `PublishRequest`, `PubSubMessage`
- exception hierarchy (`CacheException`, `CacheKeyException`, `CacheStoreException`, `LockNotHeldException`)

Out of scope:

- full implementation logic
- rich convenience overloads
- `V2` structures such as hashes and sorted sets

Exit criteria:

- API types compile cleanly
- package boundaries are stable enough for downstream modules
- public types reflect the design’s `V1 Core` semantics
- the public async surface is consistently Vert.x 5.x `Future`-based
Status: **COMPLETE**

- 33 source files: 5 service interfaces, 4 enums, 3 validated records, 12 data records, 4 exception classes, 1 facade
- 34 unit tests (CacheKeyTest 8, CacheValueTest 13, LockKeyTest 7, ExceptionHierarchyTest 6)
- `PeeGeeCacheManager` deferred to Phase 5 — its signature depends on config records that belong in the runtime bootstrap phase
### Phase 2: PostgreSQL schema and migrations

Objective:

- establish the database contract for cache entries, counters, and locks

Scope:

- migration files for schema creation
- `cache_entries`, `cache_counters`, `cache_locks`
- indexes and lock fencing sequence
- database checks and invariants from the design
- optional `UNLOGGED` posture only where explicitly intended

Out of scope:

- broad SQL function surface
- advanced observability views

Exit criteria:

- migrations create the Phase 1 schema on a clean PostgreSQL instance
- invariants such as typed payload exclusivity and lock lease sanity are enforced in SQL
- migration naming and ordering are stable

Status: **COMPLETE**

- single migration file: `V001__create_peegee_cache_schema.sql` (schema, 3 tables, 1 sequence, 5 indexes)
- 27 integration tests against real PostgreSQL via Testcontainers
- all check constraints, primary keys, indexes, and sequence monotonicity verified

### Phase 3: Repository and SQL statement catalogue

Objective:

- implement the data-access layer for `V1 Core`

Scope:

- `PgCacheRepository`
- `PgCounterRepository`
- `PgLockRepository`
- SQL statement catalogue for:
  - get/set/delete
  - TTL lookup, expire, persist
  - counter increment/decrement/set
  - lock acquire, renew, release, inspect
- row mappers
- transaction helpers for expiry-aware and lock-aware flows

Out of scope:

- bulk operations
- scan/listing
- pub/sub listener runtime

Exit criteria:

- repository methods exist for every `V1 Core` primitive
- expiry-aware semantics are implemented with database-clock logic
- lock semantics use owner token checks and database-derived lease expiry

### Phase 4: Service implementations for V1 Core

Objective:

- expose usable Java services backed by the PostgreSQL repositories

Scope:

- `PgCacheService`
- `PgCounterService`
- `PgLockService`
- `PgPeeGeeCache`
- validation and normalization logic in `peegee-cache-core`

Out of scope:

- remaining `V1` features such as scan and lightweight pub/sub
- SQL function productization for external callers

Exit criteria:

- `V1 Core` public interfaces are backed by working implementations
- type and option validation is consistent
- service behavior matches the design document for expiry, counters, and lock ownership

### Phase 5: Runtime bootstrap and managed lifecycle

Objective:

- make the library usable as a managed embedded runtime

Scope:

- `PeeGeeCacheFactory`
- `PeeGeeCaches`
- `PeeGeeCacheManager` (deferred from Phase 1 — interface signature depends on config records defined here)
- bootstrap options and config records
- explicit start/stop semantics
- expiry sweeper runtime

Out of scope:

- full observability integrations
- advanced runtime self-healing behavior

Exit criteria:

- callers can create a manager and start/stop it cleanly
- ownership of `Vertx`, `Pool`, sweeper, and listener resources is explicit
- shutdown order is deterministic and non-accidental

### Phase 6: V1 completion features

Objective:

- add the remaining features still considered part of Phase 1

Scope:

- bulk get/set/delete
- scan/list by namespace and prefix
- metadata/versioning exposure
- lightweight pub/sub
- admin and metrics hooks

Recommended order inside this phase:

1. scan and metadata
2. bulk operations
3. admin hooks
4. lightweight pub/sub

Reasoning:

- scan and metadata support debugging and operational verification early
- bulk operations are useful but do not change the core model
- pub/sub should wait until the runtime lifecycle and payload rules are already stable

#### Phase 6.4: Lightweight pub/sub implementation plan

Prerequisite: the runtime lifecycle (Phase 5) is stable and `PgConnectOptions` are available in `PeeGeeCacheBootstrapOptions`.

##### Module placement

- `PgPubSubRepository` in `peegee-cache-pg` — SQL execution for `pg_notify`, `LISTEN`, `UNLISTEN`
- `PgPubSubService` in `peegee-cache-pg` — implements `PubSubService`, manages dedicated listener connection, handler registry, reconnection
- Wired into `PgPeeGeeCache` via `PgPeeGeeCacheManager`, replacing `NotImplementedStubs.pubSubService()`

##### Implementation steps (TDD order)

1. **PgPubSubRepository — publish**
   - SQL: `SELECT pg_notify($1, $2)` with fully qualified channel name
   - Channel name: `{prefix}__{channel}` using `PgCacheStoreConfig.pubSubChannelPrefix()`
   - Validate: non-null/non-blank channel, payload size ≤ `maxPayloadBytes`
   - Test: Testcontainers integration test — publish succeeds, oversized payload rejected

2. **PgPubSubService — publish**
   - Delegates validation and SQL to repository
   - Returns `Future<Integer>` (always 1 on success)
   - Test: service-level publish test

3. **PgPubSubService — dedicated listener connection**
   - Opens `PgConnection.connect(vertx, connectOptions)` during `start()`
   - Sets `conn.notificationHandler()` to dispatch to registered handlers
   - Closes connection during `stop()`
   - Test: lifecycle test — connection opens on start, closes on stop

4. **PgPubSubService — subscribe**
   - Issues `LISTEN "channel"` on dedicated connection
   - Registers handler in `ConcurrentHashMap<String, CopyOnWriteArrayList<Consumer<PubSubMessage>>>`
   - Returns `Subscription` with `unsubscribe()` that removes handler and issues `UNLISTEN` when registry for channel is empty
   - Test: subscribe receives published message, unsubscribe stops delivery

5. **PgPubSubService — reconnection**
   - Register close handler on dedicated connection
   - On unexpected close: schedule reconnect with exponential backoff (1s base, 32s cap, 5 attempts)
   - After reconnect: replay `LISTEN` for all channels with active handlers
   - Test: kill connection, verify automatic reconnect and handler still receives after recovery

6. **PgPeeGeeCacheManager wiring**
   - Replace `NotImplementedStubs.pubSubService()` with real `PgPubSubService`
   - `startReactive()` opens listener connection
   - `stopReactive()` closes listener connection and clears handlers
   - `isListenerRunning()` reflects real connection state
   - Remove `NotImplementedStubs` class entirely when no stubs remain
   - Test: existing lifecycle test still passes, stub test updated or removed

7. **PeeGeeCacheBootstrapOptions update**
   - Add `PgConnectOptions connectOptions` to `PeeGeeCacheBootstrapOptions`
   - The connect options supply credentials and host for the dedicated listener connection
   - When absent, derive from the pool's connection info or fail with clear error
   - Test: options with and without explicit connect options

##### Configuration additions

`PgCacheStoreConfig` gains:

- `maxPayloadBytes` (default 7500) — publish requests exceeding this size are rejected with `IllegalArgumentException`

##### Exit criteria for Phase 6.4 — MET

- ✅ `PgPubSubService` passes all tests including Testcontainers integration (9 tests)
- ✅ publish delivers notification to subscriber handler (`subscriberReceivesPublishedMessage`)
- ✅ unsubscribe stops delivery (`unsubscribeStopsDelivery`)
- ✅ oversized payloads are rejected before reaching PostgreSQL (`publishRejectsOversizedPayload`)
- ✅ dedicated connection reconnects automatically after connection loss (exponential backoff, 1s–32s)
- ✅ `isListenerRunning()` accurately reflects connection state (delegates to `pubSubService.isListenerConnected()`)
- ⚠️ `NotImplementedStubs.pubSubService()` retained as fallback when no `connectOptions` provided — this is intentional for backward compatibility

Exit criteria:

- all `V1` items in the design are implemented or explicitly deferred with rationale
- operational behavior is documented well enough for first external adopters

### Phase 7: Native SQL contract hardening

Objective:

- decide how far the out-of-the-box SQL interface is part of the supported product surface

Scope:

- documented read views
- correctness-sensitive SQL functions for locks and counters first
- optional cache write functions if non-Java callers are required in the first release
- compatibility policy for SQL functions if exposed publicly

Exit criteria:

- direct-read versus function-write support boundaries are documented and implemented consistently
- external SQL callers are not forced to reconstruct concurrency-sensitive multi-statement logic from prose alone

### Phase 8: V2 and later

Objective:

- extend the product only after Phase 1 is stable under realistic usage

Scope candidates:

- hashes
- sorted sets
- durable queue semantics
- delayed jobs
- rate limiting
- keyspace notifications

Explicit non-goal unless strategy changes:

- full Redis protocol compatibility

## 3.1 Implementation tracking

Status legend:

- COMPLETE: implemented with passing tests and documented evidence
- IN PROGRESS: partial implementation exists, exit criteria not fully met
- NOT STARTED: no meaningful implementation work landed yet
- DEFERRED: intentionally postponed with rationale

Last reviewed: 2026-03-16

| Phase | Status | Evidence snapshot | Remaining to exit |
|---|---|---|---|
| Phase 0: Repository and build foundation | COMPLETE | Multi-module structure present and builds; Java 21 + Vert.x baseline already aligned in repository setup | None |
| Phase 1: API skeleton | COMPLETE | API interfaces and models exist in `peegee-cache-api/src/main/java/dev/mars/peegeeq/cache/api/**`; unit tests for core value objects and exceptions are present and previously documented | None |
| Phase 2: PostgreSQL schema and migrations | COMPLETE | `peegee-cache-pg/src/main/resources/db/migration/V001__create_peegee_cache_schema.sql` plus migration/invariant tests are already documented in this plan | None |
| Phase 3: Repository and SQL statement catalogue | COMPLETE | Repositories and SQL catalogues are in place: `PgCacheRepository`, `PgCounterRepository`, `PgLockRepository` and `CacheSql`, `CounterSql`, `LockSql` in `peegee-cache-pg/src/main/java/dev/mars/peegeeq/cache/pg/` | None |
| Phase 4: Service implementations for V1 Core | COMPLETE | Services are implemented and now share centralized argument validation via `peegee-cache-core/src/main/java/dev/mars/peegeeq/cache/core/validation/CoreValidation.java`, wired in `PgCacheService`, `PgCounterService`, and `PgLockService`; service/repository/migration tests are green | None |
| Phase 5: Runtime bootstrap and managed lifecycle | COMPLETE | Runtime bootstrap is implemented with explicit ownership of `Vertx`, `Pool`, expiry sweeper timer, and pub/sub listener lifecycle placeholder in `PgPeeGeeCacheManager`; lifecycle tests in `PeeGeeCachesLifecycleTest` verify start/stop ownership and invalid sweeper config guards | None |
| Phase 6: V1 completion features | COMPLETE | Scan: `PgScanRepository`, `PgScanService`, `CacheEntryMapper`, 17 tests green. Bulk ops: `getMany`/`setMany`/`deleteMany` in `PgCacheRepository`/`PgCacheService` with tests. Pub/sub: **Phase 6.4 COMPLETE** — `PubSubSql`, `PgPubSubRepository` (7 tests), `PgPubSubService` (9 tests), `PgCacheStoreConfig.maxPayloadBytes`, `PeeGeeCacheBootstrapOptions.connectOptions`, `PgPeeGeeCacheManager` wired with real pub/sub service, lifecycle tests updated. **Admin hooks: COMPLETE** — `MetricsSnapshot` (15-counter record), `EntryStats` record, `AdminService` interface in api; `CacheMetrics` (LongAdder-based, 8 unit tests) in core; `AdminSql`, `PgAdminRepository` (6 integration tests), `PgAdminService` (4 integration tests) in pg; `CacheMetrics` wired into `PgCacheService`, `PgCounterService`, `PgLockService`; `PeeGeeCache.admin()` added; `PgPeeGeeCacheManager` creates shared `CacheMetrics` and `PgAdminService`. 197 tests green across all modules. | None |
| Phase 7: Native SQL contract hardening | NOT STARTED | No documented SQL function boundary implementation yet | Define and implement function-first write contract for correctness-sensitive operations |
| Phase 8: V2 and later | DEFERRED | By strategy: V2 starts only after V1 is stable | Revisit after V1 completion and operational hardening |

Tracking update rules:

1. update this table in the same change set that advances a phase status
2. do not mark COMPLETE until the phase exit criteria in this document are objectively met
3. include concrete evidence (classes, migrations, tests) in each status change
4. if status changes are uncertain, keep the lower status and add a verification task

## 3.2 Strict verification (2026-03-16)

This section records a criteria-by-criteria verification pass for the currently active delivery phases.

### Phase 3 strict verification

Verdict: COMPLETE

| Exit criterion | Result | Evidence | Notes |
|---|---|---|---|
| repository methods exist for every `V1 Core` primitive | PASS | `peegee-cache-pg/src/main/java/dev/mars/peegeeq/cache/pg/repository/PgCacheRepository.java`, `peegee-cache-pg/src/main/java/dev/mars/peegeeq/cache/pg/repository/PgCounterRepository.java`, `peegee-cache-pg/src/main/java/dev/mars/peegeeq/cache/pg/repository/PgLockRepository.java` | Core primitive coverage exists for cache/counter/lock repository operations |
| expiry-aware semantics are implemented with database-clock logic | PASS | `peegee-cache-pg/src/main/java/dev/mars/peegeeq/cache/pg/sql/CacheSql.java`, `peegee-cache-pg/src/main/java/dev/mars/peegeeq/cache/pg/sql/CounterSql.java`, `peegee-cache-pg/src/main/java/dev/mars/peegeeq/cache/pg/repository/PgCacheRepository.java`, `peegee-cache-pg/src/main/java/dev/mars/peegeeq/cache/pg/repository/PgCounterRepository.java` | UPSERT/insert/update TTL paths now use SQL `NOW() + interval` expressions with TTL millis parameters |
| lock semantics use owner token checks and database-derived lease expiry | PASS | `peegee-cache-pg/src/main/java/dev/mars/peegeeq/cache/pg/sql/LockSql.java`, `peegee-cache-pg/src/main/java/dev/mars/peegeeq/cache/pg/repository/PgLockRepository.java` | SQL uses `NOW() + interval` and owner-token-guarded renew/release semantics |

### Phase 4 strict verification

Verdict: COMPLETE

| Exit criterion | Result | Evidence | Notes |
|---|---|---|---|
| `V1 Core` public interfaces are backed by working implementations | PASS (for cache/counter/lock) | `peegee-cache-pg/src/main/java/dev/mars/peegeeq/cache/pg/service/PgCacheService.java`, `peegee-cache-pg/src/main/java/dev/mars/peegeeq/cache/pg/service/PgCounterService.java`, `peegee-cache-pg/src/main/java/dev/mars/peegeeq/cache/pg/service/PgLockService.java`, `peegee-cache-pg/src/main/java/dev/mars/peegeeq/cache/pg/PgPeeGeeCache.java` | Implementations exist and are wired through `PgPeeGeeCache` |
| type and option validation is consistent | PASS | `peegee-cache-core/src/main/java/dev/mars/peegeeq/cache/core/validation/CoreValidation.java`, `peegee-cache-core/src/test/java/dev/mars/peegeeq/cache/core/validation/CoreValidationTest.java`, `peegee-cache-pg/src/main/java/dev/mars/peegeeq/cache/pg/service/PgCacheService.java`, `peegee-cache-pg/src/main/java/dev/mars/peegeeq/cache/pg/service/PgCounterService.java`, `peegee-cache-pg/src/main/java/dev/mars/peegeeq/cache/pg/service/PgLockService.java`, `peegee-cache-pg/src/test/java/dev/mars/peegeeq/cache/pg/service/PgLockServiceTest.java` | Shared core validation now enforces non-null, non-blank, and positive-duration argument rules across services |
| service behavior matches the design document for expiry, counters, and lock ownership | PASS (for implemented V1 Core surface) | `peegee-cache-pg/src/main/java/dev/mars/peegeeq/cache/pg/repository/PgCacheRepository.java`, `peegee-cache-pg/src/main/java/dev/mars/peegeeq/cache/pg/repository/PgCounterRepository.java`, `peegee-cache-pg/src/main/java/dev/mars/peegeeq/cache/pg/repository/PgLockRepository.java`, `peegee-cache-pg/src/main/java/dev/mars/peegeeq/cache/pg/sql/CacheSql.java`, `peegee-cache-pg/src/main/java/dev/mars/peegeeq/cache/pg/sql/CounterSql.java`, `peegee-cache-pg/src/main/java/dev/mars/peegeeq/cache/pg/sql/LockSql.java` | TTL writes use database-clock SQL interval expressions and lock ownership checks are enforced in SQL/repository behavior |

### Phase 5 strict verification

Verdict: COMPLETE

| Exit criterion | Result | Evidence | Notes |
|---|---|---|---|
| callers can create a manager and start/stop it cleanly | PASS | `peegee-cache-runtime/src/main/java/dev/mars/peegeeq/cache/runtime/bootstrap/PeeGeeCaches.java`, `peegee-cache-runtime/src/main/java/dev/mars/peegeeq/cache/runtime/bootstrap/PgPeeGeeCacheManager.java`, `peegee-cache-runtime/src/test/java/dev/mars/peegeeq/cache/runtime/bootstrap/PeeGeeCachesLifecycleTest.java` | Lifecycle tests cover create/start/stop, duplicate start/stop guards, and close delegation |
| ownership of `Vertx`, `Pool`, sweeper, and listener resources is explicit | PASS | `peegee-cache-runtime/src/main/java/dev/mars/peegeeq/cache/runtime/bootstrap/PgPeeGeeCacheManager.java`, `peegee-cache-runtime/src/main/java/dev/mars/peegeeq/cache/runtime/config/PeeGeeCacheConfig.java`, `peegee-cache-runtime/src/test/java/dev/mars/peegeeq/cache/runtime/bootstrap/PeeGeeCachesLifecycleTest.java` | Manager now owns sweeper timer lifecycle and listener lifecycle state with explicit start/stop behavior and config validation |
| shutdown order is deterministic and non-accidental | PASS | `peegee-cache-runtime/src/main/java/dev/mars/peegeeq/cache/runtime/bootstrap/PgPeeGeeCacheManager.java`, `peegee-cache-runtime/src/test/java/dev/mars/peegeeq/cache/runtime/bootstrap/PeeGeeCachesLifecycleTest.java` | Stop path transitions started state and then stops background components in a deterministic order |

Execution evidence from strict run:

- command executed: `mvn -pl peegee-cache-pg -am test`
- reactor result: BUILD SUCCESS
- module tests observed:
  - `peegee-cache-api`: 34 tests passed
  - `peegee-cache-core`: 4 tests passed (`CoreValidationTest`)
  - `peegee-cache-pg`: 88 tests passed (migration, repository, and service suites)
- command executed: `mvn -pl peegee-cache-runtime -am test`
- reactor result: BUILD SUCCESS
- module tests observed:
  - `peegee-cache-runtime`: 13 tests passed (`PeeGeeCachesLifecycleTest`)

Conclusion:

- test suite is green for current implementation
- Phase 3 exit criteria are satisfied
- Phase 4 exit criteria are now satisfied
- Phase 5 exit criteria are now satisfied

## 4. Feature rollout by milestone

### Milestone A: V1 Core alpha

Target outcome:

- usable embedded cache, counter, and lock library for Java callers

Must include:

- key/value get/set/delete
- TTL and expiry
- atomic conditional writes
- atomic counters
- namespaces
- lock service
- migrations
- managed bootstrap

Must not include:

- hashes
- sorted sets
- Redis protocol adaptation
- durable queue features

### Milestone B: V1 beta

Target outcome:

- operationally testable Phase 1 library with admin-facing capabilities

Must include:

- scan/listing
- metadata/versioning support
- bulk operations
- initial admin hooks
- stronger integration-test coverage

### Milestone C: V1 release candidate

Target outcome:

- complete Phase 1 surface as currently defined

Must include:

- lightweight pub/sub
- documented runtime behavior
- documented SQL support boundaries
- examples and operator guidance

## 5. Module-by-module implementation order

### `peegee-cache-api`

Implement first:

- all public interfaces and models for `V1 Core`

Implement later:

- remaining `V1` request/response types for scan and pub/sub where needed

### `peegee-cache-core`

Implement first:

- validation utilities
- TTL normalization helpers
- key and namespace rules

Implement later:

- shared result builders and metrics support utilities

### `peegee-cache-pg`

Implement first:

- migrations
- repositories
- SQL catalogue
- row mapping
- core PostgreSQL-backed services

Implement later:

- scan repository
- pub/sub runtime integration details
- SQL function layer for external callers

### `peegee-cache-runtime`

Implement first:

- bootstrap options
- manager lifecycle
- explicit startup and shutdown

Implement later:

- sweeper scheduling
- dedicated pub/sub listener management

### `peegee-cache-observability`

Implement first:

- no-op or minimal interfaces only if needed to avoid contaminating core delivery

Implement later:

- Micrometer and OpenTelemetry integration

### `peegee-cache-test-support`

Implement first:

- PostgreSQL Testcontainers helpers
- schema bootstrap fixtures
- concurrency-test helpers

Implement later:

- higher-level DSLs for repetitive test flows

### `peegee-cache-examples`

Implement first:

- one embedded example using the managed runtime

Implement later:

- examples for SQL inspection, locks, and pub/sub

## 6. Testing plan by phase

### Phase 1 through Phase 3

- compile validation for module graph
- migration smoke tests against real PostgreSQL
- repository tests for single-operation correctness

### Phase 4 through Phase 5

- service-level integration tests with Testcontainers
- concurrency tests for:
  - conditional writes
  - counter races
  - lock acquire races
  - lock renew versus release
- lifecycle tests for managed startup and shutdown

### Phase 6 onward

- scan behavior tests
- pub/sub integration tests:
  - publish delivers to subscriber handler on same Vert.x instance
  - multiple subscribers on same channel each receive notification
  - unsubscribe stops delivery for that handler
  - oversized payload rejected before reaching PostgreSQL
  - channel name validation (null, blank)
  - dedicated listener connection lifecycle (start opens, stop closes)
  - automatic reconnection after connection loss with LISTEN replay
  - publish and subscribe rejected when manager is not started
- benchmark and profiling work on realistic combined flows

The important benchmark is not isolated `GET` speed alone. It is the combined behavior of database-backed cache mutation, coordination, and notification flows.

## 7. Key risks and control points

### Risk: Phase 1 scope creep

Control:

- do not add hashes, sorted sets, or queue semantics before `V1 Core` is stable

### Risk: treating PostgreSQL like an in-memory cache without operational cost

Control:

- validate churn-heavy behavior with realistic integration tests and benchmarks

### Risk: weak lock semantics due to caller-clock assumptions

Control:

- derive lease expiry from the database clock in SQL

### Risk: unstable external SQL write behavior

Control:

- support direct reads first
- expose SQL functions for correctness-sensitive writes

### Risk: runtime lifecycle bugs

Control:

- keep manager ownership explicit
- test shutdown order and background component cleanup early

## 8. Immediate recommended next steps

The next implementation moves should be:

1. create the `peegee-cache-api` skeleton for `V1 Core`
2. add initial PostgreSQL migrations in `peegee-cache-pg`
3. implement repository interfaces and SQL statement catalogue
4. add Testcontainers-based migration and repository tests
5. wire the first managed runtime path in `peegee-cache-runtime`

## 9. Summary

The correct delivery strategy is:

- build `V1 Core` first
- finish the database contract before convenience features
- complete the runtime lifecycle before broadening the surface area
- treat native SQL support as deliberate product surface, not accidental table exposure
- defer Redis-shaped expansion until the PostgreSQL-native core is proven