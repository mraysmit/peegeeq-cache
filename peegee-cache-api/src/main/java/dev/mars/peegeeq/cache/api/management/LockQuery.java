package dev.mars.peegeeq.cache.api.management;

/** Bounded metadata-only query for active locks. */
public record LockQuery(
        String namespace,
        String prefix,
        LeaseState leaseState,
        String cursor,
        int limit) {

    public enum LeaseState { ACTIVE, EXPIRING_SOON }

    public LockQuery {
        ManagementModelValidation.page(limit, cursor);
    }
}
