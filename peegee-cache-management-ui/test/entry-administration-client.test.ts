import { createServer, type IncomingMessage, type Server } from 'node:http';
import { Buffer } from 'node:buffer';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';

import { EntryAdministrationClient } from '@src/api/entry-administration-client';
import { SessionClient } from '@src/api/session-client';

const session = {
  user: 'local-operator', roles: ['viewer', 'operator'], serverVersion: '0.1.0-SNAPSHOT',
  apiVersion: 'v1', authenticationMode: 'LOCAL_TOKEN',
  csrfToken: 'csrf-token-with-at-least-thirty-two-characters',
  sessionIdleExpiresAt: '2099-01-01T00:00:00Z', sessionExpiresAt: '2099-01-01T01:00:00Z',
  features: { setupRegistration: true, sensitiveReveal: true },
} as const;

const metadata = {
  namespace: 'orders', encodedNamespace: 'b3JkZXJz', key: 'order:1', encodedKey: 'b3JkZXI6MQ',
  valueType: 'STRING', sizeBytes: '5', version: '9007199254740993',
  createdAt: '2026-08-29T10:00:00Z', updatedAt: '2026-08-29T10:01:00Z', lastAccessedAt: null,
  ttl: { state: 'PERSISTENT', ttlMillis: null, expiresAt: null },
} as const;

describe('U5 entry administration protocol', () => {
  let server: Server;
  let baseUrl: string;
  let requests: Array<{ body: unknown; headers: IncomingMessage['headers']; method?: string; url?: string }>;
  let responseBody: unknown;
  let responseStatus: number;

  beforeEach(async () => {
    requests = [];
    responseBody = { applied: true, created: false, version: metadata.version, updatedAt: metadata.updatedAt, ttl: metadata.ttl };
    responseStatus = 200;
    server = createServer((request, response) => {
      const chunks: Buffer[] = [];
      request.on('data', (chunk: Buffer) => chunks.push(chunk));
      request.on('end', () => {
        const text = Buffer.concat(chunks).toString('utf8');
        requests.push({ body: text === '' ? undefined : JSON.parse(text), headers: request.headers, method: request.method, url: request.url });
        const body = request.url === '/api/v1/session' ? session : responseBody;
        response.writeHead(request.url === '/api/v1/session' ? 200 : responseStatus, {
          'content-type': 'application/json', etag: `"v${metadata.version}"`,
        });
        response.end(JSON.stringify(body));
      });
    });
    await new Promise<void>((resolve) => server.listen(0, '127.0.0.1', resolve));
    const address = server.address();
    if (address === null || typeof address === 'string') throw new Error('HTTP fixture did not bind');
    baseUrl = `http://127.0.0.1:${address.port}`;
  });

  afterEach(async () => {
    await new Promise<void>((resolve, reject) => server.close((error) => error ? reject(error) : resolve()));
  });

  it.each([
    ['UPSERT', undefined, undefined],
    ['ONLY_IF_ABSENT', undefined, '*'],
    ['ONLY_IF_PRESENT', '*', undefined],
    ['ONLY_IF_VERSION_MATCHES', `"v${metadata.version}"`, undefined],
  ] as const)('sends %s with its exact HTTP precondition', async (setMode, ifMatch, ifNoneMatch) => {
    const sessionClient = new SessionClient(baseUrl);
    await sessionClient.load();
    const client = new EntryAdministrationClient(sessionClient);

    await expect(client.setEntry('primary-cache', metadata.encodedNamespace, metadata.encodedKey, {
      value: { type: 'STRING', text: 'hello' }, ttlMode: 'PRESERVE_EXISTING', ttlMillis: null, setMode,
    }, setMode === 'ONLY_IF_VERSION_MATCHES' ? metadata.version : undefined)).resolves.toMatchObject({
      applied: true, version: metadata.version,
    });

    const request = requests.at(-1)!;
    expect(request.method).toBe('PUT');
    expect(request.headers['x-peegeeq-csrf']).toBe(session.csrfToken);
    expect(request.headers['if-match']).toBe(ifMatch);
    expect(request.headers['if-none-match']).toBe(ifNoneMatch);
    expect(request.body).toMatchObject({ setMode, value: { type: 'STRING', text: 'hello' } });
  });

  it('uses exact versions for TTL, persist, touch, and delete without optimistic outcomes', async () => {
    const sessionClient = new SessionClient(baseUrl);
    await sessionClient.load();
    const client = new EntryAdministrationClient(sessionClient);
    responseBody = metadata;

    await client.expireEntry('primary-cache', metadata.encodedNamespace, metadata.encodedKey, metadata.version, 60_000);
    await client.persistEntry('primary-cache', metadata.encodedNamespace, metadata.encodedKey, metadata.version);
    await client.touchEntry('primary-cache', metadata.encodedNamespace, metadata.encodedKey, metadata.version, null);
    responseStatus = 204;
    responseBody = undefined;
    await client.deleteEntry('primary-cache', metadata.encodedNamespace, metadata.encodedKey, metadata.version);

    expect(requests.slice(1).map(({ method, url, body, headers }) => ({ method, url, body, ifMatch: headers['if-match'] }))).toEqual([
      { method: 'POST', url: '/api/v1/setups/primary-cache/namespaces/b3JkZXJz/entries/b3JkZXI6MQ/ttl', body: { ttlMillis: 60_000 }, ifMatch: `"v${metadata.version}"` },
      { method: 'POST', url: '/api/v1/setups/primary-cache/namespaces/b3JkZXJz/entries/b3JkZXI6MQ/persist', body: undefined, ifMatch: `"v${metadata.version}"` },
      { method: 'POST', url: '/api/v1/setups/primary-cache/namespaces/b3JkZXJz/entries/b3JkZXI6MQ/touch', body: { refreshTtlMillis: null }, ifMatch: `"v${metadata.version}"` },
      { method: 'DELETE', url: '/api/v1/setups/primary-cache/namespaces/b3JkZXJz/entries/b3JkZXI6MQ', body: undefined, ifMatch: `"v${metadata.version}"` },
    ]);
  });

  it('validates a server-scoped one-time bulk preview and result', async () => {
    const sessionClient = new SessionClient(baseUrl);
    await sessionClient.load();
    const client = new EntryAdministrationClient(sessionClient);
    responseBody = {
      previewToken: 'p'.repeat(32), expiresAt: '2026-08-29T10:05:00Z', setupId: 'primary-cache',
      namespace: 'orders', resolvedCount: '1', totalBytes: '5', sampleKeys: ['order:1'],
      confirmationPhrase: 'DELETE 1 ENTRIES',
    };
    const preview = await client.previewBulkDelete('primary-cache', metadata.encodedNamespace, {
      selection: { type: 'EXPLICIT', targets: [{ key: metadata.key, version: metadata.version }] },
    });
    responseBody = { processedCount: '1', deletedCount: '1', conflictCount: '0', missingCount: '0', failedCount: '0', conflicts: [] };
    const result = await client.executeBulkDelete('primary-cache', metadata.encodedNamespace, {
      previewToken: preview.previewToken, confirmationPhrase: preview.confirmationPhrase,
    });

    expect(result.deletedCount).toBe('1');
    expect(requests.slice(1).map(({ url, body }) => ({ url, body }))).toEqual([
      { url: '/api/v1/setups/primary-cache/namespaces/b3JkZXJz/entries/bulk-delete/preview', body: { selection: { type: 'EXPLICIT', targets: [{ key: 'order:1', version: metadata.version }] } } },
      { url: '/api/v1/setups/primary-cache/namespaces/b3JkZXJz/entries/bulk-delete/execute', body: { previewToken: 'p'.repeat(32), confirmationPhrase: 'DELETE 1 ENTRIES' } },
    ]);
  });

  it('rejects malformed mutation success payloads', async () => {
    const sessionClient = new SessionClient(baseUrl);
    await sessionClient.load();
    const client = new EntryAdministrationClient(sessionClient);
    responseBody = { applied: true, created: false, version: 3, updatedAt: metadata.updatedAt, ttl: metadata.ttl };
    await expect(client.setEntry('primary-cache', metadata.encodedNamespace, metadata.encodedKey, {
      value: { type: 'STRING', text: 'hello' }, ttlMode: 'REMOVE', ttlMillis: null, setMode: 'UPSERT',
    })).rejects.toMatchObject({ code: 'RESPONSE_CONTRACT_INVALID', status: 502 });
  });
});
