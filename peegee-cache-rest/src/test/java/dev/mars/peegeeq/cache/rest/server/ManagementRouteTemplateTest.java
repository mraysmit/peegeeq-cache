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
        assertEquals("/ws/monitoring", ManagementRouteTemplate.resolve("/ws/monitoring"));
        assertEquals("/api/*", ManagementRouteTemplate.resolve("/api/v1/unknown/customer:secret"));
        assertEquals("/ui/*", ManagementRouteTemplate.resolve("/ui/customer:secret"));
        assertEquals("OTHER", ManagementRouteTemplate.resolve("/customer:secret"));
    }
}
