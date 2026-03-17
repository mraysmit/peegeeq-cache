package dev.mars.peegeeq.cache.pg.sql;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * SQL statement constants for cache_counters operations.
 */
public final class CounterSql {

        private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

        public final String GET;
        public final String INCREMENT_PRESERVE;
        public final String INCREMENT_REPLACE;
        public final String INCREMENT_REMOVE;
        public final String INCREMENT_NO_CREATE;
        public final String DELETE_EXPIRED;
        public final String SET_VALUE;
        public final String TTL;
        public final String EXPIRE;
        public final String PERSIST;
        public final String DELETE;

        public static CounterSql forSchema(String schemaName) {
                return new CounterSql(requireSchema(schemaName));
        }

        private CounterSql(String schemaName) {
                String counters = schemaName + ".cache_counters";

                /** Section 17.1 — get live counter value. */
                GET = """
            SELECT counter_value
                        FROM %s
            WHERE namespace = $1
              AND counter_key = $2
              AND (expires_at IS NULL OR expires_at > NOW())
                        """.formatted(counters);

                /** Section 17.2 — upsert increment with PRESERVE_EXISTING TTL mode. */
                INCREMENT_PRESERVE = """
                        INSERT INTO %s (
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
                                counter_value = %s.counter_value + EXCLUDED.counter_value,
                                version = %s.version + 1,
                updated_at = NOW(),
                                expires_at = COALESCE(%s.expires_at, EXCLUDED.expires_at)
            RETURNING counter_value, version
                        """.formatted(counters, counters, counters, counters);

                /** Section 17.2 — upsert increment with REPLACE TTL mode. */
                INCREMENT_REPLACE = """
                        INSERT INTO %s (
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
                                counter_value = %s.counter_value + EXCLUDED.counter_value,
                                version = %s.version + 1,
                updated_at = NOW(),
                expires_at = EXCLUDED.expires_at
            RETURNING counter_value, version
                        """.formatted(counters, counters, counters);

                /** Section 17.2 — upsert increment with REMOVE TTL mode. */
                INCREMENT_REMOVE = """
                        INSERT INTO %s (
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
                                counter_value = %s.counter_value + EXCLUDED.counter_value,
                                version = %s.version + 1,
                updated_at = NOW(),
                expires_at = NULL
            RETURNING counter_value, version
                        """.formatted(counters, counters, counters);

                /** Section 17.2a — increment without create. */
                INCREMENT_NO_CREATE = """
                        UPDATE %s
            SET counter_value = counter_value + $3,
                version = version + 1,
                updated_at = NOW()
            WHERE namespace = $1
              AND counter_key = $2
              AND (expires_at IS NULL OR expires_at > NOW())
            RETURNING counter_value, version
                                                """.formatted(counters);

                                /** Section 17.3 — delete expired counter before upsert. */
                                DELETE_EXPIRED = """
                                                DELETE FROM %s
            WHERE namespace = $1
              AND counter_key = $2
              AND expires_at IS NOT NULL
              AND expires_at <= NOW()
                                                """.formatted(counters);

                                /** Section 17.4 — set exact counter value (upsert). */
                                SET_VALUE = """
                                                INSERT INTO %s (
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
                                version = %s.version + 1,
                updated_at = NOW(),
                expires_at = EXCLUDED.expires_at
            RETURNING counter_value, version
                        """.formatted(counters, counters);

                /** Section 17.5 — TTL lookup for live counters. */
                TTL = """
            SELECT
                CASE
                    WHEN expires_at IS NULL THEN NULL
                    ELSE FLOOR(EXTRACT(EPOCH FROM (expires_at - NOW())) * 1000)::BIGINT
                END AS ttl_millis
                        FROM %s
            WHERE namespace = $1
              AND counter_key = $2
              AND (expires_at IS NULL OR expires_at > NOW())
                        """.formatted(counters);

                /** Section 17.6 — set TTL on an existing live counter. */
                EXPIRE = """
                        UPDATE %s
            SET expires_at = NOW() + ($3 * INTERVAL '1 millisecond'),
                version    = version + 1,
                updated_at = NOW()
            WHERE namespace = $1
              AND counter_key = $2
              AND (expires_at IS NULL OR expires_at > NOW())
                        """.formatted(counters);

                /** Section 17.7 — remove TTL from an existing live counter. */
                PERSIST = """
                        UPDATE %s
            SET expires_at = NULL,
                version    = version + 1,
                updated_at = NOW()
            WHERE namespace = $1
              AND counter_key = $2
              AND (expires_at IS NULL OR expires_at > NOW())
                        """.formatted(counters);

                /** Unconditional delete. */
                DELETE = """
                        DELETE FROM %s
            WHERE namespace = $1
              AND counter_key = $2
                        """.formatted(counters);
        }

        private static String requireSchema(String schemaName) {
                String value = Objects.requireNonNull(schemaName, "schemaName").trim();
                if (!IDENTIFIER.matcher(value).matches()) {
                        throw new IllegalArgumentException("Invalid PostgreSQL schema name: " + schemaName);
                }
                return value;
        }
}
