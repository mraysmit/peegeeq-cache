package dev.mars.peegeeq.cache.pg.test;

import dev.mars.peegeeq.cache.test.PgTestSupport;

import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import io.vertx.sqlclient.Pool;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static org.junit.jupiter.api.Assertions.assertEquals;

@ExtendWith(VertxExtension.class)
@io.vertx.junit5.Timeout(value = 90, timeUnit = java.util.concurrent.TimeUnit.SECONDS)
class PgTestSupportSchemaIntegrationTest {

    private static final String CUSTOM_SCHEMA = "test_support_custom";
    private static final PgTestSupport pg = new PgTestSupport("pg-support-schema-test", CUSTOM_SCHEMA);

    private static Pool pool;

    @BeforeAll
    static void start(Vertx vertx, VertxTestContext ctx) {
        pg.start(vertx)
                .onSuccess(ignored -> ctx.verify(() -> {
                    pool = pg.createPool(vertx);
                    ctx.completeNow();
                }))
                .onFailure(ctx::failNow);
    }

    @AfterAll
    static void stop(Vertx vertx, VertxTestContext ctx) {
        (pool == null ? pg.stop(vertx) : pg.stopAfter(vertx, pool.close()))
                .onSuccess(ignored -> {
                    pool = null;
                    ctx.completeNow();
                })
                .onFailure(ctx::failNow);
    }

    @Test
    void startCreatesConfiguredSchemaObjects(VertxTestContext ctx) {
        pool.query("SELECT to_regclass('" + CUSTOM_SCHEMA + ".cache_entries') AS regclass")
                .execute()
                .onSuccess(rows -> ctx.verify(() -> {
                    assertEquals(CUSTOM_SCHEMA + ".cache_entries",
                            rows.iterator().next().getString("regclass"));
                    ctx.completeNow();
                }))
                .onFailure(ctx::failNow);
    }

    @Test
    void resetDatabaseStateClearsDomainTablesAndRestartsFencingSequence(VertxTestContext ctx) {
        String seedSql = """
                INSERT INTO %1$s.cache_entries
                    (namespace, cache_key, value_type, value_bytes)
                VALUES ('reset', 'entry', 'STRING', convert_to('value', 'UTF8'));
                INSERT INTO %1$s.cache_counters
                    (namespace, counter_key, counter_value)
                VALUES ('reset', 'counter', 1);
                SELECT setval('%1$s.lock_fencing_seq', 42, true);
                INSERT INTO %1$s.cache_locks
                    (namespace, lock_key, owner_token, fencing_token, lease_expires_at)
                VALUES ('reset', 'lock', 'owner', 42, NOW() + INTERVAL '1 minute');
                """.formatted(CUSTOM_SCHEMA);
        String inspectSql = """
                SELECT
                    (SELECT COUNT(*) FROM %1$s.cache_entries) AS entry_count,
                    (SELECT COUNT(*) FROM %1$s.cache_counters) AS counter_count,
                    (SELECT COUNT(*) FROM %1$s.cache_locks) AS lock_count,
                    nextval('%1$s.lock_fencing_seq') AS next_fencing_token
                """.formatted(CUSTOM_SCHEMA);

        pool.query(seedSql).execute()
                .compose(ignored -> pg.resetDatabaseState(pool))
                .compose(ignored -> pool.query(inspectSql).execute())
                .onSuccess(rows -> ctx.verify(() -> {
                    var row = rows.iterator().next();
                    assertEquals(0L, row.getLong("entry_count"));
                    assertEquals(0L, row.getLong("counter_count"));
                    assertEquals(0L, row.getLong("lock_count"));
                    assertEquals(1L, row.getLong("next_fencing_token"));
                    ctx.completeNow();
                }))
                .onFailure(ctx::failNow);
    }
}
