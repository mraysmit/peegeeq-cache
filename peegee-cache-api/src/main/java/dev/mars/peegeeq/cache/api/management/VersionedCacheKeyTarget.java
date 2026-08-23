package dev.mars.peegeeq.cache.api.management;

import dev.mars.peegeeq.cache.api.model.CacheKey;

import java.util.Objects;

/** Immutable key/version pair captured by a bulk-delete preview. */
public record VersionedCacheKeyTarget(CacheKey key, long version) {
    public VersionedCacheKeyTarget {
        Objects.requireNonNull(key, "key");
        ManagementModelValidation.nonNegativeVersion(version, "version");
    }
}
