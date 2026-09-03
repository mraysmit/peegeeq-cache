package dev.mars.peegeeq.cache.rest.server;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ManagementBrowserOperationTraceTest {

    @Test
    void exactAssertionAcceptsDeclaredOperationsAndReviewedFixtureTraffic() {
        ManagementBrowserOperationTrace trace = new ManagementBrowserOperationTrace();
        trace.observe("GET", "/api/v1/session");
        trace.observe("GET", "/api/v1/setups/browser/namespaces/orders/entries");

        assertDoesNotThrow(() -> trace.assertObservedExactly(
                List.of("listEntries"), Set.of("getSession")));
    }

    @Test
    void resolvesEveryBackendParityOperationFromConcreteBrowserPaths() {
        ManagementBrowserOperationTrace trace = new ManagementBrowserOperationTrace();
        trace.observe("GET", "/api/v1/setups/browser/namespaces/orders/entries/customer/exists");
        trace.observe("POST", "/api/v1/setups/browser/entries/batch-get");
        trace.observe("POST", "/api/v1/setups/browser/entries/batch-set");
        trace.observe("POST", "/api/v1/setups/browser/entries/batch-delete");
        trace.observe("POST", "/api/v1/setups/browser/entries/scan");
        trace.observe("GET", "/api/v1/setups/browser/cache-metrics");
        trace.observe("POST", "/api/v1/setups/browser/namespaces/orders/locks/customer/acquire");
        trace.observe("POST", "/api/v1/setups/browser/namespaces/orders/locks/customer/renew");
        trace.observe("POST", "/api/v1/setups/browser/namespaces/orders/locks/customer/ownership");
        trace.observe("POST", "/api/v1/setups/browser/namespaces/orders/locks/customer/release");

        assertDoesNotThrow(() -> trace.assertObservedExactly(List.of(
                "checkEntryExists",
                "batchGetEntries",
                "batchSetEntries",
                "batchDeleteEntries",
                "scanEntries",
                "getCacheMetrics",
                "acquireLock",
                "renewLock",
                "checkLockOwnership",
                "releaseLock"), Set.of()));
    }

    @Test
    void exactAssertionRejectsMissingAndUndeclaredFeatureOperations() {
        ManagementBrowserOperationTrace trace = new ManagementBrowserOperationTrace();
        trace.observe("GET", "/api/v1/setups/browser/namespaces/orders/entries");
        trace.observe("GET", "/api/v1/setups/browser/namespaces/orders/entries/customer");

        assertThrows(AssertionError.class, () -> trace.assertObservedExactly(
                List.of("listEntries", "listCounters"), Set.of("getSession")));
        assertThrows(AssertionError.class, () -> trace.assertObservedExactly(
                List.of("listEntries"), Set.of("getSession")));
    }
}
