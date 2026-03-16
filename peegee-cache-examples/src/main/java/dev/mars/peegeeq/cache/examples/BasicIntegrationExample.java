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
import io.vertx.core.Vertx;
import io.vertx.sqlclient.Pool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.PostgreSQLContainer;

import java.time.Duration;
import java.util.Optional;

/**
 * Minimal integration example that boots peegee-cache and exercises
 * cache, counter, and lock primitives against PostgreSQL.
 */
public final class BasicIntegrationExample {

    private static final Logger log = LoggerFactory.getLogger(BasicIntegrationExample.class);

    private BasicIntegrationExample() {
    }

    public static void main(String[] args) throws Exception {
        Vertx vertx = Vertx.vertx();
        PostgreSQLContainer<?> container = null;
        Pool pool = null;
        PeeGeeCacheManager manager = null;
        log.info("Starting BasicIntegrationExample");

        try {
            container = ExampleRuntimeSupport.startContainer();
            ExampleRuntimeSupport.applyMigrations(vertx, container);
            pool = ExampleRuntimeSupport.createPool(vertx, container);
            log.info("Created PostgreSQL pool for example runtime");
            manager = ExampleRuntimeSupport.startDefaultManager(vertx, pool);
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

            CacheSetResult setResult = ExampleRuntimeSupport.await(manager.cache().cache().set(setRequest));
            Optional<CacheEntry> loaded = ExampleRuntimeSupport.await(manager.cache().cache().get(cacheKey));

            CacheKey counterKey = new CacheKey("examples", "requests");
            long counterValue = ExampleRuntimeSupport.await(manager.cache().counters().incrementBy(counterKey, 1));
            long resetCounter = ExampleRuntimeSupport.await(manager.cache().counters().setValue(counterKey, 10, CounterOptions.defaults()));

            LockKey lockKey = new LockKey("examples", "startup-lock");
            String ownerToken = "example-app";
            LockAcquireResult lock = ExampleRuntimeSupport.await(manager.cache().locks().acquire(new LockAcquireRequest(
                    lockKey,
                    ownerToken,
                    Duration.ofSeconds(20),
                    false,
                    true
            )));

                ExampleLogSupport.logData(log, "cache-set result",
                    "key", cacheKey.asQualifiedKey(),
                    "applied", setResult.applied(),
                    "version", setResult.newVersion());
                ExampleLogSupport.logData(log, "cache-get result",
                    "key", cacheKey.asQualifiedKey(),
                    "value", loaded.map(entry -> entry.value().asString()).orElse("<missing>"));
                ExampleLogSupport.logData(log, "counter results",
                    "key", counterKey.asQualifiedKey(),
                    "incremented", counterValue,
                    "resetTo", resetCounter);
                ExampleLogSupport.logData(log, "lock acquire result",
                    "key", lockKey.asQualifiedKey(),
                    "acquired", lock.acquired(),
                    "fencingToken", lock.fencingToken());

            if (lock.acquired()) {
                boolean released = ExampleRuntimeSupport.await(manager.cache().locks().release(new LockReleaseRequest(lockKey, ownerToken)));
                ExampleLogSupport.logData(log, "lock release result",
                    "key", lockKey.asQualifiedKey(),
                    "released", released);
            }
        } catch (Exception ex) {
            log.error("BasicIntegrationExample failed: {}", ex.getMessage());
            log.debug("BasicIntegrationExample exception stack trace", ex);
            throw ex;
        } finally {
            log.info("Shutting down BasicIntegrationExample");
            ExampleRuntimeSupport.shutdown(manager, pool, vertx, container);
            log.info("Shutdown complete");
        }
    }
}
