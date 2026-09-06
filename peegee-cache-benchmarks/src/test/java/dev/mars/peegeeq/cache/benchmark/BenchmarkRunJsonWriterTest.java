package dev.mars.peegeeq.cache.benchmark;

import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class BenchmarkRunJsonWriterTest {
    @TempDir Path directory;

    @Test
    void writesOneComprehensiveJsonDocumentWithExactIdentityConfigurationAndMeasurements() throws Exception {
        var initial = evidence(BenchmarkRunEvidence.Status.RUNNING, List.of(), "");
        var writer = BenchmarkRunJsonWriter.create(directory, initial);
        var complete = evidence(BenchmarkRunEvidence.Status.COMPLETED, List.of(measurement()), "Observed load completed");
        writer.checkpoint(complete);

        var json = new JsonObject(Files.readString(writer.path()));
        assertEquals(1, json.getInteger("schemaVersion"));
        assertEquals(initial.executionId().toString(), json.getString("executionId"));
        assertEquals("COMPLETED", json.getJsonObject("execution").getString("status"));
        assertEquals("UNASSESSED", json.getJsonObject("execution").getString("measurementValidity"));
        assertEquals("pool-pressure", json.getJsonObject("plan").getString("experimentId"));
        assertEquals(Long.MAX_VALUE, json.getJsonObject("plan").getLong("workloadSeed"));
        assertEquals(4, json.getJsonObject("configuration").getInteger("concurrency"));
        assertEquals(1, json.getJsonObject("configuration").getInteger("poolSize"));
        assertEquals(750_000_000L, json.getJsonObject("configuration").getLong("operationTimeoutNanos"));
        assertEquals(500_000_000L, json.getJsonObject("timeline").getLong("samplingIntervalNanos"));
        assertEquals("2026-09-06T04:00:00Z", json.getJsonObject("execution").getString("startedAtUtc"));
        assertEquals("客户/本地", json.getJsonObject("environment").getString("deployment"));
        var interval = json.getJsonArray("measurements").getJsonObject(0);
        assertEquals("cache-set-get", interval.getString("scenario"));
        assertEquals("workflow", interval.getString("operationUnit"));
        assertEquals(10L, interval.getJsonObject("after").getLong("scheduled"));
        assertEquals(5.0, interval.getJsonObject("rates").getDouble("successfulPerSecond"));
        assertNull(interval.getJsonObject("metrics").getJsonObject("databaseCpu").getValue("value"));
        assertEquals("Not available on this target", interval.getJsonObject("metrics")
                .getJsonObject("databaseCpu").getString("unavailableReason"));
        assertEquals("NOT_COLLECTED", interval.getJsonObject("latencyDistributions").getString("status"));
        assertEquals("NOT_RUN", json.getJsonObject("analysis").getString("status"));
        assertEquals("actual diagnostic", json.getJsonArray("diagnostics").getJsonObject(0).getString("message"));
        try (var files = Files.list(directory)) { assertEquals(List.of(writer.path()), files.toList()); }
    }

    @Test
    void lastCheckpointRemainsReadableWithoutFinalisationAndFailedRunsRetainMeasurements() throws Exception {
        var snapshot = evidence(BenchmarkRunEvidence.Status.RUNNING, List.of(measurement()), "");
        var writer = BenchmarkRunJsonWriter.create(directory, snapshot);
        var interrupted = new JsonObject(Files.readString(writer.path()));
        assertFalse(interrupted.getJsonObject("execution").getBoolean("finalised"));
        assertEquals(1, interrupted.getJsonArray("measurements").size());
        writer.checkpoint(evidence(BenchmarkRunEvidence.Status.FAILED, List.of(measurement()), "Connection lost"));
        var failed = new JsonObject(Files.readString(writer.path()));
        assertEquals("FAILED", failed.getJsonObject("execution").getString("status"));
        assertEquals("Connection lost", failed.getJsonObject("execution").getString("detail"));
        assertEquals(1, failed.getJsonArray("measurements").size());
        assertThrows(IllegalStateException.class, () -> writer.checkpoint(snapshot));
    }

    @Test
    void rejectsTruncatedHistoryAndChangedIdentityWithoutDamagingPublishedEvidence() throws Exception {
        var writer = BenchmarkRunJsonWriter.create(directory,
                evidence(BenchmarkRunEvidence.Status.RUNNING, List.of(measurement()), ""));
        String before = Files.readString(writer.path());
        assertThrows(IllegalArgumentException.class, () -> writer.checkpoint(
                evidence(BenchmarkRunEvidence.Status.RUNNING, List.of(), "")));
        var other = evidence(BenchmarkRunEvidence.Status.RUNNING, List.of(measurement()), "");
        var changed = new BenchmarkRunEvidence(UUID.randomUUID(), other.plan(), other.startedAt(), other.updatedAt(),
                other.status(), other.validity(), other.detail(), other.environment(), other.measurements(), other.diagnostics());
        assertThrows(IllegalArgumentException.class, () -> writer.checkpoint(changed));
        assertEquals(before, Files.readString(writer.path()));
        assertThrows(java.nio.file.FileAlreadyExistsException.class, () -> BenchmarkRunJsonWriter.create(directory, other));
        assertEquals(before, Files.readString(writer.path()));
    }

    @Test
    void refusesToOverwriteEvidenceModifiedOutsideItsOwner() throws Exception {
        var writer = BenchmarkRunJsonWriter.create(directory,
                evidence(BenchmarkRunEvidence.Status.RUNNING, List.of(), ""));
        Files.writeString(writer.path(), "another process replaced this file");
        assertThrows(java.io.IOException.class, () -> writer.checkpoint(
                evidence(BenchmarkRunEvidence.Status.COMPLETED, List.of(measurement()), "done")));
        assertEquals("another process replaced this file", Files.readString(writer.path()));
    }

    @Test
    void independentExecutionsOfTheSamePlannedRunGetDifferentFiles() throws Exception {
        var first = evidence(BenchmarkRunEvidence.Status.RUNNING, List.of(), "");
        var second = new BenchmarkRunEvidence(UUID.randomUUID(), first.plan(), first.startedAt(), first.updatedAt(),
                first.status(), first.validity(), first.detail(), first.environment(), first.measurements(), first.diagnostics());
        assertNotEquals(BenchmarkRunJsonWriter.create(directory, first).path(), BenchmarkRunJsonWriter.create(directory, second).path());
    }

    @Test
    void propagatesFileFailuresRatherThanReportingACheckpointAsWritten() throws Exception {
        Path file = directory.resolve("not-a-directory");
        Files.writeString(file, "existing data");
        assertThrows(java.io.IOException.class, () -> BenchmarkRunJsonWriter.create(file,
                evidence(BenchmarkRunEvidence.Status.RUNNING, List.of(), "")));
        assertEquals("existing data", Files.readString(file));
        var writer = BenchmarkRunJsonWriter.create(directory,
                evidence(BenchmarkRunEvidence.Status.RUNNING, List.of(), ""));
        Files.delete(writer.path());
        Files.createDirectory(writer.path());
        Files.writeString(writer.path().resolve("sentinel"), "unrelated");
        assertThrows(java.io.IOException.class, () -> writer.checkpoint(
                evidence(BenchmarkRunEvidence.Status.COMPLETED, List.of(measurement()), "done")));
        assertEquals("unrelated", Files.readString(writer.path().resolve("sentinel")));
        try (var files = Files.list(directory)) {
            assertFalse(files.anyMatch(path -> path.getFileName().toString().endsWith(".tmp")));
        }
    }

    @Test
    void rejectsNonFiniteMetricsUnknownPhasesAndOverlappingIntervals() {
        assertThrows(IllegalArgumentException.class, () -> new BenchmarkRunEvidence.Metric("percent", Double.NaN, ""));
        assertThrows(IllegalArgumentException.class, () -> new BenchmarkRunEvidence.Metric("percent", null, ""));
        assertThrows(IllegalArgumentException.class, () -> new BenchmarkRunEvidence.Metric("percent", 1.0, "unavailable"));
        assertThrows(IllegalArgumentException.class, () -> evidence(BenchmarkRunEvidence.Status.RUNNING,
                List.of(measurement(), measurement()), ""));
        var measurement = measurement();
        var unknownPhase = new BenchmarkRunEvidence.Measurement(measurement.scenario(), measurement.operationUnit(),
                "unknown", measurement.interval(), measurement.metrics());
        assertThrows(IllegalArgumentException.class, () -> evidence(BenchmarkRunEvidence.Status.RUNNING, List.of(unknownPhase), ""));
        assertThrows(IllegalArgumentException.class, () -> evidence(BenchmarkRunEvidence.Status.FAILED, List.of(), ""));
    }

    static BenchmarkRunEvidence evidence(BenchmarkRunEvidence.Status status,
                                          List<BenchmarkRunEvidence.Measurement> measurements, String detail) {
        var timeline = new BenchmarkTimeline(List.of(new BenchmarkTimeline.Phase("observe",
                BenchmarkTimeline.PhaseKind.SUSTAINED, Duration.ofSeconds(1))), Duration.ofMillis(500));
        var matrix = new BenchmarkParameterMatrix(BenchmarkParameters.LoadModel.RATE_CONTROLLED,
                List.of(4), List.of(1), List.of(10.0), 20, Duration.ofMillis(750));
        var run = new BenchmarkExperiment(1, "pool-pressure", timeline, matrix, 1, 1, Long.MAX_VALUE, 1).runAt(0);
        return new BenchmarkRunEvidence(UUID.fromString("61bbbac6-45c3-4ed8-9cc0-a1eeae8f8001"), run,
                Instant.parse("2026-09-06T04:00:00Z"), Instant.parse("2026-09-06T04:00:01Z"), status,
                BenchmarkRunEvidence.Validity.UNASSESSED, detail, Map.of("deployment", "客户/本地"), measurements,
                List.of(new BenchmarkRunEvidence.Diagnostic(0, "INFO", "actual diagnostic")));
    }

    static BenchmarkRunEvidence.Measurement measurement() {
        return new BenchmarkRunEvidence.Measurement("cache-set-get", "workflow", "observe",
                new BenchmarkInterval(0, 1_000_000_000L, BenchmarkWorkCounts.zero(),
                        new BenchmarkWorkCounts(10, 10, 8, 5, 1, 1, 0, 0)),
                Map.of("generatorCpu", new BenchmarkRunEvidence.Metric("percent", 12.5, ""),
                        "databaseCpu", new BenchmarkRunEvidence.Metric("percent", null, "Not available on this target")));
    }
}
