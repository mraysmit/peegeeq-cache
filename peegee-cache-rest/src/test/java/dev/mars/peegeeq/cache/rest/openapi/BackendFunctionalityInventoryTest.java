package dev.mars.peegeeq.cache.rest.openapi;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import dev.mars.peegeeq.cache.api.admin.AdminService;
import dev.mars.peegeeq.cache.api.cache.CacheService;
import dev.mars.peegeeq.cache.api.counter.CounterService;
import dev.mars.peegeeq.cache.api.lock.LockService;
import dev.mars.peegeeq.cache.api.management.ManagementService;
import dev.mars.peegeeq.cache.api.pubsub.PubSubService;
import dev.mars.peegeeq.cache.api.pubsub.Subscription;
import dev.mars.peegeeq.cache.api.scan.ScanService;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static java.util.Map.entry;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Fails when the public backend surface changes without an explicit management operation mapping. */
class BackendFunctionalityInventoryTest {

    private static final List<Class<?>> SERVICES = List.of(
            CacheService.class,
            CounterService.class,
            LockService.class,
            PubSubService.class,
            Subscription.class,
            ScanService.class,
            AdminService.class,
            ManagementService.class);

    private static final Map<String, Set<String>> MAPPINGS = Map.ofEntries(
            entry("CacheService.get", Set.of("getEntry", "revealEntryValue")),
            entry("CacheService.getMany", Set.of("batchGetEntries")),
            entry("CacheService.set", Set.of("batchSetEntries")),
            entry("CacheService.setMany", Set.of("batchSetEntries")),
            entry("CacheService.delete", Set.of("deleteEntry")),
            entry("CacheService.deleteMany", Set.of("batchDeleteEntries")),
            entry("CacheService.exists", Set.of("checkEntryExists")),
            entry("CacheService.ttl", Set.of("getEntry")),
            entry("CacheService.expire", Set.of("expireEntry")),
            entry("CacheService.persist", Set.of("persistEntry")),
            entry("CacheService.touch", Set.of("touchEntry")),
            entry("CounterService.increment", Set.of("adjustCounter")),
            entry("CounterService.incrementBy", Set.of("adjustCounter")),
            entry("CounterService.decrement", Set.of("adjustCounter")),
            entry("CounterService.decrementBy", Set.of("adjustCounter")),
            entry("CounterService.getValue", Set.of("getCounter")),
            entry("CounterService.setValue", Set.of("setCounter")),
            entry("CounterService.ttl", Set.of("getCounter")),
            entry("CounterService.expire", Set.of("expireCounter")),
            entry("CounterService.persist", Set.of("persistCounter")),
            entry("CounterService.delete", Set.of("deleteCounter")),
            entry("LockService.acquire", Set.of("acquireLock")),
            entry("LockService.renew", Set.of("renewLock")),
            entry("LockService.release", Set.of("releaseLock")),
            entry("LockService.isHeldBy", Set.of("checkLockOwnership")),
            entry("LockService.currentLock", Set.of("getLock", "revealLockOwner")),
            entry("PubSubService.publish", Set.of("publishPubSubMessage")),
            entry("PubSubService.subscribe", Set.of("createPubSubSubscription", "streamPubSubMessages")),
            entry("Subscription.unsubscribe", Set.of("deletePubSubSubscription")),
            entry("ScanService.scan", Set.of("scanEntries")),
            entry("AdminService.entryStats", Set.of("getNamespace")),
            entry("AdminService.metrics", Set.of("getCacheMetrics")),
            entry("ManagementService.capabilities", Set.of("getSetupCapabilities")),
            entry("ManagementService.overview", Set.of("getOverview")),
            entry("ManagementService.databaseMonitoring", Set.of("getDatabaseMonitoring")),
            entry("ManagementService.namespaces", Set.of("listNamespaces", "exportNamespaces")),
            entry("ManagementService.namespace", Set.of("getNamespace")),
            entry("ManagementService.entries", Set.of("listEntries")),
            entry("ManagementService.entry", Set.of("getEntry")),
            entry("ManagementService.revealEntry", Set.of("revealEntryValue")),
            entry("ManagementService.setEntry", Set.of("setEntry")),
            entry("ManagementService.expireEntry", Set.of("expireEntry")),
            entry("ManagementService.persistEntry", Set.of("persistEntry")),
            entry("ManagementService.touchEntry", Set.of("touchEntry")),
            entry("ManagementService.deleteEntry", Set.of("deleteEntry")),
            entry("ManagementService.counters", Set.of("listCounters")),
            entry("ManagementService.counter", Set.of("getCounter")),
            entry("ManagementService.setCounter", Set.of("setCounter")),
            entry("ManagementService.adjustCounter", Set.of("adjustCounter")),
            entry("ManagementService.expireCounter", Set.of("expireCounter")),
            entry("ManagementService.persistCounter", Set.of("persistCounter")),
            entry("ManagementService.deleteCounter", Set.of("deleteCounter")),
            entry("ManagementService.locks", Set.of("listLocks")),
            entry("ManagementService.lock", Set.of("getLock")),
            entry("ManagementService.revealLockOwner", Set.of("revealLockOwner")),
            entry("ManagementService.forceReleaseLock", Set.of("forceReleaseLock")),
            entry("ManagementService.databaseStats", Set.of("getOverview")),
            entry("ManagementService.expiryStats", Set.of("getOverview")),
            entry("ManagementService.previewEntryDelete", Set.of("previewEntryBulkDelete")),
            entry("ManagementService.executeEntryDelete", Set.of("executeEntryBulkDelete")),
            entry("ManagementService.previewCounterDelete", Set.of("previewCounterBulkDelete")),
            entry("ManagementService.executeCounterDelete", Set.of("executeCounterBulkDelete")));

    @Test
    void everyPublicBackendMethodHasAnExistingOpenApiOperation() throws Exception {
        Set<String> reflected = new LinkedHashSet<>();
        SERVICES.forEach(type -> Arrays.stream(type.getDeclaredMethods())
                .filter(method -> !isResultMetadata(type, method))
                .map(method -> type.getSimpleName() + "." + method.getName())
                .forEach(reflected::add));

        assertEquals(62, reflected.size(), "The reviewed public backend denominator changed");
        assertEquals(reflected, MAPPINGS.keySet(),
                "Every public method must be added to the functionality matrix mapping");

        Set<String> declaredOperations = openApiOperationIds();
        MAPPINGS.forEach((method, operationIds) -> operationIds.forEach(operationId ->
                assertTrue(declaredOperations.contains(operationId),
                        () -> method + " maps to absent OpenAPI operation " + operationId)));
    }

    private static boolean isResultMetadata(Class<?> type, Method method) {
        return type == Subscription.class && method.getName().equals("channel");
    }

    private static Set<String> openApiOperationIds() throws Exception {
        try (InputStream input = BackendFunctionalityInventoryTest.class.getResourceAsStream(
                "/openapi/peegeeq-cache-management-v1.yaml")) {
            JsonNode document = new ObjectMapper(new YAMLFactory()).readTree(input);
            Set<String> operationIds = new LinkedHashSet<>();
            document.path("paths").forEach(path -> path.forEach(operation -> {
                JsonNode operationId = operation.get("operationId");
                if (operationId != null) operationIds.add(operationId.asText());
            }));
            return operationIds;
        }
    }
}
