package dev.mars.peegeeq.cache.rest.protocol;

import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/** Complete normalized query scope authenticated by a management cursor. */
public record ManagementCursorScope(
        String endpoint,
        String setupId,
        String namespace,
        Map<String, String> filters,
        String sort) {

    public ManagementCursorScope {
        endpoint = required(endpoint, "endpoint");
        setupId = required(setupId, "setupId");
        sort = required(sort, "sort");
        if (namespace != null && namespace.isEmpty()) {
            throw new IllegalArgumentException("namespace must be null or non-empty");
        }
        Objects.requireNonNull(filters, "filters");
        if (filters.size() > 32) {
            throw new IllegalArgumentException("filters must contain at most 32 entries");
        }
        TreeMap<String, String> normalized = new TreeMap<>();
        filters.forEach((name, value) -> normalized.put(
                required(name, "filter name"), Objects.requireNonNull(value, "filter value")));
        filters = Map.copyOf(normalized);
    }

    private static String required(String value, String label) {
        Objects.requireNonNull(value, label);
        if (value.isEmpty()) {
            throw new IllegalArgumentException(label + " must not be empty");
        }
        return value;
    }
}
