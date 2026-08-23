package dev.mars.peegeeq.cache.rest.server;

import dev.mars.peegeeq.cache.api.management.ManagementCursorCodec;
import dev.mars.peegeeq.cache.api.management.ManagementAuditFingerprinter;
import dev.mars.peegeeq.cache.api.management.ManagementAuditSink;
import dev.mars.peegeeq.cache.api.management.ManagementService;
import dev.mars.peegeeq.cache.api.pubsub.PubSubService;
import dev.mars.peegeeq.cache.pg.management.PgManagementMutationRepository;
import dev.mars.peegeeq.cache.pg.management.PgManagementReadRepository;
import dev.mars.peegeeq.cache.pg.management.PgManagementService;
import dev.mars.peegeeq.cache.runtime.PeeGeeCacheManager;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.sqlclient.Pool;

import java.time.Instant;
import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import java.util.function.Supplier;

/** Owns a manager, its pool, and the isolated resolver runtime in shutdown order. */
final class PostgresManagedSetupRuntime implements ManagedSetupRuntime {

    private final PeeGeeCacheManager manager;
    private final Pool pool;
    private final Vertx vertx;
    private final String schema;
    private final ManagementService management;
    private Future<Void> readiness;
    private Future<Void> closing;

    PostgresManagedSetupRuntime(
            PeeGeeCacheManager manager,
            Pool pool,
            Vertx vertx,
            String schema,
            String setupId,
            byte[] cursorKey) {
        this.manager = Objects.requireNonNull(manager, "manager");
        this.pool = Objects.requireNonNull(pool, "pool");
        this.vertx = Objects.requireNonNull(vertx, "vertx");
        this.schema = Objects.requireNonNull(schema, "schema");
        this.management = new PgManagementService(
                new PgManagementReadRepository(pool, schema, "peegeeq-management-" + setupId),
                Objects.requireNonNull(setupId, "setupId"),
                new ManagementCursorCodec(
                        Objects.requireNonNull(cursorKey, "cursorKey"),
                        Clock.systemUTC(),
                        Duration.ofMinutes(15)));
    }

    PostgresManagedSetupRuntime(
            PeeGeeCacheManager manager,
            Pool pool,
            Vertx vertx,
            String schema,
            String setupId,
            byte[] cursorKey,
            ManagementAuditSink auditSink,
            ManagementAuditFingerprinter auditFingerprinter,
            Clock auditClock,
            Supplier<String> auditEventIdSupplier) {
        this.manager = Objects.requireNonNull(manager, "manager");
        this.pool = Objects.requireNonNull(pool, "pool");
        this.vertx = Objects.requireNonNull(vertx, "vertx");
        this.schema = Objects.requireNonNull(schema, "schema");
        this.management = new PgManagementService(
                new PgManagementReadRepository(pool, schema, "peegeeq-management-" + setupId),
                new PgManagementMutationRepository(pool, schema),
                Objects.requireNonNull(setupId, "setupId"),
                new ManagementCursorCodec(
                        Objects.requireNonNull(cursorKey, "cursorKey"),
                        Objects.requireNonNull(auditClock, "auditClock"),
                        Duration.ofMinutes(15)),
                Objects.requireNonNull(auditSink, "auditSink"),
                Objects.requireNonNull(auditFingerprinter, "auditFingerprinter"),
                auditClock,
                Objects.requireNonNull(auditEventIdSupplier, "auditEventIdSupplier"),
                null);
    }

    @Override
    public synchronized Future<Void> verifyReady() {
        if (closing != null) {
            return Future.failedFuture("Setup runtime is closing");
        }
        if (readiness == null) {
            readiness = manager.startReactive()
                    .compose(ignored -> pool.query("SELECT version FROM " + schema
                                    + ".schema_migrations ORDER BY version DESC LIMIT 1")
                            .execute())
                    .compose(rows -> {
                        if (!rows.iterator().hasNext()
                                || !Integer.valueOf(1).equals(rows.iterator().next().getInteger("version"))) {
                            return Future.failedFuture("PeeGeeQ schema migration version 1 is not ready");
                        }
                        return Future.succeededFuture();
                    });
        }
        return readiness;
    }

    @Override
    public Future<SetupHealth> health() {
        long started = System.nanoTime();
        return pool.query("SELECT version FROM " + schema
                        + ".schema_migrations ORDER BY version DESC LIMIT 1")
                .execute()
                .map(rows -> {
                    boolean ready = rows.iterator().hasNext()
                            && Integer.valueOf(1).equals(rows.iterator().next().getInteger("version"));
                    return new SetupHealth(
                            ready ? SetupHealthSummary.Status.UP : SetupHealthSummary.Status.DEGRADED,
                            ready,
                            elapsedMillis(started),
                            Instant.now(),
                            ready
                                    ? "Database reachable and schema ready"
                                    : "Database reachable but schema is not ready");
                })
                .recover(ignored -> Future.succeededFuture(new SetupHealth(
                        SetupHealthSummary.Status.DOWN,
                        false,
                        elapsedMillis(started),
                        Instant.now(),
                        "Database or schema unavailable")));
    }

    @Override
    public ManagementService management() {
        return management;
    }

    @Override
    public PubSubService pubSub() {
        return manager.cache().pubSub();
    }

    @Override
    public synchronized Future<Void> closeAsync() {
        if (closing != null) {
            return closing;
        }
        Future<Void> afterReadiness = readiness == null
                ? Future.succeededFuture()
                : readiness.recover(ignored -> Future.succeededFuture());
        closing = afterReadiness
                .compose(ignored -> manager.isStarted()
                        ? manager.stopReactive().recover(stopFailure -> Future.succeededFuture())
                        : Future.succeededFuture())
                .compose(ignored -> pool.close().recover(poolFailure -> Future.succeededFuture()))
                .compose(ignored -> vertx.close());
        return closing;
    }

    private static long elapsedMillis(long started) {
        return Math.max(0, (System.nanoTime() - started) / 1_000_000);
    }
}
