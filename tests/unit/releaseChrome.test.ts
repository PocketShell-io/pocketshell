import { readdirSync, readFileSync, statSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import ts from 'typescript';
import { describe, expect, it } from 'vitest';
import { createSSRApp, h } from 'vue';
import { renderToString } from 'vue/server-renderer';
import { createPinia, setActivePinia } from 'pinia';
import AboutScreen from '../../src/components/AboutScreen.vue';
import BuildIntegrityAlert from '../../src/components/BuildIntegrityAlert.vue';
import { useNavigationStore } from '../../src/stores/navigation';
import { aboutBuildLine, connectionStateLabel, terminalStateLabel } from '../../src/session/releaseLabels';
import { createFileWorkspaceService, MAX_SFTP_FILE_BYTES, type FileEditSnapshot } from '../../src/session/files';
import type { SshCapabilityPlugin } from '../../src/native/sshCapability';
import viteConfig, { devBrowserModulesIn, refuseDevBrowserModules } from '../../vite.config';

const repoRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');

/** Every Android-owned screen template the user can reach (default shell and the shared app's Android views). */
function screenTemplates(): Array<{ file: string; template: string }> {
  const files = [
    'src/App.vue',
    ...readdirSync(path.join(repoRoot, 'src/components')).filter((name) => name.endsWith('.vue')).map((name) => `src/components/${name}`),
    ...readdirSync(path.join(repoRoot, 'src/sharedApp')).filter((name) => name.endsWith('.vue')).map((name) => `src/sharedApp/${name}`),
  ];
  return files.map((file) => {
    const source = readFileSync(path.join(repoRoot, file), 'utf8');
    const start = source.indexOf('<template>');
    const end = source.lastIndexOf('</template>');
    if (start < 0 || end < 0) throw new Error(`${file} has no template`);
    return { file, template: source.slice(start, end) };
  });
}

/**
 * The #3023 inventory of removed developer chrome. Each entry is a string or
 * marker that used to render on a normal screen; where it moved is recorded
 * on the issue (hidden data-* hooks or Settings -> About).
 */
const REMOVED_CHROME: Array<[string, RegExp]> = [
  ['build strip', /build-strip|Build verified|Build verification failed|Checking bundled assets/],
  ['rewrite preview chip', /rewrite[ -]preview|rewrite-chip|REWRITE PREVIEW/i],
  ['PTY badges', /SSH PTY|NO PTY/],
  ['SSH grid footnote', /accepted by SSH|terminalResizeStatus \}\}/],
  ['raw section labels', /class="eyebrow"|SETTINGS · |LOCAL SUPPORT|KEY VAULT|CONNECTED HOST|REMOTE FILES|REMOTE SESSIONS|>\s*TRANSPORT\s*</],
  ['home build/transport panels', /SSH resource status|Source and asset diagnostics|Core formatter|pocketshell-core revision|Bundled asset SHA-256|PTY channels|SFTP clients/],
  ['raw phase names', /currentPhase\.toUpperCase\(\)/],
  ['implementation names in copy', /pocketshell-core\.|shared core policy|Android SSH bridge|to xterm|pinned desktop palette|unimplemented switch/],
  ['bridge (internal transport name)', /\bbridge\b/i],
];

/** Every app source file under src/ (TypeScript modules and Vue single-file components). */
function sourceFiles(directory = 'src'): string[] {
  return readdirSync(path.join(repoRoot, directory)).flatMap((name) => {
    const relative = `${directory}/${name}`;
    if (statSync(path.join(repoRoot, relative)).isDirectory()) return sourceFiles(relative);
    return /\.(ts|vue)$/.test(name) && !name.endsWith('.d.ts') ? [relative] : [];
  });
}

/**
 * Sentence-like string literals in script code: error messages, status text
 * and labels that a screen can show (thrown messages reach the Files banner,
 * the connection message, the migration alert, Account sync, ...).
 * Comments are not literals, and identifiers, event codes and selectors have
 * no space between two words, so neither is collected. Console output is
 * skipped: it goes to logcat, never to a screen.
 */
function scriptProse(file: string): Array<{ line: number; text: string }> {
  let source = readFileSync(path.join(repoRoot, file), 'utf8');
  if (file.endsWith('.vue')) source = [...source.matchAll(/<script[^>]*>([\s\S]*?)<\/script>/g)].map((match) => match[1]).join('\n');
  const sourceFile = ts.createSourceFile(file, source, ts.ScriptTarget.Latest, true);
  const found: Array<{ line: number; text: string }> = [];
  const isConsoleArgument = (node: ts.Node): boolean => {
    for (let current: ts.Node | undefined = node.parent; current; current = current.parent) {
      if (ts.isCallExpression(current)) {
        const callee = current.expression;
        if (ts.isPropertyAccessExpression(callee) && ts.isIdentifier(callee.expression) && callee.expression.text === 'console') return true;
      }
      if (ts.isBlock(current) || ts.isSourceFile(current)) return false;
    }
    return false;
  };
  const visit = (node: ts.Node): void => {
    let text: string | undefined;
    if (ts.isStringLiteral(node) || ts.isNoSubstitutionTemplateLiteral(node)) text = node.text;
    else if (ts.isTemplateExpression(node)) text = node.head.text + node.templateSpans.map((span) => `… ${span.literal.text}`).join('');
    if (text !== undefined && /[A-Za-z]{2,}\s+[A-Za-z]{2,}/.test(text)
      && !ts.isImportDeclaration(node.parent) && !ts.isExportDeclaration(node.parent) && !isConsoleArgument(node)) {
      found.push({ line: sourceFile.getLineAndCharacterOfPosition(node.getStart()).line + 1, text });
    }
    ts.forEachChild(node, visit);
  };
  visit(sourceFile);
  return found;
}

/** Internal transport and implementation words, and code identifiers, that must not reach user-visible messages. */
const INTERNAL_WORDING = /\b(?:bridge|native|natively|plugin|capacitor|webview|xterm|pocketshell-core|javascript|JS|base64|PTY|adapter|JSON|snapshot|chunk|request ID)\b|(?-i:\b[a-z]+[A-Z][A-Za-z]*\b)/i;

/**
 * Script strings that match INTERNAL_WORDING but never reach a screen. Each
 * is an exact (file, text) pair with its reason, and must still exist, so the
 * list cannot go stale or hide a new message.
 */
const NOT_USER_VISIBLE: Array<{ file: string; text: string; reason: string }> = [
  { file: 'src/App.vue', text: 'waiting for a live PTY', reason: 'hidden terminal-resize-status data-status hook' },
  { file: 'src/App.vue', text: 'native bridge error', reason: 'hidden terminal-resize-status data-status hook' },
  { file: 'src/platform/androidDiagnostics.ts', text: 'Native crash', reason: 'crash-report category shared with core DiagnosticsPanel and the Java recorder; follow-up' },
];

/** Planning notes for the old-destination inventory: imported by tests only, never rendered. */
const NOT_RENDERED_MODULES = ['src/destinationInventory.ts'];

/**
 * Browser dev mode (#3022): injected by the Vite dev server ahead of
 * src/main.ts and refused by production builds, so its messages reach only a
 * developer's browser, never the APK. Nothing in the app may import it.
 */
const DEV_ONLY_DIRECTORY = 'src/dev/';

/** Every module source under src/ that the bundler could load, including plain JS. */
function moduleSources(directory = 'src'): string[] {
  return readdirSync(path.join(repoRoot, directory)).flatMap((name) => {
    const relative = `${directory}/${name}`;
    if (statSync(path.join(repoRoot, relative)).isDirectory()) return moduleSources(relative);
    return /\.(?:[cm]?[jt]sx?|vue)$/.test(name) && !name.endsWith('.d.ts') ? [relative] : [];
  });
}

/**
 * Every module specifier a source loads, in any quote style: static
 * import/export-from (including type-only and side-effect imports), dynamic
 * import(), import x = require(), import type nodes, Vite's import.meta.glob
 * and new URL(..., import.meta.url). Vue files contribute their script blocks.
 */
function moduleSpecifiers(file: string, source: string): string[] {
  const code = file.endsWith('.vue')
    ? [...source.matchAll(/<script\b[^>]*>([\s\S]*?)<\/script>/g)].map((match) => match[1]).join('\n')
    : source;
  const sourceFile = ts.createSourceFile(file, code, ts.ScriptTarget.Latest, true, ts.ScriptKind.TSX);
  const found: string[] = [];
  const literal = (node: ts.Node | undefined): string | undefined =>
    node && (ts.isStringLiteral(node) || ts.isNoSubstitutionTemplateLiteral(node)) ? node.text
      // A template with substitutions: its fixed head still names the directory.
      : node && ts.isTemplateExpression(node) ? node.head.text : undefined;
  const isImportMeta = (node: ts.Node): boolean => ts.isMetaProperty(node) && node.keywordToken === ts.SyntaxKind.ImportKeyword;
  const visit = (node: ts.Node): void => {
    let specifiers: Array<string | undefined> = [];
    if ((ts.isImportDeclaration(node) || ts.isExportDeclaration(node)) && node.moduleSpecifier) specifiers = [literal(node.moduleSpecifier)];
    else if (ts.isImportEqualsDeclaration(node) && ts.isExternalModuleReference(node.moduleReference)) specifiers = [literal(node.moduleReference.expression)];
    else if (ts.isImportTypeNode(node) && ts.isLiteralTypeNode(node.argument)) specifiers = [literal(node.argument.literal)];
    else if (ts.isCallExpression(node)) {
      const callee = node.expression;
      const isDynamicImport = callee.kind === ts.SyntaxKind.ImportKeyword;
      const isRequire = ts.isIdentifier(callee) && callee.text === 'require';
      const isGlob = ts.isPropertyAccessExpression(callee) && callee.name.text === 'glob' && isImportMeta(callee.expression);
      if (isDynamicImport || isRequire) specifiers = [literal(node.arguments[0])];
      else if (isGlob) {
        const pattern = node.arguments[0];
        specifiers = pattern && ts.isArrayLiteralExpression(pattern) ? pattern.elements.map(literal) : [literal(pattern)];
      }
    } else if (ts.isNewExpression(node) && ts.isIdentifier(node.expression) && node.expression.text === 'URL') {
      const base = node.arguments?.[1];
      if (base && ts.isPropertyAccessExpression(base) && base.name.text === 'url' && isImportMeta(base.expression)) specifiers = [literal(node.arguments?.[0])];
    }
    for (const specifier of specifiers) if (specifier !== undefined) found.push(specifier.replace(/^!/, ''));
    ts.forEachChild(node, visit);
  };
  visit(sourceFile);
  return found;
}

/** The production build's alias table, read from vite.config.ts itself (first match wins, as in Vite). */
const buildAliases = Object.entries(
  (viteConfig as unknown as (env: { command: string; mode: string }) => { resolve: { alias: Record<string, string> } })(
    { command: 'build', mode: 'production' },
  ).resolve.alias,
);
const tsCompilerOptions = ts.parseJsonConfigFileContent(
  ts.readConfigFile(path.join(repoRoot, 'tsconfig.json'), ts.sys.readFile).config, ts.sys, repoRoot,
).options;

/**
 * Where a specifier loaded from `file` lands, as repo-relative paths: once by
 * Vite's rules (relative, root-absolute "/src/...", and the build aliases,
 * which is how the bundle resolves it) and once by TypeScript's resolver with
 * the tsconfig paths. A bare package name resolves to nothing under src/.
 */
function resolvedTargets(file: string, specifier: string): string[] {
  const targets: string[] = [];
  const fromDirectory = path.dirname(path.join(repoRoot, file));
  if (specifier.startsWith('./') || specifier.startsWith('../') || specifier === '.' || specifier === '..') {
    targets.push(path.resolve(fromDirectory, specifier));
  } else if (specifier.startsWith('/')) {
    targets.push(path.join(repoRoot, specifier));
  } else {
    const alias = buildAliases.find(([key]) => specifier === key || specifier.startsWith(`${key}/`));
    if (alias) targets.push(path.join(alias[1], specifier.slice(alias[0].length)));
  }
  const viaTypeScript = ts.resolveModuleName(specifier, path.join(repoRoot, file), tsCompilerOptions, ts.sys).resolvedModule;
  if (viaTypeScript) targets.push(viaTypeScript.resolvedFileName);
  return targets.map((target) => path.relative(repoRoot, target).split(path.sep).join('/'));
}

/** The specifiers in `source` (as if it were `file`) that resolve into src/dev/. */
function devImportsIn(file: string, source: string): string[] {
  return moduleSpecifiers(file, source).filter((specifier) =>
    resolvedTargets(file, specifier).some((target) => `${target}/`.startsWith(DEV_ONLY_DIRECTORY)));
}

describe('release look (#3023)', () => {
  it('renders none of the removed developer chrome on any Android screen template', () => {
    const templates = screenTemplates();
    expect(templates.length).toBeGreaterThan(15);
    const hits: string[] = [];
    for (const { file, template } of templates) {
      for (const [label, pattern] of REMOVED_CHROME) {
        const match = template.match(pattern);
        if (match) hits.push(`${file}: ${label} (${match[0]})`);
      }
    }
    // Status badges are computed in script (e.g. the composer's mode badge).
    for (const { file } of templates) {
      const source = readFileSync(path.join(repoRoot, file), 'utf8');
      const literal = source.match(/'(?:SSH PTY|NO PTY)'/);
      if (literal) hits.push(`${file}: PTY badge literal (${literal[0]})`);
    }
    expect(hits).toEqual([]);
  });

  it('names diagnostic events without implementation terms', () => {
    const diagnostics = readFileSync(path.join(repoRoot, 'src/components/DiagnosticsScreen.vue'), 'utf8');
    const titles = diagnostics.slice(diagnostics.indexOf('const titles'), diagnostics.indexOf('return titles'));
    const labels = [...titles.matchAll(/:\s*'([^']+)'/g)].map((match) => match[1]);
    expect(labels.length).toBeGreaterThan(8);
    for (const label of labels) expect(label).not.toMatch(/\bbridge\b|\bnative\b|snapshot/i);
  });

  it('keeps build identity and SSH state as hidden test hooks, not visible text', () => {
    const app = readFileSync(path.join(repoRoot, 'src/App.vue'), 'utf8');
    for (const testId of ['build-status', 'ssh-resources', 'terminal-resize-status']) {
      const at = app.indexOf(`data-testid="${testId}"`);
      expect(at, testId).toBeGreaterThan(0);
      const tag = app.slice(app.lastIndexOf('<', at), app.indexOf('>', at) + 1);
      expect(tag, testId).toMatch(/\shidden\s/);
    }
    expect(app).toMatch(/data-testid="build-status"[\s\S]*?:data-state="buildStatusTone"[\s\S]*?:data-core-revision="coreSourceRevision"[\s\S]*?:data-bundle-hash="bundleHash"/);
    expect(app).toMatch(/data-testid="terminal-resize-status" :data-status="terminalResizeStatus"/);
    // The failure reason stays available to support, on the hook and in About.
    expect(app).toMatch(/data-testid="build-status"[\s\S]*?:data-failure-reason="buildFailureReason"/);
    expect(app).toMatch(/<AboutScreen[\s\S]*?:failure-reason="buildFailureReason"/);
  });

  it('names connection states in plain language', () => {
    expect(connectionStateLabel('idle')).toBe('Not connected');
    expect(connectionStateLabel('connecting')).toBe('Connecting…');
    expect(connectionStateLabel('live')).toBe('Connected');
    expect(connectionStateLabel('reconnecting')).toBe('Reconnecting…');
    expect(connectionStateLabel('lost')).toBe('Disconnected');
    expect(connectionStateLabel('error')).toBe('Connection failed');
    expect(terminalStateLabel('lost')).toBe('Disconnected');
    expect(terminalStateLabel('idle')).toBe('Disconnected');
    expect(terminalStateLabel('reconnecting')).toBe('Reconnecting…');
    for (const phase of ['idle', 'connecting', 'awaiting-trust', 'connected', 'listing', 'attaching', 'live', 'background', 'reconnecting', 'lost', 'error'] as const) {
      expect(connectionStateLabel(phase)).not.toMatch(/PTY|[A-Z]{3,}|-/);
      expect(terminalStateLabel(phase)).not.toMatch(/PTY|[A-Z]{3,}|-/);
    }
  });

  it('puts the build identity in one plain About line', () => {
    const core = 'abcdef0123456789abcdef0123456789abcdef01';
    expect(aboutBuildLine('verified', core, '0.6.0')).toBe('Version 0.6.0 · build abcdef012345 · files checked');
    expect(aboutBuildLine('checking', core, 'unknown')).toBe('build abcdef012345 · checking files…');
    expect(aboutBuildLine('error', core, '')).toBe('build abcdef012345 · file check failed');
    const about = readFileSync(path.join(repoRoot, 'src/components/AboutScreen.vue'), 'utf8');
    expect(about).toMatch(/data-testid="about-build-identity"[\s\S]*?:data-core-revision="coreRevision"[\s\S]*?>\{\{ buildLine \}\}</);
  });

  it('shows a plain reinstall alert on home only when the build check fails', async () => {
    const html = await renderToString(createSSRApp({ render: () => h(BuildIntegrityAlert) }));
    expect(html).toContain('role="alert"');
    expect(html).toContain('data-testid="build-integrity-error"');
    expect(html).toContain('This copy of PocketShell looks damaged');
    expect(html).toContain('Reinstall the app to fix this.');
    const app = readFileSync(path.join(repoRoot, 'src/App.vue'), 'utf8');
    expect(app).toMatch(/<BuildIntegrityAlert v-if="buildStatusTone === 'error'" \/>/);
    expect(app.match(/<BuildIntegrityAlert\b/g)).toHaveLength(1);
  });

  it('tells About readers to reinstall, with the failure reason for support, only on a failed check', async () => {
    const reason = 'Bundled asset hash mismatch: assets/index-abc.js';
    const renderAbout = (buildState: 'checking' | 'verified' | 'error', failureReason = '') => {
      const pinia = createPinia();
      setActivePinia(pinia);
      useNavigationStore().open('about');
      const app = createSSRApp({
        render: () => h(AboutScreen, { buildState, coreRevision: 'abcdef0123456789abcdef0123456789abcdef01', bundleHash: '', failureReason }),
      });
      app.use(pinia);
      return renderToString(app);
    };
    const failed = await renderAbout('error', reason);
    expect(failed).toMatch(/<div[^>]*role="alert"[^>]*data-testid="about-integrity-error"/);
    expect(failed).toContain('This copy of PocketShell failed its integrity check. Reinstall the app to fix this.');
    expect(failed).toContain(`Details for support: ${reason}`);
    expect(failed).toContain('build abcdef012345 · file check failed');
    for (const state of ['checking', 'verified'] as const) {
      const html = await renderAbout(state);
      expect(html, state).not.toContain('role="alert"');
      expect(html, state).not.toContain('Reinstall the app');
      expect(html, state).not.toContain('Details for support');
    }
  });
  it('keeps internal transport and implementation words out of user-visible script strings', () => {
    const files = sourceFiles();
    expect(files.length).toBeGreaterThan(60);
    expect(files).toContain('src/session/files.ts');
    expect(files).toContain('src/components/FileWorkspaceScreen.vue');
    expect(files.some((file) => file.startsWith(DEV_ONLY_DIRECTORY))).toBe(true);
    const modules = moduleSources();
    expect(modules.filter((file) => file.startsWith(DEV_ONLY_DIRECTORY)).length).toBeGreaterThan(5);
    let specifierCount = 0;
    const devImporters: string[] = [];
    for (const file of modules) {
      if (file.startsWith(DEV_ONLY_DIRECTORY)) continue;
      const source = readFileSync(path.join(repoRoot, file), 'utf8');
      specifierCount += moduleSpecifiers(file, source).length;
      devImporters.push(...devImportsIn(file, source).map((specifier) => `${file} -> ${specifier}`));
    }
    expect(specifierCount, 'the import scan read no specifiers').toBeGreaterThan(200);
    expect(devImporters, 'app sources must not import browser dev mode').toEqual([]);
    for (const module of NOT_RENDERED_MODULES) {
      const name = path.basename(module, '.ts');
      const importers = files.filter((file) => file !== module && new RegExp(`from '[./]*/${name}'`).test(readFileSync(path.join(repoRoot, file), 'utf8')));
      expect(importers, `${module} must stay unrendered`).toEqual([]);
    }
    const allowed = new Set(NOT_USER_VISIBLE.map(({ file, text }) => `${file}\u0000${text}`));
    const seenAllowed = new Set<string>();
    let scanned = 0;
    const hits: string[] = [];
    for (const file of files) {
      if (NOT_RENDERED_MODULES.includes(file) || file.startsWith(DEV_ONLY_DIRECTORY)) continue;
      for (const { line, text } of scriptProse(file)) {
        scanned += 1;
        const match = text.match(INTERNAL_WORDING);
        if (!match) continue;
        const key = `${file}\u0000${text}`;
        if (allowed.has(key)) seenAllowed.add(key);
        else hits.push(`${file}:${line}: "${match[0]}" in ${JSON.stringify(text)}`);
      }
    }
    expect(scanned).toBeGreaterThan(300);
    expect(hits).toEqual([]);
    expect([...allowed].filter((key) => !seenAllowed.has(key)), 'stale NOT_USER_VISIBLE entries').toEqual([]);
  });

  it('finds an import of browser dev mode in every module specifier shape', () => {
    // [importing file, source, specifiers that must be flagged]
    const shapes: Array<[string, string, string[]]> = [
      ['src/session/probe.ts', "import { a } from '../dev/browser/bytes';", ['../dev/browser/bytes']],
      ['src/probe.ts', "import { a } from './dev/browser/bytes';", ['./dev/browser/bytes']],
      ['src/session/probe.ts', "import { a } from '@/dev/browser/bytes';", ['@/dev/browser/bytes']],
      ['src/probe.ts', 'import { a } from "./dev/browser/bytes";', ['./dev/browser/bytes']],
      ['src/probe.ts', "export const load = () => import('./dev/browser/bytes');", ['./dev/browser/bytes']],
      ['src/session/probe.ts', "export { a } from '@/dev/browser/bytes';", ['@/dev/browser/bytes']],
      ['src/session/probe.ts', 'export * from "../dev/browser/bytes";', ['../dev/browser/bytes']],
      ['src/session/probe.ts', 'export * as dev from "@/dev/browser";', ['@/dev/browser']],
      ['src/session/probe.ts', "import '@/dev/browser/install';", ['@/dev/browser/install']],
      ['src/session/probe.ts', 'import type { A } from "@/dev/browser/bytes";', ['@/dev/browser/bytes']],
      ['src/session/probe.ts', 'const m = import(`@/dev/browser/${name}`);', ['@/dev/browser/']],
      ['src/session/probe.ts', 'const m = await import(/* @vite-ignore */ "@/dev/browser/bytes.ts");', ['@/dev/browser/bytes.ts']],
      ['src/session/probe.ts', "const all = import.meta.glob(['../dev/browser/*.ts']);", ['../dev/browser/*.ts']],
      ['src/session/probe.ts', "const url = new URL('../dev/browser/install.ts', import.meta.url);", ['../dev/browser/install.ts']],
      ['src/session/probe.ts', "import install from '/src/dev/browser/install.ts?url';", ['/src/dev/browser/install.ts?url']],
      ['src/components/Probe.vue', '<template><p /></template>\n<script setup lang="ts">\nimport { a } from "@/dev/browser/bytes";\n</script>', ['@/dev/browser/bytes']],
      ['src/session/probe.ts', 'import { a } from "../dev/browser/bytes"; import { b } from "@/dev/browser/trace";', ['../dev/browser/bytes', '@/dev/browser/trace']],
      // Not browser dev mode: other directories that start with "dev", the shared UI, a sibling "dev" folder elsewhere, plain strings.
      ['src/session/probe.ts', "import { a } from '@/devices/list'; import { b } from './developer';", []],
      ['src/session/probe.ts', "import { a } from '@ui/dev/tokens'; import { b } from './dev/helper';", []],
      ['src/session/probe.ts', "const text = '../dev/browser/bytes'; console.log(\"@/dev/browser\");", []],
    ];
    for (const [file, source, expected] of shapes) expect.soft(devImportsIn(file, source), `${file}: ${source}`).toEqual(expected);
  });

  it('fails a production build whose module graph reaches browser dev mode', () => {
    const configFn = viteConfig as unknown as (env: { command: string; mode: string }) => { plugins: Array<{ name?: string }> };
    expect(configFn({ command: 'build', mode: 'production' }).plugins.flat().map((entry) => entry?.name)).toContain('pocketshell-refuse-dev-browser-modules');
    const plugin = refuseDevBrowserModules();
    expect(plugin.apply).toBe('build');
    const devModule = path.join(repoRoot, 'src/dev/browser/seedHosts.ts');
    const appModules = [path.join(repoRoot, 'src/main.ts'), path.join(repoRoot, 'src/App.vue?vue&type=script&setup=true&lang.ts'), '\0plugin-vue:export-helper', path.join(repoRoot, 'src/devices.ts')];
    expect(devBrowserModulesIn([...appModules, devModule, `\0${devModule}?commonjs-proxy`])).toEqual(['src/dev/browser/seedHosts.ts', 'src/dev/browser/seedHosts.ts']);
    const buildEnd = plugin.buildEnd as (this: { getModuleIds: () => IterableIterator<string> }, error?: Error) => void;
    expect(() => buildEnd.call({ getModuleIds: () => appModules.values() })).not.toThrow();
    expect(() => buildEnd.call({ getModuleIds: () => [...appModules, devModule].values() }))
      .toThrow(/must never ship: src\/dev\/browser\/seedHosts\.ts$/);
  });

  it('tells Files users about the transfer limit without naming the transport', async () => {
    const service = createFileWorkspaceService({} as SshCapabilityPlugin, {
      connection: { connectionId: 'connection-1', generationId: 'generation-1' },
      rootDirectory: '/home/alex',
      nextRequestId: () => 'release-look-1',
    });
    // Saving an edit over the limit fails before any remote call, so the capability is never touched.
    const snapshot = {
      path: '/home/alex/notes.txt',
      text: '',
      metadata: { isDirectory: false, sizeBytes: 1, modifiedEpochMs: 1 },
    } as unknown as FileEditSnapshot;
    const error = await service.saveText(snapshot, 'a'.repeat(MAX_SFTP_FILE_BYTES + 1)).catch((cause: unknown) => cause);
    expect(error).toMatchObject({ code: 'file-too-large' });
    expect((error as Error).message).toMatch(/^This file is larger than the .+ transfer limit\.$/);
    const screen = readFileSync(path.join(repoRoot, 'src/components/FileWorkspaceScreen.vue'), 'utf8');
    expect(screen).toContain('statusMessage.value = `This file is ${formatBytes(entry.sizeBytes)}, larger than the ${formatBytes(MAX_SFTP_FILE_BYTES)} transfer limit.`;');
  });
});
