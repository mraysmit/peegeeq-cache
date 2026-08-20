package dev.mars.peegeeq.cache.api;

import dev.mars.peegeeq.cache.api.admin.AdminService;
import dev.mars.peegeeq.cache.api.cache.CacheService;
import dev.mars.peegeeq.cache.api.counter.CounterService;
import dev.mars.peegeeq.cache.api.lock.LockService;
import dev.mars.peegeeq.cache.api.management.ManagementService;
import dev.mars.peegeeq.cache.api.management.UnsupportedManagementService;
import dev.mars.peegeeq.cache.api.pubsub.PubSubService;
import dev.mars.peegeeq.cache.api.scan.ScanService;

public interface PeeGeeCache {

    CacheService cache();

    CounterService counters();

    LockService locks();

    ScanService scan();

    PubSubService pubSub();

    AdminService admin();

    /**
     * Returns the privileged management surface when this implementation supports it.
     * Unsupported implementations return a service whose asynchronous operations fail
     * with a typed management-capability exception.
     */
    default ManagementService management() {
        return UnsupportedManagementService.instance();
    }
}
