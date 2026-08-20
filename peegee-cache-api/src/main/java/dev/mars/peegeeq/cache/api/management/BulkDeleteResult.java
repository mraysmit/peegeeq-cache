package dev.mars.peegeeq.cache.api.management;

import java.util.List;
import java.util.Objects;

/** Bounded terminal bulk deletion summary. */
public record BulkDeleteResult(
        long processedCount,
        long deletedCount,
        long conflictCount,
        long missingCount,
        long failedCount,
        List<BulkDeleteConflict> conflicts) {
    public BulkDeleteResult {
        ManagementModelValidation.nonNegativeVersion(processedCount, "processedCount");
        ManagementModelValidation.nonNegativeVersion(deletedCount, "deletedCount");
        ManagementModelValidation.nonNegativeVersion(conflictCount, "conflictCount");
        ManagementModelValidation.nonNegativeVersion(missingCount, "missingCount");
        ManagementModelValidation.nonNegativeVersion(failedCount, "failedCount");
        conflicts = List.copyOf(Objects.requireNonNull(conflicts, "conflicts"));
        if (conflicts.size() > 1_000 || deletedCount + conflictCount + missingCount + failedCount != processedCount) {
            throw new IllegalArgumentException("bulk result counts are inconsistent or unbounded");
        }
    }
}
