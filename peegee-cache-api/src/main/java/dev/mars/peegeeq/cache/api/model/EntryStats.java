package dev.mars.peegeeq.cache.api.model;

/**
 * Namespace-level entry statistics from the database.
 */
public record EntryStats(
        String namespace,
        long cacheEntryCount,
        long counterCount,
        long activeLockCount
) {}
