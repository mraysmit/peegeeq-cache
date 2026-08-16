package dev.mars.peegeeq.cache.benchmark;

import dev.mars.peegeeq.cache.api.cache.CacheService;
import dev.mars.peegeeq.cache.api.counter.CounterService;
import dev.mars.peegeeq.cache.api.lock.LockService;
import dev.mars.peegeeq.cache.api.pubsub.PubSubService;
import dev.mars.peegeeq.cache.api.pubsub.Subscription;
import dev.mars.peegeeq.cache.api.model.CacheKey;
import dev.mars.peegeeq.cache.api.model.CacheSetRequest;
import dev.mars.peegeeq.cache.api.model.CacheValue;
import dev.mars.peegeeq.cache.api.model.LockAcquireRequest;
import dev.mars.peegeeq.cache.api.model.LockKey;
import dev.mars.peegeeq.cache.api.model.LockReleaseRequest;
import dev.mars.peegeeq.cache.api.model.PublishRequest;
import dev.mars.peegeeq.cache.api.model.SetMode;
import dev.mars.peegeeq.cache.core.telemetry.CacheTelemetry;
import dev.mars.peegeeq.cache.observability.metrics.MicrometerCacheTelemetry;
import dev.mars.peegeeq.cache.pg.config.PgCacheStoreConfig;
import dev.mars.peegeeq.cache.runtime.PeeGeeCacheManager;
import dev.mars.peegeeq.cache.runtime.bootstrap.PeeGeeCacheBootstrapOptions;
import dev.mars.peegeeq.cache.runtime.bootstrap.PeeGeeCaches;
import dev.mars.peegeeq.cache.runtime.config.PeeGeeCacheConfig;
import dev.mars.peegeeq.cache.test.LatencyHistogram;
import dev.mars.peegeeq.cache.test.PgTestSupport;
import dev.mars.peegeeq.cache.test.VertxAwait;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
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
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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
        int exitCode = 0;
        try {
            run(BenchmarkConfig.fromSystemProperties());
        } catch (Throwable failure) {
            failure.printStackTrace(System.err);
            exitCode = 1;
        }
        System.exit(exitCode);
    }

    static BenchmarkRunResult run(BenchmarkConfig config) throws Exception {
        System.out.printf("benchmark-config concurrency=%d pool-size=%d duration-seconds=%d%n",
                config.concurrency(), config.poolSize(), config.duration().toSeconds());
        Vertx vertx = Vertx.vertx();
        PgTestSupport postgres = new PgTestSupport("cache-benchmark", SCHEMA);
        Pool pool = null;
        PeeGeeCacheManager noopManager = null;
        PeeGeeCacheManager observedManager = null;
        PeeGeeCacheManager expiryManager = null;
        SimpleMeterRegistry registry = null;
        try {
            postgres.start(vertx);
            pool = Pool.pool(vertx,
                    postgres.connectOptions().addProperty("application_name", APPLICATION_NAME),
                    foregroundPoolOptions(config));
            registry = new SimpleMeterRegistry();
            PeeGeeCacheConfig runtime = sustainedWorkloadRuntime();
            PgCacheStoreConfig store = new PgCacheStoreConfig(SCHEMA, SCHEMA);
            PeeGeeCacheBootstrapOptions noopOptions = new PeeGeeCacheBootstrapOptions(
                    runtime, store, postgres.connectOptions(), CacheTelemetry.noop());
            PeeGeeCacheBootstrapOptions observedOptions = new PeeGeeCacheBootstrapOptions(
                    runtime, store, postgres.connectOptions(),
                    new MicrometerCacheTelemetry(registry));
            noopManager = VertxAwait.await(PeeGeeCaches.create(vertx, pool, noopOptions), Duration.ofSeconds(10));
            observedManager = VertxAwait.await(
                    PeeGeeCaches.create(vertx, pool, observedOptions), Duration.ofSeconds(10));
            VertxAwait.await(noopManager.startReactive(), Duration.ofSeconds(10));
            VertxAwait.await(observedManager.startReactive(), Duration.ofSeconds(10));

            CacheService noopCache = noopManager.cache().cache();
            CacheService observedCache = observedManager.cache().cache();
            CounterService counters = observedManager.cache().counters();
            TelemetryComparison telemetryComparison = runTelemetryComparison(config,
                    mixedOperation(noopCache, new AtomicLong(), "noop"),
                    mixedOperation(observedCache, new AtomicLong(), "observed"));
            LatencyHistogram.Snapshot noopMixed = telemetryComparison.noop();
            LatencyHistogram.Snapshot observedMixed = telemetryComparison.enabled();
            List<BenchmarkScenarioResult> scenarios = new ArrayList<>();
            print("mixed-set-get-noop-telemetry", noopMixed);
            print("mixed-set-get-micrometer", observedMixed);
            scenarios.add(BenchmarkScenarioResult.from("mixed-set-get-noop-telemetry", noopMixed));
            scenarios.add(BenchmarkScenarioResult.from("mixed-set-get-micrometer", observedMixed));
            printTelemetryOverhead(noopMixed, observedMixed);
            enforce("mixed-set-get-noop-telemetry", noopMixed, config);
            enforce("mixed-set-get-micrometer", observedMixed, config);
            enforceTelemetryOverhead(noopMixed, observedMixed, config);

            LatencyHistogram.Snapshot contention = runSustained("counter-contention", config,
                    () -> counters.increment(new CacheKey("benchmark", "contended-counter")).mapEmpty());
            print("counter-contention", contention);
            scenarios.add(BenchmarkScenarioResult.from("counter-contention", contention));
            enforce("counter-contention", contention, config);

            LatencyHistogram.Snapshot lockContention = runSustained("lock-contention", config,
                    lockOperation(observedManager.cache().locks(), new AtomicLong()));
            print("lock-contention", lockContention);
            scenarios.add(BenchmarkScenarioResult.from("lock-contention", lockContention));
            enforce("lock-contention", lockContention, config);

            LatencyHistogram.Snapshot notificationLatency = runPubSubLatency(
                    observedManager.cache().pubSub(), config);
            print("pubsub-publish-to-receive", notificationLatency);
            scenarios.add(BenchmarkScenarioResult.from("pubsub-publish-to-receive", notificationLatency));
            enforce("pubsub-publish-to-receive", notificationLatency, config);

            PeeGeeCacheBootstrapOptions expiryOptions = new PeeGeeCacheBootstrapOptions(
                    expiryMeasurementRuntime(), store, null, CacheTelemetry.noop());
            expiryManager = VertxAwait.await(
                    PeeGeeCaches.create(vertx, pool, expiryOptions), Duration.ofSeconds(10));
            VertxAwait.await(expiryManager.startReactive(), Duration.ofSeconds(10));
            Duration expiryLag = measureExpiryLag(pool, config.maximumExpiryLag());
            System.out.printf("expiry-lag-ms=%d%n", expiryLag.toMillis());
            VertxAwait.await(expiryManager.stopReactive(), Duration.ofSeconds(10));
            Duration failoverRecovery = measurePoolFailover(vertx, postgres, pool, observedCache,
                    config.maximumFailoverRecovery());
            System.out.printf("pool-failover-recovery-ms=%d%n", failoverRecovery.toMillis());
            return new BenchmarkRunResult(config, scenarios,
                    new BenchmarkTelemetryResult(
                            throughputOverheadPercent(noopMixed, observedMixed),
                            p99OverheadPercent(noopMixed, observedMixed)),
                    expiryLag, failoverRecovery);
        } finally {
            if (expiryManager != null && expiryManager.isStarted()) {
                VertxAwait.await(expiryManager.stopReactive(), Duration.ofSeconds(10));
            }
            if (observedManager != null && observedManager.isStarted()) {
                VertxAwait.await(observedManager.stopReactive(), Duration.ofSeconds(10));
            }
            if (noopManager != null && noopManager.isStarted()) {
                VertxAwait.await(noopManager.stopReactive(), Duration.ofSeconds(10));
            }
            if (pool != null) {
                VertxAwait.await(pool.close(), Duration.ofSeconds(10));
            }
            postgres.stop();
            VertxAwait.await(vertx.close(), Duration.ofSeconds(10));
            if (registry != null) {
                registry.close();
            }
        }
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

    private static LatencyHistogram.Snapshot runPubSubLatency(
            PubSubService pubSub, BenchmarkConfig config) throws Exception {
        ConcurrentHashMap<String, Promise<Void>> pending = new ConcurrentHashMap<>();
        AtomicLong sequence = new AtomicLong();
        Subscription subscription = VertxAwait.await(pubSub.subscribe("benchmark-latency", message -> {
            Promise<Void> completion = pending.remove(message.payload());
            if (completion != null) {
                completion.tryComplete();
            }
        }), Duration.ofSeconds(10));
        try {
            return runSustained("pubsub-publish-to-receive", config, () -> {
                String payload = Long.toString(sequence.incrementAndGet());
                Promise<Void> completion = Promise.promise();
                pending.put(payload, completion);
                return pubSub.publish(new PublishRequest("benchmark-latency", payload, "text/plain"))
                        .compose(ignored -> completion.future())
                        .onFailure(ignored -> pending.remove(payload));
            });
        } finally {
            pending.clear();
            VertxAwait.await(subscription.unsubscribe(), Duration.ofSeconds(10));
        }
    }

    static LatencyHistogram.Snapshot runSustained(
            String scenario, BenchmarkConfig config, Supplier<Future<Void>> operation) throws Exception {
        LatencyHistogram histogram = new LatencyHistogram(config.concurrency() * 1_000);
        long startedAt = System.nanoTime();
        long deadline = startedAt + config.duration().toNanos();
        List<Callable<Void>> workers = new ArrayList<>();
        for (int worker = 0; worker < config.concurrency(); worker++) {
            workers.add(() -> {
                while (System.nanoTime() < deadline) {
                    long operationStartedAt = System.nanoTime();
                    VertxAwait.await(operation.get(), config.maximumP99().multipliedBy(5));
                    histogram.record(Duration.ofNanos(System.nanoTime() - operationStartedAt));
                }
                return null;
            });
        }
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            awaitWorkers(scenario, executor.invokeAll(workers));
        }
        return histogram.snapshot(Duration.ofNanos(System.nanoTime() - startedAt));
    }

    private static TelemetryComparison runTelemetryComparison(
            BenchmarkConfig config, Supplier<Future<Void>> noopOperation,
            Supplier<Future<Void>> enabledOperation) throws Exception {
        LatencyHistogram noopHistogram = new LatencyHistogram(config.concurrency() * 500);
        LatencyHistogram enabledHistogram = new LatencyHistogram(config.concurrency() * 500);
        long startedAt = System.nanoTime();
        long deadline = startedAt + config.duration().toNanos();
        List<Callable<Void>> workers = new ArrayList<>();
        for (int worker = 0; worker < config.concurrency(); worker++) {
            int workerIndex = worker;
            workers.add(() -> {
                long iteration = workerIndex;
                while (System.nanoTime() < deadline) {
                    boolean noop = (iteration++ & 1L) == 0;
                    long operationStartedAt = System.nanoTime();
                    VertxAwait.await((noop ? noopOperation : enabledOperation).get(),
                            config.maximumP99().multipliedBy(5));
                    (noop ? noopHistogram : enabledHistogram).record(
                            Duration.ofNanos(System.nanoTime() - operationStartedAt));
                }
                return null;
            });
        }
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            awaitWorkers("telemetry-comparison", executor.invokeAll(workers));
        }
        Duration elapsed = Duration.ofNanos(System.nanoTime() - startedAt);
        return new TelemetryComparison(noopHistogram.snapshot(elapsed), enabledHistogram.snapshot(elapsed));
    }

    private static void awaitWorkers(
            String scenario, List<java.util.concurrent.Future<Void>> workers) throws Exception {
        for (java.util.concurrent.Future<Void> worker : workers) {
            try {
                worker.get();
            } catch (ExecutionException failure) {
                Throwable cause = failure.getCause();
                String outcome = cause instanceof TimeoutException ? "timed out" : "failed";
                throw new IllegalStateException(
                        "Benchmark scenario '" + scenario + "' " + outcome, cause);
            }
        }
    }

    private static Duration measureExpiryLag(Pool pool, Duration maximumLag) throws Exception {
        VertxAwait.await(pool.query("""
                INSERT INTO peegeeq_benchmark.cache_entries
                    (namespace, cache_key, value_type, value_bytes, expires_at)
                SELECT 'benchmark-expiry', 'key-' || value, 'STRING', convert_to('v', 'UTF8'),
                       NOW() + INTERVAL '100 milliseconds'
                FROM generate_series(1, 50) AS series(value)
                """).execute(), Duration.ofSeconds(5));
        long expectedExpiry = System.nanoTime() + Duration.ofMillis(100).toNanos();
        long deadline = expectedExpiry + maximumLag.toNanos();
        while (System.nanoTime() < deadline) {
            long remaining = VertxAwait.await(pool.query("""
                    SELECT COUNT(*) AS remaining FROM peegeeq_benchmark.cache_entries
                    WHERE namespace = 'benchmark-expiry'
                    """).execute(), Duration.ofSeconds(5)).iterator().next().getLong("remaining");
            if (remaining == 0) {
                return Duration.ofNanos(Math.max(0, System.nanoTime() - expectedExpiry));
            }
            Thread.sleep(20);
        }
        throw new IllegalStateException("Expiry lag exceeded " + maximumLag);
    }

    private static Duration measurePoolFailover(Vertx vertx, PgTestSupport postgres, Pool pool,
                                                 CacheService cache, Duration maximumRecovery) throws Exception {
        List<Future<?>> warmups = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            warmups.add(pool.query("SELECT pg_sleep(0.05)").execute());
        }
        VertxAwait.await(Future.all(warmups).mapEmpty(), Duration.ofSeconds(5));
        PgConnection admin = VertxAwait.await(PgConnection.connect(vertx,
                postgres.connectOptions().addProperty("application_name", "peegeeq-cache-benchmark-admin")),
                Duration.ofSeconds(5));
        try {
            VertxAwait.await(admin.preparedQuery("""
                    SELECT pg_terminate_backend(pid)
                    FROM pg_stat_activity
                    WHERE application_name = $1 AND pid <> pg_backend_pid()
                    """).execute(Tuple.of(APPLICATION_NAME)), Duration.ofSeconds(5));
        } finally {
            VertxAwait.await(admin.close(), Duration.ofSeconds(5));
        }

        long startedAt = System.nanoTime();
        long deadline = startedAt + maximumRecovery.toNanos();
        CacheSetRequest probe = new CacheSetRequest(new CacheKey("benchmark", "failover-probe"),
                CacheValue.ofString("recovered"), Duration.ofMinutes(1), SetMode.UPSERT, null, false);
        Throwable lastFailure = null;
        while (System.nanoTime() < deadline) {
            try {
                VertxAwait.await(cache.set(probe), Duration.ofSeconds(2));
                return Duration.ofNanos(System.nanoTime() - startedAt);
            } catch (Exception failure) {
                lastFailure = failure;
                Thread.sleep(25);
            }
        }
        throw new IllegalStateException("Pool did not recover within " + maximumRecovery, lastFailure);
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

    private record TelemetryComparison(
            LatencyHistogram.Snapshot noop, LatencyHistogram.Snapshot enabled) {
    }
}
