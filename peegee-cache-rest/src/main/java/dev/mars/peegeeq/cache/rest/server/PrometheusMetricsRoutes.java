package dev.mars.peegeeq.cache.rest.server;

import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import io.vertx.core.http.HttpServerRequest;

import java.util.Objects;

/** Prometheus scrape boundary for the mandatory production management telemetry. */
final class PrometheusMetricsRoutes implements ManagementRequestRouter {

    private static final String PATH = "/metrics";
    private final PrometheusMeterRegistry registry;

    PrometheusMetricsRoutes(PrometheusMeterRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry");
    }

    @Override
    public boolean route(HttpServerRequest request) {
        if (!request.method().name().equals("GET") || !request.path().equals(PATH)) {
            return false;
        }
        request.response()
                .putHeader("content-type", "text/plain; version=0.0.4; charset=utf-8")
                .putHeader("cache-control", "no-store")
                .putHeader("x-content-type-options", "nosniff")
                .end(registry.scrape());
        return true;
    }
}
