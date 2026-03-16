package dev.mars.peegeeq.cache.core.validation;

import java.time.Duration;

/**
 * Shared argument validation helpers for service and runtime modules.
 */
public final class CoreValidation {

    private CoreValidation() {
        throw new UnsupportedOperationException("Utility class");
    }

    public static <T> T requireNonNull(T value, String fieldName) {
        if (value == null) {
            throw new IllegalArgumentException(fieldName + " cannot be null");
        }
        return value;
    }

    public static String requireNonBlank(String value, String fieldName) {
        requireNonNull(value, fieldName);
        if (value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value;
    }

    public static Duration requirePositiveDuration(Duration value, String fieldName) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(fieldName + " must be > 0");
        }
        return value;
    }

    public static Duration requireOptionalPositiveDuration(Duration value, String fieldName) {
        if (value == null) {
            return null;
        }
        return requirePositiveDuration(value, fieldName);
    }
}
