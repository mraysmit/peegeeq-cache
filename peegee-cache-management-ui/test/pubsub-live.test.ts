import { afterEach, beforeEach, describe, expect, it } from 'vitest';

import { FetchSseTransport, SseFrameDecoder } from '@src/api/live-transport';
import { currentSessionSchema } from '@src/api/protocol-schemas';
import { PubSubClient } from '@src/api/pubsub-client';
import { publishAcceptedSchema, subscriptionSummarySchema } from '@src/api/pubsub-schemas';
import { SessionClient } from '@src/api/session-client';
import { route, startLoopbackServer, type LoopbackServer } from './support/loopback-server';

const session = currentSessionSchema.parse({
  user: 'operator', roles: ['viewer', 'operator'], serverVersion: '1', apiVersion: 'v1', authenticationMode: 'LOCAL_TOKEN', csrfToken: 'c'.repeat(43),
  sessionIdleExpiresAt: '2099-01-01T00:00:00Z', sessionExpiresAt: '2099-01-01T01:00:00Z', features: { setupRegistration: true, sensitiveReveal: true },
});

describe('U7 pub/sub protocol and SSE framing', () => {
  let server: LoopbackServer;
  let sessionClient: SessionClient;

  beforeEach(async () => {
    server = await startLoopbackServer((request, respond) => {
      if (route('GET', '/api/v1/session', request)) return respond.json(200, session);
      if (route('POST', '/api/v1/setups/setup/pubsub/subscriptions', request)) {
        return respond.json(201, subscriptionSummarySchema.parse({ subscriptionId: 'sub-1', channel: 'orders', streamPath: '/api/v1/setups/setup/pubsub/subscriptions/sub-1/stream', bufferLimit: 20, createdAt: '2026-08-29T10:00:00Z', expiresAt: '2099-08-29T11:00:00Z' }));
      }
      if (route('POST', '/api/v1/setups/setup/pubsub/publish', request)) return respond.json(202, publishAcceptedSchema.parse({ accepted: true, publishedAt: '2026-08-29T10:01:00Z' }));
      if (route('DELETE', '/api/v1/setups/setup/pubsub/subscriptions/sub-1', request)) return respond.noContent();
      if (route('GET', '/stream', request)) return respond.sse([]);
      return respond.problem(404, 'NOT_FOUND', `no fixture for ${request.method} ${request.path}`);
    });
    sessionClient = new SessionClient(server.baseUrl);
    await sessionClient.load();
  });

  afterEach(async () => {
    sessionClient.clear();
    await server.close();
  });

  it('creates/stops owned subscriptions and never describes publish acceptance as delivery', async () => {
    const client = new PubSubClient(sessionClient);
    const subscription = await client.createSubscription('setup', 'orders', 20);
    const accepted = await client.publish('setup', 'orders', '{"id":1}', 'application/json');
    await client.deleteSubscription('setup', subscription.subscriptionId);
    expect(accepted).toEqual({ accepted: true, publishedAt: '2026-08-29T10:01:00Z' });
    expect(server.requests.slice(1).map((request) => ({ method: request.method, path: request.path, body: request.body }))).toEqual([
      { method: 'POST', path: '/api/v1/setups/setup/pubsub/subscriptions', body: { channel: 'orders', bufferLimit: 20 } },
      { method: 'POST', path: '/api/v1/setups/setup/pubsub/publish', body: { channel: 'orders', payload: '{"id":1}', contentType: 'application/json' } },
      { method: 'DELETE', path: '/api/v1/setups/setup/pubsub/subscriptions/sub-1', body: undefined },
    ]);
    expect(server.requests.slice(1).every((request) => request.headers['x-peegeeq-csrf'] === 'c'.repeat(43))).toBe(true);
  });

  it('rejects an out-of-range buffer limit and an invalid channel before any request leaves the browser', async () => {
    const client = new PubSubClient(sessionClient);
    await expect(client.createSubscription('setup', 'orders', 0)).rejects.toMatchObject({ code: 'VALIDATION_FAILED' });
    await expect(client.createSubscription('setup', '', 20)).rejects.toMatchObject({ code: 'VALIDATION_FAILED' });
    expect(server.requests.filter((request) => request.method === 'POST')).toHaveLength(0);
  });

  it('decodes split SSE frames, comments, nullable content type, and monotonic resume IDs', () => {
    const decoder = new SseFrameDecoder();
    expect(decoder.feed(': heartbeat\n\nid: 41\nevent: pubsub.message\ndata: {"messageId":"m1","channel":"orders",')).toEqual([]);
    expect(decoder.feed('"contentType":null,"payloadBytes":7,"receivedAt":"2026-08-29T10:01:00Z","payloadState":"MASKED"}\n\n')).toEqual([{ id: '41', event: 'pubsub.message', data: '{"messageId":"m1","channel":"orders","contentType":null,"payloadBytes":7,"receivedAt":"2026-08-29T10:01:00Z","payloadState":"MASKED"}' }]);
  });

  it('opens the SSE stream with the event-stream accept header and reports the connection lifecycle', async () => {
    const states: string[] = [];
    const stream = new FetchSseTransport().connect(`${server.baseUrl}/stream`, { onMessage: () => undefined, onState: (state) => states.push(state) });
    try {
      await expect.poll(() => states.slice(0, 3)).toEqual(['CONNECTING', 'CONNECTED', 'STALE']);
      const opened = server.requests.find((request) => request.path === '/stream');
      expect(opened?.headers.accept).toBe('text/event-stream');
      expect(opened?.headers['last-event-id']).toBeUndefined();
    } finally {
      stream.stop();
    }
  });
});
