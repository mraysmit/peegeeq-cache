package dev.mars.peegeeq.cache.rest.server;

import com.microsoft.playwright.Page;

import java.net.URI;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import static java.util.Map.entry;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Derives management operation IDs from requests observed by the real browser. */
final class ManagementBrowserOperationTrace {

    private static final Map<String, String> OPERATIONS = Map.ofEntries(
            entry("GET /api/v1/session", "getSession"),
            entry("POST /api/v1/session/local", "exchangeLocalToken"),
            entry("DELETE /api/v1/session/local", "deleteLocalSession"),
            entry("GET /api/v1/setups", "listSetups"),
            entry("POST /api/v1/setups/actions/test", "testUnregisteredSetup"),
            entry("POST /api/v1/setups", "registerSetup"),
            entry("GET /api/v1/setups/{setupId}", "getSetup"),
            entry("POST /api/v1/setups/{setupId}/connect", "connectSetup"),
            entry("POST /api/v1/setups/{setupId}/test", "testRegisteredSetup"),
            entry("POST /api/v1/setups/{setupId}/detach", "detachSetup"),
            entry("DELETE /api/v1/setups/{setupId}", "forgetSetup"),
            entry("GET /api/v1/setups/{setupId}/health", "getSetupHealth"),
            entry("GET /api/v1/setups/{setupId}/capabilities", "getSetupCapabilities"),
            entry("GET /api/v1/setups/{setupId}/overview", "getOverview"),
            entry("GET /api/v1/setups/{setupId}/namespaces", "listNamespaces"),
            entry("GET /api/v1/setups/{setupId}/namespaces/export", "exportNamespaces"),
            entry("GET /api/v1/setups/{setupId}/namespaces/{encodedNamespace}", "getNamespace"),
            entry("GET /api/v1/setups/{setupId}/namespaces/{encodedNamespace}/entries", "listEntries"),
            entry("GET /api/v1/setups/{setupId}/namespaces/{encodedNamespace}/entries/{encodedKey}", "getEntry"),
            entry("POST /api/v1/setups/{setupId}/namespaces/{encodedNamespace}/entries/{encodedKey}/value/reveal", "revealEntryValue"),
            entry("PUT /api/v1/setups/{setupId}/namespaces/{encodedNamespace}/entries/{encodedKey}", "setEntry"),
            entry("DELETE /api/v1/setups/{setupId}/namespaces/{encodedNamespace}/entries/{encodedKey}", "deleteEntry"),
            entry("POST /api/v1/setups/{setupId}/namespaces/{encodedNamespace}/entries/{encodedKey}/ttl", "expireEntry"),
            entry("POST /api/v1/setups/{setupId}/namespaces/{encodedNamespace}/entries/{encodedKey}/persist", "persistEntry"),
            entry("POST /api/v1/setups/{setupId}/namespaces/{encodedNamespace}/entries/{encodedKey}/touch", "touchEntry"),
            entry("POST /api/v1/setups/{setupId}/namespaces/{encodedNamespace}/entries/bulk-delete/preview", "previewEntryBulkDelete"),
            entry("POST /api/v1/setups/{setupId}/namespaces/{encodedNamespace}/entries/bulk-delete/execute", "executeEntryBulkDelete"),
            entry("GET /api/v1/setups/{setupId}/counters", "listCounters"),
            entry("GET /api/v1/setups/{setupId}/namespaces/{encodedNamespace}/counters/{encodedKey}", "getCounter"),
            entry("PUT /api/v1/setups/{setupId}/namespaces/{encodedNamespace}/counters/{encodedKey}", "setCounter"),
            entry("POST /api/v1/setups/{setupId}/namespaces/{encodedNamespace}/counters/{encodedKey}/increment", "adjustCounter"),
            entry("POST /api/v1/setups/{setupId}/namespaces/{encodedNamespace}/counters/{encodedKey}/ttl", "expireCounter"),
            entry("POST /api/v1/setups/{setupId}/namespaces/{encodedNamespace}/counters/{encodedKey}/persist", "persistCounter"),
            entry("DELETE /api/v1/setups/{setupId}/namespaces/{encodedNamespace}/counters/{encodedKey}", "deleteCounter"),
            entry("POST /api/v1/setups/{setupId}/counters/bulk-delete/preview", "previewCounterBulkDelete"),
            entry("POST /api/v1/setups/{setupId}/counters/bulk-delete/execute", "executeCounterBulkDelete"),
            entry("GET /api/v1/setups/{setupId}/locks", "listLocks"),
            entry("GET /api/v1/setups/{setupId}/namespaces/{encodedNamespace}/locks/{encodedKey}", "getLock"),
            entry("POST /api/v1/setups/{setupId}/namespaces/{encodedNamespace}/locks/{encodedKey}/owner/reveal", "revealLockOwner"),
            entry("POST /api/v1/setups/{setupId}/namespaces/{encodedNamespace}/locks/{encodedKey}/force-release", "forceReleaseLock"),
            entry("POST /api/v1/setups/{setupId}/pubsub/subscriptions", "createPubSubSubscription"),
            entry("GET /api/v1/setups/{setupId}/pubsub/subscriptions/{subscriptionId}/stream", "streamPubSubMessages"),
            entry("POST /api/v1/setups/{setupId}/pubsub/subscriptions/{subscriptionId}/messages/{messageId}/payload/reveal", "revealPubSubPayload"),
            entry("DELETE /api/v1/setups/{setupId}/pubsub/subscriptions/{subscriptionId}", "deletePubSubSubscription"),
            entry("POST /api/v1/setups/{setupId}/pubsub/publish", "publishPubSubMessage"),
            entry("GET /api/v1/setups/{setupId}/monitoring/database", "getDatabaseMonitoring"),
            entry("GET /api/v1/setups/{setupId}/monitoring/runtime", "getRuntimeMonitoring"),
            entry("GET /api/v1/setups/{setupId}/sse/metrics", "streamMetrics"),
            entry("GET /api/v1/setups/{setupId}/activity", "listActivity"),
            entry("GET /api/v1/setups/{setupId}/namespaces/{encodedNamespace}/entries/{encodedKey}/exists", "checkEntryExists"),
            entry("POST /api/v1/setups/{setupId}/entries/batch-get", "batchGetEntries"),
            entry("POST /api/v1/setups/{setupId}/entries/batch-set", "batchSetEntries"),
            entry("POST /api/v1/setups/{setupId}/entries/scan", "scanEntries"),
            entry("GET /api/v1/setups/{setupId}/cache-metrics", "getCacheMetrics"),
            entry("POST /api/v1/setups/{setupId}/namespaces/{encodedNamespace}/locks/{encodedKey}/acquire", "acquireLock"),
            entry("POST /api/v1/setups/{setupId}/namespaces/{encodedNamespace}/locks/{encodedKey}/renew", "renewLock"),
            entry("POST /api/v1/setups/{setupId}/namespaces/{encodedNamespace}/locks/{encodedKey}/release", "releaseLock"),
            entry("POST /api/v1/setups/{setupId}/namespaces/{encodedNamespace}/locks/{encodedKey}/ownership", "checkLockOwnership"),
            entry("GET /ws/monitoring", "monitoringWebSocket"));

    private final Set<String> observed = new LinkedHashSet<>();

    void attach(Page page) {
        page.onRequest(request -> observe(request.method(), URI.create(request.url()).getPath()));
        page.onWebSocket(socket -> observe("GET", URI.create(socket.url()).getPath()));
    }

    void assertObserved(String... expectedOperations) {
        for (String operation : expectedOperations) {
            assertTrue(observed.contains(operation),
                    () -> "Browser did not invoke declared operation " + operation + "; observed=" + observed);
        }
    }

    void assertObservedExactly(Collection<String> expectedOperations, Set<String> allowedOperations) {
        expectedOperations.forEach(operation -> assertTrue(observed.contains(operation),
                () -> "Browser did not invoke declared operation " + operation + "; observed=" + observed));
        Set<String> unexpected = new LinkedHashSet<>(observed);
        unexpected.removeAll(expectedOperations);
        unexpected.removeAll(allowedOperations);
        assertEquals(Set.of(), unexpected,
                () -> "Browser invoked undeclared feature operations " + unexpected
                        + "; declared=" + expectedOperations + "; allowed fixture traffic=" + allowedOperations);
    }

    Set<String> observed() {
        return Set.copyOf(observed);
    }

    void observe(String method, String path) {
        String operation = OPERATIONS.get(method + " " + ManagementRouteTemplate.resolve(path));
        if (operation != null) {
            observed.add(operation);
        }
    }
}
