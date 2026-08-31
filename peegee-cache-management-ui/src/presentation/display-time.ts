import { loadPreferences } from '../state/preferences';

export type DisplayTimePreference = 'UTC' | 'BROWSER_LOCAL';

export function formatDisplayInstant(
  value: string,
  preference?: DisplayTimePreference,
): string {
  const effectivePreference = preference
    ?? (loadPreferences().timezone === 'UTC' ? 'UTC' : 'BROWSER_LOCAL');
  const options: Intl.DateTimeFormatOptions = {
    dateStyle: 'medium',
    timeStyle: 'medium',
  };
  if (effectivePreference === 'UTC') options.timeZone = 'UTC';
  const formatted = new Intl.DateTimeFormat('en-GB', options).format(new Date(value));
  return effectivePreference === 'UTC' ? `${formatted} UTC` : formatted;
}
