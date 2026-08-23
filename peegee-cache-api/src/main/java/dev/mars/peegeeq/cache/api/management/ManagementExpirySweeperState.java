package dev.mars.peegeeq.cache.api.management;

import java.time.Instant;

/** Runtime-owned expiry sweeper state. */
public record ManagementExpirySweeperState(
        boolean ownedByRuntime,
        boolean running,
        Instant lastSweepAt) {

    public ManagementExpirySweeperState {
        if (running && !ownedByRuntime) {
            throw new IllegalArgumentException("a running sweeper must be owned by the runtime");
        }
    }
}
