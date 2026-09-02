package dev.mars.peegeeq.cache.rest.server;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import dev.mars.peegeeq.cache.api.management.ManagementSecretReference;
import dev.mars.peegeeq.cache.pg.bootstrap.PgSchemaMigrator;
import dev.mars.peegeeq.cache.rest.security.BrowserOriginPolicy;
import dev.mars.peegeeq.cache.rest.security.SetupTargetPolicy;
import dev.mars.peegeeq.cache.rest.security.TrustedProxyAuthenticationConfig;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.PoolOptions;
import io.vertx.sqlclient.Row;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.MountableFile;

import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Real packaged-console fixture. It deliberately provides no HTTP or browser route mocks. */
final class ManagementConsolePostgresFixture {

    private static final Map<String, String> AUDITED_OPERATIONS = Map.ofEntries(
            Map.entry("testUnregisteredSetup", "TEST_SETUP"),
            Map.entry("registerSetup", "REGISTER_SETUP"),
            Map.entry("connectSetup", "CONNECT_SETUP"),
            Map.entry("testRegisteredSetup", "TEST_SETUP"),
            Map.entry("detachSetup", "DETACH_SETUP"),
            Map.entry("forgetSetup", "FORGET_SETUP"),
            Map.entry("revealEntryValue", "REVEAL_ENTRY"),
            Map.entry("setEntry", "SET_ENTRY"),
            Map.entry("deleteEntry", "DELETE_ENTRY"),
            Map.entry("expireEntry", "EXPIRE_ENTRY"),
            Map.entry("persistEntry", "PERSIST_ENTRY"),
            Map.entry("touchEntry", "TOUCH_ENTRY"),
            Map.entry("previewEntryBulkDelete", "PREVIEW_ENTRY_DELETE"),
            Map.entry("executeEntryBulkDelete", "EXECUTE_ENTRY_DELETE"),
            Map.entry("setCounter", "SET_COUNTER"),
            Map.entry("adjustCounter", "ADJUST_COUNTER"),
            Map.entry("expireCounter", "EXPIRE_COUNTER"),
            Map.entry("persistCounter", "PERSIST_COUNTER"),
            Map.entry("deleteCounter", "DELETE_COUNTER"),
            Map.entry("previewCounterBulkDelete", "PREVIEW_COUNTER_DELETE"),
            Map.entry("executeCounterBulkDelete", "EXECUTE_COUNTER_DELETE"),
            Map.entry("revealLockOwner", "REVEAL_LOCK_OWNER"),
            Map.entry("forceReleaseLock", "FORCE_RELEASE_LOCK"),
            Map.entry("createPubSubSubscription", "CREATE_PUBSUB_SUBSCRIPTION"),
            Map.entry("revealPubSubPayload", "REVEAL_PUBSUB_PAYLOAD"),
            Map.entry("deletePubSubSubscription", "DELETE_PUBSUB_SUBSCRIPTION"),
            Map.entry("publishPubSubMessage", "PUBLISH_PUBSUB"),
            Map.entry("batchGetEntries", "BATCH_GET_ENTRIES"),
            Map.entry("batchSetEntries", "BATCH_SET_ENTRIES"),
            Map.entry("scanEntries", "SCAN_ENTRY_VALUES"),
            Map.entry("acquireLock", "ACQUIRE_LOCK"),
            Map.entry("renewLock", "RENEW_LOCK"),
            Map.entry("releaseLock", "RELEASE_LOCK"),
            Map.entry("checkLockOwnership", "CHECK_LOCK_OWNERSHIP"));
    private static final Set<String> FIXTURE_AUDITED_OPERATIONS = Set.of(
            "testUnregisteredSetup", "registerSetup");
    private static final Set<String> FIXTURE_OPERATIONS = Set.of(
            "getSession", "exchangeLocalToken", "listSetups", "testUnregisteredSetup",
            "registerSetup", "getSetupCapabilities", "getOverview");

    static final String DATABASE_PASSWORD = "test-password";
    static final String SETUP_ID = "browser-postgres";
    static final String SETUP_NAME = "Real PostgreSQL";
    static final String NAMESPACE = "logical-orders";

    private ManagementConsolePostgresFixture() {
    }

    static void run(
            Path temporaryDirectory,
            PostgreSQLContainer postgres,
            boolean seed,
            Journey journey) throws Exception {
        run(temporaryDirectory, postgres, seed, Clock.systemUTC(), journey);
    }

    static void run(
            Path temporaryDirectory,
            PostgreSQLContainer postgres,
            boolean seed,
            List<String> expectedOperations,
            Journey journey) throws Exception {
        run(temporaryDirectory, postgres, seed, Clock.systemUTC(), expectedOperations, journey);
    }

    static void run(
            Path temporaryDirectory,
            PostgreSQLContainer postgres,
            boolean seed,
            Clock clock,
            Journey journey) throws Exception {
        run(temporaryDirectory, postgres, seed, clock,
                List.of(currentScenario().operations()), journey);
    }

    static void run(
            Path temporaryDirectory,
            PostgreSQLContainer postgres,
            boolean seed,
            Clock clock,
            List<String> expectedOperations,
            Journey journey) throws Exception {
        run(temporaryDirectory, postgres, seed, clock, expectedOperations, null, Map.of(), journey);
    }

    static void runWithCapabilities(
            Path temporaryDirectory,
            PostgreSQLContainer postgres,
            boolean seed,
            SetupCapabilities advertisedCapabilities,
            List<String> expectedOperations,
            Journey journey) throws Exception {
        run(temporaryDirectory, postgres, seed, Clock.systemUTC(), expectedOperations,
                java.util.Objects.requireNonNull(advertisedCapabilities, "advertisedCapabilities"),
                Map.of(), journey);
    }

    static void runTrustedProxy(
            Path temporaryDirectory,
            PostgreSQLContainer postgres,
            boolean seed,
            Journey journey) throws Exception {
        run(temporaryDirectory, postgres, seed, Clock.systemUTC(),
                List.of(currentScenario().operations()), null,
                Map.of("X-PeeGeeQ-User", "browser-operator",
                        "X-PeeGeeQ-Roles", "viewer,operator"),
                journey);
    }

    private static void run(
            Path temporaryDirectory,
            PostgreSQLContainer postgres,
            boolean seed,
            Clock clock,
            List<String> expectedOperations,
            SetupCapabilities advertisedCapabilities,
            Map<String, String> trustedProxyHeaders,
            Journey journey) throws Exception {
        if (!postgres.isRunning()) {
            throw new IllegalStateException("Browser-worker PostgreSQL container is not running");
        }
        Vertx migrationVertx = null;
        ManagementServerApplication application = null;
        PrometheusMeterRegistry meters = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        try {
            migrationVertx = Vertx.vertx();
            resetSchema(migrationVertx, postgres);
            migrate(migrationVertx, postgres, seed);

            int managementPort = freePort();
            String origin = "http://127.0.0.1:" + managementPort;
            ManagementSecretReference auditKey = new ManagementSecretReference("audit-key");
            Path auditPath = temporaryDirectory.resolve("management-browser-audit.jsonl");
            SetupTargetPolicy targetPolicy = new SetupTargetPolicy(
                            Set.of("internal.example"),
                            Set.of("127.0.0.0/8"),
                            Set.of(postgres.getMappedPort(5432)),
                            true,
                            false,
                            false,
                            false,
                            Set.of("test-ca"));
            boolean trustedProxy = !trustedProxyHeaders.isEmpty();
            ManagementServerConfiguration configuration = trustedProxy
                    ? ManagementServerConfiguration.trustedProxy(
                    "127.0.0.1", managementPort,
                    TrustedProxyAuthenticationConfig.defaults(Set.of("127.0.0.0/8")),
                    BrowserOriginPolicy.trustedProxy(origin, Set.of()),
                    targetPolicy, auditPath, auditKey)
                    : ManagementServerConfiguration.localToken(
                    "127.0.0.1", managementPort, origin, targetPolicy, auditPath, auditKey);
            InetAddress databaseAddress = InetAddress.getByName(postgres.getHost());
            Buffer serverCertificate = certificate();
            application = advertisedCapabilities == null
                    ? await(ManagementServerApplication.start(
                    configuration,
                    reference -> reference.equals(auditKey) ? new byte[32] : null,
                    ignored -> List.of(databaseAddress),
                    trustProfile -> trustProfile.equals("test-ca") ? serverCertificate : null,
                    meters,
                    clock))
                    : await(ManagementServerApplication.start(
                    configuration,
                    reference -> reference.equals(auditKey) ? new byte[32] : null,
                    ignored -> List.of(databaseAddress),
                    trustProfile -> trustProfile.equals("test-ca") ? serverCertificate : null,
                    meters,
                    clock,
                    ignored -> advertisedCapabilities));

            String bootstrapToken = trustedProxy
                    ? ""
                    : application.takeBootstrapToken().orElseThrow();
            List.of(DATABASE_PASSWORD, "stored-value", "owner-secret", "bulk-secret",
                    "pubsub-secret", "retained-value", "special-secret")
                    .forEach(ManagementBrowserEvidenceListener::registerSensitiveCanary);
            ManagementBrowserEvidenceListener.registerSensitiveCanary(bootstrapToken);
            try (Playwright playwright = Playwright.create(new Playwright.CreateOptions()
                    .setEnv(Map.of("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD", "1")));
                 Browser browser = playwright.chromium().launch(ManagementPlaywright.launchOptions());
                 BrowserContext browserContext = browser.newContext(new Browser.NewContextOptions()
                         .setExtraHTTPHeaders(trustedProxyHeaders))) {
                Page page = browserContext.newPage();
                Diagnostics diagnostics = new Diagnostics();
                diagnostics.attach(page);
                ManagementBrowserOperationTrace operationTrace = new ManagementBrowserOperationTrace();
                operationTrace.attach(page);
                try {
                    journey.run(new Context(
                            page,
                            browserContext,
                            postgres,
                            origin,
                            bootstrapToken,
                            auditPath,
                            diagnostics,
                            operationTrace));
                } catch (Exception | AssertionError failure) {
                    captureSanitizedFailureScreenshot(page);
                    throw failure;
                }
                diagnostics.assertNoBrowserErrors();
                diagnostics.assertNoUnexpectedFailedResponses();
                operationTrace.assertObservedExactly(expectedOperations, FIXTURE_OPERATIONS);
                assertNoUndeclaredAuditedOperations(operationTrace.observed(), expectedOperations);
                assertDurableAuditObserved(auditPath, expectedOperations);
            }
        } finally {
            if (application != null) {
                await(application.closeAsync());
                double leaked = meters.find("peegeeq.management.shutdown.leaked_resources")
                        .counter() == null
                        ? 0.0
                        : meters.find("peegeeq.management.shutdown.leaked_resources").counter().count();
                assertNoLeakedResources(leaked);
            }
            if (migrationVertx != null) {
                await(migrationVertx.close());
            }
            meters.close();
        }
    }

    static void authenticate(Context context) {
        Page page = context.page();
        ManagementPlaywright.ScenarioPresentation presentation =
                ManagementPlaywright.beginScenario(page);
        try {
            assertEquals(200, page.navigate(context.origin() + "/ui/").status());
            if (context.bootstrapToken().isBlank()) {
                com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat(page.getByRole(
                        com.microsoft.playwright.options.AriaRole.HEADING,
                        new Page.GetByRoleOptions().setName("Overview").setExact(true))).isVisible();
                presentation.finish();
                return;
            }
            page.getByLabel("Bootstrap token").fill(context.bootstrapToken());
            page.getByRole(com.microsoft.playwright.options.AriaRole.BUTTON,
                    new Page.GetByRoleOptions().setName("Connect")).click();
            com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat(page.getByRole(
                    com.microsoft.playwright.options.AriaRole.HEADING,
                    new Page.GetByRoleOptions().setName("Overview").setExact(true))).isVisible();
            presentation.finish();
        } finally {
            presentation.close();
        }
    }

    static void registerSetup(Context context) {
        Page page = context.page();
        PostgreSQLContainer postgres = context.postgres();
        page.getByRole(com.microsoft.playwright.options.AriaRole.LINK,
                new Page.GetByRoleOptions().setName("Setups")).click();
        page.getByRole(com.microsoft.playwright.options.AriaRole.BUTTON,
                new Page.GetByRoleOptions().setName("Register the first setup")).click();
        var dialog = page.getByRole(com.microsoft.playwright.options.AriaRole.DIALOG,
                new Page.GetByRoleOptions().setName("Register setup"));
        dialog.getByLabel("Setup ID").fill(SETUP_ID);
        dialog.getByLabel("Display name").fill(SETUP_NAME);
        dialog.getByLabel("Host").fill("db.internal.example");
        dialog.getByLabel("Port").fill(String.valueOf(postgres.getMappedPort(5432)));
        dialog.getByLabel("Database").fill("peegeeq");
        dialog.getByLabel("Schema", new com.microsoft.playwright.Locator.GetByLabelOptions()
                .setExact(true)).fill("peegee_cache");
        dialog.getByLabel("Username").fill("peegeeq");
        dialog.getByLabel("Password").fill(DATABASE_PASSWORD);
        dialog.getByLabel("Trust profile").fill("test-ca");
        dialog.getByLabel("Pool size").fill("3");
        dialog.getByLabel("Default TTL milliseconds").fill("3600000");
        dialog.getByLabel("Run the expiry sweeper").check();
        dialog.getByLabel("Pub/Sub channel prefix").fill("browser_cache");
        dialog.getByRole(com.microsoft.playwright.options.AriaRole.BUTTON,
                new com.microsoft.playwright.Locator.GetByRoleOptions().setName("Test connection")).click();
        com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat(
                dialog.getByRole(com.microsoft.playwright.options.AriaRole.STATUS))
                .containsText("Connection succeeded");
        dialog.getByLabel("Schema bootstrap").selectOption("APPLY");
        dialog.getByRole(com.microsoft.playwright.options.AriaRole.BUTTON,
                new com.microsoft.playwright.Locator.GetByRoleOptions().setName("Register setup")).click();
        com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat(page.getByTitle("Active setup scope"))
                .hasText("Setup: " + SETUP_ID);
        assertFalse(page.content().contains(DATABASE_PASSWORD));
    }

    static void execute(PostgreSQLContainer postgres, String statement) throws Exception {
        Vertx vertx = Vertx.vertx();
        Pool pool = Pool.pool(vertx, connectOptions(postgres), new PoolOptions().setMaxSize(1));
        try {
            await(pool.query(statement).execute());
        } finally {
            await(pool.close());
            await(vertx.close());
        }
    }

    private static void captureSanitizedFailureScreenshot(Page page) {
        try {
            page.evaluate("""
                    () => {
                      document.querySelectorAll('input[type="password"], textarea')
                        .forEach(control => { control.value = '[REDACTED]'; });
                      document.querySelectorAll('.value-content, [data-sensitive="true"]')
                        .forEach(element => { element.textContent = '[REDACTED]'; });
                    }
                    """);
            Path directory = ManagementBrowserRunConfig.current().artifactDirectory();
            Files.createDirectories(directory);
            Path screenshot = directory.resolve(
                    "failure-" + System.currentTimeMillis() + "-" + Thread.currentThread().threadId() + ".png");
            page.screenshot(new Page.ScreenshotOptions().setFullPage(true).setPath(screenshot));
        } catch (Exception ignored) {
            // Artifact capture must never replace the original browser failure.
        }
    }

    static String queryScalar(PostgreSQLContainer postgres, String statement) throws Exception {
        Vertx vertx = Vertx.vertx();
        Pool pool = Pool.pool(vertx, connectOptions(postgres), new PoolOptions().setMaxSize(1));
        try {
            Row row = await(pool.query(statement).execute()).iterator().next();
            Object value = row.getValue(0);
            return value == null ? null : String.valueOf(value);
        } finally {
            await(pool.close());
            await(vertx.close());
        }
    }

    static void assertDatabaseValue(String expected, String actual, String description) {
        assertEquals(expected, actual, "PostgreSQL did not contain the expected " + description);
    }

    static void assertNoLeakedResources(double leakedResourceCategories) {
        assertEquals(0.0, leakedResourceCategories,
                "Management shutdown reported leaked resource categories");
    }

    static void assertDurableAuditObserved(Path auditPath, List<String> operations) throws Exception {
        List<String> expectedActions = operations.stream()
                .map(AUDITED_OPERATIONS::get)
                .filter(java.util.Objects::nonNull)
                .distinct()
                .toList();
        if (expectedActions.isEmpty()) return;
        String audit = Files.readString(auditPath);
        expectedActions.forEach(action -> assertTrue(audit.contains("\"action\":\"" + action + "\""),
                () -> "Durable audit did not record expected action " + action));
    }

    static Set<String> auditedOperationIds() {
        return Set.copyOf(AUDITED_OPERATIONS.keySet());
    }

    static void assertNoUndeclaredAuditedOperations(
            Set<String> observedOperations,
            List<String> expectedOperations) {
        Set<String> unexpected = new java.util.LinkedHashSet<>(observedOperations);
        unexpected.retainAll(AUDITED_OPERATIONS.keySet());
        unexpected.removeAll(FIXTURE_AUDITED_OPERATIONS);
        unexpected.removeAll(expectedOperations);
        assertEquals(Set.of(), unexpected,
                () -> "Browser invoked undeclared audited operations " + unexpected);
    }

    private static void migrate(Vertx vertx, PostgreSQLContainer postgres, boolean seed) throws Exception {
        Pool pool = Pool.pool(vertx, connectOptions(postgres), new PoolOptions().setMaxSize(1));
        try {
            await(new PgSchemaMigrator(pool, "peegee_cache").migrate());
            if (seed) {
                await(pool.query("""
                        INSERT INTO peegee_cache.cache_entries
                            (namespace, cache_key, value_type, value_bytes, numeric_value, version)
                        VALUES
                            ('logical-orders', 'customer:1', 'STRING',
                             convert_to('stored-value', 'UTF8'), NULL, 3),
                            ('logical-orders', 'json-record', 'JSON',
                             convert_to('{"safe":true}', 'UTF8'), NULL, 1),
                            ('logical-orders', 'long-boundary', 'LONG',
                             NULL, 9223372036854775807, 1),
                            ('logical-orders', 'binary-record', 'BYTES',
                             decode('00ff41', 'hex'), NULL, 1);
                        INSERT INTO peegee_cache.cache_counters
                            (namespace, counter_key, counter_value, version)
                        VALUES ('logical-orders', 'count', 42, 4);
                        INSERT INTO peegee_cache.cache_locks
                            (namespace, lock_key, owner_token, fencing_token, version, lease_expires_at)
                        VALUES ('logical-orders', 'lease', 'owner-secret', 7, 5,
                                NOW() + INTERVAL '1 hour');
                        """).execute());
            }
        } finally {
            await(pool.close());
        }
    }

    private static void resetSchema(Vertx vertx, PostgreSQLContainer postgres) throws Exception {
        Pool pool = Pool.pool(vertx, connectOptions(postgres), new PoolOptions().setMaxSize(1));
        try {
            await(pool.query("DROP SCHEMA IF EXISTS peegee_cache CASCADE").execute());
        } finally {
            await(pool.close());
        }
    }

    private static PgConnectOptions connectOptions(PostgreSQLContainer postgres) {
        return new PgConnectOptions()
                .setHost(postgres.getHost())
                .setPort(postgres.getMappedPort(5432))
                .setDatabase(postgres.getDatabaseName())
                .setUser(postgres.getUsername())
                .setPassword(postgres.getPassword());
    }

    static PostgreSQLContainer newPostgresWorkerContainer() {
        String postgresImage = ManagementBrowserRunConfig.current().postgresImage();
        return new PostgreSQLContainer(postgresImage)
                .withDatabaseName("peegeeq")
                .withUsername("peegeeq")
                .withPassword(DATABASE_PASSWORD)
                .withCopyFileToContainer(
                        MountableFile.forClasspathResource("postgres-tls-init.sh", 0775),
                        "/docker-entrypoint-initdb.d/010-postgres-tls-init.sh")
                .withCopyFileToContainer(
                        MountableFile.forClasspathResource("postgres-tls-server.crt", 0444),
                        "/docker-entrypoint-initdb.d/server.crt")
                .withCopyFileToContainer(
                        MountableFile.forClasspathResource("postgres-tls-server.key", 0444),
                        "/docker-entrypoint-initdb.d/server.key");
    }

    private static Buffer certificate() throws Exception {
        try (var input = ManagementConsolePostgresFixture.class.getResourceAsStream(
                "/postgres-tls-server.crt")) {
            if (input == null) {
                throw new IllegalStateException("Missing PostgreSQL TLS certificate fixture");
            }
            return Buffer.buffer(input.readAllBytes());
        }
    }

    private static int freePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            return socket.getLocalPort();
        }
    }

    private static <T> T await(Future<T> future) throws Exception {
        return future.toCompletionStage().toCompletableFuture().get(60, TimeUnit.SECONDS);
    }

    @FunctionalInterface
    interface Journey {
        void run(Context context) throws Exception;
    }

    record Context(
            Page page,
            BrowserContext browserContext,
            PostgreSQLContainer postgres,
            String origin,
            String bootstrapToken,
            Path auditPath,
            Diagnostics diagnostics,
            ManagementBrowserOperationTrace operationTrace) {
    }

    private static ManagementBrowserScenario currentScenario() {
        return StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE).walk(frames -> frames
                .map(frame -> java.util.Arrays.stream(frame.getDeclaringClass().getDeclaredMethods())
                        .filter(method -> method.getName().equals(frame.getMethodName()))
                        .map(method -> method.getAnnotation(ManagementBrowserScenario.class))
                        .filter(java.util.Objects::nonNull)
                        .findFirst()
                        .orElse(null))
                .filter(java.util.Objects::nonNull)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "Real PostgreSQL browser fixture must be called by a @ManagementBrowserScenario test")));
    }

    static final class Diagnostics {
        private final List<String> browserErrors = new ArrayList<>();
        private final List<String> failedResponses = new ArrayList<>();
        private final List<String> expectedFailedResponses = new ArrayList<>();

        void attach(Page page) {
            page.onConsoleMessage(message -> {
                if (message.type().equals("error")
                        && !message.text().startsWith("Failed to load resource:")) {
                    browserErrors.add(message.text());
                }
            });
            page.onPageError(browserErrors::add);
            page.onResponse(response -> {
                String path = URI.create(response.url()).getPath();
                boolean expectedUnauthenticatedBootstrap = response.status() == 401
                        && path.equals("/api/v1/session");
                if (response.status() >= 400 && !expectedUnauthenticatedBootstrap) {
                    recordResponse(response.status(), path);
                }
            });
        }

        void expectFailedResponse(int status, String path) {
            if (status < 400) throw new IllegalArgumentException("Expected failure status must be at least 400");
            expectedFailedResponses.add(status + " " + canonicalFailurePath(path));
        }

        void recordResponse(int status, String path) {
            if (status >= 400) failedResponses.add(status + " " + canonicalFailurePath(path));
        }

        List<String> failedResponses() {
            return List.copyOf(failedResponses);
        }

        void assertNoBrowserErrors() {
            assertEquals(List.of(), browserErrors, "Browser console/page errors");
        }

        void assertNoUnexpectedFailedResponses() {
            assertEquals(expectedFailedResponses, failedResponses,
                    () -> "Browser HTTP failures did not exactly match the expected status and route");
        }

        private static String canonicalFailurePath(String path) {
            return path.equals("/api") || path.startsWith("/api/")
                    || path.equals("/ws") || path.startsWith("/ws/")
                    ? ManagementRouteTemplate.resolve(path)
                    : path;
        }
    }
}
