package dev.mars.peegeeq.cache.test;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LatencyHistogramTest {

    @Test
    void calculatesNearestRankPercentilesAndThroughput() {
        LatencyHistogram histogram = new LatencyHistogram(2);
        for (int millis = 1; millis <= 100; millis++) {
            histogram.record(Duration.ofMillis(millis));
        }

        LatencyHistogram.Snapshot snapshot = histogram.snapshot(Duration.ofSeconds(2));
        assertEquals(100, snapshot.operations());
        assertEquals(Duration.ofMillis(50), snapshot.p50());
        assertEquals(Duration.ofMillis(95), snapshot.p95());
        assertEquals(Duration.ofMillis(99), snapshot.p99());
        assertEquals(50.0, snapshot.throughputPerSecond());
    }
}
