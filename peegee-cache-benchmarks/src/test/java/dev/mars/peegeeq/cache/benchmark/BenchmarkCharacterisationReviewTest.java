package dev.mars.peegeeq.cache.benchmark;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class BenchmarkCharacterisationReviewTest {
    @TempDir Path directory;

    @Test
    void publishesCompletenessBoundaryVariationLimitationsAndFollowUps() throws Exception {
        writeRun("a.json", 0, 100, 20, "NO_ONSET_OBSERVED");
        writeRun("b.json", 0, 100, 30, "OBSERVED_DEGRADATION");
        var runs = new JsonArray()
                .add(new JsonObject().put("configurationIndex", 0).put("status", "COMPLETED").put("evidenceJson", "a.json"))
                .add(new JsonObject().put("configurationIndex", 0).put("status", "COMPLETED").put("evidenceJson", "b.json"));
        Path campaign = directory.resolve("campaign-manifest.json");
        Files.writeString(campaign, new JsonObject().put("plannedRuns", 2).put("completedRuns", 2)
                .put("target", new JsonObject().put("id", "local").put("serverOwned", true))
                .put("runs", runs).encodePrettily());
        Path review = directory.resolve("characterisation-review.json");

        BenchmarkCharacterisationReview.publish(campaign, review);

        var json = new JsonObject(Files.readString(review));
        assertEquals("COMPLETE_WITH_FINDINGS", json.getString("status"));
        assertEquals(2, json.getJsonObject("completeness").getInteger("accountedRuns"));
        var variation = json.getJsonArray("repeatVariation").getJsonObject(0);
        assertEquals(20L, variation.getLong("minimumP95Nanos"));
        assertEquals(30L, variation.getLong("maximumP95Nanos"));
        assertFalse(json.getJsonArray("boundaryBrackets").isEmpty());
        assertFalse(json.getJsonArray("limitations").isEmpty());
        assertFalse(json.getJsonArray("followUps").isEmpty());
    }

    @Test
    void doesNotPresentUnassessedMeasurementAsConclusiveCampaignEvidence() throws Exception {
        writeRun("unassessed.json", 0, 100, 20, "NO_ONSET_OBSERVED", "UNASSESSED");
        var runs = new JsonArray().add(new JsonObject().put("configurationIndex", 0)
                .put("status", "COMPLETED").put("evidenceJson", "unassessed.json"));
        Path campaign = directory.resolve("campaign-manifest.json");
        Files.writeString(campaign, new JsonObject().put("plannedRuns", 1).put("completedRuns", 1)
                .put("target", new JsonObject().put("id", "local").put("serverOwned", true))
                .put("runs", runs).encodePrettily());
        Path review = directory.resolve("review.json");

        BenchmarkCharacterisationReview.publish(campaign, review);

        var json = new JsonObject(Files.readString(review));
        assertEquals("COMPLETE_INCONCLUSIVE", json.getString("status"));
        assertTrue(json.getJsonArray("limitations").stream()
                .anyMatch(value -> value.toString().contains("UNASSESSED")));
    }

    private void writeRun(String name, int configuration, double rate, long p95, String analysisStatus) throws Exception {
        writeRun(name, configuration, rate, p95, analysisStatus, "VALID");
    }

    private void writeRun(String name, int configuration, double rate, long p95,
                          String analysisStatus, String validity) throws Exception {
        var measurement = new JsonObject().put("scenario", "cache").put("phase", "load")
                .put("startNanos", 0L).put("endNanos", 1_000_000_000L)
                .put("rates", new JsonObject().put("offeredPerSecond", rate).put("successfulPerSecond", rate - 1))
                .put("latencyDistributions", new JsonObject().put("status", "COLLECTED")
                        .put("series", new JsonObject().put("successfulService",
                                new JsonObject().put("sampleCount", 20L).put("p95UpperBoundNanos", p95))));
        var findings = "OBSERVED_DEGRADATION".equals(analysisStatus)
                ? new JsonArray().add(new JsonObject().put("scenario", "cache")
                        .put("candidateOnsetNanos", 0L).put("onsetBracketEndNanos", 1_000_000_000L)) : new JsonArray();
        var run = new JsonObject().put("execution", new JsonObject().put("measurementValidity", validity))
                .put("plan", new JsonObject().put("configurationIndex", configuration))
                .put("configuration", new JsonObject().put("offeredPerSecond", rate))
                .put("measurements", new JsonArray().add(measurement))
                .put("analysis", new JsonObject().put("status", analysisStatus).put("findings", findings)
                        .put("limitations", new JsonArray()));
        Files.writeString(directory.resolve(name), run.encodePrettily());
    }
}
