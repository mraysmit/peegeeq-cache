package dev.mars.peegeeq.cache.examples;

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
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.Duration;
import java.util.List;

/**
 * Demonstrates batch operations and TTL lifecycle behavior.
 */
public final class CacheBatchAndTtlExample {

    private static final Logger log = LoggerFactory.getLogger(CacheBatchAndTtlExample.class);

    private CacheBatchAndTtlExample() {
    }
    public static void main(String[] args) throws Exception {
        Vertx vertx = Vertx.vertx();
        PostgreSQLContainer container = null;
        Pool pool = null;
        PeeGeeCacheManager manager = null;
        log.info("example.cache_batch_ttl.starting");

        try {
            container = ExampleRuntimeSupport.startContainer();
            ExampleRuntimeSupport.applyBootstrapSql(vertx, container);
            pool = ExampleRuntimeSupport.createPool(vertx, container);
            log.info("example.sql_pool.created");
            manager = ExampleRuntimeSupport.startDefaultManager(vertx, pool);
            log.info("example.cache_manager.ready");
            var cache = manager.cache();

            CacheKey alpha = new CacheKey("batch", "alpha");
            CacheKey beta = new CacheKey("batch", "beta");
            CacheKey gamma = new CacheKey("batch", "gamma");

            var requests = List.of(
                    new CacheSetRequest(alpha, CacheValue.ofString("A"), Duration.ofMinutes(5), SetMode.UPSERT, null, false),
                    new CacheSetRequest(beta, CacheValue.ofString("B"), Duration.ofSeconds(30), SetMode.UPSERT, null, false),
                    new CacheSetRequest(gamma, CacheValue.ofString("C"), null, SetMode.UPSERT, null, false)
            );
                ExampleLogSupport.logData(log, "seeding dataset", "keyCount", requests.size());
                log.atDebug().addKeyValue("key.count", requests.size()).log("example.cache_batch.set_many");
                ExampleLogSupport.timed(log, "cache.setMany", () ->
                    ExampleRuntimeSupport.await(cache.cache().setMany(requests)));

                var loaded = ExampleLogSupport.timed(log, "cache.getMany", () -> ExampleRuntimeSupport.await(cache.cache().getMany(List.of(alpha, beta, gamma))));
                ExampleLogSupport.logData(log, "batch-get dataset", "requested", requests.size(), "present", loaded.values().stream().filter(value -> value.isPresent()).count());

                ExampleLogSupport.timed(log, "cache.expire", () -> ExampleRuntimeSupport.await(cache.cache().expire(gamma, Duration.ofSeconds(20))));
                ExampleLogSupport.timed(log, "cache.touch", () -> ExampleRuntimeSupport.await(cache.cache().touch(beta, Duration.ofSeconds(45))));
                ExampleLogSupport.timed(log, "cache.persist", () -> ExampleRuntimeSupport.await(cache.cache().persist(alpha)));

                TtlResult alphaTtl = ExampleLogSupport.timed(log, "cache.ttl(alpha)", () -> ExampleRuntimeSupport.await(cache.cache().ttl(alpha)));
                TtlResult betaTtl = ExampleLogSupport.timed(log, "cache.ttl(beta)", () -> ExampleRuntimeSupport.await(cache.cache().ttl(beta)));
                TtlResult gammaTtl = ExampleLogSupport.timed(log, "cache.ttl(gamma)", () -> ExampleRuntimeSupport.await(cache.cache().ttl(gamma)));

                ExampleLogSupport.logData(log, "ttl states",
                    "alphaState", alphaTtl.state(),
                    "betaState", betaTtl.state(),
                    "gammaState", gammaTtl.state());

                long removed = ExampleLogSupport.timed(log, "cache.deleteMany", () -> ExampleRuntimeSupport.await(cache.cache().deleteMany(List.of(alpha, beta, gamma))));
                ExampleLogSupport.logData(log, "batch-delete result", "removed", removed);
        } catch (Exception ex) {
            log.atError().setCause(ex).log("example.cache_batch_ttl.failed");
            throw ex;
        } finally {
            log.info("example.cache_batch_ttl.stopping");
            ExampleRuntimeSupport.shutdown(manager, pool, vertx, container);
            log.info("example.cache_batch_ttl.stopped");
        }
    }
}
