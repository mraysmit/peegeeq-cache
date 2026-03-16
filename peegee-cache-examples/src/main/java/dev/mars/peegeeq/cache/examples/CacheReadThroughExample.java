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
import org.testcontainers.containers.PostgreSQLContainer;

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
        PostgreSQLContainer<?> container = null;
        Pool pool = null;
        PeeGeeCacheManager manager = null;
        log.info("Starting CacheReadThroughExample");

        try {
            container = ExampleRuntimeSupport.startContainer();
            ExampleRuntimeSupport.applyMigrations(vertx, container);
            pool = ExampleRuntimeSupport.createPool(vertx, container);
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
                ExampleLogSupport.logData(log, "cache-miss source payload",
                    "key", key.asQualifiedKey(),
                    "value", loadedFromSource);
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
                ExampleLogSupport.logData(log, "cache-fill winner",
                    "key", key.asQualifiedKey(),
                    "value", loadedFromSource,
                    "version", setResult.newVersion());
            } else {
                Optional<CacheEntry> existing = ExampleRuntimeSupport.await(manager.cache().cache().get(key));
                ExampleLogSupport.logData(log, "cache-fill lost race",
                    "key", key.asQualifiedKey(),
                    "existing", existing.map(entry -> entry.value().asString()).orElse("<missing>"));
            }
        } catch (Exception ex) {
            log.error("CacheReadThroughExample failed: {}", ex.getMessage());
            log.debug("CacheReadThroughExample exception stack trace", ex);
            throw ex;
        } finally {
            log.info("Shutting down CacheReadThroughExample");
            ExampleRuntimeSupport.shutdown(manager, pool, vertx, container);
            log.info("Shutdown complete");
        }
    }

    private static String loadProductJson(String productId) {
        return "{\"id\":\"" + productId + "\",\"name\":\"Widget\",\"price\":1299}";
    }
}
