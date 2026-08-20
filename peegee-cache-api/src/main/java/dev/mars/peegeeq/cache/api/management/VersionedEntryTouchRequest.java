package dev.mars.peegeeq.cache.api.management;

import dev.mars.peegeeq.cache.api.model.CacheKey;

import java.time.Duration;
import java.util.Objects;

/** Exact-version entry touch with an optional positive TTL refresh. */
public record VersionedEntryTouchRequest(CacheKey key, long expectedVersion, Duration refreshTtl) {
    public VersionedEntryTouchRequest {
        Objects.requireNonNull(key, "key");
        ManagementModelValidation.nonNegativeVersion(expectedVersion, "expectedVersion");
        if (refreshTtl != null) {
            refreshTtl = ManagementModelValidation.positiveDuration(refreshTtl, "refreshTtl");
        }
    }
}
