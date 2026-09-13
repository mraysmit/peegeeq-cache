package dev.mars.peegeeq.cache.benchmark;

import dev.mars.peegeeq.cache.test.PgTestSupport;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.PoolOptions;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

/** Executable finite local characterisation campaign with retained JSON, HTML and review artifacts. */
public final class BenchmarkLocalCampaignMain {
    private static final String SCHEMA = "benchmark_campaign";
    private BenchmarkLocalCampaignMain() { }

    public static void main(String[] args) throws InterruptedException {
        BenchmarkLocalCampaignConfig config;
        try { config = BenchmarkLocalCampaignConfig.fromProperties(System.getProperties()); }
        catch (Throwable invalid) { invalid.printStackTrace(System.err); System.exit(2); return; }
        Vertx vertx = Vertx.vertx();
        var exit = new AtomicInteger(1);
        var completed = new CountDownLatch(1);
        run(vertx, config).onComplete(result -> vertx.close().onComplete(closed -> {
            if (result.succeeded() && closed.succeeded()) {
                System.out.println("Characterisation review: " + result.result().toAbsolutePath());
                exit.set(0);
            } else {
                Throwable failure = result.failed() ? result.cause() : closed.cause();
                failure.printStackTrace(System.err);
            }
            completed.countDown();
        }));
        completed.await();
        System.exit(exit.get());
    }

    static Future<Path> run(Vertx vertx, BenchmarkLocalCampaignConfig config) {
        String image = System.getProperty("peegeeq.test.postgres.image", "postgres:18.3-alpine");
        var postgres = new PgTestSupport("benchmark-characterisation", SCHEMA);
        final Pool[] pool = new Pool[1];
        final io.vertx.core.WorkerExecutor[] worker = new io.vertx.core.WorkerExecutor[1];
        final Path repositoryRoot = Path.of(System.getProperty("campaign.repositoryRoot", "."))
                .toAbsolutePath().normalize();
        final Path output = resolveOutputDirectory(config, repositoryRoot);
        return postgres.start(vertx).compose(ignored -> {
            pool[0] = Pool.pool(vertx, postgres.connectOptions(), new PoolOptions().setMaxSize(config.poolSize()));
            worker[0] = vertx.createSharedWorkerExecutor("benchmark-characterisation-checkpoints", 1);
            var timeline = new BenchmarkTimeline(List.of(
                    new BenchmarkTimeline.Phase("baseline", BenchmarkTimeline.PhaseKind.BASELINE, config.phaseDuration()),
                    new BenchmarkTimeline.Phase("load", BenchmarkTimeline.PhaseKind.LOAD, config.phaseDuration()),
                    new BenchmarkTimeline.Phase("recovery", BenchmarkTimeline.PhaseKind.RECOVERY, config.phaseDuration()),
                    new BenchmarkTimeline.Phase("drain", BenchmarkTimeline.PhaseKind.DRAIN,
                            config.phaseDuration().multipliedBy(2))), config.sampleInterval());
            var matrix = new BenchmarkParameterMatrix(BenchmarkParameters.LoadModel.RATE_CONTROLLED,
                    List.of(config.concurrency()), List.of(config.poolSize()), config.offeredRates(),
                    config.concurrency(), Duration.ofSeconds(2));
            long runCount = Math.multiplyExact((long) config.repetitions(), config.offeredRates().size());
            var scenario = BenchmarkScenarioParameters.uniform(config.datasetCardinality(), config.payloadBytes(), .9,
                    Duration.ZERO, BenchmarkScenarioParameters.TelemetryMode.OFF);
            var specification = new BenchmarkSpecification(1, "local-cache-characterisation", timeline, matrix,
                    config.repetitions(), 1, 20260913L, runCount, scenario,
                    new BenchmarkRuntimePolicy(Runtime.getRuntime().maxMemory(), .98, Duration.ofMillis(250), false),
                    new BenchmarkPersistencePolicy(1, 10, 128L * 1024 * 1024,
                            4L * 1024 * 1024 * 1024, 256L * 1024 * 1024),
                    BenchmarkCapabilityInventory.localPostgres(), output);
            long estimatedIntervals = Math.multiplyExact(timeline.windowCount(), runCount);
            var resolved = specification.resolve(estimatedIntervals, 20_000, 100_000);
            try {
                Files.createDirectories(output);
                Files.writeString(output.resolve("resolved-experiment.json"), resolved.manifest().encodePrettily());
            } catch (Exception failure) { return Future.<Path>failedFuture(failure); }
            var target = BenchmarkDeploymentTarget.localTestcontainers(image, "benchmark_local");
            var campaign = new BenchmarkCampaignPlan(1, resolved.experiment(), target,
                    BenchmarkCampaignPlan.Order.SEEDED_RANDOM, BenchmarkCampaignPlan.Reset.CLEAR_RUN_NAMESPACE, 20260913L);
            var adapter = new BenchmarkLocalPostgresCampaignAdapter(pool[0], SCHEMA, target, scenario,
                    new BenchmarkCheckpointWriter.Limits(32, 4 * 1024 * 1024, 1), config.sampleInterval());
            var analysis = new BenchmarkAnalysisPolicy(1, "BASELINE", 5, 1.5, 2, 1.2,
                    config.sampleInterval());
            return BenchmarkCampaignRunner.run(vertx, worker[0], output, campaign, adapter, analysis)
                    .compose(result -> vertx.executeBlocking(() -> {
                        Path review = output.resolve("characterisation-review.json");
                        BenchmarkCharacterisationReview.publish(result.manifestPath(), review);
                        return review;
                    }));
        }).transform(result -> cleanup(vertx, postgres, pool[0], worker[0]).transform(cleaned -> {
            if (result.failed()) {
                if (cleaned.failed() && cleaned.cause() != result.cause()) result.cause().addSuppressed(cleaned.cause());
                return Future.failedFuture(result.cause());
            }
            return cleaned.failed() ? Future.failedFuture(cleaned.cause()) : Future.succeededFuture(result.result());
        }));
    }

    private static Future<Void> cleanup(Vertx vertx, PgTestSupport postgres, Pool pool,
                                        io.vertx.core.WorkerExecutor worker) {
        Future<Void> closed = worker == null ? Future.succeededFuture() : worker.close();
        if (pool != null) closed = closed.compose(ignored -> pool.close());
        return postgres.stopAfter(vertx, closed);
    }

    static Path resolveOutputDirectory(BenchmarkLocalCampaignConfig config, Path repositoryRoot) {
        Path configured = config.outputDirectory();
        return (configured.isAbsolute() ? configured : repositoryRoot.resolve(configured)).normalize();
    }
}
