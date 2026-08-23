package dev.mars.peegeeq.cache.api.management;

import dev.mars.peegeeq.cache.api.model.ValueType;

import java.util.HashSet;
import java.util.List;

/** Actor-bound entry bulk-delete selection using either a filter or exact versioned targets. */
public record EntryDeleteFilter(
        String namespace,
        String prefix,
        ValueType valueType,
        ManagementTtlFilter ttlFilter,
        List<VersionedCacheKeyTarget> targets) {
    public EntryDeleteFilter {
        namespace = ManagementModelValidation.boundedText(namespace, "namespace", 1, 128, false);
        String selectedNamespace = namespace;
        targets = List.copyOf(java.util.Objects.requireNonNull(targets, "targets"));
        boolean explicit = !targets.isEmpty();
        boolean filtered = ttlFilter != null;
        if (explicit == filtered) {
            throw new IllegalArgumentException("exactly one of filter or targets is required");
        }
        if (prefix != null) {
            prefix = ManagementModelValidation.boundedText(prefix, "prefix", 1, 1_024, false);
        }
        if (explicit && (prefix != null || valueType != null)) {
            throw new IllegalArgumentException("explicit selection cannot include filter fields");
        }
        if (targets.size() > 1_000) {
            throw new IllegalArgumentException("targets must contain at most 1000 entries");
        }
        if (targets.stream().anyMatch(target -> !selectedNamespace.equals(target.key().namespace()))) {
            throw new IllegalArgumentException("every selected target must belong to namespace");
        }
        if (new HashSet<>(targets).size() != targets.size()) {
            throw new IllegalArgumentException("targets must be unique");
        }
    }
}
