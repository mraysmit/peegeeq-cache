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
        Pool pool = null;
        PeeGeeCacheManager manager = null;
        log.info("Starting CacheVersionedUpdateExample");

        try {
            pool = ExampleRuntimeSupport.createPool(vertx);
            log.info("Created PostgreSQL pool for example runtime");
            manager = ExampleRuntimeSupport.startDefaultManager(vertx, pool);
            log.info("Started peegee-cache manager");

            CacheKey key = new CacheKey("orders", "A-1001");
            log.debug("Using cache key {}", key.asQualifiedKey());

            ExampleRuntimeSupport.await(manager.cache().cache().set(new CacheSetRequest(
                    key,
                    CacheValue.ofJsonUtf8("{\"status\":\"NEW\"}"),
                    Duration.ofMinutes(30),
                    SetMode.UPSERT,
                    null,
                    false
            )));
                    log.info("Seeded initial order status payload");

            CacheEntry initial = ExampleRuntimeSupport.await(manager.cache().cache().get(key)).orElseThrow();
            long expectedVersion = initial.version();
                    log.info("Loaded initial cache entry version={}", expectedVersion);

            CacheSetResult firstUpdate = ExampleRuntimeSupport.await(manager.cache().cache().set(new CacheSetRequest(
                    key,
                    CacheValue.ofJsonUtf8("{\"status\":\"PAID\"}"),
                    Duration.ofMinutes(30),
                    SetMode.ONLY_IF_VERSION_MATCHES,
                    expectedVersion,
                    true
            )));
                    log.info("First versioned update applied={}, newVersion={}", firstUpdate.applied(), firstUpdate.newVersion());

            CacheSetResult staleUpdate = ExampleRuntimeSupport.await(manager.cache().cache().set(new CacheSetRequest(
                    key,
                    CacheValue.ofJsonUtf8("{\"status\":\"SHIPPED\"}"),
                    Duration.ofMinutes(30),
                    SetMode.ONLY_IF_VERSION_MATCHES,
                    expectedVersion,
                    true
            )));
            log.info("Stale versioned update applied={} (expected false)", staleUpdate.applied());

            CacheEntry finalState = ExampleRuntimeSupport.await(manager.cache().cache().get(key)).orElseThrow();
            log.info("Final state value={}, version={}", finalState.value().asString(), finalState.version());
        } catch (Exception ex) {
            log.error("CacheVersionedUpdateExample failed: {}", ex.getMessage());
            log.debug("CacheVersionedUpdateExample exception stack trace", ex);
            throw ex;
        } finally {
            log.info("Shutting down CacheVersionedUpdateExample");
            ExampleRuntimeSupport.shutdown(manager, pool, vertx);
            log.info("Shutdown complete");
        }
    }
}
