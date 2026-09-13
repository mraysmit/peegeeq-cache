package dev.mars.peegeeq.cache.benchmark;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

class BenchmarkAnalysisPublisherTest {
    @TempDir Path directory;

    @Test
    void atomicallyAddsVersionedAnalysisAndDerivesSelfContainedHtml() throws Exception {
        Path jsonPath = directory.resolve("run.json");
        Path htmlPath = directory.resolve("run.html");
        var run = new JsonObject().put("executionId", "run-1")
                .put("execution", new JsonObject().put("finalised", true))
                .put("measurements", new JsonArray().add(new JsonObject()
                        .put("scenario", "cache").put("phase", "baseline").put("startNanos", 0L)
                        .put("rates", new JsonObject().put("offeredPerSecond", 10.0).put("successfulPerSecond", 9.0))
                        .put("latencyDistributions", new JsonObject().put("status", "COLLECTED")
                                .put("series", new JsonObject().put("successfulService",
                                        new JsonObject().put("sampleCount", 20L).put("p95UpperBoundNanos", 5_000_000L)))))
                        .add(new JsonObject().put("scenario", "cache").put("phase", "load").put("startNanos", 1L)
                                .put("rates", new JsonObject().put("offeredPerSecond", 20.0).put("successfulPerSecond", 18.0))
                                .put("latencyDistributions", new JsonObject().put("status", "COLLECTED")
                                        .put("series", new JsonObject().put("successfulService",
                                                new JsonObject().put("sampleCount", 20L).put("p95UpperBoundNanos", 8_000_000L))))))
                .put("analysis", new JsonObject().put("status", "NOT_RUN"));
        Files.writeString(jsonPath, run.encodePrettily());
        var policy = new BenchmarkAnalysisPolicy(1, "BASELINE", 10, 1.5, 2, 1.2,
                Duration.ofSeconds(1));

        BenchmarkAnalysisPublisher.publish(jsonPath, htmlPath, policy);

        var updated = new JsonObject(Files.readString(jsonPath));
        assertEquals(1, updated.getJsonObject("analysis").getInteger("policyVersion"));
        String html = Files.readString(htmlPath);
        assertTrue(html.contains("Benchmark time-series report"));
        assertTrue(html.contains("Authoritative JSON SHA-256"));
        assertTrue(html.contains("run-1"));
        assertTrue(html.contains("data-series=\"p95-latency\""));
        assertTrue(html.contains("data-series=\"successful-rate\""));
        assertTrue(html.contains("<polyline"));
        assertTrue(html.contains("5.000 ms"));
        assertFalse(html.contains("src=\"http"));
        assertFalse(html.contains("href=\"http"));
        try (var paths = Files.list(directory)) { assertEquals(2, paths.count()); }
    }
}
