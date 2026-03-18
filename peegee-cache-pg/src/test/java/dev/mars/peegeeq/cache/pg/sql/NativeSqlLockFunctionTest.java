package dev.mars.peegeeq.cache.pg.sql;

import dev.mars.peegeeq.cache.pg.test.PgTestSupport;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.Tuple;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for peegee_cache lock SQL functions.
 * These verify the native SQL contract for external callers.
 */
@ExtendWith(VertxExtension.class)
class NativeSqlLockFunctionTest {

    private static final String SCHEMA = "peegee_cache";
    private static final PgTestSupport pg = new PgTestSupport("native-lock-fn-test", SCHEMA);
    private static Pool pool;

    @BeforeAll
    static void startContainer(Vertx vertx) throws Exception {
        pg.start(vertx);
        pool = pg.createPool(vertx);
    }

    @AfterAll
    static void stopContainer() throws Exception {
        if (pool != null) pool.close();
        pg.stop();
    }

    @BeforeEach
    void cleanTables(VertxTestContext ctx) {
        pool.query("DELETE FROM %s.cache_locks".formatted(SCHEMA))
                .execute()
                .compose(v -> pool.query("ALTER SEQUENCE %s.lock_fencing_seq RESTART WITH 1".formatted(SCHEMA)).execute())
                .onComplete(ctx.succeeding(v -> ctx.completeNow()));
    }

    @Test
    void acquireLockSucceedsOnEmptyTable(VertxTestContext ctx) {
        pool.preparedQuery("SELECT * FROM %s.acquire_lock($1, $2, $3, $4, $5, $6)".formatted(SCHEMA))
                .execute(Tuple.of("ns", "lock-1", "owner-A", 5000L, false, true))
                .onComplete(ctx.succeeding(rows -> ctx.verify(() -> {
                    assertEquals(1, rows.size());
                    Row row = rows.iterator().next();
                    assertTrue(row.getBoolean("acquired"));
                    assertEquals("owner-A", row.getString("owner_token"));
                    assertNotNull(row.getLong("fencing_token"));
                    assertNotNull(row.getOffsetDateTime("lease_expires_at"));
                    ctx.completeNow();
                })));
    }

    @Test
    void acquireLockWithoutFencingTokenReturnsNullFencingToken(VertxTestContext ctx) {
        pool.preparedQuery("SELECT * FROM %s.acquire_lock($1, $2, $3, $4, $5, $6)".formatted(SCHEMA))
                .execute(Tuple.of("ns", "lock-2", "owner-A", 5000L, false, false))
                .onComplete(ctx.succeeding(rows -> ctx.verify(() -> {
                    Row row = rows.iterator().next();
                    assertTrue(row.getBoolean("acquired"));
                    assertNull(row.getLong("fencing_token"));
                    ctx.completeNow();
                })));
    }

    @Test
    void acquireLockFailsWhenAlreadyHeldByDifferentOwner(VertxTestContext ctx) {
        pool.preparedQuery("SELECT * FROM %s.acquire_lock($1, $2, $3, $4, $5, $6)".formatted(SCHEMA))
                .execute(Tuple.of("ns", "lock-3", "owner-A", 5000L, false, true))
                .compose(first -> {
                    assertTrue(first.iterator().next().getBoolean("acquired"));
                    return pool.preparedQuery("SELECT * FROM %s.acquire_lock($1, $2, $3, $4, $5, $6)".formatted(SCHEMA))
                            .execute(Tuple.of("ns", "lock-3", "owner-B", 5000L, false, true));
                })
                .onComplete(ctx.succeeding(rows -> ctx.verify(() -> {
                    Row row = rows.iterator().next();
                    assertFalse(row.getBoolean("acquired"));
                    assertNull(row.getString("owner_token"));
                    assertNull(row.getLong("fencing_token"));
                    ctx.completeNow();
                })));
    }

    @Test
    void acquireLockReentrantRenewsBySameOwner(VertxTestContext ctx) {
        pool.preparedQuery("SELECT * FROM %s.acquire_lock($1, $2, $3, $4, $5, $6)".formatted(SCHEMA))
                .execute(Tuple.of("ns", "lock-4", "owner-A", 5000L, false, true))
                .compose(first -> {
                    assertTrue(first.iterator().next().getBoolean("acquired"));
                    return pool.preparedQuery("SELECT * FROM %s.acquire_lock($1, $2, $3, $4, $5, $6)".formatted(SCHEMA))
                            .execute(Tuple.of("ns", "lock-4", "owner-A", 10000L, true, true));
                })
                .onComplete(ctx.succeeding(rows -> ctx.verify(() -> {
                    Row row = rows.iterator().next();
                    assertTrue(row.getBoolean("acquired"));
                    assertEquals("owner-A", row.getString("owner_token"));
                    assertNotNull(row.getLong("fencing_token"));
                    ctx.completeNow();
                })));
    }

    @Test
    void acquireLockReentrantDeniedForDifferentOwner(VertxTestContext ctx) {
        pool.preparedQuery("SELECT * FROM %s.acquire_lock($1, $2, $3, $4, $5, $6)".formatted(SCHEMA))
                .execute(Tuple.of("ns", "lock-5", "owner-A", 5000L, false, true))
                .compose(first -> {
                    assertTrue(first.iterator().next().getBoolean("acquired"));
                    return pool.preparedQuery("SELECT * FROM %s.acquire_lock($1, $2, $3, $4, $5, $6)".formatted(SCHEMA))
                            .execute(Tuple.of("ns", "lock-5", "owner-B", 10000L, true, true));
                })
                .onComplete(ctx.succeeding(rows -> ctx.verify(() -> {
                    assertFalse(rows.iterator().next().getBoolean("acquired"));
                    ctx.completeNow();
                })));
    }

    @Test
    void acquireLockSucceedsAfterStaleLockExpires(VertxTestContext ctx) {
        // Insert a lock with already-expired lease (via direct INSERT with past time)
        pool.query("""
                INSERT INTO %s.cache_locks (namespace, lock_key, owner_token, fencing_token, version, created_at, updated_at, lease_expires_at)
                VALUES ('ns', 'lock-6', 'stale-owner', 1, 1, NOW() - INTERVAL '10 seconds', NOW() - INTERVAL '10 seconds', NOW() - INTERVAL '1 second')
                """.formatted(SCHEMA))
                .execute()
                .compose(v -> pool.preparedQuery("SELECT * FROM %s.acquire_lock($1, $2, $3, $4, $5, $6)".formatted(SCHEMA))
                        .execute(Tuple.of("ns", "lock-6", "new-owner", 5000L, false, true)))
                .onComplete(ctx.succeeding(rows -> ctx.verify(() -> {
                    Row row = rows.iterator().next();
                    assertTrue(row.getBoolean("acquired"));
                    assertEquals("new-owner", row.getString("owner_token"));
                    ctx.completeNow();
                })));
    }

    @Test
    void renewLockSucceedsByOwner(VertxTestContext ctx) {
        pool.preparedQuery("SELECT * FROM %s.acquire_lock($1, $2, $3, $4, $5, $6)".formatted(SCHEMA))
                .execute(Tuple.of("ns", "lock-7", "owner-A", 5000L, false, true))
                .compose(first -> {
                    assertTrue(first.iterator().next().getBoolean("acquired"));
                    return pool.preparedQuery("SELECT * FROM %s.renew_lock($1, $2, $3, $4)".formatted(SCHEMA))
                            .execute(Tuple.of("ns", "lock-7", "owner-A", 10000L));
                })
                .onComplete(ctx.succeeding(rows -> ctx.verify(() -> {
                    Row row = rows.iterator().next();
                    assertTrue(row.getBoolean("renewed"));
                    assertNotNull(row.getOffsetDateTime("lease_expires_at"));
                    ctx.completeNow();
                })));
    }

    @Test
    void renewLockFailsForDifferentOwner(VertxTestContext ctx) {
        pool.preparedQuery("SELECT * FROM %s.acquire_lock($1, $2, $3, $4, $5, $6)".formatted(SCHEMA))
                .execute(Tuple.of("ns", "lock-8", "owner-A", 5000L, false, true))
                .compose(first -> {
                    assertTrue(first.iterator().next().getBoolean("acquired"));
                    return pool.preparedQuery("SELECT * FROM %s.renew_lock($1, $2, $3, $4)".formatted(SCHEMA))
                            .execute(Tuple.of("ns", "lock-8", "owner-B", 10000L));
                })
                .onComplete(ctx.succeeding(rows -> ctx.verify(() -> {
                    assertFalse(rows.iterator().next().getBoolean("renewed"));
                    ctx.completeNow();
                })));
    }

    @Test
    void releaseLockSucceedsByOwner(VertxTestContext ctx) {
        pool.preparedQuery("SELECT * FROM %s.acquire_lock($1, $2, $3, $4, $5, $6)".formatted(SCHEMA))
                .execute(Tuple.of("ns", "lock-9", "owner-A", 5000L, false, true))
                .compose(first -> {
                    assertTrue(first.iterator().next().getBoolean("acquired"));
                    return pool.preparedQuery("SELECT * FROM %s.release_lock($1, $2, $3)".formatted(SCHEMA))
                            .execute(Tuple.of("ns", "lock-9", "owner-A"));
                })
                .onComplete(ctx.succeeding(rows -> ctx.verify(() -> {
                    assertTrue(rows.iterator().next().getBoolean("released"));
                    ctx.completeNow();
                })));
    }

    @Test
    void releaseLockFailsForDifferentOwner(VertxTestContext ctx) {
        pool.preparedQuery("SELECT * FROM %s.acquire_lock($1, $2, $3, $4, $5, $6)".formatted(SCHEMA))
                .execute(Tuple.of("ns", "lock-10", "owner-A", 5000L, false, true))
                .compose(first -> {
                    assertTrue(first.iterator().next().getBoolean("acquired"));
                    return pool.preparedQuery("SELECT * FROM %s.release_lock($1, $2, $3)".formatted(SCHEMA))
                            .execute(Tuple.of("ns", "lock-10", "owner-B"));
                })
                .onComplete(ctx.succeeding(rows -> ctx.verify(() -> {
                    assertFalse(rows.iterator().next().getBoolean("released"));
                    ctx.completeNow();
                })));
    }

    @Test
    void releaseLockReturnsFalseWhenNotHeld(VertxTestContext ctx) {
        pool.preparedQuery("SELECT * FROM %s.release_lock($1, $2, $3)".formatted(SCHEMA))
                .execute(Tuple.of("ns", "no-such-lock", "owner-A"))
                .onComplete(ctx.succeeding(rows -> ctx.verify(() -> {
                    assertFalse(rows.iterator().next().getBoolean("released"));
                    ctx.completeNow();
                })));
    }

    @Test
    void fencingTokensAreMonotonicallyIncreasing(VertxTestContext ctx) {
        pool.preparedQuery("SELECT * FROM %s.acquire_lock($1, $2, $3, $4, $5, $6)".formatted(SCHEMA))
                .execute(Tuple.of("ns", "fencing-1", "owner-A", 5000L, false, true))
                .compose(first -> {
                    long token1 = first.iterator().next().getLong("fencing_token");
                    return pool.preparedQuery("SELECT * FROM %s.acquire_lock($1, $2, $3, $4, $5, $6)".formatted(SCHEMA))
                            .execute(Tuple.of("ns", "fencing-2", "owner-B", 5000L, false, true))
                            .map(second -> {
                                long token2 = second.iterator().next().getLong("fencing_token");
                                return new long[]{token1, token2};
                            });
                })
                .onComplete(ctx.succeeding(tokens -> ctx.verify(() -> {
                    assertTrue(tokens[1] > tokens[0], "fencing tokens should be monotonically increasing");
                    ctx.completeNow();
                })));
    }
}
