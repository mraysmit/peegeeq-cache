# peegee-cache operations and observability

Observability is a required production component of peegee-cache. The managed runtime accepts the vendor-neutral `CacheTelemetry` contract; `peegee-cache-observability` supplies Micrometer and OpenTelemetry implementations and a PostgreSQL readiness indicator.

## Required signals

| Signal | Meaning |
|---|---|
| `peegeeq.cache.operation` / OTel operation count and duration | Latency, rate, and outcome for the bounded `CacheOperation` set |
| `peegeeq.cache.operations.active` | In-flight cache demand; compare with the host's Vert.x pool-capacity metrics to detect saturation |
| `peegeeq.cache.lock.contention` | Lock acquisitions that completed without a grant |
| `peegeeq.cache.expiry.sweep` | Sweeper latency and outcome |
| `peegeeq.cache.expiry.rows` | Physical deletions per successful sweep |
| `peegeeq.cache.expiry.lag` | Age in seconds of the oldest expired row removed by a sweep |
| `peegeeq.cache.pubsub.reconnect` | Reconnect attempt latency and outcome |
| `peegeeq.cache.pubsub.subscriptions` | Active local handlers |
| `peegeeq.cache.pubsub.notification.dispatch` | Local notification-to-handler dispatch time |
| `peegeeq.cache.runtime.started` | Managed lifecycle state |

Metric attributes never include user-controlled keys, namespaces, channel names, payloads, SQL, or exception messages. End-to-end publish-to-receive latency is not available from bare PostgreSQL `NOTIFY`, because it carries no server timestamp; the exported notification measurement is local dispatch latency. Applications needing end-to-end notification latency should include an application timestamp in a versioned payload envelope.

## Health and alerting

Construct `PgCacheHealthIndicator` with the caller-owned pool, configured schema, and `manager::isStarted`. `UP` requires the runtime to be started, a successful database query, and all required tables, indexes, sequence, function signatures, migration ledger, and stable read views to exist. `STOPPED` is distinct from database failure, and `DOWN` names missing schema objects.

Suggested initial alerts, to be tuned from benchmark and production baselines:

- operation failure rate above 1% for five minutes;
- operation p99 above the application SLO;
- active operations persistently near the host pool's configured maximum;
- expiry lag above twice the configured sweep interval for three sweeps;
- repeated pub/sub reconnect failures or no successful reconnect before the fifth attempt;
- readiness status other than `UP`.

## Schema ownership

`SchemaBootstrapMode.EXTERNAL` is the default and recommended production policy. Deployment tooling applies `BootstrapSqlRenderer.loadForSchema(schema)` before application startup and owns forward/rollback coordination.

`SchemaBootstrapMode.APPLY` is an opt-in embedded policy. Runtime startup takes a schema-scoped advisory lock and applies only missing migrations, in order and in individual transactions, before starting the sweeper or pub/sub listener. It requires DDL permission and is unsuitable where schema changes require a separate approval window.

Applied versions are recorded in `<schema>.schema_migrations`. Rollback is operationally manual: stop writers, preserve or export required data, and use the release-specific recovery procedure. The library never automatically drops schema objects. The supported SQL surface and compatibility rules are documented in [PEEGEEQ_CACHE_NATIVE_SQL_API.md](PEEGEEQ_CACHE_NATIVE_SQL_API.md).

## PostgreSQL operations

- All current tables are logged. Backup and recovery follow the host PostgreSQL policy.
- Logical expiry is authoritative; physical cleanup lag affects storage, not read correctness.
- Monitor autovacuum, dead tuples, WAL volume, pool wait time, database CPU/IO, and connection count alongside library telemetry.
- `LISTEN/NOTIFY` is a non-durable signal. Reconciliation of missed notifications belongs to the application protocol.
- After five consecutive listener reconnect failures, restart the managed runtime after database connectivity is restored.

## Compatibility

| Component | Supported/validated posture |
|---|---|
| Java | 21 through 25; enforced by Maven Enforcer |
| Maven | 3.9.x; enforced by Maven Enforcer |
| Vert.x | 5.0.8 dependency baseline |
| PostgreSQL | 15+; full-reactor validation completed on 15.17, 16.13, 17.11, and 18.3 |
| Micrometer | 1.17.0 |
| OpenTelemetry Java API | 1.64.0 BOM |

The Testcontainers image is selected with `peegeeq.test.postgres.image`. The 2026-08-16 manual release matrix ran the complete 269-test reactor successfully on every supported major version:

```shell
mvn verify '-Dpeegeeq.test.postgres.image=postgres:15-alpine'
mvn verify '-Dpeegeeq.test.postgres.image=postgres:16-alpine'
mvn verify '-Dpeegeeq.test.postgres.image=postgres:17-alpine'
mvn verify '-Dpeegeeq.test.postgres.image=postgres:18.3-alpine'
```

The resolved server patch releases were 15.17, 16.13, 17.11, and 18.3. Each full-reactor matrix run completed with 269 tests, zero failures, zero errors, and zero skips. The subsequently added real-PostgreSQL pool-headroom/sweeper regression passed separately on all four majors; the current PostgreSQL 18.3 reactor contains 275 tests. This matrix is repeatable locally but should still be encoded in CI so future commits cannot bypass it.
