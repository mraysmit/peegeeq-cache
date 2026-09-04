import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';

import { EntriesPage } from '@src/features/entries/EntriesPage';
import { chooseOption, renderWithProviders } from './support/render';
import { entryMetadata, startEntryFixture, type EntryFixture } from './support/entry-fixture';
import type { LoopbackRequest } from './support/loopback-server';

const entry = entryMetadata;

describe('U4 metadata-only entry browser', () => {
  let fixture: EntryFixture;

  beforeEach(async () => { fixture = await startEntryFixture(); });
  afterEach(async () => { await fixture.close(); });

  const listRequests = () => fixture.requests((request: LoopbackRequest) => request.method === 'GET' && request.path.endsWith(`/namespaces/${entry.encodedNamespace}/entries`));
  const renderBrowser = (props: Partial<Parameters<typeof EntriesPage>[0]> = {}) =>
    renderWithProviders(<EntriesPage selectedNamespace={entry.namespace} selectedSetupId="primary-cache" {...props} />, { store: fixture.store });

  it('does not offer expired-entry inspection when the setup capability is absent', async () => {
    const user = userEvent.setup();
    renderBrowser({ canInspectExpired: false });

    await screen.findByRole('link', { name: entry.key });
    await user.click(screen.getByLabelText('TTL state'));
    expect(await screen.findByRole('option', { name: 'All live' })).toBeInTheDocument();
    expect(screen.queryByRole('option', { name: 'Include expired' })).not.toBeInTheDocument();
    await user.keyboard('{Escape}');
    expect(listRequests()).toHaveLength(1);
    expect(Object.fromEntries(listRequests()[0]!.query)).toEqual({ ttlState: 'ALL_LIVE', sort: 'key:asc', limit: '50' });
  });

  it('requires both setup and namespace scope instead of issuing a broad query', async () => {
    const { rerender } = renderWithProviders(<EntriesPage />, { store: fixture.store });
    expect(await screen.findByRole('heading', { name: 'Select a connected setup' })).toBeVisible();

    rerender(<EntriesPage selectedSetupId="primary-cache" />);
    expect(await screen.findByRole('heading', { name: 'Select a namespace' })).toBeVisible();
    expect(screen.queryByRole('table')).not.toBeInTheDocument();
    expect(fixture.requests((request) => request.path.includes('/entries'))).toHaveLength(0);
  });

  it('renders metadata without values and preserves opaque cursor history', async () => {
    const user = userEvent.setup();
    renderBrowser();

    expect(await screen.findByRole('link', { name: entry.key })).toHaveAttribute('href', `/keys/${entry.encodedNamespace}/${entry.encodedKey}`);
    expect(screen.getByText('9,007,199,254,740,993')).toBeVisible();
    const metadataRow = screen.getByRole('link', { name: entry.key }).closest('tr');
    expect(metadataRow).not.toBeNull();
    expect(within(metadataRow!).getByText('Expiring')).toBeVisible();
    expect(screen.getByRole('region', { name: 'Entry results' })).toHaveAttribute('tabindex', '0');
    expect(document.body.textContent).not.toContain('sensitive');

    await user.type(screen.getByLabelText('Key prefix'), 'café/');
    await chooseOption(user, screen.getByLabelText('Value type'), 'String');
    await chooseOption(user, screen.getByLabelText('TTL state'), 'Include expired');
    await user.click(screen.getByRole('button', { name: 'Apply filters' }));
    await screen.findByRole('link', { name: entry.key });
    expect(Object.fromEntries(listRequests().at(-1)!.query)).toEqual({
      prefix: 'café/', valueType: 'STRING', ttlState: 'INCLUDE_EXPIRED', sort: 'key:asc', limit: '50',
    });

    await user.click(screen.getByRole('button', { name: 'Next page' }));
    expect(await screen.findByRole('link', { name: 'next-page' })).toBeVisible();
    expect(Object.fromEntries(listRequests().at(-1)!.query)).toMatchObject({ prefix: 'café/', cursor: 'entry-cursor-2' });
    expect(screen.getByRole('navigation', { name: 'Entry pages' })).toHaveTextContent('Page 2');

    await user.click(screen.getByRole('button', { name: 'Previous page' }));
    expect(await screen.findByRole('link', { name: entry.key })).toBeVisible();
    expect(screen.getByRole('navigation', { name: 'Entry pages' })).toHaveTextContent('Page 1');
    // Backwards navigation replays the server-issued cursor stack; it never fabricates a cursor.
    expect(listRequests().map((request) => request.query.get('cursor'))).toEqual([null, null, 'entry-cursor-2']);
  });
});
