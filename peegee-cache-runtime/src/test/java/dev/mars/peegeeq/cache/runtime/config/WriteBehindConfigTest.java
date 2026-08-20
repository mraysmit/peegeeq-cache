package dev.mars.peegeeq.cache.runtime.config;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WriteBehindConfigTest {

    @Test
    void validConfigurationPreservesValues() {
        WriteBehindConfig config = new WriteBehindConfig(
                true, Duration.ofMillis(250), 2_000, 200, 5, Duration.ofSeconds(10));

        assertTrue(config.enabled());
        assertEquals(Duration.ofMillis(250), config.flushInterval());
        assertEquals(2_000, config.maxBufferSize());
        assertEquals(200, config.flushBatchSize());
        assertEquals(5, config.maxRetries());
        assertEquals(Duration.ofSeconds(10), config.shutdownDrainTimeout());
    }

    @Test
    void disabledUsesDocumentedDefaults() {
        WriteBehindConfig config = WriteBehindConfig.disabled();

        assertFalse(config.enabled());
        assertEquals(Duration.ofMillis(500), config.flushInterval());
        assertEquals(10_000, config.maxBufferSize());
        assertEquals(500, config.flushBatchSize());
        assertEquals(3, config.maxRetries());
        assertEquals(Duration.ofSeconds(5), config.shutdownDrainTimeout());
    }

    @Test
    void runtimeConfigurationDefaultsToDisabledWriteBehind() {
        assertEquals(WriteBehindConfig.disabled(), PeeGeeCacheConfig.defaults().writeBehind());
        assertEquals(WriteBehindConfig.disabled(),
                new PeeGeeCacheConfig(null, Duration.ofSeconds(30), 500, false).writeBehind());
    }

    @Test
    void runtimeConfigurationRejectsFlushIntervalLongerThanKnownDefaultTtl() {
        WriteBehindConfig writeBehind = new WriteBehindConfig(
                true, Duration.ofSeconds(2), 100, 10, 0, Duration.ofSeconds(5));

        assertThrows(IllegalArgumentException.class, () -> new PeeGeeCacheConfig(
                Duration.ofSeconds(1), Duration.ofSeconds(30), 500, false, writeBehind));
    }

    @Test
    void rejectsInvalidFlushInterval() {
        assertThrows(IllegalArgumentException.class, () -> configWithFlushInterval(null));
        assertThrows(IllegalArgumentException.class, () -> configWithFlushInterval(Duration.ZERO));
        assertThrows(IllegalArgumentException.class, () -> configWithFlushInterval(Duration.ofMillis(-1)));
    }

    @Test
    void rejectsBufferBelowMinimumCapacity() {
        assertThrows(IllegalArgumentException.class, () -> new WriteBehindConfig(
                true, Duration.ofMillis(500), 99, 1, 3, Duration.ofSeconds(5)));
    }

    @Test
    void rejectsInvalidFlushBatchSize() {
        assertThrows(IllegalArgumentException.class, () -> new WriteBehindConfig(
                true, Duration.ofMillis(500), 100, 0, 3, Duration.ofSeconds(5)));
        assertThrows(IllegalArgumentException.class, () -> new WriteBehindConfig(
                true, Duration.ofMillis(500), 100, 101, 3, Duration.ofSeconds(5)));
    }

    @Test
    void rejectsNegativeRetryCount() {
        assertThrows(IllegalArgumentException.class, () -> new WriteBehindConfig(
                true, Duration.ofMillis(500), 100, 10, -1, Duration.ofSeconds(5)));
    }

    @Test
    void rejectsInvalidShutdownDrainTimeout() {
        assertThrows(IllegalArgumentException.class, () -> configWithShutdownDrainTimeout(null));
        assertThrows(IllegalArgumentException.class, () -> configWithShutdownDrainTimeout(Duration.ZERO));
        assertThrows(IllegalArgumentException.class, () -> configWithShutdownDrainTimeout(Duration.ofMillis(-1)));
    }

    private static WriteBehindConfig configWithFlushInterval(Duration flushInterval) {
        return new WriteBehindConfig(true, flushInterval, 100, 10, 3, Duration.ofSeconds(5));
    }

    private static WriteBehindConfig configWithShutdownDrainTimeout(Duration shutdownDrainTimeout) {
        return new WriteBehindConfig(true, Duration.ofMillis(500), 100, 10, 3, shutdownDrainTimeout);
    }
}
