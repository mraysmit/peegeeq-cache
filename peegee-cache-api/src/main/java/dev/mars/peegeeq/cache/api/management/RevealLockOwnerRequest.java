package dev.mars.peegeeq.cache.api.management;

import dev.mars.peegeeq.cache.api.model.LockKey;

import java.util.Objects;

/** Sensitive lock-owner reveal request with an optional bounded audit reason. */
public record RevealLockOwnerRequest(LockKey key, String reason) {
    public RevealLockOwnerRequest {
        Objects.requireNonNull(key, "key");
        reason = ManagementModelValidation.optionalReason(reason);
    }
}
