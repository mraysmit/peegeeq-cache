-- V003: Create the cache_counters table.
-- Atomic counters with optional TTL. Always logged (never UNLOGGED)
-- because counters participate in control flow such as rate limits and quotas.

CREATE TABLE IF NOT EXISTS peegee_cache.cache_counters (
    namespace           TEXT                        NOT NULL,
    counter_key         TEXT                        NOT NULL,
    counter_value       BIGINT                      NOT NULL,
    version             BIGINT                      NOT NULL DEFAULT 1,
    created_at          TIMESTAMPTZ                 NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ                 NOT NULL DEFAULT NOW(),
    expires_at          TIMESTAMPTZ,

    PRIMARY KEY (namespace, counter_key)
);
