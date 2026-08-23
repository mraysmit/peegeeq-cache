package dev.mars.peegeeq.cache.rest.audit;

import dev.mars.peegeeq.cache.api.management.ManagementAuditAction;
import dev.mars.peegeeq.cache.api.management.ManagementAuditConflictException;
import dev.mars.peegeeq.cache.api.management.ManagementAuditException;
import dev.mars.peegeeq.cache.api.management.ManagementAuditFingerprint;
import dev.mars.peegeeq.cache.api.management.ManagementAuditIntent;
import dev.mars.peegeeq.cache.api.management.ManagementAuditOutcome;
import dev.mars.peegeeq.cache.api.management.ManagementAuditReservation;
import dev.mars.peegeeq.cache.api.management.ManagementAuditTerminalOutcome;
import dev.mars.peegeeq.cache.api.management.ManagementResourceType;
import io.vertx.core.Future;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DurableManagementAuditSinkTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void durableReservationPrecedesAcceptanceAndReservesOneOutcomeSlot() throws Exception {
        Path journal = temporaryDirectory.resolve("management-audit.jsonl");
        DurableManagementAuditSink sink = await(DurableManagementAuditSink.open(journal, 1));

        ManagementAuditReservation first = await(sink.reserveIntent(intent("event-1")));

        assertEquals(1, sink.pendingReservations());
        assertTrue(Files.readString(journal).contains("\"eventId\":\"event-1\""));
        Throwable saturated = failureOf(sink.reserveIntent(intent("event-2")));
        assertInstanceOf(ManagementAuditException.class, saturated);

        await(sink.complete(first, new ManagementAuditOutcome(
                ManagementAuditTerminalOutcome.SUCCEEDED, "ENTRY_SET", 7L)));
        assertEquals(0, sink.pendingReservations());

        await(sink.reserveIntent(intent("event-2")));
        await(sink.closeAsync());
    }

    @Test
    void terminalCompletionIsDurableIdempotentAndConflictSafe() throws Exception {
        Path journal = temporaryDirectory.resolve("idempotent-audit.jsonl");
        DurableManagementAuditSink sink = await(DurableManagementAuditSink.open(journal, 2));
        ManagementAuditReservation reservation = await(sink.reserveIntent(intent("event-idempotent")));
        ManagementAuditOutcome succeeded = new ManagementAuditOutcome(
                ManagementAuditTerminalOutcome.SUCCEEDED, "ENTRY_SET", 9L);

        await(sink.complete(reservation, succeeded));
        await(sink.complete(reservation, succeeded));

        Throwable conflict = failureOf(sink.complete(reservation, new ManagementAuditOutcome(
                ManagementAuditTerminalOutcome.FAILED, "DATABASE_UNAVAILABLE", null)));
        assertInstanceOf(ManagementAuditConflictException.class, conflict);
        String persisted = Files.readString(journal);
        assertEquals(1, persisted.lines().filter(line -> line.contains("\"recordType\":\"OUTCOME\""))
                .count());
        assertTrue(persisted.contains("\"code\":\"ENTRY_SET\""));
        await(sink.closeAsync());
    }

    @Test
    void restartRecoversIncompleteIntentAsUnknownBeforeAcceptingMutations() throws Exception {
        Path journal = temporaryDirectory.resolve("recovery-audit.jsonl");
        DurableManagementAuditSink first = await(DurableManagementAuditSink.open(journal, 2));
        ManagementAuditReservation interrupted = await(first.reserveIntent(intent("event-interrupted")));
        await(first.closeAsync());

        DurableManagementAuditSink restarted = await(DurableManagementAuditSink.open(journal, 2));

        assertTrue(restarted.isMutationReady());
        assertEquals(0, restarted.pendingReservations());
        String persisted = Files.readString(journal);
        assertTrue(persisted.contains("\"outcome\":\"UNKNOWN\""));
        assertTrue(persisted.contains("\"code\":\"INCOMPLETE_INTENT_RECOVERED\""));
        Throwable conflict = failureOf(restarted.complete(interrupted, new ManagementAuditOutcome(
                ManagementAuditTerminalOutcome.SUCCEEDED, "ENTRY_SET", 10L)));
        assertInstanceOf(ManagementAuditConflictException.class, conflict);

        await(restarted.reserveIntent(intent("event-after-recovery")));
        await(restarted.closeAsync());
        Throwable closed = failureOf(restarted.reserveIntent(intent("event-after-close")));
        assertInstanceOf(ManagementAuditException.class, closed);
        await(restarted.closeAsync());
    }

    @Test
    void terminalPersistenceFaultDropsReadinessUntilUnknownRecoveryIsDurable() throws Exception {
        Path journal = temporaryDirectory.resolve("fault-audit.jsonl");
        AtomicBoolean failOutcomes = new AtomicBoolean();
        DurableManagementAuditSink sink = await(DurableManagementAuditSink.open(
                journal,
                2,
                recordType -> {
                    if (recordType == AuditJournalRecordType.OUTCOME && failOutcomes.get()) {
                        throw new java.io.IOException("injected outcome persistence fault");
                    }
                }));
        ManagementAuditReservation reservation = await(sink.reserveIntent(intent("event-uncertain")));
        failOutcomes.set(true);

        Throwable unavailable = failureOf(sink.complete(reservation, new ManagementAuditOutcome(
                ManagementAuditTerminalOutcome.SUCCEEDED, "ENTRY_SET", 12L)));

        assertInstanceOf(ManagementAuditException.class, unavailable);
        assertTrue(!sink.isMutationReady());
        assertInstanceOf(ManagementAuditException.class,
                failureOf(sink.reserveIntent(intent("event-must-be-blocked"))));

        failOutcomes.set(false);
        await(sink.recover());
        assertTrue(sink.isMutationReady());
        assertEquals(0, sink.pendingReservations());
        assertTrue(Files.readString(journal).contains("\"code\":\"AUDIT_OUTCOME_UNAVAILABLE\""));
        await(sink.reserveIntent(intent("event-after-fault-recovery")));
        await(sink.closeAsync());
    }

    @Test
    void optionalTelemetryFailureCannotReplaceDurableSecurityAudit() throws Exception {
        Path journal = temporaryDirectory.resolve("telemetry-isolation-audit.jsonl");
        ManagementAuditTelemetry failingTelemetry = new ManagementAuditTelemetry() {
            @Override
            public void reservationAccepted(int pendingReservations, int capacity) {
                throw new IllegalStateException("telemetry exporter unavailable");
            }

            @Override
            public void outcomePersisted(
                    ManagementAuditTerminalOutcome outcome,
                    int pendingReservations) {
                throw new IllegalStateException("telemetry exporter unavailable");
            }

            @Override
            public void readinessChanged(boolean mutationReady) {
                throw new IllegalStateException("telemetry exporter unavailable");
            }
        };
        DurableManagementAuditSink sink = await(DurableManagementAuditSink.openWithTelemetry(
                journal, 2, failingTelemetry));

        ManagementAuditReservation reservation = await(sink.reserveIntent(intent("event-telemetry")));
        await(sink.complete(reservation, new ManagementAuditOutcome(
                ManagementAuditTerminalOutcome.REJECTED, "VERSION_MISMATCH", null)));

        assertTrue(sink.isMutationReady());
        String persisted = Files.readString(journal);
        assertTrue(persisted.contains("\"eventId\":\"event-telemetry\""));
        assertTrue(persisted.contains("\"code\":\"VERSION_MISMATCH\""));
        await(sink.closeAsync());
    }

    @Test
    void telemetryReportsRejectedRecoveryAndPersistenceFailureSignals() throws Exception {
        Path journal = temporaryDirectory.resolve("audit-pressure.jsonl");
        RecordingAuditTelemetry telemetry = new RecordingAuditTelemetry();
        DurableManagementAuditSink first = await(DurableManagementAuditSink.openWithTelemetry(
                journal, 1, telemetry));
        ManagementAuditReservation pending = await(first.reserveIntent(intent("event-pending")));

        assertInstanceOf(ManagementAuditException.class,
                failureOf(first.reserveIntent(intent("event-rejected"))));
        assertEquals(1, telemetry.rejectedReservations.get());
        await(first.closeAsync());

        DurableManagementAuditSink restarted = await(DurableManagementAuditSink.openWithTelemetry(
                journal, 1, telemetry));
        assertEquals(1, telemetry.recoveredIntents.get());

        AtomicBoolean failOutcomes = new AtomicBoolean(true);
        await(restarted.closeAsync());
        DurableManagementAuditSink failing = await(DurableManagementAuditSink.open(
                temporaryDirectory.resolve("audit-persistence.jsonl"),
                1,
                recordType -> {
                    if (recordType == AuditJournalRecordType.OUTCOME && failOutcomes.get()) {
                        throw new java.io.IOException("injected outcome fault");
                    }
                },
                telemetry));
        ManagementAuditReservation reservation = await(failing.reserveIntent(intent("event-failure")));
        assertInstanceOf(ManagementAuditException.class, failureOf(failing.complete(
                reservation,
                new ManagementAuditOutcome(
                        ManagementAuditTerminalOutcome.SUCCEEDED, "ENTRY_SET", 1L))));
        assertEquals(1, telemetry.outcomePersistenceFailures.get());
        failOutcomes.set(false);
        await(failing.recover());
        await(failing.closeAsync());
    }

    private static final class RecordingAuditTelemetry implements ManagementAuditTelemetry {
        private final AtomicInteger rejectedReservations = new AtomicInteger();
        private final AtomicInteger recoveredIntents = new AtomicInteger();
        private final AtomicInteger outcomePersistenceFailures = new AtomicInteger();

        @Override
        public void reservationRejected() {
            rejectedReservations.incrementAndGet();
        }

        @Override
        public void incompleteIntentsRecovered(int count) {
            recoveredIntents.addAndGet(count);
        }

        @Override
        public void persistenceFailed(AuditPersistenceOperation operation) {
            if (operation == AuditPersistenceOperation.OUTCOME) {
                outcomePersistenceFailures.incrementAndGet();
            }
        }
    }

    private static ManagementAuditIntent intent(String eventId) {
        return new ManagementAuditIntent(
                eventId,
                Instant.parse("2026-08-22T12:00:00Z"),
                "operator@example.test",
                Set.of("operator"),
                ManagementAuditAction.SET_ENTRY,
                "setup-a",
                ManagementResourceType.ENTRY,
                Map.of("key", new ManagementAuditFingerprint(
                        "HMAC-SHA-256/128/V1", "audit-v1", "a".repeat(22))),
                6L,
                "approved operation",
                "127.0.0.1",
                "correlation-1");
    }

    private static <T> T await(Future<T> future) throws Exception {
        return future.toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
    }

    private static Throwable failureOf(Future<?> future) throws Exception {
        try {
            await(future);
            throw new AssertionError("Operation unexpectedly succeeded");
        } catch (java.util.concurrent.ExecutionException failure) {
            return failure.getCause();
        }
    }
}
