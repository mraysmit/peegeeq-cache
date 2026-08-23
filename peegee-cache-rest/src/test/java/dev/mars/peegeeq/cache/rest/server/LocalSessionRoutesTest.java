package dev.mars.peegeeq.cache.rest.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.mars.peegeeq.cache.api.management.ManagementSecretReference;
import dev.mars.peegeeq.cache.rest.security.BrowserOriginPolicy;
import dev.mars.peegeeq.cache.rest.security.BrowserRequestSecurity;
import dev.mars.peegeeq.cache.rest.security.LocalTokenAuthenticationConfig;
import dev.mars.peegeeq.cache.rest.security.LocalTokenBootstrap;
import dev.mars.peegeeq.cache.rest.security.LocalTokenSessionManager;
import dev.mars.peegeeq.cache.rest.security.SetupTargetPolicy;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import org.junit.jupiter.api.Test;

import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalSessionRoutesTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void enforcesBootstrapReplayCookieSessionAndCsrfOverHttp() throws Exception {
        Vertx vertx = Vertx.vertx();
        int port = freePort();
        String origin = "http://127.0.0.1:" + port;
        LocalTokenBootstrap bootstrap = LocalTokenSessionManager.start(LocalTokenAuthenticationConfig.defaults());
        LocalSessionRoutes routes = new LocalSessionRoutes(
                bootstrap.manager(),
                new BrowserRequestSecurity(BrowserOriginPolicy.localToken(origin)),
                16 * 1024);
        SetupRegistry registry = new SetupRegistry(
                (definition, secret) -> Future.failedFuture("unused"),
                reference -> null);
        ManagementHttpServer server = new ManagementHttpServer(
                vertx,
                configuration(port),
                ManagementServerResources.noop(),
                ManagementRequestRouter.firstOf(
                        routes,
                        new SetupReadRoutes(
                                registry,
                                new LocalSessionRequestAuthenticator(bootstrap.manager()))));
        try {
            await(server.start());

            HttpResponse<String> wrongOrigin = send(port, "POST", "/api/v1/session/local",
                    "{\"token\":\"" + bootstrap.token() + "\"}", null,
                    "http://evil.example", null);
            assertEquals(403, wrongOrigin.statusCode());

            HttpResponse<String> login = send(port, "POST", "/api/v1/session/local",
                    "{\"token\":\"" + bootstrap.token() + "\"}", null, origin, null);
            assertEquals(200, login.statusCode());
            assertEquals("no-store", login.headers().firstValue("cache-control").orElseThrow());
            String setCookie = login.headers().firstValue("set-cookie").orElseThrow();
            assertTrue(setCookie.contains("PGQMGMTSESSION="));
            assertTrue(setCookie.contains("HttpOnly"));
            assertTrue(setCookie.contains("SameSite=Strict"));
            String cookie = setCookie.substring(0, setCookie.indexOf(';'));
            JsonNode session = JSON.readTree(login.body());
            String csrf = session.path("csrfToken").asText();
            assertEquals("local-operator", session.path("user").asText());
            assertEquals("LOCAL_TOKEN", session.path("authenticationMode").asText());

            assertEquals(401, send(port, "POST", "/api/v1/session/local",
                    "{\"token\":\"" + bootstrap.token() + "\"}", null, origin, null).statusCode());
            assertEquals(200, send(port, "GET", "/api/v1/session", null, cookie, null, null).statusCode());
            assertEquals(401, send(port, "GET", "/api/v1/setups", null,
                    null, null, null).statusCode());
            assertEquals(200, send(port, "GET", "/api/v1/setups", null,
                    cookie, null, null).statusCode());
            assertEquals(403, send(port, "DELETE", "/api/v1/session/local", null,
                    cookie, origin, null).statusCode());
            assertEquals(204, send(port, "DELETE", "/api/v1/session/local", null,
                    cookie, origin, csrf).statusCode());
            assertEquals(401, send(port, "GET", "/api/v1/session", null,
                    cookie, null, null).statusCode());
        } finally {
            await(server.stop());
            await(registry.closeAsync());
            bootstrap.manager().close();
            await(vertx.close());
        }
    }

    private static HttpResponse<String> send(
            int port,
            String method,
            String path,
            String body,
            String cookie,
            String origin,
            String csrf) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(
                URI.create("http://127.0.0.1:" + port + path));
        if (cookie != null) request.header("Cookie", cookie);
        if (origin != null) request.header("Origin", origin);
        if (csrf != null) request.header("X-PeeGeeQ-CSRF", csrf);
        if (body != null) request.header("Content-Type", "application/json");
        request.method(method, body == null
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body));
        return HttpClient.newHttpClient().send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static ManagementServerConfiguration configuration(int port) {
        return ManagementServerConfiguration.localToken(
                "127.0.0.1", port, "http://127.0.0.1:" + port,
                SetupTargetPolicy.privateNetworks(
                        Set.of("internal.example"), Set.of("10.0.0.0/8"),
                        Set.of(5432), Set.of("corp-ca")),
                Path.of("logs", "management-audit.jsonl"),
                new ManagementSecretReference("env:PGQ_AUDIT_KEY"));
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
