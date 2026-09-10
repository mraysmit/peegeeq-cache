package dev.mars.peegeeq.cache.rest.server;

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

/** Wave P5 real-browser scenarios for SSE, WebSocket, offline, and recovery behavior. */
class ManagementLiveTransportBrowserIT {

    @RegisterExtension
    static final ManagementBrowserPostgresWorker POSTGRES = new ManagementBrowserPostgresWorker();

    @TempDir
    Path temporaryDirectory;

    private static final List<String> ACTIONS = List.of(
            "expose a closed notifications control before a live connection is requested",
            "open the notifications drawer through its accessible control",
            "mark the notifications control expanded while its drawer is open",
            "establish the real monitoring WebSocket for an active setup",
            "show the bounded connection-ready notification emitted by the server",
            "close the notifications drawer through its internal close control",
            "return the notifications control to its collapsed state",
            "discard the process-local notification view when the drawer closes",
            "reopen notifications and establish a fresh WebSocket",
            "connect the real metrics SSE transport on the Monitoring route",
            "show a connected metrics state only after the SSE response opens",
            "run the metrics SSE and monitoring WebSocket simultaneously",
            "mark metrics stale when the browser goes offline",
            "mark the monitoring WebSocket stale when the browser goes offline",
            "retain the Monitoring workspace while both live transports are interrupted",
            "reconnect metrics automatically when browser connectivity returns",
            "reconnect notifications automatically when browser connectivity returns",
            "close only the monitoring WebSocket while leaving Monitoring usable",
            "return the global connection badge to REST-connected after notifications close",
            "reopen notifications after cleanup without reloading the console");

    static List<ManagementBrowserCase> scenarios() {
        return IntStream.range(0, ACTIONS.size())
                .mapToObj(index -> new ManagementBrowserCase(
                        "PW-LIVE-%03d".formatted(index + 1),
                        "P5 live-transport contract: " + ACTIONS.get(index),
                        ManagementBrowserArea.MONITORING,
                        index >= 9 ? ManagementBrowserRisk.CRITICAL : ManagementBrowserRisk.HIGH,
                        ACTIONS.get(index),
                        "The packaged console must " + ACTIONS.get(index) + ".",
                        "Restore browser connectivity, close live transports, reset PostgreSQL, and close browser resources",
                        operations(index),
                        Set.of(
                                ManagementBrowserEvidence.VISIBLE_RESULT,
                                ManagementBrowserEvidence.HTTP_OPERATION,
                                ManagementBrowserEvidence.DATABASE,
                                ManagementBrowserEvidence.RESOURCE_CLEANUP)))
                .toList();
    }

    private static List<String> operations(int index) {
        if (index == 0) {
            return List.of("getSetup");
        }
        if (index < 9 || index == 19) {
            return List.of("monitoringWebSocket");
        }
        if (index < 11) {
            return List.of("getDatabaseMonitoring", "getRuntimeMonitoring", "streamMetrics", "listActivity");
        }
        return List.of(
                "getDatabaseMonitoring",
                "getRuntimeMonitoring",
                "streamMetrics",
                "listActivity",
                "monitoringWebSocket");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("dev.mars.peegeeq.cache.rest.server.ManagementBrowserSelection#liveTransportScenarios")
    void liveTransportScenario(ManagementBrowserCase scenario) throws Exception {
        int index = Integer.parseInt(scenario.id().substring(scenario.id().length() - 3)) - 1;
        ManagementConsolePostgresFixture.run(
                temporaryDirectory,
                POSTGRES.postgres(),
                true,
                scenario.operations(),
                context -> {
                    verify(index, context.page());
                });
    }

    private static void verify(int index, Page page) {
        switch (index) {
            case 0 -> assertThat(page.getByLabel("Open notifications")).hasAttribute("aria-expanded", "false");
            case 1 -> {
                openNotifications(page);
                assertThat(notifications(page)).isVisible();
            }
            case 2 -> {
                openNotifications(page);
                assertThat(page.getByLabel("Close notifications").first()).hasAttribute("aria-expanded", "true");
            }
            case 3 -> {
                openNotifications(page);
                assertLive(page);
            }
            case 4 -> {
                openNotifications(page);
                assertThat(notifications(page).getByText("connection ready",
                        new com.microsoft.playwright.Locator.GetByTextOptions().setExact(true))).isVisible();
            }
            case 5 -> {
                openNotifications(page);
                notifications(page).getByLabel("Close notifications").click();
                assertThat(notifications(page)).hasCount(0);
            }
            case 6 -> {
                openNotifications(page);
                notifications(page).getByLabel("Close notifications").click();
                assertThat(page.getByLabel("Open notifications")).hasAttribute("aria-expanded", "false");
            }
            case 7 -> {
                openNotifications(page);
                notifications(page).getByLabel("Close notifications").click();
                assertThat(page.getByText("connection ready", exact())).hasCount(0);
            }
            case 8 -> {
                openNotifications(page);
                notifications(page).getByLabel("Close notifications").click();
                openNotifications(page);
                assertLive(page);
            }
            case 9, 10 -> {
                openMonitoring(page);
                assertThat(page.getByText("Live metrics connected", exact())).isVisible();
            }
            case 11 -> {
                openNotifications(page);
                openMonitoring(page);
                assertLive(page);
                assertThat(page.getByText("Live metrics connected", exact())).isVisible();
            }
            case 12 -> {
                openBoth(page);
                page.context().setOffline(true);
                assertThat(page.getByText("Live metrics interrupted; displayed values may be stale", exact())).isVisible();
                page.context().setOffline(false);
            }
            case 13 -> {
                openBoth(page);
                page.context().setOffline(true);
                assertThat(page.getByText("Live stale", exact())).isVisible();
                page.context().setOffline(false);
            }
            case 14 -> {
                openBoth(page);
                page.context().setOffline(true);
                assertThat(page.getByRole(AriaRole.HEADING,
                        new Page.GetByRoleOptions().setName("Monitoring").setExact(true))).isVisible();
                page.context().setOffline(false);
            }
            case 15 -> {
                interruptAndRestore(page);
                assertThat(page.getByText("Live metrics connected", exact())).isVisible(
                        new com.microsoft.playwright.assertions.LocatorAssertions.IsVisibleOptions()
                                .setTimeout(15_000));
            }
            case 16 -> {
                interruptAndRestore(page);
                assertLive(page, 15_000);
            }
            case 17 -> {
                openBoth(page);
                notifications(page).getByLabel("Close notifications").click();
                assertThat(page.getByText("Live metrics connected", exact())).isVisible();
            }
            case 18 -> {
                openBoth(page);
                notifications(page).getByLabel("Close notifications").click();
                assertThat(page.getByText("Connected", exact()).first()).isVisible();
            }
            case 19 -> {
                openNotifications(page);
                notifications(page).getByLabel("Close notifications").click();
                openNotifications(page);
                assertThat(notifications(page)).isVisible();
            }
            default -> throw new IllegalArgumentException("Unknown live-transport scenario " + index);
        }
    }

    private static void openNotifications(Page page) {
        page.getByLabel("Open notifications").click();
        assertThat(notifications(page)).isVisible();
        assertLive(page);
    }

    private static void openMonitoring(Page page) {
        page.getByRole(AriaRole.LINK,
                new Page.GetByRoleOptions().setName("Monitoring").setExact(true)).click();
        assertThat(page.getByRole(AriaRole.HEADING,
                new Page.GetByRoleOptions().setName("Monitoring").setExact(true))).isVisible();
    }

    private static void openBoth(Page page) {
        openNotifications(page);
        openMonitoring(page);
        assertLive(page);
        assertThat(page.getByText("Live metrics connected", exact())).isVisible();
    }

    private static void interruptAndRestore(Page page) {
        openBoth(page);
        page.context().setOffline(true);
        assertThat(page.getByText("Live stale", exact())).isVisible();
        assertThat(page.getByText("Live metrics interrupted; displayed values may be stale", exact())).isVisible();
        page.context().setOffline(false);
    }

    private static void assertLive(Page page) {
        assertThat(page.getByText("Live", exact())).isVisible();
    }

    private static void assertLive(Page page, double timeoutMillis) {
        assertThat(page.getByText("Live", exact())).isVisible(
                new com.microsoft.playwright.assertions.LocatorAssertions.IsVisibleOptions()
                        .setTimeout(timeoutMillis));
    }

    private static com.microsoft.playwright.Locator notifications(Page page) {
        return page.getByRole(AriaRole.COMPLEMENTARY,
                new Page.GetByRoleOptions().setName("Notifications").setExact(true));
    }

    private static Page.GetByTextOptions exact() {
        return new Page.GetByTextOptions().setExact(true);
    }
}
