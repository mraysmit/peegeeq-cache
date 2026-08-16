package dev.mars.peegeeq.cache.observability.health;

import dev.mars.peegeeq.cache.test.PostgreSQLTestConstants;
import dev.mars.peegeeq.cache.test.SharedPostgresContainerManager;
import io.vertx.core.Vertx;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.PoolOptions;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(VertxExtension.class)
class PgCacheHealthIndicatorTest {

    private static final String OWNER = "observability-health-test";
    private static PostgreSQLContainer postgres;
    private static Pool pool;

    @BeforeAll
    static void start(Vertx vertx, VertxTestContext context) {
        postgres = SharedPostgresContainerManager.acquire(OWNER);
        PgConnectOptions options = new PgConnectOptions()
                .setHost(postgres.getHost()).setPort(postgres.getFirstMappedPort())
                .setDatabase(PostgreSQLTestConstants.DEFAULT_DATABASE_NAME)
                .setUser(PostgreSQLTestConstants.DEFAULT_USERNAME)
                .setPassword(PostgreSQLTestConstants.DEFAULT_PASSWORD);
        pool = Pool.pool(vertx, options, new PoolOptions().setMaxSize(2));
        pool.query("CREATE SCHEMA health_check; CREATE TABLE health_check.cache_entries(id int)")
                .execute().onComplete(context.succeeding(ignored -> context.completeNow()));
    }

    @AfterAll
    static void stop(VertxTestContext context) {
        pool.close().onComplete(context.succeeding(ignored -> {
            SharedPostgresContainerManager.release(OWNER);
            context.completeNow();
        }));
    }

    @Test
    void reportsRuntimeDatabaseAndSchemaReadiness(VertxTestContext context) {
        AtomicBoolean started = new AtomicBoolean(true);
        PgCacheHealthIndicator health = new PgCacheHealthIndicator(pool, "health_check", started::get);

        health.check().compose(up -> {
            context.verify(() -> {
                assertEquals(CacheHealth.Status.UP, up.status());
                assertTrue(up.schemaReady());
                assertTrue(!up.latency().isNegative());
            });
            started.set(false);
            return health.check();
        }).onComplete(context.succeeding(stopped -> context.verify(() -> {
            assertEquals(CacheHealth.Status.STOPPED, stopped.status());
            context.completeNow();
        })));
    }
}
