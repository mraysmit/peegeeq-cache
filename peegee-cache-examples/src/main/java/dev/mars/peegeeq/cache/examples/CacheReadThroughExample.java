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
import java.util.Optional;

/**
 * Demonstrates a read-through caching flow with stampede-safe write behavior.
 */
public final class CacheReadThroughExample {

    private static final Logger log = LoggerFactory.getLogger(CacheReadThroughExample.class);

    private CacheReadThroughExample() {
    }

    public static void main(String[] args) throws Exception {
        Vertx vertx = Vertx.vertx();
        Pool pool = null;
        PeeGeeCacheManager manager = null;
        log.info("Starting CacheReadThroughExample");

        try {
            pool = ExampleRuntimeSupport.createPool(vertx);
            log.info("Created PostgreSQL pool for example runtime");
            manager = ExampleRuntimeSupport.startDefaultManager(vertx, pool);
            log.info("Started peegee-cache manager");

            CacheKey key = new CacheKey("products", "42");
            log.debug("Read-through lookup for {}", key.asQualifiedKey());
            Optional<CacheEntry> cached = ExampleRuntimeSupport.await(manager.cache().cache().get(key));
            if (cached.isPresent()) {
                log.info("cache-hit value={}", cached.get().value().asString());
                return;
            }

            String loadedFromSource = loadProductJson("42");
            log.info("cache-miss; loaded value from backing source");
            CacheSetRequest writeIfAbsent = new CacheSetRequest(
                    key,
                    CacheValue.ofJsonUtf8(loadedFromSource),
                    Duration.ofMinutes(10),
                    SetMode.ONLY_IF_ABSENT,
                    null,
                    true
            );
            CacheSetResult setResult = ExampleRuntimeSupport.await(manager.cache().cache().set(writeIfAbsent));

            if (setResult.applied()) {
                log.info("cache-fill winner value={}", loadedFromSource);
            } else {
                Optional<CacheEntry> existing = ExampleRuntimeSupport.await(manager.cache().cache().get(key));
                log.info("cache-fill lost-race existing={}",
                        existing.map(entry -> entry.value().asString()).orElse("<missing>"));
            }
        } catch (Exception ex) {
            log.error("CacheReadThroughExample failed: {}", ex.getMessage());
            log.debug("CacheReadThroughExample exception stack trace", ex);
            throw ex;
        } finally {
            log.info("Shutting down CacheReadThroughExample");
            ExampleRuntimeSupport.shutdown(manager, pool, vertx);
            log.info("Shutdown complete");
        }
    }

    private static String loadProductJson(String productId) {
        return "{\"id\":\"" + productId + "\",\"name\":\"Widget\",\"price\":1299}";
    }
}
