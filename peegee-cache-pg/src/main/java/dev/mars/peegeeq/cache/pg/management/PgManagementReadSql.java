package dev.mars.peegeeq.cache.pg.management;

import java.util.Objects;
import java.util.regex.Pattern;

/** Schema-qualified, parameterized SQL for management metadata inspection. */
final class PgManagementReadSql {

    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    final String namespaceStats;
    final String schemaName;
    final String namespaceDistributions;
    final String namespacesAscending;
    final String namespacesByEntryCount;
    final String entries;
    final String entry;
    final String counters;
    final String counter;
    final String locks;
    final String lock;
    final String databaseStats;
    final String expiryStats;

    PgManagementReadSql(String schemaName) {
        String schema = requireSchema(schemaName);
        this.schemaName = schema;
        namespaceStats = """
                SELECT $1::TEXT AS namespace,
                       COUNT(*) FILTER (
                           WHERE e.expires_at IS NULL OR e.expires_at > statement_timestamp()
                       )::BIGINT AS live_entry_count,
                       (SELECT COUNT(*)::BIGINT
                          FROM %1$s.cache_counters c
                         WHERE c.namespace = $1
                           AND (c.expires_at IS NULL OR c.expires_at > statement_timestamp()))
                           AS live_counter_count,
                       (SELECT COUNT(*)::BIGINT
                          FROM %1$s.cache_locks l
                         WHERE l.namespace = $1
                           AND l.lease_expires_at > statement_timestamp())
                           AS active_lock_count,
                       COUNT(*) FILTER (
                           WHERE e.expires_at > statement_timestamp()
                       )::BIGINT AS expiring_entry_count,
                       COUNT(*) FILTER (
                           WHERE e.expires_at IS NOT NULL
                             AND e.expires_at <= statement_timestamp()
                       )::BIGINT AS expired_entry_count,
                       COALESCE(SUM(pg_column_size(e)) FILTER (
                           WHERE e.expires_at IS NULL OR e.expires_at > statement_timestamp()
                       ), 0)::BIGINT AS estimated_storage_bytes,
                       statement_timestamp() AS observed_at
                  FROM %1$s.cache_entries e
                 WHERE e.namespace = $1
                """.formatted(schema);
        namespaceDistributions = """
                SELECT value_type,
                       CASE
                         WHEN expires_at IS NULL THEN 'PERSISTENT'
                         WHEN expires_at > statement_timestamp() THEN 'EXPIRING'
                         ELSE 'EXPIRED'
                       END AS ttl_state,
                       COUNT(*)::BIGINT AS item_count
                  FROM %1$s.cache_entries
                 WHERE namespace = $1
                 GROUP BY value_type, ttl_state
                """.formatted(schema);

        String namespaceAggregate = """
                WITH namespaces AS (
                    SELECT namespace FROM %1$s.cache_entries
                    UNION SELECT namespace FROM %1$s.cache_counters
                    UNION SELECT namespace FROM %1$s.cache_locks
                ), entry_stats AS (
                    SELECT namespace,
                           COUNT(*) FILTER (WHERE expires_at IS NULL OR expires_at > statement_timestamp())::BIGINT
                               AS live_entry_count,
                           COUNT(*) FILTER (WHERE expires_at > statement_timestamp())::BIGINT
                               AS expiring_entry_count,
                           COUNT(*) FILTER (WHERE expires_at IS NOT NULL
                                             AND expires_at <= statement_timestamp())::BIGINT
                               AS expired_entry_count,
                           COALESCE(SUM(pg_column_size(cache_entries)) FILTER (
                               WHERE expires_at IS NULL OR expires_at > statement_timestamp()), 0)::BIGINT
                               AS estimated_storage_bytes
                      FROM %1$s.cache_entries
                     GROUP BY namespace
                ), counter_stats AS (
                    SELECT namespace,
                           COUNT(*) FILTER (WHERE expires_at IS NULL OR expires_at > statement_timestamp())::BIGINT
                               AS live_counter_count
                      FROM %1$s.cache_counters
                     GROUP BY namespace
                ), lock_stats AS (
                    SELECT namespace,
                           COUNT(*) FILTER (WHERE lease_expires_at > statement_timestamp())::BIGINT
                               AS active_lock_count
                      FROM %1$s.cache_locks
                     GROUP BY namespace
                ), stats AS (
                    SELECT n.namespace,
                           COALESCE(e.live_entry_count, 0)::BIGINT AS live_entry_count,
                           COALESCE(c.live_counter_count, 0)::BIGINT AS live_counter_count,
                           COALESCE(l.active_lock_count, 0)::BIGINT AS active_lock_count,
                           COALESCE(e.expiring_entry_count, 0)::BIGINT AS expiring_entry_count,
                           COALESCE(e.expired_entry_count, 0)::BIGINT AS expired_entry_count,
                           COALESCE(e.estimated_storage_bytes, 0)::BIGINT AS estimated_storage_bytes,
                           statement_timestamp() AS observed_at
                      FROM namespaces n
                      LEFT JOIN entry_stats e USING (namespace)
                      LEFT JOIN counter_stats c USING (namespace)
                      LEFT JOIN lock_stats l USING (namespace)
                )
                """.formatted(schema);
        String filters = """
                 WHERE ($1::TEXT IS NULL OR namespace LIKE $1 ESCAPE '\\')
                   AND ($2::TEXT IS NULL
                        OR ($2 = 'READY' AND expired_entry_count = 0
                            AND live_entry_count + live_counter_count + active_lock_count > 0)
                        OR ($2 = 'EMPTY' AND live_entry_count + live_counter_count + active_lock_count = 0)
                        OR ($2 = 'EXPIRY_BACKLOG' AND expired_entry_count > 0))
                """;
        namespacesAscending = namespaceAggregate + """
                SELECT * FROM stats
                """ + filters + """
                   AND ($3::TEXT IS NULL OR namespace COLLATE "C" > $3 COLLATE "C")
                 ORDER BY namespace COLLATE "C" ASC
                 LIMIT $5
                """;
        namespacesByEntryCount = namespaceAggregate + """
                SELECT * FROM stats
                """ + filters + """
                   AND ($4::BIGINT IS NULL OR live_entry_count < $4
                        OR (live_entry_count = $4 AND namespace COLLATE "C" > $3 COLLATE "C"))
                 ORDER BY live_entry_count DESC, namespace COLLATE "C" ASC
                 LIMIT $5
                """;

        String entryProjection = """
                SELECT namespace, cache_key, value_type,
                       CASE WHEN value_type = 'LONG' THEN 8::BIGINT
                            ELSE octet_length(value_bytes)::BIGINT END AS size_bytes,
                       version, created_at, updated_at, expires_at,
                       CASE
                         WHEN expires_at IS NULL THEN NULL
                         WHEN expires_at <= statement_timestamp() THEN 0::BIGINT
                         ELSE GREATEST(1, FLOOR(EXTRACT(EPOCH FROM
                             (expires_at - statement_timestamp())) * 1000)::BIGINT)
                       END AS ttl_millis
                  FROM %1$s.cache_entries
                """.formatted(schema);
        entries = entryProjection + """
                 WHERE namespace = $1
                   AND ($2::TEXT IS NULL OR cache_key LIKE $2 ESCAPE '\\')
                   AND ($3::TEXT IS NULL OR value_type = $3)
                   AND (($4 = 'INCLUDE_EXPIRED')
                        OR (expires_at IS NULL OR expires_at > statement_timestamp()))
                   AND (($4 NOT IN ('PERSISTENT', 'EXPIRING'))
                        OR ($4 = 'PERSISTENT' AND expires_at IS NULL)
                        OR ($4 = 'EXPIRING' AND expires_at > statement_timestamp()))
                   AND ($5::TEXT IS NULL OR cache_key COLLATE "C" > $5 COLLATE "C")
                 ORDER BY cache_key COLLATE "C" ASC
                 LIMIT $6
                """;
        entry = entryProjection + """
                 WHERE namespace = $1
                   AND cache_key = $2
                   AND ($3::BOOLEAN OR expires_at IS NULL OR expires_at > statement_timestamp())
                """;

        String counterProjection = """
                SELECT namespace, counter_key, counter_value, version,
                       created_at, updated_at, expires_at,
                       CASE
                         WHEN expires_at IS NULL THEN NULL
                         WHEN expires_at <= statement_timestamp() THEN 0::BIGINT
                         ELSE GREATEST(1, FLOOR(EXTRACT(EPOCH FROM
                             (expires_at - statement_timestamp())) * 1000)::BIGINT)
                       END AS ttl_millis
                  FROM %1$s.cache_counters
                """.formatted(schema);
        counters = counterProjection + """
                 WHERE ($1::TEXT IS NULL OR namespace = $1)
                   AND ($2::TEXT IS NULL OR counter_key LIKE $2 ESCAPE '\\')
                   AND (($3 = 'INCLUDE_EXPIRED')
                        OR (expires_at IS NULL OR expires_at > statement_timestamp()))
                   AND (($3 NOT IN ('PERSISTENT', 'EXPIRING'))
                        OR ($3 = 'PERSISTENT' AND expires_at IS NULL)
                        OR ($3 = 'EXPIRING' AND expires_at > statement_timestamp()))
                   AND ($4::TEXT IS NULL
                        OR namespace COLLATE "C" > $4 COLLATE "C"
                        OR (namespace = $4 AND counter_key COLLATE "C" > $5 COLLATE "C"))
                 ORDER BY namespace COLLATE "C" ASC, counter_key COLLATE "C" ASC
                 LIMIT $6
                """;
        counter = counterProjection + """
                 WHERE namespace = $1
                   AND counter_key = $2
                   AND (expires_at IS NULL OR expires_at > statement_timestamp())
                """;

        String lockProjection = """
                SELECT namespace, lock_key, COALESCE(fencing_token, 0)::BIGINT AS fencing_token,
                       version, created_at, updated_at, lease_expires_at,
                       GREATEST(1, FLOOR(EXTRACT(EPOCH FROM
                           (lease_expires_at - statement_timestamp())) * 1000)::BIGINT)
                           AS lease_remaining_millis
                  FROM %1$s.cache_locks
                """.formatted(schema);
        locks = lockProjection + """
                 WHERE lease_expires_at > statement_timestamp()
                   AND ($1::TEXT IS NULL OR namespace = $1)
                   AND ($2::TEXT IS NULL OR lock_key LIKE $2 ESCAPE '\\')
                   AND ($3 = 'ACTIVE'
                        OR lease_expires_at <= statement_timestamp() + INTERVAL '60 seconds')
                   AND ($4::TEXT IS NULL
                        OR namespace COLLATE "C" > $4 COLLATE "C"
                        OR (namespace = $4 AND lock_key COLLATE "C" > $5 COLLATE "C"))
                 ORDER BY namespace COLLATE "C" ASC, lock_key COLLATE "C" ASC
                 LIMIT $6
                """;
        lock = lockProjection + """
                 WHERE namespace = $1
                   AND lock_key = $2
                   AND lease_expires_at > statement_timestamp()
                """;

        databaseStats = """
                SELECT statement_timestamp() AS observed_at,
                       to_regnamespace($1) IS NOT NULL AS schema_ready,
                       pg_database_size(current_database())::BIGINT AS database_bytes,
                       COALESCE((
                           SELECT SUM(pg_total_relation_size(c.oid))::BIGINT
                             FROM pg_class c
                             JOIN pg_namespace n ON n.oid = c.relnamespace
                            WHERE n.nspname = $1
                              AND c.relkind IN ('r', 'i', 'S', 't', 'm')
                       ), 0)::BIGINT AS schema_bytes
                """;
        expiryStats = """
                WITH expired AS (
                    SELECT expires_at
                      FROM %1$s.cache_entries
                     WHERE expires_at IS NOT NULL
                       AND expires_at <= statement_timestamp()
                    UNION ALL
                    SELECT expires_at
                      FROM %1$s.cache_counters
                     WHERE expires_at IS NOT NULL
                       AND expires_at <= statement_timestamp()
                )
                SELECT statement_timestamp() AS observed_at,
                       (SELECT COUNT(*)::BIGINT FROM %1$s.cache_entries
                         WHERE expires_at IS NOT NULL
                           AND expires_at <= statement_timestamp()) AS expired_entry_count,
                       (SELECT COUNT(*)::BIGINT FROM %1$s.cache_counters
                         WHERE expires_at IS NOT NULL
                           AND expires_at <= statement_timestamp()) AS expired_counter_count,
                       COALESCE(FLOOR(EXTRACT(EPOCH FROM
                           (statement_timestamp() - MIN(expires_at))) * 1000)::BIGINT, 0)
                           AS oldest_lag_millis
                  FROM expired
                """.formatted(schema);
    }

    static String requireSchema(String schemaName) {
        String schema = Objects.requireNonNull(schemaName, "schemaName").trim();
        if (!IDENTIFIER.matcher(schema).matches()) {
            throw new IllegalArgumentException("Invalid PostgreSQL schema name: " + schemaName);
        }
        return schema;
    }
}
