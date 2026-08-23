package dev.mars.peegeeq.cache.rest.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.mars.peegeeq.cache.rest.security.AuthenticatedManagementIdentity;
import dev.mars.peegeeq.cache.rest.security.ManagementAuthenticationException;
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
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SetupReadRoutesTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void listsSafeSortedSetupSummariesOnlyForViewersOverHttp() throws Exception {
        Vertx vertx = Vertx.vertx();
        int port = freePort();
        SetupRegistry registry = new SetupRegistry(
                (definition, secret) -> Future.succeededFuture(new ReadyRuntime()),
                reference -> "configured-secret".getBytes(StandardCharsets.UTF_8));
        await(registry.register(definition("zeta"), SetupSecret.owned(secret("zeta-password"))));
        await(registry.register(definition("alpha"), SetupSecret.owned(secret("alpha-password"))));

        ManagementRequestAuthenticator authenticator = request -> {
            String cookie = request.getHeader("Cookie");
            if (cookie == null) {
                throw new ManagementAuthenticationException(
                        401, "AUTHENTICATION_REQUIRED", "Authentication required");
            }
            return new AuthenticatedManagementRequest(
                    new AuthenticatedManagementIdentity(
                            "reader", cookie.equals("role=viewer") ? Set.of("viewer") : Set.of(), "127.0.0.1"),
                    cookie,
                    "test-csrf");
        };
        ManagementHttpServer server = new ManagementHttpServer(
                vertx,
                TestManagementConfigurations.local(port),
                ManagementServerResources.noop(),
                new SetupReadRoutes(registry, authenticator));
        try {
            await(server.start());

            assertEquals(401, get(port, "/api/v1/setups", null, null).statusCode());
            assertEquals(403, get(port, "/api/v1/setups", "role=unprivileged", null).statusCode());

            HttpResponse<String> response = get(
                    port, "/api/v1/setups", "role=viewer", "test-correlation");
            assertEquals(200, response.statusCode());
            assertEquals("no-store",
                    response.headers().firstValue("cache-control").orElseThrow());
            assertEquals("test-correlation",
                    response.headers().firstValue("x-correlation-id").orElseThrow());
            JsonNode body = JSON.readTree(response.body());
            assertEquals(2, body.path("items").size());
            JsonNode first = body.path("items").get(0);
            assertEquals("alpha", first.path("setupId").asText());
            assertEquals("Test alpha", first.path("displayName").asText());
            assertEquals("db.internal.example", first.path("host").asText());
            assertEquals(5432, first.path("port").asInt());
            assertEquals("peegeeq", first.path("database").asText());
            assertEquals("peegee_cache", first.path("schema").asText());
            assertEquals("VERIFY_FULL", first.path("sslMode").asText());
            assertEquals("UI_SESSION", first.path("source").asText());
            assertEquals("CONNECTED", first.path("state").asText());
            assertEquals("READY", first.path("schemaState").asText());
            assertEquals("zeta", body.path("items").get(1).path("setupId").asText());
            assertFalse(response.body().contains("username"));
            assertFalse(response.body().contains("password"));
            assertFalse(response.body().contains("trustProfile"));
            assertFalse(response.body().contains("secret"));

            HttpResponse<String> detailsResponse = get(
                    port, "/api/v1/setups/alpha", "role=viewer", null);
            assertEquals(200, detailsResponse.statusCode());
            assertEquals("no-store",
                    detailsResponse.headers().firstValue("cache-control").orElseThrow());
            JsonNode details = JSON.readTree(detailsResponse.body());
            assertEquals("alpha", details.path("setup").path("setupId").asText());
            assertEquals("1", details.path("migrationVersion").asText());
            assertTrue(details.path("runtime").path("defaultTtlMillis").isNull());
            assertFalse(details.path("runtime").path("expirySweeperEnabled").asBoolean());
            assertEquals(30_000, details.path("runtime").path("expirySweepIntervalMillis").asInt());
            assertEquals(500, details.path("runtime").path("expirySweepBatchSize").asInt());
            assertEquals(3, details.path("runtime").path("poolMaxSize").asInt());
            assertFalse(details.path("registeredAt").asText().isBlank());
            assertFalse(details.path("connectedAt").asText().isBlank());
            assertFalse(detailsResponse.body().contains("database-user"));
            assertFalse(detailsResponse.body().contains("corp-ca"));

            HttpResponse<String> invalid = get(
                    port, "/api/v1/setups/ALPHA", "role=viewer", null);
            assertEquals(400, invalid.statusCode());
            assertEquals("INVALID_IDENTIFIER", JSON.readTree(invalid.body()).path("code").asText());
            assertEquals(404, get(port, "/api/v1/setups/missing", "role=viewer", null).statusCode());
            assertEquals(404, get(
                    port, "/api/v1/setups/actions/test", "role=viewer", null).statusCode());

            JsonNode health = JSON.readTree(get(
                    port, "/api/v1/setups/alpha/health", "role=viewer", null).body());
            assertEquals("UP", health.path("status").asText());
            assertTrue(health.path("schemaReady").asBoolean());
            assertEquals("Database reachable and schema ready", health.path("detail").asText());
            JsonNode capabilities = JSON.readTree(get(
                    port, "/api/v1/setups/alpha/capabilities", "role=viewer", null).body());
            assertEquals("1", capabilities.path("migrationVersion").asText());
            assertTrue(capabilities.path("capabilities").path("namespaceInspection").asBoolean());
            assertEquals(49, capabilities.path("limits").path("pubSubChannelMaxBytes").asInt());
            assertEquals(7_500, capabilities.path("limits").path("pubSubPayloadMaxBytes").asInt());
            assertEquals(10_485_760, capabilities.path("limits").path("maximumValueBytes").asInt());

            await(registry.detach("alpha"));
            JsonNode detached = JSON.readTree(get(
                    port, "/api/v1/setups/alpha", "role=viewer", null).body());
            assertEquals("DETACHED", detached.path("setup").path("state").asText());
            assertTrue(detached.path("connectedAt").isNull());
            JsonNode detachedHealth = JSON.readTree(get(
                    port, "/api/v1/setups/alpha/health", "role=viewer", null).body());
            assertEquals("DOWN", detachedHealth.path("status").asText());
            assertFalse(detachedHealth.path("schemaReady").asBoolean());
        } finally {
            await(server.stop());
            await(registry.closeAsync());
            await(vertx.close());
        }
    }

    private static HttpResponse<String> get(
            int port, String path, String cookie, String correlationId) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(
                URI.create("http://127.0.0.1:" + port + path));
        if (cookie != null) request.header("Cookie", cookie);
        if (correlationId != null) request.header("X-Correlation-ID", correlationId);
        return HttpClient.newHttpClient().send(request.GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private static SetupDefinition definition(String setupId) {
        return new SetupDefinition(
                setupId,
                "Test " + setupId,
                new SetupTarget("db.internal.example", 5432, "corp-ca", TlsMode.VERIFY_FULL),
                "peegeeq",
                "peegee_cache",
                "database-user",
                3,
                SetupSource.UI_SESSION,
                null);
    }

    private static byte[] secret(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static int freePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static <T> T await(Future<T> future) throws Exception {
        return future.toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
    }

    private static final class ReadyRuntime implements ManagedSetupRuntime {
        @Override
        public Future<Void> verifyReady() {
            return Future.succeededFuture();
        }

        @Override
        public Future<Void> closeAsync() {
            return Future.succeededFuture();
        }
    }
}
