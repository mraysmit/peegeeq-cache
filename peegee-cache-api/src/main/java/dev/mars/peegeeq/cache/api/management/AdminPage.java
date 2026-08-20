package dev.mars.peegeeq.cache.api.management;

import java.util.List;
import java.util.Objects;

/** Immutable keyset page returned by bounded management queries. */
public record AdminPage<T>(List<T> items, String nextCursor, boolean hasMore) {

    public AdminPage {
        items = List.copyOf(Objects.requireNonNull(items, "items"));
        if (hasMore != (nextCursor != null)) {
            throw new IllegalArgumentException("hasMore must be true exactly when nextCursor is present");
        }
        if (nextCursor != null && nextCursor.isBlank()) {
            throw new IllegalArgumentException("nextCursor must be non-blank when present");
        }
    }
}
