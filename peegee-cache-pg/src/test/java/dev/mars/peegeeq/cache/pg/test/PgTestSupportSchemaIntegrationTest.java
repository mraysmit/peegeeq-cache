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
}
