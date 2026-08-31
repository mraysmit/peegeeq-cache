// @vitest-environment node

import { createServer, type Server } from 'node:http';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { FetchSseTransport } from '@src/api/live-transport';

describe('U7 Pub/Sub live HTTP transport', () => {
  let server: Server;
  const lifecycle = new globalThis.EventTarget();

  beforeEach(() => {
    Object.defineProperty(globalThis, 'navigator', { configurable: true, value: { onLine: true } });
    Object.defineProperty(globalThis, 'window', {
      configurable: true,
      value: {
        addEventListener: lifecycle.addEventListener.bind(lifecycle),
        removeEventListener: lifecycle.removeEventListener.bind(lifecycle),
        clearTimeout: globalThis.clearTimeout.bind(globalThis),
        setTimeout: globalThis.setTimeout.bind(globalThis),
      },
    });
  });

  afterEach(async () => {
    delete (globalThis as { navigator?: unknown }).navigator;
    delete (globalThis as { window?: unknown }).window;
    if (server.listening) await new Promise<void>((resolve, reject) => server.close((error) => error ? reject(error) : resolve()));
  });

  it('resumes with Last-Event-ID, discards replayed IDs, and surfaces reset frames', async () => {
    const resumeHeaders: Array<string | undefined> = [];
    let connection = 0;
    const message = (id: string, messageId: string) => `id: ${id}\nevent: pubsub.message\ndata: {"messageId":"${messageId}","channel":"orders","contentType":null,"payloadBytes":7,"receivedAt":"2026-08-29T10:01:00Z","payloadState":"MASKED"}\n\n`;
    server = createServer((request, response) => {
      const resumeHeader = request.headers['last-event-id'];
      resumeHeaders.push(Array.isArray(resumeHeader) ? resumeHeader[0] : resumeHeader);
      response.writeHead(200, { 'content-type': 'text/event-stream' });
      response.end(connection++ === 0
        ? message('41', 'm1')
        : `${message('41', 'm1')}id: 42\nevent: reset\ndata: {"reason":"bounded history expired"}\n\n${message('43', 'm2')}`);
    });
    await new Promise<void>((resolve) => server.listen(0, '127.0.0.1', resolve));
    const address = server.address();
    if (address === null || typeof address === 'string') throw new Error('fixture did not bind');

    const messages: string[] = [];
    const resets: string[] = [];
    const errors: string[] = [];
    const stream = new FetchSseTransport().connect(`http://127.0.0.1:${address.port}/stream`, {
      onMessage: (value) => messages.push(value.messageId),
      onReset: (reason) => resets.push(reason),
      onError: (messageText) => errors.push(messageText),
    });
    try {
      await vi.waitFor(() => expect({ messages, resets, resumeHeaders }).toEqual({
        messages: ['m1', 'm2'],
        resets: ['bounded history expired'],
        resumeHeaders: [undefined, '41'],
      }), { timeout: 3_000 });
      expect(errors).toEqual([]);
    } finally {
      stream.stop();
    }
  });
});
