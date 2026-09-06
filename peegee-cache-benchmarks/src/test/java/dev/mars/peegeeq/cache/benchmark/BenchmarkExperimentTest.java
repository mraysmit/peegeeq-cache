package dev.mars.peegeeq.cache.benchmark;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.HashSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class BenchmarkExperimentTest {
    @Test
    void resolvesReproducibleRunIdentityAndWorkloadSeedAcrossForksAndRepetitions() {
        var experiment = experiment(1, "pool-pressure", 3, 2, 24);
        assertEquals(24, experiment.runCount());
        assertEquals(Duration.ofSeconds(240), experiment.plannedDuration());
        var identities = new HashSet<String>();
        for (long index = 0; index < experiment.runCount(); index++) {
            var run = experiment.runAt(index);
            assertEquals("pool-pressure", run.experimentId());
            assertEquals(1, run.schemaVersion());
            assertEquals(index / 6, run.configurationIndex());
            assertEquals((index % 6) / 3, run.forkIndex());
            assertEquals(index % 3, run.repetitionIndex());
            assertEquals(42, run.workloadSeed());
            assertEquals(experiment.runAt(index), run);
            identities.add(run.experimentId() + ":" + run.configurationIndex() + ":"
                    + run.forkIndex() + ":" + run.repetitionIndex());
        }
        assertEquals(24, identities.size());
        assertEquals(4, experiment.runAt(0).parameters().concurrency());
        assertEquals(8, experiment.runAt(23).parameters().concurrency());
        assertThrows(IndexOutOfBoundsException.class, () -> experiment.runAt(-1));
        assertThrows(IndexOutOfBoundsException.class, () -> experiment.runAt(24));
    }

    @Test
    void rejectsUnsupportedVersionsMissingIdentityAndOverBudgetExpansion() {
        assertThrows(IllegalArgumentException.class, () -> experiment(2, "pressure", 1, 1, 4));
        assertThrows(IllegalArgumentException.class, () -> experiment(1, " ", 1, 1, 4));
        assertThrows(IllegalArgumentException.class, () -> experiment(1, "pressure", 0, 1, 4));
        assertThrows(IllegalArgumentException.class, () -> experiment(1, "pressure", 1, 0, 4));
        assertThrows(IllegalArgumentException.class, () -> experiment(1, "pressure", 1, 1, 0));
        assertThrows(IllegalArgumentException.class, () -> experiment(1, "pressure", 3, 2, 23));
        assertThrows(IllegalArgumentException.class, () -> experiment(
                1, "pressure", Integer.MAX_VALUE, Integer.MAX_VALUE, Long.MAX_VALUE));
    }

    @Test
    void oneFixedConfigurationStillExpandsItsRequestedRepetitions() {
        var matrix = new BenchmarkParameterMatrix(BenchmarkParameters.LoadModel.CLOSED_LOOP,
                List.of(8), List.of(2), List.of(0.0), 0, Duration.ofMillis(500));
        var experiment = new BenchmarkExperiment(1, "fixed-reference", timeline(), matrix, 3, 1, -7, 3);
        assertEquals(3, experiment.runCount());
        assertEquals(Duration.ofSeconds(30), experiment.plannedDuration());
        assertEquals(-7, experiment.runAt(2).workloadSeed());
        assertEquals(0, experiment.runAt(2).configurationIndex());
    }

    private static BenchmarkExperiment experiment(int version, String id, int repetitions, int forks, long limit) {
        var matrix = new BenchmarkParameterMatrix(BenchmarkParameters.LoadModel.RATE_CONTROLLED,
                List.of(4, 8), List.of(2, 6), List.of(100.0), 10, Duration.ofSeconds(1));
        return new BenchmarkExperiment(version, id, timeline(), matrix, repetitions, forks, 42, limit);
    }

    private static BenchmarkTimeline timeline() {
        return new BenchmarkTimeline(List.of(new BenchmarkTimeline.Phase("observe",
                BenchmarkTimeline.PhaseKind.SUSTAINED, Duration.ofSeconds(10))), Duration.ofSeconds(1));
    }
}
