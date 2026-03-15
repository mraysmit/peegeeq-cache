-- V006: Create indexes for expiry sweeper queries and prefix-scan operations.

-- cache_entries: partial index on expires_at for expiry sweeper.
CREATE INDEX IF NOT EXISTS idx_cache_entries_expires_at
    ON peegee_cache.cache_entries (expires_at)
    WHERE expires_at IS NOT NULL;

-- cache_entries: text_pattern_ops index for LIKE prefix scans.
CREATE INDEX IF NOT EXISTS idx_cache_entries_namespace_key_pattern
    ON peegee_cache.cache_entries (namespace, cache_key text_pattern_ops);

-- cache_counters: partial index on expires_at for expiry sweeper.
CREATE INDEX IF NOT EXISTS idx_cache_counters_expires_at
    ON peegee_cache.cache_counters (expires_at)
    WHERE expires_at IS NOT NULL;

-- cache_counters: text_pattern_ops index for LIKE prefix scans.
CREATE INDEX IF NOT EXISTS idx_cache_counters_namespace_key_pattern
    ON peegee_cache.cache_counters (namespace, counter_key text_pattern_ops);

-- cache_locks: full index on lease_expires_at for lock sweeper.
CREATE INDEX IF NOT EXISTS idx_cache_locks_lease_expires_at
    ON peegee_cache.cache_locks (lease_expires_at);
