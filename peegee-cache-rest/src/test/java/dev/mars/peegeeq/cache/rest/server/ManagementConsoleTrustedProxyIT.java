package dev.mars.peegeeq.cache.rest.server;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.options.AriaRole;
import dev.mars.peegeeq.cache.api.management.ManagementAuditIntent;
import dev.mars.peegeeq.cache.api.management.ManagementAuditOutcome;
import dev.mars.peegeeq.cache.api.management.ManagementAuditReservation;
import dev.mars.peegeeq.cache.api.management.ManagementAuditSink;
import dev.mars.peegeeq.cache.api.management.ManagementSecretReference;
import dev.mars.peegeeq.cache.rest.security.BrowserOriginPolicy;
import dev.mars.peegeeq.cache.rest.security.BrowserRequestSecurity;
import dev.mars.peegeeq.cache.rest.security.ManagementRateLimiter;
import dev.mars.peegeeq.cache.rest.security.RateLimitAction;
import dev.mars.peegeeq.cache.rest.security.RateLimitRule;
import dev.mars.peegeeq.cache.rest.security.RateLimitTelemetry;
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
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

@ExtendWith(VertxExtension.class)
class ManagementConsoleTrustedProxyIT {

    private ManagementHttpServer server;
    private TrustedProxySessionManager sessions;
    private MutableClock clock;
    private int port;

    @BeforeEach
    void start(Vertx vertx, VertxTestContext context) throws Exception {
        port = freePort();
        TrustedProxyAuthenticationConfig authentication =
                TrustedProxyAuthenticationConfig.defaults(Set.of("127.0.0.0/8"));
        BrowserOriginPolicy origins = BrowserOriginPolicy.trustedProxy(origin(), Set.of());
        clock = new MutableClock(Instant.now(), ZoneId.of("UTC"));
        sessions = TrustedProxySessionManager.create(
                Duration.ofMinutes(2), Duration.ofHours(1), clock);
        TrustedProxyAuthenticator proxyAuthenticator = new TrustedProxyAuthenticator(authentication);
        ManagementRequestAuthenticator requestAuthenticator =
                new TrustedProxySessionRequestAuthenticator(proxyAuthenticator, sessions);
        SetupMutationRoutes setupMutations = new SetupMutationRoutes(
                new SetupRegistry(
                        (definition, secret) -> Future.failedFuture("Setup connection is not expected"),
                        reference -> null),
                requestAuthenticator,
                new BrowserRequestSecurity(origins),
                new UnusedAuditSink(),
                permissiveRateLimiter(),
                Clock.systemUTC());
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
                ManagementRequestRouter.firstOf(
                        new TrustedProxySessionRoutes(proxyAuthenticator, sessions, origins),
                        setupMutations));
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

    @ManagementBrowserScenario(
            id = "PW-AUTH-005",
            requirement = "UI design: trusted-proxy identity bootstrap without local-token controls",
            area = ManagementBrowserArea.AUTHENTICATION,
            risk = ManagementBrowserRisk.CRITICAL,
            action = "Load the packaged console with trusted identity and role headers",
            expectedResult = "The shell displays the trusted identity and exposes no local-token login UI",
            cleanup = "Close the isolated browser context and trusted-proxy server lifecycle",
            evidence = {
                    ManagementBrowserEvidence.VISIBLE_RESULT,
                    ManagementBrowserEvidence.SENSITIVE_STATE,
                    ManagementBrowserEvidence.RESOURCE_CLEANUP
            })
    @ManagementBrowserJourney(
            value = "trusted-proxy-session",
            operations = {})
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

            String operatorCookie = sessionCookie(context);
            context.setExtraHTTPHeaders(Map.of(
                    "X-PeeGeeQ-User", "blair",
                    "X-PeeGeeQ-Roles", "viewer"));
            page.reload();
            Locator sessionStatus = page.getByLabel("Session and connection status");
            assertThat(sessionStatus.getByText(
                    "blair", new Locator.GetByTextOptions().setExact(true))).isVisible();
            assertThat(sessionStatus.getByText(
                    "Viewer", new Locator.GetByTextOptions().setExact(true))).isVisible();
            assertThat(sessionStatus.getByText(
                    "Operator", new Locator.GetByTextOptions().setExact(true))).hasCount(0);
            String viewerCookie = sessionCookie(context);
            assertNotEquals(operatorCookie, viewerCookie);

            clock.advance(Duration.ofMinutes(2));
            page.reload();
            assertThat(page.getByRole(
                    AriaRole.HEADING,
                    new Page.GetByRoleOptions().setName("Settings"))).isVisible();
            assertThat(page.getByLabel("Session and connection status").getByText(
                    "blair", new Locator.GetByTextOptions().setExact(true))).isVisible();
            assertNotEquals(viewerCookie, sessionCookie(context));
            assertThat(page.getByRole(
                    AriaRole.BUTTON,
                    new Page.GetByRoleOptions().setName("End local session"))).hasCount(0);
            assertEquals(List.of(), failedResponses);
            assertEquals(List.of(), browserErrors);
        }
    }

    @ManagementBrowserScenario(
            id = "PW-AUTH-006",
            requirement = "Management API: server-side operator authorization is authoritative",
            area = ManagementBrowserArea.AUTHENTICATION,
            risk = ManagementBrowserRisk.CRITICAL,
            action = "Call an operator-only setup route directly from a viewer browser session",
            expectedResult = "The real server denies the request and the viewer shell exposes no operator action",
            cleanup = "Close the isolated viewer context and trusted-proxy server lifecycle",
            evidence = {
                    ManagementBrowserEvidence.VISIBLE_RESULT,
                    ManagementBrowserEvidence.RESOURCE_CLEANUP
            })
    @ManagementBrowserJourney(
            value = "server-authorization",
            operations = {})
    void viewerIsDeniedByTheRealServerWhenItCallsAnOperatorRouteDirectly() {
        List<String> browserErrors = new ArrayList<>();
        try (Playwright playwright = Playwright.create(new Playwright.CreateOptions()
                .setEnv(Map.of("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD", "1")));
             Browser browser = playwright.chromium().launch(
                     ManagementPlaywright.launchOptions());
             BrowserContext context = browser.newContext(new Browser.NewContextOptions()
                     .setExtraHTTPHeaders(Map.of(
                             "X-PeeGeeQ-User", "viewer-only",
                             "X-PeeGeeQ-Roles", "viewer")))) {
            Page page = context.newPage();
            page.onPageError(browserErrors::add);
            assertEquals(200, page.navigate(origin() + "/ui/setups").status());
            assertThat(page.getByRole(
                    AriaRole.HEADING,
                    new Page.GetByRoleOptions().setName("Setups"))).isVisible();
            assertThat(page.getByRole(
                    AriaRole.BUTTON,
                    new Page.GetByRoleOptions().setName("Register setup"))).hasCount(0);

            @SuppressWarnings("unchecked")
            Map<String, Object> result = (Map<String, Object>) page.evaluate("""
                    async () => {
                      const session = await fetch('/api/v1/session', {credentials: 'include'});
                      const current = await session.json();
                      const response = await fetch('/api/v1/setups/actions/test', {
                        method: 'POST',
                        credentials: 'include',
                        headers: {
                          'content-type': 'application/json',
                          'x-peegeeq-csrf': current.csrfToken
                        },
                        body: JSON.stringify({
                          host: 'db.internal.example', port: 5432, database: 'peegeeq',
                          schema: 'peegee_cache', username: 'peegeeq', password: 'not-used',
                          sslMode: 'VERIFY_FULL', trustProfile: 'corp-ca', poolSize: 1
                        })
                      });
                      const body = await response.json();
                      return {status: response.status, code: body.code};
                    }
                    """);
            assertEquals(403, ((Number) result.get("status")).intValue());
            assertEquals("AUTHORIZATION_FAILED", result.get("code"));
            assertEquals(List.of(), browserErrors);
        }
    }

    private static ManagementRateLimiter permissiveRateLimiter() {
        return new ManagementRateLimiter(
                java.util.Arrays.stream(RateLimitAction.values()).collect(Collectors.toMap(
                        action -> action,
                        action -> new RateLimitRule(100, 100, Duration.ofMinutes(1)))),
                100,
                Clock.systemUTC(),
                RateLimitTelemetry.noop());
    }

    private static final class UnusedAuditSink implements ManagementAuditSink {
        @Override
        public Future<ManagementAuditReservation> reserveIntent(ManagementAuditIntent intent) {
            return Future.failedFuture("Viewer authorization must fail before audit reservation");
        }

        @Override
        public Future<Void> complete(
                ManagementAuditReservation reservation,
                ManagementAuditOutcome outcome) {
            return Future.failedFuture("Viewer authorization must fail before audit completion");
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

    private static final class MutableClock extends Clock {
        private volatile Instant instant;
        private final ZoneId zone;

        private MutableClock(Instant instant, ZoneId zone) {
            this.instant = instant;
            this.zone = zone;
        }

        private void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return zone;
        }

        @Override
        public Clock withZone(ZoneId requestedZone) {
            return new MutableClock(instant, requestedZone);
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
