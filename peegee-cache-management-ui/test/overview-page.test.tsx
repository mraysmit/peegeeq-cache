import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';

import {
  activityPageSchema,
  databaseMonitoringSchema,
  overviewSchema,
  runtimeMonitoringSchema,
} from '@src/api/inspection-schemas';
import { currentSessionSchema } from '@src/api/protocol-schemas';
import { SessionClient } from '@src/api/session-client';
import { setupSummaryListSchema } from '@src/api/setup-schemas';
import { OverviewPage } from '@src/features/overview/OverviewPage';
import { createManagementClients, createManagementStore, ManagementProvider, type ManagementStore } from '@src/store';
import { route, startLoopbackServer, type LoopbackServer } from './support/loopback-server';

const session = currentSessionSchema.parse({
  user: 'overview-operator', roles: ['viewer', 'operator'], serverVersion: '0.1.0-SNAPSHOT', apiVersion: 'v1',
  authenticationMode: 'LOCAL_TOKEN', csrfToken: 'overview-test-csrf-token-with-forty-five-characters',
  sessionIdleExpiresAt: '2099-01-01T00:00:00Z', sessionExpiresAt: '2099-01-01T01:00:00Z',
});

const overview = overviewSchema.parse({
  scope: 'DATABASE',
  observedAt: '2026-08-26T10:15:30Z',
  health: {
    status: 'UP',
    schemaReady: true,
    latencyMillis: 7,
    checkedAt: '2026-08-26T10:15:29Z',
    detail: 'PostgreSQL and cache schema are ready',
  },
  totals: {
    namespaceCount: '2',
    liveEntryCount: '12345678901234567890',
    liveCounterCount: '4',
    activeLockCount: '1',
    expiredEntryCount: '3',
    expiredCounterCount: '2',
    schemaBytes: { availability: 'UNAVAILABLE', reason: 'insufficient privilege', value: null },
  },
  databaseStats: {
    observedAt: '2026-08-26T10:15:30Z',
    databaseBytes: { availability: 'AVAILABLE', reason: null, value: '8192' },
    schemaBytes: { availability: 'UNAVAILABLE', reason: 'insufficient privilege', value: null },
  },
  expiryStats: {
    observedAt: '2026-08-26T10:15:30Z',
    expiredEntryCount: '3',
    expiredCounterCount: '2',
    oldestLagMillis: { availability: 'AVAILABLE', reason: null, value: '2500' },
  },
  expiry: {
    oldestExpiredRowLagMillis: 2_500,
    sweeperEnabled: true,
    lastSweepAt: '2026-08-26T10:15:00Z',
    lastSweepDeletedRows: '8',
  },
  valueTypeCounts: { STRING: '10', JSON: '7', LONG: '2', BYTES: '1' },
  topNamespaces: [{
    namespace: '客户/订单',
    encodedNamespace: '5a6i5oi3L-iureWNlQ',
    liveEntryCount: '12',
    liveCounterCount: '2',
    activeLockCount: '1',
    expiringEntryCount: '5',
    expiredEntryCount: '3',
    estimatedStorageBytes: '4096',
    observedAt: '2026-08-26T10:15:30Z',
  }],
});

const databaseMonitoring = databaseMonitoringSchema.parse({
  scope: 'DATABASE', observedAt: '2026-08-26T10:15:30Z', health: overview.health,
  tableBytes: { availability: 'AVAILABLE', reason: null, value: '2048' },
  indexBytes: { availability: 'AVAILABLE', reason: null, value: '1024' },
  schemaBytes: { availability: 'AVAILABLE', reason: null, value: '3072' },
  liveRows: { availability: 'AVAILABLE', reason: null, value: '20' },
  expiredRows: { availability: 'AVAILABLE', reason: null, value: '3' },
  deadTuples: { availability: 'UNAVAILABLE', reason: 'pg_stat privilege required', value: null },
  lastVacuumAt: null, lastAutovacuumAt: '2026-08-26T10:10:00Z',
  databaseConnections: { availability: 'AVAILABLE', reason: null, value: '12' },
  cacheConnections: { availability: 'UNAVAILABLE', reason: 'connection statistics unavailable', value: null },
  expiryBacklog: '3', oldestExpiredRowLagMillis: 2500,
});

const runtimeMonitoring = runtimeMonitoringSchema.parse({
  scope: 'MANAGEMENT_RUNTIME', observedAt: '2026-08-26T10:15:31Z', lifecycleState: 'RUNNING',
  pool: {
    active: { availability: 'AVAILABLE', reason: null, value: '1' },
    idle: { availability: 'AVAILABLE', reason: null, value: '2' },
    pending: { availability: 'AVAILABLE', reason: null, value: '0' },
    maximum: { availability: 'AVAILABLE', reason: null, value: '3' },
  },
  activeOperations: '1', pubSubSubscriptions: '2', sseClients: '1', webSocketClients: '1',
  retainedPayloadBytes: '64', auditQueue: { depth: '0', capacity: '1024', acceptingMutations: true },
  expirySweeper: { ownedByRuntime: true, running: true, lastSweepAt: '2026-08-26T10:15:00Z' },
  operations: [{ operation: 'getOverview', status: 'COMPLETE', count: '4', errorCount: '0', latencyMillis: '12' }],
});

const activity = activityPageSchema.parse({
  items: [{
    eventId: 'event-2', occurredAt: '2026-08-26T10:15:32Z', actor: 'local-operator',
    action: 'SETUP_CONNECTED', outcome: 'SUCCEEDED', setupId: 'primary-cache', namespace: null,
    resource: { type: 'SETUP', identifier: 'must-not-render' }, summary: 'Setup connected',
    correlationId: 'corr-activity-2',
  }],
  nextAfter: null,
  hasMore: false,
});


describe('U3 database overview page', () => {
  let server: LoopbackServer;
  let store: ManagementStore;
  let sessionClient: SessionClient;
  let overviewStatus: 'ok' | 'unavailable' | 'updated';
  let overviewRequests: number;

  beforeEach(async () => {
    overviewStatus = 'ok';
    overviewRequests = 0;
    server = await startLoopbackServer((request, respond) => {
      if (route('GET', '/api/v1/session', request)) return respond.json(200, session);
      if (route('GET', '/api/v1/setups', request)) return respond.json(200, setupSummaryListSchema.parse({ items: [] }));
      if (route('GET', '/api/v1/setups/primary-cache/overview', request)) {
        overviewRequests += 1;
        if (overviewStatus === 'unavailable') return respond.problem(503, 'DATABASE_UNAVAILABLE', 'Database unavailable', { correlationId: 'corr-3' });
        if (overviewStatus === 'updated') {
          return respond.json(200, overviewSchema.parse({
            ...overview, observedAt: '2026-08-26T10:16:30Z', totals: { ...overview.totals, liveEntryCount: '12345678901234567891' },
          }));
        }
        return respond.json(200, overview);
      }
      if (route('GET', '/api/v1/setups/primary-cache/monitoring/database', request)) return respond.json(200, databaseMonitoring);
      if (route('GET', '/api/v1/setups/primary-cache/monitoring/runtime', request)) return respond.json(200, runtimeMonitoring);
      if (route('GET', '/api/v1/setups/primary-cache/activity', request)) return respond.json(200, activity);
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

  const renderPage = (selectedSetupId?: string) => render(
    <MemoryRouter>
      <ManagementProvider store={store}>
        <OverviewPage selectedSetupId={selectedSetupId} />
      </ManagementProvider>
    </MemoryRouter>,
  );

  it('requires an active setup instead of inventing database values', async () => {
    renderPage();

    expect(screen.getByRole('heading', { name: 'Overview' })).toBeVisible();
    expect(await screen.findByRole('heading', { name: 'Select a connected setup' })).toBeVisible();
    expect(screen.queryByText('0')).not.toBeInTheDocument();
    expect(server.requests.filter((request) => request.path.endsWith('/overview'))).toHaveLength(0);
  });

  it('labels database truth, preserves decimal-string precision, and renders unavailable values', async () => {
    renderPage('primary-cache');

    expect(await screen.findByText('12,345,678,901,234,567,890')).toBeVisible();
    expect(screen.getByText('Database-wide snapshot')).toBeVisible();
    expect(screen.getAllByText(/Unavailable/)).not.toHaveLength(0);
    expect(screen.getByText('insufficient privilege')).toBeVisible();
    expect(screen.getByText('Expired counters awaiting cleanup')).toBeVisible();
    expect(screen.getByText('Exact expired counters')).toBeVisible();
    expect(screen.getByText('8 KiB')).toBeVisible();
    expect(screen.getByRole('time', { name: 'Snapshot observed at' })).toHaveAttribute('datetime', overview.observedAt);
    expect(await screen.findByRole('heading', { name: 'Namespace overview' })).toBeVisible();
    expect(await screen.findByRole('region', { name: 'Namespace overview results' })).toHaveAttribute('tabindex', '0');
    expect(screen.getByText('客户/订单')).toBeVisible();
    expect(await screen.findByRole('heading', { name: 'Database storage' })).toBeVisible();
    expect(await screen.findByRole('heading', { name: 'Database connections' })).toBeVisible();
    const connections = screen.getByRole('heading', { name: 'Database connections' }).closest('section');
    expect(connections).not.toBeNull();
    expect(await within(connections!).findByText('12')).toBeVisible();
    expect(screen.getByText('connection statistics unavailable')).toBeVisible();
    expect(screen.getByText('pg_stat privilege required')).toBeVisible();
    expect(await screen.findByRole('heading', { name: 'Management runtime' })).toBeVisible();
    const recentActivity = (await screen.findByRole('heading', { name: 'Recent activity' })).closest('section');
    expect(recentActivity).not.toBeNull();
    expect(await within(recentActivity!).findByRole('row', { name: /Setup connected/ })).toBeVisible();
    expect(screen.queryByText('must-not-render')).not.toBeInTheDocument();
    expect(screen.getByLabelText('Current-session cache row trend')).toBeVisible();
    expect(overviewRequests).toBe(1);
  });

  it('retains timestamped stale data after an interrupted refresh and recovers only with validated data', async () => {
    const user = userEvent.setup();
    renderPage('primary-cache');
    await screen.findByText('12,345,678,901,234,567,890');

    overviewStatus = 'unavailable';
    await user.click(await screen.findByRole('button', { name: 'Refresh overview' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('Stale data');
    expect(screen.getByRole('alert')).toHaveTextContent('corr-3');
    expect(screen.getByText('12,345,678,901,234,567,890')).toBeVisible();

    overviewStatus = 'updated';
    await user.click(await screen.findByRole('button', { name: 'Refresh overview' }));
    await waitFor(() => expect(screen.getByRole('time', { name: 'Snapshot observed at' })).toHaveAttribute('datetime', '2026-08-26T10:16:30Z'));
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
    expect(screen.getByLabelText('Current-session cache row trend')).toHaveTextContent('2 snapshots');
  });
});
