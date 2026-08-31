import { beforeEach, describe, expect, it } from 'vitest';

import { formatDisplayBytes } from '@src/presentation/display-bytes';
import { defaultPreferences, savePreferences } from '@src/state/preferences';

describe('shared byte-display preference formatter', () => {
  beforeEach(() => localStorage.clear());

  it('uses binary units when the persisted preference is BINARY', () => {
    savePreferences({ ...defaultPreferences, byteUnits: 'BINARY' });
    expect(formatDisplayBytes('1536')).toBe('1.5 KiB');
  });

  it('uses decimal units when the persisted preference is DECIMAL', () => {
    savePreferences({ ...defaultPreferences, byteUnits: 'DECIMAL' });
    expect(formatDisplayBytes('1500')).toBe('1.5 kB');
  });
});
