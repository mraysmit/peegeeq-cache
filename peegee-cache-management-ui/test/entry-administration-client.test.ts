import { afterEach, beforeEach, describe, expect, it } from 'vitest';

import { EntryAdministrationClient } from '@src/api/entry-administration-client';
import { bulkDeletePreviewSchema, bulkDeleteResultSchema, entrySetResultSchema } from '@src/api/entry-administration-schemas';
import { entryMetadataSchema } from '@src/api/inspection-schemas';
import { currentSessionSchema } from '@src/api/protocol-schemas';
import { SessionClient } from '@src/api/session-client';
import { invalidBody, route, startLoopbackServer, type LoopbackServer } from './support/loopback-server';

const session = currentSessionSchema.parse({
  user: 'local-operator', roles: ['viewer', 'operator'], serverVersion: '0.1.0-SNAPSHOT',
  apiVersion: 'v1', authenticationMode: 'LOCAL_TOKEN',
  csrfToken: 'csrf-token-with-at-least-thirty-two-characters',
  sessionIdleExpiresAt: '2099-01-01T00:00:00Z', sessionExpiresAt: '2099-01-01T01:00:00Z',
});

const metadata = entryMetadataSchema.parse({
  namespace: 'orders', encodedNamespace: 'b3JkZXJz', key: 'order:1', encodedKey: 'b3JkZXI6MQ',
  valueType: 'STRING', sizeBytes: '5', version: '9007199254740993',
  createdAt: '2026-08-29T10:00:00Z', updatedAt: '2026-08-29T10:01:00Z', lastAccessedAt: null,
  ttl: { state: 'PERSISTENT', ttlMillis: null, expiresAt: null },
});

const setResult = entrySetResultSchema.parse({ applied: true, created: false, version: metadata.version, updatedAt: metadata.updatedAt, ttl: metadata.ttl });

describe('U5 entry administration protocol', () => {
  let server: LoopbackServer;
  let body: unknown = setResult;
  let status = 200;

  beforeEach(async () => {
    body = setResult;
    status = 200;
    server = await startLoopbackServer((request, respond) => {
      if (route('GET', '/api/v1/session', request)) return respond.json(200, session);
      if (status === 204) return respond.noContent();
      return respond.json(status, body, { etag: `"v${metadata.version}"` });
    });
  });

  afterEach(async () => { await server.close(); });

  const client = async () => {
    const sessionClient = new SessionClient(server.baseUrl);
    await sessionClient.load();
    return new EntryAdministrationClient(sessionClient);
  };

  it.each([
    ['UPSERT', undefined, undefined],
    ['ONLY_IF_ABSENT', undefined, '*'],
    ['ONLY_IF_PRESENT', '*', undefined],
    ['ONLY_IF_VERSION_MATCHES', `"v${metadata.version}"`, undefined],
  ] as const)('sends %s with its exact HTTP precondition', async (setMode, ifMatch, ifNoneMatch) => {
    const administration = await client();

    await expect(administration.setEntry('primary-cache', metadata.encodedNamespace, metadata.encodedKey, {
      value: { type: 'STRING', text: 'hello' }, ttlMode: 'PRESERVE_EXISTING', ttlMillis: null, setMode,
    }, setMode === 'ONLY_IF_VERSION_MATCHES' ? metadata.version : undefined)).resolves.toMatchObject({
      applied: true, version: metadata.version,
    });

    const request = server.requests.at(-1)!;
    expect(request.method).toBe('PUT');
    expect(request.headers['x-peegeeq-csrf']).toBe(session.csrfToken);
    expect(request.headers['if-match']).toBe(ifMatch);
    expect(request.headers['if-none-match']).toBe(ifNoneMatch);
    expect(request.body).toMatchObject({ setMode, value: { type: 'STRING', text: 'hello' } });
  });

  it('refuses compare-and-set without an observed version and an invalid body before transport', async () => {
    const administration = await client();
    await expect(administration.setEntry('primary-cache', metadata.encodedNamespace, metadata.encodedKey, {
      value: { type: 'STRING', text: 'hello' }, ttlMode: 'PRESERVE_EXISTING', ttlMillis: null, setMode: 'ONLY_IF_VERSION_MATCHES',
    })).rejects.toMatchObject({ status: 400, code: 'VALIDATION_FAILED' });
    await expect(administration.setEntry('primary-cache', metadata.encodedNamespace, metadata.encodedKey, {
      value: { type: 'STRING', text: 'hello' }, ttlMode: 'REPLACE', ttlMillis: null, setMode: 'UPSERT',
    })).rejects.toMatchObject({ status: 400, code: 'VALIDATION_FAILED' });
    await expect(administration.deleteEntry('primary-cache', metadata.encodedNamespace, metadata.encodedKey, 'v1')).rejects.toMatchObject({ status: 400 });
    expect(server.requests.filter((request) => request.method !== 'GET')).toHaveLength(0);
  });

  it('uses exact versions for TTL, persist, touch, and delete without optimistic outcomes', async () => {
    const administration = await client();
    body = metadata;

    await administration.expireEntry('primary-cache', metadata.encodedNamespace, metadata.encodedKey, metadata.version, 60_000);
    await administration.persistEntry('primary-cache', metadata.encodedNamespace, metadata.encodedKey, metadata.version);
    await administration.touchEntry('primary-cache', metadata.encodedNamespace, metadata.encodedKey, metadata.version, null);
    status = 204;
    await administration.deleteEntry('primary-cache', metadata.encodedNamespace, metadata.encodedKey, metadata.version);

    expect(server.requests.slice(1).map(({ method, path, body: sent, headers }) => ({ method, path, body: sent, ifMatch: headers['if-match'] }))).toEqual([
      { method: 'POST', path: '/api/v1/setups/primary-cache/namespaces/b3JkZXJz/entries/b3JkZXI6MQ/ttl', body: { ttlMillis: 60_000 }, ifMatch: `"v${metadata.version}"` },
      { method: 'POST', path: '/api/v1/setups/primary-cache/namespaces/b3JkZXJz/entries/b3JkZXI6MQ/persist', body: undefined, ifMatch: `"v${metadata.version}"` },
      { method: 'POST', path: '/api/v1/setups/primary-cache/namespaces/b3JkZXJz/entries/b3JkZXI6MQ/touch', body: { refreshTtlMillis: null }, ifMatch: `"v${metadata.version}"` },
      { method: 'DELETE', path: '/api/v1/setups/primary-cache/namespaces/b3JkZXJz/entries/b3JkZXI6MQ', body: undefined, ifMatch: `"v${metadata.version}"` },
    ]);
  });

  it('surfaces a version conflict as the server problem, never as success', async () => {
    const administration = await client();
    server.use((request, respond) => {
      if (route('GET', '/api/v1/session', request)) return respond.json(200, session);
      return respond.problem(412, 'VERSION_MISMATCH', 'The entry changed', { correlationId: 'corr-412' });
    });
    await expect(administration.persistEntry('primary-cache', metadata.encodedNamespace, metadata.encodedKey, metadata.version))
      .rejects.toMatchObject({ status: 412, code: 'VERSION_MISMATCH', correlationId: 'corr-412' });
  });

  it('validates a server-scoped one-time bulk preview and result', async () => {
    const administration = await client();
    body = bulkDeletePreviewSchema.parse({
      previewToken: 'p'.repeat(32), expiresAt: '2026-08-29T10:05:00Z', setupId: 'primary-cache',
      namespace: 'orders', resolvedCount: '1', totalBytes: '5', sampleKeys: ['order:1'],
      confirmationPhrase: 'DELETE 1 ENTRIES',
    });
    const preview = await administration.previewBulkDelete('primary-cache', metadata.encodedNamespace, {
      selection: { type: 'EXPLICIT', targets: [{ key: metadata.key, version: metadata.version }] },
    });
    body = bulkDeleteResultSchema.parse({ processedCount: '1', deletedCount: '1', conflictCount: '0', missingCount: '0', failedCount: '0', conflicts: [] });
    const result = await administration.executeBulkDelete('primary-cache', metadata.encodedNamespace, {
      previewToken: preview.previewToken, confirmationPhrase: preview.confirmationPhrase,
    });

    expect(result.deletedCount).toBe('1');
    expect(server.requests.slice(1).map(({ path, body: sent }) => ({ path, body: sent }))).toEqual([
      { path: '/api/v1/setups/primary-cache/namespaces/b3JkZXJz/entries/bulk-delete/preview', body: { selection: { type: 'EXPLICIT', targets: [{ key: 'order:1', version: metadata.version }] } } },
      { path: '/api/v1/setups/primary-cache/namespaces/b3JkZXJz/entries/bulk-delete/execute', body: { previewToken: 'p'.repeat(32), confirmationPhrase: 'DELETE 1 ENTRIES' } },
    ]);
  });

  it('rejects malformed mutation success payloads', async () => {
    const administration = await client();
    body = invalidBody({ applied: true, created: false, version: 3, updatedAt: metadata.updatedAt, ttl: metadata.ttl });
    await expect(administration.setEntry('primary-cache', metadata.encodedNamespace, metadata.encodedKey, {
      value: { type: 'STRING', text: 'hello' }, ttlMode: 'REMOVE', ttlMillis: null, setMode: 'UPSERT',
    })).rejects.toMatchObject({ code: 'RESPONSE_CONTRACT_INVALID', status: 502 });
  });
});
