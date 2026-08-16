package dev.mars.peegeeq.cache.benchmark;

import java.time.Duration;

/** Repeatable benchmark duration, concurrency, and acceptance thresholds. */
public record BenchmarkConfig(int concurrency, Duration duration, double minimumThroughput,
                              Duration maximumP99, Duration maximumFailoverRecovery,
                              Duration maximumExpiryLag) {

    public BenchmarkConfig {
        if (concurrency <= 0 || duration.isZero() || duration.isNegative() || minimumThroughput <= 0
                || maximumP99.isZero() || maximumP99.isNegative()
                || maximumFailoverRecovery.isZero() || maximumFailoverRecovery.isNegative()
                || maximumExpiryLag.isZero() || maximumExpiryLag.isNegative()) {
            throw new IllegalArgumentException("Benchmark values and thresholds must be positive");
        }
    }

    public static BenchmarkConfig fromSystemProperties() {
        return new BenchmarkConfig(
                Integer.getInteger("peegeeq.benchmark.concurrency", 8),
                Duration.ofSeconds(Long.getLong("peegeeq.benchmark.durationSeconds", 30)),
                Double.parseDouble(System.getProperty("peegeeq.benchmark.minimumThroughput", "50")),
                Duration.ofMillis(Long.getLong("peegeeq.benchmark.maximumP99Millis", 1_000)),
                Duration.ofMillis(Long.getLong("peegeeq.benchmark.maximumFailoverRecoveryMillis", 10_000)),
                Duration.ofMillis(Long.getLong("peegeeq.benchmark.maximumExpiryLagMillis", 1_000)));
    }
}
