package dev.mars.peegeeq.cache.benchmark;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class BenchmarkWorkloadSchedulerTest {
    private final AtomicLong clock = new AtomicLong();

    private BenchmarkWorkloadScheduler scheduler(BenchmarkParameters.LoadModel model, double rate,
                                                 int concurrency, int queue, long timeout, long duration, int catchUp) {
        return new BenchmarkWorkloadScheduler(new BenchmarkParameters(model, concurrency, 1, rate, queue,
                Duration.ofNanos(timeout)), Duration.ofNanos(duration), catchUp, List.of(5L, 10L, 100L), clock::get);
    }

    @Test
    void independentArrivalsRejectAtCapacityWithoutReducingScheduledDemand() {
        var scheduler = scheduler(BenchmarkParameters.LoadModel.RATE_CONTROLLED, 100_000_000, 1, 1, 100, 40, 4);
        var first = scheduler.advance().getFirst();
        clock.set(10); assertTrue(scheduler.advance().isEmpty());
        clock.set(20); assertTrue(scheduler.advance().isEmpty());
        clock.set(25); assertTrue(scheduler.complete(first.id(), true));
        var queued = scheduler.advance().getFirst();
        assertEquals(10, queued.scheduledNanos());
        assertEquals(25, queued.startedNanos());
        clock.set(30); scheduler.advance();
        clock.set(35); scheduler.complete(queued.id(), false);
        var last = scheduler.advance().getFirst();
        clock.set(39); scheduler.complete(last.id(), true);
        clock.set(40); assertTrue(scheduler.advance().isEmpty());
        clock.set(41);
        var sample = scheduler.checkpoint();
        assertEquals(new BenchmarkWorkCounts(4, 3, 3, 2, 1, 0, 1, 0), sample.interval().after());
        assertEquals(2, sample.distributions().get("successfulEndToEnd").sampleCount());
        assertEquals(1, sample.distributions().get("failedService").sampleCount());
        assertEquals(1, scheduler.statistics().admissionRejected());
        assertTrue(scheduler.drained());
    }

    @Test
    void timeoutKeepsPhysicalCapacityAndLateCompletionNeverCountsTwice() {
        var scheduler = scheduler(BenchmarkParameters.LoadModel.RATE_CONTROLLED, 100_000_000, 1, 1, 15, 40, 4);
        var first = scheduler.advance().getFirst();
        clock.set(10); scheduler.advance();
        clock.set(15); assertTrue(scheduler.advance().isEmpty());
        assertEquals(1, scheduler.statistics().physicalInFlight());
        clock.set(20); scheduler.advance();
        clock.set(25); scheduler.advance();
        clock.set(26); assertTrue(scheduler.complete(first.id(), true));
        assertFalse(scheduler.complete(first.id(), false));
        clock.set(30); var last = scheduler.advance().getFirst();
        clock.set(31); scheduler.complete(last.id(), true);
        clock.set(40); scheduler.advance();
        clock.set(41);
        var sample = scheduler.checkpoint();
        assertEquals(new BenchmarkWorkCounts(4, 3, 2, 1, 0, 1, 1, 1), sample.interval().after());
        assertEquals(1, scheduler.statistics().lateSucceeded());
        assertEquals(0, scheduler.statistics().lateFailed());
        assertEquals(1, scheduler.statistics().duplicateCompletions());
        assertEquals(List.of(0L, 0L, 1L), sample.distributions().get("timedOutEndToEnd").counts());
        assertTrue(scheduler.drained());
    }

    @Test
    void overdueCallbackIsTimeoutEvenBeforeNextPollingTick() {
        var scheduler = scheduler(BenchmarkParameters.LoadModel.CLOSED_LOOP, 0, 1, 0, 10, 30, 1);
        var launch = scheduler.advance().getFirst();
        clock.set(12); scheduler.complete(launch.id(), false);
        clock.set(13);
        var sample = scheduler.checkpoint();
        assertEquals(1, sample.interval().timedOut());
        assertEquals(0, sample.interval().failed());
        assertEquals(1, scheduler.statistics().lateFailed());
        assertEquals(2, scheduler.statistics().maximumDeadlineDetectionLagNanos());
        assertEquals(List.of(0L, 1L, 0L), sample.distributions().get("timedOutService").counts());
    }

    @Test
    void generatorStallAccountsForEveryMissedArrivalWithoutAnUnboundedCatchUpBurst() {
        var scheduler = scheduler(BenchmarkParameters.LoadModel.RATE_CONTROLLED, 1_000_000_000, 2, 1,
                2_000_000_000L, 1_000_000_001L, 3);
        clock.set(1_000_000_000);
        var launches = scheduler.advance();
        assertEquals(2, launches.size());
        assertEquals(999_999_998L, launches.getFirst().scheduledNanos());
        assertEquals(999_999_998L, scheduler.statistics().generatorMissed());
        assertEquals(1_000_000_000L, scheduler.statistics().maximumScheduleLagNanos());
        clock.incrementAndGet();
        var sample = scheduler.checkpoint();
        assertEquals(1_000_000_001L, sample.interval().scheduled());
        assertEquals(999_999_998L, sample.interval().rejected());
        assertEquals(1, sample.interval().after().queued());
        assertFalse(scheduler.drained());
    }

    @Test
    void fractionalRateUsesAbsoluteScheduleAndExclusiveHorizon() {
        var scheduler = scheduler(BenchmarkParameters.LoadModel.RATE_CONTROLLED, 3, 1, 0,
                1_000_000_000, 1_000_000_000, 1);
        var first = scheduler.advance().getFirst();
        scheduler.complete(first.id(), true);
        clock.set(333_333_333); assertTrue(scheduler.advance().isEmpty());
        clock.set(333_333_334); var second = scheduler.advance().getFirst();
        assertEquals(333_333_334, second.scheduledNanos());
        scheduler.complete(second.id(), true);
        clock.set(666_666_667); var third = scheduler.advance().getFirst();
        scheduler.complete(third.id(), true);
        clock.set(1_000_000_000); assertTrue(scheduler.advance().isEmpty());
        clock.incrementAndGet();
        assertEquals(3, scheduler.checkpoint().interval().succeeded());
        assertTrue(scheduler.drained());
    }

    @Test
    void closedLoopRefillsOnlyAfterPhysicalCompletionAndStopsAtHorizon() {
        var scheduler = scheduler(BenchmarkParameters.LoadModel.CLOSED_LOOP, 0, 2, 0, 10, 20, 2);
        var initial = scheduler.advance();
        assertEquals(2, initial.size());
        clock.set(10); assertTrue(scheduler.advance().isEmpty());
        assertFalse(scheduler.drained());
        clock.set(12); scheduler.complete(initial.getFirst().id(), true);
        var replacement = scheduler.advance().getFirst();
        clock.set(20); scheduler.complete(replacement.id(), true);
        scheduler.complete(initial.getLast().id(), false);
        assertTrue(scheduler.advance().isEmpty());
        clock.incrementAndGet();
        assertEquals(new BenchmarkWorkCounts(3, 3, 3, 1, 0, 2, 0, 0), scheduler.checkpoint().interval().after());
        assertTrue(scheduler.drained());
    }

    @Test
    void horizonAccountsUnobservedArrivalsWithoutLaunchingPostHorizonWork() {
        var scheduler = scheduler(BenchmarkParameters.LoadModel.RATE_CONTROLLED, 100_000_000, 1, 1, 15, 30, 2);
        clock.set(30);
        assertTrue(scheduler.advance().isEmpty());
        clock.incrementAndGet();
        assertEquals(new BenchmarkWorkCounts(3, 0, 0, 0, 0, 0, 3, 0), scheduler.checkpoint().interval().after());
        assertEquals(3, scheduler.statistics().generatorMissed());
        assertTrue(scheduler.drained());
    }

    @Test
    void validatesScheduleRangeAndClockWithoutMutatingAcceptedWork() {
        assertThrows(IllegalArgumentException.class, () -> scheduler(BenchmarkParameters.LoadModel.RATE_CONTROLLED,
                Double.MAX_VALUE, 1, 0, 10, Long.MAX_VALUE, 1));
        assertThrows(IllegalArgumentException.class, () -> scheduler(BenchmarkParameters.LoadModel.CLOSED_LOOP, 0, 1, 0, 10, 0, 1));
        assertThrows(IllegalArgumentException.class, () -> scheduler(BenchmarkParameters.LoadModel.CLOSED_LOOP, 0, 1, 0, 10, 20, 0));
        var scheduler = scheduler(BenchmarkParameters.LoadModel.CLOSED_LOOP, 0, 1, 0, 10, 20, 1);
        clock.set(5); var first = scheduler.advance().getFirst();
        clock.set(4); assertThrows(IllegalArgumentException.class, scheduler::advance);
        clock.set(6); assertTrue(scheduler.complete(first.id(), true));
        assertThrows(IllegalArgumentException.class, () -> scheduler.complete(999, true));
        clock.set(7); assertEquals(1, scheduler.checkpoint().interval().succeeded());
    }

    @Test
    void checkpointCarriesLogicalOutstandingSeparatelyFromPhysicalTimeoutPopulation() {
        var scheduler = scheduler(BenchmarkParameters.LoadModel.CLOSED_LOOP, 0, 1, 0, 10, 20, 1);
        var first = scheduler.advance().getFirst();
        clock.set(10); scheduler.advance();
        clock.set(11);
        var timedOut = scheduler.checkpoint();
        assertEquals(0, timedOut.interval().after().outstanding());
        assertEquals(1.0, scheduler.statistics().metrics().get("scheduler.physicalInFlight").value());
        clock.set(12); scheduler.complete(first.id(), false);
        clock.set(20); scheduler.advance();
        clock.set(21);
        var drained = scheduler.checkpoint();
        assertEquals(timedOut.interval().after(), drained.interval().before());
        assertEquals(0, drained.interval().timedOut());
        assertEquals(0, drained.distributions().get("timedOutService").sampleCount());
        assertEquals(1.0, scheduler.statistics().metrics().get("scheduler.lateFailed").value());
        assertEquals(0.0, scheduler.statistics().metrics().get("scheduler.physicalInFlight").value());
    }

    @Test
    void bulkMissAccountingRejectsOverflowWithoutPartialMutation() {
        var recorder = new BenchmarkIntervalRecorder(List.of(10L), clock::get);
        recorder.rejectScheduled(Long.MAX_VALUE);
        assertThrows(ArithmeticException.class, () -> recorder.rejectScheduled(1));
        assertThrows(IllegalArgumentException.class, () -> recorder.rejectScheduled(0));
        clock.incrementAndGet();
        assertEquals(new BenchmarkWorkCounts(Long.MAX_VALUE, 0, 0, 0, 0, 0, Long.MAX_VALUE, 0),
                recorder.checkpoint().interval().after());
    }

    @Test
    void evidenceMetricsDoNotSilentlyRoundLargeIntegerCounters() {
        var statistics = new BenchmarkWorkloadScheduler.Statistics(Long.MAX_VALUE, 0, 0, 0, 0, 0, 0, 0, 0);
        var metric = statistics.metrics().get("scheduler.generatorMissed");
        assertNull(metric.value());
        assertTrue(metric.unavailableReason().contains(Long.toString(Long.MAX_VALUE)));
    }

    @Test
    void closedLoopConcurrencyIsNotThrottledByTheRateControlledCatchUpLimit() {
        var scheduler = scheduler(BenchmarkParameters.LoadModel.CLOSED_LOOP, 0, 3, 0, 10, 20, 1);
        assertEquals(3, scheduler.advance().size());
    }
}
