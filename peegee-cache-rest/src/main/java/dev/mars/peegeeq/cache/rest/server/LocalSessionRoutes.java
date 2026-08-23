package dev.mars.peegeeq.cache.rest.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.mars.peegeeq.cache.rest.protocol.ManagementProblem;
import dev.mars.peegeeq.cache.rest.protocol.ManagementWireRules;
import dev.mars.peegeeq.cache.rest.security.BrowserRequestSecurity;
import dev.mars.peegeeq.cache.rest.security.LocalManagementSession;
import dev.mars.peegeeq.cache.rest.security.LocalTokenSessionManager;
import io.vertx.core.http.HttpServerRequest;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Local-token session routes, including the sole CSRF bootstrap exception. */
public final class LocalSessionRoutes implements ManagementRequestRouter {

    private static final String LOCAL_SESSION_PATH = "/api/v1/session/local";
    private static final String SESSION_PATH = "/api/v1/session";

    private final LocalTokenSessionManager sessions;
    private final BrowserRequestSecurity browserSecurity;
    private final int maximumRequestBytes;
    private final ObjectMapper json = new ObjectMapper();

    public LocalSessionRoutes(
            LocalTokenSessionManager sessions,
            BrowserRequestSecurity browserSecurity,
            int maximumRequestBytes) {
        this.sessions = Objects.requireNonNull(sessions, "sessions");
        this.browserSecurity = Objects.requireNonNull(browserSecurity, "browserSecurity");
        if (maximumRequestBytes < 1) {
            throw new IllegalArgumentException("maximumRequestBytes must be positive");
        }
        this.maximumRequestBytes = maximumRequestBytes;
    }

    @Override
    public boolean route(HttpServerRequest request) {
        String method = request.method().name();
        String path = request.path();
        if (method.equals("POST") && path.equals(LOCAL_SESSION_PATH)) {
            exchange(request);
            return true;
        }
        if (method.equals("DELETE") && path.equals(LOCAL_SESSION_PATH)) {
            logout(request);
            return true;
        }
        if (method.equals("GET") && path.equals(SESSION_PATH)) {
            current(request);
            return true;
        }
        return false;
    }

    private void exchange(HttpServerRequest request) {
        try {
            ManagementWireRules.requireJsonContentType(request.getHeader("content-type"));
            browserSecurity.validateLocalBootstrap(
                    InetAddress.getByName(request.remoteAddress().hostAddress()),
                    request.getHeader("origin"),
                    request.getHeader("content-type"));
            long declaredLength = parseContentLength(request.getHeader("content-length"));
            if (declaredLength >= 0) {
                ManagementWireRules.requireRequestSize(declaredLength, maximumRequestBytes);
            }
        } catch (Throwable failure) {
            writeProblem(request, failure);
            return;
        }
        request.body().onSuccess(body -> {
            try {
                ManagementWireRules.requireRequestSize(body.length(), maximumRequestBytes);
                JsonNode root = json.readTree(body.getBytes());
                requireOnlyToken(root);
                LocalManagementSession session = sessions.exchange(
                        InetAddress.getByName(request.remoteAddress().hostAddress()),
                        root.path("token").asText(null),
                        request.isSSL());
                writeSession(request, session);
            } catch (Throwable failure) {
                writeProblem(request, failure);
            }
        }).onFailure(failure -> writeProblem(request, failure));
    }

    private void current(HttpServerRequest request) {
        try {
            LocalManagementSession session = sessions.authenticate(sessionCookie(request));
            writeSession(request, session);
        } catch (Throwable failure) {
            writeProblem(request, failure);
        }
    }

    private void logout(HttpServerRequest request) {
        try {
            String rawCookie = sessionCookie(request);
            LocalManagementSession session = sessions.authenticate(rawCookie);
            browserSecurity.validateStateChange(
                    rawCookie,
                    request.getHeader("X-PeeGeeQ-CSRF"),
                    session,
                    request.getHeader("Origin"));
            sessions.logout(rawCookie);
            request.response()
                    .setStatusCode(204)
                    .putHeader("cache-control", "no-store")
                    .putHeader("set-cookie", "PGQMGMTSESSION=; Path=/; HttpOnly; SameSite=Strict; Max-Age=0")
                    .end();
        } catch (Throwable failure) {
            writeProblem(request, failure);
        }
    }

    private void writeSession(HttpServerRequest request, LocalManagementSession session) {
        ObjectNode response = json.createObjectNode();
        response.put("user", session.identity().actor());
        ArrayNode roles = response.putArray("roles");
        session.identity().roles().stream().sorted().forEach(roles::add);
        response.put("serverVersion", "0.1.0-SNAPSHOT");
        response.put("apiVersion", "v1");
        response.put("authenticationMode", session.authenticationMode().name());
        response.put("csrfToken", session.csrfToken());
        response.put("sessionIdleExpiresAt", session.idleExpiresAt().toString());
        response.put("sessionExpiresAt", session.absoluteExpiresAt().toString());
        ObjectNode features = response.putObject("features");
        features.put("setupRegistration", true);
        features.put("sensitiveReveal", true);
        String cookie = session.cookie().name() + '=' + session.cookie().value()
                + "; Path=/; HttpOnly; SameSite=Strict"
                + (session.cookie().secure() ? "; Secure" : "");
        request.response()
                .setStatusCode(200)
                .putHeader("content-type", "application/json; charset=utf-8")
                .putHeader("cache-control", "no-store")
                .putHeader("pragma", "no-cache")
                .putHeader("set-cookie", cookie)
                .end(response.toString());
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

    private static void requireOnlyToken(JsonNode root) {
        if (root == null || !root.isObject() || !root.hasNonNull("token")
                || !root.path("token").isTextual()) {
            throw validationFailure();
        }
        Iterator<String> names = root.fieldNames();
        List<String> fields = new ArrayList<>();
        names.forEachRemaining(fields::add);
        if (!fields.equals(List.of("token"))) {
            throw validationFailure();
        }
    }

    private static RuntimeException validationFailure() {
        return new dev.mars.peegeeq.cache.rest.protocol.ManagementProtocolException(
                400, "VALIDATION_FAILED", "Request body does not match the session exchange contract");
    }

    private static String sessionCookie(HttpServerRequest request) {
        List<String> cookieHeaders = request.headers().getAll("Cookie");
        String found = null;
        for (String header : cookieHeaders) {
            for (String part : header.split(";")) {
                String trimmed = part.trim();
                if (trimmed.startsWith("PGQMGMTSESSION=")) {
                    if (found != null) {
                        throw new dev.mars.peegeeq.cache.rest.security.ManagementAuthenticationException(
                                401, "AUTHENTICATION_REQUIRED", "Authentication required");
                    }
                    found = trimmed.substring("PGQMGMTSESSION=".length());
                }
            }
        }
        if (found == null || found.isEmpty()) {
            throw new dev.mars.peegeeq.cache.rest.security.ManagementAuthenticationException(
                    401, "AUTHENTICATION_REQUIRED", "Authentication required");
        }
        return found;
    }

    private static long parseContentLength(String value) {
        if (value == null) {
            return -1;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException failure) {
            throw validationFailure();
        }
    }
}
