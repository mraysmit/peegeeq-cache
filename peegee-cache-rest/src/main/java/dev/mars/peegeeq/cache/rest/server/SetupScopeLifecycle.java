package dev.mars.peegeeq.cache.rest.server;

import io.vertx.core.Future;

/** Setup-scoped resources that must follow registry detach and shutdown. */
interface SetupScopeLifecycle {
    default Future<Void> healthChanged(String setupId, SetupHealth health) {
        return Future.succeededFuture();
    }

    Future<Void> closeSetup(String setupId);

    Future<Void> close();
}
