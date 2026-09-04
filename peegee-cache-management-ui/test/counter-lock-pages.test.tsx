import { act, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';

import { CountersPage } from '@src/features/counters/CountersPage';
import { LocksPage } from '@src/features/locks/LocksPage';
import { renderWithProviders } from './support/render';
import { processorLock, startResourceFixture, totalCounter, type ResourceFixture } from './support/resource-fixture';

describe('U6 counter and lock pages', () => {
  let fixture: ResourceFixture;

  beforeEach(async () => {
    localStorage.clear();
    sessionStorage.clear();
    fixture = await startResourceFixture();
  });
  afterEach(async () => { await fixture.close(); });

  const counters = (props: Partial<Parameters<typeof CountersPage>[0]> = {}) => renderWithProviders(<CountersPage canOperate selectedSetupId="primary-cache" {...props} />, { store: fixture.store });
  const locks = (props: Partial<Parameters<typeof LocksPage>[0]> = {}) => renderWithProviders(<LocksPage canOperate canReveal selectedSetupId="primary-cache" {...props} />, { store: fixture.store });
  const requests = (method: string, suffix: string) => fixture.requests((request) => request.method === method && request.path.endsWith(suffix));
  const openDialog = async (name: string) => {
    const dialog = await screen.findByRole('dialog', { name });
    await waitFor(() => expect(within(dialog).getByRole('heading', { name })).toBeVisible());
    return dialog;
  };

  it('keeps viewer counter and lock surfaces read-only and owner-masked', async () => {
    const view = counters({ canOperate: false });
    expect(await screen.findByText('9,223,372,036,854,775,807')).toBeVisible();
    expect(screen.getByRole('region', { name: 'Counters results' })).toHaveAttribute('tabindex', '0');
    expect(screen.getByRole('columnheader', { name: 'Value' })).toBeVisible();
    expect(screen.queryByRole('button', { name: 'Create counter' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Manage total' })).not.toBeInTheDocument();
    expect(screen.queryByLabelText('Select orders/total')).not.toBeInTheDocument();
    expect(Object.fromEntries(requests('GET', '/counters')[0]!.query)).toEqual({ ttlState: 'ALL_LIVE', sort: 'key:asc', limit: '50' });
    view.unmount();

    locks({ canOperate: false, canReveal: false });
    expect((await screen.findAllByText('Masked')).length).toBeGreaterThan(0);
    expect(screen.queryByRole('button', { name: 'Manage processor' })).not.toBeInTheDocument();
    expect(document.body).not.toHaveTextContent('sensitive-owner');
    expect(Object.fromEntries(requests('GET', '/locks')[0]!.query)).toEqual({ leaseState: 'ACTIVE', limit: '50' });
    expect(fixture.requests((request) => request.method !== 'GET')).toHaveLength(0);
  });

  it('degrades bulk-delete and forced-release capabilities independently', async () => {
    const view = counters({ canBulkDelete: false });
    expect(await screen.findByRole('button', { name: 'Create counter' })).toBeVisible();
    expect(await screen.findByRole('button', { name: 'Manage total' })).toBeVisible();
    expect(screen.queryByLabelText('Select orders/total')).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Preview selected counter deletion' })).not.toBeInTheDocument();
    view.unmount();

    locks({ canOperate: false });
    const user = userEvent.setup();
    await user.click(await screen.findByRole('button', { name: 'Manage processor' }));
    const dialog = await openDialog('Manage processor');
    expect(within(dialog).getByRole('button', { name: 'Reveal owner' })).toBeVisible();
    expect(within(dialog).queryByRole('button', { name: 'Force release' })).not.toBeInTheDocument();
  });

  it('filters counters live on the wire with trimmed values and clears the selection', async () => {
    const user = userEvent.setup();
    counters();
    await screen.findByText('9,223,372,036,854,775,807');
    await user.click(screen.getByLabelText('Select orders/total'));
    expect(screen.getByLabelText('Select orders/total')).toBeChecked();
    await user.type(screen.getByLabelText('Key prefix'), ' tot ');
    await waitFor(() => expect(requests('GET', '/counters').at(-1)!.query.get('prefix')).toBe('tot'));
    await screen.findByText('9,223,372,036,854,775,807');
    expect(screen.getByLabelText('Select orders/total')).not.toBeChecked();
    await user.type(screen.getByLabelText('Namespace'), 'absent');
    expect(await screen.findByText('No counters matched.')).toBeVisible();
    expect(requests('GET', '/counters').at(-1)!.query.get('namespace')).toBe('absent');
  });

  it('renders exact 64-bit values and only presents a committed adjustment result', async () => {
    const user = userEvent.setup();
    counters();
    expect(await screen.findByText('9,223,372,036,854,775,807')).toBeVisible();
    await user.click(screen.getByRole('button', { name: 'Manage total' }));
    const dialog = await openDialog('Manage total');
    expect(within(dialog).getByLabelText('Exact decimal value')).toHaveValue(totalCounter.value);
    expect(within(dialog).getByRole('button', { name: 'Apply adjustment' })).toBeDisabled();

    await user.type(within(dialog).getByLabelText('Signed adjustment'), '1');
    await user.click(within(dialog).getByRole('button', { name: 'Apply adjustment' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('COUNTER_OVERFLOW');
    expect(screen.queryByRole('status')).not.toBeInTheDocument();

    await user.clear(within(dialog).getByLabelText('Signed adjustment'));
    await user.type(within(dialog).getByLabelText('Signed adjustment'), '-2');
    await user.click(within(dialog).getByRole('button', { name: 'Apply adjustment' }));
    expect(await screen.findByRole('status')).toHaveTextContent('Counter adjusted: 9,223,372,036,854,775,805 · version 4');
    const increments = requests('POST', '/increment');
    expect(increments).toHaveLength(2);
    expect(increments[1]!.headers['if-match']).toBe('"v3"');
    expect(increments[1]!.body).toEqual({ delta: '-2', createIfMissing: false, ttlMode: 'PRESERVE_EXISTING', ttlMillis: null });
    await waitFor(() => expect(screen.getByRole('region', { name: 'Counters results' })).toHaveTextContent('9,223,372,036,854,775,805'));

    await user.type(within(dialog).getByLabelText('Confirm counter key'), totalCounter.key);
    await user.click(within(dialog).getByRole('button', { name: 'Delete current version' }));
    expect(await screen.findByRole('status')).toHaveTextContent('observed version 4');
    const deletes = requests('DELETE', `/counters/${totalCounter.encodedKey}`);
    expect(deletes).toHaveLength(1);
    expect(deletes[0]!.headers['if-match']).toBe('"v4"');
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
    expect(await screen.findByText('No counters matched.')).toBeVisible();
  });

  it('adds a server-confirmed newly created counter to the visible result set', async () => {
    const user = userEvent.setup();
    counters();
    await screen.findByText('9,223,372,036,854,775,807');
    await user.click(screen.getByRole('button', { name: 'Create counter' }));
    const dialog = await openDialog('Create counter');
    expect(within(dialog).getByRole('button', { name: 'Set exact value' })).toBeDisabled();
    await user.type(within(dialog).getByLabelText('Namespace'), 'logical-orders');
    await user.type(within(dialog).getByLabelText('Key'), 'bulk-counter');
    await user.type(within(dialog).getByLabelText('Exact decimal value'), '-9223372036854775808');
    await user.click(within(dialog).getByRole('button', { name: 'Set exact value' }));
    expect(await screen.findByRole('status')).toHaveTextContent('Counter created: -9,223,372,036,854,775,808 · version 1');
    const puts = requests('PUT', '/counters/YnVsay1jb3VudGVy');
    expect(puts).toHaveLength(1);
    expect(puts[0]!.path).toContain('/namespaces/bG9naWNhbC1vcmRlcnM/counters/');
    expect(puts[0]!.headers['if-none-match']).toBe('*');
    expect(puts[0]!.body).toEqual({ value: '-9223372036854775808', ttlMode: 'PRESERVE_EXISTING', ttlMillis: null });
    expect(await screen.findByLabelText('Select logical-orders/bulk-counter')).toBeInTheDocument();
    expect(await screen.findByRole('dialog', { name: 'Manage bulk-counter' })).toBeInTheDocument();
  });

  it('creates a missing counter atomically from a signed adjustment', async () => {
    const user = userEvent.setup();
    counters();
    await screen.findByText('9,223,372,036,854,775,807');
    await user.click(screen.getByRole('button', { name: 'Create counter' }));
    const dialog = await openDialog('Create counter');
    await user.type(within(dialog).getByLabelText('Namespace'), 'logical-orders');
    await user.type(within(dialog).getByLabelText('Key'), 'bulk-counter');
    await user.type(within(dialog).getByLabelText('Signed adjustment'), '-7');
    await user.type(within(dialog).getByLabelText('TTL milliseconds (blank for persistent)'), '60000');
    await user.click(within(dialog).getByRole('button', { name: 'Create by adjustment' }));

    expect(await screen.findByRole('status')).toHaveTextContent('Counter created by adjustment: -7 · version 1');
    const increments = requests('POST', '/increment');
    expect(increments).toHaveLength(1);
    expect(increments[0]!.headers['if-none-match']).toBe('*');
    expect(increments[0]!.body).toEqual({ delta: '-7', createIfMissing: true, ttlMode: 'REPLACE', ttlMillis: 60_000 });
    const managed = await openDialog('Manage bulk-counter');
    await user.type(within(managed).getByLabelText('Confirm counter key'), 'bulk-counter');
    await user.click(within(managed).getByRole('button', { name: 'Delete current version' }));
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
    expect(requests('DELETE', '/counters/YnVsay1jb3VudGVy')[0]!.headers['if-match']).toBe('"v1"');
  });

  it('previews selected counters and deletes them only with the typed server phrase', async () => {
    const user = userEvent.setup();
    counters();
    await screen.findByText('9,223,372,036,854,775,807');
    expect(screen.getByRole('button', { name: 'Preview selected counter deletion' })).toBeDisabled();
    await user.click(screen.getByLabelText('Select orders/total'));
    await user.click(screen.getByRole('button', { name: 'Preview selected counter deletion' }));
    const dialog = await openDialog('Confirm counter deletion');
    expect(dialog).toHaveTextContent('1 counters');
    expect(requests('POST', '/counters/bulk-delete/preview')[0]!.body).toEqual({ targets: [{ namespace: 'orders', key: 'total', version: '3' }] });
    expect(within(dialog).getByRole('button', { name: 'Delete previewed counters' })).toBeDisabled();
    await user.type(within(dialog).getByLabelText('Type confirmation phrase'), 'DELETE 1 COUNTERS');
    await user.click(within(dialog).getByRole('button', { name: 'Delete previewed counters' }));
    expect(await screen.findByRole('status')).toHaveTextContent('Deleted 1 of 1 counters.');
    expect(requests('POST', '/counters/bulk-delete/execute')[0]!.body).toEqual({ previewToken: 'c'.repeat(32), confirmationPhrase: 'DELETE 1 COUNTERS' });
    expect(await screen.findByText('No counters matched.')).toBeVisible();
  });

  it('keeps owners masked, clears explicit reveal, and releases only the freshly loaded version', async () => {
    const user = userEvent.setup();
    locks();
    expect(await screen.findByText('Masked')).toBeVisible();
    expect(screen.getByRole('region', { name: 'Active locks results' })).toHaveAttribute('tabindex', '0');
    expect(screen.getByRole('row', { name: /processor/u })).toHaveTextContent('60000 ms');
    expect(document.body).not.toHaveTextContent('sensitive-owner');

    await user.click(screen.getByRole('button', { name: 'Manage processor' }));
    const dialog = await openDialog('Manage processor');
    expect(dialog).toHaveTextContent('Version 4 · fencing token 9223372036854775807');
    expect(requests('GET', `/locks/${processorLock.encodedKey}`)).toHaveLength(1);
    await user.type(within(dialog).getByLabelText('Reason (optional)'), 'incident review');
    await user.click(within(dialog).getByRole('button', { name: 'Reveal owner' }));
    expect(await screen.findByText('sensitive-owner')).toBeVisible();
    const reveals = requests('POST', '/owner/reveal');
    expect(reveals).toHaveLength(1);
    expect(reveals[0]!.body).toEqual({ reason: 'incident review' });
    expect(JSON.stringify(fixture.store.getState())).not.toContain('sensitive-owner');
    expect(JSON.stringify({ localStorage, sessionStorage })).not.toContain('sensitive-owner');
    await user.click(screen.getByRole('button', { name: 'Hide owner' }));
    expect(document.body).not.toHaveTextContent('sensitive-owner');

    await user.click(within(dialog).getByRole('button', { name: 'Reveal owner' }));
    await screen.findByText('sensitive-owner');
    let hidden = true;
    Object.defineProperty(document, 'hidden', { configurable: true, get: () => hidden });
    act(() => { document.dispatchEvent(new globalThis.Event('visibilitychange')); });
    expect(document.body).not.toHaveTextContent('sensitive-owner');
    hidden = false;

    fixture.state.locks = [{ ...processorLock, version: '5' }];
    await user.click(within(dialog).getByRole('button', { name: 'Force release' }));
    const release = await openDialog('Release current lock version?');
    expect(requests('GET', `/locks/${processorLock.encodedKey}`)).toHaveLength(2);
    expect(within(release).getByLabelText('Confirm lock key')).toHaveFocus();
    expect(within(release).getByRole('button', { name: 'Release current version' })).toBeDisabled();
    await user.type(within(release).getByLabelText('Confirm lock key'), processorLock.key);
    await user.click(within(release).getByRole('button', { name: 'Release current version' }));
    expect(await screen.findByRole('status')).toHaveTextContent('Lock released after exact-version confirmation.');
    const releases = requests('POST', '/force-release');
    expect(releases).toHaveLength(1);
    expect(releases[0]!.headers['if-match']).toBe('"v5"');
    expect(releases[0]!.body).toEqual({ confirmationKey: 'processor', reason: 'incident review' });
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
    expect(await screen.findByRole('heading', { name: 'No active locks' })).toBeVisible();
  });

  it('reports a stale forced release as a conflict without releasing anything', async () => {
    const user = userEvent.setup();
    locks();
    await user.click(await screen.findByRole('button', { name: 'Manage processor' }));
    await openDialog('Manage processor');
    await user.click(screen.getByRole('button', { name: 'Force release' }));
    const release = await openDialog('Release current lock version?');
    fixture.state.locks = [{ ...processorLock, version: '6' }];
    await user.type(within(release).getByLabelText('Confirm lock key'), processorLock.key);
    await user.click(within(release).getByRole('button', { name: 'Release current version' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('VERSION_MISMATCH');
    expect(screen.queryByRole('status')).not.toBeInTheDocument();
    expect(screen.getByRole('dialog', { name: 'Release current lock version?' })).toBeInTheDocument();
  });
});
