package dev.mars.peegeeq.cache.pg.service;

import dev.mars.peegeeq.cache.api.model.CacheKey;
import dev.mars.peegeeq.cache.api.model.CacheSetRequest;
import dev.mars.peegeeq.cache.api.model.CacheValue;
import dev.mars.peegeeq.cache.api.model.ScanRequest;
import dev.mars.peegeeq.cache.api.model.SetMode;
import dev.mars.peegeeq.cache.pg.repository.PgCacheRepository;
import dev.mars.peegeeq.cache.pg.repository.PgScanRepository;
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

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(VertxExtension.class)
class PgScanServiceTest {

    private static final String SCHEMA = "peegee_cache";
    private static final PgTestSupport pg = new PgTestSupport("pgcache-scan-service-test", SCHEMA);
    private static Pool pool;
    private static PgScanService scanService;
    private static PgCacheRepository cacheRepo;

    @BeforeAll
    static void startContainer(Vertx vertx) throws Exception {
        pg.start(vertx);
        pool = pg.createPool(vertx);
        scanService = new PgScanService(new PgScanRepository(pool, SCHEMA));
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

    @Test
    void scanValidatesNullNamespace(VertxTestContext ctx) {
        ScanRequest req = new ScanRequest(null, null, null, 10, true, false);
        scanService.scan(req).onComplete(ctx.failing(err -> ctx.verify(() -> {
            assertInstanceOf(IllegalArgumentException.class, err);
            ctx.completeNow();
        })));
    }

    @Test
    void scanValidatesBlankNamespace(VertxTestContext ctx) {
        ScanRequest req = new ScanRequest("  ", null, null, 10, true, false);
        scanService.scan(req).onComplete(ctx.failing(err -> ctx.verify(() -> {
            assertInstanceOf(IllegalArgumentException.class, err);
            ctx.completeNow();
        })));
    }

    @Test
    void scanValidatesNonPositiveLimit(VertxTestContext ctx) {
        ScanRequest req = new ScanRequest("ns", null, null, 0, true, false);
        scanService.scan(req).onComplete(ctx.failing(err -> ctx.verify(() -> {
            assertInstanceOf(IllegalArgumentException.class, err);
            ctx.completeNow();
        })));
    }

    @Test
    void scanValidatesLimitExceedingMax(VertxTestContext ctx) {
        ScanRequest req = new ScanRequest("ns", null, null, PgScanRepository.MAX_LIMIT + 1, true, false);
        scanService.scan(req).onComplete(ctx.failing(err -> ctx.verify(() -> {
            assertInstanceOf(IllegalArgumentException.class, err);
            assertTrue(err.getMessage().contains(String.valueOf(PgScanRepository.MAX_LIMIT)));
            ctx.completeNow();
        })));
    }

    @Test
    void scanDelegatesToRepositoryAndReturnsResult(VertxTestContext ctx) {
        insertEntry("ns", "k1", "v1")
                .compose(v -> insertEntry("ns", "k2", "v2"))
                .compose(v -> scanService.scan(new ScanRequest("ns", null, null, 10, true, false)))
                .onComplete(ctx.succeeding(result -> ctx.verify(() -> {
                    assertEquals(2, result.entries().size());
                    assertFalse(result.hasMore());
                    ctx.completeNow();
                })));
    }

    @Test
    void scanWrapsStoreFailureAsCacheStoreException(VertxTestContext ctx) {
        // Use a broken pool to force a store-level error
        PgScanRepository brokenRepo = new PgScanRepository(pool, "nonexistent_schema");
        PgScanService brokenService = new PgScanService(brokenRepo);

        brokenService.scan(new ScanRequest("ns", null, null, 10, true, false))
                .onComplete(ctx.failing(err -> ctx.verify(() -> {
                    assertInstanceOf(
                            dev.mars.peegeeq.cache.api.exception.CacheStoreException.class, err);
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
}
