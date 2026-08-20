package dev.mars.peegeeq.cache.api.management;

import dev.mars.peegeeq.cache.api.model.CacheKey;

import java.util.Objects;

/** Exact-version counter deletion request. */
public record VersionedCounterDeleteRequest(CacheKey key, long expectedVersion) {
    public VersionedCounterDeleteRequest {
        Objects.requireNonNull(key, "key");
        ManagementModelValidation.nonNegativeVersion(expectedVersion, "expectedVersion");
    }
}
