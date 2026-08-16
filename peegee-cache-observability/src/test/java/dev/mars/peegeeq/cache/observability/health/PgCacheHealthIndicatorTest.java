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
import static org.junit.jupiter.api.Assertions.assertFalse;
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
        pool.query("""
                        CREATE SCHEMA health_check;
                        CREATE TABLE health_check.cache_entries(namespace text, cache_key text, expires_at timestamptz);
                        CREATE TABLE health_check.cache_counters(namespace text, counter_key text, expires_at timestamptz);
                        CREATE TABLE health_check.cache_locks(lease_expires_at timestamptz);
                        CREATE TABLE health_check.schema_migrations(version integer primary key, description text, applied_at timestamptz);
                        CREATE SEQUENCE health_check.lock_fencing_seq;
                        CREATE INDEX idx_cache_entries_expires_at ON health_check.cache_entries(expires_at);
                        CREATE INDEX idx_cache_entries_namespace_key_pattern ON health_check.cache_entries(namespace, cache_key);
                        CREATE INDEX idx_cache_counters_expires_at ON health_check.cache_counters(expires_at);
                        CREATE INDEX idx_cache_counters_namespace_key_pattern ON health_check.cache_counters(namespace, counter_key);
                        CREATE INDEX idx_cache_locks_lease_expires_at ON health_check.cache_locks(lease_expires_at);
                        CREATE VIEW health_check.live_entries AS SELECT * FROM health_check.cache_entries;
                        CREATE VIEW health_check.live_counters AS SELECT * FROM health_check.cache_counters;
                        CREATE VIEW health_check.active_locks AS SELECT * FROM health_check.cache_locks;
                        CREATE FUNCTION health_check.acquire_lock(text,text,text,bigint,boolean,boolean) RETURNS integer LANGUAGE sql AS 'SELECT 1';
                        CREATE FUNCTION health_check.renew_lock(text,text,text,bigint) RETURNS integer LANGUAGE sql AS 'SELECT 1';
                        CREATE FUNCTION health_check.release_lock(text,text,text) RETURNS integer LANGUAGE sql AS 'SELECT 1';
                        CREATE FUNCTION health_check.increment_counter(text,text,bigint,bigint,text,boolean) RETURNS integer LANGUAGE sql AS 'SELECT 1';
                        CREATE FUNCTION health_check.set_counter(text,text,bigint,bigint) RETURNS integer LANGUAGE sql AS 'SELECT 1';
                        CREATE FUNCTION health_check.delete_counter(text,text) RETURNS integer LANGUAGE sql AS 'SELECT 1';
                        CREATE FUNCTION health_check.set_entry(text,text,text,bytea,bigint,bigint,text,bigint) RETURNS integer LANGUAGE sql AS 'SELECT 1';
                        CREATE FUNCTION health_check.delete_entry(text,text) RETURNS integer LANGUAGE sql AS 'SELECT 1';

                        CREATE SCHEMA incomplete_health_check;
                        CREATE TABLE incomplete_health_check.cache_entries(id int);
                        """)
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

    @Test
    void reportsDownWhenOnlyCacheEntriesTableExists(VertxTestContext context) {
        PgCacheHealthIndicator health = new PgCacheHealthIndicator(pool, "incomplete_health_check", () -> true);

        health.check().onComplete(context.succeeding(result -> context.verify(() -> {
            assertEquals(CacheHealth.Status.DOWN, result.status());
            assertFalse(result.schemaReady());
            assertTrue(result.detail().contains("cache_counters"));
            assertTrue(result.detail().contains("acquire_lock"));
            context.completeNow();
        })));
    }
}
