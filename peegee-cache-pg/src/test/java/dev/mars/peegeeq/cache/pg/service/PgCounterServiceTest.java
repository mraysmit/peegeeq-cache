package dev.mars.peegeeq.cache.pg.service;

import dev.mars.peegeeq.cache.api.model.CacheKey;
import dev.mars.peegeeq.cache.api.model.CounterOptions;
import dev.mars.peegeeq.cache.api.model.TtlState;
import dev.mars.peegeeq.cache.pg.repository.PgCounterRepository;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(VertxExtension.class)
class PgCounterServiceTest {

    private static final PgTestSupport pg = new PgTestSupport("pgcounter-service-test");
    private static Pool pool;
    private static PgCounterService service;

    @BeforeAll
    static void startContainer(Vertx vertx) throws Exception {
        pg.start(vertx);
        pool = pg.createPool(vertx);
        service = new PgCounterService(new PgCounterRepository(pool));
    }

    @AfterAll
    static void stopContainer() throws Exception {
        if (pool != null) {
            pool.close();
        }
        pg.stop();
    }

    @Test
    void incrementAndDecrementWork(VertxTestContext ctx) {
        CacheKey key = new CacheKey("ns", "ctr-svc-inc-dec");
        service.increment(key)
                .compose(v -> service.incrementBy(key, 4))
                .compose(v -> service.decrement(key))
                .compose(v -> service.decrementBy(key, 2))
                .compose(v -> service.getValue(key))
                .onComplete(ctx.succeeding(value -> ctx.verify(() -> {
                    assertTrue(value.isPresent());
                    assertEquals(2L, value.orElseThrow());
                    ctx.completeNow();
                })));
    }

    @Test
    void setValueAndDeleteWork(VertxTestContext ctx) {
        CacheKey key = new CacheKey("ns", "ctr-svc-set-delete");
        service.setValue(key, 41L, CounterOptions.defaults())
                .compose(v -> service.increment(key))
                .compose(v -> service.delete(key))
                .compose(deleted -> {
                    assertTrue(deleted);
                    return service.getValue(key);
                })
                .onComplete(ctx.succeeding(value -> ctx.verify(() -> {
                    assertTrue(value.isEmpty());
                    ctx.completeNow();
                })));
    }

    @Test
    void ttlExpirePersistLifecycleWorks(VertxTestContext ctx) {
        CacheKey key = new CacheKey("ns", "ctr-svc-ttl");
        service.setValue(key, 1L, CounterOptions.defaults())
                .compose(v -> service.ttl(key))
                .compose(ttl -> {
                    assertEquals(TtlState.PERSISTENT, ttl.state());
                    return service.expire(key, Duration.ofSeconds(20));
                })
                .compose(updated -> {
                    assertTrue(updated);
                    return service.ttl(key);
                })
                .compose(ttl -> {
                    assertEquals(TtlState.EXPIRING, ttl.state());
                    assertNotNull(ttl.ttlMillis());
                    assertTrue(ttl.ttlMillis() > 0);
                    return service.persist(key);
                })
                .compose(persisted -> {
                    assertTrue(persisted);
                    return service.ttl(key);
                })
                .onComplete(ctx.succeeding(ttl -> ctx.verify(() -> {
                    assertEquals(TtlState.PERSISTENT, ttl.state());
                    ctx.completeNow();
                })));
    }

    @Test
    void expireRejectsNonPositiveTtl(VertxTestContext ctx) {
        CacheKey key = new CacheKey("ns", "ctr-svc-invalid-ttl");
        service.expire(key, Duration.ZERO)
                .onComplete(ctx.failing(err -> ctx.verify(() -> {
                    assertTrue(err instanceof IllegalArgumentException);
                    ctx.completeNow();
                })));
    }

    @Test
    void expireRejectsNullTtl(VertxTestContext ctx) {
        CacheKey key = new CacheKey("ns", "ctr-svc-expire-null");
        service.expire(key, null)
                .onComplete(ctx.failing(err -> ctx.verify(() -> {
                    assertTrue(err instanceof IllegalArgumentException);
                    assertEquals("ttl must be > 0", err.getMessage());
                    ctx.completeNow();
                })));
    }

    @Test
    void setValueRejectsNullOptions(VertxTestContext ctx) {
        CacheKey key = new CacheKey("ns", "ctr-svc-set-null-opts");
        service.setValue(key, 1L, null)
                .onComplete(ctx.failing(err -> ctx.verify(() -> {
                    assertTrue(err instanceof IllegalArgumentException);
                    assertEquals("options cannot be null", err.getMessage());
                    ctx.completeNow();
                })));
    }

    @Test
    void ttlReturnsMissingForUnknownCounter(VertxTestContext ctx) {
        CacheKey key = new CacheKey("ns", "ctr-svc-missing");
        service.ttl(key).onComplete(ctx.succeeding(ttl -> ctx.verify(() -> {
            assertEquals(TtlState.KEY_MISSING, ttl.state());
            assertFalse(ttl.ttlMillis() != null);
            ctx.completeNow();
        })));
    }
}
