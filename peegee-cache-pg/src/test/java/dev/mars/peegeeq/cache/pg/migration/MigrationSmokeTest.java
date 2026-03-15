package dev.mars.peegeeq.cache.pg.migration;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Applies all V001–V006 migration SQL files against a real PostgreSQL instance
 * and verifies the resulting schema objects.
 * <p>
 * Starts a disposable PostgreSQL container via the Docker CLI to work around
 * Testcontainers docker-java incompatibility with Docker Engine 29
 * (minimum API version 1.44). Will be switched back to {@code @Testcontainers}
 * once docker-java/Testcontainers ships a compatible release.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class MigrationSmokeTest {

    private static final String CONTAINER_NAME = "pgcache-migration-test";
    private static final String PG_IMAGE = "postgres:16-alpine";
    private static final String PG_USER = "test";
    private static final String PG_PASSWORD = "test";
    private static final String PG_DB = "testdb";
    private static int pgPort;

    @BeforeAll
    static void startContainerAndApplyMigrations() throws Exception {
        // Remove any leftover container from a previous run
        exec("docker", "rm", "-f", CONTAINER_NAME);

        // Start PostgreSQL container with a random host port
        exec("docker", "run", "-d", "--name", CONTAINER_NAME,
                "-e", "POSTGRES_USER=" + PG_USER,
                "-e", "POSTGRES_PASSWORD=" + PG_PASSWORD,
                "-e", "POSTGRES_DB=" + PG_DB,
                "-p", "0:5432",
                PG_IMAGE);

        // Discover the mapped host port
        String portOutput = execOutput("docker", "port", CONTAINER_NAME, "5432");
        // Output is like "0.0.0.0:12345" or "0.0.0.0:12345\n:::12345"
        String firstLine = portOutput.strip().split("\\R")[0];
        pgPort = Integer.parseInt(firstLine.substring(firstLine.lastIndexOf(':') + 1));

        // Wait for PostgreSQL to become ready
        waitForPostgres();

        // Apply migrations
        String[] migrations = {
                "V001__create_schema.sql",
                "V002__create_cache_entries.sql",
                "V003__create_cache_counters.sql",
                "V004__create_cache_locks.sql",
                "V005__create_lock_fencing_sequence.sql",
                "V006__create_indexes.sql"
        };
        try (Connection conn = connect()) {
            for (String file : migrations) {
                String sql = readMigration(file);
                try (Statement stmt = conn.createStatement()) {
                    stmt.execute(sql);
                }
            }
        }
    }

    @AfterAll
    static void stopContainer() throws Exception {
        exec("docker", "rm", "-f", CONTAINER_NAME);
    }

    private static void waitForPostgres() throws Exception {
        String url = jdbcUrl();
        long deadline = System.currentTimeMillis() + 30_000;
        while (System.currentTimeMillis() < deadline) {
            try (Connection conn = DriverManager.getConnection(url, PG_USER, PG_PASSWORD)) {
                try (Statement stmt = conn.createStatement()) {
                    stmt.execute("SELECT 1");
                }
                return;
            } catch (SQLException ignored) {
                Thread.sleep(500);
            }
        }
        fail("PostgreSQL did not become ready within 30 seconds");
    }

    private static String exec(String... cmd) throws Exception {
        Process p = new ProcessBuilder(cmd)
                .redirectErrorStream(true)
                .start();
        String output;
        try (var reader = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            output = reader.lines().reduce("", (a, b) -> a + b + "\n");
        }
        p.waitFor(30, TimeUnit.SECONDS);
        return output;
    }

    private static String execOutput(String... cmd) throws Exception {
        Process p = new ProcessBuilder(cmd)
                .redirectErrorStream(true)
                .start();
        String output;
        try (var reader = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            output = reader.lines().reduce("", (a, b) -> a + b + "\n");
        }
        if (!p.waitFor(30, TimeUnit.SECONDS) || p.exitValue() != 0) {
            throw new RuntimeException("Command failed: " + String.join(" ", cmd) + "\n" + output);
        }
        return output;
    }

    private static String jdbcUrl() {
        return "jdbc:postgresql://127.0.0.1:" + pgPort + "/" + PG_DB;
    }

    private static String readMigration(String fileName) throws IOException {
        String path = "/db/migration/" + fileName;
        try (var is = MigrationSmokeTest.class.getResourceAsStream(path)) {
            if (is == null) {
                throw new IOException("Migration file not found on classpath: " + path);
            }
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    // --- Schema ---

    @Test
    @Order(1)
    void schemaExists() throws SQLException {
        try (Connection conn = connect(); Statement stmt = conn.createStatement()) {
            ResultSet rs = stmt.executeQuery(
                    "SELECT 1 FROM information_schema.schemata WHERE schema_name = 'peegee_cache'");
            assertTrue(rs.next(), "peegee_cache schema must exist");
        }
    }

    // --- Tables ---

    @Test
    @Order(2)
    void cacheEntriesTableExists() throws SQLException {
        assertTableExists("cache_entries");
    }

    @Test
    @Order(3)
    void cacheCountersTableExists() throws SQLException {
        assertTableExists("cache_counters");
    }

    @Test
    @Order(4)
    void cacheLocksTableExists() throws SQLException {
        assertTableExists("cache_locks");
    }

    // --- Column verification ---

    @Test
    @Order(10)
    void cacheEntriesColumns() throws SQLException {
        List<String> expected = List.of(
                "namespace", "cache_key", "value_type", "value_bytes", "numeric_value",
                "version", "created_at", "updated_at", "expires_at",
                "hit_count", "last_accessed_at");
        List<String> actual = columnsOf("cache_entries");
        assertEquals(expected, actual);
    }

    @Test
    @Order(11)
    void cacheCountersColumns() throws SQLException {
        List<String> expected = List.of(
                "namespace", "counter_key", "counter_value",
                "version", "created_at", "updated_at", "expires_at");
        List<String> actual = columnsOf("cache_counters");
        assertEquals(expected, actual);
    }

    @Test
    @Order(12)
    void cacheLocksColumns() throws SQLException {
        List<String> expected = List.of(
                "namespace", "lock_key", "owner_token", "fencing_token",
                "version", "created_at", "updated_at", "lease_expires_at");
        List<String> actual = columnsOf("cache_locks");
        assertEquals(expected, actual);
    }

    // --- Primary keys ---

    @Test
    @Order(20)
    void cacheEntriesPrimaryKey() throws SQLException {
        List<String> pkCols = primaryKeyColumns("cache_entries");
        assertEquals(List.of("namespace", "cache_key"), pkCols);
    }

    @Test
    @Order(21)
    void cacheCountersPrimaryKey() throws SQLException {
        List<String> pkCols = primaryKeyColumns("cache_counters");
        assertEquals(List.of("namespace", "counter_key"), pkCols);
    }

    @Test
    @Order(22)
    void cacheLocksPrimaryKey() throws SQLException {
        List<String> pkCols = primaryKeyColumns("cache_locks");
        assertEquals(List.of("namespace", "lock_key"), pkCols);
    }

    // --- Check constraints ---

    @Test
    @Order(30)
    void cacheEntriesValueTypeConstraint() throws SQLException {
        assertCheckConstraintExists("cache_entries", "chk_cache_entries_value_type");
    }

    @Test
    @Order(31)
    void cacheEntriesValuePayloadConstraint() throws SQLException {
        assertCheckConstraintExists("cache_entries", "chk_cache_entries_value_payload");
    }

    @Test
    @Order(32)
    void cacheLocksLeaseConstraint() throws SQLException {
        assertCheckConstraintExists("cache_locks", "chk_cache_locks_future_lease");
    }

    // --- Sequence ---

    @Test
    @Order(40)
    void lockFencingSequenceExists() throws SQLException {
        try (Connection conn = connect(); Statement stmt = conn.createStatement()) {
            ResultSet rs = stmt.executeQuery(
                    "SELECT 1 FROM information_schema.sequences " +
                    "WHERE sequence_schema = 'peegee_cache' AND sequence_name = 'lock_fencing_seq'");
            assertTrue(rs.next(), "lock_fencing_seq sequence must exist");
        }
    }

    @Test
    @Order(41)
    void lockFencingSequenceReturnsMonotonicValues() throws SQLException {
        try (Connection conn = connect(); Statement stmt = conn.createStatement()) {
            ResultSet rs1 = stmt.executeQuery("SELECT NEXTVAL('peegee_cache.lock_fencing_seq')");
            assertTrue(rs1.next());
            long first = rs1.getLong(1);

            ResultSet rs2 = stmt.executeQuery("SELECT NEXTVAL('peegee_cache.lock_fencing_seq')");
            assertTrue(rs2.next());
            long second = rs2.getLong(1);

            assertTrue(second > first, "Fencing sequence must be monotonically increasing");
        }
    }

    // --- Indexes ---

    @Test
    @Order(50)
    void cacheEntriesExpiresAtIndex() throws SQLException {
        assertIndexExists("idx_cache_entries_expires_at");
    }

    @Test
    @Order(51)
    void cacheEntriesNamespaceKeyPatternIndex() throws SQLException {
        assertIndexExists("idx_cache_entries_namespace_key_pattern");
    }

    @Test
    @Order(52)
    void cacheCountersExpiresAtIndex() throws SQLException {
        assertIndexExists("idx_cache_counters_expires_at");
    }

    @Test
    @Order(53)
    void cacheCountersNamespaceKeyPatternIndex() throws SQLException {
        assertIndexExists("idx_cache_counters_namespace_key_pattern");
    }

    @Test
    @Order(54)
    void cacheLocksLeaseExpiresAtIndex() throws SQLException {
        assertIndexExists("idx_cache_locks_lease_expires_at");
    }

    // --- Constraint enforcement: cache_entries ---

    @Test
    @Order(60)
    void cacheEntriesRejectsInvalidValueType() throws SQLException {
        try (Connection conn = connect(); Statement stmt = conn.createStatement()) {
            boolean threw = false;
            try {
                stmt.execute(
                        "INSERT INTO peegee_cache.cache_entries " +
                        "(namespace, cache_key, value_type, value_bytes) " +
                        "VALUES ('ns', 'k1', 'INVALID', '\\x01')");
            } catch (SQLException e) {
                threw = true;
            }
            assertTrue(threw, "Should reject invalid value_type");
        }
    }

    @Test
    @Order(61)
    void cacheEntriesRejectsLongWithBytes() throws SQLException {
        try (Connection conn = connect(); Statement stmt = conn.createStatement()) {
            boolean threw = false;
            try {
                stmt.execute(
                        "INSERT INTO peegee_cache.cache_entries " +
                        "(namespace, cache_key, value_type, value_bytes, numeric_value) " +
                        "VALUES ('ns', 'k2', 'LONG', '\\x01', 42)");
            } catch (SQLException e) {
                threw = true;
            }
            assertTrue(threw, "LONG type must not have value_bytes");
        }
    }

    @Test
    @Order(62)
    void cacheEntriesRejectsBytesWithNumeric() throws SQLException {
        try (Connection conn = connect(); Statement stmt = conn.createStatement()) {
            boolean threw = false;
            try {
                stmt.execute(
                        "INSERT INTO peegee_cache.cache_entries " +
                        "(namespace, cache_key, value_type, numeric_value) " +
                        "VALUES ('ns', 'k3', 'BYTES', 42)");
            } catch (SQLException e) {
                threw = true;
            }
            assertTrue(threw, "BYTES type must not have numeric_value without value_bytes");
        }
    }

    @Test
    @Order(63)
    void cacheEntriesAcceptsValidLong() throws SQLException {
        try (Connection conn = connect(); Statement stmt = conn.createStatement()) {
            stmt.execute(
                    "INSERT INTO peegee_cache.cache_entries " +
                    "(namespace, cache_key, value_type, numeric_value) " +
                    "VALUES ('ns', 'valid-long', 'LONG', 100)");
            // cleanup
            stmt.execute(
                    "DELETE FROM peegee_cache.cache_entries " +
                    "WHERE namespace = 'ns' AND cache_key = 'valid-long'");
        }
    }

    @Test
    @Order(64)
    void cacheEntriesAcceptsValidString() throws SQLException {
        try (Connection conn = connect(); Statement stmt = conn.createStatement()) {
            stmt.execute(
                    "INSERT INTO peegee_cache.cache_entries " +
                    "(namespace, cache_key, value_type, value_bytes) " +
                    "VALUES ('ns', 'valid-str', 'STRING', 'hello')");
            stmt.execute(
                    "DELETE FROM peegee_cache.cache_entries " +
                    "WHERE namespace = 'ns' AND cache_key = 'valid-str'");
        }
    }

    // --- Constraint enforcement: cache_locks ---

    @Test
    @Order(70)
    void cacheLocksRejectsPastLease() throws SQLException {
        try (Connection conn = connect(); Statement stmt = conn.createStatement()) {
            boolean threw = false;
            try {
                stmt.execute(
                        "INSERT INTO peegee_cache.cache_locks " +
                        "(namespace, lock_key, owner_token, lease_expires_at, updated_at) " +
                        "VALUES ('ns', 'lk1', 'owner1', NOW() - INTERVAL '1 hour', NOW())");
            } catch (SQLException e) {
                threw = true;
            }
            assertTrue(threw, "Should reject lease_expires_at <= updated_at");
        }
    }

    @Test
    @Order(71)
    void cacheLocksAcceptsValidLease() throws SQLException {
        try (Connection conn = connect(); Statement stmt = conn.createStatement()) {
            stmt.execute(
                    "INSERT INTO peegee_cache.cache_locks " +
                    "(namespace, lock_key, owner_token, lease_expires_at) " +
                    "VALUES ('ns', 'valid-lock', 'owner1', NOW() + INTERVAL '1 hour')");
            stmt.execute(
                    "DELETE FROM peegee_cache.cache_locks " +
                    "WHERE namespace = 'ns' AND lock_key = 'valid-lock'");
        }
    }

    // --- Helpers ---

    private static Connection connect() throws SQLException {
        return DriverManager.getConnection(jdbcUrl(), PG_USER, PG_PASSWORD);
    }

    private void assertTableExists(String tableName) throws SQLException {
        try (Connection conn = connect(); Statement stmt = conn.createStatement()) {
            ResultSet rs = stmt.executeQuery(
                    "SELECT 1 FROM information_schema.tables " +
                    "WHERE table_schema = 'peegee_cache' AND table_name = '" + tableName + "'");
            assertTrue(rs.next(), "Table peegee_cache." + tableName + " must exist");
        }
    }

    private List<String> columnsOf(String tableName) throws SQLException {
        try (Connection conn = connect(); Statement stmt = conn.createStatement()) {
            ResultSet rs = stmt.executeQuery(
                    "SELECT column_name FROM information_schema.columns " +
                    "WHERE table_schema = 'peegee_cache' AND table_name = '" + tableName + "' " +
                    "ORDER BY ordinal_position");
            List<String> cols = new ArrayList<>();
            while (rs.next()) {
                cols.add(rs.getString(1));
            }
            return cols;
        }
    }

    private List<String> primaryKeyColumns(String tableName) throws SQLException {
        try (Connection conn = connect(); Statement stmt = conn.createStatement()) {
            ResultSet rs = stmt.executeQuery(
                    "SELECT kcu.column_name " +
                    "FROM information_schema.table_constraints tc " +
                    "JOIN information_schema.key_column_usage kcu " +
                    "  ON tc.constraint_name = kcu.constraint_name " +
                    "  AND tc.table_schema = kcu.table_schema " +
                    "WHERE tc.table_schema = 'peegee_cache' " +
                    "  AND tc.table_name = '" + tableName + "' " +
                    "  AND tc.constraint_type = 'PRIMARY KEY' " +
                    "ORDER BY kcu.ordinal_position");
            List<String> cols = new ArrayList<>();
            while (rs.next()) {
                cols.add(rs.getString(1));
            }
            return cols;
        }
    }

    private void assertCheckConstraintExists(String tableName, String constraintName) throws SQLException {
        try (Connection conn = connect(); Statement stmt = conn.createStatement()) {
            ResultSet rs = stmt.executeQuery(
                    "SELECT 1 FROM information_schema.table_constraints " +
                    "WHERE table_schema = 'peegee_cache' " +
                    "  AND table_name = '" + tableName + "' " +
                    "  AND constraint_name = '" + constraintName + "' " +
                    "  AND constraint_type = 'CHECK'");
            assertTrue(rs.next(),
                    "CHECK constraint " + constraintName + " must exist on " + tableName);
        }
    }

    private void assertIndexExists(String indexName) throws SQLException {
        try (Connection conn = connect(); Statement stmt = conn.createStatement()) {
            ResultSet rs = stmt.executeQuery(
                    "SELECT 1 FROM pg_indexes " +
                    "WHERE schemaname = 'peegee_cache' AND indexname = '" + indexName + "'");
            assertTrue(rs.next(), "Index " + indexName + " must exist");
        }
    }
}
