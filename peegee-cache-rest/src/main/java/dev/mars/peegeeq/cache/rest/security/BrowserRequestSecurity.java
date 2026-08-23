package dev.mars.peegeeq.cache.rest.security;

import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.Objects;

/** Stateless browser request checks shared by REST, SSE, and WebSocket entry points. */
public final class BrowserRequestSecurity {

    private final BrowserOriginPolicy originPolicy;

    public BrowserRequestSecurity(BrowserOriginPolicy originPolicy) {
        this.originPolicy = Objects.requireNonNull(originPolicy, "originPolicy");
    }

    public void validateLocalBootstrap(InetAddress peer, String origin, String contentType) {
        Objects.requireNonNull(peer, "peer");
        if (!peer.isLoopbackAddress()
                || !originPolicy.serverOrigin().equals(normalizedAllowedOrigin(origin))
                || !isJson(contentType)
                || originPolicy.corsEnabled()) {
            throw csrfFailure();
        }
    }

    public void validateStateChange(
            String suppliedCookie,
            String suppliedCsrf,
            LocalManagementSession session,
            String origin) {
        Objects.requireNonNull(session, "session");
        validateStateChange(
                suppliedCookie,
                suppliedCsrf,
                session.cookie().value(),
                session.csrfToken(),
                origin);
    }

    public void validateStateChange(
            String suppliedCookie,
            String suppliedCsrf,
            String expectedCookie,
            String expectedCsrf,
            String origin) {
        originPolicy.requireAllowed(origin);
        if (!constantTimeEquals(suppliedCookie, expectedCookie)
                || !constantTimeEquals(suppliedCsrf, expectedCsrf)) {
            throw csrfFailure();
        }
    }

    public void validateStreamOrigin(String origin) {
        originPolicy.requireAllowed(origin);
    }

    private String normalizedAllowedOrigin(String origin) {
        if (!originPolicy.isAllowed(origin)) {
            return null;
        }
        return originPolicy.serverOrigin();
    }

    private static boolean isJson(String contentType) {
        if (contentType == null) {
            return false;
        }
        String mediaType = contentType.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
        return mediaType.equals("application/json");
    }

    static boolean constantTimeEquals(String first, String second) {
        if (first == null || second == null) {
            return false;
        }
        return MessageDigest.isEqual(
                first.getBytes(StandardCharsets.UTF_8),
                second.getBytes(StandardCharsets.UTF_8));
    }

    private static ManagementSecurityException csrfFailure() {
        return new ManagementSecurityException(
                403, "CSRF_VALIDATION_FAILED", "CSRF validation failed");
    }
}
