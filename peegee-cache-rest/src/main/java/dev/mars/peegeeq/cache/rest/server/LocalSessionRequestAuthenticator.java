package dev.mars.peegeeq.cache.rest.server;

import dev.mars.peegeeq.cache.rest.security.LocalManagementSession;
import dev.mars.peegeeq.cache.rest.security.LocalTokenSessionManager;
import dev.mars.peegeeq.cache.rest.security.ManagementAuthenticationException;
import dev.mars.peegeeq.cache.rest.security.ManagementSessionCookie;
import io.vertx.core.http.HttpServerRequest;

import java.util.Objects;

/** Authenticates protected requests against the active local-token browser session. */
public final class LocalSessionRequestAuthenticator implements ManagementRequestAuthenticator {

    private final LocalTokenSessionManager sessions;

    public LocalSessionRequestAuthenticator(LocalTokenSessionManager sessions) {
        this.sessions = Objects.requireNonNull(sessions, "sessions");
    }

    @Override
    public AuthenticatedManagementRequest authenticate(HttpServerRequest request) {
        String cookie = requiredSessionCookie(request);
        LocalManagementSession session = sessions.authenticate(cookie);
        return new AuthenticatedManagementRequest(session.identity(), cookie, session.csrfToken());
    }

    static String requiredSessionCookie(HttpServerRequest request) {
        String found = null;
        String prefix = ManagementSessionCookie.COOKIE_NAME + '=';
        for (String header : request.headers().getAll("Cookie")) {
            for (String part : header.split(";")) {
                String trimmed = part.trim();
                if (trimmed.startsWith(prefix)) {
                    if (found != null) {
                        throw authenticationRequired();
                    }
                    found = trimmed.substring(prefix.length());
                }
            }
        }
        if (found == null || found.isEmpty()) {
            throw authenticationRequired();
        }
        return found;
    }

    private static ManagementAuthenticationException authenticationRequired() {
        return new ManagementAuthenticationException(
                401, "AUTHENTICATION_REQUIRED", "Authentication required");
    }
}
