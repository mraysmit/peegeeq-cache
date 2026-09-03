import { Buffer } from 'node:buffer';
import { createServer, type Server } from 'node:http';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { PubSubClient } from '@src/api/pubsub-client';
import { FetchSseTransport, SseFrameDecoder } from '@src/api/live-transport';
import { SessionClient } from '@src/api/session-client';

const session = { user: 'operator', roles: ['viewer', 'operator'], serverVersion: '1', apiVersion: 'v1', authenticationMode: 'LOCAL_TOKEN', csrfToken: 'c'.repeat(43), sessionIdleExpiresAt: '2099-01-01T00:00:00Z', sessionExpiresAt: '2099-01-01T01:00:00Z', features: { setupRegistration: true, sensitiveReveal: true } } as const;

describe('U7 pub/sub protocol and SSE framing', () => {
  let server: Server;
  let baseUrl: string;
  let responseBody: unknown;
  let requests: Array<{ method?: string; url?: string; body?: unknown }>;

  beforeEach(async () => {
    requests = [];
    responseBody = { subscriptionId: 'sub-1', channel: 'orders', streamPath: '/api/v1/setups/setup/pubsub/subscriptions/sub-1/stream', bufferLimit: 20, createdAt: '2026-08-29T10:00:00Z', expiresAt: '2099-08-29T11:00:00Z' };
    server = createServer((request, response) => { const chunks: Buffer[] = []; request.on('data', (chunk: Buffer) => chunks.push(chunk)); request.on('end', () => { const text = Buffer.concat(chunks).toString('utf8'); requests.push({ method: request.method, url: request.url, body: text === '' ? undefined : JSON.parse(text) }); const body = request.url === '/api/v1/session' ? session : responseBody; response.writeHead(request.method === 'DELETE' ? 204 : request.url?.endsWith('/subscriptions') ? 201 : 200, { 'content-type': 'application/json', 'cache-control': 'no-store, no-cache, must-revalidate', pragma: 'no-cache' }); response.end(request.method === 'DELETE' ? '' : JSON.stringify(body)); }); });
    await new Promise<void>((resolve) => server.listen(0, '127.0.0.1', resolve));
    const address = server.address(); if (address === null || typeof address === 'string') throw new Error('fixture did not bind'); baseUrl = `http://127.0.0.1:${address.port}`;
  });
  afterEach(async () => { await new Promise<void>((resolve, reject) => server.close((error) => error ? reject(error) : resolve())); });

  it('creates/stops owned subscriptions and never describes publish acceptance as delivery', async () => {
    const sessionClient = new SessionClient(baseUrl); await sessionClient.load(); const client = new PubSubClient(sessionClient);
    const subscription = await client.createSubscription('setup', 'orders', 20);
    responseBody = { accepted: true, publishedAt: '2026-08-29T10:01:00Z' };
    const accepted = await client.publish('setup', 'orders', '{"id":1}', 'application/json');
    await client.deleteSubscription('setup', subscription.subscriptionId);
    expect(accepted).toEqual({ accepted: true, publishedAt: '2026-08-29T10:01:00Z' });
    expect(requests.slice(1)).toEqual([
      { method: 'POST', url: '/api/v1/setups/setup/pubsub/subscriptions', body: { channel: 'orders', bufferLimit: 20 } },
      { method: 'POST', url: '/api/v1/setups/setup/pubsub/publish', body: { channel: 'orders', payload: '{"id":1}', contentType: 'application/json' } },
      { method: 'DELETE', url: '/api/v1/setups/setup/pubsub/subscriptions/sub-1', body: undefined },
    ]);
  });

  it('decodes split SSE frames, comments, nullable content type, and monotonic resume IDs', () => {
    const decoder = new SseFrameDecoder();
    expect(decoder.feed(': heartbeat\n\nid: 41\nevent: pubsub.message\ndata: {"messageId":"m1","channel":"orders",')).toEqual([]);
    expect(decoder.feed('"contentType":null,"payloadBytes":7,"receivedAt":"2026-08-29T10:01:00Z","payloadState":"MASKED"}\n\n')).toEqual([{ id: '41', event: 'pubsub.message', data: '{"messageId":"m1","channel":"orders","contentType":null,"payloadBytes":7,"receivedAt":"2026-08-29T10:01:00Z","payloadState":"MASKED"}' }]);
  });

  it('opens credentialed SSE in CORS mode so the browser supplies the required Origin header', async () => {
    let requestInit: Parameters<typeof globalThis.fetch>[1];
    vi.stubGlobal('fetch', async (_input: Parameters<typeof globalThis.fetch>[0], init?: Parameters<typeof globalThis.fetch>[1]) => {
      requestInit = init;
      return new globalThis.Response(new globalThis.ReadableStream({ start: (controller) => controller.close() }), { status: 200 });
    });
    const stream = new FetchSseTransport().connect('/stream', { onMessage: () => undefined });
    try {
      await vi.waitFor(() => expect(requestInit).toMatchObject({ credentials: 'include', mode: 'cors' }));
    } finally {
      stream.stop();
      vi.unstubAllGlobals();
    }
  });

});
