package dev.mars.peegeeq.cache.rest.server;

import io.vertx.core.http.HttpServerRequest;

import java.util.List;
import java.util.Objects;

@FunctionalInterface
public interface ManagementRequestRouter {
    /** @return true when this router owns the request, including asynchronous handling. */
    boolean route(HttpServerRequest request);

    static ManagementRequestRouter none() {
        return request -> false;
    }

    static ManagementRequestRouter firstOf(ManagementRequestRouter... routers) {
        List<ManagementRequestRouter> ordered = java.util.Arrays.stream(routers)
                .map(router -> Objects.requireNonNull(router, "router"))
                .toList();
        return request -> {
            for (ManagementRequestRouter router : ordered) {
                if (router.route(request)) {
                    return true;
                }
            }
            return false;
        };
    }
}
