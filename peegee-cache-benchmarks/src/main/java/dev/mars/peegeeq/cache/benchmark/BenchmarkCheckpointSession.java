package dev.mars.peegeeq.cache.benchmark;

import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.WorkerExecutor;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.RejectedExecutionException;

import static dev.mars.peegeeq.cache.benchmark.BenchmarkRunEvidence.*;

/**
 * Count/time-based publication of already-recorded intervals. One active batch plus one bounded
 * staging batch; admission futures mean publication by atomic replacement, not acceptance or fsync.
 * This does not schedule recorder rollover or workload arrivals. Call finish/stop and await it before
 * closing the caller-owned worker/Vert.x. Failed sessions retain the last published file unchanged.
 */
public final class BenchmarkCheckpointSession {
    private record End(Status status, Validity validity, String detail) { }

    private final Vertx vertx;
    private final BenchmarkCheckpointWriter writer;
    private final BenchmarkCheckpointPipeline pipeline;
    private final BenchmarkRunEvidence manifest;
    private final BenchmarkCheckpointWriter.Limits limits;
    private final long delayMillis;
    private final Clock clock;
    private final ArrayList<Measurement> measurements = new ArrayList<>();
    private final ArrayList<Diagnostic> diagnostics = new ArrayList<>();
    private final ArrayList<Promise<Void>> acknowledgements = new ArrayList<>();
    private final Promise<Void> completion = Promise.promise();
    private List<Promise<Void>> active = List.of();
    private boolean writing;
    private boolean due;
    private long timer = -1;
    private End end;
    private Throwable failure;

    private BenchmarkCheckpointSession(Vertx vertx, BenchmarkCheckpointWriter writer, WorkerExecutor worker,
                                       BenchmarkRunEvidence manifest, BenchmarkCheckpointWriter.Limits limits,
                                       long delayMillis, Clock clock) {
        this.vertx = vertx;
        this.writer = writer;
        this.pipeline = new BenchmarkCheckpointPipeline(worker, writer, 1, limits.maximumBatchBytes());
        this.manifest = manifest;
        this.limits = limits;
        this.delayMillis = delayMillis;
        this.clock = clock;
    }

    /** Initial manifest must contain no measurements/diagnostics and have RUNNING status. */
    public static Future<BenchmarkCheckpointSession> open(Vertx vertx, WorkerExecutor worker, Path directory,
            BenchmarkRunEvidence initial, BenchmarkCheckpointWriter.Limits limits, Duration maximumDelay, Clock clock) {
        try {
            Objects.requireNonNull(vertx, "vertx");
            Objects.requireNonNull(worker, "worker");
            Objects.requireNonNull(clock, "clock");
            Objects.requireNonNull(limits, "limits");
            long nanos = maximumDelay.toNanos();
            if (nanos < 1_000_000 || nanos % 1_000_000 != 0) {
                throw new IllegalArgumentException("Checkpoint delay must be positive whole milliseconds");
            }
            if (initial.status() != Status.RUNNING || !initial.measurements().isEmpty() || !initial.diagnostics().isEmpty()) {
                throw new IllegalArgumentException("Session requires an empty RUNNING manifest");
            }
            var environment = new HashMap<>(initial.environment());
            if (environment.keySet().stream().anyMatch(key -> key.startsWith("checkpoint."))) {
                throw new IllegalArgumentException("checkpoint.* environment fields are reserved for resolved policy");
            }
            environment.put("checkpoint.maximumDelayMillis", Long.toString(nanos / 1_000_000));
            environment.put("checkpoint.maximumBatchItems", Integer.toString(limits.maximumBatchItems()));
            environment.put("checkpoint.maximumBatchBytes", Integer.toString(limits.maximumBatchBytes()));
            environment.put("checkpoint.maximumScenarios", Integer.toString(limits.maximumScenarios()));
            environment.put("checkpoint.buffering", "one active batch plus one staging batch");
            var manifest = new BenchmarkRunEvidence(initial.executionId(), initial.plan(), initial.startedAt(), initial.updatedAt(),
                    initial.status(), initial.validity(), initial.detail(), environment, List.of(), List.of());
            return worker.executeBlocking(() -> BenchmarkCheckpointWriter.create(directory, manifest, limits))
                    .map(writer -> new BenchmarkCheckpointSession(vertx, writer, worker, manifest, limits, nanos / 1_000_000, clock));
        } catch (RuntimeException invalid) { return Future.failedFuture(invalid); }
    }

    public Path path() { return writer.path(); }
    public synchronized int pendingItems() { return active.size() + acknowledgements.size(); }
    public Future<Void> completion() { return completion.future(); }

    public synchronized Future<Void> append(Measurement measurement) { return admit(measurement, null); }
    public synchronized Future<Void> diagnostic(Diagnostic diagnostic) { return admit(null, diagnostic); }

    private Future<Void> admit(Measurement measurement, Diagnostic diagnostic) {
        if (failure != null) return Future.failedFuture(failure);
        if (end != null) return Future.failedFuture(new IllegalStateException("Session is finalising"));
        if (acknowledgements.size() >= limits.maximumBatchItems()) {
            return Future.failedFuture(new RejectedExecutionException("Checkpoint staging batch is full"));
        }
        if (measurement == null && diagnostic == null) return Future.failedFuture(new NullPointerException("item"));
        if (measurement != null) measurements.add(measurement); else diagnostics.add(diagnostic);
        try { writer.prepare(evidence(null)); }
        catch (RuntimeException invalid) {
            if (measurement != null) measurements.removeLast(); else diagnostics.removeLast();
            return Future.failedFuture(invalid);
        }
        var acknowledgement = Promise.<Void>promise();
        acknowledgements.add(acknowledgement);
        try {
            if (timer == -1 && !due) timer = vertx.setTimer(delayMillis, this::timerFired);
            flushIfReady();
        } catch (RuntimeException problem) { fail(problem); }
        return acknowledgement.future();
    }

    public Future<Void> stop(String reason) { return finish(Status.STOPPED, Validity.UNASSESSED, reason); }

    /** Identical terminal requests share completion; contradictory requests fail without changing it. */
    public synchronized Future<Void> finish(Status status, Validity validity, String detail) {
        if (failure != null) return Future.failedFuture(failure);
        var requested = new End(status, validity, detail);
        if (end != null) return end.equals(requested) ? completion.future()
                : Future.failedFuture(new IllegalStateException("Different finalisation already requested"));
        try {
            if (status == Status.RUNNING) throw new IllegalArgumentException("Final status cannot be RUNNING");
            // Ensure the terminal metadata itself fits before closing admission.
            writer.prepare(new BenchmarkRunEvidence(manifest.executionId(), manifest.plan(), manifest.startedAt(), clock.instant(),
                    status, validity, detail, manifest.environment(), List.of(), List.of()));
        } catch (RuntimeException invalid) { return Future.failedFuture(invalid); }
        end = requested;
        cancelTimer();
        flushIfReady();
        return completion.future();
    }

    private synchronized void timerFired(long firedTimer) {
        if (timer != firedTimer) return;
        timer = -1;
        if (failure != null || end != null) return;
        due = true;
        flushIfReady();
    }

    private void flushIfReady() {
        if (writing || failure != null) return;
        if (end == null && (acknowledgements.isEmpty() || (!due && acknowledgements.size() < limits.maximumBatchItems()))) return;
        BenchmarkRunEvidence batch;
        try {
            batch = evidence(end);
            try { writer.prepare(batch); }
            catch (IllegalArgumentException tooLarge) {
                if (end == null || acknowledgements.isEmpty()) throw tooLarge;
                // Preserve accepted samples when a terminal reason only fits in a separate publication.
                batch = evidence(null);
                writer.prepare(batch);
            }
        } catch (RuntimeException invalid) { fail(invalid); return; }
        boolean terminal = batch.status() != Status.RUNNING;
        var published = List.copyOf(acknowledgements);
        active = published;
        writing = true;
        measurements.clear(); diagnostics.clear(); acknowledgements.clear();
        due = false;
        cancelTimer();
        pipeline.submit(batch).onComplete(result -> written(published, terminal, result.cause()));
    }

    private synchronized void written(List<Promise<Void>> published, boolean terminal, Throwable cause) {
        if (failure != null) return;
        if (cause != null) { fail(cause); return; }
        active = List.of();
        writing = false;
        published.forEach(Promise::complete);
        if (terminal) pipeline.close().onComplete(completion);
        else flushIfReady();
    }

    private BenchmarkRunEvidence evidence(End requested) {
        return new BenchmarkRunEvidence(manifest.executionId(), manifest.plan(), manifest.startedAt(), clock.instant(),
                requested == null ? Status.RUNNING : requested.status(),
                requested == null ? manifest.validity() : requested.validity(),
                requested == null ? manifest.detail() : requested.detail(), manifest.environment(), measurements, diagnostics);
    }

    private void fail(Throwable cause) {
        if (failure != null) return;
        failure = cause;
        cancelTimer();
        var failed = new ArrayList<>(active);
        failed.addAll(acknowledgements);
        active = List.of(); writing = false;
        measurements.clear(); diagnostics.clear(); acknowledgements.clear();
        failed.forEach(promise -> promise.tryFail(cause));
        pipeline.close().onComplete(result -> {
            if (result.failed() && result.cause() != cause) cause.addSuppressed(result.cause());
            completion.tryFail(cause);
        });
    }

    private void cancelTimer() {
        if (timer != -1) { vertx.cancelTimer(timer); timer = -1; }
    }
}
