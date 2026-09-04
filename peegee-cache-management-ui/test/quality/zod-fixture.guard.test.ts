import { describe, expect, it } from 'vitest';

import { TEST_ROOT, formatViolations, scan, type ScannedSource, type Violation } from './source-scan';

/**
 * U11 guard — every fixture body a test serves is produced by a production Zod schema.
 *
 * Normative text: PEEGEEQ_CACHE_MANAGEMENT_UI_IMPLEMENTATION_PLAN.md §5 ("hard-coded successful
 * DTOs that bypass serialization and runtime validation" is prohibited) and §6.2;
 * PEEGEEQ_CACHE_TEST_COVERAGE_AND_TDD_APPROACH.md §5.
 *
 * Statically: in any test that serves HTTP responses, each served body — the argument of
 * `JSON.stringify(x)` written to a response, or the body argument of the shared fixture's
 * `respond.json(status, x)` — must be a `<name>Schema.parse(...)` call, an identifier bound from
 * one, or an explicit `invalidBody(...)` negative fixture. Component tests (anything that
 * imports a page or the shell) must be served by the shared loopback fixture rather than by
 * in-memory data.
 */

const SCHEMA_PARSE = /\b\w+Schema\s*\.\s*parse\s*\(/;

function identifiersBoundFromSchemaParse(source: ScannedSource): Set<string> {
  const bound = new Set<string>();
  for (const match of source.code.matchAll(/\b(?:const|let)\s+(\w+)\s*(?::[^=]+)?=\s*\w+Schema\s*\.\s*parse\s*\(/g)) bound.add(match[1]!);
  return bound;
}

/**
 * `JSON.stringify(...)` calls that are written to an HTTP response: `response.end(JSON.stringify(x))`,
 * `res.write(JSON.stringify(x))`, or a `body: JSON.stringify(x)` handed to the loopback fixture.
 * Stringify calls used for assertions or for building request bodies are not response fixtures.
 */
function stringifyArguments(source: ScannedSource): Array<{ line: number; argument: string }> {
  const out: Array<{ line: number; argument: string }> = [];
  const pattern = /(?:\.\s*(?:end|write)\s*\(\s*|\bbody\s*:\s*)JSON\s*\.\s*stringify\s*\(|\brespond\s*\.\s*json\s*\(\s*\d+\s*,\s*/g;
  for (const match of source.code.matchAll(pattern)) {
    const start = match.index! + match[0].length;
    let depth = 1;
    let index = start;
    while (index < source.code.length && depth > 0) {
      const char = source.code[index]!;
      if (char === '(') depth += 1;
      else if (char === ')') depth -= 1;
      index += 1;
    }
    const argument = source.code.slice(start, index - 1).trim();
    const line = source.code.slice(0, match.index!).split('\n').length;
    out.push({ line, argument });
  }
  return out;
}

function servesHttp(source: ScannedSource): boolean {
  return /\bcreateServer\s*\(/.test(source.code) || /from\s+['"]\.\/support\/loopback-server['"]/.test(source.code);
}

/** A test that renders a page or the shell. Pure presentation components (formatters) need no server. */
function rendersComponent(source: ScannedSource): boolean {
  return /from\s+['"]@src\/(?:features\/[^'"]*Page|app\/(?:ManagementShell|App))['"]/.test(source.code);
}

describe('U11 zod-fixture guard', () => {
  const testSources = scan(TEST_ROOT, { exclude: (path) => path.startsWith('test/quality/') });

  it('every JSON body served by a test comes from a *Schema.parse call', () => {
    const violations: Violation[] = [];
    for (const source of testSources) {
      if (!servesHttp(source)) continue;
      const bound = identifiersBoundFromSchemaParse(source);
      for (const { line, argument } of stringifyArguments(source)) {
        const first = argument.split(/[.[(\s]/)[0]!;
        const isSchemaCall = SCHEMA_PARSE.test(argument);
        const isBound = bound.has(first);
        // Negative-path fixtures (bodies the strict schema must reject) are declared through the
        // shared fixture's `invalidBody(...)` so the intent is explicit and greppable.
        const isDeclaredInvalid = /^invalidBody\s*\(/.test(argument);
        if (!isSchemaCall && !isBound && !isDeclaredInvalid) {
          violations.push({ file: source.path, line, label: `JSON.stringify(${argument.slice(0, 40)}${argument.length > 40 ? '…' : ''}) — body must be produced by a *Schema.parse call` });
        }
      }
    }
    expect(formatViolations(violations)).toBe('');
  });

  it('every component test is served by the shared loopback fixture, not in-memory data', () => {
    const violations: Violation[] = [];
    for (const source of testSources) {
      if (!rendersComponent(source)) continue;
      if (!/from\s+['"]\.\/support\/loopback-server['"]/.test(source.code)) {
        violations.push({ file: source.path, line: 1, label: 'renders a page/shell without test/support/loopback-server — fixtures must be served over HTTP by Zod-produced bodies' });
      }
    }
    expect(formatViolations(violations)).toBe('');
  });
});
