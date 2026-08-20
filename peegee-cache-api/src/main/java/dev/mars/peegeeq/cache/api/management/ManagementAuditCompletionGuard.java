package dev.mars.peegeeq.cache.api.management;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/** Reusable idempotency guard for audit sink terminal completion implementations. */
public final class ManagementAuditCompletionGuard {
    private final ConcurrentHashMap<String, Completion> completions = new ConcurrentHashMap<>();

    public boolean complete(ManagementAuditReservation reservation, ManagementAuditOutcome outcome) {
        Objects.requireNonNull(reservation, "reservation");
        Objects.requireNonNull(outcome, "outcome");
        Completion requested = new Completion(reservation, outcome);
        Completion existing = completions.putIfAbsent(reservation.reservationId(), requested);
        if (existing == null) {
            return true;
        }
        if (existing.equals(requested)) {
            return false;
        }
        throw new ManagementAuditConflictException(
                "Audit reservation already has a different terminal outcome");
    }

    private record Completion(ManagementAuditReservation reservation, ManagementAuditOutcome outcome) {
    }
}
