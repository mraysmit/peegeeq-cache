import { existsSync } from 'node:fs';
import { join } from 'node:path';
import { describe, expect, it } from 'vitest';

import {
  SRC_ROOT,
  findPattern,
  formatViolations,
  importedPackages,
  readPackageJson,
  scan,
  type Violation,
} from './source-scan';

/**
 * U11 guard — Ant Design 5 and Recharts are the only sources of UI controls and charts.
 *
 * Normative text: PEEGEEQ_CACHE_MANAGEMENT_UI_DESIGN.md §3.1 (mandated block) and §8.1;
 * PEEGEEQ_CACHE_TEST_COVERAGE_AND_TDD_APPROACH.md §5 "Management UI component and client tests".
 *
 * Three properties are enforced statically:
 *  1. every React component file under src/features, src/app and src/components/common imports
 *     from `antd` (a `.tsx` file that renders nothing from the component library is a hand-rolled
 *     control by definition);
 *  2. no raw control markup or hand-drawn chart markup appears in those files — tables, forms,
 *     inputs, buttons, selects, dialogs, and `<svg>` come from antd/Recharts, never from JSX;
 *  3. every declared production dependency is imported by `src/` — a declared but unused library
 *     is exactly how the U1-U10 console shipped without Ant Design.
 */

const CONTROL_ROOTS = ['src/features/', 'src/app/', 'src/components/common/'];

/** Presentation helpers that are legitimately markup-free (`.ts`) are exempt; components are not. */
function isComponentFile(path: string): boolean {
  return path.endsWith('.tsx') && CONTROL_ROOTS.some((root) => path.startsWith(root));
}

const RAW_CONTROL_MARKUP: ReadonlyArray<readonly [RegExp, string]> = [
  [/<table\b/, 'raw <table> — use antd Table'],
  [/<form\b/, 'raw <form> — use antd Form'],
  [/<input\b/, 'raw <input> — use antd Input/InputNumber/Checkbox/Radio'],
  [/<textarea\b/, 'raw <textarea> — use antd Input.TextArea'],
  [/<select\b/, 'raw <select> — use antd Select'],
  [/<button\b/, 'raw <button> — use antd Button'],
  [/<dialog\b|role=["']dialog["']/, 'raw dialog — use antd Modal/Drawer'],
  [/<svg\b/, 'hand-drawn <svg> — use Recharts'],
  [/<progress\b|<meter\b/, 'raw progress/meter — use antd Progress/Statistic'],
];

describe('U11 component library guard', () => {
  const sources = scan(SRC_ROOT);

  it('every component under src/features, src/app and src/components/common renders through antd', () => {
    const violations: Violation[] = [];
    for (const source of sources) {
      if (!isComponentFile(source.path)) continue;
      if (!importedPackages(source).has('antd')) {
        violations.push({ file: source.path, line: 1, label: 'component file does not import from antd' });
      }
    }
    expect(formatViolations(violations)).toBe('');
  });

  it('no hand-rolled control or chart markup exists in component files', () => {
    const violations: Violation[] = [];
    for (const source of sources) {
      if (!isComponentFile(source.path)) continue;
      for (const [pattern, label] of RAW_CONTROL_MARKUP) violations.push(...findPattern(source, pattern, label));
    }
    expect(formatViolations(violations)).toBe('');
  });

  it('src/components/Modal.tsx (the hand-rolled dialog) no longer exists', () => {
    expect(existsSync(join(SRC_ROOT, 'components', 'Modal.tsx'))).toBe(false);
  });

  it('every declared production dependency is imported by src/', () => {
    const declared = Object.keys(readPackageJson().dependencies).sort();
    const used = new Set<string>();
    for (const source of sources) for (const name of importedPackages(source)) used.add(name);
    // react-dom is imported by main.tsx; @ant-design/icons is a transitive of antd but imported directly when used.
    const unused = declared.filter((name) => !used.has(name));
    expect(unused, 'declared production dependencies never imported by src/').toEqual([]);
  });

  it('the mandated stack is declared: antd, recharts, @reduxjs/toolkit, react-redux, zustand, zod, react-router-dom', () => {
    const declared = Object.keys(readPackageJson().dependencies);
    for (const name of ['antd', 'recharts', '@reduxjs/toolkit', 'react-redux', 'zustand', 'zod', 'react-router-dom']) {
      expect(declared, `package.json dependencies must declare ${name}`).toContain(name);
    }
  });
});
