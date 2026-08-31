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
        String configured = System.getProperty(PROPERTY, "").trim();
        if (configured.isEmpty()) return List.copyOf(catalogue);

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
        Set<String> available = catalogue.stream()
                .map(ManagementBrowserCase::id)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        Set<String> unknown = new LinkedHashSet<>(requested);
        unknown.removeAll(available);
        if (!unknown.isEmpty()) {
            throw new IllegalArgumentException(PROPERTY + " does not match this catalogue: " + unknown);
        }
        return catalogue.stream().filter(scenario -> requested.contains(scenario.id())).toList();
    }

    public static List<ManagementBrowserCase> accessibilityScenarios() { return select(ManagementAccessibilityBrowserIT.scenarios()); }
    public static List<ManagementBrowserCase> capabilityScenarios() { return select(ManagementCapabilityBrowserIT.scenarios()); }
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
}
