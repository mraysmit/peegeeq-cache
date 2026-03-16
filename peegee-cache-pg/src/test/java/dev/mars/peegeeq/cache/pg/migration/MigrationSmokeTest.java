package dev.mars.peegeeq.cache.pg.migration;

import dev.mars.peegeeq.cache.pg.test.PostgreSQLTestConstants;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.testcontainers.containers.PostgreSQLContainer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Applies the V001 migration SQL file against a real PostgreSQL instance
 * and verifies the resulting schema objects.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class MigrationSmokeTest {

    private static PostgreSQLContainer<?> postgres;

    @BeforeAll
    static void startContainerAndApplyMigrations() throws Exception {
        postgres = new PostgreSQLContainer<>(PostgreSQLTestConstants.POSTGRES_IMAGE)
                .withDatabaseName(PostgreSQLTestConstants.DEFAULT_DATABASE_NAME)
                .withUsername(PostgreSQLTestConstants.DEFAULT_USERNAME)
                .withPassword(PostgreSQLTestConstants.DEFAULT_PASSWORD)
                .withReuse(false);
        postgres.start();

        // Apply migration
        try (Connection conn = connect()) {
            String sql = readMigration("V001__create_peegee_cache_schema.sql");
            try (Statement stmt = conn.createStatement()) {
                stmt.execute(sql);
            }
        }
    }

    @AfterAll
    static void stopContainer() throws Exception {
        if (postgres != null) {
            postgres.stop();
            postgres = null;
        }
    }

    private static String jdbcUrl() {
        return postgres.getJdbcUrl();
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
        return DriverManager.getConnection(
                jdbcUrl(),
                PostgreSQLTestConstants.DEFAULT_USERNAME,
                PostgreSQLTestConstants.DEFAULT_PASSWORD
        );
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
