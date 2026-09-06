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
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.PoolOptions;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(VertxExtension.class)
@io.vertx.junit5.Timeout(value = 90, timeUnit = java.util.concurrent.TimeUnit.SECONDS)
class BenchmarkSchedulerIntegrationTest {
    private static final String SCHEMA = "benchmark_scheduler";
    private static final PgTestSupport postgres = new PgTestSupport("benchmark-scheduler", SCHEMA);
    private static final Duration DEMAND = Duration.ofMillis(200);
    private static Pool pool;
    private static PgCacheService cache;
    @TempDir Path directory;

    @BeforeAll
    static void start(Vertx vertx, VertxTestContext ctx) {
        postgres.start(vertx).onSuccess(ignored -> ctx.verify(() -> {
            pool = Pool.pool(vertx, postgres.connectOptions(), new PoolOptions().setMaxSize(2));
            cache = new PgCacheService(new PgCacheRepository(pool, SCHEMA), new CacheMetrics());
            ctx.completeNow();
        })).onFailure(ctx::failNow);
    }

    @AfterAll
    static void stop(Vertx vertx, VertxTestContext ctx) {
        (pool == null ? postgres.stop(vertx) : postgres.stopAfter(vertx, pool.close()))
                .onSuccess(ignored -> ctx.completeNow()).onFailure(ctx::failNow);
    }

    @ParameterizedTest
    @EnumSource(BenchmarkParameters.LoadModel.class)
    void scheduledRealCacheWorkflowsReconcileAcrossIntervalsAndOneJson(BenchmarkParameters.LoadModel model,
                                                                      Vertx vertx, VertxTestContext ctx) {
        var parameters = new BenchmarkParameters(model, 3, 2,
                model == BenchmarkParameters.LoadModel.RATE_CONTROLLED ? 100 : 0, 3, Duration.ofSeconds(2));
        String namespace = "scheduler-" + UUID.randomUUID();
        exercise(vertx, parameters, launch -> {
            var key = new CacheKey(namespace, "key-" + launch.id());
            String expected = "value-" + launch.id();
            return cache.set(new CacheSetRequest(key, CacheValue.ofString(expected), null, SetMode.UPSERT, null, false))
                    .compose(ignored -> cache.get(key)).map(value -> {
                        assertEquals(expected, value.orElseThrow().value().asString());
                        return (Void) null;
                    });
        }).compose(result -> vertx.executeBlocking(() -> {
            assertTrue(result.measurements().size() >= 2);
            var totals = result.measurements().getLast().interval().after();
            assertTrue(totals.succeeded() > 0);
            assertEquals(0, totals.failed());
            assertEquals(0, totals.timedOut());
            assertEquals(0, totals.outstanding());
            assertEquals(0, result.statistics().physicalInFlight());
            if (model == BenchmarkParameters.LoadModel.RATE_CONTROLLED) assertEquals(20, totals.scheduled());
            var writer = BenchmarkCheckpointWriter.create(directory, result.evidence(List.of(), BenchmarkRunEvidence.Status.RUNNING),
                    new BenchmarkCheckpointWriter.Limits(128, 4_194_304, 1));
            for (var measurement : result.measurements()) {
                writer.append(result.evidence(List.of(measurement), BenchmarkRunEvidence.Status.RUNNING));
            }
            writer.append(result.evidence(List.of(), BenchmarkRunEvidence.Status.COMPLETED));
            var json = new JsonObject(Files.readString(writer.path()));
            assertEquals(result.measurements().size(), json.getJsonArray("measurements").size());
            assertEquals("COMPLETED", json.getJsonObject("execution").getString("status"));
            var last = json.getJsonArray("measurements").getJsonObject(result.measurements().size() - 1);
            assertEquals(0.0, last.getJsonObject("metrics").getJsonObject("scheduler.physicalInFlight").getDouble("value"));
            assertEquals(totals.succeeded(), last.getJsonObject("after").getLong("succeeded"));
            try (var paths = Files.list(directory)) { assertEquals(1, paths.count()); }
            assertEquals(BenchmarkCheckpointRecovery.Classification.FINALISED,
                    BenchmarkCheckpointRecovery.inspect(writer.path()).classification());
            return null;
        })).onSuccess(ignored -> ctx.completeNow()).onFailure(ctx::failNow);
    }

    @Test
    void realSlowPostgresQueriesRetainPhysicalSlotsAfterLogicalDeadlines(Vertx vertx, VertxTestContext ctx) {
        var parameters = new BenchmarkParameters(BenchmarkParameters.LoadModel.RATE_CONTROLLED,
                2, 2, 1_000, 2, Duration.ofMillis(2));
        // PostgreSQL supplies the delay; this is not a sleep or a transport substitute in the generator.
        exercise(vertx, parameters, launch -> pool.query("SELECT pg_sleep(0.02)").execute().mapEmpty())
                .onSuccess(result -> ctx.verify(() -> {
                    var totals = result.measurements().getLast().interval().after();
                    assertEquals(200, totals.scheduled());
                    assertTrue(totals.started() > 0);
                    assertEquals(totals.started(), totals.timedOut());
                    assertEquals(totals.timedOut(), result.statistics().lateSucceeded());
                    assertEquals(0, result.statistics().physicalInFlight());
                    assertEquals(0, result.statistics().lateFailed());
                    assertEquals(0, totals.outstanding());
                    assertEquals(totals.rejected(), result.statistics().generatorMissed() + result.statistics().admissionRejected());
                    assertTrue(result.measurements().stream().anyMatch(measurement ->
                            measurement.metrics().get("scheduler.physicalInFlight").value() > measurement.interval().after().inFlight()));
                    ctx.completeNow();
                })).onFailure(ctx::failNow);
    }

    private record Observed(UUID id, Instant started, BenchmarkParameters parameters,
                            List<BenchmarkRunEvidence.Measurement> measurements, BenchmarkWorkloadScheduler.Statistics statistics) {
        BenchmarkRunEvidence evidence(List<BenchmarkRunEvidence.Measurement> batch, BenchmarkRunEvidence.Status status) {
            // One test phase includes a 200 ms demand horizon and up to five seconds of physical drain.
            var timeline = new BenchmarkTimeline(List.of(new BenchmarkTimeline.Phase("load-and-drain",
                    BenchmarkTimeline.PhaseKind.LOAD, DEMAND.plusSeconds(5))), Duration.ofMillis(50));
            var plan = new BenchmarkExperiment.Run(1, "scheduler-integration", 0, 0, 0, 0, parameters, timeline);
            return new BenchmarkRunEvidence(id, plan, started, Instant.now(), status, BenchmarkRunEvidence.Validity.UNASSESSED,
                    "", Map.of("purpose", "Scheduler/PostgreSQL contract verification, not capacity evidence",
                    "scheduler.demandNanos", Long.toString(DEMAND.toNanos()), "scheduler.driver", "test-owned 1 ms Vert.x tick",
                    "scheduler.maximumArrivalsPerAdvance", "4", "scheduler.maximumDrainMillis", "5000"), batch, List.of());
        }
    }

    /** Bounded test driver only; production phase/cancellation/checkpoint orchestration is a separate B2 slice. */
    private static Future<Observed> exercise(Vertx vertx, BenchmarkParameters parameters,
                                             Function<BenchmarkWorkloadScheduler.Launch, Future<Void>> operation) {
        Promise<Observed> done = Promise.promise();
        vertx.runOnContext(ignored -> {
            var scheduler = new BenchmarkWorkloadScheduler(parameters, DEMAND, 4,
                    List.of(1_000_000L, 5_000_000L, 50_000_000L, 1_000_000_000L), System::nanoTime);
            Instant started = Instant.now();
            var measurements = new ArrayList<BenchmarkRunEvidence.Measurement>();
            long origin = System.nanoTime();
            long[] lastCheckpoint = {origin};
            boolean[] finishing = {false};
            var owner = Vertx.currentContext();
            Runnable tick = () -> {
                if (done.future().isComplete()) return;
                try {
                    long now = System.nanoTime();
                    // Checkpoint before the next event: actual half-open boundaries, no assumed 50 ms denominator.
                    if (finishing[0] || now - lastCheckpoint[0] >= 50_000_000) {
                        var sample = scheduler.checkpoint();
                        measurements.add(new BenchmarkRunEvidence.Measurement("scheduled-postgres", "workflow", "load-and-drain",
                                sample.interval(), scheduler.statistics().metrics(), sample.distributions()));
                        lastCheckpoint[0] = now;
                    }
                    if (finishing[0]) {
                        done.complete(new Observed(UUID.randomUUID(), started, parameters, List.copyOf(measurements), scheduler.statistics()));
                        return;
                    }
                    for (var launch : scheduler.advance()) {
                        operation.apply(launch).onComplete(result -> owner.runOnContext(callback -> {
                            if (done.future().isComplete()) return;
                            try {
                                scheduler.complete(launch.id(), result.succeeded());
                                if (result.failed()) done.tryFail(result.cause());
                            } catch (Throwable failure) { done.tryFail(failure); }
                        }));
                    }
                    assertTrue(scheduler.statistics().physicalInFlight() <= parameters.concurrency());
                    assertTrue(scheduler.statistics().queued() <= parameters.queueCapacity());
                    finishing[0] = scheduler.drained();
                } catch (Throwable failure) { done.tryFail(failure); }
            };
            long periodic = vertx.setPeriodic(1, timer -> tick.run());
            long watchdog = vertx.setTimer(DEMAND.toMillis() + 5_000,
                    timer -> done.tryFail("Scheduler integration did not physically drain"));
            done.future().onComplete(result -> {
                vertx.cancelTimer(periodic);
                vertx.cancelTimer(watchdog);
            });
            tick.run();
        });
        return done.future();
    }
}
