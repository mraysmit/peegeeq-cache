# peegee-cache Design Document

## 1. Overview

**peegee-cache** is a PostgreSQL-backed, Redis-inspired cache and coordination library built for the **PeeGeeQ family** and intended to start on **Java 21+** and **Vert.x 5.0.x**.

It is **library-first**, not daemon-first. The Java API is the primary product. PostgreSQL is the first storage implementation. Vert.x provides the asynchronous programming model and runtime integration.

This is **not** intended to be a full Redis clone in Phase 1. It is intended to provide the high-value parts of Redis that map well onto PostgreSQL and are actually useful in enterprise systems.

### Alignment with the existing PeeGeeQ codebase

The sibling `peegeeq` project establishes a few conventions that `peegee-cache` should follow from day one:

- keep the `dev.mars` group and `dev.mars.peegeeq.*` package family
- use `io.vertx.core.Future<T>` for reactive public APIs
- keep runtime lifecycle explicit rather than implicit
- expose a manager/factory bootstrap story similar to `PeeGeeQManager` and `PgClientFactory`
- preserve module separation between API, PostgreSQL implementation, runtime, observability, and test support

That means the cache design should optimize first for clean integration with the current PeeGeeQ baseline on Java 21 and Vert.x 5.0.x. A later standalone release can raise the baseline in a coordinated version bump, but the initial API should not assume a newer floor than the rest of the family already uses.

### Implementation guardrails

These are non-negotiable implementation rules for Phase 1:

- use a pure Vert.x 5.x public async contract built on `io.vertx.core.Future<T>`
- keep runtime composition and lifecycle management Vert.x-native
- follow strict TDD for behavior that can be tested
- do not use mocking for database-facing behavior
- use Testcontainers for PostgreSQL-backed integration work
- keep construction and runtime behavior configuration-driven
- do not hide important behavior in hardcoded defaults or ad hoc environment lookups

Practical meaning:

- no `CompletableFuture`, Reactor, Mutiny-as-primary-surface, or Spring-first abstractions in the Phase 1 core API
- no H2 or in-memory stand-ins for PostgreSQL semantics
- no progressing to the next step while the current changed behavior is still failing tests

### Proposed package root

```java
package dev.mars.peegeeq.cache;
```

### Product positioning

- PostgreSQL-backed caching and coordination
- Redis-inspired primitives on PostgreSQL
- Transactional cache, lock, and counter services

### Scope statement

`peegee-cache` is a PostgreSQL-backed cache and coordination service for Java/Vert.x systems. It provides Redis-inspired primitives such as key/value storage, TTL expiry, atomic counters, conditional writes, namespaces, distributed locks, scanning, and lightweight pub/sub. It is designed for transactional correctness, operational simplicity, and tight integration with PostgreSQL-centric platforms rather than exact Redis protocol or clustering compatibility.

### Why PostgreSQL instead of Redis-by-default

The main advantage is not that PostgreSQL beats Redis on raw single-operation latency. It usually does not.

The real win is **transactional locality**:

- write business data, update cache state, and emit notifications in one database transaction
- remove cross-system cache invalidation failure modes
- reduce backup, monitoring, failover, and operational surface area
- avoid paying an extra network hop for coordination-style workloads

That makes `peegee-cache` a strong fit when the system needs correctness and simplicity more than ultra-low-latency standalone cache reads.

### Decision matrix

| Situation | Recommended choice | Why |
|---|---|---|
| Java/Vert.x system needs cache, locks, counters, TTL, and lightweight notifications close to PostgreSQL transactions | `peegee-cache` | Gives a reusable library with transactional locality, explicit lifecycle, and PostgreSQL-native coordination primitives |
| Application only needs a few simple cache tables or one-off SQL helpers and does not need a reusable abstraction layer | Plain PostgreSQL only | Lower abstraction cost; direct SQL is often enough if the usage is narrow and local |
| Workload needs very high request rates, hard latency targets, or Redis-native structures as first-class features | Keep Redis | Redis still fits better for dedicated cache-tier behavior, extreme throughput, and specialized in-memory data structures |
| System needs durable queueing, workflow orchestration, or event streaming beyond lightweight coordination | Separate queue/event module, not core `peegee-cache` alone | Those concerns grow into their own subsystem and should not be hidden inside a cache API |

Short version:

- choose `peegee-cache` for PostgreSQL-native coordination and cache semantics inside the same transactional envelope
- choose plain PostgreSQL when the problem is small enough that a library adds more surface area than value
- choose Redis when the latency, throughput, or data-structure requirements are genuinely Redis-shaped

---

## 2. What Redis features are actually useful

The first step is **not** “copy Redis.” The first step is to decide which Redis features are actually valuable, feasible on PostgreSQL, and worth implementing.

### Tier 1 — Core features for Phase 1

These are the features that provide real value and map well to PostgreSQL:

- Basic key/value operations
  - `GET`
  - `SET`
  - `DEL`
  - `EXISTS`
  - `MGET`
  - `MSET`
  - `GETSET`
  - `SETNX`
  - `INCR`
  - `DECR`
  - `INCRBY`
  - `DECRBY`

- Expiry / TTL
  - `EXPIRE`
  - `PEXPIRE`
  - `TTL`
  - `PTTL`
  - `PERSIST`
  - absolute-expiry support

- Atomic conditional writes
  - set-if-absent
  - set-if-present
  - compare-and-set
  - fetch-and-delete
  - fetch-and-touch

- Bulk operations / batching
  - multi-get
  - multi-set
  - multi-delete

- Namespaces / prefixes
  - first-class namespace support
  - logical grouping
  - multi-tenant separation

- Distributed locking primitives
  - acquire lock with TTL / lease
  - renew lock
  - release only if owner matches
  - fencing tokens

- Pub/sub
  - lightweight channel publish/subscribe
  - cache invalidation fan-out
  - internal notifications

### Tier 2 — High-value, but later

- Rate limiting primitives (fixed window, sliding window, token bucket / leaky bucket)
- Hashes
- Sorted sets
- Durable job queues
- Delayed jobs
- Compare-and-set / versioned write APIs as richer patterns
- Keyspace event notifications

### Tier 3 — Later or separate module

- Sets
- Lists
- Streams
- Scripting
- Probabilistic structures
- Geo features

### Tier 4 — Do not attempt early

- Full RESP protocol compatibility
- Redis Cluster semantics
- Redis persistence semantics (RDB/AOF emulation)
- Redis modules ecosystem

---

## 3. Feature matrix

### Scoring legend

- **Business value**: High / Medium / Low
- **Complexity**: Low / Medium / High / Very High
- **PostgreSQL fit**: Excellent / Good / Fair / Poor
- **Recommendation**:
    - **V1 Core** = first implementation slice; needed to prove the product shape
    - **V1** = still Phase 1, but after the core slice is stable
  - **V2** = build after core is stable
  - **Later** = only if clearly needed
  - **No** = do not build unless forced

| Feature | Redis capability | Business value | Complexity | PostgreSQL fit | Recommendation | Why |
|---|---|---:|---:|---:|---|---|
| Basic key/value | GET, SET, DEL, EXISTS | High | Low | Excellent | V1 Core | Core cache capability |
| TTL / expiry | EXPIRE, TTL, PTTL, PERSIST | High | Medium | Good | V1 Core | Essential cache semantics |
| Atomic conditional writes | SET NX, XX, GETSET-like flows | High | Medium | Excellent | V1 Core | Required for correctness and locking |
| Atomic counters | INCR, DECR, INCRBY | High | Low | Excellent | V1 Core | Very common real use case |
| Namespaces | Prefix/grouping | High | Low | Excellent | V1 Core | Operational sanity |
| Locking | Lease lock, owner token, renew, release | High | Medium | Excellent | V1 Core | Strong PostgreSQL fit |
| Bulk key/value | MGET, MSET, multi-delete | High | Low | Excellent | V1 Core | Needed for efficiency; included in CacheService interface from day one |
| Scan / iteration | SCAN-like listing | Medium | Medium | Good | V1 | Needed for admin/debug/migration |
| Key metadata | Created time, updated time, hit count, expiry, version | High | Medium | Excellent | V1 | Needed for observability and CAS |
| Compare-and-set | Versioned optimistic updates | High | Medium | Excellent | V2 | Very useful in real systems |
| Hashes | HSET, HGET, HMGET, HINCRBY | High | Medium | Good | V2 | Strong business value |
| Sorted sets | ZADD, ZRANGE, score queries | High | Medium | Excellent | V2 | Great PostgreSQL fit |
| Sets | SADD, SREM, SISMEMBER | Medium | Medium | Good | V2 | Useful but not first priority |
| Lists | LPUSH, RPOP, etc. | Medium | High | Fair | Later | Exact Redis list behavior is awkward in SQL |
| Durable queue | BRPOP-like usage, claim/ack/retry | High | Medium | Excellent | V2 | Better built as queue semantics |
| Delayed jobs | Score/time-based execution | High | Medium | Excellent | V2 | Natural with indexed timestamps |
| Rate limiting | Fixed/sliding window, token bucket | High | Medium | Good | V2 | Strong practical value |
| Pub/Sub | PUBLISH, SUBSCRIBE | Medium | Medium | Fair | V1 light | Fine for invalidation, not massive fanout |
| Pattern subscriptions | PSUBSCRIBE | Medium | Medium | Fair | V2 | Adds routing complexity |
| Keyspace notifications | Expiry/delete/update events | Medium | Medium | Good | V2 | Good for observability |
| Transactions | MULTI / EXEC / WATCH semantics | Medium | High | Fair | No exact copy | Use SQL transactions instead |
| Scripting | Lua / EVAL | Medium | Very High | Fair | No | Huge complexity and risk |
| Streams | XADD, consumer groups | High | High | Good | Later / separate module | This becomes a subsystem |
| Probabilistic types | HyperLogLog, Bloom-like semantics | Low/Med | High | Fair | Later | Niche |
| Geo | GEOADD, GEORADIUS | Low/Med | Medium | Good via PostGIS | No Redis clone | Use PostGIS instead |
| RESP protocol compatibility | Native Redis client compatibility | Medium | Very High | N/A | Later maybe | Expensive and full of edge cases |
| Redis cluster semantics | Hash slots, resharding, failover | Medium | Very High | Poor | No | Totally different architecture |
| Redis persistence model | RDB/AOF semantics | Low | Very High | Poor | No | PostgreSQL durability replaces this |
| In-memory eviction policies | LRU/LFU/random eviction | Medium | High | Fair | V2 partial | Worth doing later |
| Replication/follower reads | Read replicas style scaling | Medium | High | Good | Later | Adds consistency complexity |

### V1 core shortlist

- key/value get/set/delete
- bulk get/set/delete
- TTL and expiry
- atomic conditional write support
- atomic counters
- namespaces
- lock service

### Remaining V1 shortlist

- scan/list by namespace and prefix
- metadata/versioning
- lightweight pub/sub
- admin/metrics hooks

### V2 shortlist

- hashes
- sorted sets
- durable queue
- delayed jobs
- rate limiting
- keyspace notifications
- compare-and-set richer APIs
- partial eviction policy support

### Things explicitly rejected for Phase 1

- exact Redis transaction semantics (`MULTI/WATCH/EXEC`)
- general list emulation
- streams
- Lua scripting
- full RESP compatibility
- cluster semantics

---

## 4. Product and naming decisions

### Project name

**peegee-cache**

This fits the broader PeeGeeQ naming style and is honest about the product focus.

### Tagline candidates

- PostgreSQL-backed caching and coordination
- Transactional cache primitives on PostgreSQL
- Redis-inspired cache services for Vert.x
- Cache, locks, counters, and TTL on PostgreSQL

Best practical tagline:

**PostgreSQL-backed caching and coordination**

### What not to say

Do not lead with:

- Redis replacement
- Redis killer
- fully compatible Redis clone

That creates the wrong expectations and invites bad comparisons.

---

## 5. Library-first architecture

The correct architectural call is **library-first**.

That means:

- the **Java API** is the product,
- PostgreSQL is the first implementation,
- Vert.x is the async/runtime substrate,
- any HTTP or TCP server is just an adapter later.

This avoids the common mistake of building a network daemon first and ending up with a terrible internal API.

### Public async contract

Because this is a Vert.x-first library, the public API should be **Vert.x-native async**:

- return `io.vertx.core.Future<T>`
- do not return blocking values
- do not lead with `CompletionStage<T>` or Reactor types

That keeps composition natural in Vert.x 5.x applications.

### Runtime lifecycle contract

The existing PeeGeeQ codebase uses explicit manager/factory objects for startup, shutdown, and ownership boundaries. `peegee-cache` should follow the same pattern:

- low-level APIs remain pure service interfaces
- runtime bootstrap produces a managed object with start/stop semantics
- ownership of `Vertx`, `Pool`, metrics, and background sweepers is explicit
- background components like expiry sweeping and `LISTEN/NOTIFY` listeners are lifecycle-managed, not ad hoc

---

## 6. Phase 1 module structure

```text
peegee-cache-parent
├── peegee-cache-api
├── peegee-cache-core
├── peegee-cache-pg
├── peegee-cache-runtime
├── peegee-cache-observability
├── peegee-cache-test-support
└── peegee-cache-examples
```

### Module roles

#### `peegee-cache-parent`
- dependency management
- plugin management
- Java 21+ / Vert.x 5.0.x baseline configuration
- version alignment

#### `peegee-cache-api`
Contains:
- public service interfaces
- request/response models
- enums
- exceptions
- option records
- subscription handles

Must remain clean:
- no PostgreSQL classes
- no SQL details
- no runtime bootstrap internals

#### `peegee-cache-core`
Backend-agnostic shared logic:
- validation
- key rules
- TTL normalization
- namespace/prefix parsing
- option merging
- result builders
- internal support abstractions

#### `peegee-cache-pg`
PostgreSQL implementation:
- repositories
- SQL statements
- row mapping
- transaction handling
- PostgreSQL `LISTEN/NOTIFY` integration
- lock implementation
- counter implementation
- expiry support

#### `peegee-cache-runtime`
Runtime/bootstrap support:
- factory / builder
- lifecycle orchestration
- managed startup/shutdown
- expiry sweeper
- embedded runtime helpers

#### `peegee-cache-observability`
Optional metrics/tracing:
- Micrometer integration
- OpenTelemetry instrumentation hooks
- command timing wrappers
- gauges / counters / histograms

#### `peegee-cache-test-support`
- Testcontainers PostgreSQL helpers
- fixtures
- concurrency test helpers
- fake clocks and support DSLs

#### `peegee-cache-examples`
- sample embedded apps
- example usage
- reference configuration

---

## 7. Package structure

### API module packages

```java
dev.mars.peegeeq.cache.api
dev.mars.peegeeq.cache.api.cache
dev.mars.peegeeq.cache.api.lock
dev.mars.peegeeq.cache.api.counter
dev.mars.peegeeq.cache.api.pubsub
dev.mars.peegeeq.cache.api.scan
dev.mars.peegeeq.cache.api.model
dev.mars.peegeeq.cache.api.options
dev.mars.peegeeq.cache.api.exception
```

### Core module packages

```java
dev.mars.peegeeq.cache.core
dev.mars.peegeeq.cache.core.key
dev.mars.peegeeq.cache.core.validation
dev.mars.peegeeq.cache.core.time
dev.mars.peegeeq.cache.core.codec
dev.mars.peegeeq.cache.core.metrics
dev.mars.peegeeq.cache.core.support
```

### PostgreSQL module packages

```java
dev.mars.peegeeq.cache.pg
dev.mars.peegeeq.cache.pg.cache
dev.mars.peegeeq.cache.pg.lock
dev.mars.peegeeq.cache.pg.counter
dev.mars.peegeeq.cache.pg.pubsub
dev.mars.peegeeq.cache.pg.scan
dev.mars.peegeeq.cache.pg.repository
dev.mars.peegeeq.cache.pg.sql
dev.mars.peegeeq.cache.pg.mapper
dev.mars.peegeeq.cache.pg.runtime
```

### Runtime module packages

```java
dev.mars.peegeeq.cache.runtime
dev.mars.peegeeq.cache.runtime.bootstrap
dev.mars.peegeeq.cache.runtime.lifecycle
dev.mars.peegeeq.cache.runtime.expiry
dev.mars.peegeeq.cache.runtime.config
```

### Observability module packages

```java
dev.mars.peegeeq.cache.observability
dev.mars.peegeeq.cache.observability.metrics
dev.mars.peegeeq.cache.observability.tracing
dev.mars.peegeeq.cache.observability.health
```

### Package discipline

Do **not** dump everything under `core`.

Use:
- `api` for public interfaces
- `model` for domain records / enums / value objects
- `spi` or internal support packages for extension points
- `pg` for PostgreSQL implementations and repositories
- feature packages like `lock`, `expiry`, `pubsub` for service logic
- `runtime` for wiring / lifecycle / bootstrap

---

## 8. Public service interfaces (Phase 1)

These are the first-cut interfaces for the library.

### 8.1 `CacheService`

```java
package dev.mars.peegeeq.cache.api.cache;

import dev.mars.peegeeq.cache.api.model.CacheEntry;
import dev.mars.peegeeq.cache.api.model.CacheKey;
import dev.mars.peegeeq.cache.api.model.CacheSetRequest;
import dev.mars.peegeeq.cache.api.model.CacheSetResult;
import dev.mars.peegeeq.cache.api.model.TtlResult;
import dev.mars.peegeeq.cache.api.model.TouchResult;
import io.vertx.core.Future;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public interface CacheService {

    Future<Optional<CacheEntry>> get(CacheKey key);

    Future<Map<CacheKey, Optional<CacheEntry>>> getMany(List<CacheKey> keys);

    Future<CacheSetResult> set(CacheSetRequest request);

    Future<Map<CacheKey, CacheSetResult>> setMany(List<CacheSetRequest> requests);

    Future<Boolean> delete(CacheKey key);

    Future<Long> deleteMany(List<CacheKey> keys);

    Future<Boolean> exists(CacheKey key);

    Future<TtlResult> ttl(CacheKey key);

    Future<Boolean> expire(CacheKey key, Duration ttl);

    Future<Boolean> persist(CacheKey key);

    Future<TouchResult> touch(CacheKey key, Duration ttl);
}
```

### 8.2 `CounterService`

```java
package dev.mars.peegeeq.cache.api.counter;

import dev.mars.peegeeq.cache.api.model.CacheKey;
import dev.mars.peegeeq.cache.api.model.CounterOptions;
import dev.mars.peegeeq.cache.api.model.TtlResult;
import io.vertx.core.Future;

import java.time.Duration;
import java.util.Optional;

public interface CounterService {

    Future<Long> increment(CacheKey key);

    Future<Long> incrementBy(CacheKey key, long delta);

    Future<Long> decrement(CacheKey key);

    Future<Long> decrementBy(CacheKey key, long delta);

    Future<Optional<Long>> getValue(CacheKey key);

    Future<Long> setValue(CacheKey key, long value, CounterOptions options);

    Future<TtlResult> ttl(CacheKey key);

    Future<Boolean> expire(CacheKey key, Duration ttl);

    Future<Boolean> persist(CacheKey key);

    Future<Boolean> delete(CacheKey key);
}
```

**Design note on `CacheKey` reuse:** `CounterService` uses `CacheKey` rather than introducing a separate `CounterKey` type. This is intentional. `CacheKey` is structurally just `(namespace, key)` — a general-purpose composite identifier. Counters and cache entries live in separate tables, so there is no key-space collision. Introducing `CounterKey` with an identical `(namespace, key)` structure would add a type without adding meaning. If counter identity diverges structurally from cache identity in a later phase, a dedicated key type can be introduced then.

### 8.3 `LockService`

```java
package dev.mars.peegeeq.cache.api.lock;

import dev.mars.peegeeq.cache.api.model.LockAcquireRequest;
import dev.mars.peegeeq.cache.api.model.LockAcquireResult;
import dev.mars.peegeeq.cache.api.model.LockState;
import dev.mars.peegeeq.cache.api.model.LockKey;
import dev.mars.peegeeq.cache.api.model.LockReleaseRequest;
import dev.mars.peegeeq.cache.api.model.LockRenewRequest;
import io.vertx.core.Future;

import java.util.Optional;

public interface LockService {

    Future<LockAcquireResult> acquire(LockAcquireRequest request);

    Future<Boolean> renew(LockRenewRequest request);

    Future<Boolean> release(LockReleaseRequest request);

    Future<Boolean> isHeldBy(LockKey key, String ownerToken);

    Future<Optional<LockState>> currentLock(LockKey key);
}
```

### 8.4 `ScanService`

```java
package dev.mars.peegeeq.cache.api.scan;

import dev.mars.peegeeq.cache.api.model.ScanRequest;
import dev.mars.peegeeq.cache.api.model.ScanResult;
import io.vertx.core.Future;

public interface ScanService {

    Future<ScanResult> scan(ScanRequest request);
}
```

**Scope note:** `ScanService` in Phase 1 covers `cache_entries` only. Scanning counters and locks is deferred to V1 completion (Phase 6) since operational inspection of those tables is lower-frequency. If needed earlier, direct SQL reads against `cache_counters` and `cache_locks` are explicitly supported (see section 25).

### 8.5 `PubSubService`

```java
package dev.mars.peegeeq.cache.api.pubsub;

import dev.mars.peegeeq.cache.api.model.PublishRequest;
import dev.mars.peegeeq.cache.api.model.PubSubMessage;
import dev.mars.peegeeq.cache.api.pubsub.Subscription;
import io.vertx.core.Future;

import java.util.function.Consumer;

public interface PubSubService {

    Future<Integer> publish(PublishRequest request);

    Future<Subscription> subscribe(String channel, Consumer<PubSubMessage> handler);
}
```

```java
package dev.mars.peegeeq.cache.api.model;

public record PubSubMessage(
        String channel,
        String payload,
        String contentType,
        long receivedAtEpochMillis
) {}
```

**Timestamp semantics:** `receivedAtEpochMillis` is stamped by the subscriber when the `NOTIFY` payload arrives, using `System.currentTimeMillis()` on the receiving JVM. PostgreSQL `NOTIFY` does not include a server-side timestamp in the payload. The field was renamed from `publishedAtEpochMillis` to `receivedAtEpochMillis` to avoid implying publisher-clock accuracy.
```

### Aggregated façade: `PeeGeeCache`

```java
package dev.mars.peegeeq.cache.api;

import dev.mars.peegeeq.cache.api.cache.CacheService;
import dev.mars.peegeeq.cache.api.counter.CounterService;
import dev.mars.peegeeq.cache.api.lock.LockService;
import dev.mars.peegeeq.cache.api.pubsub.PubSubService;
import dev.mars.peegeeq.cache.api.scan.ScanService;

public interface PeeGeeCache {

    CacheService cache();

    CounterService counters();

    LockService locks();

    ScanService scan();

    PubSubService pubSub();
}
```

---

## 9. Core public models

### 9.1 `CacheKey`

```java
package dev.mars.peegeeq.cache.api.model;

import java.util.Objects;

public record CacheKey(String namespace, String key) {

    public CacheKey {
        Objects.requireNonNull(namespace, "namespace must not be null");
        Objects.requireNonNull(key, "key must not be null");

        if (namespace.isBlank()) {
            throw new IllegalArgumentException("namespace must not be blank");
        }
        if (key.isBlank()) {
            throw new IllegalArgumentException("key must not be blank");
        }
    }

    public String asQualifiedKey() {
        return namespace + ":" + key;
    }
}
```

### 9.2 `LockKey`

```java
package dev.mars.peegeeq.cache.api.model;

public record LockKey(String namespace, String key) {
    public LockKey {
        if (namespace == null || namespace.isBlank()) {
            throw new IllegalArgumentException("namespace must not be blank");
        }
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("key must not be blank");
        }
    }

    public String asQualifiedKey() {
        return namespace + ":" + key;
    }
}
```

### 9.3 `CacheValue` and `ValueType`

```java
package dev.mars.peegeeq.cache.api.model;

import io.vertx.core.buffer.Buffer;

import java.nio.charset.StandardCharsets;
import java.util.Objects;

public record CacheValue(ValueType type, Buffer binaryValue, Long longValue) {

    public CacheValue {
        Objects.requireNonNull(type, "type must not be null");

        if (type == ValueType.LONG) {
            Objects.requireNonNull(longValue, "longValue must not be null for LONG values");
            if (binaryValue != null) {
                throw new IllegalArgumentException("binaryValue must be null for LONG values");
            }
        } else {
            Objects.requireNonNull(binaryValue, "binaryValue must not be null for binary-backed values");
            if (longValue != null) {
                throw new IllegalArgumentException("longValue must be null for non-LONG values");
            }
        }
    }

    public static CacheValue ofBytes(byte[] bytes) {
        return new CacheValue(ValueType.BYTES, Buffer.buffer(bytes), null);
    }

    public static CacheValue ofString(String value) {
        return new CacheValue(ValueType.STRING, Buffer.buffer(value, StandardCharsets.UTF_8.name()), null);
    }

    public static CacheValue ofJsonUtf8(String json) {
        return new CacheValue(ValueType.JSON, Buffer.buffer(json, StandardCharsets.UTF_8.name()), null);
    }

    public static CacheValue ofLong(long value) {
        return new CacheValue(ValueType.LONG, null, value);
    }

    public String asString() {
        if (binaryValue == null) {
            throw new IllegalStateException("Value is not binary-backed");
        }
        return binaryValue.toString(StandardCharsets.UTF_8);
    }

    public long asLong() {
        if (longValue == null) {
            throw new IllegalStateException("Value is not LONG");
        }
        return longValue;
    }
}
```

```java
package dev.mars.peegeeq.cache.api.model;

public enum ValueType {
    BYTES,
    STRING,
    JSON,
    LONG
}
```

### 9.4 `CacheEntry`

```java
package dev.mars.peegeeq.cache.api.model;

import java.time.Instant;

public record CacheEntry(
        CacheKey key,
        CacheValue value,
        long version,
        Instant createdAt,
        Instant updatedAt,
        Instant expiresAt,
        long hitCount,
        Instant lastAccessedAt
) {
    public boolean isExpiring() {
        return expiresAt != null;
    }
}
```

### 9.4a TTL result model

```java
package dev.mars.peegeeq.cache.api.model;

public enum TtlState {
    KEY_MISSING,
    PERSISTENT,
    EXPIRING
}
```

```java
package dev.mars.peegeeq.cache.api.model;

public record TtlResult(
        TtlState state,
        Long ttlMillis
) {
    public static TtlResult missing() {
        return new TtlResult(TtlState.KEY_MISSING, null);
    }

    public static TtlResult persistent() {
        return new TtlResult(TtlState.PERSISTENT, null);
    }

    public static TtlResult expiring(long ttlMillis) {
        return new TtlResult(TtlState.EXPIRING, ttlMillis);
    }
}
```

### 9.5 `SetMode`

```java
package dev.mars.peegeeq.cache.api.model;

public enum SetMode {
    UPSERT,
    ONLY_IF_ABSENT,
    ONLY_IF_PRESENT,
    ONLY_IF_VERSION_MATCHES
}
```

### 9.6 `CacheSetRequest`

```java
package dev.mars.peegeeq.cache.api.model;

import java.time.Duration;

public record CacheSetRequest(
        CacheKey key,
        CacheValue value,
        Duration ttl,
        SetMode mode,
        Long expectedVersion,
        boolean returnPreviousValue
) {}
```

### 9.7 `CacheSetResult`

```java
package dev.mars.peegeeq.cache.api.model;

public record CacheSetResult(
        boolean applied,
        long newVersion,
        CacheEntry previousEntry
) {}
```

`previousEntry` is nullable. When `CacheSetRequest.returnPreviousValue` is false or there was no previous entry, it is null.

### 9.8 `TouchResult`

```java
package dev.mars.peegeeq.cache.api.model;

public record TouchResult(
        boolean updated,
        TtlResult ttl
) {}
```

### 9.9 `CounterOptions`

```java
package dev.mars.peegeeq.cache.api.model;

import java.time.Duration;

public record CounterOptions(
        Duration ttl,
        boolean createIfMissing,
        CounterTtlMode ttlMode
) {
    public static CounterOptions defaults() {
        return new CounterOptions(null, true, CounterTtlMode.PRESERVE_EXISTING);
    }
}
```

```java
package dev.mars.peegeeq.cache.api.model;

public enum CounterTtlMode {
    PRESERVE_EXISTING,
    REPLACE,
    REMOVE
}
```

### 9.10 Lock models

```java
package dev.mars.peegeeq.cache.api.model;

import java.time.Duration;

public record LockAcquireRequest(
        LockKey key,
        String ownerToken,
        Duration leaseTtl,
        boolean reentrantForSameOwner,
        boolean issueFencingToken
) {}
```

```java
package dev.mars.peegeeq.cache.api.model;

import java.time.Instant;

public record LockAcquireResult(
        boolean acquired,
        LockKey key,
        String ownerToken,
        Long fencingToken,
        Instant leaseExpiresAt
) {}
```

```java
package dev.mars.peegeeq.cache.api.model;

import java.time.Instant;

public record LockState(
    LockKey key,
    String ownerToken,
    Long fencingToken,
    long version,
    Instant createdAt,
    Instant updatedAt,
    Instant leaseExpiresAt
) {}
```

```java
package dev.mars.peegeeq.cache.api.model;

import java.time.Duration;

public record LockRenewRequest(
        LockKey key,
        String ownerToken,
        Duration leaseTtl
) {}
```

```java
package dev.mars.peegeeq.cache.api.model;

public record LockReleaseRequest(
        LockKey key,
        String ownerToken
) {}
```

### 9.11 Scan models

```java
package dev.mars.peegeeq.cache.api.model;

public record ScanRequest(
        String namespace,
        String prefix,
        String cursor,
        int limit,
        boolean includeValues,
        boolean includeExpired
) {}
```

```java
package dev.mars.peegeeq.cache.api.model;

import java.util.List;

public record ScanResult(
        List<CacheEntry> entries,
        String nextCursor,
        boolean hasMore
) {}
```

### 9.12 Pub/sub models

```java
package dev.mars.peegeeq.cache.api.model;

public record PublishRequest(
        String channel,
        String payload,
        String contentType
) {}
```

```java
package dev.mars.peegeeq.cache.api.pubsub;

import io.vertx.core.Future;

public interface Subscription {
    String channel();
    Future<Void> unsubscribe();
}
```

### 9.13 Exception types

The `dev.mars.peegeeq.cache.api.exception` package should define the following exception hierarchy:

```java
package dev.mars.peegeeq.cache.api.exception;

public class CacheException extends RuntimeException {
    public CacheException(String message) { super(message); }
    public CacheException(String message, Throwable cause) { super(message, cause); }
}
```

```java
package dev.mars.peegeeq.cache.api.exception;

public class CacheKeyException extends CacheException {
    public CacheKeyException(String message) { super(message); }
}
```

```java
package dev.mars.peegeeq.cache.api.exception;

public class CacheStoreException extends CacheException {
    public CacheStoreException(String message, Throwable cause) { super(message, cause); }
}
```

```java
package dev.mars.peegeeq.cache.api.exception;

public class LockNotHeldException extends CacheException {
    public LockNotHeldException(String message) { super(message); }
}
```

Design rules:

- `CacheException` is the base for all peegee-cache exceptions
- `CacheKeyException` covers invalid keys, namespaces, or key format violations at the API boundary
- `CacheStoreException` wraps underlying storage failures (PostgreSQL errors, connection failures) and always carries a cause
- `LockNotHeldException` is thrown when a lock release or renew is attempted by a non-owner or on an expired lease
- service methods that return `Future<T>` should fail the future with these exceptions rather than throwing synchronously
- validation failures (null keys, blank namespaces) should throw `IllegalArgumentException` or `NullPointerException` synchronously from record constructors, not wrapped in futures

---

## 10. Bootstrap and configuration

### Recommended runtime bootstrap shape

To align with `PeeGeeQManager`, the recommended runtime entry point is a managed wrapper rather than a bare service graph.

```java
package dev.mars.peegeeq.cache.runtime;

import dev.mars.peegeeq.cache.api.PeeGeeCache;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.sqlclient.Pool;

public interface PeeGeeCacheManager extends AutoCloseable {

    Future<Void> startReactive();

    Future<Void> stopReactive();

    boolean isStarted();

    Vertx vertx();

    Pool pool();

    PeeGeeCache cache();
}
```

### Low-level public factory

```java
package dev.mars.peegeeq.cache.runtime.bootstrap;

import dev.mars.peegeeq.cache.runtime.PeeGeeCacheManager;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.sqlclient.Pool;

public interface PeeGeeCacheFactory {

    Future<PeeGeeCacheManager> createManager(Vertx vertx, Pool pool, PeeGeeCacheBootstrapOptions options);
}
```

Alternative static bootstrap helper:

```java
package dev.mars.peegeeq.cache.runtime.bootstrap;

import dev.mars.peegeeq.cache.runtime.PeeGeeCacheManager;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.sqlclient.Pool;

public final class PeeGeeCaches {

    private PeeGeeCaches() {
    }

    public static Future<PeeGeeCacheManager> createManager(Vertx vertx, Pool pool, PeeGeeCacheBootstrapOptions options) {
        return Future.failedFuture("Not yet implemented");
    }
}
```

```java
package dev.mars.peegeeq.cache.runtime.bootstrap;

import dev.mars.peegeeq.cache.pg.runtime.PgCacheStoreConfig;
import dev.mars.peegeeq.cache.runtime.config.PeeGeeCacheConfig;

public record PeeGeeCacheBootstrapOptions(
        PeeGeeCacheConfig runtimeConfig,
        PgCacheStoreConfig storeConfig,
        boolean startExpirySweeper,
        boolean startPubSubListener
) {}
```

**Known coupling:** `PeeGeeCacheBootstrapOptions` in the `runtime` module directly imports `PgCacheStoreConfig` from the `pg` module. This means the runtime module is permanently bound to the PostgreSQL implementation at compile time. If a second storage backend is added later, this coupling will need to be broken by introducing a store-agnostic configuration SPI. Acceptable for Phase 1 where PostgreSQL is the only backend.

### Runtime config

```java
package dev.mars.peegeeq.cache.runtime.config;

import java.time.Duration;

public record PeeGeeCacheConfig(
        Duration defaultTtl,
        Duration expirySweepInterval,
        int expirySweepBatchSize,
        int maxScanLimit,
        boolean enablePubSub,
        boolean enableExpirySweeper,
        boolean sampleReadMetrics,
        int readMetricSampleRate
) {}
```

### PostgreSQL-specific config

```java
package dev.mars.peegeeq.cache.pg.runtime;

public record PgCacheStoreConfig(
        String schemaName,
        String cacheTable,
        String lockTable,
        String counterTable,
        String pubSubChannelPrefix,
        boolean useUnloggedCacheTable,
        boolean useDedicatedListenerConnection
) {}
```

**Deployment-time constraint:** `useUnloggedCacheTable` is a schema-creation-time decision, not a runtime toggle. PostgreSQL does not support `ALTER TABLE` to change a table between logged and unlogged. This flag affects migration behavior only. Once the schema is created, changing this value requires dropping and recreating the `cache_entries` table. The migration logic should check this flag when creating the table and produce `CREATE UNLOGGED TABLE` or `CREATE TABLE` accordingly.
```

---

## 11. Internal SPI ideas

These should remain internal or semi-internal, not part of the main business API.

```java
package dev.mars.peegeeq.cache.spi;

import java.time.Instant;

public interface TimeSource {
    Instant now();
}
```

```java
package dev.mars.peegeeq.cache.spi;

public interface MetricsRecorder {
    void recordGet(String namespace, boolean hit);
    void recordSet(String namespace, boolean applied);
    void recordDelete(String namespace, long count);
}
```

---

## 12. First-cut PostgreSQL implementation classes

Expected classes in `peegee-cache-pg`:

```text
PgPeeGeeCache
PgPeeGeeCacheManager
PgCacheService
PgCounterService
PgLockService
PgScanService
PgPubSubService

PgCacheRepository
PgCounterRepository
PgLockRepository
PgScanRepository
PgNotificationListener

SqlStatements
RowMappers
PgTxSupport
```

Expected runtime classes:

```text
ExpirySweeper
PubSubListenerRuntime
PeeGeeCacheBootstrap
PeeGeeCacheLifecycle
```

---

## 13. PostgreSQL schema (Phase 1)

Use a dedicated schema:

```sql
CREATE SCHEMA IF NOT EXISTS peegee_cache;
```

Phase 1 tables:

- `peegee_cache.cache_entries`
- `peegee_cache.cache_counters`
- `peegee_cache.cache_locks`

Optional later:
- `peegee_cache.cache_events`
- `peegee_cache.hash_entries`
- `peegee_cache.zset_entries`
- `peegee_cache.queue_entries`

### 13.1 `cache_entries`

For disposable cache data, this table may be created as `UNLOGGED` when the operator explicitly chooses write speed and WAL reduction over crash persistence. That matches real cache semantics well: logically reconstructable data can be lost on crash without violating system truth.

Default recommendation:

- `cache_entries`: optionally `UNLOGGED`
- `cache_counters`: usually logged
- `cache_locks`: logged

Reasoning:

- plain cache values are often derived or refillable
- counters may participate in rate limits, quotas, or billing-like controls
- locks are coordination state and should not disappear after a crash unless the runtime explicitly chooses that behavior

```sql
CREATE TABLE IF NOT EXISTS peegee_cache.cache_entries (
    namespace           TEXT                        NOT NULL,
    cache_key           TEXT                        NOT NULL,
    value_type          TEXT                        NOT NULL,
    value_bytes         BYTEA,
    numeric_value       BIGINT,
    version             BIGINT                      NOT NULL DEFAULT 1,
    created_at          TIMESTAMPTZ                 NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ                 NOT NULL DEFAULT NOW(),
    expires_at          TIMESTAMPTZ,
    hit_count           BIGINT                      NOT NULL DEFAULT 0,
    last_accessed_at    TIMESTAMPTZ,
    PRIMARY KEY (namespace, cache_key),
    CONSTRAINT chk_cache_entries_value_type
        CHECK (value_type IN ('BYTES', 'STRING', 'JSON', 'LONG')),
    CONSTRAINT chk_cache_entries_value_payload
        CHECK (
            (
                value_type = 'LONG'
                AND numeric_value IS NOT NULL
                AND value_bytes IS NULL
            )
            OR
            (
                value_type IN ('BYTES', 'STRING', 'JSON')
                AND value_bytes IS NOT NULL
                AND numeric_value IS NULL
            )
        )
);
```

#### Why these columns exist

- `namespace`, `cache_key`: composite primary key, first-class namespace support
- `value_type`: supports BYTES / STRING / JSON / LONG
- `value_bytes`: binary-safe payload storage
- `numeric_value`: typed numeric support
- `version`: CAS / optimistic concurrency
- `created_at`, `updated_at`: operational visibility
- `expires_at`: TTL
- `hit_count`, `last_accessed_at`: optional observability fields

The payload `CHECK` is intentionally exclusive, not just permissive:

- `LONG` rows must use `numeric_value` only
- `BYTES` / `STRING` / `JSON` rows must use `value_bytes` only
- mixed payload rows are rejected at the database layer rather than relying on application discipline

### 13.2 `cache_entries` indexes

Expiry index:

```sql
CREATE INDEX IF NOT EXISTS idx_cache_entries_expires_at
    ON peegee_cache.cache_entries (expires_at)
    WHERE expires_at IS NOT NULL;
```

Prefix-scan support:

```sql
CREATE INDEX IF NOT EXISTS idx_cache_entries_namespace_key_pattern
    ON peegee_cache.cache_entries (namespace, cache_key text_pattern_ops);
```

### 13.3 `cache_counters`

Counters should not be stuffed into the generic cache table if heavy counter usage is expected.

Unlike disposable cache entries, counters should default to logged tables because they often affect externally visible control flow such as rate limiting, throttling, and usage accounting.

```sql
CREATE TABLE IF NOT EXISTS peegee_cache.cache_counters (
    namespace           TEXT                        NOT NULL,
    counter_key         TEXT                        NOT NULL,
    counter_value       BIGINT                      NOT NULL,
    version             BIGINT                      NOT NULL DEFAULT 1,
    created_at          TIMESTAMPTZ                 NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ                 NOT NULL DEFAULT NOW(),
    expires_at          TIMESTAMPTZ,
    PRIMARY KEY (namespace, counter_key)
);
```

Indexes:

```sql
CREATE INDEX IF NOT EXISTS idx_cache_counters_expires_at
    ON peegee_cache.cache_counters (expires_at)
    WHERE expires_at IS NOT NULL;
```

```sql
CREATE INDEX IF NOT EXISTS idx_cache_counters_namespace_key_pattern
    ON peegee_cache.cache_counters (namespace, counter_key text_pattern_ops);
```

### 13.4 `cache_locks`

Locks are not just cache entries with TTL. They need proper ownership and lease semantics.

Locks should default to logged storage. Losing lease state on crash may be acceptable in some narrow designs, but it should not be the default contract for a coordination primitive.

```sql
CREATE TABLE IF NOT EXISTS peegee_cache.cache_locks (
    namespace           TEXT                        NOT NULL,
    lock_key            TEXT                        NOT NULL,
    owner_token         TEXT                        NOT NULL,
    fencing_token       BIGINT,
    version             BIGINT                      NOT NULL DEFAULT 1,
    created_at          TIMESTAMPTZ                 NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ                 NOT NULL DEFAULT NOW(),
    lease_expires_at    TIMESTAMPTZ                 NOT NULL,
    PRIMARY KEY (namespace, lock_key),
    CONSTRAINT chk_cache_locks_future_lease
        CHECK (lease_expires_at > updated_at)
);
```

Lease times should be derived from the PostgreSQL server clock, not from caller-supplied absolute timestamps. API requests should carry a positive lease TTL, and SQL should compute `lease_expires_at` from `NOW()` inside the statement.

Index:

```sql
CREATE INDEX IF NOT EXISTS idx_cache_locks_lease_expires_at
    ON peegee_cache.cache_locks (lease_expires_at);
```

### 13.5 Lock fencing token sequence

```sql
CREATE SEQUENCE IF NOT EXISTS peegee_cache.lock_fencing_seq;
```

---

## 14. Data typing decisions

### `TEXT` for namespace and keys
Correct for Phase 1. Do not over-engineer with varchar lengths in the database first.

### `BYTEA` for stored values
Correct for a cache library:
- binary-safe
- easy Vert.x Buffer mapping
- no JSON-only lock-in

### `TIMESTAMPTZ`
Use it everywhere for temporal fields.

### `BIGINT version`
Correct and simple for mutation versioning.

---

## 15. Expiry semantics

This is one of the most important design decisions.

### Lazy expiry is authoritative
A row is treated as absent if:

```sql
expires_at IS NOT NULL AND expires_at <= NOW()
```

### Background cleanup is eventual
A sweeper deletes physically expired rows later.

That means:
- logical expiry is immediate on read
- physical deletion is eventual

This is the correct model.

### Consequences
Write operations such as set-if-absent, counter create, and lock acquire should usually treat expired rows as absent. In practice this means transactional “delete expired then act” flows are often required for correctness.

---

## 16. Core SQL operations

### 16.1 GET cache entry

```sql
SELECT
    namespace,
    cache_key,
    value_type,
    value_bytes,
    numeric_value,
    version,
    created_at,
    updated_at,
    expires_at,
    hit_count,
    last_accessed_at
FROM peegee_cache.cache_entries
WHERE namespace = $1
  AND cache_key = $2
  AND (expires_at IS NULL OR expires_at > NOW());
```

### 16.2 EXISTS

```sql
SELECT EXISTS (
    SELECT 1
    FROM peegee_cache.cache_entries
    WHERE namespace = $1
      AND cache_key = $2
      AND (expires_at IS NULL OR expires_at > NOW())
);
```

### 16.3 DELETE

```sql
DELETE FROM peegee_cache.cache_entries
WHERE namespace = $1
  AND cache_key = $2;
```

### 16.4 UPSERT set

```sql
INSERT INTO peegee_cache.cache_entries (
    namespace,
    cache_key,
    value_type,
    value_bytes,
    numeric_value,
    version,
    created_at,
    updated_at,
    expires_at,
    hit_count,
    last_accessed_at
)
VALUES (
    $1, $2, $3, $4, $5,
    1, NOW(), NOW(), $6, 0, NULL
)
ON CONFLICT (namespace, cache_key)
DO UPDATE SET
    value_type       = EXCLUDED.value_type,
    value_bytes      = EXCLUDED.value_bytes,
    numeric_value    = EXCLUDED.numeric_value,
    version          = peegee_cache.cache_entries.version + 1,
    updated_at       = NOW(),
    expires_at       = EXCLUDED.expires_at
RETURNING version;
```

#### Returning the previous value

When `CacheSetRequest.returnPreviousValue` is `true`, the upsert alone is insufficient because `INSERT ... ON CONFLICT DO UPDATE ... RETURNING` returns the *new* row, not the old one.

The correct strategy is a pre-read inside the same transaction:

Step 1: read current entry (if any) with row lock:

```sql
SELECT
    namespace, cache_key, value_type, value_bytes, numeric_value,
    version, created_at, updated_at, expires_at, hit_count, last_accessed_at
FROM peegee_cache.cache_entries
WHERE namespace = $1
  AND cache_key = $2
  AND (expires_at IS NULL OR expires_at > NOW())
FOR UPDATE;
```

Step 2: perform the upsert as above.

Both steps must run inside the same database transaction. The repository layer should only execute step 1 when `returnPreviousValue = true` to avoid unnecessary row-locking overhead.

### 16.5 SET if absent (`NX` semantics)

Naive insert:

```sql
INSERT INTO peegee_cache.cache_entries (
    namespace,
    cache_key,
    value_type,
    value_bytes,
    numeric_value,
    version,
    created_at,
    updated_at,
    expires_at,
    hit_count,
    last_accessed_at
)
VALUES (
    $1, $2, $3, $4, $5,
    1, NOW(), NOW(), $6, 0, NULL
)
ON CONFLICT (namespace, cache_key) DO NOTHING
RETURNING version;
```

But that is **not enough** because expired rows still physically exist and will still conflict.

Correct practical V1 strategy:

```sql
DELETE FROM peegee_cache.cache_entries
WHERE namespace = $1
  AND cache_key = $2
  AND expires_at IS NOT NULL
  AND expires_at <= NOW();
```

Then insert in the same transaction.

### 16.6 SET only if present (`XX` semantics)

```sql
UPDATE peegee_cache.cache_entries
SET
    value_type    = $3,
    value_bytes   = $4,
    numeric_value = $5,
    version       = version + 1,
    updated_at    = NOW(),
    expires_at    = $6
WHERE namespace = $1
  AND cache_key = $2
  AND (expires_at IS NULL OR expires_at > NOW())
RETURNING version;
```

### 16.7 SET only if version matches

```sql
UPDATE peegee_cache.cache_entries
SET
    value_type    = $4,
    value_bytes   = $5,
    numeric_value = $6,
    version       = version + 1,
    updated_at    = NOW(),
    expires_at    = $7
WHERE namespace = $1
  AND cache_key = $2
  AND version = $3
  AND (expires_at IS NULL OR expires_at > NOW())
RETURNING version;
```

### 16.8 TTL lookup

```sql
SELECT
    CASE
        WHEN expires_at IS NULL THEN NULL
        ELSE FLOOR(EXTRACT(EPOCH FROM (expires_at - NOW())) * 1000)::BIGINT
    END AS ttl_millis
FROM peegee_cache.cache_entries
WHERE namespace = $1
  AND cache_key = $2
  AND (expires_at IS NULL OR expires_at > NOW());
```

This query maps directly to `TtlResult`:

- no row returned -> `TtlResult.missing()`
- row returned with `expires_at IS NULL` -> `TtlResult.persistent()`
- row returned with positive remaining time -> `TtlResult.expiring(ttlMillis)`

### 16.9 EXPIRE existing key

```sql
UPDATE peegee_cache.cache_entries
SET
    expires_at = NOW() + ($3 * INTERVAL '1 millisecond'),
    version = version + 1,
    updated_at = NOW()
WHERE namespace = $1
  AND cache_key = $2
  AND (expires_at IS NULL OR expires_at > NOW());
```

### 16.10 PERSIST existing key

```sql
UPDATE peegee_cache.cache_entries
SET
    expires_at = NULL,
    version = version + 1,
    updated_at = NOW()
WHERE namespace = $1
  AND cache_key = $2
  AND (expires_at IS NULL OR expires_at > NOW());
```

### 16.11 TOUCH existing key

`touch()` resets both the TTL to the provided duration and updates `last_accessed_at`. It does **not** change the stored value, value type, or version. It is a lightweight metadata-only update used for keep-alive and access-tracking scenarios.

```sql
UPDATE peegee_cache.cache_entries
SET
    expires_at = CASE WHEN $3 IS NOT NULL
                      THEN NOW() + ($3 * INTERVAL '1 millisecond')
                      ELSE expires_at
                 END,
    last_accessed_at = NOW(),
    updated_at = NOW()
WHERE namespace = $1
  AND cache_key = $2
  AND (expires_at IS NULL OR expires_at > NOW())
RETURNING
    CASE
        WHEN expires_at IS NULL THEN NULL
        ELSE FLOOR(EXTRACT(EPOCH FROM (expires_at - NOW())) * 1000)::BIGINT
    END AS ttl_millis;
```

Return mapping:

- no row updated → `TouchResult(false, TtlResult.missing())`
- row updated with `expires_at IS NULL` → `TouchResult(true, TtlResult.persistent())`
- row updated with positive remaining time → `TouchResult(true, TtlResult.expiring(ttlMillis))`

If `$3` (ttl millis) is `NULL`, the existing TTL is preserved and only `last_accessed_at` is refreshed.

### 16.12 SCAN by namespace/prefix

Do **not** use offset pagination.

```sql
SELECT
    namespace,
    cache_key,
    value_type,
    value_bytes,
    numeric_value,
    version,
    created_at,
    updated_at,
    expires_at,
    hit_count,
    last_accessed_at
FROM peegee_cache.cache_entries
WHERE namespace = $1
  AND ($2 IS NULL OR cache_key LIKE $2 || '%')
  AND ($3 IS NULL OR cache_key > $3)
  AND (expires_at IS NULL OR expires_at > NOW())
ORDER BY cache_key
LIMIT $4;
```

Cursor should be opaque to callers, even if internally it carries namespace/prefix/lastKey information.

---

## 17. Counter SQL operations

### 17.1 GET counter

```sql
SELECT counter_value
FROM peegee_cache.cache_counters
WHERE namespace = $1
  AND counter_key = $2
  AND (expires_at IS NULL OR expires_at > NOW());
```

### 17.2 INCREMENT with create-if-missing

Counter TTL policy is **not** implicit. It is controlled by `CounterOptions.ttlMode`.

Policy rules:

- on insert, a non-null `ttl` sets `expires_at`; null means persistent
- `PRESERVE_EXISTING` keeps the current expiry for existing counters
- `REPLACE` sets `expires_at` to the new requested expiry
- `REMOVE` clears `expires_at` for existing counters

`PRESERVE_EXISTING` SQL:

```sql
INSERT INTO peegee_cache.cache_counters (
    namespace,
    counter_key,
    counter_value,
    version,
    created_at,
    updated_at,
    expires_at
)
VALUES (
    $1, $2, $3, 1, NOW(), NOW(), $4
)
ON CONFLICT (namespace, counter_key)
DO UPDATE SET
    counter_value = peegee_cache.cache_counters.counter_value + EXCLUDED.counter_value,
    version = peegee_cache.cache_counters.version + 1,
    updated_at = NOW(),
    expires_at = COALESCE(peegee_cache.cache_counters.expires_at, EXCLUDED.expires_at)
RETURNING counter_value, version;
```

`REPLACE` SQL changes only the expiry assignment:

```sql
expires_at = EXCLUDED.expires_at
```

`REMOVE` SQL changes only the expiry assignment:

```sql
expires_at = NULL
```

If `ttlMode = REPLACE`, the API should reject null `ttl` rather than silently converting that case into `REMOVE`.

### 17.2a INCREMENT without create (`createIfMissing = false`)

When `CounterOptions.createIfMissing` is `false`, use a pure `UPDATE` with no insert fallback. If the counter does not exist, zero rows are affected and the operation returns empty.

```sql
UPDATE peegee_cache.cache_counters
SET
    counter_value = counter_value + $3,
    version = version + 1,
    updated_at = NOW()
WHERE namespace = $1
  AND counter_key = $2
  AND (expires_at IS NULL OR expires_at > NOW())
RETURNING counter_value, version;
```

The caller should treat zero affected rows as "counter does not exist" and fail the future with an appropriate error or return `Optional.empty()` depending on the API contract.

### 17.3 Delete expired counter then increment

For correct semantics:

```sql
DELETE FROM peegee_cache.cache_counters
WHERE namespace = $1
  AND counter_key = $2
  AND expires_at IS NOT NULL
  AND expires_at <= NOW();
```

Then do the upsert in the same transaction.

**Concurrency note:** When two transactions concurrently encounter an expired counter row, both may attempt the delete-then-upsert sequence. One transaction will delete the expired row and insert a fresh counter. The other will find the row already gone (delete affects zero rows) and also insert, hitting the `ON CONFLICT` clause and updating. The net effect is correct — both increments are applied — but the counter resets to the new initial value before the second increment rather than continuing from the old expired value. This is the intended semantic: an expired counter is logically absent, so concurrent post-expiry increments both start from the initial value.

### 17.4 Set exact counter value

```sql
INSERT INTO peegee_cache.cache_counters (
    namespace,
    counter_key,
    counter_value,
    version,
    created_at,
    updated_at,
    expires_at
)
VALUES (
    $1, $2, $3, 1, NOW(), NOW(), $4
)
ON CONFLICT (namespace, counter_key)
DO UPDATE SET
    counter_value = EXCLUDED.counter_value,
    version = peegee_cache.cache_counters.version + 1,
    updated_at = NOW(),
    expires_at = EXCLUDED.expires_at
RETURNING counter_value, version;
```

---

## 18. Lock SQL operations

Locks should be lease-based only. No permanent locks.

### 18.1 Acquire lock if absent or expired

Use a positive lease TTL parameter, not a caller-computed absolute timestamp. That keeps lock correctness tied to the database clock and avoids skew between application nodes.

Step 1: delete stale lease

```sql
DELETE FROM peegee_cache.cache_locks
WHERE namespace = $1
  AND lock_key = $2
  AND lease_expires_at <= NOW();
```

Step 2: insert new lease

The `fencing_token` value depends on `LockAcquireRequest.issueFencingToken`:

- when `true`: use `nextval('peegee_cache.lock_fencing_seq')` to generate a monotonically increasing token
- when `false`: use `NULL`

The repository should select the appropriate SQL variant or bind the value accordingly.

When `issueFencingToken = true`:

```sql
INSERT INTO peegee_cache.cache_locks (
    namespace,
    lock_key,
    owner_token,
    fencing_token,
    version,
    created_at,
    updated_at,
    lease_expires_at
)
VALUES (
    $1, $2, $3, nextval('peegee_cache.lock_fencing_seq'), 1, NOW(), NOW(),
    NOW() + ($4 * INTERVAL '1 millisecond')
)
ON CONFLICT (namespace, lock_key) DO NOTHING
RETURNING namespace, lock_key, owner_token, fencing_token, lease_expires_at;
```

When `issueFencingToken = false`:

```sql
INSERT INTO peegee_cache.cache_locks (
    namespace,
    lock_key,
    owner_token,
    fencing_token,
    version,
    created_at,
    updated_at,
    lease_expires_at
)
VALUES (
    $1, $2, $3, NULL, 1, NOW(), NOW(),
    NOW() + ($4 * INTERVAL '1 millisecond')
)
ON CONFLICT (namespace, lock_key) DO NOTHING
RETURNING namespace, lock_key, owner_token, fencing_token, lease_expires_at;
```

### 18.2 Reentrant acquire by same owner (optional)

```sql
UPDATE peegee_cache.cache_locks
SET
    lease_expires_at = NOW() + ($4 * INTERVAL '1 millisecond'),
    version = version + 1,
    updated_at = NOW()
WHERE namespace = $1
  AND lock_key = $2
  AND owner_token = $3
  AND lease_expires_at > NOW()
RETURNING namespace, lock_key, owner_token, fencing_token, lease_expires_at;
```

### 18.3 Renew lease only by owner

```sql
UPDATE peegee_cache.cache_locks
SET
    lease_expires_at = NOW() + ($4 * INTERVAL '1 millisecond'),
    version = version + 1,
    updated_at = NOW()
WHERE namespace = $1
  AND lock_key = $2
  AND owner_token = $3
  AND lease_expires_at > NOW()
RETURNING lease_expires_at;
```

### 18.4 Release only by owner

```sql
DELETE FROM peegee_cache.cache_locks
WHERE namespace = $1
  AND lock_key = $2
  AND owner_token = $3;
```

### 18.5 Read current lock if active

```sql
SELECT
    namespace,
    lock_key,
    owner_token,
    fencing_token,
    version,
    created_at,
    updated_at,
    lease_expires_at
FROM peegee_cache.cache_locks
WHERE namespace = $1
  AND lock_key = $2
  AND lease_expires_at > NOW();
```

---

## 19. Expiry sweeper SQL

Background cleanup belongs in runtime/background processing.

### Sweep expired cache entries

PostgreSQL needs a CTE for bounded deletes:

```sql
WITH expired AS (
    SELECT namespace, cache_key
    FROM peegee_cache.cache_entries
    WHERE expires_at IS NOT NULL
      AND expires_at <= NOW()
    ORDER BY expires_at
    LIMIT $1
)
DELETE FROM peegee_cache.cache_entries ce
USING expired e
WHERE ce.namespace = e.namespace
  AND ce.cache_key = e.cache_key;
```

### Sweep expired counters

```sql
WITH expired AS (
    SELECT namespace, counter_key
    FROM peegee_cache.cache_counters
    WHERE expires_at IS NOT NULL
      AND expires_at <= NOW()
    ORDER BY expires_at
    LIMIT $1
)
DELETE FROM peegee_cache.cache_counters cc
USING expired e
WHERE cc.namespace = e.namespace
  AND cc.counter_key = e.counter_key;
```

### Sweep stale locks

```sql
WITH expired AS (
    SELECT namespace, lock_key
    FROM peegee_cache.cache_locks
    WHERE lease_expires_at <= NOW()
    ORDER BY lease_expires_at
    LIMIT $1
)
DELETE FROM peegee_cache.cache_locks cl
USING expired e
WHERE cl.namespace = e.namespace
  AND cl.lock_key = e.lock_key;
```

---

## 20. Pub/sub integration

Phase 1 does not require a table for pub/sub if PostgreSQL `LISTEN/NOTIFY` is used.

Suggested channel naming:

```text
peegee_cache__{channel}
```

Publish via:

```sql
SELECT pg_notify($1, $2);
```

One important architectural advantage over a separate Redis publish step is that `pg_notify` can be part of the same transaction as the underlying row changes. If the surrounding transaction rolls back, the notification should not be treated as committed state.

For domain flows that need “write plus notify” atomicity, prefer one of these patterns:

- execute `pg_notify` in the same transaction as the data mutation
- or use a trigger/outbox-style pattern so notification emission is coupled to committed database state

Important limitations:
- `NOTIFY` payload is text and size-limited
- binary payload passthrough should **not** be part of the Phase 1 public API
- for Phase 1, payloads should be UTF-8 text or JSON strings with optional `contentType`

Phase 1 public contract:

- `PublishRequest.payload` is `String`
- `PublishRequest.contentType` is advisory metadata such as `text/plain` or `application/json`
- subscribers receive `PubSubMessage` with string payloads
- pattern subscriptions are explicitly a V2 feature, not part of the Phase 1 API

Operational guidance:

- use a dedicated PostgreSQL connection for the `LISTEN` loop
- treat pub/sub as best-effort fan-out, not durable delivery
- drop or reject oversized payloads early instead of silently truncating them
- if durable notifications become a requirement, add a separate events table/module rather than stretching `NOTIFY`

Pub/sub in Phase 1 is for:
- cache invalidation
- local worker wakeups
- lightweight internal notifications

It is **not** a durable messaging system and should not be sold as one.

---

## 21. Migration file structure

Recommended migration files:

```text
V001__create_schema.sql
V002__create_cache_entries.sql
V003__create_cache_counters.sql
V004__create_cache_locks.sql
V005__create_lock_fencing_sequence.sql
V006__create_indexes.sql
```

That is cleaner than a single giant migration.

---

## 22. Non-functional requirements and concerns

### Binary-safe values
Do not build a cache that only stores strings and JSON unless you want to regret it later.

### Typed values
Support at least:
- bytes
- string
- json
- long / numeric

Do **not** expose Java serialization as a first-class public API.

### Metrics
Need instrumentation for:
- gets
- sets
- hits
- misses
- expired-on-read
- sweeper deletions
- lock acquire success/failure
- pub/sub publish failures
- pub/sub listener reconnects
- queue claim latency later
- pub/sub send/receive counts

### Concurrency tests
Need brutal integration tests for:
- two clients incrementing same key
- two workers racing to claim the same logical resource
- two owners racing for same lock
- expiry while reading
- renew vs release races
- notification ordering assumptions

### PostgreSQL hygiene
This is where PostgreSQL-backed caches can go wrong if you are careless:
- update churn
- delete churn
- index bloat
- autovacuum tuning
- table partitioning later if needed
- logged vs unlogged table decisions

Operationally, this design should assume:

- aggressive monitoring of bloat on high-churn cache tables
- explicit autovacuum tuning for cache and queue-like tables
- connection pooling as a baseline requirement, not an afterthought
- realistic performance benchmarking on combined application flows, not only isolated GET/SET microbenchmarks

The important benchmark is often not “single cache read vs Redis” but “write domain row + invalidate cache + notify subscribers” inside one transactional boundary.

### Read-path write amplification
Updating `hit_count` and `last_accessed_at` on every read may be operationally stupid for hot keys.

Better options:
- sampled updates
- optional update mode via config
- async/statistical tracking later

### Dedicated listener connections
PostgreSQL `LISTEN/NOTIFY` should not share the same connection flow as normal query traffic.

Use a dedicated listener connection or pool slot for pub/sub runtime so that:

- listener backpressure does not block foreground cache operations
- reconnect logic stays isolated
- lifecycle ownership is explicit in the runtime manager

---

## 23. Explicit design calls

These are the deliberate design choices made so far.

### Use separate services, not one giant god interface
Correct:
- `CacheService`
- `CounterService`
- `LockService`
- `ScanService`
- `PubSubService`

Wrong:
- `RedisLikeService` with a hundred unrelated methods

### Use first-class namespace + key
Correct:
- `CacheKey(namespace, key)`

Wrong:
- raw string everywhere

### Use typed requests for writes
Correct:
- `CacheSetRequest`

Wrong:
- ten overloaded `set(...)` methods that rot over time

### Use optimistic versioning early
Correct:
- every mutable entry has a `version`

Wrong:
- add versioning later after clients depend on weaker semantics

### Keep pub/sub deliberately narrow
Correct:
- invalidation and light notifications

Wrong:
- pretending PostgreSQL `NOTIFY` is Kafka

### Be honest about where Redis still wins
Redis still has a better fit when the workload requires:

- extreme throughput or very low-latency hot-path reads
- large-scale dedicated cache-tier behavior
- Redis-native structures such as streams, mature sorted-set-heavy workflows, HyperLogLog, or similar specialized patterns
- architectures that explicitly require an external cache tier independent from the primary database

`peegee-cache` should be sold as a PostgreSQL-native coordination and cache library, not as a universal reason to remove Redis from every system.

### Do not try to emulate Redis transactions exactly
Use SQL transactions instead.

### Do not treat lists as a priority
If queue semantics are needed, build a proper queue module later.

### Do not try to build protocol compatibility first
Build the Java library first. Network/server adapters can come later.

### Align with PeeGeeQ runtime patterns
Correct:
- explicit bootstrap options
- managed start/stop lifecycle
- clear ownership of `Vertx` and `Pool`

Wrong:
- hidden singleton startup
- implicit background threads with no lifecycle owner

---

## 24. Example usage

```java
PeeGeeCacheManager manager = await(PeeGeeCaches.createManager(vertx, pool, options));
await(manager.startReactive());

PeeGeeCache cache = manager.cache();

CacheKey sessionKey = new CacheKey("session", "abc123");

CacheSetRequest request = new CacheSetRequest(
        sessionKey,
    CacheValue.ofJsonUtf8("{\"userId\":\"42\"}"),
        Duration.ofMinutes(30),
        SetMode.UPSERT,
        null,
        false
);

CacheSetResult result = await(cache.cache().set(request));

Optional<CacheEntry> loaded = await(cache.cache().get(sessionKey));

TtlResult ttl = await(cache.cache().ttl(sessionKey));

Long next = await(cache.counters().incrementBy(new CacheKey("rate-limit", "client-1"), 1));

LockAcquireResult lock = await(cache.locks().acquire(
        new LockAcquireRequest(
                new LockKey("jobs", "worker-election"),
                "node-a",
                Duration.ofSeconds(15),
                false,
                true
        )
));

Subscription subscription = await(cache.pubSub().subscribe("cache-invalidation", message -> {
    System.out.println(message.channel() + " -> " + message.payload());
}));
```

---

## 25. Native SQL interface

`peegee-cache` should expose a **native PostgreSQL interface out of the box**, but the support boundary must be explicit.

### What “out of the box” should mean

Supported immediately:

- direct SQL reads against documented tables and views
- `psql` inspection and operational queries
- reporting, debugging, and admin scripts that read cache state directly
- SQL functions/procedures for correctness-sensitive writes

Not automatically supported as a stable contract:

- arbitrary external writes directly against base tables for locks, counters, or TTL-aware key mutation
- expecting every SQL caller to reimplement `NX`, expiry-aware upsert, lock lease rules, or notification semantics correctly

The rule should be simple:

- **reads may use tables/views directly**
- **writes should use documented SQL functions for semantics that matter under concurrency**

### Recommended support model

Use a mixed model:

1. **Table/view read interface**
    - stable read access for ops and diagnostics
    - examples: inspect a key, list expiring entries, inspect active locks, review counters

2. **Function-based write interface**
    - stable SQL entry points for mutation semantics
    - examples: set key, get key, expire key, increment counter, acquire lock, renew lock, release lock, publish notification

3. **Implementation tables remain internal-by-default for writes**
    - external callers should not depend on internal multi-step write flows unless the doc explicitly marks them as supported

### Why this is necessary

Direct writes are fine only for trivial cases. They become unsafe once the semantics involve:

- treating expired rows as absent
- `ON CONFLICT` plus version increments
- owner-matched lock renewal/release
- lease expiry computed from the database clock
- atomic “write plus notify” behavior

Those are exactly the cases where a stable SQL function is better than telling every caller to copy a multi-statement transaction from the docs.

### Recommended SQL API shape

Illustrative function names:

```sql
peegee_cache.get_entry(namespace text, cache_key text)
peegee_cache.set_entry(namespace text, cache_key text, value_type text, value_bytes bytea, numeric_value bigint, ttl_millis bigint, mode text, expected_version bigint)
peegee_cache.delete_entry(namespace text, cache_key text)
peegee_cache.expire_entry(namespace text, cache_key text, ttl_millis bigint)
peegee_cache.persist_entry(namespace text, cache_key text)

peegee_cache.increment_counter(namespace text, counter_key text, delta bigint, ttl_millis bigint, ttl_mode text)
peegee_cache.set_counter(namespace text, counter_key text, counter_value bigint, ttl_millis bigint)

peegee_cache.acquire_lock(namespace text, lock_key text, owner_token text, lease_ttl_millis bigint, issue_fencing_token boolean)
peegee_cache.renew_lock(namespace text, lock_key text, owner_token text, lease_ttl_millis bigint)
peegee_cache.release_lock(namespace text, lock_key text, owner_token text)

peegee_cache.publish(channel text, payload text, content_type text)
```

These do not need to be implemented before the Java API exists, but the design should reserve space for them now so non-Java callers are not treated as an accidental afterthought.

### Recommended first-cut support policy

For Phase 1:

- document tables for read access
- document views for friendly inspection where useful
- expose SQL functions for lock and counter mutation first
- expose SQL functions for cache writes if non-Java callers are a real requirement in the first release

For later phases:

- decide whether the SQL function interface is a first-class product surface with compatibility guarantees
- add migration/versioning policy for SQL functions if external clients depend on them

### Operational examples that should work from `psql`

Examples of intentionally supported direct reads:

```sql
SELECT *
FROM peegee_cache.cache_entries
WHERE namespace = 'session'
  AND cache_key = 'abc123';

SELECT *
FROM peegee_cache.cache_locks
WHERE namespace = 'jobs'
  AND lease_expires_at > NOW()
ORDER BY lease_expires_at;
```

Examples of writes that should prefer functions over raw table DML:

```sql
SELECT * FROM peegee_cache.acquire_lock('jobs', 'worker-election', 'node-a', 15000, true);

SELECT * FROM peegee_cache.increment_counter('rate-limit', 'client-1', 1, 60000, 'PRESERVE_EXISTING');
```

That gives operators and other languages a native SQL path without weakening the correctness guarantees that justify this project in the first place.

---

## 26. Recommended next steps

The design work should proceed in this order:

1. **Schema first** — done in this document
2. **API skeleton aligned with PeeGeeQ conventions**
    - `TtlResult`, `CounterTtlMode`, `LockState`, `PubSubMessage`
    - managed bootstrap via `PeeGeeCacheManager`
    - explicit runtime ownership of listener and sweeper services
3. **Repository layer and SQL statement catalogue**
   - `PgCacheRepository`
   - `PgCounterRepository`
   - `PgLockRepository`
   - SQL statement catalogue
4. **Actual Java module skeleton**
5. **Runtime bootstrap, dedicated pub/sub listener, and expiry sweeper**
6. **Integration tests with Testcontainers**
7. **Benchmark and profiling work**

### Immediate next move

Define the API module first, then the repository layer and SQL statement catalogue for `peegee-cache-pg`, so the database semantics and runtime lifecycle are reflected directly in the Vert.x code boundaries.

---

## 27. Documentation strategy going forward

Do **not** rely on chat history as the system of record.

The right process is:
- create the markdown document early,
- treat it as a living design document,
- update it after each major design checkpoint,
- preserve rationale in appendices or decision sections rather than flattening everything into vague summaries.

### Practical rule

Once a section becomes stable enough that reconstructing it from chat would be annoying, it belongs in the markdown document.

That threshold was already reached.

---

## 28. Final summary

`peegee-cache` should be built as a **library-first, PostgreSQL-backed, Vert.x-native cache and coordination library**.

### Phase 1 includes
- key/value cache
- TTL / expiry
- conditional writes
- counters
- namespaces
- scanning
- distributed locks
- light pub/sub
- metrics hooks

### Phase 1 excludes
- full Redis compatibility
- cluster semantics
- streams
- scripting
- broad list/set emulation
- queue subsystem as Redis-list emulation

### Architectural stance

This is not “Redis on PostgreSQL.”

It is a **transactional cache + lock + counter + lightweight coordination service backed by PostgreSQL**, designed for systems where PostgreSQL is already strategic and operational simplicity matters more than pretending to be Redis.
