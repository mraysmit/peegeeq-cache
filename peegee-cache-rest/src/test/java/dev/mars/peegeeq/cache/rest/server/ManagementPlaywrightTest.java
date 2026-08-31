package dev.mars.peegeeq.cache.rest.server;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ManagementPlaywrightTest {

    @BeforeEach
    @AfterEach
    void clearObservationProperties() {
        System.clearProperty("peegeeq.playwright.headless");
        System.clearProperty("peegeeq.playwright.slowMo");
        System.clearProperty("peegeeq.playwright.pauseBetweenScenarios");
    }

    @Test
    void observationConfigurationIsHeadlessAndUnpausedByDefault() {
        ManagementPlaywright.Observation actual = ManagementPlaywright.observation();

        assertEquals(true, actual.headless());
        assertEquals(0.0, actual.slowMotionMillis());
        assertEquals(0L, actual.pauseBetweenScenariosMillis());
    }

    @Test
    void observationConfigurationAcceptsExplicitHeadedTiming() {
        System.setProperty("peegeeq.playwright.headless", "false");
        System.setProperty("peegeeq.playwright.slowMo", "275");
        System.setProperty("peegeeq.playwright.pauseBetweenScenarios", "1500");

        ManagementPlaywright.Observation actual = ManagementPlaywright.observation();

        assertFalse(actual.headless());
        assertEquals(275.0, actual.slowMotionMillis());
        assertEquals(1_500L, actual.pauseBetweenScenariosMillis());
    }

    @Test
    void observationConfigurationRejectsNegativeAndNonFiniteTiming() {
        System.setProperty("peegeeq.playwright.slowMo", "-1");
        assertThrows(IllegalArgumentException.class, ManagementPlaywright::observation);

        System.setProperty("peegeeq.playwright.slowMo", "NaN");
        assertThrows(IllegalArgumentException.class, ManagementPlaywright::observation);

        System.setProperty("peegeeq.playwright.slowMo", "0");
        System.setProperty("peegeeq.playwright.pauseBetweenScenarios", "-1");
        assertThrows(IllegalArgumentException.class, ManagementPlaywright::observation);
    }

    @Test
    void observationConfigurationRejectsAnAmbiguousHeadlessValue() {
        System.setProperty("peegeeq.playwright.headless", "headed");

        assertThrows(IllegalArgumentException.class, ManagementPlaywright::observation);
    }
}
