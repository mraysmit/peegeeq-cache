package dev.mars.peegeeq.cache.benchmark;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class BenchmarkSpecificationTest {
    @Test
    void resolvesCompleteVersionedCampaignBeforeLaunch() {
        var timeline = new BenchmarkTimeline(List.of(
                new BenchmarkTimeline.Phase("baseline", BenchmarkTimeline.PhaseKind.BASELINE, Duration.ofSeconds(1)),
                new BenchmarkTimeline.Phase("load", BenchmarkTimeline.PhaseKind.LOAD, Duration.ofSeconds(2)),
                new BenchmarkTimeline.Phase("recovery", BenchmarkTimeline.PhaseKind.RECOVERY, Duration.ofSeconds(1)),
                new BenchmarkTimeline.Phase("drain", BenchmarkTimeline.PhaseKind.DRAIN, Duration.ofSeconds(1))),
                Duration.ofMillis(100));
        var matrix = new BenchmarkParameterMatrix(BenchmarkParameters.LoadModel.RATE_CONTROLLED,
                List.of(2), List.of(2), List.of(50.0, 100.0), 4, Duration.ofSeconds(1));
        var scenario = BenchmarkScenarioParameters.uniform(100, 64, .9, Duration.ZERO,
                BenchmarkScenarioParameters.TelemetryMode.OFF);
        var specification = new BenchmarkSpecification(1, "complete-spec", timeline, matrix, 2, 1, 19, 4,
                scenario, new BenchmarkRuntimePolicy(1_000_000, .9, Duration.ofMillis(50), false),
                new BenchmarkPersistencePolicy(1, 10, 10_000_000, 1_000_000_000, 30_000_000),
                BenchmarkCapabilityInventory.localPostgres(), Path.of("benchmark-results/complete-spec"));

        var resolved = specification.resolve(2_000, 2_048, 100_000);
        assertEquals(4, resolved.experiment().runCount());
        assertTrue(resolved.persistence().supported());
        assertEquals("complete-spec", resolved.manifest().getString("experimentId"));
        assertEquals(2, resolved.manifest().getJsonArray("configurations").size());
        assertEquals(4, resolved.manifest().getJsonArray("phases").size());
        assertTrue(resolved.manifest().getJsonObject("capabilities").containsKey("scenario.cache"));
    }
}
