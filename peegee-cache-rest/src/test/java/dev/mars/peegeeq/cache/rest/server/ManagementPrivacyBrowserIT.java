package dev.mars.peegeeq.cache.rest.server;

import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.AriaRole;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.IntStream;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/** Wave P6 real-browser scenarios for cross-surface privacy guarantees. */
class ManagementPrivacyBrowserIT {

    @RegisterExtension
    static final ManagementBrowserPostgresWorker POSTGRES = new ManagementBrowserPostgresWorker();

    @TempDir
    Path temporaryDirectory;

    private static final String SECRET = "stored-value";
    private static final List<String> ACTIONS = List.of(
            "show a value only after an explicit authorized reveal",
            "remove a revealed value from the DOM after explicit hide",
            "keep revealed values out of the browser URL",
            "keep revealed values out of browser history state",
            "keep revealed values out of local storage",
            "keep revealed values out of session storage",
            "keep session credentials out of script-readable cookies",
            "remove revealed values from the accessibility tree",
            "remove revealed values from serialized page markup",
            "restore the masked value panel pixel-for-pixel after explicit hide",
            "keep revealed values and credentials out of durable audit text",
            "keep setup passwords out of the rendered document",
            "remove the one-time bootstrap token after authentication",
            "render revealed content as inert preformatted text and report no failed responses");

    static List<ManagementBrowserCase> scenarios() {
        List<String> operations = List.of(
                "listNamespaces", "getNamespace", "listEntries", "getEntry", "revealEntryValue");
        return IntStream.range(0, ACTIONS.size())
                .mapToObj(index -> new ManagementBrowserCase(
                        "PW-PRIVACY-%03d".formatted(index + 1),
                        "P6 privacy contract: " + ACTIONS.get(index),
                        ManagementBrowserArea.HARDENING,
                        ManagementBrowserRisk.CRITICAL,
                        ACTIONS.get(index),
                        "The packaged console must " + ACTIONS.get(index) + ".",
                        "Hide sensitive content, clear transient DOM state, reset PostgreSQL, and close browser resources",
                        operations,
                        Set.of(
                                ManagementBrowserEvidence.VISIBLE_RESULT,
                                ManagementBrowserEvidence.HTTP_OPERATION,
                                ManagementBrowserEvidence.DATABASE,
                                ManagementBrowserEvidence.DURABLE_AUDIT,
                                ManagementBrowserEvidence.SENSITIVE_STATE,
                                ManagementBrowserEvidence.RESOURCE_CLEANUP)))
                .toList();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("dev.mars.peegeeq.cache.rest.server.ManagementBrowserSelection#privacyScenarios")
    void privacyScenario(ManagementBrowserCase scenario) throws Exception {
        int index = Integer.parseInt(scenario.id().substring(scenario.id().length() - 3)) - 1;
        ManagementConsolePostgresFixture.run(
                temporaryDirectory,
                POSTGRES.postgres(),
                true,
                scenario.operations(),
                context -> {
                    openEntry(context.page());
                    if (index == 9) {
                        context.page().getByLabel("Reveal reason (optional)")
                                .fill("privacy verification");
                        context.page().evaluate("document.activeElement.blur()");
                    }
                    byte[] maskedScreenshot = index == 9
                            ? context.page().locator(".value-panel").screenshot()
                            : null;
                    reveal(context.page());
                    verify(index, context, maskedScreenshot);
                });
    }

    private static void verify(
            int index,
            ManagementConsolePostgresFixture.Context context,
            byte[] maskedScreenshot) throws Exception {
        Page page = context.page();
        if (index == 0) {
            assertThat(page.getByText(SECRET, exact())).isVisible();
            return;
        }
        hide(page);
        switch (index) {
            case 1 -> assertThat(page.getByText(SECRET, exact())).hasCount(0);
            case 2 -> assertFalse(page.url().contains(SECRET));
            case 3 -> assertFalse(String.valueOf(page.evaluate("JSON.stringify(history.state)")).contains(SECRET));
            case 4 -> assertFalse(String.valueOf(page.evaluate("JSON.stringify(Object.values(localStorage))")).contains(SECRET));
            case 5 -> assertFalse(String.valueOf(page.evaluate("JSON.stringify(Object.values(sessionStorage))")).contains(SECRET));
            case 6 -> {
                String cookie = String.valueOf(page.evaluate("document.cookie"));
                assertFalse(cookie.contains("PGQMGMTSESSION"));
                assertFalse(cookie.contains(context.bootstrapToken()));
            }
            case 7 -> assertFalse(page.locator("body").ariaSnapshot().contains(SECRET));
            case 8 -> assertFalse(page.content().contains(SECRET));
            case 9 -> {
                page.evaluate("document.activeElement.blur()");
                byte[] afterHide = page.locator(".value-panel").screenshot();
                assertArrayEquals(maskedScreenshot, afterHide,
                        "The sensitive panel must return to its exact masked visual state");
            }
            case 10 -> {
                String audit = Files.readString(context.auditPath());
                assertFalse(audit.contains(SECRET));
                assertFalse(audit.contains(ManagementConsolePostgresFixture.DATABASE_PASSWORD));
                assertFalse(audit.contains(context.bootstrapToken()));
            }
            case 11 -> assertFalse(page.content().contains(ManagementConsolePostgresFixture.DATABASE_PASSWORD));
            case 12 -> assertFalse(page.content().contains(context.bootstrapToken()));
            case 13 -> {
                assertThat(page.locator("script:has-text('" + SECRET + "')")).hasCount(0);
                assertEquals(List.of(), context.diagnostics().failedResponses());
            }
            default -> throw new IllegalArgumentException("Unknown privacy scenario " + index);
        }
    }

    private static void openEntry(Page page) {
        page.getByRole(AriaRole.LINK,
                new Page.GetByRoleOptions().setName("Namespaces").setExact(true)).click();
        page.getByRole(AriaRole.LINK,
                new Page.GetByRoleOptions().setName(ManagementConsolePostgresFixture.NAMESPACE).setExact(true)).click();
        page.getByRole(AriaRole.TAB, new Page.GetByRoleOptions().setName("Entries")).click();
        page.getByRole(AriaRole.LINK,
                new Page.GetByRoleOptions().setName("customer:1").setExact(true)).click();
        assertThat(page.getByRole(AriaRole.HEADING,
                new Page.GetByRoleOptions().setName("customer:1").setExact(true))).isVisible();
    }

    private static void reveal(Page page) {
        page.getByLabel("Reveal reason (optional)").fill("privacy verification");
        page.getByRole(AriaRole.BUTTON,
                new Page.GetByRoleOptions().setName("Reveal value").setExact(true)).click();
        assertThat(page.getByText(SECRET, exact())).isVisible();
    }

    private static void hide(Page page) {
        page.getByRole(AriaRole.BUTTON,
                new Page.GetByRoleOptions().setName("Hide value").setExact(true)).click();
        assertThat(page.getByText("Value hidden", exact())).isVisible();
    }

    private static Page.GetByTextOptions exact() {
        return new Page.GetByTextOptions().setExact(true);
    }
}
