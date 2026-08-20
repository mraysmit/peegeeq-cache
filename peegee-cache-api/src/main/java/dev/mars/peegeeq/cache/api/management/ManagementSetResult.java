package dev.mars.peegeeq.cache.api.management;

import java.util.Objects;

/** Atomic cache-set result that additionally distinguishes creation from update. */
public record ManagementSetResult(
        ManagementMutationOutcome outcome,
        boolean created,
        Long resultingVersion,
        ManagementEntryMetadata representation) {

    public ManagementSetResult {
        Objects.requireNonNull(outcome, "outcome");
        if (outcome == ManagementMutationOutcome.APPLIED) {
            if (resultingVersion == null || resultingVersion < 0 || representation == null) {
                throw new IllegalArgumentException("an applied set requires version and representation");
            }
        } else if (created || resultingVersion != null || representation != null) {
            throw new IllegalArgumentException("a non-applied set cannot carry created or resulting state");
        }
    }

    public static ManagementSetResult created(long version, ManagementEntryMetadata representation) {
        return new ManagementSetResult(ManagementMutationOutcome.APPLIED, true, version, representation);
    }

    public static ManagementSetResult updated(long version, ManagementEntryMetadata representation) {
        return new ManagementSetResult(ManagementMutationOutcome.APPLIED, false, version, representation);
    }

    public static ManagementSetResult notApplied(ManagementMutationOutcome outcome) {
        if (outcome == ManagementMutationOutcome.APPLIED) {
            throw new IllegalArgumentException("use created or updated for an applied set");
        }
        return new ManagementSetResult(outcome, false, null, null);
    }
}
