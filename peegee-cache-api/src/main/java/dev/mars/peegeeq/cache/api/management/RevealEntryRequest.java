package dev.mars.peegeeq.cache.api.management;

import dev.mars.peegeeq.cache.api.model.CacheKey;

import java.util.Objects;

/** Sensitive entry-reveal request with an optional bounded audit reason. */
public record RevealEntryRequest(CacheKey key, String reason) {
    public RevealEntryRequest {
        Objects.requireNonNull(key, "key");
        reason = ManagementModelValidation.optionalReason(reason);
    }
}
