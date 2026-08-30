package dev.mars.peegeeq.cache.pg.management;

import dev.mars.peegeeq.cache.api.management.ManagementTtl;
import dev.mars.peegeeq.cache.api.management.ManagementCursorPosition;
import dev.mars.peegeeq.cache.api.management.ManagementEntryMetadata;
import dev.mars.peegeeq.cache.api.management.ManagementTtlFilter;
import dev.mars.peegeeq.cache.api.management.ManagementTtlBucket;
import dev.mars.peegeeq.cache.api.management.CounterEntry;
import dev.mars.peegeeq.cache.api.management.CounterQuery;
import dev.mars.peegeeq.cache.api.management.AvailableValue;
import dev.mars.peegeeq.cache.api.management.DatabaseStats;
import dev.mars.peegeeq.cache.api.management.EntryQuery;
import dev.mars.peegeeq.cache.api.management.ExpiryStats;
import dev.mars.peegeeq.cache.api.management.LockQuery;
import dev.mars.peegeeq.cache.api.management.ManagementDatabaseMonitoring;
import dev.mars.peegeeq.cache.api.management.ManagementLockMetadata;
import dev.mars.peegeeq.cache.api.management.ManagementOverview;
import dev.mars.peegeeq.cache.api.management.ManagementReadinessException;
import dev.mars.peegeeq.cache.api.management.NamespaceDetails;
import dev.mars.peegeeq.cache.api.management.NamespaceQuery;
import dev.mars.peegeeq.cache.api.management.NamespaceStats;
import dev.mars.peegeeq.cache.api.model.ValueType;
import dev.mars.peegeeq.cache.api.model.CacheKey;
import dev.mars.peegeeq.cache.api.model.LockKey;
import io.vertx.core.Future;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.Tuple;
import io.vertx.pgclient.PgException;

import java.time.OffsetDateTime;
import java.time.Instant;
import java.util.EnumMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Authoritative PostgreSQL metadata reads for the management service. */
public final class PgManagementReadRepository {

    private final Pool pool;
    private final PgManagementReadSql sql;
    private final String applicationName;

    public PgManagementReadRepository(Pool pool, String schemaName) {
        this(pool, schemaName, "");
    }

    public PgManagementReadRepository(Pool pool, String schemaName, String applicationName) {
        this.pool = Objects.requireNonNull(pool, "pool");
        this.sql = new PgManagementReadSql(schemaName);
        this.applicationName = Objects.requireNonNull(applicationName, "applicationName");
    }

    /** Returns a zero-valued model for a logical namespace with no rows. */
    public Future<NamespaceStats> namespaceStats(String namespace) {
        Objects.requireNonNull(namespace, "namespace");
        return withReadinessFailure(pool.preparedQuery(sql.namespaceStats)
                .execute(Tuple.of(namespace))
                .map(rows -> mapNamespace(rows.iterator().next())));
    }

    /** Returns aggregate counts and complete entry distributions for one logical namespace. */
    public Future<NamespaceDetails> namespaceDetails(String namespace) {
        Objects.requireNonNull(namespace, "namespace");
        return namespaceStats(namespace)
                .compose(stats -> withReadinessFailure(pool.preparedQuery(sql.namespaceDistributions)
                        .execute(Tuple.of(namespace))
                        .map(rows -> {
                            EnumMap<ValueType, Long> valueTypes = new EnumMap<>(ValueType.class);
                            EnumMap<ManagementTtl.State, Long> ttlStates =
                                    new EnumMap<>(ManagementTtl.State.class);
                            EnumMap<ManagementTtlBucket, Long> ttlBuckets =
                                    new EnumMap<>(ManagementTtlBucket.class);
                            for (Row row : rows) {
                                long count = row.getLong("item_count");
                                valueTypes.merge(ValueType.valueOf(row.getString("value_type")), count, Long::sum);
                                ttlStates.merge(
                                        ManagementTtl.State.valueOf(row.getString("ttl_state")), count, Long::sum);
                                ttlBuckets.merge(
                                        ManagementTtlBucket.valueOf(row.getString("ttl_bucket")), count, Long::sum);
                            }
                            return new NamespaceDetails(stats, valueTypes, ttlStates, ttlBuckets);
                        })));
    }

    /** Returns at most {@code limit + 1} rows so the service can construct a keyset page. */
    public Future<List<NamespaceStats>> namespaces(
            NamespaceQuery query,
            ManagementCursorPosition position) {
        Objects.requireNonNull(query, "query");
        String cursorNamespace = position == null ? null : position.identifier();
        Long cursorEntryCount = position == null ? null : position.entryCount();
        String statement = query.sort() == NamespaceQuery.Sort.NAMESPACE_ASC
                ? sql.namespacesAscending
                : sql.namespacesByEntryCount;
        Tuple parameters = Tuple.tuple()
                .addString(literalPrefix(query.prefix()))
                .addString(query.status() == null ? null : query.status().name())
                .addString(cursorNamespace)
                .addLong(cursorEntryCount)
                .addInteger(query.limit() + 1);
        return withReadinessFailure(pool.preparedQuery(statement).execute(parameters).map(rows -> {
            List<NamespaceStats> result = new ArrayList<>();
            rows.forEach(row -> result.add(mapNamespace(row)));
            return List.copyOf(result);
        }));
    }

    /** Returns at most {@code limit + 1} metadata-only entry rows. */
    public Future<List<ManagementEntryMetadata>> entries(
            EntryQuery query,
            ManagementCursorPosition position) {
        Objects.requireNonNull(query, "query");
        String cursorKey = position == null ? null : position.identifier();
        ManagementTtlFilter ttlFilter = query.ttlState() == null
                ? ManagementTtlFilter.ALL_LIVE
                : query.ttlState();
        Tuple parameters = Tuple.tuple()
                .addString(query.namespace())
                .addString(literalPrefix(query.prefix()))
                .addString(query.valueType() == null ? null : query.valueType().name())
                .addString(ttlFilter.name())
                .addString(cursorKey)
                .addInteger(query.limit() + 1);
        return withReadinessFailure(pool.preparedQuery(sql.entries).execute(parameters).map(rows -> {
            List<ManagementEntryMetadata> result = new ArrayList<>();
            rows.forEach(row -> result.add(mapEntry(row)));
            return List.copyOf(result);
        }));
    }

    /** Returns metadata only; the SQL projection never selects either value column. */
    public Future<Optional<ManagementEntryMetadata>> entry(CacheKey key, boolean includeExpired) {
        Objects.requireNonNull(key, "key");
        return withReadinessFailure(pool.preparedQuery(sql.entry)
                .execute(Tuple.of(key.namespace(), key.key(), includeExpired))
                .map(rows -> {
                    var iterator = rows.iterator();
                    return iterator.hasNext()
                            ? Optional.of(mapEntry(iterator.next()))
                            : Optional.empty();
                }));
    }

    /** Returns at most {@code limit + 1} counter rows in global qualified-key order. */
    public Future<List<CounterEntry>> counters(
            CounterQuery query,
            String cursorNamespace,
            String cursorKey) {
        Objects.requireNonNull(query, "query");
        ManagementTtlFilter ttlFilter = query.ttlState() == null
                ? ManagementTtlFilter.ALL_LIVE
                : query.ttlState();
        Tuple parameters = Tuple.tuple()
                .addString(query.namespace())
                .addString(literalPrefix(query.prefix()))
                .addString(ttlFilter.name())
                .addString(cursorNamespace)
                .addString(cursorKey)
                .addInteger(query.limit() + 1);
        return withReadinessFailure(pool.preparedQuery(sql.counters).execute(parameters).map(rows -> {
            List<CounterEntry> result = new ArrayList<>();
            rows.forEach(row -> result.add(mapCounter(row)));
            return List.copyOf(result);
        }));
    }

    /** Returns one live counter, if present. */
    public Future<Optional<CounterEntry>> counter(CacheKey key) {
        Objects.requireNonNull(key, "key");
        return withReadinessFailure(pool.preparedQuery(sql.counter)
                .execute(Tuple.of(key.namespace(), key.key()))
                .map(rows -> {
                    var iterator = rows.iterator();
                    return iterator.hasNext()
                            ? Optional.of(mapCounter(iterator.next()))
                            : Optional.empty();
                }));
    }

    /** Returns at most {@code limit + 1} active lock rows without owner tokens. */
    public Future<List<ManagementLockMetadata>> locks(
            LockQuery query,
            String cursorNamespace,
            String cursorKey) {
        Objects.requireNonNull(query, "query");
        LockQuery.LeaseState leaseState = query.leaseState() == null
                ? LockQuery.LeaseState.ACTIVE
                : query.leaseState();
        Tuple parameters = Tuple.tuple()
                .addString(query.namespace())
                .addString(literalPrefix(query.prefix()))
                .addString(leaseState.name())
                .addString(cursorNamespace)
                .addString(cursorKey)
                .addInteger(query.limit() + 1);
        return withReadinessFailure(pool.preparedQuery(sql.locks).execute(parameters).map(rows -> {
            List<ManagementLockMetadata> result = new ArrayList<>();
            rows.forEach(row -> result.add(mapLock(row)));
            return List.copyOf(result);
        }));
    }

    /** Returns one active lock without selecting its owner token. */
    public Future<Optional<ManagementLockMetadata>> lock(LockKey key) {
        Objects.requireNonNull(key, "key");
        return withReadinessFailure(pool.preparedQuery(sql.lock)
                .execute(Tuple.of(key.namespace(), key.key()))
                .map(rows -> {
                    var iterator = rows.iterator();
                    return iterator.hasNext()
                            ? Optional.of(mapLock(iterator.next()))
                            : Optional.empty();
                }));
    }

    /** Returns permission-aware physical size observations. */
    public Future<DatabaseStats> databaseStats() {
        return pool.preparedQuery(sql.databaseStats)
                .execute(Tuple.of(sql.schemaName))
                .transform(outcome -> {
                    if (outcome.failed()) {
                        String reason = "PostgreSQL size statistics are unavailable to the current role";
                        return Future.succeededFuture(new DatabaseStats(
                                Instant.now(),
                                AvailableValue.unavailable(reason),
                                AvailableValue.unavailable(reason)));
                    }
                    Row row = outcome.result().iterator().next();
                    if (!row.getBoolean("schema_ready")) {
                        return Future.failedFuture(new ManagementReadinessException(
                                ManagementReadinessException.Code.SCHEMA_UNAVAILABLE, null));
                    }
                    return Future.succeededFuture(new DatabaseStats(
                            row.getOffsetDateTime("observed_at").toInstant(),
                            AvailableValue.available(row.getLong("database_bytes")),
                            AvailableValue.available(row.getLong("schema_bytes"))));
                });
    }

    /** Returns authoritative expired-row counts and the oldest backlog age. */
    public Future<ExpiryStats> expiryStats() {
        return withReadinessFailure(pool.query(sql.expiryStats).execute().map(rows -> {
            Row row = rows.iterator().next();
            return new ExpiryStats(
                    row.getOffsetDateTime("observed_at").toInstant(),
                    row.getLong("expired_entry_count"),
                    row.getLong("expired_counter_count"),
                    AvailableValue.available(row.getLong("oldest_lag_millis")));
        }));
    }

    /** Returns accurate database-wide totals plus a separately bounded top-namespace view. */
    public Future<ManagementOverview> overview() {
        return withReadinessFailure(pool.query(sql.overview).execute().map(rows ->
                        rows.iterator().next()))
                .compose(row -> databaseStats().compose(database -> namespaces(
                                new NamespaceQuery(
                                        null,
                                        NamespaceQuery.Status.ALL,
                                        NamespaceQuery.Sort.ENTRY_COUNT_DESC,
                                        null,
                                        20),
                                null)
                        .map(topNamespaces -> {
                            EnumMap<ValueType, Long> valueTypes = new EnumMap<>(ValueType.class);
                            valueTypes.put(ValueType.STRING, row.getLong("string_count"));
                            valueTypes.put(ValueType.JSON, row.getLong("json_count"));
                            valueTypes.put(ValueType.LONG, row.getLong("long_count"));
                            valueTypes.put(ValueType.BYTES, row.getLong("bytes_count"));
                            return new ManagementOverview(
                                    row.getOffsetDateTime("observed_at").toInstant(),
                                    row.getLong("namespace_count"),
                                    row.getLong("live_entry_count"),
                                    row.getLong("live_counter_count"),
                                    row.getLong("active_lock_count"),
                                    row.getLong("expired_entry_count"),
                                    row.getLong("expired_counter_count"),
                                    database.schemaBytes(),
                                    AvailableValue.available(row.getLong("oldest_lag_millis")),
                                    valueTypes,
                                    topNamespaces);
                        })));
    }

    /** Returns exact cache-row counts plus permission-aware physical and activity statistics. */
    public Future<ManagementDatabaseMonitoring> databaseMonitoring() {
        return withReadinessFailure(pool.query(sql.databaseMonitoringCore).execute().map(rows ->
                        rows.iterator().next()))
                .compose(core -> physicalMonitoring().compose(physical -> activityMonitoring()
                        .map(activity -> new ManagementDatabaseMonitoring(
                                core.getOffsetDateTime("observed_at").toInstant(),
                                physical.tableBytes(),
                                physical.indexBytes(),
                                physical.schemaBytes(),
                                AvailableValue.available(core.getLong("live_rows")),
                                AvailableValue.available(core.getLong("expired_rows")),
                                activity.deadTuples(),
                                activity.lastVacuumAt(),
                                activity.lastAutovacuumAt(),
                                activity.databaseConnections(),
                                activity.cacheConnections(),
                                core.getLong("expiry_backlog"),
                                core.getLong("oldest_expired_row_lag_millis")))));
    }

    private Future<PhysicalMonitoring> physicalMonitoring() {
        String reason = "PostgreSQL physical size statistics are unavailable to the current role";
        return pool.preparedQuery(sql.databaseMonitoringPhysical)
                .execute(Tuple.of(sql.schemaName))
                .transform(outcome -> {
                    if (outcome.failed()) {
                        AvailableValue<Long> unavailable = AvailableValue.unavailable(reason);
                        return Future.succeededFuture(new PhysicalMonitoring(
                                unavailable, unavailable, unavailable));
                    }
                    Row row = outcome.result().iterator().next();
                    return Future.succeededFuture(new PhysicalMonitoring(
                            AvailableValue.available(row.getLong("table_bytes")),
                            AvailableValue.available(row.getLong("index_bytes")),
                            AvailableValue.available(row.getLong("schema_bytes"))));
                });
    }

    private Future<ActivityMonitoring> activityMonitoring() {
        String reason = "PostgreSQL activity statistics are unavailable to the current role";
        return pool.preparedQuery(sql.databaseMonitoringActivity)
                .execute(Tuple.of(sql.schemaName, applicationName))
                .transform(outcome -> {
                    if (outcome.failed()) {
                        AvailableValue<Long> unavailable = AvailableValue.unavailable(reason);
                        return Future.succeededFuture(new ActivityMonitoring(
                                unavailable, null, null, unavailable, unavailable));
                    }
                    Row row = outcome.result().iterator().next();
                    OffsetDateTime lastVacuumAt = row.getOffsetDateTime("last_vacuum_at");
                    OffsetDateTime lastAutovacuumAt = row.getOffsetDateTime("last_autovacuum_at");
                    return Future.succeededFuture(new ActivityMonitoring(
                            AvailableValue.available(row.getLong("dead_tuples")),
                            lastVacuumAt == null ? null : lastVacuumAt.toInstant(),
                            lastAutovacuumAt == null ? null : lastAutovacuumAt.toInstant(),
                            AvailableValue.available(row.getLong("database_connections")),
                            AvailableValue.available(row.getLong("cache_connections"))));
                });
    }

    private record PhysicalMonitoring(
            AvailableValue<Long> tableBytes,
            AvailableValue<Long> indexBytes,
            AvailableValue<Long> schemaBytes) {
    }

    private record ActivityMonitoring(
            AvailableValue<Long> deadTuples,
            Instant lastVacuumAt,
            Instant lastAutovacuumAt,
            AvailableValue<Long> databaseConnections,
            AvailableValue<Long> cacheConnections) {
    }

    private static <T> Future<T> withReadinessFailure(Future<T> operation) {
        return operation.transform(outcome -> {
            if (outcome.succeeded()) {
                return Future.succeededFuture(outcome.result());
            }
            Throwable cause = outcome.cause();
            if (cause instanceof PgException postgres
                    && ("42P01".equals(postgres.getSqlState())
                    || "3F000".equals(postgres.getSqlState())
                    || "42703".equals(postgres.getSqlState()))) {
                return Future.failedFuture(new ManagementReadinessException(
                        ManagementReadinessException.Code.SCHEMA_UNAVAILABLE, cause));
            }
            return Future.failedFuture(cause);
        });
    }

    private static String literalPrefix(String prefix) {
        if (prefix == null) {
            return null;
        }
        return prefix.replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_") + "%";
    }

    private static NamespaceStats mapNamespace(Row row) {
        OffsetDateTime observedAt = row.getOffsetDateTime("observed_at");
        return new NamespaceStats(
                row.getString("namespace"),
                row.getLong("live_entry_count"),
                row.getLong("live_counter_count"),
                row.getLong("active_lock_count"),
                row.getLong("expiring_entry_count"),
                row.getLong("expired_entry_count"),
                row.getLong("estimated_storage_bytes"),
                observedAt.toInstant());
    }

    private static ManagementEntryMetadata mapEntry(Row row) {
        OffsetDateTime createdAt = row.getOffsetDateTime("created_at");
        OffsetDateTime updatedAt = row.getOffsetDateTime("updated_at");
        OffsetDateTime lastAccessedAt = row.getOffsetDateTime("last_accessed_at");
        ManagementTtl ttl = mapTtl(row);
        return new ManagementEntryMetadata(
                new CacheKey(row.getString("namespace"), row.getString("cache_key")),
                ValueType.valueOf(row.getString("value_type")),
                row.getLong("size_bytes"),
                row.getLong("version"),
                createdAt.toInstant(),
                updatedAt.toInstant(),
                lastAccessedAt == null ? null : lastAccessedAt.toInstant(),
                ttl);
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

    private static ManagementLockMetadata mapLock(Row row) {
        return new ManagementLockMetadata(
                new LockKey(row.getString("namespace"), row.getString("lock_key")),
                row.getLong("fencing_token"),
                row.getLong("version"),
                row.getOffsetDateTime("created_at").toInstant(),
                row.getOffsetDateTime("updated_at").toInstant(),
                row.getOffsetDateTime("lease_expires_at").toInstant(),
                row.getLong("lease_remaining_millis"));
    }

    private static ManagementTtl mapTtl(Row row) {
        OffsetDateTime expiresAt = row.getOffsetDateTime("expires_at");
        Long ttlMillis = row.getLong("ttl_millis");
        if (expiresAt == null) {
            return ManagementTtl.persistent();
        }
        if (ttlMillis == 0) {
            return ManagementTtl.expired(expiresAt.toInstant());
        }
        return ManagementTtl.expiring(ttlMillis, expiresAt.toInstant());
    }
}
