package dev.mars.peegeeq.cache.api.management;

/** Current terminal or active state of a bounded operation aggregate. */
public enum ManagementOperationStatus {
    ACTIVE,
    COMPLETE,
    FAILED
}
