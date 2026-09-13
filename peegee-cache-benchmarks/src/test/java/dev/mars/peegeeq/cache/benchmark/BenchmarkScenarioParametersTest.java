package dev.mars.peegeeq.cache.benchmark;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

class BenchmarkScenarioParametersTest {
    @Test
    void uniformKeysAndPayloadsAreDeterministicAndBounded() {
        var scenario = BenchmarkScenarioParameters.uniform(100, 257, Duration.ZERO,
                BenchmarkScenarioParameters.TelemetryMode.OFF);
        for (long request = 0; request < 1_000; request++) {
            long key = scenario.keyIndex(41, request);
            assertTrue(key >= 0 && key < 100);
            assertEquals(key, scenario.keyIndex(41, request));
            assertEquals(257, scenario.payload(41, key).length);
            assertArrayEquals(scenario.payload(41, key), scenario.payload(41, key));
        }
        assertNull(scenario.entryTtl());
        assertEquals("100", scenario.environment().get("scenario.datasetCardinality"));
        assertEquals("257", scenario.environment().get("scenario.payloadBytes"));
        assertEquals("UNIFORM", scenario.environment().get("scenario.keyDistribution"));
        assertEquals("OFF", scenario.environment().get("scenario.telemetryMode"));
        assertEquals("1.0", scenario.environment().get("scenario.targetHitRatio"));
        for (long request = 0; request < 100; request++) assertTrue(scenario.expectsHit(41, request));

        var misses = BenchmarkScenarioParameters.uniform(100, 1, 0, Duration.ZERO,
                BenchmarkScenarioParameters.TelemetryMode.OFF);
        for (long request = 0; request < 100; request++) assertFalse(misses.expectsHit(41, request));
    }

    @Test
    void hotSetAndTtlAreExplicitAndValidated() {
        var scenario = new BenchmarkScenarioParameters(100, 16, 0.75,
                BenchmarkScenarioParameters.KeyDistribution.HOT_SET, 5, 0.9,
                Duration.ofSeconds(30), BenchmarkScenarioParameters.TelemetryMode.METRICS);
        assertEquals(Duration.ofSeconds(30), scenario.entryTtl());
        assertEquals(5, scenario.hotSetSize());
        assertThrows(IllegalArgumentException.class, () -> BenchmarkScenarioParameters.uniform(
                0, 1, Duration.ZERO, BenchmarkScenarioParameters.TelemetryMode.OFF));
        assertThrows(IllegalArgumentException.class, () -> new BenchmarkScenarioParameters(10, 1, 1,
                BenchmarkScenarioParameters.KeyDistribution.HOT_SET, 10, 0.9, Duration.ZERO,
                BenchmarkScenarioParameters.TelemetryMode.OFF));
        assertThrows(IllegalArgumentException.class, () -> new BenchmarkScenarioParameters(10, 1, 1,
                BenchmarkScenarioParameters.KeyDistribution.HOT_SET, 2, Double.NaN, Duration.ZERO,
                BenchmarkScenarioParameters.TelemetryMode.OFF));
        assertThrows(IllegalArgumentException.class, () -> BenchmarkScenarioParameters.uniform(
                10, 1, Duration.ofMillis(-1), BenchmarkScenarioParameters.TelemetryMode.OFF));
        assertThrows(IllegalArgumentException.class, () -> BenchmarkScenarioParameters.uniform(
                10, 1, Double.NaN, Duration.ZERO, BenchmarkScenarioParameters.TelemetryMode.OFF));
    }
}
