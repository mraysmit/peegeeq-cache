package dev.mars.peegeeq.cache.rest.audit;

import dev.mars.peegeeq.cache.api.management.ManagementAuditTerminalOutcome;

/**
 * Optional audit health telemetry. Implementations must never be treated as the
 * authoritative security audit sink and may fail without changing audit results.
 */
public interface ManagementAuditTelemetry {

    ManagementAuditTelemetry NOOP = new ManagementAuditTelemetry() { };

    default void reservationAccepted(int pendingReservations, int capacity) {
    }

    default void reservationRejected() {
    }

    default void outcomePersisted(
            ManagementAuditTerminalOutcome outcome,
            int pendingReservations) {
    }

    default void readinessChanged(boolean mutationReady) {
    }

    default void incompleteIntentsRecovered(int count) {
    }

    default void persistenceFailed(AuditPersistenceOperation operation) {
    }
}
