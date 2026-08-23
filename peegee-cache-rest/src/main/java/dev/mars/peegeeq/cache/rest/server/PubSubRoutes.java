package dev.mars.peegeeq.cache.rest.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.mars.peegeeq.cache.api.management.ManagementAuditAction;
import dev.mars.peegeeq.cache.api.management.ManagementAuditFingerprinter;
import dev.mars.peegeeq.cache.api.management.ManagementAuditIntent;
import dev.mars.peegeeq.cache.api.management.ManagementAuditOutcome;
import dev.mars.peegeeq.cache.api.management.ManagementAuditOutcomeException;
import dev.mars.peegeeq.cache.api.management.ManagementAuditReservation;
import dev.mars.peegeeq.cache.api.management.ManagementAuditSink;
import dev.mars.peegeeq.cache.api.management.ManagementAuditTerminalOutcome;
import dev.mars.peegeeq.cache.api.management.ManagementResourceType;
import dev.mars.peegeeq.cache.api.model.PublishRequest;
import dev.mars.peegeeq.cache.rest.protocol.ManagementProblem;
import dev.mars.peegeeq.cache.rest.protocol.ManagementProtocolException;
import dev.mars.peegeeq.cache.rest.protocol.ManagementWireRules;
import dev.mars.peegeeq.cache.rest.security.BrowserRequestSecurity;
import dev.mars.peegeeq.cache.rest.security.ManagementRateLimiter;
import dev.mars.peegeeq.cache.rest.security.ManagementSecurityException;
import dev.mars.peegeeq.cache.rest.security.RateLimitAction;
import io.vertx.core.Future;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpServerResponse;

import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.ArrayDeque;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Audited management pub/sub publication route. */
public final class PubSubRoutes implements ManagementRequestRouter {

    private static final Pattern PUBLISH = Pattern.compile(
            "^/api/v1/setups/([a-z][a-z0-9-]{0,62})/pubsub/publish$");
    private static final Pattern SUBSCRIPTIONS = Pattern.compile(
            "^/api/v1/setups/([a-z][a-z0-9-]{0,62})/pubsub/subscriptions$");
    private static final Pattern SUBSCRIPTION = Pattern.compile(
            "^/api/v1/setups/([a-z][a-z0-9-]{0,62})/pubsub/subscriptions/([^/]+)$");
    private static final Pattern REVEAL = Pattern.compile(
            "^/api/v1/setups/([a-z][a-z0-9-]{0,62})/pubsub/subscriptions/([^/]+)"
                    + "/messages/([^/]+)/payload/reveal$");
    private static final Pattern STREAM = Pattern.compile(
            "^/api/v1/setups/([a-z][a-z0-9-]{0,62})/pubsub/subscriptions/([^/]+)/stream$");
    private static final int REQUEST_MAX_BYTES = 16 * 1024;
    private static final int REVEAL_AUTO_HIDE_MILLIS = 60_000;
    private static final String DEFAULT_REVEAL_REASON = "INTERACTIVE_CONSOLE_REVEAL";

    private final SetupRegistry registry;
    private final ManagementRequestAuthenticator authenticator;
    private final BrowserRequestSecurity browserSecurity;
    private final ManagementAuditSink audit;
    private final ManagementAuditFingerprinter fingerprinter;
    private final ManagementRateLimiter rateLimiter;
    private final Clock clock;
    private final ManagementPubSubSubscriptions subscriptions;
    private final ManagementPeriodicScheduler scheduler;
    private final ManagementRuntimeMonitor runtimeMonitor;
    private final ObjectMapper json = new ObjectMapper();

    public PubSubRoutes(
            SetupRegistry registry,
            ManagementRequestAuthenticator authenticator,
            BrowserRequestSecurity browserSecurity,
            ManagementAuditSink audit,
            ManagementAuditFingerprinter fingerprinter,
            ManagementRateLimiter rateLimiter,
            Clock clock) {
        this(registry, authenticator, browserSecurity, audit, fingerprinter, rateLimiter, clock,
                new ManagementPubSubSubscriptions(
                        clock, 5, 100, 500, 1024 * 1024, 64L * 1024 * 1024),
                ManagementPeriodicScheduler.currentVertxContext());
    }

    public PubSubRoutes(
            SetupRegistry registry,
            ManagementRequestAuthenticator authenticator,
            BrowserRequestSecurity browserSecurity,
            ManagementAuditSink audit,
            ManagementAuditFingerprinter fingerprinter,
            ManagementRateLimiter rateLimiter,
            Clock clock,
            ManagementPubSubSubscriptions subscriptions) {
        this(registry, authenticator, browserSecurity, audit, fingerprinter, rateLimiter, clock,
                subscriptions, ManagementPeriodicScheduler.currentVertxContext());
    }

    PubSubRoutes(
            SetupRegistry registry,
            ManagementRequestAuthenticator authenticator,
            BrowserRequestSecurity browserSecurity,
            ManagementAuditSink audit,
            ManagementAuditFingerprinter fingerprinter,
            ManagementRateLimiter rateLimiter,
            Clock clock,
            ManagementPubSubSubscriptions subscriptions,
            ManagementPeriodicScheduler scheduler) {
        this(registry, authenticator, browserSecurity, audit, fingerprinter, rateLimiter, clock,
                subscriptions, scheduler, null);
    }

    PubSubRoutes(
            SetupRegistry registry,
            ManagementRequestAuthenticator authenticator,
            BrowserRequestSecurity browserSecurity,
            ManagementAuditSink audit,
            ManagementAuditFingerprinter fingerprinter,
            ManagementRateLimiter rateLimiter,
            Clock clock,
            ManagementPubSubSubscriptions subscriptions,
            ManagementPeriodicScheduler scheduler,
            ManagementRuntimeMonitor runtimeMonitor) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.authenticator = Objects.requireNonNull(authenticator, "authenticator");
        this.browserSecurity = Objects.requireNonNull(browserSecurity, "browserSecurity");
        this.audit = Objects.requireNonNull(audit, "audit");
        this.fingerprinter = Objects.requireNonNull(fingerprinter, "fingerprinter");
        this.rateLimiter = Objects.requireNonNull(rateLimiter, "rateLimiter");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.subscriptions = Objects.requireNonNull(subscriptions, "subscriptions");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.runtimeMonitor = runtimeMonitor;
        this.registry.addScopeLifecycle(subscriptions);
    }

    @Override
    public boolean route(HttpServerRequest request) {
        Matcher publishMatcher = PUBLISH.matcher(request.path());
        Matcher createMatcher = SUBSCRIPTIONS.matcher(request.path());
        Matcher deleteMatcher = SUBSCRIPTION.matcher(request.path());
        Matcher revealMatcher = REVEAL.matcher(request.path());
        Matcher streamMatcher = STREAM.matcher(request.path());
        boolean publishing = request.method().name().equals("POST") && publishMatcher.matches();
        boolean creating = request.method().name().equals("POST") && createMatcher.matches();
        boolean deleting = request.method().name().equals("DELETE") && deleteMatcher.matches();
        boolean revealing = request.method().name().equals("POST") && revealMatcher.matches();
        boolean streaming = request.method().name().equals("GET") && streamMatcher.matches();
        if (!publishing && !creating && !deleting && !revealing && !streaming) {
            return false;
        }
        String correlationId = ManagementWireRules.correlationId(
                request.getHeader("X-Correlation-ID"), () -> UUID.randomUUID().toString());
        try {
            AuthenticatedManagementRequest authenticated = authenticator.authenticate(request);
            if (streaming) {
                browserSecurity.validateStreamOrigin(request.getHeader("Origin"));
                requireEventStreamAccept(request.getHeader("Accept"));
                stream(
                        request, streamMatcher.group(1), streamMatcher.group(2), authenticated,
                        correlationId, lastEventId(request.getHeader("Last-Event-ID")));
                return true;
            }
            if (publishing || revealing) requireOperator(authenticated);
            browserSecurity.validateStateChange(
                    authenticated.sessionCookie(),
                    request.getHeader("X-PeeGeeQ-CSRF"),
                    authenticated.sessionCookie(),
                    authenticated.csrfToken(),
                    request.getHeader("Origin"));
            requireAuditReady();
            String setupId = publishing ? publishMatcher.group(1)
                    : creating ? createMatcher.group(1)
                    : revealing ? revealMatcher.group(1) : deleteMatcher.group(1);
            if (deleting) {
                deleteSubscription(
                        request, setupId, deleteMatcher.group(2), authenticated, correlationId);
                return true;
            }
            if (!revealing) {
                ManagementWireRules.requireJsonContentType(request.getHeader("Content-Type"));
            }
            long contentLength = contentLength(request.getHeader("Content-Length"));
            if (contentLength >= 0) {
                ManagementWireRules.requireRequestSize(contentLength, REQUEST_MAX_BYTES);
            }
            rateLimiter.acquire(
                    publishing ? RateLimitAction.PUBLISH
                            : revealing ? RateLimitAction.REVEAL
                            : RateLimitAction.SUBSCRIPTION_CREATE,
                    authenticated.identity().actor(),
                    InetAddress.getByName(authenticated.identity().sourceAddress()));
            request.body()
                    .onSuccess(body -> {
                        if (publishing) {
                            publish(request, setupId, authenticated, correlationId, body.getBytes());
                        } else if (revealing) {
                            reveal(
                                    request, setupId, revealMatcher.group(2), revealMatcher.group(3),
                                    authenticated, correlationId, body.getBytes());
                        } else {
                            createSubscription(
                                    request, setupId, authenticated, correlationId, body.getBytes());
                        }
                    })
                    .onFailure(failure -> writeProblem(request, failure, correlationId));
        } catch (Throwable failure) {
            writeProblem(request, failure, correlationId);
        }
        return true;
    }

    private void stream(
            HttpServerRequest request,
            String setupId,
            String subscriptionId,
            AuthenticatedManagementRequest authenticated,
            String correlationId,
            long afterEventId) {
        SseConnection connection = new SseConnection(
                request.response(), correlationId,
                request.version() == io.vertx.core.http.HttpVersion.HTTP_1_1);
        try {
            ManagementPubSubSubscriptions.StreamOpen open = subscriptions.openStream(
                    subscriptionId, authenticated.identity().actor(), setupId, afterEventId,
                    connection::message, connection::closeFromServer);
            connection.start(open);
        } catch (Throwable failure) {
            connection.cleanup();
            writeProblem(request, failure, correlationId);
        }
    }

    private void reveal(
            HttpServerRequest request,
            String setupId,
            String subscriptionId,
            String messageId,
            AuthenticatedManagementRequest authenticated,
            String correlationId,
            byte[] body) {
        try {
            ManagementWireRules.requireRequestSize(body.length, REQUEST_MAX_BYTES);
            if (body.length > 0) {
                ManagementWireRules.requireJsonContentType(request.getHeader("Content-Type"));
            }
            String reason = revealReason(body);
            ManagementAuditIntent intent = new ManagementAuditIntent(
                    UUID.randomUUID().toString(), clock.instant(),
                    authenticated.identity().actor(), authenticated.identity().roles(),
                    ManagementAuditAction.REVEAL_PUBSUB_PAYLOAD, setupId,
                    ManagementResourceType.PUBSUB_MESSAGE,
                    Map.of(
                            "subscription", fingerprinter.fingerprint(subscriptionId),
                            "message", fingerprinter.fingerprint(messageId)),
                    null, reason, authenticated.identity().sourceAddress(), correlationId);
            audit.reserveIntent(intent)
                    .compose(reservation -> retained(
                                    subscriptionId, messageId, authenticated.identity().actor(), setupId)
                            .compose(retained -> complete(reservation, new ManagementAuditOutcome(
                                            ManagementAuditTerminalOutcome.SUCCEEDED,
                                            "PUBSUB_PAYLOAD_REVEALED", null))
                                    .map(retained))
                            .recover(failure -> completeRevealFailure(reservation, failure)))
                    .onSuccess(retained -> writeRevealed(request, retained, correlationId))
                    .onFailure(failure -> writeProblem(request, failure, correlationId));
        } catch (Throwable failure) {
            writeProblem(request, failure, correlationId);
        } finally {
            java.util.Arrays.fill(body, (byte) 0);
        }
    }

    private Future<ManagementPubSubSubscriptions.RetainedMessage> retained(
            String subscriptionId, String messageId, String actor, String setupId) {
        try {
            return Future.succeededFuture(
                    subscriptions.retained(subscriptionId, actor, setupId, messageId));
        } catch (RuntimeException failure) {
            return Future.failedFuture(failure);
        }
    }

    private <T> Future<T> completeRevealFailure(
            ManagementAuditReservation reservation, Throwable failure) {
        if (failure instanceof dev.mars.peegeeq.cache.api.management.ManagementAuditException) {
            return Future.failedFuture(failure);
        }
        ManagementAuditTerminalOutcome outcome = failure instanceof PubSubManagementException
                ? ManagementAuditTerminalOutcome.REJECTED
                : ManagementAuditTerminalOutcome.FAILED;
        return complete(reservation, new ManagementAuditOutcome(
                        outcome, "PUBSUB_PAYLOAD_REVEAL_FAILED", null))
                .compose(ignored -> Future.failedFuture(failure));
    }

    private void createSubscription(
            HttpServerRequest request,
            String setupId,
            AuthenticatedManagementRequest authenticated,
            String correlationId,
            byte[] body) {
        try {
            ManagementWireRules.requireRequestSize(body.length, REQUEST_MAX_BYTES);
            SubscriptionRequest creation = parseSubscription(
                    body, registry.capabilities(setupId).limits());
            ManagementAuditIntent intent = intent(
                    authenticated, correlationId, setupId,
                    ManagementAuditAction.CREATE_PUBSUB_SUBSCRIPTION,
                    ManagementResourceType.PUBSUB_CHANNEL,
                    "channel", creation.channel());
            audit.reserveIntent(intent)
                    .compose(reservation -> subscriptions.create(
                                    authenticated.identity().actor(), setupId,
                                    creation.channel(), creation.bufferLimit(), registry.pubSub(setupId))
                            .compose(summary -> complete(reservation, new ManagementAuditOutcome(
                                            ManagementAuditTerminalOutcome.SUCCEEDED,
                                            "PUBSUB_SUBSCRIPTION_CREATED", null))
                                    .map(summary))
                            .recover(failure -> completeFailure(
                                    reservation, failure, "PUBSUB_SUBSCRIPTION_CREATE_FAILED")))
                    .onSuccess(summary -> writeSubscription(request, setupId, summary, correlationId))
                    .onFailure(failure -> writeProblem(request, failure, correlationId));
        } catch (Throwable failure) {
            writeProblem(request, failure, correlationId);
        } finally {
            java.util.Arrays.fill(body, (byte) 0);
        }
    }

    private void deleteSubscription(
            HttpServerRequest request,
            String setupId,
            String subscriptionId,
            AuthenticatedManagementRequest authenticated,
            String correlationId) {
        try {
            ManagementAuditIntent intent = intent(
                    authenticated, correlationId, setupId,
                    ManagementAuditAction.DELETE_PUBSUB_SUBSCRIPTION,
                    ManagementResourceType.PUBSUB_SUBSCRIPTION,
                    "subscription", subscriptionId);
            audit.reserveIntent(intent)
                    .compose(reservation -> subscriptions.delete(
                                    subscriptionId, authenticated.identity().actor(), setupId)
                            .compose(ignored -> complete(reservation, new ManagementAuditOutcome(
                                    ManagementAuditTerminalOutcome.SUCCEEDED,
                                    "PUBSUB_SUBSCRIPTION_DELETED", null)))
                            .recover(failure -> completeFailure(
                                    reservation, failure, "PUBSUB_SUBSCRIPTION_DELETE_FAILED")))
                    .onSuccess(ignored -> request.response().setStatusCode(204)
                            .putHeader("x-correlation-id", correlationId).end())
                    .onFailure(failure -> writeProblem(request, failure, correlationId));
        } catch (Throwable failure) {
            writeProblem(request, failure, correlationId);
        }
    }

    private <T> Future<T> completeFailure(
            ManagementAuditReservation reservation,
            Throwable failure,
            String code) {
        if (failure instanceof dev.mars.peegeeq.cache.api.management.ManagementAuditException) {
            return Future.failedFuture(failure);
        }
        return complete(reservation, new ManagementAuditOutcome(
                        ManagementAuditTerminalOutcome.FAILED, code, null))
                .compose(ignored -> Future.failedFuture(failure));
    }

    private ManagementAuditIntent intent(
            AuthenticatedManagementRequest authenticated,
            String correlationId,
            String setupId,
            ManagementAuditAction action,
            ManagementResourceType resourceType,
            String fingerprintName,
            String identifier) {
        return new ManagementAuditIntent(
                UUID.randomUUID().toString(), clock.instant(),
                authenticated.identity().actor(), authenticated.identity().roles(),
                action, setupId, resourceType,
                Map.of(fingerprintName, fingerprinter.fingerprint(identifier)),
                null, null, authenticated.identity().sourceAddress(), correlationId);
    }

    private void publish(
            HttpServerRequest request,
            String setupId,
            AuthenticatedManagementRequest authenticated,
            String correlationId,
            byte[] body) {
        try {
            ManagementWireRules.requireRequestSize(body.length, REQUEST_MAX_BYTES);
            PublishRequest publication = parse(body, registry.capabilities(setupId).limits());
            ManagementAuditIntent intent = new ManagementAuditIntent(
                    UUID.randomUUID().toString(), clock.instant(),
                    authenticated.identity().actor(), authenticated.identity().roles(),
                    ManagementAuditAction.PUBLISH_PUBSUB, setupId,
                    ManagementResourceType.PUBSUB_CHANNEL,
                    Map.of("channel", fingerprinter.fingerprint(publication.channel())),
                    null, null, authenticated.identity().sourceAddress(), correlationId);
            audit.reserveIntent(intent)
                    .compose(reservation -> execute(publication, setupId, reservation))
                    .onSuccess(ignored -> writeAccepted(request, correlationId))
                    .onFailure(failure -> writeProblem(request, failure, correlationId));
        } catch (Throwable failure) {
            writeProblem(request, failure, correlationId);
        } finally {
            java.util.Arrays.fill(body, (byte) 0);
        }
    }

    private Future<Void> execute(
            PublishRequest publication,
            String setupId,
            ManagementAuditReservation reservation) {
        return registry.pubSub(setupId).publish(publication)
                .compose(ignored -> complete(reservation, new ManagementAuditOutcome(
                        ManagementAuditTerminalOutcome.SUCCEEDED, "PUBSUB_PUBLISHED", null)))
                .recover(failure -> {
                    if (failure instanceof dev.mars.peegeeq.cache.api.management.ManagementAuditException) {
                        return Future.failedFuture(failure);
                    }
                    return complete(reservation, new ManagementAuditOutcome(
                            ManagementAuditTerminalOutcome.FAILED, "PUBSUB_PUBLISH_FAILED", null))
                            .compose(ignored -> Future.failedFuture(failure));
                });
    }

    private Future<Void> complete(
            ManagementAuditReservation reservation,
            ManagementAuditOutcome outcome) {
        try {
            return audit.complete(reservation, outcome).recover(failure -> Future.failedFuture(
                    new ManagementAuditOutcomeException(
                            "Management audit terminal outcome is unavailable", failure)));
        } catch (RuntimeException failure) {
            return Future.failedFuture(new ManagementAuditOutcomeException(
                    "Management audit terminal outcome is unavailable", failure));
        }
    }

    private PublishRequest parse(byte[] body, SetupCapabilities.Limits limits) {
        try {
            JsonNode root = json.readTree(body);
            if (root == null || !root.isObject() || !fieldNames(root).equals(Set.of("channel", "payload"))) {
                throw validationFailure();
            }
            String channel = requiredText(root, "channel");
            String payload = requiredTextAllowEmpty(root, "payload");
            int channelBytes = channel.getBytes(StandardCharsets.UTF_8).length;
            int payloadBytes = payload.getBytes(StandardCharsets.UTF_8).length;
            if (channel.indexOf('\0') >= 0
                    || channelBytes > limits.pubSubChannelMaxBytes()
                    || payloadBytes > limits.pubSubPayloadMaxBytes()) {
                throw validationFailure();
            }
            return new PublishRequest(channel, payload, null);
        } catch (ManagementProtocolException failure) {
            throw failure;
        } catch (Exception failure) {
            throw validationFailure();
        }
    }

    private SubscriptionRequest parseSubscription(
            byte[] body, SetupCapabilities.Limits limits) {
        try {
            JsonNode root = json.readTree(body);
            if (root == null || !root.isObject()) {
                throw validationFailure();
            }
            Set<String> fields = fieldNames(root);
            if (!Set.of(Set.of("channel"), Set.of("channel", "bufferLimit")).contains(fields)) {
                throw validationFailure();
            }
            String channel = requiredText(root, "channel");
            int bufferLimit = root.has("bufferLimit") ? root.get("bufferLimit").intValue() : 200;
            if (!root.path("bufferLimit").isMissingNode()
                    && (!root.get("bufferLimit").isIntegralNumber()
                    || !root.get("bufferLimit").canConvertToInt())) {
                throw validationFailure();
            }
            if (channel.indexOf('\0') >= 0
                    || channel.getBytes(StandardCharsets.UTF_8).length
                    > limits.pubSubChannelMaxBytes()
                    || bufferLimit < 1 || bufferLimit > 500) {
                throw validationFailure();
            }
            return new SubscriptionRequest(channel, bufferLimit);
        } catch (ManagementProtocolException failure) {
            throw failure;
        } catch (Exception failure) {
            throw validationFailure();
        }
    }

    private void requireAuditReady() {
        if (!audit.isMutationReady()) {
            throw new dev.mars.peegeeq.cache.api.management.ManagementAuditException(
                    "Management audit is not accepting privileged operations");
        }
    }

    private static void requireOperator(AuthenticatedManagementRequest authenticated) {
        if (!authenticated.identity().roles().contains("operator")) {
            throw new ManagementSecurityException(
                    403, "AUTHORIZATION_FAILED", "Operator role is required");
        }
    }

    private static Set<String> fieldNames(JsonNode root) {
        Set<String> names = new java.util.HashSet<>();
        root.fieldNames().forEachRemaining(names::add);
        return names;
    }

    private static String requiredText(JsonNode root, String name) {
        String value = requiredTextAllowEmpty(root, name);
        if (value.isEmpty()) throw validationFailure();
        return value;
    }

    private static String requiredTextAllowEmpty(JsonNode root, String name) {
        JsonNode value = root.get(name);
        if (value == null || !value.isTextual()) throw validationFailure();
        return value.textValue();
    }

    private static long contentLength(String value) {
        if (value == null) return -1;
        try {
            long parsed = Long.parseLong(value);
            if (parsed < 0) throw validationFailure();
            return parsed;
        } catch (NumberFormatException failure) {
            throw validationFailure();
        }
    }

    private static long lastEventId(String value) {
        if (value == null) return 0;
        if (!value.matches("0|[1-9][0-9]*")) throw validationFailure();
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException failure) {
            throw validationFailure();
        }
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

    private static ManagementProtocolException validationFailure() {
        return new ManagementProtocolException(
                400, "VALIDATION_FAILED", "Request body does not match the operation contract");
    }

    private String revealReason(byte[] body) {
        if (body.length == 0) return DEFAULT_REVEAL_REASON;
        try {
            JsonNode root = json.readTree(body);
            if (root == null || !root.isObject()) throw validationFailure();
            Set<String> fields = fieldNames(root);
            if (!fields.isEmpty() && !fields.equals(Set.of("reason"))) {
                throw validationFailure();
            }
            if (fields.isEmpty()) return DEFAULT_REVEAL_REASON;
            JsonNode value = root.get("reason");
            if (!value.isTextual()) throw validationFailure();
            String reason = value.textValue().trim();
            int bytes = reason.getBytes(StandardCharsets.UTF_8).length;
            if (bytes < 3 || bytes > 240
                    || reason.codePoints().anyMatch(Character::isISOControl)) {
                throw validationFailure();
            }
            return reason;
        } catch (ManagementProtocolException failure) {
            throw failure;
        } catch (Exception failure) {
            throw validationFailure();
        }
    }

    private void writeAccepted(HttpServerRequest request, String correlationId) {
        ObjectNode body = json.createObjectNode();
        body.put("accepted", true);
        body.put("publishedAt", clock.instant().toString());
        request.response()
                .setStatusCode(200)
                .putHeader("content-type", "application/json; charset=utf-8")
                .putHeader("cache-control", "no-store")
                .putHeader("x-correlation-id", correlationId)
                .end(body.toString());
    }

    private void writeSubscription(
            HttpServerRequest request,
            String setupId,
            ManagementPubSubSubscriptions.Summary summary,
            String correlationId) {
        ObjectNode body = json.createObjectNode();
        body.put("subscriptionId", summary.subscriptionId());
        body.put("channel", summary.channel());
        body.put("streamPath", "/api/v1/setups/" + setupId
                + "/pubsub/subscriptions/" + summary.subscriptionId() + "/stream");
        body.put("bufferLimit", summary.bufferLimit());
        body.put("createdAt", summary.createdAt().toString());
        body.put("expiresAt", summary.expiresAt().toString());
        request.response()
                .setStatusCode(201)
                .putHeader("content-type", "application/json; charset=utf-8")
                .putHeader("cache-control", "no-store")
                .putHeader("x-correlation-id", correlationId)
                .end(body.toString());
    }

    private void writeRevealed(
            HttpServerRequest request,
            ManagementPubSubSubscriptions.RetainedMessage retained,
            String correlationId) {
        ObjectNode body = json.createObjectNode();
        body.put("messageId", retained.messageId());
        body.put("channel", retained.channel());
        body.put("payload", retained.payload());
        if (retained.contentType() == null) {
            body.putNull("contentType");
        } else {
            body.put("contentType", retained.contentType());
        }
        body.put("encoding", "UTF8");
        body.put("receivedAt", retained.receivedAt().toString());
        body.put("revealedAt", clock.instant().toString());
        body.put("autoHideAfterMillis", REVEAL_AUTO_HIDE_MILLIS);
        request.response()
                .setStatusCode(200)
                .putHeader("content-type", "application/json; charset=utf-8")
                .putHeader("cache-control", "no-store")
                .putHeader("pragma", "no-cache")
                .putHeader("x-correlation-id", correlationId)
                .end(body.toString());
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

    private final class SseConnection {
        private final HttpServerResponse response;
        private final String correlationId;
        private final boolean includeConnectionHeader;
        private final AtomicBoolean cleaned = new AtomicBoolean();
        private final ArrayDeque<ManagementPubSubSubscriptions.RetainedMessage> pending =
                new ArrayDeque<>();
        private ManagementPubSubSubscriptions.StreamAttachment attachment;
        private ManagementPeriodicScheduler.Cancellable heartbeat;
        private ManagementRuntimeMonitor.ResourceLease telemetryLease;
        private boolean started;

        private SseConnection(
                HttpServerResponse response,
                String correlationId,
                boolean includeConnectionHeader) {
            this.response = response;
            this.correlationId = correlationId;
            this.includeConnectionHeader = includeConnectionHeader;
        }

        private synchronized void start(ManagementPubSubSubscriptions.StreamOpen open) {
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
            writeEvent("ready", readyData());
            if (open.reset()) {
                if (runtimeMonitor != null) runtimeMonitor.streamReset();
                ObjectNode reset = json.createObjectNode();
                reset.put("oldestAvailableEventId",
                        Long.toString(open.oldestAvailableEventId()));
                writeEvent("reset", reset.toString());
            }
            open.replay().forEach(this::writeMessage);
            started = true;
            while (!pending.isEmpty()) writeMessage(pending.removeFirst());
            heartbeat = scheduler.schedule(15_000, () -> write(": heartbeat\n\n"));
            if (cleaned.get()) heartbeat.cancel();
        }

        private synchronized void message(
                ManagementPubSubSubscriptions.RetainedMessage message) {
            if (cleaned.get()) return;
            if (!started) {
                pending.addLast(message);
                return;
            }
            writeMessage(message);
        }

        private void writeMessage(ManagementPubSubSubscriptions.RetainedMessage message) {
            ObjectNode data = json.createObjectNode();
            data.put("messageId", message.messageId());
            data.put("channel", message.channel());
            if (message.contentType() == null) data.putNull("contentType");
            else data.put("contentType", message.contentType());
            data.put("payloadBytes", message.payloadBytes());
            data.put("receivedAt", message.receivedAt().toString());
            data.put("payloadState", "MASKED");
            write("id: " + message.eventId() + "\nevent: pubsub.message\ndata: "
                    + data + "\n\n");
        }

        private String readyData() {
            ObjectNode data = json.createObjectNode();
            data.put("connectedAt", clock.instant().toString());
            return data.toString();
        }

        private void writeEvent(String event, String data) {
            write("event: " + event + "\ndata: " + data + "\n\n");
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
            ManagementPeriodicScheduler.Cancellable scheduled = heartbeat;
            if (scheduled != null) scheduled.cancel();
            ManagementPubSubSubscriptions.StreamAttachment attached = attachment;
            if (attached != null) attached.close();
            ManagementRuntimeMonitor.ResourceLease lease = telemetryLease;
            if (lease != null) lease.close();
            synchronized (this) {
                pending.clear();
            }
            return true;
        }
    }

    private record SubscriptionRequest(String channel, int bufferLimit) {
    }
}
