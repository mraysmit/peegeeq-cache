import { createServer, type IncomingMessage, type Server, type ServerResponse } from 'node:http';

import { managementProblemSchema } from '@src/api/protocol-schemas';

/**
 * Shared loopback HTTP fixture for the management UI test suite.
 *
 * This is a real `node:http` server on an ephemeral 127.0.0.1 port, not a mock: the production
 * clients perform real serialization, cookies, headers, preconditions, and error mapping against
 * it. Every JSON body handed to `respond.json(...)` must already have been produced by a
 * production Zod schema (`<name>Schema.parse(...)`); `zod-fixture.guard.test.ts` enforces that
 * statically. A body the strict schema must reject is declared with `invalidBody(...)` so the
 * negative intent is explicit.
 *
 * See PEEGEEQ_CACHE_TEST_COVERAGE_AND_TDD_APPROACH.md §5 "Management UI component and client tests".
 */

export interface LoopbackRequest {
  readonly method: string;
  readonly url: string;
  readonly path: string;
  readonly query: URLSearchParams;
  readonly headers: IncomingMessage['headers'];
  readonly body: unknown;
  readonly rawBody: string;
}

export interface LoopbackRespond {
  json(status: number, body: unknown, headers?: Record<string, string>): void;
  noContent(headers?: Record<string, string>): void;
  problem(status: number, code: string, detail: string, extra?: { title?: string; correlationId?: string; fieldErrors?: Array<{ field: string; message: string }> }): void;
  raw(status: number, headers: Record<string, string>, body: string): void;
  /** Write an SSE frame sequence and keep the socket open until `end()` or the client aborts. */
  sse(frames: readonly string[], options?: { keepOpen?: boolean }): void;
}

export type LoopbackHandler = (request: LoopbackRequest, respond: LoopbackRespond) => void | Promise<void>;

export interface LoopbackServer {
  readonly baseUrl: string;
  readonly port: number;
  /** Every request received, in order. */
  readonly requests: readonly LoopbackRequest[];
  /** Replace the handler mid-test (e.g. flip a route to a failure). */
  use(handler: LoopbackHandler): void;
  close(): Promise<void>;
}

const SESSION_COOKIE = 'peegeeq-session=fixture; Path=/; HttpOnly; SameSite=Strict';

export async function startLoopbackServer(handler: LoopbackHandler): Promise<LoopbackServer> {
  let current = handler;
  const requests: LoopbackRequest[] = [];
  const openResponses = new Set<ServerResponse>();

  const server: Server = createServer((incoming, outgoing) => {
    openResponses.add(outgoing);
    outgoing.on('close', () => openResponses.delete(outgoing));
    let rawBody = '';
    incoming.setEncoding('utf8');
    incoming.on('data', (chunk: string) => { rawBody += chunk; });
    incoming.on('end', () => {
      const url = new URL(incoming.url ?? '/', 'http://127.0.0.1');
      let body: unknown;
      if (rawBody !== '') {
        try { body = JSON.parse(rawBody) as unknown; } catch { body = rawBody; }
      }
      const request: LoopbackRequest = {
        method: incoming.method ?? 'GET',
        url: incoming.url ?? '/',
        path: url.pathname,
        query: url.searchParams,
        headers: incoming.headers,
        body,
        rawBody,
      };
      requests.push(request);
      const respond: LoopbackRespond = {
        json: (status, payload, headers = {}) => {
          outgoing.writeHead(status, { 'content-type': 'application/json', 'set-cookie': SESSION_COOKIE, ...headers });
          outgoing.end(JSON.stringify(payload));
        },
        noContent: (headers = {}) => {
          outgoing.writeHead(204, headers);
          outgoing.end();
        },
        problem: (status, code, detail, extra = {}) => {
          const problem = managementProblemSchema.parse({
            type: `https://peegeeq.dev/problems/${code.toLowerCase().replace(/_/g, '-')}`,
            title: extra.title ?? code,
            status,
            code,
            detail,
            instance: url.pathname,
            correlationId: extra.correlationId ?? 'corr-fixture',
            fieldErrors: extra.fieldErrors ?? [],
          });
          outgoing.writeHead(status, { 'content-type': 'application/problem+json' });
          outgoing.end(JSON.stringify(problem));
        },
        raw: (status, headers, payload) => {
          outgoing.writeHead(status, headers);
          outgoing.end(payload);
        },
        sse: (frames, options = {}) => {
          outgoing.writeHead(200, { 'content-type': 'text/event-stream', 'cache-control': 'no-store' });
          for (const frame of frames) outgoing.write(frame);
          if (!options.keepOpen) outgoing.end();
        },
      };
      void Promise.resolve(current(request, respond)).catch((failure: unknown) => {
        if (!outgoing.headersSent) respond.problem(500, 'FIXTURE_HANDLER_FAILED', failure instanceof Error ? failure.message : String(failure));
        else outgoing.end();
      });
    });
  });

  await new Promise<void>((resolve, reject) => {
    server.once('error', reject);
    server.listen(0, '127.0.0.1', () => resolve());
  });
  const address = server.address();
  if (address === null || typeof address === 'string') throw new Error('loopback fixture did not bind to a TCP port');

  return {
    baseUrl: `http://127.0.0.1:${address.port}`,
    port: address.port,
    requests,
    use: (next) => { current = next; },
    close: () => new Promise<void>((resolve, reject) => {
      for (const response of openResponses) response.destroy();
      server.close((error) => (error ? reject(error) : resolve()));
    }),
  };
}

/**
 * Declares a response body that the production schema must reject. The identity function exists
 * so the negative-path intent is visible at the call site and greppable by the fixture guard.
 */
export function invalidBody<T>(body: T): T {
  return body;
}

/** Route helper: match `method` and an exact path or a pattern. */
export function route(method: string, path: string | RegExp, request: LoopbackRequest): boolean {
  if (request.method !== method) return false;
  return typeof path === 'string' ? request.path === path : path.test(request.path);
}
