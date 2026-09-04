import { describe, expect, it } from 'vitest';

import { SRC_ROOT, findPattern, formatViolations, scan, type Violation } from './source-scan';

/**
 * U11 guard — sensitive DTOs stay in short-lived component memory.
 *
 * Normative text: PEEGEEQ_CACHE_MANAGEMENT_UI_DESIGN.md §8.2 (revealed sensitive value → detail
 * component memory only) and PEEGEEQ_CACHE_MANAGEMENT_UI_IMPLEMENTATION_PLAN.md §6.1 ("sensitive
 * DTOs cannot be assigned to Redux/Zustand stores, persistence middleware, URL builders,
 * notification models, analytics, or logging helpers").
 *
 * The sensitive types are named, not guessed by field name, so the guard is exact:
 * RevealedEntryValue, RevealedLockOwner, RevealedPubSubPayload, the CSRF-bearing CurrentSession,
 * and the setup registration password. Prohibited surfaces are src/state, src/store, and any file
 * that persists (localStorage/sessionStorage/IndexedDB), builds URLs, or logs.
 */

const SENSITIVE_IDENTIFIERS = [
  'RevealedEntryValue', 'revealedEntryValueSchema',
  'RevealedLockOwner', 'revealedLockOwnerSchema',
  'RevealedPubSubPayload', 'revealedPubSubPayloadSchema',
  'CurrentSession', 'currentSessionSchema', 'csrfToken',
  'bootstrapToken', 'ownerToken', 'password',
] as const;

const SENSITIVE = new RegExp(`\\b(${SENSITIVE_IDENTIFIERS.join('|')})\\b`);

const STORE_ROOTS = ['src/state/', 'src/store/'];

function isPersistenceOrLoggingSurface(code: string): boolean {
  return /\b(localStorage|sessionStorage|indexedDB)\b/.test(code)
    || /\bconsole\s*\.\s*(log|info|warn|error|debug)\s*\(/.test(code)
    || /\bnew\s+URLSearchParams\s*\(|\bnew\s+URL\s*\(/.test(code);
}

/** RTK Query endpoints cache their results in the Redux store, so a sensitive type there is a leak by construction. */
function isRtkQuerySlice(code: string): boolean {
  return /\bcreateApi\s*\(/.test(code);
}

describe('U11 sensitive-dto guard', () => {
  const sources = scan(SRC_ROOT);

  it('src/state and src/store never reference a sensitive DTO type, schema, or secret field', () => {
    const violations: Violation[] = [];
    for (const source of sources) {
      if (!STORE_ROOTS.some((root) => source.path.startsWith(root))) continue;
      violations.push(...findPattern(source, SENSITIVE, 'sensitive identifier in a store module'));
    }
    expect(formatViolations(violations)).toBe('');
  });

  it('no RTK Query slice exposes a reveal endpoint', () => {
    const violations: Violation[] = [];
    for (const source of sources) {
      if (!isRtkQuerySlice(source.code)) continue;
      violations.push(...findPattern(source, SENSITIVE, 'sensitive type in an RTK Query slice — reveals must stay on the no-store client path'));
      violations.push(...findPattern(source, /\breveal\w*\s*:\s*builder\s*\./i, 'reveal endpoint defined in createApi'));
    }
    expect(formatViolations(violations)).toBe('');
  });

  it('files that persist, build URLs, or log never touch a sensitive DTO', () => {
    const violations: Violation[] = [];
    for (const source of sources) {
      if (source.path.startsWith('src/api/')) continue; // clients build URLs and parse these types by design
      if (!isPersistenceOrLoggingSurface(source.code)) continue;
      violations.push(...findPattern(source, SENSITIVE, 'sensitive identifier in a persistence/URL/logging surface'));
    }
    expect(formatViolations(violations)).toBe('');
  });

  it('src/api clients never persist a sensitive DTO', () => {
    const violations: Violation[] = [];
    for (const source of sources) {
      if (!source.path.startsWith('src/api/')) continue;
      violations.push(...findPattern(source, /\b(localStorage|sessionStorage|indexedDB)\b/, 'browser storage access in src/api'));
    }
    expect(formatViolations(violations)).toBe('');
  });
});
