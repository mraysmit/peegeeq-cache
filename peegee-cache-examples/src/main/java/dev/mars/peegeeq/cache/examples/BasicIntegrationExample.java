package dev.mars.peegeeq.cache.examples;

import dev.mars.peegeeq.cache.api.model.CacheEntry;
import dev.mars.peegeeq.cache.api.model.CacheKey;
import dev.mars.peegeeq.cache.api.model.CacheSetRequest;
import dev.mars.peegeeq.cache.api.model.CacheSetResult;
import dev.mars.peegeeq.cache.api.model.CacheValue;
import dev.mars.peegeeq.cache.api.model.CounterOptions;
import dev.mars.peegeeq.cache.api.model.LockAcquireRequest;
import dev.mars.peegeeq.cache.api.model.LockAcquireResult;
import dev.mars.peegeeq.cache.api.model.LockKey;
import dev.mars.peegeeq.cache.api.model.LockReleaseRequest;
import dev.mars.peegeeq.cache.api.model.SetMode;
import dev.mars.peegeeq.cache.runtime.PeeGeeCacheManager;
import dev.mars.peegeeq.cache.runtime.bootstrap.PeeGeeCacheBootstrapOptions;
import dev.mars.peegeeq.cache.runtime.bootstrap.PeeGeeCaches;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.PoolOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Minimal integration example that boots peegee-cache and exercises
 * cache, counter, and lock primitives against PostgreSQL.
 */
public final class BasicIntegrationExample {

    private static final Logger log = LoggerFactory.getLogger(BasicIntegrationExample.class);
    private static final Duration AWAIT_TIMEOUT = Duration.ofSeconds(15);

    private BasicIntegrationExample() {
    }

    public static void main(String[] args) throws Exception {
        Vertx vertx = Vertx.vertx();
        Pool pool = null;
        PeeGeeCacheManager manager = null;
        log.info("Starting BasicIntegrationExample");

        try {
            pool = Pool.pool(vertx, connectOptionsFromEnv(), new PoolOptions().setMaxSize(8));
            log.info("Created PostgreSQL pool for example runtime");

            PeeGeeCacheBootstrapOptions options = PeeGeeCacheBootstrapOptions.defaults();
            manager = await(PeeGeeCaches.create(vertx, pool, options));
            await(manager.startReactive());
            log.info("Started peegee-cache manager");

            CacheKey cacheKey = new CacheKey("examples", "hello");
            CacheSetRequest setRequest = new CacheSetRequest(
                    cacheKey,
                    CacheValue.ofString("world"),
                    Duration.ofMinutes(5),
                    SetMode.UPSERT,
                    null,
                    false
            );

            CacheSetResult setResult = await(manager.cache().cache().set(setRequest));
            Optional<CacheEntry> loaded = await(manager.cache().cache().get(cacheKey));

            CacheKey counterKey = new CacheKey("examples", "requests");
            long counterValue = await(manager.cache().counters().incrementBy(counterKey, 1));
            long resetCounter = await(manager.cache().counters().setValue(counterKey, 10, CounterOptions.defaults()));

            LockKey lockKey = new LockKey("examples", "startup-lock");
            String ownerToken = "example-app";
            LockAcquireResult lock = await(manager.cache().locks().acquire(new LockAcquireRequest(
                    lockKey,
                    ownerToken,
                    Duration.ofSeconds(20),
                    false,
                    true
            )));

            log.info("set.applied={}, version={}", setResult.applied(), setResult.newVersion());
            log.info("get.value={}", loaded.map(entry -> entry.value().asString()).orElse("<missing>"));
            log.info("counter.incremented={}, counter.reset={}", counterValue, resetCounter);
            log.info("lock.acquired={}, fencingToken={}", lock.acquired(), lock.fencingToken());

            if (lock.acquired()) {
                boolean released = await(manager.cache().locks().release(new LockReleaseRequest(lockKey, ownerToken)));
                log.info("lock.released={}", released);
            }
        } catch (Exception ex) {
            log.error("BasicIntegrationExample failed: {}", ex.getMessage());
            log.debug("BasicIntegrationExample exception stack trace", ex);
            throw ex;
        } finally {
            log.info("Shutting down BasicIntegrationExample");
            if (manager != null && manager.isStarted()) {
                await(manager.stopReactive());
            }
            if (pool != null) {
                await(pool.close());
            }
            await(vertx.close());
            log.info("Shutdown complete");
        }
    }

    private static PgConnectOptions connectOptionsFromEnv() {
        String host = envOrDefault("PGHOST", "127.0.0.1");
        int port = Integer.parseInt(envOrDefault("PGPORT", "5432"));
        String database = envOrDefault("PGDATABASE", "postgres");
        String user = envOrDefault("PGUSER", "postgres");
        String password = envOrDefault("PGPASSWORD", "postgres");

        return new PgConnectOptions()
                .setHost(host)
                .setPort(port)
                .setDatabase(database)
                .setUser(user)
                .setPassword(password);
    }

    private static String envOrDefault(String name, String fallback) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return value;
    }

    private static <T> T await(Future<T> future) throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<T> resultRef = new AtomicReference<>();
        AtomicReference<Throwable> errorRef = new AtomicReference<>();

        future.onComplete(ar -> {
            if (ar.succeeded()) {
                resultRef.set(ar.result());
            } else {
                errorRef.set(ar.cause());
            }
            latch.countDown();
        });

        if (!latch.await(AWAIT_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
            throw new RuntimeException("Timed out waiting for async operation");
        }

        if (errorRef.get() != null) {
            throw new RuntimeException(errorRef.get());
        }

        return resultRef.get();
    }
}
