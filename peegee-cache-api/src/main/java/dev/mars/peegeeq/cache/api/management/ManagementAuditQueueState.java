package dev.mars.peegeeq.cache.api.management;

/** Bounded durable-audit reservation state. */
public record ManagementAuditQueueState(
        long depth,
        long capacity,
        boolean acceptingMutations) {

    public ManagementAuditQueueState {
        ManagementModelValidation.nonNegativeVersion(depth, "depth");
        ManagementModelValidation.nonNegativeVersion(capacity, "capacity");
        if (depth > capacity) {
            throw new IllegalArgumentException("depth cannot exceed capacity");
        }
    }
}
