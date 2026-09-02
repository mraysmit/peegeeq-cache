package dev.mars.peegeeq.cache.rest.server;

import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/** Resolves request paths to the reviewed, bounded route templates used by telemetry. */
public final class ManagementRouteTemplate {

    private static final List<Route> ROUTES = List.of(
            route("/health/ready"),
            route("/api/v1/session"),
            route("/api/v1/session/local"),
            route("/api/v1/setups"),
            route("/api/v1/setups/actions/test"),
            route("/api/v1/setups/{setupId}"),
            route("/api/v1/setups/{setupId}/connect"),
            route("/api/v1/setups/{setupId}/test"),
            route("/api/v1/setups/{setupId}/detach"),
            route("/api/v1/setups/{setupId}/health"),
            route("/api/v1/setups/{setupId}/capabilities"),
            route("/api/v1/setups/{setupId}/overview"),
            route("/api/v1/setups/{setupId}/namespaces"),
            route("/api/v1/setups/{setupId}/namespaces/export"),
            route("/api/v1/setups/{setupId}/namespaces/{encodedNamespace}"),
            route("/api/v1/setups/{setupId}/namespaces/{encodedNamespace}/entries"),
            route("/api/v1/setups/{setupId}/namespaces/{encodedNamespace}/entries/{encodedKey}"),
            route("/api/v1/setups/{setupId}/namespaces/{encodedNamespace}/entries/{encodedKey}/value/reveal"),
            route("/api/v1/setups/{setupId}/namespaces/{encodedNamespace}/entries/{encodedKey}/ttl"),
            route("/api/v1/setups/{setupId}/namespaces/{encodedNamespace}/entries/{encodedKey}/persist"),
            route("/api/v1/setups/{setupId}/namespaces/{encodedNamespace}/entries/{encodedKey}/touch"),
            route("/api/v1/setups/{setupId}/namespaces/{encodedNamespace}/entries/{encodedKey}/exists"),
            route("/api/v1/setups/{setupId}/namespaces/{encodedNamespace}/entries/bulk-delete/preview"),
            route("/api/v1/setups/{setupId}/namespaces/{encodedNamespace}/entries/bulk-delete/execute"),
            route("/api/v1/setups/{setupId}/entries/batch-get"),
            route("/api/v1/setups/{setupId}/entries/batch-set"),
            route("/api/v1/setups/{setupId}/entries/scan"),
            route("/api/v1/setups/{setupId}/cache-metrics"),
            route("/api/v1/setups/{setupId}/counters"),
            route("/api/v1/setups/{setupId}/namespaces/{encodedNamespace}/counters/{encodedKey}"),
            route("/api/v1/setups/{setupId}/namespaces/{encodedNamespace}/counters/{encodedKey}/increment"),
            route("/api/v1/setups/{setupId}/namespaces/{encodedNamespace}/counters/{encodedKey}/ttl"),
            route("/api/v1/setups/{setupId}/namespaces/{encodedNamespace}/counters/{encodedKey}/persist"),
            route("/api/v1/setups/{setupId}/counters/bulk-delete/preview"),
            route("/api/v1/setups/{setupId}/counters/bulk-delete/execute"),
            route("/api/v1/setups/{setupId}/locks"),
            route("/api/v1/setups/{setupId}/namespaces/{encodedNamespace}/locks/{encodedKey}"),
            route("/api/v1/setups/{setupId}/namespaces/{encodedNamespace}/locks/{encodedKey}/owner/reveal"),
            route("/api/v1/setups/{setupId}/namespaces/{encodedNamespace}/locks/{encodedKey}/force-release"),
            route("/api/v1/setups/{setupId}/namespaces/{encodedNamespace}/locks/{encodedKey}/acquire"),
            route("/api/v1/setups/{setupId}/namespaces/{encodedNamespace}/locks/{encodedKey}/renew"),
            route("/api/v1/setups/{setupId}/namespaces/{encodedNamespace}/locks/{encodedKey}/release"),
            route("/api/v1/setups/{setupId}/namespaces/{encodedNamespace}/locks/{encodedKey}/ownership"),
            route("/api/v1/setups/{setupId}/pubsub/subscriptions"),
            route("/api/v1/setups/{setupId}/pubsub/subscriptions/{subscriptionId}/stream"),
            route("/api/v1/setups/{setupId}/pubsub/subscriptions/{subscriptionId}/messages/{messageId}/payload/reveal"),
            route("/api/v1/setups/{setupId}/pubsub/subscriptions/{subscriptionId}"),
            route("/api/v1/setups/{setupId}/pubsub/publish"),
            route("/api/v1/setups/{setupId}/monitoring/database"),
            route("/api/v1/setups/{setupId}/monitoring/runtime"),
            route("/api/v1/setups/{setupId}/sse/metrics"),
            route("/api/v1/setups/{setupId}/activity"),
            route("/ws/monitoring"));

    private ManagementRouteTemplate() {
    }

    public static String resolve(String path) {
        Objects.requireNonNull(path, "path");
        for (Route route : ROUTES) {
            if (route.pattern().matcher(path).matches()) {
                return route.template();
            }
        }
        if (path.equals("/api") || path.startsWith("/api/")) return "/api/*";
        if (path.equals("/ws") || path.startsWith("/ws/")) return "/ws/*";
        if (path.equals("/ui") || path.equals("/ui/") || path.startsWith("/ui/")) return "/ui/*";
        return "OTHER";
    }

    private static Route route(String template) {
        StringBuilder regex = new StringBuilder("^");
        for (String segment : template.split("/", -1)) {
            if (!segment.isEmpty()) regex.append('/');
            regex.append(segment.startsWith("{") ? "[^/]+" : Pattern.quote(segment));
        }
        return new Route(template, Pattern.compile(regex.append('$').toString()));
    }

    private record Route(String template, Pattern pattern) {
    }
}
