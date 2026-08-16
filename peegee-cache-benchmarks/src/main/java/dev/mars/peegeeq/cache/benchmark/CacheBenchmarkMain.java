package dev.mars.peegeeq.cache.benchmark;

import dev.mars.peegeeq.cache.api.cache.CacheService;
import dev.mars.peegeeq.cache.api.counter.CounterService;
import dev.mars.peegeeq.cache.api.model.CacheKey;
import dev.mars.peegeeq.cache.api.model.CacheSetRequest;
import dev.mars.peegeeq.cache.api.model.CacheValue;
import dev.mars.peegeeq.cache.api.model.SetMode;
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
import io.vertx.core.Vertx;
import io.vertx.pgclient.PgConnection;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.PoolOptions;
import io.vertx.sqlclient.Tuple;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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
            run();
        } catch (Throwable failure) {
            failure.printStackTrace(System.err);
            exitCode = 1;
        }
        System.exit(exitCode);
    }

    private static void run() throws Exception {
        BenchmarkConfig config = BenchmarkConfig.fromSystemProperties();
        System.out.printf("benchmark-config concurrency=%d duration-seconds=%d%n",
                config.concurrency(), config.duration().toSeconds());
        Vertx vertx = Vertx.vertx();
        PgTestSupport postgres = new PgTestSupport("cache-benchmark", SCHEMA);
        Pool pool = null;
        PeeGeeCacheManager manager = null;
        try {
            postgres.start(vertx);
            pool = Pool.pool(vertx,
                    postgres.connectOptions().addProperty("application_name", APPLICATION_NAME),
                    new PoolOptions().setMaxSize(config.concurrency()));
            SimpleMeterRegistry registry = new SimpleMeterRegistry();
            PeeGeeCacheConfig runtime = new PeeGeeCacheConfig(
                    null, Duration.ofMillis(50), 250, true);
            PeeGeeCacheBootstrapOptions options = new PeeGeeCacheBootstrapOptions(
                    runtime, new PgCacheStoreConfig(SCHEMA, SCHEMA), null,
                    new MicrometerCacheTelemetry(registry));
            manager = VertxAwait.await(PeeGeeCaches.create(vertx, pool, options), Duration.ofSeconds(10));
            VertxAwait.await(manager.startReactive(), Duration.ofSeconds(10));

            CacheService cache = manager.cache().cache();
            CounterService counters = manager.cache().counters();
            LatencyHistogram.Snapshot mixed = runSustained(config,
                    mixedOperation(cache, new AtomicLong()));
            LatencyHistogram.Snapshot contention = runSustained(config,
                    () -> counters.increment(new CacheKey("benchmark", "contended-counter")).mapEmpty());
            Duration expiryLag = measureExpiryLag(pool, config.maximumExpiryLag());
            Duration failoverRecovery = measurePoolFailover(vertx, postgres, pool, cache,
                    config.maximumFailoverRecovery());

            print("mixed-set-get", mixed);
            print("counter-contention", contention);
            System.out.printf("expiry-lag-ms=%d%n", expiryLag.toMillis());
            System.out.printf("pool-failover-recovery-ms=%d%n", failoverRecovery.toMillis());
            enforce("mixed-set-get", mixed, config);
            enforce("counter-contention", contention, config);
        } finally {
            if (manager != null && manager.isStarted()) {
                VertxAwait.await(manager.stopReactive(), Duration.ofSeconds(10));
            }
            if (pool != null) {
                VertxAwait.await(pool.close(), Duration.ofSeconds(10));
            }
            postgres.stop();
            VertxAwait.await(vertx.close(), Duration.ofSeconds(10));
        }
    }

    private static Supplier<Future<Void>> mixedOperation(CacheService cache, AtomicLong sequence) {
        return () -> {
            long id = sequence.incrementAndGet();
            CacheKey key = new CacheKey("benchmark", "mixed-" + (id % 1_000));
            CacheSetRequest request = new CacheSetRequest(key, CacheValue.ofString("value-" + id),
                    Duration.ofMinutes(5), SetMode.UPSERT, null, false);
            return cache.set(request).compose(ignored -> cache.get(key)).mapEmpty();
        };
    }

    private static LatencyHistogram.Snapshot runSustained(BenchmarkConfig config,
                                                            Supplier<Future<Void>> operation) throws Exception {
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
            for (java.util.concurrent.Future<Void> worker : executor.invokeAll(workers)) {
                worker.get();
            }
        }
        return histogram.snapshot(Duration.ofNanos(System.nanoTime() - startedAt));
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

    private static void print(String name, LatencyHistogram.Snapshot result) {
        System.out.printf("scenario=%s operations=%d throughput-per-second=%.2f p50-ms=%.3f p95-ms=%.3f p99-ms=%.3f%n",
                name, result.operations(), result.throughputPerSecond(), millis(result.p50()),
                millis(result.p95()), millis(result.p99()));
    }

    private static double millis(Duration value) {
        return value.toNanos() / 1_000_000.0;
    }
}
