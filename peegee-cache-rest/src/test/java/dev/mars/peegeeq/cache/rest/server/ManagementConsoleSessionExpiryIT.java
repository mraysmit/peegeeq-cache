package dev.mars.peegeeq.cache.rest.server;

import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.AriaRole;
import dev.mars.peegeeq.cache.rest.security.BrowserOriginPolicy;
import dev.mars.peegeeq.cache.rest.security.BrowserRequestSecurity;
import dev.mars.peegeeq.cache.rest.security.LocalTokenAuthenticationConfig;
import dev.mars.peegeeq.cache.rest.security.LocalTokenBootstrap;
import dev.mars.peegeeq.cache.rest.security.LocalTokenSessionManager;
import dev.mars.peegeeq.cache.rest.security.ManagementAuthenticationMode;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.net.ServerSocket;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;

@ExtendWith(VertxExtension.class)
class ManagementConsoleSessionExpiryIT {

    private ManagementHttpServer server;
    private LocalTokenBootstrap bootstrap;
    private int port;

    @BeforeEach
    void start(Vertx vertx, VertxTestContext context) throws Exception {
        port = freePort();
        String origin = origin();
        bootstrap = LocalTokenSessionManager.start(new LocalTokenAuthenticationConfig(
                ManagementAuthenticationMode.LOCAL_TOKEN,
                Duration.ofSeconds(2),
                Duration.ofSeconds(3)));
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

    @ManagementBrowserScenario(
            id = "PW-AUTH-004",
            requirement = "UI design: bounded session expiry and non-recoverability by reload or bootstrap replay",
            area = ManagementBrowserArea.AUTHENTICATION,
            risk = ManagementBrowserRisk.CRITICAL,
            action = "Authenticate, wait for the bounded session to expire, reload, and replay the consumed token",
            expectedResult = "The shell returns to login and neither reload nor token replay restores the expired session",
            cleanup = "Close the isolated browser context and bounded-session server lifecycle",
            operations = {"getSession", "exchangeLocalToken"},
            evidence = {
                    ManagementBrowserEvidence.VISIBLE_RESULT,
                    ManagementBrowserEvidence.HTTP_OPERATION,
                    ManagementBrowserEvidence.SENSITIVE_STATE,
                    ManagementBrowserEvidence.RESOURCE_CLEANUP
            })
    @Test
    void boundedSessionExpiresInTheShellAndCannotBeRestoredByReloadOrTokenReplay() {
        String token = bootstrap.token();
        ManagementBrowserEvidenceListener.registerSensitiveCanary(token);
        List<String> browserErrors = new ArrayList<>();
        try (BrowserContext context = ManagementBrowserPlaywrightSuite.browser().newContext();
             var screenshots = ManagementBrowserScreenshots.beforeClose(context)) {
            Page page = context.newPage();
            page.onPageError(browserErrors::add);
            assertEquals(200, page.navigate(origin() + "/ui/").status());
            page.getByLabel("Bootstrap token").fill(token);
            page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Connect")).click();
            assertThat(page.getByRole(
                    AriaRole.HEADING,
                    new Page.GetByRoleOptions().setName("Overview"))).isVisible();

            assertThat(page.getByRole(
                    AriaRole.HEADING,
                    new Page.GetByRoleOptions().setName("Connect to management console")))
                    .isVisible();
            assertEquals(0, ((Number) page.evaluate("localStorage.length")).intValue());
            assertEquals(0, ((Number) page.evaluate("sessionStorage.length")).intValue());

            page.reload();
            assertThat(page.getByRole(
                    AriaRole.HEADING,
                    new Page.GetByRoleOptions().setName("Connect to management console"))).isVisible();
            page.getByLabel("Bootstrap token").fill(token);
            page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Connect")).click();
            assertThat(page.getByText(
                    "INVALID_BOOTSTRAP_TOKEN",
                    new Page.GetByTextOptions().setExact(true))).isVisible();
            assertEquals("", page.getByLabel("Bootstrap token").inputValue());
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
