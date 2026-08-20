package dev.mars.peegeeq.cache.api.management;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Default bounded audit intent; user-controlled identifiers appear only as keyed fingerprints. */
public record ManagementAuditIntent(
        String eventId,
        Instant occurredAt,
        String actor,
        Set<String> roles,
        ManagementAuditAction action,
        String setupId,
        ManagementResourceType resourceType,
        Map<String, ManagementAuditFingerprint> identifierFingerprints,
        Long expectedVersion,
        String reason,
        String sourceAddress,
        String correlationId) {
    public ManagementAuditIntent {
        eventId = ManagementModelValidation.boundedText(eventId, "eventId", 1, 128, false);
        Objects.requireNonNull(occurredAt, "occurredAt");
        actor = ManagementModelValidation.boundedText(actor, "actor", 1, 128, true);
        roles = Set.copyOf(Objects.requireNonNull(roles, "roles"));
        if (roles.isEmpty() || roles.size() > 16) {
            throw new IllegalArgumentException("roles must contain between 1 and 16 values");
        }
        Objects.requireNonNull(action, "action");
        setupId = ManagementModelValidation.boundedText(setupId, "setupId", 1, 64, false);
        Objects.requireNonNull(resourceType, "resourceType");
        identifierFingerprints = Map.copyOf(Objects.requireNonNull(
                identifierFingerprints, "identifierFingerprints"));
        if (identifierFingerprints.size() > 16) {
            throw new IllegalArgumentException("identifierFingerprints must contain at most 16 values");
        }
        identifierFingerprints.forEach((name, fingerprint) -> {
            ManagementModelValidation.boundedText(name, "fingerprint name", 1, 64, false);
            Objects.requireNonNull(fingerprint, "fingerprint");
        });
        if (expectedVersion != null) {
            ManagementModelValidation.nonNegativeVersion(expectedVersion, "expectedVersion");
        }
        reason = ManagementModelValidation.optionalReason(reason);
        sourceAddress = ManagementModelValidation.boundedText(
                sourceAddress, "sourceAddress", 1, 128, true);
        correlationId = ManagementModelValidation.boundedText(
                correlationId, "correlationId", 1, 128, false);
    }
}
