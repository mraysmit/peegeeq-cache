const maximumNamespaceBytes = 128;
const maximumKeyBytes = 1_024;
const base64UrlPattern = /^[A-Za-z0-9_-]+$/;
const utf8Encoder = new TextEncoder();
const utf8Decoder = new TextDecoder('utf-8', { fatal: true });

export function encodeNamespace(namespace: string): string {
  return encode(namespace, maximumNamespaceBytes, 'namespace');
}

export function decodeNamespace(encodedNamespace: string): string {
  return decode(encodedNamespace, maximumNamespaceBytes, 'namespace');
}

export function encodeKey(key: string): string {
  return encode(key, maximumKeyBytes, 'key');
}

export function decodeKey(encodedKey: string): string {
  return decode(encodedKey, maximumKeyBytes, 'key');
}

function encode(identifier: string, maximumBytes: number, label: string): string {
  const bytes = utf8Encoder.encode(identifier);
  validateDecoded(identifier, bytes, maximumBytes, label);
  return encodeBytes(bytes);
}

function decode(encoded: string, maximumBytes: number, label: string): string {
  if (!base64UrlPattern.test(encoded) || encoded.length % 4 === 1) {
    throw invalid(label, 'must be canonical unpadded Base64 URL data');
  }

  const bytes = decodeBytes(encoded, label);
  if (encodeBytes(bytes) !== encoded) {
    throw invalid(label, 'must use canonical unpadded Base64 URL encoding');
  }

  let identifier: string;
  try {
    identifier = utf8Decoder.decode(bytes);
  } catch (cause) {
    throw invalid(label, 'must contain valid UTF-8', cause);
  }
  validateDecoded(identifier, bytes, maximumBytes, label);
  return identifier;
}

function encodeBytes(bytes: Uint8Array): string {
  let binary = '';
  for (const byte of bytes) {
    binary += String.fromCharCode(byte);
  }
  return btoa(binary).replaceAll('+', '-').replaceAll('/', '_').replace(/=+$/, '');
}

function decodeBytes(encoded: string, label: string): Uint8Array {
  const base64 = encoded.replaceAll('-', '+').replaceAll('_', '/');
  const padded = base64.padEnd(base64.length + ((4 - (base64.length % 4)) % 4), '=');
  try {
    const binary = atob(padded);
    return Uint8Array.from(binary, (character) => character.charCodeAt(0));
  } catch (cause) {
    throw invalid(label, 'must be canonical unpadded Base64 URL data', cause);
  }
}

function validateDecoded(identifier: string, bytes: Uint8Array, maximumBytes: number, label: string): void {
  if (bytes.length === 0) {
    throw invalid(label, 'must not be empty');
  }
  if (bytes.length > maximumBytes) {
    throw invalid(label, `must not exceed ${maximumBytes} UTF-8 bytes`);
  }
  if (identifier.includes('\0')) {
    throw invalid(label, 'must not contain NUL');
  }
}

function invalid(label: string, detail: string, cause?: unknown): Error {
  return new Error(`Invalid ${label}: ${detail}`, cause === undefined ? undefined : { cause });
}
