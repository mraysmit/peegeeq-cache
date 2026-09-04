import type { NamespaceQuery } from '../../api/inspection-client';

export type NamespaceStatus = NonNullable<NamespaceQuery['status']>;
export type NamespaceSort = NonNullable<NamespaceQuery['sort']>;

/** Reviewed server values for the namespace status filter, in display order. */
export const NAMESPACE_STATUS_OPTIONS: ReadonlyArray<{ value: NamespaceStatus; label: string }> = [
  { value: 'ALL', label: 'All' },
  { value: 'HEALTHY', label: 'Healthy' },
  { value: 'EXPIRED_BACKLOG', label: 'Expired backlog' },
  { value: 'ACTIVE_LOCKS', label: 'Active locks' },
];

/** Reviewed server orderings for the namespace list, in display order. */
export const NAMESPACE_SORT_OPTIONS: ReadonlyArray<{ value: NamespaceSort; label: string }> = [
  { value: 'namespace:asc', label: 'Namespace' },
  { value: 'entryCount:desc', label: 'Live entries' },
];
