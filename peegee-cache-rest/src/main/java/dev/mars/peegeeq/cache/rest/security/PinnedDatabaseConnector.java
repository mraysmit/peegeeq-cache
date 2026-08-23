package dev.mars.peegeeq.cache.rest.security;

import io.vertx.core.Future;

import java.net.InetAddress;

@FunctionalInterface
public interface PinnedDatabaseConnector {
    Future<Void> connect(
            InetAddress address,
            int port,
            String tlsServerName,
            String trustProfileId,
            TlsMode tlsMode) throws Exception;
}
