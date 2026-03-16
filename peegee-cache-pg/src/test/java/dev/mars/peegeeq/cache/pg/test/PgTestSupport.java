package dev.mars.peegeeq.cache.pg.test;

import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.PoolOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.PostgreSQLContainer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Manages a disposable PostgreSQL Testcontainer and provides
 * a Vert.x PgPool for repository integration tests.
 */
public final class PgTestSupport {

    private static final Logger log = LoggerFactory.getLogger(PgTestSupport.class);

    private final String logLabel;
    private PostgreSQLContainer<?> postgres;

    public PgTestSupport(String logLabel) {
        this.logLabel = logLabel;
    }

    public void start(Vertx vertx) throws Exception {
        postgres = SharedPostgresContainerManager.acquire(logLabel);
        log.info("Container '{}' using shared mapped port {}", logLabel, postgres.getFirstMappedPort());
        applyMigrations(vertx);
        resetDatabaseState(vertx);
        log.info("Container '{}' ready", logLabel);
    }

    public void stop() throws Exception {
        log.info("Releasing container '{}'", logLabel);
        if (postgres != null) {
            SharedPostgresContainerManager.release(logLabel);
            postgres = null;
        }
    }

    public Pool createPool(Vertx vertx) {
        PgConnectOptions connectOptions = new PgConnectOptions()
                .setHost(postgres.getHost())
                .setPort(postgres.getFirstMappedPort())
                .setDatabase(PostgreSQLTestConstants.DEFAULT_DATABASE_NAME)
                .setUser(PostgreSQLTestConstants.DEFAULT_USERNAME)
                .setPassword(PostgreSQLTestConstants.DEFAULT_PASSWORD);

        PoolOptions poolOptions = new PoolOptions().setMaxSize(4);

        return Pool.pool(vertx, connectOptions, poolOptions);
    }

    private void applyMigrations(Vertx vertx) throws Exception {
        String sql = readClasspathResource("/db/migration/V001__create_peegee_cache_schema.sql");
        Pool migrationPool = Pool.pool(vertx, connectOptions(), new PoolOptions().setMaxSize(1));
        try {
            await(migrationPool.query(sql).execute().mapEmpty(), 10_000);
        } finally {
            migrationPool.close();
        }
    }

    private void resetDatabaseState(Vertx vertx) throws Exception {
        Pool adminPool = Pool.pool(vertx, connectOptions(), new PoolOptions().setMaxSize(1));
        try {
            await(adminPool.query(
                    "TRUNCATE TABLE peegee_cache.cache_entries, peegee_cache.cache_counters, peegee_cache.cache_locks")
                    .execute().mapEmpty(), 10_000);
            await(adminPool.query("ALTER SEQUENCE peegee_cache.lock_fencing_seq RESTART WITH 1")
                    .execute().mapEmpty(), 10_000);
        } finally {
            await(adminPool.close(), 10_000);
        }
    }

    private PgConnectOptions connectOptions() {
        return new PgConnectOptions()
                .setHost(postgres.getHost())
                .setPort(postgres.getFirstMappedPort())
                .setDatabase(PostgreSQLTestConstants.DEFAULT_DATABASE_NAME)
                .setUser(PostgreSQLTestConstants.DEFAULT_USERNAME)
                .setPassword(PostgreSQLTestConstants.DEFAULT_PASSWORD);
    }

    private static <T> T await(Future<T> future, long timeoutMillis) throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<T> resultRef = new AtomicReference<>();
        AtomicReference<Throwable> errorRef = new AtomicReference<>();

        future.onComplete(ar -> {
            if (ar.succeeded()) {
                resultRef.set(ar.result());
            } else {
                errorRef.set(ar.cause());
            }
            latch.countDown();
        });

        if (!latch.await(timeoutMillis, TimeUnit.MILLISECONDS)) {
            throw new RuntimeException("Timed out waiting for Vert.x SQL operation");
        }

        Throwable error = errorRef.get();
        if (error != null) {
            throw new RuntimeException(error);
        }
        return resultRef.get();
    }

    private static String readClasspathResource(String path) throws IOException {
        try (var is = PgTestSupport.class.getResourceAsStream(path)) {
            if (is == null) {
                throw new IOException("Resource not found on classpath: " + path);
            }
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
