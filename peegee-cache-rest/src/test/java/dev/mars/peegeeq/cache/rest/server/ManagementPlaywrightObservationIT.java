package dev.mars.peegeeq.cache.rest.server;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** Real-browser canary for the opt-in headed observation presentation. */
class ManagementPlaywrightObservationIT {

    @Test
    void scenarioOverlayCoversSetupNavigationsAndIsRemovedWhenTheFeatureIsReady() {
        try (Playwright playwright = Playwright.create(new Playwright.CreateOptions()
                .setEnv(Map.of("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD", "1")));
             Browser browser = playwright.chromium().launch(new BrowserType.LaunchOptions()
                     .setChannel("chrome").setHeadless(true))) {
            Page page = browser.newPage();
            ManagementPlaywright.ScenarioPresentation presentation =
                    ManagementPlaywright.beginScenario(
                            page, new ManagementPlaywright.Observation(false, 0, 0));

            page.navigate("data:text/html,<body><h1>Fixture login</h1></body>");
            assertThat(page.locator("#peegeeq-playwright-observation")).isVisible();
            assertEquals("fixed", page.locator("#peegeeq-playwright-observation")
                    .evaluate("element => getComputedStyle(element).position"));
            assertEquals("none", page.locator("#peegeeq-playwright-observation")
                    .evaluate("element => getComputedStyle(element).pointerEvents"));

            page.navigate("data:text/html,<body><h1>Prepared feature</h1></body>");
            assertThat(page.locator("#peegeeq-playwright-observation")).containsText(
                    "Focused PeeGeeQ Cache browser scenario");

            presentation.finish();

            assertThat(page.locator("#peegeeq-playwright-observation")).hasCount(0);
            presentation.close();
        }
    }
}
