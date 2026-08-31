import { beforeEach, describe, expect, it } from 'vitest';

import { formatDisplayInstant } from '@src/presentation/display-time';
import { defaultPreferences, savePreferences } from '@src/state/preferences';

describe('shared display-time preference formatter', () => {
  beforeEach(() => localStorage.clear());

  it('renders protocol UTC instants according to the persisted UTC preference', () => {
    savePreferences({ ...defaultPreferences, timezone: 'UTC' });
    expect(formatDisplayInstant('2026-08-26T10:15:30Z')).toBe('26 Aug 2026, 10:15:30 UTC');
  });

  it('uses the persisted browser-local preference when callers omit an override', () => {
    const value = '2026-08-26T10:15:30Z';
    savePreferences({ ...defaultPreferences, timezone: 'LOCAL' });
    const expected = new Intl.DateTimeFormat('en-GB', {
      dateStyle: 'medium',
      timeStyle: 'medium',
    }).format(new Date(value));
    expect(formatDisplayInstant(value)).toBe(expected);
  });
});
