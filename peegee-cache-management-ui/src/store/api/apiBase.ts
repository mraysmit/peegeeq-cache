import type { BaseQueryApi, BaseQueryFn } from '@reduxjs/toolkit/query';

import { ManagementClientError, type ManagementRequest } from '../../api/session-client';
import type { ManagementClients } from '../clients';

/**
 * Serialisable error carried in RTK Query state. Never the thrown `ManagementClientError`
 * itself (Redux state must stay serialisable) and never a raw response body.
 */
export interface ManagementQueryError {
  readonly status: number;
  readonly code: string;
  readonly message: string;
  readonly correlationId?: string;
}

export interface ManagementBaseQueryArgs extends ManagementRequest {
  readonly path: string;
}

/**
 * Base query that delegates to the session-aware client rather than `fetchBaseQuery`, so every
 * request — including one written with `query()` instead of `queryFn()` — carries the CSRF
 * header, credentials, and problem mapping the `src/api` layer already implements.
 */
export const managementBaseQuery: BaseQueryFn<ManagementBaseQueryArgs, unknown, ManagementQueryError> = async (
  { path, ...request },
  api,
) => delegate(() => clientsOf(api).session.requestJson(path, request));

export function clientsOf(api: Pick<BaseQueryApi, 'extra'>): ManagementClients {
  const clients = api.extra as ManagementClients | undefined;
  if (clients === undefined || clients.session === undefined) {
    throw new Error('The management store was created without ManagementClients');
  }
  return clients;
}

/** Run a typed client call and fold its outcome into the RTK Query result shape. */
export async function delegate<T>(run: () => Promise<T>): Promise<{ data: T } | { error: ManagementQueryError }> {
  try {
    return { data: await run() };
  } catch (failure: unknown) {
    return { error: toQueryError(failure) };
  }
}

export function toQueryError(failure: unknown): ManagementQueryError {
  if (failure instanceof ManagementClientError) {
    return failure.correlationId === undefined
      ? { status: failure.status, code: failure.code, message: failure.message }
      : { status: failure.status, code: failure.code, message: failure.message, correlationId: failure.correlationId };
  }
  return {
    status: 0,
    code: 'REQUEST_FAILED',
    message: failure instanceof Error ? failure.message : 'The management request failed',
  };
}

export function isManagementQueryError(value: unknown): value is ManagementQueryError {
  return typeof value === 'object' && value !== null
    && typeof (value as ManagementQueryError).status === 'number'
    && typeof (value as ManagementQueryError).code === 'string';
}
