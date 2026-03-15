package dev.mars.peegeeq.cache.api.model;

import java.time.Duration;

public record LockRenewRequest(
        LockKey key,
        String ownerToken,
        Duration leaseTtl
) {}
