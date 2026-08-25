package dev.mars.peegeeq.cache.rest.server;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.options.AriaRole;
import dev.mars.peegeeq.cache.rest.security.BrowserOriginPolicy;
import dev.mars.peegeeq.cache.rest.security.BrowserRequestSecurity;
import dev.mars.peegeeq.cache.rest.security.LocalTokenAuthenticationConfig;
import dev.mars.peegeeq.cache.rest.security.LocalTokenBootstrap;
import dev.mars.peegeeq.cache.rest.security.LocalTokenSessionManager;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

@ExtendWith(VertxExtension.class)
class ManagementConsoleLocalTokenIT {

    private ManagementHttpServer server;
    private LocalTokenBootstrap bootstrap;
    private int port;

    @BeforeEach
    void start(Vertx vertx, VertxTestContext context) throws Exception {
        port = freePort();
        String origin = origin();
        bootstrap = LocalTokenSessionManager.start(LocalTokenAuthenticationConfig.defaults());
        server = new ManagementHttpServer(
                vertx,
                TestManagementConfigurations.local(port),
                ManagementServerResources.noop(),
                new LocalSessionRoutes(
                        bootstrap.manager(),
                        new BrowserRequestSecurity(BrowserOriginPolicy.localToken(origin)),
                        16 * 1024));
        server.start()
                .onSuccess(ignored -> context.completeNow())
                .onFailure(context::failNow);
    }

    @AfterEach
    void stop(VertxTestContext context) {
        Future<Void> stopped = server == null ? Future.succeededFuture() : server.stop();
        stopped
                .onSuccess(ignored -> {
                    if (bootstrap != null) {
                        bootstrap.manager().close();
                    }
                    context.completeNow();
                })
                .onFailure(context::failNow);
    }

    @Test
    void packagedConsoleExchangesTokenNavigatesAndLogsOutWithoutSecretLeakage() {
        String token = bootstrap.token();
        List<String> browserErrors = new ArrayList<>();
        List<String> failedResponses = new ArrayList<>();
        try (Playwright playwright = Playwright.create(new Playwright.CreateOptions()
                .setEnv(Map.of("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD", "1")));
             Browser browser = playwright.chromium().launch(
                     new BrowserType.LaunchOptions().setChannel("chrome").setHeadless(true));
             BrowserContext context = browser.newContext()) {
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
            assertThat(page.getByLabel("Bootstrap token")).isVisible();
            page.getByLabel("Bootstrap token").fill(token);
            page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Connect")).click();

            assertThat(page.getByRole(AriaRole.HEADING, new Page.GetByRoleOptions().setName("Overview")))
                    .isVisible();
            assertThat(page.getByText("local-operator", new Page.GetByTextOptions().setExact(true)))
                    .isVisible();
            assertThat(page.getByText("Connected", new Page.GetByTextOptions().setExact(true)))
                    .isVisible();
            assertEquals(0, ((Number) page.evaluate("localStorage.length")).intValue());
            assertEquals(0, ((Number) page.evaluate("sessionStorage.length")).intValue());
            assertFalse(page.url().contains(token));
            assertFalse(page.content().contains(token));

            assertEquals(200, page.navigate(origin() + "/ui/monitoring").status());
            assertThat(page.getByRole(AriaRole.HEADING, new Page.GetByRoleOptions().setName("Monitoring")))
                    .isVisible();
            page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("End local session"))
                    .click();
            assertThat(page.getByRole(
                    AriaRole.HEADING,
                    new Page.GetByRoleOptions().setName("Connect to management console"))).isVisible();
            assertFalse(context.cookies().stream()
                    .anyMatch(cookie -> cookie.name.equals("PGQMGMTSESSION")));
            assertEquals(List.of("401 /api/v1/session"), failedResponses);
            assertEquals(List.of(), browserErrors);
        }
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
