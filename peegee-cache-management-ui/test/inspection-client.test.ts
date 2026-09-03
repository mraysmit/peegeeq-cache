import { createServer, type Server } from 'node:http';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';

import { InspectionClient } from '@src/api/inspection-client';
import { ManagementClientError, SessionClient } from '@src/api/session-client';

const validOverview = {
  scope: 'DATABASE',
  observedAt: '2026-08-26T10:15:30Z',
  health: {
    status: 'UP', schemaReady: true, latencyMillis: 7,
    checkedAt: '2026-08-26T10:15:29Z', detail: 'Ready',
  },
  totals: {
    namespaceCount: '1', liveEntryCount: '2', liveCounterCount: '3',
    activeLockCount: '4', expiredEntryCount: '5', expiredCounterCount: '6',
    schemaBytes: { availability: 'AVAILABLE', reason: null, value: '4096' },
  },
  databaseStats: {
    observedAt: '2026-08-26T10:15:30Z',
    databaseBytes: { availability: 'AVAILABLE', reason: null, value: '8192' },
    schemaBytes: { availability: 'AVAILABLE', reason: null, value: '4096' },
  },
  expiryStats: {
    observedAt: '2026-08-26T10:15:30Z', expiredEntryCount: '5', expiredCounterCount: '6',
    oldestLagMillis: { availability: 'UNAVAILABLE', reason: 'no expired rows', value: null },
  },
  expiry: {
    oldestExpiredRowLagMillis: null, sweeperEnabled: true,
    lastSweepAt: null, lastSweepDeletedRows: '0',
  },
  valueTypeCounts: { STRING: '2' },
  topNamespaces: [],
};

const validDatabaseMonitoring = {
  scope: 'DATABASE',
  observedAt: '2026-08-26T10:15:30Z',
  health: validOverview.health,
  tableBytes: { availability: 'AVAILABLE', reason: null, value: '2048' },
  indexBytes: { availability: 'AVAILABLE', reason: null, value: '1024' },
  schemaBytes: { availability: 'AVAILABLE', reason: null, value: '3072' },
  liveRows: { availability: 'AVAILABLE', reason: null, value: '5' },
  expiredRows: { availability: 'AVAILABLE', reason: null, value: '2' },
  deadTuples: { availability: 'UNAVAILABLE', reason: 'pg_stat privilege required', value: null },
  lastVacuumAt: null,
  lastAutovacuumAt: '2026-08-26T10:10:00Z',
  databaseConnections: { availability: 'AVAILABLE', reason: null, value: '12' },
  cacheConnections: { availability: 'AVAILABLE', reason: null, value: '3' },
  expiryBacklog: '2',
  oldestExpiredRowLagMillis: 2500,
};

const validRuntimeMonitoring = {
  scope: 'MANAGEMENT_RUNTIME',
  observedAt: '2026-08-26T10:15:31Z',
  lifecycleState: 'RUNNING',
  pool: {
    active: { availability: 'AVAILABLE', reason: null, value: '1' },
    idle: { availability: 'AVAILABLE', reason: null, value: '2' },
    pending: { availability: 'AVAILABLE', reason: null, value: '0' },
    maximum: { availability: 'AVAILABLE', reason: null, value: '3' },
  },
  activeOperations: '1',
  pubSubSubscriptions: '2',
  sseClients: '1',
  webSocketClients: '1',
  retainedPayloadBytes: '64',
  auditQueue: { depth: '0', capacity: '1024', acceptingMutations: true },
  expirySweeper: { ownedByRuntime: true, running: true, lastSweepAt: '2026-08-26T10:15:00Z' },
  operations: [{ operation: 'getOverview', status: 'COMPLETE', count: '4', errorCount: '0', latencyMillis: '12' }],
};

const validActivity = {
  items: [{
    eventId: 'event-2', occurredAt: '2026-08-26T10:15:32Z', actor: 'local-operator',
    action: 'SETUP_CONNECTED', outcome: 'SUCCEEDED', setupId: 'primary-cache',
    namespace: null, resource: { type: 'SETUP', identifier: 'must-not-render' },
    summary: 'Setup connected', correlationId: 'corr-activity-2',
  }],
  nextAfter: 'event-2',
  hasMore: true,
};

const validEntry = {
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
} as const;

const validEntryPage = {
  items: [validEntry],
  nextCursor: 'entry-cursor-2',
  hasMore: true,
} as const;

const validSession = {
  user: 'local-operator', roles: ['viewer', 'operator'], serverVersion: '0.1.0-SNAPSHOT',
  apiVersion: 'v1', authenticationMode: 'LOCAL_TOKEN',
  csrfToken: 'csrf-token-with-at-least-thirty-two-characters',
  sessionIdleExpiresAt: '2099-01-01T00:00:00Z', sessionExpiresAt: '2099-01-01T01:00:00Z',
  features: { setupRegistration: true, sensitiveReveal: true },
} as const;

const validRevealedEntry = {
  key: validEntry.key,
  version: validEntry.version,
  value: { type: 'STRING', text: 'transient-value' },
  revealedAt: '2026-08-26T10:15:30Z',
  autoHideAfterMillis: 60_000,
} as const;

describe('U3 inspection protocol client', () => {
  let server: Server;
  let baseUrl: string;
  let responseBody: unknown;
  let responseFor: (url: string) => unknown;
  let requestUrls: string[];

  beforeEach(async () => {
    responseBody = validOverview;
    responseFor = () => responseBody;
    requestUrls = [];
    server = createServer((request, response) => {
      requestUrls.push(request.url ?? '');
      response.writeHead(200, {
        'cache-control': 'no-store, no-cache, must-revalidate',
        'content-type': 'application/json',
        pragma: 'no-cache',
      });
      response.end(JSON.stringify(responseFor(request.url ?? '')));
    });
    await new Promise<void>((resolve) => server.listen(0, '127.0.0.1', resolve));
    const address = server.address();
    if (address === null || typeof address === 'string') throw new Error('HTTP fixture did not bind');
    baseUrl = `http://127.0.0.1:${address.port}`;
  });

  afterEach(async () => {
    await new Promise<void>((resolve, reject) => server.close((error) => error ? reject(error) : resolve()));
  });

  it('accepts the exact overview contract through a real HTTP fixture', async () => {
    const client = new InspectionClient(new SessionClient(baseUrl));
    await expect(client.overview('primary-cache')).resolves.toEqual(validOverview);
    expect(requestUrls).toEqual(['/api/v1/setups/primary-cache/overview']);
  });

  it('validates database/runtime monitoring and bounded activity through real HTTP', async () => {
    responseFor = (url) => {
      if (url.endsWith('/monitoring/database')) return validDatabaseMonitoring;
      if (url.endsWith('/monitoring/runtime')) return validRuntimeMonitoring;
      if (url.includes('/activity')) return validActivity;
      return validOverview;
    };
    const client = new InspectionClient(new SessionClient(baseUrl));

    await expect(client.databaseMonitoring('primary-cache')).resolves.toEqual(validDatabaseMonitoring);
    await expect(client.runtimeMonitoring('primary-cache')).resolves.toEqual(validRuntimeMonitoring);
    await expect(client.activity('primary-cache', {
      after: 'event/1', limit: 10, namespace: 'orders/eu',
      action: 'ENTRY_SET', outcome: 'SUCCEEDED',
    })).resolves.toEqual(validActivity);

    expect(requestUrls).toEqual([
      '/api/v1/setups/primary-cache/monitoring/database',
      '/api/v1/setups/primary-cache/monitoring/runtime',
      '/api/v1/setups/primary-cache/activity?after=event%2F1&limit=10&namespace=orders%2Feu&action=ENTRY_SET&outcome=SUCCEEDED',
    ]);
  });

  it('validates metadata-only entry list/detail responses and serializes exact filters', async () => {
    responseFor = (url) => url.includes('/entries/') ? validEntry : validEntryPage;
    const client = new InspectionClient(new SessionClient(baseUrl));

    await expect(client.entries('primary-cache', validEntry.encodedNamespace, {
      prefix: 'café/', valueType: 'STRING', ttlState: 'INCLUDE_EXPIRED',
      cursor: 'opaque+/cursor==', limit: 25, sort: 'key:asc',
    })).resolves.toEqual(validEntryPage);
    await expect(client.entry(
      'primary-cache', validEntry.encodedNamespace, validEntry.encodedKey, true,
    )).resolves.toEqual(validEntry);

    expect(requestUrls).toEqual([
      '/api/v1/setups/primary-cache/namespaces/5a6i5oi3L-iureWNlQ/entries?prefix=caf%C3%A9%2F&valueType=STRING&ttlState=INCLUDE_EXPIRED&cursor=opaque%2B%2Fcursor%3D%3D&limit=25&sort=key%3Aasc',
      '/api/v1/setups/primary-cache/namespaces/5a6i5oi3L-iureWNlQ/entries/Y2Fmw6kv5p2x5LqsL_CflJI_eD0x?includeExpired=true',
    ]);
  });

  it('rejects values or inconsistent cursor state in ordinary entry responses', async () => {
    const client = new InspectionClient(new SessionClient(baseUrl));

    responseBody = { ...validEntryPage, items: [{ ...validEntry, value: 'must-not-cross' }] };
    await expect(client.entries('primary-cache', validEntry.encodedNamespace)).rejects.toMatchObject({
      code: 'RESPONSE_CONTRACT_INVALID',
    });

    responseBody = { ...validEntryPage, nextCursor: null };
    await expect(client.entries('primary-cache', validEntry.encodedNamespace)).rejects.toMatchObject({
      code: 'RESPONSE_CONTRACT_INVALID',
    });
  });

  it('posts a sensitive reveal to the exact encoded route and validates its value union', async () => {
    responseFor = (url) => url === '/api/v1/session' ? validSession : validRevealedEntry;
    const sessionClient = new SessionClient(baseUrl);
    const client = new InspectionClient(sessionClient);
    await sessionClient.load();

    await expect(client.revealEntryValue(
      'primary/cache', validEntry.encodedNamespace, validEntry.encodedKey, ' incident review ',
    )).resolves.toEqual(validRevealedEntry);
    expect(requestUrls).toEqual([
      '/api/v1/session',
      '/api/v1/setups/primary%2Fcache/namespaces/5a6i5oi3L-iureWNlQ/entries/Y2Fmw6kv5p2x5LqsL_CflJI_eD0x/value/reveal',
    ]);

    responseBody = { ...validRevealedEntry, value: { type: 'BYTES', base64: 'not-base64' } };
    responseFor = () => responseBody;
    await expect(client.revealEntryValue(
      'primary-cache', validEntry.encodedNamespace, validEntry.encodedKey,
    )).rejects.toMatchObject({ code: 'RESPONSE_CONTRACT_INVALID', status: 502 });
  });

  it.each([
    ['/monitoring/database', { ...validDatabaseMonitoring, scope: 'MANAGEMENT_RUNTIME' }],
    ['/monitoring/runtime', { ...validRuntimeMonitoring, activeOperations: 1 }],
    ['/activity', { ...validActivity, hasMore: false }],
  ])('rejects incompatible monitoring payloads from %s', async (suffix, invalid) => {
    responseBody = invalid;
    const client = new InspectionClient(new SessionClient(baseUrl));
    const request = suffix === '/monitoring/database'
      ? client.databaseMonitoring('primary-cache')
      : suffix === '/monitoring/runtime'
        ? client.runtimeMonitoring('primary-cache')
        : client.activity('primary-cache');
    await expect(request).rejects.toMatchObject({
      code: 'RESPONSE_CONTRACT_INVALID', status: 502,
    } satisfies Partial<ManagementClientError>);
  });

  it.each([
    { ...validOverview, scope: 'CONSOLE' },
    { ...validOverview, observedAt: 'yesterday' },
    { ...validOverview, totals: { ...validOverview.totals, liveEntryCount: 2 } },
    { ...validOverview, totals: { ...validOverview.totals, schemaBytes: { availability: 'UNAVAILABLE', reason: 'denied', value: '0' } } },
    { ...validOverview, unexpected: 'must be rejected' },
  ])('rejects incompatible overview payloads without fabricating data', async (invalid) => {
    responseBody = invalid;
    const client = new InspectionClient(new SessionClient(baseUrl));
    await expect(client.overview('primary-cache')).rejects.toMatchObject({
      code: 'RESPONSE_CONTRACT_INVALID',
      status: 502,
    } satisfies Partial<ManagementClientError>);
  });

  it('serializes namespace filters and opaque cursors and validates list, detail, and export responses', async () => {
    const namespace = {
      namespace: 'orders/eu', encodedNamespace: 'b3JkZXJzL2V1',
      liveEntryCount: '10', liveCounterCount: '3', activeLockCount: '1',
      expiringEntryCount: '4', expiredEntryCount: '2', estimatedStorageBytes: '4096',
      observedAt: '2026-08-26T10:15:30Z',
    };
    responseFor = (url) => {
      if (url.includes('/export')) {
        return { items: [namespace], truncated: false, exportedAt: '2026-08-26T10:16:00Z' };
      }
      if (url.endsWith(`/${namespace.encodedNamespace}`)) {
        return {
          stats: namespace,
          valueTypeCounts: { STRING: '7', JSON: '3' },
          ttlStateCounts: { PERSISTENT: '6' },
          ttlDistribution: [{ range: 'PERSISTENT', count: '6' }],
        };
      }
      return { items: [namespace], nextCursor: 'opaque+/cursor==', hasMore: true };
    };
    const client = new InspectionClient(new SessionClient(baseUrl));

    await expect(client.namespaces('primary-cache', {
      prefix: 'orders/', status: 'ACTIVE_LOCKS', sort: 'entryCount:desc',
      cursor: 'opaque+/cursor==', limit: 25,
    })).resolves.toMatchObject({ hasMore: true });
    await expect(client.namespace('primary-cache', namespace.encodedNamespace)).resolves.toMatchObject({ stats: namespace });
    await expect(client.exportNamespaces('primary-cache', {
      prefix: 'orders/', status: 'ACTIVE_LOCKS', sort: 'entryCount:desc',
      cursor: 'must-not-export', limit: 1,
    })).resolves.toMatchObject({ truncated: false });

    expect(requestUrls).toEqual([
      '/api/v1/setups/primary-cache/namespaces?prefix=orders%2F&status=ACTIVE_LOCKS&sort=entryCount%3Adesc&cursor=opaque%2B%2Fcursor%3D%3D&limit=25',
      `/api/v1/setups/primary-cache/namespaces/${namespace.encodedNamespace}`,
      '/api/v1/setups/primary-cache/namespaces/export?prefix=orders%2F&status=ACTIVE_LOCKS&sort=entryCount%3Adesc',
    ]);
  });

  it('rejects inconsistent namespace cursor envelopes', async () => {
    responseBody = { items: [], nextCursor: null, hasMore: true };
    const client = new InspectionClient(new SessionClient(baseUrl));
    await expect(client.namespaces('primary-cache')).rejects.toMatchObject({
      code: 'RESPONSE_CONTRACT_INVALID', status: 502,
    } satisfies Partial<ManagementClientError>);
  });
});
