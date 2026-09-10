package dev.mars.peegeeq.cache.rest.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.mars.peegeeq.cache.rest.protocol.ManagementProblem;
import dev.mars.peegeeq.cache.rest.protocol.ManagementProtocolException;
import dev.mars.peegeeq.cache.rest.protocol.ManagementWireRules;
import dev.mars.peegeeq.cache.rest.security.AuthenticatedManagementIdentity;
import dev.mars.peegeeq.cache.rest.security.ManagementSecurityException;
import io.vertx.core.http.HttpServerRequest;

import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/** Authenticated safe setup discovery routes. */
public final class SetupReadRoutes implements ManagementRequestRouter {

    private static final String SETUPS_PATH = "/api/v1/setups";
    private static final String SETUP_PATH_PREFIX = SETUPS_PATH + "/";
    private static final Pattern SETUP_ID = Pattern.compile("[a-z][a-z0-9-]{0,62}");

    private final SetupRegistry registry;
    private final ManagementRequestAuthenticator authenticator;
    private final ObjectMapper json = new ObjectMapper();

    public SetupReadRoutes(SetupRegistry registry, ManagementRequestAuthenticator authenticator) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.authenticator = Objects.requireNonNull(authenticator, "authenticator");
    }

    @Override
    public boolean route(HttpServerRequest request) {
        if (!request.method().name().equals("GET")) {
            return false;
        }
        RouteMatch match = match(request.path());
        if (match == null) {
            return false;
        }
        String correlationId = correlationId(request);
        try {
            AuthenticatedManagementIdentity identity = authenticator.authenticate(request).identity();
            requireViewer(identity);
            if (match.kind() == RouteKind.LIST) {
                ObjectNode body = json.createObjectNode();
                ArrayNode items = body.putArray("items");
                registry.list().forEach(summary -> items.add(summary(summary)));
                writeJson(request, body, correlationId);
                return true;
            }
            requireCanonicalSetupId(match.setupId());
            if (match.kind() == RouteKind.DETAILS) {
                writeJson(request, details(
                        registry.details(match.setupId()), registry.limits(match.setupId())), correlationId);
            } else {
                registry.health(match.setupId())
                        .onSuccess(health -> writeJson(request, health(health), correlationId))
                        .onFailure(failure -> writeProblem(request, failure, correlationId));
            }
        } catch (Throwable failure) {
            writeProblem(request, failure, correlationId);
        }
        return true;
    }

    private void writeJson(HttpServerRequest request, ObjectNode body, String correlationId) {
        request.response()
                .setStatusCode(200)
                .putHeader("content-type", "application/json; charset=utf-8")
                .putHeader("cache-control", "no-store")
                .putHeader("x-correlation-id", correlationId)
                .end(body.toString());
    }

    private ObjectNode details(SetupDetails details, SetupLimits limits) {
        ObjectNode node = json.createObjectNode();
        node.set("setup", summary(details.setup()));
        node.put("migrationVersion", details.migrationVersion());
        ObjectNode limitsNode = node.putObject("limits");
        limitsNode.put("pubSubChannelMaxBytes", limits.pubSubChannelMaxBytes());
        limitsNode.put("pubSubPayloadMaxBytes", limits.pubSubPayloadMaxBytes());
        limitsNode.put("maximumValueBytes", limits.maximumValueBytes());
        ObjectNode runtime = node.putObject("runtime");
        if (details.runtime().defaultTtlMillis() == null) {
            runtime.putNull("defaultTtlMillis");
        } else {
            runtime.put("defaultTtlMillis", details.runtime().defaultTtlMillis());
        }
        runtime.put("expirySweeperEnabled", details.runtime().expirySweeperEnabled());
        runtime.put("expirySweepIntervalMillis", details.runtime().expirySweepIntervalMillis());
        runtime.put("expirySweepBatchSize", details.runtime().expirySweepBatchSize());
        runtime.put("writeBehindEnabled", details.runtime().writeBehindEnabled());
        runtime.put("writeBehindFlushIntervalMillis", details.runtime().writeBehindFlushIntervalMillis());
        runtime.put("writeBehindMaxBufferSize", details.runtime().writeBehindMaxBufferSize());
        runtime.put("writeBehindFlushBatchSize", details.runtime().writeBehindFlushBatchSize());
        runtime.put("writeBehindMaxRetries", details.runtime().writeBehindMaxRetries());
        runtime.put("writeBehindShutdownDrainTimeoutMillis", details.runtime().writeBehindShutdownDrainTimeoutMillis());
        runtime.put("pubSubChannelPrefix", details.runtime().pubSubChannelPrefix());
        runtime.put("pubSubEnabled", details.runtime().pubSubEnabled());
        runtime.put("schemaBootstrapMode", details.runtime().schemaBootstrapMode());
        runtime.put("telemetryMode", details.runtime().telemetryMode());
        runtime.put("poolMaxSize", details.runtime().poolMaxSize());
        node.put("registeredAt", details.registeredAt().toString());
        if (details.connectedAt() == null) {
            node.putNull("connectedAt");
        } else {
            node.put("connectedAt", details.connectedAt().toString());
        }
        return node;
    }

    private ObjectNode health(SetupHealth health) {
        ObjectNode node = json.createObjectNode();
        node.put("status", health.status().name());
        node.put("schemaReady", health.schemaReady());
        node.put("latencyMillis", health.latencyMillis());
        node.put("checkedAt", health.checkedAt().toString());
        node.put("detail", health.detail());
        return node;
    }

    private ObjectNode summary(SetupSummary summary) {
        ObjectNode node = json.createObjectNode();
        node.put("setupId", summary.setupId());
        node.put("displayName", summary.displayName());
        node.put("host", summary.host());
        node.put("port", summary.port());
        node.put("database", summary.database());
        node.put("schema", summary.schema());
        node.put("sslMode", summary.sslMode().name());
        node.put("source", summary.source().name());
        node.put("state", summary.state().name());
        node.put("schemaState", summary.schemaState().name());
        if (summary.lastHealth() == null) {
            node.putNull("lastHealth");
        } else {
            ObjectNode health = node.putObject("lastHealth");
            health.put("status", summary.lastHealth().status().name());
            health.put("latencyMillis", summary.lastHealth().latencyMillis());
            health.put("checkedAt", summary.lastHealth().checkedAt().toString());
        }
        return node;
    }

    private static void requireViewer(AuthenticatedManagementIdentity identity) {
        if (!identity.roles().contains("viewer") && !identity.roles().contains("operator")) {
            throw new ManagementSecurityException(
                    403, "AUTHORIZATION_FAILED", "Viewer role is required");
        }
    }

    private static RouteMatch match(String path) {
        if (path.equals(SETUPS_PATH)) {
            return new RouteMatch(null, RouteKind.LIST);
        }
        if (!path.startsWith(SETUP_PATH_PREFIX)) {
            return null;
        }
        String[] segments = path.substring(SETUP_PATH_PREFIX.length()).split("/", -1);
        if (segments.length == 1 && !segments[0].isEmpty() && !segments[0].equals("actions")) {
            return new RouteMatch(segments[0], RouteKind.DETAILS);
        }
        if (segments.length != 2 || segments[0].isEmpty()) {
            return null;
        }
        return switch (segments[1]) {
            case "health" -> new RouteMatch(segments[0], RouteKind.HEALTH);
            default -> null;
        };
    }

    private static void requireCanonicalSetupId(String setupId) {
        if (!SETUP_ID.matcher(setupId).matches()) {
            throw new ManagementProtocolException(
                    400, "INVALID_IDENTIFIER", "Setup identifier is not canonical");
        }
    }

    private static String correlationId(HttpServerRequest request) {
        return ManagementWireRules.correlationId(
                request.getHeader("X-Correlation-ID"), () -> UUID.randomUUID().toString());
    }

    private void writeProblem(HttpServerRequest request, Throwable failure, String correlationId) {
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
                .putHeader("x-correlation-id", correlationId)
                .end(response.toString());
    }

    private enum RouteKind {
        LIST,
        DETAILS,
        HEALTH
    }

    private record RouteMatch(String setupId, RouteKind kind) {
    }
}
