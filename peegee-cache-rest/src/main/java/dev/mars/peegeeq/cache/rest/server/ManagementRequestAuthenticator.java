package dev.mars.peegeeq.cache.rest.server;

import io.vertx.core.http.HttpServerRequest;

@FunctionalInterface
public interface ManagementRequestAuthenticator {
    AuthenticatedManagementRequest authenticate(HttpServerRequest request);
}
