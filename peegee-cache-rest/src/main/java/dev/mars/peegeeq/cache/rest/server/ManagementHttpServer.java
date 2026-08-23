package dev.mars.peegeeq.cache.rest.server;

import dev.mars.peegeeq.cache.api.management.ManagementRuntimeLifecycleState;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.HttpServerRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/** Minimal owning HTTP lifecycle; route slices are attached behind its reserved path boundaries. */
public final class ManagementHttpServer {

    private static final Logger LOGGER = LoggerFactory.getLogger(ManagementHttpServer.class);
    static final String ERROR_CODE_HEADER = "x-peegeeq-error-code";

    private static final String SPA = resourceText("/ui/index.html");

    private final Vertx vertx;
    private final ManagementServerConfiguration configuration;
    private final ManagementServerResources resources;
    private final ManagementRequestRouter router;
    private final ManagementHttpTelemetry telemetry;
    private HttpServer httpServer;
    private Future<Void> startFuture;
    private Future<Void> stopFuture;
    private boolean resourcesStarted;
    private boolean resourcesClosed;
    private volatile boolean started;

    public ManagementHttpServer(
            Vertx vertx,
            ManagementServerConfiguration configuration,
            ManagementServerResources resources) {
        this(vertx, configuration, resources, ManagementRequestRouter.none(),
                ManagementHttpTelemetry.noop());
    }

    public ManagementHttpServer(
            Vertx vertx,
            ManagementServerConfiguration configuration,
            ManagementServerResources resources,
            ManagementRequestRouter router) {
        this(vertx, configuration, resources, router, ManagementHttpTelemetry.noop());
    }

    public ManagementHttpServer(
            Vertx vertx,
            ManagementServerConfiguration configuration,
            ManagementServerResources resources,
            ManagementRequestRouter router,
            ManagementHttpTelemetry telemetry) {
        this.vertx = Objects.requireNonNull(vertx, "vertx");
        this.configuration = Objects.requireNonNull(configuration, "configuration");
        this.resources = Objects.requireNonNull(resources, "resources");
        this.router = Objects.requireNonNull(router, "router");
        this.telemetry = Objects.requireNonNull(telemetry, "telemetry");
    }

    public synchronized Future<Void> start() {
        if (startFuture != null) {
            return startFuture;
        }
        if (stopFuture != null) {
            return Future.failedFuture("Management HTTP server has been stopped");
        }
        HttpServer candidate = vertx.createHttpServer();
        httpServer = candidate;
        lifecycle(ManagementRuntimeLifecycleState.STARTING);
        Future<Void> attempt = resources.start()
                .onSuccess(ignored -> markResourcesStarted())
                .compose(ignored -> candidate.requestHandler(this::handle)
                        .listen(configuration.port(), configuration.bindAddress())
                        .map(server -> (Void) null))
                .onSuccess(ignored -> {
                    started = true;
                    lifecycle(ManagementRuntimeLifecycleState.RUNNING);
                    readiness(resources.ready());
                    LOGGER.info("bind_address={} port={} authentication_mode={} management.http.server.started",
                            configuration.bindAddress(),
                            configuration.port(),
                            configuration.authenticationMode());
                });
        startFuture = attempt.recover(failure -> failedStart(candidate, failure));
        return startFuture;
    }

    public synchronized Future<Void> stop() {
        if (stopFuture != null) {
            return stopFuture;
        }
        lifecycle(ManagementRuntimeLifecycleState.STOPPING);
        readiness(false);
        Future<Void> afterStart = startFuture == null
                ? Future.succeededFuture()
                : startFuture.recover(ignored -> Future.succeededFuture());
        stopFuture = afterStart
                .compose(ignored -> closeHttpServer())
                .compose(ignored -> closeResources())
                .onComplete(ignored -> started = false)
                .onSuccess(ignored -> {
                    shutdownCompleted();
                    lifecycle(ManagementRuntimeLifecycleState.STOPPED);
                    LOGGER.info("management.http.server.stopped");
                })
                .onFailure(ignored -> lifecycle(ManagementRuntimeLifecycleState.FAILED));
        return stopFuture;
    }

    public boolean isStarted() {
        return started;
    }

    private void handle(HttpServerRequest request) {
        String path = request.path();
        ManagementHttpTelemetry.Surface requestSurface = surface(path);
        Completion completion = new Completion(
                request.method().name(), requestSurface, System.nanoTime(),
                startTelemetry(request.method().name(), requestSurface, path));
        request.response().endHandler(ignored -> completion.finish(
                request.response().getStatusCode(), request.response().headers().get(ERROR_CODE_HEADER)));
        request.response().exceptionHandler(ignored -> completion.finish(
                request.response().getStatusCode(), request.response().headers().get(ERROR_CODE_HEADER)));
        request.response().closeHandler(ignored -> completion.finish(
                request.response().getStatusCode(), request.response().headers().get(ERROR_CODE_HEADER)));
        if (request.method().name().equals("GET") && path.equals("/health/ready")) {
            boolean ready = started && resources.ready();
            readiness(ready);
            var response = request.response().setStatusCode(ready ? 200 : 503);
            if (!ready) response.putHeader(ERROR_CODE_HEADER, "MANAGEMENT_NOT_READY");
            response.putHeader("content-type", "application/json; charset=utf-8")
                    .end("{\"status\":\"" + (ready ? "UP" : "DOWN") + "\"}");
            return;
        }
        if (router.route(request)) {
            return;
        }
        if (path.equals("/api") || path.startsWith("/api/")
                || path.equals("/ws") || path.startsWith("/ws/")) {
            notFound(request);
            return;
        }
        if (request.method().name().equals("GET")
                && (path.equals("/ui") || path.equals("/ui/") || path.startsWith("/ui/"))) {
            request.response()
                    .putHeader("content-type", "text/html; charset=utf-8")
                    .putHeader("x-content-type-options", "nosniff")
                    .putHeader("referrer-policy", "no-referrer")
                    .end(SPA);
            return;
        }
        notFound(request);
    }

    private static ManagementHttpTelemetry.Surface surface(String path) {
        if (path.equals("/health/ready")) {
            return ManagementHttpTelemetry.Surface.HEALTH;
        }
        if (path.equals("/api") || path.startsWith("/api/")) {
            return ManagementHttpTelemetry.Surface.API;
        }
        if (path.equals("/ws") || path.startsWith("/ws/")) {
            return ManagementHttpTelemetry.Surface.WEBSOCKET;
        }
        if (path.equals("/ui") || path.equals("/ui/") || path.startsWith("/ui/")) {
            return ManagementHttpTelemetry.Surface.UI;
        }
        return ManagementHttpTelemetry.Surface.OTHER;
    }

    private ManagementHttpTelemetry.Request startTelemetry(
            String method, ManagementHttpTelemetry.Surface surface, String path) {
        try {
            return telemetry.started(method, surface, path);
        } catch (RuntimeException failure) {
            LOGGER.warn("management.http.telemetry.failed");
            return (status, latencyMillis) -> { };
        }
    }

    private final class Completion {
        private final String method;
        private final ManagementHttpTelemetry.Surface surface;
        private final long started;
        private final ManagementHttpTelemetry.Request telemetryRequest;
        private final AtomicBoolean finished = new AtomicBoolean();

        private Completion(
                String method,
                ManagementHttpTelemetry.Surface surface,
                long started,
                ManagementHttpTelemetry.Request telemetryRequest) {
            this.method = method;
            this.surface = surface;
            this.started = started;
            this.telemetryRequest = telemetryRequest;
        }

        private void finish(int status, String errorCode) {
            if (!finished.compareAndSet(false, true)) {
                return;
            }
            long latencyMillis = Math.max(0, (System.nanoTime() - started) / 1_000_000);
            LOGGER.debug("method={} surface={} status={} latency_ms={} management.http.request.completed",
                    method, surface, status, latencyMillis);
            try {
                telemetryRequest.completed(status, latencyMillis, errorCode);
            } catch (RuntimeException failure) {
                LOGGER.warn("management.http.telemetry.failed");
            }
        }
    }

    private static void notFound(HttpServerRequest request) {
        request.response()
                .setStatusCode(404)
                .putHeader(ERROR_CODE_HEADER, "ROUTE_NOT_FOUND")
                .putHeader("content-type", "application/problem+json; charset=utf-8")
                .end("{\"status\":404,\"code\":\"ROUTE_NOT_FOUND\",\"title\":\"Route not found\"}");
    }

    private synchronized void markResourcesStarted() {
        resourcesStarted = true;
    }

    private Future<Void> unwindFailedStart(HttpServer candidate) {
        started = false;
        return candidate.close()
                .recover(ignored -> Future.succeededFuture())
                .compose(ignored -> closeResources());
    }

    private Future<Void> failedStart(HttpServer candidate, Throwable failure) {
        return unwindFailedStart(candidate)
                .onComplete(ignored -> {
                    readiness(false);
                    lifecycle(ManagementRuntimeLifecycleState.FAILED);
                })
                .compose(ignored -> Future.failedFuture(failure));
    }

    private void lifecycle(ManagementRuntimeLifecycleState state) {
        try {
            telemetry.lifecycleChanged(state);
        } catch (RuntimeException failure) {
            LOGGER.warn("management.http.telemetry.failed");
        }
    }

    private void readiness(boolean ready) {
        try {
            telemetry.serverReadinessChanged(ready);
        } catch (RuntimeException failure) {
            LOGGER.warn("management.http.telemetry.failed");
        }
    }

    private void shutdownCompleted() {
        try {
            telemetry.shutdownCompleted();
        } catch (RuntimeException failure) {
            LOGGER.warn("management.http.telemetry.failed");
        }
    }

    private synchronized Future<Void> closeHttpServer() {
        HttpServer server = httpServer;
        httpServer = null;
        if (server == null) {
            return Future.succeededFuture();
        }
        return server.close().recover(ignored -> Future.succeededFuture());
    }

    private synchronized Future<Void> closeResources() {
        if (!resourcesStarted || resourcesClosed) {
            return Future.succeededFuture();
        }
        resourcesClosed = true;
        return resources.closeAsync();
    }

    private static String resourceText(String path) {
        try (InputStream input = ManagementHttpServer.class.getResourceAsStream(path)) {
            if (input == null) throw new IllegalStateException("Missing management resource " + path);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new IllegalStateException("Management resource could not be read " + path, failure);
        }
    }
}
