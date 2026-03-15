package dev.mars.peegeeq.cache.api.model;

public record LockReleaseRequest(
        LockKey key,
        String ownerToken
) {}
