package dev.mars.peegeeq.cache.rest.server;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.Playwright;

import java.util.Map;

/** Owns the single Playwright and Chromium process used by the complete browser-test suite. */
final class ManagementBrowserPlaywrightSuite {

    private static Playwright playwright;
    private static Browser browser;

    private ManagementBrowserPlaywrightSuite() {
    }

    static synchronized Browser browser() {
        if (browser == null) {
            playwright = Playwright.create(new Playwright.CreateOptions()
                    .setEnv(Map.of("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD", "1")));
            try {
                browser = playwright.chromium().launch(ManagementPlaywright.launchOptions());
            } catch (RuntimeException failure) {
                playwright.close();
                playwright = null;
                throw failure;
            }
        }
        return browser;
    }

    static synchronized void close() {
        try {
            if (browser != null) browser.close();
        } finally {
            browser = null;
            if (playwright != null) {
                playwright.close();
                playwright = null;
            }
        }
    }
}
