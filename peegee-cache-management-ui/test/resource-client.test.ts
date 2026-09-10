import { Buffer } from 'node:buffer';
import { createServer, type IncomingMessage, type Server } from 'node:http';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';

import { ResourceClient } from '@src/api/resource-client';
import { SessionClient } from '@src/api/session-client';

const session = { user: 'operator', roles: ['viewer', 'operator'], serverVersion: '1', apiVersion: 'v1', authenticationMode: 'LOCAL_TOKEN', csrfToken: 'c'.repeat(43), sessionIdleExpiresAt: '2099-01-01T00:00:00Z', sessionExpiresAt: '2099-01-01T01:00:00Z' } as const;
const counter = { namespace: 'orders', encodedNamespace: 'b3JkZXJz', key: 'total', encodedKey: 'dG90YWw', value: '-9223372036854775808', version: '9007199254740993', createdAt: '2026-08-29T10:00:00Z', updatedAt: '2026-08-29T10:01:00Z', ttl: { state: 'PERSISTENT', ttlMillis: null, expiresAt: null } } as const;
const lock = { namespace: 'orders', encodedNamespace: 'b3JkZXJz', key: 'processor', encodedKey: 'cHJvY2Vzc29y', fencingToken: '9223372036854775807', version: '4', createdAt: '2026-08-29T10:00:00Z', updatedAt: '2026-08-29T10:01:00Z', leaseExpiresAt: '2099-08-29T10:02:00Z', leaseRemainingMillis: 60_000, owner: { state: 'MASKED' } } as const;

describe('U6 counter and lock protocol', () => {
  let server: Server;
  let baseUrl: string;
  let responseBody: unknown;
  let responseStatus: number;
  let requests: Array<{ body: unknown; headers: IncomingMessage['headers']; method?: string; url?: string }>;

  beforeEach(async () => {
    responseBody = counter;
    responseStatus = 200;
    requests = [];
    server = createServer((request, response) => {
      const chunks: Buffer[] = [];
      request.on('data', (chunk: Buffer) => chunks.push(chunk));
      request.on('end', () => {
        const text = Buffer.concat(chunks).toString('utf8');
        requests.push({ body: text === '' ? undefined : JSON.parse(text), headers: request.headers, method: request.method, url: request.url });
        const body = request.url === '/api/v1/session' ? session : responseBody;
        response.writeHead(request.url === '/api/v1/session' ? 200 : responseStatus, { 'content-type': 'application/json', 'cache-control': 'no-store, no-cache, must-revalidate', pragma: 'no-cache' });
        response.end(body === undefined ? '' : JSON.stringify(body));
      });
    });
    await new Promise<void>((resolve) => server.listen(0, '127.0.0.1', resolve));
    const address = server.address();
    if (address === null || typeof address === 'string') throw new Error('fixture did not bind');
    baseUrl = `http://127.0.0.1:${address.port}`;
  });

  afterEach(async () => { await new Promise<void>((resolve, reject) => server.close((error) => error ? reject(error) : resolve())); });

  it('round-trips signed 64-bit counter boundaries without JavaScript numbers', async () => {
    const client = await authenticatedClient(baseUrl);
    await expect(client.counter('setup', counter.encodedNamespace, counter.encodedKey)).resolves.toEqual(counter);
    responseBody = { ...counter, value: Number('9223372036854775807') };
    await expect(client.counter('setup', counter.encodedNamespace, counter.encodedKey)).rejects.toMatchObject({ code: 'RESPONSE_CONTRACT_INVALID' });
  });

  it('sends exact-version counter set and signed adjustment preconditions', async () => {
    const client = await authenticatedClient(baseUrl);
    await client.setCounter('setup', counter.encodedNamespace, counter.encodedKey, counter.version, { value: '9223372036854775807', ttlMode: 'PRESERVE_EXISTING', ttlMillis: null });
    await client.adjustCounter('setup', counter.encodedNamespace, counter.encodedKey, counter.version, { delta: '-2', createIfMissing: false, ttlMode: 'PRESERVE_EXISTING', ttlMillis: null });
    expect(requests.slice(1).map((request) => ({ method: request.method, ifMatch: request.headers['if-match'], body: request.body }))).toEqual([
      { method: 'PUT', ifMatch: `"v${counter.version}"`, body: { value: '9223372036854775807', ttlMode: 'PRESERVE_EXISTING', ttlMillis: null } },
      { method: 'POST', ifMatch: `"v${counter.version}"`, body: { delta: '-2', createIfMissing: false, ttlMode: 'PRESERVE_EXISTING', ttlMillis: null } },
    ]);
  });

  it('keeps lock owners masked in ordinary metadata and reveals through no-store transport', async () => {
    const client = await authenticatedClient(baseUrl);
    responseBody = lock;
    await expect(client.lock('setup', lock.encodedNamespace, lock.encodedKey)).resolves.toEqual(lock);
    responseBody = { key: lock.key, ownerToken: 'secret-owner', version: lock.version, revealedAt: '2026-08-29T10:01:30Z', autoHideAfterMillis: 60_000 };
    await expect(client.revealLockOwner('setup', lock.encodedNamespace, lock.encodedKey, 'incident')).resolves.toMatchObject({ ownerToken: 'secret-owner' });
  });

  it('forces release only with exact version and decoded key confirmation', async () => {
    const client = await authenticatedClient(baseUrl);
    responseStatus = 204;
    responseBody = undefined;
    await client.forceReleaseLock('setup', lock.encodedNamespace, lock.encodedKey, lock.version, lock.key, 'stale worker');
    expect(requests.at(-1)).toMatchObject({ method: 'POST', body: { confirmationKey: lock.key, reason: 'stale worker' } });
    expect(requests.at(-1)?.headers['if-match']).toBe('"v4"');
  });
});

async function authenticatedClient(baseUrl: string): Promise<ResourceClient> {
  const sessionClient = new SessionClient(baseUrl);
  await sessionClient.load();
  return new ResourceClient(sessionClient);
}
