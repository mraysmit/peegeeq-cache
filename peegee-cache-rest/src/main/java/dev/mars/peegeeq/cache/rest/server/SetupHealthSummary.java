package dev.mars.peegeeq.cache.rest.server;

import java.time.Instant;
import java.util.Objects;

public record SetupHealthSummary(Status status, long latencyMillis, Instant checkedAt) {
    public SetupHealthSummary {
        status = Objects.requireNonNull(status, "status");
        if (latencyMillis < 0) {
            throw new IllegalArgumentException("latencyMillis must not be negative");
        }
        checkedAt = Objects.requireNonNull(checkedAt, "checkedAt");
    }

    public enum Status {
        UP,
        DOWN,
        DEGRADED
    }
}
