/**
 * Regression guard for the v0.5.1-alpha UAT finding B-README-API: the GitHub
 * README documented `import OTelMobile from '...'` (a default export that does
 * not exist) while the shipped package exports the NAMED `Dash0Mobile`. Following
 * the README verbatim threw at runtime.
 *
 * This test parses the React Native code examples out of the repo README.md and
 * the tarball-bundled packages/react-native/README.md and asserts that every
 * symbol/import/method they show actually resolves against the built package. If
 * a doc example drifts from the real API again, this fails.
 */
import * as fs from 'fs';
import * as path from 'path';
import * as pkg from '../src';

const REPO_ROOT = path.resolve(__dirname, '../../..');
const ROOT_README = path.join(REPO_ROOT, 'README.md');
const BUNDLED_README = path.resolve(__dirname, '../README.md');

const PKG_NAME = '@barrysolomon/mobile-react-native';

/** Extract fenced ts/tsx/js/jsx code blocks that mention the package. */
function rnCodeBlocks(markdown: string): string[] {
  const blocks: string[] = [];
  const fence = /```(?:ts|tsx|js|jsx|typescript|javascript)\n([\s\S]*?)```/g;
  let m: RegExpExecArray | null;
  while ((m = fence.exec(markdown)) !== null) {
    if (m[1].includes(PKG_NAME)) blocks.push(m[1]);
  }
  return blocks;
}

describe('README RN examples resolve against the shipped API', () => {
  const rootMd = fs.readFileSync(ROOT_README, 'utf8');
  const bundledMd = fs.readFileSync(BUNDLED_README, 'utf8');

  it('root README exists and mentions the RN package', () => {
    expect(rootMd).toContain(PKG_NAME);
  });

  it.each([
    ['root README', () => rootMd],
    ['bundled README', () => bundledMd],
  ])('%s: no default import from the package (the B-README-API bug)', (_label, get) => {
    const md = get();
    // A default import looks like: import Something from '@barrysolomon/...'.
    // The package has NO default export — only named ones. This is the exact
    // regression that broke the UAT (`import OTelMobile from '...'`).
    const defaultImport = new RegExp(
      `import\\s+[A-Za-z_$][\\w$]*\\s+from\\s+['"]${PKG_NAME.replace(/[/\\^$*+?.()|[\]{}]/g, '\\$&')}['"]`,
    );
    expect(md).not.toMatch(defaultImport);
    // And specifically the old wrong symbol must be gone from RN examples.
    for (const block of rnCodeBlocks(md)) {
      expect(block).not.toMatch(/import\s+OTelMobile\s+from/);
    }
  });

  it.each([
    ['root README', () => rootMd],
    ['bundled README', () => bundledMd],
  ])('%s: every named import resolves to a real export', (_label, get) => {
    const md = get();
    const importRe = new RegExp(
      `import\\s*\\{([^}]+)\\}\\s*from\\s*['"]${PKG_NAME.replace(/[/\\^$*+?.()|[\]{}]/g, '\\$&')}['"]`,
      'g',
    );
    const seen: string[] = [];
    let m: RegExpExecArray | null;
    while ((m = importRe.exec(md)) !== null) {
      for (const raw of m[1].split(',')) {
        const name = raw.trim().split(/\s+as\s+/)[0].trim();
        if (name) seen.push(name);
      }
    }
    // The README must actually import something from the package.
    expect(seen.length).toBeGreaterThan(0);
    for (const name of seen) {
      expect(pkg).toHaveProperty(name);
      expect((pkg as Record<string, unknown>)[name]).toBeDefined();
    }
  });

  it.each([
    ['root README', () => rootMd],
    ['bundled README', () => bundledMd],
  ])('%s: every Dash0Mobile.<method>() call maps to a real method', (_label, get) => {
    const md = get();
    const methodRe = /Dash0Mobile\.([A-Za-z_$][\w$]*)\s*\(/g;
    const methods = new Set<string>();
    let m: RegExpExecArray | null;
    for (const block of rnCodeBlocks(get === undefined ? '' : md)) {
      while ((m = methodRe.exec(block)) !== null) methods.add(m[1]);
    }
    for (const name of methods) {
      expect(typeof (pkg.Dash0Mobile as Record<string, unknown>)[name]).toBe('function');
    }
  });
});
