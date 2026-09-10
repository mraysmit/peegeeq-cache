import { renderHook } from '@testing-library/react';
import { createElement, type ReactNode } from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { overviewSchema } from '@src/api/inspection-schemas';
import { currentSessionSchema } from '@src/api/protocol-schemas';
import { SessionClient } from '@src/api/session-client';
import { setupSummaryListSchema, setupSummarySchema } from '@src/api/setup-schemas';
import { inspectionApi, useGetOverviewQuery } from '@src/store/api/inspectionApi';
import { setupsApi } from '@src/store/api/setupsApi';
import { createManagementClients, createManagementStore, ManagementProvider, type ManagementStore } from '@src/store';
import { invalidBody, route, startLoopbackServer, type LoopbackServer } from './support/loopback-server';

/**
 * U11.1 — Redux store and RTK Query foundation.
 *
 * RTK Query owns REST request state, caching, and invalidation (design §3.1/§8.2). Every endpoint
 * delegates to the typed `src/api` client for the resource, so URL building, CSRF, preconditions,
 * and the Zod contract parse are not duplicated. The store is exercised against the real session
 * client and the loopback server; fixtures are produced by the production schemas.
 */

const session = currentSessionSchema.parse({
  user: 'store-operator',
  roles: ['viewer', 'operator'],
  serverVersion: '0.1.0-SNAPSHOT',
  apiVersion: 'v1',
  authenticationMode: 'LOCAL_TOKEN',
  csrfToken: 'store-test-csrf-token-with-forty-two-characters',
  sessionIdleExpiresAt: '2099-01-01T00:00:00Z',
  sessionExpiresAt: '2099-01-01T01:00:00Z',
});

const overview = overviewSchema.parse({
  scope: 'DATABASE',
  observedAt: '2026-09-03T10:15:30Z',
  health: { status: 'UP', schemaReady: true, latencyMillis: 7, checkedAt: '2026-09-03T10:15:29Z', detail: 'Ready' },
  totals: {
    namespaceCount: '1', liveEntryCount: '2', liveCounterCount: '3', activeLockCount: '4',
    expiredEntryCount: '5', expiredCounterCount: '6',
    schemaBytes: { availability: 'AVAILABLE', reason: null, value: '4096' },
  },
  databaseStats: {
    observedAt: '2026-09-03T10:15:30Z',
    databaseBytes: { availability: 'AVAILABLE', reason: null, value: '8192' },
    schemaBytes: { availability: 'AVAILABLE', reason: null, value: '4096' },
  },
  expiryStats: {
    observedAt: '2026-09-03T10:15:30Z', expiredEntryCount: '5', expiredCounterCount: '6',
    oldestLagMillis: { availability: 'UNAVAILABLE', reason: 'no expired rows', value: null },
  },
  expiry: { oldestExpiredRowLagMillis: null, sweeperEnabled: true, lastSweepAt: null, lastSweepDeletedRows: '0' },
  valueTypeCounts: { STRING: '2' },
  topNamespaces: [],
});

const primary = setupSummarySchema.parse({
  setupId: 'primary-cache', displayName: 'Primary cache', host: 'db.example.test', port: 5432,
  database: 'peegeeq', schema: 'public', sslMode: 'VERIFY_FULL', source: 'UI_SESSION',
  state: 'CONNECTED', schemaState: 'READY',
  lastHealth: { status: 'UP', latencyMillis: 7, checkedAt: '2099-01-01T00:00:00Z' },
});

describe('U11.1 management store and RTK Query foundation', () => {
  let server: LoopbackServer;
  let store: ManagementStore;
  let sessionClient: SessionClient;
  let registered: boolean;

  beforeEach(async () => {
    registered = false;
    server = await startLoopbackServer((request, respond) => {
      if (route('GET', '/api/v1/session', request)) return respond.json(200, session);
      if (route('GET', '/api/v1/setups/primary-cache/overview', request)) return respond.json(200, overview);
      if (route('GET', '/api/v1/setups', request)) {
        return respond.json(200, setupSummaryListSchema.parse({ items: registered ? [primary] : [] }));
      }
      if (route('POST', '/api/v1/setups', request)) {
        registered = true;
        return respond.json(201, primary);
      }
      return respond.problem(404, 'NOT_FOUND', `no fixture for ${request.method} ${request.path}`);
    });
    sessionClient = new SessionClient(server.baseUrl);
    await sessionClient.load();
    store = createManagementStore(createManagementClients(sessionClient));
  });

  afterEach(async () => {
    sessionClient.clear();
    await server.close();
  });

  it('caches a Zod-parsed Overview in the RTK Query slice after a real round trip', async () => {
    const result = await store.dispatch(inspectionApi.endpoints.getOverview.initiate({ setupId: 'primary-cache' }));

    expect(result.data).toEqual(overview);
    const cached = store.getState()[inspectionApi.reducerPath].queries;
    const entry = Object.values(cached).find((query) => query?.endpointName === 'getOverview');
    expect(entry?.status).toBe('fulfilled');
    expect(entry?.data).toEqual(overview);
    expect(server.requests.filter((request) => request.path.endsWith('/overview'))).toHaveLength(1);
    expect(server.requests.at(-1)?.headers.accept).toBe('application/json');
  });

  it('serves the hook from the cache without a second request', async () => {
    await store.dispatch(inspectionApi.endpoints.getOverview.initiate({ setupId: 'primary-cache' }));
    const wrapper = ({ children }: { children: ReactNode }) => createElement(ManagementProvider, { store, children });

    const { result } = renderHook(() => useGetOverviewQuery({ setupId: 'primary-cache' }), { wrapper });

    await vi.waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(result.current.data).toEqual(overview);
    expect(server.requests.filter((request) => request.path.endsWith('/overview'))).toHaveLength(1);
  });

  it('maps a management problem to a serialisable query error, never a thrown exception', async () => {
    server.use((request, respond) => {
      if (route('GET', '/api/v1/setups/primary-cache/overview', request)) {
        return respond.problem(403, 'ROLE_REQUIRED', 'operator role required', { correlationId: 'corr-403' });
      }
      return respond.problem(404, 'NOT_FOUND', 'unexpected');
    });

    const result = await store.dispatch(inspectionApi.endpoints.getOverview.initiate({ setupId: 'primary-cache' }));

    expect(result.error).toEqual({ status: 403, code: 'ROLE_REQUIRED', message: 'operator role required', correlationId: 'corr-403' });
  });

  it('rejects a body that violates the contract as RESPONSE_CONTRACT_INVALID', async () => {
    server.use((request, respond) => {
      if (route('GET', '/api/v1/setups/primary-cache/overview', request)) {
        return respond.json(200, invalidBody({ ...overview, totals: { ...overview.totals, unexpected: true } }));
      }
      return respond.problem(404, 'NOT_FOUND', 'unexpected');
    });

    const result = await store.dispatch(inspectionApi.endpoints.getOverview.initiate({ setupId: 'primary-cache' }));

    expect(result.error).toMatchObject({ status: 502, code: 'RESPONSE_CONTRACT_INVALID' });
  });

  it('invalidates the setup list after a registration mutation and sends the CSRF header', async () => {
    const before = await store.dispatch(setupsApi.endpoints.listSetups.initiate());
    expect(before.data).toEqual([]);

    await store.dispatch(setupsApi.endpoints.registerSetup.initiate({
      setupId: 'primary-cache', displayName: 'Primary cache', host: 'db.example.test', port: 5432,
      database: 'peegeeq', schema: 'public', username: 'cache-user', password: 'not-persisted',
      sslMode: 'VERIFY_FULL', trustProfileId: 'production-ca', poolMaxSize: 4,
    }));

    await vi.waitFor(() => {
      const entry = Object.values(store.getState()[setupsApi.reducerPath].queries).find((query) => query?.endpointName === 'listSetups');
      expect(entry?.data).toEqual([primary]);
    });
    const registration = server.requests.find((request) => request.method === 'POST' && request.path === '/api/v1/setups');
    expect(registration?.headers['x-peegeeq-csrf']).toBe(session.csrfToken);
    expect(server.requests.filter((request) => request.method === 'GET' && request.path === '/api/v1/setups')).toHaveLength(2);
  });

  it('keeps sensitive reveal operations off RTK Query and secrets out of Redux state', async () => {
    const endpointNames = [...Object.keys(inspectionApi.endpoints), ...Object.keys(setupsApi.endpoints)];
    expect(endpointNames.filter((name) => /reveal/i.test(name))).toEqual([]);

    const registration = store.dispatch(setupsApi.endpoints.registerSetup.initiate({
      setupId: 'primary-cache', displayName: 'Primary cache', host: 'db.example.test', port: 5432,
      database: 'peegeeq', schema: 'public', username: 'cache-user', password: 'never-in-redux-state',
      sslMode: 'VERIFY_FULL', trustProfileId: 'production-ca', poolMaxSize: 4,
    }));
    // While the mutation is in flight and after it settles, the password must not be in state.
    expect(JSON.stringify(store.getState())).not.toContain('never-in-redux-state');
    await registration;
    expect(JSON.stringify(store.getState())).not.toContain('never-in-redux-state');
    expect(JSON.stringify(store.getState())).not.toContain(session.csrfToken);
  });
});
