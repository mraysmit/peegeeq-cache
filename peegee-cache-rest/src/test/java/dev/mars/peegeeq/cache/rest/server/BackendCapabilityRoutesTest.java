package dev.mars.peegeeq.cache.rest.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.mars.peegeeq.cache.api.PeeGeeCache;
import dev.mars.peegeeq.cache.api.admin.AdminService;
import dev.mars.peegeeq.cache.api.cache.CacheService;
import dev.mars.peegeeq.cache.api.lock.LockService;
import dev.mars.peegeeq.cache.api.management.ManagementAuditFingerprinter;
import dev.mars.peegeeq.cache.api.management.ManagementAuditIntent;
import dev.mars.peegeeq.cache.api.management.ManagementAuditOutcome;
import dev.mars.peegeeq.cache.api.management.ManagementAuditReservation;
import dev.mars.peegeeq.cache.api.management.ManagementAuditSink;
import dev.mars.peegeeq.cache.api.management.ManagementSecretReference;
import dev.mars.peegeeq.cache.api.model.CacheEntry;
import dev.mars.peegeeq.cache.api.model.CacheKey;
import dev.mars.peegeeq.cache.api.model.CacheSetRequest;
import dev.mars.peegeeq.cache.api.model.CacheSetResult;
import dev.mars.peegeeq.cache.api.model.CacheValue;
import dev.mars.peegeeq.cache.api.model.LockAcquireResult;
import dev.mars.peegeeq.cache.api.model.MetricsSnapshot;
import dev.mars.peegeeq.cache.api.model.ScanResult;
import dev.mars.peegeeq.cache.api.scan.ScanService;
import dev.mars.peegeeq.cache.rest.protocol.ManagementIdentifierCodec;
import dev.mars.peegeeq.cache.rest.security.AuthenticatedManagementIdentity;
import dev.mars.peegeeq.cache.rest.security.BrowserOriginPolicy;
import dev.mars.peegeeq.cache.rest.security.BrowserRequestSecurity;
import dev.mars.peegeeq.cache.rest.security.ManagementAuthenticationException;
import dev.mars.peegeeq.cache.rest.security.ManagementRateLimiter;
import dev.mars.peegeeq.cache.rest.security.RateLimitAction;
import dev.mars.peegeeq.cache.rest.security.RateLimitRule;
import dev.mars.peegeeq.cache.rest.security.RateLimitTelemetry;
import dev.mars.peegeeq.cache.rest.security.SetupTarget;
import dev.mars.peegeeq.cache.rest.security.TlsMode;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BackendCapabilityRoutesTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void exposesEveryBackendCapabilityWithAuthorizationValidationAuditAndExactResults() throws Exception {
        Vertx vertx = Vertx.vertx();
        int port = freePort();
        String origin = "http://127.0.0.1:" + port;
        RecordingAuditSink audit = new RecordingAuditSink();
        SetupRegistry registry = new SetupRegistry(
                (definition, secret) -> Future.succeededFuture(new FacadeRuntime(facade())),
                ignored -> null);
        await(registry.register(definition(), SetupSecret.owned("password".getBytes(StandardCharsets.UTF_8))));
        ManagementRequestAuthenticator authenticator = request -> {
            String cookie = request.getHeader("Cookie");
            if (cookie == null) {
                throw new ManagementAuthenticationException(
                        401, "AUTHENTICATION_REQUIRED", "Authentication required");
            }
            Set<String> roles = cookie.equals("role=operator")
                    ? Set.of("viewer", "operator") : Set.of("viewer");
            return new AuthenticatedManagementRequest(
                    new AuthenticatedManagementIdentity("alex", roles, "127.0.0.1"),
                    cookie,
                    "csrf-token");
        };
        ManagementAuditFingerprinter fingerprinter = new ManagementAuditFingerprinter(
                new ManagementSecretReference("test-key"), ignored -> new byte[32], "test", 128);
        ManagementRateLimiter rateLimiter = new ManagementRateLimiter(
                java.util.Arrays.stream(RateLimitAction.values()).collect(Collectors.toMap(
                        action -> action,
                        action -> new RateLimitRule(50, 100, Duration.ofMinutes(1)))),
                100,
                Clock.systemUTC(),
                RateLimitTelemetry.noop());
        ManagementHttpServer server = new ManagementHttpServer(
                vertx,
                TestManagementConfigurations.local(port),
                ManagementServerResources.noop(),
                new BackendCapabilityRoutes(
                        registry,
                        authenticator,
                        new BrowserRequestSecurity(BrowserOriginPolicy.localToken(origin)),
                        audit,
                        fingerprinter,
                        rateLimiter,
                        Clock.systemUTC()));
        try {
            await(server.start());
            String namespace = ManagementIdentifierCodec.encodeNamespace("orders");
            String present = ManagementIdentifierCodec.encodeKey("present");
            String lock = ManagementIdentifierCodec.encodeKey("lease");

            assertEquals(401, get(port, "/api/v1/setups/test/namespaces/" + namespace
                    + "/entries/" + present + "/exists", null).statusCode());
            JsonNode exists = body(get(port, "/api/v1/setups/test/namespaces/" + namespace
                    + "/entries/" + present + "/exists", "role=viewer"));
            assertTrue(exists.path("exists").asBoolean());

            HttpResponse<String> forbidden = post(port, origin, "role=viewer",
                    "/api/v1/setups/test/entries/batch-get",
                    "{\"keys\":[{\"namespace\":\"orders\",\"key\":\"present\"}],\"reason\":\"Route test reveal\"}");
            assertEquals(403, forbidden.statusCode());
            assertTrue(audit.intents.isEmpty());

            JsonNode batchGet = body(post(port, origin, "role=operator",
                    "/api/v1/setups/test/entries/batch-get",
                    "{\"keys\":[{\"namespace\":\"orders\",\"key\":\"present\"},"
                            + "{\"namespace\":\"orders\",\"key\":\"missing\"}],"
                            + "\"reason\":\"Route test reveal\"}"));
            assertTrue(batchGet.path("items").get(0).path("found").asBoolean());
            assertEquals("value", batchGet.path("items").get(0).path("entry")
                    .path("value").path("text").asText());
            assertFalse(batchGet.path("items").get(1).path("found").asBoolean());

            JsonNode batchSet = body(post(port, origin, "role=operator",
                    "/api/v1/setups/test/entries/batch-set",
                    "{\"entries\":[{\"namespace\":\"orders\",\"key\":\"new\","
                            + "\"value\":{\"type\":\"STRING\",\"text\":\"next\"},"
                            + "\"ttlMillis\":null,\"setMode\":\"UPSERT\","
                            + "\"expectedVersion\":null,\"returnPreviousValue\":true}]}"));
            assertTrue(batchSet.path("items").get(0).path("applied").asBoolean());
            assertEquals("9", batchSet.path("items").get(0).path("newVersion").asText());
            assertEquals("value", batchSet.path("items").get(0).path("previousEntry")
                    .path("value").path("text").asText());

            JsonNode scan = body(post(port, origin, "role=operator",
                    "/api/v1/setups/test/entries/scan",
                    "{\"namespace\":\"orders\",\"prefix\":null,\"cursor\":null,"
                            + "\"limit\":20,\"includeValues\":true,\"includeExpired\":false,"
                            + "\"reason\":\"Route test scan\"}"));
            assertEquals("value", scan.path("entries").get(0).path("value").path("text").asText());
            assertFalse(scan.path("hasMore").asBoolean());

            JsonNode metrics = body(get(port, "/api/v1/setups/test/cache-metrics", "role=viewer"));
            assertEquals("9223372036854775807", metrics.path("cacheGets").asText());
            assertEquals("15", metrics.path("subscribes").asText());

            String lockPath = "/api/v1/setups/test/namespaces/" + namespace + "/locks/" + lock;
            JsonNode acquired = body(post(port, origin, "role=operator", lockPath + "/acquire",
                    "{\"ownerToken\":\"owner-secret\",\"leaseTtlMillis\":30000,"
                            + "\"reentrantForSameOwner\":false,\"issueFencingToken\":true}"));
            assertTrue(acquired.path("acquired").asBoolean());
            assertEquals("77", acquired.path("fencingToken").asText());
            assertTrue(body(post(port, origin, "role=operator", lockPath + "/ownership",
                    "{\"ownerToken\":\"owner-secret\"}")).path("heldByOwner").asBoolean());
            assertTrue(body(post(port, origin, "role=operator", lockPath + "/renew",
                    "{\"ownerToken\":\"owner-secret\",\"leaseTtlMillis\":30000}"))
                    .path("renewed").asBoolean());
            assertTrue(body(post(port, origin, "role=operator", lockPath + "/release",
                    "{\"ownerToken\":\"owner-secret\"}")).path("released").asBoolean());

            assertEquals(List.of(
                            "BATCH_GET_ENTRIES", "BATCH_SET_ENTRIES", "SCAN_ENTRY_VALUES",
                            "ACQUIRE_LOCK", "CHECK_LOCK_OWNERSHIP", "RENEW_LOCK", "RELEASE_LOCK"),
                    audit.intents.stream().map(intent -> intent.action().name()).toList());
            assertEquals(audit.intents.size(), audit.outcomes.size());
            assertTrue(audit.intents.stream().noneMatch(intent ->
                    intent.toString().contains("owner-secret") || intent.toString().contains("present")));

            HttpResponse<String> invalid = post(port, origin, "role=operator",
                    "/api/v1/setups/test/entries/batch-set", "{\"entries\":[]}");
            assertEquals(400, invalid.statusCode());
            assertEquals("VALIDATION_FAILED", JSON.readTree(invalid.body()).path("code").asText());

            HttpResponse<String> invalidMode = post(port, origin, "role=operator",
                    "/api/v1/setups/test/entries/batch-set",
                    "{\"entries\":[{\"namespace\":\"orders\",\"key\":\"new\","
                            + "\"value\":{\"type\":\"STRING\",\"text\":\"next\"},"
                            + "\"ttlMillis\":null,\"setMode\":\"UNKNOWN\","
                            + "\"expectedVersion\":null,\"returnPreviousValue\":false}]}");
            assertEquals(400, invalidMode.statusCode());
            assertEquals("VALIDATION_FAILED",
                    JSON.readTree(invalidMode.body()).path("code").asText());

            HttpResponse<String> invalidBase64 = post(port, origin, "role=operator",
                    "/api/v1/setups/test/entries/batch-set",
                    "{\"entries\":[{\"namespace\":\"orders\",\"key\":\"new\","
                            + "\"value\":{\"type\":\"BYTES\",\"base64\":\"%%%\"},"
                            + "\"ttlMillis\":null,\"setMode\":\"UPSERT\","
                            + "\"expectedVersion\":null,\"returnPreviousValue\":false}]}");
            assertEquals(400, invalidBase64.statusCode());
            assertEquals("VALIDATION_FAILED",
                    JSON.readTree(invalidBase64.body()).path("code").asText());

            HttpResponse<String> oversizedOwner = post(port, origin, "role=operator",
                    lockPath + "/release", "{\"ownerToken\":\"" + "x".repeat(4_097) + "\"}");
            assertEquals(400, oversizedOwner.statusCode());
            assertEquals("VALIDATION_FAILED",
                    JSON.readTree(oversizedOwner.body()).path("code").asText());

            HttpResponse<String> invalidLockIdentifier = post(port, origin, "role=operator",
                    "/api/v1/setups/test/namespaces/AA/locks/" + lock + "/acquire",
                    "{\"ownerToken\":\"owner-secret\",\"leaseTtlMillis\":30000,"
                            + "\"reentrantForSameOwner\":false,\"issueFencingToken\":true}");
            assertEquals(400, invalidLockIdentifier.statusCode());
            assertEquals("INVALID_IDENTIFIER",
                    JSON.readTree(invalidLockIdentifier.body()).path("code").asText());
        } finally {
            await(server.stop());
            await(registry.closeAsync());
            await(vertx.close());
        }
    }

    private static PeeGeeCache facade() {
        CacheEntry entry = new CacheEntry(
                new CacheKey("orders", "present"), CacheValue.ofString("value"), 8,
                Instant.parse("2026-01-01T00:00:00Z"), Instant.parse("2026-01-01T00:00:01Z"),
                null, 3, null);
        CacheService cache = proxy(CacheService.class, (name, arguments) -> switch (name) {
            case "exists" -> Future.succeededFuture(true);
            case "getMany" -> {
                @SuppressWarnings("unchecked")
                List<CacheKey> keys = (List<CacheKey>) arguments[0];
                Map<CacheKey, Optional<CacheEntry>> values = new LinkedHashMap<>();
                keys.forEach(key -> values.put(key, key.key().equals("present")
                        ? Optional.of(entry) : Optional.empty()));
                yield Future.succeededFuture(values);
            }
            case "setMany" -> {
                @SuppressWarnings("unchecked")
                List<CacheSetRequest> requests = (List<CacheSetRequest>) arguments[0];
                Map<CacheKey, CacheSetResult> values = new LinkedHashMap<>();
                requests.forEach(request -> values.put(
                        request.key(), new CacheSetResult(true, 9, entry)));
                yield Future.succeededFuture(values);
            }
            default -> throw new UnsupportedOperationException(name);
        });
        ScanService scan = proxy(ScanService.class, (name, arguments) ->
                Future.succeededFuture(new ScanResult(List.of(entry), null, false)));
        LockService locks = proxy(LockService.class, (name, arguments) -> switch (name) {
            case "acquire" -> Future.succeededFuture(new LockAcquireResult(
                    true,
                    new dev.mars.peegeeq.cache.api.model.LockKey("orders", "lease"),
                    "owner-secret",
                    77L,
                    Instant.parse("2026-01-01T00:01:00Z")));
            case "renew", "release", "isHeldBy" -> Future.succeededFuture(true);
            default -> throw new UnsupportedOperationException(name);
        });
        AdminService admin = proxy(AdminService.class, (name, arguments) -> {
            if (name.equals("metrics")) {
                return new MetricsSnapshot(Long.MAX_VALUE, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15);
            }
            throw new UnsupportedOperationException(name);
        });
        return proxy(PeeGeeCache.class, (name, arguments) -> switch (name) {
            case "cache" -> cache;
            case "scan" -> scan;
            case "locks" -> locks;
            case "admin" -> admin;
            default -> throw new UnsupportedOperationException(name);
        });
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, Invocation invocation) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type},
                (ignored, method, arguments) -> invocation.invoke(
                        method.getName(), arguments == null ? new Object[0] : arguments));
    }

    private static JsonNode body(HttpResponse<String> response) throws Exception {
        assertEquals(200, response.statusCode(), response.body());
        assertEquals("no-store", response.headers().firstValue("cache-control").orElse("")
                .split(",", 2)[0]);
        assertEquals("no-cache", response.headers().firstValue("pragma").orElseThrow());
        return JSON.readTree(response.body());
    }

    private static HttpResponse<String> get(int port, String path, String cookie) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .header("Accept", "application/json");
        if (cookie != null) request.header("Cookie", cookie);
        return HttpClient.newHttpClient().send(request.GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private static HttpResponse<String> post(
            int port, String origin, String cookie, String path, String body) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .header("Accept", "application/json")
                .header("Content-Type", "application/json")
                .header("Origin", origin)
                .header("X-PeeGeeQ-CSRF", "csrf-token");
        if (cookie != null) request.header("Cookie", cookie);
        return HttpClient.newHttpClient().send(
                request.POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static SetupDefinition definition() {
        return new SetupDefinition(
                "test", "Test", new SetupTarget("db.internal.example", 5432, "test-ca", TlsMode.VERIFY_FULL),
                "peegeeq", "peegee_cache", "database-user", 3, SetupSource.UI_SESSION, null);
    }

    private static int freePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static <T> T await(Future<T> future) throws Exception {
        return future.toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
    }

    @FunctionalInterface
    private interface Invocation {
        Object invoke(String name, Object[] arguments) throws Throwable;
    }

    private record FacadeRuntime(PeeGeeCache cache) implements ManagedSetupRuntime {
        @Override public Future<Void> verifyReady() { return Future.succeededFuture(); }
        @Override public Future<Void> closeAsync() { return Future.succeededFuture(); }
    }

    private static final class RecordingAuditSink implements ManagementAuditSink {
        private final List<ManagementAuditIntent> intents = new ArrayList<>();
        private final List<ManagementAuditOutcome> outcomes = new ArrayList<>();

        @Override
        public Future<ManagementAuditReservation> reserveIntent(ManagementAuditIntent intent) {
            intents.add(intent);
            return Future.succeededFuture(new ManagementAuditReservation(
                    UUID.randomUUID().toString(), intent.eventId(), "test-generation"));
        }

        @Override
        public Future<Void> complete(
                ManagementAuditReservation reservation, ManagementAuditOutcome outcome) {
            outcomes.add(outcome);
            return Future.succeededFuture();
        }
    }
}
