package dev.mars.peegeeq.cache.benchmark;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BenchmarkTrendAnalyzerTest {
    private static final BenchmarkAnalysisPolicy POLICY = new BenchmarkAnalysisPolicy(
            1, "BASELINE", 10, 1.5, 2, 1.2, Duration.ofSeconds(1));

    @Test
    void identifiesPersistentLatencyOnsetProgressionAndRecovery() {
        var run = run(sample("baseline", "BASELINE", 0, 10, 20),
                sample("load", "LOAD", 1, 10, 31), sample("load", "LOAD", 2, 10, 40),
                sample("recovery", "RECOVERY", 3, 10, 11));

        var analysis = BenchmarkTrendAnalyzer.analyse(run, POLICY);

        assertEquals("OBSERVED_DEGRADATION", analysis.getString("status"));
        var finding = analysis.getJsonArray("findings").getJsonObject(0);
        assertEquals("LATENCY_DETERIORATION", finding.getString("symptom"));
        assertEquals(1_000_000_000L, finding.getLong("candidateOnsetNanos"));
        assertEquals(2_000_000_000L, finding.getLong("confirmationNanos"));
        assertEquals("RECOVERED", finding.getString("recovery"));
        assertTrue(finding.getDouble("maximumRatio") >= 2.0);
    }

    @Test
    void reportsInconclusiveWhenReferenceSamplesAreInsufficient() {
        var analysis = BenchmarkTrendAnalyzer.analyse(
                run(sample("baseline", "BASELINE", 0, 2, 20)), POLICY);
        assertEquals("INCONCLUSIVE", analysis.getString("status"));
        assertTrue(analysis.getString("reason").contains("reference"));
    }

    @Test
    void keepsAnIsolatedLatencySpikeVisibleWithoutCallingItPersistent() {
        var analysis = BenchmarkTrendAnalyzer.analyse(run(sample("baseline", "BASELINE", 0, 10, 20),
                sample("load", "LOAD", 1, 10, 40), sample("load", "LOAD", 2, 10, 20)), POLICY);
        assertEquals("NO_ONSET_OBSERVED", analysis.getString("status"));
        assertEquals(1, analysis.getJsonArray("isolatedSpikes").size());
    }

    @Test
    void detectsPersistentReliabilityLossAndAccumulatingPressure() {
        var baseline = sample("baseline", "BASELINE", 0, 20, 20);
        var first = sample("load", "LOAD", 1, 20, 20);
        first.put("before", counts(20, 20, 0, 0, 0)).put("after", counts(40, 35, 5, 0, 2));
        first.put("metrics", metrics(2));
        var second = sample("load", "LOAD", 2, 20, 20);
        second.put("before", counts(40, 35, 5, 0, 2)).put("after", counts(60, 50, 10, 0, 5));
        second.put("metrics", metrics(5));

        var analysis = BenchmarkTrendAnalyzer.analyse(run(baseline, first, second), POLICY);
        var symptoms = analysis.getJsonArray("findings").stream()
                .map(value -> ((JsonObject) value).getString("symptom")).toList();
        assertTrue(symptoms.contains("RELIABILITY_DETERIORATION"));
        assertTrue(symptoms.contains("ACCUMULATING_PRESSURE"));
    }

    private static JsonObject run(JsonObject... samples) {
        return new JsonObject().put("measurements", new JsonArray(java.util.List.of(samples)));
    }

    private static JsonObject sample(String phase, String kind, int second, long count, long p95) {
        return new JsonObject().put("scenario", "cache").put("phase", phase).put("phaseKind", kind)
                .put("startNanos", second * 1_000_000_000L).put("endNanos", (second + 1L) * 1_000_000_000L)
                .put("latencyDistributions", new JsonObject().put("status", "COLLECTED")
                        .put("series", new JsonObject().put("successfulService",
                                new JsonObject().put("sampleCount", count).put("p95UpperBoundNanos", p95))));
    }

    private static JsonObject counts(long scheduled, long succeeded, long failed, long timedOut, long outstanding) {
        return new JsonObject().put("scheduled", scheduled).put("succeeded", succeeded).put("failed", failed)
                .put("timedOut", timedOut).put("rejected", 0L).put("outstanding", outstanding);
    }

    private static JsonObject metrics(double queued) {
        return new JsonObject().put("scheduler.queued",
                new JsonObject().put("unit", "count").put("value", queued).put("unavailableReason", ""));
    }
}
