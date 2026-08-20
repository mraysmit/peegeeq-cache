package dev.mars.peegeeq.cache.api.management;

/** Atomic outcome produced by a version-aware management mutation. */
public enum ManagementMutationOutcome {
    APPLIED,
    NOT_FOUND,
    VERSION_MISMATCH,
    CONDITION_NOT_MET
}
