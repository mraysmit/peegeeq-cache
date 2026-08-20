package dev.mars.peegeeq.cache.api.management;

import dev.mars.peegeeq.cache.api.model.CacheKey;
import dev.mars.peegeeq.cache.api.model.ValueType;

import java.time.Instant;
import java.util.Objects;

/** Cache-entry metadata that deliberately cannot carry the stored value. */
public record ManagementEntryMetadata(
        CacheKey key,
        ValueType valueType,
        long sizeBytes,
        long version,
        Instant createdAt,
        Instant updatedAt,
        Instant lastAccessedAt,
        ManagementTtl ttl) {
    public ManagementEntryMetadata {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(valueType, "valueType");
        ManagementModelValidation.nonNegativeVersion(sizeBytes, "sizeBytes");
        ManagementModelValidation.nonNegativeVersion(version, "version");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        Objects.requireNonNull(ttl, "ttl");
    }
}
