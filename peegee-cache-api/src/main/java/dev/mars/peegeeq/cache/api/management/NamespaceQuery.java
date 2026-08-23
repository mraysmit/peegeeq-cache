package dev.mars.peegeeq.cache.api.management;

import java.util.Objects;

/** Bounded namespace query with an explicit supported sort. */
public record NamespaceQuery(String prefix, Status status, Sort sort, String cursor, int limit) {

    public enum Status {
        ALL,
        HEALTHY,
        EXPIRED_BACKLOG,
        ACTIVE_LOCKS
    }

    public enum Sort {
        NAMESPACE_ASC,
        ENTRY_COUNT_DESC
    }

    public NamespaceQuery {
        sort = Objects.requireNonNull(sort, "sort");
        ManagementModelValidation.page(limit, cursor);
    }

    public static NamespaceQuery defaults() {
        return new NamespaceQuery(null, null, Sort.NAMESPACE_ASC, null, 50);
    }
}
