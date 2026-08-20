package dev.mars.peegeeq.cache.api.management;

import dev.mars.peegeeq.cache.api.model.LockKey;

import java.time.Instant;
import java.util.Objects;

/** Active-lock metadata that deliberately cannot carry the owner token. */
public record ManagementLockMetadata(
        LockKey key,
        long fencingToken,
        long version,
        Instant createdAt,
        Instant updatedAt,
        Instant leaseExpiresAt,
        long leaseRemainingMillis) {
    public ManagementLockMetadata {
        Objects.requireNonNull(key, "key");
        ManagementModelValidation.nonNegativeVersion(fencingToken, "fencingToken");
        ManagementModelValidation.nonNegativeVersion(version, "version");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        Objects.requireNonNull(leaseExpiresAt, "leaseExpiresAt");
        ManagementModelValidation.nonNegativeVersion(leaseRemainingMillis, "leaseRemainingMillis");
    }
}
