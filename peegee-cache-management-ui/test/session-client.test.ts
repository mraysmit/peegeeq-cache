import { createServer, type IncomingMessage, type Server, type ServerResponse } from 'node:http';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';

import { ManagementClientError, SessionClient } from '@src/api/session-client';

const sessionBody = {
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
};

describe('U1 session client', () => {
  let server: Server;
  let baseUrl: string;
  let handler: (request: IncomingMessage, response: ServerResponse) => void;

  beforeEach(async () => {
    handler = (_request, response) => {
      response.writeHead(500).end();
    };
    server = createServer((request, response) => handler(request, response));
    await new Promise<void>((resolve) => server.listen(0, '127.0.0.1', resolve));
    const address = server.address();
    if (address === null || typeof address === 'string') throw new Error('HTTP fixture did not bind');
    baseUrl = `http://127.0.0.1:${address.port}`;
    localStorage.clear();
    sessionStorage.clear();
  });

  afterEach(async () => {
    await new Promise<void>((resolve, reject) => server.close((error) => {
      if (error) reject(error);
      else resolve();
    }));
  });

  it('exchanges the bootstrap token and keeps token and CSRF material out of browser storage', async () => {
    const bootstrapToken = 'one-time-bootstrap-secret';
    let receivedToken = '';
    let receivedCsrf = '';
    handler = (request, response) => {
      if (request.method === 'POST') {
        let body = '';
        request.setEncoding('utf8');
        request.on('data', (chunk: string) => { body += chunk; });
        request.on('end', () => {
          receivedToken = (JSON.parse(body) as { token: string }).token;
          response.writeHead(200, { 'content-type': 'application/json' });
          response.end(JSON.stringify(sessionBody));
        });
        return;
      }
      receivedCsrf = String(request.headers['x-peegeeq-csrf'] ?? '');
      response.writeHead(204).end();
    };
    const client = new SessionClient(baseUrl);

    const session = await client.exchangeLocalToken(bootstrapToken);
    await client.logoutLocal();

    expect(session.user).toBe('local-operator');
    expect(receivedToken).toBe(bootstrapToken);
    expect(receivedCsrf).toBe(sessionBody.csrfToken);
    expect(JSON.stringify({ localStorage, sessionStorage })).not.toContain(bootstrapToken);
    expect(JSON.stringify({ localStorage, sessionStorage })).not.toContain(sessionBody.csrfToken);
  });

  it('rejects malformed success payloads at the protocol boundary', async () => {
    handler = (_request, response) => {
      response.writeHead(200, { 'content-type': 'application/json' });
      response.end(JSON.stringify({ ...sessionBody, unexpected: true }));
    };
    const client = new SessionClient(baseUrl);

    await expect(client.load()).rejects.toMatchObject({
      code: 'RESPONSE_CONTRACT_INVALID',
    } satisfies Partial<ManagementClientError>);
  });
});
