package dev.mars.peegeeq.cache.pg.repository;

import dev.mars.peegeeq.cache.api.model.CacheKey;
import dev.mars.peegeeq.cache.api.model.CacheSetRequest;
import dev.mars.peegeeq.cache.api.model.CacheValue;
import dev.mars.peegeeq.cache.api.model.CounterOptions;
import dev.mars.peegeeq.cache.api.model.EntryStats;
import dev.mars.peegeeq.cache.api.model.LockAcquireRequest;
import dev.mars.peegeeq.cache.api.model.LockKey;
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

import static org.junit.jupiter.api.Assertions.assertEquals;

@ExtendWith(VertxExtension.class)
class PgAdminRepositoryTest {

    private static final String SCHEMA = "peegee_cache";
    private static final PgTestSupport pg = new PgTestSupport("pgadmin-repo-test", SCHEMA);
    private static Pool pool;
    private static PgAdminRepository adminRepo;
    private static PgCacheRepository cacheRepo;
    private static PgCounterRepository counterRepo;
    private static PgLockRepository lockRepo;

    @BeforeAll
    static void startContainer(Vertx vertx) throws Exception {
        pg.start(vertx);
        pool = pg.createPool(vertx);
        adminRepo = new PgAdminRepository(pool, SCHEMA);
        cacheRepo = new PgCacheRepository(pool, SCHEMA);
        counterRepo = new PgCounterRepository(pool, SCHEMA);
        lockRepo = new PgLockRepository(pool, SCHEMA);
    }

    @AfterAll
    static void stopContainer() throws Exception {
        if (pool != null) pool.close();
        pg.stop();
    }

    @BeforeEach
    void cleanTables(VertxTestContext ctx) {
        pool.query("DELETE FROM %s.cache_entries; DELETE FROM %s.cache_counters; DELETE FROM %s.cache_locks"
                        .formatted(SCHEMA, SCHEMA, SCHEMA))
                .execute()
                .onComplete(ctx.succeeding(v -> ctx.completeNow()));
    }

    @Test
    void entryStatsReturnsZeroesForEmptyNamespace(VertxTestContext ctx) {
        adminRepo.entryStats("empty-ns")
                .onComplete(ctx.succeeding(stats -> ctx.verify(() -> {
                    assertEquals("empty-ns", stats.namespace());
                    assertEquals(0, stats.cacheEntryCount());
                    assertEquals(0, stats.counterCount());
                    assertEquals(0, stats.activeLockCount());
                    ctx.completeNow();
                })));
    }

    @Test
    void entryStatsCountsCacheEntries(VertxTestContext ctx) {
        CacheKey k1 = new CacheKey("ns", "admin-entry-1");
        CacheKey k2 = new CacheKey("ns", "admin-entry-2");
        CacheKey k3 = new CacheKey("other", "admin-entry-3");

        insertEntry(k1).compose(v -> insertEntry(k2))
                .compose(v -> insertEntry(k3))
                .compose(v -> adminRepo.entryStats("ns"))
                .onComplete(ctx.succeeding(stats -> ctx.verify(() -> {
                    assertEquals(2, stats.cacheEntryCount());
                    assertEquals(0, stats.counterCount());
                    assertEquals(0, stats.activeLockCount());
                    ctx.completeNow();
                })));
    }

    @Test
    void entryStatsCountsCounters(VertxTestContext ctx) {
        CacheKey k1 = new CacheKey("ns", "admin-ctr-1");
        CacheKey k2 = new CacheKey("ns", "admin-ctr-2");

        counterRepo.increment(k1, 1, CounterOptions.defaults())
                .compose(v -> counterRepo.increment(k2, 5, CounterOptions.defaults()))
                .compose(v -> adminRepo.entryStats("ns"))
                .onComplete(ctx.succeeding(stats -> ctx.verify(() -> {
                    assertEquals(0, stats.cacheEntryCount());
                    assertEquals(2, stats.counterCount());
                    assertEquals(0, stats.activeLockCount());
                    ctx.completeNow();
                })));
    }

    @Test
    void entryStatsCountsActiveLocks(VertxTestContext ctx) {
        LockKey k1 = new LockKey("ns", "admin-lock-1");
        LockKey k2 = new LockKey("ns", "admin-lock-2");
        LockAcquireRequest r1 = new LockAcquireRequest(k1, "owner-a", Duration.ofMinutes(5), false, false);
        LockAcquireRequest r2 = new LockAcquireRequest(k2, "owner-b", Duration.ofMinutes(5), false, false);

        lockRepo.acquire(r1).compose(v -> lockRepo.acquire(r2))
                .compose(v -> adminRepo.entryStats("ns"))
                .onComplete(ctx.succeeding(stats -> ctx.verify(() -> {
                    assertEquals(0, stats.cacheEntryCount());
                    assertEquals(0, stats.counterCount());
                    assertEquals(2, stats.activeLockCount());
                    ctx.completeNow();
                })));
    }

    @Test
    void entryStatsExcludesExpiredCacheEntries(VertxTestContext ctx) {
        CacheKey live = new CacheKey("ns", "admin-live");
        CacheKey expired = new CacheKey("ns", "admin-expired");
        CacheSetRequest liveReq = new CacheSetRequest(live, CacheValue.ofString("v"), null, SetMode.UPSERT, null, false);
        CacheSetRequest expiredReq = new CacheSetRequest(expired, CacheValue.ofString("v"), Duration.ofMillis(1), SetMode.UPSERT, null, false);

        cacheRepo.set(liveReq).compose(v -> cacheRepo.set(expiredReq))
                .compose(v -> {
                    // small delay to let the 1ms TTL expire
                    return pool.query("SELECT pg_sleep(0.01)").execute().mapEmpty();
                })
                .compose(v -> adminRepo.entryStats("ns"))
                .onComplete(ctx.succeeding(stats -> ctx.verify(() -> {
                    assertEquals(1, stats.cacheEntryCount());
                    ctx.completeNow();
                })));
    }

    @Test
    void entryStatsCountsAcrossAllTypes(VertxTestContext ctx) {
        CacheKey entry = new CacheKey("mixed", "admin-entry");
        CacheKey counter = new CacheKey("mixed", "admin-counter");
        LockKey lock = new LockKey("mixed", "admin-lock");

        insertEntry(entry)
                .compose(v -> counterRepo.increment(counter, 1, CounterOptions.defaults()))
                .compose(v -> lockRepo.acquire(new LockAcquireRequest(lock, "owner", Duration.ofMinutes(5), false, false)))
                .compose(v -> adminRepo.entryStats("mixed"))
                .onComplete(ctx.succeeding(stats -> ctx.verify(() -> {
                    assertEquals("mixed", stats.namespace());
                    assertEquals(1, stats.cacheEntryCount());
                    assertEquals(1, stats.counterCount());
                    assertEquals(1, stats.activeLockCount());
                    ctx.completeNow();
                })));
    }

    private Future<Void> insertEntry(CacheKey key) {
        CacheSetRequest req = new CacheSetRequest(key, CacheValue.ofString("value"), null, SetMode.UPSERT, null, false);
        return cacheRepo.set(req).mapEmpty();
    }
}
