package dev.mars.peegeeq.cache.api.management;

import dev.mars.peegeeq.cache.api.model.CacheKey;
import dev.mars.peegeeq.cache.api.model.LockKey;
import io.vertx.core.Future;

/**
 * Privileged metadata inspection, sensitive reveal, and atomic administration API.
 * Callers must check {@link #capabilities()} and supply an authenticated
 * {@link ManagementActionContext} to every reveal, mutation, or actor-bound bulk call.
 * Asynchronous capability, audit, validation, storage, and lifecycle failures are
 * reported through failed Vert.x {@link Future Futures}; they are never converted to success.
 */
public interface ManagementService {
    AdminCapabilities capabilities();
    Future<AdminPage<NamespaceStats>> namespaces(NamespaceQuery query);
    Future<AdminPage<ManagementEntryMetadata>> entries(EntryQuery query);
    Future<ManagementEntryMetadata> entry(CacheKey key, boolean includeExpired);
    Future<RevealedEntryValue> revealEntry(RevealEntryRequest request, ManagementActionContext context);
    Future<ManagementSetResult> setEntry(
            ManagementCacheSetRequest request, ManagementActionContext context);
    Future<VersionedMutationResult<ManagementEntryMetadata>> expireEntry(
            VersionedEntryTtlRequest request, ManagementActionContext context);
    Future<VersionedMutationResult<ManagementEntryMetadata>> persistEntry(
            VersionedCacheKeyRequest request, ManagementActionContext context);
    Future<VersionedMutationResult<ManagementEntryMetadata>> touchEntry(
            VersionedEntryTouchRequest request, ManagementActionContext context);
    Future<VersionedMutationResult<Void>> deleteEntry(
            VersionedEntryDeleteRequest request, ManagementActionContext context);
    Future<AdminPage<CounterEntry>> counters(CounterQuery query);
    Future<CounterEntry> counter(CacheKey key);
    Future<VersionedMutationResult<CounterEntry>> setCounter(
            ManagementCounterSetRequest request, ManagementActionContext context);
    Future<VersionedMutationResult<CounterEntry>> adjustCounter(
            ManagementCounterAdjustRequest request, ManagementActionContext context);
    Future<VersionedMutationResult<CounterEntry>> expireCounter(
            VersionedCounterTtlRequest request, ManagementActionContext context);
    Future<VersionedMutationResult<CounterEntry>> persistCounter(
            VersionedCacheKeyRequest request, ManagementActionContext context);
    Future<VersionedMutationResult<Void>> deleteCounter(
            VersionedCounterDeleteRequest request, ManagementActionContext context);
    Future<AdminPage<ManagementLockMetadata>> locks(LockQuery query);
    Future<ManagementLockMetadata> lock(LockKey key);
    Future<RevealedLockOwner> revealLockOwner(
            RevealLockOwnerRequest request, ManagementActionContext context);
    Future<VersionedMutationResult<Void>> forceReleaseLock(
            ForceReleaseLockRequest request, ManagementActionContext context);
    Future<DatabaseStats> databaseStats();
    Future<ExpiryStats> expiryStats();
    Future<BulkDeletePreview> previewEntryDelete(
            EntryDeleteFilter filter, ManagementActionContext context);
    Future<BulkDeleteResult> executeEntryDelete(
            ConfirmedEntryDelete request, ManagementActionContext context);
    Future<BulkDeletePreview> previewCounterDelete(
            CounterDeleteSelection selection, ManagementActionContext context);
    Future<BulkDeleteResult> executeCounterDelete(
            ConfirmedCounterDelete request, ManagementActionContext context);
}
