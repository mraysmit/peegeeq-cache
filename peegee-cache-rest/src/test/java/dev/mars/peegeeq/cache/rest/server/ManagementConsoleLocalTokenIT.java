package dev.mars.peegeeq.cache.rest.server;

import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
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
import java.util.concurrent.atomic.AtomicBoolean;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

@ExtendWith(VertxExtension.class)
class ManagementConsoleLocalTokenIT {

    private ManagementHttpServer server;
    private LocalTokenBootstrap bootstrap;
    private final AtomicBoolean failNextLogout = new AtomicBoolean();
    private int port;

    @BeforeEach
    void start(Vertx vertx, VertxTestContext context) throws Exception {
        port = freePort();
        String origin = origin();
        bootstrap = LocalTokenSessionManager.start(LocalTokenAuthenticationConfig.defaults());
        ManagementRequestRouter sessions = new LocalSessionRoutes(
                bootstrap.manager(),
                new BrowserRequestSecurity(BrowserOriginPolicy.localToken(origin)),
                16 * 1024);
        SetupRegistry registry = new SetupRegistry(
                (definition, secret) -> Future.failedFuture("Setup connection is not expected"),
                reference -> null);
        server = new ManagementHttpServer(
                vertx,
                TestManagementConfigurations.local(port),
                ManagementServerResources.noop(),
                ManagementRequestRouter.firstOf(
                        this::failLogoutOnce,
                        sessions,
                        new SetupReadRoutes(
                                registry,
                                new LocalSessionRequestAuthenticator(bootstrap.manager()))));
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
            id = "PW-AUTH-001",
            requirement = "UI design: local bootstrap exchange, authenticated navigation, logout, and secret cleanup",
            area = ManagementBrowserArea.AUTHENTICATION,
            risk = ManagementBrowserRisk.CRITICAL,
            action = "Exchange a bootstrap token, navigate the packaged console, and log out",
            expectedResult = "The shell authenticates, the token leaves browser surfaces, and logout removes the session",
            cleanup = "Close the isolated browser context and local-token server lifecycle",
            operations = {"getSession", "exchangeLocalToken", "deleteLocalSession"},
            evidence = {
                    ManagementBrowserEvidence.VISIBLE_RESULT,
                    ManagementBrowserEvidence.HTTP_OPERATION,
                    ManagementBrowserEvidence.SENSITIVE_STATE,
                    ManagementBrowserEvidence.RESOURCE_CLEANUP
            })
    @ManagementBrowserJourney(
            value = "local-token-session",
            operations = {"getSession", "exchangeLocalToken", "deleteLocalSession"})
    void packagedConsoleExchangesTokenNavigatesAndLogsOutWithoutSecretLeakage() {
        String token = bootstrap.token();
        ManagementBrowserEvidenceListener.registerSensitiveCanary(token);
        List<String> browserErrors = new ArrayList<>();
        List<String> failedResponses = new ArrayList<>();
        try (BrowserContext context = ManagementBrowserPlaywrightSuite.browser().newContext();
             var screenshots = ManagementBrowserScreenshots.beforeClose(context)) {
            Page page = context.newPage();
            ManagementBrowserOperationTrace operationTrace = new ManagementBrowserOperationTrace();
            operationTrace.attach(page);
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

            assertEquals(200, page.navigate(origin() + "/ui/keys?prefix=a%2Fb%25").status());
            assertThat(page.getByRole(AriaRole.HEADING, new Page.GetByRoleOptions().setName("Key Browser")))
                    .isVisible();
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
            assertEquals(List.of("401 /api/v1/session", "401 /api/v1/setups"), failedResponses);
            assertEquals(List.of(), browserErrors);
            operationTrace.assertObserved("getSession", "exchangeLocalToken", "deleteLocalSession");
        }
    }

    @ManagementBrowserScenario(
            id = "PW-AUTH-002",
            requirement = "UI design: invalid and single-use bootstrap-token behavior",
            area = ManagementBrowserArea.AUTHENTICATION,
            risk = ManagementBrowserRisk.CRITICAL,
            action = "Submit an invalid token, authenticate once, and replay the consumed token in a second context",
            expectedResult = "Invalid and replayed tokens are rejected without leaking beyond the password control",
            cleanup = "Close both isolated browser contexts and the local-token server lifecycle",
            operations = {"getSession", "exchangeLocalToken"},
            evidence = {
                    ManagementBrowserEvidence.VISIBLE_RESULT,
                    ManagementBrowserEvidence.HTTP_OPERATION,
                    ManagementBrowserEvidence.SENSITIVE_STATE,
                    ManagementBrowserEvidence.RESOURCE_CLEANUP
            })
    @Test
    void invalidAndReplayedBootstrapTokensRemainVisibleOnlyInThePasswordControl() {
        String token = bootstrap.token();
        ManagementBrowserEvidenceListener.registerSensitiveCanary(token);
        List<String> browserErrors = new ArrayList<>();
        var browser = ManagementBrowserPlaywrightSuite.browser();
        try (BrowserContext authenticatedContext = browser.newContext();
             var authenticatedScreenshots = ManagementBrowserScreenshots.beforeClose(authenticatedContext);
             BrowserContext replayContext = browser.newContext();
             var replayScreenshots = ManagementBrowserScreenshots.beforeClose(replayContext)) {
            Page authenticatedPage = authenticatedContext.newPage();
            authenticatedPage.onPageError(browserErrors::add);
            assertEquals(200, authenticatedPage.navigate(origin() + "/ui/").status());
            authenticatedPage.getByLabel("Bootstrap token").fill("invalid-bootstrap-token");
            authenticatedPage.getByRole(
                    AriaRole.BUTTON,
                    new Page.GetByRoleOptions().setName("Connect")).click();

            assertThat(authenticatedPage.getByText(
                    "INVALID_BOOTSTRAP_TOKEN",
                    new Page.GetByTextOptions().setExact(true))).isVisible();
            assertEquals("", authenticatedPage.getByLabel("Bootstrap token").inputValue());
            assertFalse(authenticatedPage.content().contains("invalid-bootstrap-token"));

            authenticatedPage.getByLabel("Bootstrap token").fill(token);
            authenticatedPage.getByRole(
                    AriaRole.BUTTON,
                    new Page.GetByRoleOptions().setName("Connect")).click();
            assertThat(authenticatedPage.getByRole(
                    AriaRole.HEADING,
                    new Page.GetByRoleOptions().setName("Overview"))).isVisible();

            Page replayPage = replayContext.newPage();
            replayPage.onPageError(browserErrors::add);
            assertEquals(200, replayPage.navigate(origin() + "/ui/").status());
            replayPage.getByLabel("Bootstrap token").fill(token);
            replayPage.getByRole(
                    AriaRole.BUTTON,
                    new Page.GetByRoleOptions().setName("Connect")).click();
            assertThat(replayPage.getByText(
                    "INVALID_BOOTSTRAP_TOKEN",
                    new Page.GetByTextOptions().setExact(true))).isVisible();
            assertEquals("", replayPage.getByLabel("Bootstrap token").inputValue());
            assertEquals(0, ((Number) replayPage.evaluate("localStorage.length")).intValue());
            assertEquals(0, ((Number) replayPage.evaluate("sessionStorage.length")).intValue());
            assertFalse(replayPage.url().contains(token));
            assertFalse(replayPage.content().contains(token));

            authenticatedPage.reload();
            assertThat(authenticatedPage.getByRole(
                    AriaRole.HEADING,
                    new Page.GetByRoleOptions().setName("Overview"))).isVisible();
            assertEquals(List.of(), browserErrors);
        }
    }

    @ManagementBrowserScenario(
            id = "PW-AUTH-003",
            requirement = "UI design: failed local logout remains retryable and does not invent unauthenticated state",
            area = ManagementBrowserArea.AUTHENTICATION,
            risk = ManagementBrowserRisk.HIGH,
            action = "Force one logout failure and retry logout from the still-authenticated shell",
            expectedResult = "The first failure preserves authenticated UI state and the retry ends the session",
            cleanup = "Clear the injected one-shot failure and close browser and server resources",
            operations = {"getSession", "exchangeLocalToken", "deleteLocalSession"},
            evidence = {
                    ManagementBrowserEvidence.VISIBLE_RESULT,
                    ManagementBrowserEvidence.HTTP_OPERATION,
                    ManagementBrowserEvidence.SENSITIVE_STATE,
                    ManagementBrowserEvidence.RESOURCE_CLEANUP
            })
    @Test
    void failedLogoutKeepsTheAuthenticatedShellAndCanBeRetried() {
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
            assertFalse(page.url().contains(token));
            assertFalse(page.content().contains(token));
            assertEquals(0, ((Number) page.evaluate("localStorage.length")).intValue());
            assertEquals(0, ((Number) page.evaluate("sessionStorage.length")).intValue());

            failNextLogout.set(true);
            page.getByRole(
                    AriaRole.BUTTON,
                    new Page.GetByRoleOptions().setName("End local session")).click();

            assertThat(page.getByRole(AriaRole.ALERT)).containsText("LOGOUT_TEST_FAILURE");
            assertThat(page.getByRole(
                    AriaRole.HEADING,
                    new Page.GetByRoleOptions().setName("Overview"))).isVisible();
            assertThat(page.getByRole(
                    AriaRole.BUTTON,
                    new Page.GetByRoleOptions().setName("End local session"))).isEnabled();
            assertEquals(1, context.cookies().stream()
                    .filter(cookie -> cookie.name.equals("PGQMGMTSESSION"))
                    .count());

            page.getByRole(
                    AriaRole.BUTTON,
                    new Page.GetByRoleOptions().setName("End local session")).click();
            assertThat(page.getByRole(
                    AriaRole.HEADING,
                    new Page.GetByRoleOptions().setName("Connect to management console"))).isVisible();
            assertFalse(context.cookies().stream()
                    .anyMatch(cookie -> cookie.name.equals("PGQMGMTSESSION")));
            assertEquals(List.of(), browserErrors);
        }
    }

    private boolean failLogoutOnce(io.vertx.core.http.HttpServerRequest request) {
        if (!request.method().name().equals("DELETE")
                || !request.path().equals("/api/v1/session/local")
                || !failNextLogout.compareAndSet(true, false)) {
            return false;
        }
        request.response()
                .setStatusCode(503)
                .putHeader("content-type", "application/problem+json; charset=utf-8")
                .putHeader("cache-control", "no-store")
                .end("""
                        {"type":"https://peegeeq.dev/problems/logout-test-failure",
                         "title":"Logout unavailable","status":503,"code":"LOGOUT_TEST_FAILURE",
                         "detail":"The local session could not be terminated","instance":"/api/v1/session/local",
                         "correlationId":"logout-test-correlation","fieldErrors":[]}
                        """);
        return true;
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
