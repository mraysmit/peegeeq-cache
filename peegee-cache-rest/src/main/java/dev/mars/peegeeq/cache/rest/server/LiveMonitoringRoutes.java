package dev.mars.peegeeq.cache.rest.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.mars.peegeeq.cache.rest.protocol.ManagementProblem;
import dev.mars.peegeeq.cache.rest.protocol.ManagementProtocolException;
import dev.mars.peegeeq.cache.rest.protocol.ManagementWireRules;
import dev.mars.peegeeq.cache.rest.security.BrowserRequestSecurity;
import dev.mars.peegeeq.cache.rest.security.ManagementSecurityException;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.http.ServerWebSocket;
import io.vertx.core.buffer.Buffer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.util.ArrayDeque;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Bounded setup metrics SSE and monitoring WebSocket routes. */
public final class LiveMonitoringRoutes implements ManagementRequestRouter {

    private static final Logger LOGGER = LoggerFactory.getLogger(LiveMonitoringRoutes.class);
    private static final Pattern METRICS = Pattern.compile(
            "^/api/v1/setups/([a-z][a-z0-9-]{0,62})/sse/metrics$");
    private static final Pattern SETUP_ID = Pattern.compile("^[a-z][a-z0-9-]{0,62}$");
    private static final Set<String> METRICS_TYPES = Set.of(
            "overview.snapshot", "runtime.snapshot", "health.changed");

    private final SetupRegistry registry;
    private final ManagementRequestAuthenticator authenticator;
    private final BrowserRequestSecurity browserSecurity;
    private final SetupInspectionRoutes inspections;
    private final ManagementLiveEventHub events;
    private final ManagementPeriodicScheduler scheduler;
    private final ManagementMetricsSampler metricsSampler;
    private final Clock clock;
    private final ManagementRuntimeMonitor runtimeMonitor;
    private final ObjectMapper json = new ObjectMapper();

    LiveMonitoringRoutes(
            SetupRegistry registry,
            ManagementRequestAuthenticator authenticator,
            BrowserRequestSecurity browserSecurity,
            SetupInspectionRoutes inspections,
            ManagementLiveEventHub events,
            ManagementPeriodicScheduler scheduler,
            Clock clock) {
        this(registry, authenticator, browserSecurity, inspections, events, scheduler, clock, null);
    }

    LiveMonitoringRoutes(
            SetupRegistry registry,
            ManagementRequestAuthenticator authenticator,
            BrowserRequestSecurity browserSecurity,
            SetupInspectionRoutes inspections,
            ManagementLiveEventHub events,
            ManagementPeriodicScheduler scheduler,
            Clock clock,
            ManagementRuntimeMonitor runtimeMonitor) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.authenticator = Objects.requireNonNull(authenticator, "authenticator");
        this.browserSecurity = Objects.requireNonNull(browserSecurity, "browserSecurity");
        this.inspections = Objects.requireNonNull(inspections, "inspections");
        this.events = Objects.requireNonNull(events, "events");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.metricsSampler = new ManagementMetricsSampler(scheduler, this::snapshot);
        this.clock = Objects.requireNonNull(clock, "clock");
        this.runtimeMonitor = runtimeMonitor;
        this.registry.addScopeLifecycle(events);
        this.registry.addScopeLifecycle(metricsSampler);
    }

    @Override
    public boolean route(HttpServerRequest request) {
        if (request.method().name().equals("GET") && request.path().equals("/ws/monitoring")) {
            routeWebSocket(request);
            return true;
        }
        Matcher matcher = METRICS.matcher(request.path());
        if (!request.method().name().equals("GET") || !matcher.matches()) return false;
        String correlationId = ManagementWireRules.correlationId(
                request.getHeader("X-Correlation-ID"), () -> UUID.randomUUID().toString());
        try {
            AuthenticatedManagementRequest authenticated = authenticator.authenticate(request);
            requireViewer(authenticated);
            browserSecurity.validateStreamOrigin(request.getHeader("Origin"));
            requireEventStreamAccept(request.getHeader("Accept"));
            String setupId = matcher.group(1);
            registry.get(setupId);
            String afterEventId = boundedEventId(request.getHeader("Last-Event-ID"));
            MetricsConnection connection = new MetricsConnection(
                    request.response(), setupId, correlationId,
                    request.version() == io.vertx.core.http.HttpVersion.HTTP_1_1);
            ManagementLiveEventHub.Open open = events.open(
                    setupId, afterEventId, connection::event, connection::closeFromServer);
            connection.start(open);
        } catch (Throwable failure) {
            writeProblem(request, failure, correlationId);
        }
        return true;
    }

    private void routeWebSocket(HttpServerRequest request) {
        String correlationId = ManagementWireRules.correlationId(
                request.getHeader("X-Correlation-ID"), () -> UUID.randomUUID().toString());
        try {
            AuthenticatedManagementRequest authenticated = authenticator.authenticate(request);
            requireViewer(authenticated);
            browserSecurity.validateStreamOrigin(request.getHeader("Origin"));
            String setupId = request.getParam("setupId");
            if (setupId == null || !SETUP_ID.matcher(setupId).matches()
                    || request.params().names().stream()
                    .anyMatch(name -> !name.equals("setupId") && !name.equals("afterEventId"))) {
                throw new ManagementProtocolException(
                        400, "VALIDATION_FAILED", "Monitoring WebSocket query is invalid");
            }
            String afterEventId = boundedEventId(request.getParam("afterEventId"));
            registry.get(setupId);
            if (!request.canUpgradeToWebSocket()) {
                throw new ManagementProtocolException(
                        400, "VALIDATION_FAILED", "A WebSocket upgrade is required");
            }
            request.toWebSocket()
                    .onSuccess(socket -> openWebSocket(socket, setupId, afterEventId))
                    .onFailure(failure -> {
                        if (!request.response().ended()) {
                            writeProblem(request, failure, correlationId);
                        }
                    });
        } catch (Throwable failure) {
            writeProblem(request, failure, correlationId);
        }
    }

    private void openWebSocket(
            ServerWebSocket socket, String setupId, String afterEventId) {
        WebSocketConnection connection = new WebSocketConnection(socket, setupId);
        try {
            ManagementLiveEventHub.Open open = events.open(
                    setupId, afterEventId, connection::event, connection::closeFromServer);
            connection.start(open);
        } catch (Throwable failure) {
            connection.cleanup();
            socket.close((short) 1011, "STREAM_INITIALIZATION_FAILED");
        }
    }

    private void snapshot(String setupId) {
        inspections.overviewSnapshot(setupId)
                .onSuccess(overview -> {
                    events.publish(setupId, "overview.snapshot", overview.toString());
                    events.publish(
                            setupId, "runtime.snapshot",
                            inspections.runtimeSnapshot(setupId).toString());
                })
                .onFailure(ignored ->
                        LOGGER.warn("management.metrics_sse.snapshot_failed"));
    }

    private static void requireViewer(AuthenticatedManagementRequest authenticated) {
        if (!authenticated.identity().roles().contains("viewer")) {
            throw new ManagementSecurityException(
                    403, "AUTHORIZATION_FAILED", "Viewer role is required");
        }
    }

    private static String boundedEventId(String value) {
        if (value == null) return null;
        if (value.isEmpty() || value.length() > 128
                || value.codePoints().anyMatch(Character::isISOControl)) {
            throw new ManagementProtocolException(
                    400, "VALIDATION_FAILED", "Last-Event-ID is invalid");
        }
        return value;
    }

    private static void requireEventStreamAccept(String accept) {
        if (accept == null || accept.equals("*/*")) return;
        boolean accepted = java.util.Arrays.stream(accept.split(","))
                .map(value -> value.split(";", 2)[0].trim().toLowerCase(java.util.Locale.ROOT))
                .anyMatch(value -> value.equals("text/event-stream") || value.equals("*/*"));
        if (!accepted) {
            throw new ManagementProtocolException(
                    406, "NOT_ACCEPTABLE", "Stream responses use text/event-stream");
        }
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

    private final class MetricsConnection {
        private final HttpServerResponse response;
        private final String setupId;
        private final String correlationId;
        private final boolean includeConnectionHeader;
        private final AtomicBoolean cleaned = new AtomicBoolean();
        private final ArrayDeque<ManagementLiveEventHub.Event> pending = new ArrayDeque<>();
        private ManagementLiveEventHub.Attachment attachment;
        private ManagementMetricsSampler.Lease samplerLease;
        private ManagementRuntimeMonitor.ResourceLease telemetryLease;
        private boolean started;

        private MetricsConnection(
                HttpServerResponse response,
                String setupId,
                String correlationId,
                boolean includeConnectionHeader) {
            this.response = response;
            this.setupId = setupId;
            this.correlationId = correlationId;
            this.includeConnectionHeader = includeConnectionHeader;
        }

        private synchronized void start(ManagementLiveEventHub.Open open) {
            attachment = open.attachment();
            if (runtimeMonitor != null) telemetryLease = runtimeMonitor.trackSseClient();
            response.setStatusCode(200)
                    .setChunked(true)
                    .putHeader("content-type", "text/event-stream; charset=utf-8")
                    .putHeader("cache-control", "no-store")
                    .putHeader("x-correlation-id", correlationId);
            if (includeConnectionHeader) response.putHeader("connection", "keep-alive");
            response.closeHandler(ignored -> cleanup());
            response.exceptionHandler(ignored -> cleanup());
            response.endHandler(ignored -> cleanup());
            ObjectNode ready = json.createObjectNode();
            ready.put("connectedAt", clock.instant().toString());
            write("event: ready\ndata: " + ready + "\n\n");
            if (open.reset()) {
                if (runtimeMonitor != null) runtimeMonitor.streamReset();
                ObjectNode reset = json.createObjectNode();
                if (open.oldestAvailableEventId() == null) {
                    reset.putNull("oldestAvailableEventId");
                } else {
                    reset.put("oldestAvailableEventId", open.oldestAvailableEventId());
                }
                write("event: reset\ndata: " + reset + "\n\n");
            }
            open.replay().forEach(this::writeEvent);
            started = true;
            while (!pending.isEmpty()) writeEvent(pending.removeFirst());
            samplerLease = metricsSampler.acquire(setupId, () -> write(": heartbeat\n\n"));
            if (cleaned.get()) samplerLease.close();
        }

        private synchronized void event(ManagementLiveEventHub.Event event) {
            if (cleaned.get() || !METRICS_TYPES.contains(event.type())) return;
            if (!started) {
                pending.addLast(event);
            } else {
                writeEvent(event);
            }
        }

        private void writeEvent(ManagementLiveEventHub.Event event) {
            if (!METRICS_TYPES.contains(event.type())) return;
            write("id: " + event.eventId() + "\nevent: " + event.type()
                    + "\ndata: " + event.data() + "\n\n");
        }

        private void write(String frame) {
            if (cleaned.get()) return;
            response.write(frame).onFailure(ignored -> cleanup());
        }

        private void closeFromServer() {
            if (cleanup()) response.end();
        }

        private boolean cleanup() {
            if (!cleaned.compareAndSet(false, true)) return false;
            if (samplerLease != null) samplerLease.close();
            if (attachment != null) attachment.close();
            if (telemetryLease != null) telemetryLease.close();
            synchronized (this) {
                pending.clear();
            }
            return true;
        }
    }

    private final class WebSocketConnection {
        private final ServerWebSocket socket;
        private final String setupId;
        private final AtomicBoolean cleaned = new AtomicBoolean();
        private final ArrayDeque<ManagementLiveEventHub.Event> pending = new ArrayDeque<>();
        private ManagementLiveEventHub.Attachment attachment;
        private ManagementPeriodicScheduler.Cancellable ping;
        private ManagementPeriodicScheduler.Cancellable pongTimeout;
        private ManagementRuntimeMonitor.ResourceLease telemetryLease;
        private boolean awaitingPong;
        private boolean started;

        private WebSocketConnection(ServerWebSocket socket, String setupId) {
            this.socket = socket;
            this.setupId = setupId;
        }

        private synchronized void start(ManagementLiveEventHub.Open open) {
            attachment = open.attachment();
            if (runtimeMonitor != null) telemetryLease = runtimeMonitor.trackWebSocketClient();
            socket.setWriteQueueMaxSize(64 * 1024);
            socket.closeHandler(ignored -> cleanup());
            socket.endHandler(ignored -> cleanup());
            socket.exceptionHandler(ignored -> cleanup());
            socket.pongHandler(ignored -> pongReceived());
            ObjectNode ready = json.createObjectNode();
            ready.put("connectedAt", clock.instant().toString());
            writeEnvelope(
                    UUID.randomUUID().toString(), "connection.ready",
                    clock.instant().toString(), ready.toString());
            if (open.reset()) {
                if (runtimeMonitor != null) runtimeMonitor.streamReset();
                ObjectNode reset = json.createObjectNode();
                if (open.oldestAvailableEventId() == null) {
                    reset.putNull("oldestAvailableEventId");
                } else {
                    reset.put("oldestAvailableEventId", open.oldestAvailableEventId());
                }
                writeEnvelope(
                        UUID.randomUUID().toString(), "stream.reset",
                        clock.instant().toString(), reset.toString());
            }
            open.replay().forEach(this::writeEvent);
            started = true;
            while (!pending.isEmpty()) writeEvent(pending.removeFirst());
            ping = scheduler.schedule(20_000, this::sendPing);
            if (cleaned.get()) ping.cancel();
        }

        private synchronized void event(ManagementLiveEventHub.Event event) {
            if (cleaned.get()) return;
            if (!started) pending.addLast(event);
            else writeEvent(event);
        }

        private void writeEvent(ManagementLiveEventHub.Event event) {
            writeEnvelope(
                    event.eventId(), event.type(), event.occurredAt().toString(), event.data());
        }

        private void writeEnvelope(
                String eventId, String type, String occurredAt, String data) {
            if (cleaned.get()) return;
            try {
                ObjectNode envelope = json.createObjectNode();
                envelope.put("eventId", eventId);
                envelope.put("type", type);
                envelope.put("occurredAt", occurredAt);
                envelope.put("setupId", setupId);
                envelope.set("data", json.readTree(data));
                socket.writeTextMessage(envelope.toString())
                        .onFailure(ignored -> cleanup());
            } catch (java.io.IOException failure) {
                cleanup();
            }
        }

        private void closeFromServer() {
            if (cleanup()) socket.close((short) 1000, "SETUP_SCOPE_CLOSED");
        }

        private synchronized void sendPing() {
            if (cleaned.get()) return;
            awaitingPong = true;
            if (pongTimeout != null) pongTimeout.cancel();
            pongTimeout = scheduler.scheduleOnce(10_000, this::pongTimedOut);
            socket.writePing(Buffer.buffer()).onFailure(ignored -> cleanup());
        }

        private synchronized void pongReceived() {
            awaitingPong = false;
            if (pongTimeout != null) {
                pongTimeout.cancel();
                pongTimeout = null;
            }
        }

        private synchronized void pongTimedOut() {
            if (!awaitingPong || cleaned.get()) return;
            awaitingPong = false;
            if (cleanup()) socket.close((short) 1001, "PONG_TIMEOUT");
        }

        private boolean cleanup() {
            if (!cleaned.compareAndSet(false, true)) return false;
            if (ping != null) ping.cancel();
            if (pongTimeout != null) pongTimeout.cancel();
            if (attachment != null) attachment.close();
            if (telemetryLease != null) telemetryLease.close();
            synchronized (this) {
                pending.clear();
            }
            return true;
        }
    }
}
