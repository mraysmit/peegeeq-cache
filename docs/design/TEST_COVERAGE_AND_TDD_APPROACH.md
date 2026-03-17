# peegee-cache Test Coverage and TDD Approach

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

**Framework:** JUnit Jupiter 5.x + Testcontainers (PostgreSQL 16-alpine via Docker CLI).

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

### peegee-cache-api (34 tests)

| Test class | Tests | What it verifies |
|---|---|---|
| `CacheKeyTest` | 8 | Null rejection (`NullPointerException`), blank rejection (`IllegalArgumentException`), equality, `asQualifiedKey()` format |
| `CacheValueTest` | 13 | Typed factory methods, accessor type guards, compact constructor payload exclusivity, null rejection |
| `LockKeyTest` | 7 | Null rejection (`NullPointerException`), blank rejection (`IllegalArgumentException`), equality, `asQualifiedKey()` format |
| `ExceptionHierarchyTest` | 6 | Catchability contract (`catch CacheException` catches all subtypes), unchecked status, `CacheStoreException` rejects null cause, `LockNotHeldException` independent catchability |

### peegee-cache-pg (27 tests)

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

### Testcontainers setup

PostgreSQL 16-alpine via Docker CLI (ProcessBuilder), not docker-java — workaround for Docker Engine 29 incompatibility with docker-java 3.4.2.

Container lifecycle is managed per-test-class using `@BeforeAll` / `@AfterAll`. Migrations run via Flyway against the container before tests execute.

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
