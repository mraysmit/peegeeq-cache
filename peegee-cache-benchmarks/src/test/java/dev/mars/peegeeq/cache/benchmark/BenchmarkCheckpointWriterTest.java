package dev.mars.peegeeq.cache.benchmark;

import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static dev.mars.peegeeq.cache.benchmark.BenchmarkRunEvidence.*;
import static org.junit.jupiter.api.Assertions.*;

class BenchmarkCheckpointWriterTest {
    @TempDir Path directory;

    @Test
    void appendsBothHistoriesToOneReadableJsonWithoutRetainingPreviousBatches() throws Exception {
        var writer = BenchmarkCheckpointWriter.create(directory, batch(0, Status.RUNNING), limits());
        for (int index = 1; index <= 200; index++) writer.append(batch(index, Status.RUNNING));
        writer.append(batch(201, Status.FAILED));
        var json = new JsonObject(Files.readString(writer.path()));
        assertEquals(201, json.getJsonArray("measurements").size());
        assertEquals(202, json.getJsonArray("diagnostics").size());
        assertEquals("FAILED", json.getJsonObject("execution").getString("status"));
        assertEquals("test termination", json.getJsonObject("execution").getString("detail"));
        assertEquals("客户/本地", json.getJsonObject("environment").getString("deployment"));
        assertEquals(201L, json.getJsonArray("measurements").getJsonObject(200)
                .getJsonObject("after").getLong("succeeded"));
        assertEquals("event 0: \"客户\"", json.getJsonArray("diagnostics").getJsonObject(0).getString("message"));
        assertEquals(1, writer.retainedScenarioCount());
        assertThrows(IllegalStateException.class, () -> writer.append(batch(202, Status.RUNNING)));
        try (var paths = Files.list(directory)) { assertEquals(List.of(writer.path()), paths.toList()); }
    }

    @Test
    void validatesBoundedBatchesAndContinuityBeforeChangingTheCheckpoint() throws Exception {
        var writer = BenchmarkCheckpointWriter.create(directory, batch(0, Status.RUNNING), limits());
        writer.append(batch(1, Status.RUNNING));
        String before = Files.readString(writer.path());
        assertThrows(IllegalArgumentException.class, () -> writer.append(batch(1, Status.RUNNING)));
        var next = batch(2, Status.RUNNING);
        var sample = next.measurements().getFirst();
        var other = new Measurement("second scenario", sample.operationUnit(), sample.phase(), sample.interval(), Map.of());
        assertThrows(IllegalArgumentException.class, () -> writer.append(withMeasurements(next, List.of(other))));
        assertThrows(IllegalArgumentException.class, () -> writer.append(withMeasurements(next, List.of(sample, other))));
        var large = new BenchmarkRunEvidence(next.executionId(), next.plan(), next.startedAt(), next.updatedAt(),
                next.status(), next.validity(), "x".repeat(100_000), next.environment(), next.measurements(), next.diagnostics());
        assertThrows(IllegalArgumentException.class, () -> writer.append(large));
        assertEquals(before, Files.readString(writer.path()));
        writer.append(next);
        assertEquals(2, new JsonObject(Files.readString(writer.path())).getJsonArray("measurements").size());
    }

    @Test
    void failedWritePreservesLastCheckpointAndAllowsAnExplicitRetry() throws Exception {
        var writer = BenchmarkCheckpointWriter.create(directory, batch(0, Status.RUNNING), limits());
        writer.append(batch(1, Status.RUNNING));
        byte[] before = Files.readAllBytes(writer.path());
        // Real filesystem interference, not a substituted persistence implementation.
        byte[] changed = before.clone();
        changed[0] = ' ';
        Files.write(writer.path(), changed);
        assertThrows(IOException.class, () -> writer.append(batch(2, Status.RUNNING)));
        assertArrayEquals(changed, Files.readAllBytes(writer.path()));
        Files.write(writer.path(), before);
        writer.append(batch(2, Status.RUNNING));
        assertEquals(2, new JsonObject(Files.readString(writer.path())).getJsonArray("measurements").size());
        try (var paths = Files.list(directory)) { assertEquals(List.of(writer.path()), paths.toList()); }
    }

    @Test
    void reservesIdentityAndRejectsChangedManifest() throws Exception {
        var initial = batch(0, Status.RUNNING);
        var writer = BenchmarkCheckpointWriter.create(directory, initial, limits());
        assertThrows(java.nio.file.FileAlreadyExistsException.class,
                () -> BenchmarkCheckpointWriter.create(directory, initial, limits()));
        var next = batch(1, Status.RUNNING);
        var changed = new BenchmarkRunEvidence(next.executionId(), next.plan(), next.startedAt(), next.updatedAt(),
                next.status(), next.validity(), next.detail(), Map.of("deployment", "other"), next.measurements(), next.diagnostics());
        assertThrows(IllegalArgumentException.class, () -> writer.append(changed));
        assertEquals(0, new JsonObject(Files.readString(writer.path())).getJsonArray("measurements").size());
        assertThrows(IllegalArgumentException.class, () -> new BenchmarkCheckpointWriter.Limits(0, 100, 1));
    }

    static BenchmarkCheckpointWriter.Limits limits() { return new BenchmarkCheckpointWriter.Limits(2, 16_384, 1); }

    static BenchmarkRunEvidence batch(int index, Status status) {
        var base = BenchmarkRunJsonWriterTest.evidence(Status.RUNNING, List.of(), "");
        List<Measurement> samples = index == 0 ? List.of() : List.of(new Measurement("cache-set-get", "workflow", "observe",
                new BenchmarkInterval(index - 1, index, counts(index - 1), counts(index)), Map.of()));
        return new BenchmarkRunEvidence(base.executionId(), base.plan(), base.startedAt(), base.updatedAt().plusNanos(index),
                status, Validity.UNASSESSED, status == Status.FAILED ? "test termination" : "", base.environment(), samples,
                List.of(new Diagnostic(index, "INFO", "event " + index + ": \"客户\"")));
    }

    private static BenchmarkWorkCounts counts(long value) {
        return new BenchmarkWorkCounts(value, value, value, value, 0, 0, 0, 0);
    }

    private static BenchmarkRunEvidence withMeasurements(BenchmarkRunEvidence source, List<Measurement> samples) {
        return new BenchmarkRunEvidence(source.executionId(), source.plan(), source.startedAt(), source.updatedAt(),
                source.status(), source.validity(), source.detail(), source.environment(), samples, source.diagnostics());
    }
}
