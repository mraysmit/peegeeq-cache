package dev.mars.peegeeq.cache.rest.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.mars.peegeeq.cache.api.management.ManagementSecretReference;
import dev.mars.peegeeq.cache.rest.security.SetupTargetPolicy;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import io.vertx.core.buffer.Buffer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ManagementServerApplicationTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path temporaryDirectory;

    @Test
    void startsCompleteLocalApplicationAndOwnsItsResources() throws Exception {
        int port = freePort();
        String origin = "http://127.0.0.1:" + port;
        ManagementSecretReference auditKey = new ManagementSecretReference("audit-key");
        ManagementServerConfiguration configuration = ManagementServerConfiguration.localToken(
                "127.0.0.1",
                port,
                origin,
                new SetupTargetPolicy(
                        Set.of("internal.example"),
                        Set.of("127.0.0.0/8"),
                        Set.of(5432),
                        true,
                        false,
                        false,
                        false,
                        Set.of("test-ca")),
                temporaryDirectory.resolve("management-audit.jsonl"),
                auditKey);
        PrometheusMeterRegistry meters = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);

        ManagementServerApplication application = await(ManagementServerApplication.start(
                configuration,
                reference -> reference.equals(auditKey) ? new byte[32] : null,
                hostname -> java.util.List.of(InetAddress.getByName(hostname)),
                ignored -> Buffer.buffer("unused-in-empty-registry"),
                meters));
        try {
            assertTrue(application.isStarted());
            String bootstrapToken = application.takeBootstrapToken().orElseThrow();
            assertFalse(application.takeBootstrapToken().isPresent());

            HttpResponse<String> health = send(port, "/health/ready", "GET", null, null, null);
            assertEquals(200, health.statusCode());

            HttpResponse<String> exchange = send(
                    port,
                    "/api/v1/session/local",
                    "POST",
                    origin,
                    "application/json",
                    "{\"token\":\"" + bootstrapToken + "\"}");
            assertEquals(200, exchange.statusCode(), exchange.body());
            String cookie = exchange.headers().firstValue("set-cookie").orElseThrow()
                    .split(";", 2)[0];
            JsonNode session = JSON.readTree(exchange.body());
            assertEquals("LOCAL_TOKEN", session.path("authenticationMode").asText());

            HttpResponse<String> setups = send(
                    port, "/api/v1/setups", "GET", origin, null, null, "Cookie", cookie);
            assertEquals(200, setups.statusCode(), setups.body());
            assertEquals(0, JSON.readTree(setups.body()).path("items").size());
            assertEquals(1.0, meters.get("peegeeq.management.server.ready").gauge().value());
            HttpResponse<String> metrics = send(port, "/metrics", "GET", null, null, null);
            assertEquals(200, metrics.statusCode());
            assertTrue(metrics.headers().firstValue("content-type").orElseThrow()
                    .startsWith("text/plain"));
            assertTrue(metrics.body().contains("peegeeq_management_server_ready"));
        } finally {
            await(application.closeAsync());
            meters.close();
        }

        assertFalse(application.isStarted());
        assertFalse(application.takeBootstrapToken().isPresent());
        assertTrue(java.nio.file.Files.isRegularFile(configuration.auditJournal()));
        await(application.closeAsync());
    }

    private static HttpResponse<String> send(
            int port,
            String path,
            String method,
            String origin,
            String contentType,
            String body,
            String... headers) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(10));
        if (origin != null) request.header("Origin", origin);
        if (contentType != null) request.header("Content-Type", contentType);
        for (int index = 0; index < headers.length; index += 2) {
            request.header(headers[index], headers[index + 1]);
        }
        request.method(method, body == null
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body));
        return HttpClient.newHttpClient().send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static int freePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            return socket.getLocalPort();
        }
    }

    private static <T> T await(io.vertx.core.Future<T> future) throws Exception {
        return future.toCompletionStage().toCompletableFuture().get(20, TimeUnit.SECONDS);
    }
}
