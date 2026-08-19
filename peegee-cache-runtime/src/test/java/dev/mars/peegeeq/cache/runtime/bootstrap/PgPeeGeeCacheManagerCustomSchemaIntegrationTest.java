package dev.mars.peegeeq.cache.runtime.bootstrap;

import dev.mars.peegeeq.cache.api.model.CacheKey;
import dev.mars.peegeeq.cache.api.model.CacheSetRequest;
import dev.mars.peegeeq.cache.api.model.CacheValue;
import dev.mars.peegeeq.cache.api.model.LockAcquireRequest;
import dev.mars.peegeeq.cache.api.model.LockKey;
import dev.mars.peegeeq.cache.api.model.LockReleaseRequest;
import dev.mars.peegeeq.cache.api.model.SetMode;
import dev.mars.peegeeq.cache.pg.config.PgCacheStoreConfig;
import dev.mars.peegeeq.cache.runtime.PeeGeeCacheManager;
import dev.mars.peegeeq.cache.runtime.config.PeeGeeCacheConfig;
import io.vertx.core.Vertx;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import dev.mars.peegeeq.cache.test.PgTestSupport;
import io.vertx.sqlclient.Pool;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(VertxExtension.class)
@io.vertx.junit5.Timeout(value = 90, timeUnit = java.util.concurrent.TimeUnit.SECONDS)
class PgPeeGeeCacheManagerCustomSchemaIntegrationTest {

    private static final String CUSTOM_SCHEMA_NAME = "runtime_custom_schema";
    private static final PgTestSupport pg = new PgTestSupport("runtime-custom-schema-test", CUSTOM_SCHEMA_NAME);

    private static Pool pool;
    private static PeeGeeCacheManager manager;

    @BeforeAll
    static void setUp(Vertx vertx, VertxTestContext ctx) {
        pg.start(vertx)
                .compose(ignored -> {
                    pool = pg.createPool(vertx);
                    PeeGeeCacheBootstrapOptions options = new PeeGeeCacheBootstrapOptions(
                            PeeGeeCacheConfig.defaults(),
                            new PgCacheStoreConfig(CUSTOM_SCHEMA_NAME, CUSTOM_SCHEMA_NAME));
                    return PeeGeeCaches.create(vertx, pool, options)
                            .timeout(10, TimeUnit.SECONDS);
                })
                .compose(createdManager -> {
                    manager = createdManager;
                    return manager.startReactive().timeout(10, TimeUnit.SECONDS);
                })
                .onSuccess(ignored -> ctx.completeNow())
                .onFailure(ctx::failNow);
    }

    @AfterAll
    static void tearDown(Vertx vertx, VertxTestContext ctx) {
        (manager != null && manager.isStarted()
                ? manager.stopReactive()
                : io.vertx.core.Future.<Void>succeededFuture())
                .compose(ignored -> pool == null
                        ? pg.stop(vertx)
                        : pg.stopAfter(vertx, pool.close()))
                .timeout(10, TimeUnit.SECONDS)
                .onSuccess(ignored -> ctx.completeNow())
                .onFailure(ctx::failNow);
    }

    @Test
    void managerUsesConfiguredNonDefaultSchemaAcrossCacheCounterAndLock(VertxTestContext ctx) {
        CacheKey cacheKey = new CacheKey("ns", "k1");
        CacheSetRequest setRequest = new CacheSetRequest(
                cacheKey, CacheValue.ofString("value-1"), null, SetMode.UPSERT, null, false);

        manager.cache().cache().set(setRequest)
                .compose(setResult -> {
                    ctx.verify(() -> assertTrue(setResult.applied()));
                    return manager.cache().cache().get(cacheKey);
                })
                .compose(entry -> {
                    ctx.verify(() -> {
                        assertTrue(entry.isPresent());
                        assertEquals("value-1", entry.get().value().asString());
                    });
                    return manager.cache().counters().increment(new CacheKey("ns", "ctr"));
                })
                .compose(counterValue -> {
                    ctx.verify(() -> assertEquals(1L, counterValue));
                    LockKey lockKey = new LockKey("ns", "lock-1");
                    return manager.cache().locks().acquire(
                            new LockAcquireRequest(lockKey, "owner-1", Duration.ofSeconds(5), false, true));
                })
                .compose(acquireResult -> {
                    ctx.verify(() -> {
                        assertTrue(acquireResult.acquired());
                        assertNotNull(acquireResult.fencingToken());
                    });
                    LockKey lockKey = new LockKey("ns", "lock-1");
                    return manager.cache().locks().release(new LockReleaseRequest(lockKey, "owner-1"));
                })
                .onComplete(ctx.succeeding(released -> ctx.verify(() -> {
                    assertTrue(released);
                    ctx.completeNow();
                })));
    }

}
