package dev.mars.peegeeq.cache.api.management;

import java.time.Instant;
import java.util.Objects;

/** Metadata-only aggregate counts for one logical namespace. */
public record NamespaceStats(
        String namespace,
        long liveEntryCount,
        long liveCounterCount,
        long activeLockCount,
        long expiringEntryCount,
        long expiredEntryCount,
        long estimatedStorageBytes,
        Instant observedAt) {
    public NamespaceStats {
        namespace = ManagementModelValidation.boundedText(namespace, "namespace", 1, 128, false);
        ManagementModelValidation.nonNegativeVersion(liveEntryCount, "liveEntryCount");
        ManagementModelValidation.nonNegativeVersion(liveCounterCount, "liveCounterCount");
        ManagementModelValidation.nonNegativeVersion(activeLockCount, "activeLockCount");
        ManagementModelValidation.nonNegativeVersion(expiringEntryCount, "expiringEntryCount");
        ManagementModelValidation.nonNegativeVersion(expiredEntryCount, "expiredEntryCount");
        ManagementModelValidation.nonNegativeVersion(estimatedStorageBytes, "estimatedStorageBytes");
        Objects.requireNonNull(observedAt, "observedAt");
    }
}
