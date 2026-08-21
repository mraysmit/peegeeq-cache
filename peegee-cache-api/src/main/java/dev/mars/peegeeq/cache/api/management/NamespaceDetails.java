package dev.mars.peegeeq.cache.api.management;

import dev.mars.peegeeq.cache.api.model.ValueType;

import java.util.Map;
import java.util.Objects;

/** Namespace aggregate plus value-type and TTL-state distributions. */
public record NamespaceDetails(
        NamespaceStats stats,
        Map<ValueType, Long> valueTypeCounts,
        Map<ManagementTtl.State, Long> ttlStateCounts) {

    public NamespaceDetails {
        Objects.requireNonNull(stats, "stats");
        valueTypeCounts = Map.copyOf(Objects.requireNonNull(valueTypeCounts, "valueTypeCounts"));
        ttlStateCounts = Map.copyOf(Objects.requireNonNull(ttlStateCounts, "ttlStateCounts"));
        valueTypeCounts.forEach((type, count) -> validateCount(type, count));
        ttlStateCounts.forEach((state, count) -> validateCount(state, count));
    }

    private static void validateCount(Object key, Long count) {
        Objects.requireNonNull(key, "distribution key");
        if (count == null || count < 0) {
            throw new IllegalArgumentException("distribution counts must be non-negative");
        }
    }
}
