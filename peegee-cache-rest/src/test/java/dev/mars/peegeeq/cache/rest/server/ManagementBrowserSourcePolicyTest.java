package dev.mars.peegeeq.cache.rest.server;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ManagementBrowserSourcePolicyTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void rejectsEveryProhibitedInterceptionAndSyntheticPagePattern() throws IOException {
        Path source = temporaryDirectory.resolve("ProhibitedBrowserPatterns.java");
        Files.writeString(source, """
                page.route("**/api/**", route -> route.fulfill(options));
                context.route("**/events", route -> route.abort());
                page.setContent("<main>synthetic product</main>");
                page.addInitScript("window.syntheticProduct = true");
                Playwright.create();
                playwright.chromium().launch();
                """);

        List<String> violations = ManagementBrowserSourcePolicy.violations(temporaryDirectory);

        assertEquals(List.of(
                "ProhibitedBrowserPatterns.java:1: page.route",
                "ProhibitedBrowserPatterns.java:1: route.fulfill",
                "ProhibitedBrowserPatterns.java:2: context.route",
                "ProhibitedBrowserPatterns.java:2: route.abort",
                "ProhibitedBrowserPatterns.java:3: page.setContent",
                "ProhibitedBrowserPatterns.java:4: page.addInitScript",
                "ProhibitedBrowserPatterns.java:5: Playwright.create outside suite owner",
                "ProhibitedBrowserPatterns.java:6: Chromium launch outside suite owner"), violations);
    }

    @Test
    void currentRealBrowserSourcesContainNoProhibitedProductSubstitution() throws IOException {
        Path browserSources = Path.of("src", "test", "java", "dev", "mars", "peegeeq",
                "cache", "rest", "server");

        assertEquals(List.of(), ManagementBrowserSourcePolicy.violations(browserSources));
    }
}
