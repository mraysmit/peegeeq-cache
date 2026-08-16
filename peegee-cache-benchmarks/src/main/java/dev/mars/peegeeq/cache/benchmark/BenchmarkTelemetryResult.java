package dev.mars.peegeeq.cache.benchmark;

import java.util.Map;

/** Measured cost of enabled Micrometer telemetry relative to the no-op baseline. */
public record BenchmarkTelemetryResult(double throughputOverheadPercent, double p99OverheadPercent) {

    public BenchmarkTelemetryResult {
        if (!Double.isFinite(throughputOverheadPercent) || !Double.isFinite(p99OverheadPercent)) {
            throw new IllegalArgumentException("Telemetry measurements must be finite");
        }
    }

    Map<String, Object> toMap() {
        return Map.of(
                "throughputOverheadPercent", throughputOverheadPercent,
                "p99OverheadPercent", p99OverheadPercent);
    }
}
