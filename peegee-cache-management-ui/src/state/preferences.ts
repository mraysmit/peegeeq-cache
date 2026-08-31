import { z } from 'zod';

const key = 'peegeeq.management.preferences';
const schema = z.object({
  theme: z.enum(['light', 'dark']), timezone: z.enum(['LOCAL', 'UTC']), byteUnits: z.enum(['BINARY', 'DECIMAL']),
  refreshSeconds: z.union([z.literal(15), z.literal(30), z.literal(60)]),
  autoHideSeconds: z.union([z.literal(30), z.literal(60), z.literal(120)]),
}).strict();
export type Preferences = z.infer<typeof schema>;
export const defaultPreferences: Preferences = { theme: 'light', timezone: 'LOCAL', byteUnits: 'BINARY', refreshSeconds: 15, autoHideSeconds: 60 };
export const PREFERENCES_CHANGED_EVENT = 'peegeeq:preferences-changed';

export function loadPreferences(): Preferences {
  try {
    const raw = globalThis.localStorage?.getItem(key); if (raw === null || raw === undefined) return defaultPreferences;
    const source = JSON.parse(raw) as Record<string, unknown>;
    const allowlisted = { theme: source.theme ?? defaultPreferences.theme, timezone: source.timezone ?? defaultPreferences.timezone, byteUnits: source.byteUnits ?? defaultPreferences.byteUnits, refreshSeconds: source.refreshSeconds ?? defaultPreferences.refreshSeconds, autoHideSeconds: source.autoHideSeconds ?? defaultPreferences.autoHideSeconds };
    const parsed = schema.safeParse(allowlisted); return parsed.success ? parsed.data : defaultPreferences;
  } catch { return defaultPreferences; }
}
export function savePreferences(preferences: Preferences): void {
  globalThis.localStorage?.setItem(key, JSON.stringify(schema.parse(preferences)));
  globalThis.window?.dispatchEvent(new globalThis.Event(PREFERENCES_CHANGED_EVENT));
}
export function effectiveAutoHideMillis(serverMaximumMillis: number): number { return Math.min(serverMaximumMillis, loadPreferences().autoHideSeconds * 1_000); }
