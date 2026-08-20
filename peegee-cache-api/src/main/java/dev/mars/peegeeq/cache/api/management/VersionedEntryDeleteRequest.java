package dev.mars.peegeeq.cache.api.management;

import dev.mars.peegeeq.cache.api.model.CacheKey;

import java.util.Objects;

/** Exact-version cache-entry deletion request. */
public record VersionedEntryDeleteRequest(CacheKey key, long expectedVersion) {
    public VersionedEntryDeleteRequest {
        Objects.requireNonNull(key, "key");
        ManagementModelValidation.nonNegativeVersion(expectedVersion, "expectedVersion");
    }
}
