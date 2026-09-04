import type { EntryQuery } from '../../api/inspection-client';
import type { CacheValue } from '../../api/inspection-schemas';
import type { EntrySetBody } from '../../api/entry-administration-schemas';

export type EntryValueTypeFilter = 'ALL' | NonNullable<EntryQuery['valueType']>;
export type EntryTtlState = NonNullable<EntryQuery['ttlState']>;
export type EntryValueType = EntrySetBody['value']['type'];

export const ENTRY_VALUE_TYPE_FILTER_OPTIONS: ReadonlyArray<{ value: EntryValueTypeFilter; label: string }> = [
  { value: 'ALL', label: 'All' },
  { value: 'STRING', label: 'String' },
  { value: 'JSON', label: 'JSON' },
  { value: 'LONG', label: 'Long' },
  { value: 'BYTES', label: 'Bytes' },
];

export const ENTRY_TTL_STATE_OPTIONS: ReadonlyArray<{ value: EntryTtlState; label: string }> = [
  { value: 'ALL_LIVE', label: 'All live' },
  { value: 'PERSISTENT', label: 'Persistent' },
  { value: 'EXPIRING', label: 'Expiring' },
  { value: 'INCLUDE_EXPIRED', label: 'Include expired' },
];

export const ENTRY_VALUE_TYPE_OPTIONS: ReadonlyArray<{ value: EntryValueType; label: string }> = [
  { value: 'STRING', label: 'String' },
  { value: 'JSON', label: 'JSON' },
  { value: 'LONG', label: 'Long' },
  { value: 'BYTES', label: 'Bytes (Base64)' },
];

export const ENTRY_SET_MODE_OPTIONS: ReadonlyArray<{ value: EntrySetBody['setMode']; label: string }> = [
  { value: 'ONLY_IF_VERSION_MATCHES', label: 'Only if observed version matches' },
  { value: 'UPSERT', label: 'Always / upsert' },
  { value: 'ONLY_IF_PRESENT', label: 'Only if present' },
  { value: 'ONLY_IF_ABSENT', label: 'Only if absent' },
];

export const ENTRY_TTL_MODE_OPTIONS: ReadonlyArray<{ value: EntrySetBody['ttlMode']; label: string }> = [
  { value: 'PRESERVE_EXISTING', label: 'Preserve existing' },
  { value: 'USE_DEFAULT', label: 'Use cache default' },
  { value: 'REPLACE', label: 'Replace TTL' },
  { value: 'REMOVE', label: 'Make persistent' },
];

export class EntryInputError extends Error {
  constructor(readonly code: string, message: string) {
    super(message);
  }
}

/** Builds the typed contract value from operator text, validating JSON and LONG locally before transport. */
export function cacheValueFor(type: EntryValueType, value: string): CacheValue {
  switch (type) {
    case 'STRING': return { type, text: value };
    case 'JSON':
      try { JSON.parse(value); } catch { throw new EntryInputError('JSON_VALUE_INVALID', 'Value must be valid JSON'); }
      return { type, text: value };
    case 'LONG':
      if (!/^-?(?:0|[1-9][0-9]*)$/u.test(value)) throw new EntryInputError('VALUE_TYPE_MISMATCH', 'Value must be a signed decimal integer');
      return { type, decimal: value };
    case 'BYTES': return { type, base64: value };
  }
}

export function positiveInteger(value: string, label = 'TTL'): number {
  const parsed = Number(value);
  if (!Number.isSafeInteger(parsed) || parsed < 1) throw new EntryInputError('VALIDATION_FAILED', `${label} must be a positive integer`);
  return parsed;
}

export function formatTtl(value: number | null): string {
  return value === null ? 'Persistent' : value < 1_000 ? `${value} ms` : `${(value / 1_000).toFixed(value % 1_000 === 0 ? 0 : 1)} s`;
}
