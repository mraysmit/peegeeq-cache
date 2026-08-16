package dev.mars.peegeeq.cache.benchmark;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BenchmarkConfigTest {

    @Test
    void loadsPositiveDefaults() {
        BenchmarkConfig config = BenchmarkConfig.fromSystemProperties();
        assertEquals(8, config.concurrency());
        assertEquals(12, config.poolSize());
        assertEquals(30, config.duration().toSeconds());
        assertEquals(100.0, config.maximumTelemetryOverheadPercent());
    }

    @Test
    void rejectsNonPositiveValues() {
        BenchmarkConfig defaults = BenchmarkConfig.fromSystemProperties();
        assertThrows(IllegalArgumentException.class, () -> new BenchmarkConfig(
                0, defaults.poolSize(), defaults.duration(), defaults.minimumThroughput(), defaults.maximumP99(),
                defaults.maximumFailoverRecovery(), defaults.maximumExpiryLag(),
                defaults.maximumTelemetryOverheadPercent()));
        assertThrows(IllegalArgumentException.class, () -> new BenchmarkConfig(
                defaults.concurrency(), defaults.poolSize(), defaults.duration(), defaults.minimumThroughput(),
                defaults.maximumP99(),
                defaults.maximumFailoverRecovery(), defaults.maximumExpiryLag(), -0.01));
    }

    @Test
    void rejectsPoolWithoutForegroundAndBackgroundHeadroom() {
        BenchmarkConfig defaults = BenchmarkConfig.fromSystemProperties();

        assertThrows(IllegalArgumentException.class, () -> new BenchmarkConfig(
                defaults.concurrency(), defaults.concurrency(), defaults.duration(),
                defaults.minimumThroughput(), defaults.maximumP99(), defaults.maximumFailoverRecovery(),
                defaults.maximumExpiryLag(), defaults.maximumTelemetryOverheadPercent()));
    }
}
