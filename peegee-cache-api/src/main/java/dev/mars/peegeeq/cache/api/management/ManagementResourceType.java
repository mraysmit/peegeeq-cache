package dev.mars.peegeeq.cache.api.management;

/** Bounded resource category for management audit events. */
public enum ManagementResourceType {
    NAMESPACE,
    ENTRY,
    COUNTER,
    LOCK,
    BULK_SELECTION
}
