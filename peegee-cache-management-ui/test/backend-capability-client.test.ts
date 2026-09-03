import { Buffer } from 'node:buffer';
import { createServer, type IncomingMessage, type Server } from 'node:http';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';

import { BackendCapabilityClient } from '@src/api/backend-capability-client';
import { SessionClient } from '@src/api/session-client';

const session = { user: 'operator', roles: ['viewer', 'operator'], serverVersion: '1', apiVersion: 'v1', authenticationMode: 'LOCAL_TOKEN', csrfToken: 'c'.repeat(43), sessionIdleExpiresAt: '2099-01-01T00:00:00Z', sessionExpiresAt: '2099-01-01T01:00:00Z', features: { setupRegistration: true, sensitiveReveal: true } } as const;
const entry = { namespace: 'orders', key: 'one', version: '2', createdAt: '2026-09-02T00:00:00Z', updatedAt: '2026-09-02T00:01:00Z', expiresAt: null, hitCount: '3', lastAccessedAt: null, value: { type: 'STRING', text: 'value' } } as const;

describe('complete backend capability protocol', () => {
  let server: Server;
  let baseUrl: string;
  let responseBody: unknown;
  let requests: Array<{ body: unknown; headers: IncomingMessage['headers']; method?: string; url?: string }>;

  beforeEach(async () => {
    responseBody = { exists: true };
    requests = [];
    server = createServer((request, response) => {
      const chunks: Buffer[] = [];
      request.on('data', (chunk: Buffer) => chunks.push(chunk));
      request.on('end', () => {
        const text = Buffer.concat(chunks).toString('utf8');
        requests.push({ body: text === '' ? undefined : JSON.parse(text), headers: request.headers, method: request.method, url: request.url });
        const body = request.url === '/api/v1/session' ? session : responseBody;
        response.writeHead(200, { 'content-type': 'application/json', 'cache-control': 'no-store, no-cache, must-revalidate', pragma: 'no-cache' });
        response.end(JSON.stringify(body));
      });
    });
    await new Promise<void>((resolve) => server.listen(0, '127.0.0.1', resolve));
    const address = server.address();
    if (address === null || typeof address === 'string') throw new Error('fixture did not bind');
    baseUrl = `http://127.0.0.1:${address.port}`;
  });

  afterEach(async () => { await new Promise<void>((resolve, reject) => server.close((error) => error ? reject(error) : resolve())); });

  it('uses dedicated existence, request-ordered batch, scan, and exact metrics contracts', async () => {
    const client = await authenticatedClient(baseUrl);
    await expect(client.entryExists('setup', 'b3JkZXJz', 'b25l')).resolves.toBe(true);

    responseBody = { items: [{ namespace: 'orders', key: 'one', found: true, entry }] };
    await client.batchGetEntries('setup', { keys: [{ namespace: 'orders', key: 'one' }], reason: 'incident review' });
    expect(requests.at(-1)).toMatchObject({ method: 'POST', body: { keys: [{ namespace: 'orders', key: 'one' }], reason: 'incident review' } });

    responseBody = { items: [{ namespace: 'orders', key: 'one', applied: true, newVersion: '3', previousEntry: entry }] };
    await client.batchSetEntries('setup', { entries: [{ namespace: 'orders', key: 'one', value: { type: 'LONG', decimal: '9223372036854775807' }, ttlMillis: null, setMode: 'ONLY_IF_VERSION_MATCHES', expectedVersion: '2', returnPreviousValue: true }] });
    expect(requests.at(-1)?.body).toMatchObject({ entries: [{ expectedVersion: '2', returnPreviousValue: true }] });

    responseBody = { deletedCount: '2' };
    await expect(client.batchDeleteEntries('setup', { keys: [
      { namespace: 'orders', key: 'one' }, { namespace: 'customers', key: 'two' },
    ] })).resolves.toEqual({ deletedCount: '2' });
    expect(requests.at(-1)).toMatchObject({ method: 'POST', url: '/api/v1/setups/setup/entries/batch-delete' });

    responseBody = { entries: [entry], nextCursor: null, hasMore: false };
    await client.scanEntries('setup', { namespace: 'orders', prefix: null, cursor: null, limit: 50, includeValues: true, includeExpired: true, reason: 'incident review' });
    expect(requests.at(-1)?.body).toMatchObject({ includeValues: true, includeExpired: true });

    responseBody = { cacheGets: '1', cacheHits: '1', cacheMisses: '0', cacheSets: '2', cacheSetsApplied: '2', cacheDeletes: '0', counterIncrements: '3', counterSets: '1', counterDeletes: '0', lockAcquires: '4', lockAcquiresGranted: '2', lockRenewals: '1', lockReleases: '1', publishes: '5', subscribes: '2' };
    await expect(client.cacheMetrics('setup')).resolves.toMatchObject({ lockAcquiresGranted: '2' });
  });

  it('exposes acquire, renew, normal owner release, and ownership checks', async () => {
    const client = await authenticatedClient(baseUrl);
    responseBody = { acquired: true, namespace: 'orders', key: 'processor', ownerToken: 'owner', fencingToken: '9', leaseExpiresAt: '2099-01-01T00:00:00Z' };
    await client.acquireLock('setup', 'b3JkZXJz', 'cHJvY2Vzc29y', { ownerToken: 'owner', leaseTtlMillis: 30_000, reentrantForSameOwner: false, issueFencingToken: true });
    expect(requests.at(-1)?.url).toContain('/acquire');

    responseBody = { renewed: true };
    await expect(client.renewLock('setup', 'b3JkZXJz', 'cHJvY2Vzc29y', { ownerToken: 'owner', leaseTtlMillis: 30_000 })).resolves.toBe(true);
    responseBody = { released: true };
    await expect(client.releaseLock('setup', 'b3JkZXJz', 'cHJvY2Vzc29y', 'owner')).resolves.toBe(true);
    responseBody = { heldByOwner: false };
    await expect(client.isLockHeldBy('setup', 'b3JkZXJz', 'cHJvY2Vzc29y', 'owner')).resolves.toBe(false);

    expect(requests.slice(-3).map((request) => request.url)).toEqual([
      '/api/v1/setups/setup/namespaces/b3JkZXJz/locks/cHJvY2Vzc29y/renew',
      '/api/v1/setups/setup/namespaces/b3JkZXJz/locks/cHJvY2Vzc29y/release',
      '/api/v1/setups/setup/namespaces/b3JkZXJz/locks/cHJvY2Vzc29y/ownership',
    ]);
    expect(requests.slice(1).every((request) => request.headers['x-peegeeq-csrf'] === session.csrfToken)).toBe(true);
  });
});

async function authenticatedClient(baseUrl: string): Promise<BackendCapabilityClient> {
  const sessionClient = new SessionClient(baseUrl);
  await sessionClient.load();
  return new BackendCapabilityClient(sessionClient);
}
