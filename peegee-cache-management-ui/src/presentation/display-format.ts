/** Shared presentation helpers for contract decimal strings, durations, and enum-style labels. */

export function formatDecimal(value: string): string {
  return BigInt(value).toLocaleString('en-US');
}

export function formatDuration(value: number | bigint): string {
  const milliseconds = typeof value === 'bigint' ? value : BigInt(value);
  if (milliseconds < 1_000n) return `${milliseconds} ms`;
  const seconds = milliseconds / 1_000n;
  const tenths = (milliseconds % 1_000n) / 100n;
  return `${seconds}${tenths > 0n ? `.${tenths}` : ''} s`;
}

export function humanize(value: string): string {
  return value.toLowerCase().replaceAll('_', ' ').replace(/^./u, (letter) => letter.toUpperCase());
}

export function titleCase(value: string): string {
  return value.charAt(0) + value.slice(1).toLowerCase().replaceAll('_', ' ');
}
