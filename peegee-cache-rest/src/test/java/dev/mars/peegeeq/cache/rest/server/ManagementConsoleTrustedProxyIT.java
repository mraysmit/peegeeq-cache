package dev.mars.peegeeq.cache.rest.server;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.options.AriaRole;
import dev.mars.peegeeq.cache.api.management.ManagementSecretReference;
import dev.mars.peegeeq.cache.rest.security.BrowserOriginPolicy;
import dev.mars.peegeeq.cache.rest.security.SetupTargetPolicy;
import dev.mars.peegeeq.cache.rest.security.TrustedProxyAuthenticationConfig;
import dev.mars.peegeeq.cache.rest.security.TrustedProxyAuthenticator;
import dev.mars.peegeeq.cache.rest.security.TrustedProxySessionManager;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.net.ServerSocket;
import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

@ExtendWith(VertxExtension.class)
class ManagementConsoleTrustedProxyIT {

    private ManagementHttpServer server;
    private TrustedProxySessionManager sessions;
    private int port;

    @BeforeEach
    void start(Vertx vertx, VertxTestContext context) throws Exception {
        port = freePort();
        TrustedProxyAuthenticationConfig authentication =
                TrustedProxyAuthenticationConfig.defaults(Set.of("127.0.0.0/8"));
        BrowserOriginPolicy origins = BrowserOriginPolicy.trustedProxy(origin(), Set.of());
        sessions = TrustedProxySessionManager.createDefault();
        server = new ManagementHttpServer(
                vertx,
                ManagementServerConfiguration.trustedProxy(
                        "127.0.0.1",
                        port,
                        authentication,
                        origins,
                        SetupTargetPolicy.privateNetworks(
                                Set.of("internal.example"),
                                Set.of("10.0.0.0/8"),
                                Set.of(5432),
                                Set.of("corp-ca")),
                        Path.of("logs", "management-audit.jsonl"),
                        new ManagementSecretReference("env:PGQ_AUDIT_KEY")),
                ManagementServerResources.noop(),
                new TrustedProxySessionRoutes(
                        new TrustedProxyAuthenticator(authentication),
                        sessions,
                        origins));
        server.start()
                .onSuccess(ignored -> context.completeNow())
                .onFailure(context::failNow);
    }

    @AfterEach
    void stop(VertxTestContext context) {
        Future<Void> stopped = server == null ? Future.succeededFuture() : server.stop();
        stopped
                .onSuccess(ignored -> {
                    if (sessions != null) {
                        sessions.close();
                    }
                    context.completeNow();
                })
                .onFailure(context::failNow);
    }

    @Test
    void packagedConsoleBootstrapsFromTrustedIdentityHeadersWithoutLocalTokenUi() {
        List<String> browserErrors = new ArrayList<>();
        List<String> failedResponses = new ArrayList<>();
        try (Playwright playwright = Playwright.create(new Playwright.CreateOptions()
                .setEnv(Map.of("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD", "1")));
             Browser browser = playwright.chromium().launch(
                     ManagementPlaywright.launchOptions());
             BrowserContext context = browser.newContext(new Browser.NewContextOptions()
                     .setExtraHTTPHeaders(Map.of(
                             "X-PeeGeeQ-User", "alex",
                             "X-PeeGeeQ-Roles", "viewer,operator")))) {
            Page page = context.newPage();
            page.onConsoleMessage(message -> {
                if (message.type().equals("error")
                        && !message.text().startsWith("Failed to load resource:")) {
                    browserErrors.add(message.text());
                }
            });
            page.onPageError(browserErrors::add);
            page.onResponse(response -> {
                if (response.status() >= 400) {
                    failedResponses.add(response.status() + " " + URI.create(response.url()).getPath());
                }
            });

            assertEquals(200, page.navigate(origin() + "/ui/").status());
            assertThat(page.getByRole(AriaRole.HEADING, new Page.GetByRoleOptions().setName("Overview")))
                    .isVisible();
            assertThat(page.getByText("alex", new Page.GetByTextOptions().setExact(true))).isVisible();
            assertThat(page.getByText("Operator", new Page.GetByTextOptions().setExact(true))).isVisible();
            assertThat(page.getByText("Connected", new Page.GetByTextOptions().setExact(true)))
                    .isVisible();
            assertThat(page.getByLabel("Bootstrap token")).hasCount(0);
            assertThat(page.getByRole(
                    AriaRole.BUTTON,
                    new Page.GetByRoleOptions().setName("End local session"))).hasCount(0);
            assertEquals(0, ((Number) page.evaluate("localStorage.length")).intValue());
            assertEquals(0, ((Number) page.evaluate("sessionStorage.length")).intValue());

            assertEquals(200, page.navigate(origin() + "/ui/settings").status());
            assertThat(page.getByRole(AriaRole.HEADING, new Page.GetByRoleOptions().setName("Settings")))
                    .isVisible();
            assertFalse(page.url().contains("X-PeeGeeQ"));
            assertEquals(List.of(), failedResponses);
            assertEquals(List.of(), browserErrors);
        }
    }

    @Test
    void trustedIdentityAndRoleChangesRotateTheBrowserSession() {
        List<String> browserErrors = new ArrayList<>();
        try (Playwright playwright = Playwright.create(new Playwright.CreateOptions()
                .setEnv(Map.of("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD", "1")));
             Browser browser = playwright.chromium().launch(
                     ManagementPlaywright.launchOptions());
             BrowserContext context = browser.newContext(new Browser.NewContextOptions()
                     .setExtraHTTPHeaders(Map.of(
                             "X-PeeGeeQ-User", "alex",
                             "X-PeeGeeQ-Roles", "viewer,operator")))) {
            Page page = context.newPage();
            page.onPageError(browserErrors::add);
            assertEquals(200, page.navigate(origin() + "/ui/").status());
            assertThat(page.getByText("alex", new Page.GetByTextOptions().setExact(true))).isVisible();
            assertThat(page.getByText("Operator", new Page.GetByTextOptions().setExact(true))).isVisible();
            String operatorCookie = sessionCookie(context);

            context.setExtraHTTPHeaders(Map.of(
                    "X-PeeGeeQ-User", "blair",
                    "X-PeeGeeQ-Roles", "viewer"));
            page.reload();

            assertThat(page.getByRole(
                    AriaRole.HEADING,
                    new Page.GetByRoleOptions().setName("Overview"))).isVisible();
            assertThat(page.getByText("blair", new Page.GetByTextOptions().setExact(true))).isVisible();
            assertThat(page.getByText("Viewer", new Page.GetByTextOptions().setExact(true))).isVisible();
            assertThat(page.getByText("Operator", new Page.GetByTextOptions().setExact(true))).hasCount(0);
            assertNotEquals(operatorCookie, sessionCookie(context));
            assertEquals(0, ((Number) page.evaluate("localStorage.length")).intValue());
            assertEquals(0, ((Number) page.evaluate("sessionStorage.length")).intValue());
            assertEquals(List.of(), browserErrors);
        }
    }

    private static String sessionCookie(BrowserContext context) {
        return context.cookies().stream()
                .filter(cookie -> cookie.name.equals("PGQMGMTSESSION"))
                .findFirst()
                .orElseThrow()
                .value;
    }

    private String origin() {
        return "http://127.0.0.1:" + port;
    }

    private static int freePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
