package dev.mars.peegeeq.cache.benchmark;

import dev.mars.peegeeq.cache.test.LatencyHistogram;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BenchmarkResultTest {

    @Test
    void convertsMeasuredSnapshotsWithoutParsingConsoleOutput() {
        LatencyHistogram.Snapshot snapshot = new LatencyHistogram.Snapshot(
                42, Duration.ofMillis(1), Duration.ofMillis(2), Duration.ofMillis(3), 21.5);

        BenchmarkScenarioResult result = BenchmarkScenarioResult.from("cache-get", snapshot);

        assertEquals("cache-get", result.name());
        assertEquals(42, result.operations());
        assertEquals(21.5, result.throughputPerSecond());
        assertEquals(3.0, result.p99Milliseconds());
    }

    @Test
    void benchmarkResultRequiresTheCompleteScenarioSet() {
        BenchmarkConfig config = BenchmarkConfig.fromSystemProperties();
        BenchmarkScenarioResult scenario = new BenchmarkScenarioResult(
                "only-one", 1, 1.0, 1.0, 1.0, 1.0);

        assertThrows(IllegalArgumentException.class, () -> new BenchmarkRunResult(
                config, List.of(scenario), new BenchmarkTelemetryResult(0, 0),
                Duration.ZERO, Duration.ZERO));
    }
}
