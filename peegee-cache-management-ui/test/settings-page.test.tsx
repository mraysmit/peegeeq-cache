import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';

import { currentSessionSchema } from '@src/api/protocol-schemas';
import { SessionClient } from '@src/api/session-client';
import { setupCapabilitiesSchema, setupSummaryListSchema } from '@src/api/setup-schemas';
import { SettingsPage } from '@src/features/settings/SettingsPage';
import { loadPreferences, PREFERENCES_CHANGED_EVENT } from '@src/state/preferences';
import { createManagementClients, createManagementStore, type ManagementStore } from '@src/store';
import { chooseOption, renderWithProviders } from './support/render';
import { route, startLoopbackServer, type LoopbackServer } from './support/loopback-server';

const session = currentSessionSchema.parse({
  user: 'operator', roles: ['viewer', 'operator'], serverVersion: '1.0', apiVersion: 'v1', authenticationMode: 'LOCAL_TOKEN',
  csrfToken: 'settings-page-csrf-token-with-forty-five-characters',
  sessionIdleExpiresAt: '2099-01-01T00:00:00Z', sessionExpiresAt: '2099-01-01T01:00:00Z', features: { setupRegistration: true, sensitiveReveal: true },
});

const capabilities = setupCapabilitiesSchema.parse({
  migrationVersion: '7',
  capabilities: {
    namespaceInspection: true, entryInspection: true, expiredEntryInspection: true, entryMutation: true, counterInspection: true, counterMutation: true,
    lockInspection: true, forcedLockRelease: true, bulkEntryDelete: true, bulkCounterDelete: true, pubSub: true, databaseStatistics: true,
    entryValueReveal: true, lockOwnerReveal: true, pubSubPayloadReveal: true, batchEntryOperations: true, valueScan: true, cacheMetrics: true, ownerLockOperations: true,
  },
  limits: { pubSubChannelMaxBytes: 48, pubSubPayloadMaxBytes: 7_500, maximumValueBytes: 10_485_760 },
});

describe('U8 Settings preferences', () => {
  let server: LoopbackServer;
  let store: ManagementStore;
  let sessionClient: SessionClient;

  beforeEach(async () => {
    localStorage.clear();
    server = await startLoopbackServer((request, respond) => {
      if (route('GET', '/api/v1/session', request)) return respond.json(200, session);
      if (route('GET', '/api/v1/setups', request)) return respond.json(200, setupSummaryListSchema.parse({ items: [] }));
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

  it('offers every approved harmless preference and persists their allowlisted values', async () => {
    const user = userEvent.setup();
    const changes: string[] = [];
    window.addEventListener(PREFERENCES_CHANGED_EVENT, () => changes.push('changed'));
    renderWithProviders(<SettingsPage capabilities={capabilities} selectedSetupId="primary" session={session} />, { store });

    expect(screen.getByRole('heading', { name: 'Connection' })).toBeVisible();
    expect(screen.getByText('LOCAL_TOKEN')).toBeVisible();
    expect(screen.getByText('primary')).toBeVisible();
    expect(screen.getByText('Automatic bounded exponential backoff')).toBeVisible();
    expect(screen.getByText('10485760')).toBeVisible();
    expect(screen.getByText('7')).toBeVisible();
    expect(screen.queryByRole('status')).not.toBeInTheDocument();

    await chooseOption(user, screen.getByLabelText('Byte display'), 'Decimal (kB, MB)');
    expect(await screen.findByRole('status')).toHaveTextContent('Display preferences saved in this browser.');
    await chooseOption(user, screen.getByLabelText('Masked-value auto-hide'), '120 seconds');
    await chooseOption(user, screen.getByLabelText('Refresh interval'), '60 seconds');
    await chooseOption(user, screen.getByLabelText('Timezone'), 'UTC');
    await chooseOption(user, screen.getByLabelText('Theme'), 'Dark');

    expect(JSON.parse(localStorage.getItem('peegeeq.management.preferences') ?? '{}')).toEqual({ theme: 'dark', timezone: 'UTC', byteUnits: 'DECIMAL', refreshSeconds: 60, autoHideSeconds: 120 });
    expect(loadPreferences()).toEqual({ theme: 'dark', timezone: 'UTC', byteUnits: 'DECIMAL', refreshSeconds: 60, autoHideSeconds: 120 });
    expect(changes).toHaveLength(5);
    expect(localStorage.getItem('peegeeq.management.preferences')).not.toContain('csrf');
    expect(screen.getByLabelText('Theme').closest('.ant-select')).toHaveTextContent('Dark');
    expect(server.requests.filter((request) => request.method !== 'GET')).toHaveLength(0);
  });

  it('renders without a selected setup and without capability limits', () => {
    renderWithProviders(<SettingsPage session={session} />, { store });
    expect(screen.getByText('None')).toBeVisible();
    expect(screen.queryByRole('heading', { name: 'Effective limits' })).not.toBeInTheDocument();
    expect(screen.getByText('Only display preferences are stored. Credentials, revealed values, owner tokens, payloads, setup secrets, and live notifications are never persisted.')).toBeVisible();
  });
});
