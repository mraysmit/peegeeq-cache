-- V004: Create the cache_locks table.
-- Distributed lease-based locks with owner tokens and optional fencing tokens.
-- Always logged (never UNLOGGED) because lock state is safety-critical.
-- Lease times must be computed from the database clock in SQL, not supplied by callers.

CREATE TABLE IF NOT EXISTS peegee_cache.cache_locks (
    namespace           TEXT                        NOT NULL,
    lock_key            TEXT                        NOT NULL,
    owner_token         TEXT                        NOT NULL,
    fencing_token       BIGINT,
    version             BIGINT                      NOT NULL DEFAULT 1,
    created_at          TIMESTAMPTZ                 NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ                 NOT NULL DEFAULT NOW(),
    lease_expires_at    TIMESTAMPTZ                 NOT NULL,

    PRIMARY KEY (namespace, lock_key),

    -- Lease expiry must be strictly after last update to prevent zero-duration
    -- or past-expiry leases at the database layer.
    CONSTRAINT chk_cache_locks_future_lease
        CHECK (lease_expires_at > updated_at)
);
