package dev.mars.peegeeq.cache.rest.server;

import com.microsoft.playwright.BrowserType;

/** Shared browser launch policy: headless by default, opt-in headed mode for local observation. */
final class ManagementPlaywright {

    private static final String HEADLESS_PROPERTY = "peegeeq.playwright.headless";
    private static final String SLOW_MOTION_PROPERTY = "peegeeq.playwright.slowMo";

    private ManagementPlaywright() {
    }

    static BrowserType.LaunchOptions launchOptions() {
        boolean headless = Boolean.parseBoolean(System.getProperty(HEADLESS_PROPERTY, "true"));
        double slowMotion = Double.parseDouble(System.getProperty(SLOW_MOTION_PROPERTY, "0"));
        if (slowMotion < 0) {
            throw new IllegalArgumentException(SLOW_MOTION_PROPERTY + " must not be negative");
        }
        return new BrowserType.LaunchOptions()
                .setChannel("chrome")
                .setHeadless(headless)
                .setSlowMo(slowMotion);
    }
}
