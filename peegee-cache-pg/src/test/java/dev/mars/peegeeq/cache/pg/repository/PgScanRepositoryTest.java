package dev.mars.peegeeq.cache.pg.repository;

import dev.mars.peegeeq.cache.api.model.CacheKey;
import dev.mars.peegeeq.cache.api.model.CacheSetRequest;
import dev.mars.peegeeq.cache.api.model.CacheValue;
import dev.mars.peegeeq.cache.api.model.ScanRequest;
import dev.mars.peegeeq.cache.api.model.ScanResult;
import dev.mars.peegeeq.cache.api.model.SetMode;
import dev.mars.peegeeq.cache.pg.test.PgTestSupport;
import io.vertx.core.Future;
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
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(VertxExtension.class)
class PgScanRepositoryTest {

    private static final String SCHEMA = "peegee_cache";
    private static final PgTestSupport pg = new PgTestSupport("pgcache-scan-repo-test", SCHEMA);
    private static Pool pool;
    private static PgScanRepository scanRepo;
    private static PgCacheRepository cacheRepo;

    @BeforeAll
    static void startContainer(Vertx vertx) throws Exception {
        pg.start(vertx);
        pool = pg.createPool(vertx);
        scanRepo = new PgScanRepository(pool, SCHEMA);
        cacheRepo = new PgCacheRepository(pool, SCHEMA);
    }

    @AfterAll
    static void stopContainer() throws Exception {
        if (pool != null) pool.close();
        pg.stop();
    }

    @BeforeEach
    void cleanTable(VertxTestContext ctx) {
        pool.query("DELETE FROM " + SCHEMA + ".cache_entries")
                .execute()
                .onComplete(ctx.succeeding(v -> ctx.completeNow()));
    }

    // --- Basic scan ---

    @Test
    void scanReturnsEmptyWhenNoEntries(VertxTestContext ctx) {
        ScanRequest req = new ScanRequest("ns", null, null, 10, true, false);
        scanRepo.scan(req).onComplete(ctx.succeeding(result -> ctx.verify(() -> {
            assertNotNull(result);
            assertTrue(result.entries().isEmpty());
            assertFalse(result.hasMore());
            assertNull(result.nextCursor());
            ctx.completeNow();
        })));
    }

    @Test
    void scanReturnsEntriesInCurrentNamespace(VertxTestContext ctx) {
        Future<Void> setup = insertEntry("ns", "alpha", "v1")
                .compose(v -> insertEntry("ns", "beta", "v2"))
                .compose(v -> insertEntry("other-ns", "gamma", "v3"));

        setup.compose(v -> scanRepo.scan(new ScanRequest("ns", null, null, 10, true, false)))
                .onComplete(ctx.succeeding(result -> ctx.verify(() -> {
                    assertEquals(2, result.entries().size());
                    assertEquals("alpha", result.entries().get(0).key().key());
                    assertEquals("beta", result.entries().get(1).key().key());
                    assertFalse(result.hasMore());
                    ctx.completeNow();
                })));
    }

    // --- Prefix filtering ---

    @Test
    void scanFiltersByPrefix(VertxTestContext ctx) {
        Future<Void> setup = insertEntry("ns", "user:1", "a")
                .compose(v -> insertEntry("ns", "user:2", "b"))
                .compose(v -> insertEntry("ns", "session:1", "c"));

        setup.compose(v -> scanRepo.scan(new ScanRequest("ns", "user:", null, 10, true, false)))
                .onComplete(ctx.succeeding(result -> ctx.verify(() -> {
                    assertEquals(2, result.entries().size());
                    assertTrue(result.entries().stream().allMatch(e -> e.key().key().startsWith("user:")));
                    ctx.completeNow();
                })));
    }

    // --- Cursor pagination ---

    @Test
    void scanPaginatesWithCursor(VertxTestContext ctx) {
        Future<Void> setup = insertEntry("ns", "a", "1")
                .compose(v -> insertEntry("ns", "b", "2"))
                .compose(v -> insertEntry("ns", "c", "3"));

        setup.compose(v -> scanRepo.scan(new ScanRequest("ns", null, null, 2, true, false)))
                .compose(page1 -> {
                    assertEquals(2, page1.entries().size());
                    assertEquals("a", page1.entries().get(0).key().key());
                    assertEquals("b", page1.entries().get(1).key().key());
                    assertTrue(page1.hasMore());
                    assertNotNull(page1.nextCursor());

                    return scanRepo.scan(new ScanRequest("ns", null, page1.nextCursor(), 2, true, false));
                })
                .onComplete(ctx.succeeding(page2 -> ctx.verify(() -> {
                    assertEquals(1, page2.entries().size());
                    assertEquals("c", page2.entries().get(0).key().key());
                    assertFalse(page2.hasMore());
                    ctx.completeNow();
                })));
    }

    @Test
    void scanCursorWorksWithPrefix(VertxTestContext ctx) {
        Future<Void> setup = insertEntry("ns", "item:a", "1")
                .compose(v -> insertEntry("ns", "item:b", "2"))
                .compose(v -> insertEntry("ns", "item:c", "3"))
                .compose(v -> insertEntry("ns", "other:x", "4"));

        setup.compose(v -> scanRepo.scan(new ScanRequest("ns", "item:", null, 2, true, false)))
                .compose(page1 -> {
                    assertEquals(2, page1.entries().size());
                    assertTrue(page1.hasMore());

                    return scanRepo.scan(new ScanRequest("ns", "item:", page1.nextCursor(), 2, true, false));
                })
                .onComplete(ctx.succeeding(page2 -> ctx.verify(() -> {
                    assertEquals(1, page2.entries().size());
                    assertEquals("item:c", page2.entries().get(0).key().key());
                    assertFalse(page2.hasMore());
                    ctx.completeNow();
                })));
    }

    // --- Expired entries ---

    @Test
    void scanPaginatesCorrectlyWithLimitOne(VertxTestContext ctx) {
        Future<Void> setup = insertEntry("ns", "a", "1")
                .compose(v -> insertEntry("ns", "b", "2"))
                .compose(v -> insertEntry("ns", "c", "3"));

        setup.compose(v -> scanRepo.scan(new ScanRequest("ns", null, null, 1, true, false)))
                .compose(page1 -> {
                    assertEquals(1, page1.entries().size());
                    assertEquals("a", page1.entries().get(0).key().key());
                    assertTrue(page1.hasMore());
                    return scanRepo.scan(new ScanRequest("ns", null, page1.nextCursor(), 1, true, false));
                })
                .compose(page2 -> {
                    assertEquals(1, page2.entries().size());
                    assertEquals("b", page2.entries().get(0).key().key());
                    assertTrue(page2.hasMore());
                    return scanRepo.scan(new ScanRequest("ns", null, page2.nextCursor(), 1, true, false));
                })
                .onComplete(ctx.succeeding(page3 -> ctx.verify(() -> {
                    assertEquals(1, page3.entries().size());
                    assertEquals("c", page3.entries().get(0).key().key());
                    assertFalse(page3.hasMore());
                    ctx.completeNow();
                })));
    }

    @Test
    void scanWithEmptyPrefixMatchesAllKeys(VertxTestContext ctx) {
        Future<Void> setup = insertEntry("ns", "alpha", "1")
                .compose(v -> insertEntry("ns", "beta", "2"));

        setup.compose(v -> scanRepo.scan(new ScanRequest("ns", "", null, 10, true, false)))
                .onComplete(ctx.succeeding(result -> ctx.verify(() -> {
                    assertEquals(2, result.entries().size());
                    assertEquals("alpha", result.entries().get(0).key().key());
                    assertEquals("beta", result.entries().get(1).key().key());
                    ctx.completeNow();
                })));
    }

    @Test
    void scanExcludesExpiredByDefault(VertxTestContext ctx) {
        insertEntry("ns", "live", "alive")
                .compose(v -> insertExpiredEntry("ns", "dead", "gone"))
                .compose(v -> scanRepo.scan(new ScanRequest("ns", null, null, 10, true, false)))
                .onComplete(ctx.succeeding(result -> ctx.verify(() -> {
                    assertEquals(1, result.entries().size());
                    assertEquals("live", result.entries().get(0).key().key());
                    ctx.completeNow();
                })));
    }

    @Test
    void scanIncludesExpiredWhenRequested(VertxTestContext ctx) {
        insertEntry("ns", "live", "alive")
                .compose(v -> insertExpiredEntry("ns", "dead", "gone"))
                .compose(v -> scanRepo.scan(new ScanRequest("ns", null, null, 10, true, true)))
                .onComplete(ctx.succeeding(result -> ctx.verify(() -> {
                    assertEquals(2, result.entries().size());
                    ctx.completeNow();
                })));
    }

    // --- includeValues flag ---

    @Test
    void scanWithoutValuesReturnsNullValues(VertxTestContext ctx) {
        insertEntry("ns", "key1", "val1")
                .compose(v -> scanRepo.scan(new ScanRequest("ns", null, null, 10, false, false)))
                .onComplete(ctx.succeeding(result -> ctx.verify(() -> {
                    assertEquals(1, result.entries().size());
                    assertNull(result.entries().get(0).value());
                    assertEquals("key1", result.entries().get(0).key().key());
                    ctx.completeNow();
                })));
    }

    @Test
    void scanWithValuesReturnsValueData(VertxTestContext ctx) {
        insertEntry("ns", "key1", "val1")
                .compose(v -> scanRepo.scan(new ScanRequest("ns", null, null, 10, true, false)))
                .onComplete(ctx.succeeding(result -> ctx.verify(() -> {
                    assertEquals(1, result.entries().size());
                    assertNotNull(result.entries().get(0).value());
                    assertEquals("val1", result.entries().get(0).value().asString());
                    ctx.completeNow();
                })));
    }

    // --- Helpers ---

    private Future<Void> insertEntry(String namespace, String key, String value) {
        CacheSetRequest req = new CacheSetRequest(
                new CacheKey(namespace, key),
                CacheValue.ofString(value),
                null,
                SetMode.UPSERT,
                null,
                false
        );
        return cacheRepo.set(req).mapEmpty();
    }

    private Future<Void> insertExpiredEntry(String namespace, String key, String value) {
        String sql = "INSERT INTO " + SCHEMA + ".cache_entries " +
                "(namespace, cache_key, value_type, value_bytes, expires_at) " +
                "VALUES ($1, $2, 'STRING', $3, NOW() - INTERVAL '1 hour') " +
                "ON CONFLICT DO NOTHING";
        return pool.preparedQuery(sql)
                .execute(io.vertx.sqlclient.Tuple.of(namespace, key, io.vertx.core.buffer.Buffer.buffer(value)))
                .mapEmpty();
    }
}
