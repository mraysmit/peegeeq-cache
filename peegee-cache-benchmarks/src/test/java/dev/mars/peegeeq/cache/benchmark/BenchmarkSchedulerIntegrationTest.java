package dev.mars.peegeeq.cache.benchmark;

import dev.mars.peegeeq.cache.api.model.CacheKey;
import dev.mars.peegeeq.cache.api.model.CacheSetRequest;
import dev.mars.peegeeq.cache.api.model.CacheValue;
import dev.mars.peegeeq.cache.api.model.SetMode;
import dev.mars.peegeeq.cache.core.metrics.CacheMetrics;
import dev.mars.peegeeq.cache.pg.repository.PgCacheRepository;
import dev.mars.peegeeq.cache.pg.repository.PgCounterRepository;
import dev.mars.peegeeq.cache.pg.repository.PgLockRepository;
import dev.mars.peegeeq.cache.pg.repository.PgScanRepository;
import dev.mars.peegeeq.cache.pg.service.PgCacheService;
import dev.mars.peegeeq.cache.pg.service.PgCounterService;
import dev.mars.peegeeq.cache.pg.service.PgLockService;
import dev.mars.peegeeq.cache.pg.service.PgScanService;
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
    private static PgCounterService counters;
    private static PgLockService locks;
    private static PgScanService scans;
    @TempDir Path directory;

    @BeforeAll
    static void start(Vertx vertx, VertxTestContext ctx) {
        postgres.start(vertx).onSuccess(ignored -> ctx.verify(() -> {
            pool = Pool.pool(vertx, postgres.connectOptions(), new PoolOptions().setMaxSize(2));
            cache = new PgCacheService(new PgCacheRepository(pool, SCHEMA), new CacheMetrics());
            counters = new PgCounterService(new PgCounterRepository(pool, SCHEMA), new CacheMetrics());
            locks = new PgLockService(new PgLockRepository(pool, SCHEMA), new CacheMetrics());
            scans = new PgScanService(new PgScanRepository(pool, SCHEMA), new CacheMetrics());
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

    @Test
    void managedExecutionRunsRealCacheOperationsAndPublishesFinalCheckpoint(Vertx vertx, VertxTestContext ctx) {
        var worker = vertx.createSharedWorkerExecutor("managed-postgres", 1);
        var parameters = new BenchmarkParameters(BenchmarkParameters.LoadModel.RATE_CONTROLLED,
                2, 2, 100, 2, Duration.ofSeconds(2));
        var timeline = new BenchmarkTimeline(List.of(
                new BenchmarkTimeline.Phase("warmup", BenchmarkTimeline.PhaseKind.WARMUP, Duration.ofMillis(40)),
                new BenchmarkTimeline.Phase("load", BenchmarkTimeline.PhaseKind.LOAD, Duration.ofMillis(80)),
                new BenchmarkTimeline.Phase("drain", BenchmarkTimeline.PhaseKind.DRAIN, Duration.ofSeconds(2))),
                Duration.ofMillis(20));
        var plan = new BenchmarkExperiment.Run(BenchmarkExperiment.SCHEMA_VERSION, "managed-postgres",
                0, 0, 0, 23, parameters, timeline);
        Instant started = Instant.now();
        var initial = new BenchmarkRunEvidence(UUID.randomUUID(), plan, started, started,
                BenchmarkRunEvidence.Status.RUNNING, BenchmarkRunEvidence.Validity.UNASSESSED, "",
                Map.of("purpose", "Managed execution/PostgreSQL contract verification, not capacity evidence"),
                List.of(), List.of());
        var warmup = new BenchmarkWorkloadMix(List.of(
                new BenchmarkWorkloadMix.Weight(BenchmarkProductWorkloads.CACHE_SET_GET, 1)));
        var load = new BenchmarkWorkloadMix(List.of(
                new BenchmarkWorkloadMix.Weight(BenchmarkProductWorkloads.CACHE_GET, 1)));
        var phasePlan = new BenchmarkPhaseWorkloadPlan(timeline, parameters, List.of(
                new BenchmarkPhaseWorkloadPlan.Profile("warmup", 1, 25, warmup),
                new BenchmarkPhaseWorkloadPlan.Profile("load", 2, 100, load)));
        var options = new BenchmarkManagedExecution.Options("managed-postgres", "operation", 4,
                List.of(1_000_000L, 5_000_000L, 50_000_000L, 1_000_000_000L),
                Duration.ofMillis(1), Duration.ofMillis(2),
                new BenchmarkCheckpointWriter.Limits(8, 4_194_304, 1), Duration.ofMillis(5),
                phasePlan, BenchmarkRetryPolicy.none());
        String namespace = "managed-" + UUID.randomUUID();
        var operations = new ArrayList<String>();

        BenchmarkManagedExecution.start(vertx, worker, directory, initial, options, attempt -> {
            operations.add(attempt.productOperation());
            var key = new CacheKey(namespace, "key-" + attempt.logicalRequestId());
            String expected = "value-" + attempt.logicalRequestId();
            if (BenchmarkProductWorkloads.CACHE_GET.equals(attempt.productOperation())) return cache.get(key).mapEmpty();
            if (BenchmarkProductWorkloads.CACHE_SET_GET.equals(attempt.productOperation())) {
                return cache.set(new CacheSetRequest(key, CacheValue.ofString(expected), null, SetMode.UPSERT, null, false))
                        .compose(ignored -> cache.get(key)).map(value -> {
                            assertEquals(expected, value.orElseThrow().value().asString());
                            return (Void) null;
                        });
            }
            return Future.failedFuture("Unexpected product operation " + attempt.productOperation());
        }).compose(BenchmarkManagedExecution.Execution::completion).compose(result -> vertx.executeBlocking(() -> {
            assertEquals(BenchmarkRunEvidence.Status.COMPLETED, result.status());
            assertEquals(0, result.statistics().physicalInFlight());
            assertTrue(operations.contains(BenchmarkProductWorkloads.CACHE_SET_GET));
            assertTrue(operations.contains(BenchmarkProductWorkloads.CACHE_GET));
            var json = new JsonObject(Files.readString(result.path()));
            assertEquals("COMPLETED", json.getJsonObject("execution").getString("status"));
            assertTrue(json.getJsonArray("measurements").size() >= 3);
            var last = json.getJsonArray("measurements").getJsonObject(json.getJsonArray("measurements").size() - 1);
            assertTrue(last.getJsonObject("after").getLong("succeeded") > 0);
            assertEquals(0L, last.getJsonObject("after").getLong("outstanding"));
            assertEquals(BenchmarkCheckpointRecovery.Classification.FINALISED,
                    BenchmarkCheckpointRecovery.inspect(result.path()).classification());
            return null;
        })).eventually(worker::close).onComplete(ctx.succeeding(ignored -> ctx.completeNow()));
    }

    @Test
    void scenarioDrivenSetGetAdapterUsesBoundedKeysPayloadAndTtlAgainstPostgres(VertxTestContext ctx) {
        String namespace = "scenario-" + UUID.randomUUID();
        var scenario = BenchmarkScenarioParameters.uniform(4, 129, Duration.ofSeconds(30),
                BenchmarkScenarioParameters.TelemetryMode.OFF);
        var operation = new BenchmarkCacheSetGetOperation(cache, namespace, scenario, 73);
        var phase = new BenchmarkTimeline.Phase("load", BenchmarkTimeline.PhaseKind.LOAD,
                Duration.ofSeconds(1));
        var futures = new ArrayList<Future<Void>>();
        for (long id = 0; id < 12; id++) {
            var launch = new BenchmarkWorkloadScheduler.Launch(id, id, id);
            futures.add(operation.execute(new BenchmarkManagedExecution.Attempt(id, 0, launch, phase,
                    BenchmarkProductWorkloads.CACHE_SET_GET)));
        }
        Future.join(futures).compose(ignored -> pool.preparedQuery(
                        "SELECT count(*), min(octet_length(value_bytes)), max(octet_length(value_bytes)), "
                                + "bool_and(expires_at IS NOT NULL) FROM \"" + SCHEMA + "\".cache_entries "
                                + "WHERE namespace = $1")
                .execute(io.vertx.sqlclient.Tuple.of(namespace))).onSuccess(rows -> ctx.verify(() -> {
            var row = rows.iterator().next();
            assertTrue(row.getLong(0) <= 4);
            assertEquals(129, row.getInteger(1));
            assertEquals(129, row.getInteger(2));
            assertTrue(row.getBoolean(3));
            ctx.completeNow();
        })).onFailure(ctx::failNow);
    }

    @Test
    void preparedCacheAdapterVerifiesHitsMissesSetsAndDeletesAgainstPostgres(VertxTestContext ctx) {
        String namespace = "cache-family-" + UUID.randomUUID();
        var hits = BenchmarkScenarioParameters.uniform(4, 65, 1, Duration.ZERO,
                BenchmarkScenarioParameters.TelemetryMode.OFF);
        var misses = BenchmarkScenarioParameters.uniform(4, 65, 0, Duration.ZERO,
                BenchmarkScenarioParameters.TelemetryMode.OFF);
        var hitAdapter = new BenchmarkCacheOperation(cache, namespace, hits, 91);
        var missAdapter = new BenchmarkCacheOperation(cache, namespace, misses, 91);
        var phase = new BenchmarkTimeline.Phase("load", BenchmarkTimeline.PhaseKind.LOAD, Duration.ofSeconds(1));
        java.util.function.BiFunction<Long, String, BenchmarkManagedExecution.Attempt> attempt = (id, operation) -> {
            var launch = new BenchmarkWorkloadScheduler.Launch(id, id, id);
            return new BenchmarkManagedExecution.Attempt(id, 0, launch, phase, operation);
        };

        hitAdapter.prepareDataset()
                .compose(ignored -> hitAdapter.execute(attempt.apply(0L, BenchmarkProductWorkloads.CACHE_GET)))
                .compose(ignored -> missAdapter.execute(attempt.apply(1L, BenchmarkProductWorkloads.CACHE_GET)))
                .compose(ignored -> hitAdapter.execute(attempt.apply(2L, BenchmarkProductWorkloads.CACHE_SET)))
                .compose(ignored -> hitAdapter.execute(attempt.apply(2L, BenchmarkProductWorkloads.CACHE_DELETE)))
                .compose(ignored -> cache.get(new CacheKey(namespace, "key-" + hits.keyIndex(91, 2))))
                .onSuccess(found -> ctx.verify(() -> {
                    assertTrue(found.isEmpty());
                    assertTrue(hitAdapter.execute(attempt.apply(3L, BenchmarkProductWorkloads.COUNTER_INCREMENT)).failed());
                    ctx.completeNow();
                })).onFailure(ctx::failNow);
    }

    @Test
    void counterAdapterPreservesAtomicTotalUnderHotKeyContention(VertxTestContext ctx) {
        String namespace = "counter-family-" + UUID.randomUUID();
        var parameters = new BenchmarkCounterParameters(8, 2, 0.95, 3, 10);
        var operation = new BenchmarkCounterOperation(counters, namespace, parameters, 101);
        var phase = new BenchmarkTimeline.Phase("load", BenchmarkTimeline.PhaseKind.LOAD, Duration.ofSeconds(1));
        var increments = new ArrayList<Future<Void>>();
        operation.prepareCounters().compose(ignored -> {
            for (long id = 0; id < 40; id++) {
                var launch = new BenchmarkWorkloadScheduler.Launch(id, id, id);
                increments.add(operation.execute(new BenchmarkManagedExecution.Attempt(id, 0, launch, phase,
                        BenchmarkProductWorkloads.COUNTER_INCREMENT)));
            }
            return Future.join(increments).mapEmpty();
        }).compose(ignored -> operation.verifyTotal(40)).onSuccess(ignored -> ctx.completeNow())
                .onFailure(ctx::failNow);
    }

    @Test
    void lockAdapterAccountsContentionFencingAndReleasesEveryLease(VertxTestContext ctx) {
        String namespace = "lock-family-" + UUID.randomUUID();
        var parameters = new BenchmarkLockParameters(2, 1, 1, Duration.ofSeconds(2), true);
        var operation = new BenchmarkLockOperation(locks, namespace, parameters, 29);
        var phase = new BenchmarkTimeline.Phase("load", BenchmarkTimeline.PhaseKind.LOAD, Duration.ofSeconds(1));
        var attempts = new ArrayList<Future<Void>>();
        for (long id = 0; id < 40; id++) {
            var launch = new BenchmarkWorkloadScheduler.Launch(id, id, id);
            attempts.add(operation.execute(new BenchmarkManagedExecution.Attempt(id, 0, launch, phase,
                    BenchmarkProductWorkloads.LOCK_ACQUIRE_RELEASE)));
        }
        Future.join(attempts).compose(ignored -> operation.verifyNoLocks()).onSuccess(ignored -> ctx.verify(() -> {
            var statistics = operation.statistics();
            assertEquals(40, statistics.acquired() + statistics.contended());
            assertTrue(statistics.acquired() > 0);
            assertTrue(statistics.contended() > 0);
            assertEquals(statistics.acquired(), statistics.released());
            assertEquals(statistics.acquired(), statistics.fencingTokens());
            ctx.completeNow();
        })).onFailure(ctx::failNow);
    }

    @Test
    void scanAdapterTraversesEveryCursorPageExactlyOnceAndVerifiesValues(VertxTestContext ctx) {
        String namespace = "scan-family-" + UUID.randomUUID();
        var scenario = BenchmarkScenarioParameters.uniform(23, 67, Duration.ZERO,
                BenchmarkScenarioParameters.TelemetryMode.OFF);
        var operation = new BenchmarkScanOperation(cache, scans, namespace, scenario,
                new BenchmarkScanParameters(5, true, false, "key-"), 211);
        var phase = new BenchmarkTimeline.Phase("load", BenchmarkTimeline.PhaseKind.LOAD, Duration.ofSeconds(1));
        var launch = new BenchmarkWorkloadScheduler.Launch(0, 0, 0);

        operation.prepareDataset().compose(ignored -> operation.execute(
                new BenchmarkManagedExecution.Attempt(0, 0, launch, phase, BenchmarkProductWorkloads.SCAN_PAGE)))
                .onSuccess(ignored -> ctx.verify(() -> {
                    assertEquals(23, operation.statistics().entries());
                    assertEquals(5, operation.statistics().pages());
                    assertEquals(23, operation.statistics().uniqueKeys());
                    ctx.completeNow();
                })).onFailure(ctx::failNow);
    }

    @Test
    void externallySuppliedPostgresTargetIsVerifiedWithoutTakingServerOwnership(VertxTestContext ctx) {
        var options = postgres.connectOptions();
        var target = BenchmarkDeploymentTarget.external("supplied-testcontainer", options.getHost(),
                options.getPort(), options.getDatabase(), "benchmark_external", false);

        BenchmarkPostgresTargetVerifier.verify(pool, target)
                .compose(identity -> {
                    assertEquals(target.id(), identity.targetId());
                    assertTrue(identity.serverVersion().contains("18."));
                    assertFalse(identity.tls());
                    // Verification must not close, restart, or otherwise take ownership of the supplied server.
                    return pool.query("SELECT 1").execute();
                }).onSuccess(rows -> ctx.verify(() -> {
                    assertEquals(1, rows.iterator().next().getInteger(0));
                    ctx.completeNow();
                })).onFailure(ctx::failNow);
    }

    @Test
    void localCampaignAdapterRunsAndCleansOnlyItsOwnedNamespace(Vertx vertx, VertxTestContext ctx) {
        var target = BenchmarkDeploymentTarget.localTestcontainers("postgres:18.3-alpine", "benchmark_local");
        var timeline = new BenchmarkTimeline(List.of(
                new BenchmarkTimeline.Phase("baseline", BenchmarkTimeline.PhaseKind.BASELINE, Duration.ofMillis(40)),
                new BenchmarkTimeline.Phase("load", BenchmarkTimeline.PhaseKind.LOAD, Duration.ofMillis(60)),
                new BenchmarkTimeline.Phase("recovery", BenchmarkTimeline.PhaseKind.RECOVERY, Duration.ofMillis(40)),
                new BenchmarkTimeline.Phase("drain", BenchmarkTimeline.PhaseKind.DRAIN, Duration.ofMillis(200))),
                Duration.ofMillis(20));
        var matrix = new BenchmarkParameterMatrix(BenchmarkParameters.LoadModel.RATE_CONTROLLED,
                List.of(2), List.of(2), List.of(50.0), 2, Duration.ofSeconds(1));
        var experiment = new BenchmarkExperiment(1, "local-real-campaign", timeline, matrix, 1, 1, 17, 1);
        var plan = new BenchmarkCampaignPlan(1, experiment, target, BenchmarkCampaignPlan.Order.DECLARED,
                BenchmarkCampaignPlan.Reset.CLEAR_RUN_NAMESPACE, 17);
        var scenario = BenchmarkScenarioParameters.uniform(12, 32, .8, Duration.ZERO,
                BenchmarkScenarioParameters.TelemetryMode.OFF);
        var worker = vertx.createSharedWorkerExecutor("local-real-campaign", 1);
        var adapter = new BenchmarkLocalPostgresCampaignAdapter(pool, SCHEMA, target, scenario,
                new BenchmarkCheckpointWriter.Limits(16, 2_000_000, 1), Duration.ofMillis(20));
        var policy = new BenchmarkAnalysisPolicy(1, "BASELINE", 1, 1.5, 2, 1.2, Duration.ofMillis(20));

        BenchmarkCampaignRunner.run(vertx, worker, directory, plan, adapter, policy)
                .compose(result -> pool.preparedQuery("SELECT count(*) FROM \"" + SCHEMA
                                + "\".cache_entries WHERE namespace = $1")
                        .execute(io.vertx.sqlclient.Tuple.of(plan.resolve().getFirst().resourceName()))
                        .map(rows -> Map.entry(result, rows.iterator().next().getLong(0))))
                .onComplete(outcome -> worker.close().onComplete(closed -> {
                    if (outcome.failed()) { ctx.failNow(outcome.cause()); return; }
                    ctx.verify(() -> {
                        assertEquals(0L, outcome.result().getValue());
                        assertEquals(1, outcome.result().getKey().runs().size());
                        assertTrue(Files.isRegularFile(outcome.result().getKey().manifestPath()));
                        ctx.completeNow();
                    });
                }));
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
