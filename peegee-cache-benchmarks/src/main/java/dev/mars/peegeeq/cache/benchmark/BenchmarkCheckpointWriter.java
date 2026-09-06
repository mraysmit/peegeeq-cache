package dev.mars.peegeeq.cache.benchmark;

import io.vertx.core.json.JsonObject;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Single-owner incremental persistence. Each input contains NEW measurements/diagnostics only.
 * Copies retained JSON ranges through a fixed buffer; never loads prior history into the heap.
 * Call off event loops. Atomic replacement needs temporary disk space approximately equal to the run.
 * Atomic visibility is not an fsync/power-loss durability guarantee; reopening is not supported yet.
 */
public final class BenchmarkCheckpointWriter {
    public record Limits(int maximumBatchItems, int maximumBatchBytes, int maximumScenarios) {
        public Limits {
            if (maximumBatchItems <= 0 || maximumBatchBytes <= 0 || maximumScenarios <= 0) {
                throw new IllegalArgumentException("Checkpoint limits must be positive");
            }
        }
    }

    private record Tail(String unit, long endNanos, BenchmarkWorkCounts counts) { }
    private record Range(long offset, long length) { }

    // Prepared batches are private snapshots, inaccessible to callers after admission.
    static final class Prepared {
        final JsonObject metadata;
        final List<BenchmarkRunEvidence.Measurement> measurements;
        final byte[] samples;
        final byte[] diagnostics;
        final byte[] header;
        final int bytes;
        final Instant updatedAt;
        final boolean terminal;

        Prepared(BenchmarkRunEvidence batch, int maximumBytes) {
            checkInputSize(batch, maximumBytes);
            var json = batch.toJson();
            samples = arrayContents(json.getJsonArray("measurements").encode());
            diagnostics = arrayContents(json.getJsonArray("diagnostics").encode());
            json.remove("measurements");
            json.remove("diagnostics");
            metadata = json;
            String encoded = json.encode();
            header = utf8(encoded.substring(0, encoded.length() - 1) + ",\"measurements\":[");
            long size = (long) header.length + samples.length + diagnostics.length + 32;
            if (size > maximumBytes) throw new IllegalArgumentException("Checkpoint exceeds maximumBatchBytes");
            bytes = (int) size;
            measurements = batch.measurements();
            updatedAt = batch.updatedAt();
            terminal = batch.status() != BenchmarkRunEvidence.Status.RUNNING;
        }
    }

    private final Path path;
    private final Limits limits;
    private JsonObject identity;
    private Instant updatedAt;
    private boolean terminal;
    private byte[] digest;
    private Range samples = new Range(0, 0);
    private Range diagnostics = new Range(0, 0);
    private Map<String, Tail> tails = Map.of();

    private BenchmarkCheckpointWriter(Path path, Limits limits) { this.path = path; this.limits = limits; }

    public static BenchmarkCheckpointWriter create(Path directory, BenchmarkRunEvidence initial, Limits limits) throws IOException {
        Objects.requireNonNull(limits, "limits");
        Objects.requireNonNull(initial, "initial");
        Path root = directory.toAbsolutePath().normalize();
        var writer = new BenchmarkCheckpointWriter(root.resolve(initial.executionId() + ".json"), limits);
        Prepared prepared = writer.prepare(initial);
        Files.createDirectories(root);
        Files.createFile(writer.path);
        try {
            writer.append(prepared);
            return writer;
        } catch (IOException | RuntimeException failure) {
            try { Files.delete(writer.path); } catch (IOException cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
    }

    public Path path() { return path; }
    public synchronized int retainedScenarioCount() { return tails.size(); }

    Prepared prepare(BenchmarkRunEvidence batch) {
        Objects.requireNonNull(batch, "batch");
        if ((long) batch.measurements().size() + batch.diagnostics().size() > limits.maximumBatchItems()) {
            throw new IllegalArgumentException("Checkpoint exceeds maximumBatchItems");
        }
        return new Prepared(batch, limits.maximumBatchBytes());
    }

    public void append(BenchmarkRunEvidence batch) throws IOException { append(prepare(batch)); }

    synchronized void append(Prepared batch) throws IOException {
        if (terminal) throw new IllegalStateException("Finalised run cannot be checkpointed again");
        var nextIdentity = batch.metadata.copy();
        nextIdentity.remove("execution");
        nextIdentity.put("startedAtUtc", batch.metadata.getJsonObject("execution").getString("startedAtUtc"));
        if (identity != null && (!identity.equals(nextIdentity) || batch.updatedAt.isBefore(updatedAt))) {
            throw new IllegalArgumentException("Checkpoint changed manifest or regressed time");
        }
        var nextTails = new HashMap<>(tails);
        for (var measurement : batch.measurements) {
            var prior = nextTails.get(measurement.scenario());
            var interval = measurement.interval();
            if (prior != null && (!prior.unit().equals(measurement.operationUnit())
                    || prior.endNanos() != interval.startNanos() || !prior.counts().equals(interval.before()))) {
                throw new IllegalArgumentException("Scenario history must be contiguous and conserve counters");
            }
            nextTails.put(measurement.scenario(), new Tail(measurement.operationUnit(), interval.endNanos(), interval.after()));
            if (nextTails.size() > limits.maximumScenarios()) throw new IllegalArgumentException("Too many scenarios");
        }
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Evidence target is not a regular file: " + path);
        if (digest != null && !MessageDigest.isEqual(digest, fileDigest(path))) {
            throw new IOException("Evidence was modified outside this writer: " + path);
        }
        Path temporary = Files.createTempFile(path.getParent(), path.getFileName() + "-", ".tmp");
        try {
            Range nextSamples;
            Range nextDiagnostics;
            try (var input = FileChannel.open(path, StandardOpenOption.READ);
                 var output = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                write(output, batch.header);
                nextSamples = appendRange(input, output, samples, batch.samples);
                write(output, utf8("],\"diagnostics\":["));
                nextDiagnostics = appendRange(input, output, diagnostics, batch.diagnostics);
                write(output, utf8("]}\n"));
            }
            byte[] nextDigest = fileDigest(temporary);
            Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            // Publish state only after successful replacement. Failed writes may be explicitly retried.
            identity = nextIdentity;
            updatedAt = batch.updatedAt;
            terminal = batch.terminal;
            tails = nextTails;
            samples = nextSamples;
            diagnostics = nextDiagnostics;
            digest = nextDigest;
        } catch (IOException | RuntimeException failure) {
            try { Files.deleteIfExists(temporary); } catch (IOException cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
    }

    private static Range appendRange(FileChannel input, FileChannel output, Range old, byte[] added) throws IOException {
        long start = output.position();
        input.position(old.offset());
        long remaining = old.length();
        var buffer = ByteBuffer.allocate(8192);
        while (remaining > 0) {
            buffer.clear().limit((int) Math.min(buffer.capacity(), remaining));
            int read = input.read(buffer);
            if (read < 0) throw new IOException("Truncated evidence history");
            remaining -= read;
            buffer.flip();
            while (buffer.hasRemaining()) output.write(buffer);
        }
        if (old.length() > 0 && added.length > 0) write(output, utf8(","));
        write(output, added);
        return new Range(start, output.position() - start);
    }

    private static byte[] fileDigest(Path file) throws IOException {
        MessageDigest sha;
        try { sha = MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException("SHA-256 unavailable", impossible); }
        try (var input = Files.newInputStream(file)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) != -1) sha.update(buffer, 0, read);
        }
        return sha.digest();
    }

    private static void write(FileChannel output, byte[] bytes) throws IOException {
        var buffer = ByteBuffer.wrap(bytes);
        while (buffer.hasRemaining()) output.write(buffer);
    }

    private static byte[] arrayContents(String encoded) { return utf8(encoded.substring(1, encoded.length() - 1)); }
    private static byte[] utf8(String text) { return text.getBytes(StandardCharsets.UTF_8); }

    /** Reject oversized inputs before constructing their JSON tree/strings; final encoding checks bytes exactly. */
    private static void checkInputSize(BenchmarkRunEvidence batch, int maximumBytes) {
        var budget = new InputBudget(maximumBytes);
        budget.text(batch.plan().experimentId());
        budget.text(batch.detail());
        batch.environment().forEach((key, value) -> { budget.text(key); budget.text(value); });
        batch.plan().timeline().phases().forEach(phase -> budget.text(phase.name()));
        for (var measurement : batch.measurements()) {
            budget.text(measurement.scenario());
            budget.text(measurement.operationUnit());
            budget.text(measurement.phase());
            measurement.metrics().forEach((key, value) -> {
                budget.text(key); budget.text(value.unit()); budget.text(value.unavailableReason());
            });
            measurement.distributions().forEach((key, value) -> {
                budget.text(key);
                budget.consume(2L * value.upperBoundsNanos().size());
            });
        }
        batch.diagnostics().forEach(event -> { budget.text(event.level()); budget.text(event.message()); });
    }

    private static final class InputBudget {
        private long remaining;
        InputBudget(long remaining) { this.remaining = remaining; }
        void text(String value) { consume(value.length()); }
        void consume(long minimumEncodedBytes) {
            remaining -= minimumEncodedBytes;
            if (remaining < 0) throw new IllegalArgumentException("Checkpoint exceeds maximumBatchBytes");
        }
    }
}
