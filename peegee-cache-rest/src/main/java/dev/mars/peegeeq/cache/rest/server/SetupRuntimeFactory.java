package dev.mars.peegeeq.cache.rest.server;

import io.vertx.core.Future;

@FunctionalInterface
public interface SetupRuntimeFactory {
    Future<ManagedSetupRuntime> create(SetupDefinition definition, byte[] secret);
}
