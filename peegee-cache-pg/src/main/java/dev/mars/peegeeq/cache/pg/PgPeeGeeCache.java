package dev.mars.peegeeq.cache.pg;

import dev.mars.peegeeq.cache.api.PeeGeeCache;
import dev.mars.peegeeq.cache.api.admin.AdminService;
import dev.mars.peegeeq.cache.api.cache.CacheService;
import dev.mars.peegeeq.cache.api.counter.CounterService;
import dev.mars.peegeeq.cache.api.lock.LockService;
import dev.mars.peegeeq.cache.api.management.ManagementService;
import dev.mars.peegeeq.cache.api.management.UnsupportedManagementService;
import dev.mars.peegeeq.cache.api.pubsub.PubSubService;
import dev.mars.peegeeq.cache.api.scan.ScanService;

import java.util.Objects;

/**
 * Simple facade wiring service interfaces into the {@link PeeGeeCache} surface.
 */
public final class PgPeeGeeCache implements PeeGeeCache {

    private final CacheService cacheService;
    private final CounterService counterService;
    private final LockService lockService;
    private final ScanService scanService;
    private final PubSubService pubSubService;
    private final AdminService adminService;
    private final ManagementService managementService;

    public PgPeeGeeCache(
            CacheService cacheService,
            CounterService counterService,
            LockService lockService,
            ScanService scanService,
            PubSubService pubSubService,
            AdminService adminService
    ) {
        this(cacheService, counterService, lockService, scanService, pubSubService, adminService,
                UnsupportedManagementService.instance());
    }

    public PgPeeGeeCache(
            CacheService cacheService,
            CounterService counterService,
            LockService lockService,
            ScanService scanService,
            PubSubService pubSubService,
            AdminService adminService,
            ManagementService managementService
    ) {
        this.cacheService = Objects.requireNonNull(cacheService, "cacheService");
        this.counterService = Objects.requireNonNull(counterService, "counterService");
        this.lockService = Objects.requireNonNull(lockService, "lockService");
        this.scanService = Objects.requireNonNull(scanService, "scanService");
        this.pubSubService = Objects.requireNonNull(pubSubService, "pubSubService");
        this.adminService = Objects.requireNonNull(adminService, "adminService");
        this.managementService = Objects.requireNonNull(managementService, "managementService");
    }

    @Override
    public CacheService cache() {
        return cacheService;
    }

    @Override
    public CounterService counters() {
        return counterService;
    }

    @Override
    public LockService locks() {
        return lockService;
    }

    @Override
    public ScanService scan() {
        return scanService;
    }

    @Override
    public PubSubService pubSub() {
        return pubSubService;
    }

    @Override
    public AdminService admin() {
        return adminService;
    }

    @Override
    public ManagementService management() {
        return managementService;
    }
}
