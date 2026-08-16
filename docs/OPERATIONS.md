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

Construct `PgCacheHealthIndicator` with the caller-owned pool, configured schema, and `manager::isStarted`. `UP` requires the runtime to be started, a successful database query, and the configured `cache_entries` table to exist. `STOPPED` is distinct from database failure.

Suggested initial alerts, to be tuned from benchmark and production baselines:

- operation failure rate above 1% for five minutes;
- operation p99 above the application SLO;
- active operations persistently near the host pool's configured maximum;
- expiry lag above twice the configured sweep interval for three sweeps;
- repeated pub/sub reconnect failures or no successful reconnect before the fifth attempt;
- readiness status other than `UP`.

## Schema ownership

`SchemaBootstrapMode.EXTERNAL` is the default and recommended production policy. Deployment tooling applies `BootstrapSqlRenderer.loadForSchema(schema)` before application startup and owns forward/rollback coordination.

`SchemaBootstrapMode.APPLY` is an opt-in embedded policy. Runtime startup applies the bundled idempotent bootstrap before starting the sweeper or pub/sub listener. It requires DDL permission and is unsuitable where schema changes require a separate approval window.

The current V001 bootstrap is additive/idempotent. Rollback is operationally manual: stop writers, preserve or export required data, and drop only explicitly selected peegee-cache objects. The library never automatically drops schema objects.

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
| PostgreSQL | Intended for 15+ SQL semantics; automated compatibility currently runs on 18.3 |
| Micrometer | 1.17.0 |
| OpenTelemetry Java API | 1.64.0 BOM |

PostgreSQL 15–17 should be added to a CI matrix before claiming those versions as release-validated rather than compatible-by-design.
