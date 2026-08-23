package dev.mars.peegeeq.cache.api.management;

import dev.mars.peegeeq.cache.api.model.ValueType;

import java.util.Map;
import java.util.Objects;

/** Namespace aggregate plus value-type and TTL-state distributions. */
public record NamespaceDetails(
        NamespaceStats stats,
        Map<ValueType, Long> valueTypeCounts,
        Map<ManagementTtl.State, Long> ttlStateCounts,
        Map<ManagementTtlBucket, Long> ttlDistribution) {

    public NamespaceDetails {
        Objects.requireNonNull(stats, "stats");
        valueTypeCounts = Map.copyOf(Objects.requireNonNull(valueTypeCounts, "valueTypeCounts"));
        ttlStateCounts = Map.copyOf(Objects.requireNonNull(ttlStateCounts, "ttlStateCounts"));
        ttlDistribution = Map.copyOf(Objects.requireNonNull(ttlDistribution, "ttlDistribution"));
        valueTypeCounts.forEach((type, count) -> validateCount(type, count));
        ttlStateCounts.forEach((state, count) -> validateCount(state, count));
        ttlDistribution.forEach((bucket, count) -> validateCount(bucket, count));
        long stateTotal = ttlStateCounts.values().stream().mapToLong(Long::longValue).sum();
        long bucketTotal = ttlDistribution.values().stream().mapToLong(Long::longValue).sum();
        if (stateTotal != bucketTotal) {
            throw new IllegalArgumentException("TTL state and bucket distributions must have equal totals");
        }
    }

    private static void validateCount(Object key, Long count) {
        Objects.requireNonNull(key, "distribution key");
        if (count == null || count < 0) {
            throw new IllegalArgumentException("distribution counts must be non-negative");
        }
    }
}
