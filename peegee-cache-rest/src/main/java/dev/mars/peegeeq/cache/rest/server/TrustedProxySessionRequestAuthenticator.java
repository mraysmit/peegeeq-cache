package dev.mars.peegeeq.cache.rest.server;

import dev.mars.peegeeq.cache.rest.security.AuthenticatedManagementIdentity;
import dev.mars.peegeeq.cache.rest.security.TrustedProxyManagementSession;
import dev.mars.peegeeq.cache.rest.security.TrustedProxyAuthenticator;
import dev.mars.peegeeq.cache.rest.security.TrustedProxySessionManager;
import io.vertx.core.http.HttpServerRequest;

import java.net.InetAddress;
import java.util.Objects;

/** Revalidates the authoritative proxy identity and its bound browser session on every request. */
public final class TrustedProxySessionRequestAuthenticator implements ManagementRequestAuthenticator {

    private final TrustedProxyAuthenticator authenticator;
    private final TrustedProxySessionManager sessions;

    public TrustedProxySessionRequestAuthenticator(
            TrustedProxyAuthenticator authenticator,
            TrustedProxySessionManager sessions) {
        this.authenticator = Objects.requireNonNull(authenticator, "authenticator");
        this.sessions = Objects.requireNonNull(sessions, "sessions");
    }

    @Override
    public AuthenticatedManagementRequest authenticate(HttpServerRequest request) {
        try {
            AuthenticatedManagementIdentity identity = authenticator.authenticate(
                    InetAddress.getByName(request.remoteAddress().hostAddress()), request.headers());
            String cookie = LocalSessionRequestAuthenticator.requiredSessionCookie(request);
            TrustedProxyManagementSession session = sessions.authenticate(cookie, identity);
            return new AuthenticatedManagementRequest(session.identity(), cookie, session.csrfToken());
        } catch (java.net.UnknownHostException failure) {
            throw new IllegalStateException("Immediate peer address is invalid", failure);
        }
    }
}
