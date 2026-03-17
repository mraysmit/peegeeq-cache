package dev.mars.peegeeq.cache.pg.repository;

import dev.mars.peegeeq.cache.api.model.LockAcquireRequest;
import dev.mars.peegeeq.cache.api.model.LockKey;
import dev.mars.peegeeq.cache.api.model.LockReleaseRequest;
import dev.mars.peegeeq.cache.api.model.LockRenewRequest;
import dev.mars.peegeeq.cache.api.model.LockState;
import dev.mars.peegeeq.cache.pg.bootstrap.BootstrapSqlRenderer;
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

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(VertxExtension.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class PgLockRepositoryTest {

    private static final PgTestSupport pg = new PgTestSupport("pgcache-lock-test", "peegee_cache");
    private static Pool pool;
    private static PgLockRepository repo;

    @BeforeAll
    static void startContainer(Vertx vertx) throws Exception {
        pg.start(vertx);
        pool = pg.createPool(vertx);
        repo = new PgLockRepository(pool, "peegee_cache");
    }

    @AfterAll
    static void stopContainer() throws Exception {
        if (pool != null) pool.close();
        pg.stop();
    }

    // --- ACQUIRE ---

    @Test
    @Order(1)
    void acquireSucceedsOnAbsentLock(VertxTestContext ctx) {
        LockKey key = new LockKey("ns", "free-lock");
        LockAcquireRequest req = new LockAcquireRequest(
                key, "owner-1", Duration.ofMinutes(5), false, false);

        repo.acquire(req).onComplete(ctx.succeeding(result -> ctx.verify(() -> {
            assertTrue(result.acquired());
            assertEquals(key, result.key());
            assertEquals("owner-1", result.ownerToken());
            assertNull(result.fencingToken(), "No fencing token requested");
            assertNotNull(result.leaseExpiresAt());
            ctx.completeNow();
        })));
    }

    @Test
    @Order(2)
    void acquireFailsWhenAlreadyHeld(VertxTestContext ctx) {
        LockKey key = new LockKey("ns", "held-lock");
        LockAcquireRequest req1 = new LockAcquireRequest(
                key, "owner-1", Duration.ofMinutes(5), false, false);
        LockAcquireRequest req2 = new LockAcquireRequest(
                key, "owner-2", Duration.ofMinutes(5), false, false);

        repo.acquire(req1)
                .compose(r -> repo.acquire(req2))
                .onComplete(ctx.succeeding(result -> ctx.verify(() -> {
                    assertFalse(result.acquired(), "Second acquire should fail");
                    ctx.completeNow();
                })));
    }

    @Test
    @Order(3)
    void acquireSucceedsAfterExpiredLease(VertxTestContext ctx) {
        LockKey key = new LockKey("ns", "expired-lock");
        LockAcquireRequest req = new LockAcquireRequest(
                key, "new-owner", Duration.ofMinutes(5), false, false);

        pool.query(
                        "INSERT INTO peegee_cache.cache_locks " +
                        "(namespace, lock_key, owner_token, lease_expires_at, updated_at) " +
                        "VALUES ('ns', 'expired-lock', 'old-owner', NOW() - INTERVAL '1 hour', NOW() - INTERVAL '2 hours') " +
                        "ON CONFLICT DO NOTHING")
                .execute()
                .compose(ignored -> repo.acquire(req))
                .onComplete(ctx.succeeding(result -> ctx.verify(() -> {
            assertTrue(result.acquired(), "Should acquire over expired lease");
            assertEquals("new-owner", result.ownerToken());
            ctx.completeNow();
        })));
    }

    @Test
    @Order(4)
    void acquireWithFencingTokenReturnsFencingToken(VertxTestContext ctx) {
        LockKey key = new LockKey("ns", "fenced-lock");
        LockAcquireRequest req = new LockAcquireRequest(
                key, "owner-f", Duration.ofMinutes(5), false, true);

        repo.acquire(req).onComplete(ctx.succeeding(result -> ctx.verify(() -> {
            assertTrue(result.acquired());
            assertNotNull(result.fencingToken(), "Fencing token should be issued");
            assertTrue(result.fencingToken() > 0);
            ctx.completeNow();
        })));
    }

    @Test
    @Order(5)
    void acquireFencingTokensAreMonotonic(VertxTestContext ctx) {
        LockKey key1 = new LockKey("ns", "fenced-mono-1");
        LockKey key2 = new LockKey("ns", "fenced-mono-2");
        LockAcquireRequest req1 = new LockAcquireRequest(
                key1, "o", Duration.ofMinutes(5), false, true);
        LockAcquireRequest req2 = new LockAcquireRequest(
                key2, "o", Duration.ofMinutes(5), false, true);

        repo.acquire(req1).compose(r1 ->
                repo.acquire(req2).map(r2 -> {
                    assertTrue(r2.fencingToken() > r1.fencingToken(),
                            "Fencing tokens must be monotonically increasing");
                    return r2;
                })
        ).onComplete(ctx.succeeding(r -> ctx.verify(ctx::completeNow)));
    }

    // --- REENTRANT ACQUIRE ---

    @Test
    @Order(10)
    void reentrantAcquireBySameOwner(VertxTestContext ctx) {
        LockKey key = new LockKey("ns", "reentrant-lock");
        LockAcquireRequest req = new LockAcquireRequest(
                key, "owner-re", Duration.ofMinutes(5), true, false);

        repo.acquire(req)
                .compose(r1 -> repo.acquire(req))
                .onComplete(ctx.succeeding(result -> ctx.verify(() -> {
                    assertTrue(result.acquired(), "Reentrant acquire by same owner should succeed");
                    assertEquals("owner-re", result.ownerToken());
                    ctx.completeNow();
                })));
    }

    @Test
    @Order(11)
    void reentrantAcquireByDifferentOwnerFails(VertxTestContext ctx) {
        LockKey key = new LockKey("ns", "reentrant-lock-2");
        LockAcquireRequest req1 = new LockAcquireRequest(
                key, "owner-a", Duration.ofMinutes(5), true, false);
        LockAcquireRequest req2 = new LockAcquireRequest(
                key, "owner-b", Duration.ofMinutes(5), true, false);

        repo.acquire(req1)
                .compose(r -> repo.acquire(req2))
                .onComplete(ctx.succeeding(result -> ctx.verify(() -> {
                    assertFalse(result.acquired(), "Different owner should not reentrant-acquire");
                    ctx.completeNow();
                })));
    }

    // --- RENEW ---

    @Test
    @Order(20)
    void renewExtendLeaseByOwner(VertxTestContext ctx) {
        LockKey key = new LockKey("ns", "renew-lock");
        LockAcquireRequest acq = new LockAcquireRequest(
                key, "owner-r", Duration.ofMinutes(1), false, false);
        LockRenewRequest renew = new LockRenewRequest(
                key, "owner-r", Duration.ofMinutes(10));

        repo.acquire(acq)
                .compose(r -> repo.renew(renew))
                .onComplete(ctx.succeeding(renewed -> ctx.verify(() -> {
                    assertTrue(renewed, "Owner should be able to renew");
                    ctx.completeNow();
                })));
    }

    @Test
    @Order(21)
    void renewByWrongOwnerFails(VertxTestContext ctx) {
        LockKey key = new LockKey("ns", "renew-wrong");
        LockAcquireRequest acq = new LockAcquireRequest(
                key, "owner-x", Duration.ofMinutes(5), false, false);
        LockRenewRequest renew = new LockRenewRequest(
                key, "wrong-owner", Duration.ofMinutes(10));

        repo.acquire(acq)
                .compose(r -> repo.renew(renew))
                .onComplete(ctx.succeeding(renewed -> ctx.verify(() -> {
                    assertFalse(renewed, "Wrong owner should not renew");
                    ctx.completeNow();
                })));
    }

    // --- RELEASE ---

    @Test
    @Order(30)
    void releaseByOwnerSucceeds(VertxTestContext ctx) {
        LockKey key = new LockKey("ns", "release-lock");
        LockAcquireRequest acq = new LockAcquireRequest(
                key, "owner-rel", Duration.ofMinutes(5), false, false);
        LockReleaseRequest rel = new LockReleaseRequest(key, "owner-rel");

        repo.acquire(acq)
                .compose(r -> repo.release(rel))
                .compose(released -> {
                    assertTrue(released);
                    return repo.currentLock(key);
                })
                .onComplete(ctx.succeeding(state -> ctx.verify(() -> {
                    assertTrue(state.isEmpty(), "Lock should be gone after release");
                    ctx.completeNow();
                })));
    }

    @Test
    @Order(31)
    void releaseByWrongOwnerFails(VertxTestContext ctx) {
        LockKey key = new LockKey("ns", "release-wrong");
        LockAcquireRequest acq = new LockAcquireRequest(
                key, "owner-own", Duration.ofMinutes(5), false, false);
        LockReleaseRequest rel = new LockReleaseRequest(key, "wrong-owner");

        repo.acquire(acq)
                .compose(r -> repo.release(rel))
                .onComplete(ctx.succeeding(released -> ctx.verify(() -> {
                    assertFalse(released, "Wrong owner should not release");
                    ctx.completeNow();
                })));
    }

    // --- CURRENT LOCK ---

    @Test
    @Order(40)
    void currentLockReturnsState(VertxTestContext ctx) {
        LockKey key = new LockKey("ns", "inspect-lock");
        LockAcquireRequest acq = new LockAcquireRequest(
                key, "owner-i", Duration.ofMinutes(5), false, true);

        repo.acquire(acq)
                .compose(r -> repo.currentLock(key))
                .onComplete(ctx.succeeding(state -> ctx.verify(() -> {
                    assertTrue(state.isPresent());
                    LockState s = state.get();
                    assertEquals(key, s.key());
                    assertEquals("owner-i", s.ownerToken());
                    assertNotNull(s.fencingToken());
                    assertNotNull(s.leaseExpiresAt());
                    ctx.completeNow();
                })));
    }

    @Test
    @Order(41)
    void currentLockReturnsEmptyForAbsent(VertxTestContext ctx) {
        LockKey key = new LockKey("ns", "no-lock");
        repo.currentLock(key).onComplete(ctx.succeeding(state -> ctx.verify(() -> {
            assertTrue(state.isEmpty());
            ctx.completeNow();
        })));
    }

    @Test
    @Order(50)
    void repositoryUsesConfiguredSchema(VertxTestContext ctx) {
        String schema = "custom_lock_repo";
        PgLockRepository customRepo = new PgLockRepository(pool, schema);
        LockKey key = new LockKey("ns", "custom-lock-key");
        LockAcquireRequest req = new LockAcquireRequest(
                key, "owner-custom", Duration.ofMinutes(5), false, true);

        applySchema(schema)
                .compose(v -> customRepo.acquire(req))
                .compose(acquired -> {
                    assertTrue(acquired.acquired());
                    return customRepo.currentLock(key);
                })
                .onComplete(ctx.succeeding(state -> ctx.verify(() -> {
                    assertTrue(state.isPresent());
                    assertEquals("owner-custom", state.get().ownerToken());
                    ctx.completeNow();
                })));
    }

    private static io.vertx.core.Future<Void> applySchema(String schemaName) {
        String sql = "DROP SCHEMA IF EXISTS " + schemaName + " CASCADE;\n"
                + BootstrapSqlRenderer.loadForSchema(schemaName);
        return pool.query(sql).execute().mapEmpty();
    }
}
