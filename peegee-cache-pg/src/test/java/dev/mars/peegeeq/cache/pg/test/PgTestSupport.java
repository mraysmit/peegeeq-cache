package dev.mars.peegeeq.cache.pg.test;

import dev.mars.peegeeq.cache.pg.bootstrap.BootstrapSqlRenderer;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.PoolOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
    private final String schemaName;
    private PgTestPostgresContainer postgres;

    public PgTestSupport(String logLabel, String schemaName) {
        this.logLabel = logLabel;
        this.schemaName = schemaName;
    }

    public void start(Vertx vertx) throws Exception {
        postgres = SharedPostgresContainerManager.acquire(logLabel);
        log.info("Container '{}' using shared mapped port {}", logLabel, postgres.getFirstMappedPort());
        applyBootstrapSql(vertx);
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

    private void applyBootstrapSql(Vertx vertx) throws Exception {
        String sql = BootstrapSqlRenderer.loadForSchema(schemaName);
        Pool bootstrapPool = Pool.pool(vertx, connectOptions(), new PoolOptions().setMaxSize(1));
        try {
            await(bootstrapPool.query(sql).execute().mapEmpty(), 10_000);
        } finally {
            bootstrapPool.close();
        }
    }

    private void resetDatabaseState(Vertx vertx) throws Exception {
        Pool adminPool = Pool.pool(vertx, connectOptions(), new PoolOptions().setMaxSize(1));
        try {
            await(adminPool.query(
                    "TRUNCATE TABLE %s.cache_entries, %s.cache_counters, %s.cache_locks".formatted(schemaName, schemaName, schemaName))
                    .execute().mapEmpty(), 10_000);
            await(adminPool.query("ALTER SEQUENCE %s.lock_fencing_seq RESTART WITH 1".formatted(schemaName))
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

}
