package dev.mars.peegeeq.cache.api.model;

public record ScanRequest(
        String namespace,
        String prefix,
        String cursor,
        int limit,
        boolean includeValues,
        boolean includeExpired
) {}
