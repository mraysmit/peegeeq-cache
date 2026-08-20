package dev.mars.peegeeq.cache.api.management;

/** Metadata-only aggregate counts for one logical namespace. */
public record NamespaceStats(
        String namespace,
        long entryCount,
        long expiredEntryCount,
        long counterCount,
        long activeLockCount) {
    public NamespaceStats {
        namespace = ManagementModelValidation.boundedText(namespace, "namespace", 1, 128, false);
        ManagementModelValidation.nonNegativeVersion(entryCount, "entryCount");
        ManagementModelValidation.nonNegativeVersion(expiredEntryCount, "expiredEntryCount");
        ManagementModelValidation.nonNegativeVersion(counterCount, "counterCount");
        ManagementModelValidation.nonNegativeVersion(activeLockCount, "activeLockCount");
    }
}
