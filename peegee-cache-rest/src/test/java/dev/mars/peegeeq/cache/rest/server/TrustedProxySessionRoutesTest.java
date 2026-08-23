package dev.mars.peegeeq.cache.rest.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.mars.peegeeq.cache.rest.security.BrowserOriginPolicy;
import dev.mars.peegeeq.cache.rest.security.TrustedProxyAuthenticationConfig;
import dev.mars.peegeeq.cache.rest.security.TrustedProxyAuthenticator;
import dev.mars.peegeeq.cache.rest.security.TrustedProxySessionManager;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import org.junit.jupiter.api.Test;

import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TrustedProxySessionRoutesTest {

    private static final String CONSOLE_ORIGIN = "https://console.example.com";
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void authenticatesAtProxyBoundaryAndBindsRotatingBrowserSessionOverHttp() throws Exception {
        Vertx vertx = Vertx.vertx();
        int port = freePort();
        TrustedProxySessionManager sessions = TrustedProxySessionManager.createDefault();
        TrustedProxyAuthenticator authenticator = new TrustedProxyAuthenticator(
                TrustedProxyAuthenticationConfig.defaults(Set.of("127.0.0.0/8")));
        TrustedProxySessionRoutes routes = new TrustedProxySessionRoutes(
                authenticator,
                sessions,
                BrowserOriginPolicy.trustedProxy(
                        "https://management.internal.example", Set.of(CONSOLE_ORIGIN)));
        SetupRegistry registry = new SetupRegistry(
                (definition, secret) -> Future.failedFuture("unused"),
                reference -> null);
        ManagementHttpServer server = new ManagementHttpServer(
                vertx,
                TestManagementConfigurations.local(port),
                ManagementServerResources.noop(),
                ManagementRequestRouter.firstOf(
                        routes,
                        new SetupReadRoutes(
                                registry,
                                new TrustedProxySessionRequestAuthenticator(authenticator, sessions))));
        try {
            await(server.start());

            assertEquals(401, send(port, null, null, CONSOLE_ORIGIN, null).statusCode());
            assertEquals(403, send(port, "alex", "viewer", "https://evil.example", null).statusCode());

            HttpResponse<String> created = send(port, "alex", "viewer", CONSOLE_ORIGIN, null);
            assertEquals(200, created.statusCode());
            assertEquals(CONSOLE_ORIGIN,
                    created.headers().firstValue("access-control-allow-origin").orElseThrow());
            assertEquals("true",
                    created.headers().firstValue("access-control-allow-credentials").orElseThrow());
            assertEquals("no-store", created.headers().firstValue("cache-control").orElseThrow());
            String firstCookie = cookie(created);
            String setCookie = created.headers().firstValue("set-cookie").orElseThrow();
            assertTrue(setCookie.contains("HttpOnly"));
            assertTrue(setCookie.contains("Secure"));
            assertTrue(setCookie.contains("SameSite=Strict"));
            assertEquals("TRUSTED_PROXY", JSON.readTree(created.body()).path("authenticationMode").asText());

            HttpResponse<String> refreshed = send(
                    port, "alex", "viewer", CONSOLE_ORIGIN, firstCookie);
            assertEquals(firstCookie, cookie(refreshed));

            assertEquals(401, sendPath(
                    port, "/api/v1/setups", null, null, null, firstCookie).statusCode());
            assertEquals(200, sendPath(
                    port, "/api/v1/setups", "alex", "viewer", null, firstCookie).statusCode());

            HttpResponse<String> elevated = send(
                    port, "alex", "viewer,operator", CONSOLE_ORIGIN, firstCookie);
            assertEquals(200, elevated.statusCode());
            assertNotEquals(firstCookie, cookie(elevated));
        } finally {
            await(server.stop());
            await(registry.closeAsync());
            sessions.close();
            await(vertx.close());
        }
    }

    private static HttpResponse<String> send(
            int port, String actor, String roles, String origin, String cookie) throws Exception {
        return sendPath(port, "/api/v1/session", actor, roles, origin, cookie);
    }

    private static HttpResponse<String> sendPath(
            int port, String path, String actor, String roles, String origin, String cookie) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(
                URI.create("http://127.0.0.1:" + port + path));
        if (actor != null) request.header("X-PeeGeeQ-User", actor);
        if (roles != null) request.header("X-PeeGeeQ-Roles", roles);
        if (origin != null) request.header("Origin", origin);
        if (cookie != null) request.header("Cookie", cookie);
        return HttpClient.newHttpClient().send(
                request.GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String cookie(HttpResponse<String> response) {
        String value = response.headers().firstValue("set-cookie").orElseThrow();
        return value.substring(0, value.indexOf(';'));
    }

    private static int freePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static <T> T await(Future<T> future) throws Exception {
        return future.toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
    }
}
