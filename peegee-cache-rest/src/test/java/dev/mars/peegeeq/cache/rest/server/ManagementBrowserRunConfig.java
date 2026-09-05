package dev.mars.peegeeq.cache.rest.server;

import dev.mars.peegeeq.cache.test.PostgreSQLTestConstants;

import java.nio.file.Path;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;

/** Immutable process-boundary configuration shared by the browser verification harness. */
record ManagementBrowserRunConfig(
        ManagementPlaywright.Observation observation,
        Set<String> requestedScenarioIds,
        boolean focusedClassRun,
        int expectedScenarios,
        Path reportPath,
        Path artifactDirectory,
        Path documentationScreenshotsDirectory,
        Path runnableArtifact,
        String postgresImage) {

    ManagementBrowserRunConfig {
        Objects.requireNonNull(observation, "observation");
        requestedScenarioIds = Set.copyOf(requestedScenarioIds);
        Objects.requireNonNull(reportPath, "reportPath");
        Objects.requireNonNull(artifactDirectory, "artifactDirectory");
        Objects.requireNonNull(documentationScreenshotsDirectory, "documentationScreenshotsDirectory");
        Objects.requireNonNull(runnableArtifact, "runnableArtifact");
        Objects.requireNonNull(postgresImage, "postgresImage");
        if (expectedScenarios < 0) {
            throw new IllegalArgumentException(
                    "peegeeq.playwright.expectedScenarios must not be negative");
        }
        if (postgresImage.isBlank()) {
            throw new IllegalArgumentException("PostgreSQL image must not be blank");
        }
    }

    static ManagementBrowserRunConfig current() {
        return Current.VALUE;
    }

    static ManagementBrowserRunConfig fromProperties(Properties properties) {
        Objects.requireNonNull(properties, "properties");
        Properties snapshot = new Properties();
        snapshot.putAll(properties);
        return new ManagementBrowserRunConfig(
                ManagementPlaywright.observation(snapshot),
                ManagementBrowserSelection.requestedScenarioIds(
                        snapshot.getProperty("peegeeq.playwright.scenarios", "")),
                !snapshot.getProperty("it.test", "").isBlank(),
                integer(snapshot, "peegeeq.playwright.expectedScenarios", 0),
                Path.of(snapshot.getProperty(
                        "peegeeq.playwright.report", "target/playwright-evidence.html")),
                Path.of(snapshot.getProperty(
                        "peegeeq.playwright.artifacts", "target/playwright-artifacts")),
                Path.of(snapshot.getProperty(
                        "peegeeq.playwright.screenshots", "../peegee-cache-management-ui/docs/screenshots")),
                Path.of(snapshot.getProperty(
                        "peegeeq.runnable.artifact", "target/peegee-cache-rest-runnable.jar")),
                PostgreSQLTestConstants.postgresImage(snapshot));
    }

    private static int integer(Properties properties, String name, int defaultValue) {
        String value = properties.getProperty(name);
        if (value == null) return defaultValue;
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException failure) {
            throw new IllegalArgumentException(name + " must be an integer", failure);
        }
    }

    private static final class Current {
        private static final ManagementBrowserRunConfig VALUE = fromSystemProperties();

        private static ManagementBrowserRunConfig fromSystemProperties() {
            return fromProperties(System.getProperties());
        }
    }
}
