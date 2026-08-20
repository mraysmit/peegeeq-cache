package dev.mars.peegeeq.cache.rest.protocol;

import java.util.Objects;

/** A typed, complete keyset position; it is data and never executable SQL. */
public record ManagementCursorPosition(Kind kind, Long entryCount, String identifier) {

    public enum Kind {
        IDENTIFIER,
        ENTRY_COUNT_DESC_NAMESPACE_ASC
    }

    public ManagementCursorPosition {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(identifier, "identifier");
        if (identifier.isEmpty()) {
            throw new IllegalArgumentException("identifier must not be empty");
        }
        if ((kind == Kind.ENTRY_COUNT_DESC_NAMESPACE_ASC) != (entryCount != null)) {
            throw new IllegalArgumentException("entryCount is required only for the composite namespace position");
        }
        if (entryCount != null && entryCount < 0) {
            throw new IllegalArgumentException("entryCount must be non-negative");
        }
    }

    public static ManagementCursorPosition identifier(String identifier) {
        return new ManagementCursorPosition(Kind.IDENTIFIER, null, identifier);
    }

    public static ManagementCursorPosition entryCount(long entryCount, String namespace) {
        return new ManagementCursorPosition(Kind.ENTRY_COUNT_DESC_NAMESPACE_ASC, entryCount, namespace);
    }
}
