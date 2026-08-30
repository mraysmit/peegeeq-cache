import type { CacheValue } from '../../api/inspection-schemas';

export function copyableValue(value: CacheValue): string {
  if (value.type === 'STRING' || value.type === 'JSON') return value.text;
  if (value.type === 'LONG') return value.decimal;
  return value.base64;
}
