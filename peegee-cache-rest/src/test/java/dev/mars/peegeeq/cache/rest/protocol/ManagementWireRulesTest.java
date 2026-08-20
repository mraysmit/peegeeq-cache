package dev.mars.peegeeq.cache.rest.protocol;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ManagementWireRulesTest {

    @Test
    void serializesSigned64BitValuesAndUtcInstantsWithoutPrecisionLoss() {
        assertEquals("9223372036854775807", ManagementWireRules.decimalString(Long.MAX_VALUE));
        assertEquals(Long.MIN_VALUE, ManagementWireRules.parseDecimalString("-9223372036854775808"));
        assertEquals("2026-08-20T06:07:08.123Z",
                ManagementWireRules.utcTimestamp(Instant.parse("2026-08-20T06:07:08.123Z")));
    }

    @Test
    void appliesStrictContentSizeJsonAndCorrelationRules() {
        ManagementWireRules.requireJsonContentType("application/json; charset=utf-8");
        ManagementWireRules.requireRequestSize(1024, 1024);
        ManagementWireRules.requireValidJson("{\"ok\":true}");
        assertEquals("client-42", ManagementWireRules.correlationId("client-42", () -> "generated"));
        assertEquals("generated", ManagementWireRules.correlationId("bad\nvalue", () -> "generated"));

        assertCode("UNSUPPORTED_MEDIA_TYPE",
                () -> ManagementWireRules.requireJsonContentType("text/plain"));
        assertCode("REQUEST_TOO_LARGE", () -> ManagementWireRules.requireRequestSize(1025, 1024));
        assertCode("VALIDATION_FAILED", () -> ManagementWireRules.requireValidJson("{broken"));
    }

    @Test
    void problemDetailsRedactUnknownExceptionMessages() {
        RuntimeException failure = new RuntimeException("password=hunter2 SQL=select secret_value");

        ManagementProblem problem = ManagementProblem.from(
                failure, "/api/v1/setups/prod/entries/key", "correlation-1");

        assertEquals(500, problem.status());
        assertEquals("INTERNAL_ERROR", problem.code());
        assertFalse(problem.detail().contains("hunter2"));
        assertFalse(problem.detail().contains("select"));
        assertTrue(problem.type().toString().endsWith("internal-error"));
    }

    @Test
    void escapesEveryPostgresqlLikeMetacharacterForLiteralPrefixes() {
        assertEquals("customer\\%\\_\\\\active", ManagementWireRules.escapeLikePrefix("customer%_\\active"));
    }

    private static void assertCode(String code, org.junit.jupiter.api.function.Executable executable) {
        ManagementProtocolException exception = assertThrows(ManagementProtocolException.class, executable);
        assertEquals(code, exception.code());
    }
}
