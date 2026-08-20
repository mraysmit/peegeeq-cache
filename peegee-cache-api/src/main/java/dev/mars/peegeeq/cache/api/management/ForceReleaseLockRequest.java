package dev.mars.peegeeq.cache.api.management;

import dev.mars.peegeeq.cache.api.model.LockKey;

import java.util.Objects;

/** Exact-version forced release guarded by exact decoded-key confirmation. */
public record ForceReleaseLockRequest(
        LockKey key,
        long expectedVersion,
        String confirmationKey,
        String reason) {
    public ForceReleaseLockRequest {
        Objects.requireNonNull(key, "key");
        ManagementModelValidation.nonNegativeVersion(expectedVersion, "expectedVersion");
        Objects.requireNonNull(confirmationKey, "confirmationKey");
        if (!key.key().equals(confirmationKey)) {
            throw new IllegalArgumentException("confirmationKey must exactly match the decoded lock key");
        }
        reason = ManagementModelValidation.optionalReason(reason);
    }
}
