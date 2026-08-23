package dev.mars.peegeeq.cache.rest.protocol;

import dev.mars.peegeeq.cache.api.management.ManagementCounterException;
import dev.mars.peegeeq.cache.rest.security.ManagementAuthenticationException;
import dev.mars.peegeeq.cache.rest.security.ManagementRateLimitException;
import dev.mars.peegeeq.cache.rest.security.ManagementSecurityException;
import dev.mars.peegeeq.cache.rest.server.SetupRegistryException;
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
    void mapsSecurityRateAndRegistryFailuresToTypedSafeProblems() {
        assertProblem(
                new ManagementAuthenticationException(401, "AUTHENTICATION_REQUIRED", "Authentication required"),
                401, "AUTHENTICATION_REQUIRED");
        assertProblem(
                new ManagementSecurityException(403, "CSRF_VALIDATION_FAILED", "CSRF validation failed"),
                403, "CSRF_VALIDATION_FAILED");
        assertProblem(new ManagementRateLimitException(), 429, "RATE_LIMITED");
        assertProblem(
                new SetupRegistryException(404, "SETUP_NOT_FOUND", "Setup not found"),
                404, "SETUP_NOT_FOUND");
        assertProblem(
                new ManagementCounterException(
                        ManagementCounterException.Code.OVERFLOW,
                        new ArithmeticException("secret database expression")),
                409, "COUNTER_OVERFLOW");
    }

    @Test
    void escapesEveryPostgresqlLikeMetacharacterForLiteralPrefixes() {
        assertEquals("customer\\%\\_\\\\active", ManagementWireRules.escapeLikePrefix("customer%_\\active"));
    }

    private static void assertCode(String code, org.junit.jupiter.api.function.Executable executable) {
        ManagementProtocolException exception = assertThrows(ManagementProtocolException.class, executable);
        assertEquals(code, exception.code());
    }

    private static void assertProblem(Throwable failure, int status, String code) {
        ManagementProblem problem = ManagementProblem.from(failure, "/safe/template", "correlation-2");
        assertEquals(status, problem.status());
        assertEquals(code, problem.code());
    }
}
