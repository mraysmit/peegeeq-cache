package dev.mars.peegeeq.cache.benchmark;

import io.vertx.core.Future;
import io.vertx.core.Promise;
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
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(VertxExtension.class)
@io.vertx.junit5.Timeout(value = 15, timeUnit = TimeUnit.SECONDS)
class BenchmarkManagedExecutionTest {
    private static final List<Long> LATENCY_BOUNDS =
            List.of(1_000_000L, 5_000_000L, 50_000_000L, 1_000_000_000L);
    @TempDir Path directory;

    @Test
    void executesDemandAcrossPhasesAndPublishesFinalEvidence(Vertx vertx, VertxTestContext ctx) {
        var worker = vertx.createSharedWorkerExecutor("managed-success", 1);
        var phases = new ArrayList<String>();
        var initial = manifest(timeline(Duration.ofMillis(40), Duration.ofMillis(40), Duration.ofMillis(300)),
                BenchmarkParameters.LoadModel.CLOSED_LOOP);

        BenchmarkManagedExecution.start(vertx, worker, directory, initial, options(initial), attempt -> {
            phases.add(attempt.phase().name());
            assertEquals(BenchmarkProductWorkloads.CACHE_SET_GET, attempt.productOperation());
            return Future.succeededFuture();
        }).compose(BenchmarkManagedExecution.Execution::completion).compose(result -> vertx.executeBlocking(() -> {
            assertEquals(BenchmarkRunEvidence.Status.COMPLETED, result.status());
            assertEquals(BenchmarkRunEvidence.Validity.VALID, result.validity());
            assertTrue(result.statistics().physicalInFlight() == 0);
            assertTrue(phases.contains("warmup"));
            assertTrue(phases.contains("load"));
            var json = new io.vertx.core.json.JsonObject(Files.readString(result.path()));
            assertEquals("COMPLETED", json.getJsonObject("execution").getString("status"));
            assertEquals("VALID", json.getJsonObject("execution").getString("measurementValidity"));
            assertTrue(json.getJsonArray("measurements").size() >= 3);
            assertTrue(json.getJsonArray("measurements").stream()
                    .map(value -> (io.vertx.core.json.JsonObject) value)
                    .anyMatch(value -> "load".equals(value.getString("phase"))));
            assertEquals(BenchmarkCheckpointRecovery.Classification.FINALISED,
                    BenchmarkCheckpointRecovery.inspect(result.path()).classification());
            return null;
        })).eventually(worker::close).onComplete(ctx.succeeding(ignored -> ctx.completeNow()));
    }

    @Test
    void explicitStopWaitsForPhysicalCompletionAndRetainsStoppedEvidence(Vertx vertx, VertxTestContext ctx) {
        var worker = vertx.createSharedWorkerExecutor("managed-stop", 1);
        var physical = new ArrayList<Promise<Void>>();
        var started = Promise.<Void>promise();
        var initial = manifest(timeline(Duration.ofMillis(200), Duration.ofMillis(200), Duration.ofMillis(300)),
                BenchmarkParameters.LoadModel.CLOSED_LOOP);

        BenchmarkManagedExecution.start(vertx, worker, directory, initial, options(initial), attempt -> {
            var pending = Promise.<Void>promise();
            physical.add(pending);
            if (physical.size() == 2) started.tryComplete();
            return pending.future();
        }).compose(execution -> started.future().compose(ignored -> {
            var stopping = execution.stop("operator requested stop");
            assertFalse(stopping.isComplete());
            assertSame(stopping, execution.stop("operator requested stop"));
            assertTrue(execution.stop("different reason").failed());
            physical.forEach(Promise::complete);
            return stopping;
        })).compose(result -> vertx.executeBlocking(() -> {
            assertEquals(BenchmarkRunEvidence.Status.STOPPED, result.status(), result.detail());
            assertEquals(0, result.statistics().physicalInFlight());
            var json = new io.vertx.core.json.JsonObject(Files.readString(result.path()));
            assertEquals("STOPPED", json.getJsonObject("execution").getString("status"));
            assertEquals("operator requested stop", json.getJsonObject("execution").getString("detail"));
            return null;
        })).eventually(worker::close).onComplete(ctx.succeeding(ignored -> ctx.completeNow()));
    }

    @Test
    void drainDeadlineFinalisesInvalidEvidenceWithoutWaitingForTransport(Vertx vertx, VertxTestContext ctx) {
        var worker = vertx.createSharedWorkerExecutor("managed-drain-timeout", 1);
        var initial = manifest(timeline(Duration.ofMillis(30), Duration.ofMillis(30), Duration.ofMillis(40)),
                BenchmarkParameters.LoadModel.CLOSED_LOOP);

        BenchmarkManagedExecution.start(vertx, worker, directory, initial, options(initial),
                attempt -> Promise.<Void>promise().future())
                .compose(BenchmarkManagedExecution.Execution::completion)
                .compose(result -> vertx.executeBlocking(() -> {
                    assertEquals(BenchmarkRunEvidence.Status.FAILED, result.status());
                    assertTrue(result.statistics().physicalInFlight() > 0);
                    var json = new io.vertx.core.json.JsonObject(Files.readString(result.path()));
                    assertEquals("FAILED", json.getJsonObject("execution").getString("status"));
                    assertEquals("INVALID", json.getJsonObject("execution").getString("measurementValidity"));
                    assertTrue(json.getJsonObject("execution").getString("detail").contains("drain deadline"));
                    return null;
                })).eventually(worker::close).onComplete(ctx.succeeding(ignored -> ctx.completeNow()));
    }

    @Test
    void operationFailuresAreMeasuredWithoutBecomingDriverFailures(Vertx vertx, VertxTestContext ctx) {
        var worker = vertx.createSharedWorkerExecutor("managed-operation-failure", 1);
        var initial = manifest(timeline(Duration.ofMillis(30), Duration.ofMillis(30), Duration.ofMillis(200)),
                BenchmarkParameters.LoadModel.RATE_CONTROLLED);

        BenchmarkManagedExecution.start(vertx, worker, directory, initial, options(initial),
                attempt -> Future.failedFuture("measured operation failure"))
                .compose(BenchmarkManagedExecution.Execution::completion)
                .compose(result -> vertx.executeBlocking(() -> {
                    assertEquals(BenchmarkRunEvidence.Status.COMPLETED, result.status());
                    assertEquals(BenchmarkRunEvidence.Validity.VALID, result.validity());
                    var json = new io.vertx.core.json.JsonObject(Files.readString(result.path()));
                    var measurements = json.getJsonArray("measurements");
                    var last = measurements.getJsonObject(measurements.size() - 1);
                    assertTrue(last.getJsonObject("after").getLong("failed") > 0);
                    assertEquals(0L, last.getJsonObject("after").getLong("outstanding"));
                    return null;
                })).eventually(worker::close).onComplete(ctx.succeeding(ignored -> ctx.completeNow()));
    }

    @Test
    void appliesPhaseSpecificLoadAndOperationMixToExecutionAndEvidence(Vertx vertx, VertxTestContext ctx) {
        var worker = vertx.createSharedWorkerExecutor("managed-phase-load", 1);
        var initial = manifest(timeline(Duration.ofMillis(40), Duration.ofMillis(60), Duration.ofMillis(200)),
                BenchmarkParameters.LoadModel.RATE_CONTROLLED);
        var getOnly = new BenchmarkWorkloadMix(List.of(
                new BenchmarkWorkloadMix.Weight(BenchmarkProductWorkloads.CACHE_GET, 1)));
        var setOnly = new BenchmarkWorkloadMix(List.of(
                new BenchmarkWorkloadMix.Weight(BenchmarkProductWorkloads.CACHE_SET, 1)));
        var phasePlan = new BenchmarkPhaseWorkloadPlan(initial.plan().timeline(), initial.plan().parameters(), List.of(
                new BenchmarkPhaseWorkloadPlan.Profile("warmup", 1, 25, getOnly),
                new BenchmarkPhaseWorkloadPlan.Profile("load", 2, 100, setOnly)));
        var observed = new ArrayList<String>();

        BenchmarkManagedExecution.start(vertx, worker, directory, initial, options(initial, phasePlan),
                attempt -> {
                    observed.add(attempt.phase().name() + ":" + attempt.productOperation());
                    return Future.succeededFuture();
                }).compose(BenchmarkManagedExecution.Execution::completion)
                .compose(result -> vertx.executeBlocking(() -> {
                    assertTrue(observed.contains("warmup:" + BenchmarkProductWorkloads.CACHE_GET));
                    assertTrue(observed.contains("load:" + BenchmarkProductWorkloads.CACHE_SET));
                    var json = new io.vertx.core.json.JsonObject(Files.readString(result.path()));
                    assertEquals("1", json.getJsonObject("environment")
                            .getString("workload.phase.warmup.concurrency"));
                    assertEquals("cache.set=1", json.getJsonObject("environment")
                            .getString("workload.phase.load.mix"));
                    var measurements = json.getJsonArray("measurements").stream()
                            .map(value -> (io.vertx.core.json.JsonObject) value).toList();
                    var warmup = measurements.stream().filter(value -> "warmup".equals(value.getString("phase")))
                            .findFirst().orElseThrow();
                    var load = measurements.stream().filter(value -> "load".equals(value.getString("phase")))
                            .findFirst().orElseThrow();
                    assertEquals(1.0, warmup.getJsonObject("metrics")
                            .getJsonObject("load.targetConcurrency").getDouble("value"));
                    assertEquals(25.0, warmup.getJsonObject("metrics")
                            .getJsonObject("load.targetOfferedPerSecond").getDouble("value"));
                    assertEquals(2.0, load.getJsonObject("metrics")
                            .getJsonObject("load.targetConcurrency").getDouble("value"));
                    assertEquals(100.0, load.getJsonObject("metrics")
                            .getJsonObject("load.targetOfferedPerSecond").getDouble("value"));
                    return null;
                })).eventually(worker::close).onComplete(ctx.succeeding(ignored -> ctx.completeNow()));
    }

    @Test
    void retriesRetainLogicalIdentityPhaseAndOperationButCountPhysicalAttempts(Vertx vertx,
                                                                               VertxTestContext ctx) {
        var worker = vertx.createSharedWorkerExecutor("managed-retries", 1);
        var initial = manifest(timeline(Duration.ofMillis(30), Duration.ofMillis(30), Duration.ofMillis(200)),
                BenchmarkParameters.LoadModel.RATE_CONTROLLED);
        var phasePlan = BenchmarkPhaseWorkloadPlan.uniform(initial.plan().timeline(), initial.plan().parameters(),
                BenchmarkProductWorkloads.setGet());
        var options = options(initial, phasePlan, new BenchmarkRetryPolicy(2, Duration.ZERO));
        var attempts = new java.util.LinkedHashMap<Long, List<BenchmarkManagedExecution.Attempt>>();

        BenchmarkManagedExecution.start(vertx, worker, directory, initial, options, attempt -> {
            attempts.computeIfAbsent(attempt.logicalRequestId(), ignored -> new ArrayList<>()).add(attempt);
            return attempt.attemptIndex() == 0 ? Future.failedFuture("retryable") : Future.succeededFuture();
        }).compose(BenchmarkManagedExecution.Execution::completion)
                .compose(result -> vertx.executeBlocking(() -> {
                    assertEquals(BenchmarkRunEvidence.Status.COMPLETED, result.status());
                    assertFalse(attempts.isEmpty());
                    attempts.values().forEach(values -> {
                        assertEquals(List.of(0, 1), values.stream()
                                .map(BenchmarkManagedExecution.Attempt::attemptIndex).toList());
                        assertEquals(1, values.stream().map(BenchmarkManagedExecution.Attempt::logicalRequestId)
                                .distinct().count());
                        assertEquals(1, values.stream().map(value -> value.phase().name()).distinct().count());
                        assertEquals(1, values.stream().map(BenchmarkManagedExecution.Attempt::productOperation)
                                .distinct().count());
                    });
                    var json = new io.vertx.core.json.JsonObject(Files.readString(result.path()));
                    var measurements = json.getJsonArray("measurements");
                    var metrics = measurements.getJsonObject(measurements.size() - 1).getJsonObject("metrics");
                    assertEquals(attempts.size() * 2.0,
                            metrics.getJsonObject("retry.attemptStarted").getDouble("value"));
                    assertEquals(attempts.size() * 1.0,
                            metrics.getJsonObject("retry.retriesScheduled").getDouble("value"));
                    assertEquals(0.0, metrics.getJsonObject("retry.retriesExhausted").getDouble("value"));
                    return null;
                })).eventually(worker::close).onComplete(ctx.succeeding(ignored -> ctx.completeNow()));
    }

    private static BenchmarkManagedExecution.Options options(BenchmarkRunEvidence initial) {
        return options(initial, BenchmarkPhaseWorkloadPlan.uniform(initial.plan().timeline(),
                initial.plan().parameters(), BenchmarkProductWorkloads.setGet()));
    }

    private static BenchmarkManagedExecution.Options options(BenchmarkRunEvidence initial,
                                                               BenchmarkPhaseWorkloadPlan phasePlan) {
        return options(initial, phasePlan, BenchmarkRetryPolicy.none());
    }

    private static BenchmarkManagedExecution.Options options(BenchmarkRunEvidence initial,
                                                               BenchmarkPhaseWorkloadPlan phasePlan,
                                                               BenchmarkRetryPolicy retryPolicy) {
        return new BenchmarkManagedExecution.Options("managed", "operation", 8, LATENCY_BOUNDS,
                Duration.ofMillis(1), Duration.ofMillis(2),
                new BenchmarkCheckpointWriter.Limits(8, 1_048_576, 1), Duration.ofMillis(5), phasePlan,
                retryPolicy);
    }

    private static BenchmarkRunEvidence manifest(BenchmarkTimeline timeline,
                                                  BenchmarkParameters.LoadModel loadModel) {
        var parameters = new BenchmarkParameters(loadModel, 2, 2,
                loadModel == BenchmarkParameters.LoadModel.RATE_CONTROLLED ? 100 : 0,
                2, Duration.ofMillis(100));
        var plan = new BenchmarkExperiment.Run(BenchmarkExperiment.SCHEMA_VERSION, "managed-test",
                0, 0, 0, 17, parameters, timeline);
        Instant now = Instant.now();
        return new BenchmarkRunEvidence(UUID.randomUUID(), plan, now, now,
                BenchmarkRunEvidence.Status.RUNNING, BenchmarkRunEvidence.Validity.UNASSESSED, "",
                Map.of("purpose", "managed execution contract verification, not capacity evidence"),
                List.of(), List.of());
    }

    private static BenchmarkTimeline timeline(Duration warmup, Duration load, Duration drain) {
        return new BenchmarkTimeline(List.of(
                new BenchmarkTimeline.Phase("warmup", BenchmarkTimeline.PhaseKind.WARMUP, warmup),
                new BenchmarkTimeline.Phase("load", BenchmarkTimeline.PhaseKind.LOAD, load),
                new BenchmarkTimeline.Phase("drain", BenchmarkTimeline.PhaseKind.DRAIN, drain)),
                Duration.ofMillis(10));
    }
}
