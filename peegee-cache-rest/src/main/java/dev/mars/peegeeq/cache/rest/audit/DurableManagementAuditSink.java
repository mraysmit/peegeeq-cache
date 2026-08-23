package dev.mars.peegeeq.cache.rest.audit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.mars.peegeeq.cache.api.management.ManagementAuditConflictException;
import dev.mars.peegeeq.cache.api.management.ManagementAuditException;
import dev.mars.peegeeq.cache.api.management.ManagementAuditFingerprint;
import dev.mars.peegeeq.cache.api.management.ManagementAuditIntent;
import dev.mars.peegeeq.cache.api.management.ManagementAuditOutcome;
import dev.mars.peegeeq.cache.api.management.ManagementAuditReservation;
import dev.mars.peegeeq.cache.api.management.ManagementAuditSink;
import dev.mars.peegeeq.cache.api.management.ManagementAuditTerminalOutcome;
import io.vertx.core.Future;
import io.vertx.core.Promise;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Append-only, fsync-backed security audit journal. All journal access is serialized
 * on one private worker so callers never perform file I/O on a Vert.x event loop.
 */
public final class DurableManagementAuditSink implements ManagementAuditSink {

    private enum State {
        STARTING,
        READY,
        CLOSING,
        CLOSED,
        OUTCOME_UNAVAILABLE
    }

    private static final ObjectMapper JSON = new ObjectMapper();

    private final Path journalPath;
    private final int reservationCapacity;
    private final AuditJournalWriteInterceptor writeInterceptor;
    private final ManagementAuditTelemetry telemetry;
    private final String generation = UUID.randomUUID().toString();
    private final ExecutorService worker = Executors.newSingleThreadExecutor(runnable ->
            Thread.ofPlatform().name("peegeeq-management-audit").daemon(true).unstarted(runnable));
    private final Map<String, ManagementAuditReservation> pending = new LinkedHashMap<>();
    private final Map<String, CompletedReservation> completed = new LinkedHashMap<>();
    private final AtomicInteger pendingCount = new AtomicInteger();

    private volatile State state = State.STARTING;
    private FileChannel channel;

    private DurableManagementAuditSink(
            Path journalPath,
            int reservationCapacity,
            AuditJournalWriteInterceptor writeInterceptor,
            ManagementAuditTelemetry telemetry) {
        this.journalPath = journalPath;
        this.reservationCapacity = reservationCapacity;
        this.writeInterceptor = writeInterceptor;
        this.telemetry = telemetry;
    }

    /** Opens or creates a journal and makes it ready only after initialization succeeds. */
    public static Future<DurableManagementAuditSink> open(Path journalPath, int reservationCapacity) {
        return openInternal(
                journalPath,
                reservationCapacity,
                ignored -> { },
                ManagementAuditTelemetry.NOOP);
    }

    static Future<DurableManagementAuditSink> open(
            Path journalPath,
            int reservationCapacity,
            AuditJournalWriteInterceptor writeInterceptor) {
        return openInternal(
                journalPath,
                reservationCapacity,
                writeInterceptor,
                ManagementAuditTelemetry.NOOP);
    }

    static Future<DurableManagementAuditSink> open(
            Path journalPath,
            int reservationCapacity,
            AuditJournalWriteInterceptor writeInterceptor,
            ManagementAuditTelemetry telemetry) {
        return openInternal(journalPath, reservationCapacity, writeInterceptor, telemetry);
    }

    /** Opens a durable sink with an optional, failure-isolated telemetry observer. */
    public static Future<DurableManagementAuditSink> openWithTelemetry(
            Path journalPath,
            int reservationCapacity,
            ManagementAuditTelemetry telemetry) {
        return openInternal(
                journalPath,
                reservationCapacity,
                ignored -> { },
                telemetry);
    }

    private static Future<DurableManagementAuditSink> openInternal(
            Path journalPath,
            int reservationCapacity,
            AuditJournalWriteInterceptor writeInterceptor,
            ManagementAuditTelemetry telemetry) {
        Objects.requireNonNull(journalPath, "journalPath");
        Objects.requireNonNull(writeInterceptor, "writeInterceptor");
        Objects.requireNonNull(telemetry, "telemetry");
        if (reservationCapacity < 1) {
            return Future.failedFuture(new IllegalArgumentException(
                    "reservationCapacity must be positive"));
        }
        DurableManagementAuditSink sink = new DurableManagementAuditSink(
                journalPath.toAbsolutePath().normalize(),
                reservationCapacity,
                writeInterceptor,
                telemetry);
        Future<DurableManagementAuditSink> opened = sink.submit(() -> {
            Path parent = sink.journalPath.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            sink.channel = FileChannel.open(
                    sink.journalPath,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.READ,
                    StandardOpenOption.WRITE);
            sink.recoverJournal();
            sink.channel.position(sink.channel.size());
            int recovered;
            try {
                recovered = sink.recoverIncompleteIntents("INCOMPLETE_INTENT_RECOVERED");
            } catch (IOException failure) {
                sink.telemetry(observer -> observer.persistenceFailed(
                        AuditPersistenceOperation.RECOVERY));
                throw failure;
            }
            if (recovered > 0) {
                int recoveredCount = recovered;
                sink.telemetry(observer -> observer.incompleteIntentsRecovered(recoveredCount));
            }
            sink.state = State.READY;
            sink.telemetry(telemetryObserver -> telemetryObserver.readinessChanged(true));
            return sink;
        }, "Management audit journal could not be opened");
        opened.onFailure(ignored -> sink.worker.shutdown());
        return opened;
    }

    @Override
    public Future<ManagementAuditReservation> reserveIntent(ManagementAuditIntent intent) {
        Objects.requireNonNull(intent, "intent");
        return submit(() -> {
            if (state != State.READY) {
                telemetry(ManagementAuditTelemetry::reservationRejected);
            }
            requireReady("reserve an audit intent");
            if (pending.size() >= reservationCapacity) {
                telemetry(ManagementAuditTelemetry::reservationRejected);
                throw new ManagementAuditException("Management audit reservation capacity is exhausted");
            }
            ManagementAuditReservation reservation = new ManagementAuditReservation(
                    UUID.randomUUID().toString(), intent.eventId(), generation);
            try {
                append(intentRecord(reservation, intent));
            } catch (IOException failure) {
                telemetry(ManagementAuditTelemetry::reservationRejected);
                telemetry(observer -> observer.persistenceFailed(AuditPersistenceOperation.INTENT));
                throw failure;
            }
            pending.put(reservation.reservationId(), reservation);
            pendingCount.set(pending.size());
            telemetry(observer -> observer.reservationAccepted(
                    pending.size(), reservationCapacity));
            return reservation;
        }, "Management audit intent reservation failed");
    }

    @Override
    public Future<Void> complete(
            ManagementAuditReservation reservation,
            ManagementAuditOutcome outcome) {
        Objects.requireNonNull(reservation, "reservation");
        Objects.requireNonNull(outcome, "outcome");
        return submit(() -> {
            requireReady("complete an audit outcome");
            CompletedReservation terminal = completed.get(reservation.reservationId());
            if (terminal != null) {
                if (!terminal.reservation().equals(reservation)
                        || !terminal.outcome().equals(outcome)) {
                    throw new ManagementAuditConflictException(
                            "Management audit reservation already has a different terminal outcome");
                }
                return null;
            }
            ManagementAuditReservation existing = pending.get(reservation.reservationId());
            if (existing == null || !existing.equals(reservation)) {
                throw new ManagementAuditException("Unknown management audit reservation");
            }
            try {
                append(outcomeRecord(reservation, outcome));
            } catch (IOException failure) {
                state = State.OUTCOME_UNAVAILABLE;
                telemetry(observer -> observer.readinessChanged(false));
                telemetry(observer -> observer.persistenceFailed(AuditPersistenceOperation.OUTCOME));
                throw failure;
            }
            pending.remove(reservation.reservationId());
            completed.put(reservation.reservationId(), new CompletedReservation(reservation, outcome));
            pendingCount.set(pending.size());
            telemetry(observer -> observer.outcomePersisted(outcome.outcome(), pending.size()));
            return null;
        }, "Management audit terminal outcome is unavailable");
    }

    /** Number of accepted intents that do not yet have a durable terminal outcome. */
    public int pendingReservations() {
        return pendingCount.get();
    }

    /** Whether reveal and mutation work may currently reserve authoritative audit intent. */
    @Override
    public boolean isMutationReady() {
        return state == State.READY;
    }

    /**
     * Resolves every uncertain in-memory reservation as UNKNOWN and restores
     * mutation readiness only after those outcomes are fsync'd.
     */
    public Future<Void> recover() {
        return submit(() -> {
            if (state != State.OUTCOME_UNAVAILABLE) {
                throw new ManagementAuditException(
                        "Management audit recovery requires an unavailable outcome state");
            }
            int recovered;
            try {
                recovered = recoverIncompleteIntents("AUDIT_OUTCOME_UNAVAILABLE");
            } catch (IOException failure) {
                telemetry(observer -> observer.persistenceFailed(AuditPersistenceOperation.RECOVERY));
                throw failure;
            }
            if (recovered > 0) {
                int recoveredCount = recovered;
                telemetry(observer -> observer.incompleteIntentsRecovered(recoveredCount));
            }
            state = State.READY;
            telemetry(observer -> observer.readinessChanged(true));
            return null;
        }, "Management audit outcome recovery failed");
    }

    /** Stops accepting reservations, drains preceding work, fsyncs, and closes the journal. */
    public Future<Void> closeAsync() {
        synchronized (this) {
            if (state == State.CLOSED) {
                return Future.succeededFuture();
            }
            if (state == State.CLOSING) {
                return Future.failedFuture(new ManagementAuditException(
                        "Management audit journal is already closing"));
            }
            state = State.CLOSING;
            telemetry(observer -> observer.readinessChanged(false));
        }
        Future<Void> close = submit(() -> {
            if (channel != null) {
                channel.force(true);
                channel.close();
            }
            state = State.CLOSED;
            return null;
        }, "Management audit journal could not be closed");
        close.onComplete(ignored -> worker.shutdown());
        return close;
    }

    private void requireReady(String action) {
        if (state != State.READY) {
            throw new ManagementAuditException(
                    "Management audit journal is not ready to " + action);
        }
    }

    private void telemetry(TelemetryCall call) {
        try {
            call.invoke(telemetry);
        } catch (RuntimeException ignored) {
            // Optional telemetry must never replace or invalidate durable security audit.
        }
    }

    private void append(ObjectNode record) throws IOException {
        writeInterceptor.beforeWrite(AuditJournalRecordType.valueOf(
                requiredText(record, "recordType")));
        byte[] json = JSON.writeValueAsBytes(record);
        ByteBuffer bytes = ByteBuffer.allocate(json.length + 1);
        bytes.put(json).put((byte) '\n').flip();
        while (bytes.hasRemaining()) {
            channel.write(bytes);
        }
        channel.force(true);
    }

    private void recoverJournal() throws IOException {
        try (BufferedReader reader = Files.newBufferedReader(journalPath)) {
            String line;
            long lineNumber = 0;
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                if (line.isBlank()) {
                    continue;
                }
                try {
                    recoverRecord(JSON.readTree(line));
                } catch (RuntimeException failure) {
                    throw new IOException("Invalid management audit record at line " + lineNumber, failure);
                }
            }
        }
        pendingCount.set(pending.size());
    }

    private void recoverRecord(JsonNode record) {
        String recordType = requiredText(record, "recordType");
        ManagementAuditReservation reservation = new ManagementAuditReservation(
                requiredText(record, "reservationId"),
                requiredText(record, "eventId"),
                requiredText(record, "sinkGeneration"));
        if ("INTENT".equals(recordType)) {
            if (pending.containsKey(reservation.reservationId())
                    || completed.containsKey(reservation.reservationId())) {
                throw new IllegalArgumentException("Duplicate audit reservation intent");
            }
            pending.put(reservation.reservationId(), reservation);
            return;
        }
        if (!"OUTCOME".equals(recordType)) {
            throw new IllegalArgumentException("Unknown audit record type");
        }
        ManagementAuditOutcome outcome = new ManagementAuditOutcome(
                ManagementAuditTerminalOutcome.valueOf(requiredText(record, "outcome")),
                requiredText(record, "code"),
                record.path("resultingVersion").isNumber()
                        ? record.get("resultingVersion").longValue() : null);
        ManagementAuditReservation accepted = pending.remove(reservation.reservationId());
        CompletedReservation prior = completed.get(reservation.reservationId());
        if (accepted == null && prior == null) {
            throw new IllegalArgumentException("Outcome has no matching intent");
        }
        if (accepted != null && !accepted.equals(reservation)) {
            throw new IllegalArgumentException("Outcome reservation does not match intent");
        }
        CompletedReservation recovered = new CompletedReservation(reservation, outcome);
        if (prior != null && !prior.equals(recovered)) {
            throw new IllegalArgumentException("Conflicting durable audit outcomes");
        }
        completed.put(reservation.reservationId(), recovered);
    }

    private int recoverIncompleteIntents(String outcomeCode) throws IOException {
        int recovered = 0;
        for (ManagementAuditReservation reservation : new ArrayList<>(pending.values())) {
            ManagementAuditOutcome unknown = new ManagementAuditOutcome(
                    ManagementAuditTerminalOutcome.UNKNOWN,
                    outcomeCode,
                    null);
            append(outcomeRecord(reservation, unknown));
            pending.remove(reservation.reservationId());
            completed.put(reservation.reservationId(),
                    new CompletedReservation(reservation, unknown));
            recovered++;
        }
        pendingCount.set(0);
        return recovered;
    }

    private static String requiredText(JsonNode record, String fieldName) {
        JsonNode value = record.get(fieldName);
        if (value == null || !value.isTextual() || value.textValue().isBlank()) {
            throw new IllegalArgumentException("Missing audit field " + fieldName);
        }
        return value.textValue();
    }

    private static ObjectNode intentRecord(
            ManagementAuditReservation reservation,
            ManagementAuditIntent intent) {
        ObjectNode record = JSON.createObjectNode();
        record.put("recordType", "INTENT");
        record.put("reservationId", reservation.reservationId());
        record.put("eventId", reservation.eventId());
        record.put("sinkGeneration", reservation.sinkGeneration());
        record.put("occurredAt", intent.occurredAt().toString());
        record.put("actor", intent.actor());
        var roles = record.putArray("roles");
        intent.roles().stream().sorted().forEach(roles::add);
        record.put("action", intent.action().name());
        record.put("setupId", intent.setupId());
        record.put("resourceType", intent.resourceType().name());
        ObjectNode fingerprints = record.putObject("identifierFingerprints");
        intent.identifierFingerprints().entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> putFingerprint(fingerprints, entry.getKey(), entry.getValue()));
        if (intent.expectedVersion() == null) {
            record.putNull("expectedVersion");
        } else {
            record.put("expectedVersion", intent.expectedVersion());
        }
        if (intent.reason() == null) {
            record.putNull("reason");
        } else {
            record.put("reason", intent.reason());
        }
        record.put("sourceAddress", intent.sourceAddress());
        record.put("correlationId", intent.correlationId());
        return record;
    }

    private static void putFingerprint(
            ObjectNode fingerprints,
            String name,
            ManagementAuditFingerprint fingerprint) {
        ObjectNode value = fingerprints.putObject(name);
        value.put("algorithmVersion", fingerprint.algorithmVersion());
        value.put("keyId", fingerprint.keyId());
        value.put("digest", fingerprint.digest());
    }

    private static ObjectNode outcomeRecord(
            ManagementAuditReservation reservation,
            ManagementAuditOutcome outcome) {
        ObjectNode record = JSON.createObjectNode();
        record.put("recordType", "OUTCOME");
        record.put("reservationId", reservation.reservationId());
        record.put("eventId", reservation.eventId());
        record.put("sinkGeneration", reservation.sinkGeneration());
        record.put("outcome", outcome.outcome().name());
        record.put("code", outcome.code());
        if (outcome.resultingVersion() == null) {
            record.putNull("resultingVersion");
        } else {
            record.put("resultingVersion", outcome.resultingVersion());
        }
        return record;
    }

    private <T> Future<T> submit(IoOperation<T> operation, String failureMessage) {
        Promise<T> promise = Promise.promise();
        try {
            worker.execute(() -> {
                try {
                    promise.complete(operation.run());
                } catch (ManagementAuditException failure) {
                    promise.fail(failure);
                } catch (Exception failure) {
                    promise.fail(new ManagementAuditException(failureMessage, failure));
                }
            });
        } catch (RuntimeException failure) {
            promise.fail(new ManagementAuditException(failureMessage, failure));
        }
        return promise.future();
    }

    @FunctionalInterface
    private interface IoOperation<T> {
        T run() throws Exception;
    }

    @FunctionalInterface
    private interface TelemetryCall {
        void invoke(ManagementAuditTelemetry telemetry);
    }

    private record CompletedReservation(
            ManagementAuditReservation reservation,
            ManagementAuditOutcome outcome) {
    }
}

enum AuditJournalRecordType {
    INTENT,
    OUTCOME
}

@FunctionalInterface
interface AuditJournalWriteInterceptor {
    void beforeWrite(AuditJournalRecordType recordType) throws IOException;
}
