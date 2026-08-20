package dev.mars.peegeeq.cache.api.management;

/** Opaque durable-intent reservation with non-secret sink generation metadata. */
public record ManagementAuditReservation(String reservationId, String eventId, String sinkGeneration) {
    public ManagementAuditReservation {
        reservationId = ManagementModelValidation.boundedText(
                reservationId, "reservationId", 1, 256, false);
        eventId = ManagementModelValidation.boundedText(eventId, "eventId", 1, 128, false);
        sinkGeneration = ManagementModelValidation.boundedText(
                sinkGeneration, "sinkGeneration", 1, 128, false);
    }
}
