package dev.mars.peegeeq.cache.benchmark;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;

/** Repeatable benchmark capacity, warm-up, duration, and acceptance thresholds. */
public record BenchmarkConfig(int concurrency, int poolSize, Duration warmup, Duration duration, double minimumThroughput,
                               Duration maximumP99, Duration maximumFailoverRecovery,
                               Duration maximumExpiryLag, double maximumTelemetryOverheadPercent) {

    public BenchmarkConfig {
        if (concurrency <= 0 || poolSize <= concurrency
                || warmup.isNegative() || duration.isZero() || duration.isNegative() || minimumThroughput <= 0
                || maximumP99.isZero() || maximumP99.isNegative()
                || maximumFailoverRecovery.isZero() || maximumFailoverRecovery.isNegative()
                || maximumExpiryLag.isZero() || maximumExpiryLag.isNegative()
                || maximumTelemetryOverheadPercent < 0) {
            throw new IllegalArgumentException(
                    "Benchmark values and thresholds must be positive, warmup must be non-negative, "
                            + "and poolSize must exceed concurrency (telemetry overhead may be zero)");
        }
    }

    public static BenchmarkConfig fromSystemProperties() {
        return fromProperties(System.getProperties());
    }

    static BenchmarkConfig fromProperties(Properties properties) {
        Objects.requireNonNull(properties, "properties");
        int concurrency = integer(properties, "peegeeq.benchmark.concurrency", 8);
        return new BenchmarkConfig(
                concurrency,
                integer(properties, "peegeeq.benchmark.poolSize", concurrency + 4),
                Duration.ofSeconds(longValue(properties, "peegeeq.benchmark.warmupSeconds", 5)),
                Duration.ofSeconds(longValue(properties, "peegeeq.benchmark.durationSeconds", 30)),
                doubleValue(properties, "peegeeq.benchmark.minimumThroughput", 50),
                Duration.ofMillis(longValue(properties, "peegeeq.benchmark.maximumP99Millis", 1_000)),
                Duration.ofMillis(longValue(
                        properties, "peegeeq.benchmark.maximumFailoverRecoveryMillis", 10_000)),
                Duration.ofMillis(longValue(
                        properties, "peegeeq.benchmark.maximumExpiryLagMillis", 1_000)),
                doubleValue(properties,
                        "peegeeq.benchmark.maximumTelemetryOverheadPercent", 100));
    }

    private static int integer(Properties properties, String name, int defaultValue) {
        String value = properties.getProperty(name);
        if (value == null) return defaultValue;
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException failure) {
            throw new IllegalArgumentException(name + " must be an integer", failure);
        }
    }

    private static long longValue(Properties properties, String name, long defaultValue) {
        String value = properties.getProperty(name);
        if (value == null) return defaultValue;
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException failure) {
            throw new IllegalArgumentException(name + " must be an integer", failure);
        }
    }

    private static double doubleValue(Properties properties, String name, double defaultValue) {
        String value = properties.getProperty(name);
        if (value == null) return defaultValue;
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException failure) {
            throw new IllegalArgumentException(name + " must be numeric", failure);
        }
    }

    Map<String, Object> toMap() {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("concurrency", concurrency);
        value.put("poolSize", poolSize);
        value.put("warmupSeconds", warmup.toSeconds());
        value.put("durationSeconds", duration.toSeconds());
        value.put("minimumThroughput", minimumThroughput);
        value.put("maximumP99Milliseconds", maximumP99.toMillis());
        value.put("maximumFailoverRecoveryMilliseconds", maximumFailoverRecovery.toMillis());
        value.put("maximumExpiryLagMilliseconds", maximumExpiryLag.toMillis());
        value.put("maximumTelemetryOverheadPercent", maximumTelemetryOverheadPercent);
        return value;
    }
}
