import { readdirSync, readFileSync, statSync } from 'node:fs';
import { join, relative, resolve, sep } from 'node:path';

/**
 * Shared scanner for the U11 `test/quality` guard tests.
 *
 * Every guard reads production or test source as text, masks comments so a prohibited token in a
 * comment cannot fail the build, and reports `file:line: label` strings. Guards are static: they
 * fail at `vitest run` time in the default gate without a browser or server.
 */

export const MODULE_ROOT = resolve(process.cwd());
export const SRC_ROOT = join(MODULE_ROOT, 'src');
export const TEST_ROOT = join(MODULE_ROOT, 'test');

export interface ScannedSource {
  /** Module-relative, forward-slash path. */
  readonly path: string;
  readonly absolutePath: string;
  /** Source with block and line comments replaced by spaces (line structure preserved). */
  readonly code: string;
  readonly lines: readonly string[];
}

export function listSources(root: string, options: { readonly extensions?: readonly string[]; readonly exclude?: (relativePath: string) => boolean } = {}): string[] {
  const extensions = options.extensions ?? ['.ts', '.tsx'];
  const out: string[] = [];
  const walk = (directory: string) => {
    for (const entry of readdirSync(directory).sort()) {
      const absolute = join(directory, entry);
      const rel = toModuleRelative(absolute);
      if (options.exclude?.(rel)) continue;
      if (statSync(absolute).isDirectory()) {
        walk(absolute);
      } else if (extensions.some((extension) => entry.endsWith(extension))) {
        out.push(absolute);
      }
    }
  };
  walk(root);
  return out;
}

export function readSource(absolutePath: string): ScannedSource {
  const raw = readFileSync(absolutePath, 'utf8');
  const code = maskComments(raw);
  return { path: toModuleRelative(absolutePath), absolutePath, code, lines: code.split(/\r?\n/) };
}

export function scan(root: string, options?: Parameters<typeof listSources>[1]): ScannedSource[] {
  return listSources(root, options).map(readSource);
}

export function toModuleRelative(absolutePath: string): string {
  return relative(MODULE_ROOT, absolutePath).split(sep).join('/');
}

/** Replace comment bodies with spaces while preserving line numbers and string contents. */
export function maskComments(source: string): string {
  let out = '';
  let index = 0;
  let state: 'code' | 'line' | 'block' | 'single' | 'double' | 'template' = 'code';
  while (index < source.length) {
    const char = source[index]!;
    const next = source[index + 1];
    switch (state) {
      case 'code':
        if (char === '/' && next === '/') { state = 'line'; out += '  '; index += 2; continue; }
        if (char === '/' && next === '*') { state = 'block'; out += '  '; index += 2; continue; }
        if (char === '\'') state = 'single';
        else if (char === '"') state = 'double';
        else if (char === '`') state = 'template';
        out += char; index += 1; continue;
      case 'line':
        if (char === '\n') { state = 'code'; out += char; } else out += ' ';
        index += 1; continue;
      case 'block':
        if (char === '*' && next === '/') { state = 'code'; out += '  '; index += 2; continue; }
        out += char === '\n' ? '\n' : ' '; index += 1; continue;
      case 'single':
      case 'double':
      case 'template': {
        const quote = state === 'single' ? '\'' : state === 'double' ? '"' : '`';
        if (char === '\\') { out += char + (next ?? ''); index += 2; continue; }
        if (char === quote) state = 'code';
        out += char; index += 1; continue;
      }
    }
  }
  return out;
}

export interface Violation { readonly file: string; readonly line: number; readonly label: string }

export function findPattern(source: ScannedSource, pattern: RegExp, label: string): Violation[] {
  const violations: Violation[] = [];
  source.lines.forEach((line, lineIndex) => {
    if (pattern.test(line)) violations.push({ file: source.path, line: lineIndex + 1, label });
    pattern.lastIndex = 0;
  });
  return violations;
}

export function formatViolations(violations: readonly Violation[]): string {
  return violations.map((violation) => `${violation.file}:${violation.line}: ${violation.label}`).join('\n');
}

export function readPackageJson(): { dependencies: Record<string, string>; devDependencies: Record<string, string> } {
  return JSON.parse(readFileSync(join(MODULE_ROOT, 'package.json'), 'utf8')) as {
    dependencies: Record<string, string>;
    devDependencies: Record<string, string>;
  };
}

/** Bare package name of an import specifier (`antd/es/table` → `antd`, `@scope/pkg/x` → `@scope/pkg`). */
export function packageNameOf(specifier: string): string {
  const parts = specifier.split('/');
  return specifier.startsWith('@') ? `${parts[0]}/${parts[1]}` : parts[0]!;
}

export function importedPackages(source: ScannedSource): Set<string> {
  const packages = new Set<string>();
  const importPattern = /(?:import|export)\s[^'"]*?from\s*['"]([^'"]+)['"]|import\s*\(\s*['"]([^'"]+)['"]\s*\)|import\s*['"]([^'"]+)['"]/g;
  for (const match of source.code.matchAll(importPattern)) {
    const specifier = match[1] ?? match[2] ?? match[3];
    if (specifier && !specifier.startsWith('.') && !specifier.startsWith('@src') && !specifier.startsWith('@generated') && !specifier.startsWith('node:')) {
      packages.add(packageNameOf(specifier));
    }
  }
  return packages;
}
