import { describe, expect, it } from 'vitest';

import {
  decodeKey,
  decodeNamespace,
  encodeKey,
  encodeNamespace,
} from '@src/api/identifier-codec';

describe('management identifier codec', () => {
  it('round-trips arbitrary UTF-8 without Base64 padding', () => {
    const namespace = '客户/订单+eu:%';
    const key = 'café/東京/🔒?x=1';

    const encodedNamespace = encodeNamespace(namespace);
    const encodedKey = encodeKey(key);

    expect(encodedNamespace).toMatch(/^[A-Za-z0-9_-]+$/);
    expect(encodedKey).toMatch(/^[A-Za-z0-9_-]+$/);
    expect(encodedNamespace).not.toContain('=');
    expect(encodedKey).not.toContain('=');
    expect(decodeNamespace(encodedNamespace)).toBe(namespace);
    expect(decodeKey(encodedKey)).toBe(key);
  });

  it.each([
    ['', 'empty'],
    ['\0', 'NUL'],
    ['n'.repeat(129), '128 UTF-8 bytes'],
  ])('rejects invalid namespace %j', (namespace, message) => {
    expect(() => encodeNamespace(namespace)).toThrow(message);
  });

  it('applies byte limits rather than JavaScript character counts', () => {
    expect(() => encodeNamespace('界'.repeat(43))).toThrow('128 UTF-8 bytes');
    expect(() => encodeKey('界'.repeat(342))).toThrow('1024 UTF-8 bytes');
  });

  it.each(['=', 'YQ==', 'YQ=', 'Y', 'YQ+', 'YQ/'])('rejects non-canonical Base64 URL input %j', (encoded) => {
    expect(() => decodeKey(encoded)).toThrow('canonical unpadded Base64 URL');
  });

  it('rejects malformed UTF-8 bytes', () => {
    expect(() => decodeNamespace('_w')).toThrow('valid UTF-8');
  });
});
