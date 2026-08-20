package dev.mars.peegeeq.cache.api.management;

import dev.mars.peegeeq.cache.api.model.CacheKey;
import dev.mars.peegeeq.cache.api.model.CounterTtlMode;

import java.time.Duration;
import java.util.Objects;

/** Signed non-zero counter adjustment with explicit creation and version semantics. */
public record ManagementCounterAdjustRequest(
        CacheKey key,
        long delta,
        Long expectedVersion,
        boolean createIfMissing,
        CounterTtlMode ttlMode,
        Duration ttl) {

    public ManagementCounterAdjustRequest {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(ttlMode, "ttlMode");
        if (delta == 0) {
            throw new IllegalArgumentException("delta must be non-zero");
        }
        if (createIfMissing == (expectedVersion != null)) {
            throw new IllegalArgumentException("exact version is required unless createIfMissing is true");
        }
        if (expectedVersion != null) {
            ManagementModelValidation.nonNegativeVersion(expectedVersion, "expectedVersion");
        }
        ttl = ManagementCounterSetRequest.validateCounterTtl(ttlMode, ttl);
    }
}
