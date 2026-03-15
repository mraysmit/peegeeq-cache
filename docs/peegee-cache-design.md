# peegee-cache Design Document

## 1. Overview

**peegee-cache** is a PostgreSQL-backed, Redis-inspired cache and coordination library built for **Java 25** and **Vert.x 5.0.8**.

It is **library-first**, not daemon-first. The Java API is the primary product. PostgreSQL is the first storage implementation. Vert.x provides the asynchronous programming model and runtime integration.

This is **not** intended to be a full Redis clone in Phase 1. It is intended to provide the high-value parts of Redis that map well onto PostgreSQL and are actually useful in enterprise systems.

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

- Rate limiting primitives
  - fixed window
  - sliding window
  - token bucket / leaky bucket style support later

- Pub/sub
  - lightweight channel publish/subscribe
  - cache invalidation fan-out
  - internal notifications

### Tier 2 — High-value, but later

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
  - **V1** = build early
  - **V2** = build after core is stable
  - **Later** = only if clearly needed
  - **No** = do not build unless forced

| Feature | Redis capability | Business value | Complexity | PostgreSQL fit | Recommendation | Why |
|---|---|---:|---:|---:|---|---|
| Basic key/value | GET, SET, DEL, EXISTS | High | Low | Excellent | V1 | Core cache capability |
| Bulk key/value | MGET, MSET, multi-delete | High | Low | Excellent | V1 | Needed for efficiency |
| TTL / expiry | EXPIRE, TTL, PTTL, PERSIST | High | Medium | Good | V1 | Essential cache semantics |
| Conditional writes | SET NX, XX, GETSET-like flows | High | Medium | Excellent | V1 | Required for correctness and locking |
| Atomic counters | INCR, DECR, INCRBY | High | Low | Excellent | V1 | Very common real use case |
| Namespaces | Prefix/grouping | High | Low | Excellent | V1 | Operational sanity |
| Scan / iteration | SCAN-like listing | Medium | Medium | Good | V1 | Needed for admin/debug/migration |
| Locking | Lease lock, owner token, renew, release | High | Medium | Excellent | V1 | Strong PostgreSQL fit |
| Key metadata | Created time, updated time, hit count, expiry, version | High | Medium | Excellent | V1 | Needed for observability and CAS |
| Compare-and-set | Versioned optimistic updates | High | Medium | Excellent | V1.5 / V2 | Very useful in real systems |
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

### V1 shortlist

- key/value get/set/delete
- bulk get/set/delete
- TTL and expiry
- conditional write support
- atomic counters
- namespaces
- scan/list by namespace and prefix
- lock service
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
- Java 25 configuration
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

    Future<Long> ttl(CacheKey key);

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
import io.vertx.core.Future;

public interface CounterService {

    Future<Long> increment(CacheKey key);

    Future<Long> incrementBy(CacheKey key, long delta);

    Future<Long> decrement(CacheKey key);

    Future<Long> decrementBy(CacheKey key, long delta);

    Future<Long> getValue(CacheKey key);

    Future<Long> setValue(CacheKey key, long value, CounterOptions options);

    Future<Boolean> delete(CacheKey key);
}
```

### 8.3 `LockService`

```java
package dev.mars.peegeeq.cache.api.lock;

import dev.mars.peegeeq.cache.api.model.LockAcquireRequest;
import dev.mars.peegeeq.cache.api.model.LockAcquireResult;
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

    Future<Optional<LockAcquireResult>> currentLock(LockKey key);
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

### 8.5 `PubSubService`

```java
package dev.mars.peegeeq.cache.api.pubsub;

import dev.mars.peegeeq.cache.api.model.PublishRequest;
import dev.mars.peegeeq.cache.api.model.Subscription;
import io.vertx.core.Future;

import java.util.function.Consumer;

public interface PubSubService {

    Future<Integer> publish(PublishRequest request);

    Future<Subscription> subscribe(String channel, Consumer<byte[]> handler);

    Future<Subscription> subscribePattern(String pattern, Consumer<PubSubMessage> handler);
}
```

```java
package dev.mars.peegeeq.cache.api.pubsub;

public record PubSubMessage(
        String channel,
        byte[] payload,
        long publishedAtEpochMillis
) {}
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

public record CacheValue(ValueType type, Buffer data) {

    public CacheValue {
        Objects.requireNonNull(type, "type must not be null");
        Objects.requireNonNull(data, "data must not be null");
    }

    public static CacheValue ofBytes(byte[] bytes) {
        return new CacheValue(ValueType.BYTES, Buffer.buffer(bytes));
    }

    public static CacheValue ofString(String value) {
        return new CacheValue(ValueType.STRING, Buffer.buffer(value, StandardCharsets.UTF_8));
    }

    public String asString() {
        return data.toString(StandardCharsets.UTF_8);
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
        ValueType valueType,
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

import java.util.Optional;

public record CacheSetResult(
        boolean applied,
        long newVersion,
        Optional<CacheEntry> previousEntry
) {}
```

### 9.8 `TouchResult`

```java
package dev.mars.peegeeq.cache.api.model;

public record TouchResult(
        boolean updated,
        long ttlMillis
) {}
```

### 9.9 `CounterOptions`

```java
package dev.mars.peegeeq.cache.api.model;

import java.time.Duration;

public record CounterOptions(
        Duration ttl,
        boolean createIfMissing,
        boolean preserveExistingTtl
) {
    public static CounterOptions defaults() {
        return new CounterOptions(null, true, true);
    }
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

import io.vertx.core.buffer.Buffer;

public record PublishRequest(
        String channel,
        Buffer payload
) {}
```

```java
package dev.mars.peegeeq.cache.api.model;

import io.vertx.core.Future;

public interface Subscription {
    String channel();
    Future<Void> unsubscribe();
}
```

---

## 10. Bootstrap and configuration

### Minimal public bootstrap shape

```java
package dev.mars.peegeeq.cache.runtime.bootstrap;

import dev.mars.peegeeq.cache.api.PeeGeeCache;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.sqlclient.Pool;

public interface PeeGeeCacheFactory {

    Future<PeeGeeCache> create(Vertx vertx, Pool pool);
}
```

Alternative static bootstrap helper:

```java
package dev.mars.peegeeq.cache.runtime.bootstrap;

import dev.mars.peegeeq.cache.api.PeeGeeCache;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.sqlclient.Pool;

public final class PeeGeeCaches {

    private PeeGeeCaches() {
    }

    public static Future<PeeGeeCache> create(Vertx vertx, Pool pool, PeeGeeCacheBootstrapOptions options) {
        return Future.failedFuture("Not yet implemented");
    }
}
```

### Runtime config

```java
package dev.mars.peegeeq.cache.runtime.config;

import java.time.Duration;

public record PeeGeeCacheConfig(
        Duration defaultTtl,
        Duration expirySweepInterval,
        int maxScanLimit,
        boolean enablePubSub,
        boolean enableExpirySweeper
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
        boolean useUnloggedCacheTable
) {}
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
            (value_type = 'LONG' AND numeric_value IS NOT NULL)
            OR
            (value_type IN ('BYTES', 'STRING', 'JSON') AND value_bytes IS NOT NULL)
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
    PRIMARY KEY (namespace, lock_key)
);
```

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

API semantics should decide how missing-vs-no-expiry is represented. It does **not** need to copy Redis return codes exactly.

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

### 16.11 SCAN by namespace/prefix

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

TTL behavior here is a policy choice and must be defined in the API.

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

Step 1: delete stale lease

```sql
DELETE FROM peegee_cache.cache_locks
WHERE namespace = $1
  AND lock_key = $2
  AND lease_expires_at <= NOW();
```

Step 2: insert new lease

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
    $1, $2, $3, $4, 1, NOW(), NOW(), $5
)
ON CONFLICT (namespace, lock_key) DO NOTHING
RETURNING namespace, lock_key, owner_token, fencing_token, lease_expires_at;
```

### 18.2 Reentrant acquire by same owner (optional)

```sql
UPDATE peegee_cache.cache_locks
SET
    lease_expires_at = $4,
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
    lease_expires_at = $4,
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

Important limitations:
- `NOTIFY` payload is text and size-limited
- for binary payloads, you need base64 or another encoding
- for Phase 1, it is saner to keep pub/sub payloads to UTF-8 text / JSON

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

### Read-path write amplification
Updating `hit_count` and `last_accessed_at` on every read may be operationally stupid for hot keys.

Better options:
- sampled updates
- optional update mode via config
- async/statistical tracking later

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

### Do not try to emulate Redis transactions exactly
Use SQL transactions instead.

### Do not treat lists as a priority
If queue semantics are needed, build a proper queue module later.

### Do not try to build protocol compatibility first
Build the Java library first. Network/server adapters can come later.

---

## 24. Example usage

```java
PeeGeeCache cache = await(PeeGeeCaches.create(vertx, pool, options));

CacheKey sessionKey = new CacheKey("session", "abc123");

CacheSetRequest request = new CacheSetRequest(
        sessionKey,
        CacheValue.ofString("{\"userId\":\"42\"}"),
        Duration.ofMinutes(30),
        SetMode.UPSERT,
        null,
        false
);

CacheSetResult result = await(cache.cache().set(request));

Optional<CacheEntry> loaded = await(cache.cache().get(sessionKey));

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
```

---

## 25. Recommended next steps

The design work should proceed in this order:

1. **Schema first** — done in this document
2. **Repository/API skeleton**
   - `PgCacheRepository`
   - `PgCounterRepository`
   - `PgLockRepository`
   - SQL statement catalogue
3. **Actual Java module skeleton**
4. **Runtime bootstrap and expiry sweeper**
5. **Integration tests with Testcontainers**
6. **Benchmark and profiling work**

### Immediate next move

Define the repository layer and SQL statement catalogue for `peegee-cache-pg` so the database semantics are reflected directly in Vert.x code boundaries.

---

## 26. Documentation strategy going forward

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

## 27. Final summary

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
