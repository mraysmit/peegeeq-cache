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
    activeLockCount: '4', expiredEntryCount: '5',
    schemaBytes: { availability: 'AVAILABLE', reason: null, value: '4096' },
  },
  expiry: {
    oldestExpiredRowLagMillis: null, sweeperEnabled: true,
    lastSweepAt: null, lastSweepDeletedRows: '0',
  },
  valueTypeCounts: { STRING: '2' },
  topNamespaces: [],
};

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
      response.writeHead(200, { 'content-type': 'application/json' });
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
