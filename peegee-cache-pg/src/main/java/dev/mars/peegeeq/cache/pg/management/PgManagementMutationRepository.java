package dev.mars.peegeeq.cache.pg.management;

import dev.mars.peegeeq.cache.api.management.RevealedEntryValue;
import dev.mars.peegeeq.cache.api.management.EntryTtlMode;
import dev.mars.peegeeq.cache.api.management.ManagementCacheSetRequest;
import dev.mars.peegeeq.cache.api.management.ManagementEntryMetadata;
import dev.mars.peegeeq.cache.api.management.ManagementMutationOutcome;
import dev.mars.peegeeq.cache.api.management.ManagementSetResult;
import dev.mars.peegeeq.cache.api.management.ManagementTtl;
import dev.mars.peegeeq.cache.api.model.CacheKey;
import dev.mars.peegeeq.cache.api.model.CacheValue;
import dev.mars.peegeeq.cache.api.model.SetMode;
import dev.mars.peegeeq.cache.api.model.ValueType;
import io.vertx.core.Future;
import io.vertx.core.buffer.Buffer;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.Tuple;

import java.util.Objects;
import java.util.Optional;
import java.time.Duration;

/** Authoritative PostgreSQL reveals and atomic management mutations. */
public final class PgManagementMutationRepository {

    private final Pool pool;
    private final PgManagementMutationSql sql;

    public PgManagementMutationRepository(Pool pool, String schemaName) {
        this.pool = Objects.requireNonNull(pool, "pool");
        this.sql = new PgManagementMutationSql(schemaName);
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
        ManagementEntryMetadata metadata = new ManagementEntryMetadata(
                new CacheKey(row.getString("namespace"), row.getString("cache_key")),
                ValueType.valueOf(row.getString("value_type")),
                row.getLong("size_bytes"),
                row.getLong("version"),
                row.getOffsetDateTime("created_at").toInstant(),
                row.getOffsetDateTime("updated_at").toInstant(),
                mapTtl(row));
        return row.getBoolean("created")
                ? ManagementSetResult.created(metadata.version(), metadata)
                : ManagementSetResult.updated(metadata.version(), metadata);
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
}
