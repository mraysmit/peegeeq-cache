package dev.mars.peegeeq.cache.benchmark;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.nio.file.Path;
import java.util.Objects;

/** Complete immutable framework specification resolved and budgeted before workload launch. */
public record BenchmarkSpecification(int version, String experimentId, BenchmarkTimeline timeline,
                                     BenchmarkParameterMatrix matrix, int repetitions, int forks,
                                     long workloadSeed, long maximumRuns,
                                     BenchmarkScenarioParameters scenario,
                                     BenchmarkRuntimePolicy runtime,
                                     BenchmarkPersistencePolicy persistence,
                                     BenchmarkCapabilityInventory capabilities,
                                     Path evidenceDirectory) {
    public record Resolved(BenchmarkExperiment experiment,
                           BenchmarkPersistencePolicy.Assessment persistence,
                           JsonObject manifest) { }

    public BenchmarkSpecification {
        if (version != 1 || experimentId == null || experimentId.isBlank()
                || repetitions <= 0 || forks <= 0 || maximumRuns <= 0) {
            throw new IllegalArgumentException("Specification identity and finite run controls are invalid");
        }
        Objects.requireNonNull(timeline, "timeline"); Objects.requireNonNull(matrix, "matrix");
        Objects.requireNonNull(scenario, "scenario"); Objects.requireNonNull(runtime, "runtime");
        Objects.requireNonNull(persistence, "persistence"); Objects.requireNonNull(capabilities, "capabilities");
        Objects.requireNonNull(evidenceDirectory, "evidenceDirectory");
    }

    public Resolved resolve(long estimatedIntervals, long estimatedBytesPerInterval, long estimatedMetadataBytes) {
        var experiment = new BenchmarkExperiment(version, experimentId, timeline, matrix,
                repetitions, forks, workloadSeed, maximumRuns);
        var assessment = persistence.assess(estimatedIntervals, estimatedBytesPerInterval, estimatedMetadataBytes);
        if (!assessment.supported()) {
            throw new IllegalArgumentException("Persistence preflight rejected experiment: " + assessment.reasons());
        }
        var phases = new JsonArray();
        timeline.phases().forEach(phase -> phases.add(new JsonObject().put("name", phase.name())
                .put("kind", phase.kind().name()).put("durationNanos", phase.duration().toNanos())));
        var configurations = new JsonArray();
        for (long index = 0; index < matrix.configurationCount(); index++) {
            var value = matrix.configurationAt(index);
            configurations.add(new JsonObject().put("index", index).put("loadModel", value.loadModel().name())
                    .put("concurrency", value.concurrency()).put("poolSize", value.poolSize())
                    .put("offeredPerSecond", value.offeredPerSecond()).put("queueCapacity", value.queueCapacity())
                    .put("operationTimeoutNanos", value.operationTimeout().toNanos()));
        }
        var capabilityJson = new JsonObject();
        capabilities.capabilities().forEach((name, value) -> capabilityJson.put(name,
                new JsonObject().put("status", value.status().name()).put("detail", value.detail())));
        var manifest = new JsonObject().put("schemaVersion", version).put("experimentId", experimentId)
                .put("runCount", experiment.runCount()).put("plannedDurationNanos", experiment.plannedDuration().toNanos())
                .put("repetitions", repetitions).put("forks", forks).put("workloadSeed", workloadSeed)
                .put("evidenceDirectory", evidenceDirectory.toString()).put("samplingIntervalNanos", timeline.samplingInterval().toNanos())
                .put("phases", phases).put("configurations", configurations)
                .put("scenario", new JsonObject().put("datasetCardinality", scenario.datasetCardinality())
                        .put("payloadBytes", scenario.payloadBytes()).put("targetHitRatio", scenario.targetHitRatio())
                        .put("keyDistribution", scenario.keyDistribution().name()).put("hotSetSize", scenario.hotSetSize())
                        .put("hotRequestFraction", scenario.hotRequestFraction()).put("ttlNanos", scenario.ttl().toNanos())
                        .put("telemetryMode", scenario.telemetryMode().name()))
                .put("runtime", new JsonObject().put("maximumHeapBytes", runtime.maximumHeapBytes())
                        .put("maximumProcessCpu", runtime.maximumProcessCpu())
                        .put("maximumEventLoopDelayNanos", runtime.maximumEventLoopDelay().toNanos())
                        .put("requireDiagnostics", runtime.requireDiagnostics()))
                .put("persistence", new JsonObject().put("policyVersion", persistence.version())
                        .put("intervalsPerCheckpoint", persistence.intervalsPerCheckpoint())
                        .put("estimatedFinalBytes", assessment.estimatedFinalBytes())
                        .put("estimatedCumulativeWriteBytes", assessment.estimatedCumulativeWriteBytes())
                        .put("estimatedPeakDiskBytes", assessment.estimatedPeakDiskBytes()))
                .put("capabilities", capabilityJson);
        return new Resolved(experiment, assessment, manifest);
    }
}
