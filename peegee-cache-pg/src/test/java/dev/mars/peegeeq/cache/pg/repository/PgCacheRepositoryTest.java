package dev.mars.peegeeq.cache.pg.repository;

import dev.mars.peegeeq.cache.api.model.CacheEntry;
import dev.mars.peegeeq.cache.api.model.CacheKey;
import dev.mars.peegeeq.cache.api.model.CacheSetRequest;
import dev.mars.peegeeq.cache.api.model.CacheSetResult;
import dev.mars.peegeeq.cache.api.model.CacheValue;
import dev.mars.peegeeq.cache.api.model.SetMode;
import dev.mars.peegeeq.cache.pg.test.PgTestSupport;
import io.vertx.core.Vertx;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import io.vertx.sqlclient.Pool;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.extension.ExtendWith;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(VertxExtension.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class PgCacheRepositoryTest {

    private static final PgTestSupport pg = new PgTestSupport("pgcache-repo-test");
    private static Pool pool;
    private static PgCacheRepository repo;

    @BeforeAll
    static void startContainer(Vertx vertx) throws Exception {
        pg.start(vertx);
        pool = pg.createPool(vertx);
        repo = new PgCacheRepository(pool);
    }

    @AfterAll
    static void stopContainer() throws Exception {
        if (pool != null) pool.close();
        pg.stop();
    }

    // --- GET ---

    @Test
    @Order(1)
    void getReturnsEmptyForMissingKey(VertxTestContext ctx) {
        CacheKey key = new CacheKey("ns", "no-such-key");
        repo.get(key).onComplete(ctx.succeeding(result -> ctx.verify(() -> {
            assertTrue(result.isEmpty());
            ctx.completeNow();
        })));
    }

    @Test
    @Order(2)
    void getReturnsValueForLiveKey(VertxTestContext ctx) {
        CacheKey key = new CacheKey("ns", "live-key");
        CacheValue value = CacheValue.ofString("hello");
        CacheSetRequest req = new CacheSetRequest(key, value, null, SetMode.UPSERT, null, false);

        repo.set(req).compose(r -> repo.get(key))
                .onComplete(ctx.succeeding(result -> ctx.verify(() -> {
                    assertTrue(result.isPresent());
                    CacheEntry entry = result.get();
                    assertEquals("hello", entry.value().asString());
                    assertEquals(1L, entry.version());
                    ctx.completeNow();
                })));
    }

    @Test
    @Order(3)
    void getReturnsEmptyForExpiredKey(VertxTestContext ctx) {
        CacheKey key = new CacheKey("ns", "expired-key");
        pool.query(
                        "INSERT INTO peegee_cache.cache_entries " +
                        "(namespace, cache_key, value_type, value_bytes, expires_at) " +
                        "VALUES ('ns', 'expired-key', 'STRING', 'old'::bytea, NOW() - INTERVAL '1 hour') " +
                        "ON CONFLICT DO NOTHING")
                .execute()
                .compose(ignored -> repo.get(key))
                .onComplete(ctx.succeeding(result -> ctx.verify(() -> {
            assertTrue(result.isEmpty(), "Expired key should be treated as absent");
            ctx.completeNow();
        })));
    }

    // --- SET ---

    @Test
    @Order(10)
    void setInsertsNewEntry(VertxTestContext ctx) {
        CacheKey key = new CacheKey("ns", "new-set-key");
        CacheValue value = CacheValue.ofString("world");
        CacheSetRequest req = new CacheSetRequest(key, value, null, SetMode.UPSERT, null, false);

        repo.set(req).onComplete(ctx.succeeding(result -> ctx.verify(() -> {
            assertTrue(result.applied());
            assertEquals(1L, result.newVersion());
            ctx.completeNow();
        })));
    }

    @Test
    @Order(11)
    void setAlwaysOverwritesExisting(VertxTestContext ctx) {
        CacheKey key = new CacheKey("ns", "overwrite-key");
        CacheValue v1 = CacheValue.ofString("first");
        CacheValue v2 = CacheValue.ofString("second");
        CacheSetRequest req1 = new CacheSetRequest(key, v1, null, SetMode.UPSERT, null, false);
        CacheSetRequest req2 = new CacheSetRequest(key, v2, null, SetMode.UPSERT, null, false);

        repo.set(req1)
                .compose(r -> repo.set(req2))
                .compose(r -> {
                    assertEquals(2L, r.newVersion());
                    return repo.get(key);
                })
                .onComplete(ctx.succeeding(result -> ctx.verify(() -> {
                    assertTrue(result.isPresent());
                    assertEquals("second", result.get().value().asString());
                    ctx.completeNow();
                })));
    }

    @Test
    @Order(12)
    void setIfAbsentSkipsExistingLiveKey(VertxTestContext ctx) {
        CacheKey key = new CacheKey("ns", "nx-existing");
        CacheValue v1 = CacheValue.ofString("original");
        CacheValue v2 = CacheValue.ofString("should-not-overwrite");
        CacheSetRequest req1 = new CacheSetRequest(key, v1, null, SetMode.UPSERT, null, false);
        CacheSetRequest req2 = new CacheSetRequest(key, v2, null, SetMode.ONLY_IF_ABSENT, null, false);

        repo.set(req1)
                .compose(r -> repo.set(req2))
                .compose(r -> {
                    assertFalse(r.applied(), "ONLY_IF_ABSENT should not overwrite live key");
                    return repo.get(key);
                })
                .onComplete(ctx.succeeding(result -> ctx.verify(() -> {
                    assertEquals("original", result.get().value().asString());
                    ctx.completeNow();
                })));
    }

    @Test
    @Order(13)
    void setIfAbsentSucceedsOverExpiredKey(VertxTestContext ctx) {
        CacheKey key = new CacheKey("ns", "nx-expired");
        CacheValue value = CacheValue.ofString("fresh");
        CacheSetRequest req = new CacheSetRequest(key, value, null, SetMode.ONLY_IF_ABSENT, null, false);

        pool.query(
                        "INSERT INTO peegee_cache.cache_entries " +
                        "(namespace, cache_key, value_type, value_bytes, expires_at) " +
                        "VALUES ('ns', 'nx-expired', 'STRING', 'old'::bytea, NOW() - INTERVAL '1 hour') " +
                        "ON CONFLICT DO NOTHING")
                .execute()
                .compose(ignored -> repo.set(req))
                .onComplete(ctx.succeeding(result -> ctx.verify(() -> {
            assertTrue(result.applied(), "ONLY_IF_ABSENT should succeed over expired key");
            ctx.completeNow();
        })));
    }

    @Test
    @Order(14)
    void setIfExistsSkipsMissingKey(VertxTestContext ctx) {
        CacheKey key = new CacheKey("ns", "xx-missing");
        CacheValue value = CacheValue.ofString("nope");
        CacheSetRequest req = new CacheSetRequest(key, value, null, SetMode.ONLY_IF_PRESENT, null, false);

        repo.set(req).onComplete(ctx.succeeding(result -> ctx.verify(() -> {
            assertFalse(result.applied(), "ONLY_IF_PRESENT should not create missing key");
            ctx.completeNow();
        })));
    }

    @Test
    @Order(15)
    void setWithReturnPreviousValue(VertxTestContext ctx) {
        CacheKey key = new CacheKey("ns", "prev-value");
        CacheValue v1 = CacheValue.ofString("old-val");
        CacheValue v2 = CacheValue.ofString("new-val");
        CacheSetRequest req1 = new CacheSetRequest(key, v1, null, SetMode.UPSERT, null, false);
        CacheSetRequest req2 = new CacheSetRequest(key, v2, null, SetMode.UPSERT, null, true);

        repo.set(req1)
                .compose(r -> repo.set(req2))
                .onComplete(ctx.succeeding(result -> ctx.verify(() -> {
                    assertTrue(result.applied());
                    assertNotNull(result.previousEntry());
                    assertEquals("old-val", result.previousEntry().value().asString());
                    ctx.completeNow();
                })));
    }

    // --- DELETE ---

    @Test
    @Order(20)
    void deleteRemovesEntry(VertxTestContext ctx) {
        CacheKey key = new CacheKey("ns", "to-delete");
        CacheValue value = CacheValue.ofString("gone");
        CacheSetRequest req = new CacheSetRequest(key, value, null, SetMode.UPSERT, null, false);

        repo.set(req)
                .compose(r -> repo.delete(key))
                .compose(deleted -> {
                    assertTrue(deleted);
                    return repo.get(key);
                })
                .onComplete(ctx.succeeding(result -> ctx.verify(() -> {
                    assertTrue(result.isEmpty());
                    ctx.completeNow();
                })));
    }

    @Test
    @Order(21)
    void deleteOnMissingKeyReturnsFalse(VertxTestContext ctx) {
        CacheKey key = new CacheKey("ns", "never-existed");
        repo.delete(key).onComplete(ctx.succeeding(deleted -> ctx.verify(() -> {
            assertFalse(deleted);
            ctx.completeNow();
        })));
    }
}
