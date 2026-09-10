import { afterEach, beforeEach, describe, expect, it } from 'vitest';

import { currentSessionSchema } from '@src/api/protocol-schemas';
import { ManagementClientError, SessionClient } from '@src/api/session-client';
import { SetupClient, type SetupRegistrationRequest } from '@src/api/setup-client';
import { setupConnectionTestSchema, setupDetailsSchema, setupHealthSchema, setupSummaryListSchema, setupSummarySchema } from '@src/api/setup-schemas';
import { invalidBody, route, startLoopbackServer, type LoopbackServer } from './support/loopback-server';

const sessionBody = currentSessionSchema.parse({
  user: 'setup-operator',
  roles: ['viewer', 'operator'],
  serverVersion: '0.1.0-SNAPSHOT',
  apiVersion: 'v1',
  authenticationMode: 'LOCAL_TOKEN',
  csrfToken: 'setup-client-csrf-token-with-forty-three-characters',
  sessionIdleExpiresAt: '2099-01-01T00:00:00Z',
  sessionExpiresAt: '2099-01-01T01:00:00Z',
});

const setup = setupSummarySchema.parse({
  setupId: 'primary-cache',
  displayName: 'Primary cache',
  host: 'db.example.test',
  port: 5432,
  database: 'peegeeq',
  schema: 'public',
  sslMode: 'VERIFY_FULL',
  source: 'UI_SESSION',
  state: 'CONNECTED',
  schemaState: 'READY',
  lastHealth: {
    status: 'UP',
    latencyMillis: 7,
    checkedAt: '2099-01-01T00:00:00Z',
  },
});

const connectionTest = setupConnectionTestSchema.parse({
  databaseReachable: true,
  schemaState: 'READY',
  migrationVersion: '1',
  latencyMillis: 9,
  limits: {
    pubSubChannelMaxBytes: 63,
    pubSubPayloadMaxBytes: 8_000,
    maximumValueBytes: 1_000_000,
  },
});

const details = setupDetailsSchema.parse({
  setup,
  migrationVersion: '1',
  runtime: {
    defaultTtlMillis: null,
    expirySweeperEnabled: true,
    expirySweepIntervalMillis: 30_000,
    expirySweepBatchSize: 500,
    writeBehindEnabled: false,
    writeBehindFlushIntervalMillis: 500,
    writeBehindMaxBufferSize: 10_000,
    writeBehindFlushBatchSize: 500,
    writeBehindMaxRetries: 3,
    writeBehindShutdownDrainTimeoutMillis: 5_000,
    pubSubChannelPrefix: 'peegee_cache',
    pubSubEnabled: true,
    schemaBootstrapMode: 'EXTERNAL',
    telemetryMode: 'NOOP',
    poolMaxSize: 12,
  },
  limits: { pubSubChannelMaxBytes: 49, pubSubPayloadMaxBytes: 7_500, maximumValueBytes: 10_485_760 },
  registeredAt: '2099-01-01T00:00:00Z',
  connectedAt: '2099-01-01T00:01:00Z',
});

const registration: SetupRegistrationRequest = {
  setupId: setup.setupId,
  displayName: setup.displayName,
  host: setup.host,
  port: setup.port,
  database: setup.database,
  schema: setup.schema,
  username: 'cache-user',
  password: 'not-persisted',
  sslMode: 'VERIFY_FULL',
  trustProfileId: 'production-ca',
  poolMaxSize: 12,
};

describe('setup management protocol client', () => {
  let server: LoopbackServer;

  beforeEach(async () => {
    localStorage.clear();
    sessionStorage.clear();
    server = await startLoopbackServer((request, respond) => {
      if (route('GET', '/api/v1/session', request)) return respond.json(200, sessionBody);
      return respond.problem(500, 'FIXTURE_UNROUTED', `no fixture for ${request.method} ${request.path}`);
    });
  });

  afterEach(async () => { await server.close(); });

  const authenticated = async () => {
    const sessionClient = new SessionClient(server.baseUrl);
    await sessionClient.load();
    return new SetupClient(sessionClient);
  };

  it('serializes lifecycle requests with the in-memory CSRF proof and parses responses', async () => {
    server.use((request, respond) => {
      if (route('GET', '/api/v1/session', request)) return respond.json(200, sessionBody);
      if (route('GET', '/api/v1/setups', request)) return respond.json(200, setupSummaryListSchema.parse({ items: [setup] }));
      if (request.method === 'GET') return respond.json(200, details);
      if (request.path.endsWith('/detach') || request.method === 'DELETE') return respond.noContent();
      if (request.path.endsWith('/test') || request.path.endsWith('/actions/test')) return respond.json(200, connectionTest);
      return respond.json(request.path === '/api/v1/setups' ? 201 : 200, setup);
    });
    const client = await authenticated();

    await expect(client.list()).resolves.toEqual([setup]);
    await expect(client.details(setup.setupId)).resolves.toMatchObject({ setup });
    await expect(client.testConnection(registration)).resolves.toMatchObject({ databaseReachable: true });
    await expect(client.register(registration)).resolves.toEqual(setup);
    await expect(client.testRegistered(setup.setupId)).resolves.toMatchObject({ schemaState: 'READY' });
    await expect(client.connect(setup.setupId)).resolves.toEqual(setup);
    await expect(client.detach(setup.setupId)).resolves.toBeUndefined();
    await expect(client.forget(setup.setupId)).resolves.toBeUndefined();

    const mutations = server.requests.filter((request) => request.method !== 'GET');
    expect(mutations.map((request) => `${request.method} ${request.path}`)).toEqual([
      'POST /api/v1/setups/actions/test',
      'POST /api/v1/setups',
      'POST /api/v1/setups/primary-cache/test',
      'POST /api/v1/setups/primary-cache/connect',
      'POST /api/v1/setups/primary-cache/detach',
      'DELETE /api/v1/setups/primary-cache',
    ]);
    expect(mutations.every((request) => request.headers['x-peegeeq-csrf'] === sessionBody.csrfToken)).toBe(true);
    expect(mutations.find((request) => request.path === '/api/v1/setups')?.body).toEqual(registration);
    expect(JSON.stringify({ localStorage, sessionStorage })).not.toContain(registration.password);
  });

  it('rejects a malformed list instead of rendering invented setup state', async () => {
    server.use((request, respond) => {
      if (route('GET', '/api/v1/session', request)) return respond.json(200, sessionBody);
      return respond.json(200, invalidBody({ items: [{ ...setup, state: 'MAGIC' }] }));
    });
    const client = await authenticated();

    await expect(client.list()).rejects.toMatchObject({
      code: 'RESPONSE_CONTRACT_INVALID',
      status: 502,
    } satisfies Partial<ManagementClientError>);
  });

  it('preserves RFC 9457 diagnostics from lifecycle failures', async () => {
    server.use((request, respond) => {
      if (route('GET', '/api/v1/session', request)) return respond.json(200, sessionBody);
      return respond.problem(403, 'SETUP_ACTION_FORBIDDEN', 'Configured setup cannot be forgotten', { title: 'Setup action forbidden', correlationId: 'setup-correlation-17' });
    });
    const client = await authenticated();

    await expect(client.forget(setup.setupId)).rejects.toMatchObject({
      code: 'SETUP_ACTION_FORBIDDEN',
      correlationId: 'setup-correlation-17',
      status: 403,
      message: 'Configured setup cannot be forgotten',
    } satisfies Partial<ManagementClientError>);
  });

  it('discovers runtime health and effective limits through validated setup endpoints', async () => {
    const health = setupHealthSchema.parse({
      status: 'UP',
      schemaReady: true,
      latencyMillis: 6,
      checkedAt: '2099-01-01T00:00:00Z',
      detail: 'PostgreSQL and cache schema are ready',
    });
    server.use((request, respond) => {
      if (route('GET', '/api/v1/session', request)) return respond.json(200, sessionBody);
      if (request.path.endsWith('/health')) return respond.json(200, health);
      if (request.path === '/api/v1/setups/primary-cache') return respond.json(200, details);
      return respond.problem(404, 'NOT_FOUND', request.path);
    });
    const client = await authenticated();

    await expect(client.health(setup.setupId)).resolves.toEqual(health);
    await expect(client.details(setup.setupId)).resolves.toMatchObject({
      migrationVersion: '1',
      limits: { pubSubChannelMaxBytes: 49, pubSubPayloadMaxBytes: 7_500, maximumValueBytes: 10_485_760 },
    });
    expect(server.requests.slice(1).map((request) => request.path)).toEqual([
      '/api/v1/setups/primary-cache/health',
      '/api/v1/setups/primary-cache',
    ]);
  });
});
