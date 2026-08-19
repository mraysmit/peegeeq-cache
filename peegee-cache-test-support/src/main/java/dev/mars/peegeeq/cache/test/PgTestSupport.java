package dev.mars.peegeeq.cache.test;

import io.vertx.core.AsyncResult;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.PoolOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/** Disposable PostgreSQL fixture with peegee-cache schema bootstrap. */
public final class PgTestSupport {

    private static final Logger log = LoggerFactory.getLogger(PgTestSupport.class);
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");
    private static final Duration SQL_TIMEOUT = Duration.ofSeconds(10);
    private final String ownerLabel;
    private final String schemaName;
    private PostgreSQLContainer postgres;

    public PgTestSupport(String ownerLabel, String schemaName) {
        this.ownerLabel = Objects.requireNonNull(ownerLabel, "ownerLabel");
        String schema = Objects.requireNonNull(schemaName, "schemaName").trim();
        if (!IDENTIFIER.matcher(schema).matches()) {
            throw new IllegalArgumentException("Invalid PostgreSQL schema name: " + schemaName);
        }
        this.schemaName = schema;
    }

    public Future<Void> start(Vertx vertx) {
        Objects.requireNonNull(vertx, "vertx");
        final String bootstrapSql;
        try {
            bootstrapSql = renderBootstrapSql();
        } catch (RuntimeException failure) {
            return Future.failedFuture(failure);
        }

        return vertx.executeBlocking(() -> {
                    postgres = SharedPostgresContainerManager.acquire(ownerLabel);
                    return null;
                })
                .compose(ignored -> initializeDatabase(vertx, bootstrapSql))
                .transform(startResult -> {
                    if (startResult.succeeded()) {
                        return Future.succeededFuture();
                    }
                    return stop(vertx).transform(stopResult -> failedAfterCleanup(startResult.cause(), stopResult));
                });
    }

    public Future<Void> stop(Vertx vertx) {
        Objects.requireNonNull(vertx, "vertx");
        if (postgres == null) {
            return Future.succeededFuture();
        }
        return vertx.executeBlocking(() -> {
            SharedPostgresContainerManager.release(ownerLabel);
            postgres = null;
            return null;
        });
    }

    public Future<Void> stopAfter(Vertx vertx, Future<Void> prerequisite) {
        Objects.requireNonNull(prerequisite, "prerequisite");
        return prerequisite.transform(prerequisiteResult -> stop(vertx)
                .transform(stopResult -> completeAfterCleanup(prerequisiteResult, stopResult)));
    }

    public Pool createPool(Vertx vertx) {
        return Pool.pool(vertx, connectOptions(), new PoolOptions().setMaxSize(4));
    }

    public PgConnectOptions connectOptions() {
        if (postgres == null) {
            throw new IllegalStateException("PgTestSupport has not been started");
        }
        return new PgConnectOptions()
                .setHost(postgres.getHost())
                .setPort(postgres.getFirstMappedPort())
                .setDatabase(PostgreSQLTestConstants.DEFAULT_DATABASE_NAME)
                .setUser(PostgreSQLTestConstants.DEFAULT_USERNAME)
                .setPassword(PostgreSQLTestConstants.DEFAULT_PASSWORD);
    }

    private Future<Void> initializeDatabase(Vertx vertx, String bootstrapSql) {
        Pool pool = Pool.pool(vertx, connectOptions(), new PoolOptions().setMaxSize(1));
        Future<Void> initialization = execute(pool, "bootstrap", bootstrapSql)
                .compose(ignored -> resetDatabaseState(pool));
        return initialization.transform(initializationResult -> observePhase("initialization.pool_close",
                        pool.close().timeout(SQL_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS))
                .transform(closeResult -> completeAfterCleanup(initializationResult, closeResult)));
    }

    private Future<Void> resetDatabaseState(Pool pool) {
        return execute(pool, "reset.truncate",
                "TRUNCATE TABLE %s.cache_entries, %s.cache_counters, %s.cache_locks"
                        .formatted(schemaName, schemaName, schemaName))
                .compose(ignored -> execute(pool, "reset.lock_fencing_sequence",
                        "ALTER SEQUENCE %s.lock_fencing_seq RESTART WITH 1".formatted(schemaName)));
    }

    private Future<Void> execute(Pool pool, String phase, String sql) {
        return observePhase(phase, pool.query(sql).execute().<Void>mapEmpty()
                .timeout(SQL_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS));
    }

    private Future<Void> observePhase(String phase, Future<Void> operation) {
        long startedAt = System.nanoTime();
        return operation
                .onSuccess(ignored -> logPhaseCompleted(phase, startedAt))
                .onFailure(failure -> logPhaseFailed(phase, startedAt, failure));
    }

    private static Future<Void> completeAfterCleanup(
            AsyncResult<Void> primaryResult,
            AsyncResult<Void> cleanupResult) {
        if (primaryResult.succeeded() && cleanupResult.succeeded()) {
            return Future.succeededFuture();
        }
        if (primaryResult.failed()) {
            return failedAfterCleanup(primaryResult.cause(), cleanupResult);
        }
        return Future.failedFuture(cleanupResult.cause());
    }

    private static Future<Void> failedAfterCleanup(Throwable primaryFailure, AsyncResult<Void> cleanupResult) {
        if (cleanupResult.failed() && cleanupResult.cause() != primaryFailure) {
            primaryFailure.addSuppressed(cleanupResult.cause());
        }
        return Future.failedFuture(primaryFailure);
    }

    private void logPhaseCompleted(String phase, long startedAt) {
        log.atInfo()
                .addKeyValue("test.suite", ownerLabel)
                .addKeyValue("schema", schemaName)
                .addKeyValue("phase", phase)
                .addKeyValue("elapsed_ms", elapsedMillis(startedAt))
                .log("test.postgres_fixture.phase.completed");
    }

    private void logPhaseFailed(String phase, long startedAt, Throwable failure) {
        log.atError()
                .setCause(failure)
                .addKeyValue("test.suite", ownerLabel)
                .addKeyValue("schema", schemaName)
                .addKeyValue("phase", phase)
                .addKeyValue("elapsed_ms", elapsedMillis(startedAt))
                .log("test.postgres_fixture.phase.failed");
    }

    private static long elapsedMillis(long startedAt) {
        return Duration.ofNanos(System.nanoTime() - startedAt).toMillis();
    }

    private String renderBootstrapSql() {
        List<String> resources = List.of(
                "/db/bootstrap/V001__create_peegee_cache_schema.sql");
        StringBuilder sql = new StringBuilder();
        for (String resource : resources) {
            try (InputStream input = PgTestSupport.class.getResourceAsStream(resource)) {
                if (input == null) {
                    throw new IllegalStateException(
                            "peegee-cache migration SQL is not on the test classpath: " + resource);
                }
                sql.append(new String(input.readAllBytes(), StandardCharsets.UTF_8)).append('\n');
            } catch (IOException failure) {
                throw new IllegalStateException("Failed to load peegee-cache migration SQL: " + resource, failure);
            }
        }
        return sql.toString().replace("peegee_cache", schemaName);
    }
}
