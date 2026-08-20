package dev.mars.peegeeq.cache.rest.openapi;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.Map;
import java.util.TreeMap;

import static java.util.Map.entry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ManagementOpenApiContractTest {

    private static final String OPENAPI_RESOURCE = "/openapi/peegeeq-cache-management-v1.yaml";

    @Test
    void loadsOpenApi31Document() throws Exception {
        JsonNode document = loadDocument();
        assertEquals("3.1.0", document.path("openapi").asText());
        assertEquals("PeeGeeQ Cache Management API", document.path("info").path("title").asText());
    }

    @Test
    void matchesReviewedOperationManifest() throws Exception {
        JsonNode paths = loadDocument().path("paths");
        Map<String, String> actual = new TreeMap<>();
        paths.properties().forEach(path -> path.getValue().properties().forEach(operation ->
                actual.put(operation.getKey().toUpperCase() + " " + path.getKey(),
                        operation.getValue().path("operationId").asText())));

        assertEquals(EXPECTED_OPERATIONS, actual);
    }

    @Test
    void declaresSecurityAndProblemResponsesForEveryProtectedOperation() throws Exception {
        JsonNode document = loadDocument();
        JsonNode problemResponse = document.path("components").path("responses").path("ManagementProblem");
        assertEquals("#/components/schemas/ManagementProblem", problemResponse.path("content")
                .path("application/problem+json").path("schema").path("$ref").asText());

        document.path("paths").properties().forEach(path -> path.getValue().properties().forEach(method -> {
            JsonNode operation = method.getValue();
            String operationId = operation.path("operationId").asText();
            assertFalse(operation.path("x-security-profile").asText().isBlank(),
                    () -> operationId + " security profile");
            if (operationId.equals("exchangeLocalToken")) {
                assertTrue(operation.path("security").isArray() && operation.path("security").isEmpty(),
                        "local bootstrap is the declared session-security exception");
            } else {
                assertTrue(operation.path("security").isArray() && !operation.path("security").isEmpty(),
                        () -> operationId + " OpenAPI security requirement");
            }
            assertEquals("#/components/responses/ManagementProblem",
                    operation.path("responses").path("default").path("$ref").asText(),
                    () -> operationId + " default problem response");
        }));
    }

    @Test
    void declaresSseAndWebSocketComponentSchemas() throws Exception {
        JsonNode document = loadDocument();
        JsonNode schemas = document.path("components").path("schemas");
        assertTrue(schemas.has("PubSubSseEvent"));
        assertTrue(schemas.has("MetricsSseEvent"));
        assertTrue(schemas.has("MonitoringWebSocketEnvelope"));

        assertEquals("sse", document.path("paths")
                .path("/api/v1/setups/{setupId}/pubsub/subscriptions/{subscriptionId}/stream")
                .path("get").path("x-transport").asText());
        assertEquals("sse", document.path("paths")
                .path("/api/v1/setups/{setupId}/sse/metrics")
                .path("get").path("x-transport").asText());
        assertEquals("websocket", document.path("paths").path("/ws/monitoring")
                .path("get").path("x-transport").asText());
    }

    private static JsonNode loadDocument() throws Exception {
        try (InputStream input = ManagementOpenApiContractTest.class.getResourceAsStream(OPENAPI_RESOURCE)) {
            assertNotNull(input, "packaged management OpenAPI document");
            return new ObjectMapper(new YAMLFactory()).readTree(input);
        }
    }

    private static final Map<String, String> EXPECTED_OPERATIONS = new TreeMap<>(Map.ofEntries(
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
            entry("GET /ws/monitoring", "monitoringWebSocket")
    ));
}
