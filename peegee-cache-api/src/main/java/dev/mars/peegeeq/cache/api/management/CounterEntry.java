package dev.mars.peegeeq.cache.api.management;

import dev.mars.peegeeq.cache.api.model.CacheKey;

import java.time.Instant;
import java.util.Objects;

/** Counter representation returned after authoritative reads or mutations. */
public record CounterEntry(
        CacheKey key,
        long value,
        long version,
        Instant createdAt,
        Instant updatedAt,
        ManagementTtl ttl) {
    public CounterEntry {
        Objects.requireNonNull(key, "key");
        ManagementModelValidation.nonNegativeVersion(version, "version");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        Objects.requireNonNull(ttl, "ttl");
    }
}
