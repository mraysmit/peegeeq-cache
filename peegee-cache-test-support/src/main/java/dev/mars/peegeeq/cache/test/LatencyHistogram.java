package dev.mars.peegeeq.cache.test;

import java.time.Duration;
import java.util.Arrays;

/** Allocation-conscious latency sample collector for repeatable integration benchmarks. */
public final class LatencyHistogram {

    private long[] samples;
    private int size;

    public LatencyHistogram(int expectedSamples) {
        if (expectedSamples <= 0) {
            throw new IllegalArgumentException("expectedSamples must be > 0");
        }
        samples = new long[expectedSamples];
    }

    public synchronized void record(Duration latency) {
        if (size == samples.length) {
            samples = Arrays.copyOf(samples, samples.length * 2);
        }
        samples[size++] = latency.toNanos();
    }

    public synchronized Snapshot snapshot(Duration elapsed) {
        if (size == 0) {
            return new Snapshot(0, Duration.ZERO, Duration.ZERO, Duration.ZERO, 0);
        }
        long[] sorted = Arrays.copyOf(samples, size);
        Arrays.sort(sorted);
        double throughput = elapsed.isZero() ? 0 : size / (elapsed.toNanos() / 1_000_000_000.0);
        return new Snapshot(size, percentile(sorted, 0.50), percentile(sorted, 0.95),
                percentile(sorted, 0.99), throughput);
    }

    private static Duration percentile(long[] sorted, double percentile) {
        int index = (int) Math.ceil(percentile * sorted.length) - 1;
        return Duration.ofNanos(sorted[Math.max(0, Math.min(index, sorted.length - 1))]);
    }

    public record Snapshot(long operations, Duration p50, Duration p95, Duration p99,
                           double throughputPerSecond) { }
}
