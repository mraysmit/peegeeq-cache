package dev.mars.peegeeq.cache.api.model;

public record CacheSetResult(
        boolean applied,
        long newVersion,
        CacheEntry previousEntry
) {}
