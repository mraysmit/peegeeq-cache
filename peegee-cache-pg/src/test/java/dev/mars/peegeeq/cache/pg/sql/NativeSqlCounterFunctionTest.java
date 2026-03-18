package dev.mars.peegeeq.cache.pg.sql;

import dev.mars.peegeeq.cache.pg.test.PgTestSupport;
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
 * Integration tests for peegee_cache counter SQL functions.
 */
@ExtendWith(VertxExtension.class)
class NativeSqlCounterFunctionTest {

    private static final String SCHEMA = "peegee_cache";
    private static final PgTestSupport pg = new PgTestSupport("native-counter-fn-test", SCHEMA);
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
        pool.query("DELETE FROM %s.cache_counters".formatted(SCHEMA))
                .execute()
                .onComplete(ctx.succeeding(v -> ctx.completeNow()));
    }

    @Test
    void incrementCreatesCounterWhenMissing(VertxTestContext ctx) {
        pool.preparedQuery("SELECT * FROM %s.increment_counter($1, $2, $3)".formatted(SCHEMA))
                .execute(Tuple.of("ns", "ctr-1", 5L))
                .onComplete(ctx.succeeding(rows -> ctx.verify(() -> {
                    Row row = rows.iterator().next();
                    assertEquals(5L, row.getLong("counter_value"));
                    assertEquals(1L, row.getLong("version"));
                    ctx.completeNow();
                })));
    }

    @Test
    void incrementAddsToExistingCounter(VertxTestContext ctx) {
        pool.preparedQuery("SELECT * FROM %s.increment_counter($1, $2, $3)".formatted(SCHEMA))
                .execute(Tuple.of("ns", "ctr-2", 10L))
                .compose(v -> pool.preparedQuery("SELECT * FROM %s.increment_counter($1, $2, $3)".formatted(SCHEMA))
                        .execute(Tuple.of("ns", "ctr-2", 3L)))
                .onComplete(ctx.succeeding(rows -> ctx.verify(() -> {
                    Row row = rows.iterator().next();
                    assertEquals(13L, row.getLong("counter_value"));
                    assertEquals(2L, row.getLong("version"));
                    ctx.completeNow();
                })));
    }

    @Test
    void incrementWithNegativeDeltaDecrements(VertxTestContext ctx) {
        pool.preparedQuery("SELECT * FROM %s.increment_counter($1, $2, $3)".formatted(SCHEMA))
                .execute(Tuple.of("ns", "ctr-3", 10L))
                .compose(v -> pool.preparedQuery("SELECT * FROM %s.increment_counter($1, $2, $3)".formatted(SCHEMA))
                        .execute(Tuple.of("ns", "ctr-3", -7L)))
                .onComplete(ctx.succeeding(rows -> ctx.verify(() -> {
                    assertEquals(3L, rows.iterator().next().getLong("counter_value"));
                    ctx.completeNow();
                })));
    }

    @Test
    void incrementPreserveTtlKeepsExistingTtl(VertxTestContext ctx) {
        // Create with TTL
        pool.preparedQuery("SELECT * FROM %s.increment_counter($1, $2, $3, $4, $5)".formatted(SCHEMA))
                .execute(Tuple.of("ns", "ctr-4", 1L, 60000L, "PRESERVE_EXISTING"))
                .compose(v ->
                        // Increment with different TTL — PRESERVE should keep the original
                        pool.preparedQuery("SELECT * FROM %s.increment_counter($1, $2, $3, $4, $5)".formatted(SCHEMA))
                                .execute(Tuple.of("ns", "ctr-4", 1L, 120000L, "PRESERVE_EXISTING")))
                .compose(v ->
                        // Check actual TTL in table
                        pool.preparedQuery("SELECT expires_at FROM %s.cache_counters WHERE namespace = $1 AND counter_key = $2".formatted(SCHEMA))
                                .execute(Tuple.of("ns", "ctr-4")))
                .onComplete(ctx.succeeding(rows -> ctx.verify(() -> {
                    // TTL should still be roughly 60s, not 120s
                    assertNotNull(rows.iterator().next().getOffsetDateTime("expires_at"));
                    ctx.completeNow();
                })));
    }

    @Test
    void incrementReplaceTtlOverwritesTtl(VertxTestContext ctx) {
        // Create with TTL
        pool.preparedQuery("SELECT * FROM %s.increment_counter($1, $2, $3, $4, $5)".formatted(SCHEMA))
                .execute(Tuple.of("ns", "ctr-5", 1L, 60000L, "REPLACE"))
                .compose(v ->
                        // Increment with NULL TTL via REPLACE — should remove TTL
                        pool.preparedQuery("SELECT * FROM %s.increment_counter($1, $2, $3, $4, $5)".formatted(SCHEMA))
                                .execute(Tuple.of("ns", "ctr-5", 1L, null, "REPLACE")))
                .compose(v ->
                        pool.preparedQuery("SELECT expires_at FROM %s.cache_counters WHERE namespace = $1 AND counter_key = $2".formatted(SCHEMA))
                                .execute(Tuple.of("ns", "ctr-5")))
                .onComplete(ctx.succeeding(rows -> ctx.verify(() -> {
                    assertNull(rows.iterator().next().getOffsetDateTime("expires_at"));
                    ctx.completeNow();
                })));
    }

    @Test
    void incrementRemoveTtlClearsTtl(VertxTestContext ctx) {
        // Create with TTL
        pool.preparedQuery("SELECT * FROM %s.increment_counter($1, $2, $3, $4, $5)".formatted(SCHEMA))
                .execute(Tuple.of("ns", "ctr-6", 1L, 60000L, "PRESERVE_EXISTING"))
                .compose(v ->
                        // Increment with REMOVE — should clear TTL
                        pool.preparedQuery("SELECT * FROM %s.increment_counter($1, $2, $3, $4, $5)".formatted(SCHEMA))
                                .execute(Tuple.of("ns", "ctr-6", 1L, null, "REMOVE")))
                .compose(v ->
                        pool.preparedQuery("SELECT expires_at FROM %s.cache_counters WHERE namespace = $1 AND counter_key = $2".formatted(SCHEMA))
                                .execute(Tuple.of("ns", "ctr-6")))
                .onComplete(ctx.succeeding(rows -> ctx.verify(() -> {
                    assertNull(rows.iterator().next().getOffsetDateTime("expires_at"));
                    ctx.completeNow();
                })));
    }

    @Test
    void incrementNoCreateReturnsNullWhenMissing(VertxTestContext ctx) {
        pool.preparedQuery("SELECT * FROM %s.increment_counter($1, $2, $3, $4, $5, $6)".formatted(SCHEMA))
                .execute(Tuple.of("ns", "ctr-7", 5L, null, "PRESERVE_EXISTING", false))
                .onComplete(ctx.succeeding(rows -> ctx.verify(() -> {
                    Row row = rows.iterator().next();
                    assertNull(row.getLong("counter_value"));
                    assertNull(row.getLong("version"));
                    ctx.completeNow();
                })));
    }

    @Test
    void incrementNoCreateUpdatesExistingCounter(VertxTestContext ctx) {
        pool.preparedQuery("SELECT * FROM %s.increment_counter($1, $2, $3)".formatted(SCHEMA))
                .execute(Tuple.of("ns", "ctr-8", 10L))
                .compose(v -> pool.preparedQuery("SELECT * FROM %s.increment_counter($1, $2, $3, $4, $5, $6)".formatted(SCHEMA))
                        .execute(Tuple.of("ns", "ctr-8", 3L, null, "PRESERVE_EXISTING", false)))
                .onComplete(ctx.succeeding(rows -> ctx.verify(() -> {
                    assertEquals(13L, rows.iterator().next().getLong("counter_value"));
                    ctx.completeNow();
                })));
    }

    @Test
    void setCounterCreatesNewCounter(VertxTestContext ctx) {
        pool.preparedQuery("SELECT * FROM %s.set_counter($1, $2, $3)".formatted(SCHEMA))
                .execute(Tuple.of("ns", "ctr-9", 42L))
                .onComplete(ctx.succeeding(rows -> ctx.verify(() -> {
                    Row row = rows.iterator().next();
                    assertEquals(42L, row.getLong("counter_value"));
                    assertEquals(1L, row.getLong("version"));
                    ctx.completeNow();
                })));
    }

    @Test
    void setCounterOverwritesExistingValue(VertxTestContext ctx) {
        pool.preparedQuery("SELECT * FROM %s.increment_counter($1, $2, $3)".formatted(SCHEMA))
                .execute(Tuple.of("ns", "ctr-10", 10L))
                .compose(v -> pool.preparedQuery("SELECT * FROM %s.set_counter($1, $2, $3)".formatted(SCHEMA))
                        .execute(Tuple.of("ns", "ctr-10", 99L)))
                .onComplete(ctx.succeeding(rows -> ctx.verify(() -> {
                    assertEquals(99L, rows.iterator().next().getLong("counter_value"));
                    ctx.completeNow();
                })));
    }

    @Test
    void deleteCounterRemovesExisting(VertxTestContext ctx) {
        pool.preparedQuery("SELECT * FROM %s.increment_counter($1, $2, $3)".formatted(SCHEMA))
                .execute(Tuple.of("ns", "ctr-11", 10L))
                .compose(v -> pool.preparedQuery("SELECT * FROM %s.delete_counter($1, $2)".formatted(SCHEMA))
                        .execute(Tuple.of("ns", "ctr-11")))
                .onComplete(ctx.succeeding(rows -> ctx.verify(() -> {
                    assertTrue(rows.iterator().next().getBoolean("deleted"));
                    ctx.completeNow();
                })));
    }

    @Test
    void deleteCounterReturnsFalseWhenMissing(VertxTestContext ctx) {
        pool.preparedQuery("SELECT * FROM %s.delete_counter($1, $2)".formatted(SCHEMA))
                .execute(Tuple.of("ns", "no-such-counter"))
                .onComplete(ctx.succeeding(rows -> ctx.verify(() -> {
                    assertFalse(rows.iterator().next().getBoolean("deleted"));
                    ctx.completeNow();
                })));
    }

    @Test
    void incrementCleansExpiredRowBeforeCreate(VertxTestContext ctx) {
        // Insert an already-expired counter directly
        pool.query("""
                INSERT INTO %s.cache_counters (namespace, counter_key, counter_value, version, created_at, updated_at, expires_at)
                VALUES ('ns', 'ctr-12', 100, 1, NOW() - INTERVAL '10 seconds', NOW() - INTERVAL '10 seconds', NOW() - INTERVAL '1 second')
                """.formatted(SCHEMA))
                .execute()
                .compose(v -> pool.preparedQuery("SELECT * FROM %s.increment_counter($1, $2, $3)".formatted(SCHEMA))
                        .execute(Tuple.of("ns", "ctr-12", 5L)))
                .onComplete(ctx.succeeding(rows -> ctx.verify(() -> {
                    // Should be 5, not 105 — expired row was cleaned before insert
                    assertEquals(5L, rows.iterator().next().getLong("counter_value"));
                    ctx.completeNow();
                })));
    }
}
