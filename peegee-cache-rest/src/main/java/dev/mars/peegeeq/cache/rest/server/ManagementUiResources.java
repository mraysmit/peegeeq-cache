package dev.mars.peegeeq.cache.rest.server;

import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpServerRequest;

import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Serves the packaged management single-page application from the classpath while keeping
 * navigation fallbacks separate from immutable, exact-match asset requests. The narrow resource
 * grammar prevents traversal into other JAR resources, and every response applies the console's
 * reviewed browser-security and cache policy.
 */
final class ManagementUiResources {

    private static final String UI_ROOT = "/ui";
    private static final String ASSET_ROOT = "/ui/assets/";
    private static final Pattern SAFE_ASSET = Pattern.compile("/ui/assets/[A-Za-z0-9][A-Za-z0-9._-]*");
    private static final String CONTENT_SECURITY_POLICY = String.join("; ",
            "default-src 'self'",
            "base-uri 'none'",
            "frame-ancestors 'none'",
            "object-src 'none'",
            "form-action 'self'",
            "script-src 'self'",
            "style-src 'self' 'unsafe-inline'",
            "connect-src 'self'",
            "img-src 'self' data:");

    private final Buffer index = resource("/ui/index.html");
    private final Map<String, Buffer> assets = new ConcurrentHashMap<>();

    boolean handle(HttpServerRequest request) {
        String path = request.path();
        if (!isUiPath(path)) {
            return false;
        }
        if (!request.method().name().equals("GET")) {
            browserHeaders(request);
            request.response()
                    .setStatusCode(405)
                    .putHeader("allow", "GET")
                    .putHeader("cache-control", "no-store")
                    .end();
            return true;
        }
        if (isTraversalAttempt(request.uri(), path)) {
            notFound(request);
            return true;
        }
        if (path.startsWith(ASSET_ROOT)) {
            serveAsset(request, path);
            return true;
        }
        if (looksLikeMissingAsset(path)) {
            notFound(request);
            return true;
        }
        browserHeaders(request);
        request.response()
                .putHeader("content-type", "text/html; charset=utf-8")
                .putHeader("cache-control", "no-store")
                .putHeader("pragma", "no-cache")
                .end(index);
        return true;
    }

    private void serveAsset(HttpServerRequest request, String path) {
        if (!SAFE_ASSET.matcher(path).matches()) {
            notFound(request);
            return;
        }
        Buffer content = assets.computeIfAbsent(path, ManagementUiResources::optionalResource);
        if (content.length() == 0) {
            assets.remove(path);
            notFound(request);
            return;
        }
        browserHeaders(request);
        request.response()
                .putHeader("content-type", contentType(path))
                .putHeader("cache-control", "public, max-age=31536000, immutable")
                .end(content);
    }

    private static void browserHeaders(HttpServerRequest request) {
        request.response()
                .putHeader("content-security-policy", CONTENT_SECURITY_POLICY)
                .putHeader("x-content-type-options", "nosniff")
                .putHeader("referrer-policy", "no-referrer")
                .putHeader("x-frame-options", "DENY");
    }

    private static boolean isUiPath(String path) {
        return path.equals(UI_ROOT) || path.equals(UI_ROOT + "/") || path.startsWith(UI_ROOT + "/");
    }

    private static boolean isTraversalAttempt(String uri, String path) {
        int query = uri.indexOf('?');
        String raw = (query < 0 ? uri : uri.substring(0, query)).toLowerCase(Locale.ROOT);
        return path.indexOf('\\') >= 0
                || raw.contains("%2e")
                || raw.contains("%2f")
                || raw.contains("%5c")
                || raw.contains("%25")
                || path.contains("/../")
                || path.endsWith("/..")
                || path.contains("/./")
                || path.endsWith("/.");
    }

    private static boolean looksLikeMissingAsset(String path) {
        int slash = path.lastIndexOf('/');
        String leaf = slash < 0 ? path : path.substring(slash + 1);
        return leaf.contains(".");
    }

    private static String contentType(String path) {
        String lower = path.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".js")) return "text/javascript; charset=utf-8";
        if (lower.endsWith(".css")) return "text/css; charset=utf-8";
        if (lower.endsWith(".svg")) return "image/svg+xml";
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".webp")) return "image/webp";
        if (lower.endsWith(".woff2")) return "font/woff2";
        if (lower.endsWith(".ico")) return "image/x-icon";
        return "application/octet-stream";
    }

    private static void notFound(HttpServerRequest request) {
        browserHeaders(request);
        request.response()
                .setStatusCode(404)
                .putHeader(ManagementHttpServer.ERROR_CODE_HEADER, "UI_RESOURCE_NOT_FOUND")
                .putHeader("content-type", "application/problem+json; charset=utf-8")
                .putHeader("cache-control", "no-store")
                .end("{\"status\":404,\"code\":\"UI_RESOURCE_NOT_FOUND\",\"title\":\"UI resource not found\"}");
    }

    private static Buffer resource(String path) {
        Buffer content = optionalResource(path);
        if (content.length() == 0) {
            throw new IllegalStateException("Missing management resource " + path);
        }
        return content;
    }

    private static Buffer optionalResource(String path) {
        try (InputStream input = ManagementUiResources.class.getResourceAsStream(path)) {
            return input == null ? Buffer.buffer() : Buffer.buffer(input.readAllBytes());
        } catch (IOException failure) {
            throw new IllegalStateException("Management resource could not be read " + path, failure);
        }
    }
}
