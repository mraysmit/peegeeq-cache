-- V001: peegee-cache bootstrap DDL.
--
-- Creates the peegee_cache schema, all V1 Core tables, sequences, indexes,
-- and PL/pgSQL functions for correctness-sensitive writes.
-- This is a greenfield bootstrap — there is no prior schema to evolve from.

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


-- ============================================================================
-- SQL FUNCTIONS — correctness-sensitive writes
-- ============================================================================
--
-- Provides stable SQL functions so external (non-Java) callers do not have to
-- reconstruct multi-statement concurrency logic from prose alone.
--
-- Reads may continue to use tables/views directly.
-- Writes that involve concurrency semantics should use these functions.

-- ============================================================================
-- LOCK FUNCTIONS
-- ============================================================================

-- acquire_lock: atomic stale-cleanup + insert + optional reentrant renew.
--
-- Parameters:
--   p_namespace           — lock namespace
--   p_lock_key            — lock key within namespace
--   p_owner_token         — owner identity token
--   p_lease_ttl_millis    — lease duration in milliseconds
--   p_reentrant           — if true, renew lease when same owner already holds it
--   p_issue_fencing_token — if true, assign a monotonic fencing token from lock_fencing_seq
--
-- Returns a single row:
--   acquired       — true if lock was obtained (including reentrant renewal)
--   owner_token    — the owning token (null if not acquired)
--   fencing_token  — monotonic fencing token (null if not requested or not acquired)
--   lease_expires_at — when the lease expires (null if not acquired)

CREATE OR REPLACE FUNCTION peegee_cache.acquire_lock(
    p_namespace           TEXT,
    p_lock_key            TEXT,
    p_owner_token         TEXT,
    p_lease_ttl_millis    BIGINT,
    p_reentrant           BOOLEAN DEFAULT FALSE,
    p_issue_fencing_token BOOLEAN DEFAULT TRUE
)
RETURNS TABLE (
    acquired         BOOLEAN,
    owner_token      TEXT,
    fencing_token    BIGINT,
    lease_expires_at TIMESTAMPTZ
)
LANGUAGE plpgsql AS $$
DECLARE
    v_owner      TEXT;
    v_fencing    BIGINT;
    v_expires    TIMESTAMPTZ;
    v_lease_interval INTERVAL := (p_lease_ttl_millis * INTERVAL '1 millisecond');
BEGIN
    -- Step 1: delete stale (expired) lease
    DELETE FROM peegee_cache.cache_locks cl
    WHERE cl.namespace = p_namespace
      AND cl.lock_key = p_lock_key
      AND cl.lease_expires_at <= NOW();

    -- Step 2: attempt insert
    IF p_issue_fencing_token THEN
        INSERT INTO peegee_cache.cache_locks (
            namespace, lock_key, owner_token, fencing_token,
            version, created_at, updated_at, lease_expires_at
        )
        VALUES (
            p_namespace, p_lock_key, p_owner_token,
            nextval('peegee_cache.lock_fencing_seq'),
            1, NOW(), NOW(), NOW() + v_lease_interval
        )
        ON CONFLICT (namespace, lock_key) DO NOTHING
        RETURNING cache_locks.owner_token, cache_locks.fencing_token, cache_locks.lease_expires_at
        INTO v_owner, v_fencing, v_expires;
    ELSE
        INSERT INTO peegee_cache.cache_locks (
            namespace, lock_key, owner_token, fencing_token,
            version, created_at, updated_at, lease_expires_at
        )
        VALUES (
            p_namespace, p_lock_key, p_owner_token, NULL,
            1, NOW(), NOW(), NOW() + v_lease_interval
        )
        ON CONFLICT (namespace, lock_key) DO NOTHING
        RETURNING cache_locks.owner_token, cache_locks.fencing_token, cache_locks.lease_expires_at
        INTO v_owner, v_fencing, v_expires;
    END IF;

    IF v_owner IS NOT NULL THEN
        -- Lock acquired
        RETURN QUERY SELECT TRUE, v_owner, v_fencing, v_expires;
        RETURN;
    END IF;

    -- Insert failed (conflict) — lock is already held
    IF p_reentrant THEN
        -- Attempt reentrant renew by same owner
        UPDATE peegee_cache.cache_locks cl
        SET lease_expires_at = NOW() + v_lease_interval,
            version = cl.version + 1,
            updated_at = NOW()
        WHERE cl.namespace = p_namespace
          AND cl.lock_key = p_lock_key
          AND cl.owner_token = p_owner_token
          AND cl.lease_expires_at > NOW()
        RETURNING cl.owner_token, cl.fencing_token, cl.lease_expires_at
        INTO v_owner, v_fencing, v_expires;

        IF v_owner IS NOT NULL THEN
            RETURN QUERY SELECT TRUE, v_owner, v_fencing, v_expires;
            RETURN;
        END IF;
    END IF;

    -- Not acquired
    RETURN QUERY SELECT FALSE, NULL::TEXT, NULL::BIGINT, NULL::TIMESTAMPTZ;
END;
$$;


-- renew_lock: extend lease for the current owner.
--
-- Parameters:
--   p_namespace        — lock namespace
--   p_lock_key         — lock key within namespace
--   p_owner_token      — must match the current holder
--   p_lease_ttl_millis — new lease duration in milliseconds from NOW()
--
-- Returns a single row:
--   renewed          — true if the lease was extended
--   lease_expires_at — new expiry (null if not renewed)

CREATE OR REPLACE FUNCTION peegee_cache.renew_lock(
    p_namespace        TEXT,
    p_lock_key         TEXT,
    p_owner_token      TEXT,
    p_lease_ttl_millis BIGINT
)
RETURNS TABLE (
    renewed          BOOLEAN,
    lease_expires_at TIMESTAMPTZ
)
LANGUAGE plpgsql AS $$
DECLARE
    v_expires TIMESTAMPTZ;
BEGIN
    UPDATE peegee_cache.cache_locks cl
    SET lease_expires_at = NOW() + (p_lease_ttl_millis * INTERVAL '1 millisecond'),
        version = cl.version + 1,
        updated_at = NOW()
    WHERE cl.namespace = p_namespace
      AND cl.lock_key = p_lock_key
      AND cl.owner_token = p_owner_token
      AND cl.lease_expires_at > NOW()
    RETURNING cl.lease_expires_at INTO v_expires;

    IF v_expires IS NOT NULL THEN
        RETURN QUERY SELECT TRUE, v_expires;
    ELSE
        RETURN QUERY SELECT FALSE, NULL::TIMESTAMPTZ;
    END IF;
END;
$$;


-- release_lock: release a lock held by the specified owner.
--
-- Parameters:
--   p_namespace   — lock namespace
--   p_lock_key    — lock key within namespace
--   p_owner_token — must match the current holder
--
-- Returns a single row:
--   released — true if the lock was released

CREATE OR REPLACE FUNCTION peegee_cache.release_lock(
    p_namespace   TEXT,
    p_lock_key    TEXT,
    p_owner_token TEXT
)
RETURNS TABLE (released BOOLEAN)
LANGUAGE plpgsql AS $$
DECLARE
    v_count INT;
BEGIN
    DELETE FROM peegee_cache.cache_locks cl
    WHERE cl.namespace = p_namespace
      AND cl.lock_key = p_lock_key
      AND cl.owner_token = p_owner_token;

    GET DIAGNOSTICS v_count = ROW_COUNT;
    RETURN QUERY SELECT (v_count > 0);
END;
$$;


-- ============================================================================
-- COUNTER FUNCTIONS
-- ============================================================================

-- increment_counter: atomic increment-or-create with TTL mode control.
--
-- Parameters:
--   p_namespace        — counter namespace
--   p_counter_key      — counter key within namespace
--   p_delta            — amount to add (negative for decrement)
--   p_ttl_millis       — TTL in milliseconds (null for no TTL)
--   p_ttl_mode         — 'PRESERVE_EXISTING' | 'REPLACE' | 'REMOVE'
--   p_create_if_missing — if false, only update existing live counters
--
-- Returns a single row:
--   counter_value — the resulting counter value (null if not found and create_if_missing=false)
--   version       — the row version after the operation (null if not found)

CREATE OR REPLACE FUNCTION peegee_cache.increment_counter(
    p_namespace         TEXT,
    p_counter_key       TEXT,
    p_delta             BIGINT,
    p_ttl_millis        BIGINT DEFAULT NULL,
    p_ttl_mode          TEXT DEFAULT 'PRESERVE_EXISTING',
    p_create_if_missing BOOLEAN DEFAULT TRUE
)
RETURNS TABLE (
    counter_value BIGINT,
    version       BIGINT
)
LANGUAGE plpgsql AS $$
DECLARE
    v_value   BIGINT;
    v_version BIGINT;
    v_ttl_interval INTERVAL := CASE WHEN p_ttl_millis IS NULL THEN NULL
                                    ELSE (p_ttl_millis * INTERVAL '1 millisecond') END;
BEGIN
    IF NOT p_create_if_missing THEN
        -- Update-only mode: increment existing live counter
        UPDATE peegee_cache.cache_counters cc
        SET counter_value = cc.counter_value + p_delta,
            version = cc.version + 1,
            updated_at = NOW()
        WHERE cc.namespace = p_namespace
          AND cc.counter_key = p_counter_key
          AND (cc.expires_at IS NULL OR cc.expires_at > NOW())
        RETURNING cc.counter_value, cc.version INTO v_value, v_version;

        RETURN QUERY SELECT v_value, v_version;
        RETURN;
    END IF;

    -- Delete expired row first (so upsert sees a clean slot)
    DELETE FROM peegee_cache.cache_counters cc
    WHERE cc.namespace = p_namespace
      AND cc.counter_key = p_counter_key
      AND cc.expires_at IS NOT NULL
      AND cc.expires_at <= NOW();

    -- Upsert with TTL mode
    IF p_ttl_mode = 'REPLACE' THEN
        INSERT INTO peegee_cache.cache_counters (
            namespace, counter_key, counter_value, version,
            created_at, updated_at, expires_at
        )
        VALUES (
            p_namespace, p_counter_key, p_delta, 1, NOW(), NOW(),
            CASE WHEN p_ttl_millis IS NULL THEN NULL
                 ELSE NOW() + v_ttl_interval END
        )
        ON CONFLICT (namespace, counter_key)
        DO UPDATE SET
            counter_value = peegee_cache.cache_counters.counter_value + EXCLUDED.counter_value,
            version = peegee_cache.cache_counters.version + 1,
            updated_at = NOW(),
            expires_at = EXCLUDED.expires_at
        RETURNING cache_counters.counter_value, cache_counters.version INTO v_value, v_version;

    ELSIF p_ttl_mode = 'REMOVE' THEN
        INSERT INTO peegee_cache.cache_counters (
            namespace, counter_key, counter_value, version,
            created_at, updated_at, expires_at
        )
        VALUES (
            p_namespace, p_counter_key, p_delta, 1, NOW(), NOW(),
            CASE WHEN p_ttl_millis IS NULL THEN NULL
                 ELSE NOW() + v_ttl_interval END
        )
        ON CONFLICT (namespace, counter_key)
        DO UPDATE SET
            counter_value = peegee_cache.cache_counters.counter_value + EXCLUDED.counter_value,
            version = peegee_cache.cache_counters.version + 1,
            updated_at = NOW(),
            expires_at = NULL
        RETURNING cache_counters.counter_value, cache_counters.version INTO v_value, v_version;

    ELSE
        -- PRESERVE_EXISTING (default)
        INSERT INTO peegee_cache.cache_counters (
            namespace, counter_key, counter_value, version,
            created_at, updated_at, expires_at
        )
        VALUES (
            p_namespace, p_counter_key, p_delta, 1, NOW(), NOW(),
            CASE WHEN p_ttl_millis IS NULL THEN NULL
                 ELSE NOW() + v_ttl_interval END
        )
        ON CONFLICT (namespace, counter_key)
        DO UPDATE SET
            counter_value = peegee_cache.cache_counters.counter_value + EXCLUDED.counter_value,
            version = peegee_cache.cache_counters.version + 1,
            updated_at = NOW(),
            expires_at = COALESCE(peegee_cache.cache_counters.expires_at, EXCLUDED.expires_at)
        RETURNING cache_counters.counter_value, cache_counters.version INTO v_value, v_version;
    END IF;

    RETURN QUERY SELECT v_value, v_version;
END;
$$;


-- set_counter: set an exact counter value via upsert.
--
-- Parameters:
--   p_namespace    — counter namespace
--   p_counter_key  — counter key within namespace
--   p_value        — exact value to set
--   p_ttl_millis   — TTL in milliseconds (null for no TTL)
--
-- Returns a single row:
--   counter_value — the stored value
--   version       — the row version after the operation

CREATE OR REPLACE FUNCTION peegee_cache.set_counter(
    p_namespace   TEXT,
    p_counter_key TEXT,
    p_value       BIGINT,
    p_ttl_millis  BIGINT DEFAULT NULL
)
RETURNS TABLE (
    counter_value BIGINT,
    version       BIGINT
)
LANGUAGE plpgsql AS $$
DECLARE
    v_value   BIGINT;
    v_version BIGINT;
BEGIN
    INSERT INTO peegee_cache.cache_counters (
        namespace, counter_key, counter_value, version,
        created_at, updated_at, expires_at
    )
    VALUES (
        p_namespace, p_counter_key, p_value, 1, NOW(), NOW(),
        CASE WHEN p_ttl_millis IS NULL THEN NULL
             ELSE NOW() + (p_ttl_millis * INTERVAL '1 millisecond') END
    )
    ON CONFLICT (namespace, counter_key)
    DO UPDATE SET
        counter_value = EXCLUDED.counter_value,
        version = peegee_cache.cache_counters.version + 1,
        updated_at = NOW(),
        expires_at = EXCLUDED.expires_at
    RETURNING cache_counters.counter_value, cache_counters.version INTO v_value, v_version;

    RETURN QUERY SELECT v_value, v_version;
END;
$$;


-- delete_counter: unconditional delete.
--
-- Returns:
--   deleted — true if a row was removed

CREATE OR REPLACE FUNCTION peegee_cache.delete_counter(
    p_namespace   TEXT,
    p_counter_key TEXT
)
RETURNS TABLE (deleted BOOLEAN)
LANGUAGE plpgsql AS $$
DECLARE
    v_count INT;
BEGIN
    DELETE FROM peegee_cache.cache_counters cc
    WHERE cc.namespace = p_namespace
      AND cc.counter_key = p_counter_key;

    GET DIAGNOSTICS v_count = ROW_COUNT;
    RETURN QUERY SELECT (v_count > 0);
END;
$$;


-- ============================================================================
-- CACHE ENTRY FUNCTIONS
-- ============================================================================

-- set_entry: unified cache write with mode support.
--
-- Parameters:
--   p_namespace        — entry namespace
--   p_cache_key        — entry key within namespace
--   p_value_type       — 'BYTES' | 'STRING' | 'JSON' | 'LONG'
--   p_value_bytes      — binary payload (null for LONG type)
--   p_numeric_value    — numeric value (null for non-LONG types)
--   p_ttl_millis       — TTL in milliseconds (null for no TTL)
--   p_mode             — 'UPSERT' | 'ONLY_IF_ABSENT' | 'ONLY_IF_PRESENT' | 'ONLY_IF_VERSION_MATCHES'
--   p_expected_version — required when mode = 'ONLY_IF_VERSION_MATCHES'
--
-- Returns a single row:
--   applied — true if the write was applied
--   version — the resulting row version (null if not applied)

CREATE OR REPLACE FUNCTION peegee_cache.set_entry(
    p_namespace        TEXT,
    p_cache_key        TEXT,
    p_value_type       TEXT,
    p_value_bytes      BYTEA,
    p_numeric_value    BIGINT,
    p_ttl_millis       BIGINT DEFAULT NULL,
    p_mode             TEXT DEFAULT 'UPSERT',
    p_expected_version BIGINT DEFAULT NULL
)
RETURNS TABLE (
    applied BOOLEAN,
    version BIGINT
)
LANGUAGE plpgsql AS $$
DECLARE
    v_version   BIGINT;
    v_ttl_expr  TIMESTAMPTZ := CASE WHEN p_ttl_millis IS NULL THEN NULL
                                    ELSE NOW() + (p_ttl_millis * INTERVAL '1 millisecond') END;
BEGIN
    IF p_mode = 'UPSERT' THEN
        INSERT INTO peegee_cache.cache_entries (
            namespace, cache_key, value_type, value_bytes, numeric_value,
            version, created_at, updated_at, expires_at, hit_count, last_accessed_at
        )
        VALUES (
            p_namespace, p_cache_key, p_value_type, p_value_bytes, p_numeric_value,
            1, NOW(), NOW(), v_ttl_expr, 0, NULL
        )
        ON CONFLICT (namespace, cache_key)
        DO UPDATE SET
            value_type    = EXCLUDED.value_type,
            value_bytes   = EXCLUDED.value_bytes,
            numeric_value = EXCLUDED.numeric_value,
            version       = peegee_cache.cache_entries.version + 1,
            updated_at    = NOW(),
            expires_at    = EXCLUDED.expires_at
        RETURNING cache_entries.version INTO v_version;

        RETURN QUERY SELECT TRUE, v_version;

    ELSIF p_mode = 'ONLY_IF_ABSENT' THEN
        -- Delete expired row first so insert sees a clean slot
        DELETE FROM peegee_cache.cache_entries ce
        WHERE ce.namespace = p_namespace
          AND ce.cache_key = p_cache_key
          AND ce.expires_at IS NOT NULL
          AND ce.expires_at <= NOW();

        INSERT INTO peegee_cache.cache_entries (
            namespace, cache_key, value_type, value_bytes, numeric_value,
            version, created_at, updated_at, expires_at, hit_count, last_accessed_at
        )
        VALUES (
            p_namespace, p_cache_key, p_value_type, p_value_bytes, p_numeric_value,
            1, NOW(), NOW(), v_ttl_expr, 0, NULL
        )
        ON CONFLICT (namespace, cache_key) DO NOTHING
        RETURNING cache_entries.version INTO v_version;

        IF v_version IS NOT NULL THEN
            RETURN QUERY SELECT TRUE, v_version;
        ELSE
            RETURN QUERY SELECT FALSE, NULL::BIGINT;
        END IF;

    ELSIF p_mode = 'ONLY_IF_PRESENT' THEN
        UPDATE peegee_cache.cache_entries ce
        SET value_type    = p_value_type,
            value_bytes   = p_value_bytes,
            numeric_value = p_numeric_value,
            version       = ce.version + 1,
            updated_at    = NOW(),
            expires_at    = v_ttl_expr
        WHERE ce.namespace = p_namespace
          AND ce.cache_key = p_cache_key
          AND (ce.expires_at IS NULL OR ce.expires_at > NOW())
        RETURNING ce.version INTO v_version;

        IF v_version IS NOT NULL THEN
            RETURN QUERY SELECT TRUE, v_version;
        ELSE
            RETURN QUERY SELECT FALSE, NULL::BIGINT;
        END IF;

    ELSIF p_mode = 'ONLY_IF_VERSION_MATCHES' THEN
        UPDATE peegee_cache.cache_entries ce
        SET value_type    = p_value_type,
            value_bytes   = p_value_bytes,
            numeric_value = p_numeric_value,
            version       = ce.version + 1,
            updated_at    = NOW(),
            expires_at    = v_ttl_expr
        WHERE ce.namespace = p_namespace
          AND ce.cache_key = p_cache_key
          AND ce.version = p_expected_version
          AND (ce.expires_at IS NULL OR ce.expires_at > NOW())
        RETURNING ce.version INTO v_version;

        IF v_version IS NOT NULL THEN
            RETURN QUERY SELECT TRUE, v_version;
        ELSE
            RETURN QUERY SELECT FALSE, NULL::BIGINT;
        END IF;

    ELSE
        RAISE EXCEPTION 'Unknown set_entry mode: %', p_mode;
    END IF;
END;
$$;


-- delete_entry: unconditional delete.
--
-- Returns:
--   deleted — true if a row was removed

CREATE OR REPLACE FUNCTION peegee_cache.delete_entry(
    p_namespace TEXT,
    p_cache_key TEXT
)
RETURNS TABLE (deleted BOOLEAN)
LANGUAGE plpgsql AS $$
DECLARE
    v_count INT;
BEGIN
    DELETE FROM peegee_cache.cache_entries ce
    WHERE ce.namespace = p_namespace
      AND ce.cache_key = p_cache_key;

    GET DIAGNOSTICS v_count = ROW_COUNT;
    RETURN QUERY SELECT (v_count > 0);
END;
$$;
