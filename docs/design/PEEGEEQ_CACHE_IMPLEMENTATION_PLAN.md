# peegee-cache Implementation Plan

## 1. Purpose

This document turns the design in `docs/design/PEEGEEQ_CACHE_DESIGN.md` into an implementation sequence.

It is not a second design document. It is the authoritative delivery and current-status plan for building Phase 1 in a controlled order, with explicit boundaries between:

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

## 2.3 No mocking frameworks and mandatory Testcontainers for database work

Mockito is prohibited everywhere in this project. Do not add Mockito dependencies, imports, extensions, agents, configuration, examples, or generated tests. Do not substitute another mocking framework to evade this rule.

Purpose-built fakes or test doubles are allowed only where they exercise a pure, non-database boundary through its real public contract. They must remain small, explicit, and behavior-focused.

Mandatory rules:

- do not use mocking frameworks in unit or integration tests
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
- module boundaries for `api`, `core`, `pg`, `runtime`, `observability`, `test-support`, `benchmarks`, and `examples`
- baseline Java and Vert.x version alignment

Exit criteria:

- root build validates
- all intended Phase 1 modules exist
- package layout matches the design document

Status: **COMPLETE**

- 8 modules created: `api`, `core`, `pg`, `runtime`, `observability`, `test-support`, `benchmarks`, `examples`
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

- 36 current source files, including 6 service interfaces, the subscription contract, public models and exceptions, and the `PeeGeeCache` facade
- 34 unit tests (CacheKeyTest 8, CacheValueTest 13, LockKeyTest 7, ExceptionHierarchyTest 6)
- `PeeGeeCacheManager` deferred to Phase 5 — its signature depends on config records that belong in the runtime bootstrap phase
### Phase 2: PostgreSQL schema and bootstrap

Objective:

- establish the database contract for cache entries, counters, and locks

Scope:

- bootstrap SQL for schema creation
- `cache_entries`, `cache_counters`, `cache_locks`
- indexes and lock fencing sequence
- database checks and invariants from the design
- optional `UNLOGGED` posture only where explicitly intended

Out of scope:

- broad SQL function surface
- advanced observability views

Exit criteria:

- bootstrap SQL creates the Phase 1 schema on a clean PostgreSQL instance
- invariants such as typed payload exclusivity and lock lease sanity are enforced in SQL
- bootstrap resource naming and loading are stable

Status: **COMPLETE**

- single bootstrap resource: `db/bootstrap/V001__create_peegee_cache_schema.sql` (schema, 3 domain tables, migration ledger, 1 sequence, 5 indexes, 8 supported SQL functions, and 3 stable read views)
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

Status: **COMPLETE**

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

Status: **COMPLETE**

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

Status: **COMPLETE**

### Phase 6: V1 completion features

Objective:

- add the remaining features still considered part of Phase 1

Scope:

- bulk get/set/delete
- scan/list by namespace and prefix
- metadata/versioning exposure
- lightweight pub/sub
- admin and metrics hooks
- production telemetry and schema readiness

Recommended order inside this phase:

1. scan and metadata
2. bulk operations
3. admin hooks
4. lightweight pub/sub
5. production observability and readiness hardening

Reasoning:

- scan and metadata support debugging and operational verification early
- bulk operations are useful but do not change the core model
- pub/sub should wait until the runtime lifecycle and payload rules are already stable
- production observability is a release gate and is completed after the service and lifecycle surfaces it must observe are stable

#### Phase 6.4: Lightweight pub/sub implementation plan

Prerequisite: the runtime lifecycle (Phase 5) is stable and `PgConnectOptions` are available in `PeeGeeCacheBootstrapOptions`.

##### Module placement

- `PgPubSubRepository` in `peegee-cache-pg` — validation and parameterized `pg_notify` execution through the shared pool
- `PgPubSubService` in `peegee-cache-pg` — implements `PubSubService` and owns `LISTEN`/`UNLISTEN`, the dedicated listener connection, handler registry, and reconnection
- `PgPeeGeeCacheManager` wires the real service when `PgConnectOptions` are supplied and exposes the unavailable-service fallback otherwise

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
   - Quotes the fully qualified channel as a PostgreSQL identifier by doubling embedded double quotes before constructing `LISTEN`/`UNLISTEN`
   - Rejects NUL and qualified channel names longer than PostgreSQL's 63-byte identifier limit instead of relying on server truncation
   - Never interpolates an unescaped caller-controlled identifier into SQL
   - Test: subscribe receives published message, unsubscribe stops delivery
   - Test: quotes, semicolons, whitespace, and other SQL metacharacters round-trip as channel data without executing unintended SQL; NUL and overlength identifiers are rejected

5. **PgPubSubService — reconnection**
   - Register close handler on dedicated connection
   - On unexpected close: schedule reconnect with exponential backoff (1s base, 32s cap, 5 attempts)
   - After reconnect: replay `LISTEN` for all channels with active handlers
   - Test: kill connection, verify automatic reconnect and handler still receives after recovery

6. **PgPeeGeeCacheManager wiring**
   - Use real `PgPubSubService` when dedicated listener connection options are configured
   - `startReactive()` opens listener connection
   - `stopReactive()` closes listener connection and clears handlers
   - `isListenerRunning()` reflects real connection state
   - Retain an explicit unavailable-service fallback when pub/sub is not configured
   - Test: lifecycle and unavailable-service behavior remain explicit

7. **PeeGeeCacheBootstrapOptions update**
   - Add `PgConnectOptions connectOptions` to `PeeGeeCacheBootstrapOptions`
   - The connect options supply credentials and host for the dedicated listener connection
   - When absent, leave pub/sub unavailable while allowing cache, counter, lock, scan, and admin services to operate
   - Test: options with and without explicit connect options

##### Configuration additions

`PgCacheStoreConfig` gains:

- `maxPayloadBytes` (default 7500) — publish requests exceeding this size are rejected with `IllegalArgumentException`

##### Current status for Phase 6.4

- ✅ `PgPubSubService` passes all tests including Testcontainers integration (12 tests)
- ✅ publish delivers notification to subscriber handler (`subscriberReceivesPublishedMessage`)
- ✅ unsubscribe stops delivery (`unsubscribeStopsDelivery`)
- ✅ oversized payloads are rejected before reaching PostgreSQL (`publishRejectsOversizedPayload`)
- ✅ dedicated connection reconnects automatically after connection loss (exponential backoff, 1s–32s)
- ✅ `isListenerRunning()` accurately reflects connection state (delegates to `pubSubService.isListenerConnected()`)
- ✅ unavailable-service fallback retained when no `connectOptions` are provided
- ✅ caller-controlled `LISTEN`/`UNLISTEN` identifiers use PostgreSQL quote doubling, reject NUL, and enforce the 63-byte UTF-8 identifier limit
- ✅ adversarial PostgreSQL coverage round-trips embedded quotes, semicolons, whitespace, and SQL metacharacters without unintended execution
- ✅ the unavailable-service fallback emits bounded failure telemetry and does not include request payloads in errors

Exit criteria:

- all `V1` items in the design are implemented or explicitly deferred with rationale
- operational behavior is documented well enough for first external adopters

Status: **COMPLETE** — the V1 completion surface, safe pub/sub handling, production observability, comprehensive readiness, and benchmark evidence are implemented and verified.

#### Phase 6.5: Production observability

Objective:

- make observability an operational contract of the runtime rather than an optional demonstration adapter

Scope:

- vendor-neutral telemetry SPI and composite fan-out in `peegee-cache-core`
- runtime injection through `PeeGeeCacheBootstrapOptions`
- Micrometer metrics and OpenTelemetry metrics/tracing adapters
- bounded operation and outcome dimensions with no user-controlled keys, namespaces, channels, payloads, SQL, or exception messages
- complete asynchronous service-operation timing, failure capture, and trace context propagation
- lock contention, expiry sweep, pub/sub reconnect, notification dispatch, subscription, schema-bootstrap, and lifecycle signals
- PostgreSQL readiness covering runtime state, connectivity, and every database object required for enabled services
- exporter failure isolation so telemetry cannot alter cache behavior

Implemented evidence:

- ✅ `CacheTelemetry`, `CompositeCacheTelemetry`, and bounded `CacheOperation` are implemented
- ✅ Micrometer and OpenTelemetry adapters have behavior tests without mocking frameworks
- ✅ runtime and service wiring record successful and failed asynchronous operations
- ✅ expiry, contention, pub/sub, subscription, notification-dispatch, bootstrap, and lifecycle signals are present
- ✅ telemetry implementation failures are isolated from product behavior
- ✅ readiness verifies all required tables, indexes, the fencing sequence, migration ledger, stable views, and exact supported function signatures
- ✅ one real-runtime contract test exercises the complete asynchronous service surface and requires all 30 bounded operations to start and complete
- ✅ unavailable pub/sub records a bounded failed operation without exposing request payloads
- ✅ the benchmark interleaves noop and Micrometer traffic under the same interval and reports throughput and p99 overhead

Exit criteria:

- all asynchronous service operations are observed, including validation and lifecycle rejection failures
- metric and span dimensions remain bounded under adversarial caller inputs
- telemetry callbacks remain bounded and non-blocking by contract, and exporter failures cannot fail product operations
- a contract test maps every asynchronous service method to its bounded `CacheOperation`, including validation and lifecycle rejection failures
- benchmark evidence characterizes enabled-versus-noop telemetry overhead under representative traffic
- readiness verifies `cache_entries`, `cache_counters`, `cache_locks`, `lock_fencing_seq`, and the supported SQL functions required by the configured runtime
- readiness reports `DOWN` with a safe diagnostic when any required object is absent
- Micrometer, OpenTelemetry, lifecycle, and readiness behavior is covered by real implementations and real PostgreSQL where applicable

Status: **COMPLETE** — observability is runtime-wired, contract-tested across all operations, failure-isolated, schema-comprehensive, and represented in the benchmark harness.

### Phase 7: Native SQL contract hardening

Objective:

- decide how far the out-of-the-box SQL interface is part of the supported product surface

Scope:

- documented direct-read table contract and optional read views where they materially simplify operations
- correctness-sensitive SQL functions for locks and counters first
- optional cache write functions if non-Java callers are required in the first release
- compatibility and migration policy for every SQL function exposed publicly

Exit criteria:

- direct-read versus function-write support boundaries are documented and implemented consistently
- external SQL callers are not forced to reconstruct concurrency-sensitive multi-statement logic from prose alone

Implemented evidence:

- ✅ 8 PL/pgSQL functions cover lock, counter, and cache-entry mutations
- ✅ 38 native SQL integration tests exercise serialization, concurrency semantics, failure behavior, and return values against PostgreSQL
- ✅ `docs/PEEGEEQ_CACHE_NATIVE_SQL_API.md` documents the exact supported function identities, return columns, modes, and TTL units
- ✅ `live_entries`, `live_counters`, and `active_locks` are the supported direct-read contract; backing tables are explicitly internal to application callers
- ✅ `schema_migrations` records ordered versions and `PgSchemaMigrator` serializes managed upgrades with an advisory lock and per-migration transactions
- ✅ baseline migration tests prove data preservation, repeat-run idempotence, and rejection of schemas newer than the running library; because the project is unreleased, the stable read views are consolidated into V001
- ✅ pre-1.0 compatibility, post-1.0 breaking-change, forward migration, and operational rollback policies are documented

Completion tasks: none for the V1 SQL contract.

Status: **COMPLETE** — public SQL reads, writes, compatibility, migration behavior, and upgrade verification are explicit.

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
- write-behind write buffering for `CacheService` (see section 12a of the design document)
- management API and browser console defined by `PEEGEEQ_CACHE_MANAGEMENT_API.md`

Explicit non-goal unless strategy changes:

- full Redis protocol compatibility

### Phase 8.1: Write-behind write buffering

**Status:** **COMPLETE** — all six ordered implementation steps and exit criteria are covered by unit, lifecycle, telemetry, concurrency, and real-PostgreSQL integration tests.

**Reference design:** `PEEGEEQ_CACHE_DESIGN.md` section 12a — read the full section before starting implementation.

**Prerequisite:** Phase 1 (Phases 0–7) is stable and tests are green on `master`.

**Module placement:**

- `WriteBehindConfig` → `peegee-cache-runtime` (`dev.mars.peegeeq.cache.runtime.config`)
- `PendingWrite`, `WriteBehindBuffer` → `peegee-cache-core` (`dev.mars.peegeeq.cache.core.writebehind`) — no PostgreSQL dependency; pure unit-testable logic
- `WriteBehindFlusher`, `WriteBehindCacheService` → `peegee-cache-runtime` (`dev.mars.peegeeq.cache.runtime.writebehind`) — uses `CacheService` interface, not PostgreSQL directly
- `PgPeeGeeCacheManager` wiring → `peegee-cache-runtime`

**TDD implementation steps (strict order — do not skip ahead):**

1. **`WriteBehindConfig` record and validation** (`peegee-cache-runtime`)
   - Failing tests: valid config constructs correctly; invalid `flushInterval`, `maxBufferSize`, `flushBatchSize` (out of range), and `maxRetries` (negative) are all rejected at construction time
   - Implement: record with compact constructor validation; `disabled()` factory
   - Tests in: `WriteBehindConfigTest` (pure unit, no DB)

2. **`PendingWrite` model and `WriteBehindBuffer`** (`peegee-cache-core`)
   - Failing tests: set→set coalesces to last; set→delete coalesces to DELETE marker; delete→set coalesces to SET; bounded capacity returns `overflow = true` when `maxBufferSize` is reached; `drain()` returns all entries and empties the buffer
   - Implement: `PendingWrite` record; `WriteBehindBuffer` using `ConcurrentHashMap`
   - Tests in: `WriteBehindBufferTest` (pure unit, no DB)

3. **`WriteBehindFlusher` with test-double `CacheService`** (`peegee-cache-runtime`)
   - Failing tests: flush calls `deleteMany` before `setMany`; flush retries up to `maxRetries` on failure; entries with elapsed TTL are dropped without calling `setMany`; `drain()` flushes all remaining entries on shutdown
   - Implement: `WriteBehindFlusher` using a Vert.x periodic timer
   - Tests in: `WriteBehindFlusherTest` (unit tests with `CacheService` test double, no DB)

4. **`WriteBehindCacheService` decorator** (`peegee-cache-runtime`)
   - Failing tests: `set`/`setMany`/`delete`/`deleteMany` accept into buffer and return succeeded `Future`; `get`/`exists`/`ttl`/`expire`/`persist`/`touch` delegate to underlying `CacheService`, bypassing buffer
   - Implement: `WriteBehindCacheService implements CacheService`
   - Tests in: `WriteBehindCacheServiceTest` (unit tests with `CacheService` test double)

5. **Integration test: flush delivers to PostgreSQL** (`peegee-cache-pg` or `peegee-cache-runtime`)
   - Failing tests: `set` accepted into buffer; after flush, `get` from PostgreSQL returns correct value; computed TTL adjusted for buffer dwell time; `delete` removes row after flush
   - Testcontainers integration test using `WriteBehindCacheService` wrapping `PgCacheService`
   - Tests in: `WriteBehindIntegrationTest`

6. **Lifecycle wiring in `PgPeeGeeCacheManager`**
   - Failing tests: manager lifecycle test — write-behind flusher starts after pool is ready; `isWriteBehindRunning()` returns `true` when started; `stopReactive()` drains buffer before pool close; `isWriteBehindRunning()` returns `false` after stop
   - Implement: `startReactive()`/`stopReactive()` changes; `isWriteBehindRunning()` probe; conditional wiring based on `WriteBehindConfig.enabled`
   - Tests in: update `PeeGeeCachesLifecycleTest`

**Exit criteria for Phase 8.1:**

- all 6 implementation steps have passing tests
- `WriteBehindBuffer` coalescing is verified under concurrent access (two threads writing same key)
- integration test confirms TTL adjustment is correct when flush is delayed
- `PgPeeGeeCacheManager` lifecycle test confirms drain on `stopReactive()`
- `PeeGeeCacheConfig.writeBehind` is documented in the runtime config
- `WriteBehindConfig.disabled()` is the default — existing behaviour is unchanged when write-behind is not configured

### Phase 8.2: Management API backend

**Detailed plan:** [PEEGEEQ_CACHE_MANAGEMENT_API_IMPLEMENTATION_PLAN.md](PEEGEEQ_CACHE_MANAGEMENT_API_IMPLEMENTATION_PLAN.md)

**Status:** **IN PROGRESS (M4.2 NEXT)** — M0 through M3 and M4.1 are complete. The PostgreSQL backend now provides snapshot-consistent entry reveal, atomic set modes/outcomes and TTL modes, exact-version concurrency, committed result metadata, and the mandatory audit-reservation boundary. Focused PostgreSQL 18.3 acceptance, the complete `peegee-cache-pg` gate, the full-reactor gate, and the Surefire log/leakage review pass. No management route or authentication/setup lifecycle implementation exists; M4.2 entry TTL, persist, touch, and delete is the next strict-TDD slice.

Scope:

- typed management contracts and atomic mutation outcomes in `peegee-cache-api`;
- real PostgreSQL inspection and guarded administration in `peegee-cache-pg`;
- Vert.x REST, SSE, WebSocket, authentication, audit, setup lifecycle, and mandatory observability in `peegee-cache-rest`;
- OpenAPI 3.1 and backend acceptance contracts consumed by `peegee-cache-management-ui`.

Prerequisites:

1. synchronize the older management UI design with the reviewed management API contract;
2. approve the REST/UI module and reproducible root-build shape;
3. follow phases M0–M10 in order, one failing behavior test at a time;
4. retain Mockito prohibition and real PostgreSQL Testcontainers coverage;
5. keep Phase 8.3 browser-console implementation deferred until an explicit start decision independent of the active backend work.

### Phase 8.3: Management browser console

**Reference designs:** [PEEGEEQ_CACHE_MANAGEMENT_UI_DESIGN.md](PEEGEEQ_CACHE_MANAGEMENT_UI_DESIGN.md) and [PEEGEEQ_CACHE_MANAGEMENT_API.md](PEEGEEQ_CACHE_MANAGEMENT_API.md)

**Status:** **NOT STARTED** — production React implementation is separate from the backend plan and has no approved detailed TDD plan yet.

Scope:

- the production React management console in `peegee-cache-management-ui`;
- generated or runtime-validated DTO consumption from the stable OpenAPI contract;
- accessible setup, browsing, guarded mutation, pub/sub, monitoring, activity, and settings workflows;
- sensitive-state isolation, compatibility gating, browser storage rules, and end-to-end journeys.

Prerequisites:

1. complete management backend Phase M0 and stabilize the OpenAPI milestone from M1;
2. synchronize `PEEGEEQ_CACHE_MANAGEMENT_UI_DESIGN.md` with the reviewed management API contract;
3. create and approve a dedicated strict-TDD frontend implementation plan before production UI code;
4. retain the backend-owned non-production browser harness for backend cookie/CSRF/origin/no-store acceptance without treating it as the production console;
5. make an explicit Phase 8.3 start decision independently from Phase 8.2.

## 3.1 Implementation tracking

Status legend:

- COMPLETE: implemented with passing tests and documented evidence
- IN PROGRESS: partial implementation exists, exit criteria not fully met
- NOT STARTED: no meaningful implementation work landed yet
- DEFERRED: intentionally postponed with rationale

Last reviewed: 2026-08-21

| Phase | Status | Evidence snapshot | Remaining to exit |
|---|---|---|---|
| Phase 0: Repository and build foundation | COMPLETE | Multi-module structure present and builds; Java 21 + Vert.x baseline already aligned in repository setup | None |
| Phase 1: API skeleton | COMPLETE | API interfaces and models exist in `peegee-cache-api/src/main/java/dev/mars/peegeeq/cache/api/**`; unit tests for core value objects and exceptions are present and previously documented | None |
| Phase 2: PostgreSQL schema and bootstrap | COMPLETE | The single unreleased V001 baseline provides the core schema, functions, stable read views, and migration ledger; `PgSchemaMigrator` is retained for future ordered transactional upgrades | None |
| Phase 3: Repository and SQL statement catalogue | COMPLETE | Repositories and SQL catalogues are in place: `PgCacheRepository`, `PgCounterRepository`, `PgLockRepository` and `CacheSql`, `CounterSql`, `LockSql` in `peegee-cache-pg/src/main/java/dev/mars/peegeeq/cache/pg/` | None |
| Phase 4: Service implementations for V1 Core | COMPLETE | Services are implemented and now share centralized argument validation via `peegee-cache-core/src/main/java/dev/mars/peegeeq/cache/core/validation/CoreValidation.java`, wired in `PgCacheService`, `PgCounterService`, and `PgLockService`; service/repository/migration tests are green | None |
| Phase 5: Runtime bootstrap and managed lifecycle | COMPLETE | `PgPeeGeeCacheManager` owns a real bounded `PgExpirySweeper`, applies configured default TTL through `PgCacheService`, and manages pub/sub listener lifecycle. Overlapping manual sweeps now share the same in-flight result, and `awaitIdle()` observes that result atomically. Runtime integration tests verify physical cleanup of entries/counters/locks, default TTL, custom schemas, sweep coalescing, and start/stop guards. `Vertx` and `Pool` remain caller-owned. | None |
| Phase 6: V1 completion features | COMPLETE | Safe/recovering pub/sub, scan, bulk operations, all-operation telemetry contracts, comprehensive readiness, and interleaved telemetry/lock/pub-sub benchmark scenarios are implemented and green. | None |
| Phase 7: Native SQL contract hardening | COMPLETE | Eight mutation functions have exact documented signatures; three stable read views, migration ledger/runner, compatibility policy, and real baseline idempotence and forward-version rejection tests are present. | None |
| Phase 8: V2 and later | IN PROGRESS | Phase 8.1 write-behind and Phase 8.2 M0–M3 plus M4.1 are complete. The management PostgreSQL backend now combines the M3 parameterized read model with snapshot-consistent reveal, atomic set/TTL outcomes, exact-version concurrency, committed metadata, and fail-closed audit reservation, with focused PostgreSQL 18.3, complete module/reactor, and safe-log evidence. | Begin M4.2 entry TTL, persist, touch, and delete using strict TDD. Phase 8.3 browser console remains NOT STARTED and requires its own explicit start decision. |

Tracking update rules:

1. update this table in the same change set that advances a phase status
2. do not mark COMPLETE until the phase exit criteria in this document are objectively met
3. include concrete evidence (classes, migrations, tests) in each status change
4. if status changes are uncertain, keep the lower status and add a verification task

## 3.2 Current strict verification (2026-08-21)

The current verification record is based on the complete reactor rather than historical module subsets.

Execution evidence:

- latest `mvn test` — BUILD SUCCESS across all 11 reactor projects; 61 current Surefire suites and 389 tests passed with zero failures/errors/skips
- latest `mvn -pl :peegee-cache-pg test` — BUILD SUCCESS; all 217 PostgreSQL-module tests passed with zero failures/errors/skips
- latest `mvn clean install` — BUILD SUCCESS across all 11 reactor projects; 60 Surefire suites and 382 tests passed with zero failures/errors/skips, and the complete reactor was rebuilt, packaged, and installed into the local Maven repository
- `mvn -pl :peegee-cache-pg -am test` — the complete module/dependency gate passed; current reports contain 37 suites and 292 tests across `peegee-cache-api`, `peegee-cache-core`, `peegee-cache-test-support`, and `peegee-cache-pg`, with zero failures/errors/skips
- `mvn -pl :peegee-cache-api -Prelease-artifacts package -DskipTests` — BUILD SUCCESS with the M2 public API source and Javadoc artifacts generated
- `mvn clean verify` — BUILD SUCCESS
- final `mvn verify` after all code and benchmark changes — BUILD SUCCESS
- `mvn verify -Pcentral-release '-Dgpg.skip=true'` — BUILD SUCCESS with the Central release profile loaded and signing intentionally skipped
- `mvn package -Pcentral-release -DskipTests` — BUILD SUCCESS with source and Javadoc artifacts generated
- `mvn validate -Pcentral-release` — all 9 reactor projects passed Enforcer and profile validation
- expanded one-second benchmark smoke — BUILD SUCCESS across telemetry comparison, counter contention, lock contention, pub/sub latency, expiry lag, and forced pool recovery
- full-reactor PostgreSQL compatibility matrix — 269 tests passed with zero failures/errors/skips on PostgreSQL 15.17, 16.13, 17.11, and 18.3; the subsequently added real-PostgreSQL benchmark-pool regression also passed on all four majors
- post-fix default-duration local benchmark — three identical executions passed every unchanged gate with zero scenario timeouts and zero Vert.x `Promise already completed` signatures
- Java-first repeatable benchmark capture — typed benchmark results feed one self-contained structured HTML report with aggregate/per-run results, environment details, and embedded raw logs, without console-output parsing or platform-specific orchestration scripts
- logging maturity hardening — SLF4J 2.0.18 provider isolation, unified Vert.x routing, structured lifecycle/failure/recovery events, TRACE-only sanitized operation detail, failure-episode suppression, and a one-second captured benchmark smoke all passed
- no publication or external upload occurred during verification

The 2026-08-19 review of commits `8fdfc31` through `ef3611a` added four correctness hardenings. Overlapping expiry sweeps now coalesce onto one atomic in-flight future; Micrometer gauges aggregate independent adapters that share a registry; migration rollback failure is attached as a suppressed exception without replacing the initiating migration failure; and every measured benchmark scenario now has an explicit, unrecorded warm-up interval configured by `peegeeq.benchmark.warmupSeconds` (default 5 seconds). Regression coverage exercises each behavior.

The review also removed `VertxAwait`. PostgreSQL fixtures, repository/service/SQL tests, runtime integration tests, and benchmark orchestration now compose Vert.x `Future` values directly, including asynchronous cleanup and failure preservation. `PgTestSupport.resetDatabaseState(Pool)` centralizes schema-aware truncation and fencing-sequence reset for test isolation; its real-PostgreSQL contract test verifies all three domain tables are cleared and the next fencing token is 1. A source scan found no remaining Java reference to `VertxAwait`.

Management API M1 is complete. `peegee-cache-rest/src/main/openapi/peegeeq-cache-management-v1.yaml` declares the exact reviewed 50-operation inventory, complete path/query/header/precondition parameters, required and optional request bodies, success statuses/schemas/headers, RFC-style problem responses, security profiles, aggregate/query models, typed SSE events, and typed WebSocket frames. Ten `ManagementOpenApiContractTest` tests compare this boundary to the reviewed manifest and validate it with Swagger Parser 2.1.46 with zero parser messages. Thirteen additional tests cover canonical identifier encoding, exact ETags and preconditions, scoped signed cursors, decimal/timestamp representation, strict request handling, safe problems, and literal prefix escaping.

Management API M2 is complete. `peegee-cache-api` provides immutable management queries, metadata, requests, mutation outcomes, reveal snapshots, capability/limit models, the asynchronous `ManagementService`, and a source-compatible `PeeGeeCache.management()` fallback. The audit SPI uses externally resolved versioned HMAC keys, stores only bounded fingerprints in default intents, guards terminal completion, and keeps mandatory audit failures distinct from optional telemetry failures. Twenty-four focused M2 tests are green, and the release-artifact profile generates API source and Javadoc jars.

The completed M3 change set corrects the management read contract and implements the PostgreSQL read model. `NamespaceStats` now matches the OpenAPI aggregate and `NamespaceDetails` adds repository-level distributions; `ManagementTtlFilter` represents live/persistent/expiring/include-expired query intent; management entry metadata no longer exposes the internal last-access timestamp; and typed not-found/readiness exceptions preserve error meaning. The shared API `ManagementCursorCodec` authenticates bounded, versioned, query-scoped keyset positions with HMAC-SHA-256, while the REST codec delegates and maps typed protocol errors.

`PgManagementReadSql`, `PgManagementReadRepository`, and `PgManagementService` implement namespace, entry, counter, lock, database-size, and expiry-backlog reads. Caller data is parameterized after separate schema-identifier validation; literal prefixes, database-clock expiry, deterministic composite sorts, expired-row visibility, active/expiring-soon locks, permission-aware unavailable sizes, and missing-schema readiness are covered by 14 focused tests on PostgreSQL 18.3. Two focused REST cursor tests and the focused `PgPeeGeeCache` facade test also pass. The complete `peegee-cache-pg` module/dependency gate and full 11-project reactor gate are green. At M3 completion, all 60 then-current Surefire XML reports parsed successfully; their captured output contained no asynchronous-failure signature, leakage canary, secret, or raw management identifier. The only flagged output was expected idempotent-schema notice, deliberate pub/sub connection-loss recovery, and injected write-behind terminal-failure behavior. No Surefire dump or dumpstream file existed, and the banned-pattern scan found no Mockito or substitute framework, in-memory database substitute, disabled/timing test, empty catch, or test exclusion.

Management API M4.1 is complete. `PgManagementMutationSql` and `PgManagementMutationRepository` return reveal snapshots and set outcomes/resulting metadata from the authoritative statement, including all four set modes and TTL modes. Expired physical rows are reclaimable by `ONLY_IF_ABSENT`, and exact-version row locking yields one winner and one stale result under concurrency. The mutation-aware `PgManagementService` reserves the mandatory fingerprint-only audit intent before database work and completes safe terminal outcomes while its original constructor remains inspection-only. Seven new PostgreSQL 18.3 tests are green; the complete PostgreSQL module now has 217 tests and the reactor has 61 current suites/389 tests. All 61 reports parse, no dumps or async-failure signatures exist, no M4.1 canary appears in captured output, and the prohibited-pattern scan is clean.

The interleaved local telemetry smoke measured 0.22% Micrometer throughput overhead and 20.67% p99 overhead. Before the pool-layout fix, two of three identical full-duration executions timed out in different scenarios. Diagnosis isolated deterministic client-side pool saturation: eight workers shared an eight-connection pool with two independent sweepers, while PostgreSQL sampling found no one-second SQL or lock wait. The strict-TDD fix reserves connection headroom, removes sweepers from sustained-workload managers, and uses one dedicated expiry-measurement manager. Three identical post-fix runs passed; the worst p99 was 30.628 ms, expiry lag was 16–28 ms, and failover recovery was 13–16 ms. These local-container figures remain regression evidence, not production SLOs.

Observed automated test inventory:

| Module | Tests | Failures | Errors | Skipped |
|---|---:|---:|---:|---:|
| `peegee-cache-api` | 58 | 0 | 0 | 0 |
| `peegee-cache-core` | 20 | 0 | 0 | 0 |
| `peegee-cache-pg` | 217 | 0 | 0 | 0 |
| `peegee-cache-runtime` | 48 | 0 | 0 | 0 |
| `peegee-cache-observability` | 5 | 0 | 0 | 0 |
| `peegee-cache-test-support` | 4 | 0 | 0 | 0 |
| `peegee-cache-benchmarks` | 14 | 0 | 0 | 0 |
| `peegee-cache-management-ui` | 0 | 0 | 0 | 0 |
| `peegee-cache-rest` | 23 | 0 | 0 | 0 |
| **Total** | **389** | **0** | **0** | **0** |

The count above is the sum of the 61 current Surefire XML suites produced under the eight tested `peegee-cache-*` reactor modules. All 61 reports parsed successfully. Stale reports under legacy, non-reactor `pg-cache-*` directories and `bin/target` trees are deliberately excluded.

Criteria verdicts:

- Phase 3: COMPLETE — repository, database-clock expiry, and ownership-aware lock criteria are satisfied
- Phase 4: COMPLETE — cache, counter, and lock services are implemented with centralized validation and real PostgreSQL coverage
- Phase 5: COMPLETE — bootstrap, managed lifecycle, bounded expiry sweeping, default TTL, and deterministic shutdown criteria are satisfied
- Phase 6: COMPLETE — pub/sub identifier safety, comprehensive readiness, all-operation telemetry coverage, and overhead evidence satisfy the exit criteria
- Phase 7: COMPLETE — stable reads, exact function signatures, compatibility policy, migration runner, and upgrade tests satisfy the SQL contract
- Phase 8.1: COMPLETE — opt-in write-behind buffering satisfies coalescing, capacity fallback, TTL accounting, retry/telemetry, PostgreSQL delivery, and manager shutdown-drain criteria
- Phase 8.2 M0–M2: COMPLETE — synchronized contracts, the exact OpenAPI boundary, protocol primitives, typed management API, compatible fallback, and audit SPI satisfy their phase gates
- Phase 8.2 M3: COMPLETE — focused PostgreSQL 18.3 acceptance, complete `peegee-cache-pg` and full-reactor suites, query-plan and leakage checks, and the Surefire output/banned-pattern review satisfy the phase gate
- Phase 8.2 M4.1: COMPLETE — snapshot-consistent entry reveal, atomic set/TTL outcomes, exact-version concurrency, committed metadata, mandatory audit reservation, and the focused/module/reactor/leakage gates satisfy the slice criteria

## 3.3 Management backend handover after M4.1 completion

M4.1 is complete. The next task is M4.2 entry TTL, persist, touch, and delete behavior, continuing one failing real-PostgreSQL behavior at a time and preserving atomic outcomes plus the mandatory audit boundary.

The implemented boundary is:

- `peegee-cache-api` owns the management query, reveal, mutation, result, audit, and reusable signed-cursor contracts established in M2/M3;
- `peegee-cache-pg` owns schema-validated/parameterized inspection SQL plus `PgManagementMutationSql`, `PgManagementMutationRepository`, and the reveal/set service behavior;
- the mutation-aware `PgManagementService` advertises entry reveal/mutation, requires audit reservation before database access, and returns statement-produced outcomes/metadata; its original constructor remains inspection-only for source compatibility;
- `peegee-cache-rest` still owns only the thin cursor-to-problem adapter and completed OpenAPI/protocol boundary; it has no management routes yet;
- entry values, raw identifiers, credentials, cursor keys, and audit keys remain absent from ordinary logs and default authoritative audit intents;
- the durable audit journal lifecycle/recovery/readiness behaviors remain M4.5 work, and authentication/setup/server work remains in M5–M7.

M4.1 completion evidence is 7 focused PostgreSQL 18.3 tests, a green complete `peegee-cache-pg` gate with 217 tests, and a green 11-project reactor containing 61 current suites and 389 tests with zero failures/errors/skips. All reports parse, the combined Surefire-output/leakage and banned-pattern review is clean, and no dump/dumpstream exists. The authoritative behavior and evidence are in `PEEGEEQ_CACHE_MANAGEMENT_API_IMPLEMENTATION_PLAN.md`.

M4.2 sequence:

1. Add the first failing real-PostgreSQL test for entry-expire atomic applied/not-found/version-mismatch outcomes and committed metadata.
2. Continue independently through persist, touch, and delete, proving stale operations leave state unchanged and delete reports its version-checked outcome without a diagnostic follow-up read.
3. Reserve the mandatory audit intent before each context-requiring operation and complete it with bounded, value-free outcome codes.
4. Repeat focused, module, reactor, and output/leakage gates before advancing to M4.3.

## 4. Feature rollout by milestone

### Milestone A: V1 Core alpha

Status: **COMPLETE**

Target outcome:

- usable embedded cache, counter, and lock library for Java callers

Must include:

- key/value get/set/delete
- TTL and expiry
- atomic conditional writes
- atomic counters
- namespaces
- lock service
- schema bootstrap
- managed bootstrap

Must not include:

- hashes
- sorted sets
- Redis protocol adaptation
- durable queue features

### Milestone B: V1 beta

Status: **COMPLETE**

Target outcome:

- operationally testable Phase 1 library with admin-facing capabilities

Must include:

- scan/listing
- metadata/versioning support
- bulk operations
- initial admin hooks
- stronger integration-test coverage

### Milestone C: V1 release candidate

Status: **COMPLETE**

Target outcome:

- complete Phase 1 surface as currently defined

Must include:

- lightweight pub/sub
- documented runtime behavior
- documented SQL support boundaries
- examples and operator guidance

Remaining release-candidate gates: none in the repository. Public Central publication remains a separate credentialed release action.

## 5. Current module inventory and remaining work

### `peegee-cache-api`

Current:

- public cache, counter, lock, scan, pub/sub, subscription, and admin interfaces
- public request, result, entry, TTL, metrics, scan, pub/sub, and lock models
- the `PeeGeeCache` facade and exception hierarchy
- immutable management contracts, typed read failures, query TTL filters, and the shared signed keyset-cursor codec

Remaining V1 work:

- none currently identified; additions require an explicit Phase 6 exit-criteria change

### `peegee-cache-core`

Current:

- validation utilities
- thread-safe in-memory metric snapshots
- vendor-neutral telemetry SPI, bounded operation names, and composite exporter fan-out

Remaining V1 work:

- none currently identified beyond changes required by open Phase 6 observability criteria

### `peegee-cache-pg`

Current:

- ordered, idempotent schema migrations and migration ledger
- repositories
- SQL catalogue
- row mapping
- cache, counter, lock, scan, admin, and pub/sub services
- 8 native SQL mutation functions
- 3 stable read views
- parameterized management inspection SQL/repository/service for namespace, entry, counter, lock, database, and expiry metadata
- optional `PgPeeGeeCache` management-service injection with the existing unsupported fallback preserved

Remaining V1 work:

- none currently identified

### `peegee-cache-runtime`

Current:

- bootstrap options
- manager lifecycle
- explicit startup and shutdown
- bounded expiry sweeper scheduling and graceful in-flight shutdown
- external-by-default schema policy with opt-in managed bootstrap
- dedicated pub/sub listener lifecycle when connection options are configured

Remaining V1 work:

- none currently identified

### `peegee-cache-observability`

Production scope:

- vendor-neutral telemetry contract in core
- Micrometer metrics adapter
- OpenTelemetry metrics and tracing adapter
- PostgreSQL/schema readiness indicator
- bounded-cardinality operation and outcome dimensions
- expiry lag, lock contention, saturation-demand, reconnect, notification dispatch, and lifecycle signals

Remaining V1 work:

- none currently identified

### `peegee-cache-test-support`

Current:

- PostgreSQL Testcontainers helpers
- schema bootstrap fixtures
- centralized schema-aware domain-table and fencing-sequence reset
- composable Vert.x `Future` fixture setup and cleanup, with cleanup failures preserved alongside primary failures
- latency percentile and throughput calculation

Potential later work:

- higher-level DSLs for repetitive test flows

### `peegee-cache-benchmarks`

Current:

- sustained mixed SET/GET throughput and p50/p95/p99 latency
- interleaved noop-versus-Micrometer throughput and p99 overhead
- contended counter throughput and latency
- contended distributed-lock throughput and latency
- publish-to-receive notification throughput and latency
- physical expiry lag
- pool recovery after forced backend termination
- configurable regression thresholds
- configurable unrecorded warm-up before every measured scenario
- repeatable Java evidence capture producing one portable HTML report with hardware/toolchain/Docker/Git metadata, aggregate/per-run results, raw logs, and immutable PostgreSQL image identity

Remaining release-hardening work: execute the benchmark on the intended production topology before turning the local regression figures into capacity or SLO commitments. The diagnosed local pool-saturation timeouts are fixed, and warm-up operations are excluded from measured windows. Per-scenario warm-up mitigates startup bias, but the sequential same-JVM repetitions do not eliminate JIT, run-order, operating-system cache, or thermal effects; independent process forks remain future work if statistically independent samples are required.

### `peegee-cache-examples`

Current:

- managed runtime bootstrap
- basic integration
- batch and TTL behavior
- read-through caching
- version-aware updates
- distributed locks and configured pub/sub
- stable SQL read-view inspection

Remaining release-candidate work:

- none currently identified

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
  - caller-controlled channel identifiers containing quotes and SQL metacharacters are safely quoted, while NUL and overlength qualified identifiers are rejected
- observability tests:
  - Micrometer operation duration, outcome, active-operation, and operational signals
  - OpenTelemetry span completion, failure status, metrics, and context activation
  - exporter exceptions cannot change operation outcomes
  - lifecycle, schema bootstrap, expiry, contention, reconnect, subscription, and notification signals
  - readiness returns `DOWN` with named missing objects for a partially installed schema
- benchmark and profiling work on realistic combined flows

The executable benchmark covers interleaved noop-versus-Micrometer cache traffic, counter contention, lock contention, publish-to-receive notification latency, expiry lag, and connection recovery.

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

### Risk: unsafe dynamic PostgreSQL identifiers

Control:

- never concatenate an unescaped caller-controlled identifier into `LISTEN`, `UNLISTEN`, or other SQL
- double embedded quotes, enforce PostgreSQL's qualified identifier byte limit, and reject NUL
- test quotes, SQL metacharacters, NUL, and overlength identifiers against real PostgreSQL

### Risk: shallow readiness reports false-positive health

Control:

- define the exact schema objects required by each enabled runtime capability
- report `DOWN` when any required table, sequence, or supported function is absent
- verify partial-schema states against real PostgreSQL

## 8. Release-hardening status

Completed:

1. production Micrometer, OpenTelemetry, and comprehensive readiness adapters are implemented and runtime-wired
2. reusable PostgreSQL fixtures live in `peegee-cache-test-support` and are consumed by PostgreSQL/runtime tests
3. the opt-in benchmark module reports sustained throughput, p50/p95/p99, expiry lag, and forced-connection-loss recovery
4. schema bootstrap is external by default with tested opt-in `SchemaBootstrapMode.APPLY`
5. operator, database-compatibility, benchmark, and release-artifact guidance is documented; build gates enforce Java/Maven versions and dependency convergence
6. dynamic pub/sub identifiers are safely quoted and byte-bounded with adversarial PostgreSQL coverage
7. readiness validates the complete required schema contract and names missing objects
8. all 30 asynchronous operation identifiers are covered by one real-runtime telemetry contract test
9. native SQL has stable read views, exact supported signatures, a compatibility policy, and baseline migration safety tests
10. benchmarks cover telemetry overhead, lock contention, and publish-to-receive latency; matching runnable examples are present
11. expiry sweep coalescing, registry-wide Micrometer gauge aggregation, and migration rollback failure preservation have explicit regression coverage
12. blocking `VertxAwait` test support has been removed in favor of Vert.x `Future` composition, and benchmark measurement windows begin only after explicit unrecorded warm-up

Maven Central publication defaults are now configured: Apache-2.0 licensing, canonical GitHub project/SCM/developer metadata, source and Javadoc artifacts, GPG best-practices signing, and Sonatype Central Portal upload with manual promotion. Actual publication remains gated only on namespace verification, a non-SNAPSHOT version, and credentials/signing keys supplied outside the repository.

The PostgreSQL 15–18 compatibility matrix is now encoded in `.github/workflows/postgresql-compatibility.yml`, using fixed 15.17, 16.13, 17.11, and 18.3 images and the complete Maven reactor for pull requests, `master` pushes, and manual dispatches. Representative production-topology benchmarking remains an operational capacity-validation action, not an open repository defect or a reason to weaken the local gates.

### Deferred release-readiness actions

The following external actions are intentionally deferred until the project is ready for its first release candidate. They are not complete and must be reviewed before making production capacity/SLO claims or publishing public artifacts:

- [ ] **Production-topology benchmark:** identify the intended database, network, storage, compute, JVM, pool, and workload topology; run at least three full-duration captures from a clean release-candidate checkout; retain the self-contained HTML evidence; and review throughput, tail latency, telemetry overhead, expiry lag, and failover recovery against the proposed production objectives. Local Testcontainers results remain regression evidence only.
- [ ] **Maven Central publication:** verify ownership of the `dev.mars` namespace, select a non-SNAPSHOT release version, prepare release notes, provide the Central Portal token and GPG signing key through external secret storage, run the documented signed deployment, review Central's validation result, and manually promote the deployment. No credentials or private signing material belong in this repository.

Review trigger: revisit both items before declaring the first release candidate production-ready. Use `docs/PEEGEEQ_CACHE_BENCHMARKS.md` and `docs/PEEGEEQ_CACHE_RELEASE_PACKAGING.md` as the execution runbooks.

## 9. Summary

The correct delivery strategy is:

- build `V1 Core` first
- finish the database contract before convenience features
- complete the runtime lifecycle before broadening the surface area
- treat native SQL support as deliberate product surface, not accidental table exposure
- defer Redis-shaped expansion until the PostgreSQL-native core is proven

Current conclusion:

- Phases 0–5 are complete
- Phase 6 is complete
- Phase 7 is complete
- Phase 8.1 and management backend M0–M3 plus M4.1 are complete; M4.2 entry TTL, persist, touch, and delete is the next strict-TDD slice
- Phase 8.3 remains intentionally deferred pending its own browser-console start decision
