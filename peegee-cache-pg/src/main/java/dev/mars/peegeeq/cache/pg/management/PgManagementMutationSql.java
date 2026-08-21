package dev.mars.peegeeq.cache.pg.management;

/** Schema-qualified, parameterized SQL for management reveals and mutations. */
final class PgManagementMutationSql {

    final String revealEntry;
    final String upsertEntryPersistent;
    final String insertEntryIfAbsentPersistent;
    final String updateEntryIfPresentPersistent;
    final String updateEntryIfVersionPersistent;
    final String conditionNotMet;

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
                              entry.version, entry.created_at, entry.updated_at, entry.expires_at,
                              CASE WHEN entry.expires_at IS NULL THEN NULL::BIGINT
                                   ELSE GREATEST(0, FLOOR(EXTRACT(EPOCH FROM
                                        (entry.expires_at - statement_timestamp())) * 1000))::BIGINT
                                   END AS ttl_millis
                )
                SELECT 'APPLIED'::TEXT AS outcome, FALSE AS created,
                       namespace, cache_key, value_type, size_bytes,
                       version, created_at, updated_at, expires_at, ttl_millis
                  FROM mutated
                UNION ALL
                SELECT CASE WHEN EXISTS (SELECT 1 FROM observed)
                            THEN 'VERSION_MISMATCH' ELSE 'NOT_FOUND' END,
                       FALSE,
                       NULL::TEXT, NULL::TEXT, NULL::TEXT, NULL::BIGINT,
                       NULL::BIGINT, NULL::TIMESTAMPTZ, NULL::TIMESTAMPTZ,
                       NULL::TIMESTAMPTZ, NULL::BIGINT
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
    }
}
