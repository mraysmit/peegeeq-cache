package dev.mars.peegeeq.cache.benchmark;

import org.junit.jupiter.api.Test;

import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BenchmarkConfigTest {

    @Test
    void loadsPositiveDefaults() {
        BenchmarkConfig config = BenchmarkConfig.fromProperties(new Properties());
        assertEquals(8, config.concurrency());
        assertEquals(12, config.poolSize());
        assertEquals(5, config.warmup().toSeconds());
        assertEquals(30, config.duration().toSeconds());
        assertEquals(100.0, config.maximumTelemetryOverheadPercent());
    }

    @Test
    void loadsOverridesFromAnIsolatedPropertySet() {
        Properties properties = new Properties();
        properties.setProperty("peegeeq.benchmark.concurrency", "16");
        properties.setProperty("peegeeq.benchmark.poolSize", "20");
        properties.setProperty("peegeeq.benchmark.durationSeconds", "45");

        BenchmarkConfig config = BenchmarkConfig.fromProperties(properties);

        assertEquals(16, config.concurrency());
        assertEquals(20, config.poolSize());
        assertEquals(45, config.duration().toSeconds());
    }

    @Test
    void rejectsNonPositiveValues() {
        BenchmarkConfig defaults = BenchmarkConfig.fromProperties(new Properties());
        assertThrows(IllegalArgumentException.class, () -> new BenchmarkConfig(
                0, defaults.poolSize(), defaults.warmup(), defaults.duration(), defaults.minimumThroughput(), defaults.maximumP99(),
                defaults.maximumFailoverRecovery(), defaults.maximumExpiryLag(),
                defaults.maximumTelemetryOverheadPercent()));
        assertThrows(IllegalArgumentException.class, () -> new BenchmarkConfig(
                defaults.concurrency(), defaults.poolSize(), defaults.warmup(), defaults.duration(), defaults.minimumThroughput(),
                defaults.maximumP99(),
                defaults.maximumFailoverRecovery(), defaults.maximumExpiryLag(), -0.01));
        assertThrows(IllegalArgumentException.class, () -> new BenchmarkConfig(
                defaults.concurrency(), defaults.poolSize(), java.time.Duration.ofSeconds(-1), defaults.duration(),
                defaults.minimumThroughput(), defaults.maximumP99(), defaults.maximumFailoverRecovery(),
                defaults.maximumExpiryLag(), defaults.maximumTelemetryOverheadPercent()));
    }

    @Test
    void rejectsPoolWithoutForegroundAndBackgroundHeadroom() {
        BenchmarkConfig defaults = BenchmarkConfig.fromProperties(new Properties());

        assertThrows(IllegalArgumentException.class, () -> new BenchmarkConfig(
                defaults.concurrency(), defaults.concurrency(), defaults.warmup(), defaults.duration(),
                defaults.minimumThroughput(), defaults.maximumP99(), defaults.maximumFailoverRecovery(),
                defaults.maximumExpiryLag(), defaults.maximumTelemetryOverheadPercent()));
    }
}
