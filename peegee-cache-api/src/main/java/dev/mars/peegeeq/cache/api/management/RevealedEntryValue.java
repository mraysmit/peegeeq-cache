package dev.mars.peegeeq.cache.api.management;

import dev.mars.peegeeq.cache.api.model.CacheKey;
import dev.mars.peegeeq.cache.api.model.CacheValue;

import java.time.Instant;
import java.util.Objects;

/** Sensitive value and version observed in one database snapshot. */
public record RevealedEntryValue(CacheKey key, CacheValue value, long version, Instant revealedAt) {
    public RevealedEntryValue {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(value, "value");
        ManagementModelValidation.nonNegativeVersion(version, "version");
        Objects.requireNonNull(revealedAt, "revealedAt");
    }
}
