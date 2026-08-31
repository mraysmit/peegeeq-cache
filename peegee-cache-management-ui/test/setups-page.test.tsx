import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';

import { ManagementClientError, type BrowserSession } from '@src/api/session-client';
import type {
  SetupClientPort,
  SetupConnectionRequest,
  SetupRegistrationRequest,
} from '@src/api/setup-client';
import type {
  SetupCapabilities,
  SetupConnectionTest,
  SetupDetails,
  SetupHealth,
  SetupSummary,
} from '@src/api/setup-schemas';
import { SetupsPage } from '@src/features/setups/SetupsPage';

const operator: BrowserSession = {
  user: 'setup-operator',
  roles: ['viewer', 'operator'],
  serverVersion: '0.1.0-SNAPSHOT',
  apiVersion: 'v1',
  authenticationMode: 'LOCAL_TOKEN',
  sessionIdleExpiresAt: '2099-01-01T00:00:00Z',
  sessionExpiresAt: '2099-01-01T01:00:00Z',
  features: { setupRegistration: true, sensitiveReveal: true },
};

const primary: SetupSummary = {
  setupId: 'primary-cache',
  displayName: 'Primary cache',
  host: 'db.example.test',
  port: 5432,
  database: 'peegeeq',
  schema: 'cache',
  sslMode: 'VERIFY_FULL',
  source: 'UI_SESSION',
  state: 'CONNECTED',
  schemaState: 'READY',
  lastHealth: {
    status: 'UP',
    latencyMillis: 7,
    checkedAt: '2099-01-01T00:00:00Z',
  },
};

const successfulTest: SetupConnectionTest = {
  databaseReachable: true,
  schemaState: 'READY',
  migrationVersion: '1',
  latencyMillis: 8,
  capabilities: {
    namespaceInspection: true,
    expiredEntryInspection: true,
    counterInspection: true,
    lockInspection: true,
    forcedLockRelease: true,
    bulkEntryDelete: true,
    bulkCounterDelete: true,
    pubSub: true,
    databaseStatistics: true,
    entryValueReveal: true,
    lockOwnerReveal: true,
    pubSubPayloadReveal: true,
  },
  limits: { pubSubChannelMaxBytes: 63, pubSubPayloadMaxBytes: 8_000, maximumValueBytes: 1_000_000 },
};

class FakeSetupClient implements SetupClientPort {
  setups: SetupSummary[];
  registration?: SetupRegistrationRequest;
  connectionRequest?: SetupConnectionRequest;
  listFailure?: ManagementClientError;

  constructor(setups: SetupSummary[] = [primary]) {
    this.setups = [...setups];
  }

  async list(): Promise<SetupSummary[]> {
    if (this.listFailure !== undefined) throw this.listFailure;
    return [...this.setups];
  }

  async details(setupId: string): Promise<SetupDetails> {
    const setup = this.required(setupId);
    return {
      setup,
      migrationVersion: '1',
      runtime: {
        defaultTtlMillis: null,
        expirySweeperEnabled: true,
        expirySweepIntervalMillis: 30_000,
        expirySweepBatchSize: 500,
        poolMaxSize: 10,
      },
      registeredAt: '2099-01-01T00:00:00Z',
      connectedAt: '2099-01-01T00:01:00Z',
    };
  }

  async health(): Promise<SetupHealth> {
    return {
      status: 'UP',
      schemaReady: true,
      latencyMillis: 6,
      checkedAt: '2099-01-01T00:00:00Z',
      detail: 'PostgreSQL and cache schema are ready',
    };
  }

  async capabilities(): Promise<SetupCapabilities> {
    return {
      migrationVersion: successfulTest.migrationVersion,
      capabilities: successfulTest.capabilities,
      limits: successfulTest.limits,
    };
  }

  async testConnection(request: SetupConnectionRequest): Promise<SetupConnectionTest> {
    this.connectionRequest = request;
    return successfulTest;
  }

  async register(request: SetupRegistrationRequest): Promise<SetupSummary> {
    this.registration = request;
    const created = { ...primary, ...request, source: 'UI_SESSION', state: 'CONNECTED' } as SetupSummary;
    this.setups = [created];
    return created;
  }

  async testRegistered(): Promise<SetupConnectionTest> {
    return successfulTest;
  }

  async connect(setupId: string): Promise<SetupSummary> {
    return this.replace(setupId, { state: 'CONNECTED' });
  }

  async detach(setupId: string): Promise<void> {
    this.replace(setupId, { state: 'DETACHED' });
  }

  async forget(setupId: string): Promise<void> {
    this.setups = this.setups.filter((setup) => setup.setupId !== setupId);
  }

  private required(setupId: string): SetupSummary {
    const found = this.setups.find((setup) => setup.setupId === setupId);
    if (found === undefined) throw new Error(`Unknown setup ${setupId}`);
    return found;
  }

  private replace(setupId: string, change: Partial<SetupSummary>): SetupSummary {
    const changed = { ...this.required(setupId), ...change };
    this.setups = this.setups.map((setup) => setup.setupId === setupId ? changed : setup);
    return changed;
  }
}

describe('functional setup management page', () => {
  it('renders live setup metadata, opens details, and changes active scope', async () => {
    const user = userEvent.setup();
    const selected: Array<string | undefined> = [];
    render(<SetupsPage client={new FakeSetupClient()} session={operator} onSelectSetup={(id) => selected.push(id)} />);

    const row = (await screen.findByText('Primary cache')).closest('tr');
    expect(screen.getByRole('region', { name: 'Registered setups' })).toHaveAttribute('tabindex', '0');
    expect(row).not.toBeNull();
    expect(within(row as HTMLElement).getByText('db.example.test:5432/cache')).toBeVisible();
    expect(within(row as HTMLElement).getByText('Up')).toBeVisible();

    await user.click(within(row as HTMLElement).getByRole('button', { name: 'Use setup' }));
    expect(selected).toEqual(['primary-cache']);

    await user.click(within(row as HTMLElement).getByRole('button', { name: 'Details' }));
    const dialog = await screen.findByRole('dialog', { name: 'Setup details' });
    expect(within(dialog).getByText('Migration')).toBeVisible();
    expect(within(dialog).getByText('db.example.test:5432')).toBeVisible();
  });

  it('presents authoritative health, capabilities, and limits in setup details', async () => {
    const user = userEvent.setup();
    render(<SetupsPage client={new FakeSetupClient()} session={operator} onSelectSetup={() => undefined} />);
    await user.click(await screen.findByRole('button', { name: 'Details' }));

    const dialog = await screen.findByRole('dialog', { name: 'Setup details' });
    expect(within(dialog).getByRole('heading', { name: 'Database health' })).toBeVisible();
    expect(within(dialog).getByText('PostgreSQL and cache schema are ready')).toBeVisible();
    expect(within(dialog).getByRole('heading', { name: 'Capabilities' })).toBeVisible();
    expect(within(dialog).getByText('Namespace inspection')).toBeVisible();
    expect(within(dialog).getByText('976 KiB')).toBeVisible();
  });

  it('enforces viewer-only controls in the rendered interface', async () => {
    render(
      <SetupsPage
        client={new FakeSetupClient()}
        session={{ ...operator, roles: ['viewer'] }}
        onSelectSetup={() => undefined}
      />,
    );

    await screen.findByText('Primary cache');
    expect(screen.getByText(/Viewer access is read-only/u)).toBeVisible();
    expect(screen.queryByRole('button', { name: 'Register setup' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Detach' })).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Details' })).toBeVisible();
  });

  it('tests and registers a complete TLS setup without rendering the password', async () => {
    const user = userEvent.setup();
    const client = new FakeSetupClient([]);
    const selected: Array<string | undefined> = [];
    const { container } = render(
      <SetupsPage client={client} session={operator} onSelectSetup={(id) => selected.push(id)} />,
    );
    await screen.findByRole('heading', { name: 'No setups registered' });
    await user.click(screen.getByRole('button', { name: /^Register setup$/u }));

    const dialog = screen.getByRole('dialog', { name: 'Register setup' });
    expect(within(dialog).getByLabelText('Setup ID')).toHaveAttribute(
      'pattern',
      '[a-z][a-z0-9\\-]{0,62}',
    );
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
    expect(client.connectionRequest).toMatchObject({ host: 'analytics.example.test', password: 'ephemeral-password' });

    await user.click(within(dialog).getByRole('button', { name: 'Register setup' }));
    expect(await screen.findByText('Analytics cache')).toBeVisible();
    expect(client.registration).toMatchObject({ setupId: 'analytics-cache', sslMode: 'VERIFY_FULL' });
    expect(selected).toContain('analytics-cache');
    expect(container).not.toHaveTextContent('ephemeral-password');
  });

  it('confirms detach, clears selected scope, and renders the resulting connect action', async () => {
    const user = userEvent.setup();
    const client = new FakeSetupClient();
    const selected: Array<string | undefined> = [];
    render(
      <SetupsPage
        client={client}
        selectedSetupId="primary-cache"
        session={operator}
        onSelectSetup={(id) => selected.push(id)}
      />,
    );
    const row = (await screen.findByText('Primary cache')).closest('tr') as HTMLElement;
    await user.click(within(row).getByRole('button', { name: 'Detach' }));
    const dialog = screen.getByRole('dialog', { name: 'Detach Primary cache?' });
    await user.click(within(dialog).getByRole('button', { name: 'Detach' }));

    expect(await screen.findByRole('button', { name: 'Connect' })).toBeVisible();
    expect(screen.getByRole('status')).toHaveTextContent('Primary cache was detached.');
    expect(selected).toContain(undefined);
  });

  it('surfaces server diagnostics and correlation IDs instead of an empty table', async () => {
    const client = new FakeSetupClient();
    client.listFailure = new ManagementClientError(
      503,
      'SETUP_REGISTRY_UNAVAILABLE',
      'Setup discovery is temporarily unavailable',
      'setup-correlation-42',
    );
    render(<SetupsPage client={client} session={operator} onSelectSetup={() => undefined} />);

    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('SETUP_REGISTRY_UNAVAILABLE');
    expect(alert).toHaveTextContent('setup-correlation-42');
    expect(screen.queryByRole('heading', { name: 'No setups registered' })).not.toBeInTheDocument();
  });
});
