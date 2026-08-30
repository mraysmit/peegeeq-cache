package dev.mars.peegeeq.cache.rest.server;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ManagementBrowserCoverageTest {

    private static final int CURRENT_SCENARIO_COUNT = 540;

    private static final Set<String> REQUIRED_JOURNEYS = Set.of(
            "trusted-proxy-session",
            "local-token-session",
            "setup-lifecycle",
            "scope-and-capabilities",
            "overview-and-namespaces",
            "entry-lifecycle",
            "entry-bulk-delete",
            "counter-lifecycle",
            "lock-lifecycle",
            "pubsub-lifecycle",
            "live-recovery",
            "server-authorization",
            "accessibility-and-responsive",
            "packaged-hosting",
            "cross-surface-leakage",
            "deterministic-shutdown");

    private static final Set<String> REQUIRED_OPERATIONS = Set.of(
            "getSession", "exchangeLocalToken", "deleteLocalSession",
            "listSetups", "testUnregisteredSetup", "registerSetup", "getSetup",
            "connectSetup", "testRegisteredSetup", "detachSetup", "forgetSetup",
            "getSetupHealth", "getSetupCapabilities", "getOverview", "listNamespaces",
            "exportNamespaces", "getNamespace", "listEntries", "getEntry",
            "revealEntryValue", "setEntry", "deleteEntry", "expireEntry", "persistEntry",
            "touchEntry", "previewEntryBulkDelete", "executeEntryBulkDelete", "listCounters",
            "getCounter", "setCounter", "adjustCounter", "expireCounter", "persistCounter",
            "deleteCounter", "previewCounterBulkDelete", "executeCounterBulkDelete", "listLocks",
            "getLock", "revealLockOwner", "forceReleaseLock", "createPubSubSubscription",
            "streamPubSubMessages", "revealPubSubPayload", "deletePubSubSubscription",
            "publishPubSubMessage", "getDatabaseMonitoring", "getRuntimeMonitoring",
            "streamMetrics", "listActivity", "monitoringWebSocket");

    private static final Set<String> DATABASE_MUTATION_OPERATIONS = Set.of(
            "registerSetup", "connectSetup", "detachSetup", "forgetSetup",
            "setEntry", "deleteEntry", "expireEntry", "persistEntry", "touchEntry",
            "executeEntryBulkDelete", "setCounter", "adjustCounter", "expireCounter",
            "persistCounter", "deleteCounter", "executeCounterBulkDelete",
            "forceReleaseLock", "createPubSubSubscription", "deletePubSubSubscription",
            "publishPubSubMessage");

    private static final Set<String> SENSITIVE_OPERATIONS = Set.of(
            "exchangeLocalToken", "revealEntryValue", "revealLockOwner", "revealPubSubPayload");

    private static final List<Class<?>> BROWSER_TEST_TYPES = List.of(
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
            ManagementAccessibilityBrowserIT.class,
            ManagementPrivacyBrowserIT.class,
            ManagementPackagingBrowserIT.class,
            ManagementShutdownBrowserIT.class);

    private static final List<List<ManagementBrowserCase>> PARAMETERIZED_CATALOGUES = List.of(
            ManagementEntryInspectionBrowserIT.scenarios(),
            ManagementEntryAdministrationBrowserIT.scenarios(),
            ManagementEntryBulkBrowserIT.scenarios(),
            ManagementCounterBrowserIT.scenarios(),
            ManagementLockBrowserIT.scenarios(),
            ManagementPubSubBrowserIT.scenarios(),
            ManagementLiveTransportBrowserIT.scenarios(),
            ManagementMonitoringBrowserIT.scenarios(),
            ManagementAccessibilityBrowserIT.scenarios(),
            ManagementPrivacyBrowserIT.scenarios(),
            ManagementPackagingBrowserIT.scenarios(),
            ManagementShutdownBrowserIT.scenarios());

    @Test
    void realBrowserSuiteCoversEveryRequiredJourneyAndOperationExactlyOnce() {
        Map<String, List<String>> journeyOwners = new LinkedHashMap<>();
        Map<String, List<String>> operationOwners = new LinkedHashMap<>();
        for (Class<?> type : BROWSER_TEST_TYPES) {
            for (Method method : type.getDeclaredMethods()) {
                ManagementBrowserJourney journey = method.getAnnotation(ManagementBrowserJourney.class);
                if (journey == null) {
                    continue;
                }
                String owner = type.getSimpleName() + "." + method.getName();
                journeyOwners.computeIfAbsent(journey.value(), ignored -> new ArrayList<>()).add(owner);
                Arrays.stream(journey.operations()).forEach(operation ->
                        operationOwners.computeIfAbsent(operation, ignored -> new ArrayList<>()).add(owner));
            }
        }

        assertEquals(REQUIRED_JOURNEYS, journeyOwners.keySet(),
                () -> coverageDifference("journeys", REQUIRED_JOURNEYS, journeyOwners));
        assertEquals(Set.of(), duplicateKeys(journeyOwners),
                () -> "Journey IDs must have one owner: " + journeyOwners);
        assertEquals(REQUIRED_OPERATIONS, operationOwners.keySet(),
                () -> coverageDifference("operations", REQUIRED_OPERATIONS, operationOwners));
        assertEquals(Set.of(), duplicateKeys(operationOwners),
                () -> "Operation IDs must have one accountable journey owner: " + operationOwners);
    }

    @Test
    void everyCurrentBrowserScenarioHasCompleteUniqueAccountabilityMetadata() {
        List<Method> browserTests = BROWSER_TEST_TYPES.stream()
                .flatMap(type -> Arrays.stream(type.getDeclaredMethods()))
                .filter(ManagementBrowserCoverageTest::isBrowserTest)
                .toList();
        Map<String, List<String>> scenarioOwners = new LinkedHashMap<>();
        List<String> missingMetadata = new ArrayList<>();
        List<String> metadataViolations = new ArrayList<>();

        for (Method method : browserTests) {
            String owner = method.getDeclaringClass().getSimpleName() + "." + method.getName();
            ManagementBrowserScenario scenario = method.getAnnotation(ManagementBrowserScenario.class);
            if (scenario == null) {
                missingMetadata.add(owner);
                continue;
            }
            scenarioOwners.computeIfAbsent(scenario.id(), ignored -> new ArrayList<>()).add(owner);
            ManagementBrowserAccountability.violations(
                            method, REQUIRED_OPERATIONS, DATABASE_MUTATION_OPERATIONS, SENSITIVE_OPERATIONS)
                    .forEach(violation -> metadataViolations.add(owner + ": " + violation));

            ManagementBrowserJourney journey = method.getAnnotation(ManagementBrowserJourney.class);
            if (journey != null) {
                assertEquals(Set.of(journey.operations()), Set.of(scenario.operations()),
                        () -> owner + " scenario operations do not match its journey operations");
            }
        }

        PARAMETERIZED_CATALOGUES.forEach(catalogue -> catalogue.forEach(scenario -> {
            String owner = "parameterized:" + scenario.id();
            scenarioOwners.computeIfAbsent(scenario.id(), ignored -> new ArrayList<>()).add(owner);
            parameterizedViolations(scenario).forEach(violation ->
                    metadataViolations.add(owner + ": " + violation));
        }));

        int parameterizedCount = PARAMETERIZED_CATALOGUES.stream().mapToInt(List::size).sum();

        assertEquals(CURRENT_SCENARIO_COUNT, browserTests.size() + parameterizedCount,
                "The active implementation wave must catalogue the exact current browser-test count");
        assertEquals(List.of(), missingMetadata,
                "Every real-browser test must own scenario metadata");
        assertEquals(List.of(), metadataViolations,
                "Every real-browser scenario must satisfy the evidence contract");
        assertEquals(CURRENT_SCENARIO_COUNT, scenarioOwners.size(),
                "Every current real-browser test must have a unique scenario ID");
        assertEquals(Set.of(), duplicateKeys(scenarioOwners),
                () -> "Scenario IDs must have one owner: " + scenarioOwners);
    }

    private static List<String> parameterizedViolations(ManagementBrowserCase scenario) {
        List<String> violations = new ArrayList<>();
        if (!scenario.id().matches("PW-[A-Z]+-[0-9]{3}")) violations.add("invalid scenario ID");
        if (scenario.requirement().isBlank()) violations.add("missing source requirement");
        if (scenario.action().isBlank()) violations.add("missing browser action");
        if (scenario.expectedResult().isBlank()) violations.add("missing expected result");
        if (scenario.cleanup().isBlank()) violations.add("missing cleanup obligation");
        if (!scenario.evidence().contains(ManagementBrowserEvidence.VISIBLE_RESULT)) violations.add("missing visible-result evidence");
        if (!scenario.evidence().contains(ManagementBrowserEvidence.RESOURCE_CLEANUP)) violations.add("missing cleanup evidence");
        Set<String> unknown = new LinkedHashSet<>(scenario.operations());
        unknown.removeAll(REQUIRED_OPERATIONS);
        if (!unknown.isEmpty()) violations.add("unknown operations " + unknown);
        if (!scenario.operations().isEmpty() && !scenario.evidence().contains(ManagementBrowserEvidence.HTTP_OPERATION)) violations.add("operations declared without operation evidence");
        if (scenario.operations().stream().anyMatch(DATABASE_MUTATION_OPERATIONS::contains)
                && !scenario.evidence().contains(ManagementBrowserEvidence.DATABASE)
                && !scenario.evidence().contains(ManagementBrowserEvidence.DURABLE_AUDIT)) violations.add("mutation lacks database or durable-audit evidence");
        if (scenario.operations().stream().anyMatch(SENSITIVE_OPERATIONS::contains)
                && !scenario.evidence().contains(ManagementBrowserEvidence.SENSITIVE_STATE)) violations.add("sensitive operation lacks leakage evidence");
        return violations;
    }

    private static boolean isBrowserTest(Method method) {
        return method.isAnnotationPresent(Test.class)
                || method.isAnnotationPresent(ManagementBrowserJourney.class);
    }

    private static Set<String> duplicateKeys(Map<String, List<String>> owners) {
        Set<String> duplicates = new LinkedHashSet<>();
        owners.forEach((key, values) -> {
            if (values.size() > 1) {
                duplicates.add(key);
            }
        });
        return duplicates;
    }

    private static String coverageDifference(
            String label,
            Set<String> required,
            Map<String, List<String>> actual) {
        Set<String> missing = new LinkedHashSet<>(required);
        missing.removeAll(actual.keySet());
        Set<String> unexpected = new LinkedHashSet<>(actual.keySet());
        unexpected.removeAll(required);
        return "Real-browser " + label + " missing=" + missing + ", unexpected=" + unexpected;
    }
}
