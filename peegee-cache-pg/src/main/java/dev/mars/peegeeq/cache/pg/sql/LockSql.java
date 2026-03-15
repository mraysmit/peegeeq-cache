package dev.mars.peegeeq.cache.pg.sql;

/**
 * SQL statement constants for cache_locks operations.
 */
public final class LockSql {

    private LockSql() {}

    /** Section 18.1 step 1 — delete stale (expired) lease. */
    public static final String DELETE_STALE = """
            DELETE FROM peegee_cache.cache_locks
            WHERE namespace = $1
              AND lock_key = $2
              AND lease_expires_at <= NOW()
            """;

    /** Section 18.1 — insert new lease WITHOUT fencing token. */
    public static final String INSERT_NO_FENCING = """
            INSERT INTO peegee_cache.cache_locks (
                namespace, lock_key, owner_token, fencing_token,
                version, created_at, updated_at, lease_expires_at
            )
            VALUES ($1, $2, $3, NULL, 1, NOW(), NOW(),
                    NOW() + ($4 * INTERVAL '1 millisecond'))
            ON CONFLICT (namespace, lock_key) DO NOTHING
            RETURNING namespace, lock_key, owner_token, fencing_token, lease_expires_at
            """;

    /** Section 18.1 — insert new lease WITH fencing token (sequence). */
    public static final String INSERT_WITH_FENCING = """
            INSERT INTO peegee_cache.cache_locks (
                namespace, lock_key, owner_token, fencing_token,
                version, created_at, updated_at, lease_expires_at
            )
            VALUES ($1, $2, $3, nextval('peegee_cache.lock_fencing_seq'), 1, NOW(), NOW(),
                    NOW() + ($4 * INTERVAL '1 millisecond'))
            ON CONFLICT (namespace, lock_key) DO NOTHING
            RETURNING namespace, lock_key, owner_token, fencing_token, lease_expires_at
            """;

    /** Section 18.2 — reentrant acquire: renew lease by same owner. */
    public static final String REENTRANT_RENEW = """
            UPDATE peegee_cache.cache_locks
            SET lease_expires_at = NOW() + ($4 * INTERVAL '1 millisecond'),
                version = version + 1,
                updated_at = NOW()
            WHERE namespace = $1
              AND lock_key = $2
              AND owner_token = $3
              AND lease_expires_at > NOW()
            RETURNING namespace, lock_key, owner_token, fencing_token, lease_expires_at
            """;

    /** Section 18.3 — renew lease only by owner. */
    public static final String RENEW = """
            UPDATE peegee_cache.cache_locks
            SET lease_expires_at = NOW() + ($4 * INTERVAL '1 millisecond'),
                version = version + 1,
                updated_at = NOW()
            WHERE namespace = $1
              AND lock_key = $2
              AND owner_token = $3
              AND lease_expires_at > NOW()
            RETURNING lease_expires_at
            """;

    /** Section 18.4 — release only by owner. */
    public static final String RELEASE = """
            DELETE FROM peegee_cache.cache_locks
            WHERE namespace = $1
              AND lock_key = $2
              AND owner_token = $3
            """;

    /** Section 18.5 — read current active lock. */
    public static final String CURRENT = """
            SELECT namespace, lock_key, owner_token, fencing_token,
                   version, created_at, updated_at, lease_expires_at
            FROM peegee_cache.cache_locks
            WHERE namespace = $1
              AND lock_key = $2
              AND lease_expires_at > NOW()
            """;
}
