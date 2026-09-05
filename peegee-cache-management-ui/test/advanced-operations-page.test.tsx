import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';

import { AdvancedOperationsPage } from '@src/features/advanced/AdvancedOperationsPage';
import { renderWithProviders } from './support/render';
import { startResourceFixture, type ResourceFixture } from './support/resource-fixture';

describe('complete backend desktop workflows', () => {
  let fixture: ResourceFixture;

  beforeEach(async () => {
    localStorage.clear();
    sessionStorage.clear();
    fixture = await startResourceFixture();
  });
  afterEach(async () => { await fixture.close(); });

  const page = (props: Partial<Parameters<typeof AdvancedOperationsPage>[0]> = {}) => renderWithProviders(
    <AdvancedOperationsPage canBatch canMetrics canOperate canOwnLocks canReveal canScan selectedSetupId="primary-cache" {...props} />,
    { store: fixture.store },
  );
  const requests = (method: string, suffix: string) => fixture.requests((request) => request.method === method && request.path.endsWith(suffix));

  it('runs existence, value-inclusive scan, batch get/set, and exact metrics through the no-store client', async () => {
    const user = userEvent.setup();
    page();

    await user.type(screen.getByLabelText('Namespace'), 'orders');
    await user.type(screen.getByLabelText('Key'), 'one');
    await user.click(screen.getByRole('button', { name: 'Check entry existence' }));
    expect(await screen.findByText('Yes')).toBeVisible();
    expect(requests('GET', '/exists')[0]!.path).toBe('/api/v1/setups/primary-cache/namespaces/b3JkZXJz/entries/b25l/exists');

    await user.click(screen.getByRole('button', { name: 'Run backend scan' }));
    expect(await screen.findByRole('region', { name: 'Scan result' })).toBeVisible();
    expect(requests('POST', '/entries/scan')[0]!.body).toEqual({ namespace: 'orders', prefix: null, cursor: null, limit: 50, includeValues: true, includeExpired: false, reason: 'Interactive console operation' });

    await user.type(screen.getByLabelText('Batch get keys'), 'orders\tone');
    await user.click(screen.getByRole('button', { name: 'Get entry batch' }));
    expect(await screen.findByRole('region', { name: 'Batch get result' })).toHaveTextContent('"found": false');
    expect(requests('POST', '/entries/batch-get')[0]!.body).toEqual({ keys: [{ namespace: 'orders', key: 'one' }], reason: 'Interactive console operation' });

    await user.click(screen.getByLabelText('Batch set entries (JSON)'));
    await user.paste(JSON.stringify([
      { namespace: 'orders', key: 'one', value: { type: 'LONG', decimal: '9223372036854775807' }, ttlMillis: 1000, setMode: 'UPSERT', expectedVersion: null, returnPreviousValue: true },
      { namespace: 'customers', key: 'two', value: { type: 'STRING', text: 'line one\nline two' }, ttlMillis: null, setMode: 'ONLY_IF_ABSENT', expectedVersion: null, returnPreviousValue: false },
    ]));
    await user.click(screen.getByRole('button', { name: 'Set entry batch' }));
    expect(await screen.findByRole('region', { name: 'Batch set result' })).toHaveTextContent('"applied": true');
    const batchSet = requests('POST', '/entries/batch-set')[0]!.body as { entries: Array<Record<string, unknown>> };
    expect(batchSet.entries[0]).toMatchObject({ returnPreviousValue: true, value: { type: 'LONG', decimal: '9223372036854775807' } });
    expect(batchSet.entries[1]).toMatchObject({ namespace: 'customers', ttlMillis: null, setMode: 'ONLY_IF_ABSENT', returnPreviousValue: false });

    await user.type(screen.getByLabelText('Batch delete keys'), 'orders\tone\ncustomers\ttwo');
    await user.click(screen.getByRole('button', { name: 'Delete entry batch' }));
    expect(await screen.findByText(/"deletedCount": "2"/u)).toBeVisible();
    expect(requests('POST', '/entries/batch-delete')[0]!.body).toEqual({ keys: [{ namespace: 'orders', key: 'one' }, { namespace: 'customers', key: 'two' }] });

    await user.click(screen.getByRole('button', { name: 'Refresh core metrics' }));
    expect(await screen.findByText('Cache Gets')).toBeVisible();
    expect(screen.getByText('Cache Sets')).toBeVisible();
    expect(requests('GET', '/cache-metrics')).toHaveLength(1);
    expect(JSON.stringify(fixture.store.getState())).not.toContain('deletedCount');
  }, 30_000);

  it('rejects malformed batch input locally before any request leaves the browser', async () => {
    const user = userEvent.setup();
    page();
    await user.type(screen.getByLabelText('Batch get keys'), 'no-tab-here');
    await user.click(screen.getByRole('button', { name: 'Get entry batch' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('VALIDATION_FAILED');
    await user.type(screen.getByLabelText('Batch set entries (JSON)'), 'not json');
    await user.click(screen.getByRole('button', { name: 'Set entry batch' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('Batch set entries must be a valid JSON array');
    expect(fixture.requests((request) => request.method === 'POST')).toHaveLength(0);
  });

  it('withholds capability-gated panels', async () => {
    page({ canBatch: false, canMetrics: false, canOwnLocks: false, canScan: false });
    expect(await screen.findByRole('button', { name: 'Check entry existence' })).toBeVisible();
    expect(screen.queryByRole('heading', { name: 'Batch get' })).not.toBeInTheDocument();
    expect(screen.queryByRole('heading', { name: 'Batch set' })).not.toBeInTheDocument();
    expect(screen.queryByRole('heading', { name: 'Exact core metrics' })).not.toBeInTheDocument();
    expect(screen.queryByRole('heading', { name: 'Owner lock lifecycle' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Run backend scan' })).not.toBeInTheDocument();
  });

  it('operates the complete owner-token lock lifecycle and clears the token', async () => {
    const user = userEvent.setup();
    page();
    const panel = screen.getByRole('region', { name: 'Owner lock lifecycle' });
    await user.type(within(panel).getByLabelText('Lock namespace'), 'orders');
    await user.type(within(panel).getByLabelText('Lock key'), 'processor');
    await user.type(within(panel).getByLabelText('Owner token'), 'owner-secret');
    expect(within(panel).getByLabelText('Owner token')).toHaveAttribute('type', 'password');
    await user.click(within(panel).getByRole('button', { name: 'Acquire lock' }));
    expect(await screen.findByText(/Acquired · fencing 7/u)).toBeVisible();
    await user.click(within(panel).getByRole('button', { name: 'Renew lock' }));
    expect(await screen.findByText('Renewed')).toBeVisible();
    await user.click(within(panel).getByRole('button', { name: 'Check ownership' }));
    expect(await screen.findByText('Owner token holds this lock')).toBeVisible();
    await user.click(within(panel).getByRole('button', { name: 'Release by owner' }));
    expect(await screen.findByText('Released by owner')).toBeVisible();

    const lockCalls = fixture.requests((request) => request.method === 'POST' && request.path.includes('/locks/'));
    expect(lockCalls.map((request) => request.path.split('/').at(-1))).toEqual(['acquire', 'renew', 'ownership', 'release']);
    expect(lockCalls[0]!.body).toEqual({ ownerToken: 'owner-secret', leaseTtlMillis: 30_000, reentrantForSameOwner: false, issueFencingToken: true });
    expect(lockCalls[3]!.body).toEqual({ ownerToken: 'owner-secret' });
    expect(JSON.stringify(fixture.store.getState())).not.toContain('owner-secret');
    await user.click(within(panel).getByRole('button', { name: 'Clear sensitive state' }));
    expect(within(panel).getByLabelText('Owner token')).toHaveValue('');
    expect(document.body).not.toHaveTextContent('Released by owner');
  });
});
