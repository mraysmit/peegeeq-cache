import { afterEach, beforeEach, describe, expect, it } from 'vitest';

import { InspectionClient } from '@src/api/inspection-client';
import {
  activityPageSchema,
  databaseMonitoringSchema,
  entryMetadataSchema,
  entryPageSchema,
  namespaceDetailsSchema,
  namespaceExportSchema,
  namespacePageSchema,
  namespaceStatsSchema,
  overviewSchema,
  revealedEntryValueSchema,
  runtimeMonitoringSchema,
} from '@src/api/inspection-schemas';
import { currentSessionSchema } from '@src/api/protocol-schemas';
import { ManagementClientError, SessionClient } from '@src/api/session-client';
import { invalidBody, route, startLoopbackServer, type LoopbackServer } from './support/loopback-server';

const NO_STORE = { 'cache-control': 'no-store, no-cache, must-revalidate', pragma: 'no-cache' } as const;

const validOverview = overviewSchema.parse({
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
});

const validDatabaseMonitoring = databaseMonitoringSchema.parse({
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
});

const validRuntimeMonitoring = runtimeMonitoringSchema.parse({
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
});

const validActivity = activityPageSchema.parse({
  items: [{
    eventId: 'event-2', occurredAt: '2026-08-26T10:15:32Z', actor: 'local-operator',
    action: 'SETUP_CONNECTED', outcome: 'SUCCEEDED', setupId: 'primary-cache',
    namespace: null, resource: { type: 'SETUP', identifier: 'must-not-render' },
    summary: 'Setup connected', correlationId: 'corr-activity-2',
  }],
  nextAfter: 'event-2',
  hasMore: true,
});

const validEntry = entryMetadataSchema.parse({
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
});

const validEntryPage = entryPageSchema.parse({
  items: [validEntry],
  nextCursor: 'entry-cursor-2',
  hasMore: true,
});

const validSession = currentSessionSchema.parse({
  user: 'local-operator', roles: ['viewer', 'operator'], serverVersion: '0.1.0-SNAPSHOT',
  apiVersion: 'v1', authenticationMode: 'LOCAL_TOKEN',
  csrfToken: 'csrf-token-with-at-least-thirty-two-characters',
  sessionIdleExpiresAt: '2099-01-01T00:00:00Z', sessionExpiresAt: '2099-01-01T01:00:00Z',
});

const validRevealedEntry = revealedEntryValueSchema.parse({
  key: validEntry.key,
  version: validEntry.version,
  value: { type: 'STRING', text: 'transient-value' },
  revealedAt: '2026-08-26T10:15:30Z',
  autoHideAfterMillis: 60_000,
});

describe('U3 inspection protocol client', () => {
  let server: LoopbackServer;
  /** Body served for every non-session request unless a test installs a routed handler through `server.use`. */
  let body: unknown = overviewSchema.parse(validOverview);

  beforeEach(async () => {
    body = validOverview;
    server = await startLoopbackServer((request, respond) => {
      if (route('GET', '/api/v1/session', request)) return respond.json(200, validSession);
      return respond.json(200, body, NO_STORE);
    });
  });

  afterEach(async () => { await server.close(); });

  const baseUrl = () => server.baseUrl;
  const requestUrls = () => server.requests.map((request) => request.url);

  it('accepts the exact overview contract through a real HTTP fixture', async () => {
    const client = new InspectionClient(new SessionClient(baseUrl()));
    await expect(client.overview('primary-cache')).resolves.toEqual(validOverview);
    expect(requestUrls()).toEqual(['/api/v1/setups/primary-cache/overview']);
  });

  it('validates database/runtime monitoring and bounded activity through real HTTP', async () => {
    server.use((request, respond) => {
      if (request.path.endsWith('/monitoring/database')) return respond.json(200, validDatabaseMonitoring, NO_STORE);
      if (request.path.endsWith('/monitoring/runtime')) return respond.json(200, validRuntimeMonitoring, NO_STORE);
      if (request.path.includes('/activity')) return respond.json(200, validActivity, NO_STORE);
      return respond.json(200, validOverview, NO_STORE);
    });
    const client = new InspectionClient(new SessionClient(baseUrl()));

    await expect(client.databaseMonitoring('primary-cache')).resolves.toEqual(validDatabaseMonitoring);
    await expect(client.runtimeMonitoring('primary-cache')).resolves.toEqual(validRuntimeMonitoring);
    await expect(client.activity('primary-cache', {
      after: 'event/1', limit: 10, namespace: 'orders/eu',
      action: 'ENTRY_SET', outcome: 'SUCCEEDED',
    })).resolves.toEqual(validActivity);

    expect(requestUrls()).toEqual([
      '/api/v1/setups/primary-cache/monitoring/database',
      '/api/v1/setups/primary-cache/monitoring/runtime',
      '/api/v1/setups/primary-cache/activity?after=event%2F1&limit=10&namespace=orders%2Feu&action=ENTRY_SET&outcome=SUCCEEDED',
    ]);
  });

  it('validates metadata-only entry list/detail responses and serializes exact filters', async () => {
    server.use((request, respond) => (request.path.includes('/entries/') ? respond.json(200, validEntry, NO_STORE) : respond.json(200, validEntryPage, NO_STORE)));
    const client = new InspectionClient(new SessionClient(baseUrl()));

    await expect(client.entries('primary-cache', validEntry.encodedNamespace, {
      prefix: 'café/', valueType: 'STRING', ttlState: 'INCLUDE_EXPIRED',
      cursor: 'opaque+/cursor==', limit: 25, sort: 'key:asc',
    })).resolves.toEqual(validEntryPage);
    await expect(client.entry(
      'primary-cache', validEntry.encodedNamespace, validEntry.encodedKey, true,
    )).resolves.toEqual(validEntry);

    expect(requestUrls()).toEqual([
      '/api/v1/setups/primary-cache/namespaces/5a6i5oi3L-iureWNlQ/entries?prefix=caf%C3%A9%2F&valueType=STRING&ttlState=INCLUDE_EXPIRED&cursor=opaque%2B%2Fcursor%3D%3D&limit=25&sort=key%3Aasc',
      '/api/v1/setups/primary-cache/namespaces/5a6i5oi3L-iureWNlQ/entries/Y2Fmw6kv5p2x5LqsL_CflJI_eD0x?includeExpired=true',
    ]);
  });

  it('rejects values or inconsistent cursor state in ordinary entry responses', async () => {
    const client = new InspectionClient(new SessionClient(baseUrl()));

    body = invalidBody({ ...validEntryPage, items: [{ ...validEntry, value: 'must-not-cross' }] });
    await expect(client.entries('primary-cache', validEntry.encodedNamespace)).rejects.toMatchObject({
      code: 'RESPONSE_CONTRACT_INVALID',
    });

    body = invalidBody({ ...validEntryPage, nextCursor: null });
    await expect(client.entries('primary-cache', validEntry.encodedNamespace)).rejects.toMatchObject({
      code: 'RESPONSE_CONTRACT_INVALID',
    });
  });

  it('posts a sensitive reveal to the exact encoded route and validates its value union', async () => {
    body = validRevealedEntry;
    const sessionClient = new SessionClient(baseUrl());
    const client = new InspectionClient(sessionClient);
    await sessionClient.load();

    await expect(client.revealEntryValue(
      'primary/cache', validEntry.encodedNamespace, validEntry.encodedKey, ' incident review ',
    )).resolves.toEqual(validRevealedEntry);
    expect(requestUrls()).toEqual([
      '/api/v1/session',
      '/api/v1/setups/primary%2Fcache/namespaces/5a6i5oi3L-iureWNlQ/entries/Y2Fmw6kv5p2x5LqsL_CflJI_eD0x/value/reveal',
    ]);

    body = invalidBody({ ...validRevealedEntry, value: { type: 'BYTES', base64: 'not-base64' } });
    await expect(client.revealEntryValue(
      'primary-cache', validEntry.encodedNamespace, validEntry.encodedKey,
    )).rejects.toMatchObject({ code: 'RESPONSE_CONTRACT_INVALID', status: 502 });
  });

  it.each([
    ['/monitoring/database', invalidBody({ ...validDatabaseMonitoring, scope: 'MANAGEMENT_RUNTIME' })],
    ['/monitoring/runtime', invalidBody({ ...validRuntimeMonitoring, activeOperations: 1 })],
    ['/activity', invalidBody({ ...validActivity, hasMore: false })],
  ])('rejects incompatible monitoring payloads from %s', async (suffix, invalid) => {
    body = invalid;
    const client = new InspectionClient(new SessionClient(baseUrl()));
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
    invalidBody({ ...validOverview, scope: 'CONSOLE' }),
    invalidBody({ ...validOverview, observedAt: 'yesterday' }),
    invalidBody({ ...validOverview, totals: { ...validOverview.totals, liveEntryCount: 2 } }),
    invalidBody({ ...validOverview, totals: { ...validOverview.totals, schemaBytes: { availability: 'UNAVAILABLE', reason: 'denied', value: '0' } } }),
    invalidBody({ ...validOverview, unexpected: 'must be rejected' }),
  ])('rejects incompatible overview payloads without fabricating data', async (invalid) => {
    body = invalid;
    const client = new InspectionClient(new SessionClient(baseUrl()));
    await expect(client.overview('primary-cache')).rejects.toMatchObject({
      code: 'RESPONSE_CONTRACT_INVALID',
      status: 502,
    } satisfies Partial<ManagementClientError>);
  });

  it('serializes namespace filters and opaque cursors and validates list, detail, and export responses', async () => {
    const namespace = namespaceStatsSchema.parse({
      namespace: 'orders/eu', encodedNamespace: 'b3JkZXJzL2V1',
      liveEntryCount: '10', liveCounterCount: '3', activeLockCount: '1',
      expiringEntryCount: '4', expiredEntryCount: '2', estimatedStorageBytes: '4096',
      observedAt: '2026-08-26T10:15:30Z',
    });
    server.use((request, respond) => {
      if (request.path.includes('/export')) {
        return respond.json(200, namespaceExportSchema.parse({ items: [namespace], truncated: false, exportedAt: '2026-08-26T10:16:00Z' }), NO_STORE);
      }
      if (request.path.endsWith(`/${namespace.encodedNamespace}`)) {
        return respond.json(200, namespaceDetailsSchema.parse({
          stats: namespace,
          valueTypeCounts: { STRING: '7', JSON: '3' },
          ttlStateCounts: { PERSISTENT: '6' },
          ttlDistribution: [{ range: 'PERSISTENT', count: '6' }],
        }), NO_STORE);
      }
      return respond.json(200, namespacePageSchema.parse({ items: [namespace], nextCursor: 'opaque+/cursor==', hasMore: true }), NO_STORE);
    });
    const client = new InspectionClient(new SessionClient(baseUrl()));

    await expect(client.namespaces('primary-cache', {
      prefix: 'orders/', status: 'ACTIVE_LOCKS', sort: 'entryCount:desc',
      cursor: 'opaque+/cursor==', limit: 25,
    })).resolves.toMatchObject({ hasMore: true });
    await expect(client.namespace('primary-cache', namespace.encodedNamespace)).resolves.toMatchObject({ stats: namespace });
    await expect(client.exportNamespaces('primary-cache', {
      prefix: 'orders/', status: 'ACTIVE_LOCKS', sort: 'entryCount:desc',
      cursor: 'must-not-export', limit: 1,
    })).resolves.toMatchObject({ truncated: false });

    expect(requestUrls()).toEqual([
      '/api/v1/setups/primary-cache/namespaces?prefix=orders%2F&status=ACTIVE_LOCKS&sort=entryCount%3Adesc&cursor=opaque%2B%2Fcursor%3D%3D&limit=25',
      `/api/v1/setups/primary-cache/namespaces/${namespace.encodedNamespace}`,
      '/api/v1/setups/primary-cache/namespaces/export?prefix=orders%2F&status=ACTIVE_LOCKS&sort=entryCount%3Adesc',
    ]);
  });

  it('rejects inconsistent namespace cursor envelopes', async () => {
    body = invalidBody({ items: [], nextCursor: null, hasMore: true });
    const client = new InspectionClient(new SessionClient(baseUrl()));
    await expect(client.namespaces('primary-cache')).rejects.toMatchObject({
      code: 'RESPONSE_CONTRACT_INVALID', status: 502,
    } satisfies Partial<ManagementClientError>);
  });
});
