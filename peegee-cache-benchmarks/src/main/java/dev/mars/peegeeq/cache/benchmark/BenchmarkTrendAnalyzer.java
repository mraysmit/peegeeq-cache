package dev.mars.peegeeq.cache.benchmark;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Interpretable interval detector for persistent p95 deterioration and recovery. */
public final class BenchmarkTrendAnalyzer {
    private record Point(String scenario, String kind, long start, long end, long samples, Long p95) { }
    private BenchmarkTrendAnalyzer() { }

    public static JsonObject analyse(JsonObject run, BenchmarkAnalysisPolicy policy) {
        Objects.requireNonNull(run, "run");
        Objects.requireNonNull(policy, "policy");
        var byScenario = points(run);
        var findings = new JsonArray();
        var isolatedSpikes = new JsonArray();
        var limitations = new JsonArray();
        boolean hasReference = false;
        for (var scenario : byScenario.entrySet()) {
            Point reference = scenario.getValue().stream()
                    .filter(point -> policy.referencePhaseKind().equals(point.kind()))
                    .filter(point -> point.samples() >= policy.minimumSamples() && point.p95() != null)
                    .findFirst().orElse(null);
            if (reference == null) {
                limitations.add(scenario.getKey() + ": no reference interval with sufficient samples");
                continue;
            }
            hasReference = true;
            detect(scenario.getKey(), scenario.getValue(), reference, policy, findings, isolatedSpikes);
        }
        detectReliabilityAndPressure(run, policy, findings);
        String status = !hasReference ? "INCONCLUSIVE" : findings.isEmpty() ? "NO_ONSET_OBSERVED" : "OBSERVED_DEGRADATION";
        String reason = !hasReference ? "No scenario has a reference interval with sufficient samples"
                : findings.isEmpty() ? "No persistent onset was observed in the tested range and timeframe" : "";
        return new JsonObject().put("status", status).put("reason", reason)
                .put("policyVersion", policy.version())
                .put("policy", new JsonObject().put("referencePhaseKind", policy.referencePhaseKind())
                        .put("minimumSamples", policy.minimumSamples()).put("latencyRatio", policy.latencyRatio())
                        .put("persistenceIntervals", policy.persistenceIntervals())
                        .put("recoveryRatio", policy.recoveryRatio())
                        .put("maximumAdverseOutcomeFraction", policy.maximumAdverseOutcomeFraction())
                        .put("minimumPressureGrowth", policy.minimumPressureGrowth())
                        .put("analysisWindowNanos", policy.analysisWindow().toNanos()))
                .put("findings", findings).put("isolatedSpikes", isolatedSpikes).put("limitations", limitations);
    }

    private static void detect(String scenario, List<Point> points, Point reference,
                               BenchmarkAnalysisPolicy policy, JsonArray findings, JsonArray isolatedSpikes) {
        int streak = 0;
        Point candidate = null;
        Point confirmation = null;
        double maximum = 0;
        for (Point point : points) {
            if (point.start() <= reference.start() || "RECOVERY".equals(point.kind())
                    || point.samples() < policy.minimumSamples() || point.p95() == null) continue;
            double ratio = (double) point.p95() / reference.p95();
            if (ratio >= policy.latencyRatio()) {
                if (streak++ == 0) candidate = point;
                maximum = Math.max(maximum, ratio);
                if (streak == policy.persistenceIntervals()) { confirmation = point; break; }
            } else {
                if (streak > 0 && candidate != null) isolatedSpikes.add(new JsonObject()
                        .put("scenario", scenario).put("metric", "successfulService.p95UpperBoundNanos")
                        .put("startNanos", candidate.start()).put("intervals", streak).put("maximumRatio", maximum));
                streak = 0; candidate = null; maximum = 0;
            }
        }
        if (confirmation == null) {
            if (streak > 0 && candidate != null) isolatedSpikes.add(new JsonObject()
                    .put("scenario", scenario).put("metric", "successfulService.p95UpperBoundNanos")
                    .put("startNanos", candidate.start()).put("intervals", streak).put("maximumRatio", maximum));
            return;
        }
        for (Point point : points) {
            if (point.start() > confirmation.start() && point.p95() != null && point.samples() >= policy.minimumSamples()) {
                maximum = Math.max(maximum, (double) point.p95() / reference.p95());
            }
        }
        Point confirmed = confirmation;
        boolean recovered = points.stream().anyMatch(point -> point.start() > confirmed.start()
                && "RECOVERY".equals(point.kind()) && point.samples() >= policy.minimumSamples()
                && point.p95() != null && (double) point.p95() / reference.p95() <= policy.recoveryRatio());
        findings.add(new JsonObject().put("id", "latency-" + scenario + "-" + candidate.start())
                .put("scenario", scenario).put("symptom", "LATENCY_DETERIORATION")
                .put("metric", "successfulService.p95UpperBoundNanos")
                .put("referenceStartNanos", reference.start()).put("referenceValue", reference.p95())
                .put("candidateOnsetNanos", candidate.start()).put("confirmationNanos", confirmation.start())
                .put("onsetBracketEndNanos", candidate.end()).put("maximumRatio", maximum)
                .put("persistenceIntervals", policy.persistenceIntervals())
                .put("recovery", recovered ? "RECOVERED" : "NOT_OBSERVED"));
    }

    private static void detectReliabilityAndPressure(JsonObject run, BenchmarkAnalysisPolicy policy, JsonArray findings) {
        Map<String, List<JsonObject>> scenarios = new LinkedHashMap<>();
        for (Object value : run.getJsonArray("measurements", new JsonArray())) {
            var measurement = (JsonObject) value;
            scenarios.computeIfAbsent(measurement.getString("scenario"), ignored -> new ArrayList<>()).add(measurement);
        }
        scenarios.forEach((scenario, measurements) -> {
            int reliabilityStreak = 0;
            long reliabilityStart = 0;
            double maximumAdverse = 0;
            int pressureStreak = 0;
            long pressureStart = 0;
            Double priorPressure = null;
            double maximumPressure = 0;
            boolean reliabilityReported = false;
            boolean pressureReported = false;
            for (JsonObject measurement : measurements) {
                JsonObject before = measurement.getJsonObject("before");
                JsonObject after = measurement.getJsonObject("after");
                if (!reliabilityReported && before != null && after != null) {
                    long success = delta(before, after, "succeeded");
                    long failed = delta(before, after, "failed");
                    long timedOut = delta(before, after, "timedOut");
                    long rejected = delta(before, after, "rejected");
                    long outcomes = success + failed + timedOut + rejected;
                    double adverse = outcomes == 0 ? 0 : (double) (failed + timedOut + rejected) / outcomes;
                    if (outcomes >= policy.minimumSamples() && adverse >= policy.maximumAdverseOutcomeFraction()) {
                        if (reliabilityStreak++ == 0) reliabilityStart = measurement.getLong("startNanos", 0L);
                        maximumAdverse = Math.max(maximumAdverse, adverse);
                        if (reliabilityStreak == policy.persistenceIntervals()) {
                            findings.add(new JsonObject().put("id", "reliability-" + scenario + "-" + reliabilityStart)
                                    .put("scenario", scenario).put("symptom", "RELIABILITY_DETERIORATION")
                                    .put("metric", "adverseOutcomeFraction").put("candidateOnsetNanos", reliabilityStart)
                                    .put("confirmationNanos", measurement.getLong("startNanos", 0L))
                                    .put("onsetBracketEndNanos", measurement.getLong("endNanos", 0L))
                                    .put("maximumFraction", maximumAdverse));
                            reliabilityReported = true;
                        }
                    } else { reliabilityStreak = 0; maximumAdverse = 0; }
                }
                if (!pressureReported) {
                    Double pressure = metric(measurement, "scheduler.queued");
                    if (pressure == null && after != null) pressure = after.getLong("outstanding", 0L).doubleValue();
                    if (pressure != null && priorPressure != null
                            && pressure - priorPressure >= policy.minimumPressureGrowth()) {
                        if (pressureStreak++ == 0) pressureStart = measurement.getLong("startNanos", 0L);
                        maximumPressure = Math.max(maximumPressure, pressure);
                        // A streak is expressed in observed pressure points, including its predecessor.
                        if (pressureStreak + 1 >= policy.persistenceIntervals()) {
                            findings.add(new JsonObject().put("id", "pressure-" + scenario + "-" + pressureStart)
                                    .put("scenario", scenario).put("symptom", "ACCUMULATING_PRESSURE")
                                    .put("metric", "scheduler.queued").put("candidateOnsetNanos", pressureStart)
                                    .put("confirmationNanos", measurement.getLong("startNanos", 0L))
                                    .put("onsetBracketEndNanos", measurement.getLong("endNanos", 0L))
                                    .put("maximumObserved", maximumPressure));
                            pressureReported = true;
                        }
                    } else if (priorPressure != null) { pressureStreak = 0; maximumPressure = 0; }
                    priorPressure = pressure;
                }
            }
        });
    }

    private static long delta(JsonObject before, JsonObject after, String name) {
        return after.getLong(name, 0L) - before.getLong(name, 0L);
    }

    private static Double metric(JsonObject measurement, String name) {
        JsonObject value = measurement.getJsonObject("metrics", new JsonObject()).getJsonObject(name);
        return value == null || value.getValue("value") == null ? null : value.getDouble("value");
    }

    private static Map<String, List<Point>> points(JsonObject run) {
        Map<String, String> phaseKinds = new LinkedHashMap<>();
        JsonObject timeline = run.getJsonObject("timeline");
        if (timeline != null && timeline.getJsonArray("phases") != null) {
            for (Object value : timeline.getJsonArray("phases")) {
                var phase = (JsonObject) value;
                phaseKinds.put(phase.getString("name"), phase.getString("kind"));
            }
        }
        var result = new LinkedHashMap<String, List<Point>>();
        JsonArray measurements = run.getJsonArray("measurements", new JsonArray());
        for (Object value : measurements) {
            var measurement = (JsonObject) value;
            String scenario = measurement.getString("scenario");
            String phase = measurement.getString("phase");
            String kind = measurement.getString("phaseKind", phaseKinds.getOrDefault(phase, phase));
            JsonObject latency = measurement.getJsonObject("latencyDistributions", new JsonObject());
            JsonObject series = latency.getJsonObject("series", new JsonObject());
            JsonObject successful = series.getJsonObject("successfulService");
            long samples = successful == null ? 0 : successful.getLong("sampleCount", 0L);
            Long p95 = successful == null ? null : successful.getLong("p95UpperBoundNanos");
            result.computeIfAbsent(scenario, ignored -> new ArrayList<>()).add(new Point(scenario, kind,
                    measurement.getLong("startNanos", 0L), measurement.getLong("endNanos", 0L), samples, p95));
        }
        return result;
    }
}
