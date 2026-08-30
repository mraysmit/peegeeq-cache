import { describe, expect, it, vi } from 'vitest';
import { MetricsSseTransport } from '@src/api/live-transport';
import { BrowserMonitoringSocket, monitoringEnvelopeSchema } from '@src/api/monitoring-live';

describe('U8 monitoring live transport', () => {
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
    vi.stubGlobal('fetch', async () => new globalThis.Response(
      new globalThis.ReadableStream({ start: (controller) => controller.close() }),
      { status: 200 },
    ));
    const states: string[] = [];
    const stream = new MetricsSseTransport().connect('/metrics', { onState: (state) => states.push(state) });
    try {
      await vi.waitFor(() => expect(states).toContain('CONNECTED'));
      await Promise.resolve();
      expect(states).toEqual(['CONNECTING', 'CONNECTED', 'STALE']);
    } finally {
      stream.stop();
      vi.unstubAllGlobals();
    }
  });
});
