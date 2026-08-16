package dev.mars.peegeeq.cache.benchmark;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BenchmarkCaptureConfigTest {

    @Test
    void resolvesPortableDefaultsFromJavaSystemProperties() {
        BenchmarkCaptureConfig config = BenchmarkCaptureConfig.fromSystemProperties();

        assertEquals(3, config.runs());
        assertEquals(Path.of("benchmark-results"), config.outputRoot());
        assertEquals("postgres:18.3-alpine", config.postgresImage());
    }

    @Test
    void rejectsNonPositiveRepetitionCounts() {
        assertThrows(IllegalArgumentException.class, () -> new BenchmarkCaptureConfig(
                0, Path.of("results"), Path.of("."), "topology", "postgres:18.3-alpine",
                false, false));
    }
}
