package dev.mars.peegeeq.cache.api.management;

import java.time.Instant;
import java.util.Objects;

/** Immutable, bounded event for the non-authoritative process-local activity view. */
public record ManagementActivityEvent(
        String eventId,
        Instant occurredAt,
        String actor,
        String action,
        ManagementAuditTerminalOutcome outcome,
        String setupId,
        String namespace,
        ManagementActivityResource resource,
        String summary,
        String correlationId) {

    public ManagementActivityEvent {
        eventId = ManagementModelValidation.boundedText(eventId, "eventId", 1, 64, false);
        Objects.requireNonNull(occurredAt, "occurredAt");
        actor = ManagementModelValidation.boundedText(actor, "actor", 1, 256, false);
        action = ManagementModelValidation.boundedText(action, "action", 1, 128, false);
        Objects.requireNonNull(outcome, "outcome");
        setupId = ManagementModelValidation.boundedText(setupId, "setupId", 1, 64, false);
        namespace = namespace == null ? null
                : ManagementModelValidation.boundedText(namespace, "namespace", 1, 128, false);
        Objects.requireNonNull(resource, "resource");
        summary = ManagementModelValidation.boundedText(summary, "summary", 0, 512, false);
        correlationId = ManagementModelValidation.boundedText(
                correlationId, "correlationId", 1, 128, false);
    }
}
