import type { Preferences } from '../../state/preferences';

export type RefreshSeconds = `${Preferences['refreshSeconds']}`;
export type AutoHideSeconds = `${Preferences['autoHideSeconds']}`;

/** Reviewed option lists for the allowlisted display preferences; values are the stored contract values. */
export const THEME_OPTIONS: ReadonlyArray<{ value: Preferences['theme']; label: string }> = [
  { value: 'light', label: 'Light' },
  { value: 'dark', label: 'Dark' },
];

export const TIMEZONE_OPTIONS: ReadonlyArray<{ value: Preferences['timezone']; label: string }> = [
  { value: 'LOCAL', label: 'Local' },
  { value: 'UTC', label: 'UTC' },
];

export const BYTE_UNIT_OPTIONS: ReadonlyArray<{ value: Preferences['byteUnits']; label: string }> = [
  { value: 'BINARY', label: 'Binary (KiB, MiB)' },
  { value: 'DECIMAL', label: 'Decimal (kB, MB)' },
];

export const REFRESH_OPTIONS: ReadonlyArray<{ value: RefreshSeconds; label: string }> = [
  { value: '15', label: '15 seconds' },
  { value: '30', label: '30 seconds' },
  { value: '60', label: '60 seconds' },
];

export const AUTO_HIDE_OPTIONS: ReadonlyArray<{ value: AutoHideSeconds; label: string }> = [
  { value: '30', label: '30 seconds' },
  { value: '60', label: '60 seconds' },
  { value: '120', label: '120 seconds' },
];
