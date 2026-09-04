import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';

import { encodeKey } from '@src/api/identifier-codec';
import { EntriesPage } from '@src/features/entries/EntriesPage';
import { EntryDetailsPage } from '@src/features/entries/EntryDetailsPage';
import { chooseOption, renderWithProviders } from './support/render';
import { ordersMetadata, startEntryFixture, type EntryFixture } from './support/entry-fixture';
import type { LoopbackRequest } from './support/loopback-server';

const metadata = ordersMetadata;
const entryPath = `/api/v1/setups/primary-cache/namespaces/${metadata.encodedNamespace}/entries/${metadata.encodedKey}`;

describe('U5 entry administration pages', () => {
  let fixture: EntryFixture;

  beforeEach(async () => { fixture = await startEntryFixture(ordersMetadata); });
  afterEach(async () => { await fixture.close(); });

  const details = (canOperate = true) => renderWithProviders(
    <EntryDetailsPage canOperate={canOperate} canReveal={false} encodedKey={metadata.encodedKey} encodedNamespace={metadata.encodedNamespace} selectedSetupId="primary-cache" />,
    { store: fixture.store },
  );
  const browser = (props: Partial<Parameters<typeof EntriesPage>[0]> = {}) => renderWithProviders(
    <EntriesPage canOperate selectedNamespace={metadata.namespace} selectedSetupId="primary-cache" {...props} />,
    { store: fixture.store },
  );
  const requests = (method: string, suffix: string) => fixture.requests((request: LoopbackRequest) => request.method === method && request.path.endsWith(suffix));

  it('renders viewer entry inventory and details without mutation or reveal controls', async () => {
    const inventory = browser({ canOperate: false });
    expect(await screen.findByRole('link', { name: metadata.key })).toBeVisible();
    expect(screen.getByRole('region', { name: 'Entry results' })).toHaveAttribute('tabindex', '0');
    expect(screen.queryByRole('button', { name: 'Create entry' })).not.toBeInTheDocument();
    expect(screen.queryByLabelText(`Select ${metadata.key}`)).not.toBeInTheDocument();
    inventory.unmount();

    details(false);
    expect(await screen.findByText('Value hidden')).toBeVisible();
    expect(screen.queryByRole('button', { name: 'Edit entry' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Reveal value' })).not.toBeInTheDocument();
    expect(fixture.requests((request) => request.method !== 'GET')).toHaveLength(0);
  });

  it('keeps single-entry administration while a setup disables bulk deletion', async () => {
    browser({ canBulkDelete: false });
    expect(await screen.findByRole('button', { name: 'Create entry' })).toBeVisible();
    await screen.findByRole('link', { name: metadata.key });
    expect(screen.queryByLabelText(`Select ${metadata.key}`)).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Preview selected deletion' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Preview matching-filter deletion' })).not.toBeInTheDocument();
  });

  it('creates an absent entry with the typed contract value and an absence precondition', async () => {
    const user = userEvent.setup();
    browser();
    await screen.findByRole('link', { name: metadata.key });
    await user.click(screen.getByRole('button', { name: 'Create entry' }));
    const dialog = await screen.findByRole('dialog', { name: 'Create entry' });
    await waitFor(() => expect(within(dialog).getByText('The key must be absent when PostgreSQL commits this request.')).toBeVisible());
    await user.type(within(dialog).getByLabelText('Key'), 'order:2');
    await chooseOption(user, within(dialog).getByLabelText('Value type'), 'JSON');
    await user.type(within(dialog).getByLabelText('TTL milliseconds (blank for persistent)'), '5000');
    await user.type(within(dialog).getByLabelText('Value'), '{{"total":42}');
    await user.click(within(dialog).getByRole('button', { name: 'Create entry' }));

    expect(await screen.findByRole('status')).toHaveTextContent('Entry created at');
    const puts = requests('PUT', `/entries/${encodeKey('order:2')}`);
    expect(puts).toHaveLength(1);
    expect(puts[0]!.headers['if-none-match']).toBe('*');
    expect(puts[0]!.headers['if-match']).toBeUndefined();
    expect(puts[0]!.body).toEqual({ value: { type: 'JSON', text: '{"total":42}' }, ttlMode: 'REPLACE', ttlMillis: 5000, setMode: 'ONLY_IF_ABSENT' });
    expect(screen.queryByRole('dialog', { name: 'Create entry' })).not.toBeInTheDocument();
  });

  it('defaults existing edits to observed-version CAS and presents the committed server result', async () => {
    const user = userEvent.setup();
    details();
    await screen.findByRole('heading', { name: metadata.key });

    await user.click(screen.getByRole('button', { name: 'Edit entry' }));
    expect(screen.getByLabelText('Set mode').closest('.ant-select')).toHaveTextContent('Only if observed version matches');
    expect(screen.getByLabelText('Value type')).toBeDisabled();
    expect(screen.getByLabelText('Replacement TTL milliseconds')).toBeDisabled();
    await user.type(screen.getByLabelText('Entry value'), 'replacement');
    await user.click(screen.getByRole('button', { name: 'Save entry' }));

    expect(await screen.findByRole('status')).toHaveTextContent('Entry updated at');
    expect(screen.getByRole('status')).toHaveTextContent('version 4');
    const puts = requests('PUT', entryPath);
    expect(puts).toHaveLength(1);
    expect(puts[0]!.headers['if-match']).toBe('"v3"');
    expect(puts[0]!.body).toEqual({ value: { type: 'STRING', text: 'replacement' }, ttlMode: 'PRESERVE_EXISTING', ttlMillis: null, setMode: 'ONLY_IF_VERSION_MATCHES' });
    await waitFor(() => expect(screen.getByLabelText('Entry metadata')).toHaveTextContent('Version4'));
    expect(screen.queryByLabelText('Entry value')).not.toBeInTheDocument();
  });

  it('sends a replacement TTL only in REPLACE mode and an upsert without a version precondition', async () => {
    const user = userEvent.setup();
    details();
    await screen.findByRole('heading', { name: metadata.key });
    await user.click(screen.getByRole('button', { name: 'Edit entry' }));
    await chooseOption(user, screen.getByLabelText('Set mode'), 'Always / upsert');
    await chooseOption(user, screen.getByLabelText('TTL behavior'), 'Replace TTL');
    expect(screen.getByLabelText('Replacement TTL milliseconds')).toBeEnabled();
    await user.type(screen.getByLabelText('Replacement TTL milliseconds'), '90000');
    await user.type(screen.getByLabelText('Entry value'), 'upserted');
    await user.click(screen.getByRole('button', { name: 'Save entry' }));

    expect(await screen.findByRole('status')).toHaveTextContent('Entry updated at');
    const puts = requests('PUT', entryPath);
    expect(puts).toHaveLength(1);
    expect(puts[0]!.headers['if-match']).toBeUndefined();
    expect(puts[0]!.headers['if-none-match']).toBeUndefined();
    expect(puts[0]!.body).toEqual({ value: { type: 'STRING', text: 'upserted' }, ttlMode: 'REPLACE', ttlMillis: 90_000, setMode: 'UPSERT' });
  });

  it('preserves edit input and reloads metadata after a CAS conflict', async () => {
    const user = userEvent.setup();
    fixture.state.setEntryProblem = { status: 412, code: 'VERSION_MISMATCH', detail: 'The entry changed' };
    details();
    await screen.findByRole('heading', { name: metadata.key });
    const readsBefore = requests('GET', entryPath).length;
    await user.click(screen.getByRole('button', { name: 'Edit entry' }));
    await user.type(screen.getByLabelText('Entry value'), 'keep this input');
    await user.click(screen.getByRole('button', { name: 'Save entry' }));

    expect(await screen.findByRole('alert')).toHaveTextContent('VERSION_MISMATCH');
    expect(screen.getByRole('alert')).toHaveTextContent('The entry changed');
    expect(screen.getByLabelText('Entry value')).toHaveValue('keep this input');
    await waitFor(() => expect(requests('GET', entryPath).length).toBeGreaterThan(readsBefore));
    expect(screen.queryByRole('status')).not.toBeInTheDocument();
  });

  it('uses authoritative metadata for TTL/touch and exact observed version for deletion', async () => {
    const user = userEvent.setup();
    details();
    await screen.findByRole('heading', { name: metadata.key });
    expect(screen.getByLabelText('Entry metadata')).toHaveTextContent('Remaining TTLPersistent');

    await user.type(screen.getByLabelText('TTL milliseconds'), '60000');
    await user.click(screen.getByRole('button', { name: 'Set TTL' }));
    expect(await screen.findByRole('status')).toHaveTextContent('Entry TTL updated');
    await waitFor(() => expect(screen.getByLabelText('Entry metadata')).toHaveTextContent('Remaining TTL60 s'));
    expect(requests('POST', '/ttl')[0]!.headers['if-match']).toBe('"v3"');
    expect(requests('POST', '/ttl')[0]!.body).toEqual({ ttlMillis: 60_000 });

    await user.click(screen.getByRole('button', { name: 'Touch entry' }));
    expect(await screen.findByRole('status')).toHaveTextContent('Entry touched');
    await waitFor(() => expect(screen.getByText('Last accessed').parentElement).toHaveTextContent('2026'));
    expect(requests('POST', '/touch')[0]!.headers['if-match']).toBe('"v3"');
    expect(requests('POST', '/touch')[0]!.body).toEqual({ refreshTtlMillis: null });

    await user.click(screen.getByRole('button', { name: 'Make persistent' }));
    expect(await screen.findByRole('status')).toHaveTextContent('Entry is persistent');
    await waitFor(() => expect(screen.getByLabelText('Entry metadata')).toHaveTextContent('Remaining TTLPersistent'));
    expect(requests('POST', '/persist')[0]!.headers['if-match']).toBe('"v3"');

    await user.click(screen.getByRole('button', { name: 'Delete entry' }));
    const dialog = await screen.findByRole('dialog', { name: `Delete ${metadata.key}?` });
    await waitFor(() => expect(within(dialog).getByText('This exact version is deleted only if it has not changed.')).toBeVisible());
    expect(screen.getByRole('button', { name: 'Confirm delete' })).toBeDisabled();
    await user.type(screen.getByLabelText('Confirm entry key'), metadata.key);
    await user.click(screen.getByRole('button', { name: 'Confirm delete' }));
    expect(await screen.findByRole('status')).toHaveTextContent('Entry deleted');
    const deletes = requests('DELETE', entryPath);
    expect(deletes).toHaveLength(1);
    expect(deletes[0]!.headers['if-match']).toBe('"v3"');
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
  });

  it('previews selected exact-version targets and executes only the typed server phrase', async () => {
    const user = userEvent.setup();
    browser();
    await screen.findByRole('link', { name: metadata.key });
    expect(screen.getByRole('button', { name: 'Preview selected deletion' })).toBeDisabled();
    await user.click(screen.getByRole('checkbox', { name: `Select ${metadata.key}` }));
    await user.click(screen.getByRole('button', { name: 'Preview selected deletion' }));

    expect(await screen.findByRole('dialog', { name: 'Confirm bulk entry deletion' })).toHaveTextContent('DELETE 1 ENTRIES');
    const previews = requests('POST', '/bulk-delete/preview');
    expect(previews).toHaveLength(1);
    expect(previews[0]!.body).toEqual({ selection: { type: 'EXPLICIT', targets: [{ key: metadata.key, version: metadata.version }] } });
    expect(screen.getByRole('button', { name: 'Delete previewed entries' })).toBeDisabled();
    await user.type(screen.getByLabelText('Type confirmation phrase'), 'DELETE 1 ENTRIE');
    expect(screen.getByRole('button', { name: 'Delete previewed entries' })).toBeDisabled();
    expect(requests('POST', '/bulk-delete/execute')).toHaveLength(0);
    await user.type(screen.getByLabelText('Type confirmation phrase'), 'S');
    await user.click(screen.getByRole('button', { name: 'Delete previewed entries' }));

    expect(await screen.findByRole('status')).toHaveTextContent('Deleted 1 of 1 previewed entries');
    const executions = requests('POST', '/bulk-delete/execute');
    expect(executions).toHaveLength(1);
    expect(executions[0]!.body).toEqual({ previewToken: 'p'.repeat(32), confirmationPhrase: 'DELETE 1 ENTRIES' });
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
  });

  it('previews a matching-filter deletion with the applied server filter rather than the visible rows', async () => {
    const user = userEvent.setup();
    browser();
    await screen.findByRole('link', { name: metadata.key });
    await user.type(screen.getByLabelText('Key prefix'), 'order:');
    await chooseOption(user, screen.getByLabelText('TTL state'), 'Persistent');
    await user.click(screen.getByRole('button', { name: 'Apply filters' }));
    await waitFor(() => expect(requests('GET', '/entries').at(-1)!.query.get('prefix')).toBe('order:'));
    await user.click(screen.getByRole('button', { name: 'Preview matching-filter deletion' }));

    const dialog = await screen.findByRole('dialog', { name: 'Confirm bulk entry deletion' });
    await waitFor(() => expect(within(dialog).getByText('DELETE 1 ENTRIES')).toBeVisible());
    expect(requests('POST', '/bulk-delete/preview')[0]!.body).toEqual({ selection: { type: 'FILTER', prefix: 'order:', ttlState: 'PERSISTENT' } });
    await user.click(screen.getByRole('button', { name: 'Cancel' }));
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    expect(requests('POST', '/bulk-delete/execute')).toHaveLength(0);
  });
});
