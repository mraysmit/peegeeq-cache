package dev.mars.peegeeq.cache.rest.server;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.AriaRole;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/** Wave P2 real-browser scenarios for database-wide overview snapshots. */
class ManagementOverviewBrowserIT {

    @RegisterExtension
    static final ManagementBrowserPostgresWorker POSTGRES = new ManagementBrowserPostgresWorker();

    @TempDir
    Path temporaryDirectory;

    @ManagementBrowserScenario(id = "PW-OVERVIEW-002", requirement = "UI design: overview snapshots expose a machine-readable observation instant", area = ManagementBrowserArea.OVERVIEW, risk = ManagementBrowserRisk.HIGH, action = "Open Overview for the seeded setup and inspect its observed time", expectedResult = "The visible snapshot time has a UTC datetime value", cleanup = "Close the scoped context and reset PostgreSQL", operations = {"getOverview", "getDatabaseMonitoring", "getRuntimeMonitoring", "listActivity"}, evidence = {ManagementBrowserEvidence.VISIBLE_RESULT, ManagementBrowserEvidence.HTTP_OPERATION, ManagementBrowserEvidence.DATABASE, ManagementBrowserEvidence.RESOURCE_CLEANUP})
    @Test
    void snapshotExposesUtcObservationInstant() throws Exception {
        overview(context -> {
            Locator time = context.page().getByLabel("Snapshot observed at");
            assertThat(time).isVisible();
            String dateTime = time.getAttribute("datetime");
            assertNotNull(dateTime);
            assertEquals(true, dateTime.endsWith("Z"));
        });
    }

    @ManagementBrowserScenario(id = "PW-OVERVIEW-003", requirement = "Management API: overview reports setup health from the real database", area = ManagementBrowserArea.OVERVIEW, risk = ManagementBrowserRisk.CRITICAL, action = "Inspect Setup status on the seeded overview", expectedResult = "The setup health is Up", cleanup = "Close the scoped context and reset PostgreSQL", operations = {"getOverview", "getDatabaseMonitoring", "getRuntimeMonitoring", "listActivity"}, evidence = {ManagementBrowserEvidence.VISIBLE_RESULT, ManagementBrowserEvidence.HTTP_OPERATION, ManagementBrowserEvidence.DATABASE, ManagementBrowserEvidence.RESOURCE_CLEANUP})
    @Test
    void overviewReportsSetupUp() throws Exception {
        overview(context -> assertThat(metric(context.page(), "Setup status")).containsText("Up"));
    }

    @ManagementBrowserScenario(id = "PW-OVERVIEW-004", requirement = "UI design: overview distinguishes health latency from status", area = ManagementBrowserArea.OVERVIEW, risk = ManagementBrowserRisk.MEDIUM, action = "Inspect the Setup status detail", expectedResult = "Round-trip latency is presented in milliseconds", cleanup = "Close the scoped context and reset PostgreSQL", operations = {"getOverview", "getDatabaseMonitoring", "getRuntimeMonitoring", "listActivity"}, evidence = {ManagementBrowserEvidence.VISIBLE_RESULT, ManagementBrowserEvidence.HTTP_OPERATION, ManagementBrowserEvidence.DATABASE, ManagementBrowserEvidence.RESOURCE_CLEANUP})
    @Test
    void overviewShowsHealthRoundTripLatency() throws Exception {
        overview(context -> assertThat(metric(context.page(), "Setup status")).containsText("ms round trip"));
    }

    @ManagementBrowserScenario(id = "PW-OVERVIEW-005", requirement = "Management API: overview live-entry total is database truth", area = ManagementBrowserArea.OVERVIEW, risk = ManagementBrowserRisk.CRITICAL, action = "Inspect Live cache entries after seeding four rows", expectedResult = "The exact total is 4", cleanup = "Close the scoped context and reset PostgreSQL", operations = {"getOverview", "getDatabaseMonitoring", "getRuntimeMonitoring", "listActivity"}, evidence = {ManagementBrowserEvidence.VISIBLE_RESULT, ManagementBrowserEvidence.HTTP_OPERATION, ManagementBrowserEvidence.DATABASE, ManagementBrowserEvidence.RESOURCE_CLEANUP})
    @Test
    void overviewShowsFourLiveEntries() throws Exception {
        overview(context -> assertThat(metric(context.page(), "Live cache entries")).containsText("4"));
    }

    @ManagementBrowserScenario(id = "PW-OVERVIEW-006", requirement = "Management API: overview counter total is database truth", area = ManagementBrowserArea.OVERVIEW, risk = ManagementBrowserRisk.CRITICAL, action = "Inspect Live counters after seeding one counter", expectedResult = "The exact total is 1", cleanup = "Close the scoped context and reset PostgreSQL", operations = {"getOverview", "getDatabaseMonitoring", "getRuntimeMonitoring", "listActivity"}, evidence = {ManagementBrowserEvidence.VISIBLE_RESULT, ManagementBrowserEvidence.HTTP_OPERATION, ManagementBrowserEvidence.DATABASE, ManagementBrowserEvidence.RESOURCE_CLEANUP})
    @Test
    void overviewShowsOneLiveCounter() throws Exception {
        overview(context -> assertThat(metric(context.page(), "Live counters")).containsText("1"));
    }

    @ManagementBrowserScenario(id = "PW-OVERVIEW-007", requirement = "Management API: overview active-lock total excludes expired leases", area = ManagementBrowserArea.OVERVIEW, risk = ManagementBrowserRisk.CRITICAL, action = "Inspect Active locks after seeding one active lease", expectedResult = "The exact active-lock total is 1", cleanup = "Close the scoped context and reset PostgreSQL", operations = {"getOverview", "getDatabaseMonitoring", "getRuntimeMonitoring", "listActivity"}, evidence = {ManagementBrowserEvidence.VISIBLE_RESULT, ManagementBrowserEvidence.HTTP_OPERATION, ManagementBrowserEvidence.DATABASE, ManagementBrowserEvidence.RESOURCE_CLEANUP})
    @Test
    void overviewShowsOneActiveLock() throws Exception {
        overview(context -> assertThat(metric(context.page(), "Active locks")).containsText("1"));
    }

    @ManagementBrowserScenario(id = "PW-OVERVIEW-008", requirement = "Management API: zero expired rows is an exact available value", area = ManagementBrowserArea.OVERVIEW, risk = ManagementBrowserRisk.HIGH, action = "Inspect Expired rows awaiting cleanup", expectedResult = "The exact value is 0 rather than Unavailable", cleanup = "Close the scoped context and reset PostgreSQL", operations = {"getOverview", "getDatabaseMonitoring", "getRuntimeMonitoring", "listActivity"}, evidence = {ManagementBrowserEvidence.VISIBLE_RESULT, ManagementBrowserEvidence.HTTP_OPERATION, ManagementBrowserEvidence.DATABASE, ManagementBrowserEvidence.RESOURCE_CLEANUP})
    @Test
    void overviewDistinguishesZeroExpiredRowsFromUnavailable() throws Exception {
        overview(context -> {
            Locator card = metric(context.page(), "Expired rows awaiting cleanup");
            assertThat(card).containsText("0");
            assertThat(card).not().containsText("Unavailable");
        });
    }

    @ManagementBrowserScenario(id = "PW-OVERVIEW-009", requirement = "Management API: available schema storage is formatted as bytes", area = ManagementBrowserArea.OVERVIEW, risk = ManagementBrowserRisk.HIGH, action = "Inspect Cache schema storage", expectedResult = "A non-zero binary byte value is visible rather than Unavailable", cleanup = "Close the scoped context and reset PostgreSQL", operations = {"getOverview", "getDatabaseMonitoring", "getRuntimeMonitoring", "listActivity"}, evidence = {ManagementBrowserEvidence.VISIBLE_RESULT, ManagementBrowserEvidence.HTTP_OPERATION, ManagementBrowserEvidence.DATABASE, ManagementBrowserEvidence.RESOURCE_CLEANUP})
    @Test
    void overviewShowsAvailableSchemaStorage() throws Exception {
        overview(context -> {
            Locator card = metric(context.page(), "Cache schema storage");
            assertThat(card).not().containsText("Unavailable");
            assertThat(card.locator("strong")).containsText("KiB");
        });
    }

    @ManagementBrowserScenario(id = "PW-OVERVIEW-010", requirement = "UI design: overview makes database-wide scope explicit", area = ManagementBrowserArea.OVERVIEW, risk = ManagementBrowserRisk.HIGH, action = "Read the overview scope notice", expectedResult = "The UI distinguishes database-wide from server-local panels", cleanup = "Close the scoped context and reset PostgreSQL", operations = {"getOverview", "getDatabaseMonitoring", "getRuntimeMonitoring", "listActivity"}, evidence = {ManagementBrowserEvidence.VISIBLE_RESULT, ManagementBrowserEvidence.HTTP_OPERATION, ManagementBrowserEvidence.RESOURCE_CLEANUP})
    @Test
    void overviewExplainsDatabaseWideScope() throws Exception {
        overview(context -> assertThat(context.page().getByText("Values are database-wide unless a panel is explicitly labelled management-server-local.")).isVisible());
    }

    @ManagementBrowserScenario(id = "PW-OVERVIEW-011", requirement = "Management API: overview reports effective sweeper ownership", area = ManagementBrowserArea.OVERVIEW, risk = ManagementBrowserRisk.HIGH, action = "Inspect Expiry and cleanup Sweeper", expectedResult = "The fixture reports Enabled", cleanup = "Close the scoped context and reset PostgreSQL", operations = {"getOverview", "getDatabaseMonitoring", "getRuntimeMonitoring", "listActivity"}, evidence = {ManagementBrowserEvidence.VISIBLE_RESULT, ManagementBrowserEvidence.HTTP_OPERATION, ManagementBrowserEvidence.DATABASE, ManagementBrowserEvidence.RESOURCE_CLEANUP})
    @Test
    void overviewShowsSweeperEnabled() throws Exception {
        overview(context -> assertThat(section(context.page(), "Expiry and cleanup")).containsText("SweeperEnabled"));
    }

    @ManagementBrowserScenario(id = "PW-OVERVIEW-012", requirement = "Management API: absent expiry backlog has an exact zero-duration value", area = ManagementBrowserArea.OVERVIEW, risk = ManagementBrowserRisk.MEDIUM, action = "Inspect Oldest backlog lag", expectedResult = "The value is 0 ms", cleanup = "Close the scoped context and reset PostgreSQL", operations = {"getOverview", "getDatabaseMonitoring", "getRuntimeMonitoring", "listActivity"}, evidence = {ManagementBrowserEvidence.VISIBLE_RESULT, ManagementBrowserEvidence.HTTP_OPERATION, ManagementBrowserEvidence.DATABASE, ManagementBrowserEvidence.RESOURCE_CLEANUP})
    @Test
    void overviewShowsZeroOldestBacklogLag() throws Exception {
        overview(context -> assertThat(section(context.page(), "Expiry and cleanup")).containsText("Oldest backlog lag0 ms"));
    }

    @ManagementBrowserScenario(id = "PW-OVERVIEW-013", requirement = "Management API: no sweep deletions is represented as exact zero", area = ManagementBrowserArea.OVERVIEW, risk = ManagementBrowserRisk.MEDIUM, action = "Inspect Last sweep deleted", expectedResult = "The exact value is 0", cleanup = "Close the scoped context and reset PostgreSQL", operations = {"getOverview", "getDatabaseMonitoring", "getRuntimeMonitoring", "listActivity"}, evidence = {ManagementBrowserEvidence.VISIBLE_RESULT, ManagementBrowserEvidence.HTTP_OPERATION, ManagementBrowserEvidence.DATABASE, ManagementBrowserEvidence.RESOURCE_CLEANUP})
    @Test
    void overviewShowsZeroLastSweepDeletes() throws Exception {
        overview(context -> assertThat(section(context.page(), "Expiry and cleanup")).containsText("Last sweep deleted0"));
    }

    @ManagementBrowserScenario(id = "PW-OVERVIEW-014", requirement = "Management API: an unobserved sweep is distinct from a false timestamp", area = ManagementBrowserArea.OVERVIEW, risk = ManagementBrowserRisk.MEDIUM, action = "Inspect Last sweep", expectedResult = "The value is Not observed", cleanup = "Close the scoped context and reset PostgreSQL", operations = {"getOverview", "getDatabaseMonitoring", "getRuntimeMonitoring", "listActivity"}, evidence = {ManagementBrowserEvidence.VISIBLE_RESULT, ManagementBrowserEvidence.HTTP_OPERATION, ManagementBrowserEvidence.DATABASE, ManagementBrowserEvidence.RESOURCE_CLEANUP})
    @Test
    void overviewShowsLastSweepNotObserved() throws Exception {
        overview(context -> assertThat(section(context.page(), "Expiry and cleanup")).containsText("Last sweepNot observed"));
    }

    @ManagementBrowserScenario(id = "PW-OVERVIEW-015", requirement = "Management API: STRING value-type count is exact", area = ManagementBrowserArea.OVERVIEW, risk = ManagementBrowserRisk.HIGH, action = "Inspect Entry value types STRING", expectedResult = "The seeded count is 1", cleanup = "Close the scoped context and reset PostgreSQL", operations = {"getOverview", "getDatabaseMonitoring", "getRuntimeMonitoring", "listActivity"}, evidence = {ManagementBrowserEvidence.VISIBLE_RESULT, ManagementBrowserEvidence.HTTP_OPERATION, ManagementBrowserEvidence.DATABASE, ManagementBrowserEvidence.RESOURCE_CLEANUP})
    @Test
    void overviewShowsOneStringEntry() throws Exception {
        overview(context -> assertThat(section(context.page(), "Entry value types")).containsText("String1"));
    }

    @ManagementBrowserScenario(id = "PW-OVERVIEW-016", requirement = "Management API: JSON value-type count is exact", area = ManagementBrowserArea.OVERVIEW, risk = ManagementBrowserRisk.HIGH, action = "Inspect Entry value types JSON", expectedResult = "The seeded count is 1", cleanup = "Close the scoped context and reset PostgreSQL", operations = {"getOverview", "getDatabaseMonitoring", "getRuntimeMonitoring", "listActivity"}, evidence = {ManagementBrowserEvidence.VISIBLE_RESULT, ManagementBrowserEvidence.HTTP_OPERATION, ManagementBrowserEvidence.DATABASE, ManagementBrowserEvidence.RESOURCE_CLEANUP})
    @Test
    void overviewShowsOneJsonEntry() throws Exception {
        overview(context -> assertThat(section(context.page(), "Entry value types")).containsText("Json1"));
    }

    @ManagementBrowserScenario(id = "PW-OVERVIEW-017", requirement = "Management API: LONG value-type count is exact", area = ManagementBrowserArea.OVERVIEW, risk = ManagementBrowserRisk.HIGH, action = "Inspect Entry value types LONG", expectedResult = "The seeded count is 1", cleanup = "Close the scoped context and reset PostgreSQL", operations = {"getOverview", "getDatabaseMonitoring", "getRuntimeMonitoring", "listActivity"}, evidence = {ManagementBrowserEvidence.VISIBLE_RESULT, ManagementBrowserEvidence.HTTP_OPERATION, ManagementBrowserEvidence.DATABASE, ManagementBrowserEvidence.RESOURCE_CLEANUP})
    @Test
    void overviewShowsOneLongEntry() throws Exception {
        overview(context -> assertThat(section(context.page(), "Entry value types")).containsText("Long1"));
    }

    @ManagementBrowserScenario(id = "PW-OVERVIEW-018", requirement = "Management API: BYTES value-type count is exact", area = ManagementBrowserArea.OVERVIEW, risk = ManagementBrowserRisk.HIGH, action = "Inspect Entry value types BYTES", expectedResult = "The seeded count is 1", cleanup = "Close the scoped context and reset PostgreSQL", operations = {"getOverview", "getDatabaseMonitoring", "getRuntimeMonitoring", "listActivity"}, evidence = {ManagementBrowserEvidence.VISIBLE_RESULT, ManagementBrowserEvidence.HTTP_OPERATION, ManagementBrowserEvidence.DATABASE, ManagementBrowserEvidence.RESOURCE_CLEANUP})
    @Test
    void overviewShowsOneBytesEntry() throws Exception {
        overview(context -> assertThat(section(context.page(), "Entry value types")).containsText("Bytes1"));
    }

    @ManagementBrowserScenario(id = "PW-OVERVIEW-019", requirement = "UI design: session trend starts from one authoritative snapshot", area = ManagementBrowserArea.OVERVIEW, risk = ManagementBrowserRisk.MEDIUM, action = "Open Overview and inspect the current-session trend", expectedResult = "The chart reports one snapshot and latest 4 live, 0 expired", cleanup = "Close the session-scoped trend context", operations = {"getOverview", "getDatabaseMonitoring", "getRuntimeMonitoring", "listActivity"}, evidence = {ManagementBrowserEvidence.VISIBLE_RESULT, ManagementBrowserEvidence.HTTP_OPERATION, ManagementBrowserEvidence.DATABASE, ManagementBrowserEvidence.RESOURCE_CLEANUP})
    @Test
    void sessionTrendStartsWithCurrentSnapshot() throws Exception {
        overview(context -> {
            Locator trend = context.page().getByLabel("Current-session cache row trend");
            assertThat(trend).containsText("1 snapshot");
            assertThat(trend).containsText("Latest: 4 live, 0 expired");
        });
    }

    @ManagementBrowserScenario(id = "PW-OVERVIEW-020", requirement = "UI design: monitoring panels load independently beside overview truth", area = ManagementBrowserArea.OVERVIEW, risk = ManagementBrowserRisk.HIGH, action = "Open Overview and inspect monitoring section headings", expectedResult = "Database monitoring, Management runtime, and Recent activity are present", cleanup = "Close the scoped monitoring context", operations = {"getDatabaseMonitoring", "getRuntimeMonitoring", "listActivity"}, evidence = {ManagementBrowserEvidence.VISIBLE_RESULT, ManagementBrowserEvidence.HTTP_OPERATION, ManagementBrowserEvidence.DATABASE, ManagementBrowserEvidence.RESOURCE_CLEANUP})
    @Test
    void overviewLoadsAllMonitoringPanels() throws Exception {
        overview(context -> {
            heading(context.page(), "Database monitoring");
            heading(context.page(), "Management runtime");
            heading(context.page(), "Recent activity");
        });
    }

    @ManagementBrowserScenario(id = "PW-OVERVIEW-021", requirement = "Management API: overview top namespaces identifies seeded database scope", area = ManagementBrowserArea.OVERVIEW, risk = ManagementBrowserRisk.HIGH, action = "Inspect Namespace overview", expectedResult = "logical-orders appears as the top namespace", cleanup = "Close the scoped context and reset PostgreSQL", operations = {"getOverview", "getDatabaseMonitoring", "getRuntimeMonitoring", "listActivity"}, evidence = {ManagementBrowserEvidence.VISIBLE_RESULT, ManagementBrowserEvidence.HTTP_OPERATION, ManagementBrowserEvidence.DATABASE, ManagementBrowserEvidence.RESOURCE_CLEANUP})
    @Test
    void overviewShowsSeededTopNamespace() throws Exception {
        overview(context -> assertThat(section(context.page(), "Namespace overview"))
                .containsText(ManagementConsolePostgresFixture.NAMESPACE));
    }

    @ManagementBrowserScenario(id = "PW-OVERVIEW-022", requirement = "Management API: top-namespace row carries exact resource totals", area = ManagementBrowserArea.OVERVIEW, risk = ManagementBrowserRisk.CRITICAL, action = "Inspect logical-orders in Namespace overview", expectedResult = "The row reports 4 entries, 1 counter, 1 lock, and 0 expired", cleanup = "Close the scoped context and reset PostgreSQL", operations = {"getOverview", "getDatabaseMonitoring", "getRuntimeMonitoring", "listActivity"}, evidence = {ManagementBrowserEvidence.VISIBLE_RESULT, ManagementBrowserEvidence.HTTP_OPERATION, ManagementBrowserEvidence.DATABASE, ManagementBrowserEvidence.RESOURCE_CLEANUP})
    @Test
    void topNamespaceRowShowsExactResourceTotals() throws Exception {
        overview(context -> {
            Locator row = context.page().getByRole(AriaRole.ROW)
                    .filter(new Locator.FilterOptions().setHasText(ManagementConsolePostgresFixture.NAMESPACE));
            assertThat(row).containsText("4");
            assertThat(row).containsText("1");
            assertThat(row).containsText("0");
        });
    }

    @ManagementBrowserScenario(id = "PW-OVERVIEW-023", requirement = "UI design: manual refresh appends current-session trend evidence", area = ManagementBrowserArea.OVERVIEW, risk = ManagementBrowserRisk.HIGH, action = "Load Overview and activate Refresh overview", expectedResult = "The trend advances to two snapshots without losing current totals", cleanup = "Close the session-scoped trend context", operations = {"getOverview", "getDatabaseMonitoring", "getRuntimeMonitoring", "listActivity"}, evidence = {ManagementBrowserEvidence.VISIBLE_RESULT, ManagementBrowserEvidence.HTTP_OPERATION, ManagementBrowserEvidence.DATABASE, ManagementBrowserEvidence.RESOURCE_CLEANUP})
    @Test
    void manualRefreshAppendsTrendSnapshot() throws Exception {
        overview(context -> {
            Page page = context.page();
            page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Refresh overview")).click();
            assertThat(page.getByLabel("Current-session cache row trend")).containsText("2 snapshots");
            assertThat(metric(page, "Live cache entries")).containsText("4");
        });
    }

    @ManagementBrowserScenario(id = "PW-OVERVIEW-024", requirement = "Management API: manual refresh observes database changes made after the first snapshot", area = ManagementBrowserArea.OVERVIEW, risk = ManagementBrowserRisk.CRITICAL, action = "Insert a fifth entry in PostgreSQL and refresh Overview", expectedResult = "Live cache entries changes from 4 to 5", cleanup = "Reset the mutated worker schema and close the context", operations = {"getOverview", "getDatabaseMonitoring", "getRuntimeMonitoring", "listActivity"}, evidence = {ManagementBrowserEvidence.VISIBLE_RESULT, ManagementBrowserEvidence.HTTP_OPERATION, ManagementBrowserEvidence.DATABASE, ManagementBrowserEvidence.RESOURCE_CLEANUP})
    @Test
    void manualRefreshObservesPostSnapshotDatabaseMutation() throws Exception {
        overview(context -> {
            Page page = context.page();
            assertThat(metric(page, "Live cache entries")).containsText("4");
            ManagementConsolePostgresFixture.execute(context.postgres(), """
                    INSERT INTO peegee_cache.cache_entries
                        (namespace, cache_key, value_type, value_bytes, numeric_value, version)
                    VALUES ('logical-orders', 'after-snapshot', 'STRING', convert_to('new', 'UTF8'), NULL, 1)
                    """);
            page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Refresh overview")).click();
            assertThat(metric(page, "Live cache entries")).containsText("5");
        });
    }

    private void overview(ManagementConsolePostgresFixture.Journey journey) throws Exception {
        ManagementConsolePostgresFixture.run(temporaryDirectory, POSTGRES.postgres(), true, context -> {
            ManagementConsolePostgresFixture.authenticate(context);
            ManagementConsolePostgresFixture.registerSetup(context);
            context.page().getByRole(AriaRole.LINK, new Page.GetByRoleOptions().setName("Overview")).click();
            heading(context.page(), "Overview");
            assertThat(metric(context.page(), "Live cache entries")).isVisible();
            journey.run(context);
        });
    }

    private static Locator metric(Page page, String label) {
        return page.locator("article.metric-card").filter(new Locator.FilterOptions().setHasText(label));
    }

    private static Locator section(Page page, String heading) {
        return page.getByRole(AriaRole.HEADING, new Page.GetByRoleOptions().setName(heading).setExact(true))
                .locator("xpath=ancestor::section[1]");
    }

    private static void heading(Page page, String name) {
        assertThat(page.getByRole(AriaRole.HEADING,
                new Page.GetByRoleOptions().setName(name).setExact(true))).isVisible();
    }
}
