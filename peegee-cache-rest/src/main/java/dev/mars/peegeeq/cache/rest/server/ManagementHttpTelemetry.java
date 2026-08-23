package dev.mars.peegeeq.cache.rest.server;

import dev.mars.peegeeq.cache.api.management.ManagementRuntimeLifecycleState;

/** Failure-isolated low-cardinality observer for management HTTP completions. */
@FunctionalInterface
public interface ManagementHttpTelemetry {

    enum Surface {
        HEALTH,
        API,
        WEBSOCKET,
        UI,
        OTHER
    }

    void completed(String method, Surface surface, int status, long latencyMillis);

    default Request started(String method, Surface surface) {
        return (status, latencyMillis) -> completed(method, surface, status, latencyMillis);
    }

    default Request started(String method, Surface surface, String path) {
        return started(method, surface);
    }

    default void lifecycleChanged(ManagementRuntimeLifecycleState state) {
    }

    default void serverReadinessChanged(boolean ready) {
    }

    default void shutdownCompleted() {
    }

    @FunctionalInterface
    interface Request {
        void completed(int status, long latencyMillis);

        default void completed(int status, long latencyMillis, String errorCode) {
            completed(status, latencyMillis);
        }
    }

    static ManagementHttpTelemetry noop() {
        return (method, surface, status, latencyMillis) -> { };
    }
}
