package dev.mars.peegeeq.cache.pg.sql;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * SQL statement constants for cache_locks operations.
 */
public final class LockSql {

        private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

        public final String DELETE_STALE;
        public final String INSERT_NO_FENCING;
        public final String INSERT_WITH_FENCING;
        public final String REENTRANT_RENEW;
        public final String RENEW;
        public final String RELEASE;
        public final String CURRENT;

        public static LockSql forSchema(String schemaName) {
                return new LockSql(requireSchema(schemaName));
        }

        private LockSql(String schemaName) {
                String locks = schemaName + ".cache_locks";
                String sequence = schemaName + ".lock_fencing_seq";

                /** Section 18.1 step 1 — delete stale (expired) lease. */
                DELETE_STALE = """
                        DELETE FROM %s
            WHERE namespace = $1
              AND lock_key = $2
              AND lease_expires_at <= NOW()
                        """.formatted(locks);

                /** Section 18.1 — insert new lease WITHOUT fencing token. */
                INSERT_NO_FENCING = """
                        INSERT INTO %s (
                namespace, lock_key, owner_token, fencing_token,
                version, created_at, updated_at, lease_expires_at
            )
            VALUES ($1, $2, $3, NULL, 1, NOW(), NOW(),
                    NOW() + ($4 * INTERVAL '1 millisecond'))
            ON CONFLICT (namespace, lock_key) DO NOTHING
            RETURNING namespace, lock_key, owner_token, fencing_token, lease_expires_at
            """.formatted(locks);

        /** Section 18.1 — insert new lease WITH fencing token (sequence). */
        INSERT_WITH_FENCING = """
            INSERT INTO %s (
                namespace, lock_key, owner_token, fencing_token,
                version, created_at, updated_at, lease_expires_at
            )
            VALUES ($1, $2, $3, nextval('%s'), 1, NOW(), NOW(),
                    NOW() + ($4 * INTERVAL '1 millisecond'))
            ON CONFLICT (namespace, lock_key) DO NOTHING
            RETURNING namespace, lock_key, owner_token, fencing_token, lease_expires_at
            """.formatted(locks, sequence);

        /** Section 18.2 — reentrant acquire: renew lease by same owner. */
        REENTRANT_RENEW = """
            UPDATE %s
            SET lease_expires_at = NOW() + ($4 * INTERVAL '1 millisecond'),
                version = version + 1,
                updated_at = NOW()
            WHERE namespace = $1
              AND lock_key = $2
              AND owner_token = $3
              AND lease_expires_at > NOW()
            RETURNING namespace, lock_key, owner_token, fencing_token, lease_expires_at
                                                """.formatted(locks);

                                /** Section 18.3 — renew lease only by owner. */
                                RENEW = """
                                                UPDATE %s
            SET lease_expires_at = NOW() + ($4 * INTERVAL '1 millisecond'),
                version = version + 1,
                updated_at = NOW()
            WHERE namespace = $1
              AND lock_key = $2
              AND owner_token = $3
              AND lease_expires_at > NOW()
            RETURNING lease_expires_at
                                                """.formatted(locks);

                                /** Section 18.4 — release only by owner. */
                                RELEASE = """
                                                DELETE FROM %s
            WHERE namespace = $1
              AND lock_key = $2
              AND owner_token = $3
                                                """.formatted(locks);

                                /** Section 18.5 — read current active lock. */
                                CURRENT = """
            SELECT namespace, lock_key, owner_token, fencing_token,
                   version, created_at, updated_at, lease_expires_at
                                                FROM %s
            WHERE namespace = $1
              AND lock_key = $2
              AND lease_expires_at > NOW()
                                                """.formatted(locks);
                }

                private static String requireSchema(String schemaName) {
                                String value = Objects.requireNonNull(schemaName, "schemaName").trim();
                                if (!IDENTIFIER.matcher(value).matches()) {
                                                throw new IllegalArgumentException("Invalid PostgreSQL schema name: " + schemaName);
                                }
                                return value;
                }
}
