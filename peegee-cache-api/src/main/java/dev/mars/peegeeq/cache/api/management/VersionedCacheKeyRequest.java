package dev.mars.peegeeq.cache.api.management;

import dev.mars.peegeeq.cache.api.model.CacheKey;

import java.util.Objects;

/** Exact-version request for a cache or counter key. */
public record VersionedCacheKeyRequest(CacheKey key, long expectedVersion) {
    public VersionedCacheKeyRequest {
        Objects.requireNonNull(key, "key");
        ManagementModelValidation.nonNegativeVersion(expectedVersion, "expectedVersion");
    }
}
