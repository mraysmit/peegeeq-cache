package dev.mars.peegeeq.cache.core.metrics;

import dev.mars.peegeeq.cache.api.model.MetricsSnapshot;
import dev.mars.peegeeq.cache.core.telemetry.CacheOperation;
import dev.mars.peegeeq.cache.core.telemetry.CacheTelemetry;
import io.vertx.core.Future;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Supplier;

/**
 * Thread-safe in-memory cache operation metrics collector.
 * <p>
 * Uses {@link LongAdder} for high-throughput concurrent counter updates.
 */
public final class CacheMetrics {

    private final CacheTelemetry telemetry;

    private final LongAdder cacheGets = new LongAdder();
    private final LongAdder cacheHits = new LongAdder();
    private final LongAdder cacheMisses = new LongAdder();
    private final LongAdder cacheSets = new LongAdder();
    private final LongAdder cacheSetsApplied = new LongAdder();
    private final LongAdder cacheDeletes = new LongAdder();
    private final LongAdder counterIncrements = new LongAdder();
    private final LongAdder counterSets = new LongAdder();
    private final LongAdder counterDeletes = new LongAdder();
    private final LongAdder lockAcquires = new LongAdder();
    private final LongAdder lockAcquiresGranted = new LongAdder();
    private final LongAdder lockRenewals = new LongAdder();
    private final LongAdder lockReleases = new LongAdder();
    private final LongAdder publishes = new LongAdder();
    private final LongAdder subscribes = new LongAdder();

    public CacheMetrics() {
        this(CacheTelemetry.noop());
    }

    public CacheMetrics(CacheTelemetry telemetry) {
        this.telemetry = Objects.requireNonNull(telemetry, "telemetry");
    }

    /**
     * Measures a complete asynchronous operation, including failures. The
     * action is invoked while the telemetry span is active so downstream
     * instrumentation can inherit its context.
     */
    public <T> Future<T> observe(CacheOperation operation, Supplier<Future<T>> action) {
        Objects.requireNonNull(operation, "operation");
        Objects.requireNonNull(action, "action");
        CacheTelemetry.OperationSpan span = safeStart(operation);
        Future<T> future;
        try (CacheTelemetry.Activation ignored = safeActivate(span)) {
            future = Objects.requireNonNull(action.get(), "operation future");
        } catch (Throwable failure) {
            safeComplete(span, failure);
            return Future.failedFuture(failure);
        }
        return future.onComplete(result -> safeComplete(span, result.failed() ? result.cause() : null));
    }

    public void recordCacheGet(boolean hit) {
        cacheGets.increment();
        if (hit) {
            cacheHits.increment();
        } else {
            cacheMisses.increment();
        }
    }

    public void recordCacheSet(boolean applied) {
        cacheSets.increment();
        if (applied) {
            cacheSetsApplied.increment();
        }
    }

    public void recordCacheDelete() {
        cacheDeletes.increment();
    }

    public void recordCounterIncrement() {
        counterIncrements.increment();
    }

    public void recordCounterSet() {
        counterSets.increment();
    }

    public void recordCounterDelete() {
        counterDeletes.increment();
    }

    public void recordLockAcquire(boolean granted) {
        lockAcquires.increment();
        if (granted) {
            lockAcquiresGranted.increment();
        } else {
            safeRun(telemetry::recordLockContention);
        }
    }

    public void recordLockRenew() {
        lockRenewals.increment();
    }

    public void recordLockRelease() {
        lockReleases.increment();
    }

    public void recordPublish() {
        publishes.increment();
    }

    public void recordSubscribe() {
        subscribes.increment();
    }

    public void recordExpirySweep(int deletedRows, Duration duration, Duration oldestExpiredRowLag,
                                  Throwable failure) {
        safeRun(() -> telemetry.recordExpirySweep(deletedRows, duration, oldestExpiredRowLag, failure));
    }

    public void recordPubSubReconnect(int attempt, Duration duration, Throwable failure) {
        safeRun(() -> telemetry.recordPubSubReconnect(attempt, duration, failure));
    }

    public void recordNotificationDispatch(int handlerCount, Duration duration) {
        safeRun(() -> telemetry.recordNotificationDispatch(handlerCount, duration));
    }

    public void recordActiveSubscriptions(int subscriptions) {
        safeRun(() -> telemetry.recordActiveSubscriptions(subscriptions));
    }

    public void recordLifecycle(boolean started) {
        safeRun(() -> telemetry.recordLifecycle(started));
    }

    public MetricsSnapshot snapshot() {
        return new MetricsSnapshot(
                cacheGets.sum(),
                cacheHits.sum(),
                cacheMisses.sum(),
                cacheSets.sum(),
                cacheSetsApplied.sum(),
                cacheDeletes.sum(),
                counterIncrements.sum(),
                counterSets.sum(),
                counterDeletes.sum(),
                lockAcquires.sum(),
                lockAcquiresGranted.sum(),
                lockRenewals.sum(),
                lockReleases.sum(),
                publishes.sum(),
                subscribes.sum()
        );
    }

    public void reset() {
        cacheGets.reset();
        cacheHits.reset();
        cacheMisses.reset();
        cacheSets.reset();
        cacheSetsApplied.reset();
        cacheDeletes.reset();
        counterIncrements.reset();
        counterSets.reset();
        counterDeletes.reset();
        lockAcquires.reset();
        lockAcquiresGranted.reset();
        lockRenewals.reset();
        lockReleases.reset();
        publishes.reset();
        subscribes.reset();
    }

    private CacheTelemetry.OperationSpan safeStart(CacheOperation operation) {
        try {
            CacheTelemetry.OperationSpan span = telemetry.startOperation(operation);
            return span != null ? span : CacheTelemetry.OperationSpan.NOOP;
        } catch (RuntimeException ignored) {
            return CacheTelemetry.OperationSpan.NOOP;
        }
    }

    private static CacheTelemetry.Activation safeActivate(CacheTelemetry.OperationSpan span) {
        try {
            CacheTelemetry.Activation activation = span.activate();
            return activation != null ? activation : CacheTelemetry.Activation.NOOP;
        } catch (RuntimeException ignored) {
            return CacheTelemetry.Activation.NOOP;
        }
    }

    private static void safeComplete(CacheTelemetry.OperationSpan span, Throwable failure) {
        safeRun(() -> span.complete(failure));
    }

    private static void safeRun(Runnable action) {
        try {
            action.run();
        } catch (RuntimeException ignored) {
            // Telemetry must never alter cache behavior.
        }
    }
}
