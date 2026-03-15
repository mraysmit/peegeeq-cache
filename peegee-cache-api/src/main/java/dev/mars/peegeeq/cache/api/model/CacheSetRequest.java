package dev.mars.peegeeq.cache.api.model;

import java.time.Duration;

public record CacheSetRequest(
        CacheKey key,
        CacheValue value,
        Duration ttl,
        SetMode mode,
        Long expectedVersion,
        boolean returnPreviousValue
) {}
