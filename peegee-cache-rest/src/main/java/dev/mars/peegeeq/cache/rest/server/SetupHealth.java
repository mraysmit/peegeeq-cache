package dev.mars.peegeeq.cache.rest.server;

import java.time.Instant;
import java.util.Objects;

public record SetupHealth(
        SetupHealthSummary.Status status,
        boolean schemaReady,
        long latencyMillis,
        Instant checkedAt,
        String detail) {

    public SetupHealth {
        status = Objects.requireNonNull(status, "status");
        if (latencyMillis < 0) {
            throw new IllegalArgumentException("latencyMillis must not be negative");
        }
        checkedAt = Objects.requireNonNull(checkedAt, "checkedAt");
        detail = Objects.requireNonNull(detail, "detail");
        if (detail.length() > 512) {
            throw new IllegalArgumentException("detail must not exceed 512 characters");
        }
    }

    SetupHealthSummary summary() {
        return new SetupHealthSummary(status, latencyMillis, checkedAt);
    }
}
