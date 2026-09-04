import { describe, expect, it } from 'vitest';

import { SRC_ROOT, TEST_ROOT, findPattern, formatViolations, scan, type Violation } from './source-scan';

/**
 * U11 guard — no test doubles of any kind in the management UI module.
 *
 * Normative text: PEEGEEQ_CACHE_MANAGEMENT_UI_IMPLEMENTATION_PLAN.md §5 (prohibited shortcuts) and
 * PEEGEEQ_CACHE_TEST_COVERAGE_AND_TDD_APPROACH.md §5 "Mandated: no test doubles".
 *
 * Two sides are enforced:
 *  - test/: no vitest mocking API, no hand-built fakes of a client/port/transport/store interface;
 *  - src/: no page or component accepts a client/port object as a prop, because that seam exists
 *    only so a test can substitute it. Pages read through RTK Query hooks and the Zustand stores.
 */

const VITEST_DOUBLES: ReadonlyArray<readonly [RegExp, string]> = [
  [/\bvi\s*\.\s*mock\s*\(/, 'vi.mock — module replacement is prohibited'],
  [/\bvi\s*\.\s*doMock\s*\(/, 'vi.doMock — module replacement is prohibited'],
  [/\bvi\s*\.\s*importMock\s*\(/, 'vi.importMock — module replacement is prohibited'],
  [/\bvi\s*\.\s*stubGlobal\s*\(/, 'vi.stubGlobal — global (fetch/WebSocket) replacement is prohibited; use the loopback server'],
  [/\bvi\s*\.\s*stubEnv\s*\(/, 'vi.stubEnv — environment replacement is prohibited'],
  [/\bvi\s*\.\s*fn\s*\(/, 'vi.fn — function doubles are prohibited; assert on observable state'],
  [/\bvi\s*\.\s*spyOn\s*\(/, 'vi.spyOn — spies are prohibited; assert on observable state'],
  [/\bvi\s*\.\s*useFakeTimers\s*\(/, 'vi.useFakeTimers — time doubles are prohibited; drive real timers with vi.waitFor'],
  [/\bjest\s*\.\s*(fn|mock|spyOn)\s*\(/, 'jest mocking API is prohibited'],
];

/** A hand-built implementation of a production port/client interface. */
const HAND_BUILT_FAKES: ReadonlyArray<readonly [RegExp, string]> = [
  [/\b\w*ClientPort\b/, 'reference to a *ClientPort type — port fakes are prohibited; render against the real client and loopback server'],
  [/\bimplements\s+\w*(Port|Client|Transport|Socket)\b/, 'class implementing a production port/client/transport in a test'],
  [/:\s*\w*(Port|Transport)\s*=\s*\{/, 'object literal typed as a production port/transport'],
  [/\bclass\s+(Fake|Stub|Mock|InMemory)\w*/, 'Fake/Stub/Mock/InMemory class in a test'],
];

describe('U11 no-test-fakes guard', () => {
  const testSources = scan(TEST_ROOT, { exclude: (path) => path.startsWith('test/quality/') });
  const srcSources = scan(SRC_ROOT);

  it('test/ uses no vitest mocking, stubbing, spying, or fake-timer API', () => {
    const violations: Violation[] = [];
    for (const source of testSources) for (const [pattern, label] of VITEST_DOUBLES) violations.push(...findPattern(source, pattern, label));
    expect(formatViolations(violations)).toBe('');
  });

  it('test/ contains no hand-built fakes of production ports, clients, or transports', () => {
    const violations: Violation[] = [];
    for (const source of testSources) for (const [pattern, label] of HAND_BUILT_FAKES) violations.push(...findPattern(source, pattern, label));
    expect(formatViolations(violations)).toBe('');
  });

  it('src/ defines no *ClientPort seam and no page receives a client as a prop', () => {
    const violations: Violation[] = [];
    for (const source of srcSources) {
      violations.push(...findPattern(source, /\b(export\s+)?(interface|type)\s+\w*ClientPort\b/, '*ClientPort interface — test-substitution seam is prohibited'));
      if (source.path.startsWith('src/features/') || source.path.startsWith('src/app/')) {
        violations.push(...findPattern(source, /\breadonly\s+\w*[cC]lient\s*:/, 'component prop carrying a client object'));
        violations.push(...findPattern(source, /\b\w*[cC]lient\s*:\s*\w*(Client|Port)\b/, 'component prop typed as a client/port'));
      }
    }
    expect(formatViolations(violations)).toBe('');
  });
});
