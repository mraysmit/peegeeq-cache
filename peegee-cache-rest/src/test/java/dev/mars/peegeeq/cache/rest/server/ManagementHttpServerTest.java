package dev.mars.peegeeq.cache.rest.server;

import dev.mars.peegeeq.cache.api.management.ManagementSecretReference;
import dev.mars.peegeeq.cache.api.management.ManagementRuntimeLifecycleState;
import dev.mars.peegeeq.cache.rest.security.SetupTargetPolicy;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import org.junit.jupiter.api.Test;

import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.Set;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ManagementHttpServerTest {

    @Test
    void emitsFailureIsolatedLowCardinalityCompletionTelemetry() throws Exception {
        Vertx vertx = Vertx.vertx();
        int port = freePort();
        List<Completion> completions = new ArrayList<>();
        ManagementHttpTelemetry telemetry = (method, surface, status, latencyMillis) -> {
            completions.add(new Completion(method, surface, status, latencyMillis));
            if (surface == ManagementHttpTelemetry.Surface.UI) {
                throw new IllegalStateException("optional telemetry failure");
            }
        };
        ManagementHttpServer server = new ManagementHttpServer(
                vertx,
                configuration(port),
                new RecordingResources(),
                ManagementRequestRouter.none(),
                telemetry);
        try {
            await(server.start());

            assertEquals(200, get(port, "/health/ready").statusCode());
            assertEquals(200, get(port, "/ui/customer:secret").statusCode());
            assertEquals(404, get(port, "/api/v1/setups/customer:secret/not-found").statusCode());

            assertEquals(List.of(
                            ManagementHttpTelemetry.Surface.HEALTH,
                            ManagementHttpTelemetry.Surface.UI,
                            ManagementHttpTelemetry.Surface.API),
                    completions.stream().map(Completion::surface).toList());
            assertEquals(List.of(200, 200, 404),
                    completions.stream().map(Completion::status).toList());
            assertTrue(completions.stream().allMatch(value -> value.latencyMillis() >= 0));
        } finally {
            await(server.stop());
            await(vertx.close());
        }
    }

    @Test
    void startsOnceReportsReadinessAndStopsIdempotently() throws Exception {
        Vertx vertx = Vertx.vertx();
        RecordingResources resources = new RecordingResources();
        int port = freePort();
        ManagementHttpServer server = new ManagementHttpServer(vertx, configuration(port), resources);
        try {
            await(server.start());
            await(server.start());

            assertTrue(server.isStarted());
            assertEquals(1, resources.starts);
            assertEquals(200, get(port, "/health/ready").statusCode());

            await(server.stop());
            await(server.stop());
            assertFalse(server.isStarted());
            assertEquals(1, resources.closes);
        } finally {
            await(server.stop());
            await(vertx.close());
        }
    }

    @Test
    void failedBindUnwindsAlreadyStartedResources() throws Exception {
        Vertx vertx = Vertx.vertx();
        Vertx conflictingVertx = Vertx.vertx();
        int port = freePort();
        ManagementHttpServer occupying = new ManagementHttpServer(
                vertx, configuration(port), new RecordingResources());
        RecordingResources resources = new RecordingResources();
        ManagementHttpServer conflicting = new ManagementHttpServer(
                conflictingVertx, configuration(port), resources);
        try {
            await(occupying.start());

            assertThrows(Exception.class, () -> await(conflicting.start()));
            assertEquals(1, resources.starts);
            assertEquals(1, resources.closes);
            assertFalse(conflicting.isStarted());
        } finally {
            await(conflicting.stop());
            await(occupying.stop());
            await(conflictingVertx.close());
            await(vertx.close());
        }
    }

    @Test
    void spaFallbackNeverCapturesApiOrWebSocketPaths() throws Exception {
        Vertx vertx = Vertx.vertx();
        int port = freePort();
        ManagementHttpServer server = new ManagementHttpServer(
                vertx, configuration(port), new RecordingResources());
        try {
            await(server.start());

            HttpResponse<String> ui = get(port, "/ui/deep/link");
            HttpResponse<String> api = get(port, "/api/v1/not-a-route");
            HttpResponse<String> websocket = get(port, "/ws/monitoring");

            assertEquals(200, ui.statusCode());
            assertTrue(ui.body().contains("PeeGeeQ Cache Management"));
            assertEquals(404, api.statusCode());
            assertTrue(api.headers().firstValue("content-type").orElseThrow()
                    .startsWith("application/problem+json"));
            assertEquals(404, websocket.statusCode());
            assertFalse(websocket.body().contains("<html"));
        } finally {
            await(server.stop());
            await(vertx.close());
        }
    }

    @Test
    void readinessReflectsRequiredDependencyState() throws Exception {
        Vertx vertx = Vertx.vertx();
        int port = freePort();
        RecordingResources resources = new RecordingResources();
        ManagementHttpServer server = new ManagementHttpServer(vertx, configuration(port), resources);
        try {
            await(server.start());
            resources.ready = false;
            assertEquals(503, get(port, "/health/ready").statusCode());
            resources.ready = true;
            assertEquals(200, get(port, "/health/ready").statusCode());
        } finally {
            await(server.stop());
            await(vertx.close());
        }
    }

    @Test
    void publishesServerLifecycleReadinessAndShutdownCompletion() throws Exception {
        Vertx vertx = Vertx.vertx();
        int port = freePort();
        RecordingResources resources = new RecordingResources();
        RecordingLifecycleTelemetry telemetry = new RecordingLifecycleTelemetry();
        ManagementHttpServer server = new ManagementHttpServer(
                vertx, configuration(port), resources, ManagementRequestRouter.none(), telemetry);
        try {
            await(server.start());
            resources.ready = false;
            assertEquals(503, get(port, "/health/ready").statusCode());
            await(server.stop());

            assertEquals(List.of(
                    ManagementRuntimeLifecycleState.STARTING,
                    ManagementRuntimeLifecycleState.RUNNING,
                    ManagementRuntimeLifecycleState.STOPPING,
                    ManagementRuntimeLifecycleState.STOPPED), telemetry.lifecycleStates);
            assertEquals(List.of(true, false, false), telemetry.readinessStates);
            assertEquals(1, telemetry.shutdownCompletions);
        } finally {
            await(server.stop());
            await(vertx.close());
        }
    }

    private static ManagementServerConfiguration configuration(int port) {
        return ManagementServerConfiguration.localToken(
                "127.0.0.1",
                port,
                "http://127.0.0.1:" + port,
                SetupTargetPolicy.privateNetworks(
                        Set.of("internal.example"),
                        Set.of("10.0.0.0/8"),
                        Set.of(5432),
                        Set.of("corp-ca")),
                Path.of("logs", "management-audit.jsonl"),
                new ManagementSecretReference("env:PGQ_AUDIT_KEY"));
    }

    private static int freePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static HttpResponse<String> get(int port, String path) throws Exception {
        return HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static <T> T await(Future<T> future) throws Exception {
        return future.toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
    }

    private static final class RecordingResources implements ManagementServerResources {
        private int starts;
        private int closes;
        private boolean ready = true;

        @Override
        public Future<Void> start() {
            starts++;
            return Future.succeededFuture();
        }

        @Override
        public boolean ready() {
            return ready;
        }

        @Override
        public Future<Void> closeAsync() {
            closes++;
            return Future.succeededFuture();
        }
    }

    private static final class RecordingLifecycleTelemetry implements ManagementHttpTelemetry {
        private final List<ManagementRuntimeLifecycleState> lifecycleStates = new ArrayList<>();
        private final List<Boolean> readinessStates = new ArrayList<>();
        private int shutdownCompletions;

        @Override
        public void completed(String method, Surface surface, int status, long latencyMillis) {
        }

        @Override
        public void lifecycleChanged(ManagementRuntimeLifecycleState state) {
            lifecycleStates.add(state);
        }

        @Override
        public void serverReadinessChanged(boolean ready) {
            readinessStates.add(ready);
        }

        @Override
        public void shutdownCompleted() {
            shutdownCompletions++;
        }
    }

    private record Completion(
            String method,
            ManagementHttpTelemetry.Surface surface,
            int status,
            long latencyMillis) {
    }
}
