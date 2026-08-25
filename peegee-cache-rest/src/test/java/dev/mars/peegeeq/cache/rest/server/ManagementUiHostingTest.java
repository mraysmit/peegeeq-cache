package dev.mars.peegeeq.cache.rest.server;

import dev.mars.peegeeq.cache.rest.security.BrowserOriginPolicy;
import dev.mars.peegeeq.cache.rest.security.TrustedProxyAuthenticationConfig;
import dev.mars.peegeeq.cache.rest.security.TrustedProxyAuthenticator;
import dev.mars.peegeeq.cache.rest.security.TrustedProxySessionManager;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClientResponse;
import io.vertx.core.http.HttpMethod;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.net.ServerSocket;
import java.util.Set;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(VertxExtension.class)
class ManagementUiHostingTest {

    private static final Pattern SCRIPT_ASSET = Pattern.compile("src=\"(/ui/assets/[^\"]+\\.js)\"");

    private ManagementHttpServer server;
    private TrustedProxySessionManager sessions;
    private int port;

    @BeforeEach
    void start(Vertx vertx, VertxTestContext context) throws Exception {
        port = freePort();
        sessions = TrustedProxySessionManager.createDefault();
        TrustedProxyAuthenticator authenticator = new TrustedProxyAuthenticator(
                TrustedProxyAuthenticationConfig.defaults(Set.of("127.0.0.0/8")));
        server = new ManagementHttpServer(
                vertx,
                TestManagementConfigurations.local(port),
                ManagementServerResources.noop(),
                new TrustedProxySessionRoutes(
                        authenticator,
                        sessions,
                        BrowserOriginPolicy.trustedProxy("http://127.0.0.1:" + port, Set.of())));
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
    void distinguishesSpaRoutesFromMissingAssetsAndAppliesBrowserHeaders(
            Vertx vertx,
            VertxTestContext context) {
        request(vertx, "/ui/overview")
                .compose(route -> request(vertx, scriptAsset(route.body()))
                        .compose(script -> request(vertx, "/ui/assets/not-packaged.js")
                                .compose(missing -> request(vertx, "/ui/%2e%2e/openapi/peegeeq-cache-management-v1.yaml")
                                        .map(traversal -> new HostingResponses(
                                                route, script, missing, traversal)))))
                .onSuccess(responses -> context.verify(() -> {
                    assertEquals(200, responses.route().status());
                    assertTrue(responses.route().contentType().startsWith("text/html"));
                    assertEquals("no-store", responses.route().cacheControl());
                    assertTrue(responses.route().contentSecurityPolicy().contains("default-src 'self'"));
                    assertEquals(200, responses.script().status());
                    assertTrue(responses.script().contentType().startsWith("text/javascript"));
                    assertEquals("public, max-age=31536000, immutable",
                            responses.script().cacheControl());
                    assertFalse(responses.script().body().isBlank());
                    assertEquals(404, responses.missing().status());
                    assertFalse(responses.missing().body().contains("<div id=\"root\"></div>"));
                    assertEquals(404, responses.traversal().status());
                    context.completeNow();
                }))
                .onFailure(context::failNow);
    }

    @Test
    void bootstrapsSameOriginTrustedProxySessionWithoutAnOriginHeader(
            Vertx vertx,
            VertxTestContext context) {
        vertx.createHttpClient()
                .request(HttpMethod.GET, port, "127.0.0.1", "/api/v1/session")
                .compose(request -> request
                        .putHeader("X-PeeGeeQ-User", "alex")
                        .putHeader("X-PeeGeeQ-Roles", "viewer,operator")
                        .send())
                .compose(response -> response.body().map(body -> new Response(
                        response.statusCode(),
                        response.getHeader("content-type"),
                        response.getHeader("cache-control"),
                        response.getHeader("content-security-policy"),
                        body.toString())))
                .onSuccess(response -> context.verify(() -> {
                    assertEquals(200, response.status());
                    assertTrue(response.body().contains("\"authenticationMode\":\"TRUSTED_PROXY\""));
                    context.completeNow();
                }))
                .onFailure(context::failNow);
    }

    private Future<Response> request(Vertx vertx, String path) {
        return vertx.createHttpClient()
                .request(HttpMethod.GET, port, "127.0.0.1", path)
                .compose(request -> request.send())
                .compose(response -> response.body().map(body -> response(response, body)));
    }

    private static Response response(HttpClientResponse response, Buffer body) {
        return new Response(
                response.statusCode(),
                response.headers().get("content-type"),
                response.headers().get("cache-control"),
                response.headers().get("content-security-policy"),
                body.toString());
    }

    private static int freePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static String scriptAsset(String index) {
        var matcher = SCRIPT_ASSET.matcher(index);
        if (!matcher.find()) {
            throw new AssertionError("Packaged index does not reference a JavaScript asset");
        }
        return matcher.group(1);
    }

    private record HostingResponses(
            Response route,
            Response script,
            Response missing,
            Response traversal) {
    }

    private record Response(
            int status,
            String contentType,
            String cacheControl,
            String contentSecurityPolicy,
            String body) {
    }
}
