import { beforeEach, describe, expect, it } from 'vitest';
import { loadPreferences, savePreferences } from '@src/state/preferences';

describe('U8 preference persistence', () => {
  beforeEach(() => localStorage.clear());
  it('persists only the explicit harmless display allowlist and rejects malformed values', () => {
    localStorage.setItem('peegeeq.management.preferences', JSON.stringify({ theme: 'dark', refreshSeconds: 30, password: 'leak', reconnect: -1 }));
    expect(loadPreferences()).toEqual(expect.objectContaining({ theme: 'dark', refreshSeconds: 30 }));
    expect(loadPreferences()).not.toHaveProperty('password');
    savePreferences({ ...loadPreferences(), theme: 'light', refreshSeconds: 15 });
    const stored = JSON.parse(localStorage.getItem('peegeeq.management.preferences') ?? '{}') as Record<string, unknown>;
    expect(stored).toEqual({ theme: 'light', timezone: 'LOCAL', byteUnits: 'BINARY', refreshSeconds: 15, autoHideSeconds: 60 });
  });
});
