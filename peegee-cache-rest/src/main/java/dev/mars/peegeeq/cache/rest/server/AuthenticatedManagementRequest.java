package dev.mars.peegeeq.cache.rest.server;

import dev.mars.peegeeq.cache.rest.security.AuthenticatedManagementIdentity;

import java.util.Objects;

/** Identity and browser-session proof established before route authorization. */
public record AuthenticatedManagementRequest(
        AuthenticatedManagementIdentity identity,
        String sessionCookie,
        String csrfToken) {

    public AuthenticatedManagementRequest {
        identity = Objects.requireNonNull(identity, "identity");
        sessionCookie = Objects.requireNonNull(sessionCookie, "sessionCookie");
        csrfToken = Objects.requireNonNull(csrfToken, "csrfToken");
    }
}
