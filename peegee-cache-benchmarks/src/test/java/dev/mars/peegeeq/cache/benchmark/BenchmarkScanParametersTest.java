package dev.mars.peegeeq.cache.benchmark;

import org.junit.jupiter.api.Test;

import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BenchmarkScanParametersTest {
    @Test
    void validatesPageControls() {
        assertThrows(IllegalArgumentException.class,
                () -> new BenchmarkScanParameters(0, true, false, null));
        assertThrows(IllegalArgumentException.class,
                () -> new BenchmarkScanParameters(10_001, true, false, null));
        assertThrows(IllegalArgumentException.class,
                () -> new BenchmarkScanParameters(10, true, false, " "));
    }

    @Test
    void resolvesExplicitEnvironmentWithoutSilentSubstitution() {
        var properties = new Properties();
        properties.setProperty("scenario.scan.pageSize", "37");
        properties.setProperty("scenario.scan.includeValues", "false");
        properties.setProperty("scenario.scan.includeExpired", "true");
        properties.setProperty("scenario.scan.prefix", "key-0");

        assertEquals(new BenchmarkScanParameters(37, false, true, "key-0"),
                BenchmarkScanParameters.fromProperties(properties));
    }
}
