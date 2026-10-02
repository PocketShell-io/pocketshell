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
      if (NOT_RENDERED_MODULES.includes(file)) continue;
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
