package dev.mars.peegeeq.cache.api.management;

import java.time.Instant;
import java.util.Objects;

/** Permission-aware PostgreSQL size observations. */
public record DatabaseStats(
        Instant observedAt,
        AvailableValue<Long> databaseBytes,
        AvailableValue<Long> schemaBytes) {
    public DatabaseStats {
        Objects.requireNonNull(observedAt, "observedAt");
        Objects.requireNonNull(databaseBytes, "databaseBytes");
        Objects.requireNonNull(schemaBytes, "schemaBytes");
    }
}
