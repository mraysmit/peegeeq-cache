```
    ____            ______          ______
   / __ \___  ___  / ____/__  ___  / ____/
  / /_/ / _ \/ _ \/ / __/ _ \/ _ \/ /
 / ____/  __/  __/ /_/ /  __/  __/ /___
/_/    \___/\___/\____/\___/\___/\____/
```


**PostgreSQL-backed caching and coordination for Java/Vert.x systems.**

peegee-cache is a library-first cache and coordination library that runs on PostgreSQL. It provides key/value storage, TTL expiry, atomic counters, distributed locks, conditional writes, namespaces, scanning, and lightweight pub/sub inside the same transactional envelope as your business data.

## Why PostgreSQL instead of a dedicated cache?

The main advantage is **transactional locality**:

- Write business data, update cache state, and emit notifications in one database transaction
- No cross-system cache invalidation failure modes
- Single backup, monitoring, failover, and operational surface
- No extra network hop for coordination workloads

PostgreSQL's shared buffer caching, prepared statement plan reuse, HOT updates, and native `LISTEN/NOTIFY` make it a capable cache backend for moderate-throughput workloads where correctness and simplicity outweigh extreme sub-millisecond latency.

**Use peegee-cache** when PostgreSQL is already in your stack and you need cache, locks, and counters close to your data.
**Consider a dedicated cache** when you need sub-ms p99, extreme throughput, or specialized in-memory data structures.

## Current Features

| Feature | Description |
|---|---|
| Key/value cache | GET, SET, DELETE, EXISTS with `BYTEA` values |
| TTL / expiry | Per-key/default TTL, touch, persist, optional background sweeper |
| Conditional writes | IF_ABSENT, IF_EXISTS, version-guarded CAS |
| Atomic counters | INCREMENT, DECREMENT, GET, RESET with namespace isolation |
| Distributed locks | Lease-based with fencing tokens, owner tracking, renewal |
| Namespaces | Logical isolation for all cache, counter, and lock operations |
| Bulk operations | Multi-get, multi-set, multi-delete |
| Scan / iteration | Cursor-based listing by namespace and key prefix |
| Lightweight pub/sub | PostgreSQL `LISTEN/NOTIFY` for cache invalidation when listener connection options are supplied |
| Production observability | Micrometer metrics, OpenTelemetry metrics/traces, readiness health, bounded operation tags, expiry lag, contention, reconnect and lifecycle signals |


## Requirements

- **Java** 21–25 (enforced by the build)
- **Vert.x** 5.0.8
- **PostgreSQL** 15+; the full reactor was manually validated against PostgreSQL 15.17, 16.13, 17.11, and 18.3

## Quick start

Apply the bundled bootstrap SQL once for the selected schema, then create and explicitly start the manager. The manager borrows the caller's `Vertx` and `Pool`; callers remain responsible for closing both after stopping the manager. Every operation returns a `Future<T>`.

```java
PgConnectOptions connectOptions = new PgConnectOptions()
    .setHost("localhost")
    .setPort(5432)
    .setDatabase("app")
    .setUser("app")
    .setPassword("secret");

PeeGeeCacheBootstrapOptions options = new PeeGeeCacheBootstrapOptions(
    PeeGeeCacheConfig.defaults(),
    PgCacheStoreConfig.defaults(),
    connectOptions); // Enables the dedicated LISTEN/NOTIFY connection

pool.query(BootstrapSqlRenderer.loadBootstrapSql()).execute()
    .compose(ignored -> PeeGeeCaches.create(vertx, pool, options))
    .compose(manager -> manager.startReactive().map(manager))
    .onSuccess(manager -> {
        PeeGeeCache cache = manager.cache();

        CacheKey key = new CacheKey("session", "user:42");
        cache.cache().set(new CacheSetRequest(
            key,
            CacheValue.ofString("data"),
            Duration.ofMinutes(30),
            SetMode.UPSERT,
            null,
            false));

        // Returns Optional.empty() if missing or logically expired
        cache.cache().get(key).onSuccess(entry ->
            entry.ifPresent(e -> System.out.println(e.value().asString())));

        cache.counters().incrementBy(new CacheKey("rate-limit", "api:tenant-7"), 1);

        cache.locks().acquire(new LockAcquireRequest(
            new LockKey("jobs", "import-batch"),
            "owner-1",
            Duration.ofSeconds(30),
            false,
            true));
    });
```

Enable physical expiry cleanup with a `PeeGeeCacheConfig` whose `enableExpirySweeper` value is `true`. Logical expiry remains authoritative even when the sweeper is disabled. Pub/sub publishing and subscribing require `PgConnectOptions`; the default two-argument `PeeGeeCaches.create(vertx, pool)` path intentionally leaves pub/sub unavailable.

Schema provisioning is external by default. Embedded deployments may opt into applying bundled, transactionally versioned migrations during `startReactive()` by setting `SchemaBootstrapMode.APPLY` in `PeeGeeCacheBootstrapOptions`. See the [native PostgreSQL API and upgrade contract](docs/PEEGEEQ_CACHE_NATIVE_SQL_API.md).

## Observability

Observability is part of the production contract. Supply one or both adapters through bootstrap options:

```java
CacheTelemetry telemetry = new CompositeCacheTelemetry(
    new MicrometerCacheTelemetry(meterRegistry),
    new OpenTelemetryCacheTelemetry(openTelemetry));

PeeGeeCacheBootstrapOptions options = new PeeGeeCacheBootstrapOptions(
    runtimeConfig,
    storeConfig,
    connectOptions,
    telemetry);
```

Instrumentation covers every service operation and failure, active operations, lock contention, expiry sweep latency/rows/oldest-row lag, pub/sub reconnect outcomes, notification dispatch latency, active subscriptions, schema bootstrap, and runtime lifecycle. Tags use bounded enums and never contain cache keys, namespaces, channels, or payloads.

`PgCacheHealthIndicator` verifies managed-runtime state, PostgreSQL connectivity, and the complete required schema-object set. See [operations guidance](docs/PEEGEEQ_CACHE_OPERATIONS.md), [benchmarking](docs/PEEGEEQ_CACHE_BENCHMARKS.md), and [release packaging](docs/PEEGEEQ_CACHE_RELEASE_PACKAGING.md).

All public APIs return `io.vertx.core.Future<T>` — compose with `.compose()`, `.map()`, `.onSuccess()`, `.onFailure()`.

## Benchmarks

Run three captured 30-second repetitions and produce one self-contained HTML report containing results, hardware, Docker, JVM, Git, and raw-log evidence:

```shell
mvn -pl peegee-cache-benchmarks -am integration-test -Pbenchmark-capture -DskipTests
```

See the [benchmark guide](docs/PEEGEEQ_CACHE_BENCHMARKS.md) for report contents, release-evidence options, and comparison discipline.

## License

Copyright © 2026 Mark A Ray-Smith, Cityline Ltd.

Licensed under the [Apache License, Version 2.0](LICENSE).
