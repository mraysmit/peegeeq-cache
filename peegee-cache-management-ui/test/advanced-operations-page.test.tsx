import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';

import type { BackendCapabilityClientPort } from '@src/api/backend-capability-client';
import type { AcquireLockRequest, BatchDeleteRequest, BatchGetRequest, BatchSetRequest, RenewLockRequest, ScanEntriesRequest } from '@src/api/backend-capability-schemas';
import { AdvancedOperationsPage } from '@src/features/advanced/AdvancedOperationsPage';

class BackendFake implements BackendCapabilityClientPort {
  batchGets: BatchGetRequest[] = [];
  batchSets: BatchSetRequest[] = [];
  batchDeletes: BatchDeleteRequest[] = [];
  scans: ScanEntriesRequest[] = [];
  lockActions: string[] = [];
  async entryExists() { return true; }
  async batchGetEntries(_setup: string, request: BatchGetRequest) { this.batchGets.push(request); return { items: request.keys.map((key) => ({ ...key, found: false, entry: null })) }; }
  async batchSetEntries(_setup: string, request: BatchSetRequest) { this.batchSets.push(request); return { items: request.entries.map((entry) => ({ namespace: entry.namespace, key: entry.key, applied: true, newVersion: '2', previousEntry: null })) }; }
  async batchDeleteEntries(_setup: string, request: BatchDeleteRequest) { this.batchDeletes.push(request); return { deletedCount: String(request.keys.length) }; }
  async scanEntries(_setup: string, request: ScanEntriesRequest) { this.scans.push(request); return { entries: [], nextCursor: null, hasMore: false }; }
  async acquireLock(_s: string, _n: string, _k: string, request: AcquireLockRequest) { this.lockActions.push(`acquire:${request.ownerToken}`); return { acquired: true, namespace: 'orders', key: 'processor', ownerToken: request.ownerToken, fencingToken: '7', leaseExpiresAt: '2099-01-01T00:00:00Z' }; }
  async renewLock(_s: string, _n: string, _k: string, request: RenewLockRequest) { this.lockActions.push(`renew:${request.ownerToken}`); return true; }
  async releaseLock(_s: string, _n: string, _k: string, owner: string) { this.lockActions.push(`release:${owner}`); return true; }
  async isLockHeldBy(_s: string, _n: string, _k: string, owner: string) { this.lockActions.push(`ownership:${owner}`); return true; }
  async cacheMetrics() { return { cacheGets: '1', cacheHits: '1', cacheMisses: '0', cacheSets: '1', cacheSetsApplied: '1', cacheDeletes: '0', counterIncrements: '0', counterSets: '0', counterDeletes: '0', lockAcquires: '1', lockAcquiresGranted: '1', lockRenewals: '0', lockReleases: '0', publishes: '0', subscribes: '0' }; }
}

describe('complete backend desktop workflows', () => {
  it('runs existence, value-inclusive scan, batch get/set, and exact metrics', async () => {
    const client = new BackendFake();
    const user = userEvent.setup();
    render(<AdvancedOperationsPage canBatch canMetrics canOperate canOwnLocks canReveal canScan client={client} selectedSetupId="primary-cache" />);

    await user.type(screen.getByLabelText('Namespace'), 'orders');
    await user.type(screen.getByLabelText('Key'), 'one');
    await user.click(screen.getByRole('button', { name: 'Check entry existence' }));
    expect(await screen.findByText('Yes')).toBeVisible();

    await user.click(screen.getByRole('button', { name: 'Run backend scan' }));
    expect(client.scans[0]).toMatchObject({ namespace: 'orders', includeValues: true, includeExpired: false });

    await user.type(screen.getByLabelText('Batch get keys'), 'orders\tone');
    await user.click(screen.getByRole('button', { name: 'Get entry batch' }));
    expect(client.batchGets[0]?.keys).toEqual([{ namespace: 'orders', key: 'one' }]);

    await user.click(screen.getByLabelText('Batch set entries (JSON)'));
    await user.paste(JSON.stringify([
      { namespace: 'orders', key: 'one', value: { type: 'LONG', decimal: '9223372036854775807' }, ttlMillis: 1000, setMode: 'UPSERT', expectedVersion: null, returnPreviousValue: true },
      { namespace: 'customers', key: 'two', value: { type: 'STRING', text: 'line one\nline two' }, ttlMillis: null, setMode: 'ONLY_IF_ABSENT', expectedVersion: null, returnPreviousValue: false },
    ]));
    await user.click(screen.getByRole('button', { name: 'Set entry batch' }));
    expect(client.batchSets[0]?.entries[0]).toMatchObject({ returnPreviousValue: true, value: { type: 'LONG', decimal: '9223372036854775807' } });
    expect(client.batchSets[0]?.entries[1]).toMatchObject({ namespace: 'customers', ttlMillis: null, setMode: 'ONLY_IF_ABSENT', returnPreviousValue: false });

    await user.type(screen.getByLabelText('Batch delete keys'), 'orders\tone\ncustomers\ttwo');
    await user.click(screen.getByRole('button', { name: 'Delete entry batch' }));
    expect(client.batchDeletes[0]?.keys).toEqual([{ namespace: 'orders', key: 'one' }, { namespace: 'customers', key: 'two' }]);
    expect(await screen.findByText(/"deletedCount": "2"/u)).toBeVisible();

    await user.click(screen.getByRole('button', { name: 'Refresh core metrics' }));
    expect(await screen.findByText('Cache Gets')).toBeVisible();
  }, 10_000);

  it('operates the complete owner-token lock lifecycle and clears the token', async () => {
    const client = new BackendFake();
    const user = userEvent.setup();
    render(<AdvancedOperationsPage canBatch canMetrics canOperate canOwnLocks canReveal canScan client={client} selectedSetupId="primary-cache" />);
    const panel = screen.getByRole('region', { name: 'Owner lock lifecycle' });
    await user.type(within(panel).getByLabelText('Lock namespace'), 'orders');
    await user.type(within(panel).getByLabelText('Lock key'), 'processor');
    await user.type(within(panel).getByLabelText('Owner token'), 'owner-secret');
    await user.click(within(panel).getByRole('button', { name: 'Acquire lock' }));
    await user.click(within(panel).getByRole('button', { name: 'Renew lock' }));
    await user.click(within(panel).getByRole('button', { name: 'Check ownership' }));
    await user.click(within(panel).getByRole('button', { name: 'Release by owner' }));
    expect(client.lockActions).toEqual(['acquire:owner-secret', 'renew:owner-secret', 'ownership:owner-secret', 'release:owner-secret']);
    await user.click(within(panel).getByRole('button', { name: 'Clear sensitive state' }));
    expect(within(panel).getByLabelText('Owner token')).toHaveValue('');
  });
});
