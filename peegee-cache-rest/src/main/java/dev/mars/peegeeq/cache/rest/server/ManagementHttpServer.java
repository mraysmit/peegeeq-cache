package dev.mars.peegeeq.cache.rest.server;

import dev.mars.peegeeq.cache.api.management.ManagementRuntimeLifecycleState;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.HttpServerRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/** Minimal owning HTTP lifecycle; route slices are attached behind its reserved path boundaries. */
public final class ManagementHttpServer {

    private static final Logger LOGGER = LoggerFactory.getLogger(ManagementHttpServer.class);
    static final String ERROR_CODE_HEADER = "x-peegeeq-error-code";

    private final Vertx vertx;
    private final ManagementServerConfiguration configuration;
    private final ManagementServerResources resources;
    private final ManagementRequestRouter router;
    private final ManagementHttpTelemetry telemetry;
    private final ManagementUiResources uiResources = new ManagementUiResources();
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
        startFuture = attempt.transform(outcome -> outcome.succeeded()
                ? Future.succeededFuture()
                : failedStart(candidate, outcome.cause()));
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
                : startFuture.transform(ignored -> Future.succeededFuture());
        stopFuture = afterStart
                .compose(ignored -> AsyncCloseSequence.closeAll(List.of(
                        this::closeHttpServer,
                        this::closeResources)))
                .transform(outcome -> {
                    started = false;
                    if (outcome.succeeded()) {
                        shutdownCompleted();
                        lifecycle(ManagementRuntimeLifecycleState.STOPPED);
                        LOGGER.info("management.http.server.stopped");
                        return Future.succeededFuture();
                    }
                    lifecycle(ManagementRuntimeLifecycleState.FAILED);
                    return Future.failedFuture(outcome.cause());
                });
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
        if (uiResources.handle(request)) {
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

    private Future<Void> failedStart(HttpServer candidate, Throwable failure) {
        started = false;
        return AsyncCloseSequence.closeAll(List.of(
                        () -> closeCandidate(candidate),
                        this::closeResources))
                .transform(cleanup -> {
                    readiness(false);
                    lifecycle(ManagementRuntimeLifecycleState.FAILED);
                    if (cleanup.failed() && cleanup.cause() != failure) {
                        failure.addSuppressed(cleanup.cause());
                    }
                    return Future.failedFuture(failure);
                });
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
        return server.close();
    }

    private synchronized Future<Void> closeCandidate(HttpServer candidate) {
        if (httpServer == candidate) {
            httpServer = null;
        }
        return candidate.close();
    }

    private synchronized Future<Void> closeResources() {
        if (!resourcesStarted || resourcesClosed) {
            return Future.succeededFuture();
        }
        resourcesClosed = true;
        return resources.closeAsync();
    }
}
