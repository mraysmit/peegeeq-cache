# peegee-cache logging standard

Logging is part of the peegee-cache production observability contract. Metrics and traces describe aggregate behavior; logs describe lifecycle transitions, degraded states, recovery, and diagnostic detail. The rules below apply to library code, runnable examples, benchmarks, and future modules.

## SLF4J ownership

Published peegee-cache libraries depend on `slf4j-api` 2.0.18 and do not select a logging provider. The consuming application must supply exactly one SLF4J 2.x provider, normally its existing Logback or Log4j 2 integration. `slf4j-simple` is limited to tests and the standalone example and benchmark processes.

For example, an application using Logback may add:

```xml
<dependency>
    <groupId>ch.qos.logback</groupId>
    <artifactId>logback-classic</artifactId>
    <version>${logback.version}</version>
    <scope>runtime</scope>
</dependency>
```

Do not add a provider to any published peegee-cache library module. `LoggingDependencyContractTest` enforces this boundary and the selected SLF4J API baseline.

## Vert.x routing

Runnable processes must route Vert.x internal logs through SLF4J so application and framework events share one format, level policy, and sink:

```text
-Dvertx.logger-delegate-factory-class-name=io.vertx.core.logging.SLF4JLogDelegateFactory
```

The example and benchmark Maven launchers set this property explicitly. Deploying applications should set it in their JVM options before Vert.x initializes.

## Level policy

| Level | Use |
|---|---|
| `ERROR` | A requested startup or operation cannot complete, or retries are exhausted. Include the exception once at the point that owns the failure. |
| `WARN` | The system enters a degraded state, loses a connection, or encounters a recoverable cleanup failure. Recurring failures are logged once per failure episode and summarized on recovery. |
| `INFO` | Low-frequency lifecycle transitions, effective operating mode, schema migration checks, and recovery. Do not log a banner or one event per cache operation. |
| `DEBUG` | State transitions, retry scheduling, disabled optional facilities, and aggregate maintenance results useful during diagnosis. |
| `TRACE` | Sanitized request/result details for individual cache operations. TRACE is intentionally high volume and must be enabled only for bounded diagnostic windows. |

Production defaults should keep `dev.mars.peegeeq.cache` at `INFO`. Temporarily set `dev.mars.peegeeq.cache.pg` to `TRACE` only when per-operation correlation is necessary.

## Event and field conventions

- Use stable lowercase event names in `<component>.<subject>.<action-or-state>` form, for example `cache.manager.started` or `pubsub.listener.reconnect_scheduled`.
- Use the SLF4J 2 fluent API and key/value fields. Field names are stable API: prefer `duration.ms`, `ttl.ms`, `attempt`, `outcome`, `schema`, and `suppressed.failures`.
- Put the exception on the owning `ERROR` or `WARN` event with `setCause`. Do not emit a message-only error followed by a duplicate DEBUG stack trace.
- Log a failure transition once, suppress identical recurring noise, and emit one recovery event with the suppressed-failure count.
- Avoid expensive field construction unless the relevant level is enabled.

These key/value pairs remain usable with text providers. Production providers should encode them as structured JSON fields where supported.

## Safe-data policy

Never log credentials, passwords, access tokens, lock owner tokens, cache values, notification payloads, raw SQL parameters, or connection strings. Secrets are omitted or rendered as `[REDACTED]`; they are never hashed because low-entropy secrets can be recovered by guessing.

Cache keys, lock keys, counter keys, namespaces, channels, prefixes, and cursors are user-controlled identifiers. When correlation is necessary, log only a bounded SHA-256 fingerprint produced by `SafeLogValue.identifier`. Payloads may be represented by byte length and cache values by bounded type metadata. Fencing tokens, versions, counts, durations, bounded outcomes, schema configuration, and PostgreSQL image versions are acceptable fields.

Fingerprints support correlation inside logs; they are not authentication or cryptographic proof and must never be used as application identifiers.

## Correlation and traces

Prefer OpenTelemetry trace/span correlation from `peegee-cache-observability`. An application may add trace and request identifiers through its logging provider or MDC, but must propagate context across Vert.x asynchronous boundaries deliberately. Library code does not create, clear, or assume ownership of application MDC values.

## Operational verification

Before release:

1. Run `mvn verify` and confirm the logging dependency contract passes.
2. Inspect the resolved dependency tree for published modules; it must contain `slf4j-api` and no non-test provider.
3. Run a short benchmark capture and confirm Vert.x and peegee-cache events use the same SLF4J format.
4. Confirm normal startup emits one manager lifecycle sequence and no automatic ASCII banner.
5. Exercise a recurring sweeper or listener failure and verify one failure transition plus one recovery summary, rather than one warning per interval.

The provider principles follow the [SLF4J manual](https://www.slf4j.org/manual.html); Vert.x backend selection is documented in the [Vert.x logging guide](https://vertx.io/docs/vertx-core/java/#_logging).
