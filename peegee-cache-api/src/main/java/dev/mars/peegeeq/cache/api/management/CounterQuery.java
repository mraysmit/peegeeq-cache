package dev.mars.peegeeq.cache.api.management;

import java.util.Objects;

/** Bounded metadata query for counters. */
public record CounterQuery(
        String namespace,
        String prefix,
        ManagementTtlFilter ttlState,
        Sort sort,
        String cursor,
        int limit) {

    public enum Sort { KEY_ASC }

    public CounterQuery {
        Objects.requireNonNull(sort, "sort");
        ManagementModelValidation.page(limit, cursor);
    }
}
