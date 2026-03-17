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

PostgreSQL's UNLOGGED tables, shared buffer caching, prepared statement plan reuse, HOT updates, and native `LISTEN/NOTIFY` make it a capable cache backend for moderate-throughput workloads where correctness and simplicity outweigh extreme sub-millisecond latency.

**Use peegee-cache** when PostgreSQL is already in your stack and you need cache, locks, and counters close to your data.
**Consider a dedicated cache** when you need sub-ms p99, extreme throughput, or specialized in-memory data structures.

## Current Features

| Feature | Description |
|---|---|
| Key/value cache | GET, SET, DELETE, EXISTS with `BYTEA` values |
| TTL / expiry | Per-key TTL, touch, persist, background sweeper |
| Conditional writes | IF_ABSENT, IF_EXISTS, version-guarded CAS |
| Atomic counters | INCREMENT, DECREMENT, GET, RESET with namespace isolation |
| Distributed locks | Lease-based with fencing tokens, owner tracking, renewal |
| Namespaces | Logical isolation for all cache, counter, and lock operations |
| Bulk operations | Multi-get, multi-set, multi-delete |
| Scan / iteration | Cursor-based listing by namespace and key prefix |
| Lightweight pub/sub | PostgreSQL `LISTEN/NOTIFY` for cache invalidation |
| Metrics hooks | Observability integration points |


## Requirements

- **Java** 21+
- **Vert.x** 5.0.x
- **PostgreSQL** 15+ (developed against 18.x)

## Quick start

`PeeGeeCaches.create()` bootstraps the schema and wires all services. Every operation returns a `Future<T>`.

```java
PeeGeeCaches.create(vertx, pool).onSuccess(manager -> {
    PeeGeeCache cache = manager.cache();

    // Store a value with a 30-minute TTL — expired entries are swept automatically
    CacheKey key = new CacheKey("session", "user:42");
    cache.cache().set(new CacheSetRequest(key, CacheValue.ofString("data"), Duration.ofMinutes(30)));

    // Read it back — returns Optional.empty() if missing or expired
    cache.cache().get(key).onSuccess(entry ->
        entry.ifPresent(e -> System.out.println(e.value().asString())));

    // Atomic counter — safe under concurrent access, useful for rate limiting or sequencing
    cache.counters().incrementBy(new CacheKey("rate-limit", "api:tenant-7"), 1);

    // Lease-based lock — only one owner holds it at a time, auto-expires after 30s.
    // The fencing token is a monotonically increasing number that downstream services
    // can use to reject stale operations from a previous lock holder.
    cache.locks().acquire(new LockAcquireRequest(
        new LockKey("jobs", "import-batch"), "owner-1", Duration.ofSeconds(30), false, true));
});
```

All public APIs return `io.vertx.core.Future<T>` — compose with `.compose()`, `.map()`, `.onSuccess()`, `.onFailure()`.

## License

Copyright © 2026 Mark A Ray-Smith, Cityline Ltd.
