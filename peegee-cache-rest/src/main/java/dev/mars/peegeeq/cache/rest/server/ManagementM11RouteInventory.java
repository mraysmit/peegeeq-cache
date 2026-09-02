package dev.mars.peegeeq.cache.rest.server;

import java.util.Collections;
import java.util.Set;
import java.util.TreeSet;

/** Backend-service operation IDs added by the complete functionality-coverage milestone. */
public final class ManagementM11RouteInventory {

    private static final Set<String> OPERATION_IDS = Collections.unmodifiableSet(
            new TreeSet<>(Set.of(
                    "checkEntryExists",
                    "batchGetEntries",
                    "batchSetEntries",
                    "scanEntries",
                    "acquireLock",
                    "renewLock",
                    "releaseLock",
                    "checkLockOwnership",
                    "getCacheMetrics")));

    private ManagementM11RouteInventory() {
    }

    public static Set<String> operationIds() {
        return OPERATION_IDS;
    }
}
