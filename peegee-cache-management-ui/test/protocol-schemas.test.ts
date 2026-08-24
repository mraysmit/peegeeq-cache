import { describe, expect, it } from 'vitest';

import { currentSessionSchema, managementProblemSchema } from '@src/api/protocol-schemas';

const validSession = {
  user: 'local-operator',
  roles: ['operator'],
  serverVersion: '0.1.0-SNAPSHOT',
  apiVersion: 'v1',
  authenticationMode: 'LOCAL_TOKEN',
  csrfToken: 'c'.repeat(43),
  sessionIdleExpiresAt: '2026-08-24T09:00:00Z',
  sessionExpiresAt: '2026-08-24T16:00:00Z',
  features: {
    setupRegistration: true,
    sensitiveReveal: true,
  },
};

describe('management protocol schemas', () => {
  it('accepts the exact current-session contract', () => {
    expect(currentSessionSchema.parse(validSession)).toEqual(validSession);
  });

  it.each([
    { ...validSession, apiVersion: 'v2' },
    { ...validSession, roles: ['administrator'] },
    { ...validSession, csrfToken: 'short' },
    { ...validSession, unexpected: true },
    { ...validSession, sessionExpiresAt: 'not-an-instant' },
  ])('rejects incompatible current-session data', (session) => {
    expect(() => currentSessionSchema.parse(session)).toThrow();
  });

  it('accepts a bounded RFC 9457 management problem', () => {
    const problem = {
      type: 'https://peegeeq.dev/problems/invalid-request',
      title: 'Invalid request',
      status: 400,
      code: 'INVALID_REQUEST',
      detail: 'One or more fields are invalid',
      instance: '/api/v1/setups',
      correlationId: 'corr-1',
      fieldErrors: [{ field: 'host', message: 'must not be blank' }],
    };

    expect(managementProblemSchema.parse(problem)).toEqual(problem);
  });

  it('rejects problem responses with unknown fields or invalid status', () => {
    expect(() => managementProblemSchema.parse({
      type: 'not a URI',
      title: 'Wrong',
      status: 200,
      code: 'WRONG',
      detail: 'Wrong',
      instance: '/api/v1/session',
      correlationId: 'corr-2',
      fieldErrors: [],
      secret: 'must-not-pass',
    })).toThrow();
  });
});
