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
import java.util.stream.IntStream;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;

/** Real packaged-browser degradation coverage for every independently wired setup capability. */
class ManagementCapabilityBrowserIT {

    @RegisterExtension
    static final ManagementBrowserPostgresWorker POSTGRES = new ManagementBrowserPostgresWorker();

    @TempDir
    Path temporaryDirectory;

    private static final List<String> ACTIONS = List.of(
            "hide Counters navigation and block its direct route when counter inspection is unavailable",
            "hide Locks navigation and block its direct route when lock inspection is unavailable",
            "hide Pub/Sub navigation and block its direct route when Pub/Sub is unavailable",
            "hide Namespaces and Keys navigation and block both direct routes when namespace inspection is unavailable",
            "remove entry bulk deletion while retaining ordinary entry administration",
            "remove counter bulk deletion while retaining ordinary counter administration",
            "remove force release while retaining lock inspection and authorized owner reveal",
            "remove entry value reveal while retaining lock owner reveal",
            "remove lock owner reveal while retaining entry value reveal",
            "remove Pub/Sub payload reveal while retaining masked live messages",
            "remove expired-entry status selection when expired-entry inspection is unavailable");

    static List<ManagementBrowserCase> scenarios() {
        return IntStream.range(0, ACTIONS.size())
                .mapToObj(index -> new ManagementBrowserCase(
                        "PW-CAPABILITY-%03d".formatted(index + 1),
                        "UI capability degradation contract: " + ACTIONS.get(index),
                        ManagementBrowserArea.SHELL,
                        ManagementBrowserRisk.CRITICAL,
                        ACTIONS.get(index),
                        "The packaged UI must " + ACTIONS.get(index) + ".",
                        "Reset the capability-specific setup, PostgreSQL schema, browser, server, and pool",
                        operations(index),
                        index == 9 ? Set.of(
                                ManagementBrowserEvidence.VISIBLE_RESULT,
                                ManagementBrowserEvidence.HTTP_OPERATION,
                                ManagementBrowserEvidence.DATABASE,
                                ManagementBrowserEvidence.DURABLE_AUDIT,
                                ManagementBrowserEvidence.SENSITIVE_STATE,
                                ManagementBrowserEvidence.RESOURCE_CLEANUP) : Set.of(
                                ManagementBrowserEvidence.VISIBLE_RESULT,
                                ManagementBrowserEvidence.HTTP_OPERATION,
                                ManagementBrowserEvidence.DATABASE,
                                ManagementBrowserEvidence.SENSITIVE_STATE,
                                ManagementBrowserEvidence.RESOURCE_CLEANUP)))
                .toList();
    }

    private static List<String> operations(int index) {
        return switch (index) {
            case 4, 10 -> List.of("getSetupCapabilities", "listNamespaces", "getNamespace", "listEntries");
            case 5 -> List.of("getSetupCapabilities", "listCounters");
            case 6 -> List.of("getSetupCapabilities", "listLocks", "getLock");
            case 7, 8 -> List.of(
                    "getSetupCapabilities", "listNamespaces", "getNamespace", "listEntries", "getEntry",
                    "listLocks", "getLock");
            case 9 -> List.of(
                    "getSetupCapabilities", "createPubSubSubscription", "streamPubSubMessages",
                    "publishPubSubMessage");
            default -> List.of("getSetupCapabilities");
        };
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("dev.mars.peegeeq.cache.rest.server.ManagementBrowserSelection#capabilityScenarios")
    void capabilityScenario(ManagementBrowserCase scenario) throws Exception {
        int index = Integer.parseInt(scenario.id().substring(scenario.id().length() - 3)) - 1;
        ManagementConsolePostgresFixture.runWithCapabilities(
                temporaryDirectory,
                POSTGRES.postgres(),
                true,
                capabilities(index),
                scenario.operations(),
                context -> {
                    ManagementConsolePostgresFixture.authenticate(context);
                    ManagementConsolePostgresFixture.registerSetup(context);
                    verify(index, context.page(), context.origin());
                });
    }

    private static void verify(int index, Page page, String origin) {
        switch (index) {
            case 0 -> verifyUnavailableRoute(page, origin, "Counters", "/ui/counters");
            case 1 -> verifyUnavailableRoute(page, origin, "Locks", "/ui/locks");
            case 2 -> verifyUnavailableRoute(page, origin, "Pub/Sub", "/ui/pubsub");
            case 3 -> {
                assertThat(navigation(page, "Namespaces")).hasCount(0);
                assertThat(navigation(page, "Keys")).hasCount(0);
                page.navigate(origin + "/ui/namespaces");
                heading(page, "Namespaces unavailable");
                page.navigate(origin + "/ui/keys");
                heading(page, "Keys unavailable");
            }
            case 4 -> {
                openEntries(page);
                assertThat(page.getByRole(AriaRole.BUTTON,
                        new Page.GetByRoleOptions().setName("Create entry").setExact(true))).isVisible();
                assertThat(page.getByRole(AriaRole.BUTTON,
                        new Page.GetByRoleOptions().setName("Preview selected deletion"))).hasCount(0);
                assertThat(page.getByLabel("Select customer:1")).hasCount(0);
            }
            case 5 -> {
                navigation(page, "Counters").click();
                heading(page, "Counters");
                assertThat(page.getByRole(AriaRole.BUTTON,
                        new Page.GetByRoleOptions().setName("Create counter"))).isVisible();
                assertThat(page.getByRole(AriaRole.BUTTON,
                        new Page.GetByRoleOptions().setName("Manage count"))).isVisible();
                assertThat(page.getByRole(AriaRole.BUTTON,
                        new Page.GetByRoleOptions().setName("Preview selected counter deletion"))).hasCount(0);
                assertThat(page.getByLabel("Select logical-orders/count")).hasCount(0);
            }
            case 6 -> {
                Locator dialog = openLock(page);
                assertThat(dialog.getByRole(AriaRole.BUTTON,
                        new Locator.GetByRoleOptions().setName("Reveal owner"))).isVisible();
                assertThat(dialog.getByRole(AriaRole.BUTTON,
                        new Locator.GetByRoleOptions().setName("Force release"))).hasCount(0);
            }
            case 7 -> {
                openEntries(page);
                page.getByRole(AriaRole.LINK,
                        new Page.GetByRoleOptions().setName("customer:1").setExact(true)).click();
                assertThat(page.getByText("Value hidden", exact())).isVisible();
                assertThat(page.getByRole(AriaRole.BUTTON,
                        new Page.GetByRoleOptions().setName("Reveal value"))).hasCount(0);
                Locator lock = openLock(page);
                assertThat(lock.getByRole(AriaRole.BUTTON,
                        new Locator.GetByRoleOptions().setName("Reveal owner"))).isVisible();
            }
            case 8 -> {
                openEntries(page);
                page.getByRole(AriaRole.LINK,
                        new Page.GetByRoleOptions().setName("customer:1").setExact(true)).click();
                assertThat(page.getByRole(AriaRole.BUTTON,
                        new Page.GetByRoleOptions().setName("Reveal value"))).isVisible();
                Locator lock = openLock(page);
                assertThat(lock.getByText("Masked", new Locator.GetByTextOptions().setExact(true))).isVisible();
                assertThat(lock.getByRole(AriaRole.BUTTON,
                        new Locator.GetByRoleOptions().setName("Reveal owner"))).hasCount(0);
            }
            case 9 -> {
                navigation(page, "Pub/Sub").click();
                page.getByLabel("Channel", new Page.GetByLabelOptions().setExact(true)).fill("events");
                page.getByRole(AriaRole.BUTTON,
                        new Page.GetByRoleOptions().setName("Start subscription")).click();
                page.getByLabel("Publish channel").fill("events");
                page.getByLabel("Payload").fill("retained-value");
                page.getByRole(AriaRole.BUTTON,
                        new Page.GetByRoleOptions().setName("Publish").setExact(true)).click();
                assertThat(page.locator("tbody tr")).hasCount(1);
                assertThat(page.locator("tbody tr")).containsText("Masked");
                assertThat(page.getByRole(AriaRole.BUTTON,
                        new Page.GetByRoleOptions().setName("Reveal payload"))).hasCount(0);
            }
            case 10 -> {
                openEntries(page);
                assertThat(page.locator("#entry-ttl-state option[value=INCLUDE_EXPIRED]")).hasCount(0);
            }
            default -> throw new IllegalArgumentException("Unknown capability scenario " + index);
        }
    }

    private static void verifyUnavailableRoute(Page page, String origin, String label, String path) {
        assertThat(navigation(page, label)).hasCount(0);
        page.navigate(origin + path);
        heading(page, label + " unavailable");
    }

    private static void openEntries(Page page) {
        navigation(page, "Namespaces").click();
        page.getByRole(AriaRole.LINK,
                new Page.GetByRoleOptions().setName(ManagementConsolePostgresFixture.NAMESPACE).setExact(true)).click();
        page.getByRole(AriaRole.TAB, new Page.GetByRoleOptions().setName("Entries")).click();
        heading(page, "Key Browser");
    }

    private static Locator openLock(Page page) {
        navigation(page, "Locks").click();
        heading(page, "Locks");
        page.getByRole(AriaRole.BUTTON,
                new Page.GetByRoleOptions().setName("Manage lease")).click();
        return page.getByRole(AriaRole.DIALOG,
                new Page.GetByRoleOptions().setName("Manage lease"));
    }

    private static Locator navigation(Page page, String label) {
        return page.getByRole(AriaRole.NAVIGATION,
                        new Page.GetByRoleOptions().setName("Management sections"))
                .getByRole(AriaRole.LINK, new Locator.GetByRoleOptions().setName(label).setExact(true));
    }

    private static void heading(Page page, String name) {
        assertThat(page.getByRole(AriaRole.HEADING,
                new Page.GetByRoleOptions().setName(name).setExact(true))).isVisible();
    }

    private static Page.GetByTextOptions exact() {
        return new Page.GetByTextOptions().setExact(true);
    }

    private static SetupCapabilities capabilities(int index) {
        SetupCapabilities.Features all = SetupCapabilities.allSupported().features();
        SetupCapabilities.Features features = switch (index) {
            case 0 -> copy(all, null, null, false, null, null, null, null, null, null, null, null, null);
            case 1 -> copy(all, null, null, null, false, null, null, null, null, null, null, null, null);
            case 2 -> copy(all, null, null, null, null, null, null, null, false, null, null, null, null);
            case 3 -> copy(all, false, null, null, null, null, null, null, null, null, null, null, null);
            case 4 -> copy(all, null, null, null, null, null, false, null, null, null, null, null, null);
            case 5 -> copy(all, null, null, null, null, null, null, false, null, null, null, null, null);
            case 6 -> copy(all, null, null, null, null, false, null, null, null, null, null, null, null);
            case 7 -> copy(all, null, null, null, null, null, null, null, null, null, false, null, null);
            case 8 -> copy(all, null, null, null, null, null, null, null, null, null, null, false, null);
            case 9 -> copy(all, null, null, null, null, null, null, null, null, null, null, null, false);
            case 10 -> copy(all, null, false, null, null, null, null, null, null, null, null, null, null);
            default -> throw new IllegalArgumentException("Unknown capability scenario " + index);
        };
        return new SetupCapabilities("1", features, SetupCapabilities.allSupported().limits());
    }

    private static SetupCapabilities.Features copy(
            SetupCapabilities.Features source,
            Boolean namespaceInspection,
            Boolean expiredEntryInspection,
            Boolean counterInspection,
            Boolean lockInspection,
            Boolean forcedLockRelease,
            Boolean bulkEntryDelete,
            Boolean bulkCounterDelete,
            Boolean pubSub,
            Boolean databaseStatistics,
            Boolean entryValueReveal,
            Boolean lockOwnerReveal,
            Boolean pubSubPayloadReveal) {
        return new SetupCapabilities.Features(
                value(namespaceInspection, source.namespaceInspection()),
                value(expiredEntryInspection, source.expiredEntryInspection()),
                value(counterInspection, source.counterInspection()),
                value(lockInspection, source.lockInspection()),
                value(forcedLockRelease, source.forcedLockRelease()),
                value(bulkEntryDelete, source.bulkEntryDelete()),
                value(bulkCounterDelete, source.bulkCounterDelete()),
                value(pubSub, source.pubSub()),
                value(databaseStatistics, source.databaseStatistics()),
                value(entryValueReveal, source.entryValueReveal()),
                value(lockOwnerReveal, source.lockOwnerReveal()),
                value(pubSubPayloadReveal, source.pubSubPayloadReveal()));
    }

    private static boolean value(Boolean override, boolean fallback) {
        return override == null ? fallback : override;
    }
}
