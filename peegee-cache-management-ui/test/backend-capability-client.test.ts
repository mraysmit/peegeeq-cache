import { afterEach, beforeEach, describe, expect, it } from 'vitest';

import { BackendCapabilityClient } from '@src/api/backend-capability-client';
import {
  acquireLockResultSchema,
  batchDeleteResultSchema,
  batchGetResultSchema,
  batchSetResultSchema,
  cacheEntrySnapshotSchema,
  cacheMetricsSnapshotSchema,
  entryExistsResultSchema,
  lockOwnershipResultSchema,
  releaseLockResultSchema,
  renewLockResultSchema,
  scanEntriesResultSchema,
} from '@src/api/backend-capability-schemas';
import { currentSessionSchema } from '@src/api/protocol-schemas';
import { SessionClient } from '@src/api/session-client';
import { invalidBody, route, startLoopbackServer, type LoopbackServer } from './support/loopback-server';

const session = currentSessionSchema.parse({
  user: 'operator', roles: ['viewer', 'operator'], serverVersion: '1', apiVersion: 'v1', authenticationMode: 'LOCAL_TOKEN', csrfToken: 'c'.repeat(43),
  sessionIdleExpiresAt: '2099-01-01T00:00:00Z', sessionExpiresAt: '2099-01-01T01:00:00Z',
});
const entry = cacheEntrySnapshotSchema.parse({
  namespace: 'orders', key: 'one', version: '2', createdAt: '2026-09-02T00:00:00Z', updatedAt: '2026-09-02T00:01:00Z', expiresAt: null, hitCount: '3', lastAccessedAt: null, value: { type: 'STRING', text: 'value' },
});
const NO_STORE = { 'cache-control': 'no-store, no-cache, must-revalidate', pragma: 'no-cache' } as const;

describe('complete backend capability protocol', () => {
  let server: LoopbackServer;
  let body: unknown = entryExistsResultSchema.parse({ exists: true });

  beforeEach(async () => {
    body = entryExistsResultSchema.parse({ exists: true });
    server = await startLoopbackServer((request, respond) => {
      if (route('GET', '/api/v1/session', request)) return respond.json(200, session);
      return respond.json(200, body, NO_STORE);
    });
  });

  afterEach(async () => { await server.close(); });

  it('uses dedicated existence, request-ordered batch, scan, and exact metrics contracts', async () => {
    const client = await authenticatedClient(server.baseUrl);
    await expect(client.entryExists('setup', 'b3JkZXJz', 'b25l')).resolves.toBe(true);
    expect(server.requests.at(-1)).toMatchObject({ method: 'GET', path: '/api/v1/setups/setup/namespaces/b3JkZXJz/entries/b25l/exists' });

    body = batchGetResultSchema.parse({ items: [{ namespace: 'orders', key: 'one', found: true, entry }] });
    await client.batchGetEntries('setup', { keys: [{ namespace: 'orders', key: 'one' }], reason: 'incident review' });
    expect(server.requests.at(-1)).toMatchObject({ method: 'POST', body: { keys: [{ namespace: 'orders', key: 'one' }], reason: 'incident review' } });

    body = batchSetResultSchema.parse({ items: [{ namespace: 'orders', key: 'one', applied: true, newVersion: '3', previousEntry: entry }] });
    await client.batchSetEntries('setup', { entries: [{ namespace: 'orders', key: 'one', value: { type: 'LONG', decimal: '9223372036854775807' }, ttlMillis: null, setMode: 'ONLY_IF_VERSION_MATCHES', expectedVersion: '2', returnPreviousValue: true }] });
    expect(server.requests.at(-1)?.body).toMatchObject({ entries: [{ expectedVersion: '2', returnPreviousValue: true }] });

    body = batchDeleteResultSchema.parse({ deletedCount: '2' });
    await expect(client.batchDeleteEntries('setup', { keys: [
      { namespace: 'orders', key: 'one' }, { namespace: 'customers', key: 'two' },
    ] })).resolves.toEqual({ deletedCount: '2' });
    expect(server.requests.at(-1)).toMatchObject({ method: 'POST', path: '/api/v1/setups/setup/entries/batch-delete' });

    body = scanEntriesResultSchema.parse({ entries: [entry], nextCursor: null, hasMore: false });
    await client.scanEntries('setup', { namespace: 'orders', prefix: null, cursor: null, limit: 50, includeValues: true, includeExpired: true, reason: 'incident review' });
    expect(server.requests.at(-1)?.body).toMatchObject({ includeValues: true, includeExpired: true });

    body = cacheMetricsSnapshotSchema.parse({ cacheGets: '1', cacheHits: '1', cacheMisses: '0', cacheSets: '2', cacheSetsApplied: '2', cacheDeletes: '0', counterIncrements: '3', counterSets: '1', counterDeletes: '0', lockAcquires: '4', lockAcquiresGranted: '2', lockRenewals: '1', lockReleases: '1', publishes: '5', subscribes: '2' });
    await expect(client.cacheMetrics('setup')).resolves.toMatchObject({ lockAcquiresGranted: '2' });
  });

  it('exposes acquire, renew, normal owner release, and ownership checks', async () => {
    const client = await authenticatedClient(server.baseUrl);
    body = acquireLockResultSchema.parse({ acquired: true, namespace: 'orders', key: 'processor', ownerToken: 'owner', fencingToken: '9', leaseExpiresAt: '2099-01-01T00:00:00Z' });
    await client.acquireLock('setup', 'b3JkZXJz', 'cHJvY2Vzc29y', { ownerToken: 'owner', leaseTtlMillis: 30_000, reentrantForSameOwner: false, issueFencingToken: true });
    expect(server.requests.at(-1)?.path).toContain('/acquire');

    body = renewLockResultSchema.parse({ renewed: true });
    await expect(client.renewLock('setup', 'b3JkZXJz', 'cHJvY2Vzc29y', { ownerToken: 'owner', leaseTtlMillis: 30_000 })).resolves.toBe(true);
    body = releaseLockResultSchema.parse({ released: true });
    await expect(client.releaseLock('setup', 'b3JkZXJz', 'cHJvY2Vzc29y', 'owner')).resolves.toBe(true);
    body = lockOwnershipResultSchema.parse({ heldByOwner: false });
    await expect(client.isLockHeldBy('setup', 'b3JkZXJz', 'cHJvY2Vzc29y', 'owner')).resolves.toBe(false);

    expect(server.requests.slice(-3).map((request) => request.path)).toEqual([
      '/api/v1/setups/setup/namespaces/b3JkZXJz/locks/cHJvY2Vzc29y/renew',
      '/api/v1/setups/setup/namespaces/b3JkZXJz/locks/cHJvY2Vzc29y/release',
      '/api/v1/setups/setup/namespaces/b3JkZXJz/locks/cHJvY2Vzc29y/ownership',
    ]);
    expect(server.requests.slice(1).every((request) => request.headers['x-peegeeq-csrf'] === session.csrfToken)).toBe(true);
  });

  it('rejects an invalid request before transport and an incompatible response after it', async () => {
    const client = await authenticatedClient(server.baseUrl);
    await expect(client.batchGetEntries('setup', { keys: [], reason: 'incident review' })).rejects.toMatchObject({ status: 400, code: 'VALIDATION_FAILED' });
    expect(server.requests.filter((request) => request.method === 'POST')).toHaveLength(0);

    body = invalidBody({ deletedCount: 2 });
    await expect(client.batchDeleteEntries('setup', { keys: [{ namespace: 'orders', key: 'one' }] })).rejects.toMatchObject({ status: 502, code: 'RESPONSE_CONTRACT_INVALID' });
  });

  it('refuses a sensitive response the server did not mark no-store', async () => {
    const client = await authenticatedClient(server.baseUrl);
    server.use((request, respond) => {
      if (route('GET', '/api/v1/session', request)) return respond.json(200, session);
      return respond.json(200, entryExistsResultSchema.parse({ exists: true }));
    });
    await expect(client.entryExists('setup', 'b3JkZXJz', 'b25l')).rejects.toMatchObject({ status: 502, code: 'SENSITIVE_RESPONSE_CACHEABLE' });
  });
});

async function authenticatedClient(baseUrl: string): Promise<BackendCapabilityClient> {
  const sessionClient = new SessionClient(baseUrl);
  await sessionClient.load();
  return new BackendCapabilityClient(sessionClient);
}
