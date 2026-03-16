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
import org.testcontainers.containers.PostgreSQLContainer;

import java.time.Duration;
import java.util.List;
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
        PostgreSQLContainer<?> container = null;
        Pool pool = null;
        PeeGeeCacheManager manager = null;
        log.info("Starting CacheBatchAndTtlExample");

        try {
            container = ExampleRuntimeSupport.startContainer();
            ExampleRuntimeSupport.applyMigrations(vertx, container);
            pool = ExampleRuntimeSupport.createPool(vertx, container);
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
                ExampleLogSupport.logData(log, "seeding dataset",
                    alpha.asQualifiedKey(), "A",
                    beta.asQualifiedKey(), "B",
                    gamma.asQualifiedKey(), "C");
                log.debug("Executing setMany for {} keys", requests.size());
            ExampleRuntimeSupport.await(manager.cache().cache().setMany(requests));

                var loaded = ExampleRuntimeSupport.await(
                    manager.cache().cache().getMany(List.of(alpha, beta, gamma))
            );
                ExampleLogSupport.logData(log, "batch-get dataset",
                    alpha.asQualifiedKey(), loaded.get(alpha).map(entry -> entry.value().asString()).orElse("<missing>"),
                    beta.asQualifiedKey(), loaded.get(beta).map(entry -> entry.value().asString()).orElse("<missing>"),
                    gamma.asQualifiedKey(), loaded.get(gamma).map(entry -> entry.value().asString()).orElse("<missing>"));

            ExampleRuntimeSupport.await(manager.cache().cache().expire(gamma, Duration.ofSeconds(20)));
            ExampleRuntimeSupport.await(manager.cache().cache().touch(beta, Duration.ofSeconds(45)));
            ExampleRuntimeSupport.await(manager.cache().cache().persist(alpha));

            TtlResult alphaTtl = ExampleRuntimeSupport.await(manager.cache().cache().ttl(alpha));
            TtlResult betaTtl = ExampleRuntimeSupport.await(manager.cache().cache().ttl(beta));
            TtlResult gammaTtl = ExampleRuntimeSupport.await(manager.cache().cache().ttl(gamma));

                ExampleLogSupport.logData(log, "ttl states",
                    alpha.asQualifiedKey(), alphaTtl.state() + "/" + alphaTtl.ttlMillis(),
                    beta.asQualifiedKey(), betaTtl.state() + "/" + betaTtl.ttlMillis(),
                    gamma.asQualifiedKey(), gammaTtl.state() + "/" + gammaTtl.ttlMillis());

            long removed = ExampleRuntimeSupport.await(manager.cache().cache().deleteMany(List.of(alpha, beta, gamma)));
                ExampleLogSupport.logData(log, "batch-delete result", "removed", removed);
        } catch (Exception ex) {
            log.error("CacheBatchAndTtlExample failed: {}", ex.getMessage());
            log.debug("CacheBatchAndTtlExample exception stack trace", ex);
            throw ex;
        } finally {
            log.info("Shutting down CacheBatchAndTtlExample");
            ExampleRuntimeSupport.shutdown(manager, pool, vertx, container);
            log.info("Shutdown complete");
        }
    }
}
