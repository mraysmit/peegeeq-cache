package dev.mars.peegeeq.cache.api.management;

import java.util.Objects;

/** Atomic mutation outcome with the resulting version and optional representation. */
public record VersionedMutationResult<T>(
        ManagementMutationOutcome outcome,
        Long resultingVersion,
        T representation) {

    public VersionedMutationResult {
        Objects.requireNonNull(outcome, "outcome");
        if (outcome == ManagementMutationOutcome.APPLIED) {
            if (resultingVersion == null || resultingVersion < 0) {
                throw new IllegalArgumentException("an applied mutation requires a non-negative resulting version");
            }
        } else if (resultingVersion != null || representation != null) {
            throw new IllegalArgumentException("a non-applied mutation cannot carry resulting state");
        }
    }

    public static <T> VersionedMutationResult<T> applied(long version, T representation) {
        return new VersionedMutationResult<>(ManagementMutationOutcome.APPLIED, version, representation);
    }

    public static <T> VersionedMutationResult<T> notFound() {
        return new VersionedMutationResult<>(ManagementMutationOutcome.NOT_FOUND, null, null);
    }

    public static <T> VersionedMutationResult<T> versionMismatch() {
        return new VersionedMutationResult<>(ManagementMutationOutcome.VERSION_MISMATCH, null, null);
    }

    public static <T> VersionedMutationResult<T> conditionNotMet() {
        return new VersionedMutationResult<>(ManagementMutationOutcome.CONDITION_NOT_MET, null, null);
    }
}
