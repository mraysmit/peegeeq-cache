package dev.mars.peegeeq.cache.rest.server;

import io.vertx.core.Future;

/** Required resources whose lifecycle is owned by the HTTP server. */
public interface ManagementServerResources {
    Future<Void> start();

    boolean ready();

    Future<Void> closeAsync();

    static ManagementServerResources noop() {
        return new ManagementServerResources() {
            @Override
            public Future<Void> start() {
                return Future.succeededFuture();
            }

            @Override
            public boolean ready() {
                return true;
            }

            @Override
            public Future<Void> closeAsync() {
                return Future.succeededFuture();
            }
        };
    }
}
