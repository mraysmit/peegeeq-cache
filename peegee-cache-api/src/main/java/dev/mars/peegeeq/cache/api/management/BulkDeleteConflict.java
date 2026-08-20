package dev.mars.peegeeq.cache.api.management;

import java.util.Objects;

/** Safe per-key bulk deletion conflict without value or failure-message data. */
public record BulkDeleteConflict(String key, Reason reason) {
    public enum Reason { VERSION_CHANGED, NOT_FOUND, FAILED }
    public BulkDeleteConflict {
        key = ManagementModelValidation.boundedText(key, "key", 1, 1_024, false);
        Objects.requireNonNull(reason, "reason");
    }
}
