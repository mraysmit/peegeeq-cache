-- V001: peegee-cache Phase 1 schema.
--
-- Creates the peegee_cache schema and all V1 Core tables, sequences, and indexes.
-- This is a greenfield migration — there is no prior schema to evolve from.

-- Schema
CREATE SCHEMA IF NOT EXISTS peegee_cache;

-- cache_entries: disposable key/value storage with typed value discriminator,
-- optimistic versioning, and optional TTL expiry.
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

    CONSTRAINT chk_cache_entries_value_type
        CHECK (value_type IN ('BYTES', 'STRING', 'JSON', 'LONG')),

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

-- cache_counters: atomic counters with optional TTL.
-- Always logged (never UNLOGGED) because counters participate in control flow.
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

-- cache_locks: distributed lease-based locks with owner tokens and optional fencing tokens.
-- Always logged (never UNLOGGED) because lock state is safety-critical.
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

    CONSTRAINT chk_cache_locks_future_lease
        CHECK (lease_expires_at > updated_at)
);

-- lock_fencing_seq: monotonic fencing tokens for lease-lock write-ordering safety.
CREATE SEQUENCE IF NOT EXISTS peegee_cache.lock_fencing_seq;

-- Indexes

CREATE INDEX IF NOT EXISTS idx_cache_entries_expires_at
    ON peegee_cache.cache_entries (expires_at)
    WHERE expires_at IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_cache_entries_namespace_key_pattern
    ON peegee_cache.cache_entries (namespace, cache_key text_pattern_ops);

CREATE INDEX IF NOT EXISTS idx_cache_counters_expires_at
    ON peegee_cache.cache_counters (expires_at)
    WHERE expires_at IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_cache_counters_namespace_key_pattern
    ON peegee_cache.cache_counters (namespace, counter_key text_pattern_ops);

CREATE INDEX IF NOT EXISTS idx_cache_locks_lease_expires_at
    ON peegee_cache.cache_locks (lease_expires_at);
