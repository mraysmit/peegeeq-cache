package dev.mars.peegeeq.cache.api.management;

import dev.mars.peegeeq.cache.api.model.CacheKey;
import dev.mars.peegeeq.cache.api.model.LockKey;
import io.vertx.core.Future;

/** Shared backward-compatible fallback for cache implementations without management support. */
public final class UnsupportedManagementService implements ManagementService {
    private static final UnsupportedManagementService INSTANCE = new UnsupportedManagementService();

    private UnsupportedManagementService() {
    }

    public static UnsupportedManagementService instance() { return INSTANCE; }
    public AdminCapabilities capabilities() { return AdminCapabilities.unsupported(); }
    public Future<AdminPage<NamespaceStats>> namespaces(NamespaceQuery query) { return unavailable(ManagementCapability.NAMESPACE_INSPECTION); }
    public Future<AdminPage<ManagementEntryMetadata>> entries(EntryQuery query) { return unavailable(ManagementCapability.ENTRY_INSPECTION); }
    public Future<ManagementEntryMetadata> entry(CacheKey key, boolean includeExpired) { return unavailable(ManagementCapability.ENTRY_INSPECTION); }
    public Future<RevealedEntryValue> revealEntry(RevealEntryRequest request, ManagementActionContext context) { return unavailable(ManagementCapability.ENTRY_REVEAL); }
    public Future<ManagementSetResult> setEntry(ManagementCacheSetRequest request, ManagementActionContext context) { return unavailable(ManagementCapability.ENTRY_MUTATION); }
    public Future<VersionedMutationResult<ManagementEntryMetadata>> expireEntry(VersionedEntryTtlRequest request, ManagementActionContext context) { return unavailable(ManagementCapability.ENTRY_MUTATION); }
    public Future<VersionedMutationResult<ManagementEntryMetadata>> persistEntry(VersionedCacheKeyRequest request, ManagementActionContext context) { return unavailable(ManagementCapability.ENTRY_MUTATION); }
    public Future<VersionedMutationResult<ManagementEntryMetadata>> touchEntry(VersionedEntryTouchRequest request, ManagementActionContext context) { return unavailable(ManagementCapability.ENTRY_MUTATION); }
    public Future<VersionedMutationResult<Void>> deleteEntry(VersionedEntryDeleteRequest request, ManagementActionContext context) { return unavailable(ManagementCapability.ENTRY_MUTATION); }
    public Future<AdminPage<CounterEntry>> counters(CounterQuery query) { return unavailable(ManagementCapability.COUNTER_INSPECTION); }
    public Future<CounterEntry> counter(CacheKey key) { return unavailable(ManagementCapability.COUNTER_INSPECTION); }
    public Future<VersionedMutationResult<CounterEntry>> setCounter(ManagementCounterSetRequest request, ManagementActionContext context) { return unavailable(ManagementCapability.COUNTER_MUTATION); }
    public Future<VersionedMutationResult<CounterEntry>> adjustCounter(ManagementCounterAdjustRequest request, ManagementActionContext context) { return unavailable(ManagementCapability.COUNTER_MUTATION); }
    public Future<VersionedMutationResult<CounterEntry>> expireCounter(VersionedCounterTtlRequest request, ManagementActionContext context) { return unavailable(ManagementCapability.COUNTER_MUTATION); }
    public Future<VersionedMutationResult<CounterEntry>> persistCounter(VersionedCacheKeyRequest request, ManagementActionContext context) { return unavailable(ManagementCapability.COUNTER_MUTATION); }
    public Future<VersionedMutationResult<Void>> deleteCounter(VersionedCounterDeleteRequest request, ManagementActionContext context) { return unavailable(ManagementCapability.COUNTER_MUTATION); }
    public Future<AdminPage<ManagementLockMetadata>> locks(LockQuery query) { return unavailable(ManagementCapability.LOCK_INSPECTION); }
    public Future<ManagementLockMetadata> lock(LockKey key) { return unavailable(ManagementCapability.LOCK_INSPECTION); }
    public Future<RevealedLockOwner> revealLockOwner(RevealLockOwnerRequest request, ManagementActionContext context) { return unavailable(ManagementCapability.LOCK_REVEAL); }
    public Future<VersionedMutationResult<Void>> forceReleaseLock(ForceReleaseLockRequest request, ManagementActionContext context) { return unavailable(ManagementCapability.FORCE_LOCK_RELEASE); }
    public Future<DatabaseStats> databaseStats() { return unavailable(ManagementCapability.DATABASE_MONITORING); }
    public Future<ExpiryStats> expiryStats() { return unavailable(ManagementCapability.EXPIRY_MONITORING); }
    public Future<BulkDeletePreview> previewEntryDelete(EntryDeleteFilter filter, ManagementActionContext context) { return unavailable(ManagementCapability.BULK_DELETE); }
    public Future<BulkDeleteResult> executeEntryDelete(ConfirmedEntryDelete request, ManagementActionContext context) { return unavailable(ManagementCapability.BULK_DELETE); }
    public Future<BulkDeletePreview> previewCounterDelete(CounterDeleteSelection selection, ManagementActionContext context) { return unavailable(ManagementCapability.BULK_DELETE); }
    public Future<BulkDeleteResult> executeCounterDelete(ConfirmedCounterDelete request, ManagementActionContext context) { return unavailable(ManagementCapability.BULK_DELETE); }

    private static <T> Future<T> unavailable(ManagementCapability capability) {
        return Future.failedFuture(new ManagementCapabilityException(capability));
    }
}
