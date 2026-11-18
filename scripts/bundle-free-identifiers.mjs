import { analyze } from 'eslint-scope';
import globals from 'globals';
import { parseAst } from 'vite';

/**
 * Unbound identifiers in a production chunk (#3161).
 *
 * The shipped APK threw `ReferenceError: i is not defined` from xterm's DECRQM
 * handler (`requestMode`). The source was fine: esbuild's minifier (0.25.x,
 * fixed upstream in 0.28.2), while lowering `r ||= {}` for Vite's default
 * es2020 build target, dropped the never-assigned `let r` but kept the write,
 * producing `void 0 || (i = {})` with `i` declared nowhere. A module chunk is
 * strict code, so that write throws the first time a host sends a DECRQM query
 * (`CSI ? Ps $ p`, which TUIs like Antigravity CLI send to probe synchronized
 * output), and the throw kills xterm's write loop (the `terminal-stall` that
 * followed it). The dev server never minifies, so only a production bundle
 * can show the bug — and only the bundle can be checked for the class.
 *
 * This reports every reference in a chunk that resolves to no binding and is
 * not a known browser/ES global:
 *  - a WRITE to an unbound name is always reported: in module (strict) code it
 *    throws on every execution path;
 *  - a READ is allowed only when the chunk itself probes that name with
 *    `typeof name` (the `typeof process < "u" && process.env` feature-detect
 *    every isomorphic library uses), and reported otherwise.
 */

const KNOWN_GLOBALS = new Set([
  ...Object.keys(globals.builtin),
  ...Object.keys(globals.browser),
  ...Object.keys(globals.worker),
]);

/**
 * Reads of a non-browser global on a path a browser never takes, each with the
 * library that does it. Reads only: a write is never allowed.
 */
const ALLOWED_UNREACHABLE_READS = new Map([
  // hash-wasm's getGlobal(): `typeof self < "u" ? self : typeof window < "u" ? window : global`.
  ['global', 'hash-wasm getGlobal() Node fallback, after self and window'],
]);

/** 1-based line and 0-based column of an offset, for a readable report. */
function position(code, offset) {
  let line = 1;
  let lineStart = 0;
  for (let i = 0; i < offset; i++) {
    if (code.charCodeAt(i) === 10) {
      line++;
      lineStart = i + 1;
    }
  }
  return { line, column: offset - lineStart };
}

/** eslint-scope reads ESTree `range`; Rollup's parser records `start`/`end` only. */
function addRanges(root) {
  const stack = [root];
  while (stack.length > 0) {
    const node = stack.pop();
    if (Array.isArray(node)) {
      for (const child of node) if (child && typeof child === 'object') stack.push(child);
      continue;
    }
    if (typeof node.type === 'string' && typeof node.start === 'number') node.range = [node.start, node.end];
    for (const key in node) {
      const child = node[key];
      if (child && typeof child === 'object' && key !== 'range') stack.push(child);
    }
  }
}

/**
 * The unbound identifiers of one ES module chunk.
 * @param {string} code
 * @returns {{ name: string, write: boolean, line: number, column: number }[]}
 */
export function findFreeIdentifiers(code) {
  const ast = parseAst(code);
  addRanges(ast);
  const scopeManager = analyze(ast, { ecmaVersion: 2022, sourceType: 'module' });
  const unresolved = scopeManager.globalScope.through
    .filter((reference) => !KNOWN_GLOBALS.has(reference.identifier.name));
  if (unresolved.length === 0) return [];

  // Names the chunk feature-detects with `typeof name`: its other reads are guarded.
  const probed = new Set();
  const typeofOperand = /\btypeof\s*\(?\s*([A-Za-z_$][\w$]*)/g;
  for (const match of code.matchAll(typeofOperand)) probed.add(match[1]);

  return unresolved
    .filter((reference) => reference.isWrite() || !(
      probed.has(reference.identifier.name) || ALLOWED_UNREACHABLE_READS.has(reference.identifier.name)
    ))
    .map((reference) => ({
      name: reference.identifier.name,
      write: reference.isWrite(),
      ...position(code, reference.identifier.start),
    }));
}

/**
 * Throw when any JS chunk of a bundle has an unbound identifier. Runs in
 * `writeBundle`, after minification and after Vite's own late rewrites (the
 * `__VITE_PRELOAD__` placeholder is filled in `generateBundle`), so it checks
 * the bytes that ship.
 * @param {Record<string, { type: string, fileName: string, code?: string }>} bundle
 */
export function assertNoFreeIdentifiers(bundle) {
  const problems = [];
  for (const output of Object.values(bundle)) {
    if (output.type !== 'chunk' || typeof output.code !== 'string') continue;
    for (const found of findFreeIdentifiers(output.code)) {
      problems.push(`${output.fileName}:${found.line}:${found.column} ${found.write ? 'assigns' : 'reads'} undeclared \`${found.name}\``);
    }
  }
  if (problems.length > 0) {
    throw new Error(
      'Production bundle references identifiers that are declared nowhere; they throw ReferenceError at runtime (#3161):\n  ' +
        problems.join('\n  '),
    );
  }
}

/** Vite plugin: fail the production build on an unbound identifier in any chunk. */
export function refuseFreeIdentifiers() {
  return {
    name: 'pocketshell-refuse-free-identifiers',
    apply: 'build',
    writeBundle(_options, bundle) {
      assertNoFreeIdentifiers(bundle);
    },
  };
}
