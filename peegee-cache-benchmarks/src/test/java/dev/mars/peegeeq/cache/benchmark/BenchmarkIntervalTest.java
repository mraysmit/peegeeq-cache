package dev.mars.peegeeq.cache.benchmark;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class BenchmarkIntervalTest {
    @Test
    void accountsSeparatelyForPendingAdmissionQueueAndUnfinishedStartedWork() {
        var counts = new BenchmarkWorkCounts(20, 15, 10, 4, 1, 2, 3, 2);
        assertEquals(2, counts.pendingAdmission());
        assertEquals(3, counts.queued());
        assertEquals(3, counts.inFlight());
        assertEquals(7, counts.completed());
        assertEquals(8, counts.outstanding());
        assertEquals(counts.scheduled(), counts.rejected() + counts.expiredBeforeStart()
                + counts.completed() + counts.outstanding());
    }

    @Test
    void acceptsCompletionsFromEarlierWindowsAndUsesActualObservedDuration() {
        var before = new BenchmarkWorkCounts(10, 10, 8, 2, 0, 0, 0, 0);
        var after = new BenchmarkWorkCounts(12, 12, 10, 6, 1, 1, 0, 0);
        var interval = new BenchmarkInterval(1_000_000_000L, 3_500_000_000L, before, after);
        assertEquals(2, interval.scheduled());
        assertEquals(2, interval.started());
        assertEquals(6, interval.completed());
        assertEquals(4, interval.succeeded());
        assertEquals(1, interval.failed());
        assertEquals(1, interval.timedOut());
        assertEquals(0.8, interval.offeredPerSecond(), 1e-12);
        assertEquals(2.4, interval.completedPerSecond(), 1e-12);
        assertEquals(1.6, interval.succeededPerSecond(), 1e-12);
        assertEquals(8, before.outstanding());
        assertEquals(4, after.outstanding());
    }

    @Test
    void retainsOverloadOutcomesRatherThanTreatingThemAsMissingRequests() {
        var after = new BenchmarkWorkCounts(100, 70, 40, 10, 5, 20, 25, 15);
        var interval = new BenchmarkInterval(0, 1_000_000_000L, BenchmarkWorkCounts.zero(), after);
        assertEquals(25, interval.rejected());
        assertEquals(15, interval.expiredBeforeStart());
        assertEquals(5, after.pendingAdmission());
        assertEquals(15, after.queued());
        assertEquals(5, after.inFlight());
        assertEquals(35, interval.completed());
        assertEquals(100.0, interval.offeredPerSecond());
        assertEquals(10.0, interval.succeededPerSecond());
    }

    @Test
    void emptyIntervalsHaveZeroRatesWithoutInventingLatency() {
        var interval = new BenchmarkInterval(0, 1, BenchmarkWorkCounts.zero(), BenchmarkWorkCounts.zero());
        assertEquals(0, interval.completed());
        assertEquals(0.0, interval.offeredPerSecond());
        assertEquals(0.0, interval.succeededPerSecond());
    }

    @Test
    void rejectsImpossibleAndOverflowingCumulativeAccounting() {
        assertThrows(IllegalArgumentException.class, () -> new BenchmarkWorkCounts(-1, 0, 0, 0, 0, 0, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new BenchmarkWorkCounts(1, 1, 0, 0, 0, 0, 1, 0));
        assertThrows(IllegalArgumentException.class, () -> new BenchmarkWorkCounts(2, 2, 2, 0, 0, 0, 0, 1));
        assertThrows(IllegalArgumentException.class, () -> new BenchmarkWorkCounts(2, 2, 2, 1, 1, 1, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new BenchmarkWorkCounts(
                Long.MAX_VALUE, Long.MAX_VALUE, 0, 0, 0, 0, 1, 0));
        var maximum = new BenchmarkWorkCounts(Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE,
                Long.MAX_VALUE, 0, 0, 0, 0);
        assertEquals(0, maximum.outstanding());
        assertEquals(Long.MAX_VALUE, maximum.completed());
    }

    @Test
    void rejectsCounterResetsAndReclassificationOfATimeoutAsSuccess() {
        var timedOut = new BenchmarkWorkCounts(1, 1, 1, 0, 0, 1, 0, 0);
        var reclassified = new BenchmarkWorkCounts(1, 1, 1, 1, 0, 0, 0, 0);
        assertThrows(IllegalArgumentException.class, () -> new BenchmarkInterval(0, 1, timedOut, reclassified));
        assertThrows(IllegalArgumentException.class, () -> new BenchmarkInterval(0, 1, timedOut, BenchmarkWorkCounts.zero()));
        assertThrows(IllegalArgumentException.class, () -> new BenchmarkInterval(1, 1, timedOut, timedOut));
        assertThrows(IllegalArgumentException.class, () -> new BenchmarkInterval(-1, 1, timedOut, timedOut));
    }
}
