package dev.mars.peegeeq.cache.rest.server;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.Cookie;
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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.UnaryOperator;
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
            Map.entry("batchDeleteEntries", "BATCH_DELETE_ENTRIES"),
            Map.entry("scanEntries", "SCAN_ENTRY_VALUES"),
            Map.entry("acquireLock", "ACQUIRE_LOCK"),
            Map.entry("renewLock", "RENEW_LOCK"),
            Map.entry("releaseLock", "RELEASE_LOCK"),
            Map.entry("checkLockOwnership", "CHECK_LOCK_OWNERSHIP"));
    private static final Set<String> FIXTURE_AUDITED_OPERATIONS = Set.of(
            "testUnregisteredSetup", "registerSetup");
    private static final Set<String> FIXTURE_OPERATIONS = Set.of(
            "getSession", "exchangeLocalToken", "listSetups", "testUnregisteredSetup",
            "registerSetup", "getSetupCapabilities", "getOverview", "getDatabaseMonitoring",
            "getRuntimeMonitoring", "listActivity");
    private static final Set<String> ISOLATED_LIFECYCLE_OPERATIONS = Set.of(
            "testUnregisteredSetup", "registerSetup", "connectSetup", "detachSetup",
            "forgetSetup", "deleteLocalSession");
    private static final Map<PostgreSQLContainer, SharedEnvironment> SHARED_ENVIRONMENTS =
            new IdentityHashMap<>();

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
        runSelected(temporaryDirectory, postgres, seed,
                List.of(currentScenario().operations()), journey);
    }

    static void run(
            Path temporaryDirectory,
            PostgreSQLContainer postgres,
            boolean seed,
            List<String> expectedOperations,
            Journey journey) throws Exception {
        runSelected(temporaryDirectory, postgres, seed, expectedOperations, journey);
    }

    static void run(
            Path temporaryDirectory,
            PostgreSQLContainer postgres,
            boolean seed,
            Clock clock,
            Journey journey) throws Exception {
        runIsolated(temporaryDirectory, postgres, seed, clock,
                List.of(currentScenario().operations()), null, Map.of(), true, journey);
    }

    static void run(
            Path temporaryDirectory,
            PostgreSQLContainer postgres,
            boolean seed,
            Clock clock,
            List<String> expectedOperations,
            Journey journey) throws Exception {
        runIsolated(temporaryDirectory, postgres, seed, clock,
                expectedOperations, null, Map.of(), true, journey);
    }

    static void runWithCapabilities(
            Path temporaryDirectory,
            PostgreSQLContainer postgres,
            boolean seed,
            UnaryOperator<SetupCapabilities.Source> capabilitySourceFilter,
            List<String> expectedOperations,
            Journey journey) throws Exception {
        runIsolated(temporaryDirectory, postgres, seed, Clock.systemUTC(), expectedOperations,
                java.util.Objects.requireNonNull(capabilitySourceFilter, "capabilitySourceFilter"),
                Map.of(), false, journey);
    }

    static void runTrustedProxy(
            Path temporaryDirectory,
            PostgreSQLContainer postgres,
            boolean seed,
            Journey journey) throws Exception {
        runIsolated(temporaryDirectory, postgres, seed, Clock.systemUTC(),
                List.of(currentScenario().operations()), null,
                Map.of("X-PeeGeeQ-User", "browser-operator",
                        "X-PeeGeeQ-Roles", "viewer,operator"),
                false, journey);
    }

    static void runIsolated(
            Path temporaryDirectory,
            PostgreSQLContainer postgres,
            boolean seed,
            List<String> expectedOperations,
            Journey journey) throws Exception {
        runIsolated(temporaryDirectory, postgres, seed, Clock.systemUTC(),
                expectedOperations, null, Map.of(), false, journey);
    }

    private static void runSelected(
            Path temporaryDirectory,
            PostgreSQLContainer postgres,
            boolean seed,
            List<String> expectedOperations,
            Journey journey) throws Exception {
        if (seed && canUseSharedSetup(expectedOperations)) {
            sharedEnvironment(temporaryDirectory, postgres).run(expectedOperations, journey);
            return;
        }
        runIsolated(temporaryDirectory, postgres, seed, Clock.systemUTC(),
                expectedOperations, null, Map.of(), seed && needsPreparedIsolatedSetup(expectedOperations), journey);
    }

    static boolean canUseSharedSetup(List<String> expectedOperations) {
        return expectedOperations.stream().noneMatch(ISOLATED_LIFECYCLE_OPERATIONS::contains);
    }

    private static boolean needsPreparedIsolatedSetup(List<String> expectedOperations) {
        return !expectedOperations.contains("testUnregisteredSetup")
                && !expectedOperations.contains("registerSetup");
    }

    private static synchronized SharedEnvironment sharedEnvironment(
            Path temporaryDirectory,
            PostgreSQLContainer postgres) throws Exception {
        SharedEnvironment current = SHARED_ENVIRONMENTS.get(postgres);
        if (current != null) return current;
        SharedEnvironment created = SharedEnvironment.start(temporaryDirectory, postgres);
        SHARED_ENVIRONMENTS.put(postgres, created);
        return created;
    }

    static synchronized void closeShared(PostgreSQLContainer postgres) throws Exception {
        SharedEnvironment environment = SHARED_ENVIRONMENTS.remove(postgres);
        if (environment != null) environment.close();
    }

    private static void runIsolated(
            Path temporaryDirectory,
            PostgreSQLContainer postgres,
            boolean seed,
            Clock clock,
            List<String> expectedOperations,
            UnaryOperator<SetupCapabilities.Source> capabilitySourceFilter,
            Map<String, String> trustedProxyHeaders,
            boolean prepareSetup,
            Journey journey) throws Exception {
        if (!postgres.isRunning()) {
            throw new IllegalStateException("Browser-worker PostgreSQL container is not running");
        }
        try (Environment environment = Environment.start(
                temporaryDirectory.resolve("management-browser-audit.jsonl"),
                postgres, seed, clock, capabilitySourceFilter, trustedProxyHeaders)) {
            environment.run(expectedOperations, journey, false, prepareSetup);
        }
    }

    private static final class SharedEnvironment implements AutoCloseable {
        private final Environment environment;

        private SharedEnvironment(Environment environment) {
            this.environment = environment;
        }

        private static SharedEnvironment start(
                Path temporaryDirectory,
                PostgreSQLContainer postgres) throws Exception {
            Path artifactDirectory = ManagementBrowserRunConfig.current().artifactDirectory();
            Path auditDirectory = artifactDirectory != null
                    ? artifactDirectory.resolve("shared-suite")
                    : temporaryDirectory.resolve("shared-suite");
            Files.createDirectories(auditDirectory);
            Path auditPath = auditDirectory.resolve(
                    "management-browser-audit-" + UUID.randomUUID() + ".jsonl");
            Environment environment = Environment.start(
                    auditPath, postgres, true, Clock.systemUTC(), null, Map.of());
            try {
                environment.run(List.of(), context -> {
                    authenticate(context);
                    registerSetup(context);
                }, true, false);
                return new SharedEnvironment(environment);
            } catch (Exception | Error failure) {
                environment.close();
                throw failure;
            }
        }

        private synchronized void run(
                List<String> expectedOperations,
                Journey journey) throws Exception {
            resetData(environment.postgres, true);
            environment.run(expectedOperations, journey, true, false);
        }

        @Override
        public synchronized void close() throws Exception {
            environment.close();
        }
    }

    private static final class Environment implements AutoCloseable {
        private final PostgreSQLContainer postgres;
        private final String origin;
        private final String bootstrapToken;
        private final Path auditPath;
        private final PrometheusMeterRegistry meters;
        private final ManagementServerApplication application;
        private final Browser browser;
        private final Map<String, String> trustedProxyHeaders;
        private List<Cookie> sessionCookies = List.of();

        private Environment(
                PostgreSQLContainer postgres,
                String origin,
                String bootstrapToken,
                Path auditPath,
                PrometheusMeterRegistry meters,
                ManagementServerApplication application,
                Browser browser,
                Map<String, String> trustedProxyHeaders) {
            this.postgres = postgres;
            this.origin = origin;
            this.bootstrapToken = bootstrapToken;
            this.auditPath = auditPath;
            this.meters = meters;
            this.application = application;
            this.browser = browser;
            this.trustedProxyHeaders = Map.copyOf(trustedProxyHeaders);
        }

        private static Environment start(
                Path auditPath,
                PostgreSQLContainer postgres,
                boolean seed,
                Clock clock,
                UnaryOperator<SetupCapabilities.Source> capabilitySourceFilter,
                Map<String, String> trustedProxyHeaders) throws Exception {
            Vertx migrationVertx = Vertx.vertx();
            try {
                migrate(migrationVertx, postgres, false);
            } finally {
                await(migrationVertx.close());
            }
            resetData(postgres, seed);

            Files.createDirectories(auditPath.toAbsolutePath().getParent());
            Files.deleteIfExists(auditPath);
            PrometheusMeterRegistry meters = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
            ManagementServerApplication application = null;
            try {
                int managementPort = freePort();
                String origin = "http://127.0.0.1:" + managementPort;
                ManagementSecretReference auditKey = new ManagementSecretReference("audit-key");
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
                application = capabilitySourceFilter == null
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
                         capabilitySourceFilter));
                String bootstrapToken = trustedProxy
                        ? ""
                        : application.takeBootstrapToken().orElseThrow();
                registerSensitiveCanaries(bootstrapToken);
                Browser browser = ManagementBrowserPlaywrightSuite.browser();
                return new Environment(
                        postgres, origin, bootstrapToken, auditPath, meters,
                        application, browser, trustedProxyHeaders);
            } catch (Exception | Error failure) {
                if (application != null) await(application.closeAsync());
                meters.close();
                throw failure;
            }
        }

        private void run(
                List<String> expectedOperations,
                Journey journey,
                boolean reuseSession,
                boolean prepareSetup) throws Exception {
            Browser.NewContextOptions options = new Browser.NewContextOptions()
                    .setExtraHTTPHeaders(trustedProxyHeaders);
            long auditStart = Files.exists(auditPath) ? Files.size(auditPath) : 0L;
            try (BrowserContext browserContext = browser.newContext(options)) {
                boolean preparedSharedScenario = reuseSession && !sessionCookies.isEmpty();
                if (preparedSharedScenario) {
                    browserContext.addCookies(sessionCookies);
                }
                if (reuseSession) {
                    browserContext.addInitScript("""
                            if (sessionStorage.getItem('peegeeq-cache.scope.v1') === null) {
                              sessionStorage.setItem(
                                'peegeeq-cache.scope.v1',
                                '{"setupId":"browser-postgres"}');
                            }
                            """);
                }
                Page page = browserContext.newPage();
                Diagnostics diagnostics = new Diagnostics();
                diagnostics.attach(page);
                ManagementBrowserOperationTrace operationTrace = new ManagementBrowserOperationTrace();
                operationTrace.attach(page);
                try {
                    Context context = new Context(
                            page,
                            browserContext,
                            postgres,
                            origin,
                            bootstrapToken,
                            auditPath,
                            diagnostics,
                            operationTrace);
                    if (preparedSharedScenario) {
                        authenticate(context);
                        com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat(
                                page.getByTitle("Active setup scope"))
                                .hasText("Setup: " + SETUP_ID);
                    } else if (prepareSetup) {
                        authenticate(context);
                        registerSetup(context);
                    }
                    journey.run(context);
                    diagnostics.assertNoBrowserErrors();
                    diagnostics.assertNoUnexpectedFailedResponses();
                    operationTrace.assertObservedExactly(expectedOperations, FIXTURE_OPERATIONS);
                    assertNoUndeclaredAuditedOperations(operationTrace.observed(), expectedOperations);
                    assertDurableAuditObserved(auditPath, auditStart, expectedOperations);
                    if (reuseSession) {
                        assertSharedSetupRegisteredOnce(auditPath);
                        cleanupActiveSubscription(page, browserContext);
                    }
                } catch (Exception | AssertionError failure) {
                    captureSanitizedFailureScreenshot(page);
                    if (reuseSession) cleanupActiveSubscriptionQuietly(page, browserContext);
                    throw failure;
                } finally {
                    if (reuseSession) {
                        sessionCookies = List.copyOf(browserContext.cookies());
                    }
                }
            }
        }

        private static void cleanupActiveSubscription(
                Page page,
                BrowserContext browserContext) {
            browserContext.setOffline(false);
            Locator stop = page.getByRole(
                    com.microsoft.playwright.options.AriaRole.BUTTON,
                    new Page.GetByRoleOptions().setName("Stop subscription").setExact(true));
            if (stop.count() == 0) return;
            stop.click(new Locator.ClickOptions().setTimeout(5_000));
            page.waitForCondition(
                    () -> stop.count() == 0,
                    new Page.WaitForConditionOptions().setTimeout(5_000));
        }

        private static void cleanupActiveSubscriptionQuietly(
                Page page,
                BrowserContext browserContext) {
            try {
                cleanupActiveSubscription(page, browserContext);
            } catch (RuntimeException ignored) {
                // Preserve the scenario's original failure and let suite shutdown close leftovers.
            }
        }

        @Override
        public void close() throws Exception {
            try {
                await(application.closeAsync());
                double leaked = meters.find("peegeeq.management.shutdown.leaked_resources")
                        .counter() == null
                        ? 0.0
                        : meters.find("peegeeq.management.shutdown.leaked_resources")
                        .counter().count();
                assertNoLeakedResources(leaked);
            } finally {
                meters.close();
            }
        }
    }

    private static void registerSensitiveCanaries(String bootstrapToken) {
        List.of(DATABASE_PASSWORD, "stored-value", "owner-secret", "bulk-secret",
                "pubsub-secret", "retained-value", "special-secret")
                .forEach(ManagementBrowserEvidenceListener::registerSensitiveCanary);
        ManagementBrowserEvidenceListener.registerSensitiveCanary(bootstrapToken);
    }

    private static void assertSharedSetupRegisteredOnce(Path auditPath) throws Exception {
        long registrations = Files.readString(auditPath).lines()
                .filter(line -> line.contains("\"action\":\"REGISTER_SETUP\""))
                .count();
        assertEquals(1L, registrations,
                "The shared browser fixture must register its setup exactly once per suite");
    }

    static void authenticate(Context context) {
        Page page = context.page();
        ManagementPlaywright.ScenarioPresentation presentation =
                ManagementPlaywright.beginScenario(page);
        try {
            assertEquals(200, page.navigate(context.origin() + "/ui/").status());
            var overview = page.getByRole(com.microsoft.playwright.options.AriaRole.HEADING,
                    new Page.GetByRoleOptions().setName("Overview").setExact(true));
            var bootstrapInput = page.getByLabel("Bootstrap token");
            page.waitForCondition(
                    () -> overview.count() > 0 || bootstrapInput.count() > 0,
                    new Page.WaitForConditionOptions().setTimeout(10_000));
            if (overview.count() > 0) {
                presentation.finish();
                return;
            }
            if (context.bootstrapToken().isBlank()) {
                com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat(page.getByRole(
                        com.microsoft.playwright.options.AriaRole.HEADING,
                        new Page.GetByRoleOptions().setName("Overview").setExact(true))).isVisible();
                presentation.finish();
                return;
            }
            bootstrapInput.fill(context.bootstrapToken());
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
        var existing = page.getByRole(com.microsoft.playwright.options.AriaRole.ROW)
                .filter(new com.microsoft.playwright.Locator.FilterOptions().setHasText(SETUP_NAME));
        var firstRegistration = page.getByRole(com.microsoft.playwright.options.AriaRole.BUTTON,
                new Page.GetByRoleOptions().setName("Register the first setup"));
        page.waitForCondition(
                () -> existing.count() > 0 || firstRegistration.count() > 0,
                new Page.WaitForConditionOptions().setTimeout(10_000));
        if (existing.count() > 0) {
            var activeScope = page.getByTitle("Active setup scope");
            com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat(activeScope)
                    .hasText("Setup: " + SETUP_ID);
            return;
        }
        firstRegistration.click();
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
        AntSelect.choose(page, dialog.getByLabel("Schema bootstrap"), "APPLY");
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
        assertDurableAuditObserved(auditPath, 0L, operations);
    }

    private static void assertDurableAuditObserved(
            Path auditPath,
            long startOffset,
            List<String> operations) throws Exception {
        List<String> expectedActions = operations.stream()
                .map(AUDITED_OPERATIONS::get)
                .filter(java.util.Objects::nonNull)
                .distinct()
                .toList();
        if (expectedActions.isEmpty()) return;
        byte[] completeAudit = Files.readAllBytes(auditPath);
        int safeOffset = (int) Math.min(Math.max(0L, startOffset), completeAudit.length);
        String audit = new String(
                completeAudit, safeOffset, completeAudit.length - safeOffset, StandardCharsets.UTF_8);
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
            if (seed) seedData(pool);
        } finally {
            await(pool.close());
        }
    }

    private static void resetData(PostgreSQLContainer postgres, boolean seed) throws Exception {
        Vertx vertx = Vertx.vertx();
        Pool pool = Pool.pool(vertx, connectOptions(postgres), new PoolOptions().setMaxSize(1));
        try {
            await(pool.query("""
                    TRUNCATE TABLE
                        peegee_cache.cache_entries,
                        peegee_cache.cache_counters,
                        peegee_cache.cache_locks;
                    ALTER SEQUENCE peegee_cache.lock_version_seq RESTART WITH 1;
                    ALTER SEQUENCE peegee_cache.lock_fencing_seq RESTART WITH 1;
                    """).execute());
            if (seed) seedData(pool);
        } finally {
            await(pool.close());
            await(vertx.close());
        }
    }

    private static void seedData(Pool pool) throws Exception {
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
