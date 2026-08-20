package dev.mars.peegeeq.cache.api.management;

import java.time.Instant;
import java.util.Objects;

/** Expiry backlog observations with permission-aware lag. */
public record ExpiryStats(
        Instant observedAt,
        long expiredEntryCount,
        long expiredCounterCount,
        AvailableValue<Long> oldestLagMillis) {
    public ExpiryStats {
        Objects.requireNonNull(observedAt, "observedAt");
        ManagementModelValidation.nonNegativeVersion(expiredEntryCount, "expiredEntryCount");
        ManagementModelValidation.nonNegativeVersion(expiredCounterCount, "expiredCounterCount");
        Objects.requireNonNull(oldestLagMillis, "oldestLagMillis");
    }
}
