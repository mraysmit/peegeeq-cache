package dev.mars.peegeeq.cache.api.management;

import java.util.List;
import java.util.Objects;

/** Immutable keyset page from the bounded current-process activity buffer. */
public record ManagementActivityPage(
        List<ManagementActivityEvent> items,
        String nextAfter,
        boolean hasMore) {

    public ManagementActivityPage {
        items = List.copyOf(Objects.requireNonNull(items, "items"));
        if (items.size() > 200) {
            throw new IllegalArgumentException("activity page must contain at most 200 items");
        }
        if (hasMore != (nextAfter != null)) {
            throw new IllegalArgumentException("hasMore must be true exactly when nextAfter is present");
        }
        nextAfter = nextAfter == null ? null
                : ManagementModelValidation.boundedText(nextAfter, "nextAfter", 1, 128, false);
    }
}
