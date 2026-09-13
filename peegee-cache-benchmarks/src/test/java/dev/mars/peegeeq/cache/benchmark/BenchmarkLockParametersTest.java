package dev.mars.peegeeq.cache.benchmark;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

class BenchmarkLockParametersTest {
    @Test
    void lockContentionIsDeterministicBoundedAndRecorded() {
        var parameters = new BenchmarkLockParameters(100, 5, 0.9, Duration.ofSeconds(2), true);
        for (long request = 0; request < 1_000; request++) {
            long index = parameters.lockIndex(17, request);
            assertTrue(index >= 0 && index < 100);
            assertEquals(index, parameters.lockIndex(17, request));
        }
        assertEquals("100", parameters.environment().get("scenario.lockCardinality"));
        assertEquals("5", parameters.environment().get("scenario.hotLockCount"));
        assertEquals("0.9", parameters.environment().get("scenario.hotLockRequestFraction"));
        assertEquals("2000", parameters.environment().get("scenario.lockLeaseMillis"));
        assertEquals("true", parameters.environment().get("scenario.lockFencing"));
    }

    @Test
    void rejectsInvalidLockBounds() {
        assertThrows(IllegalArgumentException.class,
                () -> new BenchmarkLockParameters(1, 1, 1, Duration.ofSeconds(1), true));
        assertThrows(IllegalArgumentException.class,
                () -> new BenchmarkLockParameters(10, 10, 1, Duration.ofSeconds(1), true));
        assertThrows(IllegalArgumentException.class,
                () -> new BenchmarkLockParameters(10, 2, Double.NaN, Duration.ofSeconds(1), true));
        assertThrows(IllegalArgumentException.class,
                () -> new BenchmarkLockParameters(10, 2, 1, Duration.ZERO, true));
        assertThrows(IllegalArgumentException.class,
                () -> new BenchmarkLockParameters(10, 2, 1, Duration.ofNanos(1), true));
    }
}
