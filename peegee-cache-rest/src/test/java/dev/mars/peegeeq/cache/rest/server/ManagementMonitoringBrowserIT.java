package dev.mars.peegeeq.cache.rest.server;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.AriaRole;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.IntStream;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/** Wave P5 real-browser scenarios for monitoring, activity, notifications, and settings. */
class ManagementMonitoringBrowserIT {

    @RegisterExtension
    static final ManagementBrowserPostgresWorker POSTGRES = new ManagementBrowserPostgresWorker();

    @TempDir
    Path temporaryDirectory;

    private static final List<String> ACTIONS = List.of(
            "identify database-wide and server-local telemetry scope",
            "render the Monitoring workspace heading",
            "connect the live metrics stream",
            "offer an explicit monitoring refresh",
            "complete an explicit monitoring refresh",
            "render the database-monitoring section",
            "expose a machine-readable database observation instant",
            "render database storage telemetry",
            "report table storage",
            "report index storage",
            "report total schema storage",
            "render database row and maintenance telemetry",
            "report live and expired database rows",
            "report dead tuples and expiry backlog",
            "render database connection telemetry",
            "report all and PeeGeeQ Cache database connections",
            "render management-runtime telemetry",
            "report lifecycle and active operations",
            "report Pub/Sub, SSE, and WebSocket resource gauges",
            "render management-pool telemetry",
            "render audit and expiry telemetry",
            "render bounded recent activity",
            "identify session and display configuration",
            "report the active setup in connection details",
            "report the effective maximum cache-value size",
            "report the effective Pub/Sub payload limit",
            "report the effective Pub/Sub channel limit",
            "report the active migration version",
            "persist the selected monitoring refresh interval",
            "persist the masked-value auto-hide preference",
            "persist UTC display preference without credentials",
            "state the browser-storage privacy boundary");

    static List<ManagementBrowserCase> scenarios() {
        return IntStream.range(0, ACTIONS.size())
                .mapToObj(index -> new ManagementBrowserCase(
                        "PW-MONITOR-%03d".formatted(index + 2),
                        "P5 monitoring/settings contract: " + ACTIONS.get(index),
                        index < 22 ? ManagementBrowserArea.MONITORING : ManagementBrowserArea.SHELL,
                        index < 22 ? ManagementBrowserRisk.HIGH : ManagementBrowserRisk.MEDIUM,
                        ACTIONS.get(index),
                        "The packaged console must " + ACTIONS.get(index) + ".",
                        "Clear browser preferences, close live transports, reset PostgreSQL, and close browser resources",
                        index < 22
                                ? List.of("getDatabaseMonitoring", "getRuntimeMonitoring", "streamMetrics", "listActivity")
                                : List.of("getSetupCapabilities"),
                        Set.of(
                                ManagementBrowserEvidence.VISIBLE_RESULT,
                                ManagementBrowserEvidence.HTTP_OPERATION,
                                ManagementBrowserEvidence.DATABASE,
                                ManagementBrowserEvidence.RESOURCE_CLEANUP)))
                .toList();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("scenarios")
    void monitoringAndSettingsScenario(ManagementBrowserCase scenario) throws Exception {
        int index = Integer.parseInt(scenario.id().substring(scenario.id().length() - 3)) - 2;
        ManagementConsolePostgresFixture.run(
                temporaryDirectory,
                POSTGRES.postgres(),
                true,
                scenario.operations(),
                context -> {
                    ManagementConsolePostgresFixture.authenticate(context);
                    ManagementConsolePostgresFixture.registerSetup(context);
                    Page page = context.page();
                    open(page, index < 22 ? "Monitoring" : "Settings");
                    verify(index, page);
                });
    }

    private static void verify(int index, Page page) {
        switch (index) {
            case 0 -> assertThat(page.getByText(
                    "Database-wide and management-server-local telemetry", exact())).isVisible();
            case 1 -> heading(page, "Monitoring");
            case 2 -> assertThat(page.getByText("Live metrics connected", exact())).isVisible();
            case 3 -> assertThat(page.getByRole(AriaRole.BUTTON,
                    new Page.GetByRoleOptions().setName("Refresh monitoring"))).isEnabled();
            case 4 -> {
                page.getByRole(AriaRole.BUTTON,
                        new Page.GetByRoleOptions().setName("Refresh monitoring")).click();
                assertThat(page.getByRole(AriaRole.BUTTON,
                        new Page.GetByRoleOptions().setName("Refresh monitoring"))).isEnabled();
            }
            case 5 -> heading(page, "Database monitoring");
            case 6 -> assertThat(section(page, "Database monitoring").locator("time").first())
                    .hasAttribute("datetime", java.util.regex.Pattern.compile("^\\d{4}-\\d{2}-\\d{2}T"));
            case 7 -> heading(page, "Database storage");
            case 8 -> detail(page, "Table storage");
            case 9 -> detail(page, "Index storage");
            case 10 -> detail(page, "Total schema storage");
            case 11 -> heading(page, "Database rows and maintenance");
            case 12 -> {
                detail(page, "Live rows");
                detail(page, "Expired rows");
            }
            case 13 -> {
                detail(page, "Dead tuples");
                detail(page, "Expiry backlog");
            }
            case 14 -> heading(page, "Database connections");
            case 15 -> {
                detail(page, "All database connections");
                detail(page, "PeeGeeQ Cache connections");
            }
            case 16 -> heading(page, "Management runtime");
            case 17 -> {
                detail(page, "Lifecycle");
                detail(page, "Active operations");
            }
            case 18 -> {
                detail(page, "Pub/Sub subscriptions");
                detail(page, "SSE clients");
                detail(page, "WebSocket clients");
            }
            case 19 -> heading(page, "Management pool");
            case 20 -> heading(page, "Audit and expiry");
            case 21 -> heading(page, "Recent activity");
            case 22 -> assertThat(page.getByText("Session and display configuration", exact())).isVisible();
            case 23 -> assertDetailValue(page, "Active setup", ManagementConsolePostgresFixture.SETUP_ID);
            case 24 -> assertDetailValue(page, "Maximum value bytes", "10485760");
            case 25 -> assertDetailValue(page, "Pub/Sub payload bytes", "7500");
            case 26 -> assertDetailValue(page, "Pub/Sub channel bytes", "49");
            case 27 -> assertThat(detailRow(page, "Migration version").locator("dd")).not().hasText("");
            case 28 -> selectAndVerifyStored(page, "Refresh interval", "60", "refreshSeconds", 60);
            case 29 -> selectAndVerifyStored(page, "Masked-value auto-hide", "120", "autoHideSeconds", 120);
            case 30 -> {
                selectAndVerifyStored(page, "Timezone", "UTC", "timezone", "UTC");
                String storage = (String) page.evaluate(
                        "localStorage.getItem('peegeeq.management.preferences')");
                assertFalse(storage.contains(ManagementConsolePostgresFixture.DATABASE_PASSWORD));
                assertFalse(storage.contains("bootstrap"));
            }
            case 31 -> assertThat(page.getByText(
                    "Only display preferences are stored. Credentials, revealed values, owner tokens, payloads, setup secrets, and live notifications are never persisted.",
                    exact())).isVisible();
            default -> throw new IllegalArgumentException("Unknown monitoring/settings scenario " + index);
        }
    }

    private static void open(Page page, String name) {
        page.getByRole(AriaRole.LINK, new Page.GetByRoleOptions().setName(name).setExact(true)).click();
        heading(page, name);
    }

    private static void heading(Page page, String name) {
        assertThat(page.getByRole(AriaRole.HEADING,
                new Page.GetByRoleOptions().setName(name).setExact(true))).isVisible();
    }

    private static Locator section(Page page, String heading) {
        return page.locator("section").filter(new Locator.FilterOptions().setHas(
                page.getByRole(AriaRole.HEADING,
                        new Page.GetByRoleOptions().setName(heading).setExact(true)))).first();
    }

    private static void detail(Page page, String label) {
        Locator row = detailRow(page, label);
        assertThat(row.locator("dt")).hasText(label);
        assertThat(row.locator("dd")).not().hasText("");
    }

    private static Locator detailRow(Page page, String label) {
        return page.locator("dl > div").filter(new Locator.FilterOptions().setHas(
                page.locator("dt").filter(new Locator.FilterOptions().setHasText(label)))).first();
    }

    private static void assertDetailValue(Page page, String label, String value) {
        assertThat(detailRow(page, label).locator("dd")).hasText(value);
    }

    private static void selectAndVerifyStored(
            Page page,
            String label,
            String value,
            String property,
            Object expected) {
        page.getByLabel(label).selectOption(value);
        assertThat(page.getByRole(AriaRole.STATUS)).containsText("Display preferences saved");
        Object stored = page.evaluate(
                "([key]) => JSON.parse(localStorage.getItem('peegeeq.management.preferences'))[key]",
                List.of(property));
        assertEquals(expected, stored);
    }

    private static Page.GetByTextOptions exact() {
        return new Page.GetByTextOptions().setExact(true);
    }
}
