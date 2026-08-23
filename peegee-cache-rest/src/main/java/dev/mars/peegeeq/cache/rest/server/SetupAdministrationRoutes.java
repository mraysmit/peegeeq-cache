package dev.mars.peegeeq.cache.rest.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.mars.peegeeq.cache.api.management.ManagementActionContext;
import dev.mars.peegeeq.cache.api.management.EntryTtlMode;
import dev.mars.peegeeq.cache.api.management.BulkDeleteConflict;
import dev.mars.peegeeq.cache.api.management.BulkDeletePreview;
import dev.mars.peegeeq.cache.api.management.BulkDeleteResult;
import dev.mars.peegeeq.cache.api.management.ConfirmedCounterDelete;
import dev.mars.peegeeq.cache.api.management.ConfirmedEntryDelete;
import dev.mars.peegeeq.cache.api.management.CounterDeleteSelection;
import dev.mars.peegeeq.cache.api.management.EntryDeleteFilter;
import dev.mars.peegeeq.cache.api.management.ForceReleaseLockRequest;
import dev.mars.peegeeq.cache.api.management.ManagementCacheSetRequest;
import dev.mars.peegeeq.cache.api.management.ManagementCounterAdjustRequest;
import dev.mars.peegeeq.cache.api.management.ManagementCounterSetRequest;
import dev.mars.peegeeq.cache.api.management.ManagementMutationOutcome;
import dev.mars.peegeeq.cache.api.management.CounterEntry;
import dev.mars.peegeeq.cache.api.management.ManagementEntryMetadata;
import dev.mars.peegeeq.cache.api.management.ManagementSetResult;
import dev.mars.peegeeq.cache.api.management.ManagementTtl;
import dev.mars.peegeeq.cache.api.management.ManagementTtlFilter;
import dev.mars.peegeeq.cache.api.management.RevealEntryRequest;
import dev.mars.peegeeq.cache.api.management.RevealLockOwnerRequest;
import dev.mars.peegeeq.cache.api.management.RevealedEntryValue;
import dev.mars.peegeeq.cache.api.management.RevealedLockOwner;
import dev.mars.peegeeq.cache.api.management.VersionedEntryTtlRequest;
import dev.mars.peegeeq.cache.api.management.VersionedEntryTouchRequest;
import dev.mars.peegeeq.cache.api.management.VersionedEntryDeleteRequest;
import dev.mars.peegeeq.cache.api.management.VersionedCacheKeyRequest;
import dev.mars.peegeeq.cache.api.management.VersionedCounterDeleteRequest;
import dev.mars.peegeeq.cache.api.management.VersionedCounterTtlRequest;
import dev.mars.peegeeq.cache.api.management.VersionedMutationResult;
import dev.mars.peegeeq.cache.api.management.VersionedCacheKeyTarget;
import dev.mars.peegeeq.cache.api.model.CacheKey;
import dev.mars.peegeeq.cache.api.model.CacheValue;
import dev.mars.peegeeq.cache.api.model.CounterTtlMode;
import dev.mars.peegeeq.cache.api.model.LockKey;
import dev.mars.peegeeq.cache.api.model.SetMode;
import dev.mars.peegeeq.cache.api.model.ValueType;
import dev.mars.peegeeq.cache.rest.protocol.ManagementIdentifierCodec;
import dev.mars.peegeeq.cache.rest.protocol.ManagementEntityTagCodec;
import dev.mars.peegeeq.cache.rest.protocol.ManagementPrecondition;
import dev.mars.peegeeq.cache.rest.protocol.ManagementProblem;
import dev.mars.peegeeq.cache.rest.protocol.ManagementProtocolException;
import dev.mars.peegeeq.cache.rest.protocol.ManagementWireRules;
import dev.mars.peegeeq.cache.rest.security.BrowserRequestSecurity;
import dev.mars.peegeeq.cache.rest.security.ManagementRateLimiter;
import dev.mars.peegeeq.cache.rest.security.ManagementSecurityException;
import dev.mars.peegeeq.cache.rest.security.RateLimitAction;
import io.vertx.core.http.HttpServerRequest;

import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Operator-only, fail-closed reveal and administration HTTP routes. */
public final class SetupAdministrationRoutes implements ManagementRequestRouter {

    private static final Pattern ENTRY_REVEAL = Pattern.compile(
            "^/api/v1/setups/([^/]+)/namespaces/([^/]+)/entries/([^/]+)/value/reveal$");
    private static final Pattern LOCK_OWNER_REVEAL = Pattern.compile(
            "^/api/v1/setups/([^/]+)/namespaces/([^/]+)/locks/([^/]+)/owner/reveal$");
    private static final Pattern LOCK_FORCE_RELEASE = Pattern.compile(
            "^/api/v1/setups/([^/]+)/namespaces/([^/]+)/locks/([^/]+)/force-release$");
    private static final Pattern ENTRY_BULK = Pattern.compile(
            "^/api/v1/setups/([^/]+)/namespaces/([^/]+)/entries/bulk-delete/(preview|execute)$");
    private static final Pattern COUNTER_BULK = Pattern.compile(
            "^/api/v1/setups/([^/]+)/counters/bulk-delete/(preview|execute)$");
    private static final Pattern ENTRY_SET = Pattern.compile(
            "^/api/v1/setups/([^/]+)/namespaces/([^/]+)/entries/([^/]+)$");
    private static final Pattern COUNTER_ITEM = Pattern.compile(
            "^/api/v1/setups/([^/]+)/namespaces/([^/]+)/counters/([^/]+)$");
    private static final Pattern COUNTER_INCREMENT = Pattern.compile(
            "^/api/v1/setups/([^/]+)/namespaces/([^/]+)/counters/([^/]+)/increment$");
    private static final Pattern COUNTER_TTL = Pattern.compile(
            "^/api/v1/setups/([^/]+)/namespaces/([^/]+)/counters/([^/]+)/ttl$");
    private static final Pattern COUNTER_PERSIST = Pattern.compile(
            "^/api/v1/setups/([^/]+)/namespaces/([^/]+)/counters/([^/]+)/persist$");
    private static final Pattern ENTRY_TTL = Pattern.compile(
            "^/api/v1/setups/([^/]+)/namespaces/([^/]+)/entries/([^/]+)/ttl$");
    private static final Pattern ENTRY_PERSIST = Pattern.compile(
            "^/api/v1/setups/([^/]+)/namespaces/([^/]+)/entries/([^/]+)/persist$");
    private static final Pattern ENTRY_TOUCH = Pattern.compile(
            "^/api/v1/setups/([^/]+)/namespaces/([^/]+)/entries/([^/]+)/touch$");
    private static final Pattern SETUP_ID = Pattern.compile("[a-z][a-z0-9-]{0,62}");
    private static final int REVEAL_REQUEST_MAX_BYTES = 4 * 1024;
    private static final int SET_REQUEST_MAX_BYTES = 10 * 1024 * 1024 + 4 * 1024;
    private static final int REVEAL_AUTO_HIDE_MILLIS = 60_000;
    private static final String DEFAULT_REVEAL_REASON = "INTERACTIVE_CONSOLE_REVEAL";

    private final SetupRegistry registry;
    private final ManagementRequestAuthenticator authenticator;
    private final BrowserRequestSecurity browserSecurity;
    private final ManagementRateLimiter rateLimiter;
    private final Clock clock;
    private final ObjectMapper json = new ObjectMapper();

    public SetupAdministrationRoutes(
            SetupRegistry registry,
            ManagementRequestAuthenticator authenticator,
            BrowserRequestSecurity browserSecurity,
            ManagementRateLimiter rateLimiter,
            Clock clock) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.authenticator = Objects.requireNonNull(authenticator, "authenticator");
        this.browserSecurity = Objects.requireNonNull(browserSecurity, "browserSecurity");
        this.rateLimiter = Objects.requireNonNull(rateLimiter, "rateLimiter");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public boolean route(HttpServerRequest request) {
        if (request.method().name().equals("DELETE")) {
            Matcher deleteMatcher = ENTRY_SET.matcher(request.path());
            if (deleteMatcher.matches()) {
                routeDeleteEntry(request, deleteMatcher);
                return true;
            }
            Matcher counterDeleteMatcher = COUNTER_ITEM.matcher(request.path());
            if (counterDeleteMatcher.matches()) {
                routeDeleteCounter(request, counterDeleteMatcher);
                return true;
            }
            return false;
        }
        if (request.method().name().equals("PUT")) {
            Matcher setMatcher = ENTRY_SET.matcher(request.path());
            if (setMatcher.matches()) {
                routeSetEntry(request, setMatcher);
                return true;
            }
            Matcher counterMatcher = COUNTER_ITEM.matcher(request.path());
            if (counterMatcher.matches()) {
                routeSetCounter(request, counterMatcher);
                return true;
            }
            return false;
        }
        if (!request.method().name().equals("POST")) {
            return false;
        }
        Matcher ttlMatcher = ENTRY_TTL.matcher(request.path());
        if (ttlMatcher.matches()) {
            routeExpireEntry(request, ttlMatcher);
            return true;
        }
        Matcher persistMatcher = ENTRY_PERSIST.matcher(request.path());
        if (persistMatcher.matches()) {
            routePersistEntry(request, persistMatcher);
            return true;
        }
        Matcher touchMatcher = ENTRY_TOUCH.matcher(request.path());
        if (touchMatcher.matches()) {
            routeTouchEntry(request, touchMatcher);
            return true;
        }
        Matcher counterIncrementMatcher = COUNTER_INCREMENT.matcher(request.path());
        if (counterIncrementMatcher.matches()) {
            routeAdjustCounter(request, counterIncrementMatcher);
            return true;
        }
        Matcher counterTtlMatcher = COUNTER_TTL.matcher(request.path());
        if (counterTtlMatcher.matches()) {
            routeExpireCounter(request, counterTtlMatcher);
            return true;
        }
        Matcher counterPersistMatcher = COUNTER_PERSIST.matcher(request.path());
        if (counterPersistMatcher.matches()) {
            routePersistCounter(request, counterPersistMatcher);
            return true;
        }
        Matcher forceReleaseMatcher = LOCK_FORCE_RELEASE.matcher(request.path());
        if (forceReleaseMatcher.matches()) {
            routeForceReleaseLock(request, forceReleaseMatcher);
            return true;
        }
        Matcher entryBulkMatcher = ENTRY_BULK.matcher(request.path());
        if (entryBulkMatcher.matches()) {
            routeBulk(request, entryBulkMatcher, true);
            return true;
        }
        Matcher counterBulkMatcher = COUNTER_BULK.matcher(request.path());
        if (counterBulkMatcher.matches()) {
            routeBulk(request, counterBulkMatcher, false);
            return true;
        }
        Matcher entryMatcher = ENTRY_REVEAL.matcher(request.path());
        Matcher lockMatcher = LOCK_OWNER_REVEAL.matcher(request.path());
        boolean entryReveal = entryMatcher.matches();
        boolean lockReveal = lockMatcher.matches();
        if (!entryReveal && !lockReveal) {
            return false;
        }

        String correlationId = correlationId(request);
        try {
            AuthenticatedManagementRequest authenticated = authenticator.authenticate(request);
            requireOperator(authenticated);
            browserSecurity.validateStateChange(
                    request.getHeader("Cookie"),
                    request.getHeader("X-PeeGeeQ-CSRF"),
                    authenticated.sessionCookie(),
                    authenticated.csrfToken(),
                    request.getHeader("Origin"));
            requireJsonAccept(request.getHeader("Accept"));
            Matcher matcher = entryReveal ? entryMatcher : lockMatcher;
            String setupId = requireCanonicalSetupId(matcher.group(1));
            String namespace = decodeNamespace(matcher.group(2));
            String key = decodeKey(matcher.group(3));
            long contentLength = contentLength(request.getHeader("Content-Length"));
            if (contentLength > 0) {
                ManagementWireRules.requireJsonContentType(request.getHeader("Content-Type"));
                ManagementWireRules.requireRequestSize(contentLength, REVEAL_REQUEST_MAX_BYTES);
            }
            request.body()
                    .onSuccess(body -> {
                        if (entryReveal) {
                            revealEntry(
                                    request,
                                    setupId,
                                    new CacheKey(namespace, key),
                                    authenticated,
                                    correlationId,
                                    body.getBytes());
                        } else {
                            revealLockOwner(
                                    request,
                                    setupId,
                                    new LockKey(namespace, key),
                                    authenticated,
                                    correlationId,
                                    body.getBytes());
                        }
                    })
                    .onFailure(failure -> writeProblem(request, failure, correlationId));
        } catch (Throwable failure) {
            writeProblem(request, failure, correlationId);
        }
        return true;
    }

    private void routeBulk(HttpServerRequest request, Matcher matcher, boolean entries) {
        String correlationId = correlationId(request);
        try {
            AuthenticatedManagementRequest authenticated = authenticator.authenticate(request);
            requireOperator(authenticated);
            browserSecurity.validateStateChange(
                    request.getHeader("Cookie"),
                    request.getHeader("X-PeeGeeQ-CSRF"),
                    authenticated.sessionCookie(),
                    authenticated.csrfToken(),
                    request.getHeader("Origin"));
            requireJsonAccept(request.getHeader("Accept"));
            ManagementWireRules.requireJsonContentType(request.getHeader("Content-Type"));
            String setupId = requireCanonicalSetupId(matcher.group(1));
            String namespace = entries ? decodeNamespace(matcher.group(2)) : null;
            String action = entries ? matcher.group(3) : matcher.group(2);
            long contentLength = contentLength(request.getHeader("Content-Length"));
            ManagementWireRules.requireRequestSize(contentLength, REVEAL_REQUEST_MAX_BYTES);
            if (action.equals("preview")) {
                rateLimiter.acquire(
                        RateLimitAction.BULK_PREVIEW,
                        authenticated.identity().actor(),
                        InetAddress.getByName(authenticated.identity().sourceAddress()));
            }
            request.body()
                    .onSuccess(body -> executeBulkRequest(
                            request,
                            setupId,
                            namespace,
                            action,
                            entries,
                            authenticated,
                            correlationId,
                            body.getBytes()))
                    .onFailure(failure -> writeProblem(request, failure, correlationId));
        } catch (Throwable failure) {
            writeProblem(request, failure, correlationId);
        }
    }

    private void executeBulkRequest(
            HttpServerRequest request,
            String setupId,
            String namespace,
            String action,
            boolean entries,
            AuthenticatedManagementRequest authenticated,
            String correlationId,
            byte[] body) {
        try {
            ManagementWireRules.requireRequestSize(body.length, REVEAL_REQUEST_MAX_BYTES);
            ManagementActionContext context = actionContext(authenticated, correlationId);
            if (entries && action.equals("preview")) {
                registry.management(setupId)
                        .previewEntryDelete(parseEntryDeleteSelection(body, namespace), context)
                        .onSuccess(preview -> writeBulkPreview(request, preview, correlationId))
                        .onFailure(failure -> writeProblem(request, failure, correlationId));
            } else if (entries) {
                registry.management(setupId)
                        .executeEntryDelete(parseConfirmedEntryDelete(body, namespace), context)
                        .onSuccess(result -> writeBulkResult(request, result, correlationId))
                        .onFailure(failure -> writeProblem(request, failure, correlationId));
            } else if (action.equals("preview")) {
                registry.management(setupId)
                        .previewCounterDelete(parseCounterDeleteSelection(body), context)
                        .onSuccess(preview -> writeBulkPreview(request, preview, correlationId))
                        .onFailure(failure -> writeProblem(request, failure, correlationId));
            } else {
                registry.management(setupId)
                        .executeCounterDelete(parseConfirmedCounterDelete(body), context)
                        .onSuccess(result -> writeBulkResult(request, result, correlationId))
                        .onFailure(failure -> writeProblem(request, failure, correlationId));
            }
        } catch (Throwable failure) {
            writeProblem(request, failure, correlationId);
        }
    }

    private EntryDeleteFilter parseEntryDeleteSelection(byte[] body, String namespace) {
        try {
            JsonNode root = json.readTree(body);
            requireFields(root, Set.of("selection"));
            JsonNode selection = root.get("selection");
            if (selection == null || !selection.isObject()) throw validationFailure();
            String type = requiredText(selection, "type");
            if (type.equals("EXPLICIT")) {
                requireFields(selection, Set.of("type", "targets"));
                return new EntryDeleteFilter(
                        namespace, null, null, null,
                        parseTargets(selection.get("targets"), namespace, false));
            }
            if (!type.equals("FILTER")) throw validationFailure();
            Set<String> names = fieldNames(selection);
            if (!names.containsAll(Set.of("type", "ttlState"))
                    || !Set.of("type", "prefix", "valueType", "ttlState").containsAll(names)) {
                throw validationFailure();
            }
            String prefix = optionalText(selection, "prefix");
            ValueType valueType = selection.has("valueType")
                    ? ValueType.valueOf(requiredText(selection, "valueType")) : null;
            return new EntryDeleteFilter(
                    namespace,
                    prefix,
                    valueType,
                    ManagementTtlFilter.valueOf(requiredText(selection, "ttlState")),
                    List.of());
        } catch (ManagementProtocolException failure) {
            throw failure;
        } catch (RuntimeException | java.io.IOException failure) {
            throw validationFailure();
        }
    }

    private CounterDeleteSelection parseCounterDeleteSelection(byte[] body) {
        try {
            JsonNode root = json.readTree(body);
            requireFields(root, Set.of("targets"));
            return new CounterDeleteSelection(parseTargets(root.get("targets"), null, true));
        } catch (ManagementProtocolException failure) {
            throw failure;
        } catch (RuntimeException | java.io.IOException failure) {
            throw validationFailure();
        }
    }

    private static List<VersionedCacheKeyTarget> parseTargets(
            JsonNode targets,
            String namespace,
            boolean namespacesInBody) {
        if (targets == null || !targets.isArray()
                || targets.isEmpty() || targets.size() > 1_000) {
            throw validationFailure();
        }
        java.util.ArrayList<VersionedCacheKeyTarget> parsed = new java.util.ArrayList<>();
        for (JsonNode target : targets) {
            requireFields(target, namespacesInBody
                    ? Set.of("namespace", "key", "version")
                    : Set.of("key", "version"));
            String targetNamespace = namespacesInBody
                    ? requiredText(target, "namespace") : namespace;
            parsed.add(new VersionedCacheKeyTarget(
                    new CacheKey(targetNamespace, requiredText(target, "key")),
                    ManagementWireRules.parseDecimalString(requiredText(target, "version"))));
        }
        return parsed;
    }

    private ConfirmedEntryDelete parseConfirmedEntryDelete(byte[] body, String namespace) {
        JsonNode root = parseExactConfirmation(body);
        return new ConfirmedEntryDelete(
                requiredText(root, "previewToken"),
                requiredText(root, "confirmationPhrase"),
                namespace);
    }

    private ConfirmedCounterDelete parseConfirmedCounterDelete(byte[] body) {
        JsonNode root = parseExactConfirmation(body);
        return new ConfirmedCounterDelete(
                requiredText(root, "previewToken"),
                requiredText(root, "confirmationPhrase"));
    }

    private JsonNode parseExactConfirmation(byte[] body) {
        try {
            JsonNode root = json.readTree(body);
            requireFields(root, Set.of("previewToken", "confirmationPhrase"));
            return root;
        } catch (ManagementProtocolException failure) {
            throw failure;
        } catch (RuntimeException | java.io.IOException failure) {
            throw validationFailure();
        }
    }

    private static String optionalText(JsonNode node, String name) {
        if (!node.has(name)) return null;
        return requiredText(node, name);
    }

    private void writeBulkPreview(
            HttpServerRequest request,
            BulkDeletePreview preview,
            String correlationId) {
        ObjectNode response = json.createObjectNode();
        response.put("previewToken", preview.previewToken());
        response.put("expiresAt", preview.expiresAt().toString());
        response.put("setupId", preview.setupId());
        if (preview.namespace() == null) response.putNull("namespace");
        else response.put("namespace", preview.namespace());
        response.put("resolvedCount", Long.toString(preview.resolvedCount()));
        response.put("totalBytes", Long.toString(preview.totalBytes()));
        response.set("sampleKeys", json.valueToTree(preview.sampleKeys()));
        response.put("confirmationPhrase", preview.confirmationPhrase());
        request.response()
                .setStatusCode(200)
                .putHeader("content-type", "application/json; charset=utf-8")
                .putHeader("cache-control", "no-store")
                .putHeader("x-correlation-id", correlationId)
                .end(response.toString());
    }

    private void writeBulkResult(
            HttpServerRequest request,
            BulkDeleteResult result,
            String correlationId) {
        ObjectNode response = json.createObjectNode();
        response.put("processedCount", Long.toString(result.processedCount()));
        response.put("deletedCount", Long.toString(result.deletedCount()));
        response.put("conflictCount", Long.toString(result.conflictCount()));
        response.put("missingCount", Long.toString(result.missingCount()));
        response.put("failedCount", Long.toString(result.failedCount()));
        var conflicts = response.putArray("conflicts");
        for (BulkDeleteConflict conflict : result.conflicts()) {
            ObjectNode node = conflicts.addObject();
            node.put("key", conflict.key());
            node.put("reason", conflict.reason().name());
        }
        request.response()
                .setStatusCode(200)
                .putHeader("content-type", "application/json; charset=utf-8")
                .putHeader("cache-control", "no-store")
                .putHeader("x-correlation-id", correlationId)
                .end(response.toString());
    }

    private void routeForceReleaseLock(HttpServerRequest request, Matcher matcher) {
        String correlationId = correlationId(request);
        try {
            AuthenticatedManagementRequest authenticated = authenticator.authenticate(request);
            requireOperator(authenticated);
            browserSecurity.validateStateChange(
                    request.getHeader("Cookie"),
                    request.getHeader("X-PeeGeeQ-CSRF"),
                    authenticated.sessionCookie(),
                    authenticated.csrfToken(),
                    request.getHeader("Origin"));
            requireJsonAccept(request.getHeader("Accept"));
            ManagementWireRules.requireJsonContentType(request.getHeader("Content-Type"));
            String setupId = requireCanonicalSetupId(matcher.group(1));
            LockKey key = new LockKey(
                    decodeNamespace(matcher.group(2)),
                    decodeKey(matcher.group(3)));
            long expectedVersion = exactIfMatch(request.getHeader("If-Match"));
            long contentLength = contentLength(request.getHeader("Content-Length"));
            ManagementWireRules.requireRequestSize(contentLength, REVEAL_REQUEST_MAX_BYTES);
            request.body()
                    .onSuccess(body -> forceReleaseLock(
                            request,
                            setupId,
                            key,
                            expectedVersion,
                            authenticated,
                            correlationId,
                            body.getBytes()))
                    .onFailure(failure -> writeProblem(request, failure, correlationId));
        } catch (Throwable failure) {
            writeProblem(request, failure, correlationId);
        }
    }

    private void forceReleaseLock(
            HttpServerRequest request,
            String setupId,
            LockKey key,
            long expectedVersion,
            AuthenticatedManagementRequest authenticated,
            String correlationId,
            byte[] body) {
        try {
            ManagementWireRules.requireRequestSize(body.length, REVEAL_REQUEST_MAX_BYTES);
            ForceReleaseLockRequest releaseRequest = parseForceRelease(
                    body, key, expectedVersion);
            registry.management(setupId)
                    .forceReleaseLock(
                            releaseRequest,
                            actionContext(authenticated, correlationId))
                    .onSuccess(result -> writeDeleteResult(
                            request, result, correlationId, "LOCK"))
                    .onFailure(failure -> writeProblem(request, failure, correlationId));
        } catch (Throwable failure) {
            writeProblem(request, failure, correlationId);
        }
    }

    private ForceReleaseLockRequest parseForceRelease(
            byte[] body,
            LockKey key,
            long expectedVersion) {
        try {
            JsonNode root = json.readTree(body);
            if (root == null || !root.isObject()) {
                throw validationFailure();
            }
            Set<String> names = fieldNames(root);
            if (!names.equals(Set.of("confirmationKey"))
                    && !names.equals(Set.of("confirmationKey", "reason"))) {
                throw validationFailure();
            }
            String reason = names.contains("reason")
                    ? boundedReason(requiredText(root, "reason"))
                    : null;
            return new ForceReleaseLockRequest(
                    key,
                    expectedVersion,
                    requiredText(root, "confirmationKey"),
                    reason);
        } catch (ManagementProtocolException failure) {
            throw failure;
        } catch (RuntimeException | java.io.IOException failure) {
            throw validationFailure();
        }
    }

    private void routeDeleteCounter(HttpServerRequest request, Matcher matcher) {
        String correlationId = correlationId(request);
        try {
            AuthenticatedManagementRequest authenticated = authenticator.authenticate(request);
            requireOperator(authenticated);
            browserSecurity.validateStateChange(
                    request.getHeader("Cookie"),
                    request.getHeader("X-PeeGeeQ-CSRF"),
                    authenticated.sessionCookie(),
                    authenticated.csrfToken(),
                    request.getHeader("Origin"));
            String setupId = requireCanonicalSetupId(matcher.group(1));
            CacheKey key = new CacheKey(
                    decodeNamespace(matcher.group(2)),
                    decodeKey(matcher.group(3)));
            long expectedVersion = exactIfMatch(request.getHeader("If-Match"));
            if (contentLength(request.getHeader("Content-Length")) != 0) {
                throw validationFailure();
            }
            registry.management(setupId)
                    .deleteCounter(
                            new VersionedCounterDeleteRequest(key, expectedVersion),
                            actionContext(authenticated, correlationId))
                    .onSuccess(result -> writeDeleteResult(
                            request, result, correlationId, "COUNTER"))
                    .onFailure(failure -> writeProblem(request, failure, correlationId));
        } catch (Throwable failure) {
            writeProblem(request, failure, correlationId);
        }
    }

    private void routePersistCounter(HttpServerRequest request, Matcher matcher) {
        String correlationId = correlationId(request);
        try {
            AuthenticatedManagementRequest authenticated = authenticator.authenticate(request);
            requireOperator(authenticated);
            browserSecurity.validateStateChange(
                    request.getHeader("Cookie"),
                    request.getHeader("X-PeeGeeQ-CSRF"),
                    authenticated.sessionCookie(),
                    authenticated.csrfToken(),
                    request.getHeader("Origin"));
            requireJsonAccept(request.getHeader("Accept"));
            String setupId = requireCanonicalSetupId(matcher.group(1));
            CacheKey key = new CacheKey(
                    decodeNamespace(matcher.group(2)),
                    decodeKey(matcher.group(3)));
            long expectedVersion = exactIfMatch(request.getHeader("If-Match"));
            if (contentLength(request.getHeader("Content-Length")) != 0) {
                throw validationFailure();
            }
            registry.management(setupId)
                    .persistCounter(
                            new VersionedCacheKeyRequest(key, expectedVersion),
                            actionContext(authenticated, correlationId))
                    .onSuccess(result -> writeCounterMutation(
                            request, result, false, correlationId))
                    .onFailure(failure -> writeProblem(request, failure, correlationId));
        } catch (Throwable failure) {
            writeProblem(request, failure, correlationId);
        }
    }

    private void routeExpireCounter(HttpServerRequest request, Matcher matcher) {
        String correlationId = correlationId(request);
        try {
            AuthenticatedManagementRequest authenticated = authenticator.authenticate(request);
            requireOperator(authenticated);
            browserSecurity.validateStateChange(
                    request.getHeader("Cookie"),
                    request.getHeader("X-PeeGeeQ-CSRF"),
                    authenticated.sessionCookie(),
                    authenticated.csrfToken(),
                    request.getHeader("Origin"));
            requireJsonAccept(request.getHeader("Accept"));
            ManagementWireRules.requireJsonContentType(request.getHeader("Content-Type"));
            String setupId = requireCanonicalSetupId(matcher.group(1));
            CacheKey key = new CacheKey(
                    decodeNamespace(matcher.group(2)),
                    decodeKey(matcher.group(3)));
            long expectedVersion = exactIfMatch(request.getHeader("If-Match"));
            long contentLength = contentLength(request.getHeader("Content-Length"));
            ManagementWireRules.requireRequestSize(contentLength, REVEAL_REQUEST_MAX_BYTES);
            request.body()
                    .onSuccess(body -> expireCounter(
                            request,
                            setupId,
                            key,
                            expectedVersion,
                            authenticated,
                            correlationId,
                            body.getBytes()))
                    .onFailure(failure -> writeProblem(request, failure, correlationId));
        } catch (Throwable failure) {
            writeProblem(request, failure, correlationId);
        }
    }

    private void expireCounter(
            HttpServerRequest request,
            String setupId,
            CacheKey key,
            long expectedVersion,
            AuthenticatedManagementRequest authenticated,
            String correlationId,
            byte[] body) {
        try {
            ManagementWireRules.requireRequestSize(body.length, REVEAL_REQUEST_MAX_BYTES);
            Duration ttl = parseEntryTtl(body);
            registry.management(setupId)
                    .expireCounter(
                            new VersionedCounterTtlRequest(key, expectedVersion, ttl),
                            actionContext(authenticated, correlationId))
                    .onSuccess(result -> writeCounterMutation(
                            request, result, false, correlationId))
                    .onFailure(failure -> writeProblem(request, failure, correlationId));
        } catch (Throwable failure) {
            writeProblem(request, failure, correlationId);
        }
    }

    private void routeAdjustCounter(HttpServerRequest request, Matcher matcher) {
        String correlationId = correlationId(request);
        try {
            AuthenticatedManagementRequest authenticated = authenticator.authenticate(request);
            requireOperator(authenticated);
            browserSecurity.validateStateChange(
                    request.getHeader("Cookie"),
                    request.getHeader("X-PeeGeeQ-CSRF"),
                    authenticated.sessionCookie(),
                    authenticated.csrfToken(),
                    request.getHeader("Origin"));
            requireJsonAccept(request.getHeader("Accept"));
            ManagementWireRules.requireJsonContentType(request.getHeader("Content-Type"));
            String setupId = requireCanonicalSetupId(matcher.group(1));
            CacheKey key = new CacheKey(
                    decodeNamespace(matcher.group(2)),
                    decodeKey(matcher.group(3)));
            long contentLength = contentLength(request.getHeader("Content-Length"));
            ManagementWireRules.requireRequestSize(contentLength, REVEAL_REQUEST_MAX_BYTES);
            request.body()
                    .onSuccess(body -> adjustCounter(
                            request,
                            setupId,
                            key,
                            authenticated,
                            correlationId,
                            body.getBytes()))
                    .onFailure(failure -> writeProblem(request, failure, correlationId));
        } catch (Throwable failure) {
            writeProblem(request, failure, correlationId);
        }
    }

    private void adjustCounter(
            HttpServerRequest request,
            String setupId,
            CacheKey key,
            AuthenticatedManagementRequest authenticated,
            String correlationId,
            byte[] body) {
        try {
            ManagementWireRules.requireRequestSize(body.length, REVEAL_REQUEST_MAX_BYTES);
            ManagementCounterAdjustRequest adjustRequest = parseCounterAdjust(body, key, request);
            registry.management(setupId)
                    .adjustCounter(adjustRequest, actionContext(authenticated, correlationId))
                    .onSuccess(result -> writeCounterMutation(
                            request, result, false, correlationId))
                    .onFailure(failure -> writeProblem(request, failure, correlationId));
        } catch (Throwable failure) {
            writeProblem(request, failure, correlationId);
        }
    }

    private ManagementCounterAdjustRequest parseCounterAdjust(
            byte[] body,
            CacheKey key,
            HttpServerRequest request) {
        try {
            JsonNode root = json.readTree(body);
            if (root == null || !root.isObject()
                    || !fieldNames(root).equals(Set.of(
                    "delta", "createIfMissing", "ttlMode", "ttlMillis"))) {
                throw validationFailure();
            }
            JsonNode createNode = root.get("createIfMissing");
            if (!createNode.isBoolean()) {
                throw validationFailure();
            }
            boolean createIfMissing = createNode.booleanValue();
            ManagementPrecondition precondition = counterAdjustPrecondition(
                    request.getHeader("If-Match"),
                    request.getHeader("If-None-Match"),
                    createIfMissing);
            CounterTtlMode ttlMode = CounterTtlMode.valueOf(requiredText(root, "ttlMode"));
            JsonNode ttlNode = root.get("ttlMillis");
            Duration ttl = ttlNode.isNull()
                    ? null : Duration.ofMillis(requiredPositiveLong(ttlNode));
            return new ManagementCounterAdjustRequest(
                    key,
                    ManagementWireRules.parseDecimalString(requiredText(root, "delta")),
                    precondition.kind() == ManagementPrecondition.Kind.EXACT_VERSION
                            ? precondition.version() : null,
                    createIfMissing,
                    ttlMode,
                    ttl);
        } catch (ManagementProtocolException failure) {
            throw failure;
        } catch (RuntimeException | java.io.IOException failure) {
            throw validationFailure();
        }
    }

    private static ManagementPrecondition counterAdjustPrecondition(
            String ifMatch,
            String ifNoneMatch,
            boolean createIfMissing) {
        if (ifMatch == null && ifNoneMatch == null) {
            throw new ManagementProtocolException(
                    428,
                    "PRECONDITION_REQUIRED",
                    "Counter adjustment requires a creation or version precondition");
        }
        ManagementPrecondition precondition = ManagementEntityTagCodec.parse(
                ifMatch, ifNoneMatch, false, true);
        ManagementPrecondition.Kind required = createIfMissing
                ? ManagementPrecondition.Kind.REQUIRE_ABSENT
                : ManagementPrecondition.Kind.EXACT_VERSION;
        if (precondition.kind() != required) {
            throw validationFailure();
        }
        return precondition;
    }

    private void routeSetCounter(HttpServerRequest request, Matcher matcher) {
        String correlationId = correlationId(request);
        try {
            AuthenticatedManagementRequest authenticated = authenticator.authenticate(request);
            requireOperator(authenticated);
            browserSecurity.validateStateChange(
                    request.getHeader("Cookie"),
                    request.getHeader("X-PeeGeeQ-CSRF"),
                    authenticated.sessionCookie(),
                    authenticated.csrfToken(),
                    request.getHeader("Origin"));
            requireJsonAccept(request.getHeader("Accept"));
            ManagementWireRules.requireJsonContentType(request.getHeader("Content-Type"));
            String setupId = requireCanonicalSetupId(matcher.group(1));
            CacheKey key = new CacheKey(
                    decodeNamespace(matcher.group(2)),
                    decodeKey(matcher.group(3)));
            long contentLength = contentLength(request.getHeader("Content-Length"));
            ManagementWireRules.requireRequestSize(contentLength, REVEAL_REQUEST_MAX_BYTES);
            request.body()
                    .onSuccess(body -> setCounter(
                            request,
                            setupId,
                            key,
                            authenticated,
                            correlationId,
                            body.getBytes()))
                    .onFailure(failure -> writeProblem(request, failure, correlationId));
        } catch (Throwable failure) {
            writeProblem(request, failure, correlationId);
        }
    }

    private void setCounter(
            HttpServerRequest request,
            String setupId,
            CacheKey key,
            AuthenticatedManagementRequest authenticated,
            String correlationId,
            byte[] body) {
        try {
            ManagementWireRules.requireRequestSize(body.length, REVEAL_REQUEST_MAX_BYTES);
            ManagementCounterSetRequest setRequest = parseCounterSet(body, key, request);
            registry.management(setupId)
                    .setCounter(setRequest, actionContext(authenticated, correlationId))
                    .onSuccess(result -> writeCounterMutation(
                            request, result, setRequest.requireAbsent(), correlationId))
                    .onFailure(failure -> writeProblem(request, failure, correlationId));
        } catch (Throwable failure) {
            writeProblem(request, failure, correlationId);
        }
    }

    private ManagementCounterSetRequest parseCounterSet(
            byte[] body,
            CacheKey key,
            HttpServerRequest request) {
        try {
            JsonNode root = json.readTree(body);
            if (root == null || !root.isObject()
                    || !fieldNames(root).equals(Set.of("value", "ttlMode", "ttlMillis"))) {
                throw validationFailure();
            }
            ManagementPrecondition precondition = counterSetPrecondition(
                    request.getHeader("If-Match"), request.getHeader("If-None-Match"));
            CounterTtlMode ttlMode = CounterTtlMode.valueOf(requiredText(root, "ttlMode"));
            JsonNode ttlNode = root.get("ttlMillis");
            Duration ttl = ttlNode.isNull()
                    ? null : Duration.ofMillis(requiredPositiveLong(ttlNode));
            return new ManagementCounterSetRequest(
                    key,
                    ManagementWireRules.parseDecimalString(requiredText(root, "value")),
                    precondition.version(),
                    precondition.kind() == ManagementPrecondition.Kind.REQUIRE_ABSENT,
                    ttlMode,
                    ttl);
        } catch (ManagementProtocolException failure) {
            throw failure;
        } catch (RuntimeException | java.io.IOException failure) {
            throw validationFailure();
        }
    }

    private static ManagementPrecondition counterSetPrecondition(
            String ifMatch,
            String ifNoneMatch) {
        if (ifMatch == null && ifNoneMatch == null) {
            throw new ManagementProtocolException(
                    428, "PRECONDITION_REQUIRED", "Counter set requires a creation or version precondition");
        }
        try {
            ManagementPrecondition precondition = ManagementEntityTagCodec.parse(
                    ifMatch, ifNoneMatch, false, true);
            if (precondition.kind() != ManagementPrecondition.Kind.EXACT_VERSION
                    && precondition.kind() != ManagementPrecondition.Kind.REQUIRE_ABSENT) {
                throw validationFailure();
            }
            return precondition;
        } catch (ManagementProtocolException failure) {
            throw failure;
        } catch (IllegalArgumentException failure) {
            throw validationFailure();
        }
    }

    private void writeCounterMutation(
            HttpServerRequest request,
            VersionedMutationResult<CounterEntry> result,
            boolean created,
            String correlationId) {
        if (result.outcome() != ManagementMutationOutcome.APPLIED) {
            writeMutationOutcome(request, result.outcome(), correlationId, "COUNTER");
            return;
        }
        CounterEntry counter = result.representation();
        ObjectNode response = json.createObjectNode();
        response.put("namespace", counter.key().namespace());
        response.put("encodedNamespace",
                ManagementIdentifierCodec.encodeNamespace(counter.key().namespace()));
        response.put("key", counter.key().key());
        response.put("encodedKey", ManagementIdentifierCodec.encodeKey(counter.key().key()));
        response.put("value", Long.toString(counter.value()));
        response.put("version", Long.toString(counter.version()));
        response.put("createdAt", counter.createdAt().toString());
        response.put("updatedAt", counter.updatedAt().toString());
        response.set("ttl", ttl(counter.ttl()));
        request.response()
                .setStatusCode(created ? 201 : 200)
                .putHeader("content-type", "application/json; charset=utf-8")
                .putHeader("etag", ManagementEntityTagCodec.render(result.resultingVersion()))
                .putHeader("cache-control", "no-store")
                .putHeader("x-correlation-id", correlationId)
                .end(response.toString());
    }

    private void routeDeleteEntry(HttpServerRequest request, Matcher matcher) {
        String correlationId = correlationId(request);
        try {
            AuthenticatedManagementRequest authenticated = authenticator.authenticate(request);
            requireOperator(authenticated);
            browserSecurity.validateStateChange(
                    request.getHeader("Cookie"),
                    request.getHeader("X-PeeGeeQ-CSRF"),
                    authenticated.sessionCookie(),
                    authenticated.csrfToken(),
                    request.getHeader("Origin"));
            String setupId = requireCanonicalSetupId(matcher.group(1));
            CacheKey key = new CacheKey(
                    decodeNamespace(matcher.group(2)),
                    decodeKey(matcher.group(3)));
            long expectedVersion = exactIfMatch(request.getHeader("If-Match"));
            if (contentLength(request.getHeader("Content-Length")) != 0) {
                throw validationFailure();
            }
            registry.management(setupId)
                    .deleteEntry(
                            new VersionedEntryDeleteRequest(key, expectedVersion),
                            actionContext(authenticated, correlationId))
                    .onSuccess(result -> writeDeleteResult(request, result, correlationId))
                    .onFailure(failure -> writeProblem(request, failure, correlationId));
        } catch (Throwable failure) {
            writeProblem(request, failure, correlationId);
        }
    }

    private void writeDeleteResult(
            HttpServerRequest request,
            VersionedMutationResult<Void> result,
            String correlationId) {
        writeDeleteResult(request, result, correlationId, "ENTRY");
    }

    private void writeDeleteResult(
            HttpServerRequest request,
            VersionedMutationResult<Void> result,
            String correlationId,
            String resource) {
        if (result.outcome() != ManagementMutationOutcome.APPLIED) {
            writeMutationOutcome(request, result.outcome(), correlationId, resource);
            return;
        }
        request.response()
                .setStatusCode(204)
                .putHeader("cache-control", "no-store")
                .putHeader("x-correlation-id", correlationId)
                .end();
    }

    private void routeTouchEntry(HttpServerRequest request, Matcher matcher) {
        String correlationId = correlationId(request);
        try {
            AuthenticatedManagementRequest authenticated = authenticator.authenticate(request);
            requireOperator(authenticated);
            browserSecurity.validateStateChange(
                    request.getHeader("Cookie"),
                    request.getHeader("X-PeeGeeQ-CSRF"),
                    authenticated.sessionCookie(),
                    authenticated.csrfToken(),
                    request.getHeader("Origin"));
            requireJsonAccept(request.getHeader("Accept"));
            ManagementWireRules.requireJsonContentType(request.getHeader("Content-Type"));
            String setupId = requireCanonicalSetupId(matcher.group(1));
            CacheKey key = new CacheKey(
                    decodeNamespace(matcher.group(2)),
                    decodeKey(matcher.group(3)));
            long expectedVersion = exactIfMatch(request.getHeader("If-Match"));
            long contentLength = contentLength(request.getHeader("Content-Length"));
            ManagementWireRules.requireRequestSize(contentLength, REVEAL_REQUEST_MAX_BYTES);
            request.body()
                    .onSuccess(body -> touchEntry(
                            request,
                            setupId,
                            key,
                            expectedVersion,
                            authenticated,
                            correlationId,
                            body.getBytes()))
                    .onFailure(failure -> writeProblem(request, failure, correlationId));
        } catch (Throwable failure) {
            writeProblem(request, failure, correlationId);
        }
    }

    private void touchEntry(
            HttpServerRequest request,
            String setupId,
            CacheKey key,
            long expectedVersion,
            AuthenticatedManagementRequest authenticated,
            String correlationId,
            byte[] body) {
        try {
            ManagementWireRules.requireRequestSize(body.length, REVEAL_REQUEST_MAX_BYTES);
            Duration refreshTtl = parseRefreshTtl(body);
            registry.management(setupId)
                    .touchEntry(
                            new VersionedEntryTouchRequest(key, expectedVersion, refreshTtl),
                            actionContext(authenticated, correlationId))
                    .onSuccess(result -> writeEntryMutation(request, result, correlationId))
                    .onFailure(failure -> writeProblem(request, failure, correlationId));
        } catch (Throwable failure) {
            writeProblem(request, failure, correlationId);
        }
    }

    private Duration parseRefreshTtl(byte[] body) {
        try {
            JsonNode root = json.readTree(body);
            if (root == null || !root.isObject()
                    || !fieldNames(root).equals(Set.of("refreshTtlMillis"))) {
                throw validationFailure();
            }
            JsonNode ttl = root.get("refreshTtlMillis");
            return ttl.isNull() ? null : Duration.ofMillis(requiredPositiveLong(ttl));
        } catch (ManagementProtocolException failure) {
            throw failure;
        } catch (RuntimeException | java.io.IOException failure) {
            throw validationFailure();
        }
    }

    private void routePersistEntry(HttpServerRequest request, Matcher matcher) {
        String correlationId = correlationId(request);
        try {
            AuthenticatedManagementRequest authenticated = authenticator.authenticate(request);
            requireOperator(authenticated);
            browserSecurity.validateStateChange(
                    request.getHeader("Cookie"),
                    request.getHeader("X-PeeGeeQ-CSRF"),
                    authenticated.sessionCookie(),
                    authenticated.csrfToken(),
                    request.getHeader("Origin"));
            requireJsonAccept(request.getHeader("Accept"));
            String setupId = requireCanonicalSetupId(matcher.group(1));
            CacheKey key = new CacheKey(
                    decodeNamespace(matcher.group(2)),
                    decodeKey(matcher.group(3)));
            long expectedVersion = exactIfMatch(request.getHeader("If-Match"));
            long contentLength = contentLength(request.getHeader("Content-Length"));
            if (contentLength != 0) {
                throw validationFailure();
            }
            ManagementActionContext context = actionContext(authenticated, correlationId);
            registry.management(setupId)
                    .persistEntry(new VersionedCacheKeyRequest(key, expectedVersion), context)
                    .onSuccess(result -> writeEntryMutation(request, result, correlationId))
                    .onFailure(failure -> writeProblem(request, failure, correlationId));
        } catch (Throwable failure) {
            writeProblem(request, failure, correlationId);
        }
    }

    private void routeExpireEntry(HttpServerRequest request, Matcher matcher) {
        String correlationId = correlationId(request);
        try {
            AuthenticatedManagementRequest authenticated = authenticator.authenticate(request);
            requireOperator(authenticated);
            browserSecurity.validateStateChange(
                    request.getHeader("Cookie"),
                    request.getHeader("X-PeeGeeQ-CSRF"),
                    authenticated.sessionCookie(),
                    authenticated.csrfToken(),
                    request.getHeader("Origin"));
            requireJsonAccept(request.getHeader("Accept"));
            ManagementWireRules.requireJsonContentType(request.getHeader("Content-Type"));
            String setupId = requireCanonicalSetupId(matcher.group(1));
            CacheKey key = new CacheKey(
                    decodeNamespace(matcher.group(2)),
                    decodeKey(matcher.group(3)));
            long expectedVersion = exactIfMatch(request.getHeader("If-Match"));
            long contentLength = contentLength(request.getHeader("Content-Length"));
            ManagementWireRules.requireRequestSize(contentLength, REVEAL_REQUEST_MAX_BYTES);
            request.body()
                    .onSuccess(body -> expireEntry(
                            request,
                            setupId,
                            key,
                            expectedVersion,
                            authenticated,
                            correlationId,
                            body.getBytes()))
                    .onFailure(failure -> writeProblem(request, failure, correlationId));
        } catch (Throwable failure) {
            writeProblem(request, failure, correlationId);
        }
    }

    private void expireEntry(
            HttpServerRequest request,
            String setupId,
            CacheKey key,
            long expectedVersion,
            AuthenticatedManagementRequest authenticated,
            String correlationId,
            byte[] body) {
        try {
            ManagementWireRules.requireRequestSize(body.length, REVEAL_REQUEST_MAX_BYTES);
            Duration ttl = parseEntryTtl(body);
            ManagementActionContext context = actionContext(authenticated, correlationId);
            registry.management(setupId)
                    .expireEntry(new VersionedEntryTtlRequest(key, expectedVersion, ttl), context)
                    .onSuccess(result -> writeEntryMutation(request, result, correlationId))
                    .onFailure(failure -> writeProblem(request, failure, correlationId));
        } catch (Throwable failure) {
            writeProblem(request, failure, correlationId);
        }
    }

    private Duration parseEntryTtl(byte[] body) {
        try {
            JsonNode root = json.readTree(body);
            if (root == null || !root.isObject()
                    || !fieldNames(root).equals(Set.of("ttlMillis"))) {
                throw validationFailure();
            }
            return Duration.ofMillis(requiredPositiveLong(root.get("ttlMillis")));
        } catch (ManagementProtocolException failure) {
            throw failure;
        } catch (RuntimeException | java.io.IOException failure) {
            throw validationFailure();
        }
    }

    private void writeEntryMutation(
            HttpServerRequest request,
            VersionedMutationResult<ManagementEntryMetadata> result,
            String correlationId) {
        if (result.outcome() != ManagementMutationOutcome.APPLIED) {
            writeMutationOutcome(request, result.outcome(), correlationId, "ENTRY");
            return;
        }
        ManagementEntryMetadata metadata = result.representation();
        ObjectNode response = json.createObjectNode();
        response.put("key", metadata.key().key());
        response.put("valueType", metadata.valueType().name());
        response.put("sizeBytes", Long.toString(metadata.sizeBytes()));
        response.put("version", Long.toString(metadata.version()));
        response.put("createdAt", metadata.createdAt().toString());
        response.put("updatedAt", metadata.updatedAt().toString());
        response.set("ttl", ttl(metadata.ttl()));
        request.response()
                .setStatusCode(200)
                .putHeader("content-type", "application/json; charset=utf-8")
                .putHeader("etag", ManagementEntityTagCodec.render(result.resultingVersion()))
                .putHeader("cache-control", "no-store")
                .putHeader("x-correlation-id", correlationId)
                .end(response.toString());
    }

    private static long exactIfMatch(String value) {
        try {
            return ManagementEntityTagCodec.requireExactIfMatch(value);
        } catch (ManagementProtocolException failure) {
            throw failure;
        } catch (IllegalArgumentException failure) {
            throw validationFailure();
        }
    }

    private static ManagementActionContext actionContext(
            AuthenticatedManagementRequest authenticated,
            String correlationId) {
        return new ManagementActionContext(
                authenticated.identity().actor(),
                authenticated.identity().roles(),
                correlationId,
                authenticated.identity().sourceAddress());
    }

    private void routeSetEntry(HttpServerRequest request, Matcher matcher) {
        String correlationId = correlationId(request);
        try {
            AuthenticatedManagementRequest authenticated = authenticator.authenticate(request);
            requireOperator(authenticated);
            browserSecurity.validateStateChange(
                    request.getHeader("Cookie"),
                    request.getHeader("X-PeeGeeQ-CSRF"),
                    authenticated.sessionCookie(),
                    authenticated.csrfToken(),
                    request.getHeader("Origin"));
            requireJsonAccept(request.getHeader("Accept"));
            ManagementWireRules.requireJsonContentType(request.getHeader("Content-Type"));
            String setupId = requireCanonicalSetupId(matcher.group(1));
            CacheKey key = new CacheKey(
                    decodeNamespace(matcher.group(2)),
                    decodeKey(matcher.group(3)));
            long contentLength = contentLength(request.getHeader("Content-Length"));
            ManagementWireRules.requireRequestSize(contentLength, SET_REQUEST_MAX_BYTES);
            request.body()
                    .onSuccess(body -> setEntry(
                            request,
                            setupId,
                            key,
                            authenticated,
                            correlationId,
                            body.getBytes()))
                    .onFailure(failure -> writeProblem(request, failure, correlationId));
        } catch (Throwable failure) {
            writeProblem(request, failure, correlationId);
        }
    }

    private void setEntry(
            HttpServerRequest request,
            String setupId,
            CacheKey key,
            AuthenticatedManagementRequest authenticated,
            String correlationId,
            byte[] body) {
        try {
            ManagementWireRules.requireRequestSize(body.length, SET_REQUEST_MAX_BYTES);
            ParsedSet parsed = parseSet(body, key, request);
            ManagementActionContext context = new ManagementActionContext(
                    authenticated.identity().actor(),
                    authenticated.identity().roles(),
                    correlationId,
                    authenticated.identity().sourceAddress());
            registry.management(setupId)
                    .setEntry(parsed.request(), context)
                    .onSuccess(result -> writeSetResult(request, result, correlationId))
                    .onFailure(failure -> writeProblem(request, failure, correlationId));
        } catch (Throwable failure) {
            writeProblem(request, failure, correlationId);
        }
    }

    private ParsedSet parseSet(byte[] body, CacheKey key, HttpServerRequest request) {
        try {
            JsonNode root = json.readTree(body);
            if (root == null || !root.isObject()
                    || !fieldNames(root).equals(Set.of(
                            "value", "ttlMode", "ttlMillis", "setMode"))) {
                throw validationFailure();
            }
            SetMode mode = SetMode.valueOf(requiredText(root, "setMode"));
            ManagementPrecondition precondition = setPrecondition(
                    mode,
                    request.getHeader("If-Match"),
                    request.getHeader("If-None-Match"));
            EntryTtlMode ttlMode = EntryTtlMode.valueOf(requiredText(root, "ttlMode"));
            JsonNode ttlNode = root.get("ttlMillis");
            Duration ttl = ttlNode == null || ttlNode.isNull()
                    ? null
                    : Duration.ofMillis(requiredPositiveLong(ttlNode));
            CacheValue value = cacheValue(root.get("value"));
            return new ParsedSet(new ManagementCacheSetRequest(
                    key,
                    value,
                    mode,
                    precondition.version(),
                    ttlMode,
                    ttl));
        } catch (ManagementProtocolException failure) {
            throw failure;
        } catch (RuntimeException | java.io.IOException failure) {
            throw validationFailure();
        }
    }

    private static ManagementPrecondition setPrecondition(
            SetMode mode,
            String ifMatch,
            String ifNoneMatch) {
        if (mode != SetMode.UPSERT && ifMatch == null && ifNoneMatch == null) {
            throw new ManagementProtocolException(
                    428, "PRECONDITION_REQUIRED", "The selected set mode requires a precondition");
        }
        final ManagementPrecondition precondition;
        try {
            precondition = ManagementEntityTagCodec.parse(ifMatch, ifNoneMatch, true, true);
        } catch (IllegalArgumentException failure) {
            throw validationFailure();
        }
        boolean compatible = switch (mode) {
            case UPSERT -> precondition.kind() == ManagementPrecondition.Kind.NONE;
            case ONLY_IF_ABSENT -> precondition.kind() == ManagementPrecondition.Kind.REQUIRE_ABSENT;
            case ONLY_IF_PRESENT -> precondition.kind() == ManagementPrecondition.Kind.REQUIRE_PRESENT;
            case ONLY_IF_VERSION_MATCHES ->
                    precondition.kind() == ManagementPrecondition.Kind.EXACT_VERSION;
        };
        if (!compatible) {
            throw validationFailure();
        }
        return precondition;
    }

    private static CacheValue cacheValue(JsonNode node) {
        if (node == null || !node.isObject()) {
            throw validationFailure();
        }
        String typeName = requiredText(node, "type");
        ValueType type = ValueType.valueOf(typeName);
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

    private static void requireFields(JsonNode node, Set<String> expected) {
        if (!fieldNames(node).equals(expected)) {
            throw validationFailure();
        }
    }

    private static Set<String> fieldNames(JsonNode node) {
        Set<String> names = new HashSet<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }

    private static String requiredText(JsonNode node, String name) {
        String value = requiredTextAllowEmpty(node, name);
        if (value.isEmpty()) {
            throw validationFailure();
        }
        return value;
    }

    private static String requiredTextAllowEmpty(JsonNode node, String name) {
        JsonNode value = node.get(name);
        if (value == null || !value.isTextual()) {
            throw validationFailure();
        }
        return value.textValue();
    }

    private static long requiredPositiveLong(JsonNode node) {
        if (!node.isIntegralNumber() || !node.canConvertToLong() || node.longValue() <= 0) {
            throw validationFailure();
        }
        return node.longValue();
    }

    private void writeSetResult(
            HttpServerRequest request,
            ManagementSetResult result,
            String correlationId) {
        if (result.outcome() != ManagementMutationOutcome.APPLIED) {
            writeMutationOutcome(request, result.outcome(), correlationId, "ENTRY");
            return;
        }
        ObjectNode response = json.createObjectNode();
        response.put("applied", true);
        response.put("created", result.created());
        response.put("version", Long.toString(result.resultingVersion()));
        response.put("updatedAt", result.representation().updatedAt().toString());
        response.set("ttl", ttl(result.representation().ttl()));
        request.response()
                .setStatusCode(result.created() ? 201 : 200)
                .putHeader("content-type", "application/json; charset=utf-8")
                .putHeader("etag", ManagementEntityTagCodec.render(result.resultingVersion()))
                .putHeader("cache-control", "no-store")
                .putHeader("x-correlation-id", correlationId)
                .end(response.toString());
    }

    private ObjectNode ttl(ManagementTtl ttl) {
        ObjectNode node = json.createObjectNode();
        node.put("state", ttl.state().name());
        if (ttl.ttlMillis() == null) {
            node.putNull("ttlMillis");
        } else {
            node.put("ttlMillis", ttl.ttlMillis());
        }
        if (ttl.expiresAt() == null) {
            node.putNull("expiresAt");
        } else {
            node.put("expiresAt", ttl.expiresAt().toString());
        }
        return node;
    }

    private void writeMutationOutcome(
            HttpServerRequest request,
            ManagementMutationOutcome outcome,
            String correlationId,
            String resource) {
        ManagementProtocolException failure = switch (outcome) {
            case NOT_FOUND -> new ManagementProtocolException(
                    404, resource + "_NOT_FOUND", resource + " was not found");
            case VERSION_MISMATCH -> new ManagementProtocolException(
                    412, "VERSION_MISMATCH", "The resource version does not match");
            case CONDITION_NOT_MET -> new ManagementProtocolException(
                    409, "SET_MODE_NOT_APPLIED", "The requested set condition was not met");
            case APPLIED -> throw new IllegalArgumentException("Applied outcome requires a response");
        };
        writeProblem(request, failure, correlationId);
    }

    private void revealEntry(
            HttpServerRequest request,
            String setupId,
            CacheKey key,
            AuthenticatedManagementRequest authenticated,
            String correlationId,
            byte[] body) {
        try {
            ManagementWireRules.requireRequestSize(body.length, REVEAL_REQUEST_MAX_BYTES);
            if (body.length > 0) {
                ManagementWireRules.requireJsonContentType(request.getHeader("Content-Type"));
            }
            String reason = revealReason(body);
            rateLimiter.acquire(
                    RateLimitAction.REVEAL,
                    authenticated.identity().actor(),
                    InetAddress.getByName(authenticated.identity().sourceAddress()));
            ManagementActionContext context = new ManagementActionContext(
                    authenticated.identity().actor(),
                    authenticated.identity().roles(),
                    correlationId,
                    authenticated.identity().sourceAddress());
            registry.management(setupId)
                    .revealEntry(new RevealEntryRequest(key, reason), context)
                    .onSuccess(revealed -> writeRevealed(request, revealed, correlationId))
                    .onFailure(failure -> writeProblem(request, failure, correlationId));
        } catch (Throwable failure) {
            writeProblem(request, failure, correlationId);
        }
    }

    private void revealLockOwner(
            HttpServerRequest request,
            String setupId,
            LockKey key,
            AuthenticatedManagementRequest authenticated,
            String correlationId,
            byte[] body) {
        try {
            ManagementWireRules.requireRequestSize(body.length, REVEAL_REQUEST_MAX_BYTES);
            if (body.length > 0) {
                ManagementWireRules.requireJsonContentType(request.getHeader("Content-Type"));
            }
            String reason = revealReason(body);
            rateLimiter.acquire(
                    RateLimitAction.REVEAL,
                    authenticated.identity().actor(),
                    InetAddress.getByName(authenticated.identity().sourceAddress()));
            ManagementActionContext context = new ManagementActionContext(
                    authenticated.identity().actor(),
                    authenticated.identity().roles(),
                    correlationId,
                    authenticated.identity().sourceAddress());
            registry.management(setupId)
                    .revealLockOwner(new RevealLockOwnerRequest(key, reason), context)
                    .onSuccess(revealed -> writeRevealedOwner(request, revealed, correlationId))
                    .onFailure(failure -> writeProblem(request, failure, correlationId));
        } catch (Throwable failure) {
            writeProblem(request, failure, correlationId);
        }
    }

    private String revealReason(byte[] body) {
        if (body.length == 0) {
            return DEFAULT_REVEAL_REASON;
        }
        try {
            JsonNode root = json.readTree(body);
            if (root == null || !root.isObject()) {
                throw validationFailure();
            }
            Set<String> names = new HashSet<>();
            root.fieldNames().forEachRemaining(names::add);
            if (!names.isEmpty() && !names.equals(Set.of("reason"))) {
                throw validationFailure();
            }
            JsonNode reason = root.get("reason");
            if (reason == null) {
                return DEFAULT_REVEAL_REASON;
            }
            if (!reason.isTextual()) {
                throw validationFailure();
            }
            return boundedReason(reason.textValue());
        } catch (ManagementProtocolException failure) {
            throw failure;
        } catch (RuntimeException | java.io.IOException failure) {
            throw validationFailure();
        }
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

    private void writeRevealed(
            HttpServerRequest request,
            RevealedEntryValue revealed,
            String correlationId) {
        ObjectNode response = json.createObjectNode();
        response.put("key", revealed.key().key());
        response.put("version", Long.toString(revealed.version()));
        response.set("value", value(revealed.value()));
        response.put("revealedAt", revealed.revealedAt().toString());
        response.put("autoHideAfterMillis", REVEAL_AUTO_HIDE_MILLIS);
        request.response()
                .setStatusCode(200)
                .putHeader("content-type", "application/json; charset=utf-8")
                .putHeader("cache-control", "no-store, no-cache, must-revalidate")
                .putHeader("pragma", "no-cache")
                .putHeader("x-correlation-id", correlationId)
                .end(response.toString());
    }

    private void writeRevealedOwner(
            HttpServerRequest request,
            RevealedLockOwner revealed,
            String correlationId) {
        ObjectNode response = json.createObjectNode();
        response.put("key", revealed.key().key());
        response.put("ownerToken", revealed.ownerToken());
        response.put("version", Long.toString(revealed.version()));
        response.put("revealedAt", revealed.revealedAt().toString());
        response.put("autoHideAfterMillis", REVEAL_AUTO_HIDE_MILLIS);
        request.response()
                .setStatusCode(200)
                .putHeader("content-type", "application/json; charset=utf-8")
                .putHeader("cache-control", "no-store, no-cache, must-revalidate")
                .putHeader("pragma", "no-cache")
                .putHeader("x-correlation-id", correlationId)
                .end(response.toString());
    }

    private ObjectNode value(CacheValue value) {
        ObjectNode node = json.createObjectNode();
        node.put("type", value.type().name());
        switch (value.type()) {
            case STRING, JSON -> node.put("text", value.asString());
            case LONG -> node.put("decimal", Long.toString(value.asLong()));
            case BYTES -> node.put("base64",
                    Base64.getEncoder().encodeToString(value.binaryValue().getBytes()));
        }
        return node;
    }

    private static void requireJsonAccept(String accept) {
        if (accept == null || accept.isBlank() || accept.equals("*/*")
                || accept.equals("application/json")) {
            return;
        }
        throw new ManagementProtocolException(
                406, "NOT_ACCEPTABLE", "Reveal responses use application/json");
    }

    private static String requireCanonicalSetupId(String setupId) {
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

    private static ManagementProtocolException invalidIdentifier() {
        return new ManagementProtocolException(
                400, "INVALID_IDENTIFIER", "Management identifier is invalid");
    }

    private static long contentLength(String value) {
        if (value == null) {
            return 0;
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

    private static void requireOperator(AuthenticatedManagementRequest authenticated) {
        if (!authenticated.identity().roles().contains("operator")) {
            throw new ManagementSecurityException(
                    403, "AUTHORIZATION_FAILED", "Operator role is required");
        }
    }

    private static String correlationId(HttpServerRequest request) {
        return ManagementWireRules.correlationId(
                request.getHeader("X-Correlation-ID"), () -> UUID.randomUUID().toString());
    }

    private static ManagementProtocolException validationFailure() {
        return new ManagementProtocolException(
                400, "VALIDATION_FAILED", "Reveal request body is invalid");
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

    private record ParsedSet(ManagementCacheSetRequest request) {
    }
}
