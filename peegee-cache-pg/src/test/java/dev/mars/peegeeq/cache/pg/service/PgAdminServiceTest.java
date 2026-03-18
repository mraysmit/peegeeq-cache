package dev.mars.peegeeq.cache.pg.service;

import dev.mars.peegeeq.cache.api.model.CacheKey;
import dev.mars.peegeeq.cache.api.model.CacheSetRequest;
import dev.mars.peegeeq.cache.api.model.CacheValue;
import dev.mars.peegeeq.cache.api.model.EntryStats;
import dev.mars.peegeeq.cache.api.model.MetricsSnapshot;
import dev.mars.peegeeq.cache.api.model.SetMode;
import dev.mars.peegeeq.cache.core.metrics.CacheMetrics;
import dev.mars.peegeeq.cache.pg.repository.PgAdminRepository;
import dev.mars.peegeeq.cache.pg.repository.PgCacheRepository;
import dev.mars.peegeeq.cache.pg.test.PgTestSupport;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@ExtendWith(VertxExtension.class)
class PgAdminServiceTest {

    private static final String SCHEMA = "peegee_cache";
    private static final PgTestSupport pg = new PgTestSupport("pgadmin-svc-test", SCHEMA);
    private static Pool pool;
    private static PgAdminRepository adminRepo;
    private static PgCacheRepository cacheRepo;
    private CacheMetrics metrics;
    private PgAdminService service;

    @BeforeAll
    static void startContainer(Vertx vertx) throws Exception {
        pg.start(vertx);
        pool = pg.createPool(vertx);
        adminRepo = new PgAdminRepository(pool, SCHEMA);
        cacheRepo = new PgCacheRepository(pool, SCHEMA);
    }

    @AfterAll
    static void stopContainer() throws Exception {
        if (pool != null) pool.close();
        pg.stop();
    }

    @BeforeEach
    void cleanTables(VertxTestContext ctx) {
        metrics = new CacheMetrics();
        service = new PgAdminService(adminRepo, metrics);
        pool.query("DELETE FROM %s.cache_entries; DELETE FROM %s.cache_counters; DELETE FROM %s.cache_locks"
                        .formatted(SCHEMA, SCHEMA, SCHEMA))
                .execute()
                .onComplete(ctx.succeeding(v -> ctx.completeNow()));
    }

    @Test
    void entryStatsDelegatesToRepository(Vertx vertx, VertxTestContext ctx) {
        CacheKey key = new CacheKey("admin-ns", "k1");
        CacheSetRequest request = new CacheSetRequest(key, CacheValue.ofString("v1"), Duration.ofMinutes(5), SetMode.UPSERT, null, false);

        cacheRepo.set(request)
                .compose(ignored -> service.entryStats("admin-ns"))
                .onComplete(ctx.succeeding(stats -> ctx.verify(() -> {
                    assertEquals("admin-ns", stats.namespace());
                    assertEquals(1, stats.cacheEntryCount());
                    ctx.completeNow();
                })));
    }

    @Test
    void metricsReturnsSnapshotFromCacheMetrics(VertxTestContext ctx) {
        metrics.recordCacheGet(true);
        metrics.recordCacheGet(true);
        metrics.recordCacheGet(false);
        metrics.recordCacheSet(true);

        MetricsSnapshot snapshot = service.metrics();

        assertNotNull(snapshot);
        assertEquals(3, snapshot.cacheGets());
        assertEquals(2, snapshot.cacheHits());
        assertEquals(1, snapshot.cacheMisses());
        assertEquals(1, snapshot.cacheSets());
        ctx.completeNow();
    }

    @Test
    void metricsReturnsEmptySnapshotWhenNoOperations(VertxTestContext ctx) {
        MetricsSnapshot snapshot = service.metrics();

        assertEquals(MetricsSnapshot.empty(), snapshot);
        ctx.completeNow();
    }

    @Test
    void entryStatsReturnsZerosForEmptyNamespace(Vertx vertx, VertxTestContext ctx) {
        service.entryStats("empty-ns")
                .onComplete(ctx.succeeding(stats -> ctx.verify(() -> {
                    assertEquals("empty-ns", stats.namespace());
                    assertEquals(0, stats.cacheEntryCount());
                    assertEquals(0, stats.counterCount());
                    assertEquals(0, stats.activeLockCount());
                    ctx.completeNow();
                })));
    }
}
