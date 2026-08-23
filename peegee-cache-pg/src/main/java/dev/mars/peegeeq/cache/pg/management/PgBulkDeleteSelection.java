package dev.mars.peegeeq.cache.pg.management;

import dev.mars.peegeeq.cache.api.management.VersionedCacheKeyTarget;

import java.util.List;

record PgBulkDeleteSelection(List<VersionedCacheKeyTarget> targets, long totalBytes) {
    PgBulkDeleteSelection {
        targets = List.copyOf(targets);
    }
}
