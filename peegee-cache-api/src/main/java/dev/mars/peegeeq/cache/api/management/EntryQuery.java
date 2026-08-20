package dev.mars.peegeeq.cache.api.management;

import dev.mars.peegeeq.cache.api.model.TtlState;
import dev.mars.peegeeq.cache.api.model.ValueType;

import java.util.Objects;

/** Bounded metadata-only entry query. */
public record EntryQuery(
        String namespace,
        String prefix,
        ValueType valueType,
        TtlState ttlState,
        Sort sort,
        String cursor,
        int limit) {

    public enum Sort { KEY_ASC }

    public EntryQuery {
        namespace = ManagementModelValidation.boundedText(namespace, "namespace", 1, 128, false);
        Objects.requireNonNull(sort, "sort");
        ManagementModelValidation.page(limit, cursor);
    }

    public static EntryQuery defaults(String namespace) {
        return new EntryQuery(namespace, null, null, null, Sort.KEY_ASC, null, 50);
    }
}
