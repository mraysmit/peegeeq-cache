package dev.mars.peegeeq.cache.pg.sql;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * SQL statement constants for cache_entries operations.
 * <p>
 * All expiry checks use {@code (expires_at IS NULL OR expires_at > NOW())} so
 * the database clock is the single source of truth for time comparisons.
 */
public final class CacheSql {

        private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

        public final String GET;
        public final String EXISTS;
        public final String DELETE;
        public final String UPSERT;
        public final String GET_FOR_UPDATE;
        public final String DELETE_EXPIRED;
        public final String INSERT_IF_ABSENT;
        public final String UPDATE_IF_PRESENT;
        public final String UPDATE_IF_VERSION_MATCHES;
        public final String TTL;
        public final String EXPIRE;
        public final String PERSIST;
        public final String TOUCH;
        public final String SCAN;

        public static CacheSql forSchema(String schemaName) {
                return new CacheSql(requireSchema(schemaName));
        }

        private CacheSql(String schemaName) {
                String entries = schemaName + ".cache_entries";

                /** Section 16.1 — read a live (non-expired) cache entry. */
                GET = """
            SELECT namespace, cache_key, value_type, value_bytes, numeric_value,
                   version, created_at, updated_at, expires_at, hit_count, last_accessed_at
                        FROM %s
            WHERE namespace = $1
              AND cache_key = $2
              AND (expires_at IS NULL OR expires_at > NOW())
                        """.formatted(entries);

                /** Section 16.2 — check whether a live entry exists. */
                EXISTS = """
            SELECT EXISTS (
                SELECT 1
                                FROM %s
                WHERE namespace = $1
                  AND cache_key = $2
                  AND (expires_at IS NULL OR expires_at > NOW())
            )
                        """.formatted(entries);

                /** Section 16.3 — unconditional delete. */
                DELETE = """
                        DELETE FROM %s
            WHERE namespace = $1
              AND cache_key = $2
                        """.formatted(entries);

                /** Section 16.4 — upsert (SET_ALWAYS / UPSERT). */
                UPSERT = """
                        INSERT INTO %s (
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
                                version       = %s.version + 1,
                updated_at    = NOW(),
                expires_at    = EXCLUDED.expires_at
            RETURNING version
                        """.formatted(entries, entries);

                /** Section 16.4 — pre-read with row lock for returnPreviousValue. */
                GET_FOR_UPDATE = """
            SELECT namespace, cache_key, value_type, value_bytes, numeric_value,
                   version, created_at, updated_at, expires_at, hit_count, last_accessed_at
                        FROM %s
            WHERE namespace = $1
              AND cache_key = $2
              AND (expires_at IS NULL OR expires_at > NOW())
            FOR UPDATE
                        """.formatted(entries);

                /** Section 16.5 — delete expired row before NX insert. */
                DELETE_EXPIRED = """
                        DELETE FROM %s
            WHERE namespace = $1
              AND cache_key = $2
              AND expires_at IS NOT NULL
              AND expires_at <= NOW()
                        """.formatted(entries);

                /** Section 16.5 — NX insert (SET_IF_ABSENT / ONLY_IF_ABSENT). */
                INSERT_IF_ABSENT = """
                        INSERT INTO %s (
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
                        """.formatted(entries);

                /** Section 16.6 — XX update (SET_IF_EXISTS / ONLY_IF_PRESENT). */
                UPDATE_IF_PRESENT = """
                        UPDATE %s
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
                                                """.formatted(entries);

                                /** Section 16.7 — CAS update (ONLY_IF_VERSION_MATCHES). */
                                UPDATE_IF_VERSION_MATCHES = """
                                                UPDATE %s
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
                                                """.formatted(entries);

                                /** Section 16.8 — TTL lookup. */
                                TTL = """
            SELECT
                CASE
                    WHEN expires_at IS NULL THEN NULL
                    ELSE FLOOR(EXTRACT(EPOCH FROM (expires_at - NOW())) * 1000)::BIGINT
                END AS ttl_millis
                                                FROM %s
            WHERE namespace = $1
              AND cache_key = $2
              AND (expires_at IS NULL OR expires_at > NOW())
                                                """.formatted(entries);

                                /** Section 16.9 — set a TTL on an existing live key. */
                                EXPIRE = """
                                                UPDATE %s
            SET expires_at = NOW() + ($3 * INTERVAL '1 millisecond'),
                version    = version + 1,
                updated_at = NOW()
            WHERE namespace = $1
              AND cache_key = $2
              AND (expires_at IS NULL OR expires_at > NOW())
                                                """.formatted(entries);

                                /** Section 16.10 — remove TTL from an existing live key. */
                                PERSIST = """
                                                UPDATE %s
            SET expires_at = NULL,
                version    = version + 1,
                updated_at = NOW()
            WHERE namespace = $1
              AND cache_key = $2
              AND (expires_at IS NULL OR expires_at > NOW())
                                                """.formatted(entries);

                                /** Section 16.11 — touch: update last_accessed_at, optionally refresh TTL. */
                                TOUCH = """
                                                UPDATE %s
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
                        """.formatted(entries);

                /** Section 16.12 — keyset scan with cursor pagination. */
                SCAN = """
            SELECT namespace, cache_key, value_type, value_bytes, numeric_value,
                   version, created_at, updated_at, expires_at, hit_count, last_accessed_at
                        FROM %s
            WHERE namespace = $1
                                                        AND ($2 IS NULL OR cache_key LIKE $2 || '%%')
              AND ($3 IS NULL OR cache_key > $3)
              AND (expires_at IS NULL OR expires_at > NOW())
            ORDER BY cache_key
            LIMIT $4
                        """.formatted(entries);
        }

        private static String requireSchema(String schemaName) {
                String value = Objects.requireNonNull(schemaName, "schemaName").trim();
                if (!IDENTIFIER.matcher(value).matches()) {
                        throw new IllegalArgumentException("Invalid PostgreSQL schema name: " + schemaName);
                }
                return value;
        }
}
