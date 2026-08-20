package dev.mars.peegeeq.cache.runtime.writebehind;

import dev.mars.peegeeq.cache.api.model.CacheKey;
import dev.mars.peegeeq.cache.api.model.CacheSetRequest;
import dev.mars.peegeeq.cache.api.model.CacheValue;
import dev.mars.peegeeq.cache.api.model.SetMode;
import dev.mars.peegeeq.cache.core.metrics.CacheMetrics;
import dev.mars.peegeeq.cache.core.writebehind.WriteBehindBuffer;
import dev.mars.peegeeq.cache.pg.repository.PgCacheRepository;
import dev.mars.peegeeq.cache.pg.service.PgCacheService;
import dev.mars.peegeeq.cache.pg.config.PgCacheStoreConfig;
import dev.mars.peegeeq.cache.runtime.bootstrap.PeeGeeCacheBootstrapOptions;
import dev.mars.peegeeq.cache.runtime.bootstrap.PeeGeeCaches;
import dev.mars.peegeeq.cache.runtime.config.PeeGeeCacheConfig;
import dev.mars.peegeeq.cache.runtime.config.WriteBehindConfig;
import dev.mars.peegeeq.cache.test.PgTestSupport;
import io.vertx.core.Vertx;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import io.vertx.sqlclient.Pool;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(VertxExtension.class)
@io.vertx.junit5.Timeout(value = 90, timeUnit = java.util.concurrent.TimeUnit.SECONDS)
class WriteBehindIntegrationTest {

    private static final String SCHEMA = "write_behind_integration";
    private static final PgTestSupport pg = new PgTestSupport("write-behind-integration", SCHEMA);

    private static Pool pool;
    private static PgCacheService databaseService;

    @BeforeAll
    static void start(Vertx vertx, VertxTestContext ctx) {
        pg.start(vertx)
                .onSuccess(ignored -> ctx.verify(() -> {
                    pool = pg.createPool(vertx);
                    databaseService = new PgCacheService(
                            new PgCacheRepository(pool, SCHEMA), new CacheMetrics());
                    ctx.completeNow();
                }))
                .onFailure(ctx::failNow);
    }

    @BeforeEach
    void reset(VertxTestContext ctx) {
        pg.resetDatabaseState(pool)
                .onSuccess(ignored -> ctx.completeNow())
                .onFailure(ctx::failNow);
    }

    @AfterAll
    static void stop(Vertx vertx, VertxTestContext ctx) {
        (pool == null ? pg.stop(vertx) : pg.stopAfter(vertx, pool.close()))
                .onSuccess(ignored -> ctx.completeNow())
                .onFailure(ctx::failNow);
    }

    @Test
    void bufferedSetFlushesWithTtlReducedByBufferDwellTime(Vertx vertx, VertxTestContext ctx) {
        AtomicLong nanoTime = new AtomicLong();
        WriteBehindBuffer buffer = new WriteBehindBuffer(100);
        WriteBehindCacheService service = new WriteBehindCacheService(databaseService, buffer, nanoTime::get);
        WriteBehindFlusher flusher = flusher(vertx, buffer, nanoTime);
        CacheKey key = new CacheKey("write-behind", "ttl");
        CacheSetRequest request = new CacheSetRequest(
                key, CacheValue.ofString("buffered"), Duration.ofSeconds(5),
                SetMode.UPSERT, null, false);

        service.set(request)
                .compose(accepted -> databaseService.get(key))
                .compose(beforeFlush -> {
                    ctx.verify(() -> assertTrue(beforeFlush.isEmpty(),
                            "PostgreSQL-only reads must not see an unflushed write"));
                    nanoTime.set(Duration.ofSeconds(2).toNanos());
                    return flusher.flush();
                })
                .compose(ignored -> databaseService.get(key))
                .compose(entry -> {
                    ctx.verify(() -> {
                        assertTrue(entry.isPresent());
                        assertEquals(CacheValue.ofString("buffered"), entry.orElseThrow().value());
                    });
                    return databaseService.ttl(key);
                })
                .onSuccess(ttl -> ctx.verify(() -> {
                    assertTrue(ttl.ttlMillis() > 2_000 && ttl.ttlMillis() <= 3_000,
                            "The persisted TTL must exclude the two-second buffer dwell time");
                    ctx.completeNow();
                }))
                .onFailure(ctx::failNow);
    }

    @Test
    void bufferedDeleteRemovesExistingRowAfterFlush(Vertx vertx, VertxTestContext ctx) {
        AtomicLong nanoTime = new AtomicLong();
        WriteBehindBuffer buffer = new WriteBehindBuffer(100);
        WriteBehindCacheService service = new WriteBehindCacheService(databaseService, buffer, nanoTime::get);
        WriteBehindFlusher flusher = flusher(vertx, buffer, nanoTime);
        CacheKey key = new CacheKey("write-behind", "delete");
        CacheSetRequest request = new CacheSetRequest(
                key, CacheValue.ofString("existing"), null, SetMode.UPSERT, null, false);

        databaseService.set(request)
                .compose(ignored -> service.delete(key))
                .compose(accepted -> databaseService.get(key))
                .compose(beforeFlush -> {
                    ctx.verify(() -> assertTrue(beforeFlush.isPresent(),
                            "PostgreSQL-only reads retain the row until delete flushes"));
                    return flusher.flush();
                })
                .compose(ignored -> databaseService.get(key))
                .onSuccess(afterFlush -> ctx.verify(() -> {
                    assertFalse(afterFlush.isPresent());
                    ctx.completeNow();
                }))
                .onFailure(ctx::failNow);
    }

    @Test
    void managerStopDrainsBufferedWrites(Vertx vertx, VertxTestContext ctx) {
        WriteBehindConfig writeBehind = new WriteBehindConfig(
                true, Duration.ofHours(1), 100, 10, 0, Duration.ofSeconds(5));
        PeeGeeCacheConfig runtime = new PeeGeeCacheConfig(
                null, Duration.ofSeconds(30), 500, false, writeBehind);
        PeeGeeCacheBootstrapOptions options = new PeeGeeCacheBootstrapOptions(
                runtime, new PgCacheStoreConfig(SCHEMA, SCHEMA));
        CacheKey key = new CacheKey("write-behind", "shutdown-drain");
        CacheSetRequest request = new CacheSetRequest(
                key, CacheValue.ofString("drained"), null, SetMode.UPSERT, null, false);

        PeeGeeCaches.create(vertx, pool, options)
                .compose(manager -> manager.startReactive().map(manager))
                .compose(manager -> manager.cache().cache().set(request).map(manager))
                .compose(manager -> databaseService.get(key).compose(beforeStop -> {
                    ctx.verify(() -> assertTrue(beforeStop.isEmpty()));
                    return manager.stopReactive();
                }))
                .compose(ignored -> databaseService.get(key))
                .onSuccess(afterStop -> ctx.verify(() -> {
                    assertTrue(afterStop.isPresent());
                    assertEquals(CacheValue.ofString("drained"), afterStop.orElseThrow().value());
                    ctx.completeNow();
                }))
                .onFailure(ctx::failNow);
    }

    private static WriteBehindFlusher flusher(
            Vertx vertx,
            WriteBehindBuffer buffer,
            AtomicLong nanoTime) {
        WriteBehindConfig config = new WriteBehindConfig(
                true, Duration.ofMillis(500), 100, 100, 0, Duration.ofSeconds(5));
        return new WriteBehindFlusher(
                vertx, databaseService, buffer, config, nanoTime::get, Duration.ZERO);
    }
}
