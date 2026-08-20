package dev.mars.peegeeq.cache.api.management;

import dev.mars.peegeeq.cache.api.model.CacheKey;

import java.time.Duration;
import java.util.Objects;

/** Exact-version request to replace an entry expiry. */
public record VersionedEntryTtlRequest(CacheKey key, long expectedVersion, Duration ttl) {
    public VersionedEntryTtlRequest {
        Objects.requireNonNull(key, "key");
        ManagementModelValidation.nonNegativeVersion(expectedVersion, "expectedVersion");
        ttl = ManagementModelValidation.positiveDuration(ttl, "ttl");
    }
}
