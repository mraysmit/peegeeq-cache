package dev.mars.peegeeq.cache.rest.server;

import com.microsoft.playwright.Download;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.AriaRole;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Wave P2 real-browser scenarios for namespace inspection, pagination, and export. */
class ManagementNamespaceBrowserIT {

    @RegisterExtension
    static final ManagementBrowserPostgresWorker POSTGRES = new ManagementBrowserPostgresWorker();

    @TempDir
    Path temporaryDirectory;

    @ManagementBrowserScenario(id = "PW-NAMESPACE-001", requirement = "UI design: namespace inventory states its database-wide cursor semantics", area = ManagementBrowserArea.NAMESPACE, risk = ManagementBrowserRisk.HIGH, action = "Open Namespaces for the selected setup", expectedResult = "The heading, database-wide context, and cursor description are visible", cleanup = "Close the scoped context and reset PostgreSQL", operations = {"listNamespaces"}, evidence = {ManagementBrowserEvidence.VISIBLE_RESULT, ManagementBrowserEvidence.HTTP_OPERATION, ManagementBrowserEvidence.DATABASE, ManagementBrowserEvidence.RESOURCE_CLEANUP})
    @Test
    void namespaceInventoryExplainsDatabaseWideCursorScope() throws Exception {
        namespaces(context -> {
            Page page = context.page();
            assertThat(page.getByText("Database-wide inspection", new Page.GetByTextOptions().setExact(true))).isVisible();
            assertThat(page.getByText("Cursor-paginated namespace metadata for the selected setup.")).isVisible();
        });
    }

    @ManagementBrowserScenario(id = "PW-NAMESPACE-002", requirement = "Management API: namespace inventory returns seeded logical scope", area = ManagementBrowserArea.NAMESPACE, risk = ManagementBrowserRisk.CRITICAL, action = "Inspect the first namespace page", expectedResult = "logical-orders is a navigable namespace", cleanup = "Close the scoped context and reset PostgreSQL", operations = {"listNamespaces"}, evidence = {ManagementBrowserEvidence.VISIBLE_RESULT, ManagementBrowserEvidence.HTTP_OPERATION, ManagementBrowserEvidence.DATABASE, ManagementBrowserEvidence.RESOURCE_CLEANUP})
    @Test
    void namespaceInventoryShowsSeededLogicalNamespace() throws Exception {
        namespaces(context -> assertThat(namespaceLink(context.page())).isVisible());
    }

    @ManagementBrowserScenario(id = "PW-NAMESPACE-003", requirement = "Management API: namespace row reports exact live-entry total", area = ManagementBrowserArea.NAMESPACE, risk = ManagementBrowserRisk.CRITICAL, action = "Inspect logical-orders Live entries", expectedResult = "The exact total is 4", cleanup = "Close the scoped context and reset PostgreSQL", operations = {"listNamespaces"}, evidence = {ManagementBrowserEvidence.VISIBLE_RESULT, ManagementBrowserEvidence.HTTP_OPERATION, ManagementBrowserEvidence.DATABASE, ManagementBrowserEvidence.RESOURCE_CLEANUP})
    @Test
    void namespaceRowShowsFourLiveEntries() throws Exception {
        namespaces(context -> assertThat(namespaceRow(context.page())).containsText("4"));
    }

    @ManagementBrowserScenario(id = "PW-NAMESPACE-004", requirement = "Management API: namespace row reports exact counter total", area = ManagementBrowserArea.NAMESPACE, risk = ManagementBrowserRisk.CRITICAL, action = "Inspect logical-orders Counters", expectedResult = "The exact total is 1", cleanup = "Close the scoped context and reset PostgreSQL", operations = {"listNamespaces"}, evidence = {ManagementBrowserEvidence.VISIBLE_RESULT, ManagementBrowserEvidence.HTTP_OPERATION, ManagementBrowserEvidence.DATABASE, ManagementBrowserEvidence.RESOURCE_CLEANUP})
    @Test
    void namespaceRowShowsOneCounter() throws Exception {
        namespaces(context -> {
            Locator row = namespaceRow(context.page());
            assertEquals("1", row.locator("td").nth(2).textContent());
        });
    }

    @ManagementBrowserScenario(id = "PW-NAMESPACE-005", requirement = "Management API: namespace row reports exact active-lock total", area = ManagementBrowserArea.NAMESPACE, risk = ManagementBrowserRisk.CRITICAL, action = "Inspect logical-orders Locks", expectedResult = "The exact total is 1", cleanup = "Close the scoped context and reset PostgreSQL", operations = {"listNamespaces"}, evidence = {ManagementBrowserEvidence.VISIBLE_RESULT, ManagementBrowserEvidence.HTTP_OPERATION, ManagementBrowserEvidence.DATABASE, ManagementBrowserEvidence.RESOURCE_CLEANUP})
    @Test
    void namespaceRowShowsOneActiveLock() throws Exception {
        namespaces(context -> assertEquals("1", namespaceRow(context.page()).locator("td").nth(3).textContent()));
    }

    @ManagementBrowserScenario(id = "PW-NAMESPACE-006", requirement = "Management API: zero expiring rows remains distinct and exact", area = ManagementBrowserArea.NAMESPACE, risk = ManagementBrowserRisk.HIGH, action = "Inspect logical-orders Expiring", expectedResult = "The exact value is 0", cleanup = "Close the scoped context and reset PostgreSQL", operations = {"listNamespaces"}, evidence = {ManagementBrowserEvidence.VISIBLE_RESULT, ManagementBrowserEvidence.HTTP_OPERATION, ManagementBrowserEvidence.DATABASE, ManagementBrowserEvidence.RESOURCE_CLEANUP})
    @Test
    void namespaceRowShowsZeroExpiringEntries() throws Exception {
        namespaces(context -> assertEquals("0", namespaceRow(context.page()).locator("td").nth(4).textContent()));
    }

    @ManagementBrowserScenario(id = "PW-NAMESPACE-007", requirement = "Management API: zero expired rows remains distinct and exact", area = ManagementBrowserArea.NAMESPACE, risk = ManagementBrowserRisk.HIGH, action = "Inspect logical-orders Expired", expectedResult = "The exact value is 0", cleanup = "Close the scoped context and reset PostgreSQL", operations = {"listNamespaces"}, evidence = {ManagementBrowserEvidence.VISIBLE_RESULT, ManagementBrowserEvidence.HTTP_OPERATION, ManagementBrowserEvidence.DATABASE, ManagementBrowserEvidence.RESOURCE_CLEANUP})
    @Test
    void namespaceRowShowsZeroExpiredEntries() throws Exception {
        namespaces(context -> assertEquals("0", namespaceRow(context.page()).locator("td").nth(5).textContent()));
    }

    @ManagementBrowserScenario(id = "PW-NAMESPACE-008", requirement = "Management API: namespace storage estimate is rendered as an exact byte count", area = ManagementBrowserArea.NAMESPACE, risk = ManagementBrowserRisk.HIGH, action = "Inspect logical-orders Storage", expectedResult = "A positive byte value is visible", cleanup = "Close the scoped context and reset PostgreSQL", operations = {"listNamespaces"}, evidence = {ManagementBrowserEvidence.VISIBLE_RESULT, ManagementBrowserEvidence.HTTP_OPERATION, ManagementBrowserEvidence.DATABASE, ManagementBrowserEvidence.RESOURCE_CLEANUP})
    @Test
    void namespaceRowShowsPositiveStorageBytes() throws Exception {
        namespaces(context -> {
            String storage = namespaceRow(context.page()).locator("td").nth(6).textContent();
            assertTrue(storage.endsWith(" B"));
            assertFalse(storage.equals("0 B"));
        });
    }

    @ManagementBrowserScenario(id = "PW-NAMESPACE-009", requirement = "UI design: namespace status filters expose only reviewed server values", area = ManagementBrowserArea.NAMESPACE, risk = ManagementBrowserRisk.HIGH, action = "Inspect Namespace status options", expectedResult = "All, Healthy, Expired backlog, and Active locks are available", cleanup = "Close the scoped context and reset PostgreSQL", operations = {"listNamespaces"}, evidence = {ManagementBrowserEvidence.VISIBLE_RESULT, ManagementBrowserEvidence.HTTP_OPERATION, ManagementBrowserEvidence.RESOURCE_CLEANUP})
    @Test
    void namespaceStatusOffersReviewedValues() throws Exception {
        // antd Select (U11.4): options are addressed by their reviewed labels, in display order.
        namespaces(context -> assertEquals(
                java.util.List.of("All", "Healthy", "Expired backlog", "Active locks"),
                AntSelect.optionLabels(context.page(), context.page().getByLabel("Status"))));
    }

    @ManagementBrowserScenario(id = "PW-NAMESPACE-010", requirement = "UI design: namespace sort exposes canonical name and live-entry orderings", area = ManagementBrowserArea.NAMESPACE, risk = ManagementBrowserRisk.MEDIUM, action = "Inspect Namespace sort options", expectedResult = "namespace:asc and entryCount:desc are available", cleanup = "Close the scoped context and reset PostgreSQL", operations = {"listNamespaces"}, evidence = {ManagementBrowserEvidence.VISIBLE_RESULT, ManagementBrowserEvidence.HTTP_OPERATION, ManagementBrowserEvidence.RESOURCE_CLEANUP})
    @Test
    void namespaceSortOffersReviewedValues() throws Exception {
        namespaces(context -> assertEquals(
                java.util.List.of("Namespace", "Live entries"),
                AntSelect.optionLabels(context.page(), context.page().getByLabel("Sort"))));
    }

    @ManagementBrowserScenario(id = "PW-NAMESPACE-011", requirement = "Management API: namespace prefix is bounded before transport", area = ManagementBrowserArea.NAMESPACE, risk = ManagementBrowserRisk.MEDIUM, action = "Inspect Namespace prefix maximum length", expectedResult = "The browser caps prefix at 128 characters", cleanup = "Close the scoped context and reset PostgreSQL", operations = {"listNamespaces"}, evidence = {ManagementBrowserEvidence.VISIBLE_RESULT, ManagementBrowserEvidence.HTTP_OPERATION, ManagementBrowserEvidence.RESOURCE_CLEANUP})
    @Test
    void namespacePrefixHasReviewedLengthLimit() throws Exception {
        namespaces(context -> assertThat(context.page().getByLabel("Namespace prefix")).hasAttribute("maxlength", "128"));
    }

    @ManagementBrowserScenario(id = "PW-NAMESPACE-012", requirement = "Management API: prefix filtering returns matching database scope", area = ManagementBrowserArea.NAMESPACE, risk = ManagementBrowserRisk.HIGH, action = "Filter namespace prefix to logical", expectedResult = "logical-orders remains visible", cleanup = "Close the filtered context and reset PostgreSQL", operations = {"listNamespaces"}, evidence = {ManagementBrowserEvidence.VISIBLE_RESULT, ManagementBrowserEvidence.HTTP_OPERATION, ManagementBrowserEvidence.DATABASE, ManagementBrowserEvidence.RESOURCE_CLEANUP})
    @Test
    void prefixFilterReturnsMatchingNamespace() throws Exception {
        namespaces(context -> {
            applyPrefix(context.page(), "logical");
            assertThat(namespaceLink(context.page())).isVisible();
        });
    }

    @ManagementBrowserScenario(id = "PW-NAMESPACE-013", requirement = "Management API: unmatched prefix has an explicit empty result", area = ManagementBrowserArea.NAMESPACE, risk = ManagementBrowserRisk.HIGH, action = "Filter to a prefix absent from PostgreSQL", expectedResult = "No namespaces matched is visible", cleanup = "Close the filtered context and reset PostgreSQL", operations = {"listNamespaces"}, evidence = {ManagementBrowserEvidence.VISIBLE_RESULT, ManagementBrowserEvidence.HTTP_OPERATION, ManagementBrowserEvidence.DATABASE, ManagementBrowserEvidence.RESOURCE_CLEANUP})
    @Test
    void unmatchedPrefixShowsExplicitEmptyState() throws Exception {
        namespaces(context -> {
            applyPrefix(context.page(), "absent-prefix");
            heading(context.page(), "No namespaces matched");
            assertThat(context.page().getByText("Change the current prefix or status filter.")).isVisible();
        });
    }

    @ManagementBrowserScenario(id = "PW-NAMESPACE-014", requirement = "UI design: prefix filters trim harmless surrounding whitespace", area = ManagementBrowserArea.NAMESPACE, risk = ManagementBrowserRisk.MEDIUM, action = "Apply the prefix with leading and trailing spaces", expectedResult = "The trimmed logical prefix returns logical-orders", cleanup = "Close the filtered context and reset PostgreSQL", operations = {"listNamespaces"}, evidence = {ManagementBrowserEvidence.VISIBLE_RESULT, ManagementBrowserEvidence.HTTP_OPERATION, ManagementBrowserEvidence.DATABASE, ManagementBrowserEvidence.RESOURCE_CLEANUP})
    @Test
    void prefixFilterTrimsSurroundingWhitespace() throws Exception {
        namespaces(context -> {
            applyPrefix(context.page(), "  logical  ");
            assertThat(namespaceLink(context.page())).isVisible();
        });
    }

    @ManagementBrowserScenario(id = "PW-NAMESPACE-015", requirement = "Management API: HEALTHY filter includes the seeded namespace", area = ManagementBrowserArea.NAMESPACE, risk = ManagementBrowserRisk.HIGH, action = "Apply the Healthy status filter", expectedResult = "logical-orders remains visible", cleanup = "Close the filtered context and reset PostgreSQL", operations = {"listNamespaces"}, evidence = {ManagementBrowserEvidence.VISIBLE_RESULT, ManagementBrowserEvidence.HTTP_OPERATION, ManagementBrowserEvidence.DATABASE, ManagementBrowserEvidence.RESOURCE_CLEANUP})
    @Test
    void healthyFilterIncludesSeededNamespace() throws Exception {
        namespaces(context -> {
            applyStatus(context.page(), "HEALTHY");
            assertThat(namespaceLink(context.page())).isVisible();
        });
    }

    @ManagementBrowserScenario(id = "PW-NAMESPACE-016", requirement = "Management API: ACTIVE_LOCKS filter is backed by lease truth", area = ManagementBrowserArea.NAMESPACE, risk = ManagementBrowserRisk.CRITICAL, action = "Apply the Active locks status filter", expectedResult = "logical-orders remains because it owns one active lease", cleanup = "Close the filtered context and reset PostgreSQL", operations = {"listNamespaces"}, evidence = {ManagementBrowserEvidence.VISIBLE_RESULT, ManagementBrowserEvidence.HTTP_OPERATION, ManagementBrowserEvidence.DATABASE, ManagementBrowserEvidence.RESOURCE_CLEANUP})
    @Test
    void activeLocksFilterIncludesSeededNamespace() throws Exception {
        namespaces(context -> {
            applyStatus(context.page(), "ACTIVE_LOCKS");
            assertThat(namespaceLink(context.page())).isVisible();
        });
    }

    @ManagementBrowserScenario(id = "PW-NAMESPACE-017", requirement = "Management API: EXPIRED_BACKLOG excludes a namespace with no expired entries", area = ManagementBrowserArea.NAMESPACE, risk = ManagementBrowserRisk.CRITICAL, action = "Apply the Expired backlog status filter", expectedResult = "The result is explicitly empty", cleanup = "Close the filtered context and reset PostgreSQL", operations = {"listNamespaces"}, evidence = {ManagementBrowserEvidence.VISIBLE_RESULT, ManagementBrowserEvidence.HTTP_OPERATION, ManagementBrowserEvidence.DATABASE, ManagementBrowserEvidence.RESOURCE_CLEANUP})
    @Test
    void expiredBacklogFilterExcludesHealthySeededNamespace() throws Exception {
        namespaces(context -> {
            applyStatus(context.page(), "EXPIRED_BACKLOG");
            heading(context.page(), "No namespaces matched");
        });
    }

    @ManagementBrowserScenario(id = "PW-NAMESPACE-018", requirement = "Management API: opaque cursor moves to a second page without duplication", area = ManagementBrowserArea.NAMESPACE, risk = ManagementBrowserRisk.CRITICAL, action = "Seed 55 additional namespaces and activate Next page", expectedResult = "Page 2 appears with later namespaces and Previous enabled", cleanup = "Reset the pagination seed and close the context", operations = {"listNamespaces"}, evidence = {ManagementBrowserEvidence.VISIBLE_RESULT, ManagementBrowserEvidence.HTTP_OPERATION, ManagementBrowserEvidence.DATABASE, ManagementBrowserEvidence.RESOURCE_CLEANUP})
    @Test
    void opaqueCursorAdvancesToSecondNamespacePage() throws Exception {
        paginated(context -> {
            Page page = context.page();
            page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Next page")).click();
            assertThat(page.getByRole(AriaRole.NAVIGATION, new Page.GetByRoleOptions().setName("Namespace pages"))).containsText("Page 2");
            assertThat(page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Previous page"))).isEnabled();
            assertThat(page.getByRole(AriaRole.LINK, new Page.GetByRoleOptions().setName("page-055"))).isVisible();
        });
    }

    @ManagementBrowserScenario(id = "PW-NAMESPACE-019", requirement = "UI design: namespace cursor history supports backward navigation", area = ManagementBrowserArea.NAMESPACE, risk = ManagementBrowserRisk.CRITICAL, action = "Advance to page 2 and activate Previous page", expectedResult = "Page 1 and logical-orders are restored", cleanup = "Reset the pagination seed and close the context", operations = {"listNamespaces"}, evidence = {ManagementBrowserEvidence.VISIBLE_RESULT, ManagementBrowserEvidence.HTTP_OPERATION, ManagementBrowserEvidence.DATABASE, ManagementBrowserEvidence.RESOURCE_CLEANUP})
    @Test
    void namespacePaginationReturnsToPreviousPage() throws Exception {
        paginated(context -> {
            Page page = context.page();
            page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Next page")).click();
            page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Previous page")).click();
            assertThat(page.getByRole(AriaRole.NAVIGATION, new Page.GetByRoleOptions().setName("Namespace pages"))).containsText("Page 1");
            assertThat(namespaceLink(page)).isVisible();
        });
    }

    @ManagementBrowserScenario(id = "PW-NAMESPACE-020", requirement = "Management API: namespace export downloads filtered structured data", area = ManagementBrowserArea.NAMESPACE, risk = ManagementBrowserRisk.CRITICAL, action = "Export the current namespace inventory", expectedResult = "A setup-named JSON file contains logical-orders and no database password", cleanup = "Delete the temporary download, close context, and reset PostgreSQL", operations = {"listNamespaces", "exportNamespaces"}, evidence = {ManagementBrowserEvidence.VISIBLE_RESULT, ManagementBrowserEvidence.HTTP_OPERATION, ManagementBrowserEvidence.DATABASE, ManagementBrowserEvidence.SENSITIVE_STATE, ManagementBrowserEvidence.RESOURCE_CLEANUP})
    @Test
    void namespaceExportDownloadsSecretSafeStructuredData() throws Exception {
        namespaces(context -> {
            Page page = context.page();
            Download download = page.waitForDownload(() -> page.getByRole(
                    AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Export namespaces")).click());
            assertEquals("browser-postgres-namespaces.json", download.suggestedFilename());
            String json = Files.readString(download.path());
            assertTrue(json.contains("logical-orders"));
            assertFalse(json.contains(ManagementConsolePostgresFixture.DATABASE_PASSWORD));
            assertThat(page.getByRole(AriaRole.STATUS)).containsText("Exported 1 namespace.");
        });
    }

    @ManagementBrowserScenario(id = "PW-NAMESPACE-021", requirement = "Management API: namespace link opens authoritative details", area = ManagementBrowserArea.NAMESPACE, risk = ManagementBrowserRisk.CRITICAL, action = "Open logical-orders from the inventory", expectedResult = "The details route loads its exact database scope", cleanup = "Clear selected namespace, close context, and reset PostgreSQL", operations = {"listNamespaces", "getNamespace"}, evidence = {ManagementBrowserEvidence.VISIBLE_RESULT, ManagementBrowserEvidence.HTTP_OPERATION, ManagementBrowserEvidence.DATABASE, ManagementBrowserEvidence.RESOURCE_CLEANUP})
    @Test
    void namespaceLinkOpensAuthoritativeDetails() throws Exception {
        namespaces(context -> {
            namespaceLink(context.page()).click();
            heading(context.page(), "logical-orders");
            assertThat(context.page().getByText("Database-wide · Observed", new Page.GetByTextOptions().setExact(false))).isVisible();
        });
    }

    @ManagementBrowserScenario(id = "PW-NAMESPACE-022", requirement = "Management API: namespace details expose exact resource totals and tabs", area = ManagementBrowserArea.NAMESPACE, risk = ManagementBrowserRisk.CRITICAL, action = "Open logical-orders details and inspect totals and resources", expectedResult = "4 entries, 1 counter, 1 lock, and Overview, Entries, Counters, Locks tabs are visible", cleanup = "Clear selected namespace, close context, and reset PostgreSQL", operations = {"listNamespaces", "getNamespace"}, evidence = {ManagementBrowserEvidence.VISIBLE_RESULT, ManagementBrowserEvidence.HTTP_OPERATION, ManagementBrowserEvidence.DATABASE, ManagementBrowserEvidence.RESOURCE_CLEANUP})
    @Test
    void namespaceDetailsShowsExactTotalsAndResourceTabs() throws Exception {
        namespaces(context -> {
            Page page = context.page();
            namespaceLink(page).click();
            Locator totals = page.getByLabel("Namespace totals");
            assertThat(totals).containsText("Live entries4");
            assertThat(totals).containsText("Live counters1");
            assertThat(totals).containsText("Active locks1");
            assertThat(page.getByRole(AriaRole.TAB)).hasCount(4);
        });
    }

    @ManagementBrowserScenario(id = "PW-NAMESPACE-023", requirement = "Management API: namespace details retain type and TTL distributions", area = ManagementBrowserArea.NAMESPACE, risk = ManagementBrowserRisk.HIGH, action = "Open logical-orders details and inspect distribution panels", expectedResult = "All four seeded value types and the TTL distribution are visible", cleanup = "Clear selected namespace, close context, and reset PostgreSQL", operations = {"listNamespaces", "getNamespace"}, evidence = {ManagementBrowserEvidence.VISIBLE_RESULT, ManagementBrowserEvidence.HTTP_OPERATION, ManagementBrowserEvidence.DATABASE, ManagementBrowserEvidence.RESOURCE_CLEANUP})
    @Test
    void namespaceDetailsShowsValueTypeAndTtlDistributions() throws Exception {
        namespaces(context -> {
            Page page = context.page();
            namespaceLink(page).click();
            Locator types = section(page, "Value types");
            assertThat(types).containsText("STRING1");
            assertThat(types).containsText("JSON1");
            assertThat(types).containsText("LONG1");
            assertThat(types).containsText("BYTES1");
            assertThat(section(page, "TTL distribution")).containsText("Persistent4");
        });
    }

    private void namespaces(ManagementConsolePostgresFixture.Journey journey) throws Exception {
        ManagementConsolePostgresFixture.run(temporaryDirectory, POSTGRES.postgres(), true, context -> {
            openNamespaces(context.page());
            journey.run(context);
        });
    }

    private void paginated(ManagementConsolePostgresFixture.Journey journey) throws Exception {
        ManagementConsolePostgresFixture.run(temporaryDirectory, POSTGRES.postgres(), true, context -> {
            ManagementConsolePostgresFixture.execute(context.postgres(), """
                    INSERT INTO peegee_cache.cache_entries
                        (namespace, cache_key, value_type, value_bytes, numeric_value, version)
                    SELECT 'page-' || lpad(value::text, 3, '0'), 'key', 'STRING',
                           convert_to('value', 'UTF8'), NULL, 1
                    FROM generate_series(1, 55) AS value
                    """);
            openNamespaces(context.page());
            journey.run(context);
        });
    }

    private static void openNamespaces(Page page) {
        page.getByRole(AriaRole.LINK, new Page.GetByRoleOptions().setName("Namespaces")).click();
        heading(page, "Namespaces");
        assertThat(page.getByRole(AriaRole.NAVIGATION, new Page.GetByRoleOptions().setName("Namespace pages"))).isVisible();
    }

    private static void applyPrefix(Page page, String prefix) {
        page.getByLabel("Namespace prefix").fill(prefix);
        page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Apply filters")).click();
    }

    private static void applyStatus(Page page, String status) {
        AntSelect.choose(page, page.getByLabel("Status"), status);
        page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Apply filters")).click();
    }

    private static Locator namespaceLink(Page page) {
        return page.getByRole(AriaRole.LINK,
                new Page.GetByRoleOptions().setName(ManagementConsolePostgresFixture.NAMESPACE).setExact(true));
    }

    private static Locator namespaceRow(Page page) {
        return page.getByRole(AriaRole.ROW)
                .filter(new Locator.FilterOptions().setHasText(ManagementConsolePostgresFixture.NAMESPACE));
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
