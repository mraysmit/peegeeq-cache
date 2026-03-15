package dev.mars.peegeeq.cache.api.model;

import java.util.List;

public record ScanResult(
        List<CacheEntry> entries,
        String nextCursor,
        boolean hasMore
) {}
