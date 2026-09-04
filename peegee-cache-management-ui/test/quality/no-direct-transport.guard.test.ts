import { describe, expect, it } from 'vitest';

import { SRC_ROOT, findPattern, formatViolations, importedPackages, scan, type Violation } from './source-scan';

/**
 * U11 guard — all network transport lives in src/api (and the RTK Query base query that delegates
 * to it). Pages, components, stores, and presentation helpers never construct a request.
 *
 * Normative text: PEEGEEQ_CACHE_MANAGEMENT_UI_IMPLEMENTATION_PLAN.md §6.1 ("no direct page-level
 * fetch, Axios, EventSource, or WebSocket construction exists"). `axios` is additionally banned
 * module-wide: the reference console's direct axios calls in pages are one of the accidental
 * patterns U11 explicitly does not clone.
 */

const TRANSPORT_ROOTS = ['src/api/', 'src/store/api/'];

const DIRECT_TRANSPORT: ReadonlyArray<readonly [RegExp, string]> = [
  [/(?<![\w.])fetch\s*\(/, 'direct fetch() outside src/api'],
  [/\bnew\s+EventSource\s*\(/, 'direct EventSource construction outside src/api'],
  [/\bnew\s+WebSocket\s*\(/, 'direct WebSocket construction outside src/api'],
  [/\bnew\s+XMLHttpRequest\s*\(/, 'XMLHttpRequest outside src/api'],
  [/\bnavigator\s*\.\s*sendBeacon\s*\(/, 'sendBeacon outside src/api'],
];

describe('U11 no-direct-transport guard', () => {
  const sources = scan(SRC_ROOT);

  it('no fetch, EventSource, WebSocket, or XHR is constructed outside src/api', () => {
    const violations: Violation[] = [];
    for (const source of sources) {
      if (TRANSPORT_ROOTS.some((root) => source.path.startsWith(root))) continue;
      for (const [pattern, label] of DIRECT_TRANSPORT) violations.push(...findPattern(source, pattern, label));
    }
    expect(formatViolations(violations)).toBe('');
  });

  it('axios is not used anywhere in the module', () => {
    const violations: Violation[] = [];
    for (const source of sources) {
      if (importedPackages(source).has('axios')) violations.push({ file: source.path, line: 1, label: 'axios import' });
    }
    expect(formatViolations(violations)).toBe('');
  });

  it('the RTK Query base query, when present, delegates to the session-aware client rather than fetchBaseQuery', () => {
    const violations: Violation[] = [];
    for (const source of sources) {
      if (!source.path.startsWith('src/store/')) continue;
      violations.push(...findPattern(source, /\bfetchBaseQuery\s*\(/, 'fetchBaseQuery bypasses CSRF/Origin/no-store handling in src/api — delegate to the session client'));
    }
    expect(formatViolations(violations)).toBe('');
  });
});
