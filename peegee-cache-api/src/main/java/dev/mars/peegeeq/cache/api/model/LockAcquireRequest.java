package dev.mars.peegeeq.cache.api.model;

import java.time.Duration;

public record LockAcquireRequest(
        LockKey key,
        String ownerToken,
        Duration leaseTtl,
        boolean reentrantForSameOwner,
        boolean issueFencingToken
) {}
