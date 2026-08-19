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
import io.vertx.core.AsyncResult;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import io.vertx.sqlclient.Pool;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(VertxExtension.class)
@io.vertx.junit5.Timeout(value = 90, timeUnit = java.util.concurrent.TimeUnit.SECONDS)
class BenchmarkPoolHeadroomIntegrationTest {

    private static final String SCHEMA = "benchmark_pool_headroom";

    @Test
    void foregroundOperationsCompleteWithReservedPoolHeadroomWhileSweeperRuns(
            Vertx vertx, VertxTestContext ctx) {
        PgTestSupport postgres = new PgTestSupport("benchmark-pool-headroom", SCHEMA);
        BenchmarkConfig config = new BenchmarkConfig(
                4, 8, Duration.ZERO, Duration.ofSeconds(1), 1,
                Duration.ofSeconds(1), Duration.ofSeconds(1), Duration.ofSeconds(1), 100);
        Pool[] pool = new Pool[1];
        PeeGeeCacheManager[] manager = new PeeGeeCacheManager[1];

        Future<LatencyHistogram.Snapshot> scenario = postgres.start(vertx)
                .compose(ignored -> {
                    pool[0] = Pool.pool(vertx, postgres.connectOptions(),
                            CacheBenchmarkMain.foregroundPoolOptions(config));
                    PeeGeeCacheConfig runtime = new PeeGeeCacheConfig(
                            null, Duration.ofMillis(10), 25, true);
                    PeeGeeCacheBootstrapOptions options = new PeeGeeCacheBootstrapOptions(
                            runtime, new PgCacheStoreConfig(SCHEMA, SCHEMA),
                            null, CacheTelemetry.noop());
                    return PeeGeeCaches.create(vertx, pool[0], options);
                })
                .compose(createdManager -> {
                    manager[0] = createdManager;
                    return pool[0].query("""
                            INSERT INTO benchmark_pool_headroom.cache_entries
                                (namespace, cache_key, value_type, value_bytes, expires_at)
                            SELECT 'expired', 'key-' || value, 'STRING', convert_to('v', 'UTF8'),
                                   NOW() - INTERVAL '1 second'
                            FROM generate_series(1, 1000) AS series(value)
                            """).execute();
                })
                .compose(ignored -> manager[0].startReactive())
                .compose(ignored -> {
                    CacheService cache = manager[0].cache().cache();
                    AtomicLong sequence = new AtomicLong();
                    Supplier<Future<Void>> foregroundOperation = () -> {
                        long id = sequence.incrementAndGet();
                        CacheKey key = new CacheKey("foreground", "key-" + (id % 100));
                        CacheSetRequest request = new CacheSetRequest(
                                key, CacheValue.ofString("value-" + id), Duration.ofMinutes(1),
                                SetMode.UPSERT, null, false);
                        return cache.set(request).compose(value -> cache.get(key)).mapEmpty();
                    };
                    List<Future<Void>> warmups = new ArrayList<>();
                    for (int worker = 0; worker < config.concurrency(); worker++) {
                        warmups.add(foregroundOperation.get());
                    }
                    return Future.all(warmups)
                            .compose(value -> CacheBenchmarkMain.runSustained(
                                    "foreground-with-expiry-sweeper", config,
                                    foregroundOperation));
                });

        scenario.transform(result -> cleanup(vertx, postgres, pool[0], manager[0])
                        .transform(cleanupResult -> completeAfterCleanup(result, cleanupResult)))
                .onSuccess(result -> ctx.verify(() -> {
                    assertTrue(result.operations() >= config.concurrency());
                    assertTrue(result.p99().compareTo(config.maximumP99()) <= 0,
                            () -> "p99=" + result.p99() + " exceeded " + config.maximumP99());
                    ctx.completeNow();
                }))
                .onFailure(ctx::failNow);
    }

    private static Future<Void> cleanup(
            Vertx vertx, PgTestSupport postgres, Pool pool, PeeGeeCacheManager manager) {
        Future<Void> managerStop = manager != null && manager.isStarted()
                ? manager.stopReactive()
                : Future.succeededFuture();
        return managerStop.compose(ignored -> pool == null
                ? postgres.stop(vertx)
                : postgres.stopAfter(vertx, pool.close()));
    }

    private static <T> Future<T> completeAfterCleanup(
            AsyncResult<T> result, AsyncResult<Void> cleanupResult) {
        if (result.succeeded() && cleanupResult.succeeded()) {
            return Future.succeededFuture(result.result());
        }
        if (result.failed()) {
            if (cleanupResult.failed() && cleanupResult.cause() != result.cause()) {
                result.cause().addSuppressed(cleanupResult.cause());
            }
            return Future.failedFuture(result.cause());
        }
        return Future.failedFuture(cleanupResult.cause());
    }
}
