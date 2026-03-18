package dev.mars.peegeeq.cache.api.admin;

import dev.mars.peegeeq.cache.api.model.EntryStats;
import dev.mars.peegeeq.cache.api.model.MetricsSnapshot;
import io.vertx.core.Future;

/**
 * Administrative and operational inspection surface for peegee-cache.
 * <p>
 * Provides database-level namespace statistics and in-memory operation metrics.
 */
public interface AdminService {

    /**
     * Query namespace-level entry counts from the database.
     * Only live (non-expired) entries are counted.
     */
    Future<EntryStats> entryStats(String namespace);

    /**
     * Return a point-in-time snapshot of collected operation metrics.
     */
    MetricsSnapshot metrics();
}
