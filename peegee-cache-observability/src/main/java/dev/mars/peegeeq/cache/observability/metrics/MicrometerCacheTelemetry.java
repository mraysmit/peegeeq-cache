package dev.mars.peegeeq.cache.observability.metrics;

import dev.mars.peegeeq.cache.core.telemetry.CacheOperation;
import dev.mars.peegeeq.cache.core.telemetry.CacheTelemetry;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.WeakHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/** Production Micrometer metrics adapter for peegee-cache. */
public final class MicrometerCacheTelemetry implements CacheTelemetry {

    private static final Map<MeterRegistry, SharedGauges> SHARED_GAUGES = new WeakHashMap<>();

    private final MeterRegistry registry;
    private final SharedGauges gauges;
    private final AtomicInteger localActiveSubscriptions = new AtomicInteger();
    private final AtomicInteger localLifecycle = new AtomicInteger();
    private final Counter lockContention;
    private final DistributionSummary expiryRows;
    private final DistributionSummary expiryLag;
    private final DistributionSummary notificationHandlers;

    public MicrometerCacheTelemetry(MeterRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry");
        gauges = sharedGauges(registry);
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
        gauges.activeOperations.incrementAndGet();
        long startedAt = System.nanoTime();
        return failure -> {
            long elapsed = Math.max(0, System.nanoTime() - startedAt);
            gauges.activeOperations.decrementAndGet();
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
        int normalized = Math.max(0, subscriptions);
        int previous = localActiveSubscriptions.getAndSet(normalized);
        gauges.activeSubscriptions.addAndGet(normalized - previous);
    }

    @Override
    public void recordLifecycle(boolean started) {
        int current = started ? 1 : 0;
        int previous = localLifecycle.getAndSet(current);
        gauges.startedRuntimes.addAndGet(current - previous);
    }

    private static SharedGauges sharedGauges(MeterRegistry registry) {
        synchronized (SHARED_GAUGES) {
            return SHARED_GAUGES.computeIfAbsent(registry, MicrometerCacheTelemetry::registerGauges);
        }
    }

    private static SharedGauges registerGauges(MeterRegistry registry) {
        SharedGauges gauges = new SharedGauges();
        Gauge.builder("peegeeq.cache.operations.active", gauges.activeOperations, AtomicLong::doubleValue)
                .description("Currently executing peegee-cache operations")
                .register(registry);
        Gauge.builder("peegeeq.cache.pubsub.subscriptions", gauges.activeSubscriptions, AtomicLong::doubleValue)
                .description("Active local pub/sub subscriptions across managed runtimes")
                .register(registry);
        Gauge.builder("peegeeq.cache.runtime.started", gauges.startedRuntimes, AtomicLong::doubleValue)
                .description("Number of started managed runtimes")
                .register(registry);
        return gauges;
    }

    private static String outcome(Throwable failure) {
        return failure == null ? "success" : "failure";
    }

    private static final class SharedGauges {
        private final AtomicLong activeOperations = new AtomicLong();
        private final AtomicLong activeSubscriptions = new AtomicLong();
        private final AtomicLong startedRuntimes = new AtomicLong();
    }
}
