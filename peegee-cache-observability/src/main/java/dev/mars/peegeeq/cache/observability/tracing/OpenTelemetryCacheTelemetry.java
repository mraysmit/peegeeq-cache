package dev.mars.peegeeq.cache.observability.tracing;

import dev.mars.peegeeq.cache.core.telemetry.CacheOperation;
import dev.mars.peegeeq.cache.core.telemetry.CacheTelemetry;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.DoubleHistogram;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.metrics.Meter;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

/** OpenTelemetry tracing and metrics adapter for peegee-cache. */
public final class OpenTelemetryCacheTelemetry implements CacheTelemetry {

    private static final AttributeKey<String> OPERATION = AttributeKey.stringKey("peegeeq.cache.operation");
    private static final AttributeKey<String> OUTCOME = AttributeKey.stringKey("peegeeq.cache.outcome");
    private static final AttributeKey<Long> ATTEMPT = AttributeKey.longKey("peegeeq.cache.reconnect.attempt");

    private final Tracer tracer;
    private final LongCounter operationCount;
    private final DoubleHistogram operationDuration;
    private final LongCounter lockContention;
    private final LongCounter expiredRows;
    private final DoubleHistogram expiryLag;
    private final LongCounter reconnects;
    private final DoubleHistogram notificationDispatch;
    private final AtomicLong activeOperations = new AtomicLong();
    private final AtomicLong subscriptions = new AtomicLong();
    private final AtomicLong lifecycle = new AtomicLong();

    public OpenTelemetryCacheTelemetry(OpenTelemetry openTelemetry) {
        Objects.requireNonNull(openTelemetry, "openTelemetry");
        tracer = openTelemetry.getTracer("dev.mars.peegeeq.cache", "0.1.0");
        Meter meter = openTelemetry.getMeter("dev.mars.peegeeq.cache");
        operationCount = meter.counterBuilder("peegeeq.cache.operation.count").setUnit("{operation}").build();
        operationDuration = meter.histogramBuilder("peegeeq.cache.operation.duration")
                .setUnit("s").build();
        lockContention = meter.counterBuilder("peegeeq.cache.lock.contention").setUnit("{attempt}").build();
        expiredRows = meter.counterBuilder("peegeeq.cache.expiry.rows").setUnit("{row}").build();
        expiryLag = meter.histogramBuilder("peegeeq.cache.expiry.lag").setUnit("s").build();
        reconnects = meter.counterBuilder("peegeeq.cache.pubsub.reconnect").setUnit("{attempt}").build();
        notificationDispatch = meter.histogramBuilder("peegeeq.cache.pubsub.notification.dispatch")
                .setUnit("s").build();
        meter.gaugeBuilder("peegeeq.cache.operations.active").ofLongs()
                .buildWithCallback(measurement -> measurement.record(activeOperations.get()));
        meter.gaugeBuilder("peegeeq.cache.pubsub.subscriptions").ofLongs()
                .buildWithCallback(measurement -> measurement.record(subscriptions.get()));
        meter.gaugeBuilder("peegeeq.cache.runtime.started").ofLongs()
                .buildWithCallback(measurement -> measurement.record(lifecycle.get()));
    }

    @Override
    public OperationSpan startOperation(CacheOperation operation) {
        Objects.requireNonNull(operation, "operation");
        Span span = tracer.spanBuilder("peegeeq.cache " + operation.metricName())
                .setAttribute(OPERATION, operation.metricName())
                .startSpan();
        activeOperations.incrementAndGet();
        long startedAt = System.nanoTime();
        return new OperationSpan() {
            @Override
            public Activation activate() {
                Scope scope = span.makeCurrent();
                return scope::close;
            }

            @Override
            public void complete(Throwable failure) {
                String outcome = failure == null ? "success" : "failure";
                Attributes attributes = Attributes.of(OPERATION, operation.metricName(), OUTCOME, outcome);
                operationCount.add(1, attributes);
                operationDuration.record(secondsSince(startedAt), attributes);
                activeOperations.decrementAndGet();
                span.setAttribute(OUTCOME, outcome);
                if (failure != null) {
                    span.recordException(failure);
                    span.setStatus(StatusCode.ERROR, failure.getMessage() == null ? "operation failed" : failure.getMessage());
                }
                span.end();
            }
        };
    }

    @Override
    public void recordLockContention() {
        lockContention.add(1);
    }

    @Override
    public void recordExpirySweep(int deletedRows, Duration duration, Duration oldestExpiredRowLag,
                                  Throwable failure) {
        Attributes attributes = Attributes.of(OUTCOME, failure == null ? "success" : "failure");
        operationDuration.record(duration.toNanos() / 1_000_000_000.0,
                Attributes.builder().putAll(attributes).put(OPERATION, "expiry.sweep").build());
        if (failure == null) {
            expiredRows.add(deletedRows);
            expiryLag.record(oldestExpiredRowLag.toNanos() / 1_000_000_000.0);
        }
    }

    @Override
    public void recordPubSubReconnect(int attempt, Duration duration, Throwable failure) {
        Attributes attributes = Attributes.of(OUTCOME, failure == null ? "success" : "failure", ATTEMPT, (long) attempt);
        reconnects.add(1, attributes);
        operationDuration.record(duration.toNanos() / 1_000_000_000.0,
                Attributes.builder().putAll(attributes).put(OPERATION, "pubsub.reconnect").build());
    }

    @Override
    public void recordNotificationDispatch(int handlerCount, Duration duration) {
        notificationDispatch.record(duration.toNanos() / 1_000_000_000.0,
                Attributes.of(AttributeKey.longKey("peegeeq.cache.handler.count"), (long) handlerCount));
    }

    @Override
    public void recordActiveSubscriptions(int value) {
        subscriptions.set(Math.max(0, value));
    }

    @Override
    public void recordLifecycle(boolean started) {
        lifecycle.set(started ? 1 : 0);
    }

    private static double secondsSince(long startedAt) {
        return Math.max(0, System.nanoTime() - startedAt) / 1_000_000_000.0;
    }
}
