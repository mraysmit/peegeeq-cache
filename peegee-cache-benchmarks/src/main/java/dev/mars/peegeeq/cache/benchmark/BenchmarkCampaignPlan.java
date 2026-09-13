package dev.mars.peegeeq.cache.benchmark;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Random;

/** Versioned campaign ordering, reset policy and isolated per-run resource resolution. */
public record BenchmarkCampaignPlan(int version, BenchmarkExperiment experiment,
                                    BenchmarkDeploymentTarget target, Order order,
                                    Reset reset, long orderSeed) {
    public enum Order { DECLARED, SEEDED_RANDOM }
    public enum Reset { RECREATE_RUN_SCHEMA, CLEAR_RUN_NAMESPACE }
    public record Manifest(long executionOrder, BenchmarkExperiment.Run run, String resourceName,
                           String targetId, Reset reset) { }

    public BenchmarkCampaignPlan {
        if (version != 1) throw new IllegalArgumentException("Unsupported campaign plan version");
        Objects.requireNonNull(experiment, "experiment");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(order, "order");
        Objects.requireNonNull(reset, "reset");
        if (target.kind() == BenchmarkDeploymentTarget.Kind.EXTERNAL && reset == Reset.RECREATE_RUN_SCHEMA
                && !target.schemaPrefix().startsWith("benchmark_")) {
            throw new IllegalArgumentException("External schema reconstruction requires a benchmark-owned prefix");
        }
    }

    public List<Manifest> resolve() {
        if (experiment.runCount() > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Resolved campaign order exceeds in-memory manifest limit");
        }
        var runs = new ArrayList<BenchmarkExperiment.Run>((int) experiment.runCount());
        for (long index = 0; index < experiment.runCount(); index++) runs.add(experiment.runAt(index));
        if (order == Order.SEEDED_RANDOM) Collections.shuffle(runs, new Random(orderSeed));
        var manifests = new ArrayList<Manifest>(runs.size());
        for (int index = 0; index < runs.size(); index++) {
            var run = runs.get(index);
            String suffix = Long.toString(run.configurationIndex(), 36) + "_f" + run.forkIndex()
                    + "_r" + run.repetitionIndex();
            String resource = target.schemaPrefix() + "_" + suffix;
            if (resource.length() > 63) throw new IllegalArgumentException("Resolved schema exceeds PostgreSQL identifier limit");
            manifests.add(new Manifest(index, run, resource, target.id(), reset));
        }
        return List.copyOf(manifests);
    }
}
