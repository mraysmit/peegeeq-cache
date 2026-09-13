package dev.mars.peegeeq.cache.benchmark;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class BenchmarkCampaignPlanTest {
    @Test
    void resolvesFiniteSeededOrderAndUniqueOwnedResources() {
        var plan = new BenchmarkCampaignPlan(1, experiment(),
                BenchmarkDeploymentTarget.localTestcontainers("postgres:18.3-alpine", "benchmark_test"),
                BenchmarkCampaignPlan.Order.SEEDED_RANDOM, BenchmarkCampaignPlan.Reset.RECREATE_RUN_SCHEMA, 99);

        var first = plan.resolve();
        var second = plan.resolve();
        assertEquals(first, second);
        assertEquals(4, first.size());
        assertEquals(4, first.stream().map(BenchmarkCampaignPlan.Manifest::resourceName).distinct().count());
        assertEquals(4, first.stream().map(BenchmarkCampaignPlan.Manifest::run).distinct().count());
        assertTrue(first.stream().allMatch(manifest -> manifest.resourceName().startsWith("benchmark_test_")));
    }

    private static BenchmarkExperiment experiment() {
        var timeline = new BenchmarkTimeline(List.of(
                new BenchmarkTimeline.Phase("load", BenchmarkTimeline.PhaseKind.LOAD, Duration.ofMillis(10))),
                Duration.ofMillis(5));
        var matrix = new BenchmarkParameterMatrix(BenchmarkParameters.LoadModel.RATE_CONTROLLED,
                List.of(1, 2), List.of(1), List.of(10.0), 1, Duration.ofSeconds(1));
        return new BenchmarkExperiment(1, "finite-campaign", timeline, matrix, 2, 1, 7, 4);
    }
}
