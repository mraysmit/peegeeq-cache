import { describe, expect, it } from 'vitest';

import { formatDisplayInstant } from '@src/presentation/display-time';

describe('shared display-time preference formatter', () => {
  it('renders protocol UTC instants deterministically in the default UTC preference', () => {
    expect(formatDisplayInstant('2026-08-26T10:15:30Z')).toBe('26 Aug 2026, 10:15:30 UTC');
  });

  it('supports the browser-local preference through the same boundary', () => {
    const value = '2026-08-26T10:15:30Z';
    const expected = new Intl.DateTimeFormat('en-GB', {
      dateStyle: 'medium',
      timeStyle: 'medium',
    }).format(new Date(value));
    expect(formatDisplayInstant(value, 'BROWSER_LOCAL')).toBe(expected);
  });
});
