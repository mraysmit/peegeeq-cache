package dev.mars.peegeeq.cache.examples;

import dev.mars.peegeeq.cache.api.model.CacheEntry;
import dev.mars.peegeeq.cache.api.model.CacheKey;
import dev.mars.peegeeq.cache.api.model.CacheSetRequest;
import dev.mars.peegeeq.cache.api.model.CacheSetResult;
import dev.mars.peegeeq.cache.api.model.CacheValue;
import dev.mars.peegeeq.cache.api.model.SetMode;
import dev.mars.peegeeq.cache.runtime.PeeGeeCacheManager;
import io.vertx.core.Vertx;
import io.vertx.sqlclient.Pool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.Duration;

/**
 * Demonstrates optimistic concurrency with ONLY_IF_VERSION_MATCHES.
 */
public final class CacheVersionedUpdateExample {

    private static final Logger log = LoggerFactory.getLogger(CacheVersionedUpdateExample.class);

    private CacheVersionedUpdateExample() {
    }

    public static void main(String[] args) throws Exception {
        Vertx vertx = Vertx.vertx();
        PostgreSQLContainer container = null;
        Pool pool = null;
        PeeGeeCacheManager manager = null;
        log.info("example.cache_versioned_update.starting");

        try {
            container = ExampleRuntimeSupport.startContainer();
            ExampleRuntimeSupport.applyBootstrapSql(vertx, container);
            pool = ExampleRuntimeSupport.createPool(vertx, container);
            log.info("example.sql_pool.created");
            manager = ExampleRuntimeSupport.startDefaultManager(vertx, pool);
            log.info("example.cache_manager.ready");
            var cache = manager.cache();

            CacheKey key = new CacheKey("orders", "A-1001");
            log.debug("cache.versioned_update.key_created");

            ExampleLogSupport.timed(log, "cache.set(seed)", () ->
                    ExampleRuntimeSupport.await(cache.cache().set(new CacheSetRequest(
                            key,
                            CacheValue.ofJsonUtf8("{\"status\":\"NEW\"}"),
                            Duration.ofMinutes(30),
                            SetMode.UPSERT,
                            null,
                            false
                    ))));
            ExampleLogSupport.logData(log, "seeded dataset",
                    "valueType", "JSON");

            CacheEntry initial = ExampleLogSupport.timed(log, "cache.get(initial)", () ->
                    ExampleRuntimeSupport.await(cache.cache().get(key))).orElseThrow();
            long expectedVersion = initial.version();
            ExampleLogSupport.logData(log, "loaded current entry",
                    "valueType", initial.value().type(),
                    "version", expectedVersion);

            CacheSetResult firstUpdate = ExampleLogSupport.timed(log, "cache.set(version-match)", () ->
                    ExampleRuntimeSupport.await(cache.cache().set(new CacheSetRequest(
                            key,
                            CacheValue.ofJsonUtf8("{\"status\":\"PAID\"}"),
                            Duration.ofMinutes(30),
                            SetMode.ONLY_IF_VERSION_MATCHES,
                            expectedVersion,
                            true
                    ))));
            ExampleLogSupport.logData(log, "first versioned update",
                    "applied", firstUpdate.applied(),
                    "newVersion", firstUpdate.newVersion());

            CacheSetResult staleUpdate = ExampleLogSupport.timed(log, "cache.set(stale-version)", () ->
                    ExampleRuntimeSupport.await(cache.cache().set(new CacheSetRequest(
                            key,
                            CacheValue.ofJsonUtf8("{\"status\":\"SHIPPED\"}"),
                            Duration.ofMinutes(30),
                            SetMode.ONLY_IF_VERSION_MATCHES,
                            expectedVersion,
                            true
                    ))));
            ExampleLogSupport.logData(log, "stale versioned update",
                    "applied", staleUpdate.applied(),
                    "expected", false);

            CacheEntry finalState = ExampleLogSupport.timed(log, "cache.get(final)", () ->
                    ExampleRuntimeSupport.await(cache.cache().get(key))).orElseThrow();
            ExampleLogSupport.logData(log, "final dataset",
                    "valueType", finalState.value().type(),
                    "version", finalState.version());
        } catch (Exception ex) {
            log.atError().setCause(ex).log("example.cache_versioned_update.failed");
            throw ex;
        } finally {
            log.info("example.cache_versioned_update.stopping");
            ExampleRuntimeSupport.shutdown(manager, pool, vertx, container);
            log.info("example.cache_versioned_update.stopped");
        }
    }
}
