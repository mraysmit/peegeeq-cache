package dev.mars.peegeeq.cache.rest.server;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Properties;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class ManagementBrowserRunConfigTest {

    @Test
    void snapshotsAllBrowserSettingsFromAnIsolatedPropertySet() {
        Properties properties = new Properties();
        properties.setProperty("peegeeq.playwright.headless", "false");
        properties.setProperty("peegeeq.playwright.slowMo", "125");
        properties.setProperty("peegeeq.playwright.pauseBetweenScenarios", "250");
        properties.setProperty("peegeeq.playwright.scenarios", "PW-CAPABILITY-008");
        properties.setProperty("peegeeq.playwright.expectedScenarios", "549");
        properties.setProperty("peegeeq.playwright.report", "reports/evidence.html");
        properties.setProperty("peegeeq.playwright.artifacts", "reports/artifacts");
        properties.setProperty("peegeeq.runnable.artifact", "artifacts/rest-runnable.jar");
        properties.setProperty("peegeeq.test.postgres.image", "postgres:17-alpine");

        ManagementBrowserRunConfig config =
                ManagementBrowserRunConfig.fromProperties(properties);
        properties.clear();

        assertFalse(config.observation().headless());
        assertEquals(125, config.observation().slowMotionMillis());
        assertEquals(250, config.observation().pauseBetweenScenariosMillis());
        assertEquals(Set.of("PW-CAPABILITY-008"), config.requestedScenarioIds());
        assertEquals(549, config.expectedScenarios());
        assertEquals(Path.of("reports/evidence.html"), config.reportPath());
        assertEquals(Path.of("reports/artifacts"), config.artifactDirectory());
        assertEquals(Path.of("artifacts/rest-runnable.jar"), config.runnableArtifact());
        assertEquals("postgres:17-alpine", config.postgresImage());
    }
}
