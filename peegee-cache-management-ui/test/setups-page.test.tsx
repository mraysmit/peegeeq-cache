import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { currentSessionSchema } from '@src/api/protocol-schemas';
import { SessionClient, type BrowserSession } from '@src/api/session-client';
import {
  setupCapabilitiesSchema,
  setupConnectionTestSchema,
  setupDetailsSchema,
  setupHealthSchema,
  setupSummaryListSchema,
  setupSummarySchema,
} from '@src/api/setup-schemas';
import { SetupsPage } from '@src/features/setups/SetupsPage';
import { createManagementClients, createManagementStore, type ManagementStore } from '@src/store';
import { renderWithProviders } from './support/render';
import { route, startLoopbackServer, type LoopbackRequest, type LoopbackServer } from './support/loopback-server';

const session = currentSessionSchema.parse({
  user: 'setup-operator', roles: ['viewer', 'operator'], serverVersion: '0.1.0-SNAPSHOT', apiVersion: 'v1',
  authenticationMode: 'LOCAL_TOKEN', csrfToken: 'setups-page-csrf-token-with-forty-five-characters',
  sessionIdleExpiresAt: '2099-01-01T00:00:00Z', sessionExpiresAt: '2099-01-01T01:00:00Z',
  features: { setupRegistration: true, sensitiveReveal: true },
});
// The shell receives the session without its CSRF token (BrowserSession); build it the same way.
const { csrfToken: _csrfToken, ...operator }: typeof session = session;
void _csrfToken;

const primary = setupSummarySchema.parse({
  setupId: 'primary-cache', displayName: 'Primary cache', host: 'db.example.test', port: 5432, database: 'peegeeq',
  schema: 'cache', sslMode: 'VERIFY_FULL', source: 'UI_SESSION', state: 'CONNECTED', schemaState: 'READY',
  lastHealth: { status: 'UP', latencyMillis: 7, checkedAt: '2099-01-01T00:00:00Z' },
});

const capabilityFlags = {
  namespaceInspection: true, entryInspection: true, expiredEntryInspection: true, entryMutation: true,
  counterInspection: true, counterMutation: true, lockInspection: true, forcedLockRelease: true,
  bulkEntryDelete: true, bulkCounterDelete: true, pubSub: true, databaseStatistics: true,
  entryValueReveal: true, lockOwnerReveal: true, pubSubPayloadReveal: true, batchEntryOperations: true,
  valueScan: true, cacheMetrics: true, ownerLockOperations: true,
};
const limits = { pubSubChannelMaxBytes: 63, pubSubPayloadMaxBytes: 8_000, maximumValueBytes: 1_000_000 };
const connectionTest = setupConnectionTestSchema.parse({
  databaseReachable: true, schemaState: 'READY', migrationVersion: '1', latencyMillis: 8, capabilities: capabilityFlags, limits,
});
const capabilities = setupCapabilitiesSchema.parse({ migrationVersion: '1', capabilities: capabilityFlags, limits });
const health = setupHealthSchema.parse({
  status: 'UP', schemaReady: true, latencyMillis: 7, checkedAt: '2099-01-01T00:00:00Z', detail: 'PostgreSQL and cache schema are ready',
});
const detailsOf = (setup: typeof primary) => setupDetailsSchema.parse({
  setup, migrationVersion: '1',
  runtime: {
    defaultTtlMillis: null, expirySweeperEnabled: true, expirySweepIntervalMillis: 30_000, expirySweepBatchSize: 500,
    writeBehindEnabled: false, writeBehindFlushIntervalMillis: 500, writeBehindMaxBufferSize: 10_000, writeBehindFlushBatchSize: 500,
    writeBehindMaxRetries: 3, writeBehindShutdownDrainTimeoutMillis: 5_000, pubSubChannelPrefix: 'peegee_cache', pubSubEnabled: true,
    schemaBootstrapMode: 'EXTERNAL', telemetryMode: 'NOOP', poolMaxSize: 3,
  },
  registeredAt: '2099-01-01T00:00:00Z', connectedAt: '2099-01-01T00:00:05Z',
});

describe('functional setup management page', () => {
  let server: LoopbackServer;
  let store: ManagementStore;
  let sessionClient: SessionClient;
  let registry: Array<typeof primary>;
  let listFailure: { status: number; code: string; detail: string; correlationId: string } | undefined;
  let registrationBody: unknown;
  let connectionRequestBody: unknown;

  beforeEach(async () => {
    registry = [primary];
    listFailure = undefined;
    registrationBody = undefined;
    connectionRequestBody = undefined;
    server = await startLoopbackServer((request, respond) => {
      if (route('GET', '/api/v1/session', request)) return respond.json(200, session);
      if (route('GET', '/api/v1/setups', request)) {
        if (listFailure !== undefined) return respond.problem(listFailure.status, listFailure.code, listFailure.detail, { correlationId: listFailure.correlationId });
        return respond.json(200, setupSummaryListSchema.parse({ items: registry }));
      }
      if (route('POST', '/api/v1/setups/actions/test', request)) {
        connectionRequestBody = request.body;
        return respond.json(200, connectionTest);
      }
      if (route('POST', '/api/v1/setups', request)) {
        registrationBody = request.body;
        const body = request.body as Record<string, unknown>;
        const created = setupSummarySchema.parse({
          ...primary, setupId: body.setupId, displayName: body.displayName, host: body.host, database: body.database, schema: body.schema,
        });
        registry = [...registry, created];
        return respond.json(201, created);
      }
      const setupMatch = /^\/api\/v1\/setups\/([^/]+)(?:\/(.+))?$/u.exec(request.path);
      if (setupMatch !== null) {
        const setupId = decodeURIComponent(setupMatch[1]!);
        const setup = registry.find((candidate) => candidate.setupId === setupId);
        if (setup === undefined) return respond.problem(404, 'SETUP_NOT_FOUND', `${setupId} is not registered`);
        const rest = setupMatch[2];
        if (request.method === 'GET' && rest === undefined) return respond.json(200, detailsOf(setup));
        if (request.method === 'GET' && rest === 'health') return respond.json(200, health);
        if (request.method === 'GET' && rest === 'capabilities') return respond.json(200, capabilities);
        if (request.method === 'POST' && rest === 'test') return respond.json(200, connectionTest);
        if (request.method === 'POST' && rest === 'detach') {
          registry = registry.map((candidate) => (candidate.setupId === setupId ? setupSummarySchema.parse({ ...candidate, state: 'DETACHED' }) : candidate));
          return respond.noContent();
        }
        if (request.method === 'POST' && rest === 'connect') {
          const connected = setupSummarySchema.parse({ ...setup, state: 'CONNECTED' });
          registry = registry.map((candidate) => (candidate.setupId === setupId ? connected : candidate));
          return respond.json(200, connected);
        }
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

  const renderPage = (props: { readonly session?: BrowserSession; readonly selectedSetupId?: string; readonly onSelectSetup?: (id: string | undefined) => void } = {}) => renderWithProviders(
    <SetupsPage
      onSelectSetup={props.onSelectSetup ?? (() => undefined)}
      selectedSetupId={props.selectedSetupId}
      session={props.session ?? operator}
    />,
    { store },
  );

  const requestsTo = (predicate: (request: LoopbackRequest) => boolean) => server.requests.filter(predicate);

  it('renders live setup metadata, opens details, and changes active scope', async () => {
    const user = userEvent.setup();
    const selected: Array<string | undefined> = [];
    renderPage({ onSelectSetup: (id) => selected.push(id) });

    const row = (await screen.findByText('Primary cache')).closest('tr');
    expect(screen.getByRole('region', { name: 'Registered setups' })).toHaveAttribute('tabindex', '0');
    expect(row).not.toBeNull();
    expect(within(row as HTMLElement).getByText('db.example.test:5432/cache')).toBeVisible();
    expect(within(row as HTMLElement).getByText('Up')).toBeVisible();

    await user.click(within(row as HTMLElement).getByRole('button', { name: 'Use setup' }));
    await vi.waitFor(() => expect(selected).toEqual(['primary-cache']));
    expect(requestsTo((request) => request.path.endsWith('/capabilities'))).toHaveLength(1);

    await user.click(within(row as HTMLElement).getByRole('button', { name: 'Details' }));
    const dialog = await screen.findByRole('dialog', { name: 'Setup details' });
    expect(await within(dialog).findByText('Migration')).toBeVisible();
    expect(within(dialog).getByText('db.example.test:5432')).toBeVisible();
  });

  it('presents authoritative health, capabilities, and limits in setup details', async () => {
    const user = userEvent.setup();
    renderPage();
    await user.click(await screen.findByRole('button', { name: 'Details' }));

    const dialog = await screen.findByRole('dialog', { name: 'Setup details' });
    expect(await within(dialog).findByRole('heading', { name: 'Database health' })).toBeVisible();
    expect(within(dialog).getByText('PostgreSQL and cache schema are ready')).toBeVisible();
    expect(within(dialog).getByRole('heading', { name: 'Capabilities' })).toBeVisible();
    expect(within(dialog).getByText('Namespace inspection')).toBeVisible();
    expect(within(dialog).getByText('976 KiB')).toBeVisible();
  });

  it('enforces viewer-only controls in the rendered interface', async () => {
    renderPage({ session: { ...operator, roles: ['viewer'] } });

    await screen.findByText('Primary cache');
    expect(screen.getByText(/Viewer access is read-only/u)).toBeVisible();
    expect(screen.queryByRole('button', { name: 'Register setup' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Detach' })).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Details' })).toBeVisible();
  });

  it('tests and registers a complete TLS setup without rendering the password', async () => {
    const user = userEvent.setup();
    registry = [];
    const selected: Array<string | undefined> = [];
    const { container } = renderPage({ onSelectSetup: (id) => selected.push(id) });
    await screen.findByRole('heading', { name: 'No setups registered' });
    await user.click(screen.getByRole('button', { name: /^Register setup$/u }));

    const dialog = await screen.findByRole('dialog', { name: 'Register setup' });
    expect(within(dialog).getByLabelText('Setup ID')).toHaveAttribute('pattern', '[a-z][a-z0-9\\-]{0,62}');
    await user.type(within(dialog).getByLabelText('Setup ID'), 'analytics-cache');
    await user.type(within(dialog).getByLabelText('Display name'), 'Analytics cache');
    await user.type(within(dialog).getByLabelText('Host'), 'analytics.example.test');
    await user.type(within(dialog).getByLabelText('Database'), 'analytics');
    await user.clear(within(dialog).getByLabelText('Schema'));
    await user.type(within(dialog).getByLabelText('Schema'), 'cache');
    await user.type(within(dialog).getByLabelText('Username'), 'cache-user');
    await user.type(within(dialog).getByLabelText('Password'), 'ephemeral-password');
    await user.type(within(dialog).getByLabelText('Trust profile'), 'production-ca');

    await user.click(within(dialog).getByRole('button', { name: 'Test connection' }));
    expect(await within(dialog).findByText(/Connection succeeded in 8 ms/u)).toBeVisible();
    expect(connectionRequestBody).toMatchObject({
      host: 'analytics.example.test',
      password: 'ephemeral-password',
      runtime: {
        defaultTtlMillis: null, expirySweeperEnabled: false, writeBehindEnabled: false,
        pubSubChannelPrefix: 'peegee_cache', pubSubEnabled: true, schemaBootstrapMode: 'EXTERNAL', telemetryMode: 'NOOP',
      },
    });

    await user.click(within(dialog).getByRole('button', { name: 'Register setup' }));
    expect(await screen.findByText('Analytics cache')).toBeVisible();
    expect(registrationBody).toMatchObject({
      setupId: 'analytics-cache', sslMode: 'VERIFY_FULL',
      runtime: { writeBehindMaxBufferSize: 10_000, writeBehindShutdownDrainTimeoutMillis: 5_000 },
    });
    expect(selected).toContain('analytics-cache');
    expect(container).not.toHaveTextContent('ephemeral-password');
    expect(JSON.stringify(store.getState())).not.toContain('ephemeral-password');
  }, 30_000);

  it('confirms detach, clears selected scope, and renders the resulting connect action', async () => {
    const user = userEvent.setup();
    const selected: Array<string | undefined> = [];
    renderPage({ selectedSetupId: 'primary-cache', onSelectSetup: (id) => selected.push(id) });
    const row = (await screen.findByText('Primary cache')).closest('tr') as HTMLElement;
    await user.click(within(row).getByRole('button', { name: 'Detach' }));
    const dialog = await screen.findByRole('dialog', { name: 'Detach Primary cache?' });
    await user.click(within(dialog).getByRole('button', { name: 'Detach' }));

    expect(await screen.findByRole('button', { name: 'Connect' })).toBeVisible();
    expect(screen.getByRole('status')).toHaveTextContent('Primary cache was detached.');
    expect(selected).toContain(undefined);
    expect(requestsTo((request) => request.method === 'POST' && request.path.endsWith('/detach'))[0]?.headers['x-peegeeq-csrf']).toBe(session.csrfToken);
  });

  it('surfaces server diagnostics and correlation IDs instead of an empty table', async () => {
    listFailure = { status: 503, code: 'SETUP_REGISTRY_UNAVAILABLE', detail: 'Setup discovery is temporarily unavailable', correlationId: 'setup-correlation-42' };
    renderPage();

    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('SETUP_REGISTRY_UNAVAILABLE');
    expect(alert).toHaveTextContent('setup-correlation-42');
    expect(screen.queryByRole('heading', { name: 'No setups registered' })).not.toBeInTheDocument();
  });
});
