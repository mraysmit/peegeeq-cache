import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';

import type { CounterClientPort, LockClientPort } from '@src/api/resource-client';
import type { Counter, CounterAdjustBody, CounterSetBody, LockPage, LockState, RevealedLockOwner } from '@src/api/resource-schemas';
import type { BulkDeletePreview, BulkDeleteResult } from '@src/api/entry-administration-schemas';
import { CountersPage } from '@src/features/counters/CountersPage';
import { LocksPage } from '@src/features/locks/LocksPage';

const counter: Counter = { namespace: 'orders', encodedNamespace: 'b3JkZXJz', key: 'total', encodedKey: 'dG90YWw', value: '9223372036854775807', version: '3', createdAt: '2026-08-29T10:00:00Z', updatedAt: '2026-08-29T10:01:00Z', ttl: { state: 'PERSISTENT', ttlMillis: null, expiresAt: null } };
const createdCounter: Counter = { ...counter, namespace: 'logical-orders', encodedNamespace: 'bG9naWNhbC1vcmRlcnM', key: 'bulk-counter', encodedKey: 'YnVsay1jb3VudGVy', value: '-9223372036854775808', version: '1' };
const lock: LockState = { namespace: 'orders', encodedNamespace: 'b3JkZXJz', key: 'processor', encodedKey: 'cHJvY2Vzc29y', fencingToken: '9223372036854775807', version: '4', createdAt: '2026-08-29T10:00:00Z', updatedAt: '2026-08-29T10:01:00Z', leaseExpiresAt: '2099-08-29T10:02:00Z', leaseRemainingMillis: 60_000, owner: { state: 'MASKED' } };

class CountersFake implements CounterClientPort {
  adjustments: CounterAdjustBody[] = [];
  deleted: string[] = [];
  async counters() { return { items: [counter], nextCursor: null, hasMore: false }; }
  async counter() { return counter; }
  async setCounter(_s: string, _n: string, _k: string, version: string | undefined, body: CounterSetBody) { return version === undefined ? { ...createdCounter, value: body.value } : { ...counter, value: body.value, version: '4' }; }
  async adjustCounter(_s: string, _n: string, _k: string, _v: string | undefined, body: CounterAdjustBody) { this.adjustments.push(body); return body.createIfMissing ? { ...createdCounter, value: body.delta } : { ...counter, value: '-9223372036854775808', version: '4' }; }
  async expireCounter() { return counter; } async persistCounter() { return counter; } async deleteCounter(_s: string, _n: string, _k: string, version: string) { this.deleted.push(version); }
  async previewCounterBulkDelete(): Promise<BulkDeletePreview> { throw new Error('unused'); }
  async executeCounterBulkDelete(): Promise<BulkDeleteResult> { throw new Error('unused'); }
}

class LocksFake implements LockClientPort {
  releases: Array<{ version: string; key: string }> = [];
  async locks(): Promise<LockPage> { return { items: [lock], nextCursor: null, hasMore: false }; }
  async lock(): Promise<LockState> { return lock; }
  async revealLockOwner(): Promise<RevealedLockOwner> { return { key: lock.key, ownerToken: 'sensitive-owner', version: lock.version, revealedAt: '2026-08-29T10:01:30Z', autoHideAfterMillis: 60_000 }; }
  async forceReleaseLock(_s: string, _n: string, _k: string, version: string, key: string): Promise<void> { this.releases.push({ version, key }); }
}

describe('U6 counter and lock pages', () => {
  it('keeps viewer counter and lock surfaces read-only and owner-masked', async () => {
    render(<CountersPage canOperate={false} client={new CountersFake()} selectedSetupId="primary-cache" />);
    expect(await screen.findByText('9,223,372,036,854,775,807')).toBeVisible();
    expect(screen.getByRole('region', { name: 'Counters results' })).toHaveAttribute('tabindex', '0');
    expect(screen.queryByRole('button', { name: 'Create counter' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Manage total' })).not.toBeInTheDocument();
    expect(screen.queryByLabelText('Select orders/total')).not.toBeInTheDocument();

    render(<LocksPage canOperate={false} canReveal={false} client={new LocksFake()} selectedSetupId="primary-cache" />);
    expect((await screen.findAllByText('Masked')).length).toBeGreaterThan(0);
    expect(screen.queryByRole('button', { name: 'Manage processor' })).not.toBeInTheDocument();
    expect(document.body).not.toHaveTextContent('sensitive-owner');
  });

  it('degrades bulk-delete and forced-release capabilities independently', async () => {
    const counters = render(<CountersPage canBulkDelete={false} canOperate client={new CountersFake()} selectedSetupId="primary-cache" />);
    expect(await screen.findByRole('button', { name: 'Create counter' })).toBeVisible();
    expect(screen.getByRole('button', { name: 'Manage total' })).toBeVisible();
    expect(screen.queryByLabelText('Select orders/total')).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Preview selected counter deletion' })).not.toBeInTheDocument();
    counters.unmount();

    render(<LocksPage canOperate={false} canReveal client={new LocksFake()} selectedSetupId="primary-cache" />);
    expect(await screen.findByRole('button', { name: 'Manage processor' })).toBeVisible();
    await userEvent.setup().click(screen.getByRole('button', { name: 'Manage processor' }));
    expect(screen.getByRole('button', { name: 'Reveal owner' })).toBeVisible();
    expect(screen.queryByRole('button', { name: 'Force release' })).not.toBeInTheDocument();
  });

  it('renders exact 64-bit values and only presents a committed adjustment result', async () => {
    const client = new CountersFake();
    const user = userEvent.setup();
    render(<CountersPage canOperate client={client} selectedSetupId="primary-cache" />);
    expect(await screen.findByText('9,223,372,036,854,775,807')).toBeVisible();
    await user.click(screen.getByRole('button', { name: 'Manage total' }));
    await user.type(screen.getByLabelText('Signed adjustment'), '-2');
    await user.click(screen.getByRole('button', { name: 'Apply adjustment' }));
    expect(await screen.findByRole('status')).toHaveTextContent('-9,223,372,036,854,775,808');
    expect(client.adjustments[0]?.delta).toBe('-2');
    await user.type(screen.getByLabelText('Confirm counter key'), counter.key);
    await user.click(screen.getByRole('button', { name: 'Delete current version' }));
    expect(await screen.findByRole('status')).toHaveTextContent('observed version 4');
    expect(client.deleted).toEqual(['4']);
  });

  it('adds a server-confirmed newly created counter to the visible result set', async () => {
    const client = new CountersFake();
    const user = userEvent.setup();
    render(<CountersPage canOperate client={client} selectedSetupId="primary-cache" />);
    await screen.findByText('9,223,372,036,854,775,807');
    await user.click(screen.getByRole('button', { name: 'Create counter' }));
    const dialog = screen.getByRole('dialog', { name: 'Create counter' });
    await user.type(within(dialog).getByLabelText('Namespace'), createdCounter.namespace);
    await user.type(within(dialog).getByLabelText('Key'), createdCounter.key);
    await user.type(within(dialog).getByLabelText('Exact decimal value'), createdCounter.value);
    await user.click(within(dialog).getByRole('button', { name: 'Set exact value' }));
    expect(await screen.findByLabelText('Select logical-orders/bulk-counter')).toBeVisible();
  });

  it('creates a missing counter atomically from a signed adjustment', async () => {
    const client = new CountersFake();
    const user = userEvent.setup();
    render(<CountersPage canOperate client={client} selectedSetupId="primary-cache" />);
    await screen.findByText('9,223,372,036,854,775,807');
    await user.click(screen.getByRole('button', { name: 'Create counter' }));
    const dialog = screen.getByRole('dialog', { name: 'Create counter' });
    await user.type(within(dialog).getByLabelText('Namespace'), createdCounter.namespace);
    await user.type(within(dialog).getByLabelText('Key'), createdCounter.key);
    await user.type(within(dialog).getByLabelText('Signed adjustment'), '-7');
    await user.type(within(dialog).getByLabelText('TTL milliseconds (blank for persistent)'), '60000');
    await user.click(within(dialog).getByRole('button', { name: 'Create by adjustment' }));

    expect(client.adjustments).toEqual([{
      delta: '-7',
      createIfMissing: true,
      ttlMode: 'REPLACE',
      ttlMillis: 60_000,
    }]);
    expect(await screen.findByRole('status')).toHaveTextContent('Counter created by adjustment');
    const managed = screen.getByRole('dialog', { name: 'Manage bulk-counter' });
    await user.type(within(managed).getByLabelText('Confirm counter key'), createdCounter.key);
    await user.click(within(managed).getByRole('button', { name: 'Delete current version' }));
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    expect(client.deleted).toEqual(['1']);
  });

  it('keeps owners masked, clears explicit reveal, and releases only the freshly loaded version', async () => {
    const client = new LocksFake();
    const user = userEvent.setup();
    render(<LocksPage canOperate canReveal client={client} selectedSetupId="primary-cache" />);
    expect(await screen.findByText('Masked')).toBeVisible();
    expect(screen.getByRole('region', { name: 'Active locks results' })).toHaveAttribute('tabindex', '0');
    expect(document.body).not.toHaveTextContent('sensitive-owner');
    await user.click(screen.getByRole('button', { name: 'Manage processor' }));
    await user.click(screen.getByRole('button', { name: 'Reveal owner' }));
    expect(await screen.findByText('sensitive-owner')).toBeVisible();
    await user.click(screen.getByRole('button', { name: 'Hide owner' }));
    expect(document.body).not.toHaveTextContent('sensitive-owner');
    await user.click(screen.getByRole('button', { name: 'Force release' }));
    await user.type(screen.getByLabelText('Confirm lock key'), lock.key);
    await user.click(screen.getByRole('button', { name: 'Release current version' }));
    expect(await screen.findByRole('status')).toHaveTextContent('Lock released');
    expect(client.releases).toEqual([{ version: '4', key: lock.key }]);
  });
});
