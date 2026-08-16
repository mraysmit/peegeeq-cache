package dev.mars.peegeeq.cache.observability.metrics;

import dev.mars.peegeeq.cache.core.telemetry.CacheOperation;
import dev.mars.peegeeq.cache.core.telemetry.CacheTelemetry;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/** Production Micrometer metrics adapter for peegee-cache. */
public final class MicrometerCacheTelemetry implements CacheTelemetry {

    private final MeterRegistry registry;
    private final AtomicLong activeOperations = new AtomicLong();
    private final AtomicInteger activeSubscriptions = new AtomicInteger();
    private final AtomicInteger lifecycle = new AtomicInteger();
    private final Counter lockContention;
    private final DistributionSummary expiryRows;
    private final DistributionSummary expiryLag;
    private final DistributionSummary notificationHandlers;

    public MicrometerCacheTelemetry(MeterRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry");
        Gauge.builder("peegeeq.cache.operations.active", activeOperations, AtomicLong::doubleValue)
                .description("Currently executing peegee-cache operations")
                .register(registry);
        Gauge.builder("peegeeq.cache.pubsub.subscriptions", activeSubscriptions, AtomicInteger::doubleValue)
                .description("Active local pub/sub subscriptions")
                .register(registry);
        Gauge.builder("peegeeq.cache.runtime.started", lifecycle, AtomicInteger::doubleValue)
                .description("Whether the managed runtime is started")
                .register(registry);
        lockContention = Counter.builder("peegeeq.cache.lock.contention")
                .description("Lock acquisition attempts that were not granted")
                .register(registry);
        expiryRows = DistributionSummary.builder("peegeeq.cache.expiry.rows")
                .baseUnit("rows")
                .description("Rows physically removed per expiry sweep")
                .register(registry);
        expiryLag = DistributionSummary.builder("peegeeq.cache.expiry.lag")
                .baseUnit("seconds")
                .description("Age of the oldest row removed by an expiry sweep")
                .register(registry);
        notificationHandlers = DistributionSummary.builder("peegeeq.cache.pubsub.notification.handlers")
                .baseUnit("handlers")
                .description("Local handlers invoked per PostgreSQL notification")
                .register(registry);
    }

    @Override
    public OperationSpan startOperation(CacheOperation operation) {
        Objects.requireNonNull(operation, "operation");
        activeOperations.incrementAndGet();
        long startedAt = System.nanoTime();
        return failure -> {
            long elapsed = Math.max(0, System.nanoTime() - startedAt);
            activeOperations.decrementAndGet();
            Timer.builder("peegeeq.cache.operation")
                    .description("Completed peegee-cache operation latency")
                    .publishPercentileHistogram()
                    .tag("operation", operation.metricName())
                    .tag("outcome", outcome(failure))
                    .register(registry)
                    .record(elapsed, TimeUnit.NANOSECONDS);
        };
    }

    @Override
    public void recordLockContention() {
        lockContention.increment();
    }

    @Override
    public void recordExpirySweep(int deletedRows, Duration duration, Duration oldestExpiredRowLag,
                                  Throwable failure) {
        Timer.builder("peegeeq.cache.expiry.sweep")
                .publishPercentileHistogram()
                .tag("outcome", outcome(failure))
                .register(registry)
                .record(duration);
        if (failure == null) {
            expiryRows.record(deletedRows);
            expiryLag.record(oldestExpiredRowLag.toNanos() / 1_000_000_000.0);
        }
    }

    @Override
    public void recordPubSubReconnect(int attempt, Duration duration, Throwable failure) {
        Timer.builder("peegeeq.cache.pubsub.reconnect")
                .publishPercentileHistogram()
                .tag("outcome", outcome(failure))
                .register(registry)
                .record(duration);
    }

    @Override
    public void recordNotificationDispatch(int handlerCount, Duration duration) {
        Timer.builder("peegeeq.cache.pubsub.notification.dispatch")
                .publishPercentileHistogram()
                .register(registry)
                .record(duration);
        notificationHandlers.record(handlerCount);
    }

    @Override
    public void recordActiveSubscriptions(int subscriptions) {
        activeSubscriptions.set(Math.max(0, subscriptions));
    }

    @Override
    public void recordLifecycle(boolean started) {
        lifecycle.set(started ? 1 : 0);
    }

    private static String outcome(Throwable failure) {
        return failure == null ? "success" : "failure";
    }
}
