package dev.mars.peegeeq.cache.rest.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.mars.peegeeq.cache.api.management.ManagementAuditException;
import dev.mars.peegeeq.cache.api.management.ManagementAuditIntent;
import dev.mars.peegeeq.cache.api.management.ManagementAuditOutcome;
import dev.mars.peegeeq.cache.api.management.ManagementAuditReservation;
import dev.mars.peegeeq.cache.api.management.ManagementAuditSink;
import dev.mars.peegeeq.cache.api.management.ManagementAuditTerminalOutcome;
import dev.mars.peegeeq.cache.api.management.ManagementActivityQuery;
import dev.mars.peegeeq.cache.api.management.ManagementActivityResourceType;
import dev.mars.peegeeq.cache.api.management.ManagementAuditQueueState;
import dev.mars.peegeeq.cache.rest.security.AuthenticatedManagementIdentity;
import dev.mars.peegeeq.cache.rest.security.BrowserOriginPolicy;
import dev.mars.peegeeq.cache.rest.security.BrowserRequestSecurity;
import dev.mars.peegeeq.cache.rest.security.ManagementAuthenticationException;
import dev.mars.peegeeq.cache.rest.security.ManagementRateLimiter;
import dev.mars.peegeeq.cache.rest.security.RateLimitAction;
import dev.mars.peegeeq.cache.rest.security.RateLimitRule;
import dev.mars.peegeeq.cache.rest.security.RateLimitTelemetry;
import dev.mars.peegeeq.cache.rest.security.SetupTarget;
import dev.mars.peegeeq.cache.rest.security.TlsMode;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import org.junit.jupiter.api.Test;

import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SetupMutationRoutesTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void detachFailsClosedThroughAuthOriginCsrfAndDurableAuditBeforeMutation() throws Exception {
        Vertx vertx = Vertx.vertx();
        int port = freePort();
        String origin = "http://127.0.0.1:" + port;
        RecordingRuntime runtime = new RecordingRuntime();
        SetupRegistry registry = new SetupRegistry(
                (definition, secret) -> Future.succeededFuture(runtime),
                reference -> null);
        await(registry.register(definition(), SetupSecret.owned("password".getBytes(StandardCharsets.UTF_8))));
        RecordingAuditSink audit = new RecordingAuditSink();
        ManagementActivityStore activity = new ManagementActivityStore(100);
        ManagementRequestAuthenticator authenticator = request -> {
            String cookie = request.getHeader("Cookie");
            if (cookie == null) {
                throw new ManagementAuthenticationException(
                        401, "AUTHENTICATION_REQUIRED", "Authentication required");
            }
            Set<String> roles = cookie.startsWith("role=operator")
                    ? Set.of("viewer", "operator") : Set.of("viewer");
            String actor = cookie.equals("role=operator-2") ? "blair" : "alex";
            return new AuthenticatedManagementRequest(
                    new AuthenticatedManagementIdentity(actor, roles, "127.0.0.1"),
                    cookie,
                    "csrf-token");
        };
        SetupMutationRoutes routes = new SetupMutationRoutes(
                registry,
                authenticator,
                new BrowserRequestSecurity(BrowserOriginPolicy.localToken(origin)),
                audit,
                new ManagementRateLimiter(
                        java.util.Arrays.stream(RateLimitAction.values()).collect(
                                java.util.stream.Collectors.toMap(
                                        action -> action,
                                        action -> new RateLimitRule(1, 10, Duration.ofMinutes(1)))),
                        100,
                        Clock.systemUTC(),
                        RateLimitTelemetry.noop()),
                Clock.systemUTC(),
                activity);
        ManagementHttpServer server = new ManagementHttpServer(
                vertx,
                TestManagementConfigurations.local(port),
                ManagementServerResources.noop(),
                ManagementRequestRouter.firstOf(
                        routes,
                        new SetupInspectionRoutes(
                                registry,
                                authenticator,
                                new ManagementRuntimeMonitor(),
                                () -> new ManagementAuditQueueState(0, 100, true),
                                activity)));
        try {
            await(server.start());

            assertEquals(401, post(port, null, origin, "csrf-token").statusCode());
            assertEquals(403, post(port, "role=viewer", origin, "csrf-token").statusCode());
            assertEquals(403, post(port, "role=operator", null, "csrf-token").statusCode());
            assertEquals(403, post(port, "role=operator", origin, null).statusCode());
            assertEquals(SetupState.CONNECTED, registry.get("orders").state());
            assertTrue(audit.intents.isEmpty());

            audit.rejectReservations = true;
            HttpResponse<String> unavailable = post(
                    port, "role=operator", origin, "csrf-token");
            assertEquals(503, unavailable.statusCode());
            assertEquals("AUDIT_UNAVAILABLE", JSON.readTree(unavailable.body()).path("code").asText());
            assertEquals(SetupState.CONNECTED, registry.get("orders").state());
            assertTrue(audit.outcomes.isEmpty());

            audit.rejectReservations = false;
            HttpResponse<String> detached = post(
                    port, "role=operator", origin, "csrf-token");
            assertEquals(204, detached.statusCode());
            assertEquals(SetupState.DETACHED, registry.get("orders").state());
            assertTrue(runtime.closed);
            assertEquals(1, audit.intents.size());
            assertEquals("DETACH_SETUP", audit.intents.getFirst().action().name());
            assertEquals("SETUP", audit.intents.getFirst().resourceType().name());
            assertEquals("alex", audit.intents.getFirst().actor());
            assertEquals(1, audit.outcomes.size());
            assertEquals(ManagementAuditTerminalOutcome.SUCCEEDED,
                    audit.outcomes.getFirst().outcome());
            assertEquals("SETUP_DETACHED", audit.outcomes.getFirst().code());
            var detachActivity = activity.recent(
                    "orders", new ManagementActivityQuery(
                            null, 50, null, "DETACH_SETUP", null));
            assertEquals(1, detachActivity.items().size());
            assertEquals(ManagementAuditTerminalOutcome.SUCCEEDED,
                    detachActivity.items().getFirst().outcome());
            assertEquals("alex", detachActivity.items().getFirst().actor());
            assertEquals(ManagementActivityResourceType.SETUP,
                    detachActivity.items().getFirst().resource().type());
            assertEquals("orders", detachActivity.items().getFirst().resource().identifier());
            HttpResponse<String> detachedActivity = request(
                    port, "GET", "/api/v1/setups/orders/activity?action=DETACH_SETUP",
                    "role=viewer", null, null);
            assertEquals(200, detachedActivity.statusCode());
            assertEquals("DETACH_SETUP", JSON.readTree(detachedActivity.body())
                    .path("items").get(0).path("action").asText());

            HttpResponse<String> connected = request(
                    port, "POST", "/api/v1/setups/orders/connect",
                    "role=operator", origin, "csrf-token");
            assertEquals(200, connected.statusCode());
            assertEquals("CONNECTED", JSON.readTree(connected.body()).path("state").asText());
            assertEquals(SetupState.CONNECTED, registry.get("orders").state());
            assertEquals("CONNECT_SETUP", audit.intents.get(1).action().name());
            assertEquals("SETUP_CONNECTED", audit.outcomes.get(1).code());

            await(registry.detach("orders"));
            HttpResponse<String> rateLimited = request(
                    port, "POST", "/api/v1/setups/orders/connect",
                    "role=operator", origin, "csrf-token");
            assertEquals(429, rateLimited.statusCode());
            assertEquals(SetupState.DETACHED, registry.get("orders").state());
            assertEquals(2, audit.intents.size());

            HttpResponse<String> forgotten = request(
                    port, "DELETE", "/api/v1/setups/orders",
                    "role=operator", origin, "csrf-token");
            assertEquals(204, forgotten.statusCode());
            assertTrue(registry.list().isEmpty());
            assertEquals("FORGET_SETUP", audit.intents.get(2).action().name());
            assertEquals("SETUP_FORGOTTEN", audit.outcomes.get(2).code());

            String connectionBody = """
                    {"host":"db.internal.example","port":5432,"database":"peegeeq",
                     "schema":"peegee_cache","username":"database-user","password":"candidate-password",
                     "sslMode":"VERIFY_FULL","trustProfileId":"corp-ca","poolMaxSize":3}
                    """;
            HttpResponse<String> connectionTest = request(
                    port, "POST", "/api/v1/setups/actions/test",
                    "role=operator", origin, "csrf-token", "application/json", connectionBody);
            assertEquals(200, connectionTest.statusCode());
            assertTrue(JSON.readTree(connectionTest.body()).path("databaseReachable").asBoolean());
            assertEquals("READY", JSON.readTree(connectionTest.body()).path("schemaState").asText());
            assertEquals("TEST_SETUP", audit.intents.get(3).action().name());
            assertEquals("SETUP_TEST_SUCCEEDED", audit.outcomes.get(3).code());

            int auditCount = audit.intents.size();
            assertEquals(415, request(
                    port, "POST", "/api/v1/setups/actions/test",
                    "role=operator", origin, "csrf-token", "text/plain", connectionBody).statusCode());
            String weakTls = connectionBody.replace("VERIFY_FULL", "DISABLE");
            assertEquals(400, request(
                    port, "POST", "/api/v1/setups/actions/test",
                    "role=operator", origin, "csrf-token", "application/json", weakTls).statusCode());
            assertEquals(auditCount, audit.intents.size());

            String registrationBody = """
                    {"setupId":"inventory","displayName":"Inventory","host":"db.internal.example",
                     "port":5432,"database":"peegeeq","schema":"peegee_cache",
                     "username":"database-user","password":"registration-password",
                     "sslMode":"VERIFY_FULL","trustProfileId":"corp-ca","poolMaxSize":3}
                    """;
            HttpResponse<String> registered = request(
                    port, "POST", "/api/v1/setups",
                    "role=operator", origin, "csrf-token", "application/json", registrationBody);
            assertEquals(201, registered.statusCode());
            assertEquals("inventory", JSON.readTree(registered.body()).path("setupId").asText());
            assertEquals("CONNECTED", JSON.readTree(registered.body()).path("state").asText());
            assertTrue(!registered.body().contains("database-user"));
            assertTrue(!registered.body().contains("registration-password"));
            assertTrue(!registered.body().contains("corp-ca"));
            assertEquals("REGISTER_SETUP", audit.intents.get(4).action().name());
            assertEquals("SETUP_REGISTERED", audit.outcomes.get(4).code());

            HttpResponse<String> registeredTest = request(
                    port, "POST", "/api/v1/setups/inventory/test",
                    "role=operator-2", origin, "csrf-token");
            assertEquals(200, registeredTest.statusCode());
            assertTrue(JSON.readTree(registeredTest.body()).path("databaseReachable").asBoolean());
            assertEquals("TEST_SETUP", audit.intents.get(5).action().name());
            assertEquals("inventory", audit.intents.get(5).setupId());
            assertEquals("SETUP_TEST_SUCCEEDED", audit.outcomes.get(5).code());

            audit.rejectOutcomes = true;
            HttpResponse<String> uncertain = request(
                    port, "POST", "/api/v1/setups/inventory/detach",
                    "role=operator-2", origin, "csrf-token");
            assertEquals(503, uncertain.statusCode());
            assertEquals("AUDIT_OUTCOME_UNAVAILABLE",
                    JSON.readTree(uncertain.body()).path("code").asText());
            assertEquals(SetupState.DETACHED, registry.get("inventory").state());
            int intentsAfterUncertainMutation = audit.intents.size();

            HttpResponse<String> blocked = request(
                    port, "POST", "/api/v1/setups/inventory/connect",
                    "role=operator-2", origin, "csrf-token");
            assertEquals(503, blocked.statusCode());
            assertEquals("AUDIT_UNAVAILABLE", JSON.readTree(blocked.body()).path("code").asText());
            assertEquals(SetupState.DETACHED, registry.get("inventory").state());
            assertEquals(intentsAfterUncertainMutation, audit.intents.size());
        } finally {
            await(server.stop());
            await(registry.closeAsync());
            await(vertx.close());
        }
    }

    private static HttpResponse<String> post(
            int port, String cookie, String origin, String csrf) throws Exception {
        return request(port, "POST", "/api/v1/setups/orders/detach", cookie, origin, csrf);
    }

    private static HttpResponse<String> request(
            int port, String method, String path, String cookie, String origin, String csrf) throws Exception {
        return request(port, method, path, cookie, origin, csrf, null, null);
    }

    private static HttpResponse<String> request(
            int port,
            String method,
            String path,
            String cookie,
            String origin,
            String csrf,
            String contentType,
            String body) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(
                URI.create("http://127.0.0.1:" + port + path));
        if (cookie != null) request.header("Cookie", cookie);
        if (origin != null) request.header("Origin", origin);
        if (csrf != null) request.header("X-PeeGeeQ-CSRF", csrf);
        if (contentType != null) request.header("Content-Type", contentType);
        return HttpClient.newHttpClient().send(
                request.method(method, body == null
                        ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static SetupDefinition definition() {
        return new SetupDefinition(
                "orders",
                "Orders",
                new SetupTarget("db.internal.example", 5432, "corp-ca", TlsMode.VERIFY_FULL),
                "peegeeq",
                "peegee_cache",
                "database-user",
                3,
                SetupSource.UI_SESSION,
                null);
    }

    private static int freePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static <T> T await(Future<T> future) throws Exception {
        return future.toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
    }

    private static final class RecordingRuntime implements ManagedSetupRuntime {
        private boolean closed;

        @Override
        public Future<Void> verifyReady() {
            return Future.succeededFuture();
        }

        @Override
        public Future<Void> closeAsync() {
            closed = true;
            return Future.succeededFuture();
        }
    }

    private static final class RecordingAuditSink implements ManagementAuditSink {
        private final List<ManagementAuditIntent> intents = new ArrayList<>();
        private final List<ManagementAuditOutcome> outcomes = new ArrayList<>();
        private boolean rejectReservations;
        private boolean rejectOutcomes;
        private boolean mutationReady = true;

        @Override
        public Future<ManagementAuditReservation> reserveIntent(ManagementAuditIntent intent) {
            if (rejectReservations) {
                return Future.failedFuture(new ManagementAuditException("audit unavailable"));
            }
            intents.add(intent);
            return Future.succeededFuture(new ManagementAuditReservation(
                    UUID.randomUUID().toString(), intent.eventId(), "test-generation"));
        }

        @Override
        public Future<Void> complete(
                ManagementAuditReservation reservation, ManagementAuditOutcome outcome) {
            if (rejectOutcomes) {
                mutationReady = false;
                return Future.failedFuture(new ManagementAuditException("audit outcome unavailable"));
            }
            outcomes.add(outcome);
            return Future.succeededFuture();
        }

        @Override
        public boolean isMutationReady() {
            return mutationReady;
        }
    }
}
