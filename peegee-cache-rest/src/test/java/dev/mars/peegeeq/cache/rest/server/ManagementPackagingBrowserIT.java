package dev.mars.peegeeq.cache.rest.server;

import com.microsoft.playwright.Page;
import com.microsoft.playwright.Response;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Wave P6 real-browser scenarios for packaged hosting and security headers. */
class ManagementPackagingBrowserIT {

    @RegisterExtension
    static final ManagementBrowserPostgresWorker POSTGRES = new ManagementBrowserPostgresWorker();

    @TempDir
    Path temporaryDirectory;

    private static final List<String> ACTIONS = List.of(
            "serve the packaged root document successfully",
            "serve a deep-link navigation through the SPA fallback",
            "label packaged HTML with the UTF-8 HTML media type",
            "prevent caching of the console document",
            "apply the no-cache compatibility header to the console document",
            "apply the reviewed self-only content security policy",
            "disable MIME sniffing on the console document",
            "deny referrers and framing for the console document",
            "serve the fingerprinted script with a JavaScript media type",
            "cache the fingerprinted script immutably for one year",
            "return a structured 404 for a missing asset",
            "reject percent-encoded traversal before classpath lookup");

    static List<ManagementBrowserCase> scenarios() {
        return IntStream.range(0, ACTIONS.size())
                .mapToObj(index -> new ManagementBrowserCase(
                        "PW-PACKAGE-%03d".formatted(index + 1),
                        "P6 packaged-hosting contract: " + ACTIONS.get(index),
                        ManagementBrowserArea.HARDENING,
                        ManagementBrowserRisk.HIGH,
                        ACTIONS.get(index),
                        "The management server must " + ACTIONS.get(index) + ".",
                        "Close all response pages, reset PostgreSQL, and close server and browser resources",
                        List.of(),
                        Set.of(ManagementBrowserEvidence.VISIBLE_RESULT, ManagementBrowserEvidence.RESOURCE_CLEANUP)))
                .toList();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("dev.mars.peegeeq.cache.rest.server.ManagementBrowserSelection#packagingScenarios")
    void packagingScenario(ManagementBrowserCase scenario) throws Exception {
        int index = Integer.parseInt(scenario.id().substring(scenario.id().length() - 3)) - 1;
        ManagementConsolePostgresFixture.run(
                temporaryDirectory,
                POSTGRES.postgres(),
                false,
                scenario.operations(),
                context -> {
                    if (index == 10) context.diagnostics().expectFailedResponse(404, "/ui/assets/not-present.js");
                    if (index == 11) context.diagnostics().expectFailedResponse(404, "/pom.xml");
                    verify(index, context.page(), context.origin());
                });
    }

    private static void verify(int index, Page page, String origin) {
        if (index == 10) {
            Response missing = required(page.navigate(origin + "/ui/assets/not-present.js"));
            assertEquals(404, missing.status());
            assertEquals("UI_RESOURCE_NOT_FOUND", missing.headers().get("x-peegeeq-error-code"));
            return;
        }
        if (index == 11) {
            Response traversal = required(page.navigate(origin + "/ui/assets/%2e%2e/%2e%2e/pom.xml"));
            assertEquals(404, traversal.status());
            assertTrue(traversal.headers().get("content-type").startsWith("application/problem+json"));
            return;
        }
        Response html = required(page.navigate(origin + (index == 1 ? "/ui/settings" : "/ui/")));
        if (index == 0 || index == 1) {
            assertEquals(200, html.status());
            assertFalse(html.text().isBlank());
            return;
        }
        switch (index) {
            case 2 -> assertTrue(html.headers().get("content-type").startsWith("text/html; charset=utf-8"));
            case 3 -> assertEquals("no-store", html.headers().get("cache-control"));
            case 4 -> assertEquals("no-cache", html.headers().get("pragma"));
            case 5 -> {
                String policy = html.headers().get("content-security-policy");
                assertTrue(policy.contains("default-src 'self'"));
                assertTrue(policy.contains("frame-ancestors 'none'"));
                assertTrue(policy.contains("connect-src 'self'"));
            }
            case 6 -> assertEquals("nosniff", html.headers().get("x-content-type-options"));
            case 7 -> {
                assertEquals("no-referrer", html.headers().get("referrer-policy"));
                assertEquals("DENY", html.headers().get("x-frame-options"));
            }
            case 8, 9 -> {
                String scriptSource = page.locator("script[src]").getAttribute("src");
                assertNotNull(scriptSource);
                Response script = required(page.navigate(origin + scriptSource));
                if (index == 8) {
                    assertTrue(script.headers().get("content-type").startsWith("text/javascript"));
                    assertFalse(script.text().isBlank());
                } else {
                    assertEquals("public, max-age=31536000, immutable",
                            script.headers().get("cache-control"));
                }
            }
            default -> throw new IllegalArgumentException("Unknown packaging scenario " + index);
        }
    }

    private static Response required(Response response) {
        return java.util.Objects.requireNonNull(response, "Navigation must produce an HTTP response");
    }
}
