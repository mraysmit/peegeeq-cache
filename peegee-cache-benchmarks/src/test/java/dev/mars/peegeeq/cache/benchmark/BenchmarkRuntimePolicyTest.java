package dev.mars.peegeeq.cache.benchmark;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

class BenchmarkRuntimePolicyTest {
    @Test
    void validatesAndResolvesExplicitSafetyLimits() {
        assertThrows(IllegalArgumentException.class,
                () -> new BenchmarkRuntimePolicy(0, 1, Duration.ofMillis(1), false));
        var properties = new Properties();
        properties.setProperty("runtime.maximumHeapBytes", "67108864");
        properties.setProperty("runtime.maximumProcessCpu", "0.75");
        properties.setProperty("runtime.maximumEventLoopDelay", "PT0.02S");
        properties.setProperty("runtime.requireDiagnostics", "true");

        assertEquals(new BenchmarkRuntimePolicy(67_108_864, 0.75, Duration.ofMillis(20), true),
                BenchmarkRuntimePolicy.fromProperties(properties));
    }

    @Test
    void reportsEveryLimitBreachWithoutConflatingMissingDiagnosticsWithZero() {
        var policy = new BenchmarkRuntimePolicy(100, 0.5, Duration.ofMillis(10), true);
        var result = policy.evaluate(new BenchmarkRuntimePolicy.Observation(101L, 0.6, null));

        assertFalse(result.allowed());
        assertEquals(3, result.reasons().size());
        assertTrue(result.reasons().stream().anyMatch(reason -> reason.contains("unavailable")));
    }
}
