package dev.mars.peegeeq.cache.test;

import io.vertx.core.Vertx;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.PoolOptions;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/** Disposable PostgreSQL fixture with peegee-cache schema bootstrap. */
public final class PgTestSupport {

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

    public void start(Vertx vertx) throws Exception {
        postgres = SharedPostgresContainerManager.acquire(ownerLabel);
        try {
            execute(vertx, renderBootstrapSql());
            resetDatabaseState(vertx);
        } catch (Exception failure) {
            stop();
            throw failure;
        }
    }

    public void stop() {
        if (postgres != null) {
            SharedPostgresContainerManager.release(ownerLabel);
            postgres = null;
        }
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

    private void resetDatabaseState(Vertx vertx) throws Exception {
        execute(vertx, "TRUNCATE TABLE %s.cache_entries, %s.cache_counters, %s.cache_locks"
                .formatted(schemaName, schemaName, schemaName));
        execute(vertx, "ALTER SEQUENCE %s.lock_fencing_seq RESTART WITH 1".formatted(schemaName));
    }

    private void execute(Vertx vertx, String sql) throws Exception {
        Pool pool = Pool.pool(vertx, connectOptions(), new PoolOptions().setMaxSize(1));
        try {
            VertxAwait.await(pool.query(sql).execute().mapEmpty(), SQL_TIMEOUT);
        } finally {
            VertxAwait.await(pool.close(), SQL_TIMEOUT);
        }
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
