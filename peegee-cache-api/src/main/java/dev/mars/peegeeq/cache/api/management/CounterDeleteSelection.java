package dev.mars.peegeeq.cache.api.management;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/** Explicit versioned counter targets for a setup-scoped bulk-delete preview. */
public record CounterDeleteSelection(List<VersionedCacheKeyTarget> targets) {
    public CounterDeleteSelection {
        targets = List.copyOf(Objects.requireNonNull(targets, "targets"));
        if (targets.isEmpty() || targets.size() > 1_000) {
            throw new IllegalArgumentException("targets must contain 1 to 1000 entries");
        }
        if (new HashSet<>(targets).size() != targets.size()) {
            throw new IllegalArgumentException("targets must be unique");
        }
    }
}
