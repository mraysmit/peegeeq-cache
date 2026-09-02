package dev.mars.peegeeq.cache.rest.server;

import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;

import java.util.Objects;
import java.util.Properties;
import java.util.function.Consumer;

/** Shared browser launch policy: headless by default, opt-in headed mode for local observation. */
final class ManagementPlaywright {

    private static final String HEADLESS_PROPERTY = "peegeeq.playwright.headless";
    private static final String SLOW_MOTION_PROPERTY = "peegeeq.playwright.slowMo";
    private static final String PAUSE_PROPERTY = "peegeeq.playwright.pauseBetweenScenarios";

    private ManagementPlaywright() {
    }

    static BrowserType.LaunchOptions launchOptions() {
        Observation observation = ManagementBrowserRunConfig.current().observation();
        return new BrowserType.LaunchOptions()
                .setChannel("chrome")
                .setHeadless(observation.headless())
                .setSlowMo(observation.slowMotionMillis());
    }

    static Observation observation() {
        return ManagementBrowserRunConfig.current().observation();
    }

    static Observation observation(Properties properties) {
        Objects.requireNonNull(properties, "properties");
        String configuredHeadless = properties.getProperty(HEADLESS_PROPERTY, "true").trim();
        if (!configuredHeadless.equalsIgnoreCase("true")
                && !configuredHeadless.equalsIgnoreCase("false")) {
            throw new IllegalArgumentException(HEADLESS_PROPERTY + " must be true or false");
        }
        boolean headless = Boolean.parseBoolean(configuredHeadless);
        double slowMotion = Double.parseDouble(properties.getProperty(SLOW_MOTION_PROPERTY, "0"));
        long pause = Long.parseLong(properties.getProperty(PAUSE_PROPERTY, "0"));
        if (!Double.isFinite(slowMotion) || slowMotion < 0) {
            throw new IllegalArgumentException(SLOW_MOTION_PROPERTY + " must be finite and non-negative");
        }
        if (pause < 0) throw new IllegalArgumentException(PAUSE_PROPERTY + " must not be negative");
        return new Observation(headless, slowMotion, pause);
    }

    static ScenarioPresentation beginScenario(Page page) {
        return beginScenario(page, ManagementBrowserRunConfig.current().observation());
    }

    static ScenarioPresentation beginScenario(Page page, Observation observation) {
        Objects.requireNonNull(observation, "observation");
        if (observation.headless()) return ScenarioPresentation.none();
        String title = ManagementBrowserEvidenceListener.currentScenarioTitle();
        if (title.isBlank()) title = "Focused PeeGeeQ Cache browser scenario";
        String scenarioTitle = title;
        Consumer<Page> installer = loaded -> installBanner(loaded, scenarioTitle);
        page.onDOMContentLoaded(installer);
        installBanner(page, scenarioTitle);
        return new ScenarioPresentation(
                page, installer, observation.pauseBetweenScenariosMillis());
    }

    private static void installBanner(Page page, String title) {
        page.evaluate("""
                title => {
                  document.getElementById('peegeeq-playwright-observation')?.remove();
                  const banner = document.createElement('div');
                  banner.id = 'peegeeq-playwright-observation';
                  banner.textContent = title;
                  banner.style.cssText = 'position:fixed;inset:0;z-index:2147483647;display:grid;'
                    + 'place-items:center;padding:32px;background:#10243e;color:#fff;'
                    + 'font:600 24px/1.4 system-ui;text-align:center;pointer-events:none';
                  document.body.appendChild(banner);
                }
                """, title);
    }

    record Observation(boolean headless, double slowMotionMillis, long pauseBetweenScenariosMillis) {
    }

    static final class ScenarioPresentation implements AutoCloseable {
        private final Page page;
        private final Consumer<Page> installer;
        private final long pauseMillis;
        private boolean active;

        private ScenarioPresentation(Page page, Consumer<Page> installer, long pauseMillis) {
            this.page = page;
            this.installer = installer;
            this.pauseMillis = pauseMillis;
            this.active = page != null;
        }

        private static ScenarioPresentation none() {
            return new ScenarioPresentation(null, null, 0);
        }

        void finish() {
            if (!active) return;
            if (pauseMillis > 0) page.waitForTimeout(pauseMillis);
            close();
        }

        @Override
        public void close() {
            if (!active) return;
            active = false;
            page.offDOMContentLoaded(installer);
            if (!page.isClosed()) {
                page.evaluate("document.getElementById('peegeeq-playwright-observation')?.remove()");
            }
        }
    }
}
