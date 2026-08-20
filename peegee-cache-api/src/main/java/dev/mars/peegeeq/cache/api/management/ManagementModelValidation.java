package dev.mars.peegeeq.cache.api.management;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;

final class ManagementModelValidation {

    private ManagementModelValidation() {
    }

    static void page(int limit, String cursor) {
        if (limit < 1 || limit > 200) {
            throw new IllegalArgumentException("limit must be between 1 and 200");
        }
        if (cursor != null && cursor.isBlank()) {
            throw new IllegalArgumentException("cursor must be non-blank when present");
        }
    }

    static String boundedText(String value, String label, int minimum, int maximum, boolean trim) {
        Objects.requireNonNull(value, label);
        String normalized = trim ? value.trim() : value;
        int bytes = normalized.getBytes(StandardCharsets.UTF_8).length;
        if (bytes < minimum || bytes > maximum || normalized.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(label + " must contain " + minimum + "-" + maximum
                    + " printable UTF-8 bytes");
        }
        return normalized;
    }

    static void nonNegativeVersion(long version, String label) {
        if (version < 0) {
            throw new IllegalArgumentException(label + " must be non-negative");
        }
    }

    static Duration positiveDuration(Duration duration, String label) {
        Objects.requireNonNull(duration, label);
        if (duration.isZero() || duration.isNegative()) {
            throw new IllegalArgumentException(label + " must be positive");
        }
        return duration;
    }

    static String optionalReason(String reason) {
        return reason == null ? null : boundedText(reason, "reason", 3, 240, true);
    }
}
