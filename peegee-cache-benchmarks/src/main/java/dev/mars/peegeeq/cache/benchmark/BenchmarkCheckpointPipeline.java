package dev.mars.peegeeq.cache.benchmark;

import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.WorkerExecutor;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Objects;
import java.util.concurrent.RejectedExecutionException;

/**
 * Bounded, fail-stop checkpoint admission. Capacity includes the currently writing batch.
 * One worker drain is submitted at a time; saturation never creates an unbounded worker queue.
 * A successful future means atomic publication, not merely admission. Rejection leaves ownership
 * with the producer, which must retry or stop explicitly; no samples are silently dropped/coalesced.
 * close() drains but does not invent terminal run status or close the caller-owned worker executor.
 */
public final class BenchmarkCheckpointPipeline {
    private record Pending(BenchmarkCheckpointWriter.Prepared batch, Promise<Void> promise) { }

    private final WorkerExecutor worker;
    private final BenchmarkCheckpointWriter writer;
    private final int maximumPending;
    private final long maximumPendingBytes;
    private final ArrayDeque<Pending> pending = new ArrayDeque<>();
    private final Promise<Void> closed = Promise.promise();
    private long pendingBytes;
    private long rejected;
    private boolean draining;
    private boolean closing;
    private Throwable failure;

    public BenchmarkCheckpointPipeline(WorkerExecutor worker, BenchmarkCheckpointWriter writer,
                                       int maximumPending, long maximumPendingBytes) {
        this.worker = Objects.requireNonNull(worker, "worker");
        this.writer = Objects.requireNonNull(writer, "writer");
        if (maximumPending <= 0 || maximumPendingBytes <= 0) throw new IllegalArgumentException("Queue limits must be positive");
        this.maximumPending = maximumPending;
        this.maximumPendingBytes = maximumPendingBytes;
    }

    /** Encoding/validation occurs before admission. Callers must also bound their input construction. */
    public Future<Void> submit(BenchmarkRunEvidence delta) {
        Pending accepted;
        boolean start;
        synchronized (this) {
            if (failure != null) return reject(failure);
            if (closing) return reject(new IllegalStateException("Checkpoint pipeline is closing"));
            if (pending.size() >= maximumPending) return reject(new RejectedExecutionException("Checkpoint queue is full"));
            BenchmarkCheckpointWriter.Prepared batch;
            try { batch = writer.prepare(delta); }
            catch (RuntimeException invalid) { return reject(invalid); }
            if (batch.bytes > maximumPendingBytes - pendingBytes) {
                return reject(new RejectedExecutionException("Checkpoint byte capacity exceeded"));
            }
            accepted = new Pending(batch, Promise.promise());
            pending.addLast(accepted);
            pendingBytes += batch.bytes;
            if (batch.terminal) closing = true;
            start = !draining;
            draining = true;
        }
        if (start) {
            try { worker.executeBlocking(this::drain, false).onFailure(this::failPending); }
            catch (RuntimeException rejectedSubmission) { failPending(rejectedSubmission); }
        }
        return accepted.promise().future();
    }

    public synchronized int pendingCount() { return pending.size(); }
    public synchronized long pendingBytes() { return pendingBytes; }
    public synchronized long rejectedCount() { return rejected; }

    public synchronized Future<Void> close() {
        closing = true;
        if (!draining && failure == null) closed.tryComplete();
        return closed.future();
    }

    private Future<Void> reject(Throwable cause) {
        rejected++;
        return Future.failedFuture(cause);
    }

    private Void drain() throws Exception {
        while (true) {
            Pending current;
            synchronized (this) {
                current = pending.peekFirst();
                if (current == null) {
                    draining = false;
                    if (closing) closed.tryComplete();
                    return null;
                }
            }
            writer.append(current.batch());
            synchronized (this) {
                pending.removeFirst();
                pendingBytes -= current.batch().bytes;
            }
            current.promise().complete();
        }
    }

    private void failPending(Throwable cause) {
        ArrayList<Pending> failed;
        synchronized (this) {
            if (failure != null) return;
            failure = cause;
            closing = true;
            draining = false;
            failed = new ArrayList<>(pending);
            pending.clear();
            pendingBytes = 0;
        }
        failed.forEach(item -> item.promise().tryFail(cause));
        closed.tryFail(cause);
    }
}
