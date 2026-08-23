package dev.mars.peegeeq.cache.benchmark;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BenchmarkCaptureConfigTest {

    @Test
    void resolvesPortableDefaultsFromJavaSystemProperties() {
        BenchmarkCaptureConfig config = BenchmarkCaptureConfig.fromProperties(new Properties());

        assertEquals(3, config.runs());
        assertEquals(Path.of("benchmark-results"), config.outputRoot());
        assertEquals("postgres:18.3-alpine", config.postgresImage());
    }

    @Test
    void honorsAnExplicitMatrixImageWithoutChangingGlobalProperties() {
        Properties properties = new Properties();
        properties.setProperty("peegeeq.test.postgres.image", "postgres:15.17-alpine");

        BenchmarkCaptureConfig config = BenchmarkCaptureConfig.fromProperties(properties);

        assertEquals("postgres:15.17-alpine", config.postgresImage());
    }

    @Test
    void rejectsNonPositiveRepetitionCounts() {
        assertThrows(IllegalArgumentException.class, () -> new BenchmarkCaptureConfig(
                0, Path.of("results"), Path.of("."), "topology", "postgres:18.3-alpine",
                false, false));
    }
}
