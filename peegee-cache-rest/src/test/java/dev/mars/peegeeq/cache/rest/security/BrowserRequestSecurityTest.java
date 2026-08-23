package dev.mars.peegeeq.cache.rest.security;

import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BrowserRequestSecurityTest {

    @Test
    void localModeAllowsOnlyExactSameOriginAndDisablesCors() {
        BrowserOriginPolicy policy = BrowserOriginPolicy.localToken("http://127.0.0.1:8080");

        assertTrue(policy.isAllowed("http://127.0.0.1:8080"));
        assertFalse(policy.corsEnabled());
        assertSecurityCode("ORIGIN_VALIDATION_FAILED",
                () -> policy.requireAllowed("http://localhost:8080"));
        assertSecurityCode("ORIGIN_VALIDATION_FAILED",
                () -> policy.requireAllowed("http://127.0.0.1:8080.evil.example"));
    }

    @Test
    void trustedProxyModeUsesExactHttpsAllowlistWithoutReflectionOrWildcards() {
        BrowserOriginPolicy policy = BrowserOriginPolicy.trustedProxy(
                "https://admin.internal.example", Set.of("https://console.example.com"));

        assertTrue(policy.corsEnabled());
        assertEquals("https://console.example.com",
                policy.allowCredentialedOrigin("https://console.example.com").orElseThrow());
        assertTrue(policy.allowCredentialedOrigin("https://evil.example").isEmpty());
        assertSecurityCode("ORIGIN_VALIDATION_FAILED",
                () -> policy.requireAllowed("https://console.example.com.evil.test"));
        assertThrows(IllegalArgumentException.class,
                () -> BrowserOriginPolicy.trustedProxy(
                        "https://admin.internal.example", Set.of("*")));
        assertThrows(IllegalArgumentException.class,
                () -> BrowserOriginPolicy.trustedProxy(
                        "https://admin.internal.example", Set.of("http://console.example.com")));
    }

    @Test
    void initialBootstrapExceptionIsNarrowlyConstrained() throws Exception {
        BrowserOriginPolicy policy = BrowserOriginPolicy.localToken("http://127.0.0.1:8080");
        BrowserRequestSecurity security = new BrowserRequestSecurity(policy);

        security.validateLocalBootstrap(
                InetAddress.getByName("127.0.0.1"),
                "http://127.0.0.1:8080",
                "application/json; charset=utf-8");

        assertSecurityCode("CSRF_VALIDATION_FAILED", () -> security.validateLocalBootstrap(
                InetAddress.getByName("10.0.0.4"),
                "http://127.0.0.1:8080",
                "application/json"));
        assertSecurityCode("CSRF_VALIDATION_FAILED", () -> security.validateLocalBootstrap(
                InetAddress.getLoopbackAddress(),
                "http://other.example",
                "application/json"));
        assertSecurityCode("CSRF_VALIDATION_FAILED", () -> security.validateLocalBootstrap(
                InetAddress.getLoopbackAddress(),
                "http://127.0.0.1:8080",
                "text/plain"));
    }

    @Test
    void everyOtherStateChangeRequiresMatchingCookieCsrfAndOrigin() throws Exception {
        BrowserOriginPolicy policy = BrowserOriginPolicy.localToken("http://127.0.0.1:8080");
        BrowserRequestSecurity security = new BrowserRequestSecurity(policy);
        LocalTokenBootstrap bootstrap = LocalTokenSessionManager.start(
                LocalTokenAuthenticationConfig.defaults(),
                Clock.fixed(Instant.parse("2026-08-22T12:00:00Z"), ZoneOffset.UTC),
                new SequenceEntropy());
        LocalManagementSession session = bootstrap.manager().exchange(
                InetAddress.getLoopbackAddress(), bootstrap.token(), false);

        security.validateStateChange(
                session.cookie().value(), session.csrfToken(), session, "http://127.0.0.1:8080");

        assertSecurityCode("CSRF_VALIDATION_FAILED", () -> security.validateStateChange(
                "wrong", session.csrfToken(), session, "http://127.0.0.1:8080"));
        assertSecurityCode("CSRF_VALIDATION_FAILED", () -> security.validateStateChange(
                session.cookie().value(), "wrong", session, "http://127.0.0.1:8080"));
        assertSecurityCode("ORIGIN_VALIDATION_FAILED", () -> security.validateStateChange(
                session.cookie().value(), session.csrfToken(), session, "http://evil.example"));
    }

    @Test
    void trustedProxySessionIsBoundToIdentityRolesAndSourceAndRotatesOnChange() {
        TrustedProxySessionManager sessions = new TrustedProxySessionManager(
                Duration.ofMinutes(30), Duration.ofHours(8),
                Clock.fixed(Instant.parse("2026-08-22T12:00:00Z"), ZoneOffset.UTC),
                new SequenceEntropy());
        AuthenticatedManagementIdentity initial = new AuthenticatedManagementIdentity(
                "alex", Set.of("viewer"), "10.24.1.7");

        TrustedProxyManagementSession first = sessions.createOrRefresh(initial, null, true);
        TrustedProxyManagementSession refreshed = sessions.createOrRefresh(
                initial, first.cookie().value(), true);
        assertEquals(first.cookie().value(), refreshed.cookie().value());

        AuthenticatedManagementIdentity elevated = new AuthenticatedManagementIdentity(
                "alex", Set.of("viewer", "operator"), "10.24.1.7");
        TrustedProxyManagementSession rotated = sessions.createOrRefresh(
                elevated, first.cookie().value(), true);
        assertNotEquals(first.cookie().value(), rotated.cookie().value());
        ManagementAuthenticationException invalidated = assertThrows(
                ManagementAuthenticationException.class,
                () -> sessions.authenticate(first.cookie().value(), elevated));
        assertEquals("AUTHENTICATION_REQUIRED", invalidated.code());
        assertEquals(rotated.cookie().value(),
                sessions.authenticate(rotated.cookie().value(), elevated).cookie().value());
    }

    @Test
    void rejectsOriginWithPathQueryCredentialsOrMissingScheme() {
        assertThrows(IllegalArgumentException.class,
                () -> BrowserOriginPolicy.localToken("https://example.com/path"));
        assertThrows(IllegalArgumentException.class,
                () -> BrowserOriginPolicy.localToken("https://example.com?x=1"));
        assertThrows(IllegalArgumentException.class,
                () -> BrowserOriginPolicy.localToken("https://user@example.com"));
        assertThrows(IllegalArgumentException.class,
                () -> BrowserOriginPolicy.localToken("example.com"));
    }

    private static void assertSecurityCode(String code, ThrowingRunnable action) {
        ManagementSecurityException failure = assertThrows(ManagementSecurityException.class, action::run);
        assertEquals(code, failure.code());
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    private static final class SequenceEntropy implements TokenEntropy {
        private int next = 11;

        @Override
        public byte[] nextBytes(int size) {
            byte[] result = new byte[size];
            Arrays.fill(result, (byte) next++);
            return result;
        }
    }
}
