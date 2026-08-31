package dev.mars.peegeeq.cache.rest.server;

import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.AriaRole;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.IntStream;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;

/** Wave P6 real-browser scenarios for deterministic cleanup and shutdown. */
class ManagementShutdownBrowserIT {

    @RegisterExtension
    static final ManagementBrowserPostgresWorker POSTGRES = new ManagementBrowserPostgresWorker();

    @TempDir
    Path temporaryDirectory;

    private static final List<String> ACTIONS = List.of(
            "close an authenticated registered-setup session without leaked resources",
            "close an active metrics SSE stream without leaked resources",
            "close an active monitoring WebSocket without leaked resources",
            "close an active Pub/Sub subscription stream without leaked resources",
            "close simultaneous metrics SSE and monitoring WebSocket transports",
            "close simultaneous Pub/Sub and metrics SSE transports",
            "close simultaneous Pub/Sub and monitoring WebSocket transports",
            "close Pub/Sub, metrics SSE, and monitoring WebSocket transports together");

    static List<ManagementBrowserCase> scenarios() {
        return IntStream.range(0, ACTIONS.size())
                .mapToObj(index -> {
                    List<String> operations = operations(index);
                    Set<ManagementBrowserEvidence> evidence = usesPubSub(index)
                            ? Set.of(
                            ManagementBrowserEvidence.VISIBLE_RESULT,
                            ManagementBrowserEvidence.HTTP_OPERATION,
                            ManagementBrowserEvidence.DATABASE,
                            ManagementBrowserEvidence.DURABLE_AUDIT,
                            ManagementBrowserEvidence.RESOURCE_CLEANUP)
                            : Set.of(
                            ManagementBrowserEvidence.VISIBLE_RESULT,
                            ManagementBrowserEvidence.HTTP_OPERATION,
                            ManagementBrowserEvidence.DATABASE,
                            ManagementBrowserEvidence.RESOURCE_CLEANUP);
                    return new ManagementBrowserCase(
                        "PW-SHUTDOWN-%03d".formatted(index + 1),
                        "P6 deterministic-shutdown contract: " + ACTIONS.get(index),
                        ManagementBrowserArea.HARDENING,
                        ManagementBrowserRisk.CRITICAL,
                        ACTIONS.get(index),
                        "The fixture and management server must " + ACTIONS.get(index) + ".",
                        "Close browser context first, then server, pools, schema resources, and PostgreSQL; assert the leaked-resource counter is zero",
                        operations,
                        evidence);
                })
                .toList();
    }

    private static List<String> operations(int index) {
        List<String> operations = new ArrayList<>();
        if (usesPubSub(index)) {
            operations.add("createPubSubSubscription");
            operations.add("streamPubSubMessages");
        }
        if (usesMetrics(index)) {
            operations.addAll(List.of(
                    "getDatabaseMonitoring", "getRuntimeMonitoring", "streamMetrics", "listActivity"));
        }
        if (usesWebSocket(index)) {
            operations.add("monitoringWebSocket");
        }
        return List.copyOf(operations);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("dev.mars.peegeeq.cache.rest.server.ManagementBrowserSelection#shutdownScenarios")
    void shutdownScenario(ManagementBrowserCase scenario) throws Exception {
        int index = Integer.parseInt(scenario.id().substring(scenario.id().length() - 3)) - 1;
        ManagementConsolePostgresFixture.run(
                temporaryDirectory,
                POSTGRES.postgres(),
                true,
                scenario.operations(),
                context -> {
                    ManagementConsolePostgresFixture.authenticate(context);
                    ManagementConsolePostgresFixture.registerSetup(context);
                    Page page = context.page();
                    if (usesPubSub(index)) openPubSub(page, index);
                    if (usesMetrics(index)) openMetrics(page);
                    if (usesWebSocket(index)) openWebSocket(page);
                    assertThat(page.getByText("Setup: " + ManagementConsolePostgresFixture.SETUP_ID,
                            new Page.GetByTextOptions().setExact(true))).isVisible();
                    // Returning invokes the fixture's ordered browser/server shutdown and zero-leak assertion.
                });
    }

    private static boolean usesPubSub(int index) {
        return index == 3 || index == 5 || index == 6 || index == 7;
    }

    private static boolean usesMetrics(int index) {
        return index == 1 || index == 4 || index == 5 || index == 7;
    }

    private static boolean usesWebSocket(int index) {
        return index == 2 || index == 4 || index == 6 || index == 7;
    }

    private static void openPubSub(Page page, int index) {
        page.getByRole(AriaRole.LINK,
                new Page.GetByRoleOptions().setName("Pub/Sub").setExact(true)).click();
        String channel = "shutdown-" + index;
        page.getByLabel("Channel", new Page.GetByLabelOptions().setExact(true)).fill(channel);
        page.getByRole(AriaRole.BUTTON,
                new Page.GetByRoleOptions().setName("Start subscription")).click();
        assertThat(page.getByRole(AriaRole.REGION,
                new Page.GetByRoleOptions().setName("Subscription: " + channel).setExact(true))).isVisible();
    }

    private static void openMetrics(Page page) {
        page.getByRole(AriaRole.LINK,
                new Page.GetByRoleOptions().setName("Monitoring").setExact(true)).click();
        assertThat(page.getByText("Live metrics connected",
                new Page.GetByTextOptions().setExact(true))).isVisible();
    }

    private static void openWebSocket(Page page) {
        page.getByLabel("Open notifications").click();
        assertThat(page.getByText("Live", new Page.GetByTextOptions().setExact(true))).isVisible();
    }
}
