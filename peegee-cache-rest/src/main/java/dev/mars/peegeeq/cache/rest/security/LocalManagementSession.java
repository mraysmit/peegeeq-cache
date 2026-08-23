package dev.mars.peegeeq.cache.rest.security;

import java.time.Instant;
import java.util.Objects;

public record LocalManagementSession(
        AuthenticatedManagementIdentity identity,
        ManagementAuthenticationMode authenticationMode,
        ManagementSessionCookie cookie,
        String csrfToken,
        Instant idleExpiresAt,
        Instant absoluteExpiresAt) {

    public LocalManagementSession {
        identity = Objects.requireNonNull(identity, "identity");
        authenticationMode = Objects.requireNonNull(authenticationMode, "authenticationMode");
        cookie = Objects.requireNonNull(cookie, "cookie");
        csrfToken = Objects.requireNonNull(csrfToken, "csrfToken");
        idleExpiresAt = Objects.requireNonNull(idleExpiresAt, "idleExpiresAt");
        absoluteExpiresAt = Objects.requireNonNull(absoluteExpiresAt, "absoluteExpiresAt");
    }
}
