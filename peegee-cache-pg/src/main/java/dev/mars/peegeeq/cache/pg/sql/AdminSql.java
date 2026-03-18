package dev.mars.peegeeq.cache.pg.sql;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * SQL statement constants for admin/stats queries.
 */
public final class AdminSql {

    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    public final String COUNT_LIVE_CACHE_ENTRIES;
    public final String COUNT_LIVE_COUNTERS;
    public final String COUNT_ACTIVE_LOCKS;

    private AdminSql(String schemaName) {
        String schema = requireSchema(schemaName);
        String entries = schema + ".cache_entries";
        String counters = schema + ".cache_counters";
        String locks = schema + ".cache_locks";

        COUNT_LIVE_CACHE_ENTRIES = """
                SELECT COUNT(*) FROM %s
                WHERE namespace = $1
                  AND (expires_at IS NULL OR expires_at > NOW())
                """.formatted(entries);

        COUNT_LIVE_COUNTERS = """
                SELECT COUNT(*) FROM %s
                WHERE namespace = $1
                  AND (expires_at IS NULL OR expires_at > NOW())
                """.formatted(counters);

        COUNT_ACTIVE_LOCKS = """
                SELECT COUNT(*) FROM %s
                WHERE namespace = $1
                  AND lease_expires_at > NOW()
                """.formatted(locks);
    }

    public static AdminSql forSchema(String schemaName) {
        return new AdminSql(schemaName);
    }

    private static String requireSchema(String schemaName) {
        String value = Objects.requireNonNull(schemaName, "schemaName").trim();
        if (!IDENTIFIER.matcher(value).matches()) {
            throw new IllegalArgumentException("Invalid PostgreSQL schema name: " + schemaName);
        }
        return value;
    }
}
