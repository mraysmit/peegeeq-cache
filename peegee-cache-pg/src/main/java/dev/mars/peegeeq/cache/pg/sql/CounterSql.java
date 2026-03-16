package dev.mars.peegeeq.cache.pg.sql;

/**
 * SQL statement constants for cache_counters operations.
 */
public final class CounterSql {

    private CounterSql() {}

    /** Section 17.1 — get live counter value. */
    public static final String GET = """
            SELECT counter_value
            FROM peegee_cache.cache_counters
            WHERE namespace = $1
              AND counter_key = $2
              AND (expires_at IS NULL OR expires_at > NOW())
            """;

    /** Section 17.2 — upsert increment with PRESERVE_EXISTING TTL mode. */
    public static final String INCREMENT_PRESERVE = """
            INSERT INTO peegee_cache.cache_counters (
                namespace, counter_key, counter_value, version,
                created_at, updated_at, expires_at
            )
            VALUES ($1, $2, $3, 1, NOW(), NOW(),
                    CASE WHEN $4::BIGINT IS NULL
                         THEN NULL
                         ELSE NOW() + (($4::BIGINT) * INTERVAL '1 millisecond')
                    END)
            ON CONFLICT (namespace, counter_key)
            DO UPDATE SET
                counter_value = peegee_cache.cache_counters.counter_value + EXCLUDED.counter_value,
                version = peegee_cache.cache_counters.version + 1,
                updated_at = NOW(),
                expires_at = COALESCE(peegee_cache.cache_counters.expires_at, EXCLUDED.expires_at)
            RETURNING counter_value, version
            """;

    /** Section 17.2 — upsert increment with REPLACE TTL mode. */
    public static final String INCREMENT_REPLACE = """
            INSERT INTO peegee_cache.cache_counters (
                namespace, counter_key, counter_value, version,
                created_at, updated_at, expires_at
            )
            VALUES ($1, $2, $3, 1, NOW(), NOW(),
                    CASE WHEN $4::BIGINT IS NULL
                         THEN NULL
                         ELSE NOW() + (($4::BIGINT) * INTERVAL '1 millisecond')
                    END)
            ON CONFLICT (namespace, counter_key)
            DO UPDATE SET
                counter_value = peegee_cache.cache_counters.counter_value + EXCLUDED.counter_value,
                version = peegee_cache.cache_counters.version + 1,
                updated_at = NOW(),
                expires_at = EXCLUDED.expires_at
            RETURNING counter_value, version
            """;

    /** Section 17.2 — upsert increment with REMOVE TTL mode. */
    public static final String INCREMENT_REMOVE = """
            INSERT INTO peegee_cache.cache_counters (
                namespace, counter_key, counter_value, version,
                created_at, updated_at, expires_at
            )
            VALUES ($1, $2, $3, 1, NOW(), NOW(),
                    CASE WHEN $4::BIGINT IS NULL
                         THEN NULL
                         ELSE NOW() + (($4::BIGINT) * INTERVAL '1 millisecond')
                    END)
            ON CONFLICT (namespace, counter_key)
            DO UPDATE SET
                counter_value = peegee_cache.cache_counters.counter_value + EXCLUDED.counter_value,
                version = peegee_cache.cache_counters.version + 1,
                updated_at = NOW(),
                expires_at = NULL
            RETURNING counter_value, version
            """;

    /** Section 17.2a — increment without create. */
    public static final String INCREMENT_NO_CREATE = """
            UPDATE peegee_cache.cache_counters
            SET counter_value = counter_value + $3,
                version = version + 1,
                updated_at = NOW()
            WHERE namespace = $1
              AND counter_key = $2
              AND (expires_at IS NULL OR expires_at > NOW())
            RETURNING counter_value, version
            """;

    /** Section 17.3 — delete expired counter before upsert. */
    public static final String DELETE_EXPIRED = """
            DELETE FROM peegee_cache.cache_counters
            WHERE namespace = $1
              AND counter_key = $2
              AND expires_at IS NOT NULL
              AND expires_at <= NOW()
            """;

    /** Section 17.4 — set exact counter value (upsert). */
    public static final String SET_VALUE = """
            INSERT INTO peegee_cache.cache_counters (
                namespace, counter_key, counter_value, version,
                created_at, updated_at, expires_at
            )
            VALUES ($1, $2, $3, 1, NOW(), NOW(),
                    CASE WHEN $4::BIGINT IS NULL
                         THEN NULL
                         ELSE NOW() + (($4::BIGINT) * INTERVAL '1 millisecond')
                    END)
            ON CONFLICT (namespace, counter_key)
            DO UPDATE SET
                counter_value = EXCLUDED.counter_value,
                version = peegee_cache.cache_counters.version + 1,
                updated_at = NOW(),
                expires_at = EXCLUDED.expires_at
            RETURNING counter_value, version
            """;

    /** Section 17.5 — TTL lookup for live counters. */
    public static final String TTL = """
            SELECT
                CASE
                    WHEN expires_at IS NULL THEN NULL
                    ELSE FLOOR(EXTRACT(EPOCH FROM (expires_at - NOW())) * 1000)::BIGINT
                END AS ttl_millis
            FROM peegee_cache.cache_counters
            WHERE namespace = $1
              AND counter_key = $2
              AND (expires_at IS NULL OR expires_at > NOW())
            """;

    /** Section 17.6 — set TTL on an existing live counter. */
    public static final String EXPIRE = """
            UPDATE peegee_cache.cache_counters
            SET expires_at = NOW() + ($3 * INTERVAL '1 millisecond'),
                version    = version + 1,
                updated_at = NOW()
            WHERE namespace = $1
              AND counter_key = $2
              AND (expires_at IS NULL OR expires_at > NOW())
            """;

    /** Section 17.7 — remove TTL from an existing live counter. */
    public static final String PERSIST = """
            UPDATE peegee_cache.cache_counters
            SET expires_at = NULL,
                version    = version + 1,
                updated_at = NOW()
            WHERE namespace = $1
              AND counter_key = $2
              AND (expires_at IS NULL OR expires_at > NOW())
            """;

    /** Unconditional delete. */
    public static final String DELETE = """
            DELETE FROM peegee_cache.cache_counters
            WHERE namespace = $1
              AND counter_key = $2
            """;
}
