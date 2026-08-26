package dev.mars.peegeeq.cache.rest.server;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.options.AriaRole;
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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.MountableFile;

import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/** Packaged-console acceptance against the real TLS PostgreSQL runtime. */
class ManagementConsoleSetupLifecycleIT {

    private static final String DATABASE_PASSWORD = "test-password";

    @TempDir
    Path temporaryDirectory;

    @Test
    void packagedConsoleOwnsRealPostgresSetupLifecycleWithoutPersistingThePassword() throws Exception {
        PostgreSQLContainer postgres = postgres();
        Vertx migrationVertx = null;
        ManagementServerApplication application = null;
        PrometheusMeterRegistry meters = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        try {
            postgres.start();
            Buffer serverCertificate = certificate();
            migrationVertx = Vertx.vertx();
            migrate(migrationVertx, postgres);

            int managementPort = freePort();
            String origin = "http://127.0.0.1:" + managementPort;
            ManagementSecretReference auditKey = new ManagementSecretReference("audit-key");
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
                    temporaryDirectory.resolve("setup-browser-audit.jsonl"),
                    auditKey);
            InetAddress databaseAddress = InetAddress.getByName(postgres.getHost());
            application = await(ManagementServerApplication.start(
                    configuration,
                    reference -> reference.equals(auditKey) ? new byte[32] : null,
                    ignored -> List.of(databaseAddress),
                    trustProfile -> trustProfile.equals("test-ca") ? serverCertificate : null,
                    meters));

            verifyLifecycle(origin, application.takeBootstrapToken().orElseThrow(), postgres);
        } finally {
            if (application != null) await(application.closeAsync());
            if (migrationVertx != null) await(migrationVertx.close());
            meters.close();
            postgres.stop();
        }
    }

    private static void verifyLifecycle(
            String origin,
            String bootstrapToken,
            PostgreSQLContainer postgres) {
        List<String> browserErrors = new ArrayList<>();
        List<String> failedResponses = new ArrayList<>();
        try (Playwright playwright = Playwright.create(new Playwright.CreateOptions()
                .setEnv(Map.of("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD", "1")));
             Browser browser = playwright.chromium().launch(ManagementPlaywright.launchOptions());
             BrowserContext context = browser.newContext()) {
            Page page = context.newPage();
            page.onConsoleMessage(message -> {
                if (message.type().equals("error")
                        && !message.text().startsWith("Failed to load resource:")) {
                    browserErrors.add(message.text());
                }
            });
            page.onPageError(browserErrors::add);
            page.onResponse(response -> {
                if (response.status() >= 400) {
                    failedResponses.add(response.status() + " " + URI.create(response.url()).getPath());
                }
            });

            assertEquals(200, page.navigate(origin + "/ui/").status());
            page.getByLabel("Bootstrap token").fill(bootstrapToken);
            page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Connect")).click();
            assertThat(page.getByRole(
                    AriaRole.HEADING,
                    new Page.GetByRoleOptions().setName("Overview").setExact(true))).isVisible();

            page.getByRole(AriaRole.LINK, new Page.GetByRoleOptions().setName("Setups")).click();
            assertThat(page.getByRole(
                    AriaRole.HEADING,
                    new Page.GetByRoleOptions().setName("No setups registered"))).isVisible();
            page.getByRole(
                    AriaRole.BUTTON,
                    new Page.GetByRoleOptions().setName("Register the first setup")).click();

            Locator registration = page.getByRole(
                    AriaRole.DIALOG,
                    new Page.GetByRoleOptions().setName("Register setup"));
            registration.getByLabel("Setup ID").fill("browser-postgres");
            registration.getByLabel("Display name").fill("Real PostgreSQL");
            registration.getByLabel("Host").fill("db.internal.example");
            registration.getByLabel("Port").fill(String.valueOf(postgres.getMappedPort(5432)));
            registration.getByLabel("Database").fill("peegeeq");
            registration.getByLabel("Schema").fill("peegee_cache");
            registration.getByLabel("Username").fill("peegeeq");
            registration.getByLabel("Password").fill(DATABASE_PASSWORD);
            registration.getByLabel("Trust profile").fill("test-ca");
            registration.getByLabel("Pool size").fill("3");

            registration.getByRole(
                    AriaRole.BUTTON,
                    new Locator.GetByRoleOptions().setName("Test connection")).click();
            assertThat(registration.getByRole(AriaRole.STATUS)).containsText("Connection succeeded");
            registration.getByRole(
                    AriaRole.BUTTON,
                    new Locator.GetByRoleOptions().setName("Register setup")).click();

            assertThat(page.getByRole(AriaRole.STATUS))
                    .containsText("Real PostgreSQL was registered and selected");
            assertThat(page.getByTitle("Active setup scope")).hasText("Setup: browser-postgres");
            Locator row = setupRow(page);
            assertThat(row).containsText("Connected");
            assertFalse(page.content().contains(DATABASE_PASSWORD));
            assertEquals(0, ((Number) page.evaluate("localStorage.length")).intValue());
            assertEquals(
                    "{\"setupId\":\"browser-postgres\"}",
                    page.evaluate("sessionStorage.getItem('peegeeq-cache.scope.v1')"));

            row.getByRole(
                    AriaRole.BUTTON,
                    new Locator.GetByRoleOptions().setName("Details")).click();
            Locator details = page.getByRole(
                    AriaRole.DIALOG,
                    new Page.GetByRoleOptions().setName("Setup details"));
            assertThat(details.getByRole(
                    AriaRole.HEADING,
                    new Locator.GetByRoleOptions().setName("Database health"))).isVisible();
            assertThat(details).containsText("Database reachable and schema ready");
            details.getByRole(
                    AriaRole.BUTTON,
                    new Locator.GetByRoleOptions().setName("Close details")).click();

            page.getByRole(AriaRole.LINK, new Page.GetByRoleOptions().setName("Overview")).click();
            assertThat(page.getByText("Database-wide snapshot")).isVisible();
            assertThat(metric(page, "Live cache entries")).containsText("1");
            assertThat(metric(page, "Live counters")).containsText("1");
            assertThat(metric(page, "Active locks")).containsText("1");
            assertThat(page.getByText("logical-orders")).isVisible();

            page.getByRole(AriaRole.LINK, new Page.GetByRoleOptions().setName("Namespaces")).click();
            Locator namespaceRow = page.getByRole(AriaRole.ROW)
                    .filter(new Locator.FilterOptions().setHasText("logical-orders"));
            assertThat(namespaceRow).containsText("1");
            namespaceRow.getByRole(
                    AriaRole.LINK,
                    new Locator.GetByRoleOptions().setName("logical-orders")).click();
            assertThat(page.getByRole(
                    AriaRole.HEADING,
                    new Page.GetByRoleOptions().setName("logical-orders").setExact(true))).isVisible();
            assertThat(page.getByLabel("Namespace totals")).containsText("Live entries1");
            assertEquals(
                    "{\"setupId\":\"browser-postgres\",\"namespace\":\"logical-orders\"}",
                    page.evaluate("sessionStorage.getItem('peegeeq-cache.scope.v1')"));

            page.getByRole(AriaRole.LINK, new Page.GetByRoleOptions().setName("Setups")).click();

            confirmRowAction(page, "Detach");
            assertThat(setupRow(page)).containsText("Detached");
            assertThat(page.getByTitle("Active setup scope")).hasText("No setup selected");
            assertEquals(0, ((Number) page.evaluate("sessionStorage.length")).intValue());

            confirmRowAction(page, "Connect");
            row = setupRow(page);
            assertThat(row).containsText("Connected");
            row.getByRole(
                    AriaRole.BUTTON,
                    new Locator.GetByRoleOptions().setName("Use setup")).click();
            assertThat(page.getByTitle("Active setup scope")).hasText("Setup: browser-postgres");

            confirmRowAction(page, "Forget");
            assertThat(page.getByRole(
                    AriaRole.HEADING,
                    new Page.GetByRoleOptions().setName("No setups registered"))).isVisible();
            assertThat(page.getByTitle("Active setup scope")).hasText("No setup selected");
            assertEquals(0, ((Number) page.evaluate("sessionStorage.length")).intValue());
            assertFalse(page.url().contains(bootstrapToken));
            assertFalse(page.content().contains(bootstrapToken));
            assertEquals(List.of("401 /api/v1/session"), failedResponses);
            assertEquals(List.of(), browserErrors);
        }
    }

    private static void confirmRowAction(Page page, String action) {
        setupRow(page).getByRole(
                AriaRole.BUTTON,
                new Locator.GetByRoleOptions().setName(action).setExact(true)).click();
        Locator dialog = page.getByRole(
                AriaRole.DIALOG,
                new Page.GetByRoleOptions().setName(action + " Real PostgreSQL?"));
        dialog.getByRole(
                AriaRole.BUTTON,
                new Locator.GetByRoleOptions().setName(action).setExact(true)).click();
    }

    private static Locator setupRow(Page page) {
        return page.getByRole(AriaRole.ROW)
                .filter(new Locator.FilterOptions().setHasText("Real PostgreSQL"));
    }

    private static Locator metric(Page page, String label) {
        return page.locator(".metric-card")
                .filter(new Locator.FilterOptions().setHasText(label));
    }

    private static PostgreSQLContainer postgres() {
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
        try (var input = ManagementConsoleSetupLifecycleIT.class.getResourceAsStream(
                "/postgres-tls-server.crt")) {
            return Buffer.buffer(input.readAllBytes());
        }
    }

    private static void migrate(Vertx vertx, PostgreSQLContainer postgres) throws Exception {
        Pool pool = Pool.pool(vertx, new PgConnectOptions()
                .setHost(postgres.getHost())
                .setPort(postgres.getMappedPort(5432))
                .setDatabase(postgres.getDatabaseName())
                .setUser(postgres.getUsername())
                .setPassword(postgres.getPassword()), new PoolOptions().setMaxSize(1));
        try {
            await(new PgSchemaMigrator(pool, "peegee_cache").migrate());
            await(pool.withConnection(connection -> connection.query("""
                    INSERT INTO peegee_cache.cache_entries
                        (namespace, cache_key, value_type, value_bytes, version)
                    VALUES ('logical-orders', 'customer:1', 'STRING',
                            convert_to('stored-value', 'UTF8'), 3)
                    """).execute().compose(ignored -> connection.query("""
                    INSERT INTO peegee_cache.cache_counters
                        (namespace, counter_key, counter_value, version)
                    VALUES ('logical-orders', 'count', 42, 4)
                    """).execute()).compose(ignored -> connection.query("""
                    INSERT INTO peegee_cache.cache_locks
                        (namespace, lock_key, owner_token, fencing_token, version, lease_expires_at)
                    VALUES ('logical-orders', 'lease', 'owner-secret', 7, 5,
                            NOW() + INTERVAL '1 hour')
                    """).execute())));
        } finally {
            await(pool.close());
        }
    }

    private static <T> T await(Future<T> future) throws Exception {
        return future.toCompletionStage().toCompletableFuture().get(60, TimeUnit.SECONDS);
    }

    private static int freePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            return socket.getLocalPort();
        }
    }
}
