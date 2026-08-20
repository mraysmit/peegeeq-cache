package dev.mars.peegeeq.cache.api.management;

import dev.mars.peegeeq.cache.api.model.CacheKey;
import dev.mars.peegeeq.cache.api.model.CounterTtlMode;

import java.time.Duration;
import java.util.Objects;

/** Counter set guarded either by exact version or by absence. */
public record ManagementCounterSetRequest(
        CacheKey key,
        long value,
        Long expectedVersion,
        boolean requireAbsent,
        CounterTtlMode ttlMode,
        Duration ttl) {

    public ManagementCounterSetRequest {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(ttlMode, "ttlMode");
        if ((expectedVersion == null) == !requireAbsent) {
            throw new IllegalArgumentException("exactly one of expectedVersion or requireAbsent is required");
        }
        if (expectedVersion != null) {
            ManagementModelValidation.nonNegativeVersion(expectedVersion, "expectedVersion");
        }
        ttl = validateCounterTtl(ttlMode, ttl);
    }

    static Duration validateCounterTtl(CounterTtlMode mode, Duration ttl) {
        if (mode == CounterTtlMode.REPLACE) {
            return ManagementModelValidation.positiveDuration(ttl, "ttl");
        }
        if (ttl != null) {
            throw new IllegalArgumentException("ttl is allowed only with REPLACE");
        }
        return null;
    }
}
