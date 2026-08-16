package dev.mars.peegeeq.cache.pg.bootstrap;

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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(VertxExtension.class)
class PgSchemaMigratorTest {

    private static final String OWNER = "schema-migrator-test";
    private static final String SCHEMA = "cache_upgrade_test";
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
        String baselineV1 = "DROP SCHEMA IF EXISTS " + SCHEMA + " CASCADE; "
                + BootstrapSqlRenderer.loadMigrationForSchema(1, SCHEMA);
        pool.query(baselineV1).execute()
                .compose(ignored -> pool.query("""
                        INSERT INTO cache_upgrade_test.cache_entries
                            (namespace, cache_key, value_type, value_bytes)
                        VALUES ('upgrade', 'preserved', 'STRING', convert_to('value', 'UTF8'))
                        """).execute())
                .onComplete(context.succeeding(ignored -> context.completeNow()));
    }

    @AfterAll
    static void stop(VertxTestContext context) {
        pool.close().onComplete(context.succeeding(ignored -> {
            SharedPostgresContainerManager.release(OWNER);
            context.completeNow();
        }));
    }

    @Test
    void repeatedMigrationPreservesV1BaselineDataAndIsIdempotent(VertxTestContext context) {
        PgSchemaMigrator migrator = new PgSchemaMigrator(pool, SCHEMA);

        migrator.migrate()
                .compose(ignored -> migrator.migrate())
                .compose(ignored -> pool.query("""
                        SELECT
                            (SELECT array_agg(version ORDER BY version)
                             FROM cache_upgrade_test.schema_migrations) AS versions,
                            (SELECT COUNT(*) FROM cache_upgrade_test.live_entries
                             WHERE namespace = 'upgrade' AND cache_key = 'preserved') AS preserved
                        """).execute())
                .onComplete(context.succeeding(rows -> context.verify(() -> {
                    var row = rows.iterator().next();
                    assertArrayEquals(new Integer[]{1}, row.getArrayOfIntegers("versions"));
                    assertEquals(1L, row.getLong("preserved"));
                    context.completeNow();
                })));
    }

    @Test
    void rejectsSchemaVersionNewerThanTheRunningLibrary(VertxTestContext context) {
        String futureSchema = "cache_future_version_test";
        String setup = "DROP SCHEMA IF EXISTS " + futureSchema + " CASCADE; "
                + BootstrapSqlRenderer.loadForSchema(futureSchema)
                + " INSERT INTO " + futureSchema
                + ".schema_migrations(version, description) VALUES (99, 'future');";

        pool.query(setup).execute()
                .compose(ignored -> new PgSchemaMigrator(pool, futureSchema).migrate())
                .onComplete(context.failing(failure -> context.verify(() -> {
                    assertInstanceOf(IllegalStateException.class, failure);
                    assertTrue(failure.getMessage().contains("newer than this library"));
                    context.completeNow();
                })));
    }
}
