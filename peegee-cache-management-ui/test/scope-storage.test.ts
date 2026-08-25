import { beforeEach, describe, expect, it } from 'vitest';

import {
  SETUP_SCOPE_STORAGE_KEY,
  clearSetupScopeStorage,
  readSetupScope,
  readSetupScopeId,
  writeSetupScope,
  writeSetupScopeId,
} from '@src/state/scope-storage';

describe('setup scope session-storage allowlist', () => {
  beforeEach(() => sessionStorage.clear());

  it('persists only a canonical setup identifier', () => {
    writeSetupScopeId('primary-cache');

    expect(sessionStorage).toHaveLength(1);
    expect(sessionStorage.getItem(SETUP_SCOPE_STORAGE_KEY)).toBe('{"setupId":"primary-cache"}');
    expect(readSetupScopeId()).toBe('primary-cache');
  });

  it('persists an arbitrary valid namespace through the explicit scope allowlist', () => {
    writeSetupScope({ setupId: 'primary-cache', namespace: '客户/订单+eu:%' });

    expect(sessionStorage).toHaveLength(1);
    expect(sessionStorage.getItem(SETUP_SCOPE_STORAGE_KEY)).toBe(
      '{"setupId":"primary-cache","namespace":"客户/订单+eu:%"}',
    );
    expect(readSetupScope()).toEqual({
      setupId: 'primary-cache',
      namespace: '客户/订单+eu:%',
    });
  });

  it('rejects malformed, noncanonical, and expanded persisted state', () => {
    for (const value of [
      'not-json',
      '{"setupId":"Primary"}',
      '{"setupId":"primary-cache","password":"secret"}',
      '{"namespace":"cache"}',
      '{"setupId":"primary-cache","namespace":""}',
      `{"setupId":"primary-cache","namespace":"${'界'.repeat(43)}"}`,
    ]) {
      sessionStorage.setItem(SETUP_SCOPE_STORAGE_KEY, value);
      expect(readSetupScopeId()).toBeUndefined();
      expect(sessionStorage.getItem(SETUP_SCOPE_STORAGE_KEY)).toBeNull();
    }
    expect(() => writeSetupScopeId('../unsafe')).toThrow('canonical');
    expect(() => writeSetupScope({ setupId: 'primary-cache', namespace: '\0' }))
      .toThrow('Invalid namespace');
  });

  it('clears the allowlisted scope without touching unrelated storage', () => {
    sessionStorage.setItem('unrelated', 'retained');
    writeSetupScopeId('primary-cache');
    clearSetupScopeStorage();

    expect(sessionStorage.getItem(SETUP_SCOPE_STORAGE_KEY)).toBeNull();
    expect(sessionStorage.getItem('unrelated')).toBe('retained');
  });
});
