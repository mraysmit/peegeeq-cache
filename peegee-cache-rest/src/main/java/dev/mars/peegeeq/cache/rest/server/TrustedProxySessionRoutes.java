package dev.mars.peegeeq.cache.rest.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.mars.peegeeq.cache.rest.protocol.ManagementProblem;
import dev.mars.peegeeq.cache.rest.protocol.ManagementWireRules;
import dev.mars.peegeeq.cache.rest.security.AuthenticatedManagementIdentity;
import dev.mars.peegeeq.cache.rest.security.BrowserOriginPolicy;
import dev.mars.peegeeq.cache.rest.security.ManagementAuthenticationException;
import dev.mars.peegeeq.cache.rest.security.TrustedProxyAuthenticator;
import dev.mars.peegeeq.cache.rest.security.TrustedProxyManagementSession;
import dev.mars.peegeeq.cache.rest.security.TrustedProxySessionManager;
import io.vertx.core.http.HttpServerRequest;

import java.net.InetAddress;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Creates and refreshes identity-bound browser sessions behind a trusted reverse proxy. */
public final class TrustedProxySessionRoutes implements ManagementRequestRouter {

    private static final String SESSION_PATH = "/api/v1/session";

    private final TrustedProxyAuthenticator authenticator;
    private final TrustedProxySessionManager sessions;
    private final BrowserOriginPolicy origins;
    private final ObjectMapper json = new ObjectMapper();

    public TrustedProxySessionRoutes(
            TrustedProxyAuthenticator authenticator,
            TrustedProxySessionManager sessions,
            BrowserOriginPolicy origins) {
        this.authenticator = Objects.requireNonNull(authenticator, "authenticator");
        this.sessions = Objects.requireNonNull(sessions, "sessions");
        this.origins = Objects.requireNonNull(origins, "origins");
    }

    @Override
    public boolean route(HttpServerRequest request) {
        if (!request.method().name().equals("GET") || !request.path().equals(SESSION_PATH)) {
            return false;
        }
        try {
            String origin = request.getHeader("Origin");
            if (origin != null) {
                origins.requireAllowed(origin);
            }
            AuthenticatedManagementIdentity identity = authenticator.authenticate(
                    InetAddress.getByName(request.remoteAddress().hostAddress()), request.headers());
            TrustedProxyManagementSession session = sessions.createOrRefresh(
                    identity, optionalSessionCookie(request), true);
            writeSession(request, session, origin);
        } catch (Throwable failure) {
            writeProblem(request, failure);
        }
        return true;
    }

    private void writeSession(
            HttpServerRequest request,
            TrustedProxyManagementSession session,
            String origin) {
        ObjectNode response = json.createObjectNode();
        response.put("user", session.identity().actor());
        ArrayNode roles = response.putArray("roles");
        session.identity().roles().stream().sorted().forEach(roles::add);
        response.put("serverVersion", "0.1.0-SNAPSHOT");
        response.put("apiVersion", "v1");
        response.put("authenticationMode", "TRUSTED_PROXY");
        response.put("csrfToken", session.csrfToken());
        response.put("sessionIdleExpiresAt", session.idleExpiresAt().toString());
        response.put("sessionExpiresAt", session.absoluteExpiresAt().toString());
        String cookie = session.cookie().name() + '=' + session.cookie().value()
                + "; Path=/; HttpOnly; Secure; SameSite=Strict";
        var httpResponse = request.response()
                .setStatusCode(200)
                .putHeader("content-type", "application/json; charset=utf-8")
                .putHeader("cache-control", "no-store")
                .putHeader("pragma", "no-cache")
                .putHeader("set-cookie", cookie);
        if (origin != null) {
            origins.allowCredentialedOrigin(origin).ifPresent(allowed -> httpResponse
                    .putHeader("access-control-allow-origin", allowed)
                    .putHeader("access-control-allow-credentials", "true")
                    .putHeader("vary", "Origin"));
        }
        httpResponse.end(response.toString());
    }

    private void writeProblem(HttpServerRequest request, Throwable failure) {
        String correlationId = ManagementWireRules.correlationId(
                request.getHeader("X-Correlation-ID"), () -> UUID.randomUUID().toString());
        ManagementProblem problem = ManagementProblem.from(failure, request.path(), correlationId);
        ObjectNode response = json.createObjectNode();
        response.put("type", problem.type().toString());
        response.put("title", problem.title());
        response.put("status", problem.status());
        response.put("code", problem.code());
        response.put("detail", problem.detail());
        response.put("instance", problem.instance());
        response.put("correlationId", problem.correlationId());
        response.putArray("fieldErrors");
        request.response()
                .setStatusCode(problem.status())
                .putHeader(ManagementHttpServer.ERROR_CODE_HEADER, problem.code())
                .putHeader("content-type", "application/problem+json; charset=utf-8")
                .putHeader("cache-control", "no-store")
                .end(response.toString());
    }

    private static String optionalSessionCookie(HttpServerRequest request) {
        String found = null;
        for (String header : request.headers().getAll("Cookie")) {
            for (String part : header.split(";")) {
                String trimmed = part.trim();
                if (trimmed.startsWith("PGQMGMTSESSION=")) {
                    if (found != null) {
                        throw new ManagementAuthenticationException(
                                401, "AUTHENTICATION_REQUIRED", "Authentication required");
                    }
                    found = trimmed.substring("PGQMGMTSESSION=".length());
                }
            }
        }
        return found == null || found.isEmpty() ? null : found;
    }
}
