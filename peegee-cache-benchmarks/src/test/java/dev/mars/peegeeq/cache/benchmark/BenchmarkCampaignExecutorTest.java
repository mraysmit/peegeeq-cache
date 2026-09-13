package dev.mars.peegeeq.cache.benchmark;

import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(VertxExtension.class)
@io.vertx.junit5.Timeout(value = 15, timeUnit = TimeUnit.SECONDS)
class BenchmarkCampaignExecutorTest {
    @TempDir Path directory;

    @Test
    void executesTheResolvedFiniteOrderWithPreparationBeforeEveryRun(Vertx vertx, VertxTestContext ctx) {
        var worker = vertx.createSharedWorkerExecutor("campaign-test", 1);
        var experiment = experiment(2);
        var prepared = new ArrayList<Integer>();
        var invoked = new ArrayList<Integer>();

        BenchmarkCampaignExecutor.run(vertx, worker, directory, experiment, 2, run -> {
            prepared.add(run.repetitionIndex());
            return Future.succeededFuture(spec(run, attempt -> {
                invoked.add(run.repetitionIndex());
                return Future.succeededFuture();
            }));
        }).compose(results -> vertx.executeBlocking(() -> {
            assertEquals(List.of(0, 1), prepared);
            assertEquals(List.of(0, 1), results.stream().map(result -> result.run().repetitionIndex()).toList());
            assertTrue(invoked.containsAll(List.of(0, 1)));
            assertEquals(2, results.stream().map(result -> result.result().path()).distinct().count());
            assertTrue(results.stream().allMatch(result ->
                    result.result().status() == BenchmarkRunEvidence.Status.COMPLETED));
            return null;
        })).eventually(worker::close).onComplete(ctx.succeeding(ignored -> ctx.completeNow()));
    }

    @Test
    void rejectsCampaignsBeyondTheExecutionBudgetBeforePreparation(Vertx vertx) {
        var worker = vertx.createSharedWorkerExecutor("campaign-budget-test", 1);
        try {
            var called = new java.util.concurrent.atomic.AtomicBoolean();
            var result = BenchmarkCampaignExecutor.run(vertx, worker, directory, experiment(2), 1, run -> {
                called.set(true);
                return Future.succeededFuture(spec(run, attempt -> Future.succeededFuture()));
            });
            assertTrue(result.failed());
            assertFalse(called.get());
        } finally {
            worker.close();
        }
    }

    private static BenchmarkCampaignExecutor.PreparedRun spec(BenchmarkExperiment.Run run,
                                                                BenchmarkManagedExecution.Operation operation) {
        Instant now = Instant.now();
        var initial = new BenchmarkRunEvidence(UUID.randomUUID(), run, now, now,
                BenchmarkRunEvidence.Status.RUNNING, BenchmarkRunEvidence.Validity.UNASSESSED, "",
                Map.of("purpose", "campaign executor test"), List.of(), List.of());
        var phasePlan = BenchmarkPhaseWorkloadPlan.uniform(run.timeline(), run.parameters(),
                BenchmarkProductWorkloads.setGet());
        var options = new BenchmarkManagedExecution.Options("campaign-test", "operation", 8,
                List.of(1_000L, 10_000L, 100_000L, 1_000_000L), Duration.ofMillis(1),
                Duration.ofMillis(1), new BenchmarkCheckpointWriter.Limits(8, 1_048_576, 1),
                Duration.ofMillis(5), phasePlan, BenchmarkRetryPolicy.none());
        return new BenchmarkCampaignExecutor.PreparedRun(initial, options, operation);
    }

    private static BenchmarkExperiment experiment(int repetitions) {
        var timeline = new BenchmarkTimeline(List.of(
                new BenchmarkTimeline.Phase("load", BenchmarkTimeline.PhaseKind.LOAD, Duration.ofMillis(20)),
                new BenchmarkTimeline.Phase("drain", BenchmarkTimeline.PhaseKind.DRAIN, Duration.ofMillis(100))),
                Duration.ofMillis(5));
        var matrix = new BenchmarkParameterMatrix(BenchmarkParameters.LoadModel.RATE_CONTROLLED,
                List.of(2), List.of(2), List.of(100.0), 2, Duration.ofMillis(50));
        return new BenchmarkExperiment(1, "campaign-test", timeline, matrix, repetitions, 1, 41, repetitions);
    }
}
