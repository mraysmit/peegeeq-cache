package dev.mars.peegeeq.cache.rest.security;

import java.time.Duration;
import java.util.Objects;

public record RateLimitRule(int actorLimit, int sourceLimit, Duration window) {

    public RateLimitRule {
        if (actorLimit < 1 || sourceLimit < 1) {
            throw new IllegalArgumentException("Rate limits must be positive");
        }
        window = Objects.requireNonNull(window, "window");
        if (window.isZero() || window.isNegative()) {
            throw new IllegalArgumentException("Rate-limit window must be positive");
        }
    }
}
