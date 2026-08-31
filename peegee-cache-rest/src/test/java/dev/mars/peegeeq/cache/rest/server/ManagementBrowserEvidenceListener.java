package dev.mars.peegeeq.cache.rest.server;

import org.junit.platform.engine.TestExecutionResult;
import org.junit.platform.engine.support.descriptor.MethodSource;
import org.junit.platform.launcher.TestExecutionListener;
import org.junit.platform.launcher.TestIdentifier;
import org.junit.platform.launcher.TestPlan;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Connects real JUnit browser outcomes to the single-file HTML evidence report. */
public final class ManagementBrowserEvidenceListener implements TestExecutionListener {

    private static final Pattern SCENARIO_ID = Pattern.compile("PW-[A-Z]+-[0-9]{3}");
    private static final Map<String, Long> STARTED = new ConcurrentHashMap<>();
    private static final Set<String> SENSITIVE_CANARIES = ConcurrentHashMap.newKeySet();
    private static final ThreadLocal<String> CURRENT_SCENARIO_TITLE = new ThreadLocal<>();
    private static final List<ManagementBrowserEvidenceReport.ScenarioResult> RESULTS =
            java.util.Collections.synchronizedList(new ArrayList<>());
    private static volatile Instant runStartedAt = Instant.now();

    @Override
    public void testPlanExecutionStarted(TestPlan testPlan) {
        runStartedAt = Instant.now();
        STARTED.clear();
        RESULTS.clear();
        clearSensitiveCanaries();
        try {
            Files.deleteIfExists(reportPath());
        } catch (IOException failure) {
            throw new IllegalStateException("Could not remove stale Playwright evidence", failure);
        }
    }

    @Override
    public void executionStarted(TestIdentifier testIdentifier) {
        if (!testIdentifier.isTest()) return;
        STARTED.put(testIdentifier.getUniqueId(), System.nanoTime());
        metadata(testIdentifier).ifPresent(scenario ->
                CURRENT_SCENARIO_TITLE.set(scenario.id() + " — " + scenario.name()));
    }

    @Override
    public void executionFinished(
            TestIdentifier testIdentifier,
            TestExecutionResult testExecutionResult) {
        if (!testIdentifier.isTest()) return;
        Optional<ScenarioMetadata> metadata = metadata(testIdentifier);
        if (metadata.isEmpty()) return;
        ScenarioMetadata scenario = metadata.orElseThrow();
        long started = STARTED.getOrDefault(testIdentifier.getUniqueId(), System.nanoTime());
        long durationMillis = Math.max(0L, (System.nanoTime() - started) / 1_000_000L);
        String status = switch (testExecutionResult.getStatus()) {
            case SUCCESSFUL -> "PASSED";
            case ABORTED -> "SKIPPED";
            case FAILED -> "FAILED";
        };
        RESULTS.add(new ManagementBrowserEvidenceReport.ScenarioResult(
                scenario.id(), scenario.name(), scenario.area(), scenario.risk(), status,
                durationMillis, scenario.requirement(), scenario.operations(), scenario.evidence(),
                testExecutionResult.getThrowable().map(ManagementBrowserEvidenceListener::failure).orElse("")));
        CURRENT_SCENARIO_TITLE.remove();
    }

    @Override
    public void testPlanExecutionFinished(TestPlan testPlan) {
        if (RESULTS.isEmpty()) return;
        Path report = reportPath();
        List<ManagementBrowserEvidenceReport.ScenarioResult> results;
        synchronized (RESULTS) {
            results = RESULTS.stream()
                    .sorted(Comparator.comparing(ManagementBrowserEvidenceReport.ScenarioResult::id))
                    .toList();
        }
        int expectedScenarios = Integer.parseInt(System.getProperty(
                "peegeeq.playwright.expectedScenarios", "0"));
        if (expectedScenarios > 0) assertExpectedScenarioCount(expectedScenarios, results.size());
        Instant completedAt = Instant.now();
        ManagementBrowserEvidenceReport evidence = new ManagementBrowserEvidenceReport(
                "playwright-" + completedAt.toString().replace(':', '-'),
                runStartedAt,
                completedAt,
                environment(),
                results,
                sensitiveCanaries());
        try {
            ManagementBrowserEvidenceWriter.write(report, evidence);
        } catch (IOException failure) {
            throw new IllegalStateException("Could not write Playwright evidence report " + report, failure);
        }
    }

    static void registerSensitiveCanary(String value) {
        if (value != null && !value.isEmpty()) SENSITIVE_CANARIES.add(value);
    }

    static Set<String> sensitiveCanaries() {
        return Set.copyOf(SENSITIVE_CANARIES);
    }

    static void clearSensitiveCanaries() {
        SENSITIVE_CANARIES.clear();
    }

    static String currentScenarioTitle() {
        return Optional.ofNullable(CURRENT_SCENARIO_TITLE.get()).orElse("");
    }

    static void assertExpectedScenarioCount(int expected, int actual) {
        if (expected < 1 || actual < 0) {
            throw new IllegalArgumentException("Scenario counts must be positive and non-negative");
        }
        if (expected != actual) {
            throw new IllegalStateException(
                    "Playwright evidence is incomplete: expected " + expected + " scenarios but recorded " + actual);
        }
    }

    private static Path reportPath() {
        return Path.of(System.getProperty(
                "peegeeq.playwright.report", "target/playwright-evidence.html"));
    }

    private static Optional<ScenarioMetadata> metadata(TestIdentifier identifier) {
        if (identifier.getSource().isEmpty()
                || !(identifier.getSource().orElseThrow() instanceof MethodSource source)) {
            return Optional.empty();
        }
        try {
            Class<?> type = Class.forName(source.getClassName());
            Method method = java.util.Arrays.stream(type.getDeclaredMethods())
                    .filter(candidate -> candidate.getName().equals(source.getMethodName()))
                    .findFirst()
                    .orElseThrow();
            ManagementBrowserScenario annotation = method.getAnnotation(ManagementBrowserScenario.class);
            if (annotation != null) return Optional.of(ScenarioMetadata.from(annotation, method.getName()));

            Matcher matcher = SCENARIO_ID.matcher(identifier.getDisplayName());
            if (!matcher.find()) return Optional.empty();
            String id = matcher.group();
            Method catalogue = java.util.Arrays.stream(type.getDeclaredMethods())
                    .filter(candidate -> candidate.getName().equals("scenarios"))
                    .filter(candidate -> java.lang.reflect.Modifier.isStatic(candidate.getModifiers()))
                    .findFirst()
                    .orElseThrow();
            catalogue.setAccessible(true);
            @SuppressWarnings("unchecked")
            List<ManagementBrowserCase> cases = (List<ManagementBrowserCase>) catalogue.invoke(null);
            return cases.stream().filter(candidate -> candidate.id().equals(id))
                    .findFirst().map(ScenarioMetadata::from);
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return Optional.empty();
        }
    }

    private static ManagementBrowserEvidenceReport.Environment environment() {
        java.lang.management.OperatingSystemMXBean operatingSystem =
                ManagementFactory.getOperatingSystemMXBean();
        long memoryBytes = operatingSystem instanceof com.sun.management.OperatingSystemMXBean extended
                ? extended.getTotalMemorySize()
                : Runtime.getRuntime().maxMemory();
        return new ManagementBrowserEvidenceReport.Environment(
                System.getProperty("java.version"),
                System.getProperty("os.name") + " " + System.getProperty("os.version")
                        + " (" + System.getProperty("os.arch") + ")",
                operatingSystem.getAvailableProcessors() + " logical processors",
                memoryBytes + " bytes",
                "Playwright 1.55.0 / Chromium",
                System.getProperty("peegeeq.postgres.image", "postgres:18.3-alpine"),
                gitCommit());
    }

    private static String gitCommit() {
        try {
            Process process = new ProcessBuilder("git", "rev-parse", "HEAD")
                    .redirectErrorStream(true).start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            return process.waitFor() == 0 && !output.isBlank() ? output : "unavailable";
        } catch (IOException | InterruptedException ignored) {
            if (ignored instanceof InterruptedException) Thread.currentThread().interrupt();
            return "unavailable";
        }
    }

    private static String failure(Throwable failure) {
        String value = failure.getClass().getSimpleName() + ": " + String.valueOf(failure.getMessage());
        return value.length() <= 4_000 ? value : value.substring(0, 4_000) + "…";
    }

    private record ScenarioMetadata(
            String id,
            String name,
            String requirement,
            ManagementBrowserArea area,
            ManagementBrowserRisk risk,
            List<String> operations,
            Set<ManagementBrowserEvidence> evidence) {

        private static ScenarioMetadata from(ManagementBrowserScenario scenario, String name) {
            return new ScenarioMetadata(
                    scenario.id(), name, scenario.requirement(), scenario.area(), scenario.risk(),
                    List.of(scenario.operations()), Set.of(scenario.evidence()));
        }

        private static ScenarioMetadata from(ManagementBrowserCase scenario) {
            return new ScenarioMetadata(
                    scenario.id(), scenario.action(), scenario.requirement(), scenario.area(), scenario.risk(),
                    scenario.operations(), scenario.evidence());
        }
    }
}
