package dev.mars.peegeeq.cache.api.management;

import java.util.Objects;

/** Permission- and implementation-aware Vert.x pool observations. */
public record ManagementRuntimePoolState(
        AvailableValue<Long> active,
        AvailableValue<Long> idle,
        AvailableValue<Long> pending,
        AvailableValue<Long> maximum) {

    public ManagementRuntimePoolState {
        validate(active, "active");
        validate(idle, "idle");
        validate(pending, "pending");
        validate(maximum, "maximum");
    }

    private static void validate(AvailableValue<Long> value, String name) {
        Objects.requireNonNull(value, name);
        if (value.availability() == Availability.AVAILABLE) {
            ManagementModelValidation.nonNegativeVersion(value.value(), name);
        }
    }
}
