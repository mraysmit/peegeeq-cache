package dev.mars.peegeeq.cache.rest.server;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import dev.mars.peegeeq.cache.api.management.ManagementSecretReference;
import dev.mars.peegeeq.cache.pg.bootstrap.PgSchemaMigrator;
import dev.mars.peegeeq.cache.rest.security.SetupTargetPolicy;
import dev.mars.peegeeq.cache.test.PostgreSQLTestConstants;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.PoolOptions;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.MountableFile;

import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/** Real packaged-console fixture. It deliberately provides no HTTP or browser route mocks. */
final class ManagementConsolePostgresFixture {

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
            ManagementServerConfiguration configuration = ManagementServerConfiguration.localToken(
                    "127.0.0.1",
                    managementPort,
                    origin,
                    new SetupTargetPolicy(
                            Set.of("internal.example"),
                            Set.of("127.0.0.0/8"),
                            Set.of(postgres.getMappedPort(5432)),
                            true,
                            false,
                            false,
                            false,
                            Set.of("test-ca")),
                    auditPath,
                    auditKey);
            InetAddress databaseAddress = InetAddress.getByName(postgres.getHost());
            Buffer serverCertificate = certificate();
            application = await(ManagementServerApplication.start(
                    configuration,
                    reference -> reference.equals(auditKey) ? new byte[32] : null,
                    ignored -> List.of(databaseAddress),
                    trustProfile -> trustProfile.equals("test-ca") ? serverCertificate : null,
                    meters,
                    clock));

            String bootstrapToken = application.takeBootstrapToken().orElseThrow();
            try (Playwright playwright = Playwright.create(new Playwright.CreateOptions()
                    .setEnv(Map.of("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD", "1")));
                 Browser browser = playwright.chromium().launch(ManagementPlaywright.launchOptions());
                 BrowserContext browserContext = browser.newContext()) {
                Page page = browserContext.newPage();
                Diagnostics diagnostics = new Diagnostics();
                diagnostics.attach(page);
                ManagementBrowserOperationTrace operationTrace = new ManagementBrowserOperationTrace();
                operationTrace.attach(page);
                journey.run(new Context(
                        page,
                        browserContext,
                        postgres,
                        origin,
                        bootstrapToken,
                        auditPath,
                        diagnostics,
                        operationTrace));
                diagnostics.assertNoBrowserErrors();
                operationTrace.assertObserved(expectedOperations.toArray(String[]::new));
            }
        } finally {
            if (application != null) {
                await(application.closeAsync());
                double leaked = meters.find("peegeeq.management.shutdown.leaked_resources")
                        .counter() == null
                        ? 0.0
                        : meters.find("peegeeq.management.shutdown.leaked_resources").counter().count();
                assertEquals(0.0, leaked, "Management shutdown reported leaked resource categories");
            }
            if (migrationVertx != null) {
                await(migrationVertx.close());
            }
            meters.close();
        }
    }

    static void authenticate(Context context) {
        Page page = context.page();
        assertEquals(200, page.navigate(context.origin() + "/ui/").status());
        page.getByLabel("Bootstrap token").fill(context.bootstrapToken());
        page.getByRole(com.microsoft.playwright.options.AriaRole.BUTTON,
                new Page.GetByRoleOptions().setName("Connect")).click();
        com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat(page.getByRole(
                com.microsoft.playwright.options.AriaRole.HEADING,
                new Page.GetByRoleOptions().setName("Overview").setExact(true))).isVisible();
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
        dialog.getByLabel("Schema").fill("peegee_cache");
        dialog.getByLabel("Username").fill("peegeeq");
        dialog.getByLabel("Password").fill(DATABASE_PASSWORD);
        dialog.getByLabel("Trust profile").fill("test-ca");
        dialog.getByLabel("Pool size").fill("3");
        dialog.getByRole(com.microsoft.playwright.options.AriaRole.BUTTON,
                new com.microsoft.playwright.Locator.GetByRoleOptions().setName("Test connection")).click();
        com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat(
                dialog.getByRole(com.microsoft.playwright.options.AriaRole.STATUS))
                .containsText("Connection succeeded");
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
        return new PostgreSQLContainer(PostgreSQLTestConstants.postgresImage())
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
                    failedResponses.add(response.status() + " " + path);
                }
            });
        }

        List<String> failedResponses() {
            return List.copyOf(failedResponses);
        }

        void assertNoBrowserErrors() {
            assertEquals(List.of(), browserErrors, "Browser console/page errors");
        }
    }
}
