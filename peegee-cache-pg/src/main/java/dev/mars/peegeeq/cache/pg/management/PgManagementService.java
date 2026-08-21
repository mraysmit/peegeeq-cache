package dev.mars.peegeeq.cache.pg.management;

import dev.mars.peegeeq.cache.api.management.*;
import dev.mars.peegeeq.cache.api.model.CacheKey;
import dev.mars.peegeeq.cache.api.model.LockKey;
import io.vertx.core.Future;

import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** PostgreSQL-backed management service, implemented incrementally by management phase. */
public final class PgManagementService implements ManagementService {

    private static final AdminCapabilities CAPABILITIES = new AdminCapabilities(
            EnumSet.of(
                    ManagementCapability.NAMESPACE_INSPECTION,
                    ManagementCapability.ENTRY_INSPECTION,
                    ManagementCapability.COUNTER_INSPECTION,
                    ManagementCapability.LOCK_INSPECTION,
                    ManagementCapability.DATABASE_MONITORING,
                    ManagementCapability.EXPIRY_MONITORING),
            ManagementLimits.defaults());

    private final PgManagementReadRepository repository;
    private final String setupId;
    private final ManagementCursorCodec cursors;

    public PgManagementService(
            PgManagementReadRepository repository,
            String setupId,
            ManagementCursorCodec cursors) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.setupId = Objects.requireNonNull(setupId, "setupId");
        this.cursors = Objects.requireNonNull(cursors, "cursors");
    }

    @Override
    public AdminCapabilities capabilities() {
        return CAPABILITIES;
    }

    @Override
    public Future<AdminPage<NamespaceStats>> namespaces(NamespaceQuery query) {
        Objects.requireNonNull(query, "query");
        ManagementCursorScope scope = namespaceScope(query);
        ManagementCursorPosition position = query.cursor() == null ? null : cursors.decode(query.cursor(), scope);
        validateNamespacePosition(query.sort(), position);
        return repository.namespaces(query, position).map(rows -> namespacePage(query, scope, rows));
    }

    private AdminPage<NamespaceStats> namespacePage(
            NamespaceQuery query,
            ManagementCursorScope scope,
            List<NamespaceStats> rows) {
        boolean hasMore = rows.size() > query.limit();
        List<NamespaceStats> items = hasMore ? List.copyOf(rows.subList(0, query.limit())) : rows;
        if (!hasMore) {
            return new AdminPage<>(items, null, false);
        }
        NamespaceStats last = items.getLast();
        ManagementCursorPosition next = query.sort() == NamespaceQuery.Sort.NAMESPACE_ASC
                ? ManagementCursorPosition.identifier(last.namespace())
                : ManagementCursorPosition.entryCount(last.liveEntryCount(), last.namespace());
        return new AdminPage<>(items, cursors.encode(scope, next), true);
    }

    private ManagementCursorScope namespaceScope(NamespaceQuery query) {
        Map<String, String> filters = new LinkedHashMap<>();
        if (query.prefix() != null) {
            filters.put("prefix", query.prefix());
        }
        if (query.status() != null) {
            filters.put("status", query.status().name());
        }
        return new ManagementCursorScope(
                "namespaces", setupId, null, filters,
                query.sort() == NamespaceQuery.Sort.NAMESPACE_ASC
                        ? "namespace:asc"
                        : "entryCount:desc,namespace:asc");
    }

    private static void validateNamespacePosition(
            NamespaceQuery.Sort sort,
            ManagementCursorPosition position) {
        if (position == null) {
            return;
        }
        ManagementCursorPosition.Kind expected = sort == NamespaceQuery.Sort.NAMESPACE_ASC
                ? ManagementCursorPosition.Kind.IDENTIFIER
                : ManagementCursorPosition.Kind.ENTRY_COUNT_DESC_NAMESPACE_ASC;
        if (position.kind() != expected) {
            throw new ManagementCursorException(
                    ManagementCursorException.Code.SCOPE_MISMATCH,
                    "Cursor position does not match the namespace sort.");
        }
    }

    @Override
    public Future<AdminPage<ManagementEntryMetadata>> entries(EntryQuery query) {
        Objects.requireNonNull(query, "query");
        ManagementCursorScope scope = entryScope(query);
        ManagementCursorPosition position = query.cursor() == null ? null : cursors.decode(query.cursor(), scope);
        if (position != null && position.kind() != ManagementCursorPosition.Kind.IDENTIFIER) {
            throw new ManagementCursorException(
                    ManagementCursorException.Code.SCOPE_MISMATCH,
                    "Cursor position does not match the entry sort.");
        }
        return repository.entries(query, position).map(rows -> identifierPage(
                rows, query.limit(), scope, item -> item.key().key()));
    }

    @Override
    public Future<ManagementEntryMetadata> entry(CacheKey key, boolean includeExpired) {
        Objects.requireNonNull(key, "key");
        return repository.entry(key, includeExpired).compose(result -> result
                .map(Future::succeededFuture)
                .orElseGet(() -> Future.failedFuture(
                        new ManagementNotFoundException(ManagementNotFoundException.Resource.ENTRY))));
    }

    private ManagementCursorScope entryScope(EntryQuery query) {
        Map<String, String> filters = new LinkedHashMap<>();
        if (query.prefix() != null) { filters.put("prefix", query.prefix()); }
        if (query.valueType() != null) { filters.put("valueType", query.valueType().name()); }
        if (query.ttlState() != null) { filters.put("ttlState", query.ttlState().name()); }
        return new ManagementCursorScope("entries", setupId, query.namespace(), filters, "key:asc");
    }

    private <T> AdminPage<T> identifierPage(
            List<T> rows,
            int limit,
            ManagementCursorScope scope,
            java.util.function.Function<T, String> identifier) {
        boolean hasMore = rows.size() > limit;
        List<T> items = hasMore ? List.copyOf(rows.subList(0, limit)) : rows;
        if (!hasMore) {
            return new AdminPage<>(items, null, false);
        }
        String next = cursors.encode(
                scope, ManagementCursorPosition.identifier(identifier.apply(items.getLast())));
        return new AdminPage<>(items, next, true);
    }
    @Override public Future<RevealedEntryValue> revealEntry(RevealEntryRequest request, ManagementActionContext context) { return unavailable(ManagementCapability.ENTRY_REVEAL); }
    @Override public Future<ManagementSetResult> setEntry(ManagementCacheSetRequest request, ManagementActionContext context) { return unavailable(ManagementCapability.ENTRY_MUTATION); }
    @Override public Future<VersionedMutationResult<ManagementEntryMetadata>> expireEntry(VersionedEntryTtlRequest request, ManagementActionContext context) { return unavailable(ManagementCapability.ENTRY_MUTATION); }
    @Override public Future<VersionedMutationResult<ManagementEntryMetadata>> persistEntry(VersionedCacheKeyRequest request, ManagementActionContext context) { return unavailable(ManagementCapability.ENTRY_MUTATION); }
    @Override public Future<VersionedMutationResult<ManagementEntryMetadata>> touchEntry(VersionedEntryTouchRequest request, ManagementActionContext context) { return unavailable(ManagementCapability.ENTRY_MUTATION); }
    @Override public Future<VersionedMutationResult<Void>> deleteEntry(VersionedEntryDeleteRequest request, ManagementActionContext context) { return unavailable(ManagementCapability.ENTRY_MUTATION); }
    @Override
    public Future<AdminPage<CounterEntry>> counters(CounterQuery query) {
        Objects.requireNonNull(query, "query");
        ManagementCursorScope scope = counterScope(query);
        ManagementCursorPosition position = decodeIdentifier(query.cursor(), scope, "counter");
        QualifiedPosition qualified = position == null ? null : decodeQualified(position.identifier());
        return repository.counters(
                        query,
                        qualified == null ? null : qualified.namespace(),
                        qualified == null ? null : qualified.key())
                .map(rows -> identifierPage(
                        rows, query.limit(), scope,
                        item -> encodeQualified(item.key().namespace(), item.key().key())));
    }

    @Override
    public Future<CounterEntry> counter(CacheKey key) {
        Objects.requireNonNull(key, "key");
        return repository.counter(key).compose(result -> result
                .map(Future::succeededFuture)
                .orElseGet(() -> Future.failedFuture(
                        new ManagementNotFoundException(ManagementNotFoundException.Resource.COUNTER))));
    }
    @Override public Future<VersionedMutationResult<CounterEntry>> setCounter(ManagementCounterSetRequest request, ManagementActionContext context) { return unavailable(ManagementCapability.COUNTER_MUTATION); }
    @Override public Future<VersionedMutationResult<CounterEntry>> adjustCounter(ManagementCounterAdjustRequest request, ManagementActionContext context) { return unavailable(ManagementCapability.COUNTER_MUTATION); }
    @Override public Future<VersionedMutationResult<CounterEntry>> expireCounter(VersionedCounterTtlRequest request, ManagementActionContext context) { return unavailable(ManagementCapability.COUNTER_MUTATION); }
    @Override public Future<VersionedMutationResult<CounterEntry>> persistCounter(VersionedCacheKeyRequest request, ManagementActionContext context) { return unavailable(ManagementCapability.COUNTER_MUTATION); }
    @Override public Future<VersionedMutationResult<Void>> deleteCounter(VersionedCounterDeleteRequest request, ManagementActionContext context) { return unavailable(ManagementCapability.COUNTER_MUTATION); }
    @Override
    public Future<AdminPage<ManagementLockMetadata>> locks(LockQuery query) {
        Objects.requireNonNull(query, "query");
        ManagementCursorScope scope = lockScope(query);
        ManagementCursorPosition position = decodeIdentifier(query.cursor(), scope, "lock");
        QualifiedPosition qualified = position == null ? null : decodeQualified(position.identifier());
        return repository.locks(
                        query,
                        qualified == null ? null : qualified.namespace(),
                        qualified == null ? null : qualified.key())
                .map(rows -> identifierPage(
                        rows, query.limit(), scope,
                        item -> encodeQualified(item.key().namespace(), item.key().key())));
    }

    @Override
    public Future<ManagementLockMetadata> lock(LockKey key) {
        Objects.requireNonNull(key, "key");
        return repository.lock(key).compose(result -> result
                .map(Future::succeededFuture)
                .orElseGet(() -> Future.failedFuture(
                        new ManagementNotFoundException(ManagementNotFoundException.Resource.LOCK))));
    }
    @Override public Future<RevealedLockOwner> revealLockOwner(RevealLockOwnerRequest request, ManagementActionContext context) { return unavailable(ManagementCapability.LOCK_REVEAL); }
    @Override public Future<VersionedMutationResult<Void>> forceReleaseLock(ForceReleaseLockRequest request, ManagementActionContext context) { return unavailable(ManagementCapability.FORCE_LOCK_RELEASE); }
    @Override public Future<DatabaseStats> databaseStats() { return repository.databaseStats(); }
    @Override public Future<ExpiryStats> expiryStats() { return repository.expiryStats(); }
    @Override public Future<BulkDeletePreview> previewEntryDelete(EntryDeleteFilter filter, ManagementActionContext context) { return unavailable(ManagementCapability.BULK_DELETE); }
    @Override public Future<BulkDeleteResult> executeEntryDelete(ConfirmedEntryDelete request, ManagementActionContext context) { return unavailable(ManagementCapability.BULK_DELETE); }
    @Override public Future<BulkDeletePreview> previewCounterDelete(CounterDeleteSelection selection, ManagementActionContext context) { return unavailable(ManagementCapability.BULK_DELETE); }
    @Override public Future<BulkDeleteResult> executeCounterDelete(ConfirmedCounterDelete request, ManagementActionContext context) { return unavailable(ManagementCapability.BULK_DELETE); }

    private static <T> Future<T> unavailable(ManagementCapability capability) {
        return Future.failedFuture(new ManagementCapabilityException(capability));
    }

    private ManagementCursorScope counterScope(CounterQuery query) {
        Map<String, String> filters = new LinkedHashMap<>();
        if (query.prefix() != null) { filters.put("prefix", query.prefix()); }
        if (query.ttlState() != null) { filters.put("ttlState", query.ttlState().name()); }
        return new ManagementCursorScope(
                "counters", setupId, query.namespace(), filters, "namespace:asc,key:asc");
    }

    private ManagementCursorScope lockScope(LockQuery query) {
        Map<String, String> filters = new LinkedHashMap<>();
        if (query.prefix() != null) { filters.put("prefix", query.prefix()); }
        if (query.leaseState() != null) { filters.put("leaseState", query.leaseState().name()); }
        return new ManagementCursorScope(
                "locks", setupId, query.namespace(), filters, "namespace:asc,key:asc");
    }

    private ManagementCursorPosition decodeIdentifier(
            String cursor,
            ManagementCursorScope scope,
            String resource) {
        ManagementCursorPosition position = cursor == null ? null : cursors.decode(cursor, scope);
        if (position != null && position.kind() != ManagementCursorPosition.Kind.IDENTIFIER) {
            throw new ManagementCursorException(
                    ManagementCursorException.Code.SCOPE_MISMATCH,
                    "Cursor position does not match the " + resource + " sort.");
        }
        return position;
    }

    private static String encodeQualified(String namespace, String key) {
        return namespace.length() + ":" + namespace + key;
    }

    private static QualifiedPosition decodeQualified(String encoded) {
        int separator = encoded.indexOf(':');
        if (separator < 1) {
            throw invalidQualifiedCursor();
        }
        try {
            int namespaceLength = Integer.parseInt(encoded.substring(0, separator));
            int namespaceStart = separator + 1;
            int keyStart = namespaceStart + namespaceLength;
            if (namespaceLength < 1 || keyStart >= encoded.length()) {
                throw invalidQualifiedCursor();
            }
            return new QualifiedPosition(
                    encoded.substring(namespaceStart, keyStart),
                    encoded.substring(keyStart));
        } catch (NumberFormatException failure) {
            throw invalidQualifiedCursor();
        }
    }

    private static ManagementCursorException invalidQualifiedCursor() {
        return new ManagementCursorException(
                ManagementCursorException.Code.INVALID_CURSOR,
                "Cursor contains an invalid qualified-key position.");
    }

    private record QualifiedPosition(String namespace, String key) {
    }
}
