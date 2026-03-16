package dev.mars.peegeeq.cache.examples;

import dev.mars.peegeeq.cache.api.model.CacheEntry;
import dev.mars.peegeeq.cache.api.model.CacheKey;
import dev.mars.peegeeq.cache.api.model.CacheSetRequest;
import dev.mars.peegeeq.cache.api.model.CacheValue;
import dev.mars.peegeeq.cache.api.model.SetMode;
import dev.mars.peegeeq.cache.api.model.TtlResult;
import dev.mars.peegeeq.cache.runtime.PeeGeeCacheManager;
import io.vertx.core.Vertx;
import io.vertx.sqlclient.Pool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Demonstrates batch operations and TTL lifecycle behavior.
 */
public final class CacheBatchAndTtlExample {

    private static final Logger log = LoggerFactory.getLogger(CacheBatchAndTtlExample.class);

    private CacheBatchAndTtlExample() {
    }

    public static void main(String[] args) throws Exception {
        Vertx vertx = Vertx.vertx();
        Pool pool = null;
        PeeGeeCacheManager manager = null;
        log.info("Starting CacheBatchAndTtlExample");

        try {
            pool = ExampleRuntimeSupport.createPool(vertx);
            log.info("Created PostgreSQL pool for example runtime");
            manager = ExampleRuntimeSupport.startDefaultManager(vertx, pool);
            log.info("Started peegee-cache manager");

            CacheKey alpha = new CacheKey("batch", "alpha");
            CacheKey beta = new CacheKey("batch", "beta");
            CacheKey gamma = new CacheKey("batch", "gamma");

            List<CacheSetRequest> requests = List.of(
                    new CacheSetRequest(alpha, CacheValue.ofString("A"), Duration.ofMinutes(5), SetMode.UPSERT, null, false),
                    new CacheSetRequest(beta, CacheValue.ofString("B"), Duration.ofSeconds(30), SetMode.UPSERT, null, false),
                    new CacheSetRequest(gamma, CacheValue.ofString("C"), null, SetMode.UPSERT, null, false)
            );
                log.debug("Executing setMany for {} keys", requests.size());
            ExampleRuntimeSupport.await(manager.cache().cache().setMany(requests));

            Map<CacheKey, Optional<CacheEntry>> loaded = ExampleRuntimeSupport.await(
                    manager.cache().cache().getMany(List.of(alpha, beta, gamma))
            );
            log.info("batch-get alpha={}", loaded.get(alpha).map(entry -> entry.value().asString()).orElse("<missing>"));
            log.info("batch-get beta={}", loaded.get(beta).map(entry -> entry.value().asString()).orElse("<missing>"));
            log.info("batch-get gamma={}", loaded.get(gamma).map(entry -> entry.value().asString()).orElse("<missing>"));

            ExampleRuntimeSupport.await(manager.cache().cache().expire(gamma, Duration.ofSeconds(20)));
            ExampleRuntimeSupport.await(manager.cache().cache().touch(beta, Duration.ofSeconds(45)));
            ExampleRuntimeSupport.await(manager.cache().cache().persist(alpha));

            TtlResult alphaTtl = ExampleRuntimeSupport.await(manager.cache().cache().ttl(alpha));
            TtlResult betaTtl = ExampleRuntimeSupport.await(manager.cache().cache().ttl(beta));
            TtlResult gammaTtl = ExampleRuntimeSupport.await(manager.cache().cache().ttl(gamma));

            log.info("ttl alpha state={}, ttlMillis={}", alphaTtl.state(), alphaTtl.ttlMillis());
            log.info("ttl beta state={}, ttlMillis={}", betaTtl.state(), betaTtl.ttlMillis());
            log.info("ttl gamma state={}, ttlMillis={}", gammaTtl.state(), gammaTtl.ttlMillis());

            long removed = ExampleRuntimeSupport.await(manager.cache().cache().deleteMany(List.of(alpha, beta, gamma)));
            log.info("batch-delete removed={}", removed);
        } catch (Exception ex) {
            log.error("CacheBatchAndTtlExample failed: {}", ex.getMessage());
            log.debug("CacheBatchAndTtlExample exception stack trace", ex);
            throw ex;
        } finally {
            log.info("Shutting down CacheBatchAndTtlExample");
            ExampleRuntimeSupport.shutdown(manager, pool, vertx);
            log.info("Shutdown complete");
        }
    }
}
