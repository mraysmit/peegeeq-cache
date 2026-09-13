package dev.mars.peegeeq.cache.benchmark;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Campaign-scope review derived only from retained run JSON and its campaign manifest. */
public final class BenchmarkCharacterisationReview {
    private record Run(int configuration, String file, JsonObject json, Long peakP95) { }
    private BenchmarkCharacterisationReview() { }

    public static void publish(Path campaignManifest, Path reviewPath) throws IOException {
        var campaign = new JsonObject(Files.readString(campaignManifest));
        int planned = campaign.getInteger("plannedRuns", 0);
        JsonArray declared = campaign.getJsonArray("runs", new JsonArray());
        var loaded = new ArrayList<Run>();
        var limitations = new JsonArray();
        var followUps = new JsonArray();
        var brackets = new JsonArray();
        var curves = new JsonArray();
        int accounted = 0;
        boolean findings = false;
        boolean inconclusive = false;
        for (Object value : declared) {
            var entry = (JsonObject) value;
            if (!"COMPLETED".equals(entry.getString("status"))) continue;
            accounted++;
            String file = entry.getString("evidenceJson");
            if (file == null) {
                limitations.add("Completed campaign entry has no retained evidence path");
                inconclusive = true;
                continue;
            }
            var run = new JsonObject(Files.readString(campaignManifest.resolveSibling(file)));
            String validity = run.getJsonObject("execution", new JsonObject())
                    .getString("measurementValidity", "UNASSESSED");
            if (!"VALID".equals(validity)) {
                limitations.add(file + ": measurement validity is " + validity);
                inconclusive = true;
            }
            Long peak = peakP95(run);
            int configuration = entry.getInteger("configurationIndex", -1);
            loaded.add(new Run(configuration, file, run, peak));
            curves.add(curve(configuration, file, run));
            JsonObject analysis = run.getJsonObject("analysis", new JsonObject());
            String analysisStatus = analysis.getString("status", "NOT_RUN");
            if ("OBSERVED_DEGRADATION".equals(analysisStatus)) findings = true;
            if ("INCONCLUSIVE".equals(analysisStatus) || "NOT_RUN".equals(analysisStatus)) inconclusive = true;
            for (Object item : analysis.getJsonArray("limitations", new JsonArray())) limitations.add(file + ": " + item);
            for (Object item : analysis.getJsonArray("findings", new JsonArray())) {
                var finding = (JsonObject) item;
                brackets.add(new JsonObject().put("evidenceJson", file).put("scenario", finding.getString("scenario"))
                        .put("candidateOnsetNanos", finding.getLong("candidateOnsetNanos"))
                        .put("onsetBracketEndNanos", finding.getLong("onsetBracketEndNanos")));
            }
        }
        if (campaign.getJsonObject("target", new JsonObject()).getBoolean("serverOwned", false)) {
            limitations.add("Results describe a local disposable target and are not external production capacity evidence");
        }
        if (findings) followUps.add("Repeat and refine each observed onset bracket with an independently declared matrix");
        if (inconclusive) followUps.add("Collect the missing reference samples or diagnostics before drawing a capacity conclusion");
        if (findings && loaded.stream().anyMatch(run -> "NO_ONSET_OBSERVED".equals(
                run.json().getJsonObject("analysis", new JsonObject()).getString("status")))) {
            followUps.add("Investigate contradictory repeat outcomes and environmental variation");
        }
        boolean complete = planned > 0 && accounted == planned && loaded.size() == planned;
        String status = !complete ? "INCOMPLETE" : inconclusive ? "COMPLETE_INCONCLUSIVE"
                : findings ? "COMPLETE_WITH_FINDINGS" : "COMPLETE_NO_ONSET_OBSERVED";
        var review = new JsonObject().put("schemaVersion", 1).put("generatedAtUtc", Instant.now().toString())
                .put("status", status).put("campaignManifest", campaignManifest.getFileName().toString())
                .put("completeness", new JsonObject().put("plannedRuns", planned).put("accountedRuns", accounted)
                        .put("loadedEvidenceRuns", loaded.size()).put("complete", complete))
                .put("curves", curves).put("boundaryBrackets", brackets)
                .put("repeatVariation", variation(loaded)).put("limitations", limitations).put("followUps", followUps);
        replace(reviewPath, (review.encodePrettily() + System.lineSeparator()).getBytes(StandardCharsets.UTF_8));
    }

    private static JsonObject curve(int configuration, String file, JsonObject run) {
        var points = new JsonArray();
        for (Object value : run.getJsonArray("measurements", new JsonArray())) {
            var measurement = (JsonObject) value;
            var rates = measurement.getJsonObject("rates", new JsonObject());
            points.add(new JsonObject().put("scenario", measurement.getString("scenario"))
                    .put("phase", measurement.getString("phase")).put("startNanos", measurement.getLong("startNanos"))
                    .put("offeredPerSecond", rates.getDouble("offeredPerSecond"))
                    .put("successfulPerSecond", rates.getDouble("successfulPerSecond"))
                    .put("p95Nanos", p95(measurement)));
        }
        return new JsonObject().put("configurationIndex", configuration).put("evidenceJson", file).put("points", points);
    }

    private static JsonArray variation(List<Run> runs) {
        Map<Integer, List<Long>> groups = new LinkedHashMap<>();
        for (Run run : runs) if (run.peakP95() != null) groups.computeIfAbsent(run.configuration(), ignored -> new ArrayList<>()).add(run.peakP95());
        var output = new JsonArray();
        groups.forEach((configuration, values) -> output.add(new JsonObject().put("configurationIndex", configuration)
                .put("runs", values.size()).put("minimumP95Nanos", values.stream().mapToLong(Long::longValue).min().orElseThrow())
                .put("maximumP95Nanos", values.stream().mapToLong(Long::longValue).max().orElseThrow())));
        return output;
    }

    private static Long peakP95(JsonObject run) {
        Long peak = null;
        for (Object value : run.getJsonArray("measurements", new JsonArray())) {
            Long next = p95((JsonObject) value);
            if (next != null && (peak == null || next > peak)) peak = next;
        }
        return peak;
    }

    private static Long p95(JsonObject measurement) {
        var latency = measurement.getJsonObject("latencyDistributions", new JsonObject());
        var series = latency.getJsonObject("series", new JsonObject());
        var successful = series.getJsonObject("successfulService");
        return successful == null ? null : successful.getLong("p95UpperBoundNanos");
    }

    private static void replace(Path target, byte[] bytes) throws IOException {
        Files.createDirectories(target.toAbsolutePath().normalize().getParent());
        Path temporary = Files.createTempFile(target.getParent(), target.getFileName() + "-", ".tmp");
        try {
            Files.write(temporary, bytes);
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temporary); }
    }
}
