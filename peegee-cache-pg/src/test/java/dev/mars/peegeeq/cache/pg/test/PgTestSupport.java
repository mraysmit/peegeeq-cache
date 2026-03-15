package dev.mars.peegeeq.cache.pg.test;

import io.vertx.core.Vertx;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.PoolOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.TimeUnit;

/**
 * Manages a disposable PostgreSQL container via Docker CLI and provides
 * a Vert.x PgPool for repository integration tests.
 * <p>
 * Uses Docker CLI (ProcessBuilder) instead of Testcontainers docker-java
 * due to Docker Engine 29 incompatibility with docker-java 3.4.2.
 */
public final class PgTestSupport {

    private static final Logger log = LoggerFactory.getLogger(PgTestSupport.class);
    private static final String PG_IMAGE = "postgres:16-alpine";
    private static final String PG_USER = "test";
    private static final String PG_PASSWORD = "test";
    private static final String PG_DB = "testdb";

    private final String containerName;
    private int pgPort;

    public PgTestSupport(String containerName) {
        this.containerName = containerName;
    }

    public void start() throws Exception {
        log.info("Starting PostgreSQL container '{}' (image: {})", containerName, PG_IMAGE);
        exec("docker", "rm", "-f", containerName);

        exec("docker", "run", "-d", "--name", containerName,
                "-e", "POSTGRES_USER=" + PG_USER,
                "-e", "POSTGRES_PASSWORD=" + PG_PASSWORD,
                "-e", "POSTGRES_DB=" + PG_DB,
                "-p", "0:5432",
                PG_IMAGE);

        String portOutput = execOutput("docker", "port", containerName, "5432");
        String firstLine = portOutput.strip().split("\\R")[0];
        pgPort = Integer.parseInt(firstLine.substring(firstLine.lastIndexOf(':') + 1));
        log.info("Container '{}' mapped to port {}", containerName, pgPort);

        waitForPostgres();
        applyMigrations();
        log.info("Container '{}' ready", containerName);
    }

    public void stop() throws Exception {
        log.info("Stopping container '{}'", containerName);
        exec("docker", "rm", "-f", containerName);
    }

    public Pool createPool(Vertx vertx) {
        PgConnectOptions connectOptions = new PgConnectOptions()
                .setHost("127.0.0.1")
                .setPort(pgPort)
                .setDatabase(PG_DB)
                .setUser(PG_USER)
                .setPassword(PG_PASSWORD);

        PoolOptions poolOptions = new PoolOptions().setMaxSize(4);

        return Pool.pool(vertx, connectOptions, poolOptions);
    }

    /** JDBC connection for test setup/verification outside of Vert.x. */
    public Connection jdbcConnection() throws SQLException {
        return DriverManager.getConnection(
                "jdbc:postgresql://127.0.0.1:" + pgPort + "/" + PG_DB, PG_USER, PG_PASSWORD);
    }

    private void waitForPostgres() throws Exception {
        long deadline = System.currentTimeMillis() + 30_000;
        while (System.currentTimeMillis() < deadline) {
            try (Connection conn = jdbcConnection()) {
                try (Statement stmt = conn.createStatement()) {
                    stmt.execute("SELECT 1");
                }
                return;
            } catch (SQLException ignored) {
                Thread.sleep(500);
            }
        }
        throw new RuntimeException("PostgreSQL did not become ready within 30 seconds");
    }

    private void applyMigrations() throws Exception {
        String sql = readClasspathResource("/db/migration/V001__create_peegee_cache_schema.sql");
        try (Connection conn = jdbcConnection(); Statement stmt = conn.createStatement()) {
            stmt.execute(sql);
        }
    }

    private static String readClasspathResource(String path) throws IOException {
        try (var is = PgTestSupport.class.getResourceAsStream(path)) {
            if (is == null) {
                throw new IOException("Resource not found on classpath: " + path);
            }
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static String exec(String... cmd) throws Exception {
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        String output;
        try (var reader = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            output = reader.lines().reduce("", (a, b) -> a + b + "\n");
        }
        p.waitFor(30, TimeUnit.SECONDS);
        return output;
    }

    private static String execOutput(String... cmd) throws Exception {
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        String output;
        try (var reader = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            output = reader.lines().reduce("", (a, b) -> a + b + "\n");
        }
        if (!p.waitFor(30, TimeUnit.SECONDS) || p.exitValue() != 0) {
            throw new RuntimeException("Command failed: " + String.join(" ", cmd) + "\n" + output);
        }
        return output;
    }
}
