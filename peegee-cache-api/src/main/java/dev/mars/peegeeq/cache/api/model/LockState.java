package dev.mars.peegeeq.cache.api.model;

import java.time.Instant;

public record LockState(
        LockKey key,
        String ownerToken,
        Long fencingToken,
        long version,
        Instant createdAt,
        Instant updatedAt,
        Instant leaseExpiresAt
) {}
