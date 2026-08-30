package dev.mars.peegeeq.cache.rest.server;

import com.microsoft.playwright.Locator;
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
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Wave P6 real-browser scenarios for accessibility and responsive behavior. */
class ManagementAccessibilityBrowserIT {

    @RegisterExtension
    static final ManagementBrowserPostgresWorker POSTGRES = new ManagementBrowserPostgresWorker();

    @TempDir
    Path temporaryDirectory;

    private static final List<Route> ROUTES = List.of(
            new Route("Overview", "/ui/"),
            new Route("Setups", "/ui/setups"),
            new Route("Namespaces", "/ui/namespaces"),
            new Route("Key Browser", "/ui/keys"),
            new Route("Counters", "/ui/counters"),
            new Route("Locks", "/ui/locks"),
            new Route("Pub/Sub", "/ui/pubsub"),
            new Route("Monitoring", "/ui/monitoring"),
            new Route("Settings", "/ui/settings"));

    private static final List<String> ACTIONS = List.of(
            "axe-scan Overview at the desktop breakpoint",
            "axe-scan Setups at the desktop breakpoint",
            "axe-scan Namespaces at the desktop breakpoint",
            "axe-scan Key Browser at the desktop breakpoint",
            "axe-scan Counters at the desktop breakpoint",
            "axe-scan Locks at the desktop breakpoint",
            "axe-scan Pub/Sub at the desktop breakpoint",
            "axe-scan Monitoring at the desktop breakpoint",
            "axe-scan Settings at the desktop breakpoint",
            "axe-scan Overview at the mobile breakpoint",
            "axe-scan Setups at the mobile breakpoint",
            "axe-scan Namespaces at the mobile breakpoint",
            "axe-scan Key Browser at the mobile breakpoint",
            "axe-scan Counters at the mobile breakpoint",
            "axe-scan Locks at the mobile breakpoint",
            "axe-scan Pub/Sub at the mobile breakpoint",
            "axe-scan Monitoring at the mobile breakpoint",
            "axe-scan Settings at the mobile breakpoint",
            "contain populated Counters at 360 by 640",
            "contain populated Counters at 390 by 844",
            "contain populated Counters at 768 by 1024",
            "contain populated Counters at 1920 by 1080",
            "navigate to Counters using only keyboard focus and Enter",
            "trap, dismiss, and restore focus for the counter management dialog");

    static List<ManagementBrowserCase> scenarios() {
        return IntStream.range(0, ACTIONS.size())
                .mapToObj(index -> new ManagementBrowserCase(
                        "PW-ACCESS-%03d".formatted(index + 1),
                        "P6 accessibility/responsive contract: " + ACTIONS.get(index),
                        ManagementBrowserArea.HARDENING,
                        index < 18 ? ManagementBrowserRisk.HIGH : ManagementBrowserRisk.MEDIUM,
                        ACTIONS.get(index),
                        "The packaged console must " + ACTIONS.get(index) + " without accessibility or containment regressions.",
                        "Close dialogs, restore viewport state, reset PostgreSQL, and close browser resources",
                        List.of(),
                        Set.of(ManagementBrowserEvidence.VISIBLE_RESULT, ManagementBrowserEvidence.RESOURCE_CLEANUP)))
                .toList();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("scenarios")
    void accessibilityScenario(ManagementBrowserCase scenario) throws Exception {
        int index = Integer.parseInt(scenario.id().substring(scenario.id().length() - 3)) - 1;
        ManagementConsolePostgresFixture.run(
                temporaryDirectory,
                POSTGRES.postgres(),
                true,
                scenario.operations(),
                context -> {
                    ManagementConsolePostgresFixture.authenticate(context);
                    ManagementConsolePostgresFixture.registerSetup(context);
                    verify(index, context.page(), context.origin());
                });
    }

    private static void verify(int index, Page page, String origin) throws Exception {
        if (index < 18) {
            int routeIndex = index % ROUTES.size();
            boolean mobile = index >= ROUTES.size();
            Route route = ROUTES.get(routeIndex);
            page.setViewportSize(mobile ? 390 : 1440, mobile ? 844 : 900);
            assertEquals(200, page.navigate(origin + route.path()).status());
            heading(page, route.heading());
            assertNoAxeViolations(page);
            return;
        }
        int[][] viewports = {{360, 640}, {390, 844}, {768, 1024}, {1920, 1080}};
        if (index < 22) {
            int[] viewport = viewports[index - 18];
            page.setViewportSize(viewport[0], viewport[1]);
            openCounters(page);
            assertViewportContained(page);
            return;
        }
        if (index == 22) {
            Locator link = page.getByRole(AriaRole.LINK,
                    new Page.GetByRoleOptions().setName("Counters").setExact(true));
            link.focus();
            page.keyboard().press("Enter");
            heading(page, "Counters");
            assertTrue(page.url().endsWith("/ui/counters"));
            return;
        }
        openCounters(page);
        Locator trigger = page.getByRole(AriaRole.BUTTON,
                new Page.GetByRoleOptions().setName("Manage count"));
        trigger.focus();
        page.keyboard().press("Enter");
        Locator dialog = page.getByRole(AriaRole.DIALOG,
                new Page.GetByRoleOptions().setName("Manage count"));
        assertThat(dialog).isVisible();
        page.keyboard().press("Shift+Tab");
        assertTrue((Boolean) dialog.evaluate("element => element.contains(document.activeElement)"));
        page.keyboard().press("Escape");
        assertThat(dialog).hasCount(0);
        assertTrue((Boolean) trigger.evaluate("element => element === document.activeElement"));
    }

    private static void openCounters(Page page) {
        page.getByRole(AriaRole.LINK,
                new Page.GetByRoleOptions().setName("Counters").setExact(true)).click();
        heading(page, "Counters");
    }

    private static void heading(Page page, String name) {
        assertThat(page.getByRole(AriaRole.HEADING,
                new Page.GetByRoleOptions().setName(name).setExact(true))).isVisible();
    }

    private static void assertViewportContained(Page page) {
        String overflow = (String) page.evaluate("""
                () => Array.from(document.querySelectorAll(
                  'body, #root, .console, .console__header, .console__sidebar, .console__main, .workspace, .table-scroll'))
                  .filter((element) => {
                    const box = element.getBoundingClientRect();
                    return box.left < -1 || box.right > window.innerWidth + 1;
                  })
                  .map((element) => element.tagName.toLowerCase() + '.' + (element.className || '-'))
                  .join(' | ')
                """);
        assertEquals("", overflow, "No primary surface may escape the viewport");
    }

    private static void assertNoAxeViolations(Page page) throws Exception {
        Path axe = List.of(
                        Path.of("peegee-cache-management-ui", "node_modules", "axe-core", "axe.min.js"),
                        Path.of("..", "peegee-cache-management-ui", "node_modules", "axe-core", "axe.min.js"))
                .stream()
                .map(Path::toAbsolutePath)
                .filter(Files::isRegularFile)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("axe-core is absent; build the management UI first"));
        page.evaluate(Files.readString(axe));
        String violations = (String) page.evaluate("""
                async () => JSON.stringify((await axe.run(document, {
                  runOnly: {type: 'tag', values: ['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa']}
                })).violations.map(violation => ({
                  id: violation.id,
                  impact: violation.impact,
                  targets: violation.nodes.map(node => node.target)
                })))
                """);
        assertEquals("[]", violations, () -> "axe-core violations: " + violations);
    }

    private record Route(String heading, String path) {
    }
}
