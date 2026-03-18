package dev.mars.peegeeq.cache.api.model;

/**
 * Immutable point-in-time snapshot of collected cache operation metrics.
 */
public record MetricsSnapshot(
        long cacheGets,
        long cacheHits,
        long cacheMisses,
        long cacheSets,
        long cacheSetsApplied,
        long cacheDeletes,
        long counterIncrements,
        long counterSets,
        long counterDeletes,
        long lockAcquires,
        long lockAcquiresGranted,
        long lockRenewals,
        long lockReleases,
        long publishes,
        long subscribes
) {
    public static MetricsSnapshot empty() {
        return new MetricsSnapshot(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
    }
}
