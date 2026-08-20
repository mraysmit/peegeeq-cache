package dev.mars.peegeeq.cache.rest.protocol;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ManagementCursorCodecTest {

    private static final byte[] KEY = "0123456789abcdef0123456789abcdef".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    private static final Instant NOW = Instant.parse("2026-08-20T06:00:00Z");

    @Test
    void signedCursorRoundTripsCompleteScopedCompositePosition() {
        ManagementCursorScope scope = new ManagementCursorScope(
                "namespaces", "prod-eu", null,
                Map.of("status", "READY", "prefix", "customer:%_\\"),
                "entryCount:desc,namespace:asc");
        ManagementCursorPosition position = ManagementCursorPosition.entryCount(42, "客户/a");
        ManagementCursorCodec codec = codecAt(NOW);

        String cursor = codec.encode(scope, position);

        assertEquals(position, codec.decode(cursor, scope));
        assertFalse(cursor.contains("customer"));
        assertFalse(cursor.contains("DROP TABLE"));
    }

    @Test
    void rejectsTamperingExpiryWrongScopeUnknownVersionAndMalformedPayloads() {
        ManagementCursorScope scope = new ManagementCursorScope(
                "entries", "prod-eu", "customers", Map.of("prefix", "a"), "key:asc");
        String cursor = codecAt(NOW).encode(scope, ManagementCursorPosition.identifier("a/1"));

        assertCode("INVALID_CURSOR", () -> codecAt(NOW).decode(cursor.substring(0, cursor.length() - 1) + "A", scope));
        assertCode("INVALID_CURSOR", () -> codecAt(NOW.plus(Duration.ofMinutes(16))).decode(cursor, scope));
        assertCode("CURSOR_SCOPE_MISMATCH", () -> codecAt(NOW).decode(cursor,
                new ManagementCursorScope("entries", "other", "customers", Map.of("prefix", "a"), "key:asc")));
        assertCode("INVALID_CURSOR", () -> codecAt(NOW).decode("not-a-cursor", scope));
        assertCode("INVALID_CURSOR", () -> codecAt(NOW).decode(
                ManagementCursorCodecTestTokens.withVersion(cursor, KEY, 99), scope));
    }

    private static ManagementCursorCodec codecAt(Instant instant) {
        return new ManagementCursorCodec(KEY, Clock.fixed(instant, ZoneOffset.UTC), Duration.ofMinutes(15));
    }

    private static void assertCode(String code, org.junit.jupiter.api.function.Executable executable) {
        ManagementProtocolException exception = assertThrows(ManagementProtocolException.class, executable);
        assertEquals(code, exception.code());
    }
}
