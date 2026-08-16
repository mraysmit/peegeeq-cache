package dev.mars.peegeeq.cache.benchmark;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BenchmarkConfigTest {

    @Test
    void loadsPositiveDefaults() {
        BenchmarkConfig config = BenchmarkConfig.fromSystemProperties();
        assertEquals(8, config.concurrency());
        assertEquals(30, config.duration().toSeconds());
    }

    @Test
    void rejectsNonPositiveValues() {
        BenchmarkConfig defaults = BenchmarkConfig.fromSystemProperties();
        assertThrows(IllegalArgumentException.class, () -> new BenchmarkConfig(
                0, defaults.duration(), defaults.minimumThroughput(), defaults.maximumP99(),
                defaults.maximumFailoverRecovery(), defaults.maximumExpiryLag()));
    }
}
