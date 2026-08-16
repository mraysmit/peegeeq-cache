package dev.mars.peegeeq.cache.benchmark;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/** Repeatable benchmark capacity, duration, and acceptance thresholds. */
public record BenchmarkConfig(int concurrency, int poolSize, Duration duration, double minimumThroughput,
                              Duration maximumP99, Duration maximumFailoverRecovery,
                              Duration maximumExpiryLag, double maximumTelemetryOverheadPercent) {

    public BenchmarkConfig {
        if (concurrency <= 0 || poolSize <= concurrency
                || duration.isZero() || duration.isNegative() || minimumThroughput <= 0
                || maximumP99.isZero() || maximumP99.isNegative()
                || maximumFailoverRecovery.isZero() || maximumFailoverRecovery.isNegative()
                || maximumExpiryLag.isZero() || maximumExpiryLag.isNegative()
                || maximumTelemetryOverheadPercent < 0) {
            throw new IllegalArgumentException(
                    "Benchmark values and thresholds must be positive, and poolSize must exceed concurrency "
                            + "(telemetry overhead may be zero)");
        }
    }

    public static BenchmarkConfig fromSystemProperties() {
        int concurrency = Integer.getInteger("peegeeq.benchmark.concurrency", 8);
        return new BenchmarkConfig(
                concurrency,
                Integer.getInteger("peegeeq.benchmark.poolSize", concurrency + 4),
                Duration.ofSeconds(Long.getLong("peegeeq.benchmark.durationSeconds", 30)),
                Double.parseDouble(System.getProperty("peegeeq.benchmark.minimumThroughput", "50")),
                Duration.ofMillis(Long.getLong("peegeeq.benchmark.maximumP99Millis", 1_000)),
                Duration.ofMillis(Long.getLong("peegeeq.benchmark.maximumFailoverRecoveryMillis", 10_000)),
                Duration.ofMillis(Long.getLong("peegeeq.benchmark.maximumExpiryLagMillis", 1_000)),
                Double.parseDouble(System.getProperty(
                        "peegeeq.benchmark.maximumTelemetryOverheadPercent", "100")));
    }

    Map<String, Object> toMap() {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("concurrency", concurrency);
        value.put("poolSize", poolSize);
        value.put("durationSeconds", duration.toSeconds());
        value.put("minimumThroughput", minimumThroughput);
        value.put("maximumP99Milliseconds", maximumP99.toMillis());
        value.put("maximumFailoverRecoveryMilliseconds", maximumFailoverRecovery.toMillis());
        value.put("maximumExpiryLagMilliseconds", maximumExpiryLag.toMillis());
        value.put("maximumTelemetryOverheadPercent", maximumTelemetryOverheadPercent);
        return value;
    }
}
