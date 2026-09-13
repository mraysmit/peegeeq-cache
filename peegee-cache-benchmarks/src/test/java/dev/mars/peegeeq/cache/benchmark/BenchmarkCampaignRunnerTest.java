package dev.mars.peegeeq.cache.benchmark;

import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(VertxExtension.class)
class BenchmarkCampaignRunnerTest {
    @TempDir Path directory;

    @Test
    void verifiesTargetResetsRunsCleansAndPublishesManifestAndAnalysis(Vertx vertx, VertxTestContext ctx) {
        var worker = vertx.createSharedWorkerExecutor("campaign-lifecycle", 1);
        var events = new ArrayList<String>();
        var plan = new BenchmarkCampaignPlan(1, experiment(2),
                BenchmarkDeploymentTarget.localTestcontainers("postgres:18.3-alpine", "benchmark_lifecycle"),
                BenchmarkCampaignPlan.Order.DECLARED, BenchmarkCampaignPlan.Reset.RECREATE_RUN_SCHEMA, 3);
        var adapter = new BenchmarkCampaignRunner.TargetAdapter() {
            public Future<BenchmarkCampaignRunner.TargetIdentity> verify(BenchmarkDeploymentTarget target) {
                events.add("verify");
                return Future.succeededFuture(new BenchmarkCampaignRunner.TargetIdentity(
                        target.id(), "PostgreSQL 18.3", "local-container", false));
            }
            public Future<BenchmarkCampaignExecutor.PreparedRun> prepare(BenchmarkCampaignPlan.Manifest manifest) {
                events.add("prepare-" + manifest.run().repetitionIndex());
                return Future.succeededFuture(spec(manifest.run()));
            }
            public Future<Void> reset(BenchmarkCampaignPlan.Manifest manifest) {
                events.add("reset-" + manifest.run().repetitionIndex());
                return Future.succeededFuture();
            }
            public Future<Void> cleanup(BenchmarkCampaignPlan.Manifest manifest) {
                events.add("cleanup-" + manifest.run().repetitionIndex());
                return Future.succeededFuture();
            }
        };
        var policy = new BenchmarkAnalysisPolicy(1, "BASELINE", 1, 1.5, 2, 1.2, Duration.ofMillis(5));

        BenchmarkCampaignRunner.run(vertx, worker, directory, plan, adapter, policy)
                .compose(result -> vertx.executeBlocking(() -> {
                    assertEquals(2, result.runs().size());
                    assertEquals(List.of("verify", "reset-0", "prepare-0", "cleanup-0",
                            "reset-1", "prepare-1", "cleanup-1"), events);
                    assertTrue(Files.isRegularFile(result.manifestPath()));
                    assertTrue(result.runs().stream().allMatch(run -> Files.isRegularFile(run.htmlPath())));
                    assertTrue(Files.readString(result.manifestPath()).contains("COMPLETED"));
                    try (var paths = Files.list(directory)) { assertEquals(5, paths.count()); }
                    return null;
                })).eventually(worker::close).onComplete(ctx.succeeding(ignored -> ctx.completeNow()));
    }

    @Test
    void cleanupFailureIsSuppressedBehindPreparationFailureAndManifestIsRetained(Vertx vertx, VertxTestContext ctx) {
        var worker = vertx.createSharedWorkerExecutor("campaign-failure-cleanup", 1);
        var primary = new IllegalStateException("preparation failed");
        var cleanup = new IllegalStateException("cleanup failed");
        var plan = new BenchmarkCampaignPlan(1, experiment(1),
                BenchmarkDeploymentTarget.localTestcontainers("postgres:18.3-alpine", "benchmark_failure"),
                BenchmarkCampaignPlan.Order.DECLARED, BenchmarkCampaignPlan.Reset.CLEAR_RUN_NAMESPACE, 1);
        var cleaned = new java.util.concurrent.atomic.AtomicInteger();
        var adapter = new BenchmarkCampaignRunner.TargetAdapter() {
            public Future<BenchmarkCampaignRunner.TargetIdentity> verify(BenchmarkDeploymentTarget target) {
                return Future.succeededFuture(new BenchmarkCampaignRunner.TargetIdentity(target.id(), "PostgreSQL 18", "local", false));
            }
            public Future<Void> reset(BenchmarkCampaignPlan.Manifest manifest) { return Future.succeededFuture(); }
            public Future<BenchmarkCampaignExecutor.PreparedRun> prepare(BenchmarkCampaignPlan.Manifest manifest) {
                return Future.failedFuture(primary);
            }
            public Future<Void> cleanup(BenchmarkCampaignPlan.Manifest manifest) {
                cleaned.incrementAndGet();
                return Future.failedFuture(cleanup);
            }
        };
        var policy = new BenchmarkAnalysisPolicy(1, "BASELINE", 1, 1.5, 2, 1.2, Duration.ofMillis(5));

        BenchmarkCampaignRunner.run(vertx, worker, directory, plan, adapter, policy)
                .onSuccess(ignored -> ctx.failNow("Expected campaign failure"))
                .onFailure(failure -> worker.close().onComplete(ignored -> ctx.verify(() -> {
                    assertSame(primary, failure);
                    assertEquals(1, cleaned.get());
                    assertArrayEquals(new Throwable[]{cleanup}, primary.getSuppressed());
                    assertTrue(Files.readString(directory.resolve("campaign-manifest.json")).contains("FAILED"));
                    ctx.completeNow();
                })));
    }

    private static BenchmarkCampaignExecutor.PreparedRun spec(BenchmarkExperiment.Run run) {
        Instant now = Instant.now();
        var initial = new BenchmarkRunEvidence(UUID.randomUUID(), run, now, now,
                BenchmarkRunEvidence.Status.RUNNING, BenchmarkRunEvidence.Validity.UNASSESSED, "",
                Map.of("purpose", "campaign lifecycle test"), List.of(), List.of());
        var phasePlan = BenchmarkPhaseWorkloadPlan.uniform(run.timeline(), run.parameters(),
                BenchmarkProductWorkloads.setGet());
        var options = new BenchmarkManagedExecution.Options("campaign-lifecycle", "operation", 8,
                List.of(1_000L, 10_000L, 100_000L, 1_000_000L), Duration.ofMillis(1), Duration.ofMillis(1),
                new BenchmarkCheckpointWriter.Limits(8, 1_048_576, 1), Duration.ofMillis(5),
                phasePlan, BenchmarkRetryPolicy.none());
        return new BenchmarkCampaignExecutor.PreparedRun(initial, options, attempt -> Future.succeededFuture());
    }

    private static BenchmarkExperiment experiment(int repetitions) {
        var timeline = new BenchmarkTimeline(List.of(
                new BenchmarkTimeline.Phase("load", BenchmarkTimeline.PhaseKind.LOAD, Duration.ofMillis(20)),
                new BenchmarkTimeline.Phase("drain", BenchmarkTimeline.PhaseKind.DRAIN, Duration.ofMillis(100))),
                Duration.ofMillis(5));
        var matrix = new BenchmarkParameterMatrix(BenchmarkParameters.LoadModel.RATE_CONTROLLED,
                List.of(1), List.of(1), List.of(50.0), 1, Duration.ofMillis(50));
        return new BenchmarkExperiment(1, "campaign-lifecycle", timeline, matrix, repetitions, 1, 41, repetitions);
    }
}
