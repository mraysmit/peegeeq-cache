package dev.mars.peegeeq.cache.api.management;

import dev.mars.peegeeq.cache.api.PeeGeeCache;
import dev.mars.peegeeq.cache.api.admin.AdminService;
import dev.mars.peegeeq.cache.api.cache.CacheService;
import dev.mars.peegeeq.cache.api.counter.CounterService;
import dev.mars.peegeeq.cache.api.lock.LockService;
import dev.mars.peegeeq.cache.api.pubsub.PubSubService;
import dev.mars.peegeeq.cache.api.scan.ScanService;
import io.vertx.core.Future;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ManagementServiceFallbackTest {

    @Test
    void legacyImplementationsReceiveBackwardCompatibleUnsupportedDefault() {
        PeeGeeCache legacyImplementation = legacyImplementation();

        ManagementService service = legacyImplementation.management();

        assertTrue(service.capabilities().supported().isEmpty());
        Future<AdminPage<NamespaceStats>> result = service.namespaces(NamespaceQuery.defaults());
        assertTrue(result.failed());
        assertInstanceOf(ManagementCapabilityException.class, result.cause());
    }

    @Test
    void unsupportedServiceIsAStableSharedFallback() {
        assertSame(UnsupportedManagementService.instance(), UnsupportedManagementService.instance());
    }

    @Test
    void everySensitiveMutationAndActorBoundBulkMethodRequiresActionContext() {
        Set<String> privileged = Set.of(
                "revealEntry", "setEntry", "expireEntry", "persistEntry", "touchEntry", "deleteEntry",
                "setCounter", "adjustCounter", "expireCounter", "persistCounter", "deleteCounter",
                "revealLockOwner", "forceReleaseLock", "previewEntryDelete", "executeEntryDelete",
                "previewCounterDelete", "executeCounterDelete");

        for (Method method : ManagementService.class.getMethods()) {
            if (privileged.contains(method.getName())) {
                assertTrue(method.getParameterCount() >= 2
                                && method.getParameterTypes()[method.getParameterCount() - 1]
                                == ManagementActionContext.class,
                        () -> method + " must end with ManagementActionContext");
            }
        }
    }

    private static PeeGeeCache legacyImplementation() {
        return new PeeGeeCache() {
            public CacheService cache() { return null; }
            public CounterService counters() { return null; }
            public LockService locks() { return null; }
            public ScanService scan() { return null; }
            public PubSubService pubSub() { return null; }
            public AdminService admin() { return null; }
        };
    }
}
