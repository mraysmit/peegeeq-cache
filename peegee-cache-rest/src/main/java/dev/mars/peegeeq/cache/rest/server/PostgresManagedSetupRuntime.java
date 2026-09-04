package dev.mars.peegeeq.cache.rest.server;

import dev.mars.peegeeq.cache.api.management.ManagementCursorCodec;
import dev.mars.peegeeq.cache.api.PeeGeeCache;
import dev.mars.peegeeq.cache.api.management.ManagementAuditFingerprinter;
import dev.mars.peegeeq.cache.api.management.ManagementAuditSink;
import dev.mars.peegeeq.cache.api.management.ManagementService;
import dev.mars.peegeeq.cache.api.pubsub.PubSubService;
import dev.mars.peegeeq.cache.pg.management.PgManagementMutationRepository;
import dev.mars.peegeeq.cache.pg.management.PgManagementReadRepository;
import dev.mars.peegeeq.cache.pg.management.PgManagementService;
import dev.mars.peegeeq.cache.runtime.PeeGeeCacheManager;
import dev.mars.peegeeq.cache.runtime.bootstrap.SchemaBootstrapMode;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.sqlclient.Pool;

import java.time.Instant;
import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

/** Owns a manager, its pool, and the isolated resolver runtime in shutdown order. */
final class PostgresManagedSetupRuntime implements ManagedSetupRuntime {

    private static final long CLOSE_NOTIFICATION_TIMEOUT_SECONDS = 1;

    private final PeeGeeCacheManager manager;
    private final Pool pool;
    private final Vertx vertx;
    private final String schema;
    private final SchemaBootstrapMode schemaBootstrapMode;
    private final ManagementService management;
    private Future<Void> readiness;
    private Future<Void> closing;

    PostgresManagedSetupRuntime(
            PeeGeeCacheManager manager,
            Pool pool,
            Vertx vertx,
            String schema,
            SchemaBootstrapMode schemaBootstrapMode,
            String setupId,
            byte[] cursorKey) {
        this.manager = Objects.requireNonNull(manager, "manager");
        this.pool = Objects.requireNonNull(pool, "pool");
        this.vertx = Objects.requireNonNull(vertx, "vertx");
        this.schema = Objects.requireNonNull(schema, "schema");
        this.schemaBootstrapMode = Objects.requireNonNull(schemaBootstrapMode, "schemaBootstrapMode");
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
            SchemaBootstrapMode schemaBootstrapMode,
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
        this.schemaBootstrapMode = Objects.requireNonNull(schemaBootstrapMode, "schemaBootstrapMode");
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
            readiness = schemaBootstrapMode == SchemaBootstrapMode.APPLY
                    ? manager.startReactive()
                    : verifyMigrationVersion().compose(ignored -> manager.startReactive());
        }
        return readiness;
    }

    private Future<Void> verifyMigrationVersion() {
        return pool.query("SELECT version FROM " + schema
                        + ".schema_migrations ORDER BY version DESC LIMIT 1")
                .execute()
                .compose(rows -> {
                    if (!rows.iterator().hasNext()
                            || !Integer.valueOf(1).equals(rows.iterator().next().getInteger("version"))) {
                        return Future.failedFuture("PeeGeeQ schema migration version 1 is not ready");
                    }
                    return Future.succeededFuture();
                });
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
    public PeeGeeCache cache() {
        return manager.cache();
    }

    @Override
    public boolean supportsPubSub() {
        return true;
    }

    @Override
    public boolean supportsPubSubPayloadReveal() {
        return true;
    }

    @Override
    public boolean supportsBatchEntryOperations() {
        return true;
    }

    @Override
    public boolean supportsValueScan() {
        return true;
    }

    @Override
    public boolean supportsCacheMetrics() {
        return true;
    }

    @Override
    public boolean supportsOwnerLockOperations() {
        return true;
    }

    @Override
    public synchronized Future<Void> closeAsync() {
        if (closing != null) {
            return closing;
        }
        Promise<Void> completion = Promise.promise();
        closing = completion.future();
        Thread.ofVirtual().name("peegeeq-setup-runtime-close").start(() -> closeResources(completion));
        return closing;
    }

    private void closeResources(Promise<Void> completion) {
        try {
            awaitBestEffort(() -> readiness, 10);
            if (manager.isStarted()) {
                awaitBestEffort(manager::stopReactive, CLOSE_NOTIFICATION_TIMEOUT_SECONDS);
            }
            awaitBestEffort(pool::close, CLOSE_NOTIFICATION_TIMEOUT_SECONDS);
            completeBeforeClosingVertx(completion, null);
        } catch (Throwable failure) {
            if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
            completeBeforeClosingVertx(completion, failure);
        }
    }

    private void completeBeforeClosingVertx(Promise<Void> completion, Throwable failure) {
        try {
            vertx.runOnContext(ignored -> {
                if (failure == null) {
                    completion.tryComplete();
                } else {
                    completion.tryFail(failure);
                }
                vertx.runOnContext(next -> Thread.ofVirtual()
                        .name("peegeeq-setup-vertx-close")
                        .start(() -> {
                            try {
                                awaitBestEffort(vertx::close, CLOSE_NOTIFICATION_TIMEOUT_SECONDS);
                            } catch (InterruptedException interrupted) {
                                Thread.currentThread().interrupt();
                            }
                        }));
            });
        } catch (Throwable contextUnavailable) {
            if (failure == null) {
                completion.tryComplete();
            } else {
                completion.tryFail(failure);
            }
        }
    }

    private static void awaitBestEffort(
            Supplier<? extends Future<?>> operation,
            long timeoutSeconds) throws InterruptedException {
        CompletableFuture<Void> attempted = new CompletableFuture<>();
        Thread.ofVirtual().name("peegeeq-setup-resource-close").start(() -> {
            try {
                Future<?> future = operation.get();
                if (future == null) {
                    attempted.complete(null);
                    return;
                }
                future.onComplete(ignored -> attempted.complete(null));
            } catch (Throwable closeFailure) {
                attempted.complete(null);
            }
        });
        try {
            attempted.get(timeoutSeconds, TimeUnit.SECONDS);
        } catch (TimeoutException closeNotificationLost) {
            // A close call or callback can be lost while the isolated executor tears down.
        } catch (java.util.concurrent.ExecutionException impossible) {
            // The helper completes normally for both successful and failed best-effort attempts.
        }
    }

    private static long elapsedMillis(long started) {
        return Math.max(0, (System.nanoTime() - started) / 1_000_000);
    }
}
