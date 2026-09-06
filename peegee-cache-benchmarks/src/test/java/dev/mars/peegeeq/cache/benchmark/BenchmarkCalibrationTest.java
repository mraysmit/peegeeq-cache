package dev.mars.peegeeq.cache.benchmark;

import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(VertxExtension.class)
@io.vertx.junit5.Timeout(value = 45, timeUnit = TimeUnit.SECONDS)
class BenchmarkCalibrationTest {
    @TempDir Path directory;

    @Test
    void preservesRawPairedConcurrentWindowsAndCheckpointCostsInOneJson(Vertx vertx, VertxTestContext ctx) {
        var config = BenchmarkCalibrationConfigTest.config(2, 16, 40, 110, 50, 2, 2_000_000);
        BenchmarkRecorderCalibration.run(vertx, directory, config, 3).compose(path -> vertx.executeBlocking(() -> {
            var json = new JsonObject(Files.readString(path));
            assertEquals("COMPLETED", json.getJsonObject("execution").getString("status"));
            assertEquals("UNASSESSED", json.getJsonObject("execution").getString("measurementValidity"));
            assertEquals(3, json.getJsonObject("plan").getInteger("forkIndex"));
            assertEquals("RECORDER_CALIBRATION", json.getJsonObject("environment").getString("purpose"));
            long recorderWindows = 0;
            long writes = 0;
            long completed = 0;
            for (Object value : json.getJsonArray("measurements")) {
                var item = (JsonObject) value;
                if (item.getString("scenario").equals("recorder-calibration")) {
                    recorderWindows++;
                    var metrics = item.getJsonObject("metrics");
                    assertTrue(metrics.getJsonObject("recorded.operations").getDouble("value") > 0);
                    assertTrue(metrics.getJsonObject("baseline.operations").getDouble("value") > 0);
                    assertTrue(metrics.containsKey("recorded.worker.1.elapsedNanos"));
                    assertTrue(metrics.containsKey("heap.usedBytes"));
                    assertTrue(metrics.containsKey("recorded.worker.0.allocatedBytes"));
                    var after = item.getJsonObject("after");
                    long terminal = after.getLong("succeeded") + after.getLong("failed") + after.getLong("timedOut");
                    assertTrue(terminal > completed);
                    completed = terminal;
                    assertEquals(0L, after.getLong("outstanding"));
                    assertEquals("COLLECTED", item.getJsonObject("latencyDistributions").getString("status"));
                } else if (item.getString("scenario").equals("checkpoint-publication")) {
                    writes++;
                    assertTrue(item.getJsonObject("metrics").getJsonObject("writeNanos").getDouble("value") > 0);
                    assertTrue(item.getJsonObject("metrics").getJsonObject("publishedBytes").getDouble("value") > 0);
                }
            }
            assertEquals(config.windowCount(), recorderWindows);
            assertEquals(2, writes);
            try (var paths = Files.list(directory)) { assertEquals(1, paths.count()); }
            assertFalse(BenchmarkCheckpointRecovery.inspect(path).requiresOwnerReview());
            return null;
        })).onComplete(ctx.succeeding(ignored -> ctx.completeNow()));
    }

    @Test
    void diskBudgetFailurePreservesAnExplicitlyFailedSingleFile(Vertx vertx, VertxTestContext ctx) {
        var config = BenchmarkCalibrationConfigTest.config(1, 1024, 0, 400, 50, 1, 131_072);
        BenchmarkRecorderCalibration.run(vertx, directory, config, 0).transform(result -> {
            assertTrue(result.failed());
            return vertx.executeBlocking(() -> {
                try (var paths = Files.list(directory)) {
                    var files = paths.toList();
                    assertEquals(1, files.size());
                    assertTrue(Files.size(files.getFirst()) <= config.maximumEvidenceBytes());
                    var json = new JsonObject(Files.readString(files.getFirst()));
                    assertEquals("FAILED", json.getJsonObject("execution").getString("status"));
                    assertTrue(json.getJsonObject("execution").getString("detail").contains("budget"));
                    assertTrue(json.getJsonArray("diagnostics").size() > 0);
                    assertTrue(json.getJsonArray("diagnostics").getJsonObject(0).getString("message")
                            .contains("unpublishedMeasurements"));
                }
                return null;
            });
        }).onComplete(ctx.succeeding(ignored -> ctx.completeNow()));
    }
}
