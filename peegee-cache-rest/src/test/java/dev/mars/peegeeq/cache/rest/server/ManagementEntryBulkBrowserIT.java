package dev.mars.peegeeq.cache.rest.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Response;
import com.microsoft.playwright.options.AriaRole;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Wave P3 real-browser scenarios for bulk-entry preview, execution, and fail-closed behavior. */
class ManagementEntryBulkBrowserIT {

    @RegisterExtension
    static final ManagementBrowserPostgresWorker POSTGRES = new ManagementBrowserPostgresWorker();

    @TempDir
    Path temporaryDirectory;

    private static final List<String> ACTIONS = List.of(
            "enable selected deletion after one row is selected",
            "disable selected deletion after deselection",
            "visibly mark a selected row",
            "open an explicit-target preview",
            "show the preview setup scope",
            "show the preview namespace scope",
            "show the exact resolved target count",
            "show the exact resolved byte total",
            "show a bounded target sample",
            "show the server-issued confirmation phrase",
            "disable autocomplete on destructive confirmation",
            "disable execution for a blank phrase",
            "disable execution for a mismatched phrase",
            "enable execution only for the exact phrase",
            "cancel preview without deleting",
            "dismiss preview with Escape without deleting",
            "execute one explicit-target deletion",
            "preview two explicit versioned targets",
            "preview all matching live entries",
            "preview only a matching key prefix",
            "preview only a matching value type",
            "preview only persistent entries",
            "execute a matching-filter deletion",
            "report a concurrent partial conflict",
            "reject replay of a consumed preview token",
            "exclude preview tokens and values from visible and URL state");

    static List<ManagementBrowserCase> scenarios() {
        return java.util.stream.IntStream.range(0, ACTIONS.size()).mapToObj(index -> {
            List<String> operations = index < 3
                    ? List.of("listNamespaces", "getNamespace", "listEntries")
                    : index == 16 || index == 22 || index == 23 || index == 24
                    ? List.of("listNamespaces", "getNamespace", "listEntries",
                    "previewEntryBulkDelete", "executeEntryBulkDelete")
                    : List.of("listNamespaces", "getNamespace", "listEntries", "previewEntryBulkDelete");
            Set<ManagementBrowserEvidence> evidence = operations.contains("executeEntryBulkDelete")
                    ? Set.of(ManagementBrowserEvidence.VISIBLE_RESULT, ManagementBrowserEvidence.HTTP_OPERATION,
                    ManagementBrowserEvidence.DATABASE, ManagementBrowserEvidence.DURABLE_AUDIT,
                    ManagementBrowserEvidence.RESOURCE_CLEANUP)
                    : operations.contains("previewEntryBulkDelete")
                    ? Set.of(ManagementBrowserEvidence.VISIBLE_RESULT, ManagementBrowserEvidence.HTTP_OPERATION,
                    ManagementBrowserEvidence.DATABASE, ManagementBrowserEvidence.DURABLE_AUDIT,
                    ManagementBrowserEvidence.RESOURCE_CLEANUP)
                    : Set.of(ManagementBrowserEvidence.VISIBLE_RESULT, ManagementBrowserEvidence.HTTP_OPERATION,
                    ManagementBrowserEvidence.DATABASE, ManagementBrowserEvidence.RESOURCE_CLEANUP);
            return new ManagementBrowserCase(
                    "PW-ENTRY-%03d".formatted(index + 107),
                    "P3 bulk-entry contract: " + ACTIONS.get(index),
                    ManagementBrowserArea.ENTRY,
                    index >= 16 ? ManagementBrowserRisk.CRITICAL : ManagementBrowserRisk.HIGH,
                    ACTIONS.get(index),
                    "The packaged console must " + ACTIONS.get(index) + ".",
                    "Close the preview, reset PostgreSQL, and close browser, server, audit, and pool resources",
                    operations,
                    evidence);
        }).toList();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("dev.mars.peegeeq.cache.rest.server.ManagementBrowserSelection#entryBulkScenarios")
    void entryBulkScenario(ManagementBrowserCase scenario) throws Exception {
        int index = Integer.parseInt(scenario.id().substring(scenario.id().length() - 3)) - 107;
        ManagementConsolePostgresFixture.run(
                temporaryDirectory, POSTGRES.postgres(), true, scenario.operations(), context -> {
                    if (index == 24) context.diagnostics().expectFailedResponse(409,
                            "/api/v1/setups/{setupId}/namespaces/{encodedNamespace}/entries/bulk-delete/execute");
                    ManagementConsolePostgresFixture.authenticate(context);
                    ManagementConsolePostgresFixture.registerSetup(context);
                    openEntries(context.page());
                    verify(index, context);
                });
    }

    private static void verify(int index, ManagementConsolePostgresFixture.Context context) throws Exception {
        Page page = context.page();
        switch (index) {
            case 0 -> { select(page, "customer:1"); assertThat(selectedPreviewButton(page)).isEnabled(); }
            case 1 -> { select(page, "customer:1"); page.getByLabel("Select customer:1").uncheck(); assertThat(selectedPreviewButton(page)).isDisabled(); }
            case 2 -> { select(page, "customer:1"); assertThat(row(page, "customer:1")).hasAttribute("data-selected", "true"); }
            case 3 -> assertThat(selectedPreview(page, "customer:1")).isVisible();
            case 4 -> assertThat(selectedPreview(page, "customer:1")).containsText("Setup browser-postgres");
            case 5 -> assertThat(selectedPreview(page, "customer:1")).containsText("namespace logical-orders");
            case 6 -> assertThat(selectedPreview(page, "customer:1")).containsText("Resolved 1 entries");
            case 7 -> assertThat(selectedPreview(page, "customer:1")).containsText("12 B");
            case 8 -> assertThat(selectedPreview(page, "customer:1")).containsText("Sample: customer:1");
            case 9 -> assertTrue(phrase(selectedPreview(page, "customer:1")).startsWith("DELETE "));
            case 10 -> assertThat(selectedPreview(page, "customer:1").getByLabel("Type confirmation phrase")).hasAttribute("autocomplete", "off");
            case 11 -> assertThat(deleteButton(selectedPreview(page, "customer:1"))).isDisabled();
            case 12 -> { Locator dialog = selectedPreview(page, "customer:1"); dialog.getByLabel("Type confirmation phrase").fill("wrong"); assertThat(deleteButton(dialog)).isDisabled(); }
            case 13 -> { Locator dialog = selectedPreview(page, "customer:1"); dialog.getByLabel("Type confirmation phrase").fill(phrase(dialog)); assertThat(deleteButton(dialog)).isEnabled(); }
            case 14 -> { Locator dialog = selectedPreview(page, "customer:1"); dialog.getByRole(AriaRole.BUTTON, new Locator.GetByRoleOptions().setName("Cancel")).click(); assertThat(dialog).hasCount(0); assertThat(link(page, "customer:1")).isVisible(); }
            case 15 -> { Locator dialog = selectedPreview(page, "customer:1"); page.keyboard().press("Escape"); assertThat(dialog).hasCount(0); assertThat(link(page, "customer:1")).isVisible(); }
            case 16 -> { Locator dialog = selectedPreview(page, "customer:1"); execute(dialog); assertThat(page.getByRole(AriaRole.STATUS)).containsText("Deleted 1 of 1"); assertThat(link(page, "customer:1")).hasCount(0); assertEquals("0", scalar(context, "SELECT count(*) FROM peegee_cache.cache_entries WHERE cache_key='customer:1'")); }
            case 17 -> { select(page, "customer:1"); select(page, "json-record"); selectedPreviewButton(page).click(); assertThat(bulkDialog(page)).containsText("Resolved 2 entries"); assertThat(bulkDialog(page)).containsText("customer:1"); assertThat(bulkDialog(page)).containsText("json-record"); }
            case 18 -> { matchingPreview(page); assertThat(bulkDialog(page)).containsText("Resolved 4 entries"); }
            case 19 -> { filter(page, "customer", "ALL", "ALL_LIVE"); matchingPreview(page); assertThat(bulkDialog(page)).containsText("Resolved 1 entries"); assertThat(bulkDialog(page)).containsText("customer:1"); }
            case 20 -> { filter(page, "", "JSON", "ALL_LIVE"); matchingPreview(page); assertThat(bulkDialog(page)).containsText("Resolved 1 entries"); assertThat(bulkDialog(page)).containsText("json-record"); }
            case 21 -> { filter(page, "", "ALL", "PERSISTENT"); matchingPreview(page); assertThat(bulkDialog(page)).containsText("Resolved 4 entries"); }
            case 22 -> { matchingPreview(page); execute(bulkDialog(page)); assertThat(page.getByRole(AriaRole.STATUS)).containsText("Deleted 4 of 4"); assertThat(page.getByRole(AriaRole.HEADING, new Page.GetByRoleOptions().setName("No entries matched"))).isVisible(); assertEquals("0", scalar(context, "SELECT count(*) FROM peegee_cache.cache_entries WHERE namespace='logical-orders'")); }
            case 23 -> { select(page, "customer:1"); select(page, "json-record"); selectedPreviewButton(page).click(); ManagementConsolePostgresFixture.execute(context.postgres(), "UPDATE peegee_cache.cache_entries SET version=version+1 WHERE namespace='logical-orders' AND cache_key='customer:1'"); execute(bulkDialog(page)); assertThat(page.getByRole(AriaRole.STATUS)).containsText("Deleted 1 of 2"); assertThat(page.getByRole(AriaRole.STATUS)).containsText("conflicts 1"); assertThat(link(page, "customer:1")).isVisible(); assertEquals("1", scalar(context, "SELECT count(*) FROM peegee_cache.cache_entries WHERE cache_key IN ('customer:1','json-record')")); }
            case 24 -> assertReplayRejected(page);
            case 25 -> assertPreviewStateIsSecretFree(page);
            default -> throw new IllegalArgumentException("Unknown scenario " + index);
        }
    }

    private static void assertReplayRejected(Page page) throws Exception {
        select(page, "customer:1");
        Response response = page.waitForResponse(candidate -> candidate.url().endsWith("/entries/bulk-delete/preview"), () -> selectedPreviewButton(page).click());
        JsonNode body = new ObjectMapper().readTree(response.text());
        Locator dialog = bulkDialog(page);
        execute(dialog);
        @SuppressWarnings("unchecked")
        Map<String, Object> replay = (Map<String, Object>) page.evaluate("""
                async ([token, phrase]) => {
                  const session = await (await fetch('/api/v1/session', {credentials:'include'})).json();
                  const response = await fetch('/api/v1/setups/browser-postgres/namespaces/bG9naWNhbC1vcmRlcnM/entries/bulk-delete/execute', {
                    method:'POST', credentials:'include',
                    headers:{'content-type':'application/json','x-peegeeq-csrf':session.csrfToken},
                    body:JSON.stringify({previewToken:token,confirmationPhrase:phrase})
                  });
                  return {status:response.status, body:await response.json()};
                }
                """, List.of(body.path("previewToken").asText(), body.path("confirmationPhrase").asText()));
        assertEquals(409, ((Number) replay.get("status")).intValue());
        assertTrue(String.valueOf(replay.get("body")).contains("PREVIEW_TOKEN_INVALID"));
    }

    private static void assertPreviewStateIsSecretFree(Page page) throws Exception {
        select(page, "customer:1");
        Response response = page.waitForResponse(candidate -> candidate.url().endsWith("/entries/bulk-delete/preview"), () -> selectedPreviewButton(page).click());
        JsonNode body = new ObjectMapper().readTree(response.text());
        String token = body.path("previewToken").asText();
        assertFalse(token.isBlank());
        assertFalse(page.content().contains(token));
        assertFalse(page.url().contains(token));
        assertFalse(page.content().contains("stored-value"));
    }

    private static void openEntries(Page page) { page.getByRole(AriaRole.LINK, new Page.GetByRoleOptions().setName("Namespaces").setExact(true)).click(); page.getByRole(AriaRole.LINK, new Page.GetByRoleOptions().setName("logical-orders").setExact(true)).click(); page.getByRole(AriaRole.TAB, new Page.GetByRoleOptions().setName("Entries")).click(); assertThat(page.getByRole(AriaRole.HEADING, new Page.GetByRoleOptions().setName("Key Browser").setExact(true))).isVisible(); }
    private static void select(Page page, String key) { page.getByLabel("Select " + key).check(); }
    private static Locator selectedPreviewButton(Page page) { return page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Preview selected deletion")); }
    private static Locator selectedPreview(Page page, String key) { select(page, key); selectedPreviewButton(page).click(); return bulkDialog(page); }
    private static void matchingPreview(Page page) { page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Preview matching-filter deletion")).click(); }
    private static Locator bulkDialog(Page page) { return page.getByRole(AriaRole.DIALOG, new Page.GetByRoleOptions().setName("Confirm bulk entry deletion")); }
    private static Locator deleteButton(Locator dialog) { return dialog.getByRole(AriaRole.BUTTON, new Locator.GetByRoleOptions().setName("Delete previewed entries")); }
    private static String phrase(Locator dialog) { return dialog.locator("strong").last().textContent(); }
    private static void execute(Locator dialog) { dialog.getByLabel("Type confirmation phrase").fill(phrase(dialog)); deleteButton(dialog).click(); }
    private static Locator link(Page page, String key) { return page.getByRole(AriaRole.LINK, new Page.GetByRoleOptions().setName(key).setExact(true)); }
    private static Locator row(Page page, String key) { return page.getByRole(AriaRole.ROW).filter(new Locator.FilterOptions().setHasText(key)); }
    private static void filter(Page page, String prefix, String type, String ttl) { page.getByLabel("Key prefix").fill(prefix); page.locator("#entry-value-type").selectOption(type); page.locator("#entry-ttl-state").selectOption(ttl); page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Apply filters")).click(); }
    private static String scalar(ManagementConsolePostgresFixture.Context context, String sql) throws Exception { return ManagementConsolePostgresFixture.queryScalar(context.postgres(), sql); }
}
