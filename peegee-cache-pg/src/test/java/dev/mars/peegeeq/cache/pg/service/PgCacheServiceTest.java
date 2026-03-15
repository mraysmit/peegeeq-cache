package dev.mars.peegeeq.cache.pg.service;

import dev.mars.peegeeq.cache.api.model.CacheKey;
import dev.mars.peegeeq.cache.api.model.CacheSetRequest;
import dev.mars.peegeeq.cache.api.model.CacheValue;
import dev.mars.peegeeq.cache.api.model.SetMode;
import dev.mars.peegeeq.cache.api.model.TouchResult;
import dev.mars.peegeeq.cache.api.model.TtlState;
import dev.mars.peegeeq.cache.pg.repository.PgCacheRepository;
import dev.mars.peegeeq.cache.pg.test.PgTestSupport;
import io.vertx.core.Vertx;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import io.vertx.sqlclient.Pool;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(VertxExtension.class)
class PgCacheServiceTest {

    private static final PgTestSupport pg = new PgTestSupport("pgcache-service-test");
    private static Pool pool;
    private static PgCacheService service;

    @BeforeAll
    static void startContainer(Vertx vertx) throws Exception {
        pg.start(vertx);
        pool = pg.createPool(vertx);
        service = new PgCacheService(new PgCacheRepository(pool));
    }

    @AfterAll
    static void stopContainer() throws Exception {
        if (pool != null) pool.close();
        pg.stop();
    }

    @Test
    void getSetDeleteRoundTrip(VertxTestContext ctx) {
        CacheKey key = new CacheKey("ns", "svc-roundtrip");
        CacheSetRequest setReq = new CacheSetRequest(key, CacheValue.ofString("v1"), null, SetMode.UPSERT, null, false);

        service.set(setReq)
                .compose(ignored -> service.get(key))
                .compose(entry -> {
                    assertTrue(entry.isPresent());
                    assertEquals("v1", entry.get().value().asString());
                    return service.delete(key);
                })
                .compose(ignored -> service.get(key))
                .onComplete(ctx.succeeding(entry -> ctx.verify(() -> {
                    assertTrue(entry.isEmpty());
                    ctx.completeNow();
                })));
    }

    @Test
    void getManySetManyAndDeleteManyWork(VertxTestContext ctx) {
        CacheKey k1 = new CacheKey("ns", "svc-many-1");
        CacheKey k2 = new CacheKey("ns", "svc-many-2");

        CacheSetRequest r1 = new CacheSetRequest(k1, CacheValue.ofString("a"), null, SetMode.UPSERT, null, false);
        CacheSetRequest r2 = new CacheSetRequest(k2, CacheValue.ofString("b"), null, SetMode.UPSERT, null, false);

        service.setMany(List.of(r1, r2))
                .compose(ignored -> service.getMany(List.of(k1, k2)))
                .compose(found -> {
                    assertEquals("a", found.get(k1).orElseThrow().value().asString());
                    assertEquals("b", found.get(k2).orElseThrow().value().asString());
                    return service.deleteMany(List.of(k1, k2));
                })
                .onComplete(ctx.succeeding(deleted -> ctx.verify(() -> {
                    assertEquals(2L, deleted);
                    ctx.completeNow();
                })));
    }

    @Test
    void existsReflectsLiveRows(VertxTestContext ctx) {
        CacheKey key = new CacheKey("ns", "svc-exists");
        CacheSetRequest setReq = new CacheSetRequest(key, CacheValue.ofString("x"), null, SetMode.UPSERT, null, false);

        service.exists(key)
                .compose(before -> {
                    assertFalse(before);
                    return service.set(setReq);
                })
                .compose(ignored -> service.exists(key))
                .onComplete(ctx.succeeding(after -> ctx.verify(() -> {
                    assertTrue(after);
                    ctx.completeNow();
                })));
    }

    @Test
    void ttlExpirePersistAndTouch(VertxTestContext ctx) {
        CacheKey key = new CacheKey("ns", "svc-ttl");
        CacheSetRequest setReq = new CacheSetRequest(key, CacheValue.ofString("ttl"), null, SetMode.UPSERT, null, false);

        service.set(setReq)
                .compose(ignored -> service.ttl(key))
                .compose(initial -> {
                    assertEquals(TtlState.PERSISTENT, initial.state());
                    return service.expire(key, Duration.ofSeconds(30));
                })
                .compose(expired -> {
                    assertTrue(expired);
                    return service.ttl(key);
                })
                .compose(afterExpire -> {
                    assertEquals(TtlState.EXPIRING, afterExpire.state());
                    assertNotNull(afterExpire.ttlMillis());
                    assertTrue(afterExpire.ttlMillis() > 0);
                    return service.persist(key);
                })
                .compose(persisted -> {
                    assertTrue(persisted);
                    return service.touch(key, Duration.ofSeconds(5));
                })
                .compose(touch -> {
                    assertTrue(touch.updated());
                    assertEquals(TtlState.EXPIRING, touch.ttl().state());
                    return service.ttl(key);
                })
                .onComplete(ctx.succeeding(finalTtl -> ctx.verify(() -> {
                    assertEquals(TtlState.EXPIRING, finalTtl.state());
                    assertNotNull(finalTtl.ttlMillis());
                    assertTrue(finalTtl.ttlMillis() > 0);
                    ctx.completeNow();
                })));
    }

    @Test
    void touchReturnsMissingForUnknownKey(VertxTestContext ctx) {
        CacheKey key = new CacheKey("ns", "svc-touch-missing");
        service.touch(key, Duration.ofSeconds(10))
                .onComplete(ctx.succeeding(result -> ctx.verify(() -> {
                    TouchResult touch = result;
                    assertFalse(touch.updated());
                    assertEquals(TtlState.KEY_MISSING, touch.ttl().state());
                    ctx.completeNow();
                })));
    }

    @Test
    void expireRejectsNonPositiveTtl(VertxTestContext ctx) {
        CacheKey key = new CacheKey("ns", "svc-expire-invalid");
        service.expire(key, Duration.ZERO)
                .onComplete(ctx.failing(err -> ctx.verify(() -> {
                    assertTrue(err instanceof IllegalArgumentException);
                    ctx.completeNow();
                })));
    }

    @Test
    void touchWithNullTtlPreservesPersistentState(VertxTestContext ctx) {
        CacheKey key = new CacheKey("ns", "svc-touch-null-ttl");
        CacheSetRequest setReq = new CacheSetRequest(key, CacheValue.ofString("v"), null, SetMode.UPSERT, null, false);

        service.set(setReq)
                .compose(ignored -> service.touch(key, null))
                .compose(touch -> {
                    assertTrue(touch.updated());
                    assertEquals(TtlState.PERSISTENT, touch.ttl().state());
                    assertNull(touch.ttl().ttlMillis());
                    return service.ttl(key);
                })
                .onComplete(ctx.succeeding(ttl -> ctx.verify(() -> {
                    assertEquals(TtlState.PERSISTENT, ttl.state());
                    assertNull(ttl.ttlMillis());
                    ctx.completeNow();
                })));
    }
}