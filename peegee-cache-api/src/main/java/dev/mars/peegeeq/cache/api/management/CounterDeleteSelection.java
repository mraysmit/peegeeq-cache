package dev.mars.peegeeq.cache.api.management;

import dev.mars.peegeeq.cache.api.model.CacheKey;

import java.util.List;

/** Counter bulk-delete selection using either a literal prefix or exact keys. */
public record CounterDeleteSelection(String namespace, String prefix, List<CacheKey> keys) {
    public CounterDeleteSelection {
        EntryDeleteFilter validated = new EntryDeleteFilter(namespace, prefix, keys);
        namespace = validated.namespace();
        prefix = validated.prefix();
        keys = validated.keys();
    }
}
