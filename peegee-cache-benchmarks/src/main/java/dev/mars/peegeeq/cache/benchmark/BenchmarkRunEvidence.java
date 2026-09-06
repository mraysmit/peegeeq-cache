package dev.mars.peegeeq.cache.benchmark;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Immutable checkpoint of one actual execution, independent of planned repetition identity. */
public record BenchmarkRunEvidence(UUID executionId, BenchmarkExperiment.Run plan,
                                    Instant startedAt, Instant updatedAt, Status status,
                                    Validity validity, String detail, Map<String, String> environment,
                                    List<Measurement> measurements, List<Diagnostic> diagnostics) {
    public enum Status { RUNNING, COMPLETED, FAILED, STOPPED }
    public enum Validity { UNASSESSED, VALID, INVALID }

    /** Missing values have an explicit reason rather than a fabricated zero. */
    public record Metric(String unit, Double value, String unavailableReason) {
        public Metric {
            named(unit, "metric unit");
            Objects.requireNonNull(unavailableReason, "unavailableReason");
            if (value == null ? unavailableReason.isBlank() : !Double.isFinite(value) || !unavailableReason.isEmpty()) {
                throw new IllegalArgumentException("Metric requires a finite value or an unavailable reason, not both");
            }
        }
    }

    public record Measurement(String scenario, String operationUnit, String phase,
                               BenchmarkInterval interval, Map<String, Metric> metrics,
                               Map<String, BenchmarkLatencyDistribution> distributions) {
        public Measurement(String scenario, String operationUnit, String phase,
                           BenchmarkInterval interval, Map<String, Metric> metrics) {
            this(scenario, operationUnit, phase, interval, metrics, Map.of());
        }

        public Measurement {
            named(scenario, "scenario");
            named(operationUnit, "operationUnit");
            named(phase, "phase");
            Objects.requireNonNull(interval, "interval");
            metrics = Map.copyOf(metrics);
            metrics.keySet().forEach(key -> named(key, "metric name"));
            distributions = Map.copyOf(distributions);
            if (!distributions.isEmpty()) {
                if (!distributions.keySet().equals(new java.util.HashSet<>(BenchmarkIntervalRecorder.SERIES))) {
                    throw new IllegalArgumentException("Collected latency requires all six named outcome series");
                }
                long[] outcomes = {interval.succeeded(), interval.failed(), interval.timedOut()};
                for (int index = 0; index < BenchmarkIntervalRecorder.SERIES.size(); index++) {
                    if (distributions.get(BenchmarkIntervalRecorder.SERIES.get(index)).sampleCount() != outcomes[index / 2]) {
                        throw new IllegalArgumentException("Latency sample counts must match their interval outcome counts");
                    }
                }
            }
        }
    }

    public record Diagnostic(long elapsedNanos, String level, String message) {
        public Diagnostic {
            if (elapsedNanos < 0) throw new IllegalArgumentException("Diagnostic offset must not be negative");
            named(level, "level");
            named(message, "message");
        }
    }

    public BenchmarkRunEvidence {
        Objects.requireNonNull(executionId, "executionId");
        Objects.requireNonNull(plan, "plan");
        named(plan.experimentId(), "experimentId");
        if (plan.schemaVersion() != BenchmarkExperiment.SCHEMA_VERSION || plan.configurationIndex() < 0
                || plan.forkIndex() < 0 || plan.repetitionIndex() < 0) {
            throw new IllegalArgumentException("Unsupported plan version or negative run identity");
        }
        Objects.requireNonNull(plan.parameters(), "parameters");
        Objects.requireNonNull(plan.timeline(), "timeline");
        Objects.requireNonNull(startedAt, "startedAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(validity, "validity");
        Objects.requireNonNull(detail, "detail");
        if (updatedAt.isBefore(startedAt)) throw new IllegalArgumentException("Checkpoint precedes run start");
        if ((status == Status.FAILED || status == Status.STOPPED || validity == Validity.INVALID) && detail.isBlank()) {
            throw new IllegalArgumentException("Failure, stop and invalid measurements require a reason");
        }
        environment = Map.copyOf(environment);
        measurements = List.copyOf(measurements);
        diagnostics = List.copyOf(diagnostics);
        environment.keySet().forEach(key -> named(key, "environment field"));
        var last = new HashMap<String, Measurement>();
        var phases = plan.timeline().phases().stream().map(BenchmarkTimeline.Phase::name).toList();
        for (Measurement measurement : measurements) {
            if (!phases.contains(measurement.phase())) throw new IllegalArgumentException("Unknown measurement phase");
            Measurement previous = last.put(measurement.scenario(), measurement);
            if (previous != null && (!previous.operationUnit().equals(measurement.operationUnit())
                    || previous.interval().endNanos() != measurement.interval().startNanos()
                    || !previous.interval().after().equals(measurement.interval().before()))) {
                throw new IllegalArgumentException("Scenario measurement history must be contiguous and conserve counters");
            }
        }
    }

    /** Returns a new JSON tree; callers cannot mutate this checkpoint through the returned value. */
    public JsonObject toJson() {
        var parameters = plan.parameters();
        var phases = new JsonArray();
        long offset = 0;
        for (var phase : plan.timeline().phases()) {
            phases.add(new JsonObject().put("name", phase.name()).put("kind", phase.kind().name())
                    .put("startNanos", offset).put("durationNanos", phase.duration().toNanos()));
            offset += phase.duration().toNanos();
        }
        var samples = new JsonArray();
        measurements.forEach(measurement -> samples.add(measurementJson(measurement)));
        var events = new JsonArray();
        diagnostics.forEach(event -> events.add(new JsonObject().put("elapsedNanos", event.elapsedNanos())
                .put("level", event.level()).put("message", event.message())));
        var context = new JsonObject();
        environment.forEach(context::put);
        return new JsonObject().put("schemaVersion", 1).put("executionId", executionId.toString())
                .put("plan", new JsonObject().put("schemaVersion", plan.schemaVersion())
                        .put("experimentId", plan.experimentId()).put("configurationIndex", plan.configurationIndex())
                        .put("forkIndex", plan.forkIndex()).put("repetitionIndex", plan.repetitionIndex())
                        .put("workloadSeed", plan.workloadSeed()))
                .put("execution", new JsonObject().put("startedAtUtc", startedAt.toString())
                        .put("checkpointAtUtc", updatedAt.toString()).put("status", status.name())
                        .put("finalised", status != Status.RUNNING).put("measurementValidity", validity.name())
                        .put("detail", detail))
                .put("configuration", new JsonObject().put("loadModel", parameters.loadModel().name())
                        .put("concurrency", parameters.concurrency()).put("poolSize", parameters.poolSize())
                        .put("offeredPerSecond", parameters.offeredPerSecond()).put("queueCapacity", parameters.queueCapacity())
                        .put("operationTimeoutNanos", parameters.operationTimeout().toNanos()))
                .put("timeline", new JsonObject().put("samplingIntervalNanos", plan.timeline().samplingInterval().toNanos())
                        .put("plannedDurationNanos", plan.timeline().duration().toNanos()).put("phases", phases))
                .put("environment", context).put("measurements", samples).put("diagnostics", events)
                .put("analysis", new JsonObject().put("status", "NOT_RUN")
                        .put("reason", "Trend analysis is not connected to this evidence contract yet"));
    }

    private static JsonObject measurementJson(Measurement measurement) {
        var interval = measurement.interval();
        var metrics = new JsonObject();
        measurement.metrics().forEach((name, metric) -> metrics.put(name, new JsonObject()
                .put("unit", metric.unit()).put("value", metric.value()).put("unavailableReason", metric.unavailableReason())));
        return new JsonObject().put("scenario", measurement.scenario()).put("operationUnit", measurement.operationUnit())
                .put("phase", measurement.phase()).put("startNanos", interval.startNanos()).put("endNanos", interval.endNanos())
                .put("before", countsJson(interval.before())).put("after", countsJson(interval.after()))
                .put("rates", new JsonObject().put("unit", measurement.operationUnit() + "/second")
                        .put("offeredPerSecond", interval.offeredPerSecond()).put("completedPerSecond", interval.completedPerSecond())
                        .put("successfulPerSecond", interval.succeededPerSecond()))
                .put("metrics", metrics).put("latencyDistributions", distributionsJson(measurement.distributions()));
    }

    private static JsonObject distributionsJson(Map<String, BenchmarkLatencyDistribution> distributions) {
        if (distributions.isEmpty()) return new JsonObject().put("status", "NOT_COLLECTED")
                .put("reason", "No latency distributions supplied for this interval");
        var series = new JsonObject();
        distributions.forEach((name, value) -> series.put(name, value.toJson()));
        return new JsonObject().put("status", "COLLECTED").put("series", series);
    }

    private static JsonObject countsJson(BenchmarkWorkCounts counts) {
        return new JsonObject().put("scheduled", counts.scheduled()).put("admitted", counts.admitted())
                .put("started", counts.started()).put("succeeded", counts.succeeded()).put("failed", counts.failed())
                .put("timedOut", counts.timedOut()).put("rejected", counts.rejected())
                .put("expiredBeforeStart", counts.expiredBeforeStart()).put("pendingAdmission", counts.pendingAdmission())
                .put("queued", counts.queued()).put("inFlight", counts.inFlight()).put("outstanding", counts.outstanding());
    }

    private static void named(String value, String field) {
        Objects.requireNonNull(value, field);
        if (value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
    }
}
