import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { describe, expect, it } from 'vitest';

import type { EntryClientPort, EntryQuery } from '@src/api/inspection-client';
import type { EntryMetadata, EntryPage } from '@src/api/inspection-schemas';
import { EntriesPage } from '@src/features/entries/EntriesPage';

const entry: EntryMetadata = {
  namespace: '客户/订单',
  encodedNamespace: '5a6i5oi3L-iureWNlQ',
  key: 'café/東京/🔒?x=1',
  encodedKey: 'Y2Fmw6kv5p2x5LqsL_CflJI_eD0x',
  valueType: 'STRING',
  sizeBytes: '17',
  version: '9007199254740993',
  createdAt: '2026-08-26T10:00:00Z',
  updatedAt: '2026-08-26T10:15:00Z',
  lastAccessedAt: null,
  ttl: { state: 'EXPIRING', ttlMillis: 45_000, expiresAt: '2026-08-26T10:15:45Z' },
};

class FakeEntryClient implements EntryClientPort {
  queries: EntryQuery[] = [];

  async entries(
    _setupId: string,
    _encodedNamespace: string,
    query: EntryQuery = {},
  ): Promise<EntryPage> {
    this.queries.push(query);
    return {
      items: [{ ...entry, key: query.cursor === 'entry-cursor-2' ? 'next-page' : entry.key }],
      nextCursor: query.cursor === undefined ? 'entry-cursor-2' : null,
      hasMore: query.cursor === undefined,
    };
  }

  async entry(): Promise<EntryMetadata> { return entry; }
}

describe('U4 metadata-only entry browser', () => {
  it('does not offer expired-entry inspection when the setup capability is absent', async () => {
    const client = new FakeEntryClient();
    render(
      <MemoryRouter>
        <EntriesPage canInspectExpired={false} client={client} selectedNamespace={entry.namespace} selectedSetupId="primary-cache" />
      </MemoryRouter>,
    );

    await screen.findByRole('link', { name: entry.key });
    expect(screen.getByLabelText('TTL state')).not.toHaveTextContent('Include expired');
    expect(client.queries).toEqual([{ ttlState: 'ALL_LIVE', sort: 'key:asc', limit: 50 }]);
  });

  it('requires both setup and namespace scope instead of issuing a broad query', () => {
    const client = new FakeEntryClient();
    const { rerender } = render(<MemoryRouter><EntriesPage client={client} /></MemoryRouter>);
    expect(screen.getByRole('heading', { name: 'Select a connected setup' })).toBeVisible();

    rerender(
      <MemoryRouter>
        <EntriesPage client={client} selectedSetupId="primary-cache" />
      </MemoryRouter>,
    );
    expect(screen.getByRole('heading', { name: 'Select a namespace' })).toBeVisible();
    expect(client.queries).toEqual([]);
  });

  it('renders metadata without values and preserves opaque cursor history', async () => {
    const user = userEvent.setup();
    const client = new FakeEntryClient();
    render(
      <MemoryRouter>
        <EntriesPage client={client} selectedNamespace={entry.namespace} selectedSetupId="primary-cache" />
      </MemoryRouter>,
    );

    expect(await screen.findByRole('link', { name: entry.key })).toHaveAttribute(
      'href',
      `/keys/${entry.encodedNamespace}/${entry.encodedKey}`,
    );
    expect(screen.getByText('9,007,199,254,740,993')).toBeVisible();
    const metadataRow = screen.getByRole('link', { name: entry.key }).closest('tr');
    expect(metadataRow).not.toBeNull();
    expect(within(metadataRow!).getByText('Expiring')).toBeVisible();
    expect(document.body.textContent).not.toContain('must-not-cross');

    await user.type(screen.getByLabelText('Key prefix'), 'café/');
    await user.selectOptions(screen.getByLabelText('Value type'), 'STRING');
    await user.selectOptions(screen.getByLabelText('TTL state'), 'INCLUDE_EXPIRED');
    await user.click(screen.getByRole('button', { name: 'Apply filters' }));
    expect(client.queries.at(-1)).toEqual({
      prefix: 'café/', valueType: 'STRING', ttlState: 'INCLUDE_EXPIRED',
      sort: 'key:asc', limit: 50,
    });

    await user.click(screen.getByRole('button', { name: 'Next page' }));
    expect(await screen.findByRole('link', { name: 'next-page' })).toBeVisible();
    expect(client.queries.at(-1)).toMatchObject({ cursor: 'entry-cursor-2' });

    await user.click(screen.getByRole('button', { name: 'Previous page' }));
    expect(await screen.findByRole('link', { name: entry.key })).toBeVisible();
    expect(client.queries.at(-1)?.cursor).toBeUndefined();
  });
});
