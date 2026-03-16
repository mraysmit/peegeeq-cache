package dev.mars.peegeeq.cache.runtime.bootstrap;

import dev.mars.peegeeq.cache.api.PeeGeeCache;
import dev.mars.peegeeq.cache.pg.PgPeeGeeCache;
import dev.mars.peegeeq.cache.pg.config.PgCacheStoreConfig;
import dev.mars.peegeeq.cache.pg.repository.PgCacheRepository;
import dev.mars.peegeeq.cache.pg.repository.PgCounterRepository;
import dev.mars.peegeeq.cache.pg.repository.PgLockRepository;
import dev.mars.peegeeq.cache.pg.service.PgCacheService;
import dev.mars.peegeeq.cache.pg.service.PgCounterService;
import dev.mars.peegeeq.cache.pg.service.PgLockService;
import dev.mars.peegeeq.cache.runtime.Banner;
import dev.mars.peegeeq.cache.runtime.PeeGeeCacheManager;
import dev.mars.peegeeq.cache.runtime.config.PeeGeeCacheConfig;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.sqlclient.Pool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * PostgreSQL-backed implementation of {@link PeeGeeCacheManager}.
 * <p>
 * Wires repositories and services, prints the startup banner,
 * and manages explicit start/stop lifecycle.
 */
final class PgPeeGeeCacheManager implements PeeGeeCacheManager {

    private static final Logger log = LoggerFactory.getLogger(PgPeeGeeCacheManager.class);

    private final Vertx vertx;
    private final Pool pool;
    private final PeeGeeCacheBootstrapOptions options;
    private final PeeGeeCache cache;
    private final AtomicBoolean started = new AtomicBoolean(false);
    private final AtomicBoolean listenerRunning = new AtomicBoolean(false);

    private volatile long sweeperTimerId = -1L;

    PgPeeGeeCacheManager(Vertx vertx, Pool pool, PeeGeeCacheBootstrapOptions options) {
        this.vertx = Objects.requireNonNull(vertx, "vertx");
        this.pool = Objects.requireNonNull(pool, "pool");
        this.options = normalizeOptions(options);

        // Wire the service graph
        PgCacheRepository cacheRepo = new PgCacheRepository(pool);
        PgCounterRepository counterRepo = new PgCounterRepository(pool);
        PgLockRepository lockRepo = new PgLockRepository(pool);

        PgCacheService cacheService = new PgCacheService(cacheRepo);
        PgCounterService counterService = new PgCounterService(counterRepo);
        PgLockService lockService = new PgLockService(lockRepo);

        // ScanService and PubSubService are not yet implemented — use stubs
        this.cache = new PgPeeGeeCache(
                cacheService,
                counterService,
                lockService,
                NotImplementedStubs.scanService(),
                NotImplementedStubs.pubSubService()
        );
    }

    @Override
    public Future<Void> startReactive() {
        if (!started.compareAndSet(false, true)) {
            return Future.failedFuture(new IllegalStateException("Manager is already started"));
        }

        return startBackgroundComponents()
                .onSuccess(v -> {
                    Banner.print();
                    log.info("PeeGeeCacheManager started (schema={})", options.storeConfig().schemaName());
                })
                .onFailure(err -> {
                    stopBackgroundComponents();
                    started.set(false);
                });
    }

    @Override
    public Future<Void> stopReactive() {
        if (!started.compareAndSet(true, false)) {
            return Future.failedFuture(new IllegalStateException("Manager is not started"));
        }
        log.info("PeeGeeCacheManager stopping");
        stopBackgroundComponents();
        log.info("PeeGeeCacheManager stopped");
        return Future.succeededFuture();
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
            stopReactive().onComplete(ar -> latch.countDown());
            try {
                if (!latch.await(10, TimeUnit.SECONDS)) {
                    log.warn("Timed out waiting for PeeGeeCacheManager to stop");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("Interrupted while closing PeeGeeCacheManager");
            }
        }
    }

    boolean isExpirySweeperRunning() {
        return sweeperTimerId >= 0;
    }

    boolean isListenerRunning() {
        return listenerRunning.get();
    }

    private Future<Void> startBackgroundComponents() {
        PeeGeeCacheConfig runtime = options.runtimeConfig();

        if (runtime.enableExpirySweeper()) {
            long intervalMillis = runtime.expirySweepInterval().toMillis();
            sweeperTimerId = vertx.setPeriodic(intervalMillis, id -> {
                // Phase 5 runtime ownership: timer lifecycle is now explicit and managed.
                log.debug("Expiry sweeper tick (schema={}, batchSize={})",
                        options.storeConfig().schemaName(),
                        runtime.expirySweepBatchSize());
            });
            log.info("Expiry sweeper started (interval={}ms, batchSize={})", intervalMillis, runtime.expirySweepBatchSize());
        }

        listenerRunning.set(true);
        log.info("Pub/Sub listener lifecycle started (placeholder implementation)");
        return Future.succeededFuture();
    }

    private void stopBackgroundComponents() {
        long timerId = sweeperTimerId;
        if (timerId >= 0) {
            vertx.cancelTimer(timerId);
            sweeperTimerId = -1L;
            log.info("Expiry sweeper stopped");
        }

        if (listenerRunning.compareAndSet(true, false)) {
            log.info("Pub/Sub listener lifecycle stopped");
        }
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

        return new PeeGeeCacheBootstrapOptions(runtimeConfig, storeConfig);
    }
}
