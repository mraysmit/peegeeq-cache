package dev.mars.peegeeq.cache.api.management;

import dev.mars.peegeeq.cache.api.model.CacheKey;
import dev.mars.peegeeq.cache.api.model.CacheValue;
import dev.mars.peegeeq.cache.api.model.SetMode;

import java.time.Duration;
import java.util.Objects;

/** Version-aware cache write with an atomic TTL mode. */
public record ManagementCacheSetRequest(
        CacheKey key,
        CacheValue value,
        SetMode mode,
        Long expectedVersion,
        EntryTtlMode ttlMode,
        Duration ttl) {

    public ManagementCacheSetRequest {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(value, "value");
        Objects.requireNonNull(mode, "mode");
        Objects.requireNonNull(ttlMode, "ttlMode");
        if ((mode == SetMode.ONLY_IF_VERSION_MATCHES) != (expectedVersion != null)) {
            throw new IllegalArgumentException("expectedVersion is required only for version-matched sets");
        }
        if (expectedVersion != null) {
            ManagementModelValidation.nonNegativeVersion(expectedVersion, "expectedVersion");
        }
        if (ttlMode == EntryTtlMode.REPLACE) {
            ttl = ManagementModelValidation.positiveDuration(ttl, "ttl");
        } else if (ttl != null) {
            throw new IllegalArgumentException("ttl is allowed only with REPLACE");
        }
    }
}
