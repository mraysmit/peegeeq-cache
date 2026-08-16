package dev.mars.peegeeq.cache.observability.health;

import java.time.Duration;
import java.time.Instant;

/** Point-in-time database and runtime readiness result. */
public record CacheHealth(Status status, boolean schemaReady, Duration latency,
                          Instant checkedAt, String detail) {

    public enum Status {
        UP,
        DOWN,
        STOPPED
    }
}
