package dev.mars.peegeeq.cache.rest.server;

import dev.mars.peegeeq.cache.api.management.ManagementAuditTerminalOutcome;
import dev.mars.peegeeq.cache.api.management.ManagementRuntimeLifecycleState;
import dev.mars.peegeeq.cache.rest.audit.ManagementAuditTelemetry;
import dev.mars.peegeeq.cache.rest.audit.AuditPersistenceOperation;
import dev.mars.peegeeq.cache.rest.security.RateLimitAction;
import dev.mars.peegeeq.cache.rest.security.RateLimitOutcome;
import dev.mars.peegeeq.cache.rest.security.RateLimitTelemetry;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/** Failure-isolated, low-cardinality Micrometer adapter for the management server. */
public final class MicrometerManagementTelemetry implements
        ManagementHttpTelemetry, ManagementAuditTelemetry, RateLimitTelemetry {

    private final MeterRegistry registry;
    private final ManagementRuntimeMonitor runtimeMonitor;
    private final AtomicLong activeHttpRequests = new AtomicLong();
    private final AtomicLong auditPending = new AtomicLong();
    private final AtomicLong auditCapacity = new AtomicLong();
    private final AtomicLong auditReady = new AtomicLong(1);
    private final AtomicLong serverReady = new AtomicLong();

    public MicrometerManagementTelemetry(
            MeterRegistry registry, ManagementRuntimeMonitor runtimeMonitor) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.runtimeMonitor = Objects.requireNonNull(runtimeMonitor, "runtimeMonitor");
        Gauge.builder("peegeeq.management.http.active", activeHttpRequests, AtomicLong::get)
                .register(registry);
        Gauge.builder("peegeeq.management.audit.pending", auditPending, AtomicLong::get)
                .register(registry);
        Gauge.builder("peegeeq.management.audit.capacity", auditCapacity, AtomicLong::get)
                .register(registry);
        Gauge.builder("peegeeq.management.audit.ready", auditReady, AtomicLong::get)
                .register(registry);
        Gauge.builder("peegeeq.management.server.ready", serverReady, AtomicLong::get)
                .register(registry);
        resourceGauge(registry, runtimeMonitor, "peegeeq.management.setups.registered",
                ManagementRuntimeMonitor.ResourceSnapshot::registeredSetups);
        resourceGauge(registry, runtimeMonitor, "peegeeq.management.pools.active",
                ManagementRuntimeMonitor.ResourceSnapshot::activePools);
        resourceGauge(registry, runtimeMonitor, "peegeeq.management.pubsub.subscriptions",
                snapshot -> snapshot.pubSubSubscriptions());
        resourceGauge(registry, runtimeMonitor, "peegeeq.management.sse.clients",
                snapshot -> snapshot.sseClients());
        resourceGauge(registry, runtimeMonitor, "peegeeq.management.websocket.clients",
                snapshot -> snapshot.webSocketClients());
        resourceGauge(registry, runtimeMonitor, "peegeeq.management.retained_payload.bytes",
                snapshot -> snapshot.retainedPayloadBytes());
        resourceGauge(registry, runtimeMonitor, "peegeeq.management.buffer.evictions",
                snapshot -> snapshot.bufferEvictions());
        resourceGauge(registry, runtimeMonitor, "peegeeq.management.stream.resets",
                snapshot -> snapshot.streamResets());
        runtimeMonitor.operationTelemetry(this::recordPostgresqlOperation);
    }

    MeterRegistry meterRegistry() {
        return registry;
    }

    private void recordPostgresqlOperation(
            String operation, boolean failed, long elapsedNanos) {
        String outcome = failed ? "FAILED" : "SUCCEEDED";
        Counter.builder("peegeeq.management.postgresql.operations")
                .tag("operation", operation)
                .tag("outcome", outcome)
                .register(registry)
                .increment();
        Timer.builder("peegeeq.management.postgresql.duration")
                .tag("operation", operation)
                .tag("outcome", outcome)
                .register(registry)
                .record(elapsedNanos, TimeUnit.NANOSECONDS);
    }

    @Override
    public Request started(String method, Surface surface) {
        return started(method, surface, "OTHER");
    }

    @Override
    public Request started(String method, Surface surface, String path) {
        String boundedMethod = boundedMethod(method);
        Objects.requireNonNull(surface, "surface");
        String route = ManagementRouteTemplate.resolve(path);
        activeHttpRequests.incrementAndGet();
        AtomicBoolean completed = new AtomicBoolean();
        return new Request() {
            @Override
            public void completed(int status, long latencyMillis) {
                completed(status, latencyMillis, null);
            }

            @Override
            public void completed(int status, long latencyMillis, String errorCode) {
                if (!completed.compareAndSet(false, true)) return;
                activeHttpRequests.decrementAndGet();
                recordHttp(boundedMethod, surface, status, latencyMillis, route, errorCode);
            }
        };
    }

    @Override
    public void completed(String method, Surface surface, int status, long latencyMillis) {
        recordHttp(boundedMethod(method), Objects.requireNonNull(surface, "surface"),
                status, latencyMillis, "OTHER", null);
    }

    @Override
    public void lifecycleChanged(ManagementRuntimeLifecycleState state) {
        ManagementRuntimeLifecycleState bounded = Objects.requireNonNull(state, "state");
        runtimeMonitor.lifecycle(bounded);
        increment("peegeeq.management.server.lifecycle", "state", bounded.name());
    }

    @Override
    public void serverReadinessChanged(boolean ready) {
        serverReady.set(ready ? 1 : 0);
    }

    @Override
    public void shutdownCompleted() {
        ManagementRuntimeMonitor.ResourceSnapshot snapshot = runtimeMonitor.resourceSnapshot();
        long leakedCategories = 0;
        if (snapshot.registeredSetups() > 0) leakedCategories++;
        if (snapshot.activePools() > 0) leakedCategories++;
        if (snapshot.pubSubSubscriptions() > 0) leakedCategories++;
        if (snapshot.sseClients() > 0) leakedCategories++;
        if (snapshot.webSocketClients() > 0) leakedCategories++;
        if (snapshot.retainedPayloadBytes() > 0) leakedCategories++;
        increment("peegeeq.management.shutdown.leaked_resources", leakedCategories);
    }

    @Override
    public void record(RateLimitAction action, RateLimitOutcome outcome) {
        try {
            Counter.builder("peegeeq.management.rate_limit.outcomes")
                    .tag("action", Objects.requireNonNull(action, "action").name())
                    .tag("outcome", Objects.requireNonNull(outcome, "outcome").name())
                    .register(registry)
                    .increment();
        } catch (RuntimeException ignored) {
            // Optional telemetry cannot alter security enforcement.
        }
    }

    @Override
    public void reservationAccepted(int pendingReservations, int capacity) {
        auditPending.set(Math.max(0, pendingReservations));
        auditCapacity.set(Math.max(0, capacity));
        increment("peegeeq.management.audit.reservations", "outcome", "ACCEPTED");
    }

    @Override
    public void reservationRejected() {
        increment("peegeeq.management.audit.reservations", "outcome", "REJECTED");
    }

    @Override
    public void outcomePersisted(
            ManagementAuditTerminalOutcome outcome, int pendingReservations) {
        auditPending.set(Math.max(0, pendingReservations));
        increment("peegeeq.management.audit.outcomes", "outcome", outcome.name());
    }

    @Override
    public void readinessChanged(boolean mutationReady) {
        auditReady.set(mutationReady ? 1 : 0);
    }

    @Override
    public void incompleteIntentsRecovered(int count) {
        increment("peegeeq.management.audit.recovered_intents", Math.max(0, count));
    }

    @Override
    public void persistenceFailed(AuditPersistenceOperation operation) {
        increment("peegeeq.management.audit.persistence_failures",
                "operation", Objects.requireNonNull(operation, "operation").name());
    }

    private void recordHttp(
            String method, Surface surface, int status, long latencyMillis,
            String route, String errorCode) {
        try {
            String boundedStatus = Integer.toString(Math.max(100, Math.min(599, status)));
            String boundedError = boundedError(status, errorCode);
            Counter.builder("peegeeq.management.http.requests")
                    .tags("method", method, "surface", surface.name(), "status", boundedStatus,
                            "route", route, "error", boundedError)
                    .register(registry)
                    .increment();
            Timer.builder("peegeeq.management.http.duration")
                    .tags("method", method, "surface", surface.name(), "status", boundedStatus,
                            "route", route, "error", boundedError)
                    .register(registry)
                    .record(Math.max(0, latencyMillis), TimeUnit.MILLISECONDS);
            recordSecurityOutcomes(method, surface, status, route, boundedError);
        } catch (RuntimeException ignored) {
            // Optional telemetry cannot alter HTTP completion.
        }
    }

    private void recordSecurityOutcomes(
            String method, Surface surface, int status, String route, String error) {
        boolean knownProtectedRoute = (surface == Surface.API || surface == Surface.WEBSOCKET)
                && !route.endsWith("/*") && !"OTHER".equals(route);
        if (!knownProtectedRoute) return;

        boolean authenticationRejected = switch (error) {
            case "AUTHENTICATION_REQUIRED", "INVALID_BOOTSTRAP_TOKEN",
                    "INVALID_IDENTITY", "SESSION_EXPIRED" -> true;
            default -> false;
        };
        securityOutcome("AUTHENTICATION", authenticationRejected ? "REJECTED" : "ACCEPTED");
        if (authenticationRejected) return;

        boolean authorizationRejected = "AUTHORIZATION_FAILED".equals(error);
        securityOutcome("AUTHORIZATION", authorizationRejected ? "REJECTED" : "ACCEPTED");
        if (authorizationRejected) return;

        boolean unsafe = switch (method) {
            case "POST", "PUT", "PATCH", "DELETE" -> true;
            default -> false;
        };
        boolean stream = route.endsWith("/stream") || route.endsWith("/sse/metrics")
                || "/ws/monitoring".equals(route);
        if (unsafe && !"/api/v1/session/local".equals(route)) {
            if ("CSRF_VALIDATION_FAILED".equals(error)) {
                securityOutcome("CSRF", "REJECTED");
            } else if (!"ORIGIN_VALIDATION_FAILED".equals(error)) {
                securityOutcome("CSRF", "ACCEPTED");
            }
        }
        if (unsafe || stream) {
            securityOutcome("ORIGIN",
                    "ORIGIN_VALIDATION_FAILED".equals(error) ? "REJECTED" : "ACCEPTED");
        }
        boolean targetRoute = ("POST".equals(method) && "/api/v1/setups".equals(route))
                || "/api/v1/setups/actions/test".equals(route);
        if ("TARGET_FORBIDDEN".equals(error)) {
            securityOutcome("TARGET_POLICY", "REJECTED");
        } else if (targetRoute && status < 400) {
            securityOutcome("TARGET_POLICY", "ACCEPTED");
        }
    }

    private void securityOutcome(String control, String outcome) {
        Counter.builder("peegeeq.management.security.outcomes")
                .tags("control", control, "outcome", outcome)
                .register(registry)
                .increment();
    }

    private void increment(String name, String tagName, String tagValue) {
        try {
            Counter.builder(name).tag(tagName, tagValue).register(registry).increment();
        } catch (RuntimeException ignored) {
            // Optional telemetry cannot alter authoritative audit behavior.
        }
    }

    private void increment(String name, double amount) {
        try {
            Counter.builder(name).register(registry).increment(amount);
        } catch (RuntimeException ignored) {
            // Optional telemetry cannot alter authoritative audit behavior.
        }
    }

    private static String boundedMethod(String method) {
        Objects.requireNonNull(method, "method");
        return switch (method) {
            case "GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS", "HEAD" -> method;
            default -> "OTHER";
        };
    }

    private static String boundedError(int status, String errorCode) {
        if (status < 400) return "NONE";
        if (errorCode == null || !errorCode.matches("[A-Z][A-Z0-9_]{0,63}")) {
            return "UNSPECIFIED";
        }
        return errorCode;
    }

    private static void resourceGauge(
            MeterRegistry registry,
            ManagementRuntimeMonitor monitor,
            String name,
            java.util.function.ToLongFunction<ManagementRuntimeMonitor.ResourceSnapshot> value) {
        Gauge.builder(name, monitor, target -> value.applyAsLong(target.resourceSnapshot()))
                .register(registry);
    }
}
