import { z } from 'zod';

import { activityEventSchema } from './inspection-schemas';
import { utcInstantSchema } from './protocol-schemas';
import { setupHealthSchema } from './setup-schemas';

const envelope = {
  eventId: z.string().min(1).max(256),
  occurredAt: utcInstantSchema,
  setupId: z.string().min(1).max(64),
};

export const monitoringEnvelopeSchema = z.discriminatedUnion('type', [
  z.strictObject({ ...envelope, type: z.literal('connection.ready'), data: z.strictObject({ connectedAt: utcInstantSchema }) }),
  z.strictObject({ ...envelope, type: z.literal('setup.state.changed'), data: z.strictObject({ state: z.enum(['CONNECTING', 'CONNECTED', 'DETACHING', 'DETACHED', 'UNHEALTHY']) }) }),
  z.strictObject({ ...envelope, type: z.literal('health.changed'), data: setupHealthSchema }),
  z.strictObject({ ...envelope, type: z.literal('activity.created'), data: activityEventSchema }),
  z.strictObject({ ...envelope, type: z.literal('resource.changed'), data: z.strictObject({
    resourceType: z.enum(['CACHE_ENTRY', 'COUNTER', 'LOCK', 'SUBSCRIPTION', 'PUBSUB_MESSAGE']),
    namespace: z.string().max(128).nullable(),
    identifier: z.string().max(1_024).nullable(),
    action: z.string().min(1).max(128),
    resultingVersion: z.string().regex(/^(?:0|[1-9][0-9]*)$/u).nullable(),
    actor: z.string().min(1).max(256),
    outcome: z.enum(['SUCCEEDED', 'REJECTED', 'FAILED', 'UNKNOWN']),
    correlationId: z.string().min(1).max(128),
  }) }),
  z.strictObject({ ...envelope, type: z.literal('stream.reset'), data: z.strictObject({ oldestAvailableEventId: z.string().min(1).max(256).nullable() }) }),
  z.strictObject({ ...envelope, type: z.literal('server.shutting_down'), data: z.strictObject({ reason: z.string().min(1).max(256) }) }),
]).superRefine((value, context) => {
  if (value.type === 'activity.created' && value.data.setupId !== value.setupId) {
    context.addIssue({ code: 'custom', path: ['data', 'setupId'], message: 'Activity scope does not match its WebSocket envelope' });
  }
});

export type MonitoringEnvelope = z.infer<typeof monitoringEnvelopeSchema>;
export type MonitoringConnectionState = 'CONNECTING' | 'CONNECTED' | 'STALE' | 'STOPPED';
export interface MonitoringSocket { stop(): void; }
export class BrowserMonitoringSocket {
  connect(setupId: string, onEvent: (event: MonitoringEnvelope) => void, onState: (state: MonitoringConnectionState) => void): MonitoringSocket {
    let socket: InstanceType<typeof globalThis.WebSocket> | undefined;
    let stopped = false;
    let attempts = 0;
    let lastEventId: string | undefined;
    let timer: number | undefined;
    const seen = new Set<string>();
    const open = () => {
      if (stopped) return;
      timer = undefined;
      if (!globalThis.navigator.onLine) { onState('STALE'); return; }
      onState(attempts === 0 ? 'CONNECTING' : 'STALE');
      const scheme = globalThis.location.protocol === 'https:' ? 'wss:' : 'ws:';
      const query = new globalThis.URLSearchParams({ setupId });
      if (lastEventId !== undefined) query.set('afterEventId', lastEventId);
      const current = new globalThis.WebSocket(`${scheme}//${globalThis.location.host}/ws/monitoring?${query.toString()}`);
      socket = current;
      current.addEventListener('open', () => { attempts = 0; onState('CONNECTED'); });
      current.addEventListener('message', (message) => {
        if (typeof message.data !== 'string') return;
        let decoded: unknown;
        try { decoded = JSON.parse(message.data) as unknown; } catch { return; }
        const parsed = monitoringEnvelopeSchema.safeParse(decoded);
        if (!parsed.success || parsed.data.setupId !== setupId || seen.has(parsed.data.eventId)) return;
        seen.add(parsed.data.eventId);
        if (seen.size > 1_000) seen.delete(seen.values().next().value as string);
        lastEventId = parsed.data.eventId;
        onEvent(parsed.data);
        if (parsed.data.type === 'server.shutting_down') { stopped = true; socket?.close(); onState('STOPPED'); }
      });
      current.addEventListener('close', () => { if (socket === current) socket = undefined; schedule(); });
      current.addEventListener('error', () => current.close());
    };
    const schedule = () => {
      if (stopped) return;
      onState('STALE');
      if (attempts >= 6 || !globalThis.navigator.onLine || timer !== undefined) return;
      const delay = Math.min(30_000, 500 * (2 ** attempts)) + Math.floor(Math.random() * 251);
      attempts += 1;
      timer = window.setTimeout(open, delay);
    };
    const offline = () => {
      if (stopped) return;
      if (timer !== undefined) { window.clearTimeout(timer); timer = undefined; }
      onState('STALE');
      const current = socket; socket = undefined; current?.close();
    };
    const online = () => {
      if (stopped || socket !== undefined || timer !== undefined) return;
      open();
    };
    window.addEventListener('offline', offline);
    window.addEventListener('online', online);
    open();
    return { stop: () => { stopped = true; if (timer !== undefined) window.clearTimeout(timer); window.removeEventListener('offline', offline); window.removeEventListener('online', online); socket?.close(); onState('STOPPED'); } };
  }
}
