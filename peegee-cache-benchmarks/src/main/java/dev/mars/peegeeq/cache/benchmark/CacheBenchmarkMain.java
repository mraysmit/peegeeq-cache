package dev.mars.peegeeq.cache.benchmark;

import dev.mars.peegeeq.cache.api.cache.CacheService;
import dev.mars.peegeeq.cache.api.counter.CounterService;
import dev.mars.peegeeq.cache.api.lock.LockService;
import dev.mars.peegeeq.cache.api.model.CacheKey;
import dev.mars.peegeeq.cache.api.model.CacheSetRequest;
import dev.mars.peegeeq.cache.api.model.CacheValue;
import dev.mars.peegeeq.cache.api.model.LockAcquireRequest;
import dev.mars.peegeeq.cache.api.model.LockKey;
import dev.mars.peegeeq.cache.api.model.LockReleaseRequest;
import dev.mars.peegeeq.cache.api.model.PublishRequest;
import dev.mars.peegeeq.cache.api.model.SetMode;
import dev.mars.peegeeq.cache.api.pubsub.PubSubService;
import dev.mars.peegeeq.cache.core.telemetry.CacheTelemetry;
import dev.mars.peegeeq.cache.observability.metrics.MicrometerCacheTelemetry;
import dev.mars.peegeeq.cache.pg.config.PgCacheStoreConfig;
import dev.mars.peegeeq.cache.runtime.PeeGeeCacheManager;
import dev.mars.peegeeq.cache.runtime.bootstrap.PeeGeeCacheBootstrapOptions;
import dev.mars.peegeeq.cache.runtime.bootstrap.PeeGeeCaches;
import dev.mars.peegeeq.cache.runtime.config.PeeGeeCacheConfig;
import dev.mars.peegeeq.cache.test.LatencyHistogram;
import dev.mars.peegeeq.cache.test.PgTestSupport;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.vertx.core.AsyncResult;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.pgclient.PgConnection;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.PoolOptions;
import io.vertx.sqlclient.Tuple;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * Opt-in PostgreSQL integration benchmark. Reports sustained throughput plus
 * p50/p95/p99 latency and exercises connection loss and physical expiry lag.
 */
public final class CacheBenchmarkMain {

    private static final String SCHEMA = "peegeeq_benchmark";
    private static final String APPLICATION_NAME = "peegeeq-cache-benchmark";

    private CacheBenchmarkMain() {
    }

    public static void main(String[] args) {
        run(BenchmarkConfig.fromSystemProperties())
                .onSuccess(ignored -> System.exit(0))
                .onFailure(failure -> {
                    failure.printStackTrace(System.err);
                    System.exit(1);
                });
    }

    static Future<BenchmarkRunResult> run(BenchmarkConfig config) {
        System.out.printf("benchmark-config concurrency=%d pool-size=%d warmup-seconds=%d duration-seconds=%d%n",
                config.concurrency(), config.poolSize(), config.warmup().toSeconds(),
                config.duration().toSeconds());
        BenchmarkResources resources = new BenchmarkResources(config);
        return resources.initialize()
                .compose(ignored -> runScenarios(resources, config))
                .transform(runResult -> resources.close()
                        .transform(closeResult -> completeAfterCleanup(runResult, closeResult)));
    }

    private static Future<BenchmarkRunResult> runScenarios(
            BenchmarkResources resources, BenchmarkConfig config) {
        CacheService noopCache = resources.noopManager.cache().cache();
        CacheService observedCache = resources.observedManager.cache().cache();
        CounterService counters = resources.observedManager.cache().counters();
        ScenarioState state = new ScenarioState();

        return runTelemetryComparison(config,
                mixedOperation(noopCache, new AtomicLong(), "noop"),
                mixedOperation(observedCache, new AtomicLong(), "observed"))
                .compose(telemetryComparison -> {
                    state.noopMixed = telemetryComparison.noop();
                    state.observedMixed = telemetryComparison.enabled();
                    print("mixed-set-get-noop-telemetry", state.noopMixed);
                    print("mixed-set-get-micrometer", state.observedMixed);
                    state.scenarios.add(BenchmarkScenarioResult.from(
                            "mixed-set-get-noop-telemetry", state.noopMixed));
                    state.scenarios.add(BenchmarkScenarioResult.from(
                            "mixed-set-get-micrometer", state.observedMixed));
                    printTelemetryOverhead(state.noopMixed, state.observedMixed);
                    enforce("mixed-set-get-noop-telemetry", state.noopMixed, config);
                    enforce("mixed-set-get-micrometer", state.observedMixed, config);
                    enforceTelemetryOverhead(state.noopMixed, state.observedMixed, config);
                    return runSustained("counter-contention", config,
                            () -> counters.increment(new CacheKey(
                                    "benchmark", "contended-counter")).mapEmpty());
                })
                .compose(contention -> {
                    print("counter-contention", contention);
                    state.scenarios.add(BenchmarkScenarioResult.from("counter-contention", contention));
                    enforce("counter-contention", contention, config);
                    return runSustained("lock-contention", config,
                            lockOperation(resources.observedManager.cache().locks(), new AtomicLong()));
                })
                .compose(lockContention -> {
                    print("lock-contention", lockContention);
                    state.scenarios.add(BenchmarkScenarioResult.from("lock-contention", lockContention));
                    enforce("lock-contention", lockContention, config);
                    return runPubSubLatency(resources.observedManager.cache().pubSub(), config);
                })
                .compose(notificationLatency -> {
                    print("pubsub-publish-to-receive", notificationLatency);
                    state.scenarios.add(BenchmarkScenarioResult.from(
                            "pubsub-publish-to-receive", notificationLatency));
                    enforce("pubsub-publish-to-receive", notificationLatency, config);
                    PeeGeeCacheBootstrapOptions expiryOptions = new PeeGeeCacheBootstrapOptions(
                            expiryMeasurementRuntime(), resources.store, null, CacheTelemetry.noop());
                    return PeeGeeCaches.create(resources.vertx, resources.pool, expiryOptions);
                })
                .compose(expiryManager -> {
                    resources.expiryManager = expiryManager;
                    return expiryManager.startReactive();
                })
                .compose(ignored -> measureExpiryLag(
                        resources.vertx, resources.pool, config.maximumExpiryLag()))
                .compose(expiryLag -> {
                    state.expiryLag = expiryLag;
                    System.out.printf("expiry-lag-ms=%d%n", expiryLag.toMillis());
                    return resources.expiryManager.stopReactive();
                })
                .compose(ignored -> measurePoolFailover(resources.vertx, resources.postgres,
                        resources.pool, observedCache, config.maximumFailoverRecovery()))
                .map(failoverRecovery -> {
                    System.out.printf("pool-failover-recovery-ms=%d%n", failoverRecovery.toMillis());
                    return new BenchmarkRunResult(config, state.scenarios,
                            new BenchmarkTelemetryResult(
                                    throughputOverheadPercent(state.noopMixed, state.observedMixed),
                                    p99OverheadPercent(state.noopMixed, state.observedMixed)),
                            state.expiryLag, failoverRecovery);
                });
    }

    static PeeGeeCacheConfig sustainedWorkloadRuntime() {
        return new PeeGeeCacheConfig(null, null, 0, false);
    }

    static PoolOptions foregroundPoolOptions(BenchmarkConfig config) {
        return new PoolOptions().setMaxSize(config.poolSize());
    }

    static PeeGeeCacheConfig expiryMeasurementRuntime() {
        return new PeeGeeCacheConfig(null, Duration.ofMillis(50), 250, true);
    }

    private static Supplier<Future<Void>> mixedOperation(
            CacheService cache, AtomicLong sequence, String telemetryMode) {
        return () -> {
            long id = sequence.incrementAndGet();
            CacheKey key = new CacheKey("benchmark", telemetryMode + "-mixed-" + (id % 1_000));
            CacheSetRequest request = new CacheSetRequest(key, CacheValue.ofString("value-" + id),
                    Duration.ofMinutes(5), SetMode.UPSERT, null, false);
            return cache.set(request).compose(ignored -> cache.get(key)).mapEmpty();
        };
    }

    private static Supplier<Future<Void>> lockOperation(LockService locks, AtomicLong sequence) {
        LockKey key = new LockKey("benchmark", "contended-lock");
        return () -> {
            String owner = "owner-" + sequence.incrementAndGet();
            LockAcquireRequest acquire = new LockAcquireRequest(
                    key, owner, Duration.ofSeconds(1), false, true);
            return locks.acquire(acquire).compose(result -> result.acquired()
                    ? locks.release(new LockReleaseRequest(key, owner)).mapEmpty()
                    : Future.succeededFuture());
        };
    }

    private static Future<LatencyHistogram.Snapshot> runPubSubLatency(
            dev.mars.peegeeq.cache.api.pubsub.PubSubService pubSub, BenchmarkConfig config) {
        ConcurrentHashMap<String, Promise<Void>> pending = new ConcurrentHashMap<>();
        AtomicLong sequence = new AtomicLong();
        return pubSub.subscribe("benchmark-latency", message -> {
                    Promise<Void> completion = pending.remove(message.payload());
                    if (completion != null) {
                        completion.tryComplete();
                    }
                })
                .compose(subscription -> runSustained("pubsub-publish-to-receive", config, () -> {
                            String payload = Long.toString(sequence.incrementAndGet());
                            Promise<Void> completion = Promise.promise();
                            pending.put(payload, completion);
                            return pubSub.publish(new PublishRequest(
                                            "benchmark-latency", payload, "text/plain"))
                                    .compose(ignored -> completion.future())
                                    .onFailure(ignored -> pending.remove(payload));
                        })
                        .transform(result -> {
                            pending.clear();
                            return subscription.unsubscribe()
                                    .transform(unsubscribeResult -> completeAfterCleanup(
                                            result, unsubscribeResult));
                        }));
    }

    static Future<LatencyHistogram.Snapshot> runSustained(
            String scenario, BenchmarkConfig config, Supplier<Future<Void>> operation) {
        if (config.warmup().isZero()) {
            return runSustainedWindow(scenario, config, operation, config.duration());
        }
        return runSustainedWindow(scenario + "-warmup", config, operation, config.warmup())
                .compose(ignored -> runSustainedWindow(scenario, config, operation, config.duration()));
    }

    private static Future<LatencyHistogram.Snapshot> runSustainedWindow(
            String scenario, BenchmarkConfig config, Supplier<Future<Void>> operation,
            Duration window) {
        LatencyHistogram histogram = new LatencyHistogram(config.concurrency() * 1_000);
        long startedAt = System.nanoTime();
        long deadline = startedAt + window.toNanos();
        List<Future<Void>> workers = new ArrayList<>();
        for (int worker = 0; worker < config.concurrency(); worker++) {
            workers.add(runWorker(scenario, operation, histogram, deadline,
                    config.maximumP99().multipliedBy(5)));
        }
        return Future.all(workers)
                .map(ignored -> histogram.snapshot(Duration.ofNanos(System.nanoTime() - startedAt)));
    }

    private static Future<Void> runWorker(
            String scenario, Supplier<Future<Void>> operation, LatencyHistogram histogram,
            long deadline, Duration operationTimeout) {
        if (System.nanoTime() >= deadline) {
            return Future.succeededFuture();
        }
        long operationStartedAt = System.nanoTime();
        return invoke(operation)
                .timeout(operationTimeout.toMillis(), TimeUnit.MILLISECONDS)
                .transform(result -> {
                    if (result.failed()) {
                        return Future.failedFuture(scenarioFailure(scenario, result.cause()));
                    }
                    histogram.record(Duration.ofNanos(System.nanoTime() - operationStartedAt));
                    return runWorker(scenario, operation, histogram, deadline, operationTimeout);
                });
    }

    private static TelemetryComparison snapshots(
            LatencyHistogram noopHistogram, LatencyHistogram enabledHistogram, long startedAt) {
        Duration elapsed = Duration.ofNanos(System.nanoTime() - startedAt);
        return new TelemetryComparison(
                noopHistogram.snapshot(elapsed), enabledHistogram.snapshot(elapsed));
    }

    private static Future<TelemetryComparison> runTelemetryComparison(
            BenchmarkConfig config, Supplier<Future<Void>> noopOperation,
            Supplier<Future<Void>> enabledOperation) {
        if (config.warmup().isZero()) {
            return runTelemetryComparisonWindow(config, noopOperation, enabledOperation, config.duration());
        }
        return runTelemetryComparisonWindow(config, noopOperation, enabledOperation, config.warmup())
                .compose(ignored -> runTelemetryComparisonWindow(
                        config, noopOperation, enabledOperation, config.duration()));
    }

    private static Future<TelemetryComparison> runTelemetryComparisonWindow(
            BenchmarkConfig config, Supplier<Future<Void>> noopOperation,
            Supplier<Future<Void>> enabledOperation, Duration window) {
        LatencyHistogram noopHistogram = new LatencyHistogram(config.concurrency() * 500);
        LatencyHistogram enabledHistogram = new LatencyHistogram(config.concurrency() * 500);
        long startedAt = System.nanoTime();
        long deadline = startedAt + window.toNanos();
        List<Future<Void>> workers = new ArrayList<>();
        for (int worker = 0; worker < config.concurrency(); worker++) {
            workers.add(runTelemetryWorker(worker, noopOperation, enabledOperation,
                    noopHistogram, enabledHistogram, deadline,
                    config.maximumP99().multipliedBy(5)));
        }
        return Future.all(workers)
                .map(ignored -> snapshots(noopHistogram, enabledHistogram, startedAt));
    }

    private static Future<Void> runTelemetryWorker(
            long iteration, Supplier<Future<Void>> noopOperation,
            Supplier<Future<Void>> enabledOperation, LatencyHistogram noopHistogram,
            LatencyHistogram enabledHistogram, long deadline, Duration operationTimeout) {
        if (System.nanoTime() >= deadline) {
            return Future.succeededFuture();
        }
        boolean noop = (iteration & 1L) == 0;
        long operationStartedAt = System.nanoTime();
        return invoke(noop ? noopOperation : enabledOperation)
                .timeout(operationTimeout.toMillis(), TimeUnit.MILLISECONDS)
                .transform(result -> {
                    if (result.failed()) {
                        return Future.failedFuture(scenarioFailure(
                                "telemetry-comparison", result.cause()));
                    }
                    (noop ? noopHistogram : enabledHistogram).record(
                            Duration.ofNanos(System.nanoTime() - operationStartedAt));
                    return runTelemetryWorker(iteration + 1, noopOperation, enabledOperation,
                            noopHistogram, enabledHistogram, deadline, operationTimeout);
                });
    }

    private static Future<Void> invoke(Supplier<Future<Void>> operation) {
        try {
            return operation.get();
        } catch (Throwable failure) {
            return Future.failedFuture(failure);
        }
    }

    private static IllegalStateException scenarioFailure(String scenario, Throwable cause) {
        String outcome = cause instanceof TimeoutException ? "timed out" : "failed";
        return new IllegalStateException("Benchmark scenario '" + scenario + "' " + outcome, cause);
    }

    private static Future<Duration> measureExpiryLag(
            Vertx vertx, Pool pool, Duration maximumLag) {
        return pool.query("""
                        INSERT INTO peegeeq_benchmark.cache_entries
                            (namespace, cache_key, value_type, value_bytes, expires_at)
                        SELECT 'benchmark-expiry', 'key-' || value, 'STRING', convert_to('v', 'UTF8'),
                               NOW() + INTERVAL '100 milliseconds'
                        FROM generate_series(1, 50) AS series(value)
                        """)
                .execute()
                .timeout(5, TimeUnit.SECONDS)
                .compose(ignored -> {
                    long expectedExpiry = System.nanoTime() + Duration.ofMillis(100).toNanos();
                    return pollExpiryLag(vertx, pool, expectedExpiry,
                            expectedExpiry + maximumLag.toNanos(), maximumLag);
                });
    }

    private static Future<Duration> pollExpiryLag(
            Vertx vertx, Pool pool, long expectedExpiry, long deadline, Duration maximumLag) {
        if (System.nanoTime() >= deadline) {
            return Future.failedFuture("Expiry lag exceeded " + maximumLag);
        }
        return pool.query("""
                        SELECT COUNT(*) AS remaining FROM peegeeq_benchmark.cache_entries
                        WHERE namespace = 'benchmark-expiry'
                        """)
                .execute()
                .timeout(5, TimeUnit.SECONDS)
                .compose(rows -> {
                    long remaining = rows.iterator().next().getLong("remaining");
                    if (remaining == 0) {
                        return Future.succeededFuture(Duration.ofNanos(
                                Math.max(0, System.nanoTime() - expectedExpiry)));
                    }
                    return delay(vertx, 20)
                            .compose(ignored -> pollExpiryLag(
                                    vertx, pool, expectedExpiry, deadline, maximumLag));
                });
    }

    private static Future<Duration> measurePoolFailover(
            Vertx vertx, PgTestSupport postgres, Pool pool,
            CacheService cache, Duration maximumRecovery) {
        List<Future<?>> warmups = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            warmups.add(pool.query("SELECT pg_sleep(0.05)").execute());
        }
        return Future.all(warmups)
                .timeout(5, TimeUnit.SECONDS)
                .compose(ignored -> PgConnection.connect(vertx,
                        postgres.connectOptions().addProperty(
                                "application_name", "peegeeq-cache-benchmark-admin")))
                .compose(admin -> admin.preparedQuery("""
                                SELECT pg_terminate_backend(pid)
                                FROM pg_stat_activity
                                WHERE application_name = $1 AND pid <> pg_backend_pid()
                                """)
                        .execute(Tuple.of(APPLICATION_NAME))
                        .timeout(5, TimeUnit.SECONDS)
                        .transform(terminateResult -> admin.close()
                                .timeout(5, TimeUnit.SECONDS)
                                .transform(closeResult -> completeAfterCleanup(
                                        terminateResult, closeResult))))
                .compose(ignored -> {
                    long startedAt = System.nanoTime();
                    CacheSetRequest probe = new CacheSetRequest(
                            new CacheKey("benchmark", "failover-probe"),
                            CacheValue.ofString("recovered"), Duration.ofMinutes(1),
                            SetMode.UPSERT, null, false);
                    return awaitPoolRecovery(vertx, cache, probe, startedAt,
                            startedAt + maximumRecovery.toNanos(), maximumRecovery, null);
                });
    }

    private static Future<Duration> awaitPoolRecovery(
            Vertx vertx, CacheService cache, CacheSetRequest probe,
            long startedAt, long deadline, Duration maximumRecovery, Throwable lastFailure) {
        if (System.nanoTime() >= deadline) {
            return Future.failedFuture(new IllegalStateException(
                    "Pool did not recover within " + maximumRecovery, lastFailure));
        }
        return cache.set(probe)
                .timeout(2, TimeUnit.SECONDS)
                .transform(attempt -> {
                    if (attempt.succeeded()) {
                        return Future.succeededFuture(
                                Duration.ofNanos(System.nanoTime() - startedAt));
                    }
                    return delay(vertx, 25)
                            .compose(ignored -> awaitPoolRecovery(vertx, cache, probe,
                                    startedAt, deadline, maximumRecovery, attempt.cause()));
                });
    }

    private static Future<Void> delay(Vertx vertx, long delayMillis) {
        return Future.future(promise -> vertx.setTimer(delayMillis, ignored -> promise.complete()));
    }

    private static <T> Future<T> completeAfterCleanup(
            AsyncResult<T> primaryResult, AsyncResult<?> cleanupResult) {
        if (primaryResult.succeeded() && cleanupResult.succeeded()) {
            return Future.succeededFuture(primaryResult.result());
        }
        if (primaryResult.failed()) {
            Throwable primaryFailure = primaryResult.cause();
            if (cleanupResult.failed() && cleanupResult.cause() != primaryFailure) {
                primaryFailure.addSuppressed(cleanupResult.cause());
            }
            return Future.failedFuture(primaryFailure);
        }
        return Future.failedFuture(cleanupResult.cause());
    }

    private static Future<Void> continueCleanup(
            Future<Void> prior, Supplier<Future<Void>> next) {
        return prior.transform(priorResult -> {
            Future<Void> nextFuture;
            try {
                nextFuture = next.get();
            } catch (Throwable failure) {
                nextFuture = Future.failedFuture(failure);
            }
            return nextFuture.transform(nextResult -> completeAfterCleanup(priorResult, nextResult));
        });
    }

    private static void enforce(String name, LatencyHistogram.Snapshot result, BenchmarkConfig config) {
        if (result.throughputPerSecond() < config.minimumThroughput()) {
            throw new IllegalStateException(name + " throughput below threshold: " + result.throughputPerSecond());
        }
        if (result.p99().compareTo(config.maximumP99()) > 0) {
            throw new IllegalStateException(name + " p99 exceeded threshold: " + result.p99());
        }
    }

    private static void enforceTelemetryOverhead(
            LatencyHistogram.Snapshot noop, LatencyHistogram.Snapshot enabled, BenchmarkConfig config) {
        double throughputOverhead = throughputOverheadPercent(noop, enabled);
        if (throughputOverhead > config.maximumTelemetryOverheadPercent()) {
            throw new IllegalStateException("Micrometer throughput overhead exceeded threshold: "
                    + throughputOverhead + "%");
        }
    }

    private static void printTelemetryOverhead(
            LatencyHistogram.Snapshot noop, LatencyHistogram.Snapshot enabled) {
        System.out.printf("telemetry=micrometer throughput-overhead-percent=%.2f p99-overhead-percent=%.2f%n",
                throughputOverheadPercent(noop, enabled), p99OverheadPercent(noop, enabled));
    }

    private static double p99OverheadPercent(
            LatencyHistogram.Snapshot noop, LatencyHistogram.Snapshot enabled) {
        return ((double) enabled.p99().toNanos()
                / Math.max(1L, noop.p99().toNanos()) - 1.0) * 100.0;
    }

    private static double throughputOverheadPercent(
            LatencyHistogram.Snapshot noop, LatencyHistogram.Snapshot enabled) {
        return Math.max(0.0, (1.0 - enabled.throughputPerSecond()
                / noop.throughputPerSecond()) * 100.0);
    }

    private static void print(String name, LatencyHistogram.Snapshot result) {
        System.out.printf("scenario=%s operations=%d throughput-per-second=%.2f p50-ms=%.3f p95-ms=%.3f p99-ms=%.3f%n",
                name, result.operations(), result.throughputPerSecond(), millis(result.p50()),
                millis(result.p95()), millis(result.p99()));
    }

    private static double millis(Duration value) {
        return value.toNanos() / 1_000_000.0;
    }

    private static final class BenchmarkResources {
        private final Vertx vertx = Vertx.vertx();
        private final PgTestSupport postgres = new PgTestSupport("cache-benchmark", SCHEMA);
        private final BenchmarkConfig config;
        private final PgCacheStoreConfig store = new PgCacheStoreConfig(SCHEMA, SCHEMA);
        private Pool pool;
        private PeeGeeCacheManager noopManager;
        private PeeGeeCacheManager observedManager;
        private PeeGeeCacheManager expiryManager;
        private SimpleMeterRegistry registry;

        private BenchmarkResources(BenchmarkConfig config) {
            this.config = config;
        }

        private Future<Void> initialize() {
            return postgres.start(vertx)
                    .compose(ignored -> {
                        pool = Pool.pool(vertx,
                                postgres.connectOptions().addProperty(
                                        "application_name", APPLICATION_NAME),
                                foregroundPoolOptions(config));
                        registry = new SimpleMeterRegistry();
                        PeeGeeCacheConfig runtime = sustainedWorkloadRuntime();
                        PeeGeeCacheBootstrapOptions noopOptions = new PeeGeeCacheBootstrapOptions(
                                runtime, store, postgres.connectOptions(), CacheTelemetry.noop());
                        return PeeGeeCaches.create(vertx, pool, noopOptions)
                                .timeout(10, TimeUnit.SECONDS);
                    })
                    .compose(createdManager -> {
                        noopManager = createdManager;
                        PeeGeeCacheBootstrapOptions observedOptions = new PeeGeeCacheBootstrapOptions(
                                sustainedWorkloadRuntime(), store, postgres.connectOptions(),
                                new MicrometerCacheTelemetry(registry));
                        return PeeGeeCaches.create(vertx, pool, observedOptions)
                                .timeout(10, TimeUnit.SECONDS);
                    })
                    .compose(createdManager -> {
                        observedManager = createdManager;
                        return noopManager.startReactive().timeout(10, TimeUnit.SECONDS);
                    })
                    .compose(ignored -> observedManager.startReactive()
                            .timeout(10, TimeUnit.SECONDS));
        }

        private Future<Void> close() {
            Future<Void> cleanup = Future.succeededFuture();
            if (expiryManager != null && expiryManager.isStarted()) {
                cleanup = continueCleanup(cleanup, expiryManager::stopReactive);
            }
            if (observedManager != null && observedManager.isStarted()) {
                cleanup = continueCleanup(cleanup, observedManager::stopReactive);
            }
            if (noopManager != null && noopManager.isStarted()) {
                cleanup = continueCleanup(cleanup, noopManager::stopReactive);
            }
            if (pool != null) {
                cleanup = continueCleanup(cleanup, pool::close);
            }
            cleanup = continueCleanup(cleanup, () -> postgres.stop(vertx));
            cleanup = continueCleanup(cleanup, vertx::close);
            if (registry != null) {
                cleanup = continueCleanup(cleanup, () -> {
                    registry.close();
                    return Future.succeededFuture();
                });
            }
            return cleanup.timeout(30, TimeUnit.SECONDS);
        }
    }

    private static final class ScenarioState {
        private final List<BenchmarkScenarioResult> scenarios = new ArrayList<>();
        private LatencyHistogram.Snapshot noopMixed;
        private LatencyHistogram.Snapshot observedMixed;
        private Duration expiryLag;
    }

    private record TelemetryComparison(
            LatencyHistogram.Snapshot noop, LatencyHistogram.Snapshot enabled) {
    }
}
