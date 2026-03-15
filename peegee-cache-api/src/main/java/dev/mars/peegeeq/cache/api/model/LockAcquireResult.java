package dev.mars.peegeeq.cache.api.model;

import java.time.Instant;

public record LockAcquireResult(
        boolean acquired,
        LockKey key,
        String ownerToken,
        Long fencingToken,
        Instant leaseExpiresAt
) {}
