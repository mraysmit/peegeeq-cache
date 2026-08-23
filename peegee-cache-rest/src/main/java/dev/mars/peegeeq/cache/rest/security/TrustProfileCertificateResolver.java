package dev.mars.peegeeq.cache.rest.security;

import io.vertx.core.buffer.Buffer;

/** Resolves system-owned PEM trust material without accepting request-supplied paths. */
@FunctionalInterface
public interface TrustProfileCertificateResolver {
    Buffer resolve(String trustProfileId);
}
