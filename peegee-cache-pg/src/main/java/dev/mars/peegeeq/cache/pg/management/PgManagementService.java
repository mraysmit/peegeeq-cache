package dev.mars.peegeeq.cache.pg.management;

import dev.mars.peegeeq.cache.api.management.*;
import dev.mars.peegeeq.cache.api.model.CacheKey;
import dev.mars.peegeeq.cache.api.model.LockKey;
import io.vertx.core.Future;

import java.time.Clock;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

/** PostgreSQL-backed management service, implemented incrementally by management phase. */
public final class PgManagementService implements ManagementService {

    private final PgManagementReadRepository repository;
    private final PgManagementMutationRepository mutationRepository;
    private final String setupId;
    private final ManagementCursorCodec cursors;
    private final ManagementAuditSink auditSink;
    private final ManagementAuditFingerprinter auditFingerprinter;
    private final Clock auditClock;
    private final Supplier<String> auditEventIdSupplier;
    private final Duration defaultEntryTtl;
    private final PgManagementBulkDeleteCoordinator bulkDeletes;

    public PgManagementService(
            PgManagementReadRepository repository,
            PgManagementMutationRepository mutationRepository,
            String setupId,
            ManagementCursorCodec cursors,
            ManagementAuditSink auditSink,
            ManagementAuditFingerprinter auditFingerprinter,
            Clock auditClock,
            Supplier<String> auditEventIdSupplier,
            Duration defaultEntryTtl) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.mutationRepository = Objects.requireNonNull(mutationRepository, "mutationRepository");
        this.setupId = Objects.requireNonNull(setupId, "setupId");
        this.cursors = Objects.requireNonNull(cursors, "cursors");
        this.auditSink = Objects.requireNonNull(auditSink, "auditSink");
        this.auditFingerprinter = Objects.requireNonNull(auditFingerprinter, "auditFingerprinter");
        this.auditClock = Objects.requireNonNull(auditClock, "auditClock");
        this.auditEventIdSupplier = Objects.requireNonNull(auditEventIdSupplier, "auditEventIdSupplier");
        if (defaultEntryTtl != null && (defaultEntryTtl.isZero() || defaultEntryTtl.isNegative())) {
            throw new IllegalArgumentException("defaultEntryTtl must be positive when configured");
        }
        this.defaultEntryTtl = defaultEntryTtl;
        this.bulkDeletes = new PgManagementBulkDeleteCoordinator(
                mutationRepository, setupId, auditClock);
    }

    @Override
    public Future<AdminPage<NamespaceStats>> namespaces(NamespaceQuery query) {
        Objects.requireNonNull(query, "query");
        ManagementCursorScope scope = namespaceScope(query);
        ManagementCursorPosition position = query.cursor() == null ? null : cursors.decode(query.cursor(), scope);
        validateNamespacePosition(query.sort(), position);
        return repository.namespaces(query, position).map(rows -> namespacePage(query, scope, rows));
    }

    @Override
    public Future<NamespaceDetails> namespace(String namespace) {
        return repository.namespaceDetails(Objects.requireNonNull(namespace, "namespace"));
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
    @Override
    public Future<RevealedEntryValue> revealEntry(
            RevealEntryRequest request,
            ManagementActionContext context) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(context, "context");
        ManagementAuditIntent intent = entryIntent(
                ManagementAuditAction.REVEAL_ENTRY,
                request.key(),
                null,
                request.reason(),
                context);
        return reserveAudit(intent).compose(reservation ->
                mutationRepository.revealEntry(request.key()).transform(result -> {
                    if (result.failed()) {
                        return completeAudit(
                                reservation,
                                new ManagementAuditOutcome(
                                        ManagementAuditTerminalOutcome.FAILED,
                                        "DATABASE_UNAVAILABLE",
                                        null))
                                .compose(ignored -> Future.failedFuture(result.cause()));
                    }
                    return result.result()
                            .<Future<RevealedEntryValue>>map(revealed -> completeAudit(
                                            reservation,
                                            new ManagementAuditOutcome(
                                                    ManagementAuditTerminalOutcome.SUCCEEDED,
                                                    "ENTRY_VALUE_REVEALED",
                                                    revealed.version()))
                                    .map(revealed))
                            .orElseGet(() -> completeAudit(
                                            reservation,
                                            new ManagementAuditOutcome(
                                                    ManagementAuditTerminalOutcome.REJECTED,
                                                    "ENTRY_NOT_FOUND",
                                                    null))
                                    .compose(ignored -> Future.failedFuture(
                                            new ManagementNotFoundException(
                                                    ManagementNotFoundException.Resource.ENTRY))));
                }));
    }
    @Override
    public Future<ManagementSetResult> setEntry(
            ManagementCacheSetRequest request,
            ManagementActionContext context) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(context, "context");
        ManagementAuditIntent intent = entryIntent(
                ManagementAuditAction.SET_ENTRY,
                request.key(),
                request.expectedVersion(),
                null,
                context);
        return reserveAudit(intent).compose(reservation ->
                mutationRepository.setEntry(request, defaultEntryTtl).transform(result -> {
                    if (result.failed()) {
                        return completeAudit(
                                reservation,
                                new ManagementAuditOutcome(
                                        ManagementAuditTerminalOutcome.FAILED,
                                        "DATABASE_UNAVAILABLE",
                                        null))
                                .compose(ignored -> Future.failedFuture(result.cause()));
                    }
                    ManagementSetResult setResult = result.result();
                    ManagementAuditOutcome auditOutcome = setResult.outcome() == ManagementMutationOutcome.APPLIED
                            ? new ManagementAuditOutcome(
                                    ManagementAuditTerminalOutcome.SUCCEEDED,
                                    "ENTRY_SET",
                                    setResult.resultingVersion())
                            : new ManagementAuditOutcome(
                                    ManagementAuditTerminalOutcome.REJECTED,
                                    setAuditCode(setResult.outcome()),
                                    null);
                    return completeAudit(reservation, auditOutcome).map(setResult);
                }));
    }
    @Override
    public Future<VersionedMutationResult<ManagementEntryMetadata>> expireEntry(
            VersionedEntryTtlRequest request,
            ManagementActionContext context) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(context, "context");
        return auditedEntryMutation(
                ManagementAuditAction.EXPIRE_ENTRY,
                request.key(),
                request.expectedVersion(),
                context,
                "ENTRY_TTL_SET",
                () -> mutationRepository.expireEntry(request));
    }
    @Override
    public Future<VersionedMutationResult<ManagementEntryMetadata>> persistEntry(
            VersionedCacheKeyRequest request,
            ManagementActionContext context) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(context, "context");
        return auditedEntryMutation(
                ManagementAuditAction.PERSIST_ENTRY,
                request.key(),
                request.expectedVersion(),
                context,
                "ENTRY_PERSISTED",
                () -> mutationRepository.persistEntry(request));
    }
    @Override
    public Future<VersionedMutationResult<ManagementEntryMetadata>> touchEntry(
            VersionedEntryTouchRequest request,
            ManagementActionContext context) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(context, "context");
        return auditedEntryMutation(
                ManagementAuditAction.TOUCH_ENTRY,
                request.key(),
                request.expectedVersion(),
                context,
                "ENTRY_TOUCHED",
                () -> mutationRepository.touchEntry(request));
    }
    @Override
    public Future<VersionedMutationResult<Void>> deleteEntry(
            VersionedEntryDeleteRequest request,
            ManagementActionContext context) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(context, "context");
        return auditedEntryMutation(
                ManagementAuditAction.DELETE_ENTRY,
                request.key(),
                request.expectedVersion(),
                context,
                "ENTRY_DELETED",
                () -> mutationRepository.deleteEntry(request));
    }
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
    @Override
    public Future<VersionedMutationResult<CounterEntry>> setCounter(
            ManagementCounterSetRequest request,
            ManagementActionContext context) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(context, "context");
        return auditedCounterMutation(
                ManagementAuditAction.SET_COUNTER,
                request.key(),
                request.expectedVersion(),
                context,
                "COUNTER_SET",
                () -> mutationRepository.setCounter(request));
    }
    @Override
    public Future<VersionedMutationResult<CounterEntry>> adjustCounter(
            ManagementCounterAdjustRequest request,
            ManagementActionContext context) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(context, "context");
        return auditedCounterMutation(
                ManagementAuditAction.ADJUST_COUNTER,
                request.key(),
                request.expectedVersion(),
                context,
                "COUNTER_ADJUSTED",
                () -> mutationRepository.adjustCounter(request));
    }
    @Override
    public Future<VersionedMutationResult<CounterEntry>> expireCounter(
            VersionedCounterTtlRequest request,
            ManagementActionContext context) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(context, "context");
        return auditedCounterMutation(
                ManagementAuditAction.EXPIRE_COUNTER,
                request.key(), request.expectedVersion(), context,
                "COUNTER_TTL_SET", () -> mutationRepository.expireCounter(request));
    }

    @Override
    public Future<VersionedMutationResult<CounterEntry>> persistCounter(
            VersionedCacheKeyRequest request,
            ManagementActionContext context) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(context, "context");
        return auditedCounterMutation(
                ManagementAuditAction.PERSIST_COUNTER,
                request.key(), request.expectedVersion(), context,
                "COUNTER_PERSISTED", () -> mutationRepository.persistCounter(request));
    }

    @Override
    public Future<VersionedMutationResult<Void>> deleteCounter(
            VersionedCounterDeleteRequest request,
            ManagementActionContext context) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(context, "context");
        return auditedCounterMutation(
                ManagementAuditAction.DELETE_COUNTER,
                request.key(), request.expectedVersion(), context,
                "COUNTER_DELETED", () -> mutationRepository.deleteCounter(request));
    }
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
    @Override
    public Future<RevealedLockOwner> revealLockOwner(
            RevealLockOwnerRequest request,
            ManagementActionContext context) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(context, "context");
        ManagementAuditIntent intent = lockIntent(
                ManagementAuditAction.REVEAL_LOCK_OWNER,
                request.key(), null, request.reason(), context);
        return reserveAudit(intent).compose(reservation ->
                mutationRepository.revealLockOwner(request.key()).transform(result -> {
                    if (result.failed()) {
                        return completeAudit(reservation, new ManagementAuditOutcome(
                                ManagementAuditTerminalOutcome.FAILED,
                                "DATABASE_UNAVAILABLE", null))
                                .compose(ignored -> Future.failedFuture(result.cause()));
                    }
                    if (result.result().isEmpty()) {
                        return completeAudit(reservation, new ManagementAuditOutcome(
                                ManagementAuditTerminalOutcome.REJECTED,
                                "LOCK_NOT_FOUND", null))
                                .compose(ignored -> Future.failedFuture(
                                        new ManagementNotFoundException(
                                                ManagementNotFoundException.Resource.LOCK)));
                    }
                    RevealedLockOwner revealed = result.result().orElseThrow();
                    return completeAudit(reservation, new ManagementAuditOutcome(
                            ManagementAuditTerminalOutcome.SUCCEEDED,
                            "LOCK_OWNER_REVEALED", revealed.version())).map(revealed);
                }));
    }
    @Override
    public Future<VersionedMutationResult<Void>> forceReleaseLock(
            ForceReleaseLockRequest request,
            ManagementActionContext context) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(context, "context");
        ManagementAuditIntent intent = lockIntent(
                ManagementAuditAction.FORCE_RELEASE_LOCK,
                request.key(), request.expectedVersion(), request.reason(), context);
        return reserveAudit(intent).compose(reservation ->
                mutationRepository.forceReleaseLock(request).transform(result -> {
                    if (result.failed()) {
                        return completeAudit(reservation, new ManagementAuditOutcome(
                                ManagementAuditTerminalOutcome.FAILED,
                                "DATABASE_UNAVAILABLE", null))
                                .compose(ignored -> Future.failedFuture(result.cause()));
                    }
                    VersionedMutationResult<Void> mutation = result.result();
                    ManagementAuditOutcome outcome = mutation.outcome() == ManagementMutationOutcome.APPLIED
                            ? new ManagementAuditOutcome(
                                    ManagementAuditTerminalOutcome.SUCCEEDED,
                                    "LOCK_RELEASED", mutation.resultingVersion())
                            : new ManagementAuditOutcome(
                                    ManagementAuditTerminalOutcome.REJECTED,
                                    mutation.outcome() == ManagementMutationOutcome.NOT_FOUND
                                            ? "LOCK_NOT_FOUND" : "VERSION_MISMATCH",
                                    null);
                    return completeAudit(reservation, outcome).map(mutation);
                }));
    }
    @Override public Future<DatabaseStats> databaseStats() { return repository.databaseStats(); }
    @Override public Future<ExpiryStats> expiryStats() { return repository.expiryStats(); }
    @Override public Future<ManagementOverview> overview() { return repository.overview(); }
    @Override public Future<ManagementDatabaseMonitoring> databaseMonitoring() { return repository.databaseMonitoring(); }
    @Override
    public Future<BulkDeletePreview> previewEntryDelete(
            EntryDeleteFilter filter,
            ManagementActionContext context) {
        Objects.requireNonNull(filter, "filter");
        Objects.requireNonNull(context, "context");
        return auditedBulk(
                ManagementAuditAction.PREVIEW_ENTRY_DELETE,
                Map.of("namespace", auditFingerprinter.fingerprint(filter.namespace())),
                context,
                "ENTRY_DELETE_PREVIEWED",
                () -> bulkDeletes.previewEntry(filter, context));
    }

    @Override
    public Future<BulkDeleteResult> executeEntryDelete(
            ConfirmedEntryDelete request,
            ManagementActionContext context) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(context, "context");
        return auditedBulk(
                ManagementAuditAction.EXECUTE_ENTRY_DELETE,
                Map.of(),
                context,
                "ENTRY_DELETE_EXECUTED",
                () -> bulkDeletes.executeEntry(request, context));
    }

    @Override
    public Future<BulkDeletePreview> previewCounterDelete(
            CounterDeleteSelection selection,
            ManagementActionContext context) {
        Objects.requireNonNull(selection, "selection");
        Objects.requireNonNull(context, "context");
        return auditedBulk(
                ManagementAuditAction.PREVIEW_COUNTER_DELETE,
                Map.of(),
                context,
                "COUNTER_DELETE_PREVIEWED",
                () -> bulkDeletes.previewCounter(selection, context));
    }

    @Override
    public Future<BulkDeleteResult> executeCounterDelete(
            ConfirmedCounterDelete request,
            ManagementActionContext context) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(context, "context");
        return auditedBulk(
                ManagementAuditAction.EXECUTE_COUNTER_DELETE,
                Map.of(),
                context,
                "COUNTER_DELETE_EXECUTED",
                () -> bulkDeletes.executeCounter(request, context));
    }

    private ManagementAuditIntent entryIntent(
            ManagementAuditAction action,
            CacheKey key,
            Long expectedVersion,
            String reason,
            ManagementActionContext context) {
        return new ManagementAuditIntent(
                auditEventIdSupplier.get(),
                auditClock.instant(),
                context.actor(),
                context.roles(),
                action,
                setupId,
                ManagementResourceType.ENTRY,
                Map.of(
                        "namespace", auditFingerprinter.fingerprint(key.namespace()),
                        "key", auditFingerprinter.fingerprint(key.key())),
                expectedVersion,
                reason,
                context.sourceAddress(),
                context.correlationId());
    }

    private ManagementAuditIntent lockIntent(
            ManagementAuditAction action,
            LockKey key,
            Long expectedVersion,
            String reason,
            ManagementActionContext context) {
        return new ManagementAuditIntent(
                auditEventIdSupplier.get(),
                auditClock.instant(),
                context.actor(),
                context.roles(),
                action,
                setupId,
                ManagementResourceType.LOCK,
                Map.of(
                        "namespace", auditFingerprinter.fingerprint(key.namespace()),
                        "key", auditFingerprinter.fingerprint(key.key())),
                expectedVersion,
                reason,
                context.sourceAddress(),
                context.correlationId());
    }

    private <T> Future<VersionedMutationResult<T>> auditedEntryMutation(
            ManagementAuditAction action,
            CacheKey key,
            long expectedVersion,
            ManagementActionContext context,
            String successCode,
            Supplier<Future<VersionedMutationResult<T>>> operation) {
        ManagementAuditIntent intent = entryIntent(
                action, key, expectedVersion, null, context);
        return reserveAudit(intent).compose(reservation -> operation.get().transform(result -> {
            if (result.failed()) {
                return completeAudit(
                        reservation,
                        new ManagementAuditOutcome(
                                ManagementAuditTerminalOutcome.FAILED,
                                "DATABASE_UNAVAILABLE",
                                null))
                        .compose(ignored -> Future.failedFuture(result.cause()));
            }
            VersionedMutationResult<T> mutation = result.result();
            ManagementAuditOutcome auditOutcome = mutation.outcome() == ManagementMutationOutcome.APPLIED
                    ? new ManagementAuditOutcome(
                            ManagementAuditTerminalOutcome.SUCCEEDED,
                            successCode,
                            mutation.resultingVersion())
                    : new ManagementAuditOutcome(
                            ManagementAuditTerminalOutcome.REJECTED,
                            entryVersionedAuditCode(mutation.outcome()),
                            null);
            return completeAudit(reservation, auditOutcome).map(mutation);
        }));
    }

    private <T> Future<VersionedMutationResult<T>> auditedCounterMutation(
            ManagementAuditAction action,
            CacheKey key,
            Long expectedVersion,
            ManagementActionContext context,
            String successCode,
            Supplier<Future<VersionedMutationResult<T>>> operation) {
        ManagementAuditIntent intent = new ManagementAuditIntent(
                auditEventIdSupplier.get(),
                auditClock.instant(),
                context.actor(),
                context.roles(),
                action,
                setupId,
                ManagementResourceType.COUNTER,
                Map.of(
                        "namespace", auditFingerprinter.fingerprint(key.namespace()),
                        "key", auditFingerprinter.fingerprint(key.key())),
                expectedVersion,
                null,
                context.sourceAddress(),
                context.correlationId());
        return reserveAudit(intent).compose(reservation -> operation.get().transform(result -> {
            if (result.failed()) {
                boolean rejected = result.cause() instanceof ManagementCounterException;
                return completeAudit(
                        reservation,
                        new ManagementAuditOutcome(
                                rejected
                                        ? ManagementAuditTerminalOutcome.REJECTED
                                        : ManagementAuditTerminalOutcome.FAILED,
                                rejected ? "VALIDATION_FAILED" : "DATABASE_UNAVAILABLE",
                                null))
                        .compose(ignored -> Future.failedFuture(result.cause()));
            }
            VersionedMutationResult<T> mutation = result.result();
            ManagementAuditOutcome auditOutcome = mutation.outcome() == ManagementMutationOutcome.APPLIED
                    ? new ManagementAuditOutcome(
                            ManagementAuditTerminalOutcome.SUCCEEDED,
                            successCode,
                            mutation.resultingVersion())
                    : new ManagementAuditOutcome(
                            ManagementAuditTerminalOutcome.REJECTED,
                            counterAuditCode(mutation.outcome()),
                            null);
            return completeAudit(reservation, auditOutcome).map(mutation);
        }));
    }

    private <T> Future<T> auditedBulk(
            ManagementAuditAction action,
            Map<String, ManagementAuditFingerprint> identifiers,
            ManagementActionContext context,
            String successCode,
            Supplier<Future<T>> operation) {
        ManagementAuditIntent intent = new ManagementAuditIntent(
                auditEventIdSupplier.get(),
                auditClock.instant(),
                context.actor(),
                context.roles(),
                action,
                setupId,
                ManagementResourceType.BULK_SELECTION,
                identifiers,
                null,
                null,
                context.sourceAddress(),
                context.correlationId());
        return reserveAudit(intent).compose(reservation -> operation.get().transform(result -> {
            if (result.succeeded()) {
                return completeAudit(
                        reservation,
                        new ManagementAuditOutcome(
                                ManagementAuditTerminalOutcome.SUCCEEDED,
                                successCode,
                                null)).map(result.result());
            }
            boolean rejected = result.cause() instanceof ManagementBulkDeleteException;
            String code = rejected
                    ? ((ManagementBulkDeleteException) result.cause()).code().name()
                    : "DATABASE_UNAVAILABLE";
            return completeAudit(
                    reservation,
                    new ManagementAuditOutcome(
                            rejected
                                    ? ManagementAuditTerminalOutcome.REJECTED
                                    : ManagementAuditTerminalOutcome.FAILED,
                            code,
                            null))
                    .compose(ignored -> Future.failedFuture(result.cause()));
        }));
    }

    private Future<ManagementAuditReservation> reserveAudit(ManagementAuditIntent intent) {
        try {
            return auditSink.reserveIntent(intent).recover(failure ->
                    Future.failedFuture(new ManagementAuditException(
                            "Management audit intent reservation failed", failure)));
        } catch (RuntimeException failure) {
            return Future.failedFuture(new ManagementAuditException(
                    "Management audit intent reservation failed", failure));
        }
    }

    private Future<Void> completeAudit(
            ManagementAuditReservation reservation,
            ManagementAuditOutcome outcome) {
        try {
            return auditSink.complete(reservation, outcome).recover(failure ->
                    Future.failedFuture(new ManagementAuditOutcomeException(
                            "Management audit terminal outcome is unavailable", failure)));
        } catch (RuntimeException failure) {
            return Future.failedFuture(new ManagementAuditOutcomeException(
                    "Management audit terminal outcome is unavailable", failure));
        }
    }

    private static String setAuditCode(ManagementMutationOutcome outcome) {
        return switch (outcome) {
            case NOT_FOUND -> "ENTRY_NOT_FOUND";
            case VERSION_MISMATCH -> "VERSION_MISMATCH";
            case CONDITION_NOT_MET -> "SET_MODE_NOT_APPLIED";
            case APPLIED -> throw new IllegalArgumentException("Applied sets use a succeeded audit outcome");
        };
    }

    private static String entryVersionedAuditCode(ManagementMutationOutcome outcome) {
        return switch (outcome) {
            case NOT_FOUND -> "ENTRY_NOT_FOUND";
            case VERSION_MISMATCH -> "VERSION_MISMATCH";
            case CONDITION_NOT_MET -> "SET_MODE_NOT_APPLIED";
            case APPLIED -> throw new IllegalArgumentException(
                    "Applied mutations use a succeeded audit outcome");
        };
    }

    private static String counterAuditCode(ManagementMutationOutcome outcome) {
        return switch (outcome) {
            case NOT_FOUND -> "COUNTER_NOT_FOUND";
            case VERSION_MISMATCH -> "VERSION_MISMATCH";
            case CONDITION_NOT_MET -> "COUNTER_ALREADY_EXISTS";
            case APPLIED -> throw new IllegalArgumentException(
                    "Applied counter mutations use a succeeded audit outcome");
        };
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
