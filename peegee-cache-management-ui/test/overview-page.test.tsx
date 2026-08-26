import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';

import type { OverviewClientPort } from '@src/api/inspection-client';
import type { Overview } from '@src/api/inspection-schemas';
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

class FakeOverviewClient implements OverviewClientPort {
  response: Overview = overview;
  failure?: ManagementClientError;
  calls: string[] = [];

  async overview(setupId: string): Promise<Overview> {
    this.calls.push(setupId);
    if (this.failure !== undefined) throw this.failure;
    return this.response;
  }
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
    expect(screen.getByText('Unavailable')).toBeVisible();
    expect(screen.getByText('insufficient privilege')).toBeVisible();
    expect(screen.getByRole('time', { name: 'Snapshot observed at' })).toHaveAttribute(
      'datetime',
      overview.observedAt,
    );
    expect(screen.getByRole('heading', { name: 'Namespace overview' })).toBeVisible();
    expect(screen.getByText('客户/订单')).toBeVisible();
    expect(screen.getByText('Management server activity')).toBeVisible();
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
    client.response = { ...overview, observedAt: '2026-08-26T10:16:30Z' };
    await user.click(screen.getByRole('button', { name: 'Refresh overview' }));
    expect(await screen.findByRole('time', { name: 'Snapshot observed at' })).toHaveAttribute(
      'datetime',
      '2026-08-26T10:16:30Z',
    );
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
  });
});
