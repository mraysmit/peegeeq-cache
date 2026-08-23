package dev.mars.peegeeq.cache.rest.security;

import java.time.Duration;
import java.util.Objects;

public record LocalTokenAuthenticationConfig(
        ManagementAuthenticationMode mode,
        Duration idleLifetime,
        Duration absoluteLifetime) {

    public static final Duration DEFAULT_IDLE_LIFETIME = Duration.ofMinutes(30);
    public static final Duration DEFAULT_ABSOLUTE_LIFETIME = Duration.ofHours(8);
    public static final Duration MAXIMUM_ABSOLUTE_LIFETIME = Duration.ofHours(24);

    public LocalTokenAuthenticationConfig {
        mode = Objects.requireNonNull(mode, "mode");
        idleLifetime = requirePositive(idleLifetime, "idleLifetime");
        absoluteLifetime = requirePositive(absoluteLifetime, "absoluteLifetime");
        if (mode != ManagementAuthenticationMode.LOCAL_TOKEN) {
            throw new IllegalArgumentException("Local token sessions require LOCAL_TOKEN mode");
        }
        if (absoluteLifetime.compareTo(MAXIMUM_ABSOLUTE_LIFETIME) > 0) {
            throw new IllegalArgumentException("Absolute session lifetime cannot exceed 24 hours");
        }
        if (idleLifetime.compareTo(absoluteLifetime) > 0) {
            throw new IllegalArgumentException("Idle lifetime cannot exceed absolute lifetime");
        }
    }

    public static LocalTokenAuthenticationConfig defaults() {
        return new LocalTokenAuthenticationConfig(
                ManagementAuthenticationMode.LOCAL_TOKEN,
                DEFAULT_IDLE_LIFETIME,
                DEFAULT_ABSOLUTE_LIFETIME);
    }

    private static Duration requirePositive(Duration value, String field) {
        Duration duration = Objects.requireNonNull(value, field);
        if (duration.isZero() || duration.isNegative()) {
            throw new IllegalArgumentException(field + " must be positive");
        }
        return duration;
    }
}
