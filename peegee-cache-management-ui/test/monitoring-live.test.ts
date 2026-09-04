// @vitest-environment node
/* global Event, EventTarget */

import { afterEach, beforeEach, describe, expect, it } from 'vitest';

import { MetricsSseTransport } from '@src/api/live-transport';
import { BrowserMonitoringSocket, monitoringEnvelopeSchema } from '@src/api/monitoring-live';
import { startLoopbackServer, type LoopbackServer } from './support/loopback-server';

describe('U8 monitoring live transport', () => {
  let server: LoopbackServer;
  let lifecycle: EventTarget;

  beforeEach(async () => {
    lifecycle = new EventTarget();
    Object.defineProperty(globalThis, 'localStorage', { configurable: true, value: { length: 0 } });
    Object.defineProperty(globalThis, 'navigator', { configurable: true, value: { onLine: true } });
    Object.defineProperty(globalThis, 'window', {
      configurable: true,
      value: {
        addEventListener: lifecycle.addEventListener.bind(lifecycle),
        clearTimeout: globalThis.clearTimeout.bind(globalThis),
        dispatchEvent: lifecycle.dispatchEvent.bind(lifecycle),
        removeEventListener: lifecycle.removeEventListener.bind(lifecycle),
        setTimeout: globalThis.setTimeout.bind(globalThis),
      },
    });
    server = await startLoopbackServer((request, respond) => {
      // A stream that the server ends immediately after the handshake.
      if (request.path === '/metrics') return respond.sse([': connected\n\n']);
      return respond.problem(404, 'NOT_FOUND', `no fixture for ${request.method} ${request.path}`);
    });
  });

  afterEach(async () => {
    delete (globalThis as { localStorage?: unknown }).localStorage;
    delete (globalThis as { navigator?: unknown }).navigator;
    delete (globalThis as { window?: unknown }).window;
    await server.close();
  });

  it('exposes a stoppable browser transport without persisting event content', () => {
    expect(new BrowserMonitoringSocket()).toBeInstanceOf(BrowserMonitoringSocket);
    expect(localStorage.length).toBe(0);
  });

  it('strictly validates event-specific payloads and rejects cross-scope activity', () => {
    const base = { eventId: 'event-1', occurredAt: '2026-08-29T10:03:00Z', setupId: 'primary-cache' };
    expect(monitoringEnvelopeSchema.safeParse({ ...base, type: 'resource.changed', data: { resourceType: 'CACHE_ENTRY', namespace: 'orders', identifier: 'secret-key', action: 'UPDATED', resultingVersion: '2', actor: 'operator', outcome: 'SUCCEEDED', correlationId: 'corr-1' } }).success).toBe(true);
    expect(monitoringEnvelopeSchema.safeParse({ ...base, type: 'resource.changed', data: { resourceType: 'CACHE_ENTRY', namespace: 'orders', identifier: 'secret-key', action: 'UPDATED', resultingVersion: '2', actor: 'operator', outcome: 'SUCCEEDED', correlationId: 'corr-1', payload: 'forbidden' } }).success).toBe(false);
    expect(monitoringEnvelopeSchema.safeParse({ ...base, type: 'activity.created', data: { eventId: 'activity-1', occurredAt: base.occurredAt, actor: 'operator', action: 'ENTRY_UPDATED', outcome: 'SUCCEEDED', setupId: 'another-setup', namespace: 'orders', resource: { type: 'CACHE_ENTRY', identifier: 'key' }, summary: 'Entry updated', correlationId: 'corr-1' } }).success).toBe(false);
  });

  it('marks metrics stale immediately when a connected stream ends', async () => {
    const states: string[] = [];
    const errors: string[] = [];
    const stream = new MetricsSseTransport().connect(`${server.baseUrl}/metrics`, {
      onError: (message) => errors.push(message),
      onState: (state) => states.push(state),
    });
    try {
      await expect.poll(() => ({ errors, states: states.slice(0, 3) }), { timeout: 5_000 })
        .toEqual({ errors: [], states: ['CONNECTING', 'CONNECTED', 'STALE'] });
      expect(server.requests).not.toHaveLength(0);
      expect(server.requests[0]!.headers.accept).toBe('text/event-stream');
    } finally {
      stream.stop();
    }
  });

  it('reconnects when online follows offline before the aborted stream settles', async () => {
    server.use((request, respond) => {
      if (request.path === '/metrics') return respond.sse([': connected\n\n'], { keepOpen: true });
      return respond.problem(404, 'NOT_FOUND', `no fixture for ${request.method} ${request.path}`);
    });
    const states: string[] = [];
    const stream = new MetricsSseTransport().connect(`${server.baseUrl}/metrics`, { onState: (state) => states.push(state) });
    try {
      await expect.poll(() => server.requests.length).toBe(1);
      await expect.poll(() => states.at(-1)).toBe('CONNECTED');
      Object.defineProperty(globalThis.navigator, 'onLine', { configurable: true, value: false });
      globalThis.window.dispatchEvent(new Event('offline'));
      expect(states.at(-1)).toBe('STALE');
      Object.defineProperty(globalThis.navigator, 'onLine', { configurable: true, value: true });
      globalThis.window.dispatchEvent(new Event('online'));
      await expect.poll(() => server.requests.length).toBe(2);
      await expect.poll(() => states.at(-1)).toBe('CONNECTED');
    } finally {
      stream.stop();
    }
  });
});
