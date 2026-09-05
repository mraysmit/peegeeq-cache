package dev.mars.peegeeq.cache.rest.server;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.AriaRole;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Set;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Wave P3 real-browser scenarios for entry inspection, value presentation, and reveal cleanup. */
class ManagementEntryInspectionBrowserIT {

    @RegisterExtension
    static final ManagementBrowserPostgresWorker POSTGRES = new ManagementBrowserPostgresWorker();

    @TempDir
    Path temporaryDirectory;

    private static final List<String> ACTIONS = List.of(
            "show the metadata-only inspection boundary",
            "show the selected logical-orders namespace",
            "bound key prefixes to 1024 characters",
            "offer every canonical value-type filter",
            "offer every reviewed TTL-state filter",
            "list all four seeded PostgreSQL entries",
            "make customer:1 navigable",
            "render STRING type exactly",
            "render JSON type exactly",
            "render LONG type exactly",
            "render BYTES type exactly",
            "render version 3 without precision loss",
            "distinguish Never from Persistent",
            "humanize LIVE status",
            "filter STRING entries from database truth",
            "filter JSON entries from database truth",
            "filter LONG entries from database truth",
            "filter BYTES entries from database truth",
            "filter a matching key prefix",
            "trim harmless prefix whitespace",
            "show an explicit unmatched-filter state",
            "exclude an expiring row from Persistent",
            "select an expiring database row",
            "include an expired database row explicitly",
            "exclude expired rows by default",
            "start opaque pagination at page one",
            "advance an opaque cursor without URL leakage",
            "return through opaque cursor history",
            "mask the value on entry details",
            "show exact namespace metadata",
            "show exact STRING metadata",
            "show exact entry version metadata",
            "show persistent TTL metadata",
            "bound the optional reveal reason",
            "reveal a STRING value exactly",
            "offer UTF-8 and escaped STRING views",
            "neutralize hostile-looking STRING markup",
            "preserve Unicode STRING content",
            "default valid JSON to a structured tree",
            "render formatted JSON",
            "preserve raw JSON source text",
            "identify malformed stored JSON",
            "preserve signed 64-bit maximum precision",
            "preserve signed 64-bit minimum precision",
            "default BYTES to hexadecimal",
            "report exact BYTES length",
            "render exact Base64",
            "reject replacement decoding of invalid UTF-8",
            "remove a value after explicit hide",
            "remove a value on route transition",
            "remove a prior value on entry transition",
            "remove a value on reload",
            "remove a value on visibility loss",
            "remove a value and protected scope on logout");

    static List<ManagementBrowserCase> scenarios() {
        return java.util.stream.IntStream.range(0, ACTIONS.size()).mapToObj(index -> {
            List<String> operations = index < 28
                    ? List.of("listNamespaces", "getNamespace", "listEntries")
                    : index < 34
                    ? List.of("listNamespaces", "getNamespace", "listEntries", "getEntry")
                    : index == 53
                    ? List.of("listNamespaces", "getNamespace", "listEntries", "getEntry",
                    "revealEntryValue", "deleteLocalSession")
                    : List.of("listNamespaces", "getNamespace", "listEntries", "getEntry", "revealEntryValue");
            Set<ManagementBrowserEvidence> evidence = index >= 34
                    ? Set.of(ManagementBrowserEvidence.VISIBLE_RESULT, ManagementBrowserEvidence.HTTP_OPERATION,
                    ManagementBrowserEvidence.DATABASE, ManagementBrowserEvidence.DURABLE_AUDIT,
                    ManagementBrowserEvidence.SENSITIVE_STATE,
                    ManagementBrowserEvidence.RESOURCE_CLEANUP)
                    : Set.of(ManagementBrowserEvidence.VISIBLE_RESULT, ManagementBrowserEvidence.HTTP_OPERATION,
                    ManagementBrowserEvidence.DATABASE, ManagementBrowserEvidence.RESOURCE_CLEANUP);
            return new ManagementBrowserCase(
                    "PW-ENTRY-%03d".formatted(index + 3),
                    "P3 entry-inspection contract: " + ACTIONS.get(index),
                    ManagementBrowserArea.ENTRY,
                    index >= 28 ? ManagementBrowserRisk.CRITICAL : ManagementBrowserRisk.HIGH,
                    ACTIONS.get(index),
                    "The packaged console must " + ACTIONS.get(index) + ".",
                    "Clear browser state, reset the PostgreSQL schema, and close all resources",
                    operations,
                    evidence);
        }).toList();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("dev.mars.peegeeq.cache.rest.server.ManagementBrowserSelection#entryInspectionScenarios")
    void entryInspectionScenario(ManagementBrowserCase scenario) throws Exception {
        int index = Integer.parseInt(scenario.id().substring(scenario.id().length() - 3)) - 3;
        ManagementConsolePostgresFixture.run(
                temporaryDirectory, POSTGRES.postgres(), true, scenario.operations(), context -> {
                    seedFor(index, context);
                    openEntries(context.page());
                    verify(index, context);
                });
    }

    private static void verify(int index, ManagementConsolePostgresFixture.Context context) {
        Page page = context.page();
        switch (index) {
            case 0 -> assertThat(page.getByText("Metadata-only entry inspection", exact())).isVisible();
            case 1 -> assertThat(page.getByText("logical-orders", exact())).isVisible();
            case 2 -> assertThat(page.getByLabel("Key prefix")).hasAttribute("maxlength", "1024");
            case 3 -> assertEquals(List.of("ALL", "STRING", "JSON", "LONG", "BYTES"), options(page, "#entry-value-type"));
            case 4 -> assertEquals(List.of("ALL_LIVE", "PERSISTENT", "EXPIRING", "INCLUDE_EXPIRED"), options(page, "#entry-ttl-state"));
            case 5 -> assertThat(page.locator("tbody tr")).hasCount(4);
            case 6 -> assertThat(link(page, "customer:1")).isVisible();
            case 7 -> assertThat(row(page, "customer:1")).containsText("STRING");
            case 8 -> assertThat(row(page, "json-record")).containsText("JSON");
            case 9 -> assertThat(row(page, "long-boundary")).containsText("LONG");
            case 10 -> assertThat(row(page, "binary-record")).containsText("BYTES");
            case 11 -> assertEquals("3", row(page, "customer:1").locator("td").nth(4).textContent());
            case 12 -> { assertThat(row(page, "customer:1")).containsText("Never"); assertThat(row(page, "customer:1")).containsText("Persistent"); }
            case 13 -> assertEquals("Persistent", row(page, "customer:1").locator("td").nth(9).textContent());
            case 14 -> assertSingleFilter(page, "", "STRING", "ALL_LIVE", "customer:1");
            case 15 -> assertSingleFilter(page, "", "JSON", "ALL_LIVE", "json-record");
            case 16 -> assertSingleFilter(page, "", "LONG", "ALL_LIVE", "long-boundary");
            case 17 -> assertSingleFilter(page, "", "BYTES", "ALL_LIVE", "binary-record");
            case 18 -> assertSingleFilter(page, "customer", "ALL", "ALL_LIVE", "customer:1");
            case 19 -> assertSingleFilter(page, "  customer  ", "ALL", "ALL_LIVE", "customer:1");
            case 20 -> { filter(page, "absent", "ALL", "ALL_LIVE"); assertThat(page.getByRole(AriaRole.HEADING, new Page.GetByRoleOptions().setName("No entries matched"))).isVisible(); }
            case 21 -> { filter(page, "", "ALL", "PERSISTENT"); assertThat(page.locator("tbody tr")).hasCount(4); assertThat(link(page, "expiring-key")).hasCount(0); }
            case 22 -> assertSingleFilter(page, "", "ALL", "EXPIRING", "expiring-key");
            case 23 -> { filter(page, "expired", "ALL", "INCLUDE_EXPIRED"); assertThat(row(page, "expired-key")).containsText("Expired"); }
            case 24 -> assertThat(link(page, "expired-key")).hasCount(0);
            case 25 -> { assertThat(page.getByText("Page 1", exact())).isVisible(); assertThat(page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Previous page"))).isDisabled(); }
            case 26 -> { page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Next page")).click(); assertThat(page.getByText("Page 2", exact())).isVisible(); assertFalse(page.url().contains("cursor")); }
            case 27 -> { page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Next page")).click(); page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Previous page")).click(); assertThat(page.getByText("Page 1", exact())).isVisible(); }
            case 28 -> { openDetail(page, "customer:1"); assertThat(page.getByText("Value hidden", exact())).isVisible(); assertFalse(page.content().contains("stored-value")); }
            case 29 -> { openDetail(page, "customer:1"); assertThat(page.getByText("logical-orders", exact())).isVisible(); }
            case 30 -> { openDetail(page, "customer:1"); assertThat(details(page)).containsText("STRING"); }
            case 31 -> { openDetail(page, "customer:1"); assertThat(details(page)).containsText("3"); }
            case 32 -> { openDetail(page, "customer:1"); assertThat(details(page)).containsText("Persistent"); }
            case 33 -> { openDetail(page, "customer:1"); Locator reason = page.getByLabel("Reveal reason (optional)"); assertThat(reason).hasAttribute("minlength", "3"); assertThat(reason).hasAttribute("maxlength", "240"); }
            case 34 -> { reveal(page, "customer:1"); assertThat(page.getByText("stored-value", exact())).isVisible(); }
            case 35 -> { reveal(page, "customer:1"); assertThat(page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("UTF-8 text"))).isVisible(); assertThat(page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Escaped text"))).isVisible(); }
            case 36 -> { reveal(page, "hostile"); page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Escaped text")).click(); assertThat(page.locator(".value-content")).containsText("<script>alert(1)</script>\\nline"); assertTrue((Boolean) page.locator(".value-content").evaluate("node => node.querySelector('script') === null")); }
            case 37 -> { reveal(page, "unicode"); assertThat(page.getByText("缓存-🚀", exact())).isVisible(); }
            case 38 -> { reveal(page, "json-record"); assertThat(page.getByText("Valid JSON", exact())).isVisible(); assertThat(page.locator(".json-tree")).containsText("safe"); }
            case 39 -> { reveal(page, "json-record"); page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Formatted text")).click(); assertThat(page.locator(".value-content")).containsText("\"safe\": true"); }
            case 40 -> { reveal(page, "json-record"); page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Raw UTF-8")).click(); assertThat(page.locator(".value-content")).hasText("{\"safe\":true}"); }
            case 41 -> { reveal(page, "bad-json"); assertThat(page.getByText("Invalid JSON", exact())).isVisible(); assertThat(page.locator(".value-content")).hasText("{bad"); }
            case 42 -> { reveal(page, "long-boundary"); assertThat(page.getByText("9223372036854775807", exact())).isVisible(); }
            case 43 -> { reveal(page, "long-min"); assertThat(page.getByText("-9223372036854775808", exact())).isVisible(); }
            case 44 -> { reveal(page, "binary-record"); assertThat(page.locator(".value-content")).hasText("00 ff 41"); }
            case 45 -> { reveal(page, "binary-record"); assertThat(page.getByText("3 bytes", exact())).isVisible(); }
            case 46 -> { reveal(page, "binary-record"); page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Base64")).click(); assertThat(page.getByText("AP9B", exact())).isVisible(); }
            case 47 -> { reveal(page, "binary-record"); page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("UTF-8 attempt")).click(); assertThat(page.getByText("Not valid UTF-8", exact())).isVisible(); }
            case 48 -> { reveal(page, "customer:1"); hide(page); assertFalse(page.content().contains("stored-value")); }
            case 49 -> { reveal(page, "customer:1"); page.getByRole(AriaRole.LINK, new Page.GetByRoleOptions().setName("Key Browser").setExact(true)).click(); assertThat(page.getByRole(AriaRole.HEADING, new Page.GetByRoleOptions().setName("Key Browser").setExact(true))).isVisible(); assertThat(page.getByText("stored-value", exact())).hasCount(0); }
            case 50 -> { reveal(page, "customer:1"); page.navigate(context.origin() + "/ui/keys/" + encoded("logical-orders") + "/" + encoded("json-record")); assertThat(page.getByText("Value hidden", exact())).isVisible(); assertFalse(page.content().contains("stored-value")); }
            case 51 -> { reveal(page, "customer:1"); page.reload(); assertThat(page.getByText("Value hidden", exact())).isVisible(); assertFalse(page.content().contains("stored-value")); }
            case 52 -> { reveal(page, "customer:1"); page.evaluate("Object.defineProperty(document, 'hidden', {value:true, configurable:true}); document.dispatchEvent(new Event('visibilitychange'))"); assertThat(page.getByText("Value hidden", exact())).isVisible(); }
            case 53 -> { reveal(page, "customer:1"); page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("End local session")).click(); assertThat(page.getByRole(AriaRole.HEADING, new Page.GetByRoleOptions().setName("Connect to management console"))).isVisible(); assertThat(page.getByText("stored-value", exact())).hasCount(0); }
            default -> throw new IllegalArgumentException("Unknown scenario " + index);
        }
    }

    private static void seedFor(int index, ManagementConsolePostgresFixture.Context context) throws Exception {
        String sql = switch (index) {
            case 21, 22 -> "INSERT INTO peegee_cache.cache_entries(namespace,cache_key,value_type,value_bytes,numeric_value,version,expires_at) VALUES ('logical-orders','expiring-key','STRING',convert_to('soon','UTF8'),NULL,1,NOW()+INTERVAL '1 hour')";
            case 23, 24 -> "INSERT INTO peegee_cache.cache_entries(namespace,cache_key,value_type,value_bytes,numeric_value,version,expires_at) VALUES ('logical-orders','expired-key','STRING',convert_to('old','UTF8'),NULL,1,NOW()-INTERVAL '1 hour')";
            case 26, 27 -> "INSERT INTO peegee_cache.cache_entries(namespace,cache_key,value_type,value_bytes,numeric_value,version) SELECT 'logical-orders','page-'||lpad(i::text,3,'0'),'STRING',convert_to(i::text,'UTF8'),NULL,1 FROM generate_series(1,51) i";
            case 36 -> stringSql("hostile", "<script>alert(1)</script>\nline");
            case 37 -> stringSql("unicode", "缓存-🚀");
            case 41 -> stringSqlOfType("bad-json", "JSON", "{bad");
            case 43 -> "INSERT INTO peegee_cache.cache_entries(namespace,cache_key,value_type,value_bytes,numeric_value,version) VALUES ('logical-orders','long-min','LONG',NULL,-9223372036854775808,1)";
            default -> null;
        };
        if (sql != null) ManagementConsolePostgresFixture.execute(context.postgres(), sql);
    }

    private static void openEntries(Page page) {
        page.getByRole(AriaRole.LINK, new Page.GetByRoleOptions().setName("Namespaces").setExact(true)).click();
        page.getByRole(AriaRole.LINK, new Page.GetByRoleOptions().setName("logical-orders").setExact(true)).click();
        page.getByRole(AriaRole.TAB, new Page.GetByRoleOptions().setName("Entries")).click();
        assertThat(page.getByRole(AriaRole.HEADING, new Page.GetByRoleOptions().setName("Key Browser").setExact(true))).isVisible();
    }

    private static void openDetail(Page page, String key) {
        link(page, key).click();
        assertThat(page.getByRole(AriaRole.HEADING, new Page.GetByRoleOptions().setName(key).setExact(true))).isVisible();
    }

    private static void reveal(Page page, String key) {
        openDetail(page, key);
        page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Reveal value").setExact(true)).click();
        assertThat(page.getByRole(
                AriaRole.BUTTON,
                new Page.GetByRoleOptions().setName("Hide value").setExact(true))).isVisible();
    }
    private static void hide(Page page) { page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Hide value").setExact(true)).click(); assertThat(page.getByText("Value hidden", exact())).isVisible(); }
    private static Locator link(Page page, String key) { return page.getByRole(AriaRole.LINK, new Page.GetByRoleOptions().setName(key).setExact(true)); }
    private static Locator row(Page page, String key) { return page.getByRole(AriaRole.ROW).filter(new Locator.FilterOptions().setHasText(key)); }
    private static Locator details(Page page) { return page.locator("[aria-label='Entry metadata']"); }
    private static Page.GetByTextOptions exact() { return new Page.GetByTextOptions().setExact(true); }
    private static List<String> options(Page page, String selector) { return AntSelect.values(page, page.locator(selector)); }
    private static void filter(Page page, String prefix, String type, String ttl) { page.getByLabel("Key prefix").fill(prefix); AntSelect.choose(page, page.locator("#entry-value-type"), type); AntSelect.choose(page, page.locator("#entry-ttl-state"), ttl); page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Apply filters")).click(); }
    private static void assertSingleFilter(Page page, String prefix, String type, String ttl, String key) { filter(page, prefix, type, ttl); assertThat(link(page, key)).isVisible(); assertThat(page.locator("tbody tr")).hasCount(1); }
    private static String encoded(String value) { return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8)); }
    private static String stringSql(String key, String value) { return stringSqlOfType(key, "STRING", value); }
    private static String stringSqlOfType(String key, String type, String value) { return "INSERT INTO peegee_cache.cache_entries(namespace,cache_key,value_type,value_bytes,numeric_value,version) VALUES ('logical-orders','" + key + "','" + type + "',convert_to('" + value.replace("'", "''") + "','UTF8'),NULL,1)"; }
}
