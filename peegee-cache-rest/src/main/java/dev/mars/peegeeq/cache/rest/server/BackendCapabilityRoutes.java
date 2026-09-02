package dev.mars.peegeeq.cache.rest.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.mars.peegeeq.cache.api.management.ManagementAuditAction;
import dev.mars.peegeeq.cache.api.management.ManagementAuditException;
import dev.mars.peegeeq.cache.api.management.ManagementAuditFingerprinter;
import dev.mars.peegeeq.cache.api.management.ManagementAuditIntent;
import dev.mars.peegeeq.cache.api.management.ManagementAuditOutcome;
import dev.mars.peegeeq.cache.api.management.ManagementAuditOutcomeException;
import dev.mars.peegeeq.cache.api.management.ManagementAuditReservation;
import dev.mars.peegeeq.cache.api.management.ManagementAuditSink;
import dev.mars.peegeeq.cache.api.management.ManagementAuditTerminalOutcome;
import dev.mars.peegeeq.cache.api.management.ManagementResourceType;
import dev.mars.peegeeq.cache.api.model.CacheEntry;
import dev.mars.peegeeq.cache.api.model.CacheKey;
import dev.mars.peegeeq.cache.api.model.CacheSetRequest;
import dev.mars.peegeeq.cache.api.model.CacheSetResult;
import dev.mars.peegeeq.cache.api.model.CacheValue;
import dev.mars.peegeeq.cache.api.model.LockAcquireRequest;
import dev.mars.peegeeq.cache.api.model.LockAcquireResult;
import dev.mars.peegeeq.cache.api.model.LockKey;
import dev.mars.peegeeq.cache.api.model.LockReleaseRequest;
import dev.mars.peegeeq.cache.api.model.LockRenewRequest;
import dev.mars.peegeeq.cache.api.model.MetricsSnapshot;
import dev.mars.peegeeq.cache.api.model.ScanRequest;
import dev.mars.peegeeq.cache.api.model.ScanResult;
import dev.mars.peegeeq.cache.api.model.SetMode;
import dev.mars.peegeeq.cache.api.model.ValueType;
import dev.mars.peegeeq.cache.rest.protocol.ManagementIdentifierCodec;
import dev.mars.peegeeq.cache.rest.protocol.ManagementProblem;
import dev.mars.peegeeq.cache.rest.protocol.ManagementProtocolException;
import dev.mars.peegeeq.cache.rest.protocol.ManagementWireRules;
import dev.mars.peegeeq.cache.rest.security.BrowserRequestSecurity;
import dev.mars.peegeeq.cache.rest.security.ManagementSecurityException;
import dev.mars.peegeeq.cache.rest.security.ManagementRateLimiter;
import dev.mars.peegeeq.cache.rest.security.RateLimitAction;
import io.vertx.core.Future;
import io.vertx.core.http.HttpServerRequest;

import java.nio.charset.StandardCharsets;
import java.net.InetAddress;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Routes that expose public PeeGeeQ Cache service capabilities not represented by the
 * original management-only contract. Sensitive values and owner tokens are always
 * operator-only, no-store, CSRF protected, and durably audited.
 */
public final class BackendCapabilityRoutes implements ManagementRequestRouter {

    private static final Pattern ENTRY_EXISTS = Pattern.compile(
            "^/api/v1/setups/([^/]+)/namespaces/([^/]+)/entries/([^/]+)/exists$");
    private static final Pattern CACHE_METRICS = Pattern.compile(
            "^/api/v1/setups/([^/]+)/cache-metrics$");
    private static final Pattern BATCH_GET = Pattern.compile(
            "^/api/v1/setups/([^/]+)/entries/batch-get$");
    private static final Pattern BATCH_SET = Pattern.compile(
            "^/api/v1/setups/([^/]+)/entries/batch-set$");
    private static final Pattern SCAN = Pattern.compile(
            "^/api/v1/setups/([^/]+)/entries/scan$");
    private static final Pattern LOCK_ACTION = Pattern.compile(
            "^/api/v1/setups/([^/]+)/namespaces/([^/]+)/locks/([^/]+)/(acquire|renew|release|ownership)$");
    private static final Pattern SETUP_ID = Pattern.compile("[a-z][a-z0-9-]{0,62}");
    private static final int MAX_BATCH_ITEMS = 1_000;
    private static final int MAX_SCAN_ITEMS = 200;
    private static final int MAX_REQUEST_BYTES = 10 * 1024 * 1024 + 64 * 1024;
    private static final String DEFAULT_REASON = "INTERACTIVE_CONSOLE_OPERATION";

    private final SetupRegistry registry;
    private final ManagementRequestAuthenticator authenticator;
    private final BrowserRequestSecurity browserSecurity;
    private final ManagementAuditSink audit;
    private final ManagementAuditFingerprinter fingerprinter;
    private final ManagementRateLimiter rateLimiter;
    private final Clock clock;
    private final ObjectMapper json = new ObjectMapper();

    public BackendCapabilityRoutes(
            SetupRegistry registry,
            ManagementRequestAuthenticator authenticator,
            BrowserRequestSecurity browserSecurity,
            ManagementAuditSink audit,
            ManagementAuditFingerprinter fingerprinter,
            ManagementRateLimiter rateLimiter,
            Clock clock) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.authenticator = Objects.requireNonNull(authenticator, "authenticator");
        this.browserSecurity = Objects.requireNonNull(browserSecurity, "browserSecurity");
        this.audit = Objects.requireNonNull(audit, "audit");
        this.fingerprinter = Objects.requireNonNull(fingerprinter, "fingerprinter");
        this.rateLimiter = Objects.requireNonNull(rateLimiter, "rateLimiter");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public boolean route(HttpServerRequest request) {
        Matcher exists = ENTRY_EXISTS.matcher(request.path());
        if (request.method().name().equals("GET") && exists.matches()) {
            routeExists(request, exists);
            return true;
        }
        Matcher metrics = CACHE_METRICS.matcher(request.path());
        if (request.method().name().equals("GET") && metrics.matches()) {
            routeMetrics(request, metrics);
            return true;
        }
        if (!request.method().name().equals("POST")) {
            return false;
        }
        Matcher batchGet = BATCH_GET.matcher(request.path());
        if (batchGet.matches()) {
            routeBody(request, batchGet.group(1), Operation.BATCH_GET, null);
            return true;
        }
        Matcher batchSet = BATCH_SET.matcher(request.path());
        if (batchSet.matches()) {
            routeBody(request, batchSet.group(1), Operation.BATCH_SET, null);
            return true;
        }
        Matcher scan = SCAN.matcher(request.path());
        if (scan.matches()) {
            routeBody(request, scan.group(1), Operation.SCAN, null);
            return true;
        }
        Matcher lock = LOCK_ACTION.matcher(request.path());
        if (lock.matches()) {
            RawLockTarget target = new RawLockTarget(lock.group(2), lock.group(3), lock.group(4));
            routeBody(request, lock.group(1), Operation.LOCK, target);
            return true;
        }
        return false;
    }

    private void routeExists(HttpServerRequest request, Matcher matcher) {
        String correlationId = correlationId(request);
        try {
            authenticator.authenticate(request);
            requireJsonAccept(request.getHeader("Accept"));
            String setupId = requireSetupId(matcher.group(1));
            CacheKey key = new CacheKey(decodeNamespace(matcher.group(2)), decodeKey(matcher.group(3)));
            registry.cache(setupId).cache().exists(key)
                    .onSuccess(exists -> {
                        ObjectNode response = json.createObjectNode();
                        response.put("exists", exists);
                        writeJson(request, 200, response, correlationId, false);
                    })
                    .onFailure(failure -> writeProblem(request, failure, correlationId));
        } catch (Throwable failure) {
            writeProblem(request, failure, correlationId);
        }
    }

    private void routeMetrics(HttpServerRequest request, Matcher matcher) {
        String correlationId = correlationId(request);
        try {
            authenticator.authenticate(request);
            requireJsonAccept(request.getHeader("Accept"));
            String setupId = requireSetupId(matcher.group(1));
            writeJson(
                    request,
                    200,
                    metrics(registry.cache(setupId).admin().metrics()),
                    correlationId,
                    false);
        } catch (Throwable failure) {
            writeProblem(request, failure, correlationId);
        }
    }

    private void routeBody(
            HttpServerRequest request,
            String rawSetupId,
            Operation operation,
            RawLockTarget rawLockTarget) {
        String correlationId = correlationId(request);
        try {
            AuthenticatedManagementRequest authenticated = authenticator.authenticate(request);
            requireOperator(authenticated);
            requireAuditReady();
            rateLimiter.acquire(
                    RateLimitAction.BACKEND_OPERATION,
                    authenticated.identity().actor(),
                    InetAddress.getByName(authenticated.identity().sourceAddress()));
            browserSecurity.validateStateChange(
                    authenticated.sessionCookie(),
                    request.getHeader("X-PeeGeeQ-CSRF"),
                    authenticated.sessionCookie(),
                    authenticated.csrfToken(),
                    request.getHeader("Origin"));
            ManagementWireRules.requireJsonContentType(request.getHeader("Content-Type"));
            requireJsonAccept(request.getHeader("Accept"));
            String setupId = requireSetupId(rawSetupId);
            LockTarget lockTarget = rawLockTarget == null ? null : new LockTarget(
                    new LockKey(
                            decodeNamespace(rawLockTarget.encodedNamespace()),
                            decodeKey(rawLockTarget.encodedKey())),
                    rawLockTarget.action());
            long contentLength = contentLength(request.getHeader("Content-Length"));
            ManagementWireRules.requireRequestSize(contentLength, MAX_REQUEST_BYTES);
            request.body()
                    .onSuccess(body -> executeBody(
                            request,
                            setupId,
                            operation,
                            lockTarget,
                            authenticated,
                            correlationId,
                            body.getBytes()))
                    .onFailure(failure -> writeProblem(request, failure, correlationId));
        } catch (Throwable failure) {
            writeProblem(request, failure, correlationId);
        }
    }

    private void executeBody(
            HttpServerRequest request,
            String setupId,
            Operation operation,
            LockTarget lockTarget,
            AuthenticatedManagementRequest authenticated,
            String correlationId,
            byte[] body) {
        try {
            ManagementWireRules.requireRequestSize(body.length, MAX_REQUEST_BYTES);
            switch (operation) {
                case BATCH_GET -> executeBatchGet(
                        request, setupId, authenticated, correlationId, parseBatchGet(body));
                case BATCH_SET -> executeBatchSet(
                        request, setupId, authenticated, correlationId, parseBatchSet(body));
                case SCAN -> executeScan(
                        request, setupId, authenticated, correlationId, parseScan(body));
                case LOCK -> executeLock(
                        request,
                        setupId,
                        authenticated,
                        correlationId,
                        Objects.requireNonNull(lockTarget, "lockTarget"),
                        parseObject(body));
            }
        } catch (Throwable failure) {
            writeProblem(request, failure, correlationId);
        } finally {
            java.util.Arrays.fill(body, (byte) 0);
        }
    }

    private void executeBatchGet(
            HttpServerRequest request,
            String setupId,
            AuthenticatedManagementRequest authenticated,
            String correlationId,
            BatchGet parsed) {
        audited(
                authenticated,
                correlationId,
                setupId,
                ManagementAuditAction.BATCH_GET_ENTRIES,
                ManagementResourceType.BULK_SELECTION,
                Map.of(),
                parsed.reason(),
                "ENTRIES_BATCH_REVEALED",
                () -> registry.cache(setupId).cache().getMany(parsed.keys()))
                .onSuccess(results -> writeBatchGet(request, parsed.keys(), results, correlationId))
                .onFailure(failure -> writeProblem(request, failure, correlationId));
    }

    private void executeBatchSet(
            HttpServerRequest request,
            String setupId,
            AuthenticatedManagementRequest authenticated,
            String correlationId,
            List<CacheSetRequest> entries) {
        audited(
                authenticated,
                correlationId,
                setupId,
                ManagementAuditAction.BATCH_SET_ENTRIES,
                ManagementResourceType.BULK_SELECTION,
                Map.of(),
                null,
                "ENTRIES_BATCH_SET",
                () -> registry.cache(setupId).cache().setMany(entries))
                .onSuccess(results -> writeBatchSet(request, entries, results, correlationId))
                .onFailure(failure -> writeProblem(request, failure, correlationId));
    }

    private void executeScan(
            HttpServerRequest request,
            String setupId,
            AuthenticatedManagementRequest authenticated,
            String correlationId,
            ParsedScan parsed) {
        audited(
                authenticated,
                correlationId,
                setupId,
                ManagementAuditAction.SCAN_ENTRY_VALUES,
                ManagementResourceType.NAMESPACE,
                Map.of("namespace", fingerprinter.fingerprint(parsed.request().namespace())),
                parsed.reason(),
                "ENTRIES_SCANNED",
                () -> registry.cache(setupId).scan().scan(parsed.request()))
                .onSuccess(result -> writeScan(request, result, correlationId))
                .onFailure(failure -> writeProblem(request, failure, correlationId));
    }

    private void executeLock(
            HttpServerRequest request,
            String setupId,
            AuthenticatedManagementRequest authenticated,
            String correlationId,
            LockTarget target,
            JsonNode body) {
        String ownerToken = requiredBoundedText(body, "ownerToken", 4_096);
        Map<String, dev.mars.peegeeq.cache.api.management.ManagementAuditFingerprint> identifiers = Map.of(
                "namespace", fingerprinter.fingerprint(target.key().namespace()),
                "key", fingerprinter.fingerprint(target.key().key()));
        switch (target.action()) {
            case "acquire" -> {
                requireFields(body, Set.of(
                        "ownerToken", "leaseTtlMillis", "reentrantForSameOwner", "issueFencingToken"));
                LockAcquireRequest acquire = new LockAcquireRequest(
                        target.key(),
                        ownerToken,
                        Duration.ofMillis(requiredPositiveLong(body.get("leaseTtlMillis"))),
                        requiredBoolean(body, "reentrantForSameOwner"),
                        requiredBoolean(body, "issueFencingToken"));
                audited(
                        authenticated, correlationId, setupId, ManagementAuditAction.ACQUIRE_LOCK,
                        ManagementResourceType.LOCK, identifiers, null, "LOCK_ACQUIRE_ATTEMPTED",
                        () -> registry.cache(setupId).locks().acquire(acquire))
                        .onSuccess(result -> writeAcquire(request, result, correlationId))
                        .onFailure(failure -> writeProblem(request, failure, correlationId));
            }
            case "renew" -> {
                requireFields(body, Set.of("ownerToken", "leaseTtlMillis"));
                LockRenewRequest renew = new LockRenewRequest(
                        target.key(), ownerToken,
                        Duration.ofMillis(requiredPositiveLong(body.get("leaseTtlMillis"))));
                audited(
                        authenticated, correlationId, setupId, ManagementAuditAction.RENEW_LOCK,
                        ManagementResourceType.LOCK, identifiers, null, "LOCK_RENEWED",
                        () -> registry.cache(setupId).locks().renew(renew))
                        .onSuccess(result -> writeBoolean(request, "renewed", result, correlationId))
                        .onFailure(failure -> writeProblem(request, failure, correlationId));
            }
            case "release" -> {
                requireFields(body, Set.of("ownerToken"));
                audited(
                        authenticated, correlationId, setupId, ManagementAuditAction.RELEASE_LOCK,
                        ManagementResourceType.LOCK, identifiers, null, "LOCK_RELEASED_BY_OWNER",
                        () -> registry.cache(setupId).locks().release(
                                new LockReleaseRequest(target.key(), ownerToken)))
                        .onSuccess(result -> writeBoolean(request, "released", result, correlationId))
                        .onFailure(failure -> writeProblem(request, failure, correlationId));
            }
            case "ownership" -> {
                requireFields(body, Set.of("ownerToken"));
                audited(
                        authenticated, correlationId, setupId,
                        ManagementAuditAction.CHECK_LOCK_OWNERSHIP,
                        ManagementResourceType.LOCK, identifiers, null, "LOCK_OWNERSHIP_CHECKED",
                        () -> registry.cache(setupId).locks().isHeldBy(target.key(), ownerToken))
                        .onSuccess(result -> writeBoolean(request, "heldByOwner", result, correlationId))
                        .onFailure(failure -> writeProblem(request, failure, correlationId));
            }
            default -> throw validationFailure();
        }
    }

    private BatchGet parseBatchGet(byte[] body) {
        JsonNode root = parseObject(body);
        requireFields(root, Set.of("keys", "reason"));
        List<CacheKey> keys = parseKeys(root.get("keys"));
        return new BatchGet(keys, boundedReason(requiredText(root, "reason")));
    }

    private List<CacheSetRequest> parseBatchSet(byte[] body) {
        JsonNode root = parseObject(body);
        requireFields(root, Set.of("entries"));
        JsonNode array = root.get("entries");
        if (array == null || !array.isArray() || array.isEmpty() || array.size() > MAX_BATCH_ITEMS) {
            throw validationFailure();
        }
        List<CacheSetRequest> entries = new ArrayList<>(array.size());
        Set<CacheKey> unique = new java.util.HashSet<>();
        for (JsonNode node : array) {
            requireFields(node, Set.of(
                    "namespace", "key", "value", "ttlMillis", "setMode",
                    "expectedVersion", "returnPreviousValue"));
            CacheKey key = validatedCacheKey(
                    requiredText(node, "namespace"), requiredText(node, "key"));
            if (!unique.add(key)) {
                throw validationFailure();
            }
            SetMode mode;
            try {
                mode = SetMode.valueOf(requiredText(node, "setMode"));
            } catch (IllegalArgumentException failure) {
                throw validationFailure();
            }
            Long expectedVersion = nullableNonNegativeDecimal(node.get("expectedVersion"));
            if ((mode == SetMode.ONLY_IF_VERSION_MATCHES) != (expectedVersion != null)) {
                throw validationFailure();
            }
            Long ttlMillis = nullablePositiveLong(node.get("ttlMillis"));
            entries.add(new CacheSetRequest(
                    key,
                    cacheValue(node.get("value")),
                    ttlMillis == null ? null : Duration.ofMillis(ttlMillis),
                    mode,
                    expectedVersion,
                    requiredBoolean(node, "returnPreviousValue")));
        }
        return List.copyOf(entries);
    }

    private ParsedScan parseScan(byte[] body) {
        JsonNode root = parseObject(body);
        requireFields(root, Set.of(
                "namespace", "prefix", "cursor", "limit", "includeValues",
                "includeExpired", "reason"));
        String namespace = validatedNamespace(requiredText(root, "namespace"));
        String prefix = nullableBoundedText(root.get("prefix"), 1_024, true);
        String cursor = nullableBoundedText(root.get("cursor"), 4_096, true);
        int limit = requiredPositiveInt(root.get("limit"), MAX_SCAN_ITEMS);
        boolean includeValues = requiredBoolean(root, "includeValues");
        boolean includeExpired = requiredBoolean(root, "includeExpired");
        return new ParsedScan(
                new ScanRequest(namespace, prefix, cursor, limit, includeValues, includeExpired),
                boundedReason(requiredText(root, "reason")));
    }

    private List<CacheKey> parseKeys(JsonNode array) {
        if (array == null || !array.isArray() || array.isEmpty() || array.size() > MAX_BATCH_ITEMS) {
            throw validationFailure();
        }
        List<CacheKey> keys = new ArrayList<>(array.size());
        Set<CacheKey> unique = new java.util.HashSet<>();
        for (JsonNode node : array) {
            requireFields(node, Set.of("namespace", "key"));
            CacheKey key = validatedCacheKey(
                    requiredText(node, "namespace"), requiredText(node, "key"));
            if (!unique.add(key)) {
                throw validationFailure();
            }
            keys.add(key);
        }
        return List.copyOf(keys);
    }

    private JsonNode parseObject(byte[] body) {
        try {
            JsonNode root = json.readTree(body);
            if (root == null || !root.isObject()) {
                throw validationFailure();
            }
            return root;
        } catch (ManagementProtocolException failure) {
            throw failure;
        } catch (Exception failure) {
            throw validationFailure();
        }
    }

    private <T> Future<T> audited(
            AuthenticatedManagementRequest authenticated,
            String correlationId,
            String setupId,
            ManagementAuditAction action,
            ManagementResourceType resourceType,
            Map<String, dev.mars.peegeeq.cache.api.management.ManagementAuditFingerprint> identifiers,
            String reason,
            String successCode,
            Supplier<Future<T>> operation) {
        ManagementAuditIntent intent = new ManagementAuditIntent(
                UUID.randomUUID().toString(),
                clock.instant(),
                authenticated.identity().actor(),
                authenticated.identity().roles(),
                action,
                setupId,
                resourceType,
                identifiers,
                null,
                reason,
                authenticated.identity().sourceAddress(),
                correlationId);
        return reserve(intent).compose(reservation -> {
            final Future<T> execution;
            try {
                execution = operation.get();
            } catch (RuntimeException failure) {
                return complete(reservation, new ManagementAuditOutcome(
                        ManagementAuditTerminalOutcome.FAILED, "BACKEND_OPERATION_FAILED", null))
                        .compose(ignored -> Future.failedFuture(failure));
            }
            return execution.transform(result -> {
                if (result.succeeded()) {
                    return complete(reservation, new ManagementAuditOutcome(
                            ManagementAuditTerminalOutcome.SUCCEEDED, successCode, null))
                            .map(result.result());
                }
                return complete(reservation, new ManagementAuditOutcome(
                        ManagementAuditTerminalOutcome.FAILED, "BACKEND_OPERATION_FAILED", null))
                        .compose(ignored -> Future.failedFuture(result.cause()));
            });
        });
    }

    private Future<ManagementAuditReservation> reserve(ManagementAuditIntent intent) {
        try {
            return audit.reserveIntent(intent).recover(failure -> Future.failedFuture(
                    new ManagementAuditException("Management audit intent reservation failed", failure)));
        } catch (RuntimeException failure) {
            return Future.failedFuture(new ManagementAuditException(
                    "Management audit intent reservation failed", failure));
        }
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

    private void writeBatchGet(
            HttpServerRequest request,
            List<CacheKey> keys,
            Map<CacheKey, Optional<CacheEntry>> results,
            String correlationId) {
        ObjectNode response = json.createObjectNode();
        ArrayNode items = response.putArray("items");
        for (CacheKey key : keys) {
            ObjectNode item = items.addObject();
            item.put("namespace", key.namespace());
            item.put("key", key.key());
            Optional<CacheEntry> entry = results.getOrDefault(key, Optional.empty());
            item.put("found", entry.isPresent());
            if (entry.isPresent()) {
                item.set("entry", entry(entry.orElseThrow(), true));
            } else {
                item.putNull("entry");
            }
        }
        writeJson(request, 200, response, correlationId, true);
    }

    private void writeBatchSet(
            HttpServerRequest request,
            List<CacheSetRequest> entries,
            Map<CacheKey, CacheSetResult> results,
            String correlationId) {
        ObjectNode response = json.createObjectNode();
        ArrayNode items = response.putArray("items");
        for (CacheSetRequest entry : entries) {
            CacheSetResult result = results.get(entry.key());
            ObjectNode item = items.addObject();
            item.put("namespace", entry.key().namespace());
            item.put("key", entry.key().key());
            item.put("applied", result.applied());
            item.put("newVersion", Long.toString(result.newVersion()));
            if (result.previousEntry() == null) {
                item.putNull("previousEntry");
            } else {
                item.set("previousEntry", entry(result.previousEntry(), true));
            }
        }
        writeJson(request, 200, response, correlationId, true);
    }

    private void writeScan(HttpServerRequest request, ScanResult result, String correlationId) {
        ObjectNode response = json.createObjectNode();
        ArrayNode entries = response.putArray("entries");
        for (CacheEntry entry : result.entries()) {
            entries.add(entry(entry, entry.value() != null));
        }
        if (result.nextCursor() == null) {
            response.putNull("nextCursor");
        } else {
            response.put("nextCursor", result.nextCursor());
        }
        response.put("hasMore", result.hasMore());
        writeJson(request, 200, response, correlationId, true);
    }

    private void writeAcquire(
            HttpServerRequest request,
            LockAcquireResult result,
            String correlationId) {
        ObjectNode response = json.createObjectNode();
        response.put("acquired", result.acquired());
        response.put("namespace", result.key().namespace());
        response.put("key", result.key().key());
        if (result.ownerToken() == null) response.putNull("ownerToken");
        else response.put("ownerToken", result.ownerToken());
        if (result.fencingToken() == null) response.putNull("fencingToken");
        else response.put("fencingToken", Long.toString(result.fencingToken()));
        if (result.leaseExpiresAt() == null) response.putNull("leaseExpiresAt");
        else response.put("leaseExpiresAt", result.leaseExpiresAt().toString());
        writeJson(request, 200, response, correlationId, true);
    }

    private void writeBoolean(
            HttpServerRequest request,
            String field,
            boolean result,
            String correlationId) {
        ObjectNode response = json.createObjectNode();
        response.put(field, result);
        writeJson(request, 200, response, correlationId, true);
    }

    private ObjectNode entry(CacheEntry entry, boolean includeValue) {
        ObjectNode node = json.createObjectNode();
        node.put("namespace", entry.key().namespace());
        node.put("key", entry.key().key());
        node.put("version", Long.toString(entry.version()));
        node.put("createdAt", entry.createdAt().toString());
        node.put("updatedAt", entry.updatedAt().toString());
        if (entry.expiresAt() == null) node.putNull("expiresAt");
        else node.put("expiresAt", entry.expiresAt().toString());
        node.put("hitCount", Long.toString(entry.hitCount()));
        if (entry.lastAccessedAt() == null) node.putNull("lastAccessedAt");
        else node.put("lastAccessedAt", entry.lastAccessedAt().toString());
        if (includeValue && entry.value() != null) node.set("value", value(entry.value()));
        else node.putNull("value");
        return node;
    }

    private ObjectNode value(CacheValue value) {
        ObjectNode node = json.createObjectNode();
        node.put("type", value.type().name());
        switch (value.type()) {
            case STRING, JSON -> node.put("text", value.asString());
            case LONG -> node.put("decimal", Long.toString(value.asLong()));
            case BYTES -> node.put(
                    "base64", Base64.getEncoder().encodeToString(value.binaryValue().getBytes()));
        }
        return node;
    }

    private ObjectNode metrics(MetricsSnapshot snapshot) {
        ObjectNode node = json.createObjectNode();
        node.put("cacheGets", Long.toString(snapshot.cacheGets()));
        node.put("cacheHits", Long.toString(snapshot.cacheHits()));
        node.put("cacheMisses", Long.toString(snapshot.cacheMisses()));
        node.put("cacheSets", Long.toString(snapshot.cacheSets()));
        node.put("cacheSetsApplied", Long.toString(snapshot.cacheSetsApplied()));
        node.put("cacheDeletes", Long.toString(snapshot.cacheDeletes()));
        node.put("counterIncrements", Long.toString(snapshot.counterIncrements()));
        node.put("counterSets", Long.toString(snapshot.counterSets()));
        node.put("counterDeletes", Long.toString(snapshot.counterDeletes()));
        node.put("lockAcquires", Long.toString(snapshot.lockAcquires()));
        node.put("lockAcquiresGranted", Long.toString(snapshot.lockAcquiresGranted()));
        node.put("lockRenewals", Long.toString(snapshot.lockRenewals()));
        node.put("lockReleases", Long.toString(snapshot.lockReleases()));
        node.put("publishes", Long.toString(snapshot.publishes()));
        node.put("subscribes", Long.toString(snapshot.subscribes()));
        return node;
    }

    private CacheValue cacheValue(JsonNode node) {
        if (node == null || !node.isObject()) throw validationFailure();
        ValueType type = ValueType.valueOf(requiredText(node, "type"));
        return switch (type) {
            case STRING -> {
                requireFields(node, Set.of("type", "text"));
                yield CacheValue.ofString(requiredTextAllowEmpty(node, "text"));
            }
            case JSON -> {
                requireFields(node, Set.of("type", "text"));
                String text = requiredTextAllowEmpty(node, "text");
                ManagementWireRules.requireValidJson(text);
                yield CacheValue.ofJsonUtf8(text);
            }
            case LONG -> {
                requireFields(node, Set.of("type", "decimal"));
                yield CacheValue.ofLong(ManagementWireRules.parseDecimalString(
                        requiredText(node, "decimal")));
            }
            case BYTES -> {
                requireFields(node, Set.of("type", "base64"));
                yield CacheValue.ofBytes(Base64.getDecoder().decode(
                        requiredTextAllowEmpty(node, "base64")));
            }
        };
    }

    private void writeJson(
            HttpServerRequest request,
            int status,
            ObjectNode body,
            String correlationId,
            boolean sensitive) {
        request.response()
                .setStatusCode(status)
                .putHeader("content-type", "application/json; charset=utf-8")
                .putHeader("cache-control", sensitive
                        ? "no-store, no-cache, must-revalidate" : "no-store")
                .putHeader("pragma", "no-cache")
                .putHeader("x-correlation-id", correlationId)
                .end(body.toString());
    }

    private void writeProblem(
            HttpServerRequest request,
            Throwable failure,
            String correlationId) {
        Throwable safeFailure = failure instanceof IllegalArgumentException
                && !(failure instanceof ManagementProtocolException)
                ? validationFailure()
                : failure;
        ManagementProblem problem = ManagementProblem.from(safeFailure, request.path(), correlationId);
        ObjectNode body = json.createObjectNode();
        body.put("type", problem.type().toString());
        body.put("title", problem.title());
        body.put("status", problem.status());
        body.put("code", problem.code());
        body.put("detail", problem.detail());
        body.put("instance", problem.instance());
        body.put("correlationId", problem.correlationId());
        body.putArray("fieldErrors");
        request.response()
                .setStatusCode(problem.status())
                .putHeader("content-type", "application/problem+json; charset=utf-8")
                .putHeader("cache-control", "no-store")
                .putHeader("x-correlation-id", correlationId)
                .end(body.toString());
    }

    private void requireAuditReady() {
        if (!audit.isMutationReady()) {
            throw new ManagementAuditException(
                    "Management audit is not accepting privileged operations");
        }
    }

    private static void requireOperator(AuthenticatedManagementRequest authenticated) {
        if (!authenticated.identity().roles().contains("operator")) {
            throw new ManagementSecurityException(
                    403, "AUTHORIZATION_FAILED", "Operator role is required");
        }
    }

    private static void requireJsonAccept(String accept) {
        if (accept == null || accept.isBlank() || accept.equals("*/*")
                || accept.equals("application/json")) {
            return;
        }
        throw new ManagementProtocolException(
                406, "NOT_ACCEPTABLE", "Responses use application/json");
    }

    private static void requireFields(JsonNode node, Set<String> expected) {
        if (node == null || !node.isObject()) throw validationFailure();
        Set<String> actual = new java.util.HashSet<>();
        node.fieldNames().forEachRemaining(actual::add);
        if (!actual.equals(expected)) throw validationFailure();
    }

    private static String requiredText(JsonNode node, String name) {
        String value = requiredTextAllowEmpty(node, name);
        if (value.isEmpty()) throw validationFailure();
        return value;
    }

    private static String requiredBoundedText(JsonNode node, String name, int maximumLength) {
        String value = requiredText(node, name);
        if (value.length() > maximumLength) throw validationFailure();
        return value;
    }

    private static String requiredTextAllowEmpty(JsonNode node, String name) {
        JsonNode value = node.get(name);
        if (value == null || !value.isTextual()) throw validationFailure();
        return value.textValue();
    }

    private static String nullableText(JsonNode node) {
        if (node == null || node.isNull()) return null;
        if (!node.isTextual()) throw validationFailure();
        return node.textValue();
    }

    private static String nullableBoundedText(
            JsonNode node, int maximumLength, boolean allowEmpty) {
        String value = nullableText(node);
        if (value == null) return null;
        if ((!allowEmpty && value.isEmpty()) || value.length() > maximumLength
                || value.indexOf('\0') >= 0) {
            throw validationFailure();
        }
        return value;
    }

    private static boolean requiredBoolean(JsonNode node, String name) {
        JsonNode value = node.get(name);
        if (value == null || !value.isBoolean()) throw validationFailure();
        return value.booleanValue();
    }

    private static long requiredPositiveLong(JsonNode node) {
        if (node == null || !node.isIntegralNumber() || !node.canConvertToLong()
                || node.longValue() <= 0) {
            throw validationFailure();
        }
        return node.longValue();
    }

    private static Long nullablePositiveLong(JsonNode node) {
        if (node == null || node.isNull()) return null;
        return requiredPositiveLong(node);
    }

    private static Long nullableNonNegativeDecimal(JsonNode node) {
        if (node == null || node.isNull()) return null;
        if (!node.isTextual() || !node.textValue().matches("0|[1-9][0-9]*")) {
            throw validationFailure();
        }
        try {
            return Long.parseLong(node.textValue());
        } catch (NumberFormatException failure) {
            throw validationFailure();
        }
    }

    private static int requiredPositiveInt(JsonNode node, int maximum) {
        if (node == null || !node.isIntegralNumber() || !node.canConvertToInt()
                || node.intValue() < 1 || node.intValue() > maximum) {
            throw validationFailure();
        }
        return node.intValue();
    }

    private static String boundedReason(String value) {
        String reason = value.trim();
        int bytes = reason.getBytes(StandardCharsets.UTF_8).length;
        if (bytes < 3 || bytes > 240
                || reason.codePoints().anyMatch(Character::isISOControl)) {
            throw validationFailure();
        }
        return reason;
    }

    private static long contentLength(String value) {
        if (value == null) return 0;
        try {
            long length = Long.parseLong(value);
            if (length < 0) throw validationFailure();
            return length;
        } catch (NumberFormatException failure) {
            throw validationFailure();
        }
    }

    private static String requireSetupId(String setupId) {
        if (!SETUP_ID.matcher(setupId).matches()) {
            throw new ManagementProtocolException(
                    400, "INVALID_IDENTIFIER", "Setup identifier is not canonical");
        }
        return setupId;
    }

    private static String decodeNamespace(String encoded) {
        try {
            return ManagementIdentifierCodec.decodeNamespace(encoded);
        } catch (IllegalArgumentException failure) {
            throw invalidIdentifier();
        }
    }

    private static String decodeKey(String encoded) {
        try {
            return ManagementIdentifierCodec.decodeKey(encoded);
        } catch (IllegalArgumentException failure) {
            throw invalidIdentifier();
        }
    }

    private static CacheKey validatedCacheKey(String namespace, String key) {
        return new CacheKey(validatedNamespace(namespace), validatedKey(key));
    }

    private static String validatedNamespace(String namespace) {
        try {
            ManagementIdentifierCodec.encodeNamespace(namespace);
            return namespace;
        } catch (IllegalArgumentException failure) {
            throw invalidIdentifier();
        }
    }

    private static String validatedKey(String key) {
        try {
            ManagementIdentifierCodec.encodeKey(key);
            return key;
        } catch (IllegalArgumentException failure) {
            throw invalidIdentifier();
        }
    }

    private static ManagementProtocolException invalidIdentifier() {
        return new ManagementProtocolException(
                400, "INVALID_IDENTIFIER", "Management identifier is invalid");
    }

    private static ManagementProtocolException validationFailure() {
        return new ManagementProtocolException(
                400, "VALIDATION_FAILED", "Request body does not match the operation contract");
    }

    private static String correlationId(HttpServerRequest request) {
        String supplied = request.getHeader("X-Correlation-ID");
        return supplied == null || supplied.isBlank() ? UUID.randomUUID().toString() : supplied;
    }

    private enum Operation { BATCH_GET, BATCH_SET, SCAN, LOCK }

    private record BatchGet(List<CacheKey> keys, String reason) { }

    private record ParsedScan(ScanRequest request, String reason) { }

    private record RawLockTarget(String encodedNamespace, String encodedKey, String action) { }

    private record LockTarget(LockKey key, String action) { }
}
