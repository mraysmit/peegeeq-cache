package dev.mars.peegeeq.cache.api.management;

import java.util.Objects;

/** Low-cardinality process-local operation aggregate. */
public record ManagementOperationAggregate(
        String operation,
        ManagementOperationStatus status,
        long count,
        long errorCount,
        long latencyMillis) {

    public ManagementOperationAggregate {
        operation = ManagementModelValidation.boundedText(
                operation, "operation", 1, 128, true);
        Objects.requireNonNull(status, "status");
        ManagementModelValidation.nonNegativeVersion(count, "count");
        ManagementModelValidation.nonNegativeVersion(errorCount, "errorCount");
        ManagementModelValidation.nonNegativeVersion(latencyMillis, "latencyMillis");
        if (errorCount > count) {
            throw new IllegalArgumentException("errorCount cannot exceed count");
        }
    }
}
