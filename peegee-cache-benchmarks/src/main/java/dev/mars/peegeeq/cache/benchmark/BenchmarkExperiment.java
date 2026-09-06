package dev.mars.peegeeq.cache.benchmark;

import java.time.Duration;
import java.util.Objects;

/**
 * Versioned, budget-checked experiment plan. Resolves run descriptors, not processes or workloads.
 * Order is configuration, fork, repetition. A shared workload seed permits matched datasets;
 * this seed does not imply randomised execution order or statistical independence.
 */
public final class BenchmarkExperiment {
    public static final int SCHEMA_VERSION = 1;

    public record Run(int schemaVersion, String experimentId, long configurationIndex, int forkIndex,
                       int repetitionIndex, long workloadSeed, BenchmarkParameters parameters,
                       BenchmarkTimeline timeline) { }

    private final String experimentId;
    private final BenchmarkTimeline timeline;
    private final BenchmarkParameterMatrix matrix;
    private final int repetitions;
    private final long runsPerConfiguration;
    private final long workloadSeed;
    private final long runCount;
    private final Duration plannedDuration;

    public BenchmarkExperiment(int schemaVersion, String experimentId, BenchmarkTimeline timeline,
                                BenchmarkParameterMatrix matrix, int repetitions, int forks,
                                long workloadSeed, long maximumRuns) {
        this.experimentId = Objects.requireNonNull(experimentId, "experimentId");
        this.timeline = Objects.requireNonNull(timeline, "timeline");
        this.matrix = Objects.requireNonNull(matrix, "matrix");
        if (schemaVersion != SCHEMA_VERSION || experimentId.isBlank()
                || repetitions <= 0 || forks <= 0 || maximumRuns <= 0) {
            throw new IllegalArgumentException("Experiment version, identity, repetitions, forks and run budget must be valid");
        }
        this.repetitions = repetitions;
        this.workloadSeed = workloadSeed;
        try {
            runsPerConfiguration = Math.multiplyExact((long) repetitions, forks);
            runCount = Math.multiplyExact(matrix.configurationCount(), runsPerConfiguration);
            if (runCount > maximumRuns) throw new IllegalArgumentException("Experiment exceeds run budget: " + runCount);
            plannedDuration = timeline.duration().multipliedBy(runCount);
        } catch (ArithmeticException overflow) {
            throw new IllegalArgumentException("Experiment count or planned duration exceeds supported range", overflow);
        }
    }

    public long runCount() { return runCount; }

    /** Sum of phase durations, excluding provisioning, process startup and uncontrolled reset time. */
    public Duration plannedDuration() { return plannedDuration; }

    public Run runAt(long index) {
        if (index < 0 || index >= runCount) throw new IndexOutOfBoundsException("Run index: " + index);
        long configurationIndex = index / runsPerConfiguration;
        long withinConfiguration = index % runsPerConfiguration;
        return new Run(SCHEMA_VERSION, experimentId, configurationIndex,
                (int) (withinConfiguration / repetitions), (int) (withinConfiguration % repetitions),
                workloadSeed, matrix.configurationAt(configurationIndex), timeline);
    }
}
