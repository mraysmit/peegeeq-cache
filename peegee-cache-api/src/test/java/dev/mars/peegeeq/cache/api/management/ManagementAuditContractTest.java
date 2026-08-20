package dev.mars.peegeeq.cache.api.management;

import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.time.Instant;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ManagementAuditContractTest {

    @Test
    void fingerprinterUsesVersionedAtLeast128BitHmacFromSecretReference() {
        ManagementSecretReference reference = new ManagementSecretReference("audit-key-current");
        ManagementAuditFingerprinter first = new ManagementAuditFingerprinter(
                reference, ignored -> "0123456789abcdef0123456789abcdef".getBytes(), "key-2026-08", 128);
        ManagementAuditFingerprinter second = new ManagementAuditFingerprinter(
                reference, ignored -> "abcdef0123456789abcdef0123456789".getBytes(), "key-2026-09", 128);

        ManagementAuditFingerprint a = first.fingerprint("customers/42");
        ManagementAuditFingerprint repeat = first.fingerprint("customers/42");
        ManagementAuditFingerprint rotated = second.fingerprint("customers/42");

        assertEquals(a, repeat);
        assertEquals("HMAC-SHA-256/128/V1", a.algorithmVersion());
        assertEquals(22, a.digest().length());
        assertNotEquals(a.digest(), rotated.digest());
        assertThrows(IllegalArgumentException.class, () -> new ManagementAuditFingerprinter(
                reference, ignored -> new byte[32], "key", 120));
    }

    @Test
    void defaultAuditIntentHasNoRawSensitiveOrIdentifierFields() {
        Set<String> forbidden = Set.of("namespace", "key", "channel", "prefix", "cursor", "value", "payload",
                "owner", "password", "authorization", "credential", "secret");

        for (RecordComponent component : ManagementAuditIntent.class.getRecordComponents()) {
            assertFalse(forbidden.contains(component.getName().toLowerCase()), component.getName());
        }

        ManagementAuditIntent intent = new ManagementAuditIntent(
                "event-1", Instant.parse("2026-08-20T06:00:00Z"), "operator", Set.of("operator"),
                ManagementAuditAction.SET_ENTRY, "prod", ManagementResourceType.ENTRY,
                Map.of("resource", new ManagementAuditFingerprint("HMAC-SHA-256/128/V1", "key-1", "a".repeat(22))),
                4L, "approved change", "127.0.0.1", "correlation-1");
        assertEquals(ManagementAuditAction.SET_ENTRY, intent.action());
        assertTrue(intent.identifierFingerprints().containsKey("resource"));
    }

    @Test
    void reservationCarriesOnlyOpaqueIdentifiersAndNonSecretGeneration() {
        assertEquals(Set.of("reservationId", "eventId", "sinkGeneration"),
                Arrays.stream(ManagementAuditReservation.class.getRecordComponents())
                        .map(RecordComponent::getName).collect(java.util.stream.Collectors.toSet()));
    }

    @Test
    void terminalCompletionIsIdempotentForSameOutcomeAndRejectsConflict() {
        ManagementAuditCompletionGuard guard = new ManagementAuditCompletionGuard();
        ManagementAuditReservation reservation = new ManagementAuditReservation("reservation-1", "event-1", "gen-1");
        ManagementAuditOutcome succeeded = new ManagementAuditOutcome(
                ManagementAuditTerminalOutcome.SUCCEEDED, "APPLIED", 5L);

        assertTrue(guard.complete(reservation, succeeded));
        assertFalse(guard.complete(reservation, succeeded));
        assertThrows(ManagementAuditConflictException.class, () -> guard.complete(reservation,
                new ManagementAuditOutcome(ManagementAuditTerminalOutcome.FAILED, "DATABASE_UNAVAILABLE", null)));
    }

    @Test
    void securityAuditFailuresRemainDistinctFromOptionalTelemetryFailures() {
        assertFalse(ManagementAuditException.class.isAssignableFrom(ManagementTelemetryException.class));
        assertFalse(ManagementTelemetryException.class.isAssignableFrom(ManagementAuditException.class));
    }
}
