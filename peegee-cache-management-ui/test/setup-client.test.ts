import { createServer, type IncomingMessage, type Server, type ServerResponse } from 'node:http';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';

import { ManagementClientError, SessionClient } from '@src/api/session-client';
import { SetupClient, type SetupRegistrationRequest } from '@src/api/setup-client';

const sessionBody = {
  user: 'setup-operator',
  roles: ['viewer', 'operator'],
  serverVersion: '0.1.0-SNAPSHOT',
  apiVersion: 'v1',
  authenticationMode: 'LOCAL_TOKEN',
  csrfToken: 'setup-client-csrf-token-with-forty-three-characters',
  sessionIdleExpiresAt: '2099-01-01T00:00:00Z',
  sessionExpiresAt: '2099-01-01T01:00:00Z',
  features: { setupRegistration: true, sensitiveReveal: true },
};

const setup = {
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
};

const connectionTest = {
  databaseReachable: true,
  schemaState: 'READY',
  migrationVersion: '1',
  latencyMillis: 9,
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
  limits: {
    pubSubChannelMaxBytes: 63,
    pubSubPayloadMaxBytes: 8_000,
    maximumValueBytes: 1_000_000,
  },
};

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
  let server: Server;
  let baseUrl: string;
  let handler: (request: IncomingMessage, response: ServerResponse) => void;

  beforeEach(async () => {
    handler = (_request, response) => response.writeHead(500).end();
    server = createServer((request, response) => handler(request, response));
    await new Promise<void>((resolve) => server.listen(0, '127.0.0.1', resolve));
    const address = server.address();
    if (address === null || typeof address === 'string') throw new Error('HTTP fixture did not bind');
    baseUrl = `http://127.0.0.1:${address.port}`;
  });

  afterEach(async () => {
    await new Promise<void>((resolve, reject) => server.close((error) => {
      if (error) reject(error);
      else resolve();
    }));
  });

  it('serializes lifecycle requests with the in-memory CSRF proof and parses responses', async () => {
    const requests: Array<{ method: string; path: string; csrf: string; body: unknown }> = [];
    handler = (request, response) => {
      if (request.url === '/api/v1/session') {
        json(response, 200, sessionBody);
        return;
      }
      readBody(request).then((body) => {
        requests.push({
          method: request.method ?? '',
          path: request.url ?? '',
          csrf: String(request.headers['x-peegeeq-csrf'] ?? ''),
          body,
        });
        if (request.method === 'GET' && request.url === '/api/v1/setups') {
          json(response, 200, { items: [setup] });
        } else if (request.method === 'GET') {
          json(response, 200, {
            setup,
            migrationVersion: '1',
            runtime: {
              defaultTtlMillis: null,
              expirySweeperEnabled: true,
              expirySweepIntervalMillis: 30_000,
              expirySweepBatchSize: 500,
              poolMaxSize: 12,
            },
            registeredAt: '2099-01-01T00:00:00Z',
            connectedAt: '2099-01-01T00:01:00Z',
          });
        } else if (request.url?.endsWith('/detach') || request.method === 'DELETE') {
          response.writeHead(204).end();
        } else if (request.url?.endsWith('/test') || request.url?.endsWith('/actions/test')) {
          json(response, 200, connectionTest);
        } else {
          json(response, request.url === '/api/v1/setups' ? 201 : 200, setup);
        }
      }).catch(() => response.writeHead(500).end());
    };
    const sessionClient = new SessionClient(baseUrl);
    await sessionClient.load();
    const client = new SetupClient(sessionClient);

    await expect(client.list()).resolves.toEqual([setup]);
    await expect(client.details(setup.setupId)).resolves.toMatchObject({ setup });
    await expect(client.testConnection(registration)).resolves.toMatchObject({ databaseReachable: true });
    await expect(client.register(registration)).resolves.toEqual(setup);
    await expect(client.testRegistered(setup.setupId)).resolves.toMatchObject({ schemaState: 'READY' });
    await expect(client.connect(setup.setupId)).resolves.toEqual(setup);
    await expect(client.detach(setup.setupId)).resolves.toBeUndefined();
    await expect(client.forget(setup.setupId)).resolves.toBeUndefined();

    const mutations = requests.filter((request) => request.method !== 'GET');
    expect(mutations).toHaveLength(6);
    expect(mutations.every((request) => request.csrf === sessionBody.csrfToken)).toBe(true);
    expect(mutations.find((request) => request.path === '/api/v1/setups')?.body).toEqual(registration);
    expect(JSON.stringify({ localStorage, sessionStorage })).not.toContain(registration.password);
  });

  it('rejects a malformed list instead of rendering invented setup state', async () => {
    handler = (request, response) => {
      if (request.url === '/api/v1/session') json(response, 200, sessionBody);
      else json(response, 200, { items: [{ ...setup, state: 'MAGIC' }] });
    };
    const sessionClient = new SessionClient(baseUrl);
    await sessionClient.load();

    await expect(new SetupClient(sessionClient).list()).rejects.toMatchObject({
      code: 'RESPONSE_CONTRACT_INVALID',
      status: 502,
    } satisfies Partial<ManagementClientError>);
  });

  it('preserves RFC 9457 diagnostics from lifecycle failures', async () => {
    handler = (request, response) => {
      if (request.url === '/api/v1/session') {
        json(response, 200, sessionBody);
        return;
      }
      json(response, 403, {
        type: 'https://peegeeq.dev/problems/setup-action-forbidden',
        title: 'Setup action forbidden',
        status: 403,
        code: 'SETUP_ACTION_FORBIDDEN',
        detail: 'Configured setup cannot be forgotten',
        instance: request.url,
        correlationId: 'setup-correlation-17',
        fieldErrors: [],
      });
    };
    const sessionClient = new SessionClient(baseUrl);
    await sessionClient.load();

    await expect(new SetupClient(sessionClient).forget(setup.setupId)).rejects.toMatchObject({
      code: 'SETUP_ACTION_FORBIDDEN',
      correlationId: 'setup-correlation-17',
      status: 403,
    } satisfies Partial<ManagementClientError>);
  });

  it('discovers runtime health and capabilities through validated setup endpoints', async () => {
    const health = {
      status: 'UP',
      schemaReady: true,
      latencyMillis: 6,
      checkedAt: '2099-01-01T00:00:00Z',
      detail: 'PostgreSQL and cache schema are ready',
    };
    handler = (request, response) => {
      if (request.url === '/api/v1/session') json(response, 200, sessionBody);
      else if (request.url?.endsWith('/health')) json(response, 200, health);
      else if (request.url?.endsWith('/capabilities')) {
        json(response, 200, {
          migrationVersion: connectionTest.migrationVersion,
          capabilities: connectionTest.capabilities,
          limits: connectionTest.limits,
        });
      } else response.writeHead(404).end();
    };
    const sessionClient = new SessionClient(baseUrl);
    await sessionClient.load();
    const client = new SetupClient(sessionClient) as SetupClient & {
      health(setupId: string): Promise<typeof health>;
      capabilities(setupId: string): Promise<{
        migrationVersion: string;
        capabilities: typeof connectionTest.capabilities;
        limits: typeof connectionTest.limits;
      }>;
    };

    await expect(client.health(setup.setupId)).resolves.toEqual(health);
    await expect(client.capabilities(setup.setupId)).resolves.toMatchObject({
      migrationVersion: '1',
      capabilities: { namespaceInspection: true, pubSub: true },
    });
  });
});

function json(response: ServerResponse, status: number, body: unknown): void {
  response.writeHead(status, {
    'cache-control': 'no-store',
    'content-type': status >= 400 ? 'application/problem+json' : 'application/json',
  });
  response.end(JSON.stringify(body));
}

async function readBody(request: IncomingMessage): Promise<unknown> {
  if (request.method === 'GET') return undefined;
  let body = '';
  for await (const chunk of request) body += String(chunk);
  return body.length === 0 ? undefined : JSON.parse(body) as unknown;
}
