package dev.mars.peegeeq.cache.core.telemetry;

import java.time.Duration;

/**
 * Vendor-neutral production telemetry contract.
 *
 * <p>Implementations must be thread-safe, non-blocking, and must not throw into
 * cache operations. Operation and outcome values are deliberately bounded to
 * prevent accidental high-cardinality telemetry.</p>
 */
public interface CacheTelemetry {

    CacheTelemetry NOOP = new CacheTelemetry() { };

    default OperationSpan startOperation(CacheOperation operation) {
        return OperationSpan.NOOP;
    }

    default void recordLockContention() {
    }

    default void recordExpirySweep(int deletedRows, Duration duration, Duration oldestExpiredRowLag,
                                   Throwable failure) {
    }

    default void recordPubSubReconnect(int attempt, Duration duration, Throwable failure) {
    }

    default void recordNotificationDispatch(int handlerCount, Duration duration) {
    }

    default void recordActiveSubscriptions(int subscriptions) {
    }

    default void recordLifecycle(boolean started) {
    }

    default void recordWriteBehindOverflow() {
    }

    default void recordWriteBehindFlush(int entryCount, Duration duration, Throwable failure) {
    }

    default void recordWriteBehindDiscard(int entryCount) {
    }

    static CacheTelemetry noop() {
        return NOOP;
    }

    interface OperationSpan {
        OperationSpan NOOP = failure -> { };

        default Activation activate() {
            return Activation.NOOP;
        }

        void complete(Throwable failure);
    }

    interface Activation extends AutoCloseable {
        Activation NOOP = () -> { };

        @Override
        void close();
    }
}
