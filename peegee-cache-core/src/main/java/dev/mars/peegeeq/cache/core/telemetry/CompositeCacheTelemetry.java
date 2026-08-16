package dev.mars.peegeeq.cache.core.telemetry;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/** Combines multiple independent telemetry exporters. */
public final class CompositeCacheTelemetry implements CacheTelemetry {

    private final List<CacheTelemetry> delegates;

    public CompositeCacheTelemetry(CacheTelemetry... delegates) {
        Objects.requireNonNull(delegates, "delegates");
        this.delegates = Arrays.stream(delegates).map(d -> Objects.requireNonNull(d, "delegate")).toList();
    }

    @Override
    public OperationSpan startOperation(CacheOperation operation) {
        List<OperationSpan> spans = delegates.stream().map(d -> safeStart(d, operation)).toList();
        return new OperationSpan() {
            @Override
            public Activation activate() {
                List<Activation> activations = spans.stream().map(CompositeCacheTelemetry::safeActivate).toList();
                return () -> {
                    for (int i = activations.size() - 1; i >= 0; i--) {
                        safeRun(activations.get(i)::close);
                    }
                };
            }

            @Override
            public void complete(Throwable failure) {
                spans.forEach(span -> safeRun(() -> span.complete(failure)));
            }
        };
    }

    @Override
    public void recordLockContention() {
        delegates.forEach(d -> safeRun(d::recordLockContention));
    }

    @Override
    public void recordExpirySweep(int rows, Duration duration, Duration lag, Throwable failure) {
        delegates.forEach(d -> safeRun(() -> d.recordExpirySweep(rows, duration, lag, failure)));
    }

    @Override
    public void recordPubSubReconnect(int attempt, Duration duration, Throwable failure) {
        delegates.forEach(d -> safeRun(() -> d.recordPubSubReconnect(attempt, duration, failure)));
    }

    @Override
    public void recordNotificationDispatch(int handlers, Duration duration) {
        delegates.forEach(d -> safeRun(() -> d.recordNotificationDispatch(handlers, duration)));
    }

    @Override
    public void recordActiveSubscriptions(int subscriptions) {
        delegates.forEach(d -> safeRun(() -> d.recordActiveSubscriptions(subscriptions)));
    }

    @Override
    public void recordLifecycle(boolean started) {
        delegates.forEach(d -> safeRun(() -> d.recordLifecycle(started)));
    }

    private static OperationSpan safeStart(CacheTelemetry delegate, CacheOperation operation) {
        try {
            OperationSpan span = delegate.startOperation(operation);
            return span != null ? span : OperationSpan.NOOP;
        } catch (RuntimeException ignored) {
            return OperationSpan.NOOP;
        }
    }

    private static Activation safeActivate(OperationSpan span) {
        try {
            Activation activation = span.activate();
            return activation != null ? activation : Activation.NOOP;
        } catch (RuntimeException ignored) {
            return Activation.NOOP;
        }
    }

    private static void safeRun(Runnable action) {
        try {
            action.run();
        } catch (RuntimeException ignored) {
            // Telemetry must never change product behavior.
        }
    }
}
