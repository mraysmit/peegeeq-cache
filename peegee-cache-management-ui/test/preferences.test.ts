import { beforeEach, describe, expect, it, vi } from 'vitest';
import { loadPreferences, PREFERENCES_CHANGED_EVENT, savePreferences } from '@src/state/preferences';

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

  it('notifies the mounted shell when a preference changes', () => {
    const listener = vi.fn();
    window.addEventListener(PREFERENCES_CHANGED_EVENT, listener);
    try {
      savePreferences({ ...loadPreferences(), theme: 'dark' });
      expect(listener).toHaveBeenCalledOnce();
    } finally {
      window.removeEventListener(PREFERENCES_CHANGED_EVENT, listener);
    }
  });
});
