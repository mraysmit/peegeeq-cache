package dev.mars.peegeeq.cache.api.management;

/** Effective setup-specific limits exposed to management clients. */
public record ManagementLimits(
        int maximumPageSize,
        int maximumBulkTargets,
        long maximumValueBytes,
        int maximumPubSubBufferEntries,
        int maximumSubscriptionsPerActor) {
    public ManagementLimits {
        if (maximumPageSize < 1 || maximumPageSize > 200) {
            throw new IllegalArgumentException("maximumPageSize must be between 1 and 200");
        }
        if (maximumBulkTargets < 1 || maximumValueBytes < 1
                || maximumPubSubBufferEntries < 1 || maximumSubscriptionsPerActor < 1) {
            throw new IllegalArgumentException("management limits must be positive");
        }
    }

    public static ManagementLimits defaults() {
        return new ManagementLimits(200, 1_000, 10L * 1024 * 1024, 500, 5);
    }
}
