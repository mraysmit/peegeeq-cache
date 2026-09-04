package dev.mars.peegeeq.cache.rest.server;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.AriaRole;
import com.microsoft.playwright.options.Cookie;
import com.microsoft.playwright.options.SameSiteAttribute;
import dev.mars.peegeeq.cache.rest.security.BrowserOriginPolicy;
import dev.mars.peegeeq.cache.rest.security.BrowserRequestSecurity;
import dev.mars.peegeeq.cache.rest.security.LocalTokenAuthenticationConfig;
import dev.mars.peegeeq.cache.rest.security.LocalTokenBootstrap;
import dev.mars.peegeeq.cache.rest.security.LocalTokenSessionManager;
import io.vertx.core.Vertx;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.api.TestInstance;

import java.net.ServerSocket;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Wave P1 real-browser scenarios for local-token authentication and session security. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ManagementAuthenticationBrowserIT {

    private Vertx vertx;
    private ManagementHttpServer server;
    private LocalTokenSessionManager sessions;
    private Browser browser;
    private ManagementBrowserScenario currentScenario;
    private String bootstrapToken;
    private int port;

    @BeforeAll
    void startWorker() throws Exception {
        port = freePort();
        vertx = Vertx.vertx();
        LocalTokenBootstrap initial = LocalTokenSessionManager.start(LocalTokenAuthenticationConfig.defaults());
        sessions = initial.manager();
        server = new ManagementHttpServer(vertx, TestManagementConfigurations.local(port),
                ManagementServerResources.noop(), new LocalSessionRoutes(sessions,
                new BrowserRequestSecurity(BrowserOriginPolicy.localToken(origin())), 16 * 1024));
        server.start().toCompletionStage().toCompletableFuture().get(30, TimeUnit.SECONDS);
        browser = ManagementBrowserPlaywrightSuite.browser();
    }

    @BeforeEach
    void resetSession(TestInfo testInfo) {
        bootstrapToken = sessions.regenerateBootstrapToken().token();
        ManagementBrowserEvidenceListener.registerSensitiveCanary(bootstrapToken);
        currentScenario = testInfo.getTestMethod().orElseThrow()
                .getAnnotation(ManagementBrowserScenario.class);
        assertNotNull(currentScenario);
    }

    @AfterAll
    void stopWorker() throws Exception {
        if (server != null) server.stop().toCompletionStage().toCompletableFuture().get(30, TimeUnit.SECONDS);
        if (sessions != null) sessions.close();
        if (vertx != null) vertx.close().toCompletionStage().toCompletableFuture().get(30, TimeUnit.SECONDS);
    }

    @ManagementBrowserScenario(id = "PW-AUTH-007", requirement = "UI design: unauthenticated local access has one explicit connection gate", area = ManagementBrowserArea.AUTHENTICATION, risk = ManagementBrowserRisk.CRITICAL, action = "Open the packaged console without a session", expectedResult = "The local-token connection gate is visible and the management shell is absent", cleanup = "Close the isolated unauthenticated context", operations = {"getSession"}, evidence = {ManagementBrowserEvidence.VISIBLE_RESULT, ManagementBrowserEvidence.HTTP_OPERATION, ManagementBrowserEvidence.RESOURCE_CLEANUP})
    @Test
    void unauthenticatedLocalAccessShowsOnlyTheConnectionGate() {
        scenario(test -> {
            Page page = test.openLogin("/ui/");
            assertThat(page.getByRole(AriaRole.NAVIGATION)).hasCount(0);
            assertThat(page.getByText("Enter the one-time bootstrap token printed by the local management server.")).isVisible();
        });
    }

    @ManagementBrowserScenario(id = "PW-AUTH-008", requirement = "UI design: bootstrap secrets use a password control", area = ManagementBrowserArea.AUTHENTICATION, risk = ManagementBrowserRisk.CRITICAL, action = "Inspect the bootstrap-token control before entering a secret", expectedResult = "The browser masks the token with a password input", cleanup = "Close the isolated unauthenticated context", operations = {"getSession"}, evidence = {ManagementBrowserEvidence.VISIBLE_RESULT, ManagementBrowserEvidence.HTTP_OPERATION, ManagementBrowserEvidence.SENSITIVE_STATE, ManagementBrowserEvidence.RESOURCE_CLEANUP})
    @Test
    void bootstrapTokenControlUsesPasswordMasking() {
        scenario(test -> assertEquals("password", test.openLogin("/ui/")
                .getByLabel("Bootstrap token").getAttribute("type")));
    }

    @ManagementBrowserScenario(id = "PW-AUTH-009", requirement = "UI design: bootstrap secrets are excluded from browser autofill", area = ManagementBrowserArea.AUTHENTICATION, risk = ManagementBrowserRisk.HIGH, action = "Inspect the bootstrap-token autocomplete policy", expectedResult = "The token control disables autocomplete", cleanup = "Close the isolated unauthenticated context", operations = {"getSession"}, evidence = {ManagementBrowserEvidence.VISIBLE_RESULT, ManagementBrowserEvidence.HTTP_OPERATION, ManagementBrowserEvidence.SENSITIVE_STATE, ManagementBrowserEvidence.RESOURCE_CLEANUP})
    @Test
    void bootstrapTokenControlDisablesAutocomplete() {
        scenario(test -> assertEquals("off", test.openLogin("/ui/")
                .getByLabel("Bootstrap token").getAttribute("autocomplete")));
    }

    @ManagementBrowserScenario(id = "PW-AUTH-010", requirement = "UI design: empty bootstrap submission is prevented client-side", area = ManagementBrowserArea.AUTHENTICATION, risk = ManagementBrowserRisk.HIGH, action = "Leave the token empty and attempt to connect", expectedResult = "Connect remains disabled and no token exchange is sent", cleanup = "Close the isolated unauthenticated context", operations = {"getSession"}, evidence = {ManagementBrowserEvidence.VISIBLE_RESULT, ManagementBrowserEvidence.HTTP_OPERATION, ManagementBrowserEvidence.RESOURCE_CLEANUP})
    @Test
    void emptyBootstrapTokenKeepsConnectDisabled() {
        scenario(test -> assertThat(test.openLogin("/ui/")
                .getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Connect"))).isDisabled());
    }

    @ManagementBrowserScenario(id = "PW-AUTH-011", requirement = "UI design: entering a token makes the explicit exchange action available", area = ManagementBrowserArea.AUTHENTICATION, risk = ManagementBrowserRisk.MEDIUM, action = "Type a non-empty bootstrap token", expectedResult = "Connect becomes enabled while the value remains masked", cleanup = "Clear the secret by closing the isolated context", operations = {"getSession"}, evidence = {ManagementBrowserEvidence.VISIBLE_RESULT, ManagementBrowserEvidence.HTTP_OPERATION, ManagementBrowserEvidence.SENSITIVE_STATE, ManagementBrowserEvidence.RESOURCE_CLEANUP})
    @Test
    void enteringBootstrapTokenEnablesConnect() {
        scenario(test -> {
            Page page = test.openLogin("/ui/");
            page.getByLabel("Bootstrap token").fill(bootstrapToken);
            assertThat(page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Connect"))).isEnabled();
            assertEquals("password", page.getByLabel("Bootstrap token").getAttribute("type"));
        });
    }

    @ManagementBrowserScenario(id = "PW-AUTH-012", requirement = "Management API: invalid bootstrap tokens fail with stable diagnostics", area = ManagementBrowserArea.AUTHENTICATION, risk = ManagementBrowserRisk.CRITICAL, action = "Submit an invalid token through the login form", expectedResult = "The visible diagnostic reports INVALID_BOOTSTRAP_TOKEN", cleanup = "Clear the submitted canary and close the context", operations = {"getSession", "exchangeLocalToken"}, evidence = {ManagementBrowserEvidence.VISIBLE_RESULT, ManagementBrowserEvidence.HTTP_OPERATION, ManagementBrowserEvidence.SENSITIVE_STATE, ManagementBrowserEvidence.RESOURCE_CLEANUP})
    @Test
    void invalidBootstrapTokenShowsStableDiagnosticCode() {
        scenario(test -> {
            Page page = test.submit("invalid-token");
            assertThat(page.getByRole(AriaRole.ALERT)).containsText("INVALID_BOOTSTRAP_TOKEN");
            assertThat(page.getByRole(AriaRole.ALERT)).containsText("Invalid bootstrap token");
        });
    }

    @ManagementBrowserScenario(id = "PW-AUTH-013", requirement = "UI design: rejected bootstrap secrets are cleared immediately", area = ManagementBrowserArea.AUTHENTICATION, risk = ManagementBrowserRisk.CRITICAL, action = "Submit a rejected bootstrap secret", expectedResult = "The password input is empty after the failed exchange", cleanup = "Close the context after confirming the canary is cleared", operations = {"getSession", "exchangeLocalToken"}, evidence = {ManagementBrowserEvidence.VISIBLE_RESULT, ManagementBrowserEvidence.HTTP_OPERATION, ManagementBrowserEvidence.SENSITIVE_STATE, ManagementBrowserEvidence.RESOURCE_CLEANUP})
    @Test
    void rejectedBootstrapTokenIsClearedFromTheInput() {
        scenario(test -> {
            Page page = test.submit("rejected-secret");
            assertThat(page.getByRole(AriaRole.ALERT)).containsText("INVALID_BOOTSTRAP_TOKEN");
            assertThat(page.getByLabel("Bootstrap token")).hasValue("");
        });
    }

    @ManagementBrowserScenario(id = "PW-AUTH-014", requirement = "UI design: rejected secrets never become cookies", area = ManagementBrowserArea.AUTHENTICATION, risk = ManagementBrowserRisk.CRITICAL, action = "Submit a rejected secret and inspect browser cookies", expectedResult = "No management session cookie is created", cleanup = "Close the cookie-isolated context", operations = {"getSession", "exchangeLocalToken"}, evidence = {ManagementBrowserEvidence.VISIBLE_RESULT, ManagementBrowserEvidence.HTTP_OPERATION, ManagementBrowserEvidence.SENSITIVE_STATE, ManagementBrowserEvidence.RESOURCE_CLEANUP})
    @Test
    void rejectedBootstrapTokenCreatesNoSessionCookie() {
        scenario(test -> {
            assertThat(test.submit("cookie-canary").getByRole(AriaRole.ALERT)).isVisible();
            assertFalse(hasSessionCookie(test.context));
        });
    }

    @ManagementBrowserScenario(id = "PW-AUTH-015", requirement = "UI design: rejected secrets leave no DOM copy", area = ManagementBrowserArea.AUTHENTICATION, risk = ManagementBrowserRisk.CRITICAL, action = "Submit a unique rejected secret and inspect the rendered document", expectedResult = "The secret is absent from DOM text and markup", cleanup = "Close the sensitive context", operations = {"getSession", "exchangeLocalToken"}, evidence = {ManagementBrowserEvidence.VISIBLE_RESULT, ManagementBrowserEvidence.HTTP_OPERATION, ManagementBrowserEvidence.SENSITIVE_STATE, ManagementBrowserEvidence.RESOURCE_CLEANUP})
    @Test
    void rejectedBootstrapTokenLeavesNoDomCopy() {
        scenario(test -> {
            String canary = "dom-rejection-canary";
            Page page = test.submit(canary);
            assertThat(page.getByRole(AriaRole.ALERT)).isVisible();
            assertFalse(page.content().contains(canary));
        });
    }

    @ManagementBrowserScenario(id = "PW-AUTH-016", requirement = "UI design: rejected secrets leave no URL, history-state, or storage copy", area = ManagementBrowserArea.AUTHENTICATION, risk = ManagementBrowserRisk.CRITICAL, action = "Submit a unique rejected secret and inspect browser persistence surfaces", expectedResult = "The secret is absent from URL, history state, local storage, and session storage", cleanup = "Close the sensitive context", operations = {"getSession", "exchangeLocalToken"}, evidence = {ManagementBrowserEvidence.VISIBLE_RESULT, ManagementBrowserEvidence.HTTP_OPERATION, ManagementBrowserEvidence.SENSITIVE_STATE, ManagementBrowserEvidence.RESOURCE_CLEANUP})
    @Test
    void rejectedBootstrapTokenLeavesNoNavigationOrStorageCopy() {
        scenario(test -> {
            String canary = "history-storage-canary";
            Page page = test.submit(canary);
            assertThat(page.getByRole(AriaRole.ALERT)).isVisible();
            assertFalse(page.url().contains(canary));
            assertFalse(String.valueOf(page.evaluate("JSON.stringify(history.state)")).contains(canary));
            assertEquals(0, ((Number) page.evaluate("localStorage.length")).intValue());
            assertEquals(0, ((Number) page.evaluate("sessionStorage.length")).intValue());
        });
    }

    @ManagementBrowserScenario(id = "PW-AUTH-017", requirement = "Management API: bootstrap tokens are exact opaque values", area = ManagementBrowserArea.AUTHENTICATION, risk = ManagementBrowserRisk.CRITICAL, action = "Submit the valid token with leading and trailing whitespace", expectedResult = "The modified token is rejected and the original token remains unconsumed", cleanup = "Clear the modified secret and close the context", operations = {"getSession", "exchangeLocalToken"}, evidence = {ManagementBrowserEvidence.VISIBLE_RESULT, ManagementBrowserEvidence.HTTP_OPERATION, ManagementBrowserEvidence.SENSITIVE_STATE, ManagementBrowserEvidence.RESOURCE_CLEANUP})
    @Test
    void whitespaceModifiedBootstrapTokenIsRejected() {
        scenario(test -> assertThat(test.submit(" " + bootstrapToken + " ")
                .getByRole(AriaRole.ALERT)).containsText("INVALID_BOOTSTRAP_TOKEN"));
    }

    @ManagementBrowserScenario(id = "PW-AUTH-018", requirement = "Management API: bootstrap tokens are case-sensitive opaque values", area = ManagementBrowserArea.AUTHENTICATION, risk = ManagementBrowserRisk.CRITICAL, action = "Submit a case-mutated bootstrap token", expectedResult = "The mutated value is rejected visibly", cleanup = "Clear the mutated secret and close the context", operations = {"getSession", "exchangeLocalToken"}, evidence = {ManagementBrowserEvidence.VISIBLE_RESULT, ManagementBrowserEvidence.HTTP_OPERATION, ManagementBrowserEvidence.SENSITIVE_STATE, ManagementBrowserEvidence.RESOURCE_CLEANUP})
    @Test
    void caseMutatedBootstrapTokenIsRejected() {
        scenario(test -> assertThat(test.submit(caseMutatedBootstrapToken())
                .getByRole(AriaRole.ALERT)).containsText("INVALID_BOOTSTRAP_TOKEN"));
    }

    @ManagementBrowserScenario(id = "PW-AUTH-019", requirement = "UI design: keyboard submission performs the one-time bootstrap exchange", area = ManagementBrowserArea.AUTHENTICATION, risk = ManagementBrowserRisk.HIGH, action = "Enter the valid token and press Enter", expectedResult = "The authenticated Overview appears for local-operator", cleanup = "Close the authenticated context and invalidate its session", operations = {"getSession", "exchangeLocalToken"}, evidence = {ManagementBrowserEvidence.VISIBLE_RESULT, ManagementBrowserEvidence.HTTP_OPERATION, ManagementBrowserEvidence.SENSITIVE_STATE, ManagementBrowserEvidence.RESOURCE_CLEANUP})
    @Test
    void enterKeySubmitsTheValidBootstrapToken() {
        scenario(test -> {
            Page page = test.openLogin("/ui/");
            page.getByLabel("Bootstrap token").fill(bootstrapToken);
            page.getByLabel("Bootstrap token").press("Enter");
            heading(page, "Overview");
        });
    }

    @ManagementBrowserScenario(id = "PW-AUTH-020", requirement = "UI design: explicit Connect performs the one-time bootstrap exchange", area = ManagementBrowserArea.AUTHENTICATION, risk = ManagementBrowserRisk.CRITICAL, action = "Enter the valid token and activate Connect", expectedResult = "The authenticated Overview is displayed", cleanup = "Close the authenticated context and invalidate its session", operations = {"getSession", "exchangeLocalToken"}, evidence = {ManagementBrowserEvidence.VISIBLE_RESULT, ManagementBrowserEvidence.HTTP_OPERATION, ManagementBrowserEvidence.SENSITIVE_STATE, ManagementBrowserEvidence.RESOURCE_CLEANUP})
    @Test
    void connectButtonAuthenticatesTheValidBootstrapToken() {
        scenario(test -> heading(test.authenticate("/ui/"), "Overview"));
    }

    @ManagementBrowserScenario(id = "PW-AUTH-021", requirement = "UI design: local sessions expose the canonical local actor", area = ManagementBrowserArea.AUTHENTICATION, risk = ManagementBrowserRisk.HIGH, action = "Authenticate and inspect session identity in the shell", expectedResult = "The shell identifies local-operator exactly", cleanup = "Close the authenticated context", operations = {"getSession", "exchangeLocalToken"}, evidence = {ManagementBrowserEvidence.VISIBLE_RESULT, ManagementBrowserEvidence.HTTP_OPERATION, ManagementBrowserEvidence.SENSITIVE_STATE, ManagementBrowserEvidence.RESOURCE_CLEANUP})
    @Test
    void authenticatedShellShowsCanonicalLocalActor() {
        scenario(test -> assertThat(test.authenticate("/ui/")
                .getByText("local-operator", new Page.GetByTextOptions().setExact(true))).isVisible());
    }

    @ManagementBrowserScenario(id = "PW-AUTH-022", requirement = "UI design: local sessions expose effective operator authority", area = ManagementBrowserArea.AUTHENTICATION, risk = ManagementBrowserRisk.HIGH, action = "Authenticate and inspect the role badge", expectedResult = "The shell visibly identifies the session as Operator", cleanup = "Close the authenticated context", operations = {"getSession", "exchangeLocalToken"}, evidence = {ManagementBrowserEvidence.VISIBLE_RESULT, ManagementBrowserEvidence.HTTP_OPERATION, ManagementBrowserEvidence.SENSITIVE_STATE, ManagementBrowserEvidence.RESOURCE_CLEANUP})
    @Test
    void authenticatedShellShowsOperatorRole() {
        scenario(test -> assertThat(test.authenticate("/ui/")
                .getByText("Operator", new Page.GetByTextOptions().setExact(true))).isVisible());
    }

    @ManagementBrowserScenario(id = "PW-AUTH-023", requirement = "UI design: Settings reports local-token authentication mode", area = ManagementBrowserArea.AUTHENTICATION, risk = ManagementBrowserRisk.HIGH, action = "Authenticate directly into Settings", expectedResult = "Connection details report LOCAL_TOKEN", cleanup = "Close the authenticated settings context", operations = {"getSession", "exchangeLocalToken"}, evidence = {ManagementBrowserEvidence.VISIBLE_RESULT, ManagementBrowserEvidence.HTTP_OPERATION, ManagementBrowserEvidence.SENSITIVE_STATE, ManagementBrowserEvidence.RESOURCE_CLEANUP})
    @Test
    void settingsReportsLocalTokenAuthenticationMode() {
        scenario(test -> {
            Page page = test.authenticate("/ui/settings");
            heading(page, "Settings");
            assertThat(page.getByText("LOCAL_TOKEN", new Page.GetByTextOptions().setExact(true))).isVisible();
        });
    }

    @ManagementBrowserScenario(id = "PW-AUTH-024", requirement = "UI design: Settings reports both effective local roles", area = ManagementBrowserArea.AUTHENTICATION, risk = ManagementBrowserRisk.HIGH, action = "Authenticate and inspect role details in Settings", expectedResult = "Connection details report operator and viewer roles", cleanup = "Close the authenticated settings context", operations = {"getSession", "exchangeLocalToken"}, evidence = {ManagementBrowserEvidence.VISIBLE_RESULT, ManagementBrowserEvidence.HTTP_OPERATION, ManagementBrowserEvidence.SENSITIVE_STATE, ManagementBrowserEvidence.RESOURCE_CLEANUP})
    @Test
    void settingsReportsBothEffectiveLocalRoles() {
        scenario(test -> assertThat(test.authenticate("/ui/settings")
                .getByText("operator, viewer", new Page.GetByTextOptions().setExact(true))).isVisible());
    }

    @ManagementBrowserScenario(id = "PW-AUTH-025", requirement = "UI design: authenticated connection state is explicit", area = ManagementBrowserArea.AUTHENTICATION, risk = ManagementBrowserRisk.MEDIUM, action = "Authenticate and inspect session status", expectedResult = "The shell visibly reports Connected", cleanup = "Close the authenticated context", operations = {"getSession", "exchangeLocalToken"}, evidence = {ManagementBrowserEvidence.VISIBLE_RESULT, ManagementBrowserEvidence.HTTP_OPERATION, ManagementBrowserEvidence.SENSITIVE_STATE, ManagementBrowserEvidence.RESOURCE_CLEANUP})
    @Test
    void authenticatedShellReportsConnectedState() {
        scenario(test -> assertThat(test.authenticate("/ui/")
                .getByText("Connected", new Page.GetByTextOptions().setExact(true))).isVisible());
    }

    @ManagementBrowserScenario(id = "PW-AUTH-026", requirement = "Management API: local sessions use an HttpOnly cookie", area = ManagementBrowserArea.AUTHENTICATION, risk = ManagementBrowserRisk.CRITICAL, action = "Authenticate and inspect the browser-managed session cookie", expectedResult = "PGQMGMTSESSION is HttpOnly and unavailable to document script", cleanup = "Close the cookie-isolated context", operations = {"getSession", "exchangeLocalToken"}, evidence = {ManagementBrowserEvidence.VISIBLE_RESULT, ManagementBrowserEvidence.HTTP_OPERATION, ManagementBrowserEvidence.SENSITIVE_STATE, ManagementBrowserEvidence.RESOURCE_CLEANUP})
    @Test
    void localSessionCookieIsHttpOnly() {
        scenario(test -> {
            Page page = test.authenticate("/ui/");
            assertTrue(sessionCookie(test.context).httpOnly);
            assertFalse(String.valueOf(page.evaluate("document.cookie")).contains("PGQMGMTSESSION"));
        });
    }

    @ManagementBrowserScenario(id = "PW-AUTH-027", requirement = "Management API: local session cookies use strict same-site isolation", area = ManagementBrowserArea.AUTHENTICATION, risk = ManagementBrowserRisk.CRITICAL, action = "Authenticate and inspect the browser-managed cookie policy", expectedResult = "PGQMGMTSESSION has SameSite Strict", cleanup = "Close the cookie-isolated context", operations = {"getSession", "exchangeLocalToken"}, evidence = {ManagementBrowserEvidence.VISIBLE_RESULT, ManagementBrowserEvidence.HTTP_OPERATION, ManagementBrowserEvidence.SENSITIVE_STATE, ManagementBrowserEvidence.RESOURCE_CLEANUP})
    @Test
    void localSessionCookieUsesStrictSameSitePolicy() {
        scenario(test -> {
            heading(test.authenticate("/ui/"), "Overview");
            assertEquals(SameSiteAttribute.STRICT, sessionCookie(test.context).sameSite);
        });
    }

    @ManagementBrowserScenario(id = "PW-AUTH-028", requirement = "Management API: the session cookie covers packaged UI and API routes", area = ManagementBrowserArea.AUTHENTICATION, risk = ManagementBrowserRisk.HIGH, action = "Authenticate and inspect the browser-managed cookie path", expectedResult = "PGQMGMTSESSION is scoped to the root path", cleanup = "Close the cookie-isolated context", operations = {"getSession", "exchangeLocalToken"}, evidence = {ManagementBrowserEvidence.VISIBLE_RESULT, ManagementBrowserEvidence.HTTP_OPERATION, ManagementBrowserEvidence.SENSITIVE_STATE, ManagementBrowserEvidence.RESOURCE_CLEANUP})
    @Test
    void localSessionCookieUsesRootPath() {
        scenario(test -> {
            heading(test.authenticate("/ui/"), "Overview");
            assertEquals("/", sessionCookie(test.context).path);
        });
    }

    @ManagementBrowserScenario(id = "PW-AUTH-029", requirement = "UI design: the raw session identifier never enters page-controlled surfaces", area = ManagementBrowserArea.AUTHENTICATION, risk = ManagementBrowserRisk.CRITICAL, action = "Authenticate and scan DOM, URL, history, and storage for the cookie value", expectedResult = "Only the browser cookie jar contains the raw session identifier", cleanup = "Close the sensitive cookie context", operations = {"getSession", "exchangeLocalToken"}, evidence = {ManagementBrowserEvidence.VISIBLE_RESULT, ManagementBrowserEvidence.HTTP_OPERATION, ManagementBrowserEvidence.SENSITIVE_STATE, ManagementBrowserEvidence.RESOURCE_CLEANUP})
    @Test
    void rawSessionIdentifierIsAbsentFromPageControlledSurfaces() {
        scenario(test -> {
            Page page = test.authenticate("/ui/");
            String secret = sessionCookie(test.context).value;
            assertFalse(page.content().contains(secret));
            assertFalse(page.url().contains(secret));
            assertFalse(String.valueOf(page.evaluate("JSON.stringify(history.state)")).contains(secret));
            assertFalse(String.valueOf(page.evaluate("JSON.stringify(localStorage)")).contains(secret));
            assertFalse(String.valueOf(page.evaluate("JSON.stringify(sessionStorage)")).contains(secret));
        });
    }

    @ManagementBrowserScenario(id = "PW-AUTH-030", requirement = "UI design: a successful bootstrap removes the token control and secret from DOM", area = ManagementBrowserArea.AUTHENTICATION, risk = ManagementBrowserRisk.CRITICAL, action = "Authenticate with a unique valid token and inspect the resulting shell", expectedResult = "The login form is unmounted and the token is absent from markup", cleanup = "Close the sensitive authenticated context", operations = {"getSession", "exchangeLocalToken"}, evidence = {ManagementBrowserEvidence.VISIBLE_RESULT, ManagementBrowserEvidence.HTTP_OPERATION, ManagementBrowserEvidence.SENSITIVE_STATE, ManagementBrowserEvidence.RESOURCE_CLEANUP})
    @Test
    void successfulBootstrapRemovesTokenInputAndDomCopy() {
        scenario(test -> {
            String secret = bootstrapToken;
            Page page = test.authenticate("/ui/");
            assertThat(page.getByLabel("Bootstrap token")).hasCount(0);
            assertFalse(page.content().contains(secret));
        });
    }

    @ManagementBrowserScenario(id = "PW-AUTH-031", requirement = "UI design: successful bootstrap never places its secret in navigation state", area = ManagementBrowserArea.AUTHENTICATION, risk = ManagementBrowserRisk.CRITICAL, action = "Authenticate and inspect URL and history state", expectedResult = "The bootstrap token is absent from both navigation surfaces", cleanup = "Close the sensitive authenticated context", operations = {"getSession", "exchangeLocalToken"}, evidence = {ManagementBrowserEvidence.VISIBLE_RESULT, ManagementBrowserEvidence.HTTP_OPERATION, ManagementBrowserEvidence.SENSITIVE_STATE, ManagementBrowserEvidence.RESOURCE_CLEANUP})
    @Test
    void successfulBootstrapLeavesNoNavigationSecret() {
        scenario(test -> {
            String secret = bootstrapToken;
            Page page = test.authenticate("/ui/");
            assertFalse(page.url().contains(secret));
            assertFalse(String.valueOf(page.evaluate("JSON.stringify(history.state)")).contains(secret));
        });
    }

    @ManagementBrowserScenario(id = "PW-AUTH-032", requirement = "UI design: successful bootstrap never persists its secret in Web Storage", area = ManagementBrowserArea.AUTHENTICATION, risk = ManagementBrowserRisk.CRITICAL, action = "Authenticate and inspect local and session storage", expectedResult = "Neither storage area contains the bootstrap token", cleanup = "Close the sensitive authenticated context", operations = {"getSession", "exchangeLocalToken"}, evidence = {ManagementBrowserEvidence.VISIBLE_RESULT, ManagementBrowserEvidence.HTTP_OPERATION, ManagementBrowserEvidence.SENSITIVE_STATE, ManagementBrowserEvidence.RESOURCE_CLEANUP})
    @Test
    void successfulBootstrapLeavesNoStorageSecret() {
        scenario(test -> {
            String secret = bootstrapToken;
            Page page = test.authenticate("/ui/");
            assertFalse(String.valueOf(page.evaluate("JSON.stringify(localStorage)")).contains(secret));
            assertFalse(String.valueOf(page.evaluate("JSON.stringify(sessionStorage)")).contains(secret));
        });
    }

    @ManagementBrowserScenario(id = "PW-AUTH-033", requirement = "UI design: CSRF state remains in module memory rather than render or storage surfaces", area = ManagementBrowserArea.AUTHENTICATION, risk = ManagementBrowserRisk.CRITICAL, action = "Authenticate and scan browser-controlled surfaces for the CSRF response field", expectedResult = "The shell, URL, and Web Storage contain no csrfToken field", cleanup = "Close the sensitive authenticated context", operations = {"getSession", "exchangeLocalToken"}, evidence = {ManagementBrowserEvidence.VISIBLE_RESULT, ManagementBrowserEvidence.HTTP_OPERATION, ManagementBrowserEvidence.SENSITIVE_STATE, ManagementBrowserEvidence.RESOURCE_CLEANUP})
    @Test
    void csrfStateIsAbsentFromRenderedAndPersistentSurfaces() {
        scenario(test -> {
            Page page = test.authenticate("/ui/");
            assertFalse(page.content().contains("csrfToken"));
            assertFalse(page.url().contains("csrfToken"));
            assertFalse(String.valueOf(page.evaluate("JSON.stringify(localStorage)")).contains("csrfToken"));
            assertFalse(String.valueOf(page.evaluate("JSON.stringify(sessionStorage)")).contains("csrfToken"));
        });
    }

    @ManagementBrowserScenario(id = "PW-AUTH-034", requirement = "UI design: a valid local session survives a full document reload", area = ManagementBrowserArea.AUTHENTICATION, risk = ManagementBrowserRisk.CRITICAL, action = "Authenticate and reload the packaged Overview", expectedResult = "The server-authenticated Overview returns without showing the token gate", cleanup = "Close the reloaded authenticated context", operations = {"getSession", "exchangeLocalToken"}, evidence = {ManagementBrowserEvidence.VISIBLE_RESULT, ManagementBrowserEvidence.HTTP_OPERATION, ManagementBrowserEvidence.SENSITIVE_STATE, ManagementBrowserEvidence.RESOURCE_CLEANUP})
    @Test
    void validLocalSessionSurvivesFullReload() {
        scenario(test -> {
            Page page = test.authenticate("/ui/");
            assertEquals(200, page.reload().status());
            heading(page, "Overview");
            assertThat(page.getByLabel("Bootstrap token")).hasCount(0);
        });
    }

    @ManagementBrowserScenario(id = "PW-AUTH-035", requirement = "UI design: unauthenticated deep links resume at their requested route after login", area = ManagementBrowserArea.AUTHENTICATION, risk = ManagementBrowserRisk.HIGH, action = "Open Settings without a session and then authenticate", expectedResult = "The authenticated shell preserves the Settings deep link", cleanup = "Close the deep-link context", operations = {"getSession", "exchangeLocalToken"}, evidence = {ManagementBrowserEvidence.VISIBLE_RESULT, ManagementBrowserEvidence.HTTP_OPERATION, ManagementBrowserEvidence.SENSITIVE_STATE, ManagementBrowserEvidence.RESOURCE_CLEANUP})
    @Test
    void unauthenticatedSettingsDeepLinkResumesAfterLogin() {
        scenario(test -> {
            Page page = test.authenticate("/ui/settings");
            heading(page, "Settings");
            assertTrue(page.url().endsWith("/ui/settings"));
        });
    }

    @ManagementBrowserScenario(id = "PW-AUTH-036", requirement = "Management API: local authentication is isolated to the browser context holding its cookie", area = ManagementBrowserArea.AUTHENTICATION, risk = ManagementBrowserRisk.CRITICAL, action = "Authenticate one context and open the console in a second context", expectedResult = "The first context stays authenticated while the second shows the connection gate", cleanup = "Close both isolated contexts", operations = {"getSession", "exchangeLocalToken"}, evidence = {ManagementBrowserEvidence.VISIBLE_RESULT, ManagementBrowserEvidence.HTTP_OPERATION, ManagementBrowserEvidence.SENSITIVE_STATE, ManagementBrowserEvidence.RESOURCE_CLEANUP})
    @Test
    void authenticatedSessionIsIsolatedFromASecondBrowserContext() {
        scenario(test -> {
            heading(test.authenticate("/ui/"), "Overview");
            try (BrowserContext second = browser.newContext()) {
                Page page = test.attach(second.newPage());
                assertEquals(200, page.navigate(origin() + "/ui/").status());
                heading(page, "Connect to management console");
                assertFalse(hasSessionCookie(second));
            }
        });
    }

    @ManagementBrowserScenario(id = "PW-AUTH-037", requirement = "Management API: a consumed bootstrap token cannot create a second session", area = ManagementBrowserArea.AUTHENTICATION, risk = ManagementBrowserRisk.CRITICAL, action = "Authenticate once and replay the same token in another isolated context", expectedResult = "The replay is rejected and the original shell remains authenticated", cleanup = "Close both contexts and clear the original session", operations = {"getSession", "exchangeLocalToken"}, evidence = {ManagementBrowserEvidence.VISIBLE_RESULT, ManagementBrowserEvidence.HTTP_OPERATION, ManagementBrowserEvidence.SENSITIVE_STATE, ManagementBrowserEvidence.RESOURCE_CLEANUP})
    @Test
    void consumedBootstrapTokenCannotAuthenticateAnotherContext() {
        scenario(test -> {
            String consumed = bootstrapToken;
            Page original = test.authenticate("/ui/");
            try (BrowserContext second = browser.newContext()) {
                Page replay = test.attach(second.newPage());
                assertEquals(200, replay.navigate(origin() + "/ui/").status());
                heading(replay, "Connect to management console");
                replay.getByLabel("Bootstrap token").fill(consumed);
                replay.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Connect")).click();
                assertThat(replay.getByRole(AriaRole.ALERT)).containsText("INVALID_BOOTSTRAP_TOKEN");
            }
            heading(original, "Overview");
        });
    }

    @ManagementBrowserScenario(id = "PW-AUTH-038", requirement = "Management API: local bootstrap requires the configured canonical browser origin", area = ManagementBrowserArea.AUTHENTICATION, risk = ManagementBrowserRisk.CRITICAL, action = "Load the UI through a different loopback host name and submit the valid token", expectedResult = "Origin enforcement rejects the exchange and preserves the login gate", cleanup = "Clear the token input and close the alternate-origin context", operations = {"getSession", "exchangeLocalToken"}, evidence = {ManagementBrowserEvidence.VISIBLE_RESULT, ManagementBrowserEvidence.HTTP_OPERATION, ManagementBrowserEvidence.SENSITIVE_STATE, ManagementBrowserEvidence.RESOURCE_CLEANUP})
    @Test
    void alternateLoopbackOriginCannotExchangeBootstrapToken() {
        scenario(test -> {
            Page page = test.page;
            assertEquals(200, page.navigate("http://localhost:" + port + "/ui/").status());
            heading(page, "Connect to management console");
            page.getByLabel("Bootstrap token").fill(bootstrapToken);
            page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Connect")).click();
            assertThat(page.getByRole(AriaRole.ALERT)).containsText("CSRF_VALIDATION_FAILED");
            assertEquals("", page.getByLabel("Bootstrap token").inputValue());
        });
    }

    @ManagementBrowserScenario(id = "PW-AUTH-039", requirement = "UI design: successful local logout returns to the connection gate", area = ManagementBrowserArea.AUTHENTICATION, risk = ManagementBrowserRisk.CRITICAL, action = "Authenticate and activate End local session", expectedResult = "The authenticated shell is replaced by the login gate", cleanup = "Verify logout and close the context", operations = {"getSession", "exchangeLocalToken", "deleteLocalSession"}, evidence = {ManagementBrowserEvidence.VISIBLE_RESULT, ManagementBrowserEvidence.HTTP_OPERATION, ManagementBrowserEvidence.SENSITIVE_STATE, ManagementBrowserEvidence.RESOURCE_CLEANUP})
    @Test
    void successfulLogoutReturnsToConnectionGate() {
        scenario(test -> {
            Page page = test.authenticate("/ui/");
            page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("End local session")).click();
            heading(page, "Connect to management console");
        });
    }

    @ManagementBrowserScenario(id = "PW-AUTH-040", requirement = "Management API: successful local logout expires the session cookie", area = ManagementBrowserArea.AUTHENTICATION, risk = ManagementBrowserRisk.CRITICAL, action = "Authenticate, log out, and inspect browser cookies", expectedResult = "PGQMGMTSESSION is absent after logout", cleanup = "Close the cookie-isolated context", operations = {"getSession", "exchangeLocalToken", "deleteLocalSession"}, evidence = {ManagementBrowserEvidence.VISIBLE_RESULT, ManagementBrowserEvidence.HTTP_OPERATION, ManagementBrowserEvidence.SENSITIVE_STATE, ManagementBrowserEvidence.RESOURCE_CLEANUP})
    @Test
    void successfulLogoutClearsSessionCookie() {
        scenario(test -> {
            Page page = test.authenticate("/ui/");
            assertTrue(hasSessionCookie(test.context));
            page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("End local session")).click();
            heading(page, "Connect to management console");
            assertFalse(hasSessionCookie(test.context));
        });
    }

    @ManagementBrowserScenario(id = "PW-AUTH-041", requirement = "UI design: reload cannot recover a logged-out local session", area = ManagementBrowserArea.AUTHENTICATION, risk = ManagementBrowserRisk.CRITICAL, action = "Authenticate, log out, and reload the document", expectedResult = "The connection gate remains authoritative after reload", cleanup = "Close the reloaded logged-out context", operations = {"getSession", "exchangeLocalToken", "deleteLocalSession"}, evidence = {ManagementBrowserEvidence.VISIBLE_RESULT, ManagementBrowserEvidence.HTTP_OPERATION, ManagementBrowserEvidence.SENSITIVE_STATE, ManagementBrowserEvidence.RESOURCE_CLEANUP})
    @Test
    void reloadCannotRecoverLoggedOutSession() {
        scenario(test -> {
            Page page = test.authenticate("/ui/");
            page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("End local session")).click();
            heading(page, "Connect to management console");
            assertEquals(200, page.reload().status());
            heading(page, "Connect to management console");
        });
    }

    @ManagementBrowserScenario(id = "PW-AUTH-042", requirement = "Management API: logout does not make the consumed bootstrap token reusable", area = ManagementBrowserArea.AUTHENTICATION, risk = ManagementBrowserRisk.CRITICAL, action = "Authenticate, log out, and replay the original bootstrap token", expectedResult = "Replay is rejected with INVALID_BOOTSTRAP_TOKEN", cleanup = "Clear the replayed token and close the context", operations = {"getSession", "exchangeLocalToken", "deleteLocalSession"}, evidence = {ManagementBrowserEvidence.VISIBLE_RESULT, ManagementBrowserEvidence.HTTP_OPERATION, ManagementBrowserEvidence.SENSITIVE_STATE, ManagementBrowserEvidence.RESOURCE_CLEANUP})
    @Test
    void logoutDoesNotMakeConsumedBootstrapTokenReusable() {
        scenario(test -> {
            String consumed = bootstrapToken;
            Page page = test.authenticate("/ui/");
            page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("End local session")).click();
            heading(page, "Connect to management console");
            page.getByLabel("Bootstrap token").fill(consumed);
            page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Connect")).click();
            assertThat(page.getByRole(AriaRole.ALERT)).containsText("INVALID_BOOTSTRAP_TOKEN");
        });
    }

    @ManagementBrowserScenario(id = "PW-AUTH-043", requirement = "UI design: logout is authoritative from an authenticated deep link", area = ManagementBrowserArea.AUTHENTICATION, risk = ManagementBrowserRisk.HIGH, action = "Authenticate on Settings and end the local session", expectedResult = "Settings is removed and the connection gate appears", cleanup = "Close the logged-out deep-link context", operations = {"getSession", "exchangeLocalToken", "deleteLocalSession"}, evidence = {ManagementBrowserEvidence.VISIBLE_RESULT, ManagementBrowserEvidence.HTTP_OPERATION, ManagementBrowserEvidence.SENSITIVE_STATE, ManagementBrowserEvidence.RESOURCE_CLEANUP})
    @Test
    void logoutFromSettingsRemovesAuthenticatedDeepLink() {
        scenario(test -> {
            Page page = test.authenticate("/ui/settings");
            heading(page, "Settings");
            page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("End local session")).click();
            heading(page, "Connect to management console");
            assertThat(page.getByRole(AriaRole.HEADING, new Page.GetByRoleOptions().setName("Settings"))).hasCount(0);
        });
    }

    @ManagementBrowserScenario(id = "PW-AUTH-044", requirement = "UI design: logout clears persisted setup scope without deleting harmless preferences", area = ManagementBrowserArea.AUTHENTICATION, risk = ManagementBrowserRisk.CRITICAL, action = "Seed setup scope in session storage, authenticate, and log out", expectedResult = "The allowlisted setup scope is removed during session cleanup", cleanup = "Close the storage-isolated context", operations = {"getSession", "exchangeLocalToken", "deleteLocalSession"}, evidence = {ManagementBrowserEvidence.VISIBLE_RESULT, ManagementBrowserEvidence.HTTP_OPERATION, ManagementBrowserEvidence.SENSITIVE_STATE, ManagementBrowserEvidence.RESOURCE_CLEANUP})
    @Test
    void logoutClearsPersistedSetupScope() {
        scenario(test -> {
            Page page = test.authenticate("/ui/");
            page.evaluate("sessionStorage.setItem('peegeeq-cache.scope.v1', JSON.stringify({setupId:'local-test'}))");
            assertEquals(1, ((Number) page.evaluate("sessionStorage.length")).intValue());
            page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("End local session")).click();
            heading(page, "Connect to management console");
            assertEquals(0, ((Number) page.evaluate("sessionStorage.length")).intValue());
        });
    }

    @ManagementBrowserScenario(id = "PW-AUTH-045", requirement = "Management API: logout requires the in-memory CSRF token", area = ManagementBrowserArea.AUTHENTICATION, risk = ManagementBrowserRisk.CRITICAL, action = "Authenticate and issue a browser logout request without the CSRF header", expectedResult = "The server rejects the request while the authenticated shell and cookie remain", cleanup = "Use context closure to invalidate the retained session", operations = {"getSession", "exchangeLocalToken", "deleteLocalSession"}, evidence = {ManagementBrowserEvidence.VISIBLE_RESULT, ManagementBrowserEvidence.HTTP_OPERATION, ManagementBrowserEvidence.SENSITIVE_STATE, ManagementBrowserEvidence.RESOURCE_CLEANUP})
    @Test
    void logoutWithoutCsrfIsRejectedAndShellRemainsAuthenticated() {
        scenario(test -> {
            Page page = test.authenticate("/ui/");
            @SuppressWarnings("unchecked")
            Map<String, Object> result = (Map<String, Object>) page.evaluate("""
                    async () => {
                      const response = await fetch('/api/v1/session/local', {method:'DELETE', credentials:'include'});
                      const body = await response.json();
                      return {status: response.status, code: body.code};
                    }
                    """);
            assertEquals(403, ((Number) result.get("status")).intValue());
            assertEquals("CSRF_VALIDATION_FAILED", result.get("code"));
            heading(page, "Overview");
            assertTrue(hasSessionCookie(test.context));
        });
    }

    @ManagementBrowserScenario(id = "PW-AUTH-046", requirement = "Management API: logout rejects an incorrect CSRF token", area = ManagementBrowserArea.AUTHENTICATION, risk = ManagementBrowserRisk.CRITICAL, action = "Authenticate and issue a browser logout request with a forged CSRF header", expectedResult = "The server rejects the forged request while preserving visible authenticated state", cleanup = "Use context closure to invalidate the retained session", operations = {"getSession", "exchangeLocalToken", "deleteLocalSession"}, evidence = {ManagementBrowserEvidence.VISIBLE_RESULT, ManagementBrowserEvidence.HTTP_OPERATION, ManagementBrowserEvidence.SENSITIVE_STATE, ManagementBrowserEvidence.RESOURCE_CLEANUP})
    @Test
    void logoutWithForgedCsrfIsRejectedAndShellRemainsAuthenticated() {
        scenario(test -> {
            Page page = test.authenticate("/ui/");
            @SuppressWarnings("unchecked")
            Map<String, Object> result = (Map<String, Object>) page.evaluate("""
                    async () => {
                      const response = await fetch('/api/v1/session/local', {
                        method:'DELETE', credentials:'include', headers:{'X-PeeGeeQ-CSRF':'forged-csrf-token'}
                      });
                      const body = await response.json();
                      return {status: response.status, code: body.code};
                    }
                    """);
            assertEquals(403, ((Number) result.get("status")).intValue());
            assertEquals("CSRF_VALIDATION_FAILED", result.get("code"));
            heading(page, "Overview");
            assertTrue(hasSessionCookie(test.context));
        });
    }

    private void scenario(BrowserAction action) {
        List<String> pageErrors = new ArrayList<>();
        ManagementBrowserOperationTrace trace = new ManagementBrowserOperationTrace();
        try (BrowserContext context = browser.newContext()) {
            Page page = context.newPage();
            page.onPageError(pageErrors::add);
            trace.attach(page);
            action.run(new TestBrowser(context, page, trace));
            trace.assertObserved(currentScenario.operations());
            assertEquals(List.of(), pageErrors);
        }
    }

    private final class TestBrowser {
        private final BrowserContext context;
        private final Page page;
        private final ManagementBrowserOperationTrace trace;

        private TestBrowser(BrowserContext context, Page page, ManagementBrowserOperationTrace trace) {
            this.context = context;
            this.page = page;
            this.trace = trace;
        }

        private Page openLogin(String path) {
            assertEquals(200, page.navigate(origin() + path).status());
            heading(page, "Connect to management console");
            return page;
        }

        private Page submit(String token) {
            ManagementBrowserEvidenceListener.registerSensitiveCanary(token);
            openLogin("/ui/");
            page.getByLabel("Bootstrap token").fill(token);
            page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Connect")).click();
            return page;
        }

        private Page authenticate(String path) {
            ManagementBrowserEvidenceListener.registerSensitiveCanary(bootstrapToken);
            openLogin(path);
            page.getByLabel("Bootstrap token").fill(bootstrapToken);
            page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Connect")).click();
            assertThat(page.getByLabel("Session and connection status")).isVisible();
            return page;
        }

        private Page attach(Page additionalPage) {
            trace.attach(additionalPage);
            return additionalPage;
        }
    }

    private static void heading(Page page, String name) {
        assertThat(page.getByRole(AriaRole.HEADING,
                new Page.GetByRoleOptions().setName(name).setExact(true))).isVisible();
    }

    private static boolean hasSessionCookie(BrowserContext context) {
        return context.cookies().stream().anyMatch(cookie -> cookie.name.equals("PGQMGMTSESSION"));
    }

    private static Cookie sessionCookie(BrowserContext context) {
        return context.cookies().stream().filter(cookie -> cookie.name.equals("PGQMGMTSESSION"))
                .findFirst().orElseThrow();
    }

    private String caseMutatedBootstrapToken() {
        while (bootstrapToken.chars().noneMatch(Character::isLetter)) {
            bootstrapToken = sessions.regenerateBootstrapToken().token();
        }
        int index = 0;
        while (!Character.isLetter(bootstrapToken.charAt(index))) index++;
        char original = bootstrapToken.charAt(index);
        char replacement = Character.isUpperCase(original)
                ? Character.toLowerCase(original)
                : Character.toUpperCase(original);
        return bootstrapToken.substring(0, index) + replacement + bootstrapToken.substring(index + 1);
    }

    private String origin() {
        return "http://127.0.0.1:" + port;
    }

    private static int freePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    @FunctionalInterface
    private interface BrowserAction {
        void run(TestBrowser test);
    }
}
