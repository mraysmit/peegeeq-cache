import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';

import { currentSessionSchema } from '@src/api/protocol-schemas';
import { SessionClient } from '@src/api/session-client';
import { setupCapabilitiesSchema, setupSummaryListSchema } from '@src/api/setup-schemas';
import { App } from '@src/app/App';
import { ManagementShell } from '@src/app/ManagementShell';
import { useSetupScopeStore } from '@src/state/scope-store';
import { createManagementClients, createManagementStore, type ManagementStore } from '@src/store';
import { renderWithProviders } from './support/render';
import { route, startLoopbackServer, type LoopbackServer } from './support/loopback-server';

const CSRF = 'shell-csrf-token-with-at-least-thirty-two-characters';
const session = currentSessionSchema.parse({
  user: 'alex', roles: ['viewer', 'operator'], serverVersion: '0.1.0-SNAPSHOT', apiVersion: 'v1', authenticationMode: 'LOCAL_TOKEN', csrfToken: CSRF,
  sessionIdleExpiresAt: '2099-01-01T00:00:00Z', sessionExpiresAt: '2099-01-01T01:00:00Z', features: { setupRegistration: true, sensitiveReveal: true },
});

const capabilities = setupCapabilitiesSchema.parse({
  migrationVersion: '1',
  capabilities: {
    namespaceInspection: true, entryInspection: true, expiredEntryInspection: true, entryMutation: true, counterInspection: false, counterMutation: false,
    lockInspection: false, forcedLockRelease: false, bulkEntryDelete: true, bulkCounterDelete: false, pubSub: false, databaseStatistics: true,
    entryValueReveal: false, lockOwnerReveal: false, pubSubPayloadReveal: false, batchEntryOperations: true, valueScan: true, cacheMetrics: true, ownerLockOperations: true,
  },
  limits: { maximumValueBytes: 1024, pubSubChannelMaxBytes: 49, pubSubPayloadMaxBytes: 7500 },
});

describe('U1 authenticated management shell', () => {
  let server: LoopbackServer;
  let store: ManagementStore;
  let sessionClient: SessionClient;
  let authenticated = true;

  beforeEach(async () => {
    localStorage.clear();
    sessionStorage.clear();
    authenticated = true;
    useSetupScopeStore.getState().clear();
    server = await startLoopbackServer((request, respond) => {
      if (route('GET', '/api/v1/session', request)) {
        return authenticated ? respond.json(200, session) : respond.problem(401, 'SESSION_REQUIRED', 'Authenticate with the bootstrap token');
      }
      if (route('POST', '/api/v1/session/local', request)) {
        const { token } = request.body as { token: string };
        if (token !== 'bootstrap-secret') return respond.problem(401, 'BOOTSTRAP_TOKEN_INVALID', 'The bootstrap token was rejected');
        authenticated = true;
        return respond.json(200, session);
      }
      if (route('DELETE', '/api/v1/session/local', request)) {
        if (request.headers['x-peegeeq-csrf'] !== CSRF) return respond.problem(403, 'CSRF_REQUIRED', 'CSRF proof missing');
        authenticated = false;
        return respond.noContent();
      }
      if (route('GET', '/api/v1/setups', request)) return respond.json(200, setupSummaryListSchema.parse({ items: [] }));
      if (route('GET', '/api/v1/setups/primary-cache/capabilities', request)) return respond.json(200, capabilities);
      return respond.problem(404, 'NOT_FOUND', `no fixture for ${request.method} ${request.path}`);
    });
    sessionClient = new SessionClient(server.baseUrl);
    await sessionClient.load();
    store = createManagementStore(createManagementClients(sessionClient));
  });

  afterEach(async () => {
    useSetupScopeStore.getState().clear();
    sessionClient.clear();
    await server.close();
  });

  const shell = (initialEntries: string[]) => renderWithProviders(<ManagementShell session={session} onLogout={() => Promise.resolve()} />, { store, initialEntries });

  it('renders route navigation, identity, connection state, and role-aware controls', async () => {
    shell(['/']);

    expect(screen.getByRole('navigation', { name: 'Management sections' })).toBeVisible();
    expect(await screen.findByRole('heading', { name: 'Overview' })).toBeVisible();
    expect(screen.getByText('alex')).toBeVisible();
    expect(screen.getByText('Operator')).toBeVisible();
    expect(screen.getByText('Connected')).toBeVisible();
    expect(screen.getByRole('link', { name: 'Setups' })).toHaveAttribute('href', '/setups');
    expect(screen.getByRole('button', { name: 'End local session' })).toBeVisible();
    expect(document.body).not.toHaveTextContent(CSRF);
  });

  it('provides theme and notification controls without exposing session secrets', async () => {
    const user = userEvent.setup();
    const { container } = shell(['/monitoring']);

    await user.click(screen.getByRole('button', { name: 'Use dark theme' }));
    await user.click(screen.getByRole('button', { name: 'Open notifications' }));

    expect(container.querySelector('.console')).toHaveAttribute('data-theme', 'dark');
    expect(await screen.findByRole('complementary', { name: 'Notifications' })).toBeVisible();
    expect(container).not.toHaveTextContent(CSRF);
    expect(JSON.parse(localStorage.getItem('peegeeq.management.preferences') ?? '{}')).toMatchObject({ theme: 'dark' });
    expect(localStorage.getItem('peegeeq.management.preferences')).not.toContain(CSRF);
  });

  it('blocks direct routes for capabilities the active setup does not provide', async () => {
    useSetupScopeStore.getState().select('primary-cache', capabilities);
    shell(['/counters']);

    expect(await screen.findByRole('heading', { name: 'Counters unavailable' })).toBeVisible();
    expect(screen.getByText(/does not provide counter inspection/u)).toBeVisible();
    expect(screen.queryByRole('link', { name: 'Counters' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Create counter' })).not.toBeInTheDocument();
    expect(server.requests.filter((request) => request.path.includes('/counters'))).toHaveLength(0);
  });

  it('restores a remembered setup scope by re-reading its capabilities from the server', async () => {
    useSetupScopeStore.getState().select('primary-cache', capabilities);
    useSetupScopeStore.setState({ capabilities: undefined });
    shell(['/counters']);

    expect(await screen.findByRole('heading', { name: 'Counters unavailable' })).toBeVisible();
    expect(server.requests.filter((request) => request.path === '/api/v1/setups/primary-cache/capabilities')).toHaveLength(1);
    expect(useSetupScopeStore.getState().capabilities).toEqual(capabilities);
  });

  it('gates the console behind the local bootstrap token and ends the session with CSRF proof', async () => {
    const user = userEvent.setup();
    sessionClient.clear();
    authenticated = false;
    // The production router is mounted under the /ui base path.
    window.history.pushState({}, '', '/ui/');
    render(<App apiBaseUrl={server.baseUrl} />);

    expect(await screen.findByRole('heading', { name: 'Connect to management console' })).toBeVisible();
    await user.type(screen.getByLabelText('Bootstrap token'), 'wrong-token');
    await user.click(screen.getByRole('button', { name: 'Connect' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('BOOTSTRAP_TOKEN_INVALID');
    expect(screen.getByLabelText('Bootstrap token')).toHaveValue('');

    await user.type(screen.getByLabelText('Bootstrap token'), 'bootstrap-secret');
    await user.click(screen.getByRole('button', { name: 'Connect' }));
    expect(await screen.findByRole('navigation', { name: 'Management sections' })).toBeVisible();
    expect(screen.getByText('alex')).toBeVisible();
    expect(document.body).not.toHaveTextContent('bootstrap-secret');
    expect(document.body).not.toHaveTextContent(CSRF);
    const exchanges = server.requests.filter((request) => request.method === 'POST' && request.path === '/api/v1/session/local');
    expect(exchanges.map((request) => request.body)).toEqual([{ token: 'wrong-token' }, { token: 'bootstrap-secret' }]);

    await user.click(screen.getByRole('button', { name: 'End local session' }));
    expect(await screen.findByRole('heading', { name: 'Connect to management console' })).toBeVisible();
    const logout = server.requests.find((request) => request.method === 'DELETE' && request.path === '/api/v1/session/local');
    expect(logout?.headers['x-peegeeq-csrf']).toBe(CSRF);
    await waitFor(() => expect(JSON.stringify(localStorage)).not.toContain(CSRF));
  });
});
