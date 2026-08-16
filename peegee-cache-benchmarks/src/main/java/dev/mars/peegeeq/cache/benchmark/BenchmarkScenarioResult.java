package dev.mars.peegeeq.cache.benchmark;

import dev.mars.peegeeq.cache.test.LatencyHistogram;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** A named, structured benchmark measurement. */
public record BenchmarkScenarioResult(String name, long operations, double throughputPerSecond,
                                      double p50Milliseconds, double p95Milliseconds,
                                      double p99Milliseconds) {

    public BenchmarkScenarioResult {
        Objects.requireNonNull(name, "name");
        if (name.isBlank() || operations < 0 || throughputPerSecond < 0
                || p50Milliseconds < 0 || p95Milliseconds < 0 || p99Milliseconds < 0) {
            throw new IllegalArgumentException("Benchmark scenario values must be non-negative and named");
        }
    }

    static BenchmarkScenarioResult from(String name, LatencyHistogram.Snapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        return new BenchmarkScenarioResult(name, snapshot.operations(), snapshot.throughputPerSecond(),
                millis(snapshot.p50()), millis(snapshot.p95()), millis(snapshot.p99()));
    }

    Map<String, Object> toMap() {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("name", name);
        value.put("operations", operations);
        value.put("throughputPerSecond", throughputPerSecond);
        value.put("p50Milliseconds", p50Milliseconds);
        value.put("p95Milliseconds", p95Milliseconds);
        value.put("p99Milliseconds", p99Milliseconds);
        return value;
    }

    private static double millis(Duration value) {
        return value.toNanos() / 1_000_000.0;
    }
}
