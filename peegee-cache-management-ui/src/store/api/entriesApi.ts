import type {
  BulkDeletePreview,
  BulkDeleteResult,
  ConfirmedEntryDelete,
  EntryDeleteSelection,
  EntrySetBody,
  EntrySetResult,
} from '../../api/entry-administration-schemas';
import type { EntryMetadata } from '../../api/inspection-schemas';
import { clientsOf, delegate } from './apiBase';
import { entryTag, managementApi, namespaceTag, setupDerivedTags } from './managementApi';

interface EntryRef { readonly setupId: string; readonly encodedNamespace: string; readonly encodedKey: string }

/**
 * Entry administration mutations. Every committed mutation invalidates the entry, its namespace
 * list and details, and the setup-derived reads (Overview totals, monitoring, activity), so the
 * console never shows optimistic state: the next read reflects what PostgreSQL committed.
 */
const entryInvalidations = ({ setupId, encodedNamespace, encodedKey }: EntryRef) => [
  entryTag(setupId, encodedNamespace, encodedKey),
  { type: 'Entry' as const, id: `${setupId}/${encodedNamespace}/LIST` },
  namespaceTag(setupId, encodedNamespace),
  ...setupDerivedTags(setupId),
];

export const entriesApi = managementApi.injectEndpoints({
  endpoints: (build) => ({
    setEntry: build.mutation<EntrySetResult, EntryRef & { body: EntrySetBody; observedVersion?: string }>({
      queryFn: ({ setupId, encodedNamespace, encodedKey, body, observedVersion }, api) =>
        delegate(() => clientsOf(api).entryAdministration.setEntry(setupId, encodedNamespace, encodedKey, body, observedVersion)),
      invalidatesTags: (_result, _error, ref) => entryInvalidations(ref),
    }),
    expireEntry: build.mutation<EntryMetadata, EntryRef & { version: string; ttlMillis: number }>({
      queryFn: ({ setupId, encodedNamespace, encodedKey, version, ttlMillis }, api) =>
        delegate(() => clientsOf(api).entryAdministration.expireEntry(setupId, encodedNamespace, encodedKey, version, ttlMillis)),
      invalidatesTags: (_result, _error, ref) => entryInvalidations(ref),
    }),
    persistEntry: build.mutation<EntryMetadata, EntryRef & { version: string }>({
      queryFn: ({ setupId, encodedNamespace, encodedKey, version }, api) =>
        delegate(() => clientsOf(api).entryAdministration.persistEntry(setupId, encodedNamespace, encodedKey, version)),
      invalidatesTags: (_result, _error, ref) => entryInvalidations(ref),
    }),
    touchEntry: build.mutation<EntryMetadata, EntryRef & { version: string; refreshTtlMillis: number | null }>({
      queryFn: ({ setupId, encodedNamespace, encodedKey, version, refreshTtlMillis }, api) =>
        delegate(() => clientsOf(api).entryAdministration.touchEntry(setupId, encodedNamespace, encodedKey, version, refreshTtlMillis)),
      invalidatesTags: (_result, _error, ref) => entryInvalidations(ref),
    }),
    deleteEntry: build.mutation<void, EntryRef & { version: string }>({
      queryFn: ({ setupId, encodedNamespace, encodedKey, version }, api) =>
        delegate(() => clientsOf(api).entryAdministration.deleteEntry(setupId, encodedNamespace, encodedKey, version)),
      invalidatesTags: (_result, _error, ref) => entryInvalidations(ref),
    }),
    previewEntryBulkDelete: build.mutation<BulkDeletePreview, { setupId: string; encodedNamespace: string; selection: EntryDeleteSelection }>({
      queryFn: ({ setupId, encodedNamespace, selection }, api) =>
        delegate(() => clientsOf(api).entryAdministration.previewBulkDelete(setupId, encodedNamespace, selection)),
    }),
    executeEntryBulkDelete: build.mutation<BulkDeleteResult, { setupId: string; encodedNamespace: string; confirmation: ConfirmedEntryDelete }>({
      queryFn: ({ setupId, encodedNamespace, confirmation }, api) =>
        delegate(() => clientsOf(api).entryAdministration.executeBulkDelete(setupId, encodedNamespace, confirmation)),
      invalidatesTags: (_result, _error, { setupId, encodedNamespace }) => [
        { type: 'Entry' as const, id: `${setupId}/${encodedNamespace}/LIST` },
        namespaceTag(setupId, encodedNamespace),
        ...setupDerivedTags(setupId),
      ],
    }),
  }),
});

export const {
  useSetEntryMutation,
  useExpireEntryMutation,
  usePersistEntryMutation,
  useTouchEntryMutation,
  useDeleteEntryMutation,
  usePreviewEntryBulkDeleteMutation,
  useExecuteEntryBulkDeleteMutation,
} = entriesApi;
