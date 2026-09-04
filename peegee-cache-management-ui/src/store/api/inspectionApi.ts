import type { ActivityQuery, EntryQuery, NamespaceQuery } from '../../api/inspection-client';
import type {
  ActivityPage,
  DatabaseMonitoring,
  EntryMetadata,
  EntryPage,
  NamespaceDetails,
  NamespaceExport,
  NamespacePage,
  Overview,
  RuntimeMonitoring,
} from '../../api/inspection-schemas';
import { clientsOf, delegate } from './apiBase';
import { entryTag, managementApi, namespaceTag } from './managementApi';

/**
 * Read-only inspection endpoints: Overview, monitoring, activity, namespaces, and entry metadata.
 * Entry value reveal is deliberately absent — it is sensitive, served no-store, and lives on
 * `InspectionClient.revealEntryValue` reached through `useManagementClients()` (design §8.2).
 */
export const inspectionApi = managementApi.injectEndpoints({
  endpoints: (build) => ({
    getOverview: build.query<Overview, { setupId: string }>({
      queryFn: ({ setupId }, api) => delegate(() => clientsOf(api).inspection.overview(setupId)),
      providesTags: (_result, _error, { setupId }) => [{ type: 'Overview', id: setupId }],
    }),
    getDatabaseMonitoring: build.query<DatabaseMonitoring, { setupId: string }>({
      queryFn: ({ setupId }, api) => delegate(() => clientsOf(api).inspection.databaseMonitoring(setupId)),
      providesTags: (_result, _error, { setupId }) => [{ type: 'Monitoring', id: setupId }],
    }),
    getRuntimeMonitoring: build.query<RuntimeMonitoring, { setupId: string }>({
      queryFn: ({ setupId }, api) => delegate(() => clientsOf(api).inspection.runtimeMonitoring(setupId)),
      providesTags: (_result, _error, { setupId }) => [{ type: 'Monitoring', id: setupId }],
    }),
    getActivity: build.query<ActivityPage, { setupId: string; query?: ActivityQuery }>({
      queryFn: ({ setupId, query }, api) => delegate(() => clientsOf(api).inspection.activity(setupId, query)),
      providesTags: (_result, _error, { setupId }) => [{ type: 'Activity', id: setupId }],
    }),
    getNamespaces: build.query<NamespacePage, { setupId: string; query?: NamespaceQuery }>({
      queryFn: ({ setupId, query }, api) => delegate(() => clientsOf(api).inspection.namespaces(setupId, query)),
      providesTags: (result, _error, { setupId }) => [
        { type: 'Namespace', id: `${setupId}/LIST` },
        ...(result?.items ?? []).map((item) => namespaceTag(setupId, item.encodedNamespace)),
      ],
    }),
    getNamespace: build.query<NamespaceDetails, { setupId: string; encodedNamespace: string }>({
      queryFn: ({ setupId, encodedNamespace }, api) => delegate(() => clientsOf(api).inspection.namespace(setupId, encodedNamespace)),
      providesTags: (_result, _error, { setupId, encodedNamespace }) => [namespaceTag(setupId, encodedNamespace)],
    }),
    exportNamespaces: build.query<NamespaceExport, { setupId: string; query?: NamespaceQuery }>({
      queryFn: ({ setupId, query }, api) => delegate(() => clientsOf(api).inspection.exportNamespaces(setupId, query)),
      keepUnusedDataFor: 0,
    }),
    getEntries: build.query<EntryPage, { setupId: string; encodedNamespace: string; query?: EntryQuery }>({
      queryFn: ({ setupId, encodedNamespace, query }, api) => delegate(() => clientsOf(api).inspection.entries(setupId, encodedNamespace, query)),
      providesTags: (result, _error, { setupId, encodedNamespace }) => [
        { type: 'Entry', id: `${setupId}/${encodedNamespace}/LIST` },
        ...(result?.items ?? []).map((item) => entryTag(setupId, encodedNamespace, item.encodedKey)),
      ],
    }),
    getEntry: build.query<EntryMetadata, { setupId: string; encodedNamespace: string; encodedKey: string; includeExpired?: boolean }>({
      queryFn: ({ setupId, encodedNamespace, encodedKey, includeExpired }, api) => delegate(() => clientsOf(api).inspection.entry(setupId, encodedNamespace, encodedKey, includeExpired)),
      providesTags: (_result, _error, { setupId, encodedNamespace, encodedKey }) => [entryTag(setupId, encodedNamespace, encodedKey)],
    }),
  }),
});

export const {
  useGetOverviewQuery,
  useGetDatabaseMonitoringQuery,
  useGetRuntimeMonitoringQuery,
  useGetActivityQuery,
  useGetNamespacesQuery,
  useGetNamespaceQuery,
  useLazyExportNamespacesQuery,
  useGetEntriesQuery,
  useGetEntryQuery,
} = inspectionApi;
