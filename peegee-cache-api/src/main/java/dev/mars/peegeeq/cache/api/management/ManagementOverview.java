package dev.mars.peegeeq.cache.api.management;

import dev.mars.peegeeq.cache.api.model.ValueType;

import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Bounded database-wide aggregate used by the management overview route. */
public record ManagementOverview(
        Instant observedAt,
        long namespaceCount,
        long liveEntryCount,
        long liveCounterCount,
        long activeLockCount,
        long expiredEntryCount,
        long expiredCounterCount,
        AvailableValue<Long> schemaBytes,
        AvailableValue<Long> oldestExpiredRowLagMillis,
        Map<ValueType, Long> valueTypeCounts,
        List<NamespaceStats> topNamespaces) {

    public ManagementOverview {
        Objects.requireNonNull(observedAt, "observedAt");
        ManagementModelValidation.nonNegativeVersion(namespaceCount, "namespaceCount");
        ManagementModelValidation.nonNegativeVersion(liveEntryCount, "liveEntryCount");
        ManagementModelValidation.nonNegativeVersion(liveCounterCount, "liveCounterCount");
        ManagementModelValidation.nonNegativeVersion(activeLockCount, "activeLockCount");
        ManagementModelValidation.nonNegativeVersion(expiredEntryCount, "expiredEntryCount");
        ManagementModelValidation.nonNegativeVersion(expiredCounterCount, "expiredCounterCount");
        validateAvailableNonNegative(Objects.requireNonNull(schemaBytes, "schemaBytes"), "schemaBytes");
        validateAvailableNonNegative(
                Objects.requireNonNull(oldestExpiredRowLagMillis, "oldestExpiredRowLagMillis"),
                "oldestExpiredRowLagMillis");
        EnumMap<ValueType, Long> counts = new EnumMap<>(ValueType.class);
        Objects.requireNonNull(valueTypeCounts, "valueTypeCounts").forEach((type, count) -> {
            Objects.requireNonNull(type, "valueTypeCounts key");
            ManagementModelValidation.nonNegativeVersion(
                    Objects.requireNonNull(count, "valueTypeCounts value"), "valueTypeCount");
            counts.put(type, count);
        });
        valueTypeCounts = Map.copyOf(counts);
        topNamespaces = List.copyOf(Objects.requireNonNull(topNamespaces, "topNamespaces"));
        if (topNamespaces.size() > 20) {
            throw new IllegalArgumentException("topNamespaces must contain at most 20 items");
        }
    }

    private static void validateAvailableNonNegative(AvailableValue<Long> value, String name) {
        if (value.availability() == Availability.AVAILABLE) {
            ManagementModelValidation.nonNegativeVersion(value.value(), name);
        }
    }
}
