package dev.mars.peegeeq.cache.benchmark;

import dev.mars.peegeeq.cache.api.model.CacheKey;
import dev.mars.peegeeq.cache.api.model.CacheSetRequest;
import dev.mars.peegeeq.cache.api.model.CacheValue;
import dev.mars.peegeeq.cache.api.model.SetMode;
import dev.mars.peegeeq.cache.core.metrics.CacheMetrics;
import dev.mars.peegeeq.cache.pg.repository.PgCacheRepository;
import dev.mars.peegeeq.cache.pg.service.PgCacheService;
import dev.mars.peegeeq.cache.test.PgTestSupport;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import io.vertx.sqlclient.Pool;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.LongSupplier;
import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(VertxExtension.class)
@io.vertx.junit5.Timeout(value = 90, timeUnit = java.util.concurrent.TimeUnit.SECONDS)
class BenchmarkRecorderIntegrationTest {
    private static final String SCHEMA = "benchmark_recorder";
    private static final PgTestSupport postgres = new PgTestSupport("benchmark-recorder", SCHEMA);
    private static Pool pool;
    private static PgCacheService cache;
    @TempDir Path directory;

    @BeforeAll
    static void start(Vertx vertx, VertxTestContext ctx) {
        postgres.start(vertx).onSuccess(ignored -> ctx.verify(() -> {
            pool = postgres.createPool(vertx);
            cache = new PgCacheService(new PgCacheRepository(pool, SCHEMA), new CacheMetrics());
            ctx.completeNow();
        })).onFailure(ctx::failNow);
    }

    @AfterAll
    static void stop(Vertx vertx, VertxTestContext ctx) {
        (pool == null ? postgres.stop(vertx) : postgres.stopAfter(vertx, pool.close()))
                .onSuccess(ignored -> ctx.completeNow()).onFailure(ctx::failNow);
    }

    @Test
    void capturesTwoRealCacheWorkloadIntervalsWithDistributionsInOneJson(Vertx vertx, VertxTestContext ctx) {
        long origin = System.nanoTime();
        Instant started = Instant.now();
        LongSupplier clock = () -> System.nanoTime() - origin;
        var recorder = new BenchmarkIntervalRecorder(List.of(1_000_000L, 10_000_000L, 100_000_000L, 1_000_000_000L), clock);
        var samples = new ArrayList<BenchmarkIntervalRecorder.Sample>();
        batch(recorder, clock, 0).compose(ignored -> nextTurn(vertx))
                .compose(ignored -> {
                    samples.add(recorder.checkpoint());
                    return batch(recorder, clock, 20);
                }).compose(ignored -> nextTurn(vertx))
                .compose(ignored -> {
                    samples.add(recorder.checkpoint());
                    return vertx.executeBlocking(() -> {
                        var measurements = samples.stream().map(sample -> new BenchmarkRunEvidence.Measurement(
                                "cache-set-get", "workflow", "observe", sample.interval(), Map.of(), sample.distributions())).toList();
                        var base = BenchmarkRunJsonWriterTest.evidence(BenchmarkRunEvidence.Status.RUNNING, List.of(), "");
                        var evidence = new BenchmarkRunEvidence(UUID.randomUUID(), base.plan(), started, Instant.now(),
                                BenchmarkRunEvidence.Status.RUNNING, BenchmarkRunEvidence.Validity.UNASSESSED, "",
                                Map.of("purpose", "Real PostgreSQL integration verification, not capacity evidence"), measurements, List.of());
                        var writer = BenchmarkRunJsonWriter.create(directory, evidence);
                        return new JsonObject(Files.readString(writer.path()));
                    });
                }).onSuccess(json -> ctx.verify(() -> {
                    assertEquals(2, json.getJsonArray("measurements").size());
                    for (var sample : samples) {
                        assertEquals(20, sample.interval().succeeded());
                        assertEquals(0, sample.interval().failed());
                        assertEquals(20, sample.distributions().get("successfulService").sampleCount());
                        assertEquals(0, sample.interval().after().outstanding());
                    }
                    assertEquals(40, samples.getLast().interval().after().succeeded());
                    assertTrue(samples.getFirst().interval().endNanos() > samples.getFirst().interval().startNanos());
                    assertEquals("COLLECTED", json.getJsonArray("measurements").getJsonObject(1)
                            .getJsonObject("latencyDistributions").getString("status"));
                    ctx.completeNow();
                })).onFailure(ctx::failNow);
    }

    private static Future<Void> nextTurn(Vertx vertx) {
        return Future.future(promise -> vertx.runOnContext(ignored -> promise.complete()));
    }

    @Test
    void streamsRealIntervalsThroughBoundedPipelineBeforeFinalisation(Vertx vertx, VertxTestContext ctx) {
        long origin = System.nanoTime();
        Instant started = Instant.now();
        LongSupplier clock = () -> System.nanoTime() - origin;
        var recorder = new BenchmarkIntervalRecorder(List.of(1_000_000L, 100_000_000L, 1_000_000_000L), clock);
        var worker = vertx.createSharedWorkerExecutor("recorder-integration-checkpoints", 1);
        var base = BenchmarkRunJsonWriterTest.evidence(BenchmarkRunEvidence.Status.RUNNING, List.of(), "");
        UUID executionId = UUID.randomUUID();
        var environment = Map.of("purpose", "Real PostgreSQL checkpoint integration, not capacity evidence");
        var initial = new BenchmarkRunEvidence(executionId, base.plan(), started, Instant.now(),
                BenchmarkRunEvidence.Status.RUNNING, BenchmarkRunEvidence.Validity.UNASSESSED, "", environment, List.of(), List.of());
        worker.executeBlocking(() -> BenchmarkCheckpointWriter.create(directory, initial,
                new BenchmarkCheckpointWriter.Limits(2, 1_048_576, 1))).compose(writer -> {
            var pipeline = new BenchmarkCheckpointPipeline(worker, writer, 2, 2_097_152);
            return batch(recorder, clock, 40).compose(ignored -> nextTurn(vertx)).compose(ignored -> {
                var first = recorder.checkpoint();
                var measurement = new BenchmarkRunEvidence.Measurement("cache-set-get", "workflow", "observe",
                        first.interval(), Map.of(), first.distributions());
                return pipeline.submit(new BenchmarkRunEvidence(executionId, base.plan(), started, Instant.now(),
                        BenchmarkRunEvidence.Status.RUNNING, BenchmarkRunEvidence.Validity.UNASSESSED, "", environment,
                        List.of(measurement), List.of()));
            }).compose(ignored -> batch(recorder, clock, 60)).compose(ignored -> nextTurn(vertx)).compose(ignored -> {
                var second = recorder.checkpoint();
                var measurement = new BenchmarkRunEvidence.Measurement("cache-set-get", "workflow", "observe",
                        second.interval(), Map.of(), second.distributions());
                return pipeline.submit(new BenchmarkRunEvidence(executionId, base.plan(), started, Instant.now(),
                        BenchmarkRunEvidence.Status.COMPLETED, BenchmarkRunEvidence.Validity.UNASSESSED, "", environment,
                        List.of(measurement), List.of()));
            }).eventually(pipeline::close).compose(ignored -> worker.executeBlocking(() -> {
                var json = new JsonObject(Files.readString(writer.path()));
                assertEquals("COMPLETED", json.getJsonObject("execution").getString("status"));
                var measurements = json.getJsonArray("measurements");
                assertEquals(2, measurements.size());
                assertEquals(40L, measurements.getJsonObject(1).getJsonObject("after").getLong("succeeded"));
                assertEquals(20L, measurements.getJsonObject(1).getJsonObject("latencyDistributions")
                        .getJsonObject("series").getJsonObject("successfulService").getLong("sampleCount"));
                assertEquals(0, pipeline.pendingCount());
                try (var paths = Files.list(writer.path().getParent())) {
                    assertTrue(paths.noneMatch(path -> path.toString().endsWith(".tmp")));
                }
                return null;
            }));
        }).eventually(worker::close).onSuccess(ignored -> ctx.completeNow()).onFailure(ctx::failNow);
    }

    private static Future<Void> batch(BenchmarkIntervalRecorder recorder, LongSupplier clock, int first) {
        var operations = new ArrayList<Future<Void>>();
        for (int index = first; index < first + 20; index++) {
            var key = new CacheKey("measurements", "key-" + index);
            String expected = "value-" + index;
            var request = new CacheSetRequest(key, CacheValue.ofString(expected), null, SetMode.UPSERT, null, false);
            recorder.schedule(); recorder.admit(); recorder.start();
            long started = clock.getAsLong();
            operations.add(cache.set(request).compose(ignored -> cache.get(key)).<Void>map(value -> {
                assertEquals(expected, value.orElseThrow().value().asString());
                return null;
            }).transform(result -> {
                long latency = clock.getAsLong() - started;
                recorder.complete(result.succeeded() ? BenchmarkIntervalRecorder.Outcome.SUCCESS
                        : BenchmarkIntervalRecorder.Outcome.FAILURE, latency, latency);
                return result.succeeded() ? Future.succeededFuture() : Future.failedFuture(result.cause());
            }));
        }
        return Future.all(operations).mapEmpty();
    }

    @Test
    void timedSessionPublishesRealIntervalAndRecoveryRecognisesFinalisedEvidence(Vertx vertx, VertxTestContext ctx) {
        long origin = System.nanoTime();
        Instant started = Instant.now();
        LongSupplier elapsed = () -> System.nanoTime() - origin;
        var recorder = new BenchmarkIntervalRecorder(List.of(1_000_000L, 100_000_000L, 1_000_000_000L), elapsed);
        var worker = vertx.createSharedWorkerExecutor("recorder-session-integration", 1);
        var base = BenchmarkRunJsonWriterTest.evidence(BenchmarkRunEvidence.Status.RUNNING, List.of(), "");
        var initial = new BenchmarkRunEvidence(UUID.randomUUID(), base.plan(), started, Instant.now(),
                BenchmarkRunEvidence.Status.RUNNING, BenchmarkRunEvidence.Validity.UNASSESSED, "",
                Map.of("purpose", "PostgreSQL cadence/recovery verification, not capacity evidence"), List.of(), List.of());
        BenchmarkCheckpointSession.open(vertx, worker, directory, initial,
                new BenchmarkCheckpointWriter.Limits(4, 1_048_576, 1), java.time.Duration.ofMillis(5), java.time.Clock.systemUTC())
                .compose(session -> batch(recorder, elapsed, 80).compose(ignored -> nextTurn(vertx)).compose(ignored -> {
                    var sample = recorder.checkpoint();
                    return session.append(new BenchmarkRunEvidence.Measurement("cache-set-get", "workflow", "observe",
                            sample.interval(), Map.of(), sample.distributions()));
                }).compose(ignored -> session.finish(BenchmarkRunEvidence.Status.COMPLETED,
                        BenchmarkRunEvidence.Validity.UNASSESSED, "integration complete"))
                .compose(ignored -> worker.executeBlocking(() -> {
                    var inspection = BenchmarkCheckpointRecovery.inspect(session.path());
                    assertEquals(BenchmarkCheckpointRecovery.Classification.FINALISED, inspection.classification());
                    assertEquals(1, inspection.measurementCount());
                    var json = new JsonObject(Files.readString(session.path()));
                    assertEquals(20L, json.getJsonArray("measurements").getJsonObject(0)
                            .getJsonObject("after").getLong("succeeded"));
                    assertEquals("5", json.getJsonObject("environment").getString("checkpoint.maximumDelayMillis"));
                    return null;
                })).eventually(() -> session.completion().isComplete() ? session.completion() : session.stop("integration cleanup")))
                .eventually(worker::close).onSuccess(ignored -> ctx.completeNow()).onFailure(ctx::failNow);
    }
}
