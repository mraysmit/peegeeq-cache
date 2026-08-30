import { pubSubMessageMetadataSchema, type PubSubMessageMetadata } from './pubsub-schemas';
import { overviewSchema, runtimeMonitoringSchema, type Overview, type RuntimeMonitoring } from './inspection-schemas';

export interface SseFrame { readonly id?: string; readonly event?: string; readonly data: string; }
export interface LiveStream { stop(): void; }
export interface PubSubStreamHandlers {
  readonly onMessage: (message: PubSubMessageMetadata) => void;
  readonly onState?: (state: 'CONNECTING' | 'CONNECTED' | 'STALE' | 'STOPPED') => void;
  readonly onReset?: (reason: string) => void;
  readonly onError?: (message: string) => void;
}
export interface PubSubStreamPort { connect(path: string, handlers: PubSubStreamHandlers): LiveStream; }
export interface MetricsStreamHandlers { readonly onOverview?: (value: Overview) => void; readonly onRuntime?: (value: RuntimeMonitoring) => void; readonly onState?: PubSubStreamHandlers['onState']; readonly onReset?: (reason: string) => void; readonly onError?: (message: string) => void; }
export interface MetricsStreamPort { connect(path: string, handlers: MetricsStreamHandlers): LiveStream; }

/** Incremental UTF-8-decoded SSE frame parser. Comments and retry fields are ignored. */
export class SseFrameDecoder {
  private buffer = '';

  feed(chunk: string): SseFrame[] {
    this.buffer += chunk.replace(/\r\n/gu, '\n').replace(/\r/gu, '\n');
    const frames: SseFrame[] = [];
    let boundary = this.buffer.indexOf('\n\n');
    while (boundary >= 0) {
      const raw = this.buffer.slice(0, boundary);
      this.buffer = this.buffer.slice(boundary + 2);
      const frame = decodeFrame(raw);
      if (frame !== undefined) frames.push(frame);
      boundary = this.buffer.indexOf('\n\n');
    }
    return frames;
  }

  reset(): void { this.buffer = ''; }
}

export class FetchSseTransport implements PubSubStreamPort {
  connect(path: string, handlers: PubSubStreamHandlers): LiveStream {
    let lastEventId: string | undefined;
    let attempts = 0;
    let stopped = false;
    const seen = new Set<string>();
    let retryTimer: number | undefined;
    let activeController: InstanceType<typeof globalThis.AbortController> | undefined;
    const run = async () => {
      if (stopped) return;
      retryTimer = undefined;
      if (!globalThis.navigator.onLine) { handlers.onState?.('STALE'); return; }
      handlers.onState?.(attempts === 0 ? 'CONNECTING' : 'STALE');
      const controller = new globalThis.AbortController();
      activeController = controller;
      try {
        const headers: Record<string, string> = { accept: 'text/event-stream' };
        if (lastEventId !== undefined) headers['Last-Event-ID'] = lastEventId;
        const response = await fetch(path, { credentials: 'include', headers, mode: 'cors', signal: controller.signal });
        if (!response.ok || response.body === null) throw new Error(`Stream failed with status ${response.status}`);
        handlers.onState?.('CONNECTED'); attempts = 0;
        const decoder = new SseFrameDecoder(); const reader = response.body.getReader(); const textDecoder = new globalThis.TextDecoder();
        while (!stopped) {
          const part = await reader.read(); if (part.done) break;
          for (const frame of decoder.feed(textDecoder.decode(part.value, { stream: true }))) {
            if (frame.id !== undefined) lastEventId = frame.id;
            if (frame.event === 'pubsub.message') {
              if (frame.id !== undefined && seen.has(frame.id)) continue;
              const parsed = pubSubMessageMetadataSchema.safeParse(json(frame.data));
              if (!parsed.success) { handlers.onError?.('The stream returned an incompatible Pub/Sub message'); continue; }
              if (frame.id !== undefined) { seen.add(frame.id); if (seen.size > 1_000) seen.delete(seen.values().next().value as string); }
              handlers.onMessage(parsed.data);
            } else if (frame.event === 'reset') {
              handlers.onReset?.(resetReason(frame.data));
            }
          }
        }
        if (!stopped) schedule();
      } catch (failure: unknown) {
        if (!stopped && !(failure instanceof Error && failure.name === 'AbortError')) {
          handlers.onError?.('Live Pub/Sub connection was interrupted');
          schedule();
        }
      } finally {
        if (activeController === controller) activeController = undefined;
      }
    };
    const schedule = () => {
      if (stopped || retryTimer !== undefined) return;
      handlers.onState?.('STALE');
      if (attempts >= 6 || !globalThis.navigator.onLine) return;
      const delay = Math.min(30_000, 500 * (2 ** attempts)) + Math.floor(Math.random() * 251); attempts += 1;
      retryTimer = window.setTimeout(() => void run(), delay);
    };
    const offline = () => {
      if (stopped) return;
      if (retryTimer !== undefined) { window.clearTimeout(retryTimer); retryTimer = undefined; }
      handlers.onState?.('STALE');
      activeController?.abort();
    };
    const online = () => {
      if (stopped || activeController !== undefined || retryTimer !== undefined) return;
      void run();
    };
    window.addEventListener('offline', offline);
    window.addEventListener('online', online);
    void run();
    return { stop: () => {
      stopped = true;
      activeController?.abort();
      if (retryTimer !== undefined) window.clearTimeout(retryTimer);
      window.removeEventListener('offline', offline);
      window.removeEventListener('online', online);
      handlers.onState?.('STOPPED');
    } };
  }
}

export class MetricsSseTransport implements MetricsStreamPort {
  connect(path: string, handlers: MetricsStreamHandlers): LiveStream {
    return connectValidatedSse(path, (frame) => {
      if (frame.event === 'overview.snapshot') {
        const parsed = overviewSchema.safeParse(json(frame.data)); if (parsed.success) handlers.onOverview?.(parsed.data); else handlers.onError?.('The metrics stream returned an incompatible overview snapshot');
      } else if (frame.event === 'runtime.snapshot') {
        const parsed = runtimeMonitoringSchema.safeParse(json(frame.data)); if (parsed.success) handlers.onRuntime?.(parsed.data); else handlers.onError?.('The metrics stream returned an incompatible runtime snapshot');
      } else if (frame.event === 'reset') handlers.onReset?.(resetReason(frame.data));
    }, handlers);
  }
}

function connectValidatedSse(path: string, onFrame: (frame: SseFrame) => void, handlers: Pick<MetricsStreamHandlers, 'onState' | 'onError'>): LiveStream {
  let stopped = false; let attempts = 0; let lastEventId: string | undefined; let timer: number | undefined; let activeController: InstanceType<typeof globalThis.AbortController> | undefined; const seen = new Set<string>();
  const run = async () => {
    if (stopped) return;
    timer = undefined;
    if (!globalThis.navigator.onLine) { handlers.onState?.('STALE'); return; }
    handlers.onState?.(attempts === 0 ? 'CONNECTING' : 'STALE');
    const controller = new globalThis.AbortController(); activeController = controller;
    try { const headers: Record<string, string> = { accept: 'text/event-stream' }; if (lastEventId !== undefined) headers['Last-Event-ID'] = lastEventId; const response = await fetch(path, { credentials: 'include', headers, mode: 'cors', signal: controller.signal }); if (!response.ok || response.body === null) throw new Error('Metrics stream unavailable'); handlers.onState?.('CONNECTED'); attempts = 0; const reader = response.body.getReader(); const textDecoder = new globalThis.TextDecoder(); const decoder = new SseFrameDecoder(); while (!stopped) { const part = await reader.read(); if (part.done) break; for (const frame of decoder.feed(textDecoder.decode(part.value, { stream: true }))) { if (frame.id !== undefined) lastEventId = frame.id; if (frame.id !== undefined && seen.has(frame.id)) continue; if (frame.id !== undefined) { seen.add(frame.id); if (seen.size > 1_000) seen.delete(seen.values().next().value as string); } onFrame(frame); } } if (!stopped) schedule(); } catch (failure) { if (!stopped && !(failure instanceof Error && failure.name === 'AbortError')) { handlers.onError?.('Live metrics connection was interrupted'); schedule(); } } finally { if (activeController === controller) activeController = undefined; }
  };
  const schedule = () => { if (stopped) return; handlers.onState?.('STALE'); if (attempts >= 6 || !globalThis.navigator.onLine) return; const delay = Math.min(30_000, 500 * (2 ** attempts)) + Math.floor(Math.random() * 251); attempts += 1; timer = window.setTimeout(() => void run(), delay); };
  const offline = () => { if (stopped) return; if (timer !== undefined) { window.clearTimeout(timer); timer = undefined; } handlers.onState?.('STALE'); activeController?.abort(); };
  const online = () => { if (stopped || activeController !== undefined || timer !== undefined) return; void run(); };
  window.addEventListener('offline', offline); window.addEventListener('online', online);
  void run(); return { stop: () => { stopped = true; activeController?.abort(); if (timer !== undefined) window.clearTimeout(timer); window.removeEventListener('offline', offline); window.removeEventListener('online', online); handlers.onState?.('STOPPED'); } };
}

function decodeFrame(raw: string): SseFrame | undefined {
  let id: string | undefined;
  let event: string | undefined;
  const data: string[] = [];
  for (const line of raw.split('\n')) {
    if (line === '' || line.startsWith(':')) continue;
    const colon = line.indexOf(':');
    const field = colon < 0 ? line : line.slice(0, colon);
    let value = colon < 0 ? '' : line.slice(colon + 1);
    if (value.startsWith(' ')) value = value.slice(1);
    if (field === 'id' && !value.includes('\0')) id = value;
    else if (field === 'event') event = value;
    else if (field === 'data') data.push(value);
  }
  if (data.length === 0) return undefined;
  return { ...(id === undefined ? {} : { id }), ...(event === undefined ? {} : { event }), data: data.join('\n') };
}

function json(value: string): unknown { try { return JSON.parse(value) as unknown; } catch { return undefined; } }
function resetReason(value: string): string { const parsed = json(value); return typeof parsed === 'object' && parsed !== null && 'reason' in parsed && typeof parsed.reason === 'string' ? parsed.reason : 'The server reset this bounded stream.'; }
