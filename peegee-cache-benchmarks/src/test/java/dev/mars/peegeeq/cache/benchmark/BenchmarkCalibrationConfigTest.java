package dev.mars.peegeeq.cache.benchmark;

import org.junit.jupiter.api.Test;
import java.time.Duration;
import static org.junit.jupiter.api.Assertions.*;

class BenchmarkCalibrationConfigTest {
    @Test
    void resolvesWarmupAndPartialMeasuredWindowsWithoutDiscardingEither() {
        var config = config(2, 16, 125, 230, 100, 2, 1_000_000);
        assertEquals(5, config.windowCount());
        assertEquals("warmup", config.windowAt(1).phase());
        assertEquals(25_000_000, config.windowAt(1).durationNanos());
        assertEquals("observe", config.windowAt(2).phase());
        assertEquals(30_000_000, config.windowAt(4).durationNanos());
        assertThrows(IndexOutOfBoundsException.class, () -> config.windowAt(5));
    }

    @Test
    void rejectsInvalidResourceLimitsAndDurationsBeforeLaunchingWorkers() {
        assertThrows(IllegalArgumentException.class, () -> config(0, 16, 0, 100, 50, 1, 1_000_000));
        assertThrows(IllegalArgumentException.class, () -> config(2, 0, 0, 100, 50, 1, 1_000_000));
        assertThrows(IllegalArgumentException.class, () -> config(2, 16, -1, 100, 50, 1, 1_000_000));
        assertThrows(IllegalArgumentException.class, () -> config(2, 16, 0, 0, 50, 1, 1_000_000));
        assertThrows(IllegalArgumentException.class, () -> config(2, 16, 0, 100, 0, 1, 1_000_000));
        assertThrows(IllegalArgumentException.class, () -> config(2, 16, 0, 100, 50, 0, 1_000_000));
        assertThrows(IllegalArgumentException.class, () -> config(2, 16, 0, 100, 50, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> new BenchmarkCalibrationConfig(2, 16,
                Duration.ZERO, Duration.ofSeconds(Long.MAX_VALUE), Duration.ofMillis(10), 1, 1_000_000));
        assertThrows(IllegalArgumentException.class, () -> new BenchmarkCalibrationConfig(2, 16,
                Duration.ZERO, Duration.ofMillis(100), Duration.ofNanos(Long.MAX_VALUE), 1, 1_000_000));
    }

    static BenchmarkCalibrationConfig config(int concurrency, int buckets, long warmup, long duration,
                                              long window, int checkpoint, long bytes) {
        return new BenchmarkCalibrationConfig(concurrency, buckets, Duration.ofMillis(warmup),
                Duration.ofMillis(duration), Duration.ofMillis(window), checkpoint, bytes);
    }
}
