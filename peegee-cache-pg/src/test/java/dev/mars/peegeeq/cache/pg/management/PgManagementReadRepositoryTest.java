package dev.mars.peegeeq.cache.pg.management;

import dev.mars.peegeeq.cache.api.management.ManagementTtl;
import dev.mars.peegeeq.cache.api.management.ManagementCursorCodec;
import dev.mars.peegeeq.cache.api.management.ManagementCursorException;
import dev.mars.peegeeq.cache.api.management.ManagementNotFoundException;
import dev.mars.peegeeq.cache.api.management.ManagementTtlFilter;
import dev.mars.peegeeq.cache.api.management.NamespaceQuery;
import dev.mars.peegeeq.cache.api.management.EntryQuery;
import dev.mars.peegeeq.cache.api.management.CounterQuery;
import dev.mars.peegeeq.cache.api.management.LockQuery;
import dev.mars.peegeeq.cache.api.management.Availability;
import dev.mars.peegeeq.cache.api.management.ManagementReadinessException;
import dev.mars.peegeeq.cache.api.model.CacheKey;
import dev.mars.peegeeq.cache.api.model.LockKey;
import dev.mars.peegeeq.cache.api.model.ValueType;
import dev.mars.peegeeq.cache.test.PgTestSupport;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.PoolOptions;
import io.vertx.sqlclient.Tuple;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(VertxExtension.class)
@io.vertx.junit5.Timeout(value = 90, timeUnit = TimeUnit.SECONDS)
class PgManagementReadRepositoryTest {

    private static final String SCHEMA = "management_read";
    private static final PgTestSupport POSTGRES = new PgTestSupport("management-read", SCHEMA);
    private static Pool pool;
    private static PgManagementReadRepository repository;
    private static PgManagementService service;

    @BeforeAll
    static void start(Vertx vertx, VertxTestContext context) {
        POSTGRES.start(vertx)
                .onSuccess(ignored -> context.verify(() -> {
                    pool = POSTGRES.createPool(vertx);
                    repository = new PgManagementReadRepository(pool, SCHEMA);
                    service = new PgManagementService(
                            repository,
                            "setup-a",
                            new ManagementCursorCodec(
                                    "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8),
                                    Clock.systemUTC(),
                                    Duration.ofMinutes(15)));
                    context.completeNow();
                }))
                .onFailure(context::failNow);
    }

    @AfterAll
    static void stop(Vertx vertx, VertxTestContext context) {
        (pool == null ? POSTGRES.stop(vertx) : POSTGRES.stopAfter(vertx, pool.close()))
                .onSuccess(ignored -> context.completeNow())
                .onFailure(context::failNow);
    }

    @BeforeEach
    void reset(VertxTestContext context) {
        POSTGRES.resetDatabaseState(pool)
                .onSuccess(ignored -> context.completeNow())
                .onFailure(context::failNow);
    }

    @Test
    void emptyLogicalNamespaceReturnsZeroCounts(VertxTestContext context) {
        repository.namespaceStats("logical-empty")
                .onSuccess(stats -> context.verify(() -> {
                    assertEquals("logical-empty", stats.namespace());
                    assertEquals(0, stats.liveEntryCount());
                    assertEquals(0, stats.expiredEntryCount());
                    assertEquals(0, stats.liveCounterCount());
                    assertEquals(0, stats.activeLockCount());
                    context.completeNow();
                }))
                .onFailure(context::failNow);
    }

    @Test
    void namespaceCountsAndDistributionsMatchIndependentSql(VertxTestContext context) {
        seedMixedNamespace("mixed")
                .compose(ignored -> repository.namespaceDetails("mixed"))
                .compose(details -> independentCounts("mixed").map(counts -> new Object[]{details, counts}))
                .onSuccess(result -> context.verify(() -> {
                    var details = (dev.mars.peegeeq.cache.api.management.NamespaceDetails) result[0];
                    var counts = (io.vertx.sqlclient.Row) result[1];
                    assertEquals(counts.getLong("live_entries"), details.stats().liveEntryCount());
                    assertEquals(counts.getLong("expired_entries"), details.stats().expiredEntryCount());
                    assertEquals(counts.getLong("live_counters"), details.stats().liveCounterCount());
                    assertEquals(counts.getLong("active_locks"), details.stats().activeLockCount());
                    assertEquals(1L, details.valueTypeCounts().get(ValueType.STRING));
                    assertEquals(1L, details.valueTypeCounts().get(ValueType.LONG));
                    assertEquals(1L, details.valueTypeCounts().get(ValueType.JSON));
                    assertEquals(1L, details.ttlStateCounts().get(ManagementTtl.State.PERSISTENT));
                    assertEquals(1L, details.ttlStateCounts().get(ManagementTtl.State.EXPIRING));
                    assertEquals(1L, details.ttlStateCounts().get(ManagementTtl.State.EXPIRED));
                    assertTrue(details.stats().estimatedStorageBytes() > 0);
                    context.completeNow();
                }))
                .onFailure(context::failNow);
    }

    @Test
    void namespacePagesUseLiteralPrefixesStableSortsAndScopedCursors(VertxTestContext context) {
        seedNamespaceCounts()
                .compose(ignored -> service.namespaces(new NamespaceQuery(
                        "literal%_\\", null, NamespaceQuery.Sort.NAMESPACE_ASC, null, 1)))
                .compose(first -> {
                    assertEquals(1, first.items().size());
                    assertEquals("literal%_\\a", first.items().getFirst().namespace());
                    assertTrue(first.hasMore());
                    return service.namespaces(new NamespaceQuery(
                                    "literal%_\\", null, NamespaceQuery.Sort.NAMESPACE_ASC,
                                    first.nextCursor(), 1))
                            .map(second -> new Object[]{first, second});
                })
                .compose(pages -> {
                    var second = (dev.mars.peegeeq.cache.api.management.AdminPage<?>) pages[1];
                    assertEquals("literal%_\\b",
                            ((dev.mars.peegeeq.cache.api.management.NamespaceStats)
                                    second.items().getFirst()).namespace());
                    return service.namespaces(new NamespaceQuery(
                            "changed", null, NamespaceQuery.Sort.NAMESPACE_ASC,
                            ((dev.mars.peegeeq.cache.api.management.AdminPage<?>) pages[0]).nextCursor(), 1));
                })
                .onSuccess(ignored -> context.failNow("A cursor from another prefix scope must be rejected"))
                .onFailure(failure -> context.verify(() -> {
                    ManagementCursorException cursorFailure =
                            assertInstanceOf(ManagementCursorException.class, failure);
                    assertEquals(ManagementCursorException.Code.SCOPE_MISMATCH, cursorFailure.code());
                    context.completeNow();
                }));
    }

    @Test
    void entryCountSortUsesNamespaceAsItsDeterministicTieBreaker(VertxTestContext context) {
        seedNamespaceCounts()
                .compose(ignored -> service.namespaces(new NamespaceQuery(
                        null, null, NamespaceQuery.Sort.ENTRY_COUNT_DESC, null, 2)))
                .compose(first -> service.namespaces(new NamespaceQuery(
                                null, null, NamespaceQuery.Sort.ENTRY_COUNT_DESC, first.nextCursor(), 2))
                        .map(second -> new Object[]{first, second}))
                .onSuccess(pages -> context.verify(() -> {
                    @SuppressWarnings("unchecked")
                    var first = (dev.mars.peegeeq.cache.api.management.AdminPage<
                            dev.mars.peegeeq.cache.api.management.NamespaceStats>) pages[0];
                    @SuppressWarnings("unchecked")
                    var second = (dev.mars.peegeeq.cache.api.management.AdminPage<
                            dev.mars.peegeeq.cache.api.management.NamespaceStats>) pages[1];
                    assertEquals(java.util.List.of("large", "literal%_\\a"),
                            first.items().stream().map(item -> item.namespace()).toList());
                    assertEquals(java.util.List.of("literal%_\\b", "small"),
                            second.items().stream().map(item -> item.namespace()).toList());
                    context.completeNow();
                }))
                .onFailure(context::failNow);
    }

    @Test
    void entryMetadataNeverCarriesValuesAndExpiredVisibilityIsExplicit(VertxTestContext context) {
        seedEntryMetadata()
                .compose(ignored -> service.entry(new CacheKey("entries", "live-secret"), false))
                .compose(live -> {
                    assertEquals(ValueType.STRING, live.valueType());
                            assertEquals(17, live.sizeBytes());
                    assertEquals(ManagementTtl.State.PERSISTENT, live.ttl().state());
                    assertFalse(java.util.Arrays.stream(live.getClass().getRecordComponents())
                            .anyMatch(component -> component.getName().equalsIgnoreCase("value")));
                    return service.entry(new CacheKey("entries", "expired"), false);
                })
                .transform(hidden -> {
                    if (hidden.succeeded()) {
                        return Future.failedFuture("Expired metadata was visible without includeExpired");
                    }
                    ManagementNotFoundException notFound =
                            assertInstanceOf(ManagementNotFoundException.class, hidden.cause());
                    assertEquals(ManagementNotFoundException.Resource.ENTRY, notFound.resource());
                    return service.entry(new CacheKey("entries", "expired"), true);
                })
                .onSuccess(expired -> context.verify(() -> {
                    assertEquals(ManagementTtl.State.EXPIRED, expired.ttl().state());
                    assertEquals(0L, expired.ttl().ttlMillis());
                    context.completeNow();
                }))
                .onFailure(context::failNow);
    }

    @Test
    void entryPagesRoundTripMultibyteKeysAndUseLiteralPrefixes(VertxTestContext context) {
        seedEntryMetadata()
                .compose(ignored -> service.entries(new EntryQuery(
                        "entries", "literal%_\\", null, ManagementTtlFilter.INCLUDE_EXPIRED,
                        EntryQuery.Sort.KEY_ASC, null, 1)))
                .compose(first -> {
                    assertEquals("literal%_\\一", first.items().getFirst().key().key());
                    assertTrue(first.hasMore());
                    return service.entries(new EntryQuery(
                            "entries", "literal%_\\", null, ManagementTtlFilter.INCLUDE_EXPIRED,
                            EntryQuery.Sort.KEY_ASC, first.nextCursor(), 1));
                })
                .onSuccess(second -> context.verify(() -> {
                    assertEquals("literal%_\\二", second.items().getFirst().key().key());
                    assertFalse(second.hasMore());
                    context.completeNow();
                }))
                .onFailure(context::failNow);
    }

    @Test
    void counterPagesPreserveLongPrecisionAndUseCompositeKeysetCursors(VertxTestContext context) {
        seedCounterAndLockMetadata()
                .compose(ignored -> service.counters(new CounterQuery(
                        null, "literal%_\\", ManagementTtlFilter.INCLUDE_EXPIRED,
                        CounterQuery.Sort.KEY_ASC, null, 1)))
                .compose(first -> {
                    assertEquals("alpha", first.items().getFirst().key().namespace());
                    assertEquals(Long.MAX_VALUE - 1, first.items().getFirst().value());
                    assertTrue(first.hasMore());
                    return service.counters(new CounterQuery(
                            null, "literal%_\\", ManagementTtlFilter.INCLUDE_EXPIRED,
                            CounterQuery.Sort.KEY_ASC, first.nextCursor(), 1));
                })
                .compose(second -> {
                    assertEquals("beta", second.items().getFirst().key().namespace());
                    assertEquals("literal%_\\二", second.items().getFirst().key().key());
                    return service.counter(new CacheKey("alpha", "expired"));
                })
                .onSuccess(ignored -> context.failNow("Expired counter detail must be hidden"))
                .onFailure(failure -> context.verify(() -> {
                    ManagementNotFoundException notFound =
                            assertInstanceOf(ManagementNotFoundException.class, failure);
                    assertEquals(ManagementNotFoundException.Resource.COUNTER, notFound.resource());
                    context.completeNow();
                }));
    }

    @Test
    void lockMetadataExcludesOwnersAndExpiredLeases(VertxTestContext context) {
        seedCounterAndLockMetadata()
                .compose(ignored -> service.locks(new LockQuery(
                        null, "literal%_\\", LockQuery.LeaseState.EXPIRING_SOON, null, 10)))
                .compose(page -> {
                    assertEquals(1, page.items().size());
                    var lock = page.items().getFirst();
                    assertEquals("alpha", lock.key().namespace());
                    assertEquals(Long.MAX_VALUE - 2, lock.fencingToken());
                    assertTrue(lock.leaseRemainingMillis() > 0);
                    assertFalse(java.util.Arrays.stream(lock.getClass().getRecordComponents())
                            .anyMatch(component -> component.getName().toLowerCase().contains("owner")));
                    return service.lock(new LockKey("alpha", "expired"));
                })
                .onSuccess(ignored -> context.failNow("Expired lock detail must be hidden"))
                .onFailure(failure -> context.verify(() -> {
                    ManagementNotFoundException notFound =
                            assertInstanceOf(ManagementNotFoundException.class, failure);
                    assertEquals(ManagementNotFoundException.Resource.LOCK, notFound.resource());
                    context.completeNow();
                }));
    }

    @Test
    void operationalStatsReportAuthoritativeSizesAndExpiryBacklog(VertxTestContext context) {
        seedMixedNamespace("stats")
                .compose(ignored -> service.databaseStats())
                .compose(database -> {
                    assertEquals(Availability.AVAILABLE, database.databaseBytes().availability());
                    assertEquals(Availability.AVAILABLE, database.schemaBytes().availability());
                    assertTrue(database.databaseBytes().value() > 0);
                    assertTrue(database.schemaBytes().value() > 0);
                    return service.expiryStats();
                })
                .onSuccess(expiry -> context.verify(() -> {
                    assertEquals(1, expiry.expiredEntryCount());
                    assertEquals(1, expiry.expiredCounterCount());
                    assertEquals(Availability.AVAILABLE, expiry.oldestLagMillis().availability());
                    assertTrue(expiry.oldestLagMillis().value() >= Duration.ofMinutes(59).toMillis());
                    context.completeNow();
                }))
                .onFailure(context::failNow);
    }

    @Test
    void unavailablePrivilegedSizesAreNeverReportedAsZero(
            Vertx vertx,
            VertxTestContext context) {
        String role = "management_stats_reader";
        String password = "management-stats-password";
        pool.query("""
                        CREATE ROLE management_stats_reader LOGIN PASSWORD 'management-stats-password';
                        GRANT CONNECT ON DATABASE testdb TO management_stats_reader;
                        GRANT USAGE ON SCHEMA management_read TO management_stats_reader;
                        GRANT SELECT ON ALL TABLES IN SCHEMA management_read TO management_stats_reader;
                        REVOKE EXECUTE ON FUNCTION pg_database_size(name) FROM PUBLIC
                        """).execute()
                .compose(ignored -> {
                    var options = POSTGRES.connectOptions().setUser(role).setPassword(password);
                    Pool restricted = Pool.pool(vertx, options, new PoolOptions().setMaxSize(1));
                    return new PgManagementReadRepository(restricted, SCHEMA).databaseStats()
                            .compose(stats -> restricted.close().map(stats));
                })
                        .transform(result -> pool.query("""
                                GRANT EXECUTE ON FUNCTION pg_database_size(name) TO PUBLIC;
                                REVOKE CONNECT ON DATABASE testdb FROM management_stats_reader;
                                REVOKE USAGE ON SCHEMA management_read FROM management_stats_reader;
                                REVOKE SELECT ON ALL TABLES IN SCHEMA management_read FROM management_stats_reader;
                                DROP ROLE management_stats_reader
                                """).execute().transform(cleanup -> {
                            if (result.failed()) {
                                if (cleanup.failed() && cleanup.cause() != result.cause()) {
                                    result.cause().addSuppressed(cleanup.cause());
                                }
                                return Future.failedFuture(result.cause());
                            }
                            if (cleanup.failed()) {
                                return Future.failedFuture(cleanup.cause());
                            }
                            return Future.succeededFuture(result.result());
                        }))
                .onSuccess(stats -> context.verify(() -> {
                    assertEquals(Availability.UNAVAILABLE, stats.databaseBytes().availability());
                    assertEquals(Availability.UNAVAILABLE, stats.schemaBytes().availability());
                    assertEquals(null, stats.databaseBytes().value());
                    assertEquals(null, stats.schemaBytes().value());
                    context.completeNow();
                }))
                .onFailure(context::failNow);
    }

    @Test
    void missingSchemaProducesTypedReadinessFailure(VertxTestContext context) {
        PgManagementReadRepository missing =
                new PgManagementReadRepository(pool, "missing_management_schema");
        missing.expiryStats()
                .transform(expiry -> {
                    if (expiry.succeeded()) {
                        return Future.failedFuture("Missing schema returned expiry statistics");
                    }
                    ManagementReadinessException readiness =
                            assertInstanceOf(ManagementReadinessException.class, expiry.cause());
                    assertEquals(ManagementReadinessException.Code.SCHEMA_UNAVAILABLE, readiness.code());
                    return missing.databaseStats();
                })
                .onSuccess(ignored -> context.failNow("Missing management schema must not look ready"))
                .onFailure(failure -> context.verify(() -> {
                    ManagementReadinessException readiness =
                            assertInstanceOf(ManagementReadinessException.class, failure);
                    assertEquals(ManagementReadinessException.Code.SCHEMA_UNAVAILABLE, readiness.code());
                    context.completeNow();
                }));
    }

    @Test
    void namespaceKeysetDocumentsChangesBetweenPages(VertxTestContext context) {
        pool.query("""
                        INSERT INTO management_read.cache_entries
                            (namespace, cache_key, value_type, value_bytes)
                        VALUES ('a', '1', 'STRING', convert_to('v', 'UTF8')),
                               ('b', '1', 'STRING', convert_to('v', 'UTF8')),
                               ('d', '1', 'STRING', convert_to('v', 'UTF8'))
                        """).execute()
                .compose(ignored -> service.namespaces(new NamespaceQuery(
                        null, null, NamespaceQuery.Sort.NAMESPACE_ASC, null, 2)))
                .compose(first -> pool.withTransaction(connection -> connection.query("""
                                INSERT INTO management_read.cache_entries
                                    (namespace, cache_key, value_type, value_bytes)
                                VALUES ('aa', '1', 'STRING', convert_to('v', 'UTF8')),
                                       ('c', '1', 'STRING', convert_to('v', 'UTF8'));
                                DELETE FROM management_read.cache_entries WHERE namespace = 'd'
                                """).execute().map(first)))
                .compose(first -> service.namespaces(new NamespaceQuery(
                                null, null, NamespaceQuery.Sort.NAMESPACE_ASC,
                                first.nextCursor(), 2))
                        .map(second -> new Object[]{first, second}))
                .onSuccess(pages -> context.verify(() -> {
                    @SuppressWarnings("unchecked")
                    var first = (dev.mars.peegeeq.cache.api.management.AdminPage<
                            dev.mars.peegeeq.cache.api.management.NamespaceStats>) pages[0];
                    @SuppressWarnings("unchecked")
                    var second = (dev.mars.peegeeq.cache.api.management.AdminPage<
                            dev.mars.peegeeq.cache.api.management.NamespaceStats>) pages[1];
                    assertEquals(java.util.List.of("a", "b"),
                            first.items().stream().map(item -> item.namespace()).toList());
                    assertEquals(java.util.List.of("c"),
                            second.items().stream().map(item -> item.namespace()).toList());
                    context.completeNow();
                }))
                .onFailure(context::failNow);
    }

    @Test
    void maximumManagementIdentifierLengthsRoundTrip(VertxTestContext context) {
        String namespace = "n".repeat(128);
        String key = "键" + "k".repeat(1021);
        pool.preparedQuery("""
                        INSERT INTO management_read.cache_entries
                            (namespace, cache_key, value_type, value_bytes)
                        VALUES ($1, $2, 'STRING', convert_to('v', 'UTF8'))
                        """).execute(Tuple.of(namespace, key))
                .compose(ignored -> service.entry(new CacheKey(namespace, key), false))
                .onSuccess(entry -> context.verify(() -> {
                    assertEquals(namespace, entry.key().namespace());
                    assertEquals(key, entry.key().key());
                    context.completeNow();
                }))
                .onFailure(context::failNow);
    }

    @Test
    void boundedMetadataPlansUseV001Indexes(VertxTestContext context) {
        PgManagementReadSql statements = new PgManagementReadSql(SCHEMA);
        pool.withTransaction(connection -> connection.query("SET LOCAL enable_seqscan = off").execute()
                .compose(ignored -> connection.preparedQuery("EXPLAIN " + statements.entries)
                        .execute(Tuple.of("plan", null, null, "ALL_LIVE", null, 10)))
                .compose(entryPlan -> connection.preparedQuery("EXPLAIN " + statements.counters)
                        .execute(Tuple.of("plan", null, "ALL_LIVE", null, null, 10))
                        .map(counterPlan -> new Object[]{entryPlan, counterPlan}))
                .compose(plans -> connection.preparedQuery("EXPLAIN " + statements.locks)
                        .execute(Tuple.of("plan", null, "ACTIVE", null, null, 10))
                        .map(lockPlan -> new Object[]{plans[0], plans[1], lockPlan})))
                .onSuccess(plans -> context.verify(() -> {
                    String entryPlan = explainText((io.vertx.sqlclient.RowSet<io.vertx.sqlclient.Row>) plans[0]);
                    String counterPlan = explainText((io.vertx.sqlclient.RowSet<io.vertx.sqlclient.Row>) plans[1]);
                    String lockPlan = explainText((io.vertx.sqlclient.RowSet<io.vertx.sqlclient.Row>) plans[2]);
                    assertTrue(entryPlan.contains("cache_entries_pkey")
                            || entryPlan.contains("idx_cache_entries_namespace_key_pattern"));
                    assertTrue(counterPlan.contains("cache_counters_pkey")
                            || counterPlan.contains("idx_cache_counters_namespace_key_pattern"));
                    assertTrue(lockPlan.contains("cache_locks_pkey")
                            || lockPlan.contains("idx_cache_locks_lease_expires_at"));
                    context.completeNow();
                }))
                .onFailure(context::failNow);
    }

    private static String explainText(io.vertx.sqlclient.RowSet<io.vertx.sqlclient.Row> rows) {
        StringBuilder plan = new StringBuilder();
        rows.forEach(row -> plan.append(row.getString(0)).append('\n'));
        return plan.toString();
    }

    private Future<Void> seedMixedNamespace(String namespace) {
        return pool.withTransaction(connection -> connection.preparedQuery("""
                        INSERT INTO management_read.cache_entries
                            (namespace, cache_key, value_type, value_bytes, numeric_value, expires_at)
                        VALUES ($1, 'persistent', 'STRING', convert_to('value', 'UTF8'), NULL, NULL),
                               ($1, 'expiring', 'LONG', NULL, 42, NOW() + INTERVAL '1 hour'),
                               ($1, 'expired', 'JSON', convert_to('{}', 'UTF8'), NULL, NOW() - INTERVAL '1 hour')
                        """).execute(Tuple.of(namespace))
                .compose(ignored -> connection.preparedQuery("""
                        INSERT INTO management_read.cache_counters
                            (namespace, counter_key, counter_value, expires_at)
                        VALUES ($1, 'live', 7, NULL),
                               ($1, 'expired', 9, NOW() - INTERVAL '1 hour')
                        """).execute(Tuple.of(namespace)))
                .compose(ignored -> connection.preparedQuery("""
                        INSERT INTO management_read.cache_locks
                            (namespace, lock_key, owner_token, fencing_token, updated_at, lease_expires_at)
                        VALUES ($1, 'active', 'owner-active', 11, NOW(), NOW() + INTERVAL '1 hour'),
                               ($1, 'expired', 'owner-expired', 12,
                                NOW() - INTERVAL '2 hours', NOW() - INTERVAL '1 hour')
                        """).execute(Tuple.of(namespace)))
                .mapEmpty());
    }

    private Future<io.vertx.sqlclient.Row> independentCounts(String namespace) {
        return pool.preparedQuery("""
                        SELECT
                          (SELECT COUNT(*) FROM management_read.cache_entries
                            WHERE namespace = $1 AND (expires_at IS NULL OR expires_at > NOW()))::BIGINT AS live_entries,
                          (SELECT COUNT(*) FROM management_read.cache_entries
                            WHERE namespace = $1 AND expires_at <= NOW())::BIGINT AS expired_entries,
                          (SELECT COUNT(*) FROM management_read.cache_counters
                            WHERE namespace = $1 AND (expires_at IS NULL OR expires_at > NOW()))::BIGINT AS live_counters,
                          (SELECT COUNT(*) FROM management_read.cache_locks
                            WHERE namespace = $1 AND lease_expires_at > NOW())::BIGINT AS active_locks
                        """)
                .execute(Tuple.of(namespace))
                .map(rows -> rows.iterator().next());
    }

    private Future<Void> seedNamespaceCounts() {
        return pool.withTransaction(connection -> connection.query("""
                        INSERT INTO management_read.cache_entries
                            (namespace, cache_key, value_type, value_bytes, numeric_value)
                        VALUES ('large', '1', 'STRING', convert_to('v', 'UTF8'), NULL),
                               ('large', '2', 'STRING', convert_to('v', 'UTF8'), NULL),
                               ('literal%_\\a', '1', 'STRING', convert_to('v', 'UTF8'), NULL),
                               ('literal%_\\b', '1', 'STRING', convert_to('v', 'UTF8'), NULL),
                               ('small', '1', 'STRING', convert_to('v', 'UTF8'), NULL),
                               ('wildcard-x', '1', 'STRING', convert_to('v', 'UTF8'), NULL)
                """).execute().mapEmpty());
    }

    private Future<Void> seedEntryMetadata() {
        return pool.withTransaction(connection -> connection.preparedQuery("""
                        INSERT INTO management_read.cache_entries
                            (namespace, cache_key, value_type, value_bytes, numeric_value,
                             version, created_at, updated_at, expires_at, last_accessed_at)
                        VALUES
                            ('entries', 'live-secret', 'STRING', convert_to($1, 'UTF8'), NULL,
                             7, NOW() - INTERVAL '2 hours', NOW() - INTERVAL '1 hour', NULL, NOW()),
                            ('entries', 'expired', 'JSON', convert_to($2, 'UTF8'), NULL,
                             8, NOW() - INTERVAL '3 hours', NOW() - INTERVAL '2 hours',
                             NOW() - INTERVAL '1 hour', NULL),
                            ('entries', 'literal%_\\一', 'LONG', NULL, 1, 9, NOW(), NOW(), NULL, NULL),
                            ('entries', 'literal%_\\二', 'BYTES', decode('00ff', 'hex'), NULL,
                             10, NOW(), NOW(), NOW() + INTERVAL '1 hour', NULL),
                            ('entries', 'wildcard-x', 'STRING', convert_to('not-a-literal-match', 'UTF8'), NULL,
                             11, NOW(), NOW(), NULL, NULL)
                        """).execute(Tuple.of("do-not-leak-value", "{\"password\":\"do-not-log\"}"))
                .mapEmpty());
    }

    private Future<Void> seedCounterAndLockMetadata() {
        return pool.withTransaction(connection -> connection.query("""
                        INSERT INTO management_read.cache_counters
                            (namespace, counter_key, counter_value, version, expires_at)
                        VALUES
                            ('alpha', 'literal%_\\一', 9223372036854775806, 9223372036854775805, NULL),
                            ('beta', 'literal%_\\二', -9223372036854775807, 7, NOW() + INTERVAL '1 hour'),
                            ('alpha', 'expired', 9, 8, NOW() - INTERVAL '1 hour');

                        INSERT INTO management_read.cache_locks
                            (namespace, lock_key, owner_token, fencing_token, version,
                             updated_at, lease_expires_at)
                        VALUES
                            ('alpha', 'literal%_\\soon', 'secret-owner', 9223372036854775805, 17,
                             NOW(), NOW() + INTERVAL '30 seconds'),
                            ('beta', 'literal%_\\later', 'another-secret', 21, 18,
                             NOW(), NOW() + INTERVAL '2 hours'),
                            ('alpha', 'expired', 'expired-secret', 22, 19,
                             NOW() - INTERVAL '2 hours', NOW() - INTERVAL '1 hour')
                        """).execute().mapEmpty());
    }
}
