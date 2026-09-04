import { afterEach, beforeEach, describe, expect, it } from 'vitest';

import { revealedEntryValueSchema } from '@src/api/inspection-schemas';
import { currentSessionSchema } from '@src/api/protocol-schemas';
import { ManagementClientError, SessionClient } from '@src/api/session-client';
import { invalidBody, route, startLoopbackServer, type LoopbackServer } from './support/loopback-server';

const sessionBody = currentSessionSchema.parse({
  user: 'local-operator',
  roles: ['viewer', 'operator'],
  serverVersion: '0.1.0-SNAPSHOT',
  apiVersion: 'v1',
  authenticationMode: 'LOCAL_TOKEN',
  csrfToken: 'csrf-token-with-at-least-thirty-two-characters',
  sessionIdleExpiresAt: '2099-01-01T00:00:00Z',
  sessionExpiresAt: '2099-01-01T01:00:00Z',
  features: {
    setupRegistration: true,
    sensitiveReveal: true,
  },
});

const revealed = revealedEntryValueSchema.parse({
  key: 'order:1', version: '3', value: { type: 'STRING', text: 'transient-value' }, revealedAt: '2026-08-29T10:01:30Z', autoHideAfterMillis: 60_000,
});

const NO_STORE = { 'cache-control': 'private, no-store, no-cache, must-revalidate', pragma: 'no-cache' } as const;

describe('U1 session client', () => {
  let server: LoopbackServer;

  beforeEach(async () => {
    localStorage.clear();
    sessionStorage.clear();
    server = await startLoopbackServer((request, respond) => {
      if (route('GET', '/api/v1/session', request)) return respond.json(200, sessionBody);
      return respond.problem(500, 'FIXTURE_UNROUTED', `no fixture for ${request.method} ${request.path}`);
    });
  });

  afterEach(async () => { await server.close(); });

  it('exchanges the bootstrap token and keeps token and CSRF material out of browser storage', async () => {
    const bootstrapToken = 'one-time-bootstrap-secret';
    server.use((request, respond) => {
      if (route('POST', '/api/v1/session/local', request)) return respond.json(200, sessionBody);
      if (route('DELETE', '/api/v1/session/local', request)) return respond.noContent();
      return respond.problem(500, 'FIXTURE_UNROUTED', request.path);
    });
    const client = new SessionClient(server.baseUrl);

    const session = await client.exchangeLocalToken(bootstrapToken);
    await client.logoutLocal();

    expect(session.user).toBe('local-operator');
    expect(session).not.toHaveProperty('csrfToken');
    expect(server.requests[0]!.body).toEqual({ token: bootstrapToken });
    expect(server.requests[1]!.headers['x-peegeeq-csrf']).toBe(sessionBody.csrfToken);
    expect(JSON.stringify({ localStorage, sessionStorage })).not.toContain(bootstrapToken);
    expect(JSON.stringify({ localStorage, sessionStorage })).not.toContain(sessionBody.csrfToken);
  });

  it('rejects malformed success payloads at the protocol boundary', async () => {
    server.use((_request, respond) => respond.json(200, invalidBody({ ...sessionBody, unexpected: true })));
    const client = new SessionClient(server.baseUrl);

    await expect(client.load()).rejects.toMatchObject({
      code: 'RESPONSE_CONTRACT_INVALID',
    } satisfies Partial<ManagementClientError>);
  });

  it('maps an unauthenticated session, an unreadable body, and a non-problem failure to typed errors', async () => {
    server.use((request, respond) => {
      if (route('GET', '/api/v1/session', request)) return respond.problem(401, 'SESSION_REQUIRED', 'Authenticate first', { correlationId: 'corr-401' });
      if (route('GET', '/unreadable', request)) return respond.raw(200, { 'content-type': 'application/json' }, '{not json');
      if (route('GET', '/plain-failure', request)) return respond.raw(500, { 'content-type': 'text/plain' }, 'upstream exploded');
      return respond.raw(500, { 'content-type': 'application/json' }, '{"error":"not a problem document"}');
    });
    const client = new SessionClient(server.baseUrl);

    await expect(client.load()).rejects.toMatchObject({ status: 401, code: 'SESSION_REQUIRED', message: 'Authenticate first', correlationId: 'corr-401' });
    await expect(client.requestJson('/unreadable')).rejects.toMatchObject({ status: 200, code: 'RESPONSE_BODY_INVALID' });
    await expect(client.requestJson('/plain-failure')).rejects.toMatchObject({ status: 500, code: 'RESPONSE_BODY_INVALID' });
    await expect(client.requestJson('/exploded')).rejects.toMatchObject({ status: 500, code: 'HTTP_REQUEST_FAILED' });
    await expect(client.logoutLocal()).rejects.toMatchObject({ status: 401, code: 'SESSION_STATE_MISSING' });
    await expect(client.requestJson('/mutation', { method: 'POST' })).rejects.toMatchObject({ status: 401, code: 'SESSION_STATE_MISSING' });
  });

  it('retains the CSRF proof when logout fails so termination can be retried', async () => {
    let logoutAttempts = 0;
    server.use((request, respond) => {
      if (route('GET', '/api/v1/session', request)) return respond.json(200, sessionBody);
      logoutAttempts++;
      if (logoutAttempts === 1) return respond.problem(503, 'LOGOUT_UNAVAILABLE', 'Logout is temporarily unavailable', { correlationId: 'logout-correlation' });
      return respond.noContent();
    });
    const client = new SessionClient(server.baseUrl);

    await client.load();
    await expect(client.logoutLocal()).rejects.toMatchObject({ code: 'LOGOUT_UNAVAILABLE', correlationId: 'logout-correlation' });
    await expect(client.logoutLocal()).resolves.toBeUndefined();

    expect(server.requests.slice(1).map((request) => request.headers['x-peegeeq-csrf'])).toEqual([sessionBody.csrfToken, sessionBody.csrfToken]);
  });

  it('drops the CSRF proof after an unauthorized response so the next mutation cannot reuse it', async () => {
    server.use((request, respond) => {
      if (route('GET', '/api/v1/session', request)) return respond.json(200, sessionBody);
      return respond.problem(401, 'SESSION_EXPIRED', 'The session expired');
    });
    const client = new SessionClient(server.baseUrl);
    await client.load();
    await expect(client.requestJson('/mutation', { method: 'POST', body: { any: 'thing' } })).rejects.toMatchObject({ status: 401, code: 'SESSION_EXPIRED' });
    await expect(client.requestJson('/mutation', { method: 'POST' })).rejects.toMatchObject({ code: 'SESSION_STATE_MISSING' });
    expect(server.requests.filter((request) => request.method === 'POST')).toHaveLength(1);
  });

  it('accepts sensitive JSON only when the response is explicitly non-cacheable', async () => {
    server.use((request, respond) => {
      if (route('GET', '/api/v1/session', request)) return respond.json(200, sessionBody);
      return respond.json(200, revealed, NO_STORE);
    });
    const client = new SessionClient(server.baseUrl);

    await client.load();
    await expect(client.requestSensitiveJson('/reveal', {
      body: { reason: 'incident review' }, method: 'POST',
    })).resolves.toEqual(revealed);

    const reveal = server.requests.at(-1)!;
    expect(reveal.rawBody).toBe('{"reason":"incident review"}');
    expect(reveal.headers['content-type']).toBe('application/json');
    expect(reveal.headers['x-peegeeq-csrf']).toBe(sessionBody.csrfToken);
    expect(JSON.stringify({ localStorage, sessionStorage })).not.toContain('transient-value');
  });

  it.each<{ headers: Record<string, string>; description: string }>([
    { headers: { pragma: 'no-cache' }, description: 'missing Cache-Control no-store' },
    { headers: { 'cache-control': 'no-store' }, description: 'missing Pragma no-cache' },
  ])('rejects a sensitive response with $description', async ({ headers }) => {
    server.use((request, respond) => {
      if (route('GET', '/api/v1/session', request)) return respond.json(200, sessionBody);
      return respond.json(200, revealed, headers);
    });
    const client = new SessionClient(server.baseUrl);

    await client.load();
    await expect(client.requestSensitiveJson('/reveal', { method: 'POST' })).rejects.toMatchObject({
      code: 'SENSITIVE_RESPONSE_CACHEABLE', status: 502,
    } satisfies Partial<ManagementClientError>);
  });
});
