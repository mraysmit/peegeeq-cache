import { render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { activityPageSchema, databaseMonitoringSchema, runtimeMonitoringSchema } from '@src/api/inspection-schemas';
import { currentSessionSchema } from '@src/api/protocol-schemas';
import { SessionClient } from '@src/api/session-client';
import { setupSummaryListSchema } from '@src/api/setup-schemas';
import { MonitoringPage } from '@src/features/monitoring/MonitoringPage';
import { createManagementClients, createManagementStore, ManagementProvider, type ManagementStore } from '@src/store';
import { route, startLoopbackServer, type LoopbackServer } from './support/loopback-server';

const session = currentSessionSchema.parse({
  user: 'monitoring-operator', roles: ['viewer', 'operator'], serverVersion: '0.1.0-SNAPSHOT', apiVersion: 'v1',
  authenticationMode: 'LOCAL_TOKEN', csrfToken: 'monitoring-test-csrf-token-with-forty-seven-chars',
  sessionIdleExpiresAt: '2099-01-01T00:00:00Z', sessionExpiresAt: '2099-01-01T01:00:00Z',
});
const health = { status: 'UP', schemaReady: true, latencyMillis: 2, checkedAt: '2026-08-29T10:00:00Z', detail: 'Ready' };
const database = databaseMonitoringSchema.parse({
  scope: 'DATABASE', observedAt: '2026-08-29T10:00:00Z', health,
  tableBytes: available('1'), indexBytes: available('1'), schemaBytes: available('2'), liveRows: available('3'), expiredRows: available('0'), deadTuples: available('0'),
  lastVacuumAt: null, lastAutovacuumAt: null, databaseConnections: available('1'), cacheConnections: available('1'), expiryBacklog: '0', oldestExpiredRowLagMillis: null,
});
const runtime = runtimeMonitoringSchema.parse({
  scope: 'MANAGEMENT_RUNTIME', observedAt: '2026-08-29T10:00:00Z', lifecycleState: 'RUNNING',
  pool: { active: available('1'), idle: available('2'), pending: available('0'), maximum: available('3') },
  activeOperations: '1', pubSubSubscriptions: '0', sseClients: '1', webSocketClients: '1', retainedPayloadBytes: '0',
  auditQueue: { depth: '0', capacity: '100', acceptingMutations: true }, expirySweeper: { ownedByRuntime: true, running: true, lastSweepAt: null }, operations: [],
});
const runtimeUpdate = runtimeMonitoringSchema.parse({ ...runtime, observedAt: '2026-08-29T10:00:05Z', activeOperations: '7' });
const activity = activityPageSchema.parse({ items: [], nextAfter: null, hasMore: false });

describe('U8 live monitoring page', () => {
  let server: LoopbackServer;
  let store: ManagementStore;
  let sessionClient: SessionClient;
  let streamOpened: number;
  let streamClosed: number;
  let streamFailuresRemaining: number;

  beforeEach(async () => {
    streamOpened = 0;
    streamClosed = 0;
    streamFailuresRemaining = 1;
    server = await startLoopbackServer((request, respond) => {
      if (route('GET', '/api/v1/session', request)) return respond.json(200, session);
      if (route('GET', '/api/v1/setups', request)) return respond.json(200, setupSummaryListSchema.parse({ items: [] }));
      if (route('GET', '/api/v1/setups/primary/monitoring/database', request)) return respond.json(200, database);
      if (route('GET', '/api/v1/setups/primary/monitoring/runtime', request)) return respond.json(200, runtime);
      if (route('GET', '/api/v1/setups/primary/activity', request)) return respond.json(200, activity);
      if (route('GET', '/api/v1/setups/primary/sse/metrics', request)) {
        if (streamFailuresRemaining > 0) {
          streamFailuresRemaining -= 1;
          return respond.problem(503, 'STREAM_UNAVAILABLE', 'the first metrics handshake is unavailable');
        }
        streamOpened += 1;
        request.onClose(() => { streamClosed += 1; });
        return respond.sse([
          `id: 1\nevent: runtime.snapshot\ndata: ${JSON.stringify(runtimeUpdate)}\n\n`,
        ], { keepOpen: true });
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

  it('connects the setup-scoped metrics stream, applies live runtime snapshots to the cache, and stops it on disposal', async () => {
    const rendered = render(
      <MemoryRouter>
        <ManagementProvider store={store}>
          <MonitoringPage selectedSetupId="primary" />
        </ManagementProvider>
      </MemoryRouter>,
    );

    expect(await screen.findByText('Live metrics connected', undefined, { timeout: 5_000 })).toBeVisible();
    expect(screen.queryByText('Live metrics connection was interrupted')).not.toBeInTheDocument();
    expect(streamOpened).toBe(1);
    // The SSE runtime frame replaced the cached runtime snapshot without a second REST request.
    expect(await screen.findByText('7')).toBeVisible();
    expect(server.requests.filter((request) => request.path.endsWith('/monitoring/runtime'))).toHaveLength(1);

    rendered.unmount();
    await vi.waitFor(() => expect(streamClosed).toBe(1));
  });
});

function available(value: string) { return { availability: 'AVAILABLE', reason: null, value }; }
