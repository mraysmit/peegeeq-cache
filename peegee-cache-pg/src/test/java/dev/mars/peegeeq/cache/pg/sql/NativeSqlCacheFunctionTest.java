package dev.mars.peegeeq.cache.pg.sql;

import dev.mars.peegeeq.cache.pg.test.PgTestSupport;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
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
 * Integration tests for peegee_cache cache entry SQL functions.
 */
@ExtendWith(VertxExtension.class)
class NativeSqlCacheFunctionTest {

    private static final String SCHEMA = "peegee_cache";
    private static final PgTestSupport pg = new PgTestSupport("native-cache-fn-test", SCHEMA);
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
        pool.query("DELETE FROM %s.cache_entries".formatted(SCHEMA))
                .execute()
                .onComplete(ctx.succeeding(v -> ctx.completeNow()));
    }

    // ---- UPSERT mode ----

    @Test
    void upsertCreatesNewEntry(VertxTestContext ctx) {
        Buffer payload = Buffer.buffer("hello");
        pool.preparedQuery("SELECT * FROM %s.set_entry($1, $2, $3, $4, $5, $6, $7)".formatted(SCHEMA))
                .execute(Tuple.of("ns", "k1", "STRING", payload, null, null, "UPSERT"))
                .onComplete(ctx.succeeding(rows -> ctx.verify(() -> {
                    Row row = rows.iterator().next();
                    assertTrue(row.getBoolean("applied"));
                    assertEquals(1L, row.getLong("version"));
                    ctx.completeNow();
                })));
    }

    @Test
    void upsertUpdatesExistingEntry(VertxTestContext ctx) {
        Buffer payload1 = Buffer.buffer("v1");
        Buffer payload2 = Buffer.buffer("v2");
        pool.preparedQuery("SELECT * FROM %s.set_entry($1, $2, $3, $4, $5, $6, $7)".formatted(SCHEMA))
                .execute(Tuple.of("ns", "k2", "STRING", payload1, null, null, "UPSERT"))
                .compose(v -> pool.preparedQuery("SELECT * FROM %s.set_entry($1, $2, $3, $4, $5, $6, $7)".formatted(SCHEMA))
                        .execute(Tuple.of("ns", "k2", "STRING", payload2, null, null, "UPSERT")))
                .onComplete(ctx.succeeding(rows -> ctx.verify(() -> {
                    Row row = rows.iterator().next();
                    assertTrue(row.getBoolean("applied"));
                    assertEquals(2L, row.getLong("version"));
                    ctx.completeNow();
                })));
    }

    @Test
    void upsertWithTtl(VertxTestContext ctx) {
        Buffer payload = Buffer.buffer("ttl-value");
        pool.preparedQuery("SELECT * FROM %s.set_entry($1, $2, $3, $4, $5, $6, $7)".formatted(SCHEMA))
                .execute(Tuple.of("ns", "k3", "STRING", payload, null, 60000L, "UPSERT"))
                .compose(v -> pool.preparedQuery("SELECT expires_at FROM %s.cache_entries WHERE namespace = $1 AND cache_key = $2".formatted(SCHEMA))
                        .execute(Tuple.of("ns", "k3")))
                .onComplete(ctx.succeeding(rows -> ctx.verify(() -> {
                    assertNotNull(rows.iterator().next().getOffsetDateTime("expires_at"));
                    ctx.completeNow();
                })));
    }

    @Test
    void upsertWithLongType(VertxTestContext ctx) {
        pool.preparedQuery("SELECT * FROM %s.set_entry($1, $2, $3, $4, $5, $6, $7)".formatted(SCHEMA))
                .execute(Tuple.of("ns", "k4", "LONG", null, 42L, null, "UPSERT"))
                .compose(v -> pool.preparedQuery("SELECT numeric_value FROM %s.cache_entries WHERE namespace = $1 AND cache_key = $2".formatted(SCHEMA))
                        .execute(Tuple.of("ns", "k4")))
                .onComplete(ctx.succeeding(rows -> ctx.verify(() -> {
                    assertEquals(42L, rows.iterator().next().getLong("numeric_value"));
                    ctx.completeNow();
                })));
    }

    // ---- ONLY_IF_ABSENT mode ----

    @Test
    void onlyIfAbsentCreatesWhenMissing(VertxTestContext ctx) {
        Buffer payload = Buffer.buffer("nx-value");
        pool.preparedQuery("SELECT * FROM %s.set_entry($1, $2, $3, $4, $5, $6, $7)".formatted(SCHEMA))
                .execute(Tuple.of("ns", "k5", "STRING", payload, null, null, "ONLY_IF_ABSENT"))
                .onComplete(ctx.succeeding(rows -> ctx.verify(() -> {
                    Row row = rows.iterator().next();
                    assertTrue(row.getBoolean("applied"));
                    assertEquals(1L, row.getLong("version"));
                    ctx.completeNow();
                })));
    }

    @Test
    void onlyIfAbsentSkipsWhenPresent(VertxTestContext ctx) {
        Buffer payload = Buffer.buffer("existing");
        pool.preparedQuery("SELECT * FROM %s.set_entry($1, $2, $3, $4, $5, $6, $7)".formatted(SCHEMA))
                .execute(Tuple.of("ns", "k6", "STRING", payload, null, null, "UPSERT"))
                .compose(v -> pool.preparedQuery("SELECT * FROM %s.set_entry($1, $2, $3, $4, $5, $6, $7)".formatted(SCHEMA))
                        .execute(Tuple.of("ns", "k6", "STRING", Buffer.buffer("new"), null, null, "ONLY_IF_ABSENT")))
                .onComplete(ctx.succeeding(rows -> ctx.verify(() -> {
                    assertFalse(rows.iterator().next().getBoolean("applied"));
                    ctx.completeNow();
                })));
    }

    @Test
    void onlyIfAbsentSucceedsAfterExpiry(VertxTestContext ctx) {
        // Insert an already-expired entry directly
        pool.query("""
                INSERT INTO %s.cache_entries (namespace, cache_key, value_type, value_bytes, version, created_at, updated_at, expires_at, hit_count)
                VALUES ('ns', 'k7', 'STRING', 'old'::bytea, 1, NOW() - INTERVAL '10 seconds', NOW() - INTERVAL '10 seconds', NOW() - INTERVAL '1 second', 0)
                """.formatted(SCHEMA))
                .execute()
                .compose(v -> pool.preparedQuery("SELECT * FROM %s.set_entry($1, $2, $3, $4, $5, $6, $7)".formatted(SCHEMA))
                        .execute(Tuple.of("ns", "k7", "STRING", Buffer.buffer("new"), null, null, "ONLY_IF_ABSENT")))
                .onComplete(ctx.succeeding(rows -> ctx.verify(() -> {
                    assertTrue(rows.iterator().next().getBoolean("applied"));
                    ctx.completeNow();
                })));
    }

    // ---- ONLY_IF_PRESENT mode ----

    @Test
    void onlyIfPresentUpdatesWhenExists(VertxTestContext ctx) {
        Buffer payload = Buffer.buffer("v1");
        pool.preparedQuery("SELECT * FROM %s.set_entry($1, $2, $3, $4, $5, $6, $7)".formatted(SCHEMA))
                .execute(Tuple.of("ns", "k8", "STRING", payload, null, null, "UPSERT"))
                .compose(v -> pool.preparedQuery("SELECT * FROM %s.set_entry($1, $2, $3, $4, $5, $6, $7)".formatted(SCHEMA))
                        .execute(Tuple.of("ns", "k8", "STRING", Buffer.buffer("v2"), null, null, "ONLY_IF_PRESENT")))
                .onComplete(ctx.succeeding(rows -> ctx.verify(() -> {
                    Row row = rows.iterator().next();
                    assertTrue(row.getBoolean("applied"));
                    assertEquals(2L, row.getLong("version"));
                    ctx.completeNow();
                })));
    }

    @Test
    void onlyIfPresentSkipsWhenMissing(VertxTestContext ctx) {
        pool.preparedQuery("SELECT * FROM %s.set_entry($1, $2, $3, $4, $5, $6, $7)".formatted(SCHEMA))
                .execute(Tuple.of("ns", "k9", "STRING", Buffer.buffer("val"), null, null, "ONLY_IF_PRESENT"))
                .onComplete(ctx.succeeding(rows -> ctx.verify(() -> {
                    assertFalse(rows.iterator().next().getBoolean("applied"));
                    ctx.completeNow();
                })));
    }

    // ---- ONLY_IF_VERSION_MATCHES mode ----

    @Test
    void versionMatchUpdatesWhenVersionMatches(VertxTestContext ctx) {
        pool.preparedQuery("SELECT * FROM %s.set_entry($1, $2, $3, $4, $5, $6, $7)".formatted(SCHEMA))
                .execute(Tuple.of("ns", "k10", "STRING", Buffer.buffer("v1"), null, null, "UPSERT"))
                .compose(v -> pool.preparedQuery("SELECT * FROM %s.set_entry($1, $2, $3, $4, $5, $6, $7, $8)".formatted(SCHEMA))
                        .execute(Tuple.of("ns", "k10", "STRING", Buffer.buffer("v2"), null, null, "ONLY_IF_VERSION_MATCHES", 1L)))
                .onComplete(ctx.succeeding(rows -> ctx.verify(() -> {
                    Row row = rows.iterator().next();
                    assertTrue(row.getBoolean("applied"));
                    assertEquals(2L, row.getLong("version"));
                    ctx.completeNow();
                })));
    }

    @Test
    void versionMatchSkipsWhenVersionDiffers(VertxTestContext ctx) {
        pool.preparedQuery("SELECT * FROM %s.set_entry($1, $2, $3, $4, $5, $6, $7)".formatted(SCHEMA))
                .execute(Tuple.of("ns", "k11", "STRING", Buffer.buffer("v1"), null, null, "UPSERT"))
                .compose(v -> pool.preparedQuery("SELECT * FROM %s.set_entry($1, $2, $3, $4, $5, $6, $7, $8)".formatted(SCHEMA))
                        .execute(Tuple.of("ns", "k11", "STRING", Buffer.buffer("v2"), null, null, "ONLY_IF_VERSION_MATCHES", 99L)))
                .onComplete(ctx.succeeding(rows -> ctx.verify(() -> {
                    assertFalse(rows.iterator().next().getBoolean("applied"));
                    ctx.completeNow();
                })));
    }

    // ---- delete_entry ----

    @Test
    void deleteEntryRemovesExisting(VertxTestContext ctx) {
        pool.preparedQuery("SELECT * FROM %s.set_entry($1, $2, $3, $4, $5, $6, $7)".formatted(SCHEMA))
                .execute(Tuple.of("ns", "k12", "STRING", Buffer.buffer("val"), null, null, "UPSERT"))
                .compose(v -> pool.preparedQuery("SELECT * FROM %s.delete_entry($1, $2)".formatted(SCHEMA))
                        .execute(Tuple.of("ns", "k12")))
                .onComplete(ctx.succeeding(rows -> ctx.verify(() -> {
                    assertTrue(rows.iterator().next().getBoolean("deleted"));
                    ctx.completeNow();
                })));
    }

    @Test
    void deleteEntryReturnsFalseWhenMissing(VertxTestContext ctx) {
        pool.preparedQuery("SELECT * FROM %s.delete_entry($1, $2)".formatted(SCHEMA))
                .execute(Tuple.of("ns", "no-such-key"))
                .onComplete(ctx.succeeding(rows -> ctx.verify(() -> {
                    assertFalse(rows.iterator().next().getBoolean("deleted"));
                    ctx.completeNow();
                })));
    }
}
