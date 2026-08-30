package dev.mars.peegeeq.cache.pg.management;

import dev.mars.peegeeq.cache.api.management.EntryTtlMode;
import dev.mars.peegeeq.cache.api.management.EntryDeleteFilter;
import dev.mars.peegeeq.cache.api.management.CounterEntry;
import dev.mars.peegeeq.cache.api.management.ForceReleaseLockRequest;
import dev.mars.peegeeq.cache.api.management.ManagementCacheSetRequest;
import dev.mars.peegeeq.cache.api.management.ManagementCounterSetRequest;
import dev.mars.peegeeq.cache.api.management.ManagementCounterAdjustRequest;
import dev.mars.peegeeq.cache.api.management.ManagementCounterException;
import dev.mars.peegeeq.cache.api.management.ManagementEntryMetadata;
import dev.mars.peegeeq.cache.api.management.ManagementMutationOutcome;
import dev.mars.peegeeq.cache.api.management.ManagementSetResult;
import dev.mars.peegeeq.cache.api.management.ManagementTtl;
import dev.mars.peegeeq.cache.api.management.RevealedEntryValue;
import dev.mars.peegeeq.cache.api.management.RevealedLockOwner;
import dev.mars.peegeeq.cache.api.management.VersionedCacheKeyRequest;
import dev.mars.peegeeq.cache.api.management.VersionedCounterDeleteRequest;
import dev.mars.peegeeq.cache.api.management.VersionedCounterTtlRequest;
import dev.mars.peegeeq.cache.api.management.VersionedEntryDeleteRequest;
import dev.mars.peegeeq.cache.api.management.VersionedEntryTouchRequest;
import dev.mars.peegeeq.cache.api.management.VersionedEntryTtlRequest;
import dev.mars.peegeeq.cache.api.management.VersionedMutationResult;
import dev.mars.peegeeq.cache.api.model.CacheKey;
import dev.mars.peegeeq.cache.api.model.CacheValue;
import dev.mars.peegeeq.cache.api.model.LockKey;
import dev.mars.peegeeq.cache.api.model.SetMode;
import dev.mars.peegeeq.cache.api.model.ValueType;
import io.vertx.core.Future;
import io.vertx.core.buffer.Buffer;
import io.vertx.pgclient.PgException;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.Tuple;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Authoritative PostgreSQL reveals and atomic management mutations. */
public final class PgManagementMutationRepository {

    private final Pool pool;
    private final PgManagementMutationSql sql;
    private final String schema;

    public PgManagementMutationRepository(Pool pool, String schemaName) {
        this.pool = Objects.requireNonNull(pool, "pool");
        this.sql = new PgManagementMutationSql(schemaName);
        this.schema = PgManagementReadSql.requireSchema(schemaName);
    }

    /** Resolves a filter preview to an exact bounded key/version set. */
    Future<PgBulkDeleteSelection> resolveEntryDelete(EntryDeleteFilter selection) {
        Objects.requireNonNull(selection, "selection");
        if (!selection.targets().isEmpty()) {
            List<Future<Long>> sizes = selection.targets().stream()
                    .map(target -> pool.preparedQuery("""
                                    SELECT CASE WHEN value_type = 'LONG' THEN 8::BIGINT
                                                ELSE COALESCE(octet_length(value_bytes), 0)::BIGINT
                                           END AS size_bytes
                                      FROM %s.cache_entries
                                     WHERE namespace = $1 AND cache_key = $2
                                    """.formatted(schema))
                            .execute(Tuple.of(target.key().namespace(), target.key().key()))
                            .map(rows -> {
                                var iterator = rows.iterator();
                                return iterator.hasNext()
                                        ? iterator.next().getLong("size_bytes") : 0L;
                            }))
                    .toList();
            return Future.all(sizes).map(results -> {
                long totalBytes = 0;
                for (int index = 0; index < sizes.size(); index++) {
                    totalBytes += (Long) results.resultAt(index);
                }
                return new PgBulkDeleteSelection(selection.targets(), totalBytes);
            });
        }
        String statement = """
                SELECT cache_key,
                       version,
                       CASE WHEN value_type = 'LONG' THEN 8::BIGINT
                            ELSE COALESCE(octet_length(value_bytes), 0)::BIGINT END AS size_bytes
                  FROM %s.cache_entries
                 WHERE namespace = $1
                   AND ($2::TEXT IS NULL OR cache_key LIKE $2 ESCAPE '\\')
                   AND ($3::TEXT IS NULL OR value_type = $3)
                   AND CASE $4::TEXT
                       WHEN 'ALL_LIVE' THEN expires_at IS NULL OR expires_at > statement_timestamp()
                       WHEN 'PERSISTENT' THEN expires_at IS NULL
                       WHEN 'EXPIRING' THEN expires_at > statement_timestamp()
                       WHEN 'INCLUDE_EXPIRED' THEN TRUE
                       ELSE FALSE
                       END
                 ORDER BY cache_key ASC
                 LIMIT 10001
                """.formatted(schema);
        String prefix = selection.prefix() == null
                ? null : escapeLike(selection.prefix()) + "%";
        String valueType = selection.valueType() == null
                ? null : selection.valueType().name();
        return pool.preparedQuery(statement)
                .execute(Tuple.of(
                        selection.namespace(),
                        prefix,
                        valueType,
                        selection.ttlFilter().name()))
                .map(rows -> {
                    List<dev.mars.peegeeq.cache.api.management.VersionedCacheKeyTarget> targets =
                            new ArrayList<>();
                    long totalBytes = 0;
                    for (Row row : rows) {
                        targets.add(new dev.mars.peegeeq.cache.api.management.VersionedCacheKeyTarget(
                                new CacheKey(selection.namespace(), row.getString("cache_key")),
                                row.getLong("version")));
                        totalBytes += row.getLong("size_bytes");
                    }
                    return new PgBulkDeleteSelection(targets, totalBytes);
                });
    }

    private static String escapeLike(String value) {
        return value.replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
    }

    /** Returns value, version, and database observation time from one live-row statement. */
    public Future<Optional<RevealedEntryValue>> revealEntry(CacheKey key) {
        Objects.requireNonNull(key, "key");
        return pool.preparedQuery(sql.revealEntry)
                .execute(Tuple.of(key.namespace(), key.key()))
                .map(rows -> {
                    var iterator = rows.iterator();
                    return iterator.hasNext()
                            ? Optional.of(mapRevealedEntry(iterator.next()))
                            : Optional.empty();
                });
    }

    /** Returns the condition outcome and resulting representation from one mutation statement. */
    public Future<ManagementSetResult> setEntry(
            ManagementCacheSetRequest request,
            Duration defaultEntryTtl) {
        Objects.requireNonNull(request, "request");
        boolean preserveCreation = request.ttlMode() == EntryTtlMode.PRESERVE_EXISTING
                && request.mode() == SetMode.ONLY_IF_ABSENT;
        String statement = preserveCreation
                ? sql.conditionNotMet
                : switch (request.mode()) {
            case UPSERT -> sql.upsertEntryPersistent;
            case ONLY_IF_ABSENT -> sql.insertEntryIfAbsentPersistent;
            case ONLY_IF_PRESENT -> sql.updateEntryIfPresentPersistent;
            case ONLY_IF_VERSION_MATCHES -> sql.updateEntryIfVersionPersistent;
        };
        if (request.ttlMode() == EntryTtlMode.PRESERVE_EXISTING
                && request.mode() == SetMode.UPSERT) {
            statement = sql.updateEntryIfPresentPersistent;
        }
        Tuple parameters = preserveCreation
                ? Tuple.tuple()
                : request.mode() == SetMode.ONLY_IF_VERSION_MATCHES
                    ? versionedSetParameters(request, defaultEntryTtl)
                    : setParameters(request, defaultEntryTtl);
        return pool.preparedQuery(statement)
                .execute(parameters)
                .map(rows -> mapSetResult(rows.iterator().next()));
    }

    /** Atomically replaces a live entry's expiry when its exact version matches. */
    public Future<VersionedMutationResult<ManagementEntryMetadata>> expireEntry(
            VersionedEntryTtlRequest request) {
        Objects.requireNonNull(request, "request");
        return pool.preparedQuery(sql.expireEntry)
                .execute(Tuple.of(
                        request.key().namespace(),
                        request.key().key(),
                        request.expectedVersion(),
                        request.ttl().toMillis()))
                .map(rows -> mapVersionedEntryMutation(rows.iterator().next()));
    }

    /** Atomically removes a live entry's expiry when its exact version matches. */
    public Future<VersionedMutationResult<ManagementEntryMetadata>> persistEntry(
            VersionedCacheKeyRequest request) {
        Objects.requireNonNull(request, "request");
        return pool.preparedQuery(sql.persistEntry)
                .execute(Tuple.of(
                        request.key().namespace(),
                        request.key().key(),
                        request.expectedVersion()))
                .map(rows -> mapVersionedEntryMutation(rows.iterator().next()));
    }

    /** Atomically touches a live entry without changing its version. */
    public Future<VersionedMutationResult<ManagementEntryMetadata>> touchEntry(
            VersionedEntryTouchRequest request) {
        Objects.requireNonNull(request, "request");
        Long refreshTtlMillis = request.refreshTtl() == null
                ? null
                : request.refreshTtl().toMillis();
        return pool.preparedQuery(sql.touchEntry)
                .execute(Tuple.of(
                        request.key().namespace(),
                        request.key().key(),
                        request.expectedVersion(),
                        refreshTtlMillis))
                .map(rows -> mapVersionedEntryMutation(rows.iterator().next()));
    }

    /** Atomically deletes a live entry when its exact version matches. */
    public Future<VersionedMutationResult<Void>> deleteEntry(
            VersionedEntryDeleteRequest request) {
        Objects.requireNonNull(request, "request");
        return pool.preparedQuery(sql.deleteEntry)
                .execute(Tuple.of(
                        request.key().namespace(),
                        request.key().key(),
                        request.expectedVersion()))
                .map(rows -> mapVersionedDelete(rows.iterator().next()));
    }

    /** Atomically creates a counter only when no live counter exists. */
    public Future<VersionedMutationResult<CounterEntry>> setCounter(
            ManagementCounterSetRequest request) {
        Objects.requireNonNull(request, "request");
        Long ttlMillis = request.ttl() == null ? null : request.ttl().toMillis();
        String statement = request.requireAbsent()
                ? sql.setCounterIfAbsent
                : sql.setCounterIfVersion;
        Tuple parameters = request.requireAbsent()
                ? Tuple.of(
                    request.key().namespace(),
                    request.key().key(),
                    request.value(),
                    request.ttlMode().name(),
                    ttlMillis)
                : Tuple.of(
                    request.key().namespace(),
                    request.key().key(),
                    request.expectedVersion(),
                    request.value(),
                    request.ttlMode().name(),
                    ttlMillis);
        return pool.preparedQuery(statement)
                .execute(parameters)
                .map(rows -> mapVersionedCounterMutation(rows.iterator().next()));
    }

    /** Atomically adjusts an exact-version counter or creates it under an absence condition. */
    public Future<VersionedMutationResult<CounterEntry>> adjustCounter(
            ManagementCounterAdjustRequest request) {
        Objects.requireNonNull(request, "request");
        Long ttlMillis = request.ttl() == null ? null : request.ttl().toMillis();
        String statement = request.createIfMissing()
                ? sql.setCounterIfAbsent
                : sql.adjustCounterIfVersion;
        Tuple parameters = request.createIfMissing()
                ? Tuple.of(
                    request.key().namespace(),
                    request.key().key(),
                    request.delta(),
                    request.ttlMode().name(),
                    ttlMillis)
                : Tuple.of(
                    request.key().namespace(),
                    request.key().key(),
                    request.expectedVersion(),
                    request.delta(),
                    request.ttlMode().name(),
                    ttlMillis);
        return pool.preparedQuery(statement)
                .execute(parameters)
                .map(rows -> mapVersionedCounterMutation(rows.iterator().next()))
                .recover(failure -> isNumericOverflow(failure)
                        ? Future.failedFuture(new ManagementCounterException(
                                ManagementCounterException.Code.OVERFLOW, failure))
                        : Future.failedFuture(failure));
    }

    /** Atomically replaces a live counter's expiry when its exact version matches. */
    public Future<VersionedMutationResult<CounterEntry>> expireCounter(
            VersionedCounterTtlRequest request) {
        Objects.requireNonNull(request, "request");
        return pool.preparedQuery(sql.expireCounter)
                .execute(Tuple.of(
                        request.key().namespace(),
                        request.key().key(),
                        request.expectedVersion(),
                        request.ttl().toMillis()))
                .map(rows -> mapVersionedCounterMutation(rows.iterator().next()));
    }

    /** Atomically removes a live counter's expiry when its exact version matches. */
    public Future<VersionedMutationResult<CounterEntry>> persistCounter(
            VersionedCacheKeyRequest request) {
        Objects.requireNonNull(request, "request");
        return pool.preparedQuery(sql.persistCounter)
                .execute(Tuple.of(
                        request.key().namespace(),
                        request.key().key(),
                        request.expectedVersion()))
                .map(rows -> mapVersionedCounterMutation(rows.iterator().next()));
    }

    /** Atomically deletes a live counter when its exact version matches. */
    public Future<VersionedMutationResult<Void>> deleteCounter(
            VersionedCounterDeleteRequest request) {
        Objects.requireNonNull(request, "request");
        return pool.preparedQuery(sql.deleteCounter)
                .execute(Tuple.of(
                        request.key().namespace(),
                        request.key().key(),
                        request.expectedVersion()))
                .map(rows -> mapVersionedDelete(rows.iterator().next()));
    }

    /** Returns owner token and version from one active-lock database snapshot. */
    public Future<Optional<RevealedLockOwner>> revealLockOwner(LockKey key) {
        Objects.requireNonNull(key, "key");
        return pool.preparedQuery(sql.revealLockOwner)
                .execute(Tuple.of(key.namespace(), key.key()))
                .map(rows -> {
                    var iterator = rows.iterator();
                    if (!iterator.hasNext()) {
                        return Optional.empty();
                    }
                    Row row = iterator.next();
                    return Optional.of(new RevealedLockOwner(
                            new LockKey(row.getString("namespace"), row.getString("lock_key")),
                            row.getString("owner_token"),
                            row.getLong("version"),
                            row.getOffsetDateTime("revealed_at").toInstant()));
                });
    }

    /** Atomically releases an active lock only when its exact version matches. */
    public Future<VersionedMutationResult<Void>> forceReleaseLock(
            ForceReleaseLockRequest request) {
        Objects.requireNonNull(request, "request");
        return pool.preparedQuery(sql.forceReleaseLock)
                .execute(Tuple.of(
                        request.key().namespace(),
                        request.key().key(),
                        request.expectedVersion()))
                .map(rows -> mapVersionedDelete(rows.iterator().next()));
    }

    private static RevealedEntryValue mapRevealedEntry(Row row) {
        ValueType type = ValueType.valueOf(row.getString("value_type"));
        CacheValue value = type == ValueType.LONG
                ? CacheValue.ofLong(row.getLong("numeric_value"))
                : new CacheValue(type, row.getBuffer("value_bytes"), null);
        return new RevealedEntryValue(
                new CacheKey(row.getString("namespace"), row.getString("cache_key")),
                value,
                row.getLong("version"),
                row.getOffsetDateTime("revealed_at").toInstant());
    }

    private static ManagementSetResult mapSetResult(Row row) {
        ManagementMutationOutcome outcome = ManagementMutationOutcome.valueOf(row.getString("outcome"));
        if (outcome != ManagementMutationOutcome.APPLIED) {
            return ManagementSetResult.notApplied(outcome);
        }
        ManagementEntryMetadata metadata = mapEntryMetadata(row);
        return row.getBoolean("created")
                ? ManagementSetResult.created(metadata.version(), metadata)
                : ManagementSetResult.updated(metadata.version(), metadata);
    }

    private static VersionedMutationResult<ManagementEntryMetadata> mapVersionedEntryMutation(Row row) {
        ManagementMutationOutcome outcome = ManagementMutationOutcome.valueOf(row.getString("outcome"));
        if (outcome == ManagementMutationOutcome.APPLIED) {
            ManagementEntryMetadata metadata = mapEntryMetadata(row);
            return VersionedMutationResult.applied(metadata.version(), metadata);
        }
        return switch (outcome) {
            case NOT_FOUND -> VersionedMutationResult.notFound();
            case VERSION_MISMATCH -> VersionedMutationResult.versionMismatch();
            case CONDITION_NOT_MET -> VersionedMutationResult.conditionNotMet();
            case APPLIED -> throw new IllegalStateException("Applied mutation was already mapped");
        };
    }

    private static VersionedMutationResult<Void> mapVersionedDelete(Row row) {
        ManagementMutationOutcome outcome = ManagementMutationOutcome.valueOf(row.getString("outcome"));
        if (outcome == ManagementMutationOutcome.APPLIED) {
            return VersionedMutationResult.applied(row.getLong("version"), null);
        }
        return switch (outcome) {
            case NOT_FOUND -> VersionedMutationResult.notFound();
            case VERSION_MISMATCH -> VersionedMutationResult.versionMismatch();
            case CONDITION_NOT_MET -> VersionedMutationResult.conditionNotMet();
            case APPLIED -> throw new IllegalStateException("Applied deletion was already mapped");
        };
    }

    private static VersionedMutationResult<CounterEntry> mapVersionedCounterMutation(Row row) {
        ManagementMutationOutcome outcome = ManagementMutationOutcome.valueOf(row.getString("outcome"));
        if (outcome == ManagementMutationOutcome.APPLIED) {
            CounterEntry counter = mapCounter(row);
            return VersionedMutationResult.applied(counter.version(), counter);
        }
        return switch (outcome) {
            case NOT_FOUND -> VersionedMutationResult.notFound();
            case VERSION_MISMATCH -> VersionedMutationResult.versionMismatch();
            case CONDITION_NOT_MET -> VersionedMutationResult.conditionNotMet();
            case APPLIED -> throw new IllegalStateException("Applied counter mutation was already mapped");
        };
    }

    private static CounterEntry mapCounter(Row row) {
        return new CounterEntry(
                new CacheKey(row.getString("namespace"), row.getString("counter_key")),
                row.getLong("counter_value"),
                row.getLong("version"),
                row.getOffsetDateTime("created_at").toInstant(),
                row.getOffsetDateTime("updated_at").toInstant(),
                mapTtl(row));
    }

    private static ManagementEntryMetadata mapEntryMetadata(Row row) {
        var lastAccessedAt = row.getColumnIndex("last_accessed_at") < 0
                ? null
                : row.getOffsetDateTime("last_accessed_at");
        return new ManagementEntryMetadata(
                new CacheKey(row.getString("namespace"), row.getString("cache_key")),
                ValueType.valueOf(row.getString("value_type")),
                row.getLong("size_bytes"),
                row.getLong("version"),
                row.getOffsetDateTime("created_at").toInstant(),
                row.getOffsetDateTime("updated_at").toInstant(),
                lastAccessedAt == null ? null : lastAccessedAt.toInstant(),
                mapTtl(row));
    }

    private static Tuple setParameters(
            ManagementCacheSetRequest request,
            Duration defaultEntryTtl) {
        return Tuple.of(
                request.key().namespace(),
                request.key().key(),
                request.value().type().name(),
                valueBytes(request.value()),
                numericValue(request.value()),
                request.ttlMode().name(),
                effectiveTtlMillis(request, defaultEntryTtl));
    }

    private static Tuple versionedSetParameters(
            ManagementCacheSetRequest request,
            Duration defaultEntryTtl) {
        return Tuple.of(
                request.key().namespace(),
                request.key().key(),
                request.expectedVersion(),
                request.value().type().name(),
                valueBytes(request.value()),
                numericValue(request.value()),
                request.ttlMode().name(),
                effectiveTtlMillis(request, defaultEntryTtl));
    }

    private static Buffer valueBytes(CacheValue value) {
        return value.type() == ValueType.LONG ? null : value.binaryValue();
    }

    private static Long numericValue(CacheValue value) {
        return value.type() == ValueType.LONG ? value.longValue() : null;
    }

    private static Long effectiveTtlMillis(
            ManagementCacheSetRequest request,
            Duration defaultEntryTtl) {
        return switch (request.ttlMode()) {
            case PRESERVE_EXISTING, REMOVE -> null;
            case USE_DEFAULT -> defaultEntryTtl == null ? null : defaultEntryTtl.toMillis();
            case REPLACE -> request.ttl().toMillis();
        };
    }

    private static ManagementTtl mapTtl(Row row) {
        var expiresAt = row.getOffsetDateTime("expires_at");
        if (expiresAt == null) {
            return ManagementTtl.persistent();
        }
        long ttlMillis = row.getLong("ttl_millis");
        return ttlMillis == 0
                ? ManagementTtl.expired(expiresAt.toInstant())
                : ManagementTtl.expiring(ttlMillis, expiresAt.toInstant());
    }

    private static boolean isNumericOverflow(Throwable failure) {
        return failure instanceof PgException postgres
                && "22003".equals(postgres.getSqlState());
    }
}
