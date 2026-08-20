package dev.mars.peegeeq.cache.api.management;

import dev.mars.peegeeq.cache.api.model.CacheKey;

import java.util.List;

/** Actor-bound entry bulk-delete selection using either a literal prefix or exact keys. */
public record EntryDeleteFilter(String namespace, String prefix, List<CacheKey> keys) {
    public EntryDeleteFilter {
        namespace = ManagementModelValidation.boundedText(namespace, "namespace", 1, 128, false);
        String selectedNamespace = namespace;
        keys = List.copyOf(java.util.Objects.requireNonNull(keys, "keys"));
        boolean hasPrefix = prefix != null;
        boolean hasKeys = !keys.isEmpty();
        if (hasPrefix == hasKeys) {
            throw new IllegalArgumentException("exactly one of prefix or keys is required");
        }
        if (prefix != null && prefix.isEmpty()) {
            throw new IllegalArgumentException("prefix must not be empty");
        }
        if (keys.size() > 1_000) {
            throw new IllegalArgumentException("keys must contain at most 1000 entries");
        }
        if (keys.stream().anyMatch(key -> !selectedNamespace.equals(key.namespace()))) {
            throw new IllegalArgumentException("every selected key must belong to namespace");
        }
    }
}
