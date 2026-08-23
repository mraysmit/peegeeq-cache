package dev.mars.peegeeq.cache.rest.security;

import java.time.Instant;
import java.util.Objects;

public record TrustedProxyManagementSession(
        AuthenticatedManagementIdentity identity,
        ManagementSessionCookie cookie,
        String csrfToken,
        Instant idleExpiresAt,
        Instant absoluteExpiresAt) {

    public TrustedProxyManagementSession {
        identity = Objects.requireNonNull(identity, "identity");
        cookie = Objects.requireNonNull(cookie, "cookie");
        csrfToken = Objects.requireNonNull(csrfToken, "csrfToken");
        idleExpiresAt = Objects.requireNonNull(idleExpiresAt, "idleExpiresAt");
        absoluteExpiresAt = Objects.requireNonNull(absoluteExpiresAt, "absoluteExpiresAt");
    }
}
