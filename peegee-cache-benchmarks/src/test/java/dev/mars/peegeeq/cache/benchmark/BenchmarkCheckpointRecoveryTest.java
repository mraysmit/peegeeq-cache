package dev.mars.peegeeq.cache.benchmark;

import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static dev.mars.peegeeq.cache.benchmark.BenchmarkCheckpointWriterTest.*;
import static dev.mars.peegeeq.cache.benchmark.BenchmarkRunEvidence.Status.*;
import static org.junit.jupiter.api.Assertions.*;

class BenchmarkCheckpointRecoveryTest {
    @TempDir Path directory;

    @Test
    void classifiesUnfinishedEvidenceWithoutAssumingACrashOrChangingTheFile() throws Exception {
        var writer = BenchmarkCheckpointWriter.create(directory, batch(0, RUNNING), limits());
        for (int index = 1; index <= 30; index++) writer.append(batch(index, RUNNING));
        byte[] before = Files.readAllBytes(writer.path());
        var recovery = BenchmarkCheckpointRecovery.inspect(writer.path());
        assertEquals(batch(0, RUNNING).executionId(), recovery.executionId());
        assertEquals(RUNNING, recovery.status());
        assertEquals(BenchmarkCheckpointRecovery.Classification.UNFINALISED, recovery.classification());
        assertTrue(recovery.requiresOwnerReview());
        assertEquals(30, recovery.measurementCount());
        assertEquals(31, recovery.diagnosticCount());
        assertEquals(batch(30, RUNNING).updatedAt(), recovery.checkpointAt());
        assertArrayEquals(before, Files.readAllBytes(writer.path()));
        // Inspection does not seize ownership or prevent the live owner from continuing.
        writer.append(batch(31, COMPLETED));
        assertFalse(BenchmarkCheckpointRecovery.inspect(writer.path()).requiresOwnerReview());
    }

    @Test
    void recognisesFailedAndStoppedAsFinalisedWithoutCallingThemSuccessful() throws Exception {
        var writer = BenchmarkCheckpointWriter.create(directory, batch(0, RUNNING), limits());
        writer.append(batch(1, FAILED));
        var failed = BenchmarkCheckpointRecovery.inspect(writer.path());
        assertEquals(FAILED, failed.status());
        assertEquals(BenchmarkCheckpointRecovery.Classification.FINALISED, failed.classification());
        assertFalse(failed.requiresOwnerReview());
        var json = new JsonObject(Files.readString(writer.path()));
        json.getJsonObject("execution").put("status", "STOPPED");
        Files.writeString(writer.path(), json.encode());
        assertEquals(STOPPED, BenchmarkCheckpointRecovery.inspect(writer.path()).status());
    }

    @Test
    void rejectsTruncationUnsupportedSchemaAndContradictoryStatusWithoutRepairingThem() throws Exception {
        var writer = BenchmarkCheckpointWriter.create(directory, batch(0, RUNNING), limits());
        var source = new JsonObject(Files.readString(writer.path()));
        var unsupported = source.copy().put("schemaVersion", 2);
        assertRejectedUnchanged(writer.path(), unsupported.encode());
        var contradictory = source.copy();
        contradictory.getJsonObject("execution").put("finalised", true);
        assertRejectedUnchanged(writer.path(), contradictory.encode());
        var missing = source.copy();
        missing.remove("execution");
        assertRejectedUnchanged(writer.path(), missing.encode());
        assertRejectedUnchanged(writer.path(), source.encode().substring(0, 150));
        assertRejectedUnchanged(writer.path(), source.encode() + "{}");
        assertRejectedUnchanged(writer.path(), source.encode().replace("\"status\":\"RUNNING\"",
                "\"status\":\"RUNNING\",\"status\":\"COMPLETED\""));
        assertRejectedUnchanged(writer.path(), "[]");
    }

    @Test
    void rejectsOversizedHeaderValuesRatherThanAllocatingAnUnboundedManifest() throws Exception {
        var writer = BenchmarkCheckpointWriter.create(directory, batch(0, RUNNING), limits());
        var source = new JsonObject(Files.readString(writer.path()));
        source.put("executionId", "x".repeat(100_000));
        assertRejectedUnchanged(writer.path(), source.encode());
    }

    private static void assertRejectedUnchanged(Path path, String content) throws Exception {
        Files.writeString(path, content);
        assertThrows(IOException.class, () -> BenchmarkCheckpointRecovery.inspect(path));
        assertEquals(content, Files.readString(path));
    }
}
