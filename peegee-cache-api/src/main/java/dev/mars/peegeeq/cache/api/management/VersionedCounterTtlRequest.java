package dev.mars.peegeeq.cache.api.management;

import dev.mars.peegeeq.cache.api.model.CacheKey;

import java.time.Duration;
import java.util.Objects;

/** Exact-version request to replace a counter expiry. */
public record VersionedCounterTtlRequest(CacheKey key, long expectedVersion, Duration ttl) {
    public VersionedCounterTtlRequest {
        Objects.requireNonNull(key, "key");
        ManagementModelValidation.nonNegativeVersion(expectedVersion, "expectedVersion");
        ttl = ManagementModelValidation.positiveDuration(ttl, "ttl");
    }
}
