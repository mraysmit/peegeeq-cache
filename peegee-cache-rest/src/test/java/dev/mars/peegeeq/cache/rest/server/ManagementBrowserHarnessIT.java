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
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(VertxExtension.class)
class ManagementBrowserHarnessIT {

    private static final String EXCHANGE_AND_INSPECT = """
            async token => {
              try {
                const session = await fetch('/api/v1/session/local', {
                  method: 'POST',
                  credentials: 'include',
                  headers: {'content-type': 'application/json'},
                  body: JSON.stringify({token})
                });
                const sessionBody = await session.json();
                const setups = session.ok
                  ? await fetch('/api/v1/setups', {credentials: 'include'})
                  : null;
                const setupsBody = setups ? await setups.json() : null;
                return {
                  sessionStatus: session.status,
                  sessionCacheControl: session.headers.get('cache-control'),
                  authenticationMode: sessionBody.authenticationMode || null,
                  setupsStatus: setups ? setups.status : null,
                  setupsCacheControl: setups ? setups.headers.get('cache-control') : null,
                  setupCount: setupsBody && setupsBody.items ? setupsBody.items.length : null,
                  errorCode: sessionBody.code || null
                };
              } catch (error) {
                return {fetchRejected: true, message: String(error)};
              }
            }
            """;

    @TempDir
    Path temporaryDirectory;

    @Test
    void realBrowserKeepsCredentialsOutOfStorageAndEnforcesExactOrigin(
            Vertx vertx,
            VertxTestContext context) throws Exception {
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
        Future<Void> verification = ManagementServerApplication.start(
                configuration,
                reference -> reference.equals(auditKey) ? new byte[32] : null,
                hostname -> java.util.List.of(InetAddress.getByName(hostname)),
                ignored -> Buffer.buffer("unused"),
                meters)
                .compose(application -> {
                    String token = application.takeBootstrapToken().orElseThrow();
                    Future<Void> browserVerification = vertx.executeBlocking(() -> {
                        verifyBrowserContract(port, origin, token);
                        return null;
                    });
                    return closeApplicationAfter(browserVerification, application);
                })
                .transform(result -> {
                    meters.close();
                    return result.failed()
                            ? Future.failedFuture(result.cause())
                            : Future.succeededFuture();
                });

        verification
                .onSuccess(ignored -> context.completeNow())
                .onFailure(context::failNow);
    }

    private static void verifyBrowserContract(int port, String origin, String token) {
        try (Playwright playwright = Playwright.create(new Playwright.CreateOptions()
                .setEnv(Map.of("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD", "1")));
             Browser browser = playwright.chromium().launch(
                     new BrowserType.LaunchOptions().setChannel("chrome").setHeadless(true))) {
            BrowserContext context = browser.newContext();
            Page page = context.newPage();
            assertEquals(200, page.navigate(origin + "/ui/").status());

            @SuppressWarnings("unchecked")
            Map<String, Object> result =
                    (Map<String, Object>) page.evaluate(EXCHANGE_AND_INSPECT, token);
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
            @SuppressWarnings("unchecked")
            Map<String, Object> wrongOriginResult = (Map<String, Object>) wrongOrigin.evaluate(
                    EXCHANGE_AND_INSPECT,
                    "invalid-token-not-the-real-secret");
            assertEquals(403, ((Number) wrongOriginResult.get("sessionStatus")).intValue());
            assertEquals("CSRF_VALIDATION_FAILED", wrongOriginResult.get("errorCode"));
            assertEquals(0, wrongOriginContext.cookies().size());
            wrongOriginContext.close();
            context.close();
        }
    }

    private static Future<Void> closeApplicationAfter(
            Future<Void> operation,
            ManagementServerApplication application) {
        return operation.transform(operationResult -> application.closeAsync().transform(closeResult -> {
            if (operationResult.failed()) {
                if (closeResult.failed()) {
                    operationResult.cause().addSuppressed(closeResult.cause());
                }
                return Future.failedFuture(operationResult.cause());
            }
            return closeResult.failed()
                    ? Future.failedFuture(closeResult.cause())
                    : Future.succeededFuture();
        }));
    }

    private static int freePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            return socket.getLocalPort();
        }
    }

}
