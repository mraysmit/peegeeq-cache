package dev.mars.peegeeq.cache.benchmark;

import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class BenchmarkLatencyDistributionTest {
    @Test
    void reportsExplicitBucketUpperBoundsAndDoesNotClampOverflowPercentiles() {
        var distribution = new BenchmarkLatencyDistribution(List.of(0L, 10L, 100L), List.of(1L, 2L, 1L), 1);
        assertEquals(5, distribution.sampleCount());
        assertEquals(10, distribution.percentileUpperBound(0.5).orElseThrow());
        assertTrue(distribution.percentileUpperBound(0.99).isEmpty());
        var json = distribution.toJson();
        assertEquals(1L, json.getLong("overflowCount"));
        assertNull(json.getValue("p99UpperBoundNanos"));
        assertEquals("BUCKET_UPPER_BOUND", json.getString("percentileRepresentation"));
    }

    @Test
    void mergesCountsInsteadOfAveragingPercentiles() {
        var low = new BenchmarkLatencyDistribution(List.of(10L, 100L), List.of(99L, 0L), 0);
        var high = new BenchmarkLatencyDistribution(List.of(10L, 100L), List.of(0L, 1L), 0);
        var merged = low.merge(high);
        assertEquals(List.of(99L, 1L), merged.counts());
        assertEquals(10, merged.percentileUpperBound(.99).orElseThrow());
        assertEquals(100, merged.percentileUpperBound(1).orElseThrow());
        assertEquals(99, low.sampleCount());
        assertThrows(IllegalArgumentException.class, () -> low.merge(
                new BenchmarkLatencyDistribution(List.of(11L, 100L), List.of(1L, 0L), 0)));
    }

    @Test
    void handlesEmptyAndLargeCountsWithoutInventingOrRoundingRanks() {
        assertTrue(new BenchmarkLatencyDistribution(List.of(1L), List.of(0L), 0).percentileUpperBound(.5).isEmpty());
        var large = new BenchmarkLatencyDistribution(List.of(1L, 2L), List.of(Long.MAX_VALUE - 1, 1L), 0);
        assertEquals(2L, large.percentileUpperBound(1).orElseThrow());
        assertThrows(IllegalArgumentException.class, () -> large.merge(
                new BenchmarkLatencyDistribution(List.of(1L, 2L), List.of(1L, 0L), 0)));
        for (double q : new double[]{0, -1, 1.1, Double.NaN, Double.POSITIVE_INFINITY}) {
            assertThrows(IllegalArgumentException.class, () -> large.percentileUpperBound(q));
        }
    }

    @Test
    void freezesValidatedBucketDataAndRejectsUnboundedOrInvalidShapes() {
        var counts = new ArrayList<>(List.of(1L, 2L));
        var distribution = new BenchmarkLatencyDistribution(List.of(0L, 10L), counts, 0);
        counts.clear();
        assertEquals(3, distribution.sampleCount());
        assertThrows(UnsupportedOperationException.class, () -> distribution.counts().clear());
        assertThrows(IllegalArgumentException.class, () -> new BenchmarkLatencyDistribution(List.of(), List.of(), 0));
        assertThrows(IllegalArgumentException.class, () -> new BenchmarkLatencyDistribution(List.of(1L, 1L), List.of(0L, 0L), 0));
        assertThrows(IllegalArgumentException.class, () -> new BenchmarkLatencyDistribution(List.of(-1L), List.of(0L), 0));
        assertThrows(IllegalArgumentException.class, () -> new BenchmarkLatencyDistribution(List.of(1L), List.of(-1L), 0));
        assertThrows(IllegalArgumentException.class, () -> new BenchmarkLatencyDistribution(List.of(1L), List.of(0L), -1));
        assertThrows(IllegalArgumentException.class, () -> new BenchmarkLatencyDistribution(List.of(1L), List.of(), 0));
        assertThrows(IllegalArgumentException.class, () -> new BenchmarkLatencyDistribution(
                java.util.stream.LongStream.range(0, 65_537).boxed().toList(),
                java.util.Collections.nCopies(65_537, 0L), 0));
    }
}
