package dev.mars.peegeeq.cache.benchmark;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class BenchmarkPhaseWorkloadPlanTest {
    private static final BenchmarkTimeline TIMELINE = new BenchmarkTimeline(List.of(
            new BenchmarkTimeline.Phase("baseline", BenchmarkTimeline.PhaseKind.BASELINE, Duration.ofNanos(20)),
            new BenchmarkTimeline.Phase("load", BenchmarkTimeline.PhaseKind.LOAD, Duration.ofNanos(30)),
            new BenchmarkTimeline.Phase("drain", BenchmarkTimeline.PhaseKind.DRAIN, Duration.ofNanos(10))),
            Duration.ofNanos(10));

    @Test
    void validatesCompleteOrderedPhaseCoverageAndConfiguredCeilings() {
        var ceiling = new BenchmarkParameters(BenchmarkParameters.LoadModel.RATE_CONTROLLED,
                4, 4, 100, 8, Duration.ofSeconds(1));
        var baseline = new BenchmarkPhaseWorkloadPlan.Profile("baseline", 2, 25,
                BenchmarkProductWorkloads.readWrite(3, 1));
        var load = new BenchmarkPhaseWorkloadPlan.Profile("load", 4, 100,
                BenchmarkProductWorkloads.setGet());
        var plan = new BenchmarkPhaseWorkloadPlan(TIMELINE, ceiling, List.of(baseline, load));

        assertEquals(baseline, plan.profileAt(0));
        assertEquals(load, plan.profileAt(20));
        assertEquals(load, plan.profileAt(49));
        assertFalse(plan.crossesTransition(0, 20));
        assertTrue(plan.crossesTransition(19, 21));
        assertTrue(plan.crossesTransition(49, 51));
        assertThrows(IllegalArgumentException.class, () -> plan.profileAt(50));
        assertThrows(IllegalArgumentException.class, () -> new BenchmarkPhaseWorkloadPlan(TIMELINE, ceiling,
                List.of(load, baseline)));
        assertThrows(IllegalArgumentException.class, () -> new BenchmarkPhaseWorkloadPlan(TIMELINE, ceiling,
                List.of(baseline, new BenchmarkPhaseWorkloadPlan.Profile("load", 5, 100,
                        BenchmarkProductWorkloads.setGet()))));
        assertThrows(IllegalArgumentException.class, () -> new BenchmarkPhaseWorkloadPlan(TIMELINE, ceiling,
                List.of(baseline, new BenchmarkPhaseWorkloadPlan.Profile("load", 4, 101,
                        BenchmarkProductWorkloads.setGet()))));
    }

    @Test
    void weightedOperationSelectionIsDeterministicAndUsesStableProductNames() {
        var mix = BenchmarkProductWorkloads.readWrite(3, 1);
        assertEquals(List.of(BenchmarkProductWorkloads.CACHE_GET, BenchmarkProductWorkloads.CACHE_SET),
                mix.operations());
        var first = java.util.stream.LongStream.range(0, 100)
                .mapToObj(id -> mix.select(37, id)).toList();
        var second = java.util.stream.LongStream.range(0, 100)
                .mapToObj(id -> mix.select(37, id)).toList();
        assertEquals(first, second);
        assertTrue(first.contains(BenchmarkProductWorkloads.CACHE_GET));
        assertTrue(first.contains(BenchmarkProductWorkloads.CACHE_SET));
        assertNotEquals(first, java.util.stream.LongStream.range(0, 100)
                .mapToObj(id -> mix.select(38, id)).toList());
        assertThrows(IllegalArgumentException.class, () -> mix.select(37, -1));
        assertThrows(IllegalArgumentException.class, () -> new BenchmarkWorkloadMix(List.of()));
        assertThrows(IllegalArgumentException.class, () -> new BenchmarkWorkloadMix(List.of(
                new BenchmarkWorkloadMix.Weight("duplicate", 1),
                new BenchmarkWorkloadMix.Weight("duplicate", 2))));
        assertThrows(IllegalArgumentException.class,
                () -> new BenchmarkWorkloadMix.Weight("invalid", 0));
    }

    @Test
    void uniformPlanCopiesTheRunCeilingIntoEveryDemandPhase() {
        var parameters = new BenchmarkParameters(BenchmarkParameters.LoadModel.CLOSED_LOOP,
                3, 2, 0, 2, Duration.ofMillis(50));
        var plan = BenchmarkPhaseWorkloadPlan.uniform(TIMELINE, parameters,
                BenchmarkProductWorkloads.setGet());
        assertEquals(List.of("baseline", "load"), plan.profiles().stream()
                .map(BenchmarkPhaseWorkloadPlan.Profile::phase).toList());
        assertTrue(plan.profiles().stream().allMatch(profile -> profile.concurrency() == 3
                && profile.offeredPerSecond() == 0));
    }
}
