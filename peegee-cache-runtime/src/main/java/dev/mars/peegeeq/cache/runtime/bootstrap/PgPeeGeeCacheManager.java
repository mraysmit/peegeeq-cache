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
        Banner.print();
        log.info("PeeGeeCacheManager started (schema={})", options.storeConfig().schemaName());
        return Future.succeededFuture();
    }

    @Override
    public Future<Void> stopReactive() {
        if (!started.compareAndSet(true, false)) {
            return Future.failedFuture(new IllegalStateException("Manager is not started"));
        }
        log.info("PeeGeeCacheManager stopping");
        // Future: stop sweeper, close listener connections, etc.
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
