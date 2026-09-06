package dev.mars.peegeeq.cache.benchmark;

import java.util.Objects;

/**
 * Observed interval deltas between coherent cumulative snapshots from the same run/scenario.
 * Boundaries are elapsed nanoseconds, not planned timer cadence. Started and completed counts
 * are deliberately independent within an interval: prior work can finish in a later window.
 * Latency distributions and physical-attempt outcomes are separate measurement contracts.
 */
public record BenchmarkInterval(long startNanos, long endNanos,
                                BenchmarkWorkCounts before, BenchmarkWorkCounts after) {
    public BenchmarkInterval {
        Objects.requireNonNull(before, "before");
        Objects.requireNonNull(after, "after");
        if (startNanos < 0 || endNanos <= startNanos) {
            throw new IllegalArgumentException("Observed interval must have positive elapsed duration");
        }
        if (!after.follows(before)) {
            throw new IllegalArgumentException("Cumulative counters must not reset or reclassify outcomes");
        }
    }

    public long scheduled() { return after.scheduled() - before.scheduled(); }
    public long admitted() { return after.admitted() - before.admitted(); }
    public long started() { return after.started() - before.started(); }
    public long succeeded() { return after.succeeded() - before.succeeded(); }
    public long failed() { return after.failed() - before.failed(); }
    public long timedOut() { return after.timedOut() - before.timedOut(); }
    public long rejected() { return after.rejected() - before.rejected(); }
    public long expiredBeforeStart() { return after.expiredBeforeStart() - before.expiredBeforeStart(); }
    public long completed() { return after.completed() - before.completed(); }
    public double offeredPerSecond() { return perSecond(scheduled()); }
    public double completedPerSecond() { return perSecond(completed()); }
    public double succeededPerSecond() { return perSecond(succeeded()); }

    private double perSecond(long count) {
        return count / ((endNanos - startNanos) / 1_000_000_000.0);
    }
}
