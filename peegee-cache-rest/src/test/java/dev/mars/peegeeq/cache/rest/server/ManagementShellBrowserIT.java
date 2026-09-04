package dev.mars.peegeeq.cache.rest.server;

import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.AriaRole;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Base64;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Wave P1 real-browser scenarios for shell, routing, scope, and navigation behavior. */
class ManagementShellBrowserIT {

    @RegisterExtension
    static final ManagementBrowserPostgresWorker POSTGRES = new ManagementBrowserPostgresWorker();

    @TempDir
    Path temporaryDirectory;

    @ManagementBrowserScenario(id = "PW-SHELL-002",
            requirement = "UI design: a populated overview deep link presents database truth",
            area = ManagementBrowserArea.SHELL, risk = ManagementBrowserRisk.HIGH,
            action = "Open the packaged overview route with a connected setup",
            expectedResult = "The overview identifies database scope and shows the seeded live-entry total",
            cleanup = "Close the isolated context and reset the worker schema",
            operations = {"getOverview", "getDatabaseMonitoring", "getRuntimeMonitoring", "listActivity"}, evidence = {ManagementBrowserEvidence.VISIBLE_RESULT,
            ManagementBrowserEvidence.HTTP_OPERATION, ManagementBrowserEvidence.DATABASE,
            ManagementBrowserEvidence.RESOURCE_CLEANUP})
    @Test
    void populatedOverviewDeepLinkShowsDatabaseTruth() throws Exception {
        prepared(context -> {
            Page page = navigate(context, "/ui/", "Overview");
            assertThat(page.getByText("Database-wide snapshot", new Page.GetByTextOptions().setExact(true)))
                    .isVisible();
            assertThat(page.getByText("4", new Page.GetByTextOptions().setExact(true)).first()).isVisible();
        });
    }

    @ManagementBrowserScenario(id = "PW-SHELL-003",
            requirement = "UI design: the setups deep link exposes the active registered setup",
            area = ManagementBrowserArea.SHELL, risk = ManagementBrowserRisk.HIGH,
            action = "Open the packaged setups route after registration",
            expectedResult = "The active setup row shows its identifier, name, and connected state",
            cleanup = "Close the isolated context and reset the worker schema",
            operations = {"listSetups"}, evidence = {ManagementBrowserEvidence.VISIBLE_RESULT,
            ManagementBrowserEvidence.HTTP_OPERATION, ManagementBrowserEvidence.RESOURCE_CLEANUP})
    @Test
    void setupsDeepLinkShowsActiveRegisteredSetup() throws Exception {
        prepared(context -> {
            Page page = navigate(context, "/ui/setups", "Setups");
            assertThat(page.getByText(ManagementConsolePostgresFixture.SETUP_NAME,
                    new Page.GetByTextOptions().setExact(true))).isVisible();
            assertThat(page.getByTitle("Active setup scope"))
                    .hasText("Setup: " + ManagementConsolePostgresFixture.SETUP_ID);
        });
    }

    @ManagementBrowserScenario(id = "PW-SHELL-004",
            requirement = "UI design: the namespaces deep link renders database-backed namespace rows",
            area = ManagementBrowserArea.SHELL, risk = ManagementBrowserRisk.HIGH,
            action = "Open the namespaces route with seeded PostgreSQL state",
            expectedResult = "The namespace table contains the seeded logical namespace and pagination controls",
            cleanup = "Close the isolated context and reset the worker schema",
            operations = {"listNamespaces"}, evidence = {ManagementBrowserEvidence.VISIBLE_RESULT,
            ManagementBrowserEvidence.HTTP_OPERATION, ManagementBrowserEvidence.DATABASE,
            ManagementBrowserEvidence.RESOURCE_CLEANUP})
    @Test
    void namespacesDeepLinkShowsSeededNamespace() throws Exception {
        prepared(context -> {
            Page page = navigate(context, "/ui/namespaces", "Namespaces");
            assertThat(page.getByRole(AriaRole.LINK,
                    new Page.GetByRoleOptions().setName(ManagementConsolePostgresFixture.NAMESPACE)
                            .setExact(true))).isVisible();
            assertThat(page.getByRole(AriaRole.NAVIGATION,
                    new Page.GetByRoleOptions().setName("Namespace pages"))).isVisible();
        });
    }

    @ManagementBrowserScenario(id = "PW-SHELL-005",
            requirement = "UI design: a namespace-details deep link binds the decoded route to database data",
            area = ManagementBrowserArea.SHELL, risk = ManagementBrowserRisk.HIGH,
            action = "Open the encoded logical-orders namespace route directly",
            expectedResult = "The decoded namespace heading and entry, counter, and lock tabs are visible",
            cleanup = "Clear namespace scope, close the context, and reset the schema",
            operations = {"getNamespace"}, evidence = {ManagementBrowserEvidence.VISIBLE_RESULT,
            ManagementBrowserEvidence.HTTP_OPERATION, ManagementBrowserEvidence.DATABASE,
            ManagementBrowserEvidence.RESOURCE_CLEANUP})
    @Test
    void namespaceDetailsDeepLinkBindsDecodedRoute() throws Exception {
        prepared(context -> {
            Page page = navigate(context, "/ui/namespaces/" + encoded(ManagementConsolePostgresFixture.NAMESPACE),
                    ManagementConsolePostgresFixture.NAMESPACE);
            assertThat(page.getByRole(AriaRole.TAB, new Page.GetByRoleOptions().setName("Entries"))).isVisible();
            assertThat(page.getByRole(AriaRole.TAB, new Page.GetByRoleOptions().setName("Counters"))).isVisible();
            assertThat(page.getByRole(AriaRole.TAB, new Page.GetByRoleOptions().setName("Locks"))).isVisible();
        });
    }

    @ManagementBrowserScenario(id = "PW-SHELL-006",
            requirement = "UI design: key browsing uses the namespace selected by the namespace route",
            area = ManagementBrowserArea.SHELL, risk = ManagementBrowserRisk.HIGH,
            action = "Select a namespace through its details route and then open Key Browser",
            expectedResult = "The key table is scoped to logical-orders and contains customer:1",
            cleanup = "Clear namespace scope, close the context, and reset the schema",
            operations = {"getNamespace", "listEntries"}, evidence = {ManagementBrowserEvidence.VISIBLE_RESULT,
            ManagementBrowserEvidence.HTTP_OPERATION, ManagementBrowserEvidence.DATABASE,
            ManagementBrowserEvidence.RESOURCE_CLEANUP})
    @Test
    void keyBrowserUsesNamespaceSelectedByRoute() throws Exception {
        prepared(context -> {
            Page page = navigate(context, "/ui/namespaces/" + encoded(ManagementConsolePostgresFixture.NAMESPACE),
                    ManagementConsolePostgresFixture.NAMESPACE);
            navigate(context, "/ui/keys", "Key Browser");
            assertThat(page.getByRole(AriaRole.LINK,
                    new Page.GetByRoleOptions().setName("customer:1").setExact(true))).isVisible();
            assertThat(page.getByText("Namespace: logical-orders",
                    new Page.GetByTextOptions().setExact(true))).isVisible();
        });
    }

    @ManagementBrowserScenario(id = "PW-SHELL-007",
            requirement = "UI design: an entry deep link retains masked-value defaults",
            area = ManagementBrowserArea.SHELL, risk = ManagementBrowserRisk.CRITICAL,
            action = "Open the encoded customer:1 entry route directly",
            expectedResult = "The entry metadata loads while the stored value remains hidden",
            cleanup = "Close the sensitive route context and reset the schema",
            operations = {"getEntry"}, evidence = {ManagementBrowserEvidence.VISIBLE_RESULT,
            ManagementBrowserEvidence.HTTP_OPERATION, ManagementBrowserEvidence.DATABASE,
            ManagementBrowserEvidence.SENSITIVE_STATE, ManagementBrowserEvidence.RESOURCE_CLEANUP})
    @Test
    void entryDeepLinkKeepsValueMasked() throws Exception {
        prepared(context -> {
            Page page = navigate(context, entryPath(ManagementConsolePostgresFixture.NAMESPACE, "customer:1"),
                    "customer:1");
            assertThat(page.getByText("Value hidden", new Page.GetByTextOptions().setExact(true))).isVisible();
            assertFalse(page.content().contains("stored-value"));
        });
    }

    @ManagementBrowserScenario(id = "PW-SHELL-008",
            requirement = "UI design: the counters deep link preserves exact signed numeric presentation",
            area = ManagementBrowserArea.SHELL, risk = ManagementBrowserRisk.HIGH,
            action = "Open the counters route with a seeded counter",
            expectedResult = "The count counter and its exact value 42 are available for management",
            cleanup = "Close the isolated context and reset the schema",
            operations = {"listCounters"}, evidence = {ManagementBrowserEvidence.VISIBLE_RESULT,
            ManagementBrowserEvidence.HTTP_OPERATION, ManagementBrowserEvidence.DATABASE,
            ManagementBrowserEvidence.RESOURCE_CLEANUP})
    @Test
    void countersDeepLinkShowsExactSeededValue() throws Exception {
        prepared(context -> {
            Page page = navigate(context, "/ui/counters", "Counters");
            assertThat(page.getByRole(AriaRole.BUTTON,
                    new Page.GetByRoleOptions().setName("Manage count").setExact(true))).isVisible();
            assertThat(page.getByText("42", new Page.GetByTextOptions().setExact(true))).isVisible();
        });
    }

    @ManagementBrowserScenario(id = "PW-SHELL-009",
            requirement = "UI design: the locks deep link masks owner tokens in its table",
            area = ManagementBrowserArea.SHELL, risk = ManagementBrowserRisk.CRITICAL,
            action = "Open the locks route with an active seeded lease",
            expectedResult = "The lease is visible with a masked owner and no owner token in the DOM",
            cleanup = "Close the sensitive route context and reset the schema",
            operations = {"listLocks"}, evidence = {ManagementBrowserEvidence.VISIBLE_RESULT,
            ManagementBrowserEvidence.HTTP_OPERATION, ManagementBrowserEvidence.DATABASE,
            ManagementBrowserEvidence.SENSITIVE_STATE, ManagementBrowserEvidence.RESOURCE_CLEANUP})
    @Test
    void locksDeepLinkMasksOwnerToken() throws Exception {
        prepared(context -> {
            Page page = navigate(context, "/ui/locks", "Locks");
            assertThat(page.getByRole(AriaRole.BUTTON,
                    new Page.GetByRoleOptions().setName("Manage lease").setExact(true))).isVisible();
            assertThat(page.getByText("Masked", new Page.GetByTextOptions().setExact(true))).isVisible();
            assertFalse(page.content().contains("owner-secret"));
        });
    }

    @ManagementBrowserScenario(id = "PW-SHELL-010",
            requirement = "UI design: the Pub/Sub route begins stopped and exposes bounded subscription controls",
            area = ManagementBrowserArea.SHELL, risk = ManagementBrowserRisk.HIGH,
            action = "Open Pub/Sub without creating a subscription",
            expectedResult = "Subscribe and publish controls show server capability limits without opening a transport",
            cleanup = "Verify no subscription exists and close all fixture resources",
            evidence = {ManagementBrowserEvidence.VISIBLE_RESULT, ManagementBrowserEvidence.RESOURCE_CLEANUP})
    @Test
    void pubSubDeepLinkStartsWithoutTransport() throws Exception {
        prepared(context -> {
            Page page = navigate(context, "/ui/pubsub", "Pub/Sub");
            assertThat(page.getByRole(AriaRole.HEADING,
                    new Page.GetByRoleOptions().setName("Subscribe").setExact(true))).isVisible();
            assertThat(page.getByLabel("Channel", new Page.GetByLabelOptions().setExact(true))).isVisible();
            assertThat(page.getByText("No retained message metadata.")).hasCount(0);
        });
    }

    @ManagementBrowserScenario(id = "PW-SHELL-011",
            requirement = "UI design: the monitoring deep link separates database and runtime telemetry",
            area = ManagementBrowserArea.SHELL, risk = ManagementBrowserRisk.HIGH,
            action = "Open Monitoring with a connected PostgreSQL setup",
            expectedResult = "Database, runtime, activity, and live metrics surfaces are independently visible",
            cleanup = "Close the metrics stream, context, server, and worker schema",
            operations = {"getDatabaseMonitoring", "getRuntimeMonitoring", "listActivity", "streamMetrics"},
            evidence = {ManagementBrowserEvidence.VISIBLE_RESULT, ManagementBrowserEvidence.HTTP_OPERATION,
            ManagementBrowserEvidence.DATABASE, ManagementBrowserEvidence.RESOURCE_CLEANUP})
    @Test
    void monitoringDeepLinkSeparatesTelemetryScopes() throws Exception {
        prepared(context -> {
            Page page = navigate(context, "/ui/monitoring", "Monitoring");
            assertThat(page.getByText("Database-wide", new Page.GetByTextOptions().setExact(true))).isVisible();
            assertThat(page.getByText("Management-server-local",
                    new Page.GetByTextOptions().setExact(true))).isVisible();
            assertThat(page.getByText("Live metrics connected",
                    new Page.GetByTextOptions().setExact(true))).isVisible();
        });
    }

    @ManagementBrowserScenario(id = "PW-SHELL-012",
            requirement = "UI design: Settings reports effective connection, session, transport, and setup state",
            area = ManagementBrowserArea.SHELL, risk = ManagementBrowserRisk.MEDIUM,
            action = "Open Settings after selecting a setup",
            expectedResult = "Connection and transport facts identify local-token authentication and the active setup",
            cleanup = "Close the isolated context and reset the schema",
            operations = {"getSetupCapabilities"}, evidence = {ManagementBrowserEvidence.VISIBLE_RESULT,
            ManagementBrowserEvidence.HTTP_OPERATION, ManagementBrowserEvidence.RESOURCE_CLEANUP})
    @Test
    void settingsDeepLinkReportsEffectiveConnectionState() throws Exception {
        prepared(context -> {
            Page page = navigate(context, "/ui/settings", "Settings");
            assertThat(page.getByText("LOCAL_TOKEN", new Page.GetByTextOptions().setExact(true))).isVisible();
            assertThat(page.getByText(ManagementConsolePostgresFixture.SETUP_ID,
                    new Page.GetByTextOptions().setExact(true))).isVisible();
            assertThat(page.getByText("Automatic bounded exponential backoff",
                    new Page.GetByTextOptions().setExact(true))).isVisible();
        });
    }

    @ManagementBrowserScenario(id = "PW-SHELL-013",
            requirement = "UI design: browser back and forward navigation restore meaningful populated routes",
            area = ManagementBrowserArea.SHELL, risk = ManagementBrowserRisk.HIGH,
            action = "Navigate Namespaces then Counters and use browser back and forward",
            expectedResult = "History restores each populated route without losing active setup scope",
            cleanup = "Close the isolated history and reset the schema",
            operations = {"listNamespaces", "listCounters"}, evidence = {ManagementBrowserEvidence.VISIBLE_RESULT,
            ManagementBrowserEvidence.HTTP_OPERATION, ManagementBrowserEvidence.DATABASE,
            ManagementBrowserEvidence.RESOURCE_CLEANUP})
    @Test
    void browserHistoryRestoresPopulatedRoutes() throws Exception {
        prepared(context -> {
            Page page = navigate(context, "/ui/namespaces", "Namespaces");
            navigate(context, "/ui/counters", "Counters");
            page.goBack();
            heading(page, "Namespaces");
            page.goForward();
            heading(page, "Counters");
            assertThat(page.getByTitle("Active setup scope"))
                    .hasText("Setup: " + ManagementConsolePostgresFixture.SETUP_ID);
        });
    }

    @ManagementBrowserScenario(id = "PW-SHELL-014",
            requirement = "UI design: unknown client routes recover to the scoped overview",
            area = ManagementBrowserArea.SHELL, risk = ManagementBrowserRisk.MEDIUM,
            action = "Navigate to an unknown non-asset UI route",
            expectedResult = "The client router replaces it with Overview while retaining setup scope",
            cleanup = "Close the isolated context and reset the schema",
            operations = {"getOverview", "getDatabaseMonitoring", "getRuntimeMonitoring", "listActivity"}, evidence = {ManagementBrowserEvidence.VISIBLE_RESULT,
            ManagementBrowserEvidence.HTTP_OPERATION, ManagementBrowserEvidence.RESOURCE_CLEANUP})
    @Test
    void unknownClientRouteRecoversToOverview() throws Exception {
        prepared(context -> {
            Page page = navigate(context, "/ui/not-a-real-management-route", "Overview");
            assertEquals(context.origin() + "/ui", page.url());
            assertThat(page.getByTitle("Active setup scope"))
                    .hasText("Setup: " + ManagementConsolePostgresFixture.SETUP_ID);
        });
    }

    @ManagementBrowserScenario(id = "PW-SHELL-015",
            requirement = "UI design: missing asset-like paths are not rewritten to the application shell",
            area = ManagementBrowserArea.SHELL, risk = ManagementBrowserRisk.HIGH,
            action = "Navigate the browser to a missing JavaScript asset beneath /ui",
            expectedResult = "The real server returns 404 and does not render the Overview application",
            cleanup = "Close the isolated failed navigation and all fixture resources",
            evidence = {ManagementBrowserEvidence.VISIBLE_RESULT, ManagementBrowserEvidence.RESOURCE_CLEANUP})
    @Test
    void missingAssetLikePathIsNotRewrittenToShell() throws Exception {
        prepared(context -> {
            context.diagnostics().expectFailedResponse(404, "/ui/missing-bundle.js");
            Page page = context.page();
            assertEquals(404, page.navigate(context.origin() + "/ui/missing-bundle.js").status());
            assertThat(page.getByRole(AriaRole.HEADING,
                    new Page.GetByRoleOptions().setName("Overview").setExact(true))).hasCount(0);
        });
    }

    @ManagementBrowserScenario(id = "PW-SHELL-016",
            requirement = "UI design: encoded namespace identifiers round-trip Unicode, slash, and percent characters",
            area = ManagementBrowserArea.SHELL, risk = ManagementBrowserRisk.HIGH,
            action = "Seed and open a namespace named orders/東京% through its encoded deep link",
            expectedResult = "The exact decoded identifier is rendered and selected without path corruption",
            cleanup = "Remove the special namespace through schema reset and close the context",
            operations = {"getNamespace"}, evidence = {ManagementBrowserEvidence.VISIBLE_RESULT,
            ManagementBrowserEvidence.HTTP_OPERATION, ManagementBrowserEvidence.DATABASE,
            ManagementBrowserEvidence.RESOURCE_CLEANUP})
    @Test
    void encodedNamespaceRoundTripsSpecialCharacters() throws Exception {
        prepared(context -> {
            String namespace = "orders/東京%";
            ManagementConsolePostgresFixture.execute(context.postgres(), """
                    INSERT INTO peegee_cache.cache_entries
                        (namespace, cache_key, value_type, value_bytes, numeric_value, version)
                    VALUES ('orders/東京%', 'special', 'STRING', convert_to('safe', 'UTF8'), NULL, 1)
                    """);
            Page page = navigate(context, "/ui/namespaces/" + encoded(namespace), namespace);
            assertEquals("{\"setupId\":\"browser-postgres\",\"namespace\":\"orders/東京%\"}",
                    page.evaluate("sessionStorage.getItem('peegeeq-cache.scope.v1')"));
        });
    }

    @ManagementBrowserScenario(id = "PW-SHELL-017",
            requirement = "UI design: encoded entry keys round-trip Unicode, slash, and percent characters",
            area = ManagementBrowserArea.SHELL, risk = ManagementBrowserRisk.CRITICAL,
            action = "Seed and open customer/東京% through its encoded entry deep link",
            expectedResult = "The exact key is rendered while its value remains masked",
            cleanup = "Remove the special entry through schema reset and close sensitive browser state",
            operations = {"getEntry"}, evidence = {ManagementBrowserEvidence.VISIBLE_RESULT,
            ManagementBrowserEvidence.HTTP_OPERATION, ManagementBrowserEvidence.DATABASE,
            ManagementBrowserEvidence.SENSITIVE_STATE, ManagementBrowserEvidence.RESOURCE_CLEANUP})
    @Test
    void encodedEntryKeyRoundTripsSpecialCharacters() throws Exception {
        prepared(context -> {
            String key = "customer/東京%";
            ManagementConsolePostgresFixture.execute(context.postgres(), """
                    INSERT INTO peegee_cache.cache_entries
                        (namespace, cache_key, value_type, value_bytes, numeric_value, version)
                    VALUES ('logical-orders', 'customer/東京%', 'STRING',
                            convert_to('special-secret', 'UTF8'), NULL, 1)
                    """);
            Page page = navigate(context, entryPath(ManagementConsolePostgresFixture.NAMESPACE, key), key);
            assertThat(page.getByText("Value hidden", new Page.GetByTextOptions().setExact(true))).isVisible();
            assertFalse(page.content().contains("special-secret"));
        });
    }

    @ManagementBrowserScenario(id = "PW-SHELL-018",
            requirement = "UI design: authenticated entry deep links survive a full browser reload",
            area = ManagementBrowserArea.SHELL, risk = ManagementBrowserRisk.HIGH,
            action = "Open customer:1 directly and reload the document",
            expectedResult = "The server hosts the deep link and the UI restores the same masked entry",
            cleanup = "Close the reloaded context and reset the schema",
            operations = {"getEntry"}, evidence = {ManagementBrowserEvidence.VISIBLE_RESULT,
            ManagementBrowserEvidence.HTTP_OPERATION, ManagementBrowserEvidence.DATABASE,
            ManagementBrowserEvidence.SENSITIVE_STATE, ManagementBrowserEvidence.RESOURCE_CLEANUP})
    @Test
    void entryDeepLinkSurvivesFullReload() throws Exception {
        prepared(context -> {
            Page page = navigate(context, entryPath(ManagementConsolePostgresFixture.NAMESPACE, "customer:1"),
                    "customer:1");
            assertEquals(200, page.reload().status());
            heading(page, "customer:1");
            assertThat(page.getByText("Value hidden", new Page.GetByTextOptions().setExact(true))).isVisible();
        });
    }

    @ManagementBrowserScenario(id = "PW-SHELL-019",
            requirement = "UI design: the harmless theme preference persists across reloads",
            area = ManagementBrowserArea.SHELL, risk = ManagementBrowserRisk.MEDIUM,
            action = "Switch to dark theme from the shell and reload",
            expectedResult = "Dark theme remains active and only the allowlisted preference record is stored",
            cleanup = "Close the isolated preference context and reset the schema",
            evidence = {ManagementBrowserEvidence.VISIBLE_RESULT, ManagementBrowserEvidence.RESOURCE_CLEANUP})
    @Test
    void darkThemePersistsAcrossReload() throws Exception {
        prepared(context -> {
            Page page = context.page();
            page.getByLabel("Use dark theme").click();
            assertThat(page.locator("[data-theme=dark]")).isVisible();
            page.reload();
            assertThat(page.locator("[data-theme=dark]")).isVisible();
            assertEquals(1, ((Number) page.evaluate("localStorage.length")).intValue());
        });
    }

    @ManagementBrowserScenario(id = "PW-SHELL-020",
            requirement = "UI design: timezone display preference persists independently of authentication state",
            area = ManagementBrowserArea.SHELL, risk = ManagementBrowserRisk.MEDIUM,
            action = "Set timezone to UTC in Settings and reload",
            expectedResult = "UTC remains selected without changing active session or setup scope",
            cleanup = "Close the isolated preference context and reset the schema",
            evidence = {ManagementBrowserEvidence.VISIBLE_RESULT, ManagementBrowserEvidence.RESOURCE_CLEANUP})
    @Test
    void timezonePreferencePersistsAcrossReload() throws Exception {
        prepared(context -> {
            Page page = navigate(context, "/ui/settings", "Settings");
            page.getByLabel("Timezone").selectOption("UTC");
            assertThat(page.getByRole(AriaRole.STATUS)).containsText("Display preferences saved");
            page.reload();
            assertThat(page.getByLabel("Timezone")).hasValue("UTC");
            assertThat(page.getByTitle("Active setup scope"))
                    .hasText("Setup: " + ManagementConsolePostgresFixture.SETUP_ID);
        });
    }

    @ManagementBrowserScenario(id = "PW-SHELL-021",
            requirement = "UI design: refresh preference accepts only reviewed intervals and survives reload",
            area = ManagementBrowserArea.SHELL, risk = ManagementBrowserRisk.MEDIUM,
            action = "Set the monitoring refresh interval to 60 seconds and reload Settings",
            expectedResult = "The reviewed 60-second value persists in the allowlisted preference record",
            cleanup = "Close the isolated preference context and reset the schema",
            evidence = {ManagementBrowserEvidence.VISIBLE_RESULT, ManagementBrowserEvidence.RESOURCE_CLEANUP})
    @Test
    void refreshIntervalPreferencePersistsAcrossReload() throws Exception {
        prepared(context -> {
            Page page = navigate(context, "/ui/settings", "Settings");
            page.getByLabel("Refresh interval").selectOption("60");
            assertThat(page.getByRole(AriaRole.STATUS)).containsText("Display preferences saved");
            page.reload();
            assertThat(page.getByLabel("Refresh interval")).hasValue("60");
            assertTrue(String.valueOf(page.evaluate(
                    "localStorage.getItem('peegeeq.management.preferences')")).contains("\"refreshSeconds\":60"));
        });
    }

    @ManagementBrowserScenario(id = "PW-SHELL-022",
            requirement = "UI design: the explicit no-setup state directs the user without issuing database requests",
            area = ManagementBrowserArea.SHELL, risk = ManagementBrowserRisk.MEDIUM,
            action = "Authenticate and open Counters before registering or selecting a setup",
            expectedResult = "The shell identifies that no setup is selected and directs the user to connect one",
            cleanup = "Close the setup-free browser and server lifecycle",
            evidence = {ManagementBrowserEvidence.VISIBLE_RESULT, ManagementBrowserEvidence.RESOURCE_CLEANUP})
    @Test
    void explicitNoSetupStateDirectsUserToConnectSetup() throws Exception {
        unprepared(context -> {
            Page page = navigate(context, "/ui/counters", "Counters");
            assertThat(page.getByTitle("Active setup scope")).hasText("No setup selected");
            assertThat(page.getByText("Select a connected setup before inspecting counters.",
                    new Page.GetByTextOptions().setExact(true))).isVisible();
        });
    }

    private void prepared(BrowserAction action) throws Exception {
        ManagementConsolePostgresFixture.run(
                temporaryDirectory, POSTGRES.postgres(), true, context -> {
                    action.run(context);
                });
    }

    private void unprepared(BrowserAction action) throws Exception {
        ManagementConsolePostgresFixture.run(
                temporaryDirectory, POSTGRES.postgres(), false, context -> {
                    ManagementConsolePostgresFixture.authenticate(context);
                    action.run(context);
                });
    }

    private static Page navigate(
            ManagementConsolePostgresFixture.Context context,
            String path,
            String heading) {
        Page page = context.page();
        assertEquals(200, page.navigate(context.origin() + path).status());
        heading(page, heading);
        return page;
    }

    private static void heading(Page page, String name) {
        assertThat(page.getByRole(AriaRole.HEADING,
                new Page.GetByRoleOptions().setName(name).setExact(true))).isVisible();
    }

    private static String entryPath(String namespace, String key) {
        return "/ui/keys/" + encoded(namespace) + "/" + encoded(key);
    }

    private static String encoded(String value) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    @FunctionalInterface
    private interface BrowserAction {
        void run(ManagementConsolePostgresFixture.Context context) throws Exception;
    }
}
