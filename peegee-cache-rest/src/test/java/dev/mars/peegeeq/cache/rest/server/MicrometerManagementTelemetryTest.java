package dev.mars.peegeeq.cache.rest.server;

import dev.mars.peegeeq.cache.api.management.ManagementAuditTerminalOutcome;
import dev.mars.peegeeq.cache.api.management.ManagementRuntimeLifecycleState;
import dev.mars.peegeeq.cache.rest.audit.AuditPersistenceOperation;
import dev.mars.peegeeq.cache.rest.security.RateLimitAction;
import dev.mars.peegeeq.cache.rest.security.RateLimitOutcome;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MicrometerManagementTelemetryTest {

    @Test
    void exportsBoundedHttpSecurityAuditAndResourceSignals() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        ManagementRuntimeMonitor runtime = new ManagementRuntimeMonitor();
        MicrometerManagementTelemetry telemetry =
                new MicrometerManagementTelemetry(registry, runtime);

        ManagementHttpTelemetry.Request request = telemetry.started(
                "GET", ManagementHttpTelemetry.Surface.API,
                "/api/v1/setups/customer:secret/namespaces/orders/entries/key:secret");
        assertEquals(1.0, registry.get("peegeeq.management.http.active").gauge().value());
        request.completed(404, 7, "ENTRY_NOT_FOUND");
        telemetry.started("POST", ManagementHttpTelemetry.Surface.API,
                        "/api/v1/setups/setup-a/namespaces/orders/entries/key-a")
                .completed(403, 1, "CSRF_VALIDATION_FAILED");
        telemetry.started("GET", ManagementHttpTelemetry.Surface.API, "/api/v1/setups")
                .completed(200, 1, null);
        telemetry.record(RateLimitAction.PUBLISH, RateLimitOutcome.REJECTED);
        telemetry.reservationAccepted(3, 100);
        telemetry.reservationRejected();
        telemetry.incompleteIntentsRecovered(2);
        telemetry.persistenceFailed(AuditPersistenceOperation.OUTCOME);
        telemetry.outcomePersisted(ManagementAuditTerminalOutcome.SUCCEEDED, 2);
        telemetry.readinessChanged(false);
        telemetry.serverReadinessChanged(false);
        telemetry.lifecycleChanged(ManagementRuntimeLifecycleState.RUNNING);
        runtime.pubSubSubscriptions(4);
        runtime.registeredSetups(2);
        runtime.activePools(1);
        runtime.retainedPayloadBytes(8192);
        runtime.bufferEvicted();
        runtime.streamReset();
        runtime.begin("database.overview").succeeded();
        runtime.begin("namespace.list").failed();
        telemetry.shutdownCompleted();

        assertEquals(0.0, registry.get("peegeeq.management.http.active").gauge().value());
        assertEquals(1.0, registry.get("peegeeq.management.http.requests")
                .tags("method", "GET", "surface", "API", "status", "404",
                        "route", "/api/v1/setups/{setupId}/namespaces/{encodedNamespace}/entries/{encodedKey}",
                        "error", "ENTRY_NOT_FOUND")
                .counter().count());
        assertEquals(1, registry.get("peegeeq.management.http.duration")
                .tags("method", "GET", "surface", "API", "status", "404",
                        "route", "/api/v1/setups/{setupId}/namespaces/{encodedNamespace}/entries/{encodedKey}",
                        "error", "ENTRY_NOT_FOUND")
                .timer().count());
        assertEquals(1.0, registry.get("peegeeq.management.rate_limit.outcomes")
                .tags("action", "PUBLISH", "outcome", "REJECTED").counter().count());
        assertEquals(1.0, registry.get("peegeeq.management.security.outcomes")
                .tags("control", "CSRF", "outcome", "REJECTED").counter().count());
        assertEquals(3.0, registry.get("peegeeq.management.security.outcomes")
                .tags("control", "AUTHENTICATION", "outcome", "ACCEPTED").counter().count());
        assertEquals(3.0, registry.get("peegeeq.management.security.outcomes")
                .tags("control", "AUTHORIZATION", "outcome", "ACCEPTED").counter().count());
        assertEquals(2.0, registry.get("peegeeq.management.audit.pending").gauge().value());
        assertEquals(0.0, registry.get("peegeeq.management.audit.ready").gauge().value());
        assertEquals(1.0, registry.get("peegeeq.management.audit.reservations")
                .tag("outcome", "REJECTED").counter().count());
        assertEquals(2.0, registry.get("peegeeq.management.audit.recovered_intents")
                .counter().count());
        assertEquals(1.0, registry.get("peegeeq.management.audit.persistence_failures")
                .tag("operation", "OUTCOME").counter().count());
        assertEquals(4.0, registry.get("peegeeq.management.pubsub.subscriptions").gauge().value());
        assertEquals(2.0, registry.get("peegeeq.management.setups.registered").gauge().value());
        assertEquals(1.0, registry.get("peegeeq.management.pools.active").gauge().value());
        assertEquals(8192.0, registry.get("peegeeq.management.retained_payload.bytes").gauge().value());
        assertEquals(1.0, registry.get("peegeeq.management.buffer.evictions").gauge().value());
        assertEquals(1.0, registry.get("peegeeq.management.stream.resets").gauge().value());
        assertEquals(0.0, registry.get("peegeeq.management.server.ready").gauge().value());
        assertEquals(1.0, registry.get("peegeeq.management.server.lifecycle")
                .tag("state", "RUNNING").counter().count());
        assertEquals(4.0, registry.get("peegeeq.management.shutdown.leaked_resources")
                .counter().count());
        assertEquals(1.0, registry.get("peegeeq.management.postgresql.operations")
                .tags("operation", "database.overview", "outcome", "SUCCEEDED")
                .counter().count());
        assertEquals(1.0, registry.get("peegeeq.management.postgresql.operations")
                .tags("operation", "namespace.list", "outcome", "FAILED")
                .counter().count());
        assertEquals(1, registry.get("peegeeq.management.postgresql.duration")
                .tags("operation", "database.overview", "outcome", "SUCCEEDED")
                .timer().count());
    }
}
