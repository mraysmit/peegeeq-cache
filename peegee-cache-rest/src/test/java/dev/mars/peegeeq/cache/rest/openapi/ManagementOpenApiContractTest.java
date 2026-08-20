package dev.mars.peegeeq.cache.rest.openapi;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.swagger.v3.parser.OpenAPIV3Parser;
import io.swagger.v3.parser.core.models.ParseOptions;
import io.swagger.v3.parser.core.models.SwaggerParseResult;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static java.util.Map.entry;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
    void passesStandardsAwareOpenApi31Validation() throws Exception {
        ParseOptions options = new ParseOptions();
        options.setResolve(true);
        options.setResolveFully(false);
        options.setResolveCombinators(false);

        SwaggerParseResult result = new OpenAPIV3Parser().readContents(loadDocumentText(), null, options);
        assertNotNull(result.getOpenAPI(), () -> "OpenAPI parser did not produce a model: " + result.getMessages());
        assertEquals("3.1.0", result.getOpenAPI().getOpenapi());
        assertTrue(result.getMessages().isEmpty(), () -> "OpenAPI validation messages: " + result.getMessages());
    }

    @Test
    void matchesReviewedOperationManifest() throws Exception {
        JsonNode paths = loadDocument().path("paths");
        Map<String, String> actual = new TreeMap<>();
        paths.properties().forEach(path -> path.getValue().properties().forEach(operation -> {
            if (HTTP_METHODS.contains(operation.getKey())) {
                actual.put(operation.getKey().toUpperCase() + " " + path.getKey(),
                        operation.getValue().path("operationId").asText());
            }
        }));

        assertEquals(EXPECTED_OPERATIONS, actual);
    }

    @Test
    void declaresReviewedSuccessResponsesAndHeaders() throws Exception {
        JsonNode document = loadDocument();
        Map<String, JsonNode> operations = operationsById(document);

        EXPECTED_SUCCESS.forEach((operationId, expected) -> {
            JsonNode operation = operations.get(operationId);
            assertNotNull(operation, operationId + " operation");
            assertEquals(expected.statuses(), responseStatuses(operation),
                    () -> operationId + " success statuses");

            expected.statuses().forEach(status -> {
                JsonNode response = resolveComponent(document, operation.path("responses").path(status), "responses");
                assertResponseHeaders(document, operationId, response, expected.headerProfile());
                if (expected.schema() != null) {
                    assertEquals("#/components/schemas/" + expected.schema(), response.path("content")
                                    .path(expected.mediaType()).path("schema").path("$ref").asText(),
                            () -> operationId + " " + status + " success schema");
                } else {
                    assertTrue(response.path("content").isMissingNode(),
                            () -> operationId + " " + status + " must not declare a response body");
                }
            });
        });
    }

    @Test
    void declaresEveryPathTemplateParameter() throws Exception {
        JsonNode document = loadDocument();
        document.path("paths").properties().forEach(pathEntry -> {
            Set<String> expected = new java.util.TreeSet<>();
            Matcher matcher = Pattern.compile("\\{([^}]+)}").matcher(pathEntry.getKey());
            while (matcher.find()) {
                expected.add(matcher.group(1));
            }

            Set<String> actual = new java.util.TreeSet<>();
            pathEntry.getValue().path("parameters").forEach(parameter -> {
                JsonNode resolved = resolveComponent(document, parameter, "parameters");
                assertEquals("path", resolved.path("in").asText(),
                        () -> pathEntry.getKey() + " parameter location");
                assertTrue(resolved.path("required").asBoolean(),
                        () -> pathEntry.getKey() + " path parameter must be required");
                actual.add(resolved.path("name").asText());
            });
            assertEquals(expected, actual, () -> pathEntry.getKey() + " template parameters");
        });
    }

    @Test
    void declaresReviewedJsonRequestBodies() throws Exception {
        JsonNode document = loadDocument();
        operationsById(document).forEach((operationId, operation) -> {
            RequestContract expected = EXPECTED_REQUEST_BODIES.get(operationId);
            if (expected == null) {
                assertTrue(operation.path("requestBody").isMissingNode(),
                        () -> operationId + " must not declare a request body");
                return;
            }

            JsonNode requestBody = resolveComponent(document, operation.path("requestBody"), "requestBodies");
            assertEquals(expected.required(), requestBody.path("required").asBoolean(),
                    () -> operationId + " request-body required flag");
            assertEquals("#/components/schemas/" + expected.schema(), requestBody.path("content")
                            .path("application/json").path("schema").path("$ref").asText(),
                    () -> operationId + " request-body schema");
        });
    }

    @Test
    void declaresReviewedQueryHeaderAndPreconditionParameters() throws Exception {
        JsonNode document = loadDocument();
        operationsById(document).forEach((operationId, operation) -> {
            Set<String> expected = new java.util.TreeSet<>(
                    EXPECTED_OPERATION_PARAMETERS.getOrDefault(operationId, Set.of()));
            switch (operation.path("x-security-profile").asText()) {
                case "OPERATE", "VIEW_MUTATE", "REVEAL" -> {
                    expected.add("header:Origin");
                    expected.add("header:X-PeeGeeQ-CSRF");
                }
                case "LOCAL_BOOTSTRAP", "SSE", "WS" -> expected.add("header:Origin");
                default -> {
                }
            }

            Set<String> actual = new java.util.TreeSet<>();
            operation.path("parameters").forEach(parameter -> {
                JsonNode resolved = resolveComponent(document, parameter, "parameters");
                actual.add(resolved.path("in").asText() + ":" + resolved.path("name").asText());
            });
            assertEquals(expected, actual, () -> operationId + " non-path parameters");
        });
    }

    @Test
    void declaresSecurityAndProblemResponsesForEveryProtectedOperation() throws Exception {
        JsonNode document = loadDocument();
        JsonNode problemResponse = document.path("components").path("responses").path("ManagementProblem");
        assertEquals("#/components/schemas/ManagementProblem", problemResponse.path("content")
                .path("application/problem+json").path("schema").path("$ref").asText());

        document.path("paths").properties().forEach(path -> path.getValue().properties().forEach(method -> {
            if (!HTTP_METHODS.contains(method.getKey())) {
                return;
            }
            JsonNode operation = method.getValue();
            String operationId = operation.path("operationId").asText();
            assertEquals(expectedSecurityProfile(operationId), operation.path("x-security-profile").asText(),
                    () -> operationId + " reviewed security profile");
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
        assertEquals("#/components/schemas/MonitoringWebSocketEnvelope", document.path("paths")
                .path("/ws/monitoring").path("get").path("x-websocket-message-schema")
                .path("$ref").asText());
    }

    @Test
    void closesReviewedAggregateQueryAndMutationSemantics() throws Exception {
        JsonNode document = loadDocument();
        JsonNode schemas = document.path("components").path("schemas");
        Set.of("NamespaceQuery", "NamespaceExportQuery", "EntryQuery", "CounterQuery", "LockQuery",
                        "ActivityQuery", "Overview", "NamespaceDetails", "DatabaseMonitoring", "RuntimeMonitoring",
                        "ActivityPage", "AvailableLongValue")
                .forEach(schema -> assertTrue(schemas.has(schema), () -> schema + " component schema"));

        assertSchemaRequires(schemas, "Overview",
                "scope", "observedAt", "health", "totals", "expiry", "valueTypeCounts", "topNamespaces");
        assertSchemaRequires(schemas, "NamespaceDetails", "stats", "valueTypeCounts", "ttlDistribution");
        assertSchemaRequires(schemas, "RuntimeMonitoring", "scope", "observedAt", "lifecycleState", "pool",
                "activeOperations", "pubSubSubscriptions", "sseClients", "webSocketClients",
                "retainedPayloadBytes", "auditQueue", "expirySweeper", "operations");
        assertSchemaRequires(schemas, "ManagementProblem", "type", "title", "status", "code", "detail",
                "instance", "correlationId", "fieldErrors");

        Map<String, JsonNode> operations = operationsById(document);
        assertTrue(operations.get("touchEntry").path("x-version-stable").asBoolean(),
                "touchEntry explicitly preserves the matched version");
        assertEquals("SET_MODE_NOT_APPLIED", operations.get("setEntry")
                .path("x-wildcard-condition-not-met").asText());
        assertEquals("SET_MODE_NOT_APPLIED", operations.get("setCounter")
                .path("x-wildcard-condition-not-met").asText());
        assertEquals("SET_MODE_NOT_APPLIED", operations.get("adjustCounter")
                .path("x-wildcard-condition-not-met").asText());
    }

    private static JsonNode loadDocument() throws Exception {
        try (InputStream input = ManagementOpenApiContractTest.class.getResourceAsStream(OPENAPI_RESOURCE)) {
            assertNotNull(input, "packaged management OpenAPI document");
            return new ObjectMapper(new YAMLFactory()).readTree(input);
        }
    }

    private static String loadDocumentText() throws Exception {
        try (InputStream input = ManagementOpenApiContractTest.class.getResourceAsStream(OPENAPI_RESOURCE)) {
            assertNotNull(input, "packaged management OpenAPI document");
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static Map<String, JsonNode> operationsById(JsonNode document) {
        Map<String, JsonNode> operations = new TreeMap<>();
        document.path("paths").properties().forEach(path -> path.getValue().properties().forEach(method -> {
            if (!HTTP_METHODS.contains(method.getKey())) {
                return;
            }
            JsonNode operation = method.getValue();
            operations.put(operation.path("operationId").asText(), operation);
        }));
        return operations;
    }

    private static Set<String> responseStatuses(JsonNode operation) {
        Set<String> statuses = new java.util.TreeSet<>();
        operation.path("responses").fieldNames().forEachRemaining(status -> {
            if (!status.equals("default")) {
                statuses.add(status);
            }
        });
        return statuses;
    }

    private static void assertResponseHeaders(
            JsonNode document, String operationId, JsonNode response, String headerProfile) {
        Set<String> expectedHeaders = switch (headerProfile) {
            case "C" -> Set.of("X-Correlation-ID");
            case "N" -> Set.of("X-Correlation-ID", "Cache-Control");
            case "R" -> Set.of("X-Correlation-ID", "Cache-Control", "Pragma");
            case "E" -> Set.of("X-Correlation-ID", "ETag");
            case "S" -> Set.of("X-Correlation-ID", "Cache-Control", "Connection");
            case "W" -> Set.of();
            default -> throw new IllegalArgumentException("Unknown header profile " + headerProfile);
        };
        if (Set.of("getSession", "exchangeLocalToken", "deleteLocalSession").contains(operationId)) {
            expectedHeaders = new java.util.HashSet<>(expectedHeaders);
            expectedHeaders.add("Set-Cookie");
        }
        Set<String> actualHeaders = new java.util.TreeSet<>();
        response.path("headers").fieldNames().forEachRemaining(actualHeaders::add);
        assertEquals(expectedHeaders, actualHeaders, () -> operationId + " response headers");

        actualHeaders.forEach(header -> {
            String reference = response.path("headers").path(header).path("$ref").asText();
            assertEquals("#/components/headers/" + header.replace("-", ""), reference,
                    () -> operationId + " " + header + " reusable header");
            assertTrue(document.path("components").path("headers").has(header.replace("-", "")),
                    () -> header + " component");
        });
    }

    private static JsonNode resolveComponent(JsonNode document, JsonNode node, String componentType) {
        String prefix = "#/components/" + componentType + "/";
        String reference = node.path("$ref").asText();
        return reference.startsWith(prefix)
                ? document.path("components").path(componentType).path(reference.substring(prefix.length()))
                : node;
    }

    private static void assertSchemaRequires(JsonNode schemas, String schemaName, String... properties) {
        Set<String> actual = new java.util.TreeSet<>();
        schemas.path(schemaName).path("required").forEach(value -> actual.add(value.asText()));
        assertTrue(actual.containsAll(Set.of(properties)),
                () -> schemaName + " required properties: " + actual);
    }

    private static String expectedSecurityProfile(String operationId) {
        if (operationId.equals("getSession")) {
            return "SESSION";
        }
        if (operationId.equals("exchangeLocalToken")) {
            return "LOCAL_BOOTSTRAP";
        }
        if (Set.of("deleteLocalSession", "createPubSubSubscription", "deletePubSubSubscription")
                .contains(operationId)) {
            return "VIEW_MUTATE";
        }
        if (Set.of("revealEntryValue", "revealLockOwner", "revealPubSubPayload").contains(operationId)) {
            return "REVEAL";
        }
        if (Set.of("streamPubSubMessages", "streamMetrics").contains(operationId)) {
            return "SSE";
        }
        if (operationId.equals("monitoringWebSocket")) {
            return "WS";
        }
        if (OPERATE_OPERATIONS.contains(operationId)) {
            return "OPERATE";
        }
        return "VIEW";
    }

    private record SuccessContract(Set<String> statuses, String schema, String mediaType, String headerProfile) {
        private SuccessContract(String status, String schema, String headerProfile) {
            this(Set.of(status), schema, "application/json", headerProfile);
        }

        private SuccessContract(Set<String> statuses, String schema, String headerProfile) {
            this(statuses, schema, "application/json", headerProfile);
        }
    }

    private record RequestContract(String schema, boolean required) {
    }

    private static final Map<String, RequestContract> EXPECTED_REQUEST_BODIES = Map.ofEntries(
            entry("exchangeLocalToken", new RequestContract("LocalTokenExchangeRequest", true)),
            entry("testUnregisteredSetup", new RequestContract("SetupConnectionRequest", true)),
            entry("registerSetup", new RequestContract("SetupRegistrationRequest", true)),
            entry("revealEntryValue", new RequestContract("RevealReasonRequest", false)),
            entry("setEntry", new RequestContract("ManagementCacheSetBody", true)),
            entry("expireEntry", new RequestContract("EntryTtlRequest", true)),
            entry("touchEntry", new RequestContract("EntryTouchRequest", true)),
            entry("previewEntryBulkDelete", new RequestContract("EntryDeleteSelection", true)),
            entry("executeEntryBulkDelete", new RequestContract("ConfirmedEntryDelete", true)),
            entry("setCounter", new RequestContract("ManagementCounterSetBody", true)),
            entry("adjustCounter", new RequestContract("ManagementCounterAdjustBody", true)),
            entry("expireCounter", new RequestContract("CounterTtlRequest", true)),
            entry("previewCounterBulkDelete", new RequestContract("CounterDeleteSelection", true)),
            entry("executeCounterBulkDelete", new RequestContract("ConfirmedCounterDelete", true)),
            entry("revealLockOwner", new RequestContract("RevealReasonRequest", false)),
            entry("forceReleaseLock", new RequestContract("ForceReleaseLockBody", true)),
            entry("createPubSubSubscription", new RequestContract("CreateSubscriptionRequest", true)),
            entry("revealPubSubPayload", new RequestContract("RevealReasonRequest", false)),
            entry("publishPubSubMessage", new RequestContract("PublishRequest", true))
    );

    private static final Set<String> OPERATE_OPERATIONS = Set.of(
            "testUnregisteredSetup", "registerSetup", "connectSetup", "testRegisteredSetup", "detachSetup",
            "forgetSetup", "setEntry", "deleteEntry", "expireEntry", "persistEntry", "touchEntry",
            "previewEntryBulkDelete", "executeEntryBulkDelete", "setCounter", "adjustCounter", "expireCounter",
            "persistCounter", "deleteCounter", "previewCounterBulkDelete", "executeCounterBulkDelete",
            "forceReleaseLock", "publishPubSubMessage");

    private static final Map<String, Set<String>> EXPECTED_OPERATION_PARAMETERS = Map.ofEntries(
            entry("listNamespaces", Set.of("query:prefix", "query:status", "query:sort", "query:cursor", "query:limit")),
            entry("exportNamespaces", Set.of("query:prefix", "query:status", "query:sort", "header:Accept")),
            entry("listEntries", Set.of("query:prefix", "query:valueType", "query:ttlState", "query:cursor", "query:limit", "query:sort")),
            entry("getEntry", Set.of("query:includeExpired")),
            entry("setEntry", Set.of("header:If-Match", "header:If-None-Match")),
            entry("deleteEntry", Set.of("header:If-Match")),
            entry("expireEntry", Set.of("header:If-Match")),
            entry("persistEntry", Set.of("header:If-Match")),
            entry("touchEntry", Set.of("header:If-Match")),
            entry("listCounters", Set.of("query:namespace", "query:prefix", "query:ttlState", "query:cursor", "query:limit", "query:sort")),
            entry("setCounter", Set.of("header:If-Match", "header:If-None-Match")),
            entry("adjustCounter", Set.of("header:If-Match", "header:If-None-Match")),
            entry("expireCounter", Set.of("header:If-Match")),
            entry("persistCounter", Set.of("header:If-Match")),
            entry("deleteCounter", Set.of("header:If-Match")),
            entry("listLocks", Set.of("query:namespace", "query:prefix", "query:leaseState", "query:cursor", "query:limit")),
            entry("forceReleaseLock", Set.of("header:If-Match")),
            entry("streamPubSubMessages", Set.of("header:Last-Event-ID")),
            entry("streamMetrics", Set.of("header:Last-Event-ID")),
            entry("listActivity", Set.of("query:after", "query:limit", "query:namespace", "query:action", "query:outcome")),
            entry("monitoringWebSocket", Set.of("query:setupId", "query:afterEventId"))
    );

    private static final Map<String, SuccessContract> EXPECTED_SUCCESS = Map.ofEntries(
            entry("getSession", new SuccessContract("200", "CurrentSession", "N")),
            entry("exchangeLocalToken", new SuccessContract("200", "CurrentSession", "N")),
            entry("deleteLocalSession", new SuccessContract("204", null, "C")),
            entry("listSetups", new SuccessContract("200", "SetupSummaryList", "C")),
            entry("testUnregisteredSetup", new SuccessContract("200", "SetupConnectionTest", "C")),
            entry("registerSetup", new SuccessContract("201", "SetupSummary", "C")),
            entry("getSetup", new SuccessContract("200", "SetupDetails", "C")),
            entry("connectSetup", new SuccessContract("200", "SetupSummary", "C")),
            entry("testRegisteredSetup", new SuccessContract("200", "SetupConnectionTest", "C")),
            entry("detachSetup", new SuccessContract("204", null, "C")),
            entry("forgetSetup", new SuccessContract("204", null, "C")),
            entry("getSetupHealth", new SuccessContract("200", "SetupHealth", "C")),
            entry("getSetupCapabilities", new SuccessContract("200", "SetupCapabilities", "C")),
            entry("getOverview", new SuccessContract("200", "Overview", "C")),
            entry("listNamespaces", new SuccessContract("200", "AdminPageNamespaceStats", "C")),
            entry("exportNamespaces", new SuccessContract(Set.of("200"), "NamespaceExport", "application/json", "C")),
            entry("getNamespace", new SuccessContract("200", "NamespaceDetails", "C")),
            entry("listEntries", new SuccessContract("200", "AdminPageManagementEntryMetadata", "C")),
            entry("getEntry", new SuccessContract("200", "ManagementEntryMetadata", "E")),
            entry("revealEntryValue", new SuccessContract("200", "RevealedEntryValue", "R")),
            entry("setEntry", new SuccessContract(Set.of("200", "201"), "ManagementSetResult", "E")),
            entry("deleteEntry", new SuccessContract("204", null, "C")),
            entry("expireEntry", new SuccessContract("200", "ManagementEntryMetadata", "E")),
            entry("persistEntry", new SuccessContract("200", "ManagementEntryMetadata", "E")),
            entry("touchEntry", new SuccessContract("200", "ManagementEntryMetadata", "E")),
            entry("previewEntryBulkDelete", new SuccessContract("200", "BulkDeletePreview", "C")),
            entry("executeEntryBulkDelete", new SuccessContract("200", "BulkDeleteResult", "C")),
            entry("listCounters", new SuccessContract("200", "AdminPageCounterEntry", "C")),
            entry("getCounter", new SuccessContract("200", "CounterEntry", "E")),
            entry("setCounter", new SuccessContract(Set.of("200", "201"), "CounterEntry", "E")),
            entry("adjustCounter", new SuccessContract("200", "CounterEntry", "E")),
            entry("expireCounter", new SuccessContract("200", "CounterEntry", "E")),
            entry("persistCounter", new SuccessContract("200", "CounterEntry", "E")),
            entry("deleteCounter", new SuccessContract("204", null, "C")),
            entry("previewCounterBulkDelete", new SuccessContract("200", "BulkDeletePreview", "C")),
            entry("executeCounterBulkDelete", new SuccessContract("200", "BulkDeleteResult", "C")),
            entry("listLocks", new SuccessContract("200", "AdminPageLockState", "C")),
            entry("getLock", new SuccessContract("200", "LockState", "E")),
            entry("revealLockOwner", new SuccessContract("200", "RevealedLockOwner", "R")),
            entry("forceReleaseLock", new SuccessContract("204", null, "C")),
            entry("createPubSubSubscription", new SuccessContract("201", "SubscriptionSummary", "C")),
            entry("streamPubSubMessages", new SuccessContract(Set.of("200"), "PubSubSseEvent", "text/event-stream", "S")),
            entry("revealPubSubPayload", new SuccessContract("200", "RevealedPubSubPayload", "R")),
            entry("deletePubSubSubscription", new SuccessContract("204", null, "C")),
            entry("publishPubSubMessage", new SuccessContract("200", "PublishAccepted", "C")),
            entry("getDatabaseMonitoring", new SuccessContract("200", "DatabaseMonitoring", "C")),
            entry("getRuntimeMonitoring", new SuccessContract("200", "RuntimeMonitoring", "C")),
            entry("streamMetrics", new SuccessContract(Set.of("200"), "MetricsSseEvent", "text/event-stream", "S")),
            entry("listActivity", new SuccessContract("200", "ActivityPage", "C")),
            entry("monitoringWebSocket", new SuccessContract("101", null, "W"))
    );

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

    private static final Set<String> HTTP_METHODS = Set.of(
            "get", "put", "post", "delete", "patch", "head", "options", "trace");
}
