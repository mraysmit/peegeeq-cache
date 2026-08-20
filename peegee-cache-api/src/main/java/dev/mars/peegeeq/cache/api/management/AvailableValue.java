package dev.mars.peegeeq.cache.api.management;

import java.util.Objects;

/** Prevents an unavailable monitoring value from being misrepresented as zero. */
public record AvailableValue<T>(Availability availability, T value, String reason) {
    public AvailableValue {
        Objects.requireNonNull(availability, "availability");
        if (availability == Availability.AVAILABLE) {
            if (value == null || reason != null) {
                throw new IllegalArgumentException("available values require value and no reason");
            }
        } else {
            if (value != null) {
                throw new IllegalArgumentException("unavailable values cannot carry a value");
            }
            reason = ManagementModelValidation.boundedText(reason, "reason", 1, 240, true);
        }
    }

    public static <T> AvailableValue<T> available(T value) {
        return new AvailableValue<>(Availability.AVAILABLE, value, null);
    }

    public static <T> AvailableValue<T> unavailable(String reason) {
        return new AvailableValue<>(Availability.UNAVAILABLE, null, reason);
    }
}
