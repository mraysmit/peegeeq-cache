package dev.mars.peegeeq.cache.rest.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.mars.peegeeq.cache.api.management.AdminPage;
import dev.mars.peegeeq.cache.api.management.CounterEntry;
import dev.mars.peegeeq.cache.api.management.CounterQuery;
import dev.mars.peegeeq.cache.api.management.DatabaseStats;
import dev.mars.peegeeq.cache.api.management.EntryQuery;
import dev.mars.peegeeq.cache.api.management.ExpiryStats;
import dev.mars.peegeeq.cache.api.management.LockQuery;
import dev.mars.peegeeq.cache.api.management.ManagementEntryMetadata;
import dev.mars.peegeeq.cache.api.management.ManagementActivityEvent;
import dev.mars.peegeeq.cache.api.management.ManagementActivityPage;
import dev.mars.peegeeq.cache.api.management.ManagementActivityQuery;
import dev.mars.peegeeq.cache.api.management.ManagementAuditTerminalOutcome;
import dev.mars.peegeeq.cache.api.management.ManagementDatabaseMonitoring;
import dev.mars.peegeeq.cache.api.management.ManagementAuditQueueState;
import dev.mars.peegeeq.cache.api.management.ManagementLockMetadata;
import dev.mars.peegeeq.cache.api.management.ManagementOverview;
import dev.mars.peegeeq.cache.api.management.ManagementRuntimeLifecycleState;
import dev.mars.peegeeq.cache.api.management.ManagementRuntimeMonitoring;
import dev.mars.peegeeq.cache.api.management.ManagementTtl;
import dev.mars.peegeeq.cache.api.management.ManagementTtlBucket;
import dev.mars.peegeeq.cache.api.management.ManagementTtlFilter;
import dev.mars.peegeeq.cache.api.management.NamespaceDetails;
import dev.mars.peegeeq.cache.api.management.NamespaceQuery;
import dev.mars.peegeeq.cache.api.management.NamespaceStats;
import dev.mars.peegeeq.cache.api.model.CacheKey;
import dev.mars.peegeeq.cache.api.model.LockKey;
import dev.mars.peegeeq.cache.api.model.ValueType;
import dev.mars.peegeeq.cache.rest.protocol.ManagementEntityTagCodec;
import dev.mars.peegeeq.cache.rest.protocol.ManagementIdentifierCodec;
import dev.mars.peegeeq.cache.rest.protocol.ManagementProblem;
import dev.mars.peegeeq.cache.rest.protocol.ManagementProtocolException;
import dev.mars.peegeeq.cache.rest.protocol.ManagementWireRules;
import dev.mars.peegeeq.cache.rest.security.ManagementSecurityException;
import io.vertx.core.Future;
import io.vertx.core.http.HttpServerRequest;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Database-backed, metadata-only inspection routes. */
public final class SetupInspectionRoutes implements ManagementRequestRouter {

    private static final Pattern NAMESPACES = Pattern.compile(
            "^/api/v1/setups/([^/]+)/namespaces$");
    private static final Pattern OVERVIEW = Pattern.compile(
            "^/api/v1/setups/([^/]+)/overview$");
    private static final Pattern DATABASE_MONITORING = Pattern.compile(
            "^/api/v1/setups/([^/]+)/monitoring/database$");
    private static final Pattern RUNTIME_MONITORING = Pattern.compile(
            "^/api/v1/setups/([^/]+)/monitoring/runtime$");
    private static final Pattern ACTIVITY = Pattern.compile(
            "^/api/v1/setups/([^/]+)/activity$");
    private static final Pattern NAMESPACE = Pattern.compile(
            "^/api/v1/setups/([^/]+)/namespaces/([^/]+)$");
    private static final Pattern NAMESPACE_EXPORT = Pattern.compile(
            "^/api/v1/setups/([^/]+)/namespaces/export$");
    private static final Pattern ENTRIES = Pattern.compile(
            "^/api/v1/setups/([^/]+)/namespaces/([^/]+)/entries$");
    private static final Pattern ENTRY = Pattern.compile(
            "^/api/v1/setups/([^/]+)/namespaces/([^/]+)/entries/([^/]+)$");
    private static final Pattern COUNTERS = Pattern.compile(
            "^/api/v1/setups/([^/]+)/counters$");
    private static final Pattern COUNTER = Pattern.compile(
            "^/api/v1/setups/([^/]+)/namespaces/([^/]+)/counters/([^/]+)$");
    private static final Pattern LOCKS = Pattern.compile(
            "^/api/v1/setups/([^/]+)/locks$");
    private static final Pattern LOCK = Pattern.compile(
            "^/api/v1/setups/([^/]+)/namespaces/([^/]+)/locks/([^/]+)$");
    private static final Pattern SETUP_ID = Pattern.compile("[a-z][a-z0-9-]{0,62}");

    private final SetupRegistry registry;
    private final ManagementRequestAuthenticator authenticator;
    private final ManagementRuntimeMonitor runtimeMonitor;
    private final Supplier<ManagementAuditQueueState> auditQueue;
    private final ManagementActivityStore activity;
    private final ObjectMapper json = new ObjectMapper();

    public SetupInspectionRoutes(
            SetupRegistry registry,
            ManagementRequestAuthenticator authenticator) {
        this(
                registry,
                authenticator,
                new ManagementRuntimeMonitor(),
                () -> new ManagementAuditQueueState(0, 0, false),
                new ManagementActivityStore(10_000));
    }

    public SetupInspectionRoutes(
            SetupRegistry registry,
            ManagementRequestAuthenticator authenticator,
            ManagementRuntimeMonitor runtimeMonitor,
            Supplier<ManagementAuditQueueState> auditQueue) {
        this(registry, authenticator, runtimeMonitor, auditQueue,
                new ManagementActivityStore(10_000));
    }

    public SetupInspectionRoutes(
            SetupRegistry registry,
            ManagementRequestAuthenticator authenticator,
            ManagementRuntimeMonitor runtimeMonitor,
            Supplier<ManagementAuditQueueState> auditQueue,
            ManagementActivityStore activity) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.authenticator = Objects.requireNonNull(authenticator, "authenticator");
        this.runtimeMonitor = Objects.requireNonNull(runtimeMonitor, "runtimeMonitor");
        this.auditQueue = Objects.requireNonNull(auditQueue, "auditQueue");
        this.activity = Objects.requireNonNull(activity, "activity");
        this.runtimeMonitor.lifecycle(ManagementRuntimeLifecycleState.RUNNING);
    }

    @Override
    public boolean route(HttpServerRequest request) {
        if (!request.method().name().equals("GET")) {
            return false;
        }
        Match match = match(request.path());
        if (match == null) {
            return false;
        }
        ManagementRuntimeMonitor.Operation operation =
                runtimeMonitor.begin(match.resource().operationName);
        request.response().endHandler(ignored -> {
            if (request.response().getStatusCode() >= 500) {
                operation.failed();
            } else {
                operation.succeeded();
            }
        });
        request.response().exceptionHandler(ignored -> operation.failed());
        request.response().closeHandler(ignored -> operation.failed());
        String correlationId = correlationId(request);
        try {
            AuthenticatedManagementRequest authenticated = authenticator.authenticate(request);
            requireViewer(authenticated);
            requireCanonicalSetupId(match.setupId());
            routeInspection(request, match, correlationId);
        } catch (Throwable failure) {
            writeProblem(request, failure, correlationId);
        }
        return true;
    }

    private void routeInspection(HttpServerRequest request, Match match, String correlationId) {
        if (match.resource() == Resource.ACTIVITY) {
            writeJson(
                    request,
                    activityPage(match.setupId(), activityQuery(request)),
                    correlationId);
            return;
        }
        var management = registry.management(match.setupId());
        switch (match.resource()) {
            case OVERVIEW -> overviewSnapshot(match.setupId())
                    .onSuccess(value -> writeJson(request, value, correlationId))
                    .onFailure(failure -> writeProblem(request, failure, correlationId));
            case DATABASE_MONITORING -> management.databaseMonitoring()
                    .compose(value -> registry.health(match.setupId())
                            .map(health -> databaseMonitoring(value, health)))
                    .onSuccess(value -> writeJson(request, value, correlationId))
                    .onFailure(failure -> writeProblem(request, failure, correlationId));
            case RUNTIME_MONITORING -> writeJson(
                    request,
                    runtimeSnapshot(match.setupId()),
                    correlationId);
            case ACTIVITY -> throw new IllegalStateException("Activity is routed before database access");
            case NAMESPACES -> management.namespaces(namespaceQuery(request))
                    .onSuccess(page -> writeJson(request, namespacePage(page), correlationId))
                    .onFailure(failure -> writeProblem(request, failure, correlationId));
            case NAMESPACE -> management.namespace(decodeNamespace(match.encodedNamespace()))
                    .onSuccess(value -> writeJson(request, namespaceDetails(value), correlationId))
                    .onFailure(failure -> writeProblem(request, failure, correlationId));
            case NAMESPACE_EXPORT -> {
                NamespaceQuery query = namespaceQuery(request);
                String mediaType = exportMediaType(request.getHeader("Accept"));
                collectNamespaces(management, query, new ArrayList<>())
                        .onSuccess(export -> writeNamespaceExport(
                                request, export, mediaType, correlationId))
                        .onFailure(failure -> writeProblem(request, failure, correlationId));
            }
            case ENTRIES -> management.entries(entryQuery(request, decodeNamespace(match.encodedNamespace())))
                    .onSuccess(page -> writeJson(request, entryPage(page), correlationId))
                    .onFailure(failure -> writeProblem(request, failure, correlationId));
            case ENTRY -> management.entry(cacheKey(match), includeExpired(request))
                    .onSuccess(value -> writeVersionedJson(
                            request, entry(value), value.version(), correlationId))
                    .onFailure(failure -> writeProblem(request, failure, correlationId));
            case COUNTERS -> management.counters(counterQuery(request))
                    .onSuccess(page -> writeJson(request, counterPage(page), correlationId))
                    .onFailure(failure -> writeProblem(request, failure, correlationId));
            case COUNTER -> management.counter(cacheKey(match))
                    .onSuccess(value -> writeVersionedJson(
                            request, counter(value), value.version(), correlationId))
                    .onFailure(failure -> writeProblem(request, failure, correlationId));
            case LOCKS -> management.locks(lockQuery(request))
                    .onSuccess(page -> writeJson(request, lockPage(page), correlationId))
                    .onFailure(failure -> writeProblem(request, failure, correlationId));
            case LOCK -> management.lock(lockKey(match))
                    .onSuccess(value -> writeVersionedJson(
                            request, lock(value), value.version(), correlationId))
                    .onFailure(failure -> writeProblem(request, failure, correlationId));
        }
    }

    Future<ObjectNode> overviewSnapshot(String setupId) {
        var management = registry.management(setupId);
        return management.overview()
                .compose(value -> management.databaseStats()
                        .compose(database -> management.expiryStats()
                                .compose(expiry -> registry.health(setupId)
                                        .map(health -> overview(
                                                value,
                                                database,
                                                expiry,
                                                health,
                                                registry.details(setupId).runtime())))));
    }

    ObjectNode runtimeSnapshot(String setupId) {
        return runtimeMonitoring(registry.details(setupId).runtime());
    }

    ObjectNode healthSnapshot(SetupHealth health) {
        return health(health);
    }

    private static Match match(String path) {
        Matcher matcher = OVERVIEW.matcher(path);
        if (matcher.matches()) {
            return new Match(Resource.OVERVIEW, matcher.group(1), null, null);
        }
        matcher = DATABASE_MONITORING.matcher(path);
        if (matcher.matches()) {
            return new Match(Resource.DATABASE_MONITORING, matcher.group(1), null, null);
        }
        matcher = RUNTIME_MONITORING.matcher(path);
        if (matcher.matches()) {
            return new Match(Resource.RUNTIME_MONITORING, matcher.group(1), null, null);
        }
        matcher = ACTIVITY.matcher(path);
        if (matcher.matches()) {
            return new Match(Resource.ACTIVITY, matcher.group(1), null, null);
        }
        matcher = NAMESPACES.matcher(path);
        if (matcher.matches()) {
            return new Match(Resource.NAMESPACES, matcher.group(1), null, null);
        }
        matcher = ENTRIES.matcher(path);
        if (matcher.matches()) {
            return new Match(Resource.ENTRIES, matcher.group(1), matcher.group(2), null);
        }
        matcher = ENTRY.matcher(path);
        if (matcher.matches()) {
            return new Match(Resource.ENTRY, matcher.group(1), matcher.group(2), matcher.group(3));
        }
        matcher = COUNTERS.matcher(path);
        if (matcher.matches()) {
            return new Match(Resource.COUNTERS, matcher.group(1), null, null);
        }
        matcher = COUNTER.matcher(path);
        if (matcher.matches()) {
            return new Match(Resource.COUNTER, matcher.group(1), matcher.group(2), matcher.group(3));
        }
        matcher = LOCKS.matcher(path);
        if (matcher.matches()) {
            return new Match(Resource.LOCKS, matcher.group(1), null, null);
        }
        matcher = LOCK.matcher(path);
        if (matcher.matches()) {
            return new Match(Resource.LOCK, matcher.group(1), matcher.group(2), matcher.group(3));
        }
        matcher = NAMESPACE_EXPORT.matcher(path);
        if (matcher.matches()) {
            return new Match(Resource.NAMESPACE_EXPORT, matcher.group(1), null, null);
        }
        matcher = NAMESPACE.matcher(path);
        if (matcher.matches() && !matcher.group(2).equals("export")) {
            return new Match(Resource.NAMESPACE, matcher.group(1), matcher.group(2), null);
        }
        return null;
    }

    private static NamespaceQuery namespaceQuery(HttpServerRequest request) {
        try {
            String prefix = request.getParam("prefix");
            String status = request.getParam("status");
            String sort = request.getParam("sort");
            String cursor = request.getParam("cursor");
            String limit = request.getParam("limit");
            return new NamespaceQuery(
                    prefix,
                    status == null ? null : NamespaceQuery.Status.valueOf(status),
                    namespaceSort(sort),
                    cursor,
                    limit == null ? 50 : Integer.parseInt(limit));
        } catch (RuntimeException failure) {
            throw new ManagementProtocolException(
                    400, "VALIDATION_FAILED", "Invalid namespace query");
        }
    }

    private static NamespaceQuery.Sort namespaceSort(String value) {
        if (value == null || value.equals("namespace:asc")) {
            return NamespaceQuery.Sort.NAMESPACE_ASC;
        }
        if (value.equals("entryCount:desc")) {
            return NamespaceQuery.Sort.ENTRY_COUNT_DESC;
        }
        throw new IllegalArgumentException("unsupported namespace sort");
    }

    private Future<NamespaceExportResult> collectNamespaces(
            dev.mars.peegeeq.cache.api.management.ManagementService management,
            NamespaceQuery template,
            List<NamespaceStats> collected) {
        int remaining = 10_000 - collected.size();
        NamespaceQuery pageQuery = new NamespaceQuery(
                template.prefix(),
                template.status(),
                template.sort(),
                template.cursor(),
                Math.min(200, remaining));
        return management.namespaces(pageQuery).compose(page -> {
            collected.addAll(page.items());
            if (!page.hasMore()) {
                return Future.succeededFuture(new NamespaceExportResult(collected, false, Instant.now()));
            }
            if (collected.size() >= 10_000) {
                return Future.succeededFuture(new NamespaceExportResult(collected, true, Instant.now()));
            }
            NamespaceQuery next = new NamespaceQuery(
                    template.prefix(),
                    template.status(),
                    template.sort(),
                    page.nextCursor(),
                    Math.min(200, 10_000 - collected.size()));
            return collectNamespaces(management, next, collected);
        });
    }

    private static String exportMediaType(String accept) {
        if (accept == null || accept.isBlank() || accept.equals("*/*")
                || accept.equals("application/json")) {
            return "application/json";
        }
        if (accept.equals("text/csv")) {
            return "text/csv";
        }
        throw new ManagementProtocolException(
                406, "NOT_ACCEPTABLE", "Namespace export supports application/json or text/csv");
    }

    private static EntryQuery entryQuery(HttpServerRequest request, String namespace) {
        try {
            requireKeySort(request.getParam("sort"));
            return new EntryQuery(
                    namespace,
                    request.getParam("prefix"),
                    enumValue(ValueType.class, request.getParam("valueType")),
                    enumValue(ManagementTtlFilter.class, request.getParam("ttlState")),
                    EntryQuery.Sort.KEY_ASC,
                    request.getParam("cursor"),
                    limit(request));
        } catch (RuntimeException failure) {
            throw invalidQuery("entry", failure);
        }
    }

    private static CounterQuery counterQuery(HttpServerRequest request) {
        try {
            requireKeySort(request.getParam("sort"));
            return new CounterQuery(
                    request.getParam("namespace"),
                    request.getParam("prefix"),
                    enumValue(ManagementTtlFilter.class, request.getParam("ttlState")),
                    CounterQuery.Sort.KEY_ASC,
                    request.getParam("cursor"),
                    limit(request));
        } catch (RuntimeException failure) {
            throw invalidQuery("counter", failure);
        }
    }

    private static LockQuery lockQuery(HttpServerRequest request) {
        try {
            return new LockQuery(
                    request.getParam("namespace"),
                    request.getParam("prefix"),
                    enumValue(LockQuery.LeaseState.class, request.getParam("leaseState")),
                    request.getParam("cursor"),
                    limit(request));
        } catch (RuntimeException failure) {
            throw invalidQuery("lock", failure);
        }
    }

    private static ManagementActivityQuery activityQuery(HttpServerRequest request) {
        try {
            String limit = request.getParam("limit");
            return new ManagementActivityQuery(
                    request.getParam("after"),
                    limit == null ? 50 : Integer.parseInt(limit),
                    request.getParam("namespace"),
                    request.getParam("action"),
                    enumValue(ManagementAuditTerminalOutcome.class, request.getParam("outcome")));
        } catch (RuntimeException failure) {
            throw invalidQuery("activity", failure);
        }
    }

    private static int limit(HttpServerRequest request) {
        String value = request.getParam("limit");
        return value == null ? 50 : Integer.parseInt(value);
    }

    private static boolean includeExpired(HttpServerRequest request) {
        String value = request.getParam("includeExpired");
        if (value == null || value.equals("false")) {
            return false;
        }
        if (value.equals("true")) {
            return true;
        }
        throw invalidQuery("entry", null);
    }

    private static void requireKeySort(String value) {
        if (value != null && !value.equals("key:asc")) {
            throw new IllegalArgumentException("unsupported sort");
        }
    }

    private static <E extends Enum<E>> E enumValue(Class<E> type, String value) {
        return value == null ? null : Enum.valueOf(type, value);
    }

    private static ManagementProtocolException invalidQuery(String resource, Throwable cause) {
        return new ManagementProtocolException(
                400, "VALIDATION_FAILED", "Invalid " + resource + " query");
    }

    private static CacheKey cacheKey(Match match) {
        return new CacheKey(
                decodeNamespace(match.encodedNamespace()),
                decodeKey(match.encodedKey()));
    }

    private static LockKey lockKey(Match match) {
        return new LockKey(
                decodeNamespace(match.encodedNamespace()),
                decodeKey(match.encodedKey()));
    }

    private ObjectNode namespacePage(AdminPage<NamespaceStats> page) {
        ObjectNode node = json.createObjectNode();
        ArrayNode items = node.putArray("items");
        page.items().forEach(item -> items.add(namespaceStats(item)));
        if (page.nextCursor() == null) {
            node.putNull("nextCursor");
        } else {
            node.put("nextCursor", page.nextCursor());
        }
        node.put("hasMore", page.hasMore());
        return node;
    }

    private ObjectNode namespaceDetails(NamespaceDetails details) {
        ObjectNode node = json.createObjectNode();
        node.set("stats", namespaceStats(details.stats()));
        ObjectNode valueTypes = node.putObject("valueTypeCounts");
        for (ValueType type : ValueType.values()) {
            valueTypes.put(type.name(), Long.toString(details.valueTypeCounts().getOrDefault(type, 0L)));
        }
        ObjectNode ttlStates = node.putObject("ttlStateCounts");
        for (ManagementTtl.State state : ManagementTtl.State.values()) {
            ttlStates.put(state.name(),
                    Long.toString(details.ttlStateCounts().getOrDefault(state, 0L)));
        }
        ArrayNode ttl = node.putArray("ttlDistribution");
        for (ManagementTtlBucket bucket : ManagementTtlBucket.values()) {
            long count = details.ttlDistribution().getOrDefault(bucket, 0L);
            if (count > 0) {
                ttl.add(ttlBucket(bucket.name(), count));
            }
        }
        return node;
    }

    private ObjectNode overview(
            ManagementOverview overview,
            DatabaseStats database,
            ExpiryStats expiryStats,
            SetupHealth health,
            SetupRuntimeSummary runtime) {
        ObjectNode node = json.createObjectNode();
        node.put("scope", "DATABASE");
        node.put("observedAt", overview.observedAt().toString());
        node.set("health", health(health));
        ObjectNode totals = node.putObject("totals");
        totals.put("namespaceCount", Long.toString(overview.namespaceCount()));
        totals.put("liveEntryCount", Long.toString(overview.liveEntryCount()));
        totals.put("liveCounterCount", Long.toString(overview.liveCounterCount()));
        totals.put("activeLockCount", Long.toString(overview.activeLockCount()));
        totals.put("expiredEntryCount", Long.toString(overview.expiredEntryCount()));
        totals.put("expiredCounterCount", Long.toString(overview.expiredCounterCount()));
        totals.set("schemaBytes", availableLong(overview.schemaBytes()));
        ObjectNode databaseNode = node.putObject("databaseStats");
        databaseNode.put("observedAt", database.observedAt().toString());
        databaseNode.set("databaseBytes", availableLong(database.databaseBytes()));
        databaseNode.set("schemaBytes", availableLong(database.schemaBytes()));
        ObjectNode expiryStatsNode = node.putObject("expiryStats");
        expiryStatsNode.put("observedAt", expiryStats.observedAt().toString());
        expiryStatsNode.put("expiredEntryCount", Long.toString(expiryStats.expiredEntryCount()));
        expiryStatsNode.put("expiredCounterCount", Long.toString(expiryStats.expiredCounterCount()));
        expiryStatsNode.set("oldestLagMillis", availableLong(expiryStats.oldestLagMillis()));
        ObjectNode expiry = node.putObject("expiry");
        if (overview.oldestExpiredRowLagMillis().availability()
                == dev.mars.peegeeq.cache.api.management.Availability.AVAILABLE) {
            expiry.put("oldestExpiredRowLagMillis",
                    overview.oldestExpiredRowLagMillis().value());
        } else {
            expiry.putNull("oldestExpiredRowLagMillis");
        }
        expiry.put("sweeperEnabled", runtime.expirySweeperEnabled());
        expiry.putNull("lastSweepAt");
        expiry.put("lastSweepDeletedRows", "0");
        ObjectNode valueTypes = node.putObject("valueTypeCounts");
        for (ValueType type : ValueType.values()) {
            valueTypes.put(type.name(),
                    Long.toString(overview.valueTypeCounts().getOrDefault(type, 0L)));
        }
        ArrayNode top = node.putArray("topNamespaces");
        overview.topNamespaces().forEach(item -> top.add(namespaceStats(item)));
        return node;
    }

    private ObjectNode databaseMonitoring(
            ManagementDatabaseMonitoring monitoring,
            SetupHealth health) {
        ObjectNode node = json.createObjectNode();
        node.put("scope", "DATABASE");
        node.put("observedAt", monitoring.observedAt().toString());
        node.set("health", health(health));
        node.set("tableBytes", availableLong(monitoring.tableBytes()));
        node.set("indexBytes", availableLong(monitoring.indexBytes()));
        node.set("schemaBytes", availableLong(monitoring.schemaBytes()));
        node.set("liveRows", availableLong(monitoring.liveRows()));
        node.set("expiredRows", availableLong(monitoring.expiredRows()));
        node.set("deadTuples", availableLong(monitoring.deadTuples()));
        putInstant(node, "lastVacuumAt", monitoring.lastVacuumAt());
        putInstant(node, "lastAutovacuumAt", monitoring.lastAutovacuumAt());
        node.set("databaseConnections", availableLong(monitoring.databaseConnections()));
        node.set("cacheConnections", availableLong(monitoring.cacheConnections()));
        node.put("expiryBacklog", Long.toString(monitoring.expiryBacklog()));
        if (monitoring.oldestExpiredRowLagMillis() == null) {
            node.putNull("oldestExpiredRowLagMillis");
        } else {
            node.put("oldestExpiredRowLagMillis", monitoring.oldestExpiredRowLagMillis());
        }
        return node;
    }

    private ObjectNode runtimeMonitoring(SetupRuntimeSummary runtime) {
        ManagementRuntimeMonitoring monitoring = runtimeMonitor.snapshot(
                runtime.poolMaxSize(),
                runtime.expirySweeperEnabled(),
                null,
                auditQueue.get());
        ObjectNode node = json.createObjectNode();
        node.put("scope", "MANAGEMENT_RUNTIME");
        node.put("observedAt", monitoring.observedAt().toString());
        node.put("lifecycleState", monitoring.lifecycleState().name());
        ObjectNode pool = node.putObject("pool");
        pool.set("active", availableLong(monitoring.pool().active()));
        pool.set("idle", availableLong(monitoring.pool().idle()));
        pool.set("pending", availableLong(monitoring.pool().pending()));
        pool.set("maximum", availableLong(monitoring.pool().maximum()));
        node.put("activeOperations", Long.toString(monitoring.activeOperations()));
        node.put("pubSubSubscriptions", Long.toString(monitoring.pubSubSubscriptions()));
        node.put("sseClients", Long.toString(monitoring.sseClients()));
        node.put("webSocketClients", Long.toString(monitoring.webSocketClients()));
        node.put("retainedPayloadBytes", Long.toString(monitoring.retainedPayloadBytes()));
        ObjectNode audit = node.putObject("auditQueue");
        audit.put("depth", Long.toString(monitoring.auditQueue().depth()));
        audit.put("capacity", Long.toString(monitoring.auditQueue().capacity()));
        audit.put("acceptingMutations", monitoring.auditQueue().acceptingMutations());
        ObjectNode sweeper = node.putObject("expirySweeper");
        sweeper.put("ownedByRuntime", monitoring.expirySweeper().ownedByRuntime());
        sweeper.put("running", monitoring.expirySweeper().running());
        putInstant(sweeper, "lastSweepAt", monitoring.expirySweeper().lastSweepAt());
        ArrayNode operations = node.putArray("operations");
        monitoring.operations().forEach(value -> {
            ObjectNode aggregate = operations.addObject();
            aggregate.put("operation", value.operation());
            aggregate.put("status", value.status().name());
            aggregate.put("count", Long.toString(value.count()));
            aggregate.put("errorCount", Long.toString(value.errorCount()));
            aggregate.put("latencyMillis", Long.toString(value.latencyMillis()));
        });
        return node;
    }

    private ObjectNode activityPage(String setupId, ManagementActivityQuery query) {
        ManagementActivityPage page;
        try {
            page = activity.recent(setupId, query);
        } catch (IllegalArgumentException failure) {
            throw invalidQuery("activity", failure);
        }
        ObjectNode node = json.createObjectNode();
        ArrayNode items = node.putArray("items");
        page.items().forEach(value -> items.add(activityEvent(value)));
        if (page.nextAfter() == null) {
            node.putNull("nextAfter");
        } else {
            node.put("nextAfter", page.nextAfter());
        }
        node.put("hasMore", page.hasMore());
        return node;
    }

    private ObjectNode activityEvent(ManagementActivityEvent event) {
        ObjectNode node = json.createObjectNode();
        node.put("eventId", event.eventId());
        node.put("occurredAt", event.occurredAt().toString());
        node.put("actor", event.actor());
        node.put("action", event.action());
        node.put("outcome", event.outcome().name());
        node.put("setupId", event.setupId());
        if (event.namespace() == null) {
            node.putNull("namespace");
        } else {
            node.put("namespace", event.namespace());
        }
        ObjectNode resource = node.putObject("resource");
        resource.put("type", event.resource().type().name());
        if (event.resource().identifier() == null) {
            resource.putNull("identifier");
        } else {
            resource.put("identifier", event.resource().identifier());
        }
        node.put("summary", event.summary());
        node.put("correlationId", event.correlationId());
        return node;
    }

    private ObjectNode health(SetupHealth health) {
        ObjectNode node = json.createObjectNode();
        node.put("status", health.status().name());
        node.put("schemaReady", health.schemaReady());
        node.put("latencyMillis", health.latencyMillis());
        node.put("checkedAt", health.checkedAt().toString());
        node.put("detail", health.detail());
        return node;
    }

    private static void putInstant(ObjectNode node, String field, Instant value) {
        if (value == null) {
            node.putNull(field);
        } else {
            node.put(field, value.toString());
        }
    }

    private ObjectNode availableLong(
            dev.mars.peegeeq.cache.api.management.AvailableValue<Long> available) {
        ObjectNode node = json.createObjectNode();
        node.put("availability", available.availability().name());
        if (available.availability()
                == dev.mars.peegeeq.cache.api.management.Availability.AVAILABLE) {
            node.put("value", Long.toString(available.value()));
            node.putNull("reason");
        } else {
            node.putNull("value");
            node.put("reason", available.reason());
        }
        return node;
    }

    private void writeNamespaceExport(
            HttpServerRequest request,
            NamespaceExportResult export,
            String mediaType,
            String correlationId) {
        if (mediaType.equals("text/csv")) {
            StringBuilder csv = new StringBuilder(
                    "namespace,liveEntryCount,liveCounterCount,activeLockCount,"
                            + "expiringEntryCount,expiredEntryCount,estimatedStorageBytes,observedAt\r\n");
            export.items().forEach(value -> csv
                    .append(csvField(value.namespace())).append(',')
                    .append(value.liveEntryCount()).append(',')
                    .append(value.liveCounterCount()).append(',')
                    .append(value.activeLockCount()).append(',')
                    .append(value.expiringEntryCount()).append(',')
                    .append(value.expiredEntryCount()).append(',')
                    .append(value.estimatedStorageBytes()).append(',')
                    .append(value.observedAt()).append("\r\n"));
            request.response()
                    .setStatusCode(200)
                    .putHeader("content-type", "text/csv; charset=utf-8")
                    .putHeader("x-correlation-id", correlationId)
                    .end(csv.toString());
            return;
        }
        ObjectNode node = json.createObjectNode();
        ArrayNode items = node.putArray("items");
        export.items().forEach(item -> items.add(namespaceStats(item)));
        node.put("truncated", export.truncated());
        node.put("exportedAt", export.exportedAt().toString());
        writeJson(request, node, correlationId);
    }

    private static String csvField(String value) {
        if (value.indexOf(',') < 0 && value.indexOf('"') < 0
                && value.indexOf('\r') < 0 && value.indexOf('\n') < 0) {
            return value;
        }
        return '"' + value.replace("\"", "\"\"") + '"';
    }

    private ObjectNode entryPage(AdminPage<ManagementEntryMetadata> page) {
        ObjectNode node = pageEnvelope(page.nextCursor(), page.hasMore());
        ArrayNode items = (ArrayNode) node.get("items");
        page.items().forEach(item -> items.add(entry(item)));
        return node;
    }

    private ObjectNode counterPage(AdminPage<CounterEntry> page) {
        ObjectNode node = pageEnvelope(page.nextCursor(), page.hasMore());
        ArrayNode items = (ArrayNode) node.get("items");
        page.items().forEach(item -> items.add(counter(item)));
        return node;
    }

    private ObjectNode lockPage(AdminPage<ManagementLockMetadata> page) {
        ObjectNode node = pageEnvelope(page.nextCursor(), page.hasMore());
        ArrayNode items = (ArrayNode) node.get("items");
        page.items().forEach(item -> items.add(lock(item)));
        return node;
    }

    private ObjectNode pageEnvelope(String nextCursor, boolean hasMore) {
        ObjectNode node = json.createObjectNode();
        node.putArray("items");
        if (nextCursor == null) {
            node.putNull("nextCursor");
        } else {
            node.put("nextCursor", nextCursor);
        }
        node.put("hasMore", hasMore);
        return node;
    }

    private ObjectNode entry(ManagementEntryMetadata value) {
        ObjectNode node = encodedKey(value.key().namespace(), value.key().key());
        node.put("valueType", value.valueType().name());
        node.put("sizeBytes", Long.toString(value.sizeBytes()));
        node.put("version", Long.toString(value.version()));
        node.put("createdAt", value.createdAt().toString());
        node.put("updatedAt", value.updatedAt().toString());
        if (value.lastAccessedAt() == null) {
            node.putNull("lastAccessedAt");
        } else {
            node.put("lastAccessedAt", value.lastAccessedAt().toString());
        }
        node.set("ttl", ttl(value.ttl()));
        return node;
    }

    private ObjectNode counter(CounterEntry value) {
        ObjectNode node = encodedKey(value.key().namespace(), value.key().key());
        node.put("value", Long.toString(value.value()));
        node.put("version", Long.toString(value.version()));
        node.put("createdAt", value.createdAt().toString());
        node.put("updatedAt", value.updatedAt().toString());
        node.set("ttl", ttl(value.ttl()));
        return node;
    }

    private ObjectNode lock(ManagementLockMetadata value) {
        ObjectNode node = encodedKey(value.key().namespace(), value.key().key());
        node.put("fencingToken", Long.toString(value.fencingToken()));
        node.put("version", Long.toString(value.version()));
        node.put("createdAt", value.createdAt().toString());
        node.put("updatedAt", value.updatedAt().toString());
        node.put("leaseExpiresAt", value.leaseExpiresAt().toString());
        node.put("leaseRemainingMillis", value.leaseRemainingMillis());
        node.putObject("owner").put("state", "MASKED");
        return node;
    }

    private ObjectNode encodedKey(String namespace, String key) {
        ObjectNode node = json.createObjectNode();
        node.put("namespace", namespace);
        node.put("encodedNamespace", ManagementIdentifierCodec.encodeNamespace(namespace));
        node.put("key", key);
        node.put("encodedKey", ManagementIdentifierCodec.encodeKey(key));
        return node;
    }

    private ObjectNode ttl(ManagementTtl ttl) {
        ObjectNode node = json.createObjectNode();
        node.put("state", ttl.state().name());
        if (ttl.ttlMillis() == null) {
            node.putNull("ttlMillis");
        } else {
            node.put("ttlMillis", ttl.ttlMillis());
        }
        if (ttl.expiresAt() == null) {
            node.putNull("expiresAt");
        } else {
            node.put("expiresAt", ttl.expiresAt().toString());
        }
        return node;
    }

    private ObjectNode ttlBucket(String range, long count) {
        ObjectNode node = json.createObjectNode();
        node.put("range", range);
        node.put("count", Long.toString(count));
        return node;
    }

    private ObjectNode namespaceStats(NamespaceStats stats) {
        ObjectNode node = json.createObjectNode();
        node.put("namespace", stats.namespace());
        node.put("encodedNamespace", ManagementIdentifierCodec.encodeNamespace(stats.namespace()));
        node.put("liveEntryCount", Long.toString(stats.liveEntryCount()));
        node.put("liveCounterCount", Long.toString(stats.liveCounterCount()));
        node.put("activeLockCount", Long.toString(stats.activeLockCount()));
        node.put("expiringEntryCount", Long.toString(stats.expiringEntryCount()));
        node.put("expiredEntryCount", Long.toString(stats.expiredEntryCount()));
        node.put("estimatedStorageBytes", Long.toString(stats.estimatedStorageBytes()));
        node.put("observedAt", stats.observedAt().toString());
        return node;
    }

    private static String decodeNamespace(String encoded) {
        try {
            return ManagementIdentifierCodec.decodeNamespace(encoded);
        } catch (IllegalArgumentException failure) {
            throw new ManagementProtocolException(
                    400, "INVALID_IDENTIFIER", "Namespace identifier is invalid");
        }
    }

    private static String decodeKey(String encoded) {
        try {
            return ManagementIdentifierCodec.decodeKey(encoded);
        } catch (IllegalArgumentException failure) {
            throw new ManagementProtocolException(
                    400, "INVALID_IDENTIFIER", "Key identifier is invalid");
        }
    }

    private static void requireViewer(AuthenticatedManagementRequest authenticated) {
        if (!authenticated.identity().roles().contains("viewer")
                && !authenticated.identity().roles().contains("operator")) {
            throw new ManagementSecurityException(
                    403, "AUTHORIZATION_FAILED", "Viewer role is required");
        }
    }

    private static void requireCanonicalSetupId(String setupId) {
        if (!SETUP_ID.matcher(setupId).matches()) {
            throw new ManagementProtocolException(
                    400, "INVALID_IDENTIFIER", "Setup identifier is not canonical");
        }
    }

    private static String correlationId(HttpServerRequest request) {
        return ManagementWireRules.correlationId(
                request.getHeader("X-Correlation-ID"), () -> UUID.randomUUID().toString());
    }

    private void writeJson(HttpServerRequest request, ObjectNode body, String correlationId) {
        request.response()
                .setStatusCode(200)
                .putHeader("content-type", "application/json; charset=utf-8")
                .putHeader("x-correlation-id", correlationId)
                .end(body.toString());
    }

    private void writeVersionedJson(
            HttpServerRequest request,
            ObjectNode body,
            long version,
            String correlationId) {
        request.response()
                .setStatusCode(200)
                .putHeader("content-type", "application/json; charset=utf-8")
                .putHeader("etag", ManagementEntityTagCodec.render(version))
                .putHeader("x-correlation-id", correlationId)
                .end(body.toString());
    }

    private void writeProblem(HttpServerRequest request, Throwable failure, String correlationId) {
        ManagementProblem problem = ManagementProblem.from(failure, request.path(), correlationId);
        ObjectNode response = json.createObjectNode();
        response.put("type", problem.type().toString());
        response.put("title", problem.title());
        response.put("status", problem.status());
        response.put("code", problem.code());
        response.put("detail", problem.detail());
        response.put("instance", problem.instance());
        response.put("correlationId", problem.correlationId());
        response.putArray("fieldErrors");
        request.response()
                .setStatusCode(problem.status())
                .putHeader(ManagementHttpServer.ERROR_CODE_HEADER, problem.code())
                .putHeader("content-type", "application/problem+json; charset=utf-8")
                .putHeader("cache-control", "no-store")
                .putHeader("x-correlation-id", correlationId)
                .end(response.toString());
    }

    private enum Resource {
        OVERVIEW("getOverview"),
        DATABASE_MONITORING("getDatabaseMonitoring"),
        RUNTIME_MONITORING("getRuntimeMonitoring"),
        ACTIVITY("listActivity"),
        NAMESPACES("listNamespaces"),
        NAMESPACE("getNamespace"),
        NAMESPACE_EXPORT("exportNamespaces"),
        ENTRIES("listEntries"),
        ENTRY("getEntry"),
        COUNTERS("listCounters"),
        COUNTER("getCounter"),
        LOCKS("listLocks"),
        LOCK("getLock");

        private final String operationName;

        Resource(String operationName) {
            this.operationName = operationName;
        }
    }

    private record Match(
            Resource resource,
            String setupId,
            String encodedNamespace,
            String encodedKey) {
    }

    private record NamespaceExportResult(
            List<NamespaceStats> items,
            boolean truncated,
            Instant exportedAt) {

        private NamespaceExportResult {
            items = List.copyOf(items);
        }
    }
}
