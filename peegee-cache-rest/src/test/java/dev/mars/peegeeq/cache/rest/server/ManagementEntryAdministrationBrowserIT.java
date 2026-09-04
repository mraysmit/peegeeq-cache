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
import java.util.regex.Pattern;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Wave P3 real-browser scenarios for entry creation, mutation, TTL, and concurrency. */
class ManagementEntryAdministrationBrowserIT {

    @RegisterExtension
    static final ManagementBrowserPostgresWorker POSTGRES = new ManagementBrowserPostgresWorker();

    @TempDir
    Path temporaryDirectory;

    private static final List<String> ACTIONS = List.of(
            "open the create-entry dialog",
            "explain absence-checked creation",
            "require a new entry key",
            "bound new entry keys to 1024 characters",
            "offer all four creation value types",
            "default creation to STRING",
            "require positive creation TTL values",
            "require a creation value",
            "cancel creation without mutation",
            "dismiss creation with Escape",
            "expose one unambiguous create submit action",
            "disable selected deletion without selection",
            "enable matching-filter preview",
            "show operator-only entry actions",
            "make each row independently selectable",
            "keep sensitive values out of the inventory DOM",
            "create a persistent STRING entry",
            "create an expiring STRING entry",
            "create a valid JSON entry",
            "create the signed 64-bit maximum",
            "create the signed 64-bit minimum",
            "create a BYTES entry from Base64",
            "create a Unicode key and value",
            "reject duplicate-key creation",
            "reject malformed JSON before transport",
            "reject malformed LONG before transport",
            "reject a zero creation TTL",
            "reject a negative creation TTL",
            "show entry administration on details",
            "open the edit-entry form",
            "cancel edit without mutation",
            "lock the existing value type",
            "offer all four set modes",
            "offer all four TTL modes",
            "disable replacement TTL by default",
            "enable replacement TTL only for REPLACE",
            "require replacement TTL for REPLACE",
            "require a replacement value",
            "default edits to observed-version matching",
            "update STRING with exact version",
            "update STRING with UPSERT",
            "update STRING only when present",
            "reject only-if-absent on an existing key",
            "retain form state after a concurrent version conflict",
            "set a positive entry TTL",
            "make an entry persistent",
            "touch an entry without replacing TTL",
            "touch an entry with a refreshed TTL",
            "require the exact key before deletion",
            "delete the exact observed entry version");

    static List<ManagementBrowserCase> scenarios() {
        return java.util.stream.IntStream.range(0, ACTIONS.size()).mapToObj(index -> {
            List<String> operations = operations(index);
            Set<ManagementBrowserEvidence> evidence = operations.stream().anyMatch(op -> Set.of(
                    "setEntry", "deleteEntry", "expireEntry", "persistEntry", "touchEntry").contains(op))
                    ? Set.of(ManagementBrowserEvidence.VISIBLE_RESULT, ManagementBrowserEvidence.HTTP_OPERATION,
                    ManagementBrowserEvidence.DATABASE, ManagementBrowserEvidence.DURABLE_AUDIT,
                    ManagementBrowserEvidence.RESOURCE_CLEANUP)
                    : Set.of(ManagementBrowserEvidence.VISIBLE_RESULT, ManagementBrowserEvidence.HTTP_OPERATION,
                    ManagementBrowserEvidence.RESOURCE_CLEANUP);
            return new ManagementBrowserCase(
                    "PW-ENTRY-%03d".formatted(index + 57),
                    "P3 entry-administration contract: " + ACTIONS.get(index),
                    ManagementBrowserArea.ENTRY,
                    index >= 16 ? ManagementBrowserRisk.CRITICAL : ManagementBrowserRisk.HIGH,
                    ACTIONS.get(index),
                    "The packaged console must " + ACTIONS.get(index) + ".",
                    "Reset the PostgreSQL schema and close dialog, browser, server, and pool resources",
                    operations,
                    evidence);
        }).toList();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("dev.mars.peegeeq.cache.rest.server.ManagementBrowserSelection#entryAdministrationScenarios")
    void entryAdministrationScenario(ManagementBrowserCase scenario) throws Exception {
        int index = Integer.parseInt(scenario.id().substring(scenario.id().length() - 3)) - 57;
        ManagementConsolePostgresFixture.run(
                temporaryDirectory, POSTGRES.postgres(), true, scenario.operations(), context -> {
                    if (index == 23 || index == 42) context.diagnostics().expectFailedResponse(409,
                            "/api/v1/setups/{setupId}/namespaces/{encodedNamespace}/entries/{encodedKey}");
                    if (index == 43) context.diagnostics().expectFailedResponse(412,
                            "/api/v1/setups/{setupId}/namespaces/{encodedNamespace}/entries/{encodedKey}");
                    if (index == 49) context.diagnostics().allowFailedResponse(404,
                            "/api/v1/setups/{setupId}/namespaces/{encodedNamespace}/entries/{encodedKey}");
                    if (index == 45) {
                        ManagementConsolePostgresFixture.execute(context.postgres(),
                                "UPDATE peegee_cache.cache_entries SET expires_at=clock_timestamp()+interval '5 minutes' WHERE cache_key='customer:1'");
                    }
                    openEntries(context.page());
                    verify(index, context);
                });
    }

    private static List<String> operations(int index) {
        if (index < 16 || index >= 24 && index <= 27) return entryOperations();
        if (index <= 23) return entryOperations("setEntry");
        if (index < 39) return entryOperations("getEntry");
        if (index <= 43) return entryOperations("getEntry", "setEntry");
        return switch (index) {
            case 44 -> entryOperations("getEntry", "expireEntry");
            case 45 -> entryOperations("getEntry", "persistEntry");
            case 46, 47 -> entryOperations("getEntry", "touchEntry");
            case 48 -> entryOperations("getEntry");
            case 49 -> entryOperations("getEntry", "deleteEntry");
            default -> throw new IllegalArgumentException("Unknown scenario " + index);
        };
    }

    private static List<String> entryOperations(String... additional) {
        List<String> operations = new java.util.ArrayList<>(
                List.of("listNamespaces", "getNamespace", "listEntries"));
        operations.addAll(List.of(additional));
        return List.copyOf(operations);
    }

    private static void verify(int index, ManagementConsolePostgresFixture.Context context) throws Exception {
        Page page = context.page();
        switch (index) {
            case 0 -> assertThat(create(page)).isVisible();
            case 1 -> assertThat(create(page).getByText("The key must be absent when PostgreSQL commits this request.")).isVisible();
            case 2 -> assertThat(create(page).getByLabel("Key")).hasAttribute("required", "");
            case 3 -> assertThat(create(page).getByLabel("Key")).hasAttribute("maxlength", "1024");
            case 4 -> assertEquals(List.of("STRING", "JSON", "LONG", "BYTES"), options(page, create(page), "#new-entry-type"));
            case 5 -> assertThat(AntSelect.selection(create(page).locator("#new-entry-type"))).hasAttribute("data-value", "STRING");
            case 6 -> assertThat(create(page).getByLabel("TTL milliseconds (blank for persistent)")).hasAttribute("min", "1");
            case 7 -> assertThat(create(page).locator("#new-entry-value")).hasAttribute("required", "");
            case 8 -> { Locator dialog = create(page); dialog.getByRole(AriaRole.BUTTON, new Locator.GetByRoleOptions().setName("Cancel")).click(); assertThat(dialog).hasCount(0); assertThat(link(page, "customer:1")).isVisible(); }
            case 9 -> { Locator dialog = create(page); dialog.getByRole(AriaRole.BUTTON, new Locator.GetByRoleOptions().setName("Cancel").setExact(true)).press("Escape"); assertThat(dialog).hasCount(0); }
            case 10 -> assertThat(create(page).getByRole(AriaRole.BUTTON, new Locator.GetByRoleOptions().setName("Create entry").setExact(true))).hasCount(1);
            case 11 -> assertThat(page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Preview selected deletion"))).isDisabled();
            case 12 -> assertThat(page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Preview matching-filter deletion"))).isEnabled();
            case 13 -> assertThat(page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Create entry").setExact(true))).isVisible();
            case 14 -> assertThat(page.getByLabel("Select customer:1")).isVisible();
            case 15 -> assertFalse(page.content().contains("stored-value"));
            case 16 -> { createEntry(page, "created-string", "STRING", "created-value", ""); assertEntryExists(context, "created-string", "STRING"); }
            case 17 -> { createEntry(page, "created-ttl", "STRING", "temporary", "60000"); assertEntryExists(context, "created-ttl", "STRING"); assertEquals("true", scalar(context, "SELECT expires_at IS NOT NULL FROM peegee_cache.cache_entries WHERE cache_key='created-ttl'")); }
            case 18 -> { createEntry(page, "created-json", "JSON", "{\"ok\":true}", ""); assertEntryExists(context, "created-json", "JSON"); }
            case 19 -> { createEntry(page, "created-max", "LONG", "9223372036854775807", ""); assertEntryExists(context, "created-max", "LONG"); }
            case 20 -> { createEntry(page, "created-min", "LONG", "-9223372036854775808", ""); assertEntryExists(context, "created-min", "LONG"); }
            case 21 -> { createEntry(page, "created-bytes", "BYTES", "AP9B", ""); assertEntryExists(context, "created-bytes", "BYTES"); }
            case 22 -> { createEntry(page, "客户:🚀", "STRING", "缓存值", ""); assertEntryExists(context, "客户:🚀", "STRING"); }
            case 23 -> { fillCreate(page, "customer:1", "STRING", "duplicate", ""); submitCreate(page); assertThat(page.getByRole(AriaRole.ALERT)).isVisible(); assertThat(createDialog(page)).isVisible(); }
            case 24 -> { fillCreate(page, "bad-json", "JSON", "{bad", ""); submitCreate(page); assertThat(page.getByRole(AriaRole.ALERT)).containsText("JSON_VALUE_INVALID"); }
            case 25 -> { fillCreate(page, "bad-long", "LONG", "1.5", ""); submitCreate(page); assertThat(page.getByRole(AriaRole.ALERT)).containsText("VALUE_TYPE_MISMATCH"); }
            case 26 -> { Locator dialog = create(page); dialog.getByLabel("TTL milliseconds (blank for persistent)").fill("0"); assertFalse((Boolean) dialog.getByLabel("TTL milliseconds (blank for persistent)").evaluate("input => input.checkValidity()")); }
            case 27 -> { Locator dialog = create(page); dialog.getByLabel("TTL milliseconds (blank for persistent)").fill("-1"); assertFalse((Boolean) dialog.getByLabel("TTL milliseconds (blank for persistent)").evaluate("input => input.checkValidity()")); }
            case 28 -> { openDetail(page); assertThat(page.getByRole(AriaRole.HEADING, new Page.GetByRoleOptions().setName("Entry administration"))).isVisible(); }
            case 29 -> { openEdit(page); assertThat(page.getByLabel("Entry value")).isVisible(); }
            case 30 -> { openEdit(page); page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Cancel edit")).click(); assertThat(page.getByLabel("Entry value")).hasCount(0); }
            case 31 -> { openEdit(page); assertThat(page.locator("#entry-edit-type")).isDisabled(); assertThat(page.locator("#entry-edit-type")).hasValue("STRING"); }
            case 32 -> { openEdit(page); assertEquals(List.of("ONLY_IF_VERSION_MATCHES", "UPSERT", "ONLY_IF_PRESENT", "ONLY_IF_ABSENT"), options(page, page.locator("form.form-grid"), "#entry-set-mode")); }
            case 33 -> { openEdit(page); assertEquals(List.of("PRESERVE_EXISTING", "USE_DEFAULT", "REPLACE", "REMOVE"), options(page, page.locator("form.form-grid"), "#entry-ttl-mode")); }
            case 34 -> { openEdit(page); assertThat(page.locator("#entry-edit-ttl")).isDisabled(); }
            case 35 -> { openEdit(page); AntSelect.choose(page, page.locator("#entry-ttl-mode"), "REPLACE"); assertThat(page.locator("#entry-edit-ttl")).isEnabled(); }
            case 36 -> { openEdit(page); AntSelect.choose(page, page.locator("#entry-ttl-mode"), "REPLACE"); assertThat(page.locator("#entry-edit-ttl")).hasAttribute("required", ""); }
            case 37 -> { openEdit(page); assertThat(page.getByLabel("Entry value")).hasAttribute("required", ""); }
            case 38 -> { openEdit(page); assertThat(AntSelect.selection(page.locator("#entry-set-mode"))).hasAttribute("data-value", "ONLY_IF_VERSION_MATCHES"); }
            case 39 -> { save(page, "updated-exact", null); assertEquals("updated-exact", scalar(context, "SELECT convert_from(value_bytes,'UTF8') FROM peegee_cache.cache_entries WHERE cache_key='customer:1'")); }
            case 40 -> { save(page, "updated-upsert", "UPSERT"); assertEquals("updated-upsert", scalar(context, "SELECT convert_from(value_bytes,'UTF8') FROM peegee_cache.cache_entries WHERE cache_key='customer:1'")); }
            case 41 -> { save(page, "updated-present", "ONLY_IF_PRESENT"); assertEquals("updated-present", scalar(context, "SELECT convert_from(value_bytes,'UTF8') FROM peegee_cache.cache_entries WHERE cache_key='customer:1'")); }
            case 42 -> { openEdit(page); AntSelect.choose(page, page.locator("#entry-set-mode"), "ONLY_IF_ABSENT"); page.getByLabel("Entry value").fill("must-not-write"); page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Save entry")).click(); assertThat(page.getByRole(AriaRole.ALERT)).isVisible(); assertThat(page.getByLabel("Entry value")).hasValue("must-not-write"); }
            case 43 -> { openEdit(page); page.getByLabel("Entry value").fill("conflicting-value"); ManagementConsolePostgresFixture.execute(context.postgres(), "UPDATE peegee_cache.cache_entries SET version=version+1 WHERE namespace='logical-orders' AND cache_key='customer:1'"); page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Save entry")).click(); assertThat(page.getByRole(AriaRole.ALERT)).containsText("VERSION_MISMATCH"); assertThat(page.getByLabel("Entry value")).hasValue("conflicting-value"); }
            case 44 -> { openDetail(page); page.getByLabel("TTL milliseconds", new Page.GetByLabelOptions().setExact(true)).fill("60000"); page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Set TTL")).click(); assertThat(page.getByRole(AriaRole.STATUS)).containsText("TTL updated"); assertEquals("true", scalar(context, "SELECT expires_at IS NOT NULL FROM peegee_cache.cache_entries WHERE cache_key='customer:1'")); }
            case 45 -> { openDetail(page); page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Make persistent")).click(); assertThat(page.getByRole(AriaRole.STATUS)).containsText("persistent"); assertEquals("true", scalar(context, "SELECT expires_at IS NULL FROM peegee_cache.cache_entries WHERE cache_key='customer:1'")); }
            case 46 -> { openDetail(page); page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Touch entry")).click(); assertThat(page.getByRole(AriaRole.STATUS)).containsText("authoritative metadata"); assertEquals("true", scalar(context, "SELECT last_accessed_at IS NOT NULL FROM peegee_cache.cache_entries WHERE cache_key='customer:1'")); }
            case 47 -> { openDetail(page); page.getByLabel("Refresh TTL milliseconds (optional)").fill("45000"); page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Touch entry")).click(); assertThat(page.getByRole(AriaRole.STATUS)).containsText("authoritative metadata"); assertThat(details(page)).containsText(Pattern.compile("4[0-5](?:\\.\\d{3})? s")); assertEquals("true", scalar(context, "SELECT expires_at IS NOT NULL FROM peegee_cache.cache_entries WHERE cache_key='customer:1'")); }
            case 48 -> { openDetail(page); page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Delete entry")).click(); Locator dialog = page.getByRole(AriaRole.DIALOG, new Page.GetByRoleOptions().setName("Delete customer:1?")); assertThat(dialog.getByRole(AriaRole.BUTTON, new Locator.GetByRoleOptions().setName("Confirm delete"))).isDisabled(); dialog.getByLabel("Confirm entry key").fill("wrong"); assertThat(dialog.getByRole(AriaRole.BUTTON, new Locator.GetByRoleOptions().setName("Confirm delete"))).isDisabled(); }
            case 49 -> { openDetail(page); page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Delete entry")).click(); Locator dialog = page.getByRole(AriaRole.DIALOG, new Page.GetByRoleOptions().setName("Delete customer:1?")); dialog.getByLabel("Confirm entry key").fill("customer:1"); dialog.getByRole(AriaRole.BUTTON, new Locator.GetByRoleOptions().setName("Confirm delete")).click(); assertThat(page.getByRole(AriaRole.STATUS)).containsText("deleted"); assertEquals("0", scalar(context, "SELECT count(*) FROM peegee_cache.cache_entries WHERE cache_key='customer:1'")); }
            default -> throw new IllegalArgumentException("Unknown scenario " + index);
        }
    }

    private static Locator create(Page page) { page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Create entry").setExact(true)).click(); return createDialog(page); }
    private static String scalar(ManagementConsolePostgresFixture.Context context, String sql) throws Exception { return ManagementConsolePostgresFixture.queryScalar(context.postgres(), sql); }
    private static void assertEntryExists(ManagementConsolePostgresFixture.Context context, String key, String type) throws Exception { assertEquals(type, scalar(context, "SELECT value_type FROM peegee_cache.cache_entries WHERE cache_key='" + key.replace("'", "''") + "'")); }
    private static Locator createDialog(Page page) { return page.getByRole(AriaRole.DIALOG, new Page.GetByRoleOptions().setName("Create entry")); }
    private static void fillCreate(Page page, String key, String type, String value, String ttl) { Locator dialog = create(page); dialog.getByLabel("Key").fill(key); AntSelect.choose(page, dialog.locator("#new-entry-type"), type); dialog.locator("#new-entry-value").fill(value); if (!ttl.isEmpty()) dialog.getByLabel("TTL milliseconds (blank for persistent)").fill(ttl); }
    private static void submitCreate(Page page) { createDialog(page).getByRole(AriaRole.BUTTON, new Locator.GetByRoleOptions().setName("Create entry").setExact(true)).click(); }
    private static void createEntry(Page page, String key, String type, String value, String ttl) { fillCreate(page, key, type, value, ttl); submitCreate(page); assertThat(page.getByRole(AriaRole.STATUS)).containsText("created"); assertThat(link(page, key)).isVisible(); }
    private static void openEntries(Page page) { page.getByRole(AriaRole.LINK, new Page.GetByRoleOptions().setName("Namespaces").setExact(true)).click(); page.getByRole(AriaRole.LINK, new Page.GetByRoleOptions().setName("logical-orders").setExact(true)).click(); page.getByRole(AriaRole.TAB, new Page.GetByRoleOptions().setName("Entries")).click(); assertThat(page.getByRole(AriaRole.HEADING, new Page.GetByRoleOptions().setName("Key Browser").setExact(true))).isVisible(); }
    private static void openDetail(Page page) { link(page, "customer:1").click(); assertThat(page.getByRole(AriaRole.HEADING, new Page.GetByRoleOptions().setName("customer:1").setExact(true))).isVisible(); }
    private static void openEdit(Page page) { openDetail(page); page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Edit entry")).click(); }
    private static void save(Page page, String value, String mode) { openEdit(page); if (mode != null) AntSelect.choose(page, page.locator("#entry-set-mode"), mode); page.getByLabel("Entry value").fill(value); page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Save entry")).click(); assertThat(page.getByRole(AriaRole.STATUS)).containsText("updated"); }
    private static Locator link(Page page, String key) { return page.getByRole(AriaRole.LINK, new Page.GetByRoleOptions().setName(key).setExact(true)); }
    private static Locator details(Page page) { return page.locator("[aria-label='Entry metadata']"); }
    private static List<String> options(Page page, Locator scope, String selector) { return AntSelect.values(page, scope.locator(selector)); }
}
