package dev.mars.peegeeq.cache.rest.server;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Explicit opt-in filtering for parameterized browser catalogues. */
public final class ManagementBrowserSelection {

    private static final String PROPERTY = "peegeeq.playwright.scenarios";

    private ManagementBrowserSelection() {
    }

    static List<ManagementBrowserCase> select(List<ManagementBrowserCase> catalogue) {
        return select(catalogue, requestedScenarioIds());
    }

    static List<ManagementBrowserCase> select(
            List<ManagementBrowserCase> catalogue, String configured) {
        return select(catalogue, requestedScenarioIds(configured));
    }

    private static List<ManagementBrowserCase> select(
            List<ManagementBrowserCase> catalogue, Set<String> requested) {
        if (requested.isEmpty()) return List.copyOf(catalogue);
        return catalogue.stream().filter(scenario -> requested.contains(scenario.id())).toList();
    }

    static Set<String> requestedScenarioIds() {
        return ManagementBrowserRunConfig.current().requestedScenarioIds();
    }

    static Set<String> requestedScenarioIds(String configured) {
        configured = java.util.Objects.requireNonNull(configured, "configured").trim();
        if (configured.isEmpty()) return Set.of();
        List<String> values = Arrays.stream(configured.split(",", -1))
                .map(String::trim)
                .toList();
        if (values.stream().anyMatch(value -> !value.matches("PW-[A-Z]+-[0-9]{3}"))) {
            throw new IllegalArgumentException(PROPERTY
                    + " must contain comma-separated exact PW-<AREA>-NNN identifiers");
        }
        Set<String> requested = new LinkedHashSet<>(values);
        if (requested.size() != values.size()) {
            throw new IllegalArgumentException(PROPERTY + " contains duplicate scenario identifiers");
        }
        Set<String> unknown = new LinkedHashSet<>(requested);
        unknown.removeAll(AvailableScenarioIds.VALUE);
        if (!unknown.isEmpty()) {
            throw new IllegalArgumentException(PROPERTY + " contains unknown scenario identifiers: " + unknown);
        }
        return Set.copyOf(requested);
    }

    static boolean classHasRequestedScenario(Class<?> type) {
        return classHasRequestedScenario(type, requestedScenarioIds());
    }

    static boolean classHasRequestedScenario(Class<?> type, String configured) {
        return classHasRequestedScenario(type, requestedScenarioIds(configured));
    }

    private static boolean classHasRequestedScenario(Class<?> type, Set<String> requested) {
        if (requested.isEmpty()) return true;
        return scenarioIds(type).stream().anyMatch(requested::contains);
    }

    public static List<ManagementBrowserCase> accessibilityScenarios() { return select(ManagementAccessibilityBrowserIT.scenarios()); }
    public static List<ManagementBrowserCase> counterScenarios() { return select(ManagementCounterBrowserIT.scenarios()); }
    public static List<ManagementBrowserCase> entryAdministrationScenarios() { return select(ManagementEntryAdministrationBrowserIT.scenarios()); }
    public static List<ManagementBrowserCase> entryBulkScenarios() { return select(ManagementEntryBulkBrowserIT.scenarios()); }
    public static List<ManagementBrowserCase> entryInspectionScenarios() { return select(ManagementEntryInspectionBrowserIT.scenarios()); }
    public static List<ManagementBrowserCase> liveTransportScenarios() { return select(ManagementLiveTransportBrowserIT.scenarios()); }
    public static List<ManagementBrowserCase> lockScenarios() { return select(ManagementLockBrowserIT.scenarios()); }
    public static List<ManagementBrowserCase> monitoringScenarios() { return select(ManagementMonitoringBrowserIT.scenarios()); }
    public static List<ManagementBrowserCase> packagingScenarios() { return select(ManagementPackagingBrowserIT.scenarios()); }
    public static List<ManagementBrowserCase> privacyScenarios() { return select(ManagementPrivacyBrowserIT.scenarios()); }
    public static List<ManagementBrowserCase> pubSubScenarios() { return select(ManagementPubSubBrowserIT.scenarios()); }
    public static List<ManagementBrowserCase> shutdownScenarios() { return select(ManagementShutdownBrowserIT.scenarios()); }

    private static Set<String> scenarioIds(Class<?> type) {
        Set<String> ids = new LinkedHashSet<>();
        Arrays.stream(type.getDeclaredMethods())
                .map(method -> method.getAnnotation(ManagementBrowserScenario.class))
                .filter(java.util.Objects::nonNull)
                .map(ManagementBrowserScenario::id)
                .forEach(ids::add);
        parameterizedCatalogue(type).stream().map(ManagementBrowserCase::id).forEach(ids::add);
        return Set.copyOf(ids);
    }

    private static List<ManagementBrowserCase> parameterizedCatalogue(Class<?> type) {
        if (type == ManagementAccessibilityBrowserIT.class) return ManagementAccessibilityBrowserIT.scenarios();
        if (type == ManagementCounterBrowserIT.class) return ManagementCounterBrowserIT.scenarios();
        if (type == ManagementEntryAdministrationBrowserIT.class) return ManagementEntryAdministrationBrowserIT.scenarios();
        if (type == ManagementEntryBulkBrowserIT.class) return ManagementEntryBulkBrowserIT.scenarios();
        if (type == ManagementEntryInspectionBrowserIT.class) return ManagementEntryInspectionBrowserIT.scenarios();
        if (type == ManagementLiveTransportBrowserIT.class) return ManagementLiveTransportBrowserIT.scenarios();
        if (type == ManagementLockBrowserIT.class) return ManagementLockBrowserIT.scenarios();
        if (type == ManagementMonitoringBrowserIT.class) return ManagementMonitoringBrowserIT.scenarios();
        if (type == ManagementPackagingBrowserIT.class) return ManagementPackagingBrowserIT.scenarios();
        if (type == ManagementPrivacyBrowserIT.class) return ManagementPrivacyBrowserIT.scenarios();
        if (type == ManagementPubSubBrowserIT.class) return ManagementPubSubBrowserIT.scenarios();
        if (type == ManagementShutdownBrowserIT.class) return ManagementShutdownBrowserIT.scenarios();
        return List.of();
    }

    private static final class AvailableScenarioIds {
        private static final Set<String> VALUE = availableScenarioIds();

        private static Set<String> availableScenarioIds() {
            Set<String> ids = new LinkedHashSet<>();
            List.of(
                    ManagementBrowserHarnessIT.class,
                    ManagementConsoleLocalTokenIT.class,
                    ManagementConsoleSessionExpiryIT.class,
                    ManagementConsoleTrustedProxyIT.class,
                    ManagementConsoleProductJourneysIT.class,
                    ManagementShellBrowserIT.class,
                    ManagementAuthenticationBrowserIT.class,
                    ManagementSetupBrowserIT.class,
                    ManagementOverviewBrowserIT.class,
                    ManagementNamespaceBrowserIT.class,
                    ManagementEntryInspectionBrowserIT.class,
                    ManagementEntryAdministrationBrowserIT.class,
                    ManagementEntryBulkBrowserIT.class,
                    ManagementCounterBrowserIT.class,
                    ManagementLockBrowserIT.class,
                    ManagementPubSubBrowserIT.class,
                    ManagementLiveTransportBrowserIT.class,
                    ManagementMonitoringBrowserIT.class,
                    ManagementViewerBrowserIT.class,
                    ManagementBoundaryBrowserIT.class,
                    ManagementAccessibilityBrowserIT.class,
                    ManagementPrivacyBrowserIT.class,
                    ManagementPackagingBrowserIT.class,
                    ManagementShutdownBrowserIT.class)
                    .forEach(type -> ids.addAll(scenarioIds(type)));
            return Set.copyOf(ids);
        }
    }
}
