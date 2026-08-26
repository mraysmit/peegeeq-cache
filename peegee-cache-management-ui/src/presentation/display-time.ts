export type DisplayTimePreference = 'UTC' | 'BROWSER_LOCAL';

export function formatDisplayInstant(
  value: string,
  preference: DisplayTimePreference = 'UTC',
): string {
  const options: Intl.DateTimeFormatOptions = {
    dateStyle: 'medium',
    timeStyle: 'medium',
  };
  if (preference === 'UTC') options.timeZone = 'UTC';
  const formatted = new Intl.DateTimeFormat('en-GB', options).format(new Date(value));
  return preference === 'UTC' ? `${formatted} UTC` : formatted;
}
