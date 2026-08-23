package dev.mars.peegeeq.cache.api.management;

import java.time.Instant;
import java.util.Objects;

/** Permission-aware PostgreSQL monitoring snapshot for one cache setup. */
public record ManagementDatabaseMonitoring(
        Instant observedAt,
        AvailableValue<Long> tableBytes,
        AvailableValue<Long> indexBytes,
        AvailableValue<Long> schemaBytes,
        AvailableValue<Long> liveRows,
        AvailableValue<Long> expiredRows,
        AvailableValue<Long> deadTuples,
        Instant lastVacuumAt,
        Instant lastAutovacuumAt,
        AvailableValue<Long> databaseConnections,
        AvailableValue<Long> cacheConnections,
        long expiryBacklog,
        Long oldestExpiredRowLagMillis) {

    public ManagementDatabaseMonitoring {
        Objects.requireNonNull(observedAt, "observedAt");
        validateAvailableNonNegative(tableBytes, "tableBytes");
        validateAvailableNonNegative(indexBytes, "indexBytes");
        validateAvailableNonNegative(schemaBytes, "schemaBytes");
        validateAvailableNonNegative(liveRows, "liveRows");
        validateAvailableNonNegative(expiredRows, "expiredRows");
        validateAvailableNonNegative(deadTuples, "deadTuples");
        validateAvailableNonNegative(databaseConnections, "databaseConnections");
        validateAvailableNonNegative(cacheConnections, "cacheConnections");
        ManagementModelValidation.nonNegativeVersion(expiryBacklog, "expiryBacklog");
        if (oldestExpiredRowLagMillis != null) {
            ManagementModelValidation.nonNegativeVersion(
                    oldestExpiredRowLagMillis, "oldestExpiredRowLagMillis");
        }
    }

    private static void validateAvailableNonNegative(AvailableValue<Long> value, String name) {
        Objects.requireNonNull(value, name);
        if (value.availability() == Availability.AVAILABLE) {
            ManagementModelValidation.nonNegativeVersion(value.value(), name);
        }
    }
}
