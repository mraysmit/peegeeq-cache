import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';

import type { MonitoringClientPort, OverviewClientPort } from '@src/api/inspection-client';
import type {
  ActivityPage,
  DatabaseMonitoring,
  Overview,
  RuntimeMonitoring,
} from '@src/api/inspection-schemas';
import { OverviewPage } from '@src/features/overview/OverviewPage';
import { ManagementClientError } from '@src/api/session-client';

const overview: Overview = {
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
    schemaBytes: { availability: 'UNAVAILABLE', reason: 'insufficient privilege', value: null },
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
};

const databaseMonitoring: DatabaseMonitoring = {
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
};

const runtimeMonitoring: RuntimeMonitoring = {
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
};

const activity: ActivityPage = {
  items: [{
    eventId: 'event-2', occurredAt: '2026-08-26T10:15:32Z', actor: 'local-operator',
    action: 'SETUP_CONNECTED', outcome: 'SUCCEEDED', setupId: 'primary-cache', namespace: null,
    resource: { type: 'SETUP', identifier: 'must-not-render' }, summary: 'Setup connected',
    correlationId: 'corr-activity-2',
  }],
  nextAfter: null,
  hasMore: false,
};

class FakeOverviewClient implements OverviewClientPort, MonitoringClientPort {
  response: Overview = overview;
  failure?: ManagementClientError;
  calls: string[] = [];

  async overview(setupId: string): Promise<Overview> {
    this.calls.push(setupId);
    if (this.failure !== undefined) throw this.failure;
    return this.response;
  }

  async databaseMonitoring(): Promise<DatabaseMonitoring> { return databaseMonitoring; }
  async runtimeMonitoring(): Promise<RuntimeMonitoring> { return runtimeMonitoring; }
  async activity(): Promise<ActivityPage> { return activity; }
}

describe('U3 database overview page', () => {
  it('requires an active setup instead of inventing database values', () => {
    render(<OverviewPage client={new FakeOverviewClient()} />);

    expect(screen.getByRole('heading', { name: 'Overview' })).toBeVisible();
    expect(screen.getByRole('heading', { name: 'Select a connected setup' })).toBeVisible();
    expect(screen.queryByText('0')).not.toBeInTheDocument();
  });

  it('labels database truth, preserves decimal-string precision, and renders unavailable values', async () => {
    const client = new FakeOverviewClient();
    render(<OverviewPage client={client} selectedSetupId="primary-cache" />);

    expect(await screen.findByText('12,345,678,901,234,567,890')).toBeVisible();
    expect(screen.getByText('Database-wide snapshot')).toBeVisible();
    expect(screen.getAllByText('Unavailable')).not.toHaveLength(0);
    expect(screen.getByText('insufficient privilege')).toBeVisible();
    expect(screen.getByRole('time', { name: 'Snapshot observed at' })).toHaveAttribute(
      'datetime',
      overview.observedAt,
    );
    expect(screen.getByRole('heading', { name: 'Namespace overview' })).toBeVisible();
    expect(screen.getByRole('region', { name: 'Namespace overview results' })).toHaveAttribute('tabindex', '0');
    expect(screen.getByText('客户/订单')).toBeVisible();
    expect(screen.getByRole('heading', { name: 'Database storage' })).toBeVisible();
    expect(screen.getByRole('heading', { name: 'Database connections' })).toBeVisible();
    const connections = screen.getByRole('heading', { name: 'Database connections' }).closest('section');
    expect(connections).not.toBeNull();
    expect(within(connections!).getByText('12')).toBeVisible();
    expect(screen.getByText('connection statistics unavailable')).toBeVisible();
    expect(screen.getByText('pg_stat privilege required')).toBeVisible();
    expect(screen.getByRole('heading', { name: 'Management runtime' })).toBeVisible();
    const recentActivity = screen.getByRole('heading', { name: 'Recent activity' }).closest('section');
    expect(recentActivity).not.toBeNull();
    expect(within(recentActivity!).getByRole('row', { name: /Setup connected/ })).toBeVisible();
    expect(screen.queryByText('must-not-render')).not.toBeInTheDocument();
    expect(screen.getByLabelText('Current-session cache row trend')).toBeVisible();
    expect(client.calls).toEqual(['primary-cache']);
  });

  it('retains timestamped stale data after an interrupted refresh and recovers only with validated data', async () => {
    const user = userEvent.setup();
    const client = new FakeOverviewClient();
    render(<OverviewPage client={client} selectedSetupId="primary-cache" />);
    await screen.findByText('12,345,678,901,234,567,890');

    client.failure = new ManagementClientError(503, 'DATABASE_UNAVAILABLE', 'Database unavailable', 'corr-3');
    await user.click(screen.getByRole('button', { name: 'Refresh overview' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('Stale data');
    expect(screen.getByRole('alert')).toHaveTextContent('corr-3');
    expect(screen.getByText('12,345,678,901,234,567,890')).toBeVisible();

    client.failure = undefined;
    client.response = {
      ...overview,
      observedAt: '2026-08-26T10:16:30Z',
      totals: { ...overview.totals, liveEntryCount: '12345678901234567891' },
    };
    await user.click(screen.getByRole('button', { name: 'Refresh overview' }));
    expect(await screen.findByRole('time', { name: 'Snapshot observed at' })).toHaveAttribute(
      'datetime',
      '2026-08-26T10:16:30Z',
    );
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
    expect(screen.getByLabelText('Current-session cache row trend')).toHaveTextContent('2 snapshots');
  });
});
