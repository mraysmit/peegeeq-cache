import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { describe, expect, it } from 'vitest';

import type {
  NamespaceClientPort,
  NamespaceQuery,
} from '@src/api/inspection-client';
import type {
  NamespaceDetails,
  NamespaceExport,
  NamespacePage,
} from '@src/api/inspection-schemas';
import { NamespaceDetailsPage } from '@src/features/namespaces/NamespaceDetailsPage';
import { NamespacesPage } from '@src/features/namespaces/NamespacesPage';

const row = {
  namespace: 'orders/eu',
  encodedNamespace: 'b3JkZXJzL2V1',
  liveEntryCount: '10',
  liveCounterCount: '3',
  activeLockCount: '1',
  expiringEntryCount: '4',
  expiredEntryCount: '2',
  estimatedStorageBytes: '4096',
  observedAt: '2026-08-26T10:15:30Z',
} as const;

class FakeNamespaceClient implements NamespaceClientPort {
  queries: NamespaceQuery[] = [];
  selectedDetails: string[] = [];
  exports: NamespaceQuery[] = [];

  async namespaces(_setupId: string, query: NamespaceQuery = {}): Promise<NamespacePage> {
    this.queries.push(query);
    return {
      items: [{ ...row, namespace: query.cursor === 'cursor-2' ? 'orders/us' : row.namespace }],
      nextCursor: query.cursor === undefined ? 'cursor-2' : null,
      hasMore: query.cursor === undefined,
    };
  }

  async namespace(_setupId: string, encodedNamespace: string): Promise<NamespaceDetails> {
    this.selectedDetails.push(encodedNamespace);
    return {
      stats: row,
      valueTypeCounts: { STRING: '7', JSON: '3' },
      ttlDistribution: [
        { range: 'PERSISTENT', count: '6' },
        { range: 'LT_1_MINUTE', count: '4' },
      ],
    };
  }

  async exportNamespaces(_setupId: string, query: NamespaceQuery = {}): Promise<NamespaceExport> {
    this.exports.push(query);
    return { items: [row], truncated: false, exportedAt: '2026-08-26T10:16:00Z' };
  }
}

describe('U3 namespace inspection pages', () => {
  it('requires selected setup scope and never fabricates an empty database table', () => {
    render(<MemoryRouter><NamespacesPage client={new FakeNamespaceClient()} /></MemoryRouter>);
    expect(screen.getByRole('heading', { name: 'Select a connected setup' })).toBeVisible();
    expect(screen.queryByRole('table')).not.toBeInTheDocument();
  });

  it('applies prefix filters and preserves the opaque cursor stack for forward and back navigation', async () => {
    const user = userEvent.setup();
    const client = new FakeNamespaceClient();
    render(<MemoryRouter><NamespacesPage client={client} selectedSetupId="primary-cache" /></MemoryRouter>);
    await screen.findByText('orders/eu');

    await user.type(screen.getByLabelText('Namespace prefix'), 'orders');
    await user.click(screen.getByRole('button', { name: 'Apply filters' }));
    expect(client.queries.at(-1)).toEqual({ prefix: 'orders', status: 'ALL', sort: 'namespace:asc', limit: 50 });

    await user.click(screen.getByRole('button', { name: 'Next page' }));
    expect(await screen.findByText('orders/us')).toBeVisible();
    expect(client.queries.at(-1)).toMatchObject({ prefix: 'orders', cursor: 'cursor-2' });

    await user.click(screen.getByRole('button', { name: 'Previous page' }));
    expect(await screen.findByText('orders/eu')).toBeVisible();
    expect(client.queries.at(-1)?.cursor).toBeUndefined();
  });

  it('exports the validated current filter rather than a browser-reconstructed table', async () => {
    const user = userEvent.setup();
    const client = new FakeNamespaceClient();
    render(<MemoryRouter><NamespacesPage client={client} selectedSetupId="primary-cache" /></MemoryRouter>);
    await screen.findByText('orders/eu');
    await user.type(screen.getByLabelText('Namespace prefix'), 'ord');
    await user.click(screen.getByRole('button', { name: 'Apply filters' }));
    await user.click(screen.getByRole('button', { name: 'Export namespaces' }));

    expect(await screen.findByRole('status')).toHaveTextContent('Exported 1 namespace');
    expect(client.exports).toEqual([{ prefix: 'ord', status: 'ALL', sort: 'namespace:asc' }]);
  });

  it('renders namespace details and changes namespace scope without decoding route syntax locally', async () => {
    const client = new FakeNamespaceClient();
    const selected: string[] = [];
    render(
      <MemoryRouter>
        <NamespaceDetailsPage
          client={client}
          encodedNamespace={row.encodedNamespace}
          onSelectNamespace={(namespace) => selected.push(namespace)}
          selectedSetupId="primary-cache"
        />
      </MemoryRouter>,
    );

    expect(await screen.findByRole('heading', { name: 'orders/eu' })).toBeVisible();
    expect(screen.getByRole('tab', { name: 'Overview' })).toBeVisible();
    expect(screen.getByRole('tab', { name: 'Entries' })).toBeVisible();
    expect(within(screen.getByLabelText('Namespace totals')).getByText('10')).toBeVisible();
    expect(client.selectedDetails).toEqual([row.encodedNamespace]);
    expect(selected).toEqual([row.namespace]);
  });
});
