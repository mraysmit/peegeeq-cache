package dev.mars.peegeeq.cache.rest.server;

import java.time.Instant;
import java.util.Objects;

public record SetupDetails(
        SetupSummary setup,
        String migrationVersion,
        SetupRuntimeSummary runtime,
        Instant registeredAt,
        Instant connectedAt) {

    public SetupDetails {
        setup = Objects.requireNonNull(setup, "setup");
        migrationVersion = Objects.requireNonNull(migrationVersion, "migrationVersion");
        runtime = Objects.requireNonNull(runtime, "runtime");
        registeredAt = Objects.requireNonNull(registeredAt, "registeredAt");
    }
}
