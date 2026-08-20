package dev.mars.peegeeq.cache.api.management;

import dev.mars.peegeeq.cache.api.model.LockKey;

import java.time.Instant;
import java.util.Objects;

/** Sensitive lock owner and version observed in one database snapshot. */
public record RevealedLockOwner(LockKey key, String ownerToken, long version, Instant revealedAt) {
    public RevealedLockOwner {
        Objects.requireNonNull(key, "key");
        ownerToken = ManagementModelValidation.boundedText(ownerToken, "ownerToken", 1, 1_024, false);
        ManagementModelValidation.nonNegativeVersion(version, "version");
        Objects.requireNonNull(revealedAt, "revealedAt");
    }
}
