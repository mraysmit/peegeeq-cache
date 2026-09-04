import { createApi } from '@reduxjs/toolkit/query/react';

import { managementBaseQuery } from './apiBase';

/**
 * Single RTK Query API for the management contract. Each resource family injects its endpoints
 * from its own module (`setupsApi`, `inspectionApi`, `entriesApi`, `resourcesApi`, `pubSubApi`),
 * mirroring the reference console's per-family modules while keeping one tag universe so a
 * mutation in one family (an entry delete) can invalidate reads in another (Overview totals).
 *
 * Tag conventions: `{ type, id: 'LIST' }` for collections; `{ type, id: '<setupId>' }` or
 * `{ type, id: '<setupId>/<encodedNamespace>[/<encodedKey>]' }` for single resources.
 */
export const managementApi = createApi({
  reducerPath: 'managementApi',
  baseQuery: managementBaseQuery,
  tagTypes: ['Setup', 'Capabilities', 'Overview', 'Monitoring', 'Activity', 'Namespace', 'Entry', 'Counter', 'Lock', 'Subscription'],
  refetchOnReconnect: true,
  endpoints: () => ({}),
});

export type ManagementTag = Parameters<typeof managementApi.util.invalidateTags>[0][number];

export const setupTag = (setupId: string) => ({ type: 'Setup' as const, id: setupId });
export const namespaceTag = (setupId: string, encodedNamespace: string) => ({ type: 'Namespace' as const, id: `${setupId}/${encodedNamespace}` });
export const entryTag = (setupId: string, encodedNamespace: string, encodedKey: string) => ({ type: 'Entry' as const, id: `${setupId}/${encodedNamespace}/${encodedKey}` });
export const counterTag = (setupId: string, encodedNamespace: string, encodedKey: string) => ({ type: 'Counter' as const, id: `${setupId}/${encodedNamespace}/${encodedKey}` });
export const lockTag = (setupId: string, encodedNamespace: string, encodedKey: string) => ({ type: 'Lock' as const, id: `${setupId}/${encodedNamespace}/${encodedKey}` });

/** Reads whose totals change whenever entries, counters, or locks in a setup change. */
export const setupDerivedTags = (setupId: string): ManagementTag[] => [
  { type: 'Overview', id: setupId },
  { type: 'Monitoring', id: setupId },
  { type: 'Activity', id: setupId },
  { type: 'Namespace', id: `${setupId}/LIST` },
];
