package dev.mars.peegeeq.cache.pg.service;

import dev.mars.peegeeq.cache.api.exception.LockNotHeldException;
import dev.mars.peegeeq.cache.api.model.LockAcquireRequest;
import dev.mars.peegeeq.cache.api.model.LockKey;
import dev.mars.peegeeq.cache.api.model.LockReleaseRequest;
import dev.mars.peegeeq.cache.api.model.LockRenewRequest;
import dev.mars.peegeeq.cache.pg.repository.PgLockRepository;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(VertxExtension.class)
class PgLockServiceTest {

    private static final PgTestSupport pg = new PgTestSupport("pglock-service-test");
    private static Pool pool;
    private static PgLockService service;

    @BeforeAll
    static void startContainer(Vertx vertx) throws Exception {
        pg.start();
        pool = pg.createPool(vertx);
        service = new PgLockService(new PgLockRepository(pool));
    }

    @AfterAll
    static void stopContainer() throws Exception {
        if (pool != null) {
            pool.close();
        }
        pg.stop();
    }

    @Test
    void acquireAndOwnerChecksWork(VertxTestContext ctx) {
        LockKey key = new LockKey("ns", "lock-svc-owner");
        LockAcquireRequest acquire = new LockAcquireRequest(key, "owner-a", Duration.ofMinutes(5), false, true);

        service.acquire(acquire)
                .compose(result -> {
                    assertTrue(result.acquired());
                    return service.isHeldBy(key, "owner-a");
                })
                .compose(held -> {
                    assertTrue(held);
                    return service.isHeldBy(key, "owner-b");
                })
                .onComplete(ctx.succeeding(heldByOther -> ctx.verify(() -> {
                    assertFalse(heldByOther);
                    ctx.completeNow();
                })));
    }

    @Test
    void renewByWrongOwnerFailsWithLockNotHeldException(VertxTestContext ctx) {
        LockKey key = new LockKey("ns", "lock-svc-renew-fail");
        LockAcquireRequest acquire = new LockAcquireRequest(key, "owner-a", Duration.ofMinutes(5), false, false);
        LockRenewRequest renewWrong = new LockRenewRequest(key, "owner-b", Duration.ofMinutes(5));

        service.acquire(acquire)
                .compose(ignored -> service.renew(renewWrong))
                .onComplete(ctx.failing(err -> ctx.verify(() -> {
                    assertTrue(err instanceof LockNotHeldException);
                    assertEquals("Lock is not held by owner for renew", err.getMessage());
                    ctx.completeNow();
                })));
    }

    @Test
    void releaseByWrongOwnerFailsWithLockNotHeldException(VertxTestContext ctx) {
        LockKey key = new LockKey("ns", "lock-svc-release-fail");
        LockAcquireRequest acquire = new LockAcquireRequest(key, "owner-a", Duration.ofMinutes(5), false, false);
        LockReleaseRequest releaseWrong = new LockReleaseRequest(key, "owner-b");

        service.acquire(acquire)
                .compose(ignored -> service.release(releaseWrong))
                .onComplete(ctx.failing(err -> ctx.verify(() -> {
                    assertTrue(err instanceof LockNotHeldException);
                    assertEquals("Lock is not held by owner for release", err.getMessage());
                    ctx.completeNow();
                })));
    }

    @Test
    void currentLockIsEmptyAfterReleaseByOwner(VertxTestContext ctx) {
        LockKey key = new LockKey("ns", "lock-svc-release-ok");
        LockAcquireRequest acquire = new LockAcquireRequest(key, "owner-a", Duration.ofMinutes(5), false, false);
        LockReleaseRequest release = new LockReleaseRequest(key, "owner-a");

        service.acquire(acquire)
                .compose(ignored -> service.release(release))
                .compose(ignored -> service.currentLock(key))
                .onComplete(ctx.succeeding(state -> ctx.verify(() -> {
                    assertTrue(state.isEmpty());
                    ctx.completeNow();
                })));
    }
}
