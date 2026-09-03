package dev.mars.peegeeq.cache.rest.server;

import dev.mars.peegeeq.cache.pg.bootstrap.PgSchemaMigrator;
import dev.mars.peegeeq.cache.api.management.ManagementCapability;
import dev.mars.peegeeq.cache.api.management.ManagementActivityEvent;
import dev.mars.peegeeq.cache.api.management.ManagementActivityResource;
import dev.mars.peegeeq.cache.api.management.ManagementActivityResourceType;
import dev.mars.peegeeq.cache.api.management.ManagementAuditQueueState;
import dev.mars.peegeeq.cache.api.management.ManagementAuditAction;
import dev.mars.peegeeq.cache.api.management.ManagementAuditException;
import dev.mars.peegeeq.cache.api.management.ManagementAuditFingerprinter;
import dev.mars.peegeeq.cache.api.management.ManagementAuditIntent;
import dev.mars.peegeeq.cache.api.management.ManagementAuditOutcome;
import dev.mars.peegeeq.cache.api.management.ManagementAuditReservation;
import dev.mars.peegeeq.cache.api.management.ManagementAuditSink;
import dev.mars.peegeeq.cache.api.management.ManagementSecretReference;
import dev.mars.peegeeq.cache.api.management.ManagementAuditTerminalOutcome;
import dev.mars.peegeeq.cache.api.model.PubSubMessage;
import dev.mars.peegeeq.cache.api.model.CacheKey;
import dev.mars.peegeeq.cache.api.model.CacheSetRequest;
import dev.mars.peegeeq.cache.api.model.CacheValue;
import dev.mars.peegeeq.cache.api.model.SetMode;
import dev.mars.peegeeq.cache.api.pubsub.Subscription;
import dev.mars.peegeeq.cache.rest.protocol.ManagementIdentifierCodec;
import dev.mars.peegeeq.cache.rest.security.AuthenticatedManagementIdentity;
import dev.mars.peegeeq.cache.rest.security.BrowserOriginPolicy;
import dev.mars.peegeeq.cache.rest.security.BrowserRequestSecurity;
import dev.mars.peegeeq.cache.rest.security.ManagementRateLimiter;
import dev.mars.peegeeq.cache.rest.security.RateLimitAction;
import dev.mars.peegeeq.cache.rest.security.RateLimitRule;
import dev.mars.peegeeq.cache.rest.security.RateLimitTelemetry;
import dev.mars.peegeeq.cache.rest.security.SetupTarget;
import dev.mars.peegeeq.cache.rest.security.SetupTargetPolicy;
import dev.mars.peegeeq.cache.rest.security.TlsMode;
import dev.mars.peegeeq.cache.test.PostgreSQLTestConstants;
import dev.mars.peegeeq.cache.runtime.bootstrap.SchemaBootstrapMode;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.PoolOptions;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.MountableFile;

import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.LinkedBlockingQueue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PostgresSetupRuntimeFactoryTest {

    private static PostgreSQLContainer postgres;
    private static Buffer serverCertificate;
    private static Vertx bootstrapVertx;

    @BeforeAll
    static void startDatabase() throws Exception {
        postgres = new PostgreSQLContainer(PostgreSQLTestConstants.postgresImage())
                .withDatabaseName("peegeeq")
                .withUsername("peegeeq")
                .withPassword("test-password")
                .withCopyFileToContainer(
                        MountableFile.forClasspathResource("postgres-tls-init.sh", 0775),
                        "/docker-entrypoint-initdb.d/010-postgres-tls-init.sh")
                .withCopyFileToContainer(
                        MountableFile.forClasspathResource("postgres-tls-server.crt", 0444),
                        "/docker-entrypoint-initdb.d/server.crt")
                .withCopyFileToContainer(
                        MountableFile.forClasspathResource("postgres-tls-server.key", 0444),
                        "/docker-entrypoint-initdb.d/server.key");
        postgres.start();
        try (var certificate = PostgresSetupRuntimeFactoryTest.class.getResourceAsStream(
                "/postgres-tls-server.crt")) {
            serverCertificate = Buffer.buffer(certificate.readAllBytes());
        }

        bootstrapVertx = Vertx.vertx();
        Pool pool = Pool.pool(bootstrapVertx, new PgConnectOptions()
                .setHost(postgres.getHost())
                .setPort(postgres.getMappedPort(5432))
                .setDatabase("peegeeq")
                .setUser("peegeeq")
                .setPassword("test-password"), new PoolOptions().setMaxSize(1));
        await(new PgSchemaMigrator(pool, "peegee_cache").migrate());
        await(pool.close());
    }

    @AfterAll
    static void stopDatabase() throws Exception {
        if (bootstrapVertx != null) {
            await(bootstrapVertx.close());
        }
        if (postgres != null) {
            postgres.stop();
        }
    }

    @Test
    void configuredRuntimeAffectsRealCacheAndCapabilities() throws Exception {
        InetAddress pinnedAddress = InetAddress.getByName(postgres.getHost());
        SetupTargetPolicy policy = new SetupTargetPolicy(
                Set.of("internal.example"), Set.of("127.0.0.0/8"),
                Set.of(postgres.getMappedPort(5432)), true, false, false, false, Set.of("test-ca"));
        ManagementAuditFingerprinter fingerprinter = new ManagementAuditFingerprinter(
                new ManagementSecretReference("test-audit-key"),
                ignored -> new byte[32],
                "test-audit-key",
                128);
        PostgresSetupRuntimeFactory factory = new PostgresSetupRuntimeFactory(
                policy,
                ignored -> List.of(pinnedAddress),
                ignored -> serverCertificate,
                Duration.ofSeconds(10),
                new RecordingAuditSink(),
                fingerprinter,
                Clock.systemUTC(),
                () -> UUID.randomUUID().toString());
        SetupRegistry registry = new SetupRegistry(factory, ignored -> null);
        SetupRuntimeConfiguration configuration = new SetupRuntimeConfiguration(
                120_000L,
                true,
                12_000L,
                321,
                false,
                250L,
                1_200,
                200,
                4,
                4_000L,
                "configured_cache",
                false,
                SchemaBootstrapMode.EXTERNAL,
                SetupRuntimeConfiguration.TelemetryMode.NOOP);
        SetupDefinition definition = new SetupDefinition(
                "configured-runtime",
                "Configured runtime",
                new SetupTarget("db.internal.example", postgres.getMappedPort(5432),
                        "test-ca", TlsMode.VERIFY_FULL),
                "peegeeq",
                "peegee_cache",
                "peegeeq",
                3,
                configuration,
                SetupSource.UI_SESSION,
                null);

        try {
            await(registry.register(definition, SetupSecret.owned(
                    "test-password".getBytes(StandardCharsets.UTF_8))));
            SetupRuntimeSummary configuredRuntime = registry.details("configured-runtime").runtime();
            assertEquals(120_000L, configuredRuntime.defaultTtlMillis());
            assertEquals(12_000L, configuredRuntime.expirySweepIntervalMillis());
            assertEquals(321, configuredRuntime.expirySweepBatchSize());
            assertEquals("configured_cache", configuredRuntime.pubSubChannelPrefix());
            assertEquals("EXTERNAL", configuredRuntime.schemaBootstrapMode());
            assertFalse(configuredRuntime.pubSubEnabled());
            assertFalse(registry.capabilities("configured-runtime").features().pubSub());

            CacheKey key = new CacheKey("runtime-config", "default-ttl");
            java.time.Instant beforeSet = java.time.Instant.now();
            assertTrue(await(registry.cache("configured-runtime").cache().set(new CacheSetRequest(
                    key, CacheValue.ofString("configured"), null, SetMode.UPSERT, null, false))).applied());
            var entry = await(registry.cache("configured-runtime").cache().get(key)).orElseThrow();
            assertTrue(entry.expiresAt().isAfter(beforeSet.plusSeconds(115)));
            assertTrue(entry.expiresAt().isBefore(beforeSet.plusSeconds(125)));
            assertTrue(await(registry.cache("configured-runtime").cache().delete(key)));
        } finally {
            await(registry.closeAsync());
        }
        assertEquals(0, applicationConnectionCount("peegeeq-management-configured-runtime"));
    }

    @Test
    void registeredApplyRuntimeCanBeRetestedWithoutStallingReadiness() throws Exception {
        InetAddress pinnedAddress = InetAddress.getByName(postgres.getHost());
        SetupTargetPolicy policy = new SetupTargetPolicy(
                Set.of("internal.example"), Set.of("127.0.0.0/8"),
                Set.of(postgres.getMappedPort(5432)), true, false, false, false, Set.of("test-ca"));
        PostgresSetupRuntimeFactory factory = new PostgresSetupRuntimeFactory(
                policy,
                ignored -> List.of(pinnedAddress),
                ignored -> serverCertificate,
                Duration.ofSeconds(10));
        SetupRegistry registry = new SetupRegistry(factory, ignored -> null);
        SetupRuntimeConfiguration configuration = new SetupRuntimeConfiguration(
                3_600_000L,
                true,
                30_000L,
                500,
                false,
                500L,
                10_000,
                500,
                3,
                5_000L,
                "retest_cache",
                true,
                SchemaBootstrapMode.APPLY,
                SetupRuntimeConfiguration.TelemetryMode.NOOP);
        SetupDefinition definition = new SetupDefinition(
                "apply-retest",
                "Apply retest",
                new SetupTarget("db.internal.example", postgres.getMappedPort(5432),
                        "test-ca", TlsMode.VERIFY_FULL),
                "peegeeq",
                "peegee_cache",
                "peegeeq",
                3,
                configuration,
                SetupSource.UI_SESSION,
                null);

        try {
            await(registry.register(definition, SetupSecret.owned(
                    "test-password".getBytes(StandardCharsets.UTF_8))));
            SetupConnectionTest result = await(registry.testRegistered("apply-retest"));
            assertTrue(result.databaseReachable());
            assertEquals(SetupSchemaState.READY, result.schemaState());
            assertEquals("1", result.migrationVersion());
        } finally {
            await(registry.closeAsync());
        }
        assertEquals(0, applicationConnectionCount("peegeeq-management-apply-retest"));
    }

    @Test
    void registryOwnsRealTlsRuntimeAndRevalidatesPinnedTargetOnReconnect() throws Exception {
        InetAddress pinnedAddress = InetAddress.getByName(postgres.getHost());
        AtomicInteger resolutions = new AtomicInteger();
        SetupTargetPolicy policy = new SetupTargetPolicy(
                Set.of("internal.example"), Set.of("127.0.0.0/8"),
                Set.of(postgres.getMappedPort(5432)), true, false, false, false, Set.of("test-ca"));
        RecordingAuditSink administrationAudit = new RecordingAuditSink();
        ManagementActivityStore activity = new ManagementActivityStore(100);
        ManagementLiveEventHub liveEvents = new ManagementLiveEventHub(
                Clock.systemUTC(), Duration.ofMinutes(5), 10_000);
        ManagementAuditSink liveAudit = new LivePublishingManagementAuditSink(
                administrationAudit, activity, liveEvents);
        ManagementAuditFingerprinter auditFingerprinter = new ManagementAuditFingerprinter(
                new ManagementSecretReference("test-audit-key"),
                ignored -> new byte[32],
                "test-audit-key",
                128);
        PostgresSetupRuntimeFactory factory = new PostgresSetupRuntimeFactory(
                policy,
                ignored -> {
                    resolutions.incrementAndGet();
                    return List.of(pinnedAddress);
                },
                ignored -> serverCertificate,
                Duration.ofSeconds(10),
                liveAudit,
                auditFingerprinter,
                Clock.systemUTC(),
                () -> UUID.randomUUID().toString());
        SetupRegistry registry = new SetupRegistry(factory, ignored -> null);
        SetupDefinition definition = definition("orders");

        try {
            SetupSummary registered = await(registry.register(
                    definition, SetupSecret.owned(
                            "test-password".getBytes(java.nio.charset.StandardCharsets.UTF_8))));
            assertEquals(SetupState.CONNECTED, registered.state());
            assertEquals(1, resolutions.get());
            assertTrue(applicationConnectionCount("peegeeq-management-orders") > 0);
            SetupHealth health = await(registry.health("orders"));
            assertEquals(SetupHealthSummary.Status.UP, health.status());
            assertTrue(health.schemaReady());
            assertTrue(health.latencyMillis() >= 0);
            assertEquals("Database reachable and schema ready", health.detail());
            SetupCapabilities capabilities = registry.capabilities("orders");
            assertEquals("1", capabilities.migrationVersion());
            assertTrue(capabilities.features().namespaceInspection());
            assertEquals(7_500, capabilities.limits().pubSubPayloadMaxBytes());
            assertTrue(registry.management("orders").capabilities().supports(
                    ManagementCapability.NAMESPACE_INSPECTION));
            assertTrue(await(registry.management("orders").databaseStats()).schemaBytes() != null);
            seedInspectionData();
            int managementPort = freePort();
            String managementOrigin = "http://127.0.0.1:" + managementPort;
            Vertx managementVertx = Vertx.vertx();
            ManagementRuntimeMonitor runtimeMonitor = new ManagementRuntimeMonitor();
            ManagementPubSubSubscriptions managementSubscriptions =
                    new ManagementPubSubSubscriptions(
                            Clock.systemUTC(), 5, 100, 500,
                            1024 * 1024, 64L * 1024 * 1024, runtimeMonitor);
            RecordingPeriodicScheduler streamScheduler = new RecordingPeriodicScheduler();
            activity.publish(new ManagementActivityEvent(
                    "01K2VD6DA7G4EJ4CQG3ST6JN5M",
                    java.time.Instant.parse("2026-08-23T10:44:00Z"),
                    "operator",
                    "ENTRY_SET",
                    ManagementAuditTerminalOutcome.SUCCEEDED,
                    "orders",
                    "logical-orders",
                    new ManagementActivityResource(
                            ManagementActivityResourceType.CACHE_ENTRY, "customer:1"),
                    "Cache entry set",
                    "activity-correlation"));
            ManagementRequestAuthenticator requestAuthenticator = request -> {
                boolean operator = "operator".equals(request.getHeader("X-Test-Role"));
                String requestedActor = request.getHeader("X-Test-Actor");
                String actor = requestedActor == null
                        ? operator ? "operator" : "reader"
                        : requestedActor;
                return new AuthenticatedManagementRequest(
                        new AuthenticatedManagementIdentity(
                                actor,
                                operator ? Set.of("viewer", "operator") : Set.of("viewer"),
                                "127.0.0.1"),
                        "test-session",
                        "test-csrf");
            };
            SetupInspectionRoutes inspectionRoutes = new SetupInspectionRoutes(
                    registry,
                    requestAuthenticator,
                    runtimeMonitor,
                    () -> new ManagementAuditQueueState(0, 0, false),
                    activity);
            ManagementHttpServer managementServer = new ManagementHttpServer(
                    managementVertx,
                    TestManagementConfigurations.local(managementPort),
                    ManagementServerResources.noop(),
                    ManagementRequestRouter.firstOf(
                            new SetupAdministrationRoutes(
                                    registry,
                                    requestAuthenticator,
                                    new BrowserRequestSecurity(
                                            BrowserOriginPolicy.localToken(managementOrigin)),
                                    new ManagementRateLimiter(
                                            Arrays.stream(RateLimitAction.values()).collect(
                                                    java.util.stream.Collectors.toMap(
                                                            action -> action,
                                                            action -> new RateLimitRule(
                                                                    10, 20, Duration.ofMinutes(1)))),
                                            100,
                                            Clock.systemUTC(),
                                            RateLimitTelemetry.noop()),
                                    Clock.systemUTC()),
                            new PubSubRoutes(
                                    registry,
                                    requestAuthenticator,
                                    new BrowserRequestSecurity(
                                            BrowserOriginPolicy.localToken(managementOrigin)),
                                    liveAudit,
                                    auditFingerprinter,
                                    new ManagementRateLimiter(
                                            Arrays.stream(RateLimitAction.values()).collect(
                                                    java.util.stream.Collectors.toMap(
                                                            action -> action,
                                                            action -> new RateLimitRule(
                                                                    10, 20, Duration.ofMinutes(1)))),
                                            100,
                                            Clock.systemUTC(),
                                            RateLimitTelemetry.noop()),
                                    Clock.systemUTC(),
                                    managementSubscriptions,
                                    streamScheduler,
                                    runtimeMonitor),
                            new LiveMonitoringRoutes(
                                    registry,
                                    requestAuthenticator,
                                    new BrowserRequestSecurity(
                                            BrowserOriginPolicy.localToken(managementOrigin)),
                                    inspectionRoutes,
                                    liveEvents,
                                    streamScheduler,
                                    Clock.systemUTC(),
                                    runtimeMonitor),
                            inspectionRoutes));
            try {
                await(managementServer.start());
                HttpResponse<String> overview = get(
                        managementPort, "/api/v1/setups/orders/overview");
                assertEquals(200, overview.statusCode());
                var overviewBody = new com.fasterxml.jackson.databind.ObjectMapper()
                        .readTree(overview.body());
                assertEquals("DATABASE", overviewBody.path("scope").asText());
                assertEquals("UP", overviewBody.path("health").path("status").asText());
                assertEquals("1", overviewBody.path("totals").path("namespaceCount").asText());
                assertEquals("1", overviewBody.path("totals").path("liveEntryCount").asText());
                assertEquals("1", overviewBody.path("totals").path("liveCounterCount").asText());
                assertEquals("1", overviewBody.path("totals").path("activeLockCount").asText());
                assertEquals("AVAILABLE", overviewBody.path("totals")
                        .path("schemaBytes").path("availability").asText());
                assertTrue(Long.parseLong(overviewBody.path("totals")
                        .path("schemaBytes").path("value").asText()) > 0);
                assertEquals("1", overviewBody.path("valueTypeCounts").path("STRING").asText());
                assertEquals("logical-orders",
                        overviewBody.path("topNamespaces").get(0).path("namespace").asText());
                assertTrue(!overviewBody.path("expiry").path("sweeperEnabled").asBoolean());
                assertTrue(overviewBody.path("expiry").path("lastSweepAt").isNull());
                HttpResponse<String> databaseMonitoring = get(
                        managementPort, "/api/v1/setups/orders/monitoring/database");
                assertEquals(200, databaseMonitoring.statusCode());
                var monitoringBody = new com.fasterxml.jackson.databind.ObjectMapper()
                        .readTree(databaseMonitoring.body());
                assertEquals("DATABASE", monitoringBody.path("scope").asText());
                assertEquals("UP", monitoringBody.path("health").path("status").asText());
                assertEquals("AVAILABLE", monitoringBody.path("tableBytes")
                        .path("availability").asText());
                assertTrue(monitoringBody.path("tableBytes").path("reason").isNull());
                assertTrue(Long.parseLong(monitoringBody.path("tableBytes")
                        .path("value").asText()) > 0);
                assertEquals("3", monitoringBody.path("liveRows").path("value").asText());
                assertEquals("0", monitoringBody.path("expiredRows").path("value").asText());
                assertEquals("0", monitoringBody.path("expiryBacklog").asText());
                assertTrue(monitoringBody.path("oldestExpiredRowLagMillis").isNull());
                assertTrue(Long.parseLong(monitoringBody.path("databaseConnections")
                        .path("value").asText()) >= 1);
                assertTrue(Long.parseLong(monitoringBody.path("cacheConnections")
                        .path("value").asText()) >= 1);
                HttpResponse<String> runtimeMonitoring = get(
                        managementPort, "/api/v1/setups/orders/monitoring/runtime");
                assertEquals(200, runtimeMonitoring.statusCode());
                var runtimeBody = new com.fasterxml.jackson.databind.ObjectMapper()
                        .readTree(runtimeMonitoring.body());
                assertEquals("MANAGEMENT_RUNTIME", runtimeBody.path("scope").asText());
                assertEquals("RUNNING", runtimeBody.path("lifecycleState").asText());
                assertEquals("UNAVAILABLE", runtimeBody.path("pool").path("active")
                        .path("availability").asText());
                assertTrue(runtimeBody.path("pool").path("active").path("value").isNull());
                assertEquals("3", runtimeBody.path("pool").path("maximum")
                        .path("value").asText());
                assertEquals("1", runtimeBody.path("activeOperations").asText());
                assertEquals("0", runtimeBody.path("pubSubSubscriptions").asText());
                assertEquals("0", runtimeBody.path("sseClients").asText());
                assertEquals("0", runtimeBody.path("webSocketClients").asText());
                assertEquals("0", runtimeBody.path("retainedPayloadBytes").asText());
                assertEquals("0", runtimeBody.path("auditQueue").path("depth").asText());
                assertTrue(!runtimeBody.path("auditQueue")
                        .path("acceptingMutations").asBoolean());
                assertTrue(runtimeBody.path("expirySweeper")
                        .path("ownedByRuntime").asBoolean());
                assertTrue(!runtimeBody.path("expirySweeper").path("running").asBoolean());
                assertTrue(runtimeBody.path("expirySweeper").path("lastSweepAt").isNull());
                var runtimeOperation = java.util.stream.StreamSupport.stream(
                                runtimeBody.path("operations").spliterator(), false)
                        .filter(operation -> operation.path("operation").asText()
                                .equals("getRuntimeMonitoring"))
                        .findFirst()
                        .orElseThrow();
                assertEquals("ACTIVE", runtimeOperation.path("status").asText());
                assertEquals("1", runtimeOperation.path("count").asText());
                HttpResponse<String> namespaces = get(
                        managementPort, "/api/v1/setups/orders/namespaces");
                assertEquals(200, namespaces.statusCode());
                assertEquals(1, new com.fasterxml.jackson.databind.ObjectMapper()
                        .readTree(namespaces.body()).path("items").size());
                assertEquals(1, new com.fasterxml.jackson.databind.ObjectMapper()
                        .readTree(get(managementPort,
                                "/api/v1/setups/orders/namespaces?status=ACTIVE_LOCKS"
                                        + "&sort=entryCount:desc").body())
                        .path("items").size());
                HttpResponse<String> jsonExport = get(
                        managementPort, "/api/v1/setups/orders/namespaces/export");
                assertEquals(200, jsonExport.statusCode());
                var exportBody = new com.fasterxml.jackson.databind.ObjectMapper()
                        .readTree(jsonExport.body());
                assertEquals(1, exportBody.path("items").size());
                assertTrue(!exportBody.path("truncated").asBoolean());
                assertTrue(exportBody.path("exportedAt").asText().endsWith("Z"));
                HttpResponse<String> csvExport = get(
                        managementPort,
                        "/api/v1/setups/orders/namespaces/export",
                        "text/csv");
                assertEquals(200, csvExport.statusCode());
                assertEquals("text/csv; charset=utf-8",
                        csvExport.headers().firstValue("content-type").orElseThrow());
                assertTrue(csvExport.body().startsWith(
                        "namespace,liveEntryCount,liveCounterCount,activeLockCount,"));
                assertTrue(csvExport.body().contains("logical-orders,1,1,1,"));
                assertEquals(406, get(
                        managementPort,
                        "/api/v1/setups/orders/namespaces/export",
                        "application/xml").statusCode());
                String encoded = ManagementIdentifierCodec.encodeNamespace("logical-orders");
                HttpResponse<String> namespace = get(
                        managementPort, "/api/v1/setups/orders/namespaces/" + encoded);
                assertEquals(200, namespace.statusCode());
                var namespaceBody = new com.fasterxml.jackson.databind.ObjectMapper().readTree(namespace.body());
                assertEquals("logical-orders", namespaceBody.path("stats").path("namespace").asText());
                assertEquals("1", namespaceBody.path("stats").path("liveEntryCount").asText());
                String encodedKey = ManagementIdentifierCodec.encodeKey("customer:1");
                HttpResponse<String> entries = get(managementPort,
                        "/api/v1/setups/orders/namespaces/" + encoded + "/entries");
                assertEquals(200, entries.statusCode());
                var entriesBody = new com.fasterxml.jackson.databind.ObjectMapper().readTree(entries.body());
                assertEquals("customer:1", entriesBody.path("items").get(0).path("key").asText());
                assertTrue(entriesBody.path("items").get(0).has("lastAccessedAt"));
                assertTrue(entriesBody.path("items").get(0).path("lastAccessedAt").isNull());
                assertTrue(!entries.body().contains("stored-value"));
                HttpResponse<String> entry = get(managementPort,
                        "/api/v1/setups/orders/namespaces/" + encoded + "/entries/" + encodedKey);
                assertEquals(200, entry.statusCode());
                assertEquals("\"v3\"", entry.headers().firstValue("etag").orElseThrow());
                var entryBody = new com.fasterxml.jackson.databind.ObjectMapper().readTree(entry.body());
                assertTrue(entryBody.has("lastAccessedAt"));
                assertTrue(entryBody.path("lastAccessedAt").isNull());
                assertTrue(!entry.body().contains("stored-value"));
                String revealPath = "/api/v1/setups/orders/namespaces/" + encoded
                        + "/entries/" + encodedKey + "/value/reveal";
                assertEquals(403, post(
                        managementPort, revealPath, null, managementOrigin,
                        "test-session", "test-csrf", "application/json", "{}").statusCode());
                assertEquals(403, post(
                        managementPort, revealPath, "operator", managementOrigin,
                        "test-session", null, "application/json", "{}").statusCode());
                assertTrue(administrationAudit.intents.isEmpty());
                administrationAudit.rejectReservations = true;
                HttpResponse<String> auditUnavailable = post(
                        managementPort, revealPath, "operator", managementOrigin,
                        "test-session", "test-csrf", "application/json", "{}");
                assertEquals(503, auditUnavailable.statusCode());
                assertEquals("AUDIT_UNAVAILABLE",
                        new com.fasterxml.jackson.databind.ObjectMapper()
                                .readTree(auditUnavailable.body()).path("code").asText());
                assertTrue(administrationAudit.intents.isEmpty());
                administrationAudit.rejectReservations = false;
                HttpResponse<String> revealed = post(
                        managementPort, revealPath, "operator", managementOrigin,
                        "test-session", "test-csrf", "application/json",
                        "{\"reason\":\"Investigating cache refresh\"}");
                assertEquals(200, revealed.statusCode());
                assertEquals("no-store, no-cache, must-revalidate",
                        revealed.headers().firstValue("cache-control").orElseThrow());
                assertEquals("no-cache",
                        revealed.headers().firstValue("pragma").orElseThrow());
                var revealedBody = new com.fasterxml.jackson.databind.ObjectMapper()
                        .readTree(revealed.body());
                assertEquals("customer:1", revealedBody.path("key").asText());
                assertEquals("3", revealedBody.path("version").asText());
                assertEquals("STRING", revealedBody.path("value").path("type").asText());
                assertEquals("stored-value", revealedBody.path("value").path("text").asText());
                assertTrue(revealedBody.path("revealedAt").asText().endsWith("Z"));
                assertEquals(60_000, revealedBody.path("autoHideAfterMillis").asInt());
                assertEquals(1, administrationAudit.intents.size());
                assertEquals(ManagementAuditAction.REVEAL_ENTRY,
                        administrationAudit.intents.getFirst().action());
                assertEquals("Investigating cache refresh",
                        administrationAudit.intents.getFirst().reason());
                assertEquals(2,
                        administrationAudit.intents.getFirst().identifierFingerprints().size());
                assertTrue(!administrationAudit.intents.getFirst()
                        .identifierFingerprints().toString().contains("customer:1"));
                assertEquals(ManagementAuditTerminalOutcome.SUCCEEDED,
                        administrationAudit.outcomes.getFirst().outcome());
                assertEquals("ENTRY_VALUE_REVEALED",
                        administrationAudit.outcomes.getFirst().code());
                HttpResponse<String> missingReveal = post(
                        managementPort,
                        "/api/v1/setups/orders/namespaces/" + encoded + "/entries/"
                                + ManagementIdentifierCodec.encodeKey("missing") + "/value/reveal",
                        "operator", managementOrigin, "test-session", "test-csrf",
                        "application/json", "{}");
                assertEquals(404, missingReveal.statusCode());
                assertEquals("ENTRY_NOT_FOUND",
                        new com.fasterxml.jackson.databind.ObjectMapper()
                                .readTree(missingReveal.body()).path("code").asText());
                String setBody = """
                        {"value":{"type":"STRING","text":"replacement-value"},
                         "ttlMode":"PRESERVE_EXISTING","ttlMillis":null,
                         "setMode":"ONLY_IF_VERSION_MATCHES"}
                        """;
                HttpResponse<String> missingSetPrecondition = put(
                        managementPort,
                        "/api/v1/setups/orders/namespaces/" + encoded + "/entries/" + encodedKey,
                        "operator", managementOrigin, "test-session", "test-csrf",
                        null, null, setBody);
                assertEquals(428, missingSetPrecondition.statusCode());
                assertEquals("PRECONDITION_REQUIRED",
                        new com.fasterxml.jackson.databind.ObjectMapper()
                                .readTree(missingSetPrecondition.body()).path("code").asText());
                HttpResponse<String> staleSet = put(
                        managementPort,
                        "/api/v1/setups/orders/namespaces/" + encoded + "/entries/" + encodedKey,
                        "operator", managementOrigin, "test-session", "test-csrf",
                        "\"v99\"", null, setBody);
                assertEquals(412, staleSet.statusCode());
                assertEquals("VERSION_MISMATCH",
                        new com.fasterxml.jackson.databind.ObjectMapper()
                                .readTree(staleSet.body()).path("code").asText());
                HttpResponse<String> set = put(
                        managementPort,
                        "/api/v1/setups/orders/namespaces/" + encoded + "/entries/" + encodedKey,
                        "operator", managementOrigin, "test-session", "test-csrf",
                        "\"v3\"", null, setBody);
                assertEquals(200, set.statusCode());
                assertEquals("\"v4\"", set.headers().firstValue("etag").orElseThrow());
                var setResponse = new com.fasterxml.jackson.databind.ObjectMapper()
                        .readTree(set.body());
                assertTrue(setResponse.path("applied").asBoolean());
                assertTrue(!setResponse.path("created").asBoolean());
                assertEquals("4", setResponse.path("version").asText());
                assertTrue(!set.body().contains("replacement-value"));
                assertTrue(!set.body().contains("stored-value"));
                assertEquals(ManagementAuditAction.SET_ENTRY,
                        administrationAudit.intents.getLast().action());
                assertEquals("ENTRY_SET", administrationAudit.outcomes.getLast().code());
                assertEquals(4L, administrationAudit.outcomes.getLast().resultingVersion());
                String expirePath = "/api/v1/setups/orders/namespaces/" + encoded
                        + "/entries/" + encodedKey + "/ttl";
                assertEquals(428, postConditional(
                        managementPort, expirePath, "operator", managementOrigin,
                        "test-session", "test-csrf", null,
                        "application/json", "{\"ttlMillis\":1800000}").statusCode());
                HttpResponse<String> staleExpire = postConditional(
                        managementPort, expirePath, "operator", managementOrigin,
                        "test-session", "test-csrf", "\"v99\"",
                        "application/json", "{\"ttlMillis\":1800000}");
                assertEquals(412, staleExpire.statusCode());
                assertEquals("VERSION_MISMATCH",
                        new com.fasterxml.jackson.databind.ObjectMapper()
                                .readTree(staleExpire.body()).path("code").asText());
                HttpResponse<String> expired = postConditional(
                        managementPort, expirePath, "operator", managementOrigin,
                        "test-session", "test-csrf", "\"v4\"",
                        "application/json", "{\"ttlMillis\":1800000}");
                assertEquals(200, expired.statusCode());
                assertEquals("\"v5\"", expired.headers().firstValue("etag").orElseThrow());
                var expiredBody = new com.fasterxml.jackson.databind.ObjectMapper()
                        .readTree(expired.body());
                assertEquals("5", expiredBody.path("version").asText());
                assertEquals("EXPIRING", expiredBody.path("ttl").path("state").asText());
                assertTrue(expiredBody.path("ttl").path("ttlMillis").asLong() > 0);
                assertEquals(ManagementAuditAction.EXPIRE_ENTRY,
                        administrationAudit.intents.getLast().action());
                assertEquals("ENTRY_TTL_SET", administrationAudit.outcomes.getLast().code());
                String persistPath = "/api/v1/setups/orders/namespaces/" + encoded
                        + "/entries/" + encodedKey + "/persist";
                assertEquals(428, postConditional(
                        managementPort, persistPath, "operator", managementOrigin,
                        "test-session", "test-csrf", null, null, null).statusCode());
                HttpResponse<String> persisted = postConditional(
                        managementPort, persistPath, "operator", managementOrigin,
                        "test-session", "test-csrf", "\"v5\"", null, null);
                assertEquals(200, persisted.statusCode());
                assertEquals("\"v6\"", persisted.headers().firstValue("etag").orElseThrow());
                var persistedBody = new com.fasterxml.jackson.databind.ObjectMapper()
                        .readTree(persisted.body());
                assertEquals("6", persistedBody.path("version").asText());
                assertEquals("PERSISTENT", persistedBody.path("ttl").path("state").asText());
                assertTrue(persistedBody.path("ttl").path("ttlMillis").isNull());
                assertEquals(ManagementAuditAction.PERSIST_ENTRY,
                        administrationAudit.intents.getLast().action());
                assertEquals("ENTRY_PERSISTED", administrationAudit.outcomes.getLast().code());
                String touchPath = "/api/v1/setups/orders/namespaces/" + encoded
                        + "/entries/" + encodedKey + "/touch";
                assertEquals(428, postConditional(
                        managementPort, touchPath, "operator", managementOrigin,
                        "test-session", "test-csrf", null, "application/json",
                        "{\"refreshTtlMillis\":null}").statusCode());
                HttpResponse<String> touched = postConditional(
                        managementPort, touchPath, "operator", managementOrigin,
                        "test-session", "test-csrf", "\"v6\"", "application/json",
                        "{\"refreshTtlMillis\":null}");
                assertEquals(200, touched.statusCode());
                assertEquals("\"v6\"", touched.headers().firstValue("etag").orElseThrow());
                var touchedBody = new com.fasterxml.jackson.databind.ObjectMapper()
                        .readTree(touched.body());
                assertEquals("6", touchedBody.path("version").asText());
                assertEquals("PERSISTENT", touchedBody.path("ttl").path("state").asText());
                assertEquals(ManagementAuditAction.TOUCH_ENTRY,
                        administrationAudit.intents.getLast().action());
                assertEquals("ENTRY_TOUCHED", administrationAudit.outcomes.getLast().code());
                assertEquals(6L, administrationAudit.outcomes.getLast().resultingVersion());
                HttpResponse<String> missingEntry = get(managementPort,
                        "/api/v1/setups/orders/namespaces/" + encoded + "/entries/"
                                + ManagementIdentifierCodec.encodeKey("missing"));
                assertEquals(404, missingEntry.statusCode());
                assertEquals("ENTRY_NOT_FOUND", new com.fasterxml.jackson.databind.ObjectMapper()
                        .readTree(missingEntry.body()).path("code").asText());
                String deletePath = "/api/v1/setups/orders/namespaces/" + encoded
                        + "/entries/" + encodedKey;
                assertEquals(428, delete(
                        managementPort, deletePath, "operator", managementOrigin,
                        "test-session", "test-csrf", null).statusCode());
                HttpResponse<String> staleDelete = delete(
                        managementPort, deletePath, "operator", managementOrigin,
                        "test-session", "test-csrf", "\"v99\"");
                assertEquals(412, staleDelete.statusCode());
                HttpResponse<String> deleted = delete(
                        managementPort, deletePath, "operator", managementOrigin,
                        "test-session", "test-csrf", "\"v6\"");
                assertEquals(204, deleted.statusCode());
                assertTrue(deleted.body().isEmpty());
                assertEquals(ManagementAuditAction.DELETE_ENTRY,
                        administrationAudit.intents.getLast().action());
                assertEquals("ENTRY_DELETED", administrationAudit.outcomes.getLast().code());
                assertEquals(404, get(managementPort, deletePath).statusCode());

                seedBulkEntries();
                String entryBulkPreviewPath = "/api/v1/setups/orders/namespaces/" + encoded
                        + "/entries/bulk-delete/preview";
                String entryBulkExecutePath = "/api/v1/setups/orders/namespaces/" + encoded
                        + "/entries/bulk-delete/execute";
                String entryBulkSelection = """
                        {"selection":{"type":"EXPLICIT","targets":[
                          {"key":"zz-bulk-first","version":"11"},
                          {"key":"zz-bulk-second","version":"12"}]}}
                        """;
                assertEquals(403, post(
                        managementPort, entryBulkPreviewPath, null, managementOrigin,
                        "test-session", "test-csrf", "application/json",
                        entryBulkSelection).statusCode());
                HttpResponse<String> entryBulkPreview = post(
                        managementPort, entryBulkPreviewPath, "operator", managementOrigin,
                        "test-session", "test-csrf", "application/json",
                        entryBulkSelection);
                assertEquals(200, entryBulkPreview.statusCode());
                var entryBulkPreviewBody = new com.fasterxml.jackson.databind.ObjectMapper()
                        .readTree(entryBulkPreview.body());
                assertEquals("2", entryBulkPreviewBody.path("resolvedCount").asText());
                assertEquals("DELETE logical-orders",
                        entryBulkPreviewBody.path("confirmationPhrase").asText());
                assertTrue(entryBulkPreviewBody.path("expiresAt").asText().endsWith("Z"));
                assertEquals(ManagementAuditAction.PREVIEW_ENTRY_DELETE,
                        administrationAudit.intents.getLast().action());
                changeBulkEntryVersion();
                String entryBulkExecution = new com.fasterxml.jackson.databind.ObjectMapper()
                        .createObjectNode()
                        .put("previewToken", entryBulkPreviewBody.path("previewToken").asText())
                        .put("confirmationPhrase",
                                entryBulkPreviewBody.path("confirmationPhrase").asText())
                        .toString();
                String otherNamespaceExecutePath = "/api/v1/setups/orders/namespaces/"
                        + ManagementIdentifierCodec.encodeNamespace("other-namespace")
                        + "/entries/bulk-delete/execute";
                HttpResponse<String> wrongNamespaceResult = post(
                        managementPort, otherNamespaceExecutePath, "operator", managementOrigin,
                        "test-session", "test-csrf", "application/json",
                        entryBulkExecution);
                assertEquals(403, wrongNamespaceResult.statusCode());
                assertEquals("PREVIEW_SCOPE_MISMATCH",
                        new com.fasterxml.jackson.databind.ObjectMapper()
                                .readTree(wrongNamespaceResult.body()).path("code").asText());
                HttpResponse<String> entryBulkResult = post(
                        managementPort, entryBulkExecutePath, "operator", managementOrigin,
                        "test-session", "test-csrf", "application/json",
                        entryBulkExecution);
                assertEquals(200, entryBulkResult.statusCode());
                var entryBulkResultBody = new com.fasterxml.jackson.databind.ObjectMapper()
                        .readTree(entryBulkResult.body());
                assertEquals("2", entryBulkResultBody.path("processedCount").asText());
                assertEquals("1", entryBulkResultBody.path("deletedCount").asText());
                assertEquals("1", entryBulkResultBody.path("conflictCount").asText());
                assertEquals("VERSION_CHANGED", entryBulkResultBody.path("conflicts")
                        .get(0).path("reason").asText());
                assertEquals(ManagementAuditAction.EXECUTE_ENTRY_DELETE,
                        administrationAudit.intents.getLast().action());
                assertEquals(409, post(
                        managementPort, entryBulkExecutePath, "operator", managementOrigin,
                        "test-session", "test-csrf", "application/json",
                        entryBulkExecution).statusCode());

                HttpResponse<String> activityResponse = get(managementPort,
                        "/api/v1/setups/orders/activity?namespace=logical-orders"
                                + "&action=ENTRY_SET&outcome=SUCCEEDED&limit=1");
                assertEquals(200, activityResponse.statusCode());
                var activityBody = new com.fasterxml.jackson.databind.ObjectMapper()
                        .readTree(activityResponse.body());
                assertEquals("01K2VD6DA7G4EJ4CQG3ST6JN5M",
                        activityBody.path("items").get(0).path("eventId").asText());
                assertEquals("customer:1", activityBody.path("items").get(0)
                        .path("resource").path("identifier").asText());
                assertTrue(!activityResponse.body().contains("test-password"));
                assertEquals(400, get(managementPort,
                        "/api/v1/setups/orders/activity?after=unknown").statusCode());

                HttpResponse<String> counters = get(
                        managementPort, "/api/v1/setups/orders/counters?namespace=logical-orders");
                assertEquals(200, counters.statusCode());
                assertEquals("42", new com.fasterxml.jackson.databind.ObjectMapper()
                        .readTree(counters.body()).path("items").get(0).path("value").asText());
                HttpResponse<String> counter = get(managementPort,
                        "/api/v1/setups/orders/namespaces/" + encoded + "/counters/"
                                + ManagementIdentifierCodec.encodeKey("count"));
                assertEquals(200, counter.statusCode());
                assertEquals("\"v4\"", counter.headers().firstValue("etag").orElseThrow());
                String counterPath = "/api/v1/setups/orders/namespaces/" + encoded
                        + "/counters/" + ManagementIdentifierCodec.encodeKey("count");
                String counterSetBody =
                        "{\"value\":\"50\",\"ttlMode\":\"PRESERVE_EXISTING\",\"ttlMillis\":null}";
                assertEquals(428, put(
                        managementPort, counterPath, "operator", managementOrigin,
                        "test-session", "test-csrf", null, null, counterSetBody).statusCode());
                HttpResponse<String> staleCounterSet = put(
                        managementPort, counterPath, "operator", managementOrigin,
                        "test-session", "test-csrf", "\"v99\"", null, counterSetBody);
                assertEquals(412, staleCounterSet.statusCode());
                HttpResponse<String> counterSet = put(
                        managementPort, counterPath, "operator", managementOrigin,
                        "test-session", "test-csrf", "\"v4\"", null, counterSetBody);
                assertEquals(200, counterSet.statusCode());
                assertEquals("\"v5\"", counterSet.headers().firstValue("etag").orElseThrow());
                var counterSetResponse = new com.fasterxml.jackson.databind.ObjectMapper()
                        .readTree(counterSet.body());
                assertEquals("50", counterSetResponse.path("value").asText());
                assertEquals("5", counterSetResponse.path("version").asText());
                assertEquals(ManagementAuditAction.SET_COUNTER,
                        administrationAudit.intents.getLast().action());
                assertEquals("COUNTER_SET", administrationAudit.outcomes.getLast().code());

                String counterIncrementPath = counterPath + "/increment";
                String counterAdjustBody =
                        "{\"delta\":\"10\",\"createIfMissing\":false,"
                                + "\"ttlMode\":\"PRESERVE_EXISTING\",\"ttlMillis\":null}";
                assertEquals(428, postConditional(
                        managementPort, counterIncrementPath, "operator", managementOrigin,
                        "test-session", "test-csrf", null, "application/json",
                        counterAdjustBody).statusCode());
                assertEquals(400, postConditional(
                        managementPort, counterIncrementPath, "operator", managementOrigin,
                        "test-session", "test-csrf", "\"v5\"", "application/json",
                        "{\"delta\":\"0\",\"createIfMissing\":false,"
                                + "\"ttlMode\":\"PRESERVE_EXISTING\",\"ttlMillis\":null}")
                        .statusCode());
                HttpResponse<String> counterAdjusted = postConditional(
                        managementPort, counterIncrementPath, "operator", managementOrigin,
                        "test-session", "test-csrf", "\"v5\"", "application/json",
                        counterAdjustBody);
                assertEquals(200, counterAdjusted.statusCode());
                assertEquals("\"v6\"",
                        counterAdjusted.headers().firstValue("etag").orElseThrow());
                var counterAdjustedResponse = new com.fasterxml.jackson.databind.ObjectMapper()
                        .readTree(counterAdjusted.body());
                assertEquals("60", counterAdjustedResponse.path("value").asText());
                assertEquals("6", counterAdjustedResponse.path("version").asText());
                assertEquals(ManagementAuditAction.ADJUST_COUNTER,
                        administrationAudit.intents.getLast().action());
                assertEquals("COUNTER_ADJUSTED", administrationAudit.outcomes.getLast().code());

                String counterTtlPath = counterPath + "/ttl";
                assertEquals(428, postConditional(
                        managementPort, counterTtlPath, "operator", managementOrigin,
                        "test-session", "test-csrf", null, "application/json",
                        "{\"ttlMillis\":60000}").statusCode());
                HttpResponse<String> counterExpired = postConditional(
                        managementPort, counterTtlPath, "operator", managementOrigin,
                        "test-session", "test-csrf", "\"v6\"", "application/json",
                        "{\"ttlMillis\":60000}");
                assertEquals(200, counterExpired.statusCode());
                assertEquals("\"v7\"",
                        counterExpired.headers().firstValue("etag").orElseThrow());
                var counterExpiredResponse = new com.fasterxml.jackson.databind.ObjectMapper()
                        .readTree(counterExpired.body());
                assertEquals("7", counterExpiredResponse.path("version").asText());
                assertEquals("EXPIRING",
                        counterExpiredResponse.path("ttl").path("state").asText());
                assertTrue(counterExpiredResponse.path("ttl").path("ttlMillis").asLong() > 0);
                assertEquals(ManagementAuditAction.EXPIRE_COUNTER,
                        administrationAudit.intents.getLast().action());
                assertEquals("COUNTER_TTL_SET", administrationAudit.outcomes.getLast().code());

                String counterPersistPath = counterPath + "/persist";
                assertEquals(428, postConditional(
                        managementPort, counterPersistPath, "operator", managementOrigin,
                        "test-session", "test-csrf", null, null, null).statusCode());
                HttpResponse<String> counterPersisted = postConditional(
                        managementPort, counterPersistPath, "operator", managementOrigin,
                        "test-session", "test-csrf", "\"v7\"", null, null);
                assertEquals(200, counterPersisted.statusCode());
                assertEquals("\"v8\"",
                        counterPersisted.headers().firstValue("etag").orElseThrow());
                var counterPersistedResponse = new com.fasterxml.jackson.databind.ObjectMapper()
                        .readTree(counterPersisted.body());
                assertEquals("8", counterPersistedResponse.path("version").asText());
                assertEquals("PERSISTENT",
                        counterPersistedResponse.path("ttl").path("state").asText());
                assertTrue(counterPersistedResponse.path("ttl").path("ttlMillis").isNull());
                assertEquals(ManagementAuditAction.PERSIST_COUNTER,
                        administrationAudit.intents.getLast().action());
                assertEquals("COUNTER_PERSISTED", administrationAudit.outcomes.getLast().code());

                String maximumCounterBody =
                        "{\"value\":\"9223372036854775807\","
                                + "\"ttlMode\":\"PRESERVE_EXISTING\",\"ttlMillis\":null}";
                HttpResponse<String> maximumCounter = put(
                        managementPort, counterPath, "operator", managementOrigin,
                        "test-session", "test-csrf", "\"v8\"", null, maximumCounterBody);
                assertEquals(200, maximumCounter.statusCode());
                assertEquals("\"v9\"", maximumCounter.headers().firstValue("etag").orElseThrow());
                HttpResponse<String> overflow = postConditional(
                        managementPort, counterIncrementPath, "operator", managementOrigin,
                        "test-session", "test-csrf", "\"v9\"", "application/json",
                        "{\"delta\":\"1\",\"createIfMissing\":false,"
                                + "\"ttlMode\":\"PRESERVE_EXISTING\",\"ttlMillis\":null}");
                assertEquals(409, overflow.statusCode());
                assertEquals("COUNTER_OVERFLOW", new com.fasterxml.jackson.databind.ObjectMapper()
                        .readTree(overflow.body()).path("code").asText());
                HttpResponse<String> unchangedMaximum = get(managementPort, counterPath);
                assertEquals("\"v9\"", unchangedMaximum.headers().firstValue("etag").orElseThrow());
                assertEquals("9223372036854775807",
                        new com.fasterxml.jackson.databind.ObjectMapper().readTree(unchangedMaximum.body())
                                .path("value").asText());

                assertEquals(428, delete(
                        managementPort, counterPath, "operator", managementOrigin,
                        "test-session", "test-csrf", null).statusCode());
                HttpResponse<String> staleCounterDelete = delete(
                        managementPort, counterPath, "operator", managementOrigin,
                        "test-session", "test-csrf", "\"v99\"");
                assertEquals(412, staleCounterDelete.statusCode());
                HttpResponse<String> counterDeleted = delete(
                        managementPort, counterPath, "operator", managementOrigin,
                        "test-session", "test-csrf", "\"v9\"");
                assertEquals(204, counterDeleted.statusCode());
                assertTrue(counterDeleted.body().isEmpty());
                assertEquals(ManagementAuditAction.DELETE_COUNTER,
                        administrationAudit.intents.getLast().action());
                assertEquals("COUNTER_DELETED", administrationAudit.outcomes.getLast().code());
                assertEquals(404, get(managementPort, counterPath).statusCode());

                seedBulkCounters();
                String counterBulkPreviewPath =
                        "/api/v1/setups/orders/counters/bulk-delete/preview";
                String counterBulkExecutePath =
                        "/api/v1/setups/orders/counters/bulk-delete/execute";
                String counterBulkSelection = """
                        {"targets":[
                          {"namespace":"logical-orders","key":"bulk-counter-first","version":"21"},
                          {"namespace":"logical-orders","key":"bulk-counter-second","version":"22"}]}
                        """;
                HttpResponse<String> counterBulkPreview = post(
                        managementPort, counterBulkPreviewPath, "operator", managementOrigin,
                        "test-session", "test-csrf", "application/json",
                        counterBulkSelection);
                assertEquals(200, counterBulkPreview.statusCode());
                var counterBulkPreviewBody = new com.fasterxml.jackson.databind.ObjectMapper()
                        .readTree(counterBulkPreview.body());
                assertEquals("2", counterBulkPreviewBody.path("resolvedCount").asText());
                assertEquals("16", counterBulkPreviewBody.path("totalBytes").asText());
                assertEquals("DELETE 2 COUNTERS",
                        counterBulkPreviewBody.path("confirmationPhrase").asText());
                assertTrue(counterBulkPreviewBody.path("namespace").isNull());
                assertEquals(ManagementAuditAction.PREVIEW_COUNTER_DELETE,
                        administrationAudit.intents.getLast().action());
                changeBulkCounterVersion();
                String counterBulkExecution = new com.fasterxml.jackson.databind.ObjectMapper()
                        .createObjectNode()
                        .put("previewToken", counterBulkPreviewBody.path("previewToken").asText())
                        .put("confirmationPhrase",
                                counterBulkPreviewBody.path("confirmationPhrase").asText())
                        .toString();
                HttpResponse<String> counterBulkResult = post(
                        managementPort, counterBulkExecutePath, "operator", managementOrigin,
                        "test-session", "test-csrf", "application/json",
                        counterBulkExecution);
                assertEquals(200, counterBulkResult.statusCode());
                var counterBulkResultBody = new com.fasterxml.jackson.databind.ObjectMapper()
                        .readTree(counterBulkResult.body());
                assertEquals("1", counterBulkResultBody.path("deletedCount").asText());
                assertEquals("1", counterBulkResultBody.path("conflictCount").asText());
                assertEquals(ManagementAuditAction.EXECUTE_COUNTER_DELETE,
                        administrationAudit.intents.getLast().action());

                String publishPath = "/api/v1/setups/orders/pubsub/publish";
                String subscriptionsPath = "/api/v1/setups/orders/pubsub/subscriptions";
                HttpResponse<String> createdSubscription = post(
                        managementPort, subscriptionsPath, null, managementOrigin,
                        "test-session", "test-csrf", "application/json",
                        "{\"channel\":\"management-events\",\"bufferLimit\":2}");
                assertEquals(201, createdSubscription.statusCode());
                var subscriptionBody = new com.fasterxml.jackson.databind.ObjectMapper()
                        .readTree(createdSubscription.body());
                String managementSubscriptionId = subscriptionBody.path("subscriptionId").asText();
                assertEquals("management-events", subscriptionBody.path("channel").asText());
                assertEquals(2, subscriptionBody.path("bufferLimit").asInt());
                assertTrue(subscriptionBody.path("streamPath").asText().endsWith(
                        "/" + managementSubscriptionId + "/stream"));
                String managementSubscriptionPath = subscriptionsPath + "/" + managementSubscriptionId;
                assertEquals(404, delete(
                        managementPort, managementSubscriptionPath, "operator", managementOrigin,
                        "test-session", "test-csrf", null).statusCode());
                String publishBody = "{\"channel\":\"management-events\","
                        + "\"payload\":\"published-value\",\"contentType\":\"text/plain\"}";
                assertEquals(403, post(
                        managementPort, publishPath, null, managementOrigin,
                        "test-session", "test-csrf", "application/json",
                        publishBody).statusCode());
                assertEquals(403, post(
                        managementPort, publishPath, "operator", managementOrigin,
                        "test-session", null, "application/json", publishBody).statusCode());
                assertEquals(400, post(
                        managementPort, publishPath, "operator", managementOrigin,
                        "test-session", "test-csrf", "application/json",
                        "{\"channel\":\"bad\\u0000name\",\"payload\":\"x\"}")
                        .statusCode());
                assertEquals(400, post(
                        managementPort, publishPath, "operator", managementOrigin,
                        "test-session", "test-csrf", "application/json",
                        "{\"channel\":\"" + "界".repeat(17) + "\",\"payload\":\"x\"}")
                        .statusCode());
                assertEquals(400, post(
                        managementPort, publishPath, "operator", managementOrigin,
                        "test-session", "test-csrf", "application/json",
                        "{\"channel\":\"management-events\",\"payload\":\""
                                + "界".repeat(2501) + "\"}").statusCode());
                CompletableFuture<PubSubMessage> receivedPublication = new CompletableFuture<>();
                AtomicInteger publicationCount = new AtomicInteger();
                Subscription publicationSubscription = await(registry.pubSub("orders").subscribe(
                        "management-events", message -> {
                            publicationCount.incrementAndGet();
                            receivedPublication.complete(message);
                        }));
                administrationAudit.rejectReservations = true;
                assertEquals(503, post(
                        managementPort, publishPath, "operator", managementOrigin,
                        "test-session", "test-csrf", "application/json",
                        "{\"channel\":\"management-events\",\"payload\":\"not-published\"}")
                        .statusCode());
                administrationAudit.rejectReservations = false;
                HttpResponse<String> published = post(
                        managementPort, publishPath, "operator", managementOrigin,
                        "test-session", "test-csrf", "application/json", publishBody);
                assertEquals(200, published.statusCode());
                var publishedBody = new com.fasterxml.jackson.databind.ObjectMapper()
                        .readTree(published.body());
                assertTrue(publishedBody.path("accepted").asBoolean());
                assertTrue(publishedBody.path("publishedAt").asText().endsWith("Z"));
                assertTrue(!publishedBody.has("listenerCount"));
                assertEquals(ManagementAuditAction.PUBLISH_PUBSUB,
                        administrationAudit.intents.getLast().action());
                assertEquals("PUBSUB_PUBLISHED", administrationAudit.outcomes.getLast().code());
                PubSubMessage received = receivedPublication.get(5, TimeUnit.SECONDS);
                assertEquals("management-events", received.channel());
                assertEquals("published-value", received.payload());
                assertEquals("text/plain", received.contentType());
                assertEquals(1, publicationCount.get());
                await(publicationSubscription.unsubscribe());
                var retained = managementSubscriptions.snapshot(
                        managementSubscriptionId, "reader", "orders");
                assertEquals("published-value", retained.messages().getLast().payload());

                HttpResponse<String> operatorSubscription = post(
                        managementPort, subscriptionsPath, "operator", managementOrigin,
                        "test-session", "test-csrf", "application/json",
                        "{\"channel\":\"management-events\",\"bufferLimit\":1}");
                assertEquals(201, operatorSubscription.statusCode());
                String operatorSubscriptionId = new com.fasterxml.jackson.databind.ObjectMapper()
                        .readTree(operatorSubscription.body()).path("subscriptionId").asText();
                assertEquals(200, post(
                        managementPort, publishPath, "operator", managementOrigin,
                        "test-session", "test-csrf", "application/json",
                        "{\"channel\":\"management-events\",\"payload\":\"reveal-secret\"}")
                        .statusCode());
                var operatorMessage = awaitRetained(
                        managementSubscriptions, operatorSubscriptionId, "operator", "orders",
                        "reveal-secret");
                String pubSubRevealPath = subscriptionsPath + "/" + operatorSubscriptionId
                        + "/messages/" + operatorMessage.messageId() + "/payload/reveal";
                assertEquals(403, post(
                        managementPort, pubSubRevealPath, null, managementOrigin,
                        "test-session", "test-csrf", "application/json", "{}").statusCode());
                assertEquals(404, postAs(
                        managementPort, pubSubRevealPath, "operator", "another-operator", managementOrigin,
                        "test-session", "test-csrf", "application/json", "{}").statusCode());
                assertEquals(403, post(
                        managementPort, pubSubRevealPath, "operator", managementOrigin,
                        "test-session", null, "application/json", "{}").statusCode());
                administrationAudit.rejectReservations = true;
                assertEquals(503, post(
                        managementPort, pubSubRevealPath, "operator", managementOrigin,
                        "test-session", "test-csrf", "application/json", "{}").statusCode());
                administrationAudit.rejectReservations = false;
                HttpResponse<String> reveal = post(
                        managementPort, pubSubRevealPath, "operator", managementOrigin,
                        "test-session", "test-csrf", "application/json",
                        "{\"reason\":\"Investigating an invalidation incident\"}");
                assertEquals(200, reveal.statusCode());
                assertEquals("no-store", reveal.headers().firstValue("cache-control").orElseThrow());
                assertEquals("no-cache", reveal.headers().firstValue("pragma").orElseThrow());
                var revealBody = new com.fasterxml.jackson.databind.ObjectMapper()
                        .readTree(reveal.body());
                assertEquals(operatorMessage.messageId(), revealBody.path("messageId").asText());
                assertEquals("management-events", revealBody.path("channel").asText());
                assertEquals("reveal-secret", revealBody.path("payload").asText());
                assertTrue(revealBody.path("contentType").isNull());
                assertEquals("UTF8", revealBody.path("encoding").asText());
                assertEquals(operatorMessage.receivedAt().toString(),
                        revealBody.path("receivedAt").asText());
                assertTrue(revealBody.path("revealedAt").asText().endsWith("Z"));
                assertEquals(60_000, revealBody.path("autoHideAfterMillis").asInt());
                assertEquals(ManagementAuditAction.REVEAL_PUBSUB_PAYLOAD,
                        administrationAudit.intents.getLast().action());
                assertTrue(!administrationAudit.intents.getLast()
                        .identifierFingerprints().toString().contains("reveal-secret"));
                assertEquals("PUBSUB_PAYLOAD_REVEALED",
                        administrationAudit.outcomes.getLast().code());

                assertEquals(200, post(
                        managementPort, publishPath, "operator", managementOrigin,
                        "test-session", "test-csrf", "application/json",
                        "{\"channel\":\"management-events\",\"payload\":\"evicts-secret\"}")
                        .statusCode());
                awaitRetained(managementSubscriptions, operatorSubscriptionId, "operator", "orders",
                        "evicts-secret");
                assertEquals(410, post(
                        managementPort, pubSubRevealPath, "operator", managementOrigin,
                        "test-session", "test-csrf", "application/json", "{}").statusCode());
                assertEquals(200, post(
                        managementPort, publishPath, "operator", managementOrigin,
                        "test-session", "test-csrf", "application/json",
                        "{\"channel\":\"management-events\",\"payload\":\"stream-secret\"}")
                        .statusCode());
                var streamMessage = awaitRetained(
                        managementSubscriptions, operatorSubscriptionId, "operator", "orders",
                        "stream-secret");
                String streamPath = subscriptionsPath + "/" + operatorSubscriptionId + "/stream";
                HttpResponse<InputStream> stream = stream(
                        managementPort, streamPath, "operator", managementOrigin,
                        "test-session", Long.toString(operatorMessage.eventId()));
                assertEquals(200, stream.statusCode());
                assertEquals("text/event-stream; charset=utf-8",
                        stream.headers().firstValue("content-type").orElseThrow());
                assertEquals("no-store", stream.headers().firstValue("cache-control").orElseThrow());
                assertEquals("keep-alive", stream.headers().firstValue("connection").orElseThrow());
                BufferedReader streamReader = new BufferedReader(new InputStreamReader(
                        stream.body(), StandardCharsets.UTF_8));
                String readyEvent = readSseEvent(streamReader);
                assertTrue(readyEvent.contains("event: ready"));
                assertTrue(readyEvent.contains("\"connectedAt\""));
                assertEquals(1, runtimeMonitor.snapshot(
                        10, false, null, new ManagementAuditQueueState(0, 1, true))
                        .sseClients());
                String resetEvent = readSseEvent(streamReader);
                assertTrue(resetEvent.contains("event: reset"));
                assertTrue(resetEvent.contains("\"oldestAvailableEventId\":\""
                        + streamMessage.eventId() + "\""));
                String replayedEvent = readSseEvent(streamReader);
                assertTrue(replayedEvent.contains("id: " + streamMessage.eventId()));
                assertTrue(replayedEvent.contains("event: pubsub.message"));
                assertTrue(replayedEvent.contains("\"messageId\":\""
                        + streamMessage.messageId() + "\""));
                assertTrue(replayedEvent.contains("\"payloadState\":\"MASKED\""));
                assertTrue(!replayedEvent.contains("stream-secret"));
                streamScheduler.fire();
                assertEquals(": heartbeat", readSseEvent(streamReader));
                assertEquals(200, post(
                        managementPort, publishPath, "operator", managementOrigin,
                        "test-session", "test-csrf", "application/json",
                        "{\"channel\":\"management-events\",\"payload\":\"live-secret\"}")
                        .statusCode());
                var liveMessage = awaitRetained(
                        managementSubscriptions, operatorSubscriptionId, "operator", "orders",
                        "live-secret");
                String liveEvent = readSseEvent(streamReader);
                assertTrue(liveEvent.contains("id: " + liveMessage.eventId()));
                assertTrue(liveEvent.contains("\"messageId\":\""
                        + liveMessage.messageId() + "\""));
                assertTrue(!liveEvent.contains("live-secret"));
                stream.body().close();
                awaitCondition(() -> streamScheduler.cancelCount.get() == 1,
                        "SSE heartbeat was not cancelled exactly once");
                assertEquals(0, runtimeMonitor.snapshot(
                        10, false, null, new ManagementAuditQueueState(0, 1, true))
                        .sseClients());
                assertEquals(204, delete(
                        managementPort, subscriptionsPath + "/" + operatorSubscriptionId,
                        "operator", managementOrigin, "test-session", "test-csrf", null)
                        .statusCode());
                assertEquals(204, delete(
                        managementPort, managementSubscriptionPath, null, managementOrigin,
                        "test-session", "test-csrf", null).statusCode());

                HttpResponse<InputStream> metricsStream = stream(
                        managementPort, "/api/v1/setups/orders/sse/metrics", null,
                        managementOrigin, "test-session", null);
                assertEquals(200, metricsStream.statusCode());
                BufferedReader metricsReader = new BufferedReader(new InputStreamReader(
                        metricsStream.body(), StandardCharsets.UTF_8));
                assertTrue(readSseEvent(metricsReader).contains("event: ready"));
                assertEquals(1, runtimeMonitor.snapshot(
                        10, false, null, new ManagementAuditQueueState(0, 1, true))
                        .sseClients());
                streamScheduler.fire();
                assertEquals(": heartbeat", readSseEvent(metricsReader));
                String overviewSnapshot = readSseEvent(metricsReader);
                assertTrue(overviewSnapshot.contains("event: overview.snapshot"));
                assertTrue(overviewSnapshot.contains("\"scope\":\"DATABASE\""));
                String runtimeSnapshot = readSseEvent(metricsReader);
                assertTrue(runtimeSnapshot.contains("event: runtime.snapshot"));
                assertTrue(runtimeSnapshot.contains("\"scope\":\"MANAGEMENT_RUNTIME\""));
                assertTrue(!overviewSnapshot.contains("stored-value"));
                assertTrue(!runtimeSnapshot.contains("reveal-secret"));
                metricsStream.body().close();
                awaitCondition(() -> streamScheduler.cancelCount.get() == 2,
                        "metrics SSE heartbeat was not cancelled exactly once");
                assertEquals(0, runtimeMonitor.snapshot(
                        10, false, null, new ManagementAuditQueueState(0, 1, true))
                        .sseClients());

                RecordingWebSocketListener webSocketListener = new RecordingWebSocketListener();
                WebSocket webSocket = HttpClient.newHttpClient().newWebSocketBuilder()
                        .header("Origin", managementOrigin)
                        .header("Cookie", "test-session")
                        .buildAsync(
                                URI.create("ws://127.0.0.1:" + managementPort
                                        + "/ws/monitoring?setupId=orders"),
                                webSocketListener)
                        .get(5, TimeUnit.SECONDS);
                var readyEnvelope = new com.fasterxml.jackson.databind.ObjectMapper()
                        .readTree(webSocketListener.next());
                assertEquals("connection.ready", readyEnvelope.path("type").asText());
                assertEquals("orders", readyEnvelope.path("setupId").asText());
                assertEquals(1, runtimeMonitor.snapshot(
                        10, false, null, new ManagementAuditQueueState(0, 1, true))
                        .webSocketClients());
                assertEquals(200, post(
                        managementPort, publishPath, "operator", managementOrigin,
                        "test-session", "test-csrf", "application/json",
                        "{\"channel\":\"management-events\",\"payload\":\"ws-secret-one\"}")
                        .statusCode());
                var activityEnvelope = new com.fasterxml.jackson.databind.ObjectMapper()
                        .readTree(webSocketListener.next());
                assertEquals("activity.created", activityEnvelope.path("type").asText());
                assertEquals("PUBSUB_PUBLISHED",
                        activityEnvelope.path("data").path("summary").asText());
                assertTrue(!activityEnvelope.toString().contains("ws-secret-one"));
                var firstResourceEnvelope = new com.fasterxml.jackson.databind.ObjectMapper()
                        .readTree(webSocketListener.next());
                assertEquals("resource.changed", firstResourceEnvelope.path("type").asText());
                assertTrue(!firstResourceEnvelope.toString().contains("ws-secret-one"));
                String firstResourceEventId = firstResourceEnvelope.path("eventId").asText();
                streamScheduler.fire();
                awaitCondition(() -> streamScheduler.oneShotCancelCount.get() == 1,
                        "WebSocket pong did not cancel its timeout");
                liveEvents.publish(
                        "orders", "activity.created",
                        "{\"summary\":\"Second entry changed\"}");
                var secondActivityEnvelope = new com.fasterxml.jackson.databind.ObjectMapper()
                        .readTree(webSocketListener.next());
                assertEquals("activity.created", secondActivityEnvelope.path("type").asText());
                String secondActivityEventId = secondActivityEnvelope.path("eventId").asText();
                webSocket.sendClose(WebSocket.NORMAL_CLOSURE, "test complete")
                        .get(5, TimeUnit.SECONDS);
                awaitCondition(() -> streamScheduler.cancelCount.get() == 3,
                        "WebSocket ping timer was not cancelled exactly once");
                assertEquals(0, runtimeMonitor.snapshot(
                        10, false, null, new ManagementAuditQueueState(0, 1, true))
                        .webSocketClients());

                RecordingWebSocketListener resumedListener = new RecordingWebSocketListener();
                WebSocket resumedWebSocket = HttpClient.newHttpClient().newWebSocketBuilder()
                        .header("Origin", managementOrigin)
                        .header("Cookie", "test-session")
                        .buildAsync(
                                URI.create("ws://127.0.0.1:" + managementPort
                                        + "/ws/monitoring?setupId=orders&afterEventId="
                                        + firstResourceEventId),
                                resumedListener)
                        .get(5, TimeUnit.SECONDS);
                assertEquals("connection.ready",
                        new com.fasterxml.jackson.databind.ObjectMapper()
                                .readTree(resumedListener.next()).path("type").asText());
                assertEquals(secondActivityEventId,
                        new com.fasterxml.jackson.databind.ObjectMapper()
                                .readTree(resumedListener.next()).path("eventId").asText());
                resumedWebSocket.sendClose(WebSocket.NORMAL_CLOSURE, "test complete")
                        .get(5, TimeUnit.SECONDS);
                awaitCondition(() -> streamScheduler.cancelCount.get() == 4,
                        "resumed WebSocket ping timer was not cancelled exactly once");

                HttpResponse<String> locks = get(
                        managementPort, "/api/v1/setups/orders/locks?namespace=logical-orders");
                assertEquals(200, locks.statusCode());
                assertEquals("MASKED", new com.fasterxml.jackson.databind.ObjectMapper()
                        .readTree(locks.body()).path("items").get(0).path("owner").path("state").asText());
                assertTrue(!locks.body().contains("owner-secret"));
                HttpResponse<String> lock = get(managementPort,
                        "/api/v1/setups/orders/namespaces/" + encoded + "/locks/"
                                + ManagementIdentifierCodec.encodeKey("lease"));
                assertEquals(200, lock.statusCode());
                assertEquals("\"v5\"", lock.headers().firstValue("etag").orElseThrow());
                assertTrue(!lock.body().contains("owner-secret"));
                String lockRevealPath = "/api/v1/setups/orders/namespaces/" + encoded
                        + "/locks/" + ManagementIdentifierCodec.encodeKey("lease")
                        + "/owner/reveal";
                assertEquals(403, post(
                        managementPort, lockRevealPath, null, managementOrigin,
                        "test-session", "test-csrf", "application/json", "{}").statusCode());
                HttpResponse<String> revealedOwner = post(
                        managementPort, lockRevealPath, "operator", managementOrigin,
                        "test-session", "test-csrf", "application/json",
                        "{\"reason\":\"Investigating stalled lock\"}");
                assertEquals(200, revealedOwner.statusCode());
                assertEquals("no-store, no-cache, must-revalidate",
                        revealedOwner.headers().firstValue("cache-control").orElseThrow());
                assertEquals("no-cache",
                        revealedOwner.headers().firstValue("pragma").orElseThrow());
                var ownerBody = new com.fasterxml.jackson.databind.ObjectMapper()
                        .readTree(revealedOwner.body());
                assertEquals("lease", ownerBody.path("key").asText());
                assertEquals("owner-secret", ownerBody.path("ownerToken").asText());
                assertEquals("5", ownerBody.path("version").asText());
                assertTrue(ownerBody.path("revealedAt").asText().endsWith("Z"));
                assertEquals(60_000, ownerBody.path("autoHideAfterMillis").asInt());
                assertEquals(ManagementAuditAction.REVEAL_LOCK_OWNER,
                        administrationAudit.intents.getLast().action());
                assertEquals("Investigating stalled lock",
                        administrationAudit.intents.getLast().reason());
                assertTrue(!administrationAudit.intents.getLast()
                        .identifierFingerprints().toString().contains("owner-secret"));
                assertEquals("LOCK_OWNER_REVEALED",
                        administrationAudit.outcomes.getLast().code());
                String forceReleasePath = "/api/v1/setups/orders/namespaces/" + encoded
                        + "/locks/" + ManagementIdentifierCodec.encodeKey("lease")
                        + "/force-release";
                String forceReleaseBody =
                        "{\"confirmationKey\":\"lease\","
                                + "\"reason\":\"Worker terminated unexpectedly\"}";
                assertEquals(403, postConditional(
                        managementPort, forceReleasePath, null, managementOrigin,
                        "test-session", "test-csrf", "\"v5\"", "application/json",
                        forceReleaseBody).statusCode());
                assertEquals(428, postConditional(
                        managementPort, forceReleasePath, "operator", managementOrigin,
                        "test-session", "test-csrf", null, "application/json",
                        forceReleaseBody).statusCode());
                assertEquals(400, postConditional(
                        managementPort, forceReleasePath, "operator", managementOrigin,
                        "test-session", "test-csrf", "\"v5\"", "application/json",
                        "{\"confirmationKey\":\"wrong\"}").statusCode());
                assertEquals(412, postConditional(
                        managementPort, forceReleasePath, "operator", managementOrigin,
                        "test-session", "test-csrf", "\"v99\"", "application/json",
                        forceReleaseBody).statusCode());
                HttpResponse<String> forceReleased = postConditional(
                        managementPort, forceReleasePath, "operator", managementOrigin,
                        "test-session", "test-csrf", "\"v5\"", "application/json",
                        forceReleaseBody);
                assertEquals(204, forceReleased.statusCode());
                assertTrue(forceReleased.body().isEmpty());
                assertEquals(ManagementAuditAction.FORCE_RELEASE_LOCK,
                        administrationAudit.intents.getLast().action());
                assertEquals("Worker terminated unexpectedly",
                        administrationAudit.intents.getLast().reason());
                assertEquals("LOCK_RELEASED", administrationAudit.outcomes.getLast().code());
                assertTrue(!administrationAudit.intents.getLast()
                        .identifierFingerprints().toString().contains("owner-secret"));
                assertEquals(404, get(managementPort,
                        "/api/v1/setups/orders/namespaces/" + encoded + "/locks/"
                                + ManagementIdentifierCodec.encodeKey("lease")).statusCode());
                assertEquals(400, get(
                        managementPort, "/api/v1/setups/orders/namespaces/AA").statusCode());
            } finally {
                await(managementServer.stop());
                await(managementVertx.close());
            }

            await(registry.detach("orders"));
            assertEquals(0, applicationConnectionCount("peegeeq-management-orders"));
            assertEquals(SetupState.DETACHED, registry.get("orders").state());
            assertEquals(SetupHealthSummary.Status.DOWN, await(registry.health("orders")).status());

            await(registry.connect("orders"));
            assertEquals(2, resolutions.get());
            assertTrue(applicationConnectionCount("peegeeq-management-orders") > 0);
        } finally {
            await(registry.closeAsync());
        }
        assertEquals(0, applicationConnectionCount("peegeeq-management-orders"));
    }

    private static SetupDefinition definition(String setupId) {
        return new SetupDefinition(
                setupId,
                "Orders database",
                new SetupTarget("db.internal.example", postgres.getMappedPort(5432),
                        "test-ca", TlsMode.VERIFY_FULL),
                "peegeeq",
                "peegee_cache",
                "peegeeq",
                3,
                SetupSource.UI_SESSION,
                null);
    }

    private static int applicationConnectionCount(String applicationName) throws Exception {
        try (var connection = java.sql.DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             var statement = connection.prepareStatement(
                     "SELECT COUNT(*) FROM pg_stat_activity WHERE application_name = ?")) {
            statement.setString(1, applicationName);
            try (var rows = statement.executeQuery()) {
                rows.next();
                return rows.getInt(1);
            }
        }
    }

    private static void seedInspectionData() throws Exception {
        try (var connection = java.sql.DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             var statement = connection.createStatement()) {
            statement.executeUpdate("""
                    INSERT INTO peegee_cache.cache_entries
                        (namespace, cache_key, value_type, value_bytes, version)
                    VALUES ('logical-orders', 'customer:1', 'STRING',
                            convert_to('stored-value', 'UTF8'), 3)
                    """);
            statement.executeUpdate("""
                    INSERT INTO peegee_cache.cache_counters
                        (namespace, counter_key, counter_value, version)
                    VALUES ('logical-orders', 'count', 42, 4)
                    """);
            statement.executeUpdate("""
                    INSERT INTO peegee_cache.cache_locks
                        (namespace, lock_key, owner_token, fencing_token, version, lease_expires_at)
                    VALUES ('logical-orders', 'lease', 'owner-secret', 7, 5, NOW() + INTERVAL '1 hour')
                    """);
        }
    }

    private static void seedBulkEntries() throws Exception {
        try (var connection = java.sql.DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             var statement = connection.createStatement()) {
            statement.execute("""
                    INSERT INTO peegee_cache.cache_entries
                        (namespace, cache_key, value_type, value_bytes, version)
                    VALUES ('logical-orders', 'zz-bulk-first', 'STRING',
                            convert_to('bulk-first-secret', 'UTF8'), 11),
                           ('logical-orders', 'zz-bulk-second', 'STRING',
                            convert_to('bulk-second-secret', 'UTF8'), 12)
                    """);
        }
    }

    private static void changeBulkEntryVersion() throws Exception {
        try (var connection = java.sql.DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             var statement = connection.createStatement()) {
            statement.execute("""
                    UPDATE peegee_cache.cache_entries
                       SET version = 13
                     WHERE namespace = 'logical-orders'
                       AND cache_key = 'zz-bulk-second'
                    """);
        }
    }

    private static void seedBulkCounters() throws Exception {
        try (var connection = java.sql.DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             var statement = connection.createStatement()) {
            statement.execute("""
                    INSERT INTO peegee_cache.cache_counters
                        (namespace, counter_key, counter_value, version)
                    VALUES ('logical-orders', 'bulk-counter-first', 100, 21),
                           ('logical-orders', 'bulk-counter-second', 200, 22)
                    """);
        }
    }

    private static void changeBulkCounterVersion() throws Exception {
        try (var connection = java.sql.DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             var statement = connection.createStatement()) {
            statement.execute("""
                    UPDATE peegee_cache.cache_counters
                       SET version = 23
                     WHERE namespace = 'logical-orders'
                       AND counter_key = 'bulk-counter-second'
                    """);
        }
    }

    private static HttpResponse<String> get(int port, String path) throws Exception {
        return get(port, path, null);
    }

    private static HttpResponse<String> get(int port, String path, String accept) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(
                URI.create("http://127.0.0.1:" + port + path)).GET();
        if (accept != null) {
            request.header("Accept", accept);
        }
        return HttpClient.newHttpClient().send(
                request.build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static HttpResponse<InputStream> stream(
            int port,
            String path,
            String role,
            String origin,
            String cookie,
            String lastEventId) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(
                URI.create("http://127.0.0.1:" + port + path)).GET()
                .header("Accept", "text/event-stream");
        if (role != null) request.header("X-Test-Role", role);
        if (origin != null) request.header("Origin", origin);
        if (cookie != null) request.header("Cookie", cookie);
        if (lastEventId != null) request.header("Last-Event-ID", lastEventId);
        return HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build().send(
                request.build(), HttpResponse.BodyHandlers.ofInputStream());
    }

    private static HttpResponse<String> post(
            int port,
            String path,
            String role,
            String origin,
            String cookie,
            String csrf,
            String contentType,
            String body) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(
                URI.create("http://127.0.0.1:" + port + path));
        if (role != null) request.header("X-Test-Role", role);
        if (origin != null) request.header("Origin", origin);
        if (cookie != null) request.header("Cookie", cookie);
        if (csrf != null) request.header("X-PeeGeeQ-CSRF", csrf);
        if (contentType != null) request.header("Content-Type", contentType);
        return HttpClient.newHttpClient().send(
                request.POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static HttpResponse<String> postAs(
            int port,
            String path,
            String role,
            String actor,
            String origin,
            String cookie,
            String csrf,
            String contentType,
            String body) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(
                URI.create("http://127.0.0.1:" + port + path));
        if (role != null) request.header("X-Test-Role", role);
        if (actor != null) request.header("X-Test-Actor", actor);
        if (origin != null) request.header("Origin", origin);
        if (cookie != null) request.header("Cookie", cookie);
        if (csrf != null) request.header("X-PeeGeeQ-CSRF", csrf);
        if (contentType != null) request.header("Content-Type", contentType);
        return HttpClient.newHttpClient().send(
                request.POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static HttpResponse<String> put(
            int port,
            String path,
            String role,
            String origin,
            String cookie,
            String csrf,
            String ifMatch,
            String ifNoneMatch,
            String body) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(
                URI.create("http://127.0.0.1:" + port + path));
        if (role != null) request.header("X-Test-Role", role);
        if (origin != null) request.header("Origin", origin);
        if (cookie != null) request.header("Cookie", cookie);
        if (csrf != null) request.header("X-PeeGeeQ-CSRF", csrf);
        if (ifMatch != null) request.header("If-Match", ifMatch);
        if (ifNoneMatch != null) request.header("If-None-Match", ifNoneMatch);
        request.header("Content-Type", "application/json");
        return HttpClient.newHttpClient().send(
                request.PUT(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static HttpResponse<String> postConditional(
            int port,
            String path,
            String role,
            String origin,
            String cookie,
            String csrf,
            String ifMatch,
            String contentType,
            String body) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(
                URI.create("http://127.0.0.1:" + port + path));
        if (role != null) request.header("X-Test-Role", role);
        if (origin != null) request.header("Origin", origin);
        if (cookie != null) request.header("Cookie", cookie);
        if (csrf != null) request.header("X-PeeGeeQ-CSRF", csrf);
        if (ifMatch != null) request.header("If-Match", ifMatch);
        if (contentType != null) request.header("Content-Type", contentType);
        return HttpClient.newHttpClient().send(
                request.POST(body == null
                        ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static HttpResponse<String> delete(
            int port,
            String path,
            String role,
            String origin,
            String cookie,
            String csrf,
            String ifMatch) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(
                URI.create("http://127.0.0.1:" + port + path));
        if (role != null) request.header("X-Test-Role", role);
        if (origin != null) request.header("Origin", origin);
        if (cookie != null) request.header("Cookie", cookie);
        if (csrf != null) request.header("X-PeeGeeQ-CSRF", csrf);
        if (ifMatch != null) request.header("If-Match", ifMatch);
        return HttpClient.newHttpClient().send(
                request.DELETE().build(), HttpResponse.BodyHandlers.ofString());
    }

    private static int freePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static <T> T await(Future<T> future) throws Exception {
        return future.toCompletionStage().toCompletableFuture().get(20, TimeUnit.SECONDS);
    }

    private static String readSseEvent(BufferedReader reader) throws Exception {
        return CompletableFuture.supplyAsync(() -> {
            try {
                List<String> lines = new ArrayList<>();
                String line;
                while ((line = reader.readLine()) != null && !line.isEmpty()) {
                    lines.add(line);
                }
                if (lines.isEmpty() && line == null) {
                    throw new AssertionError("SSE stream closed before the expected event");
                }
                return String.join("\n", lines);
            } catch (java.io.IOException failure) {
                throw new java.io.UncheckedIOException(failure);
            }
        }).get(5, TimeUnit.SECONDS);
    }

    private static void awaitCondition(
            java.util.function.BooleanSupplier condition, String failureMessage) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) return;
            Thread.sleep(10);
        }
        throw new AssertionError(failureMessage);
    }

    private static ManagementPubSubSubscriptions.RetainedMessage awaitRetained(
            ManagementPubSubSubscriptions subscriptions,
            String subscriptionId,
            String actor,
            String setupId,
            String expectedPayload) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            var snapshot = subscriptions.snapshot(subscriptionId, actor, setupId);
            if (!snapshot.messages().isEmpty()
                    && expectedPayload.equals(snapshot.messages().getLast().payload())) {
                return snapshot.messages().getLast();
            }
            Thread.sleep(10);
        }
        throw new AssertionError("retained pub/sub message was not observed");
    }

    private static final class RecordingAuditSink implements ManagementAuditSink {
        private final List<ManagementAuditIntent> intents = new ArrayList<>();
        private final List<ManagementAuditOutcome> outcomes = new ArrayList<>();
        private boolean rejectReservations;

        @Override
        public Future<ManagementAuditReservation> reserveIntent(ManagementAuditIntent intent) {
            if (rejectReservations) {
                return Future.failedFuture(new ManagementAuditException("audit unavailable"));
            }
            intents.add(intent);
            return Future.succeededFuture(new ManagementAuditReservation(
                    UUID.randomUUID().toString(), intent.eventId(), "test-generation"));
        }

        @Override
        public Future<Void> complete(
                ManagementAuditReservation reservation,
                ManagementAuditOutcome outcome) {
            outcomes.add(outcome);
            return Future.succeededFuture();
        }
    }

    private static final class RecordingPeriodicScheduler implements ManagementPeriodicScheduler {
        private ScheduledTask periodic;
        private ScheduledTask oneShot;
        private final AtomicInteger cancelCount = new AtomicInteger();
        private final AtomicInteger oneShotCancelCount = new AtomicInteger();

        @Override
        public ManagementPeriodicScheduler.Cancellable schedule(
                long intervalMillis, Runnable task) {
            assertTrue(intervalMillis == 15_000 || intervalMillis == 20_000);
            periodic = new ScheduledTask(task, cancelCount);
            return periodic::cancel;
        }

        @Override
        public ManagementPeriodicScheduler.Cancellable scheduleOnce(
                long delayMillis, Runnable task) {
            assertEquals(10_000, delayMillis);
            oneShot = new ScheduledTask(task, oneShotCancelCount);
            return oneShot::cancel;
        }

        private void fire() {
            periodic.fire();
        }

        private static final class ScheduledTask {
            private final Runnable task;
            private final AtomicInteger cancelCount;
            private final java.util.concurrent.atomic.AtomicBoolean cancelled =
                    new java.util.concurrent.atomic.AtomicBoolean();

            private ScheduledTask(Runnable task, AtomicInteger cancelCount) {
                this.task = task;
                this.cancelCount = cancelCount;
            }

            private void fire() {
                if (!cancelled.get()) task.run();
            }

            private void cancel() {
                if (cancelled.compareAndSet(false, true)) cancelCount.incrementAndGet();
            }
        }
    }

    private static final class RecordingWebSocketListener implements WebSocket.Listener {
        private final LinkedBlockingQueue<String> messages = new LinkedBlockingQueue<>();
        private final StringBuilder partial = new StringBuilder();

        @Override
        public void onOpen(WebSocket webSocket) {
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onText(
                WebSocket webSocket, CharSequence data, boolean last) {
            partial.append(data);
            if (last) {
                messages.add(partial.toString());
                partial.setLength(0);
            }
            webSocket.request(1);
            return null;
        }

        private String next() throws Exception {
            String message = messages.poll(5, TimeUnit.SECONDS);
            if (message == null) throw new AssertionError("WebSocket message was not received");
            return message;
        }
    }
}
