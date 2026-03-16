package dev.mars.peegeeq.cache.pg.sql;

/**
 * SQL statement constants for cache_entries operations.
 * <p>
 * All expiry checks use {@code (expires_at IS NULL OR expires_at > NOW())} so
 * the database clock is the single source of truth for time comparisons.
 */
public final class CacheSql {

    private CacheSql() {}

    /** Section 16.1 — read a live (non-expired) cache entry. */
    public static final String GET = """
            SELECT namespace, cache_key, value_type, value_bytes, numeric_value,
                   version, created_at, updated_at, expires_at, hit_count, last_accessed_at
            FROM peegee_cache.cache_entries
            WHERE namespace = $1
              AND cache_key = $2
              AND (expires_at IS NULL OR expires_at > NOW())
            """;

    /** Section 16.2 — check whether a live entry exists. */
    public static final String EXISTS = """
            SELECT EXISTS (
                SELECT 1
                FROM peegee_cache.cache_entries
                WHERE namespace = $1
                  AND cache_key = $2
                  AND (expires_at IS NULL OR expires_at > NOW())
            )
            """;

    /** Section 16.3 — unconditional delete. */
    public static final String DELETE = """
            DELETE FROM peegee_cache.cache_entries
            WHERE namespace = $1
              AND cache_key = $2
            """;

    /** Section 16.4 — upsert (SET_ALWAYS / UPSERT). */
    public static final String UPSERT = """
            INSERT INTO peegee_cache.cache_entries (
                namespace, cache_key, value_type, value_bytes, numeric_value,
                version, created_at, updated_at, expires_at, hit_count, last_accessed_at
            )
            VALUES ($1, $2, $3, $4, $5,
                                        1, NOW(), NOW(),
                                        CASE WHEN $6::BIGINT IS NULL
                                                 THEN NULL
                                                 ELSE NOW() + (($6::BIGINT) * INTERVAL '1 millisecond')
                                        END,
                                        0, NULL)
            ON CONFLICT (namespace, cache_key)
            DO UPDATE SET
                value_type    = EXCLUDED.value_type,
                value_bytes   = EXCLUDED.value_bytes,
                numeric_value = EXCLUDED.numeric_value,
                version       = peegee_cache.cache_entries.version + 1,
                updated_at    = NOW(),
                expires_at    = EXCLUDED.expires_at
            RETURNING version
            """;

    /** Section 16.4 — pre-read with row lock for returnPreviousValue. */
    public static final String GET_FOR_UPDATE = """
            SELECT namespace, cache_key, value_type, value_bytes, numeric_value,
                   version, created_at, updated_at, expires_at, hit_count, last_accessed_at
            FROM peegee_cache.cache_entries
            WHERE namespace = $1
              AND cache_key = $2
              AND (expires_at IS NULL OR expires_at > NOW())
            FOR UPDATE
            """;

    /** Section 16.5 — delete expired row before NX insert. */
    public static final String DELETE_EXPIRED = """
            DELETE FROM peegee_cache.cache_entries
            WHERE namespace = $1
              AND cache_key = $2
              AND expires_at IS NOT NULL
              AND expires_at <= NOW()
            """;

    /** Section 16.5 — NX insert (SET_IF_ABSENT / ONLY_IF_ABSENT). */
    public static final String INSERT_IF_ABSENT = """
            INSERT INTO peegee_cache.cache_entries (
                namespace, cache_key, value_type, value_bytes, numeric_value,
                version, created_at, updated_at, expires_at, hit_count, last_accessed_at
            )
            VALUES ($1, $2, $3, $4, $5,
                    1, NOW(), NOW(),
                    CASE WHEN $6::BIGINT IS NULL
                         THEN NULL
                         ELSE NOW() + (($6::BIGINT) * INTERVAL '1 millisecond')
                    END,
                    0, NULL)
            ON CONFLICT (namespace, cache_key) DO NOTHING
            RETURNING version
            """;

    /** Section 16.6 — XX update (SET_IF_EXISTS / ONLY_IF_PRESENT). */
    public static final String UPDATE_IF_PRESENT = """
            UPDATE peegee_cache.cache_entries
            SET value_type    = $3,
                value_bytes   = $4,
                numeric_value = $5,
                version       = version + 1,
                updated_at    = NOW(),
                expires_at    = CASE WHEN $6::BIGINT IS NULL
                                     THEN NULL
                                     ELSE NOW() + (($6::BIGINT) * INTERVAL '1 millisecond')
                                END
            WHERE namespace = $1
              AND cache_key = $2
              AND (expires_at IS NULL OR expires_at > NOW())
            RETURNING version
            """;

    /** Section 16.7 — CAS update (ONLY_IF_VERSION_MATCHES). */
    public static final String UPDATE_IF_VERSION_MATCHES = """
            UPDATE peegee_cache.cache_entries
            SET value_type    = $4,
                value_bytes   = $5,
                numeric_value = $6,
                version       = version + 1,
                updated_at    = NOW(),
                expires_at    = CASE WHEN $7::BIGINT IS NULL
                                     THEN NULL
                                     ELSE NOW() + (($7::BIGINT) * INTERVAL '1 millisecond')
                                END
            WHERE namespace = $1
              AND cache_key = $2
              AND version   = $3
              AND (expires_at IS NULL OR expires_at > NOW())
            RETURNING version
            """;

    /** Section 16.8 — TTL lookup. */
    public static final String TTL = """
            SELECT
                CASE
                    WHEN expires_at IS NULL THEN NULL
                    ELSE FLOOR(EXTRACT(EPOCH FROM (expires_at - NOW())) * 1000)::BIGINT
                END AS ttl_millis
            FROM peegee_cache.cache_entries
            WHERE namespace = $1
              AND cache_key = $2
              AND (expires_at IS NULL OR expires_at > NOW())
            """;

    /** Section 16.9 — set a TTL on an existing live key. */
    public static final String EXPIRE = """
            UPDATE peegee_cache.cache_entries
            SET expires_at = NOW() + ($3 * INTERVAL '1 millisecond'),
                version    = version + 1,
                updated_at = NOW()
            WHERE namespace = $1
              AND cache_key = $2
              AND (expires_at IS NULL OR expires_at > NOW())
            """;

    /** Section 16.10 — remove TTL from an existing live key. */
    public static final String PERSIST = """
            UPDATE peegee_cache.cache_entries
            SET expires_at = NULL,
                version    = version + 1,
                updated_at = NOW()
            WHERE namespace = $1
              AND cache_key = $2
              AND (expires_at IS NULL OR expires_at > NOW())
            """;

    /** Section 16.11 — touch: update last_accessed_at, optionally refresh TTL. */
    public static final String TOUCH = """
            UPDATE peegee_cache.cache_entries
            SET expires_at = CASE WHEN $3::BIGINT IS NOT NULL
                                  THEN NOW() + (($3::BIGINT) * INTERVAL '1 millisecond')
                                  ELSE expires_at
                             END,
                last_accessed_at = NOW(),
                updated_at = NOW()
            WHERE namespace = $1
              AND cache_key = $2
              AND (expires_at IS NULL OR expires_at > NOW())
            RETURNING
                CASE
                    WHEN expires_at IS NULL THEN NULL
                    ELSE FLOOR(EXTRACT(EPOCH FROM (expires_at - NOW())) * 1000)::BIGINT
                END AS ttl_millis
            """;

    /** Section 16.12 — keyset scan with cursor pagination. */
    public static final String SCAN = """
            SELECT namespace, cache_key, value_type, value_bytes, numeric_value,
                   version, created_at, updated_at, expires_at, hit_count, last_accessed_at
            FROM peegee_cache.cache_entries
            WHERE namespace = $1
              AND ($2 IS NULL OR cache_key LIKE $2 || '%')
              AND ($3 IS NULL OR cache_key > $3)
              AND (expires_at IS NULL OR expires_at > NOW())
            ORDER BY cache_key
            LIMIT $4
            """;
}
