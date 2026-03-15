-- V002: Create the cache_entries table.
-- Core disposable key/value storage with composite (namespace, cache_key) primary key,
-- typed value discriminator, optimistic versioning, and optional TTL expiry.

CREATE TABLE IF NOT EXISTS peegee_cache.cache_entries (
    namespace           TEXT                        NOT NULL,
    cache_key           TEXT                        NOT NULL,
    value_type          TEXT                        NOT NULL,
    value_bytes         BYTEA,
    numeric_value       BIGINT,
    version             BIGINT                      NOT NULL DEFAULT 1,
    created_at          TIMESTAMPTZ                 NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ                 NOT NULL DEFAULT NOW(),
    expires_at          TIMESTAMPTZ,
    hit_count           BIGINT                      NOT NULL DEFAULT 0,
    last_accessed_at    TIMESTAMPTZ,

    PRIMARY KEY (namespace, cache_key),

    -- Restrict value_type to the four supported discriminators.
    CONSTRAINT chk_cache_entries_value_type
        CHECK (value_type IN ('BYTES', 'STRING', 'JSON', 'LONG')),

    -- Enforce mutual exclusivity between binary and numeric payloads:
    --   LONG  -> numeric_value NOT NULL, value_bytes NULL
    --   other -> value_bytes   NOT NULL, numeric_value NULL
    CONSTRAINT chk_cache_entries_value_payload
        CHECK (
            (
                value_type = 'LONG'
                AND numeric_value IS NOT NULL
                AND value_bytes IS NULL
            )
            OR
            (
                value_type IN ('BYTES', 'STRING', 'JSON')
                AND value_bytes IS NOT NULL
                AND numeric_value IS NULL
            )
        )
);
