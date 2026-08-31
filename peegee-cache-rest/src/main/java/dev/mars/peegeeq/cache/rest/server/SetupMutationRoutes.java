package dev.mars.peegeeq.cache.rest.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.mars.peegeeq.cache.api.management.ManagementAuditAction;
import dev.mars.peegeeq.cache.api.management.ManagementActivityEvent;
import dev.mars.peegeeq.cache.api.management.ManagementActivityResource;
import dev.mars.peegeeq.cache.api.management.ManagementActivityResourceType;
import dev.mars.peegeeq.cache.api.management.ManagementAuditIntent;
import dev.mars.peegeeq.cache.api.management.ManagementAuditOutcome;
import dev.mars.peegeeq.cache.api.management.ManagementAuditOutcomeException;
import dev.mars.peegeeq.cache.api.management.ManagementAuditReservation;
import dev.mars.peegeeq.cache.api.management.ManagementAuditSink;
import dev.mars.peegeeq.cache.api.management.ManagementAuditTerminalOutcome;
import dev.mars.peegeeq.cache.api.management.ManagementResourceType;
import dev.mars.peegeeq.cache.rest.protocol.ManagementProblem;
import dev.mars.peegeeq.cache.rest.protocol.ManagementProtocolException;
import dev.mars.peegeeq.cache.rest.protocol.ManagementWireRules;
import dev.mars.peegeeq.cache.rest.security.BrowserRequestSecurity;
import dev.mars.peegeeq.cache.rest.security.ManagementRateLimiter;
import dev.mars.peegeeq.cache.rest.security.ManagementSecurityException;
import dev.mars.peegeeq.cache.rest.security.RateLimitAction;
import dev.mars.peegeeq.cache.rest.security.SetupTarget;
import dev.mars.peegeeq.cache.rest.security.TlsMode;
import io.vertx.core.Future;
import io.vertx.core.http.HttpServerRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Setup lifecycle mutations guarded by session, CSRF, authorization, and durable audit. */
public final class SetupMutationRoutes implements ManagementRequestRouter {

    private static final Logger LOGGER = LoggerFactory.getLogger(SetupMutationRoutes.class);

    private static final Pattern ACTION = Pattern.compile(
            "^/api/v1/setups/([^/]+)/(detach|connect|test)$");
    private static final Pattern FORGET = Pattern.compile(
            "^/api/v1/setups/([^/]+)$");
    private static final Pattern SETUP_ID = Pattern.compile("[a-z][a-z0-9-]{0,62}");
    private static final int SETUP_REQUEST_MAX_BYTES = 64 * 1024;

    private final SetupRegistry registry;
    private final ManagementRequestAuthenticator authenticator;
    private final BrowserRequestSecurity browserSecurity;
    private final ManagementAuditSink audit;
    private final ManagementRateLimiter rateLimiter;
    private final Clock clock;
    private final ManagementActivityStore activity;
    private final ObjectMapper json = new ObjectMapper();

    public SetupMutationRoutes(
            SetupRegistry registry,
            ManagementRequestAuthenticator authenticator,
            BrowserRequestSecurity browserSecurity,
            ManagementAuditSink audit,
            ManagementRateLimiter rateLimiter,
            Clock clock) {
        this(registry, authenticator, browserSecurity, audit, rateLimiter, clock,
                new ManagementActivityStore(10_000));
    }

    public SetupMutationRoutes(
            SetupRegistry registry,
            ManagementRequestAuthenticator authenticator,
            BrowserRequestSecurity browserSecurity,
            ManagementAuditSink audit,
            ManagementRateLimiter rateLimiter,
            Clock clock,
            ManagementActivityStore activity) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.authenticator = Objects.requireNonNull(authenticator, "authenticator");
        this.browserSecurity = Objects.requireNonNull(browserSecurity, "browserSecurity");
        this.audit = Objects.requireNonNull(audit, "audit");
        this.rateLimiter = Objects.requireNonNull(rateLimiter, "rateLimiter");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.activity = Objects.requireNonNull(activity, "activity");
    }

    @Override
    public boolean route(HttpServerRequest request) {
        Operation operation = match(request.method().name(), request.path());
        if (operation == null) {
            return false;
        }
        String correlationId = correlationId(request);
        try {
            String setupId = operation.setupId();
            AuthenticatedManagementRequest authenticated = authenticator.authenticate(request);
            requireOperator(authenticated);
            browserSecurity.validateStateChange(
                    authenticated.sessionCookie(),
                    request.getHeader("X-PeeGeeQ-CSRF"),
                    authenticated.sessionCookie(),
                    authenticated.csrfToken(),
                    request.getHeader("Origin"));
            if (operation.kind().body) {
                ManagementWireRules.requireJsonContentType(request.getHeader("Content-Type"));
                long contentLength = contentLength(request.getHeader("Content-Length"));
                if (contentLength >= 0) {
                    ManagementWireRules.requireRequestSize(contentLength, SETUP_REQUEST_MAX_BYTES);
                }
                request.body()
                        .onSuccess(body -> handleBody(
                                request, operation, authenticated, correlationId, body.getBytes()))
                        .onFailure(failure -> writeProblem(request, failure, correlationId));
                return true;
            }
            executeLifecycle(request, operation, authenticated, correlationId);
        } catch (Throwable failure) {
            writeProblem(request, failure, correlationId);
        }
        return true;
    }

    private void executeLifecycle(
            HttpServerRequest request,
            Operation operation,
            AuthenticatedManagementRequest authenticated,
            String correlationId) throws Exception {
        String setupId = operation.setupId();
            requireAuditReady();
            requireCanonicalSetupId(setupId);
            if (operation.kind() == OperationKind.CONNECT) {
                rateLimiter.acquire(
                        RateLimitAction.SETUP_CONNECT,
                        authenticated.identity().actor(),
                        InetAddress.getByName(authenticated.identity().sourceAddress()));
            } else if (operation.kind() == OperationKind.TEST_REGISTERED) {
                rateLimiter.acquire(
                        RateLimitAction.SETUP_TEST,
                        authenticated.identity().actor(),
                        InetAddress.getByName(authenticated.identity().sourceAddress()));
            }
            ManagementAuditIntent intent = new ManagementAuditIntent(
                    UUID.randomUUID().toString(),
                    clock.instant(),
                    authenticated.identity().actor(),
                    authenticated.identity().roles(),
                    operation.kind().auditAction,
                    setupId,
                    ManagementResourceType.SETUP,
                    Map.of(),
                    null,
                    null,
                    authenticated.identity().sourceAddress(),
                    correlationId);
            audit.reserveIntent(intent)
                    .compose(reservation -> executeAndComplete(
                            operation, reservation, activityContext(intent)))
                    .onSuccess(result -> writeSuccess(request, operation, result, correlationId))
                    .onFailure(failure -> writeProblem(request, failure, correlationId));
    }

    private void handleBody(
            HttpServerRequest request,
            Operation operation,
            AuthenticatedManagementRequest authenticated,
            String correlationId,
            byte[] body) {
        SetupPayload payload = null;
        try {
            requireAuditReady();
            ManagementWireRules.requireRequestSize(body.length, SETUP_REQUEST_MAX_BYTES);
            payload = parsePayload(body, operation.kind() == OperationKind.REGISTER);
            RateLimitAction rateAction = operation.kind() == OperationKind.REGISTER
                    ? RateLimitAction.SETUP_REGISTER : RateLimitAction.SETUP_TEST;
            rateLimiter.acquire(
                    rateAction,
                    authenticated.identity().actor(),
                    InetAddress.getByName(authenticated.identity().sourceAddress()));
            String setupId = payload.definition().setupId();
            ManagementAuditIntent intent = new ManagementAuditIntent(
                    UUID.randomUUID().toString(),
                    clock.instant(),
                    authenticated.identity().actor(),
                    authenticated.identity().roles(),
                    operation.kind().auditAction,
                    setupId,
                    ManagementResourceType.SETUP,
                    Map.of(),
                    null,
                    null,
                    authenticated.identity().sourceAddress(),
                    correlationId);
            SetupPayload ownedPayload = payload;
            Future<ManagementAuditReservation> reservation = audit.reserveIntent(intent);
            reservation.onFailure(ignored -> ownedPayload.secret().close());
            reservation
                    .compose(accepted -> {
                        Future<Object> mutation = operation.kind() == OperationKind.REGISTER
                                ? registry.register(ownedPayload.definition(), ownedPayload.secret())
                                        .map(result -> (Object) result)
                                : registry.test(ownedPayload.definition(), ownedPayload.secret())
                                        .map(result -> (Object) result);
                        return completeMutation(
                                mutation, accepted, operation.kind(), activityContext(intent));
                    })
                    .onSuccess(result -> writeBodySuccess(
                            request, operation.kind(), result, correlationId))
                    .onFailure(failure -> writeProblem(request, failure, correlationId));
        } catch (Throwable failure) {
            if (payload != null) {
                payload.secret().close();
            }
            writeProblem(request, failure, correlationId);
        } finally {
            java.util.Arrays.fill(body, (byte) 0);
        }
    }

    private <T> Future<T> completeMutation(
            Future<T> mutation,
            ManagementAuditReservation reservation,
            OperationKind kind,
            ActivityContext activityContext) {
        return mutation
                .compose(result -> completeOutcome(
                        reservation,
                        new ManagementAuditOutcome(
                                ManagementAuditTerminalOutcome.SUCCEEDED,
                                kind.successCode,
                                null)).map(ignored -> {
                                    publishActivity(
                                            activityContext, kind,
                                            ManagementAuditTerminalOutcome.SUCCEEDED);
                                    return result;
                                }))
                .recover(failure -> {
                    if (failure instanceof dev.mars.peegeeq.cache.api.management.ManagementAuditException) {
                        return Future.failedFuture(failure);
                    }
                    return completeOutcome(
                                    reservation,
                                    new ManagementAuditOutcome(
                                            ManagementAuditTerminalOutcome.FAILED,
                                            kind.failureCode,
                                            null))
                            .compose(ignored -> {
                                publishActivity(
                                        activityContext, kind,
                                        ManagementAuditTerminalOutcome.FAILED);
                                return Future.failedFuture(failure);
                            });
                });
    }

    private Future<Object> executeAndComplete(
            Operation operation,
            ManagementAuditReservation reservation,
            ActivityContext activityContext) {
        Future<Object> mutation = switch (operation.kind()) {
            case TEST_REGISTERED -> registry.testRegistered(operation.setupId()).map(result -> (Object) result);
            case CONNECT -> registry.connect(operation.setupId()).map(result -> (Object) result);
            case DETACH -> registry.detach(operation.setupId()).map((Object) null);
            case FORGET -> registry.forget(operation.setupId()).map((Object) null);
            case TEST_UNREGISTERED, REGISTER -> throw new IllegalStateException(
                    "Body operations use the body mutation path");
        };
        return mutation
                .compose(result -> completeOutcome(
                        reservation,
                        new ManagementAuditOutcome(
                                ManagementAuditTerminalOutcome.SUCCEEDED,
                                operation.kind().successCode,
                                null)).map(ignored -> {
                                    publishActivity(
                                            activityContext,
                                            operation.kind(),
                                            ManagementAuditTerminalOutcome.SUCCEEDED);
                                    return result;
                                }))
                .recover(failure -> {
                    if (failure instanceof dev.mars.peegeeq.cache.api.management.ManagementAuditException) {
                        return Future.failedFuture(failure);
                    }
                    return completeOutcome(
                                    reservation,
                                    new ManagementAuditOutcome(
                                            ManagementAuditTerminalOutcome.FAILED,
                                            operation.kind().failureCode,
                                            null))
                            .compose(ignored -> {
                                publishActivity(
                                        activityContext,
                                        operation.kind(),
                                        ManagementAuditTerminalOutcome.FAILED);
                                return Future.failedFuture(failure);
                            });
                });
    }

    private static ActivityContext activityContext(ManagementAuditIntent intent) {
        return new ActivityContext(
                intent.eventId(),
                intent.actor(),
                intent.setupId(),
                intent.correlationId());
    }

    private void publishActivity(
            ActivityContext context,
            OperationKind kind,
            ManagementAuditTerminalOutcome outcome) {
        if (audit instanceof ManagementActivityPublishingAuditSink) return;
        try {
            activity.publish(new ManagementActivityEvent(
                    context.eventId(),
                    clock.instant(),
                    context.actor(),
                    kind.auditAction.name(),
                    outcome,
                    context.setupId(),
                    null,
                    new ManagementActivityResource(
                            ManagementActivityResourceType.SETUP, context.setupId()),
                    outcome == ManagementAuditTerminalOutcome.SUCCEEDED
                            ? kind.successCode : kind.failureCode,
                    context.correlationId()));
        } catch (RuntimeException failure) {
            LOGGER.warn("action={} outcome={} activity.publish.failed",
                    kind.auditAction, outcome, failure);
        }
    }

    private void requireAuditReady() {
        if (!audit.isMutationReady()) {
            throw new dev.mars.peegeeq.cache.api.management.ManagementAuditException(
                    "Management audit is not accepting privileged operations");
        }
    }

    private Future<Void> completeOutcome(
            ManagementAuditReservation reservation,
            ManagementAuditOutcome outcome) {
        try {
            return audit.complete(reservation, outcome).recover(failure ->
                    Future.failedFuture(new ManagementAuditOutcomeException(
                            "Management audit terminal outcome is unavailable", failure)));
        } catch (RuntimeException failure) {
            return Future.failedFuture(new ManagementAuditOutcomeException(
                    "Management audit terminal outcome is unavailable", failure));
        }
    }

    private void writeSuccess(
            HttpServerRequest request,
            Operation operation,
            Object result,
            String correlationId) {
        var response = request.response()
                .setStatusCode(operation.kind().status)
                .putHeader("x-correlation-id", correlationId);
        if (result == null) {
            response.end();
            return;
        }
        ObjectNode body = operation.kind() == OperationKind.TEST_REGISTERED
                ? connectionTest((SetupConnectionTest) result)
                : summary((SetupSummary) result);
        response.putHeader("content-type", "application/json; charset=utf-8")
                .end(body.toString());
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
        node.putNull("lastHealth");
        return node;
    }

    private void writeBodySuccess(
            HttpServerRequest request,
            OperationKind kind,
            Object result,
            String correlationId) {
        ObjectNode body = switch (kind) {
            case TEST_UNREGISTERED -> connectionTest((SetupConnectionTest) result);
            case REGISTER -> summary((SetupSummary) result);
            default -> throw new IllegalStateException("Unexpected body operation");
        };
        request.response()
                .setStatusCode(kind.status)
                .putHeader("content-type", "application/json; charset=utf-8")
                .putHeader("x-correlation-id", correlationId)
                .end(body.toString());
    }

    private ObjectNode connectionTest(SetupConnectionTest test) {
        ObjectNode node = json.createObjectNode();
        node.put("databaseReachable", test.databaseReachable());
        node.put("schemaState", test.schemaState().name());
        node.put("migrationVersion", test.migrationVersion());
        node.put("latencyMillis", test.latencyMillis());
        putFeatures(node.putObject("capabilities"), test.capabilities());
        ObjectNode limits = node.putObject("limits");
        limits.put("pubSubChannelMaxBytes", test.limits().pubSubChannelMaxBytes());
        limits.put("pubSubPayloadMaxBytes", test.limits().pubSubPayloadMaxBytes());
        limits.put("maximumValueBytes", test.limits().maximumValueBytes());
        return node;
    }

    private static void putFeatures(
            ObjectNode node, SetupCapabilities.Features features) {
        node.put("namespaceInspection", features.namespaceInspection());
        node.put("expiredEntryInspection", features.expiredEntryInspection());
        node.put("counterInspection", features.counterInspection());
        node.put("lockInspection", features.lockInspection());
        node.put("forcedLockRelease", features.forcedLockRelease());
        node.put("bulkEntryDelete", features.bulkEntryDelete());
        node.put("bulkCounterDelete", features.bulkCounterDelete());
        node.put("pubSub", features.pubSub());
        node.put("databaseStatistics", features.databaseStatistics());
        node.put("entryValueReveal", features.entryValueReveal());
        node.put("lockOwnerReveal", features.lockOwnerReveal());
        node.put("pubSubPayloadReveal", features.pubSubPayloadReveal());
    }

    private SetupPayload parsePayload(byte[] body, boolean registration) {
        try {
            JsonNode root = json.readTree(body);
            Set<String> expected = registration
                    ? Set.of("setupId", "displayName", "host", "port", "database", "schema",
                            "username", "password", "sslMode", "trustProfileId", "poolMaxSize")
                    : Set.of("host", "port", "database", "schema", "username", "password",
                            "sslMode", "trustProfileId", "poolMaxSize");
            if (root == null || !root.isObject() || fieldNames(root).equals(expected) == false) {
                throw validationFailure();
            }
            String sslMode = requiredText(root, "sslMode");
            if (!sslMode.equals("VERIFY_FULL")) {
                throw validationFailure();
            }
            String password = requiredText(root, "password");
            if (password.length() > 4_096) {
                throw validationFailure();
            }
            SetupDefinition definition = new SetupDefinition(
                    registration ? requiredText(root, "setupId") : "connection-test",
                    registration ? requiredText(root, "displayName") : "Unregistered connection test",
                    new SetupTarget(
                            requiredText(root, "host"),
                            requiredInt(root, "port"),
                            requiredText(root, "trustProfileId"),
                            TlsMode.VERIFY_FULL),
                    requiredText(root, "database"),
                    requiredText(root, "schema"),
                    requiredText(root, "username"),
                    requiredInt(root, "poolMaxSize"),
                    SetupSource.UI_SESSION,
                    null);
            return new SetupPayload(
                    definition,
                    SetupSecret.owned(password.getBytes(StandardCharsets.UTF_8)));
        } catch (ManagementProtocolException failure) {
            throw failure;
        } catch (Exception failure) {
            throw validationFailure();
        }
    }

    private static Set<String> fieldNames(JsonNode root) {
        Set<String> names = new HashSet<>();
        root.fieldNames().forEachRemaining(names::add);
        return names;
    }

    private static String requiredText(JsonNode root, String name) {
        JsonNode value = root.get(name);
        if (value == null || !value.isTextual() || value.textValue().isEmpty()) {
            throw validationFailure();
        }
        return value.textValue();
    }

    private static int requiredInt(JsonNode root, String name) {
        JsonNode value = root.get(name);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToInt()) {
            throw validationFailure();
        }
        return value.intValue();
    }

    private static ManagementProtocolException validationFailure() {
        return new ManagementProtocolException(
                400, "VALIDATION_FAILED", "Request body does not match the setup contract");
    }

    private static long contentLength(String value) {
        if (value == null) {
            return -1;
        }
        try {
            long length = Long.parseLong(value);
            if (length < 0) {
                throw validationFailure();
            }
            return length;
        } catch (NumberFormatException failure) {
            throw validationFailure();
        }
    }

    private static Operation match(String method, String path) {
        if (method.equals("POST")) {
            if (path.equals("/api/v1/setups/actions/test")) {
                return new Operation(null, OperationKind.TEST_UNREGISTERED);
            }
            if (path.equals("/api/v1/setups")) {
                return new Operation(null, OperationKind.REGISTER);
            }
            Matcher matcher = ACTION.matcher(path);
            if (matcher.matches()) {
                OperationKind kind = switch (matcher.group(2)) {
                    case "connect" -> OperationKind.CONNECT;
                    case "test" -> OperationKind.TEST_REGISTERED;
                    default -> OperationKind.DETACH;
                };
                return new Operation(matcher.group(1), kind);
            }
        }
        if (method.equals("DELETE")) {
            Matcher matcher = FORGET.matcher(path);
            if (matcher.matches()) {
                return new Operation(matcher.group(1), OperationKind.FORGET);
            }
        }
        return null;
    }

    private static void requireOperator(AuthenticatedManagementRequest authenticated) {
        if (!authenticated.identity().roles().contains("operator")) {
            throw new ManagementSecurityException(
                    403, "AUTHORIZATION_FAILED", "Operator role is required");
        }
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

    private enum OperationKind {
        TEST_UNREGISTERED(
                ManagementAuditAction.TEST_SETUP,
                "SETUP_TEST_SUCCEEDED",
                "SETUP_TEST_FAILED",
                200,
                true),
        REGISTER(
                ManagementAuditAction.REGISTER_SETUP,
                "SETUP_REGISTERED",
                "SETUP_REGISTER_FAILED",
                201,
                true),
        TEST_REGISTERED(
                ManagementAuditAction.TEST_SETUP,
                "SETUP_TEST_SUCCEEDED",
                "SETUP_TEST_FAILED",
                200,
                false),
        CONNECT(
                ManagementAuditAction.CONNECT_SETUP,
                "SETUP_CONNECTED",
                "SETUP_CONNECT_FAILED",
                200,
                false),
        DETACH(
                ManagementAuditAction.DETACH_SETUP,
                "SETUP_DETACHED",
                "SETUP_DETACH_FAILED",
                204,
                false),
        FORGET(
                ManagementAuditAction.FORGET_SETUP,
                "SETUP_FORGOTTEN",
                "SETUP_FORGET_FAILED",
                204,
                false);

        private final ManagementAuditAction auditAction;
        private final String successCode;
        private final String failureCode;
        private final int status;
        private final boolean body;

        OperationKind(
                ManagementAuditAction auditAction,
                String successCode,
                String failureCode,
                int status,
                boolean body) {
            this.auditAction = auditAction;
            this.successCode = successCode;
            this.failureCode = failureCode;
            this.status = status;
            this.body = body;
        }
    }

    private record Operation(String setupId, OperationKind kind) {
    }

    private record ActivityContext(
            String eventId,
            String actor,
            String setupId,
            String correlationId) {
    }

    private record SetupPayload(SetupDefinition definition, SetupSecret secret) {
    }
}
