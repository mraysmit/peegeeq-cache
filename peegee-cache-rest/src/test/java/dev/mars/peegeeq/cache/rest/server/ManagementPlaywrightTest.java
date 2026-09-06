package dev.mars.peegeeq.cache.rest.server;

import com.microsoft.playwright.BrowserType;
import org.junit.jupiter.api.Test;

import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ManagementPlaywrightTest {

    @Test
    void observationConfigurationIsHeadlessAndUnpausedByDefault() {
        ManagementPlaywright.Observation actual = ManagementPlaywright.observation(new Properties());

        assertEquals(true, actual.headless());
        assertEquals(0.0, actual.slowMotionMillis());
        assertEquals(0L, actual.pauseBetweenScenariosMillis());
    }

    @Test
    void browserConfigurationUsesChromeByDefaultAndManagedChromiumWhenSelected() {
        BrowserType.LaunchOptions chrome = ManagementPlaywright.launchOptions(new Properties());
        assertEquals("chrome", chrome.channel);

        Properties properties = new Properties();
        properties.setProperty("peegeeq.playwright.browser", "chromium");
        BrowserType.LaunchOptions chromium = ManagementPlaywright.launchOptions(properties);
        assertEquals(null, chromium.channel);
    }

    @Test
    void browserConfigurationRejectsUnknownDistributions() {
        Properties properties = new Properties();
        properties.setProperty("peegeeq.playwright.browser", "firefox");

        assertThrows(IllegalArgumentException.class,
                () -> ManagementPlaywright.launchOptions(properties));
    }

    @Test
    void observationConfigurationAcceptsExplicitHeadedTiming() {
        Properties properties = new Properties();
        properties.setProperty("peegeeq.playwright.headless", "false");
        properties.setProperty("peegeeq.playwright.slowMo", "275");
        properties.setProperty("peegeeq.playwright.pauseBetweenScenarios", "1500");

        ManagementPlaywright.Observation actual = ManagementPlaywright.observation(properties);

        assertFalse(actual.headless());
        assertEquals(275.0, actual.slowMotionMillis());
        assertEquals(1_500L, actual.pauseBetweenScenariosMillis());
    }

    @Test
    void observationConfigurationRejectsNegativeAndNonFiniteTiming() {
        Properties properties = new Properties();
        properties.setProperty("peegeeq.playwright.slowMo", "-1");
        assertThrows(IllegalArgumentException.class,
                () -> ManagementPlaywright.observation(properties));

        properties.setProperty("peegeeq.playwright.slowMo", "NaN");
        assertThrows(IllegalArgumentException.class,
                () -> ManagementPlaywright.observation(properties));

        properties.setProperty("peegeeq.playwright.slowMo", "0");
        properties.setProperty("peegeeq.playwright.pauseBetweenScenarios", "-1");
        assertThrows(IllegalArgumentException.class,
                () -> ManagementPlaywright.observation(properties));
    }

    @Test
    void observationConfigurationRejectsAnAmbiguousHeadlessValue() {
        Properties properties = new Properties();
        properties.setProperty("peegeeq.playwright.headless", "headed");

        assertThrows(IllegalArgumentException.class,
                () -> ManagementPlaywright.observation(properties));
    }
}
