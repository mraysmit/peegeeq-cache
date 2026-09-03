package dev.mars.peegeeq.cache.rest.server;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ManagementRouteTemplateTest {

    @Test
    void replacesEveryUserControlledSegmentAndBoundsUnknownRoutes() {
        assertEquals("/health/ready", ManagementRouteTemplate.resolve("/health/ready"));
        assertEquals("/api/v1/setups/{setupId}/namespaces/{encodedNamespace}/entries/{encodedKey}",
                ManagementRouteTemplate.resolve(
                        "/api/v1/setups/customer:secret/namespaces/orders%2Fprivate/entries/key:secret"));
        assertEquals("/api/v1/setups/{setupId}/pubsub/subscriptions/{subscriptionId}/stream",
                ManagementRouteTemplate.resolve(
                        "/api/v1/setups/customer:secret/pubsub/subscriptions/sub-secret/stream"));
        assertEquals("/api/v1/setups/{setupId}/namespaces/{encodedNamespace}/entries/{encodedKey}/exists",
                ManagementRouteTemplate.resolve(
                        "/api/v1/setups/customer:secret/namespaces/orders%2Fprivate/entries/key:secret/exists"));
        assertEquals("/api/v1/setups/{setupId}/entries/batch-get",
                ManagementRouteTemplate.resolve("/api/v1/setups/customer:secret/entries/batch-get"));
        assertEquals("/api/v1/setups/{setupId}/entries/batch-set",
                ManagementRouteTemplate.resolve("/api/v1/setups/customer:secret/entries/batch-set"));
        assertEquals("/api/v1/setups/{setupId}/entries/batch-delete",
                ManagementRouteTemplate.resolve("/api/v1/setups/customer:secret/entries/batch-delete"));
        assertEquals("/api/v1/setups/{setupId}/entries/scan",
                ManagementRouteTemplate.resolve("/api/v1/setups/customer:secret/entries/scan"));
        assertEquals("/api/v1/setups/{setupId}/cache-metrics",
                ManagementRouteTemplate.resolve("/api/v1/setups/customer:secret/cache-metrics"));
        for (String action : new String[] {"acquire", "renew", "release", "ownership"}) {
            assertEquals(
                    "/api/v1/setups/{setupId}/namespaces/{encodedNamespace}/locks/{encodedKey}/" + action,
                    ManagementRouteTemplate.resolve(
                            "/api/v1/setups/customer:secret/namespaces/orders%2Fprivate/locks/key:secret/" + action));
        }
        assertEquals("/ws/monitoring", ManagementRouteTemplate.resolve("/ws/monitoring"));
        assertEquals("/api/*", ManagementRouteTemplate.resolve("/api/v1/unknown/customer:secret"));
        assertEquals("/ui/*", ManagementRouteTemplate.resolve("/ui/customer:secret"));
        assertEquals("OTHER", ManagementRouteTemplate.resolve("/customer:secret"));
    }
}
