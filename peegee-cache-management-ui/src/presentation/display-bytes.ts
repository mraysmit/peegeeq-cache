import { loadPreferences, type Preferences } from '../state/preferences';

export function formatDisplayBytes(
  value: string | number | bigint,
  preference: Preferences['byteUnits'] = loadPreferences().byteUnits,
): string {
  const bytes = BigInt(value);
  const base = preference === 'BINARY' ? 1_024n : 1_000n;
  if (bytes < base) return `${bytes.toLocaleString('en-US')} B`;
  const units = preference === 'BINARY'
    ? ['KiB', 'MiB', 'GiB', 'TiB', 'PiB', 'EiB']
    : ['kB', 'MB', 'GB', 'TB', 'PB', 'EB'];
  let divisor = base;
  let unit = 0;
  while (bytes >= divisor * base && unit < units.length - 1) {
    divisor *= base;
    unit += 1;
  }
  const whole = bytes / divisor;
  const tenths = (bytes % divisor) * 10n / divisor;
  return `${whole}${whole < 10n && tenths > 0n ? `.${tenths}` : ''} ${units[unit]}`;
}
