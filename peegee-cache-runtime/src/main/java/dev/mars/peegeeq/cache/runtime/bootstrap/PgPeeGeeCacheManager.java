package dev.mars.peegeeq.cache.runtime.bootstrap;

import dev.mars.peegeeq.cache.api.PeeGeeCache;
import dev.mars.peegeeq.cache.core.metrics.CacheMetrics;
import dev.mars.peegeeq.cache.pg.PgPeeGeeCache;
import dev.mars.peegeeq.cache.pg.config.PgCacheStoreConfig;
import dev.mars.peegeeq.cache.pg.bootstrap.PgSchemaMigrator;
import dev.mars.peegeeq.cache.core.telemetry.CacheOperation;
import dev.mars.peegeeq.cache.pg.repository.PgAdminRepository;
import dev.mars.peegeeq.cache.pg.repository.PgCacheRepository;
import dev.mars.peegeeq.cache.pg.repository.PgCounterRepository;
import dev.mars.peegeeq.cache.pg.repository.PgLockRepository;
import dev.mars.peegeeq.cache.pg.repository.PgPubSubRepository;
import dev.mars.peegeeq.cache.pg.repository.PgScanRepository;
import dev.mars.peegeeq.cache.pg.service.PgAdminService;
import dev.mars.peegeeq.cache.pg.service.PgCacheService;
import dev.mars.peegeeq.cache.pg.service.PgCounterService;
import dev.mars.peegeeq.cache.pg.service.PgLockService;
import dev.mars.peegeeq.cache.pg.service.PgPubSubService;
import dev.mars.peegeeq.cache.pg.service.PgScanService;
import dev.mars.peegeeq.cache.runtime.PeeGeeCacheManager;
import dev.mars.peegeeq.cache.runtime.config.PeeGeeCacheConfig;
import dev.mars.peegeeq.cache.runtime.expiry.PgExpirySweeper;
import dev.mars.peegeeq.cache.runtime.logging.RecurringFailureTracker;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.sqlclient.Pool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * PostgreSQL-backed implementation of {@link PeeGeeCacheManager}.
 * <p>
 * Wires repositories and services and manages explicit start/stop lifecycle.
 */
final class PgPeeGeeCacheManager implements PeeGeeCacheManager {

    private static final Logger log = LoggerFactory.getLogger(PgPeeGeeCacheManager.class);

    private final Vertx vertx;
    private final Pool pool;
    private final PeeGeeCacheBootstrapOptions options;
    private final PeeGeeCache cache;
    private final PgPubSubService pubSubService;
    private final PgExpirySweeper expirySweeper;
    private final CacheMetrics metrics;
    private final AtomicBoolean started = new AtomicBoolean(false);
    private final RecurringFailureTracker expirySweepFailures = new RecurringFailureTracker();

    private volatile long sweeperTimerId = -1L;

    PgPeeGeeCacheManager(Vertx vertx, Pool pool, PeeGeeCacheBootstrapOptions options) {
        this.vertx = Objects.requireNonNull(vertx, "vertx");
        this.pool = Objects.requireNonNull(pool, "pool");
        this.options = normalizeOptions(options);

        // Wire the service graph
        PgCacheStoreConfig storeConfig = this.options.storeConfig();
        String schemaName = storeConfig.schemaName();
        this.metrics = new CacheMetrics(this.options.telemetry());

        PgCacheRepository cacheRepo = new PgCacheRepository(pool, schemaName);
        PgCounterRepository counterRepo = new PgCounterRepository(pool, schemaName);
        PgLockRepository lockRepo = new PgLockRepository(pool, schemaName);

        PgCacheService cacheService = new PgCacheService(
                cacheRepo, this.metrics, this.options.runtimeConfig().defaultTtl());
        PgCounterService counterService = new PgCounterService(counterRepo, this.metrics);
        PgLockService lockService = new PgLockService(lockRepo, this.metrics);

        PgScanRepository scanRepo = new PgScanRepository(pool, schemaName);
        PgScanService scanService = new PgScanService(scanRepo, this.metrics);

        PgAdminRepository adminRepo = new PgAdminRepository(pool, schemaName);
        PgAdminService adminService = new PgAdminService(adminRepo, this.metrics);
        this.expirySweeper = new PgExpirySweeper(pool, schemaName);

        PgPubSubRepository pubSubRepo = new PgPubSubRepository(pool, storeConfig);
        PgConnectOptions connectOpts = this.options.connectOptions();
        if (connectOpts != null) {
            this.pubSubService = new PgPubSubService(vertx, pubSubRepo, connectOpts, storeConfig, this.metrics);
        } else {
            this.pubSubService = null;
        }

        this.cache = new PgPeeGeeCache(
                cacheService,
                counterService,
                lockService,
                scanService,
                pubSubService != null ? pubSubService : NotImplementedStubs.pubSubService(this.metrics),
                adminService
        );
    }

    @Override
    public Future<Void> startReactive() {
        if (!started.compareAndSet(false, true)) {
            return Future.failedFuture(new IllegalStateException("Manager is already started"));
        }

        return startBackgroundComponents()
                .onSuccess(v -> {
                    metrics.recordLifecycle(true);
                    log.atInfo()
                            .addKeyValue("schema", options.storeConfig().schemaName())
                            .addKeyValue("expiry.sweeper", options.runtimeConfig().enableExpirySweeper())
                            .addKeyValue("pubsub", pubSubService != null)
                            .log("cache.manager.started");
                })
                .onFailure(err -> {
                    metrics.recordLifecycle(false);
                    log.atError().addKeyValue("schema", options.storeConfig().schemaName())
                            .setCause(err).log("cache.manager.start_failed");
                    stopBackgroundComponents()
                            .onComplete(v -> started.set(false));
                });
    }

    @Override
    public Future<Void> stopReactive() {
        if (!started.compareAndSet(true, false)) {
            return Future.failedFuture(new IllegalStateException("Manager is not started"));
        }
        log.atInfo().addKeyValue("schema", options.storeConfig().schemaName())
                .log("cache.manager.stopping");
        return stopBackgroundComponents()
                .onSuccess(v -> {
                    metrics.recordLifecycle(false);
                    log.atInfo().addKeyValue("schema", options.storeConfig().schemaName())
                            .log("cache.manager.stopped");
                });
    }

    @Override
    public boolean isStarted() {
        return started.get();
    }

    @Override
    public Vertx vertx() {
        return vertx;
    }

    @Override
    public Pool pool() {
        return pool;
    }

    @Override
    public PeeGeeCache cache() {
        if (!started.get()) {
            throw new IllegalStateException("Manager is not started — call startReactive() first");
        }
        return cache;
    }

    @Override
    public void close() {
        if (started.get()) {
            CountDownLatch latch = new CountDownLatch(1);
            AtomicReference<Throwable> stopFailure = new AtomicReference<>();
            stopReactive().onComplete(ar -> {
                if (ar.failed()) {
                    stopFailure.set(ar.cause());
                }
                latch.countDown();
            });
            try {
                if (!latch.await(10, TimeUnit.SECONDS)) {
                    log.atWarn().addKeyValue("timeout.seconds", 10)
                            .log("cache.manager.stop_timed_out");
                } else if (stopFailure.get() != null) {
                    log.atWarn().setCause(stopFailure.get()).log("cache.manager.stop_failed");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.atWarn().setCause(e).log("cache.manager.stop_interrupted");
            }
        }
    }

    boolean isExpirySweeperRunning() {
        return sweeperTimerId >= 0;
    }

    boolean isListenerRunning() {
        return pubSubService != null && pubSubService.isListenerConnected();
    }

    private Future<Void> startBackgroundComponents() {
        return applySchemaPolicy().compose(ignored -> startConfiguredBackgroundComponents());
    }

    private Future<Void> applySchemaPolicy() {
        if (options.schemaBootstrapMode() == SchemaBootstrapMode.EXTERNAL) {
            return Future.succeededFuture();
        }
        return metrics.<Void>observe(CacheOperation.SCHEMA_BOOTSTRAP,
                () -> new PgSchemaMigrator(pool, options.storeConfig().schemaName()).migrate())
                .onSuccess(ignored -> log.atInfo()
                        .addKeyValue("schema", options.storeConfig().schemaName())
                        .log("cache.schema.migration_checked"));
    }

    private Future<Void> startConfiguredBackgroundComponents() {
        PeeGeeCacheConfig runtime = options.runtimeConfig();

        if (runtime.enableExpirySweeper()) {
            long intervalMillis = runtime.expirySweepInterval().toMillis();
            sweeperTimerId = vertx.setPeriodic(intervalMillis, id -> {
                long sweepStartedAt = System.nanoTime();
                expirySweeper.sweepDetailed(runtime.expirySweepBatchSize())
                        .onSuccess(result -> {
                            metrics.recordExpirySweep(result.deletedRows(),
                                    java.time.Duration.ofNanos(System.nanoTime() - sweepStartedAt),
                                    result.oldestExpiredRowLag(), null);
                            expirySweepFailures.recordRecovery().ifPresent(suppressed ->
                                    log.atInfo().addKeyValue("schema", options.storeConfig().schemaName())
                                            .addKeyValue("suppressed.failures", suppressed)
                                            .log("cache.expiry_sweep.recovered"));
                            if (result.deletedRows() > 0) {
                                log.atDebug().addKeyValue("schema", options.storeConfig().schemaName())
                                        .addKeyValue("deleted.rows", result.deletedRows())
                                        .addKeyValue("oldest.lag.ms", result.oldestExpiredRowLag().toMillis())
                                        .log("cache.expiry_sweep.completed");
                            }
                        })
                        .onFailure(err -> {
                            metrics.recordExpirySweep(0,
                                    java.time.Duration.ofNanos(System.nanoTime() - sweepStartedAt),
                                    java.time.Duration.ZERO, err);
                            RecurringFailureTracker.Failure observation = expirySweepFailures.recordFailure();
                            if (observation.firstFailure()) {
                                log.atWarn().addKeyValue("schema", options.storeConfig().schemaName())
                                        .setCause(err).log("cache.expiry_sweep.failed");
                            }
                        });
            });
            log.atInfo().addKeyValue("interval.ms", intervalMillis)
                    .addKeyValue("batch.size", runtime.expirySweepBatchSize())
                    .log("cache.expiry_sweeper.started");
        }

        if (pubSubService != null) {
            return pubSubService.start();
        }

        log.atDebug().addKeyValue("reason", "connect_options_absent")
                .log("pubsub.listener.disabled");
        return Future.succeededFuture();
    }

    private Future<Void> stopBackgroundComponents() {
        long timerId = sweeperTimerId;
        if (timerId >= 0) {
            vertx.cancelTimer(timerId);
            sweeperTimerId = -1L;
            log.atInfo().log("cache.expiry_sweeper.stopped");
        }

        Future<Void> sweeperStopped = expirySweeper.awaitIdle();
        if (pubSubService != null) {
            return sweeperStopped.compose(ignored -> pubSubService.stop())
                    .onSuccess(v -> log.atInfo().log("pubsub.listener.stopped"))
                    .onFailure(err -> log.atWarn().setCause(err).log("pubsub.listener.stop_failed"));
        }
        return sweeperStopped;
    }

    private static PeeGeeCacheBootstrapOptions normalizeOptions(PeeGeeCacheBootstrapOptions options) {
        PeeGeeCacheBootstrapOptions resolved = options != null
                ? options
                : PeeGeeCacheBootstrapOptions.defaults();

        PeeGeeCacheConfig runtimeConfig = resolved.runtimeConfig() != null
                ? resolved.runtimeConfig()
                : PeeGeeCacheConfig.defaults();

        PgCacheStoreConfig storeConfig = resolved.storeConfig() != null
                ? resolved.storeConfig()
                : PgCacheStoreConfig.defaults();

        return new PeeGeeCacheBootstrapOptions(runtimeConfig, storeConfig, resolved.connectOptions(),
                resolved.telemetry() != null ? resolved.telemetry() : dev.mars.peegeeq.cache.core.telemetry.CacheTelemetry.noop(),
                resolved.schemaBootstrapMode() != null ? resolved.schemaBootstrapMode() : SchemaBootstrapMode.EXTERNAL);
    }
}
