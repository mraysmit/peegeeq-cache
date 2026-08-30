package dev.mars.peegeeq.cache.pg.management;

/** Schema-qualified, parameterized SQL for management reveals and mutations. */
final class PgManagementMutationSql {

    final String revealEntry;
    final String upsertEntryPersistent;
    final String insertEntryIfAbsentPersistent;
    final String updateEntryIfPresentPersistent;
    final String updateEntryIfVersionPersistent;
    final String conditionNotMet;
    final String expireEntry;
    final String persistEntry;
    final String touchEntry;
    final String deleteEntry;
    final String setCounterIfAbsent;
    final String setCounterIfVersion;
    final String adjustCounterIfVersion;
    final String expireCounter;
    final String persistCounter;
    final String deleteCounter;
    final String revealLockOwner;
    final String forceReleaseLock;

    PgManagementMutationSql(String schemaName) {
        String schema = PgManagementReadSql.requireSchema(schemaName);
        revealEntry = """
                SELECT namespace,
                       cache_key,
                       value_type,
                       value_bytes,
                       numeric_value,
                       version,
                       statement_timestamp() AS revealed_at
                  FROM %s.cache_entries
                 WHERE namespace = $1
                   AND cache_key = $2
                   AND (expires_at IS NULL OR expires_at > statement_timestamp())
                """.formatted(schema);

        String entries = schema + ".cache_entries";
        upsertEntryPersistent = """
                INSERT INTO %1$s
                    (namespace, cache_key, value_type, value_bytes, numeric_value,
                     version, created_at, updated_at, expires_at, hit_count, last_accessed_at)
                VALUES ($1, $2, $3, $4, $5, 1,
                        statement_timestamp(), statement_timestamp(),
                        CASE WHEN $6::TEXT IN ('USE_DEFAULT', 'REPLACE')
                                  AND $7::BIGINT IS NOT NULL
                             THEN statement_timestamp() + ($7::BIGINT * INTERVAL '1 millisecond')
                             ELSE NULL END,
                        0, NULL)
                ON CONFLICT (namespace, cache_key) DO UPDATE
                    SET value_type = EXCLUDED.value_type,
                        value_bytes = EXCLUDED.value_bytes,
                        numeric_value = EXCLUDED.numeric_value,
                        version = %1$s.version + 1,
                        updated_at = statement_timestamp(),
                        expires_at = CASE
                            WHEN $6::TEXT = 'REMOVE' THEN NULL
                            WHEN $6::TEXT IN ('USE_DEFAULT', 'REPLACE')
                                 AND $7::BIGINT IS NOT NULL
                                THEN statement_timestamp() + ($7::BIGINT * INTERVAL '1 millisecond')
                            ELSE NULL END
                RETURNING 'APPLIED'::TEXT AS outcome,
                          (version = 1) AS created,
                          namespace, cache_key, value_type,
                          CASE WHEN value_type = 'LONG' THEN 8::BIGINT
                               ELSE octet_length(value_bytes)::BIGINT END AS size_bytes,
                          version, created_at, updated_at, expires_at,
                          CASE WHEN expires_at IS NULL THEN NULL::BIGINT
                               ELSE GREATEST(0, FLOOR(EXTRACT(EPOCH FROM
                                    (expires_at - statement_timestamp())) * 1000))::BIGINT
                               END AS ttl_millis
                """.formatted(entries);

        insertEntryIfAbsentPersistent = """
                WITH mutated AS (
                    INSERT INTO %1$s
                        (namespace, cache_key, value_type, value_bytes, numeric_value,
                         version, created_at, updated_at, expires_at, hit_count, last_accessed_at)
                    SELECT $1, $2, $3, $4, $5, 1,
                           statement_timestamp(), statement_timestamp(),
                           CASE WHEN $6::TEXT IN ('USE_DEFAULT', 'REPLACE')
                                     AND $7::BIGINT IS NOT NULL
                                THEN statement_timestamp() + ($7::BIGINT * INTERVAL '1 millisecond')
                                ELSE NULL END,
                           0, NULL
                     WHERE $6::TEXT <> 'PRESERVE_EXISTING'
                    ON CONFLICT (namespace, cache_key) DO UPDATE
                        SET value_type = EXCLUDED.value_type,
                            value_bytes = EXCLUDED.value_bytes,
                            numeric_value = EXCLUDED.numeric_value,
                            version = 1,
                            created_at = statement_timestamp(),
                            updated_at = statement_timestamp(),
                            expires_at = EXCLUDED.expires_at,
                            hit_count = 0,
                            last_accessed_at = NULL
                      WHERE %1$s.expires_at IS NOT NULL
                        AND %1$s.expires_at <= statement_timestamp()
                    RETURNING namespace, cache_key, value_type,
                              CASE WHEN value_type = 'LONG' THEN 8::BIGINT
                                   ELSE octet_length(value_bytes)::BIGINT END AS size_bytes,
                              version, created_at, updated_at, expires_at,
                              CASE WHEN expires_at IS NULL THEN NULL::BIGINT
                                   ELSE GREATEST(0, FLOOR(EXTRACT(EPOCH FROM
                                        (expires_at - statement_timestamp())) * 1000))::BIGINT
                                   END AS ttl_millis
                )
                SELECT 'APPLIED'::TEXT AS outcome, TRUE AS created,
                       namespace, cache_key, value_type, size_bytes,
                       version, created_at, updated_at, expires_at, ttl_millis
                  FROM mutated
                UNION ALL
                SELECT 'CONDITION_NOT_MET', FALSE,
                       NULL::TEXT, NULL::TEXT, NULL::TEXT, NULL::BIGINT,
                       NULL::BIGINT, NULL::TIMESTAMPTZ, NULL::TIMESTAMPTZ,
                       NULL::TIMESTAMPTZ, NULL::BIGINT
                 WHERE NOT EXISTS (SELECT 1 FROM mutated)
                """.formatted(entries);

        updateEntryIfPresentPersistent = """
                WITH mutated AS (
                    UPDATE %1$s
                       SET value_type = $3,
                           value_bytes = $4,
                           numeric_value = $5,
                           version = version + 1,
                           updated_at = statement_timestamp(),
                           expires_at = CASE
                               WHEN $6::TEXT = 'PRESERVE_EXISTING' THEN expires_at
                               WHEN $6::TEXT = 'REMOVE' THEN NULL
                               WHEN $6::TEXT IN ('USE_DEFAULT', 'REPLACE')
                                    AND $7::BIGINT IS NOT NULL
                                   THEN statement_timestamp() + ($7::BIGINT * INTERVAL '1 millisecond')
                               ELSE NULL END
                     WHERE namespace = $1
                       AND cache_key = $2
                       AND (expires_at IS NULL OR expires_at > statement_timestamp())
                    RETURNING namespace, cache_key, value_type,
                              CASE WHEN value_type = 'LONG' THEN 8::BIGINT
                                   ELSE octet_length(value_bytes)::BIGINT END AS size_bytes,
                              version, created_at, updated_at, expires_at,
                              CASE WHEN expires_at IS NULL THEN NULL::BIGINT
                                   ELSE GREATEST(0, FLOOR(EXTRACT(EPOCH FROM
                                        (expires_at - statement_timestamp())) * 1000))::BIGINT
                                   END AS ttl_millis
                )
                SELECT 'APPLIED'::TEXT AS outcome, FALSE AS created,
                       namespace, cache_key, value_type, size_bytes,
                       version, created_at, updated_at, expires_at, ttl_millis
                  FROM mutated
                UNION ALL
                SELECT 'CONDITION_NOT_MET', FALSE,
                       NULL::TEXT, NULL::TEXT, NULL::TEXT, NULL::BIGINT,
                       NULL::BIGINT, NULL::TIMESTAMPTZ, NULL::TIMESTAMPTZ,
                       NULL::TIMESTAMPTZ, NULL::BIGINT
                 WHERE NOT EXISTS (SELECT 1 FROM mutated)
                """.formatted(entries);

        updateEntryIfVersionPersistent = """
                WITH observed AS MATERIALIZED (
                    SELECT version
                      FROM %1$s
                     WHERE namespace = $1
                       AND cache_key = $2
                       AND (expires_at IS NULL OR expires_at > statement_timestamp())
                     FOR UPDATE
                ),
                mutated AS (
                    UPDATE %1$s entry
                       SET value_type = $4,
                           value_bytes = $5,
                           numeric_value = $6,
                           version = entry.version + 1,
                           updated_at = statement_timestamp(),
                           expires_at = CASE
                               WHEN $7::TEXT = 'PRESERVE_EXISTING' THEN entry.expires_at
                               WHEN $7::TEXT = 'REMOVE' THEN NULL
                               WHEN $7::TEXT IN ('USE_DEFAULT', 'REPLACE')
                                    AND $8::BIGINT IS NOT NULL
                                   THEN statement_timestamp() + ($8::BIGINT * INTERVAL '1 millisecond')
                               ELSE NULL END
                      FROM observed
                     WHERE entry.namespace = $1
                       AND entry.cache_key = $2
                       AND observed.version = $3
                    RETURNING entry.namespace, entry.cache_key, entry.value_type,
                              CASE WHEN entry.value_type = 'LONG' THEN 8::BIGINT
                                   ELSE octet_length(entry.value_bytes)::BIGINT END AS size_bytes,
                              entry.version, entry.created_at, entry.updated_at,
                              entry.last_accessed_at, entry.expires_at,
                              CASE WHEN entry.expires_at IS NULL THEN NULL::BIGINT
                                   ELSE GREATEST(0, FLOOR(EXTRACT(EPOCH FROM
                                        (entry.expires_at - statement_timestamp())) * 1000))::BIGINT
                                   END AS ttl_millis
                )
                SELECT 'APPLIED'::TEXT AS outcome, FALSE AS created,
                       namespace, cache_key, value_type, size_bytes,
                       version, created_at, updated_at, last_accessed_at, expires_at, ttl_millis
                  FROM mutated
                UNION ALL
                SELECT CASE WHEN EXISTS (SELECT 1 FROM observed)
                            THEN 'VERSION_MISMATCH' ELSE 'NOT_FOUND' END,
                       FALSE,
                       NULL::TEXT, NULL::TEXT, NULL::TEXT, NULL::BIGINT,
                       NULL::BIGINT, NULL::TIMESTAMPTZ, NULL::TIMESTAMPTZ,
                       NULL::TIMESTAMPTZ, NULL::TIMESTAMPTZ, NULL::BIGINT
                 WHERE NOT EXISTS (SELECT 1 FROM mutated)
                """.formatted(entries);

        conditionNotMet = """
                SELECT 'CONDITION_NOT_MET'::TEXT AS outcome,
                       FALSE AS created,
                       NULL::TEXT AS namespace,
                       NULL::TEXT AS cache_key,
                       NULL::TEXT AS value_type,
                       NULL::BIGINT AS size_bytes,
                       NULL::BIGINT AS version,
                       NULL::TIMESTAMPTZ AS created_at,
                       NULL::TIMESTAMPTZ AS updated_at,
                       NULL::TIMESTAMPTZ AS expires_at,
                       NULL::BIGINT AS ttl_millis
                """;

        expireEntry = """
                WITH observed AS MATERIALIZED (
                    SELECT version
                      FROM %1$s
                     WHERE namespace = $1
                       AND cache_key = $2
                       AND (expires_at IS NULL OR expires_at > statement_timestamp())
                     FOR UPDATE
                ),
                mutated AS (
                    UPDATE %1$s entry
                       SET version = entry.version + 1,
                           updated_at = statement_timestamp(),
                           expires_at = statement_timestamp()
                               + ($4::BIGINT * INTERVAL '1 millisecond')
                      FROM observed
                     WHERE entry.namespace = $1
                       AND entry.cache_key = $2
                       AND observed.version = $3
                    RETURNING entry.namespace, entry.cache_key, entry.value_type,
                              CASE WHEN entry.value_type = 'LONG' THEN 8::BIGINT
                                   ELSE octet_length(entry.value_bytes)::BIGINT END AS size_bytes,
                              entry.version, entry.created_at, entry.updated_at, entry.expires_at,
                              GREATEST(0, FLOOR(EXTRACT(EPOCH FROM
                                  (entry.expires_at - statement_timestamp())) * 1000))::BIGINT
                                  AS ttl_millis
                )
                SELECT 'APPLIED'::TEXT AS outcome,
                       namespace, cache_key, value_type, size_bytes,
                       version, created_at, updated_at, expires_at, ttl_millis
                  FROM mutated
                UNION ALL
                SELECT CASE WHEN EXISTS (SELECT 1 FROM observed)
                            THEN 'VERSION_MISMATCH' ELSE 'NOT_FOUND' END,
                       NULL::TEXT, NULL::TEXT, NULL::TEXT, NULL::BIGINT,
                       NULL::BIGINT, NULL::TIMESTAMPTZ, NULL::TIMESTAMPTZ,
                       NULL::TIMESTAMPTZ, NULL::BIGINT
                 WHERE NOT EXISTS (SELECT 1 FROM mutated)
                """.formatted(entries);

        persistEntry = """
                WITH observed AS MATERIALIZED (
                    SELECT version
                      FROM %1$s
                     WHERE namespace = $1
                       AND cache_key = $2
                       AND (expires_at IS NULL OR expires_at > statement_timestamp())
                     FOR UPDATE
                ),
                mutated AS (
                    UPDATE %1$s entry
                       SET version = entry.version + 1,
                           updated_at = statement_timestamp(),
                           expires_at = NULL
                      FROM observed
                     WHERE entry.namespace = $1
                       AND entry.cache_key = $2
                       AND observed.version = $3
                    RETURNING entry.namespace, entry.cache_key, entry.value_type,
                              CASE WHEN entry.value_type = 'LONG' THEN 8::BIGINT
                                   ELSE octet_length(entry.value_bytes)::BIGINT END AS size_bytes,
                              entry.version, entry.created_at, entry.updated_at, entry.expires_at,
                              NULL::BIGINT AS ttl_millis
                )
                SELECT 'APPLIED'::TEXT AS outcome,
                       namespace, cache_key, value_type, size_bytes,
                       version, created_at, updated_at, expires_at, ttl_millis
                  FROM mutated
                UNION ALL
                SELECT CASE WHEN EXISTS (SELECT 1 FROM observed)
                            THEN 'VERSION_MISMATCH' ELSE 'NOT_FOUND' END,
                       NULL::TEXT, NULL::TEXT, NULL::TEXT, NULL::BIGINT,
                       NULL::BIGINT, NULL::TIMESTAMPTZ, NULL::TIMESTAMPTZ,
                       NULL::TIMESTAMPTZ, NULL::BIGINT
                 WHERE NOT EXISTS (SELECT 1 FROM mutated)
                """.formatted(entries);

        touchEntry = """
                WITH observed AS MATERIALIZED (
                    SELECT version
                      FROM %1$s
                     WHERE namespace = $1
                       AND cache_key = $2
                       AND (expires_at IS NULL OR expires_at > statement_timestamp())
                     FOR UPDATE
                ),
                mutated AS (
                    UPDATE %1$s entry
                       SET updated_at = statement_timestamp(),
                           last_accessed_at = statement_timestamp(),
                           expires_at = CASE WHEN $4::BIGINT IS NULL
                               THEN entry.expires_at
                               ELSE statement_timestamp()
                                   + ($4::BIGINT * INTERVAL '1 millisecond') END
                      FROM observed
                     WHERE entry.namespace = $1
                       AND entry.cache_key = $2
                       AND observed.version = $3
                    RETURNING entry.namespace, entry.cache_key, entry.value_type,
                              CASE WHEN entry.value_type = 'LONG' THEN 8::BIGINT
                                   ELSE octet_length(entry.value_bytes)::BIGINT END AS size_bytes,
                              entry.version, entry.created_at, entry.updated_at,
                              entry.last_accessed_at, entry.expires_at,
                              CASE WHEN entry.expires_at IS NULL THEN NULL::BIGINT
                                   ELSE GREATEST(0, FLOOR(EXTRACT(EPOCH FROM
                                        (entry.expires_at - statement_timestamp())) * 1000))::BIGINT
                                   END AS ttl_millis
                )
                SELECT 'APPLIED'::TEXT AS outcome,
                       namespace, cache_key, value_type, size_bytes,
                       version, created_at, updated_at, last_accessed_at, expires_at, ttl_millis
                  FROM mutated
                UNION ALL
                SELECT CASE WHEN EXISTS (SELECT 1 FROM observed)
                            THEN 'VERSION_MISMATCH' ELSE 'NOT_FOUND' END,
                       NULL::TEXT, NULL::TEXT, NULL::TEXT, NULL::BIGINT,
                       NULL::BIGINT, NULL::TIMESTAMPTZ, NULL::TIMESTAMPTZ,
                       NULL::TIMESTAMPTZ, NULL::TIMESTAMPTZ, NULL::BIGINT
                 WHERE NOT EXISTS (SELECT 1 FROM mutated)
                """.formatted(entries);

        deleteEntry = """
                WITH observed AS MATERIALIZED (
                    SELECT version
                      FROM %1$s
                     WHERE namespace = $1
                       AND cache_key = $2
                       AND (expires_at IS NULL OR expires_at > statement_timestamp())
                     FOR UPDATE
                ),
                deleted AS (
                    DELETE FROM %1$s entry
                     USING observed
                     WHERE entry.namespace = $1
                       AND entry.cache_key = $2
                       AND observed.version = $3
                    RETURNING entry.version
                )
                SELECT 'APPLIED'::TEXT AS outcome, version
                  FROM deleted
                UNION ALL
                SELECT CASE WHEN EXISTS (SELECT 1 FROM observed)
                            THEN 'VERSION_MISMATCH' ELSE 'NOT_FOUND' END,
                       NULL::BIGINT
                 WHERE NOT EXISTS (SELECT 1 FROM deleted)
                """.formatted(entries);

        String counters = schema + ".cache_counters";
        setCounterIfAbsent = """
                WITH removed_expired AS (
                    DELETE FROM %1$s
                     WHERE namespace = $1
                       AND counter_key = $2
                       AND expires_at IS NOT NULL
                       AND expires_at <= statement_timestamp()
                ),
                inserted AS (
                    INSERT INTO %1$s (
                        namespace, counter_key, counter_value, version,
                        created_at, updated_at, expires_at)
                    VALUES (
                        $1, $2, $3, 1,
                        statement_timestamp(), statement_timestamp(),
                        CASE WHEN $4 = 'REPLACE'
                             THEN statement_timestamp()
                                 + ($5::BIGINT * INTERVAL '1 millisecond')
                             ELSE NULL END)
                    ON CONFLICT (namespace, counter_key) DO NOTHING
                    RETURNING namespace, counter_key, counter_value, version,
                              created_at, updated_at, expires_at,
                              CASE WHEN expires_at IS NULL THEN NULL::BIGINT
                                   ELSE GREATEST(0, FLOOR(EXTRACT(EPOCH FROM
                                        (expires_at - statement_timestamp())) * 1000))::BIGINT
                                   END AS ttl_millis
                )
                SELECT 'APPLIED'::TEXT AS outcome,
                       namespace, counter_key, counter_value, version,
                       created_at, updated_at, expires_at, ttl_millis
                  FROM inserted
                UNION ALL
                SELECT 'CONDITION_NOT_MET'::TEXT,
                       NULL::TEXT, NULL::TEXT, NULL::BIGINT, NULL::BIGINT,
                       NULL::TIMESTAMPTZ, NULL::TIMESTAMPTZ,
                       NULL::TIMESTAMPTZ, NULL::BIGINT
                 WHERE NOT EXISTS (SELECT 1 FROM inserted)
                """.formatted(counters);

        setCounterIfVersion = """
                WITH observed AS MATERIALIZED (
                    SELECT version
                      FROM %1$s
                     WHERE namespace = $1
                       AND counter_key = $2
                       AND (expires_at IS NULL OR expires_at > statement_timestamp())
                     FOR UPDATE
                ),
                mutated AS (
                    UPDATE %1$s counter
                       SET counter_value = $4,
                           version = counter.version + 1,
                           updated_at = statement_timestamp(),
                           expires_at = CASE $5
                               WHEN 'PRESERVE_EXISTING' THEN counter.expires_at
                               WHEN 'REPLACE' THEN statement_timestamp()
                                   + ($6::BIGINT * INTERVAL '1 millisecond')
                               WHEN 'REMOVE' THEN NULL
                           END
                      FROM observed
                     WHERE counter.namespace = $1
                       AND counter.counter_key = $2
                       AND observed.version = $3
                    RETURNING counter.namespace, counter.counter_key,
                              counter.counter_value, counter.version,
                              counter.created_at, counter.updated_at, counter.expires_at,
                              CASE WHEN counter.expires_at IS NULL THEN NULL::BIGINT
                                   ELSE GREATEST(0, FLOOR(EXTRACT(EPOCH FROM
                                        (counter.expires_at - statement_timestamp())) * 1000))::BIGINT
                                   END AS ttl_millis
                )
                SELECT 'APPLIED'::TEXT AS outcome,
                       namespace, counter_key, counter_value, version,
                       created_at, updated_at, expires_at, ttl_millis
                  FROM mutated
                UNION ALL
                SELECT CASE WHEN EXISTS (SELECT 1 FROM observed)
                            THEN 'VERSION_MISMATCH' ELSE 'NOT_FOUND' END,
                       NULL::TEXT, NULL::TEXT, NULL::BIGINT, NULL::BIGINT,
                       NULL::TIMESTAMPTZ, NULL::TIMESTAMPTZ,
                       NULL::TIMESTAMPTZ, NULL::BIGINT
                 WHERE NOT EXISTS (SELECT 1 FROM mutated)
                """.formatted(counters);

        adjustCounterIfVersion = """
                WITH observed AS MATERIALIZED (
                    SELECT version
                      FROM %1$s
                     WHERE namespace = $1
                       AND counter_key = $2
                       AND (expires_at IS NULL OR expires_at > statement_timestamp())
                     FOR UPDATE
                ),
                mutated AS (
                    UPDATE %1$s counter
                       SET counter_value = counter.counter_value + $4,
                           version = counter.version + 1,
                           updated_at = statement_timestamp(),
                           expires_at = CASE $5
                               WHEN 'PRESERVE_EXISTING' THEN counter.expires_at
                               WHEN 'REPLACE' THEN statement_timestamp()
                                   + ($6::BIGINT * INTERVAL '1 millisecond')
                               WHEN 'REMOVE' THEN NULL
                           END
                      FROM observed
                     WHERE counter.namespace = $1
                       AND counter.counter_key = $2
                       AND observed.version = $3
                    RETURNING counter.namespace, counter.counter_key,
                              counter.counter_value, counter.version,
                              counter.created_at, counter.updated_at, counter.expires_at,
                              CASE WHEN counter.expires_at IS NULL THEN NULL::BIGINT
                                   ELSE GREATEST(0, FLOOR(EXTRACT(EPOCH FROM
                                        (counter.expires_at - statement_timestamp())) * 1000))::BIGINT
                                   END AS ttl_millis
                )
                SELECT 'APPLIED'::TEXT AS outcome,
                       namespace, counter_key, counter_value, version,
                       created_at, updated_at, expires_at, ttl_millis
                  FROM mutated
                UNION ALL
                SELECT CASE WHEN EXISTS (SELECT 1 FROM observed)
                            THEN 'VERSION_MISMATCH' ELSE 'NOT_FOUND' END,
                       NULL::TEXT, NULL::TEXT, NULL::BIGINT, NULL::BIGINT,
                       NULL::TIMESTAMPTZ, NULL::TIMESTAMPTZ,
                       NULL::TIMESTAMPTZ, NULL::BIGINT
                 WHERE NOT EXISTS (SELECT 1 FROM mutated)
                """.formatted(counters);

        expireCounter = counterMetadataMutation(counters, """
                expires_at = statement_timestamp()
                    + ($4::BIGINT * INTERVAL '1 millisecond')
                """);

        persistCounter = counterMetadataMutation(counters, "expires_at = NULL");

        deleteCounter = """
                WITH observed AS MATERIALIZED (
                    SELECT version
                      FROM %1$s
                     WHERE namespace = $1
                       AND counter_key = $2
                       AND (expires_at IS NULL OR expires_at > statement_timestamp())
                     FOR UPDATE
                ),
                deleted AS (
                    DELETE FROM %1$s counter
                     USING observed
                     WHERE counter.namespace = $1
                       AND counter.counter_key = $2
                       AND observed.version = $3
                    RETURNING counter.version
                )
                SELECT 'APPLIED'::TEXT AS outcome, version
                  FROM deleted
                UNION ALL
                SELECT CASE WHEN EXISTS (SELECT 1 FROM observed)
                            THEN 'VERSION_MISMATCH' ELSE 'NOT_FOUND' END,
                       NULL::BIGINT
                 WHERE NOT EXISTS (SELECT 1 FROM deleted)
                """.formatted(counters);

        String locks = schema + ".cache_locks";
        revealLockOwner = """
                SELECT namespace, lock_key, owner_token, version,
                       statement_timestamp() AS revealed_at
                  FROM %s
                 WHERE namespace = $1
                   AND lock_key = $2
                   AND lease_expires_at > statement_timestamp()
                """.formatted(locks);

        forceReleaseLock = """
                WITH observed AS MATERIALIZED (
                    SELECT version
                      FROM %1$s
                     WHERE namespace = $1
                       AND lock_key = $2
                       AND lease_expires_at > statement_timestamp()
                     FOR UPDATE
                ),
                deleted AS (
                    DELETE FROM %1$s lock
                     USING observed
                     WHERE lock.namespace = $1
                       AND lock.lock_key = $2
                       AND observed.version = $3
                    RETURNING lock.version
                )
                SELECT 'APPLIED'::TEXT AS outcome, version
                  FROM deleted
                UNION ALL
                SELECT CASE WHEN EXISTS (SELECT 1 FROM observed)
                            THEN 'VERSION_MISMATCH' ELSE 'NOT_FOUND' END,
                       NULL::BIGINT
                 WHERE NOT EXISTS (SELECT 1 FROM deleted)
                """.formatted(locks);
    }

    private static String counterMetadataMutation(String counters, String expiryAssignment) {
        return """
                WITH observed AS MATERIALIZED (
                    SELECT version
                      FROM %1$s
                     WHERE namespace = $1
                       AND counter_key = $2
                       AND (expires_at IS NULL OR expires_at > statement_timestamp())
                     FOR UPDATE
                ),
                mutated AS (
                    UPDATE %1$s counter
                       SET version = counter.version + 1,
                           updated_at = statement_timestamp(),
                           %2$s
                      FROM observed
                     WHERE counter.namespace = $1
                       AND counter.counter_key = $2
                       AND observed.version = $3
                    RETURNING counter.namespace, counter.counter_key,
                              counter.counter_value, counter.version,
                              counter.created_at, counter.updated_at, counter.expires_at,
                              CASE WHEN counter.expires_at IS NULL THEN NULL::BIGINT
                                   ELSE GREATEST(0, FLOOR(EXTRACT(EPOCH FROM
                                        (counter.expires_at - statement_timestamp())) * 1000))::BIGINT
                                   END AS ttl_millis
                )
                SELECT 'APPLIED'::TEXT AS outcome,
                       namespace, counter_key, counter_value, version,
                       created_at, updated_at, expires_at, ttl_millis
                  FROM mutated
                UNION ALL
                SELECT CASE WHEN EXISTS (SELECT 1 FROM observed)
                            THEN 'VERSION_MISMATCH' ELSE 'NOT_FOUND' END,
                       NULL::TEXT, NULL::TEXT, NULL::BIGINT, NULL::BIGINT,
                       NULL::TIMESTAMPTZ, NULL::TIMESTAMPTZ,
                       NULL::TIMESTAMPTZ, NULL::BIGINT
                 WHERE NOT EXISTS (SELECT 1 FROM mutated)
                """.formatted(counters, expiryAssignment);
    }
}
