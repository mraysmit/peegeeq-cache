package dev.mars.peegeeq.cache.pg.management;

import dev.mars.peegeeq.cache.api.management.ManagementActionContext;
import dev.mars.peegeeq.cache.api.management.ManagementAuditFingerprinter;
import dev.mars.peegeeq.cache.api.management.ManagementAuditException;
import dev.mars.peegeeq.cache.api.management.ManagementAuditIntent;
import dev.mars.peegeeq.cache.api.management.ManagementAuditOutcome;
import dev.mars.peegeeq.cache.api.management.ManagementAuditReservation;
import dev.mars.peegeeq.cache.api.management.ManagementAuditSink;
import dev.mars.peegeeq.cache.api.management.ManagementAuditTerminalOutcome;
import dev.mars.peegeeq.cache.api.management.ManagementCursorCodec;
import dev.mars.peegeeq.cache.api.management.EntryTtlMode;
import dev.mars.peegeeq.cache.api.management.ManagementCacheSetRequest;
import dev.mars.peegeeq.cache.api.management.ManagementMutationOutcome;
import dev.mars.peegeeq.cache.api.management.ManagementNotFoundException;
import dev.mars.peegeeq.cache.api.management.ManagementSecretReference;
import dev.mars.peegeeq.cache.api.management.ManagementTtl;
import dev.mars.peegeeq.cache.api.management.RevealEntryRequest;
import dev.mars.peegeeq.cache.api.model.CacheKey;
import dev.mars.peegeeq.cache.api.model.CacheValue;
import dev.mars.peegeeq.cache.api.model.SetMode;
import dev.mars.peegeeq.cache.test.PgTestSupport;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.Tuple;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.time.Instant;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(VertxExtension.class)
@io.vertx.junit5.Timeout(value = 90, timeUnit = TimeUnit.SECONDS)
class PgManagementMutationRepositoryTest {

    private static final String SCHEMA = "management_mutation";
    private static final PgTestSupport POSTGRES = new PgTestSupport("management-mutation", SCHEMA);

    private static Pool pool;
    private static PgManagementMutationRepository repository;
    private static PgManagementService service;
    private static PgManagementService defaultTtlService;
    private static RecordingAuditSink auditSink;

    private static final ManagementActionContext ACTION_CONTEXT = new ManagementActionContext(
            "operator@example.test", Set.of("operator"), "correlation-m4", "127.0.0.1");

    @BeforeAll
    static void start(Vertx vertx, VertxTestContext context) {
        POSTGRES.start(vertx)
                .onSuccess(ignored -> context.verify(() -> {
                    pool = POSTGRES.createPool(vertx);
                    repository = new PgManagementMutationRepository(pool, SCHEMA);
                    auditSink = new RecordingAuditSink();
                    service = managementService(auditSink, null);
                    defaultTtlService = managementService(auditSink, Duration.ofMinutes(10));
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
        auditSink.clear();
        POSTGRES.resetDatabaseState(pool)
                .onSuccess(ignored -> context.completeNow())
                .onFailure(context::failNow);
    }

    @Test
    void revealReturnsValueVersionAndDatabaseTimeFromOneSnapshot(VertxTestContext context) {
        CacheKey key = new CacheKey("entries", "snapshot");
        Instant before = Instant.now();

        pool.preparedQuery("""
                        INSERT INTO management_mutation.cache_entries
                            (namespace, cache_key, value_type, value_bytes, version)
                        VALUES ($1, $2, 'STRING', convert_to($3, 'UTF8'), 41)
                        """)
                .execute(Tuple.of(key.namespace(), key.key(), "snapshot-secret"))
                .compose(ignored -> repository.revealEntry(key))
                .onSuccess(result -> context.verify(() -> {
                    assertTrue(result.isPresent());
                    var revealed = result.orElseThrow();
                    assertEquals(key, revealed.key());
                    assertEquals("snapshot-secret", revealed.value().asString());
                    assertEquals(41L, revealed.version());
                    assertTrue(!revealed.revealedAt().isBefore(before));
                    context.completeNow();
                }))
                .onFailure(context::failNow);
    }

    @Test
    void revealReturnsTypedNotFoundForMissingAndExpiredEntries(VertxTestContext context) {
        CacheKey missing = new CacheKey("entries", "missing");
        CacheKey expired = new CacheKey("entries", "expired");

        pool.preparedQuery("""
                        INSERT INTO management_mutation.cache_entries
                            (namespace, cache_key, value_type, value_bytes, expires_at)
                        VALUES ($1, $2, 'STRING', convert_to('expired-secret', 'UTF8'), NOW() - INTERVAL '1 minute')
                        """)
                .execute(Tuple.of(expired.namespace(), expired.key()))
                .compose(ignored -> failureOf(service.revealEntry(
                        new RevealEntryRequest(missing, "investigate missing entry"), ACTION_CONTEXT)))
                .compose(missingFailure -> {
                    assertInstanceOf(ManagementNotFoundException.class, missingFailure);
                    return failureOf(service.revealEntry(
                            new RevealEntryRequest(expired, "investigate expired entry"), ACTION_CONTEXT));
                })
                .onSuccess(expiredFailure -> context.verify(() -> {
                    assertInstanceOf(ManagementNotFoundException.class, expiredFailure);
                    assertEquals(2, auditSink.intents.size());
                    assertEquals(2, auditSink.outcomes.size());
                    assertTrue(auditSink.outcomes.stream().allMatch(outcome ->
                            outcome.outcome() == ManagementAuditTerminalOutcome.REJECTED
                                    && outcome.code().equals("ENTRY_NOT_FOUND")));
                    context.completeNow();
                }))
                .onFailure(context::failNow);
    }

    @Test
    void setModesReturnAtomicTypedOutcomesAndResultingVersions(VertxTestContext context) {
        CacheKey key = new CacheKey("entries", "set-modes");
        CacheKey missing = new CacheKey("entries", "set-modes-missing");

        service.setEntry(setRequest(key, "v1", SetMode.UPSERT, null), ACTION_CONTEXT)
                .compose(created -> {
                    assertEquals(ManagementMutationOutcome.APPLIED, created.outcome());
                    assertTrue(created.created());
                    assertEquals(1L, created.resultingVersion());
                    assertEquals(1L, created.representation().version());
                    return service.setEntry(
                            setRequest(key, "ignored", SetMode.ONLY_IF_ABSENT, null), ACTION_CONTEXT);
                })
                .compose(notAbsent -> {
                    assertEquals(ManagementMutationOutcome.CONDITION_NOT_MET, notAbsent.outcome());
                    return service.setEntry(
                            setRequest(missing, "ignored", SetMode.ONLY_IF_PRESENT, null), ACTION_CONTEXT);
                })
                .compose(notPresent -> {
                    assertEquals(ManagementMutationOutcome.CONDITION_NOT_MET, notPresent.outcome());
                    return service.setEntry(
                            setRequest(key, "stale", SetMode.ONLY_IF_VERSION_MATCHES, 99L), ACTION_CONTEXT);
                })
                .compose(stale -> {
                    assertEquals(ManagementMutationOutcome.VERSION_MISMATCH, stale.outcome());
                    return service.setEntry(
                            setRequest(missing, "missing", SetMode.ONLY_IF_VERSION_MATCHES, 1L), ACTION_CONTEXT);
                })
                .compose(notFound -> {
                    assertEquals(ManagementMutationOutcome.NOT_FOUND, notFound.outcome());
                    return service.setEntry(
                            setRequest(key, "v2", SetMode.ONLY_IF_VERSION_MATCHES, 1L), ACTION_CONTEXT);
                })
                .onSuccess(updated -> context.verify(() -> {
                    assertEquals(ManagementMutationOutcome.APPLIED, updated.outcome());
                    assertTrue(!updated.created());
                    assertEquals(2L, updated.resultingVersion());
                    assertEquals(2L, updated.representation().version());
                    assertEquals(6, auditSink.intents.size());
                    assertEquals(6, auditSink.outcomes.size());
                    context.completeNow();
                }))
                .onFailure(context::failNow);
    }

    @Test
    void setTtlModesAreAtomicWithTheValueMutation(VertxTestContext context) {
        CacheKey preserve = new CacheKey("entries", "preserve");
        CacheKey defaulted = new CacheKey("entries", "defaulted");
        CacheKey noDefault = new CacheKey("entries", "no-default");
        CacheKey replace = new CacheKey("entries", "replace");
        CacheKey preserveMissing = new CacheKey("entries", "preserve-missing");

        pool.preparedQuery("""
                        INSERT INTO management_mutation.cache_entries
                            (namespace, cache_key, value_type, value_bytes, expires_at)
                        VALUES ($1, $2, 'STRING', convert_to('before', 'UTF8'),
                                statement_timestamp() + INTERVAL '1 hour')
                        RETURNING expires_at
                        """)
                .execute(Tuple.of(preserve.namespace(), preserve.key()))
                .compose(rows -> {
                    Instant originalExpiry = rows.iterator().next()
                            .getOffsetDateTime("expires_at").toInstant();
                    return service.setEntry(
                                    ttlRequest(preserve, "preserved", SetMode.ONLY_IF_PRESENT,
                                            EntryTtlMode.PRESERVE_EXISTING, null),
                                    ACTION_CONTEXT)
                            .map(result -> new Object[]{originalExpiry, result});
                })
                .compose(preserved -> {
                    Instant originalExpiry = (Instant) preserved[0];
                    var result = (dev.mars.peegeeq.cache.api.management.ManagementSetResult) preserved[1];
                    assertEquals(ManagementMutationOutcome.APPLIED, result.outcome());
                    assertEquals(ManagementTtl.State.EXPIRING, result.representation().ttl().state());
                    assertEquals(originalExpiry, result.representation().ttl().expiresAt());
                    return defaultTtlService.setEntry(
                            ttlRequest(defaulted, "default", SetMode.UPSERT,
                                    EntryTtlMode.USE_DEFAULT, null), ACTION_CONTEXT);
                })
                .compose(defaultResult -> {
                    assertEquals(ManagementTtl.State.EXPIRING, defaultResult.representation().ttl().state());
                    assertTrue(defaultResult.representation().ttl().ttlMillis() <= Duration.ofMinutes(10).toMillis());
                    assertTrue(defaultResult.representation().ttl().ttlMillis() > Duration.ofMinutes(9).toMillis());
                    return service.setEntry(
                            ttlRequest(noDefault, "persistent", SetMode.UPSERT,
                                    EntryTtlMode.USE_DEFAULT, null), ACTION_CONTEXT);
                })
                .compose(noDefaultResult -> {
                    assertEquals(ManagementTtl.State.PERSISTENT, noDefaultResult.representation().ttl().state());
                    return service.setEntry(
                            ttlRequest(replace, "replace", SetMode.UPSERT,
                                    EntryTtlMode.REPLACE, Duration.ofMinutes(5)), ACTION_CONTEXT);
                })
                .compose(replaced -> {
                    assertEquals(ManagementTtl.State.EXPIRING, replaced.representation().ttl().state());
                    assertTrue(replaced.representation().ttl().ttlMillis() <= Duration.ofMinutes(5).toMillis());
                    assertTrue(replaced.representation().ttl().ttlMillis() > Duration.ofMinutes(4).toMillis());
                    return service.setEntry(
                            ttlRequest(replace, "persistent", SetMode.ONLY_IF_PRESENT,
                                    EntryTtlMode.REMOVE, null), ACTION_CONTEXT);
                })
                .compose(removed -> {
                    assertEquals(ManagementTtl.State.PERSISTENT, removed.representation().ttl().state());
                    return service.setEntry(
                            ttlRequest(preserveMissing, "ignored", SetMode.UPSERT,
                                    EntryTtlMode.PRESERVE_EXISTING, null), ACTION_CONTEXT);
                })
                .onSuccess(notCreated -> context.verify(() -> {
                    assertEquals(ManagementMutationOutcome.CONDITION_NOT_MET, notCreated.outcome());
                    context.completeNow();
                }))
                .onFailure(context::failNow);
    }

    @Test
    void onlyIfAbsentReclaimsAnExpiredPhysicalRow(VertxTestContext context) {
        CacheKey key = new CacheKey("entries", "expired-slot");

        pool.preparedQuery("""
                        INSERT INTO management_mutation.cache_entries
                            (namespace, cache_key, value_type, value_bytes, version, expires_at)
                        VALUES ($1, $2, 'STRING', convert_to('expired-value', 'UTF8'), 27,
                                statement_timestamp() - INTERVAL '1 minute')
                        """)
                .execute(Tuple.of(key.namespace(), key.key()))
                .compose(ignored -> service.setEntry(
                        setRequest(key, "replacement", SetMode.ONLY_IF_ABSENT, null),
                        ACTION_CONTEXT))
                .compose(result -> {
                    assertEquals(ManagementMutationOutcome.APPLIED, result.outcome());
                    assertTrue(result.created());
                    assertEquals(1L, result.resultingVersion());
                    return repository.revealEntry(key);
                })
                .onSuccess(revealed -> context.verify(() -> {
                    assertTrue(revealed.isPresent());
                    assertEquals("replacement", revealed.orElseThrow().value().asString());
                    assertEquals(1L, revealed.orElseThrow().version());
                    context.completeNow();
                }))
                .onFailure(context::failNow);
    }

    @Test
    void concurrentExactVersionUpdatesHaveOneWinnerAndCommittedMetadata(VertxTestContext context) {
        CacheKey key = new CacheKey("entries", "concurrent-version");

        service.setEntry(setRequest(key, "seed", SetMode.UPSERT, null), ACTION_CONTEXT)
                .compose(seed -> {
                    assertEquals(1L, seed.resultingVersion());
                    Future<dev.mars.peegeeq.cache.api.management.ManagementSetResult> left =
                            service.setEntry(setRequest(
                                    key, "left-winner", SetMode.ONLY_IF_VERSION_MATCHES, 1L),
                                    ACTION_CONTEXT);
                    Future<dev.mars.peegeeq.cache.api.management.ManagementSetResult> right =
                            service.setEntry(setRequest(
                                    key, "right-winner", SetMode.ONLY_IF_VERSION_MATCHES, 1L),
                                    ACTION_CONTEXT);
                    return Future.all(List.of(left, right));
                })
                .compose(results -> {
                    var left = results.<dev.mars.peegeeq.cache.api.management.ManagementSetResult>resultAt(0);
                    var right = results.<dev.mars.peegeeq.cache.api.management.ManagementSetResult>resultAt(1);
                    assertEquals(1L, List.of(left, right).stream()
                            .filter(result -> result.outcome() == ManagementMutationOutcome.APPLIED)
                            .count());
                    assertEquals(1L, List.of(left, right).stream()
                            .filter(result -> result.outcome() == ManagementMutationOutcome.VERSION_MISMATCH)
                            .count());
                    var applied = left.outcome() == ManagementMutationOutcome.APPLIED ? left : right;
                    assertEquals(2L, applied.resultingVersion());
                    assertEquals(applied.resultingVersion(), applied.representation().version());
                    String winningValue = left.outcome() == ManagementMutationOutcome.APPLIED
                            ? "left-winner" : "right-winner";
                    return repository.revealEntry(key)
                            .map(revealed -> new Object[]{winningValue, revealed.orElseThrow()});
                })
                .onSuccess(committed -> context.verify(() -> {
                    String winningValue = (String) committed[0];
                    var revealed = (dev.mars.peegeeq.cache.api.management.RevealedEntryValue) committed[1];
                    assertEquals(winningValue, revealed.value().asString());
                    assertEquals(2L, revealed.version());
                    context.completeNow();
                }))
                .onFailure(context::failNow);
    }

    @Test
    void auditReservationFailurePreventsMutationAndAuditUsesOnlyFingerprints(VertxTestContext context) {
        CacheKey blockedKey = new CacheKey("blocked-namespace", "blocked-key");
        ManagementAuditSink unavailableAudit = new ManagementAuditSink() {
            @Override
            public Future<ManagementAuditReservation> reserveIntent(ManagementAuditIntent intent) {
                return Future.failedFuture("audit unavailable");
            }

            @Override
            public Future<Void> complete(
                    ManagementAuditReservation reservation,
                    ManagementAuditOutcome outcome) {
                return Future.succeededFuture();
            }
        };

        failureOf(managementService(unavailableAudit, null).setEntry(
                        setRequest(blockedKey, "must-never-reach-postgres", SetMode.UPSERT, null),
                        ACTION_CONTEXT))
                .compose(failure -> {
                    assertInstanceOf(ManagementAuditException.class, failure);
                    return pool.preparedQuery("""
                                    SELECT count(*) AS row_count
                                      FROM management_mutation.cache_entries
                                     WHERE namespace = $1 AND cache_key = $2
                                    """)
                            .execute(Tuple.of(blockedKey.namespace(), blockedKey.key()));
                })
                .compose(rows -> {
                    assertEquals(0L, rows.iterator().next().getLong("row_count"));
                    CacheKey auditedKey = new CacheKey("raw-secret-namespace", "raw-secret-key");
                    return service.setEntry(
                            setRequest(auditedKey, "raw-secret-value", SetMode.UPSERT, null),
                            ACTION_CONTEXT);
                })
                .onSuccess(ignored -> context.verify(() -> {
                    ManagementAuditIntent intent = auditSink.intents.getFirst();
                    String serialized = intent.toString();
                    assertTrue(!serialized.contains("raw-secret-namespace"));
                    assertTrue(!serialized.contains("raw-secret-key"));
                    assertTrue(!serialized.contains("raw-secret-value"));
                    assertEquals(Set.of("namespace", "key"), intent.identifierFingerprints().keySet());
                    context.completeNow();
                }))
                .onFailure(context::failNow);
    }

    private static ManagementCacheSetRequest setRequest(
            CacheKey key,
            String value,
            SetMode mode,
            Long expectedVersion) {
        return new ManagementCacheSetRequest(
                key,
                CacheValue.ofString(value),
                mode,
                expectedVersion,
                EntryTtlMode.REMOVE,
                null);
    }

    private static ManagementCacheSetRequest ttlRequest(
            CacheKey key,
            String value,
            SetMode mode,
            EntryTtlMode ttlMode,
            Duration ttl) {
        return new ManagementCacheSetRequest(
                key,
                CacheValue.ofString(value),
                mode,
                null,
                ttlMode,
                ttl);
    }

    private static Future<Throwable> failureOf(Future<?> operation) {
        return operation.transform(result -> result.failed()
                ? Future.succeededFuture(result.cause())
                : Future.failedFuture(new AssertionError("Operation unexpectedly succeeded")));
    }

    private static PgManagementService managementService(
            ManagementAuditSink sink,
            Duration defaultEntryTtl) {
        AtomicInteger eventSequence = new AtomicInteger();
        return new PgManagementService(
                new PgManagementReadRepository(pool, SCHEMA),
                repository,
                "setup-a",
                new ManagementCursorCodec(
                        "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8),
                        Clock.systemUTC(),
                        Duration.ofMinutes(15)),
                sink,
                new ManagementAuditFingerprinter(
                        new ManagementSecretReference("audit-test-key"),
                        ignoredKey -> "abcdef0123456789abcdef0123456789".getBytes(StandardCharsets.UTF_8),
                        "audit-v1",
                        128),
                Clock.fixed(Instant.parse("2026-08-21T13:00:00Z"), ZoneOffset.UTC),
                () -> "management-" + eventSequence.incrementAndGet(),
                defaultEntryTtl);
    }

    private static final class RecordingAuditSink implements ManagementAuditSink {
        private final List<ManagementAuditIntent> intents = new CopyOnWriteArrayList<>();
        private final List<ManagementAuditOutcome> outcomes = new CopyOnWriteArrayList<>();

        @Override
        public Future<ManagementAuditReservation> reserveIntent(ManagementAuditIntent intent) {
            intents.add(intent);
            return Future.succeededFuture(new ManagementAuditReservation(
                    "reservation-" + intents.size(), intent.eventId(), "test-generation"));
        }

        @Override
        public Future<Void> complete(
                ManagementAuditReservation reservation,
                ManagementAuditOutcome outcome) {
            outcomes.add(outcome);
            return Future.succeededFuture();
        }

        private void clear() {
            intents.clear();
            outcomes.clear();
        }
    }
}
