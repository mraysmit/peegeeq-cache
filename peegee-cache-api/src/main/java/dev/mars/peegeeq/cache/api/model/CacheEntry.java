package dev.mars.peegeeq.cache.api.model;

import java.time.Instant;

public record CacheEntry(
        CacheKey key,
        CacheValue value,
        long version,
        Instant createdAt,
        Instant updatedAt,
        Instant expiresAt,
        long hitCount,
        Instant lastAccessedAt
) {
    public boolean isExpiring() {
        return expiresAt != null;
    }
}
