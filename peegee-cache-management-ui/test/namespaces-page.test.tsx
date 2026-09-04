import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';

import { namespaceDetailsSchema, namespaceExportSchema, namespacePageSchema } from '@src/api/inspection-schemas';
import { currentSessionSchema } from '@src/api/protocol-schemas';
import { SessionClient } from '@src/api/session-client';
import { setupSummaryListSchema } from '@src/api/setup-schemas';
import { NamespaceDetailsPage } from '@src/features/namespaces/NamespaceDetailsPage';
import { NamespacesPage } from '@src/features/namespaces/NamespacesPage';
import { createManagementClients, createManagementStore, type ManagementStore } from '@src/store';
import { renderWithProviders } from './support/render';
import { route, startLoopbackServer, type LoopbackRequest, type LoopbackServer } from './support/loopback-server';

const session = currentSessionSchema.parse({
  user: 'namespace-operator', roles: ['viewer', 'operator'], serverVersion: '0.1.0-SNAPSHOT', apiVersion: 'v1',
  authenticationMode: 'LOCAL_TOKEN', csrfToken: 'namespaces-page-csrf-token-with-forty-nine-chars',
  sessionIdleExpiresAt: '2099-01-01T00:00:00Z', sessionExpiresAt: '2099-01-01T01:00:00Z',
  features: { setupRegistration: true, sensitiveReveal: true },
});

const row = {
  namespace: 'orders/eu', encodedNamespace: 'b3JkZXJzL2V1', liveEntryCount: '10', liveCounterCount: '3', activeLockCount: '1',
  expiringEntryCount: '4', expiredEntryCount: '2', estimatedStorageBytes: '4096', observedAt: '2026-08-26T10:15:30Z',
} as const;

describe('U3 namespace inspection pages', () => {
  let server: LoopbackServer;
  let store: ManagementStore;
  let sessionClient: SessionClient;

  beforeEach(async () => {
    server = await startLoopbackServer((request, respond) => {
      if (route('GET', '/api/v1/session', request)) return respond.json(200, session);
      if (route('GET', '/api/v1/setups', request)) return respond.json(200, setupSummaryListSchema.parse({ items: [] }));
      if (route('GET', '/api/v1/setups/primary-cache/namespaces', request)) {
        const cursor = request.query.get('cursor');
        return respond.json(200, namespacePageSchema.parse({
          items: [{ ...row, namespace: cursor === 'cursor-2' ? 'orders/us' : row.namespace }],
          nextCursor: cursor === null ? 'cursor-2' : null,
          hasMore: cursor === null,
        }));
      }
      if (route('GET', '/api/v1/setups/primary-cache/namespaces/export', request)) {
        return respond.json(200, namespaceExportSchema.parse({ items: [row], truncated: false, exportedAt: '2026-08-26T10:16:00Z' }));
      }
      if (route('GET', `/api/v1/setups/primary-cache/namespaces/${row.encodedNamespace}`, request)) {
        return respond.json(200, namespaceDetailsSchema.parse({
          stats: row,
          valueTypeCounts: { STRING: '7', JSON: '3' },
          ttlStateCounts: { PERSISTENT: '6', EXPIRING: '4', EXPIRED: '0' },
          ttlDistribution: [{ range: 'PERSISTENT', count: '6' }, { range: 'LT_1_MINUTE', count: '4' }],
        }));
      }
      return respond.problem(404, 'NOT_FOUND', `no fixture for ${request.method} ${request.path}`);
    });
    sessionClient = new SessionClient(server.baseUrl);
    await sessionClient.load();
    store = createManagementStore(createManagementClients(sessionClient));
  });

  afterEach(async () => {
    sessionClient.clear();
    await server.close();
  });

  const listRequests = () => server.requests.filter((request: LoopbackRequest) => request.path === '/api/v1/setups/primary-cache/namespaces');
  const renderList = (selectedSetupId?: string) => renderWithProviders(<NamespacesPage selectedSetupId={selectedSetupId} />, { store });

  it('requires selected setup scope and never fabricates an empty database table', async () => {
    renderList();
    expect(await screen.findByRole('heading', { name: 'Select a connected setup' })).toBeVisible();
    expect(screen.queryByRole('table')).not.toBeInTheDocument();
    expect(listRequests()).toHaveLength(0);
  });

  it('applies prefix filters and preserves the opaque cursor stack for forward and back navigation', async () => {
    const user = userEvent.setup();
    renderList('primary-cache');
    await screen.findByText('orders/eu');
    expect(screen.getByRole('region', { name: 'Namespace results' })).toHaveAttribute('tabindex', '0');

    await user.type(screen.getByLabelText('Namespace prefix'), 'orders');
    await user.click(screen.getByRole('button', { name: 'Apply filters' }));
    await screen.findByText('orders/eu');
    expect(Object.fromEntries(listRequests().at(-1)!.query)).toEqual({ prefix: 'orders', status: 'ALL', sort: 'namespace:asc', limit: '50' });

    await user.click(screen.getByRole('button', { name: 'Next page' }));
    expect(await screen.findByText('orders/us')).toBeVisible();
    expect(Object.fromEntries(listRequests().at(-1)!.query)).toMatchObject({ prefix: 'orders', cursor: 'cursor-2' });

    await user.click(screen.getByRole('button', { name: 'Previous page' }));
    expect(await screen.findByText('orders/eu')).toBeVisible();
    expect(screen.getByRole('navigation', { name: 'Namespace pages' })).toHaveTextContent('Page 1');
  });

  it('exports the validated current filter rather than a browser-reconstructed table', async () => {
    const user = userEvent.setup();
    renderList('primary-cache');
    await screen.findByText('orders/eu');
    await user.type(screen.getByLabelText('Namespace prefix'), 'ord');
    await user.click(screen.getByRole('button', { name: 'Apply filters' }));
    await user.click(screen.getByRole('button', { name: 'Export namespaces' }));

    expect(await screen.findByRole('status')).toHaveTextContent('Exported 1 namespace');
    const exportRequest = server.requests.find((request) => request.path.endsWith('/namespaces/export'));
    expect(Object.fromEntries(exportRequest!.query)).toEqual({ prefix: 'ord', status: 'ALL', sort: 'namespace:asc' });
  });

  it('renders namespace details and changes namespace scope without decoding route syntax locally', async () => {
    const selected: string[] = [];
    renderWithProviders(
      <NamespaceDetailsPage
        encodedNamespace={row.encodedNamespace}
        onSelectNamespace={(namespace) => selected.push(namespace)}
        selectedSetupId="primary-cache"
      />,
      { store },
    );

    expect(await screen.findByRole('heading', { name: 'orders/eu' })).toBeVisible();
    expect(screen.getByRole('tab', { name: 'Overview' })).toBeVisible();
    expect(screen.getByRole('tab', { name: 'Entries' })).toBeVisible();
    expect(within(screen.getByLabelText('Namespace totals')).getByText('10')).toBeVisible();
    expect(screen.getByRole('heading', { name: 'TTL states' }).closest('section')).toHaveTextContent('Expiring4');
    expect(server.requests.filter((request) => request.path.endsWith(`/namespaces/${row.encodedNamespace}`))).toHaveLength(1);
    expect(selected).toEqual([row.namespace]);
  });
});
