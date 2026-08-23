package dev.mars.peegeeq.cache.rest.server;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.options.SameSiteAttribute;
import dev.mars.peegeeq.cache.api.management.ManagementSecretReference;
import dev.mars.peegeeq.cache.rest.security.SetupTargetPolicy;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import io.vertx.core.buffer.Buffer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ManagementBrowserHarnessIT {

    @TempDir
    Path temporaryDirectory;

    @Test
    void realBrowserKeepsCredentialsOutOfStorageAndEnforcesExactOrigin() throws Exception {
        int port = freePort();
        String origin = "http://127.0.0.1:" + port;
        ManagementSecretReference auditKey = new ManagementSecretReference("audit-key");
        ManagementServerConfiguration configuration = ManagementServerConfiguration.localToken(
                "127.0.0.1", port, origin,
                new SetupTargetPolicy(
                        Set.of("internal"), Set.of("10.0.0.0/8"), Set.of(5432),
                        false, true, false, false, Set.of("default")),
                temporaryDirectory.resolve("browser-audit.jsonl"), auditKey);
        PrometheusMeterRegistry meters = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        ManagementServerApplication application = await(ManagementServerApplication.start(
                configuration,
                reference -> reference.equals(auditKey) ? new byte[32] : null,
                hostname -> java.util.List.of(InetAddress.getByName(hostname)),
                ignored -> Buffer.buffer("unused"),
                meters));
        String token = application.takeBootstrapToken().orElseThrow();
        String harness = resource("/management-browser-harness.html");

        try (Playwright playwright = Playwright.create();
             Browser browser = playwright.chromium().launch(
                     new BrowserType.LaunchOptions().setChannel("chrome").setHeadless(true))) {
            BrowserContext context = browser.newContext();
            Page page = context.newPage();
            assertEquals(200, page.navigate(origin + "/ui/").status());
            page.setContent(harness);
            page.locator("#token").fill(token);
            page.locator("#exchange").click();
            page.waitForFunction("() => window.harnessResult !== undefined");

            @SuppressWarnings("unchecked")
            Map<String, Object> result = (Map<String, Object>) page.evaluate("window.harnessResult");
            assertEquals(200, ((Number) result.get("sessionStatus")).intValue());
            assertEquals("LOCAL_TOKEN", result.get("authenticationMode"));
            assertEquals(200, ((Number) result.get("setupsStatus")).intValue());
            assertEquals(0, ((Number) result.get("setupCount")).intValue());
            assertEquals("no-store", result.get("sessionCacheControl"), result::toString);
            assertEquals("no-store", result.get("setupsCacheControl"), result::toString);
            assertEquals(0, ((Number) page.evaluate("localStorage.length")).intValue());
            assertEquals(0, ((Number) page.evaluate("sessionStorage.length")).intValue());
            assertFalse(page.url().contains(token));
            assertFalse(page.content().contains(token));
            var cookie = context.cookies().stream()
                    .filter(candidate -> candidate.name.equals("PGQMGMTSESSION"))
                    .findFirst().orElseThrow();
            assertTrue(cookie.httpOnly);
            assertEquals(SameSiteAttribute.STRICT, cookie.sameSite);
            assertFalse(cookie.secure);

            BrowserContext wrongOriginContext = browser.newContext();
            Page wrongOrigin = wrongOriginContext.newPage();
            assertEquals(200, wrongOrigin.navigate("http://localhost:" + port + "/ui/").status());
            wrongOrigin.setContent(harness);
            wrongOrigin.locator("#token").fill("invalid-token-not-the-real-secret");
            wrongOrigin.locator("#exchange").click();
            wrongOrigin.waitForFunction("() => window.harnessResult !== undefined");
            assertEquals(403, ((Number) wrongOrigin.evaluate(
                    "window.harnessResult.sessionStatus")).intValue());
            assertEquals("CSRF_VALIDATION_FAILED", wrongOrigin.evaluate(
                    "window.harnessResult.errorCode"));
            assertEquals(0, wrongOriginContext.cookies().size());
            wrongOriginContext.close();
            context.close();
        } finally {
            await(application.closeAsync());
            meters.close();
        }
    }

    private static String resource(String name) throws Exception {
        try (var input = ManagementBrowserHarnessIT.class.getResourceAsStream(name)) {
            if (input == null) throw new IllegalStateException("Missing browser harness");
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static int freePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            return socket.getLocalPort();
        }
    }

    private static <T> T await(io.vertx.core.Future<T> future) throws Exception {
        return future.toCompletionStage().toCompletableFuture().get(20, TimeUnit.SECONDS);
    }
}
