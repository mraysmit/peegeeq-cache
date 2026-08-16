package dev.mars.peegeeq.cache.benchmark;

import dev.mars.peegeeq.cache.api.cache.CacheService;
import dev.mars.peegeeq.cache.api.model.CacheKey;
import dev.mars.peegeeq.cache.api.model.CacheSetRequest;
import dev.mars.peegeeq.cache.api.model.CacheValue;
import dev.mars.peegeeq.cache.api.model.SetMode;
import dev.mars.peegeeq.cache.core.telemetry.CacheTelemetry;
import dev.mars.peegeeq.cache.pg.config.PgCacheStoreConfig;
import dev.mars.peegeeq.cache.runtime.PeeGeeCacheManager;
import dev.mars.peegeeq.cache.runtime.bootstrap.PeeGeeCacheBootstrapOptions;
import dev.mars.peegeeq.cache.runtime.bootstrap.PeeGeeCaches;
import dev.mars.peegeeq.cache.runtime.config.PeeGeeCacheConfig;
import dev.mars.peegeeq.cache.test.LatencyHistogram;
import dev.mars.peegeeq.cache.test.PgTestSupport;
import dev.mars.peegeeq.cache.test.VertxAwait;
import io.vertx.core.Vertx;
import io.vertx.sqlclient.Pool;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertTrue;

class BenchmarkPoolHeadroomIntegrationTest {

    private static final String SCHEMA = "benchmark_pool_headroom";

    @Test
    void foregroundOperationsCompleteWithReservedPoolHeadroomWhileSweeperRuns() throws Exception {
        Vertx vertx = Vertx.vertx();
        PgTestSupport postgres = new PgTestSupport("benchmark-pool-headroom", SCHEMA);
        Pool pool = null;
        PeeGeeCacheManager manager = null;
        try {
            postgres.start(vertx);
            BenchmarkConfig config = new BenchmarkConfig(
                    4, 8, Duration.ofSeconds(1), 1,
                    Duration.ofSeconds(1), Duration.ofSeconds(1), Duration.ofSeconds(1), 100);
            pool = Pool.pool(vertx, postgres.connectOptions(),
                    CacheBenchmarkMain.foregroundPoolOptions(config));
            PeeGeeCacheConfig runtime = new PeeGeeCacheConfig(
                    null, Duration.ofMillis(10), 25, true);
            PeeGeeCacheBootstrapOptions options = new PeeGeeCacheBootstrapOptions(
                    runtime, new PgCacheStoreConfig(SCHEMA, SCHEMA), null, CacheTelemetry.noop());
            manager = VertxAwait.await(PeeGeeCaches.create(vertx, pool, options), Duration.ofSeconds(10));

            VertxAwait.await(pool.query("""
                    INSERT INTO benchmark_pool_headroom.cache_entries
                        (namespace, cache_key, value_type, value_bytes, expires_at)
                    SELECT 'expired', 'key-' || value, 'STRING', convert_to('v', 'UTF8'),
                           NOW() - INTERVAL '1 second'
                    FROM generate_series(1, 1000) AS series(value)
                    """).execute().mapEmpty(), Duration.ofSeconds(10));
            VertxAwait.await(manager.startReactive(), Duration.ofSeconds(10));

            CacheService cache = manager.cache().cache();
            AtomicLong sequence = new AtomicLong();
            LatencyHistogram.Snapshot result = CacheBenchmarkMain.runSustained(
                    "foreground-with-expiry-sweeper", config, () -> {
                        long id = sequence.incrementAndGet();
                        CacheKey key = new CacheKey("foreground", "key-" + (id % 100));
                        CacheSetRequest request = new CacheSetRequest(
                                key, CacheValue.ofString("value-" + id), Duration.ofMinutes(1),
                                SetMode.UPSERT, null, false);
                        return cache.set(request).compose(ignored -> cache.get(key)).mapEmpty();
                    });

            assertTrue(result.operations() >= config.concurrency());
            assertTrue(result.p99().compareTo(config.maximumP99()) <= 0);
        } finally {
            if (manager != null && manager.isStarted()) {
                VertxAwait.await(manager.stopReactive(), Duration.ofSeconds(10));
            }
            if (pool != null) {
                VertxAwait.await(pool.close(), Duration.ofSeconds(10));
            }
            postgres.stop();
            VertxAwait.await(vertx.close(), Duration.ofSeconds(10));
        }
    }
}
