package dev.mars.peegeeq.cache.api.management;

/** Independently reportable management service capabilities. */
public enum ManagementCapability {
    NAMESPACE_INSPECTION,
    ENTRY_INSPECTION,
    ENTRY_REVEAL,
    ENTRY_MUTATION,
    COUNTER_INSPECTION,
    COUNTER_MUTATION,
    LOCK_INSPECTION,
    LOCK_REVEAL,
    FORCE_LOCK_RELEASE,
    BULK_DELETE,
    DATABASE_MONITORING,
    EXPIRY_MONITORING
}
