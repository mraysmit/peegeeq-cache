package dev.mars.peegeeq.cache.rest.server;

import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.AriaRole;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;

class ManagementViewerBrowserIT {

    @RegisterExtension
    static final ManagementBrowserPostgresWorker POSTGRES = new ManagementBrowserPostgresWorker();

    @TempDir
    Path temporaryDirectory;

    @ManagementBrowserScenario(id = "PW-VIEWER-001",
            requirement = "UI design: viewer setup inventory is read-only",
            area = ManagementBrowserArea.SETUP, risk = ManagementBrowserRisk.CRITICAL,
            action = "Register a setup as an operator, rotate the trusted identity to viewer, and open Setups",
            expectedResult = "The setup remains visible while every setup mutation control is absent",
            cleanup = "Close the trusted-proxy session, setup runtime, browser, server, and PostgreSQL resources",
            evidence = {ManagementBrowserEvidence.VISIBLE_RESULT, ManagementBrowserEvidence.RESOURCE_CLEANUP})
    @Test
    void viewerSetupInventoryHasNoMutationControls() throws Exception {
        prepared(context -> {
            Page page = openAsViewer(context, "/ui/setups");
            assertThat(page.getByText(ManagementConsolePostgresFixture.SETUP_NAME,
                    new Page.GetByTextOptions().setExact(true))).isVisible();
            assertThat(page.getByRole(AriaRole.BUTTON,
                    new Page.GetByRoleOptions().setName("Register setup"))).hasCount(0);
            assertThat(page.getByRole(AriaRole.BUTTON,
                    new Page.GetByRoleOptions().setName("Test").setExact(true))).hasCount(0);
            assertThat(page.getByRole(AriaRole.BUTTON,
                    new Page.GetByRoleOptions().setName("Detach").setExact(true))).hasCount(0);
            assertThat(page.getByRole(AriaRole.BUTTON,
                    new Page.GetByRoleOptions().setName("Forget").setExact(true))).hasCount(0);
        });
    }

    @ManagementBrowserScenario(id = "PW-VIEWER-002",
            requirement = "UI design: viewer entry inventory and details are metadata-only",
            area = ManagementBrowserArea.ENTRY, risk = ManagementBrowserRisk.CRITICAL,
            action = "Browse a seeded entry and open its details after rotating to viewer",
            expectedResult = "Metadata remains available without create, bulk delete, edit, delete, or reveal controls",
            cleanup = "Clear entry detail state and close trusted-proxy, browser, server, and PostgreSQL resources",
            operations = {"listNamespaces", "getNamespace", "listEntries", "getEntry"},
            evidence = {ManagementBrowserEvidence.VISIBLE_RESULT, ManagementBrowserEvidence.HTTP_OPERATION,
                    ManagementBrowserEvidence.SENSITIVE_STATE, ManagementBrowserEvidence.RESOURCE_CLEANUP})
    @Test
    void viewerEntriesRemainMetadataOnly() throws Exception {
        prepared(context -> {
            Page page = openAsViewer(context, "/ui/namespaces");
            page.getByRole(AriaRole.LINK,
                    new Page.GetByRoleOptions().setName("logical-orders").setExact(true)).click();
            page.getByRole(AriaRole.TAB,
                    new Page.GetByRoleOptions().setName("Entries").setExact(true)).click();
            page.getByRole(AriaRole.LINK,
                    new Page.GetByRoleOptions().setName("customer:1").setExact(true)).click();
            assertThat(page.getByText("Value hidden", new Page.GetByTextOptions().setExact(true))).isVisible();
            assertThat(page.getByRole(AriaRole.BUTTON,
                    new Page.GetByRoleOptions().setName("Create entry").setExact(true))).hasCount(0);
            assertThat(page.getByRole(AriaRole.BUTTON,
                    new Page.GetByRoleOptions().setName("Preview selected deletion"))).hasCount(0);
            assertThat(page.getByRole(AriaRole.BUTTON,
                    new Page.GetByRoleOptions().setName("Reveal value").setExact(true))).hasCount(0);
            assertThat(page.getByRole(AriaRole.BUTTON,
                    new Page.GetByRoleOptions().setName("Edit entry").setExact(true))).hasCount(0);
            assertThat(page.getByRole(AriaRole.BUTTON,
                    new Page.GetByRoleOptions().setName("Delete entry").setExact(true))).hasCount(0);
        });
    }

    @ManagementBrowserScenario(id = "PW-VIEWER-003",
            requirement = "UI design: viewer counter inventory is read-only",
            area = ManagementBrowserArea.COUNTER, risk = ManagementBrowserRisk.CRITICAL,
            action = "Open exact counter inventory after rotating the trusted identity to viewer",
            expectedResult = "Exact values remain visible without create, selection, manage, or bulk-delete controls",
            cleanup = "Close the viewer counter page and all fixture resources",
            operations = {"listCounters"},
            evidence = {ManagementBrowserEvidence.VISIBLE_RESULT, ManagementBrowserEvidence.HTTP_OPERATION,
                    ManagementBrowserEvidence.RESOURCE_CLEANUP})
    @Test
    void viewerCountersAreReadOnly() throws Exception {
        prepared(context -> {
            Page page = openAsViewer(context, "/ui/counters");
            assertThat(page.getByText("42", new Page.GetByTextOptions().setExact(true))).isVisible();
            assertThat(page.getByRole(AriaRole.BUTTON,
                    new Page.GetByRoleOptions().setName("Create counter"))).hasCount(0);
            assertThat(page.getByLabel("Select logical-orders/count")).hasCount(0);
            assertThat(page.getByRole(AriaRole.BUTTON,
                    new Page.GetByRoleOptions().setName("Manage count"))).hasCount(0);
            assertThat(page.getByRole(AriaRole.BUTTON,
                    new Page.GetByRoleOptions().setName("Preview selected counter deletion"))).hasCount(0);
        });
    }

    @ManagementBrowserScenario(id = "PW-VIEWER-004",
            requirement = "UI design: viewer lock inventory never exposes owner or force-release actions",
            area = ManagementBrowserArea.LOCK, risk = ManagementBrowserRisk.CRITICAL,
            action = "Open the active lock inventory after rotating the trusted identity to viewer",
            expectedResult = "Lease metadata and masked owner remain visible without any management action",
            cleanup = "Close the viewer lock page and all fixture resources",
            operations = {"listLocks"},
            evidence = {ManagementBrowserEvidence.VISIBLE_RESULT, ManagementBrowserEvidence.HTTP_OPERATION,
                    ManagementBrowserEvidence.SENSITIVE_STATE, ManagementBrowserEvidence.RESOURCE_CLEANUP})
    @Test
    void viewerLocksAreMaskedAndReadOnly() throws Exception {
        prepared(context -> {
            Page page = openAsViewer(context, "/ui/locks");
            assertThat(page.getByText("Masked", new Page.GetByTextOptions().setExact(true))).isVisible();
            assertThat(page.getByRole(AriaRole.BUTTON,
                    new Page.GetByRoleOptions().setName("Manage lease"))).hasCount(0);
            assertThat(page.getByText("owner-secret", new Page.GetByTextOptions().setExact(true))).hasCount(0);
        });
    }

    @ManagementBrowserScenario(id = "PW-VIEWER-005",
            requirement = "UI design: viewer Pub/Sub permits bounded subscription but not publishing or reveal",
            area = ManagementBrowserArea.PUBSUB, risk = ManagementBrowserRisk.CRITICAL,
            action = "Open Pub/Sub after rotating the trusted identity to viewer",
            expectedResult = "Subscription controls remain while publishing and payload reveal controls are absent",
            cleanup = "Close the viewer Pub/Sub page without opening a transport and close all fixture resources",
            evidence = {ManagementBrowserEvidence.VISIBLE_RESULT, ManagementBrowserEvidence.SENSITIVE_STATE,
                    ManagementBrowserEvidence.RESOURCE_CLEANUP})
    @Test
    void viewerPubSubExposesSubscriptionButNotPublishOrReveal() throws Exception {
        prepared(context -> {
            Page page = openAsViewer(context, "/ui/pubsub");
            assertThat(page.getByRole(AriaRole.HEADING,
                    new Page.GetByRoleOptions().setName("Subscribe").setExact(true))).isVisible();
            assertThat(page.getByRole(AriaRole.BUTTON,
                    new Page.GetByRoleOptions().setName("Start subscription"))).isVisible();
            assertThat(page.getByRole(AriaRole.HEADING,
                    new Page.GetByRoleOptions().setName("Publish").setExact(true))).hasCount(0);
            assertThat(page.getByRole(AriaRole.BUTTON,
                    new Page.GetByRoleOptions().setName("Reveal payload"))).hasCount(0);
        });
    }

    private void prepared(ManagementConsolePostgresFixture.Journey journey) throws Exception {
        ManagementConsolePostgresFixture.runTrustedProxy(
                temporaryDirectory, POSTGRES.postgres(), true, journey);
    }

    private static Page openAsViewer(
            ManagementConsolePostgresFixture.Context context,
            String path) {
        ManagementConsolePostgresFixture.authenticate(context);
        ManagementConsolePostgresFixture.registerSetup(context);
        context.browserContext().setExtraHTTPHeaders(Map.of(
                "X-PeeGeeQ-User", "browser-viewer",
                "X-PeeGeeQ-Roles", "viewer"));
        Page page = context.page();
        page.navigate(context.origin() + path);
        assertThat(page.getByLabel("Session and connection status").getByText(
                "Viewer", new com.microsoft.playwright.Locator.GetByTextOptions().setExact(true))).isVisible();
        return page;
    }
}
