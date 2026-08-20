package dev.mars.peegeeq.cache.rest.protocol;

import java.util.Objects;

/** A normalized single-resource HTTP mutation precondition. */
public record ManagementPrecondition(Kind kind, Long version) {

    public enum Kind {
        NONE,
        EXACT_VERSION,
        REQUIRE_PRESENT,
        REQUIRE_ABSENT
    }

    public ManagementPrecondition {
        Objects.requireNonNull(kind, "kind");
        if ((kind == Kind.EXACT_VERSION) != (version != null)) {
            throw new IllegalArgumentException("only an exact-version precondition carries a version");
        }
        if (version != null && version < 0) {
            throw new IllegalArgumentException("version must be non-negative");
        }
    }

    public static ManagementPrecondition none() {
        return new ManagementPrecondition(Kind.NONE, null);
    }

    public static ManagementPrecondition exact(long version) {
        return new ManagementPrecondition(Kind.EXACT_VERSION, version);
    }

    public static ManagementPrecondition requirePresent() {
        return new ManagementPrecondition(Kind.REQUIRE_PRESENT, null);
    }

    public static ManagementPrecondition requireAbsent() {
        return new ManagementPrecondition(Kind.REQUIRE_ABSENT, null);
    }
}
