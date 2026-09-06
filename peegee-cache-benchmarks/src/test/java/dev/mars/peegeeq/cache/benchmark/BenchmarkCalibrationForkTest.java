package dev.mars.peegeeq.cache.benchmark;

import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

class BenchmarkCalibrationForkTest {
    @TempDir Path directory;

    @Test
    void independentHeapLimitedForksProduceDistinctCompleteSingleRunFiles() throws Exception {
        var outputs = directory.resolve("runs");
        for (int fork = 0; fork < 2; fork++) {
            assertEquals(0, launch("fork-" + fork, outputs.toString(), "2", "512", "25", "125", "25", "1", "2000000", Integer.toString(fork)));
        }
        try (var paths = Files.list(outputs)) {
            var files = paths.toList();
            assertEquals(2, files.size());
            var first = new JsonObject(Files.readString(files.get(0)));
            var second = new JsonObject(Files.readString(files.get(1)));
            assertNotEquals(first.getString("executionId"), second.getString("executionId"));
            assertNotEquals(first.getJsonObject("environment").getString("processId"), second.getJsonObject("environment").getString("processId"));
            for (var json : List.of(first, second)) {
                assertEquals("COMPLETED", json.getJsonObject("execution").getString("status"));
                assertTrue(Long.parseLong(json.getJsonObject("environment").getString("maximumHeapBytes")) <= 48L * 1024 * 1024);
                assertEquals(12, json.getJsonArray("measurements").size());
            }
        }
    }

    @Test
    void invalidArgumentsExitBeforeCreatingEvidence() throws Exception {
        var output = directory.resolve("invalid");
        assertEquals(2, launch("invalid", output.toString(), "0", "16", "0", "100", "50", "1", "2000000", "0"));
        assertFalse(Files.exists(output));
    }

    @Test
    void diskBudgetFailureHasNonzeroProcessExitAndPreservesFailedEvidence() throws Exception {
        var output = directory.resolve("budget");
        assertEquals(1, launch("budget", output.toString(), "1", "1024", "0", "400", "50", "1", "131072", "0"));
        try (var paths = Files.list(output)) {
            var files = paths.toList();
            assertEquals(1, files.size());
            assertEquals(BenchmarkRunEvidence.Status.FAILED, BenchmarkCheckpointRecovery.inspect(files.getFirst()).status());
        }
    }

    private int launch(String label, String... arguments) throws Exception {
        String executable = System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
        var command = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin", executable).toString(),
                "-Xmx48m", "-XX:ActiveProcessorCount=4", "-cp", System.getProperty("java.class.path"),
                "dev.mars.peegeeq.cache.benchmark.BenchmarkCalibrationMain"));
        command.addAll(List.of(arguments));
        Path log = directory.resolve(label + ".log");
        var process = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            assertTrue(process.waitFor(45, TimeUnit.SECONDS), "Calibration child exceeded deadline; see " + log);
            int exit = process.exitValue();
            String output = Files.readString(log);
            System.out.println("calibration-child " + label + " exit=" + exit + " output=" + output);
            assertFalse(output.contains("event executor terminated"), "Completion must survive Vert.x shutdown");
            if (exit == 0) assertTrue(output.contains("CALIBRATION_JSON="), "Success must be explicitly reported after cleanup");
            return exit;
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
                assertTrue(process.waitFor(5, TimeUnit.SECONDS), "Owned calibration child did not terminate");
            }
        }
    }
}
