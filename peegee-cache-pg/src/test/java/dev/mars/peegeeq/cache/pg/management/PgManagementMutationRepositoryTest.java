package dev.mars.peegeeq.cache.pg.management;

import dev.mars.peegeeq.cache.api.management.BulkDeleteResult;
import dev.mars.peegeeq.cache.api.management.ConfirmedEntryDelete;
import dev.mars.peegeeq.cache.api.management.ConfirmedCounterDelete;
import dev.mars.peegeeq.cache.api.management.CounterDeleteSelection;
import dev.mars.peegeeq.cache.api.management.EntryTtlMode;
import dev.mars.peegeeq.cache.api.management.EntryDeleteFilter;
import dev.mars.peegeeq.cache.api.management.ForceReleaseLockRequest;
import dev.mars.peegeeq.cache.api.management.ManagementActionContext;
import dev.mars.peegeeq.cache.api.management.ManagementAuditAction;
import dev.mars.peegeeq.cache.api.management.ManagementAuditFingerprinter;
import dev.mars.peegeeq.cache.api.management.ManagementAuditException;
import dev.mars.peegeeq.cache.api.management.ManagementAuditIntent;
import dev.mars.peegeeq.cache.api.management.ManagementAuditOutcome;
import dev.mars.peegeeq.cache.api.management.ManagementAuditReservation;
import dev.mars.peegeeq.cache.api.management.ManagementAuditSink;
import dev.mars.peegeeq.cache.api.management.ManagementAuditTerminalOutcome;
import dev.mars.peegeeq.cache.api.management.ManagementBulkDeleteException;
import dev.mars.peegeeq.cache.api.management.ManagementCursorCodec;
import dev.mars.peegeeq.cache.api.management.ManagementCacheSetRequest;
import dev.mars.peegeeq.cache.api.management.ManagementCounterSetRequest;
import dev.mars.peegeeq.cache.api.management.ManagementCounterAdjustRequest;
import dev.mars.peegeeq.cache.api.management.ManagementCounterException;
import dev.mars.peegeeq.cache.api.management.ManagementMutationOutcome;
import dev.mars.peegeeq.cache.api.management.ManagementNotFoundException;
import dev.mars.peegeeq.cache.api.management.ManagementSecretReference;
import dev.mars.peegeeq.cache.api.management.ManagementTtl;
import dev.mars.peegeeq.cache.api.management.RevealEntryRequest;
import dev.mars.peegeeq.cache.api.management.RevealLockOwnerRequest;
import dev.mars.peegeeq.cache.api.management.RevealedEntryValue;
import dev.mars.peegeeq.cache.api.management.RevealedLockOwner;
import dev.mars.peegeeq.cache.api.management.VersionedCacheKeyRequest;
import dev.mars.peegeeq.cache.api.management.VersionedCounterDeleteRequest;
import dev.mars.peegeeq.cache.api.management.VersionedCounterTtlRequest;
import dev.mars.peegeeq.cache.api.management.VersionedEntryDeleteRequest;
import dev.mars.peegeeq.cache.api.management.VersionedEntryTouchRequest;
import dev.mars.peegeeq.cache.api.management.VersionedEntryTtlRequest;
import dev.mars.peegeeq.cache.api.management.VersionedCacheKeyTarget;
import dev.mars.peegeeq.cache.api.model.CacheKey;
import dev.mars.peegeeq.cache.api.model.CacheValue;
import dev.mars.peegeeq.cache.api.model.CounterTtlMode;
import dev.mars.peegeeq.cache.api.model.SetMode;
import dev.mars.peegeeq.cache.api.model.LockKey;
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

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
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

        pool.query("SELECT statement_timestamp() AS before")
                .execute()
                .map(rows -> rows.iterator().next().getOffsetDateTime("before").toInstant())
                .compose(before -> pool.preparedQuery("""
                        INSERT INTO management_mutation.cache_entries
                            (namespace, cache_key, value_type, value_bytes, version)
                        VALUES ($1, $2, 'STRING', convert_to($3, 'UTF8'), 41)
                        """)
                        .execute(Tuple.of(key.namespace(), key.key(), "snapshot-secret"))
                        .compose(ignored -> repository.revealEntry(key))
                        .map(result -> new Timed<>(before, result)))
                .onSuccess(state -> context.verify(() -> {
                    Instant before = state.before();
                    java.util.Optional<RevealedEntryValue> result = state.result();
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

    @Test
    void expireEntryReturnsAtomicOutcomesAndCommittedMetadata(VertxTestContext context) {
        CacheKey key = new CacheKey("entries", "expire");
        CacheKey missing = new CacheKey("entries", "expire-missing");

        pool.preparedQuery("""
                        INSERT INTO management_mutation.cache_entries
                            (namespace, cache_key, value_type, value_bytes, version)
                        VALUES ($1, $2, 'STRING', convert_to('unchanged-value', 'UTF8'), 5)
                        """)
                .execute(Tuple.of(key.namespace(), key.key()))
                .compose(ignored -> service.expireEntry(
                        new VersionedEntryTtlRequest(key, 5, Duration.ofMinutes(5)),
                        ACTION_CONTEXT))
                .compose(applied -> {
                    assertEquals(ManagementMutationOutcome.APPLIED, applied.outcome());
                    assertEquals(6L, applied.resultingVersion());
                    assertEquals(6L, applied.representation().version());
                    assertEquals(ManagementTtl.State.EXPIRING, applied.representation().ttl().state());
                    assertTrue(applied.representation().ttl().ttlMillis() > Duration.ofMinutes(4).toMillis());
                    assertTrue(applied.representation().ttl().ttlMillis() <= Duration.ofMinutes(5).toMillis());
                    return service.expireEntry(
                                    new VersionedEntryTtlRequest(key, 5, Duration.ofMinutes(1)),
                                    ACTION_CONTEXT)
                            .map(stale -> new Object[]{applied, stale});
                })
                .compose(results -> {
                    var applied = (dev.mars.peegeeq.cache.api.management.VersionedMutationResult<?>) results[0];
                    var stale = (dev.mars.peegeeq.cache.api.management.VersionedMutationResult<?>) results[1];
                    assertEquals(ManagementMutationOutcome.VERSION_MISMATCH, stale.outcome());
                    return service.expireEntry(
                                    new VersionedEntryTtlRequest(missing, 1, Duration.ofMinutes(1)),
                                    ACTION_CONTEXT)
                            .map(notFound -> new Object[]{applied, notFound});
                })
                .compose(results -> {
                    var applied = (dev.mars.peegeeq.cache.api.management.VersionedMutationResult<?>) results[0];
                    var notFound = (dev.mars.peegeeq.cache.api.management.VersionedMutationResult<?>) results[1];
                    assertEquals(ManagementMutationOutcome.NOT_FOUND, notFound.outcome());
                    return pool.preparedQuery("""
                                    SELECT value_bytes, version, updated_at, expires_at
                                      FROM management_mutation.cache_entries
                                     WHERE namespace = $1 AND cache_key = $2
                                    """)
                            .execute(Tuple.of(key.namespace(), key.key()))
                            .map(rows -> new Object[]{applied, rows.iterator().next()});
                })
                .onSuccess(results -> context.verify(() -> {
                    var applied = (dev.mars.peegeeq.cache.api.management.VersionedMutationResult<?>) results[0];
                    var metadata = (dev.mars.peegeeq.cache.api.management.ManagementEntryMetadata)
                            applied.representation();
                    var row = (io.vertx.sqlclient.Row) results[1];
                    assertEquals("unchanged-value", row.getBuffer("value_bytes").toString());
                    assertEquals(6L, row.getLong("version"));
                    assertEquals(metadata.updatedAt(), row.getOffsetDateTime("updated_at").toInstant());
                    assertEquals(metadata.ttl().expiresAt(), row.getOffsetDateTime("expires_at").toInstant());
                    assertEquals(List.of(
                                    ManagementAuditAction.EXPIRE_ENTRY,
                                    ManagementAuditAction.EXPIRE_ENTRY,
                                    ManagementAuditAction.EXPIRE_ENTRY),
                            auditSink.intents.stream().map(ManagementAuditIntent::action).toList());
                    assertEquals(List.of("ENTRY_TTL_SET", "VERSION_MISMATCH", "ENTRY_NOT_FOUND"),
                            auditSink.outcomes.stream().map(ManagementAuditOutcome::code).toList());
                    assertEquals(6L, auditSink.outcomes.getFirst().resultingVersion());
                    context.completeNow();
                }))
                .onFailure(context::failNow);
    }

    @Test
    void persistEntryReturnsAtomicOutcomesAndCommittedMetadata(VertxTestContext context) {
        CacheKey key = new CacheKey("entries", "persist");
        CacheKey missing = new CacheKey("entries", "persist-missing");

        pool.preparedQuery("""
                        INSERT INTO management_mutation.cache_entries
                            (namespace, cache_key, value_type, value_bytes, version, expires_at)
                        VALUES ($1, $2, 'STRING', convert_to('unchanged-value', 'UTF8'), 7,
                                statement_timestamp() + INTERVAL '1 hour')
                        """)
                .execute(Tuple.of(key.namespace(), key.key()))
                .compose(ignored -> service.persistEntry(
                        new VersionedCacheKeyRequest(key, 7), ACTION_CONTEXT))
                .compose(applied -> {
                    assertEquals(ManagementMutationOutcome.APPLIED, applied.outcome());
                    assertEquals(8L, applied.resultingVersion());
                    assertEquals(8L, applied.representation().version());
                    assertEquals(ManagementTtl.State.PERSISTENT, applied.representation().ttl().state());
                    return service.persistEntry(
                                    new VersionedCacheKeyRequest(key, 7), ACTION_CONTEXT)
                            .map(stale -> new Object[]{applied, stale});
                })
                .compose(results -> {
                    var applied = (dev.mars.peegeeq.cache.api.management.VersionedMutationResult<?>) results[0];
                    var stale = (dev.mars.peegeeq.cache.api.management.VersionedMutationResult<?>) results[1];
                    assertEquals(ManagementMutationOutcome.VERSION_MISMATCH, stale.outcome());
                    return service.persistEntry(
                                    new VersionedCacheKeyRequest(missing, 1), ACTION_CONTEXT)
                            .map(notFound -> new Object[]{applied, notFound});
                })
                .compose(results -> {
                    var applied = (dev.mars.peegeeq.cache.api.management.VersionedMutationResult<?>) results[0];
                    var notFound = (dev.mars.peegeeq.cache.api.management.VersionedMutationResult<?>) results[1];
                    assertEquals(ManagementMutationOutcome.NOT_FOUND, notFound.outcome());
                    return pool.preparedQuery("""
                                    SELECT value_bytes, version, updated_at, expires_at
                                      FROM management_mutation.cache_entries
                                     WHERE namespace = $1 AND cache_key = $2
                                    """)
                            .execute(Tuple.of(key.namespace(), key.key()))
                            .map(rows -> new Object[]{applied, rows.iterator().next()});
                })
                .onSuccess(results -> context.verify(() -> {
                    var applied = (dev.mars.peegeeq.cache.api.management.VersionedMutationResult<?>) results[0];
                    var metadata = (dev.mars.peegeeq.cache.api.management.ManagementEntryMetadata)
                            applied.representation();
                    var row = (io.vertx.sqlclient.Row) results[1];
                    assertEquals("unchanged-value", row.getBuffer("value_bytes").toString());
                    assertEquals(8L, row.getLong("version"));
                    assertEquals(metadata.updatedAt(), row.getOffsetDateTime("updated_at").toInstant());
                    assertNull(row.getOffsetDateTime("expires_at"));
                    assertEquals(List.of(
                                    ManagementAuditAction.PERSIST_ENTRY,
                                    ManagementAuditAction.PERSIST_ENTRY,
                                    ManagementAuditAction.PERSIST_ENTRY),
                            auditSink.intents.stream().map(ManagementAuditIntent::action).toList());
                    assertEquals(List.of("ENTRY_PERSISTED", "VERSION_MISMATCH", "ENTRY_NOT_FOUND"),
                            auditSink.outcomes.stream().map(ManagementAuditOutcome::code).toList());
                    assertEquals(8L, auditSink.outcomes.getFirst().resultingVersion());
                    context.completeNow();
                }))
                .onFailure(context::failNow);
    }

    @Test
    void touchEntryIsVersionStableAndAtomicallyRefreshesOptionalTtl(VertxTestContext context) {
        CacheKey key = new CacheKey("entries", "touch");
        CacheKey missing = new CacheKey("entries", "touch-missing");

        pool.preparedQuery("""
                        INSERT INTO management_mutation.cache_entries
                            (namespace, cache_key, value_type, value_bytes, version,
                             updated_at, expires_at, last_accessed_at)
                        VALUES ($1, $2, 'STRING', convert_to('unchanged-value', 'UTF8'), 11,
                                statement_timestamp() - INTERVAL '1 hour',
                                statement_timestamp() + INTERVAL '1 hour', NULL)
                        RETURNING expires_at
                        """)
                .execute(Tuple.of(key.namespace(), key.key()))
                .compose(rows -> {
                    Instant originalExpiry = rows.iterator().next()
                            .getOffsetDateTime("expires_at").toInstant();
                    return service.touchEntry(
                                    new VersionedEntryTouchRequest(key, 11, null), ACTION_CONTEXT)
                            .map(retained -> new Object[]{originalExpiry, retained});
                })
                .compose(results -> {
                    Instant originalExpiry = (Instant) results[0];
                    var retained = (dev.mars.peegeeq.cache.api.management.VersionedMutationResult<?>) results[1];
                    assertEquals(ManagementMutationOutcome.APPLIED, retained.outcome());
                    assertEquals(11L, retained.resultingVersion());
                    var metadata = (dev.mars.peegeeq.cache.api.management.ManagementEntryMetadata)
                            retained.representation();
                    assertEquals(11L, metadata.version());
                    assertEquals(originalExpiry, metadata.ttl().expiresAt());
                    return service.touchEntry(
                            new VersionedEntryTouchRequest(key, 11, Duration.ofMinutes(5)), ACTION_CONTEXT);
                })
                .compose(refreshed -> {
                    assertEquals(ManagementMutationOutcome.APPLIED, refreshed.outcome());
                    assertEquals(11L, refreshed.resultingVersion());
                    assertEquals(11L, refreshed.representation().version());
                    assertTrue(refreshed.representation().ttl().ttlMillis() > Duration.ofMinutes(4).toMillis());
                    assertTrue(refreshed.representation().ttl().ttlMillis() <= Duration.ofMinutes(5).toMillis());
                    return service.touchEntry(
                                    new VersionedEntryTouchRequest(key, 10, null), ACTION_CONTEXT)
                            .map(stale -> new Object[]{refreshed, stale});
                })
                .compose(results -> {
                    var refreshed = (dev.mars.peegeeq.cache.api.management.VersionedMutationResult<?>) results[0];
                    var stale = (dev.mars.peegeeq.cache.api.management.VersionedMutationResult<?>) results[1];
                    assertEquals(ManagementMutationOutcome.VERSION_MISMATCH, stale.outcome());
                    return service.touchEntry(
                                    new VersionedEntryTouchRequest(missing, 1, null), ACTION_CONTEXT)
                            .map(notFound -> new Object[]{refreshed, notFound});
                })
                .compose(results -> {
                    var refreshed = (dev.mars.peegeeq.cache.api.management.VersionedMutationResult<?>) results[0];
                    var notFound = (dev.mars.peegeeq.cache.api.management.VersionedMutationResult<?>) results[1];
                    assertEquals(ManagementMutationOutcome.NOT_FOUND, notFound.outcome());
                    return pool.preparedQuery("""
                                    SELECT value_bytes, version, updated_at, expires_at, last_accessed_at
                                      FROM management_mutation.cache_entries
                                     WHERE namespace = $1 AND cache_key = $2
                                    """)
                            .execute(Tuple.of(key.namespace(), key.key()))
                            .map(rows -> new Object[]{refreshed, rows.iterator().next()});
                })
                .onSuccess(results -> context.verify(() -> {
                    var refreshed = (dev.mars.peegeeq.cache.api.management.VersionedMutationResult<?>) results[0];
                    var metadata = (dev.mars.peegeeq.cache.api.management.ManagementEntryMetadata)
                            refreshed.representation();
                    var row = (io.vertx.sqlclient.Row) results[1];
                    assertEquals("unchanged-value", row.getBuffer("value_bytes").toString());
                    assertEquals(11L, row.getLong("version"));
                    assertEquals(metadata.updatedAt(), row.getOffsetDateTime("updated_at").toInstant());
                    assertEquals(metadata.updatedAt(), row.getOffsetDateTime("last_accessed_at").toInstant());
                    assertEquals(metadata.ttl().expiresAt(), row.getOffsetDateTime("expires_at").toInstant());
                    assertEquals(List.of(
                                    ManagementAuditAction.TOUCH_ENTRY,
                                    ManagementAuditAction.TOUCH_ENTRY,
                                    ManagementAuditAction.TOUCH_ENTRY,
                                    ManagementAuditAction.TOUCH_ENTRY),
                            auditSink.intents.stream().map(ManagementAuditIntent::action).toList());
                    assertEquals(List.of(
                                    "ENTRY_TOUCHED", "ENTRY_TOUCHED",
                                    "VERSION_MISMATCH", "ENTRY_NOT_FOUND"),
                            auditSink.outcomes.stream().map(ManagementAuditOutcome::code).toList());
                    assertEquals(11L, auditSink.outcomes.getFirst().resultingVersion());
                    assertEquals(11L, auditSink.outcomes.get(1).resultingVersion());
                    context.completeNow();
                }))
                .onFailure(context::failNow);
    }

    @Test
    void deleteEntryReturnsAtomicOutcomeWithoutAFollowUpDiagnosticRead(VertxTestContext context) {
        CacheKey key = new CacheKey("entries", "delete");
        CacheKey missing = new CacheKey("entries", "delete-missing");

        pool.preparedQuery("""
                        INSERT INTO management_mutation.cache_entries
                            (namespace, cache_key, value_type, value_bytes, version)
                        VALUES ($1, $2, 'STRING', convert_to('unchanged-value', 'UTF8'), 13)
                        """)
                .execute(Tuple.of(key.namespace(), key.key()))
                .compose(ignored -> service.deleteEntry(
                        new VersionedEntryDeleteRequest(key, 12), ACTION_CONTEXT))
                .compose(stale -> {
                    assertEquals(ManagementMutationOutcome.VERSION_MISMATCH, stale.outcome());
                    return pool.preparedQuery("""
                                    SELECT value_bytes, version
                                      FROM management_mutation.cache_entries
                                     WHERE namespace = $1 AND cache_key = $2
                                    """)
                            .execute(Tuple.of(key.namespace(), key.key()));
                })
                .compose(rows -> {
                    var row = rows.iterator().next();
                    assertEquals("unchanged-value", row.getBuffer("value_bytes").toString());
                    assertEquals(13L, row.getLong("version"));
                    return service.deleteEntry(
                            new VersionedEntryDeleteRequest(missing, 1), ACTION_CONTEXT);
                })
                .compose(notFound -> {
                    assertEquals(ManagementMutationOutcome.NOT_FOUND, notFound.outcome());
                    return service.deleteEntry(
                            new VersionedEntryDeleteRequest(key, 13), ACTION_CONTEXT);
                })
                .compose(applied -> {
                    assertEquals(ManagementMutationOutcome.APPLIED, applied.outcome());
                    assertEquals(13L, applied.resultingVersion());
                    assertNull(applied.representation());
                    return pool.preparedQuery("""
                                    SELECT count(*) AS row_count
                                      FROM management_mutation.cache_entries
                                     WHERE namespace = $1 AND cache_key = $2
                                    """)
                            .execute(Tuple.of(key.namespace(), key.key()));
                })
                .onSuccess(rows -> context.verify(() -> {
                    assertEquals(0L, rows.iterator().next().getLong("row_count"));
                    assertEquals(List.of(
                                    ManagementAuditAction.DELETE_ENTRY,
                                    ManagementAuditAction.DELETE_ENTRY,
                                    ManagementAuditAction.DELETE_ENTRY),
                            auditSink.intents.stream().map(ManagementAuditIntent::action).toList());
                    assertEquals(List.of("VERSION_MISMATCH", "ENTRY_NOT_FOUND", "ENTRY_DELETED"),
                            auditSink.outcomes.stream().map(ManagementAuditOutcome::code).toList());
                    assertEquals(13L, auditSink.outcomes.getLast().resultingVersion());
                    context.completeNow();
                }))
                .onFailure(context::failNow);
    }

    @Test
    void auditReservationFailurePreventsEveryM42EntryMutation(VertxTestContext context) {
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
        PgManagementService blocked = managementService(unavailableAudit, null);
        CacheKey expire = new CacheKey("entries", "audit-expire");
        CacheKey persist = new CacheKey("entries", "audit-persist");
        CacheKey touch = new CacheKey("entries", "audit-touch");
        CacheKey delete = new CacheKey("entries", "audit-delete");

        pool.query("""
                        INSERT INTO management_mutation.cache_entries
                            (namespace, cache_key, value_type, value_bytes, version, expires_at)
                        SELECT 'entries', key, 'STRING', convert_to('unchanged-value', 'UTF8'), 1,
                               statement_timestamp() + INTERVAL '1 hour'
                          FROM unnest(ARRAY[
                              'audit-expire', 'audit-persist', 'audit-touch', 'audit-delete']) AS key
                        """)
                .execute()
                .compose(ignored -> failureOf(blocked.expireEntry(
                        new VersionedEntryTtlRequest(expire, 1, Duration.ofMinutes(5)), ACTION_CONTEXT)))
                .compose(failure -> {
                    assertInstanceOf(ManagementAuditException.class, failure);
                    return failureOf(blocked.persistEntry(
                            new VersionedCacheKeyRequest(persist, 1), ACTION_CONTEXT));
                })
                .compose(failure -> {
                    assertInstanceOf(ManagementAuditException.class, failure);
                    return failureOf(blocked.touchEntry(
                            new VersionedEntryTouchRequest(touch, 1, Duration.ofMinutes(5)), ACTION_CONTEXT));
                })
                .compose(failure -> {
                    assertInstanceOf(ManagementAuditException.class, failure);
                    return failureOf(blocked.deleteEntry(
                            new VersionedEntryDeleteRequest(delete, 1), ACTION_CONTEXT));
                })
                .compose(failure -> {
                    assertInstanceOf(ManagementAuditException.class, failure);
                    return pool.query("""
                                    SELECT count(*) AS row_count,
                                           bool_and(version = 1) AS versions_unchanged,
                                           count(expires_at) AS expiring_count,
                                           count(last_accessed_at) AS accessed_count
                                      FROM management_mutation.cache_entries
                                     WHERE namespace = 'entries'
                                       AND cache_key LIKE 'audit-%'
                                    """)
                            .execute();
                })
                .onSuccess(rows -> context.verify(() -> {
                    var row = rows.iterator().next();
                    assertEquals(4L, row.getLong("row_count"));
                    assertTrue(row.getBoolean("versions_unchanged"));
                    assertEquals(4L, row.getLong("expiring_count"));
                    assertEquals(0L, row.getLong("accessed_count"));
                    context.completeNow();
                }))
                .onFailure(context::failNow);
    }

    @Test
    void createCounterRequiresAbsenceAndReturnsCommittedRepresentation(VertxTestContext context) {
        CacheKey key = new CacheKey("counters", "create-only");
        ManagementCounterSetRequest create = new ManagementCounterSetRequest(
                key, 41L, null, true, CounterTtlMode.REPLACE, Duration.ofMinutes(5));

        service.setCounter(create, ACTION_CONTEXT)
                .compose(created -> {
                    assertEquals(ManagementMutationOutcome.APPLIED, created.outcome());
                    assertEquals(1L, created.resultingVersion());
                    assertEquals(key, created.representation().key());
                    assertEquals(41L, created.representation().value());
                    assertEquals(1L, created.representation().version());
                    assertEquals(ManagementTtl.State.EXPIRING, created.representation().ttl().state());
                    return service.setCounter(create, ACTION_CONTEXT);
                })
                .compose(rejected -> {
                    assertEquals(ManagementMutationOutcome.CONDITION_NOT_MET, rejected.outcome());
                    return pool.preparedQuery("""
                                    SELECT counter_value, version
                                      FROM management_mutation.cache_counters
                                     WHERE namespace = $1 AND counter_key = $2
                                    """)
                            .execute(Tuple.of(key.namespace(), key.key()));
                })
                .onSuccess(rows -> context.verify(() -> {
                    var row = rows.iterator().next();
                    assertEquals(41L, row.getLong("counter_value"));
                    assertEquals(1L, row.getLong("version"));
                    assertEquals(2, auditSink.intents.size());
                    assertEquals(2, auditSink.outcomes.size());
                    context.completeNow();
                }))
                .onFailure(context::failNow);
    }

    @Test
    void setCounterRequiresExactVersionAndPreservesStateWhenStale(VertxTestContext context) {
        CacheKey key = new CacheKey("counters", "exact-set");
        CacheKey missing = new CacheKey("counters", "exact-set-missing");

        pool.preparedQuery("""
                        INSERT INTO management_mutation.cache_counters
                            (namespace, counter_key, counter_value, version, expires_at)
                        VALUES ($1, $2, 10, 7, statement_timestamp() + INTERVAL '1 hour')
                        RETURNING expires_at
                        """)
                .execute(Tuple.of(key.namespace(), key.key()))
                .compose(inserted -> {
                    Instant originalExpiry = inserted.iterator().next()
                            .getOffsetDateTime("expires_at").toInstant();
                    ManagementCounterSetRequest update = new ManagementCounterSetRequest(
                            key, 20L, 7L, false, CounterTtlMode.PRESERVE_EXISTING, null);
                    return service.setCounter(update, ACTION_CONTEXT).map(result ->
                            new Object[] {originalExpiry, result});
                })
                .compose(state -> {
                    Instant originalExpiry = (Instant) state[0];
                    @SuppressWarnings("unchecked")
                    var updated = (dev.mars.peegeeq.cache.api.management.VersionedMutationResult<
                            dev.mars.peegeeq.cache.api.management.CounterEntry>) state[1];
                    assertEquals(ManagementMutationOutcome.APPLIED, updated.outcome());
                    assertEquals(8L, updated.resultingVersion());
                    assertEquals(20L, updated.representation().value());
                    assertEquals(originalExpiry, updated.representation().ttl().expiresAt());
                    ManagementCounterSetRequest stale = new ManagementCounterSetRequest(
                            key, 30L, 7L, false, CounterTtlMode.REMOVE, null);
                    return service.setCounter(stale, ACTION_CONTEXT).map(result ->
                            new Object[] {originalExpiry, result});
                })
                .compose(state -> {
                    assertEquals(ManagementMutationOutcome.VERSION_MISMATCH,
                            ((dev.mars.peegeeq.cache.api.management.VersionedMutationResult<?>) state[1]).outcome());
                    ManagementCounterSetRequest absent = new ManagementCounterSetRequest(
                            missing, 1L, 1L, false, CounterTtlMode.REMOVE, null);
                    return service.setCounter(absent, ACTION_CONTEXT).map(result ->
                            new Object[] {state[0], result});
                })
                .compose(state -> {
                    assertEquals(ManagementMutationOutcome.NOT_FOUND,
                            ((dev.mars.peegeeq.cache.api.management.VersionedMutationResult<?>) state[1]).outcome());
                    return pool.preparedQuery("""
                                    SELECT counter_value, version, expires_at
                                      FROM management_mutation.cache_counters
                                     WHERE namespace = $1 AND counter_key = $2
                                    """)
                            .execute(Tuple.of(key.namespace(), key.key()))
                            .map(rows -> new Object[] {state[0], rows.iterator().next()});
                })
                .onSuccess(state -> context.verify(() -> {
                    Instant originalExpiry = (Instant) state[0];
                    var row = (io.vertx.sqlclient.Row) state[1];
                    assertEquals(20L, row.getLong("counter_value"));
                    assertEquals(8L, row.getLong("version"));
                    assertEquals(originalExpiry, row.getOffsetDateTime("expires_at").toInstant());
                    context.completeNow();
                }))
                .onFailure(context::failNow);
    }

    @Test
    void adjustCounterAtomicallyReturnsValueVersionAndTtl(VertxTestContext context) {
        CacheKey existing = new CacheKey("counters", "adjust-existing");
        CacheKey missing = new CacheKey("counters", "adjust-create");

        pool.preparedQuery("""
                        INSERT INTO management_mutation.cache_counters
                            (namespace, counter_key, counter_value, version)
                        VALUES ($1, $2, 5, 2)
                        """)
                .execute(Tuple.of(existing.namespace(), existing.key()))
                .compose(ignored -> service.adjustCounter(
                        new ManagementCounterAdjustRequest(
                                existing, -3L, 2L, false,
                                CounterTtlMode.REPLACE, Duration.ofMinutes(5)),
                        ACTION_CONTEXT))
                .compose(adjusted -> {
                    assertEquals(ManagementMutationOutcome.APPLIED, adjusted.outcome());
                    assertEquals(3L, adjusted.resultingVersion());
                    assertEquals(2L, adjusted.representation().value());
                    assertEquals(ManagementTtl.State.EXPIRING, adjusted.representation().ttl().state());
                    return service.adjustCounter(
                            new ManagementCounterAdjustRequest(
                                    missing, 7L, null, true,
                                    CounterTtlMode.REMOVE, null),
                            ACTION_CONTEXT);
                })
                .onSuccess(created -> context.verify(() -> {
                    assertEquals(ManagementMutationOutcome.APPLIED, created.outcome());
                    assertEquals(1L, created.resultingVersion());
                    assertEquals(7L, created.representation().value());
                    assertEquals(ManagementTtl.State.PERSISTENT, created.representation().ttl().state());
                    context.completeNow();
                }))
                .onFailure(context::failNow);
    }

    @Test
    void counterExpirePersistAndDeleteReturnAtomicVersionedOutcomes(VertxTestContext context) {
        CacheKey key = new CacheKey("counters", "lifecycle");

        pool.preparedQuery("""
                        INSERT INTO management_mutation.cache_counters
                            (namespace, counter_key, counter_value, version)
                        VALUES ($1, $2, 12, 2)
                        """)
                .execute(Tuple.of(key.namespace(), key.key()))
                .compose(ignored -> service.expireCounter(
                        new VersionedCounterTtlRequest(key, 2L, Duration.ofMinutes(5)),
                        ACTION_CONTEXT))
                .compose(expired -> {
                    assertEquals(ManagementMutationOutcome.APPLIED, expired.outcome());
                    assertEquals(3L, expired.resultingVersion());
                    assertEquals(ManagementTtl.State.EXPIRING, expired.representation().ttl().state());
                    return service.persistCounter(
                            new VersionedCacheKeyRequest(key, 2L), ACTION_CONTEXT);
                })
                .compose(stale -> {
                    assertEquals(ManagementMutationOutcome.VERSION_MISMATCH, stale.outcome());
                    return service.persistCounter(
                            new VersionedCacheKeyRequest(key, 3L), ACTION_CONTEXT);
                })
                .compose(persisted -> {
                    assertEquals(ManagementMutationOutcome.APPLIED, persisted.outcome());
                    assertEquals(4L, persisted.resultingVersion());
                    assertEquals(ManagementTtl.State.PERSISTENT, persisted.representation().ttl().state());
                    return service.deleteCounter(
                            new VersionedCounterDeleteRequest(key, 4L), ACTION_CONTEXT);
                })
                .compose(deleted -> {
                    assertEquals(ManagementMutationOutcome.APPLIED, deleted.outcome());
                    assertEquals(4L, deleted.resultingVersion());
                    assertNull(deleted.representation());
                    return service.deleteCounter(
                            new VersionedCounterDeleteRequest(key, 4L), ACTION_CONTEXT);
                })
                .onSuccess(missing -> context.verify(() -> {
                    assertEquals(ManagementMutationOutcome.NOT_FOUND, missing.outcome());
                    context.completeNow();
                }))
                .onFailure(context::failNow);
    }

    @Test
    void counterOverflowIsTypedAuditedAndLeavesStateUnchanged(VertxTestContext context) {
        CacheKey key = new CacheKey("counters", "overflow");

        pool.preparedQuery("""
                        INSERT INTO management_mutation.cache_counters
                            (namespace, counter_key, counter_value, version)
                        VALUES ($1, $2, $3, 4)
                        """)
                .execute(Tuple.of(key.namespace(), key.key(), Long.MAX_VALUE))
                .compose(ignored -> failureOf(service.adjustCounter(
                        new ManagementCounterAdjustRequest(
                                key, 1L, 4L, false,
                                CounterTtlMode.PRESERVE_EXISTING, null),
                        ACTION_CONTEXT)))
                .compose(failure -> {
                    ManagementCounterException overflow = assertInstanceOf(
                            ManagementCounterException.class, failure);
                    assertEquals(ManagementCounterException.Code.OVERFLOW, overflow.code());
                    return pool.preparedQuery("""
                                    SELECT counter_value, version
                                      FROM management_mutation.cache_counters
                                     WHERE namespace = $1 AND counter_key = $2
                                    """)
                            .execute(Tuple.of(key.namespace(), key.key()));
                })
                .onSuccess(rows -> context.verify(() -> {
                    var row = rows.iterator().next();
                    assertEquals(Long.MAX_VALUE, row.getLong("counter_value"));
                    assertEquals(4L, row.getLong("version"));
                    assertEquals(1, auditSink.outcomes.size());
                    assertEquals(ManagementAuditTerminalOutcome.REJECTED,
                            auditSink.outcomes.getFirst().outcome());
                    assertEquals("VALIDATION_FAILED", auditSink.outcomes.getFirst().code());
                    context.completeNow();
                }))
                .onFailure(context::failNow);
    }

    @Test
    void concurrentCounterAdjustmentsHaveOneWinnerAndOneStaleOutcome(VertxTestContext context) {
        CacheKey key = new CacheKey("counters", "concurrent-adjust");

        pool.preparedQuery("""
                        INSERT INTO management_mutation.cache_counters
                            (namespace, counter_key, counter_value, version)
                        VALUES ($1, $2, 10, 1)
                        """)
                .execute(Tuple.of(key.namespace(), key.key()))
                .compose(ignored -> {
                    var request = new ManagementCounterAdjustRequest(
                            key, 5L, 1L, false,
                            CounterTtlMode.PRESERVE_EXISTING, null);
                    return Future.all(List.of(
                            service.adjustCounter(request, ACTION_CONTEXT),
                            service.adjustCounter(request, ACTION_CONTEXT)));
                })
                .compose(results -> {
                    var left = results.<dev.mars.peegeeq.cache.api.management.VersionedMutationResult<
                            dev.mars.peegeeq.cache.api.management.CounterEntry>>resultAt(0);
                    var right = results.<dev.mars.peegeeq.cache.api.management.VersionedMutationResult<
                            dev.mars.peegeeq.cache.api.management.CounterEntry>>resultAt(1);
                    assertEquals(1L, List.of(left, right).stream()
                            .filter(result -> result.outcome() == ManagementMutationOutcome.APPLIED)
                            .count());
                    assertEquals(1L, List.of(left, right).stream()
                            .filter(result -> result.outcome() == ManagementMutationOutcome.VERSION_MISMATCH)
                            .count());
                    return pool.preparedQuery("""
                                    SELECT counter_value, version
                                      FROM management_mutation.cache_counters
                                     WHERE namespace = $1 AND counter_key = $2
                                    """)
                            .execute(Tuple.of(key.namespace(), key.key()));
                })
                .onSuccess(rows -> context.verify(() -> {
                    var row = rows.iterator().next();
                    assertEquals(15L, row.getLong("counter_value"));
                    assertEquals(2L, row.getLong("version"));
                    context.completeNow();
                }))
                .onFailure(context::failNow);
    }

    @Test
    void auditFailureBlocksEveryCounterMutation(VertxTestContext context) {
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
        PgManagementService blocked = managementService(unavailableAudit, null);
        CacheKey key = new CacheKey("counters", "audit-blocked");

        pool.preparedQuery("""
                        INSERT INTO management_mutation.cache_counters
                            (namespace, counter_key, counter_value, version, expires_at)
                        VALUES ($1, $2, 9, 1, statement_timestamp() + INTERVAL '1 hour')
                        """)
                .execute(Tuple.of(key.namespace(), key.key()))
                .compose(ignored -> failureOf(blocked.setCounter(
                        new ManagementCounterSetRequest(
                                key, 10L, 1L, false,
                                CounterTtlMode.REMOVE, null), ACTION_CONTEXT)))
                .compose(failure -> {
                    assertInstanceOf(ManagementAuditException.class, failure);
                    return failureOf(blocked.adjustCounter(
                            new ManagementCounterAdjustRequest(
                                    key, 1L, 1L, false,
                                    CounterTtlMode.PRESERVE_EXISTING, null), ACTION_CONTEXT));
                })
                .compose(failure -> {
                    assertInstanceOf(ManagementAuditException.class, failure);
                    return failureOf(blocked.expireCounter(
                            new VersionedCounterTtlRequest(key, 1L, Duration.ofMinutes(5)),
                            ACTION_CONTEXT));
                })
                .compose(failure -> {
                    assertInstanceOf(ManagementAuditException.class, failure);
                    return failureOf(blocked.persistCounter(
                            new VersionedCacheKeyRequest(key, 1L), ACTION_CONTEXT));
                })
                .compose(failure -> {
                    assertInstanceOf(ManagementAuditException.class, failure);
                    return failureOf(blocked.deleteCounter(
                            new VersionedCounterDeleteRequest(key, 1L), ACTION_CONTEXT));
                })
                .compose(failure -> {
                    assertInstanceOf(ManagementAuditException.class, failure);
                    return pool.preparedQuery("""
                                    SELECT counter_value, version, expires_at
                                      FROM management_mutation.cache_counters
                                     WHERE namespace = $1 AND counter_key = $2
                                    """)
                            .execute(Tuple.of(key.namespace(), key.key()));
                })
                .onSuccess(rows -> context.verify(() -> {
                    var row = rows.iterator().next();
                    assertEquals(9L, row.getLong("counter_value"));
                    assertEquals(1L, row.getLong("version"));
                    assertTrue(row.getOffsetDateTime("expires_at") != null);
                    context.completeNow();
                }))
                .onFailure(context::failNow);
    }

    @Test
    void revealLockOwnerReturnsOwnerAndVersionFromOneSnapshot(VertxTestContext context) {
        LockKey key = new LockKey("locks", "reveal-owner");

        pool.query("SELECT statement_timestamp() AS before")
                .execute()
                .map(rows -> rows.iterator().next().getOffsetDateTime("before").toInstant())
                .compose(before -> pool.preparedQuery("""
                        INSERT INTO management_mutation.cache_locks
                            (namespace, lock_key, owner_token, version, lease_expires_at)
                        VALUES ($1, $2, $3, 7, statement_timestamp() + INTERVAL '5 minutes')
                        """)
                        .execute(Tuple.of(key.namespace(), key.key(), "owner-secret-token"))
                        .compose(ignored -> service.revealLockOwner(
                                new RevealLockOwnerRequest(key, "investigate stalled holder"),
                                ACTION_CONTEXT))
                        .map(revealed -> new Timed<>(before, revealed)))
                .onSuccess(state -> context.verify(() -> {
                    Instant before = state.before();
                    RevealedLockOwner revealed = state.result();
                    assertEquals(key, revealed.key());
                    assertEquals("owner-secret-token", revealed.ownerToken());
                    assertEquals(7L, revealed.version());
                    assertTrue(!revealed.revealedAt().isBefore(before));
                    assertEquals(ManagementAuditAction.REVEAL_LOCK_OWNER,
                            auditSink.intents.getFirst().action());
                    assertTrue(!auditSink.intents.getFirst().toString().contains("owner-secret-token"));
                    context.completeNow();
                }))
                .onFailure(context::failNow);
    }

    @Test
    void forceReleaseLockRequiresExactVersionAndReturnsAtomicOutcome(VertxTestContext context) {
        LockKey key = new LockKey("locks", "force-release");

        pool.preparedQuery("""
                        INSERT INTO management_mutation.cache_locks
                            (namespace, lock_key, owner_token, version, lease_expires_at)
                        VALUES ($1, $2, 'owner-secret-release', 5,
                                statement_timestamp() + INTERVAL '5 minutes')
                        """)
                .execute(Tuple.of(key.namespace(), key.key()))
                .compose(ignored -> service.forceReleaseLock(
                        new ForceReleaseLockRequest(
                                key, 4L, key.key(), "release abandoned lock"),
                        ACTION_CONTEXT))
                .compose(stale -> {
                    assertEquals(ManagementMutationOutcome.VERSION_MISMATCH, stale.outcome());
                    return service.forceReleaseLock(
                            new ForceReleaseLockRequest(
                                    key, 5L, key.key(), "release abandoned lock"),
                            ACTION_CONTEXT);
                })
                .compose(released -> {
                    assertEquals(ManagementMutationOutcome.APPLIED, released.outcome());
                    assertEquals(5L, released.resultingVersion());
                    return service.forceReleaseLock(
                            new ForceReleaseLockRequest(
                                    key, 5L, key.key(), "confirm missing result"),
                            ACTION_CONTEXT);
                })
                .onSuccess(missing -> context.verify(() -> {
                    assertEquals(ManagementMutationOutcome.NOT_FOUND, missing.outcome());
                    context.completeNow();
                }))
                .onFailure(context::failNow);
    }

    @Test
    void staleForceReleaseCannotDeleteAReacquiredLease(VertxTestContext context) {
        LockKey key = new LockKey("locks", "reacquired");

        pool.preparedQuery("SELECT * FROM management_mutation.acquire_lock($1, $2, $3, $4, $5, $6)")
                .execute(Tuple.of(
                        key.namespace(), key.key(), "old-owner-secret",
                        300_000L, false, true))
                .compose(ignored -> pool.preparedQuery("""
                                SELECT version
                                  FROM management_mutation.cache_locks
                                 WHERE namespace = $1 AND lock_key = $2
                                """)
                        .execute(Tuple.of(key.namespace(), key.key())))
                .compose(rows -> {
                    long staleVersion = rows.iterator().next().getLong("version");
                    return pool.preparedQuery("""
                                    UPDATE management_mutation.cache_locks
                                       SET updated_at = statement_timestamp() - INTERVAL '2 minutes',
                                           lease_expires_at = statement_timestamp() - INTERVAL '1 minute'
                                     WHERE namespace = $1 AND lock_key = $2
                                    """)
                            .execute(Tuple.of(key.namespace(), key.key()))
                            .map(staleVersion);
                })
                .compose(staleVersion -> pool.preparedQuery(
                                "SELECT * FROM management_mutation.acquire_lock($1, $2, $3, $4, $5, $6)")
                        .execute(Tuple.of(
                                key.namespace(), key.key(), "new-owner-secret",
                                300_000L, false, true))
                        .map(staleVersion))
                .compose(staleVersion -> service.forceReleaseLock(
                        new ForceReleaseLockRequest(
                                key, staleVersion, key.key(), "stale delayed release"),
                        ACTION_CONTEXT))
                .compose(outcome -> {
                    assertEquals(ManagementMutationOutcome.VERSION_MISMATCH, outcome.outcome());
                    return pool.preparedQuery("""
                                    SELECT owner_token
                                      FROM management_mutation.cache_locks
                                     WHERE namespace = $1 AND lock_key = $2
                                    """)
                            .execute(Tuple.of(key.namespace(), key.key()));
                })
                .onSuccess(rows -> context.verify(() -> {
                    assertEquals("new-owner-secret",
                            rows.iterator().next().getString("owner_token"));
                    context.completeNow();
                }))
                .onFailure(context::failNow);
    }

    @Test
    void revealTreatsMissingAndExpiredLocksAsNotFound(VertxTestContext context) {
        LockKey expired = new LockKey("locks", "expired-owner");
        LockKey missing = new LockKey("locks", "missing-owner");

        pool.preparedQuery("""
                        INSERT INTO management_mutation.cache_locks
                            (namespace, lock_key, owner_token, version, lease_expires_at)
                        VALUES ($1, $2, 'expired-owner-secret', 17,
                                statement_timestamp() + INTERVAL '5 minutes')
                        """)
                .execute(Tuple.of(expired.namespace(), expired.key()))
                .compose(ignored -> pool.preparedQuery("""
                                UPDATE management_mutation.cache_locks
                                   SET updated_at = statement_timestamp() - INTERVAL '2 minutes',
                                       lease_expires_at = statement_timestamp() - INTERVAL '1 minute'
                                 WHERE namespace = $1 AND lock_key = $2
                                """)
                        .execute(Tuple.of(expired.namespace(), expired.key())))
                .compose(ignored -> failureOf(service.revealLockOwner(
                        new RevealLockOwnerRequest(expired, "inspect expired lock"),
                        ACTION_CONTEXT)))
                .compose(expiredFailure -> {
                    ManagementNotFoundException notFound = assertInstanceOf(
                            ManagementNotFoundException.class, expiredFailure);
                    assertEquals(ManagementNotFoundException.Resource.LOCK, notFound.resource());
                    return failureOf(service.revealLockOwner(
                            new RevealLockOwnerRequest(missing, "inspect missing lock"),
                            ACTION_CONTEXT));
                })
                .onSuccess(missingFailure -> context.verify(() -> {
                    ManagementNotFoundException notFound = assertInstanceOf(
                            ManagementNotFoundException.class, missingFailure);
                    assertEquals(ManagementNotFoundException.Resource.LOCK, notFound.resource());
                    assertEquals(List.of("LOCK_NOT_FOUND", "LOCK_NOT_FOUND"),
                            auditSink.outcomes.stream().map(ManagementAuditOutcome::code).toList());
                    assertTrue(auditSink.intents.stream().noneMatch(intent ->
                            intent.toString().contains("expired-owner-secret")));
                    context.completeNow();
                }))
                .onFailure(context::failNow);
    }

    @Test
    void auditReservationFailureBlocksLockRevealAndForcedRelease(VertxTestContext context) {
        LockKey key = new LockKey("locks", "audit-blocked-lock");
        ManagementAuditSink rejectingSink = new ManagementAuditSink() {
            @Override
            public Future<ManagementAuditReservation> reserveIntent(ManagementAuditIntent intent) {
                return Future.failedFuture("audit queue saturated");
            }

            @Override
            public Future<Void> complete(
                    ManagementAuditReservation reservation,
                    ManagementAuditOutcome outcome) {
                return Future.failedFuture("reservation must not exist");
            }
        };
        PgManagementService blockedService = managementService(rejectingSink, null);

        pool.preparedQuery("""
                        INSERT INTO management_mutation.cache_locks
                            (namespace, lock_key, owner_token, version, lease_expires_at)
                        VALUES ($1, $2, 'audit-owner-secret', 23,
                                statement_timestamp() + INTERVAL '5 minutes')
                        """)
                .execute(Tuple.of(key.namespace(), key.key()))
                .compose(ignored -> failureOf(blockedService.revealLockOwner(
                        new RevealLockOwnerRequest(key, "audit unavailable"), ACTION_CONTEXT)))
                .compose(revealFailure -> {
                    assertInstanceOf(ManagementAuditException.class, revealFailure);
                    return failureOf(blockedService.forceReleaseLock(
                            new ForceReleaseLockRequest(
                                    key, 23L, key.key(), "audit unavailable"),
                            ACTION_CONTEXT));
                })
                .compose(releaseFailure -> {
                    assertInstanceOf(ManagementAuditException.class, releaseFailure);
                    return pool.preparedQuery("""
                                    SELECT owner_token, version
                                      FROM management_mutation.cache_locks
                                     WHERE namespace = $1 AND lock_key = $2
                                    """)
                            .execute(Tuple.of(key.namespace(), key.key()));
                })
                .onSuccess(rows -> context.verify(() -> {
                    var row = rows.iterator().next();
                    assertEquals("audit-owner-secret", row.getString("owner_token"));
                    assertEquals(23L, row.getLong("version"));
                    context.completeNow();
                }))
                .onFailure(context::failNow);
    }

    @Test
    void postCommitAuditOutcomeFailureIsUncertainAndBlocksFurtherMutations(
            VertxTestContext context) {
        AtomicBoolean mutationReady = new AtomicBoolean(true);
        AtomicInteger reservationSequence = new AtomicInteger();
        ManagementAuditSink failingOutcomeSink = new ManagementAuditSink() {
            @Override
            public Future<ManagementAuditReservation> reserveIntent(ManagementAuditIntent intent) {
                if (!mutationReady.get()) {
                    return Future.failedFuture("audit outcome recovery required");
                }
                return Future.succeededFuture(new ManagementAuditReservation(
                        "uncertain-" + reservationSequence.incrementAndGet(),
                        intent.eventId(),
                        "fault-generation"));
            }

            @Override
            public Future<Void> complete(
                    ManagementAuditReservation reservation,
                    ManagementAuditOutcome outcome) {
                mutationReady.set(false);
                return Future.failedFuture("injected terminal outcome failure");
            }
        };
        PgManagementService uncertainService = managementService(failingOutcomeSink, null);
        CacheKey committed = new CacheKey("entries", "uncertain-committed");
        CacheKey blocked = new CacheKey("entries", "uncertain-blocked");

        failureOf(uncertainService.setEntry(
                        setRequest(committed, "committed-value", SetMode.UPSERT, null),
                        ACTION_CONTEXT))
                .compose(firstFailure -> {
                    assertInstanceOf(ManagementAuditException.class, firstFailure);
                    return pool.preparedQuery("""
                                    SELECT convert_from(value_bytes, 'UTF8') AS value_text
                                      FROM management_mutation.cache_entries
                                     WHERE namespace = $1 AND cache_key = $2
                                    """)
                            .execute(Tuple.of(committed.namespace(), committed.key()));
                })
                .compose(rows -> {
                    assertEquals("committed-value",
                            rows.iterator().next().getString("value_text"));
                    return failureOf(uncertainService.setEntry(
                            setRequest(blocked, "must-not-commit", SetMode.UPSERT, null),
                            ACTION_CONTEXT));
                })
                .compose(blockedFailure -> {
                    assertInstanceOf(ManagementAuditException.class, blockedFailure);
                    return pool.preparedQuery("""
                                    SELECT COUNT(*) AS row_count
                                      FROM management_mutation.cache_entries
                                     WHERE namespace = $1 AND cache_key = $2
                                    """)
                            .execute(Tuple.of(blocked.namespace(), blocked.key()));
                })
                .onSuccess(rows -> context.verify(() -> {
                    assertEquals(0L, rows.iterator().next().getLong("row_count"));
                    context.completeNow();
                }))
                .onFailure(context::failNow);
    }

    @Test
    void entryBulkPreviewCapturesVersionsAndExecutionIsSingleUse(VertxTestContext context) {
        CacheKey first = new CacheKey("bulk", "first");
        CacheKey second = new CacheKey("bulk", "second");
        EntryDeleteFilter selection = new EntryDeleteFilter(
                "bulk", null, null, null,
                List.of(
                        new VersionedCacheKeyTarget(first, 3),
                        new VersionedCacheKeyTarget(second, 4)));

        pool.preparedQuery("""
                        INSERT INTO management_mutation.cache_entries
                            (namespace, cache_key, value_type, value_bytes, version)
                        VALUES ($1, $2, 'STRING', convert_to('first-value', 'UTF8'), 3),
                               ($1, $3, 'STRING', convert_to('second-value', 'UTF8'), 4)
                        """)
                .execute(Tuple.of("bulk", "first", "second"))
                .compose(ignored -> service.previewEntryDelete(selection, ACTION_CONTEXT))
                .map(preview -> {
                    context.verify(() -> {
                    assertEquals(2, preview.resolvedCount());
                    assertEquals("DELETE bulk", preview.confirmationPhrase());
                    assertTrue(preview.previewToken().length() >= 32);
                    assertTrue(preview.totalBytes() > 0);
                    assertEquals(List.of("first", "second"), preview.sampleKeys());
                    });
                    return preview;
                })
                .compose(preview -> pool.query("""
                                UPDATE management_mutation.cache_entries
                                   SET version = 5
                                 WHERE namespace = 'bulk' AND cache_key = 'second'
                                """).execute().map(preview))
                .compose(preview -> service.executeEntryDelete(
                        new ConfirmedEntryDelete(
                                preview.previewToken(), preview.confirmationPhrase(), "bulk"),
                        ACTION_CONTEXT).map(result -> new BulkExecution(preview.previewToken(), result)))
                .map(execution -> {
                    context.verify(() -> {
                        BulkDeleteResult result = execution.result();
                        assertEquals(2, result.processedCount());
                        assertEquals(1, result.deletedCount());
                        assertEquals(1, result.conflictCount());
                        assertEquals("second", result.conflicts().getFirst().key());
                        assertEquals(ManagementAuditAction.EXECUTE_ENTRY_DELETE,
                                auditSink.intents.getLast().action());
                    });
                    return execution;
                })
                .compose(execution -> failureOf(service.executeEntryDelete(
                        new ConfirmedEntryDelete(execution.token(), "DELETE bulk", "bulk"),
                        ACTION_CONTEXT)))
                .onSuccess(failure -> context.verify(() -> {
                    assertInstanceOf(ManagementBulkDeleteException.class, failure);
                    assertEquals(ManagementBulkDeleteException.Code.TOKEN_INVALID,
                            ((ManagementBulkDeleteException) failure).code());
                    context.completeNow();
                }))
                .onFailure(context::failNow);
    }

    @Test
    void bulkTokensStoreOnlyDigestsAndEnforceExpiryActorResourceAndRestartScopes(
            VertxTestContext context) {
        MutableClock tokenClock = new MutableClock(Instant.parse("2026-08-21T14:00:00Z"));
        PgManagementBulkDeleteCoordinator coordinator = new PgManagementBulkDeleteCoordinator(
                repository, "setup-a", tokenClock);
        CounterDeleteSelection selection = new CounterDeleteSelection(List.of(
                new VersionedCacheKeyTarget(new CacheKey("bulk", "counter"), 1)));
        ManagementActionContext otherActor = new ManagementActionContext(
                "other@example.test", Set.of("operator"), "correlation-other", "127.0.0.2");

        coordinator.previewCounter(selection, ACTION_CONTEXT)
                .compose(first -> coordinator.previewCounter(selection, ACTION_CONTEXT)
                        .map(second -> {
                            context.verify(() -> assertNotEquals(
                                    first.previewToken(), second.previewToken()));
                            return first;
                        }))
                .map(preview -> {
                    context.verify(() -> {
                        assertTrue(coordinator.containsTokenDigest(preview.previewToken()));
                        assertTrue(!coordinator.containsRawToken(preview.previewToken()));
                    });
                    return preview;
                })
                .compose(preview -> failureOf(coordinator.executeCounter(
                        new ConfirmedCounterDelete(
                                preview.previewToken(), preview.confirmationPhrase()),
                        otherActor)).map(failure -> new TokenFailure(preview, failure)))
                .map(state -> {
                    context.verify(() -> assertEquals(
                            ManagementBulkDeleteException.Code.SCOPE_MISMATCH,
                            ((ManagementBulkDeleteException) state.failure()).code()));
                    return state.preview();
                })
                .compose(preview -> failureOf(coordinator.executeEntry(
                        new ConfirmedEntryDelete(
                                preview.previewToken(), preview.confirmationPhrase(), "bulk"),
                        ACTION_CONTEXT)).map(failure -> new TokenFailure(preview, failure)))
                .map(state -> {
                    context.verify(() -> assertEquals(
                            ManagementBulkDeleteException.Code.SCOPE_MISMATCH,
                            ((ManagementBulkDeleteException) state.failure()).code()));
                    return state.preview();
                })
                .compose(preview -> failureOf(coordinator.executeCounter(
                        new ConfirmedCounterDelete(
                                preview.previewToken(), "DELETE 9 COUNTERS"),
                        ACTION_CONTEXT)).map(failure -> new TokenFailure(preview, failure)))
                .map(state -> {
                    context.verify(() -> assertEquals(
                            ManagementBulkDeleteException.Code.CONFIRMATION_MISMATCH,
                            ((ManagementBulkDeleteException) state.failure()).code()));
                    return state.preview();
                })
                .compose(preview -> {
                    PgManagementBulkDeleteCoordinator restarted =
                            new PgManagementBulkDeleteCoordinator(repository, "setup-a", tokenClock);
                    return failureOf(restarted.executeCounter(
                            new ConfirmedCounterDelete(
                                    preview.previewToken(), preview.confirmationPhrase()),
                            ACTION_CONTEXT)).map(failure -> new TokenFailure(preview, failure));
                })
                .map(state -> {
                    context.verify(() -> assertEquals(
                            ManagementBulkDeleteException.Code.TOKEN_INVALID,
                            ((ManagementBulkDeleteException) state.failure()).code()));
                    return state.preview();
                })
                .compose(preview -> {
                    tokenClock.advance(Duration.ofMinutes(5));
                    return failureOf(coordinator.executeCounter(
                            new ConfirmedCounterDelete(
                                    preview.previewToken(), preview.confirmationPhrase()),
                            ACTION_CONTEXT));
                })
                .onSuccess(failure -> context.verify(() -> {
                    assertEquals(ManagementBulkDeleteException.Code.TOKEN_EXPIRED,
                            ((ManagementBulkDeleteException) failure).code());
                    context.completeNow();
                }))
                .onFailure(context::failNow);
    }

    @Test
    void entryBulkFilterEscapesLikeMetacharactersAndIncludesExpiredRows(
            VertxTestContext context) {
        EntryDeleteFilter selection = new EntryDeleteFilter(
                "filter-bulk", "literal%", dev.mars.peegeeq.cache.api.model.ValueType.STRING,
                dev.mars.peegeeq.cache.api.management.ManagementTtlFilter.INCLUDE_EXPIRED,
                List.of());

        pool.query("""
                        INSERT INTO management_mutation.cache_entries
                            (namespace, cache_key, value_type, value_bytes, version, expires_at)
                        VALUES ('filter-bulk', 'literal%live', 'STRING', convert_to('one', 'UTF8'), 1, NULL),
                               ('filter-bulk', 'literal%expired', 'STRING', convert_to('two', 'UTF8'), 2,
                                NOW() - INTERVAL '1 minute'),
                               ('filter-bulk', 'literalXother', 'STRING', convert_to('three', 'UTF8'), 3, NULL)
                        """)
                .execute()
                .compose(ignored -> service.previewEntryDelete(selection, ACTION_CONTEXT))
                .onSuccess(preview -> context.verify(() -> {
                    assertEquals(2, preview.resolvedCount());
                    assertEquals(6, preview.totalBytes());
                    assertEquals(List.of("literal%expired", "literal%live"), preview.sampleKeys());
                    context.completeNow();
                }))
                .onFailure(context::failNow);
    }

    @Test
    void entryBulkFilterRejectsScopesAboveTenThousandRows(VertxTestContext context) {
        EntryDeleteFilter selection = new EntryDeleteFilter(
                "oversized-bulk", null, null,
                dev.mars.peegeeq.cache.api.management.ManagementTtlFilter.ALL_LIVE,
                List.of());

        pool.query("""
                        INSERT INTO management_mutation.cache_entries
                            (namespace, cache_key, value_type, value_bytes, version)
                        SELECT 'oversized-bulk', 'key-' || number, 'STRING', convert_to('x', 'UTF8'), 1
                          FROM generate_series(1, 10001) AS number
                        """)
                .execute()
                .compose(ignored -> failureOf(service.previewEntryDelete(selection, ACTION_CONTEXT)))
                .onSuccess(failure -> context.verify(() -> {
                    assertInstanceOf(ManagementBulkDeleteException.class, failure);
                    assertEquals(ManagementBulkDeleteException.Code.SCOPE_TOO_LARGE,
                            ((ManagementBulkDeleteException) failure).code());
                    assertEquals(ManagementAuditTerminalOutcome.REJECTED,
                            auditSink.outcomes.getLast().outcome());
                    context.completeNow();
                }))
                .onFailure(context::failNow);
    }

    @Test
    void bulkExecutionConsumesTokenBeforeDatabaseFailure(
            Vertx vertx, VertxTestContext context) {
        Pool unavailablePool = POSTGRES.createPool(vertx);
        PgManagementBulkDeleteCoordinator coordinator = new PgManagementBulkDeleteCoordinator(
                new PgManagementMutationRepository(unavailablePool, SCHEMA),
                "setup-a", Clock.systemUTC());
        CounterDeleteSelection selection = new CounterDeleteSelection(List.of(
                new VersionedCacheKeyTarget(new CacheKey("bulk", "unavailable"), 1)));

        coordinator.previewCounter(selection, ACTION_CONTEXT)
                .compose(preview -> unavailablePool.close().map(preview))
                .compose(preview -> coordinator.executeCounter(
                        new ConfirmedCounterDelete(
                                preview.previewToken(), preview.confirmationPhrase()),
                        ACTION_CONTEXT).map(result -> new FailedBulkExecution(preview, result)))
                .compose(execution -> {
                    context.verify(() -> {
                        assertEquals(1, execution.result().processedCount());
                        assertEquals(1, execution.result().failedCount());
                    });
                    return failureOf(coordinator.executeCounter(
                            new ConfirmedCounterDelete(
                                    execution.preview().previewToken(),
                                    execution.preview().confirmationPhrase()),
                            ACTION_CONTEXT));
                })
                .onSuccess(failure -> context.verify(() -> {
                    assertEquals(ManagementBulkDeleteException.Code.TOKEN_INVALID,
                            ((ManagementBulkDeleteException) failure).code());
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

    private record Timed<T>(Instant before, T result) {
    }

    private record BulkExecution(String token, BulkDeleteResult result) {
    }

    private record FailedBulkExecution(
            dev.mars.peegeeq.cache.api.management.BulkDeletePreview preview,
            BulkDeleteResult result) {
    }

    private record TokenFailure(
            dev.mars.peegeeq.cache.api.management.BulkDeletePreview preview,
            Throwable failure) {
    }

    private static final class MutableClock extends Clock {
        private Instant now;

        private MutableClock(Instant now) {
            this.now = now;
        }

        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return Clock.fixed(now, zone);
        }

        @Override
        public Instant instant() {
            return now;
        }

        private void advance(Duration duration) {
            now = now.plus(duration);
        }
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
