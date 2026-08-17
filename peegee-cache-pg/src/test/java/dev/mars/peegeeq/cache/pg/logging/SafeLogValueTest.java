package dev.mars.peegeeq.cache.pg.logging;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SafeLogValueTest {

    @Test
    void fingerprintsIdentifiersWithoutExposingTheirRawValue() {
        String rawIdentifier = "tenant@example.com:customer-4182";

        String safe = SafeLogValue.identifier(rawIdentifier);

        assertTrue(safe.startsWith("id:sha256:"));
        assertFalse(safe.contains(rawIdentifier));
        assertFalse(safe.contains("tenant@example.com"));
        assertEquals(safe, SafeLogValue.identifier(rawIdentifier));
        assertNotEquals(safe, SafeLogValue.identifier("tenant@example.com:customer-4183"));
    }

    @Test
    void representsNullIdentifiersWithoutThrowing() {
        assertEquals("id:null", SafeLogValue.identifier(null));
    }

    @Test
    void secretMarkerCannotContainTheSecret() {
        String ownerToken = "owner-token-that-must-never-be-logged";

        String safe = SafeLogValue.secret();

        assertEquals("[REDACTED]", safe);
        assertFalse(safe.contains(ownerToken));
    }
}
