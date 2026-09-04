import type { BulkDeletePreview, BulkDeleteResult } from '../../api/entry-administration-schemas';
import type { CounterQuery, LockQuery } from '../../api/resource-client';
import type {
  ConfirmedCounterDelete,
  Counter,
  CounterAdjustBody,
  CounterPage,
  CounterSelection,
  CounterSetBody,
  LockPage,
  LockState,
} from '../../api/resource-schemas';
import { clientsOf, delegate } from './apiBase';
import { counterTag, lockTag, managementApi, setupDerivedTags } from './managementApi';

interface ResourceRef { readonly setupId: string; readonly encodedNamespace: string; readonly encodedKey: string }

/**
 * Counter and lock endpoints. Lock owner reveal is sensitive and deliberately absent; it stays on
 * `ResourceClient.revealLockOwner` via `useManagementClients()`. Force-release is a committed
 * mutation and invalidates the lock, the lock list, and setup-derived reads.
 */
const counterInvalidations = ({ setupId, encodedNamespace, encodedKey }: ResourceRef) => [
  counterTag(setupId, encodedNamespace, encodedKey),
  { type: 'Counter' as const, id: `${setupId}/LIST` },
  ...setupDerivedTags(setupId),
];

export const resourcesApi = managementApi.injectEndpoints({
  endpoints: (build) => ({
    getCounters: build.query<CounterPage, { setupId: string; query?: CounterQuery }>({
      queryFn: ({ setupId, query }, api) => delegate(() => clientsOf(api).resource.counters(setupId, query)),
      providesTags: (result, _error, { setupId }) => [
        { type: 'Counter', id: `${setupId}/LIST` },
        ...(result?.items ?? []).map((item) => counterTag(setupId, item.encodedNamespace, item.encodedKey)),
      ],
    }),
    getCounter: build.query<Counter, ResourceRef>({
      queryFn: ({ setupId, encodedNamespace, encodedKey }, api) => delegate(() => clientsOf(api).resource.counter(setupId, encodedNamespace, encodedKey)),
      providesTags: (_result, _error, { setupId, encodedNamespace, encodedKey }) => [counterTag(setupId, encodedNamespace, encodedKey)],
    }),
    setCounter: build.mutation<Counter, ResourceRef & { version?: string; body: CounterSetBody }>({
      queryFn: ({ setupId, encodedNamespace, encodedKey, version, body }, api) =>
        delegate(() => clientsOf(api).resource.setCounter(setupId, encodedNamespace, encodedKey, version, body)),
      invalidatesTags: (_result, _error, ref) => counterInvalidations(ref),
    }),
    adjustCounter: build.mutation<Counter, ResourceRef & { version?: string; body: CounterAdjustBody }>({
      queryFn: ({ setupId, encodedNamespace, encodedKey, version, body }, api) =>
        delegate(() => clientsOf(api).resource.adjustCounter(setupId, encodedNamespace, encodedKey, version, body)),
      invalidatesTags: (_result, _error, ref) => counterInvalidations(ref),
    }),
    expireCounter: build.mutation<Counter, ResourceRef & { version: string; ttlMillis: number }>({
      queryFn: ({ setupId, encodedNamespace, encodedKey, version, ttlMillis }, api) =>
        delegate(() => clientsOf(api).resource.expireCounter(setupId, encodedNamespace, encodedKey, version, ttlMillis)),
      invalidatesTags: (_result, _error, ref) => counterInvalidations(ref),
    }),
    persistCounter: build.mutation<Counter, ResourceRef & { version: string }>({
      queryFn: ({ setupId, encodedNamespace, encodedKey, version }, api) =>
        delegate(() => clientsOf(api).resource.persistCounter(setupId, encodedNamespace, encodedKey, version)),
      invalidatesTags: (_result, _error, ref) => counterInvalidations(ref),
    }),
    deleteCounter: build.mutation<void, ResourceRef & { version: string }>({
      queryFn: ({ setupId, encodedNamespace, encodedKey, version }, api) =>
        delegate(() => clientsOf(api).resource.deleteCounter(setupId, encodedNamespace, encodedKey, version)),
      invalidatesTags: (_result, _error, ref) => counterInvalidations(ref),
    }),
    previewCounterBulkDelete: build.mutation<BulkDeletePreview, { setupId: string; selection: CounterSelection }>({
      queryFn: ({ setupId, selection }, api) => delegate(() => clientsOf(api).resource.previewCounterBulkDelete(setupId, selection)),
    }),
    executeCounterBulkDelete: build.mutation<BulkDeleteResult, { setupId: string; confirmation: ConfirmedCounterDelete }>({
      queryFn: ({ setupId, confirmation }, api) => delegate(() => clientsOf(api).resource.executeCounterBulkDelete(setupId, confirmation)),
      invalidatesTags: (_result, _error, { setupId }) => [{ type: 'Counter', id: `${setupId}/LIST` }, ...setupDerivedTags(setupId)],
    }),
    getLocks: build.query<LockPage, { setupId: string; query?: LockQuery }>({
      queryFn: ({ setupId, query }, api) => delegate(() => clientsOf(api).resource.locks(setupId, query)),
      providesTags: (result, _error, { setupId }) => [
        { type: 'Lock', id: `${setupId}/LIST` },
        ...(result?.items ?? []).map((item) => lockTag(setupId, item.encodedNamespace, item.encodedKey)),
      ],
    }),
    getLock: build.query<LockState, ResourceRef>({
      queryFn: ({ setupId, encodedNamespace, encodedKey }, api) => delegate(() => clientsOf(api).resource.lock(setupId, encodedNamespace, encodedKey)),
      providesTags: (_result, _error, { setupId, encodedNamespace, encodedKey }) => [lockTag(setupId, encodedNamespace, encodedKey)],
    }),
    forceReleaseLock: build.mutation<void, ResourceRef & { version: string; confirmationKey: string; reason?: string }>({
      queryFn: ({ setupId, encodedNamespace, encodedKey, version, confirmationKey, reason }, api) =>
        delegate(() => clientsOf(api).resource.forceReleaseLock(setupId, encodedNamespace, encodedKey, version, confirmationKey, reason)),
      invalidatesTags: (_result, _error, { setupId, encodedNamespace, encodedKey }) => [
        lockTag(setupId, encodedNamespace, encodedKey),
        { type: 'Lock', id: `${setupId}/LIST` },
        ...setupDerivedTags(setupId),
      ],
    }),
  }),
});

export const {
  useGetCountersQuery,
  useGetCounterQuery,
  useSetCounterMutation,
  useAdjustCounterMutation,
  useExpireCounterMutation,
  usePersistCounterMutation,
  useDeleteCounterMutation,
  usePreviewCounterBulkDeleteMutation,
  useExecuteCounterBulkDeleteMutation,
  useGetLocksQuery,
  useGetLockQuery,
  useForceReleaseLockMutation,
} = resourcesApi;
