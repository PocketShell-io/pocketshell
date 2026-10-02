/**
 * Browser dev mode (#3022), `dev:mock`: a believable fake dev box.
 *
 * It answers the host commands the app actually sends (the `pocketshell`
 * CLI's JSON verbs, the bootstrap probes, the listener scan, provider usage)
 * in the exact shapes pocketshell-core parses, runs a tiny line-editing shell
 * behind each session PTY, and keeps an in-memory file tree for SFTP. Unknown
 * commands answer `command not found` and are logged, so a missing verb is
 * obvious and easy to add here.
 */

export interface MockSession {
  name: string;
  id: string;
  workspace: string;
  tag: string;
  engine: string | null;
  profile: string | null;
  agent: string | null;
  agentState: 'idle' | 'waiting' | 'working' | null;
  createdEpoch: number;
  activityEpoch: number;
}

export interface MockExecResult {
  exitCode: number;
  stdout: string;
  stderr: string;
}

export interface MockFile {
  kind: 'file' | 'directory';
  content: Uint8Array;
  modifiedEpochMs: number;
}

export const MOCK_HOME = '/home/dev';
export const MOCK_HOSTNAME = 'devbox';

const encoder = new TextEncoder();
const decoder = new TextDecoder();

/** Split a POSIX shell command into words (single/double quotes, backslashes). */
export function shellWords(command: string): string[] {
  const words: string[] = [];
  let current = '';
  let inWord = false;
  for (let index = 0; index < command.length; index += 1) {
    const char = command[index];
    if (char === "'") {
      const end = command.indexOf("'", index + 1);
      current += command.slice(index + 1, end < 0 ? command.length : end);
      index = end < 0 ? command.length : end;
      inWord = true;
    } else if (char === '"') {
      index += 1;
      while (index < command.length && command[index] !== '"') {
        if (command[index] === '\\' && index + 1 < command.length) index += 1;
        current += command[index];
        index += 1;
      }
      inWord = true;
    } else if (char === '\\' && index + 1 < command.length) {
      current += command[index + 1];
      index += 1;
      inWord = true;
    } else if (/\s/u.test(char)) {
      if (inWord) words.push(current);
      current = '';
      inWord = false;
    } else {
      current += char;
      inWord = true;
    }
  }
  if (inWord) words.push(current);
  return words;
}

/**
 * Peel core's `pathAwareCommand` wrapper (`/bin/sh -lc 'export PATH=…; CMD'`)
 * and any `export VAR=…;` prefixes, returning the command itself.
 */
export function unwrapCommand(command: string): string {
  let inner = command.trim();
  const wrapped = /^\/bin\/sh -lc '(.*)'$/su.exec(inner);
  if (wrapped) inner = wrapped[1].replace(/'\\''/gu, "'");
  while (/^export [^;]*;\s*/u.test(inner)) inner = inner.replace(/^export [^;]*;\s*/u, '');
  return inner.trim();
}

function json(value: unknown): MockExecResult {
  return { exitCode: 0, stdout: `${JSON.stringify(value, null, 2)}\n`, stderr: '' };
}

function failure(exitCode: number, stderr: string): MockExecResult {
  return { exitCode, stdout: '', stderr: stderr.endsWith('\n') ? stderr : `${stderr}\n` };
}

const MOCK_TOOLS: Record<string, string> = {
  pocketshell: 'pocketshell 0.6.0 (browser dev mock)',
  a: 'a 0.9.0 (browser dev mock)',
  aplexer: 'aplexer 0.9.0 (browser dev mock)',
  tmux: 'tmux 3.4',
  tmuxctl: 'tmuxctl 0.6.0 (browser dev mock)',
  uv: 'uv 0.8.0',
  systemctl: 'systemd 255',
};

const MOCK_ENGINES = [
  { id: 'claude', family: 'claude', harness: 'claude', label: 'Claude', provider_mark: 'Anthropic', usage_provider: 'claude', enabled: true, available: true, available_for_create: true, unavailable_reason: null },
  { id: 'codex', family: 'codex', harness: 'codex', label: 'Codex', provider_mark: 'OpenAI', usage_provider: 'codex', enabled: true, available: true, available_for_create: true, unavailable_reason: null },
  { id: 'shell', family: 'shell', harness: 'shell', label: 'Shell', provider_mark: '', usage_provider: null, enabled: true, available: true, available_for_create: true, unavailable_reason: null },
];

const MOCK_PROFILES = [
  { name: 'default', engine: 'claude', config_dir: `${MOCK_HOME}/.claude`, default: true },
  { name: 'work', engine: 'claude', config_dir: `${MOCK_HOME}/.claude-work`, default: false },
  { name: 'default', engine: 'codex', config_dir: `${MOCK_HOME}/.codex`, default: true },
];

function usageNdjson(now: number): string {
  const at = (hours: number) => new Date(now + hours * 3_600_000).toISOString().replace(/\.\d+Z$/u, 'Z');
  return [
    { provider: 'claude', status: 'ok', windows: { '5h': { percent_remaining: 64.0, reset_at: at(2.5) }, '7d': { percent_remaining: 81.0, reset_at: at(90) } }, block_reason: null, error: null, details: {} },
    { provider: 'codex', status: 'ok', windows: { '5h': { percent_remaining: 12.0, reset_at: at(1) }, '7d': { percent_remaining: 47.0, reset_at: at(120) } }, block_reason: null, error: null, details: { limit_reached: false, reset_credits: [{ expires_at: at(200), status: 'available', title: 'Full reset' }], reset_credits_available: 1, reset_credits_error: null } },
    { provider: 'copilot', status: 'blocked', windows: { monthly: { percent_remaining: 1.0, reset_at: at(300) } }, block_reason: 'monthly premium requests exhausted', error: null, details: {} },
    { provider: 'gemini', status: 'error', windows: {}, block_reason: null, error: 'not logged in on this host', details: {} },
  ].map((row) => JSON.stringify(row)).join('\n') + '\n';
}

const LISTENERS = [
  { port: 22, address: '0.0.0.0', process: null },
  { port: 3000, address: '127.0.0.1', process: { name: 'node', pid: 4101, cwd: `${MOCK_HOME}/git/pocketshell-site` } },
  { port: 5173, address: '0.0.0.0', process: { name: 'node', pid: 4202, cwd: `${MOCK_HOME}/git/pocketshell` } },
  { port: 5432, address: '127.0.0.1', process: { name: 'postgres', pid: 812, cwd: '/var/lib/postgresql' } },
  { port: 8000, address: '0.0.0.0', process: { name: 'python3', pid: 5303, cwd: `${MOCK_HOME}/notes` } },
];

function listenerScan(): string {
  const header = 'State  Recv-Q Send-Q Local Address:Port  Peer Address:PortProcess';
  const line = (listener: typeof LISTENERS[number], withProcess: boolean) =>
    `LISTEN 0      128   ${`${listener.address}:${listener.port}`.padEnd(20)} 0.0.0.0:*    ${withProcess && listener.process ? `users:(("${listener.process.name}",pid=${listener.process.pid},fd=20))` : ''}`;
  return [
    '<<<PS_SS_TLN>>>', header, ...LISTENERS.map((listener) => line(listener, false)),
    '<<<PS_SS_TLNP>>>', header, ...LISTENERS.map((listener) => line(listener, true)),
    '<<<PS_NETSTAT_TLNP>>>', '<<<PS_NETSTAT_TLN>>>', '',
  ].join('\n');
}

function seedFiles(now: number): Map<string, MockFile> {
  const files = new Map<string, MockFile>();
  const dir = (path: string) => files.set(path, { kind: 'directory', content: new Uint8Array(), modifiedEpochMs: now - 86_400_000 });
  const file = (path: string, text: string, ageMs = 3_600_000) =>
    files.set(path, { kind: 'file', content: encoder.encode(text), modifiedEpochMs: Math.floor((now - ageMs) / 1000) * 1000 });
  ['/', '/home', MOCK_HOME, `${MOCK_HOME}/git`, `${MOCK_HOME}/git/pocketshell`, `${MOCK_HOME}/git/pocketshell/src`,
    `${MOCK_HOME}/git/pocketshell/docs`, `${MOCK_HOME}/git/pocketshell-site`, `${MOCK_HOME}/notes`, `${MOCK_HOME}/inbox`].forEach(dir);
  file(`${MOCK_HOME}/git/pocketshell/README.md`, '# PocketShell\n\nVoice-first SSH client. (This file lives on the browser dev mock host.)\n');
  file(`${MOCK_HOME}/git/pocketshell/package.json`, '{\n  "name": "pocketshell",\n  "private": true\n}\n');
  file(`${MOCK_HOME}/git/pocketshell/src/main.ts`, "import { boot } from './boot';\n\nvoid boot();\n", 600_000);
  file(`${MOCK_HOME}/git/pocketshell/docs/roadmap.md`, '# Roadmap\n\n- [x] Browser dev mode\n- [ ] Everything else\n');
  file(`${MOCK_HOME}/git/pocketshell-site/index.html`, '<!doctype html>\n<title>PocketShell</title>\n');
  file(`${MOCK_HOME}/notes/todo.md`, '# TODO\n\n1. Try the composer\n2. Dictate something\n3. Open Files\n', 120_000);
  file(`${MOCK_HOME}/notes/server.py`, 'import http.server\nhttp.server.test(port=8000)\n');
  file(`${MOCK_HOME}/.bashrc`, 'export EDITOR=vim\n');
  return files;
}

export interface MockHostOptions {
  now?: () => number;
  log?: (line: string) => void;
}

/** A per-page fake dev box: sessions, workspaces, files and host commands. */
export class MockHost {
  readonly sessions: MockSession[];
  readonly workspaces: string[];
  readonly files: Map<string, MockFile>;
  private readonly now: () => number;
  private readonly log: (line: string) => void;
  private nextSessionNumber = 1;

  constructor(options: MockHostOptions = {}) {
    this.now = options.now ?? Date.now;
    this.log = options.log ?? ((line) => console.warn(line));
    const seconds = Math.floor(this.now() / 1000);
    const session = (workspace: string, tag: string, agent: string | null, agentState: MockSession['agentState'], ageMinutes: number): MockSession => ({
      name: `${workspace.split('/').pop()}:${tag}`,
      id: `00000000-0000-4000-8000-${String(this.nextSessionNumber++).padStart(12, '0')}`,
      workspace,
      tag,
      engine: agent,
      profile: agent ? 'default' : null,
      agent,
      agentState,
      createdEpoch: seconds - ageMinutes * 60 - 600,
      activityEpoch: seconds - ageMinutes * 60,
    });
    this.workspaces = [`${MOCK_HOME}/git/pocketshell`, `${MOCK_HOME}/git/pocketshell-site`, `${MOCK_HOME}/notes`];
    this.sessions = [
      session(`${MOCK_HOME}/git/pocketshell`, 'claude-main', 'claude', 'working', 1),
      session(`${MOCK_HOME}/git/pocketshell`, 'codex-review', 'codex', 'waiting', 7),
      session(`${MOCK_HOME}/git/pocketshell`, 'shell', null, null, 42),
      session(`${MOCK_HOME}/git/pocketshell-site`, 'dev-server', null, 'idle', 15),
      session(`${MOCK_HOME}/notes`, 'scratch', null, null, 180),
    ];
    this.files = seedFiles(this.now());
  }

  findSession(name: string): MockSession | undefined {
    return this.sessions.find((session) => session.name === name);
  }

  touch(session: MockSession): void {
    session.activityEpoch = Math.floor(this.now() / 1000);
  }

  sessionsListing(): unknown {
    return {
      schema: 3,
      sessions: this.sessions.map((session) => ({
        name: session.name,
        id: session.id,
        workspace: session.workspace,
        tag: session.tag,
        engine: session.engine,
        profile: session.profile,
        agent: session.agent,
        agent_state: session.agentState,
        agent_state_source: session.agentState ? 'reported' : null,
        attached: false,
        created_epoch: session.createdEpoch,
        activity_epoch: session.activityEpoch,
        phase: 'running',
        alive: true,
      })),
      errors: [],
    };
  }

  /** Run one non-interactive command the way `exec` would on the box. */
  exec(command: string): MockExecResult {
    const inner = unwrapCommand(command);
    if (inner.startsWith('echo "<<<PS_SS_TLN>>>"')) return { exitCode: 0, stdout: listenerScan(), stderr: '' };
    if (inner.startsWith('for pid in ')) {
      const pids = inner.slice('for pid in '.length).split(';')[0].trim().split(/\s+/u).map(Number);
      const lines = pids.map((pid) => LISTENERS.find((listener) => listener.process?.pid === pid)?.process)
        .filter((process): process is NonNullable<typeof process> => Boolean(process))
        .map((process) => `${process.pid}\t${process.cwd}`);
      return { exitCode: 0, stdout: lines.length ? `${lines.join('\n')}\n` : '', stderr: '' };
    }
    if (inner === 'printf %s "$HOME"') return { exitCode: 0, stdout: MOCK_HOME, stderr: '' };
    if (/systemctl --user is-active /u.test(inner)) return { exitCode: 0, stdout: 'active\n', stderr: '' };
    if (/systemctl --user is-enabled /u.test(inner)) return { exitCode: 0, stdout: 'enabled\n', stderr: '' };

    const words = shellWords(inner);
    if (words[0] === 'command' && words[1] === '-v' && words.length === 3) {
      return MOCK_TOOLS[words[2]] ? { exitCode: 0, stdout: `/usr/local/bin/${words[2]}\n`, stderr: '' } : failure(1, '');
    }
    if (words.length === 2 && words[1] === '--version' && MOCK_TOOLS[words[0]]) {
      return { exitCode: 0, stdout: `${MOCK_TOOLS[words[0]]}\n`, stderr: '' };
    }
    if (words[0] === 'pocketshell') return this.pocketshell(words.slice(1), inner);
    const shell = this.runShellLine(inner, MOCK_HOME);
    if (shell) return { exitCode: shell.exitCode, stdout: shell.output, stderr: '' };
    this.log(`[dev mock] host has no answer for: ${inner}`);
    return failure(127, `mock host: command not found: ${words[0] ?? inner}`);
  }

  private pocketshell(args: string[], inner: string): MockExecResult {
    const [group, verb] = args;
    const flag = (name: string) => {
      const index = args.indexOf(name);
      return index >= 0 ? args[index + 1] ?? null : null;
    };
    const afterDashes = () => {
      const index = args.indexOf('--');
      return index >= 0 ? args[index + 1] ?? '' : '';
    };
    if (group === 'sessions' && verb === 'list') return json(this.sessionsListing());
    if (group === 'sessions' && verb === 'warnings') return json([]);
    if (group === 'sessions' && verb === 'ack') return json({ schema: 1, acked: 0 });
    if (group === 'sessions' && verb === 'create') {
      const tag = afterDashes();
      if (!/^[\w.-]{1,64}$/u.test(tag)) return { exitCode: 2, stdout: JSON.stringify({ schema: 3, error: 'Session names may use letters, digits, dot, dash and underscore.' }), stderr: '' };
      const workspace = flag('--cwd') ?? `${MOCK_HOME}/git/pocketshell`;
      const name = `${workspace.split('/').pop()}:${tag}`;
      const existing = this.findSession(name);
      if (existing) return json({ schema: 3, name, id: existing.id, created: false });
      const engine = flag('--engine');
      const seconds = Math.floor(this.now() / 1000);
      const session: MockSession = {
        name,
        id: globalThis.crypto.randomUUID(),
        workspace,
        tag,
        engine: engine && engine !== 'shell' ? engine : null,
        profile: flag('--profile'),
        agent: engine && engine !== 'shell' ? engine : null,
        agentState: engine && engine !== 'shell' ? 'idle' : null,
        createdEpoch: seconds,
        activityEpoch: seconds,
      };
      this.sessions.unshift(session);
      if (!this.workspaces.includes(workspace)) this.workspaces.push(workspace);
      return json({ schema: 3, name, id: session.id, created: true });
    }
    if (group === 'sessions' && verb === 'kill') {
      const name = afterDashes();
      const index = this.sessions.findIndex((session) => session.name === name);
      if (index < 0) return failure(1, `no session named ${name}`);
      this.sessions.splice(index, 1);
      return { exitCode: 0, stdout: '', stderr: '' };
    }
    if (group === 'workspaces' && (verb === 'list' || verb === 'add' || verb === 'remove')) {
      const target = verb === 'list' ? null : args[2];
      if (verb === 'add' && target && !this.workspaces.includes(target)) this.workspaces.push(target);
      if (verb === 'remove' && target && this.workspaces.includes(target)) this.workspaces.splice(this.workspaces.indexOf(target), 1);
      return json({
        host: flag('--host') ?? MOCK_HOSTNAME,
        schema: 1,
        workspaces: this.workspaces.map((path) => ({ path, display_path: path.replace(MOCK_HOME, '~') })),
      });
    }
    if (group === 'engines' && verb === 'list') return json({ engines: MOCK_ENGINES });
    if (group === 'profiles' && verb === 'list') return json({ profiles: MOCK_PROFILES });
    if (group === 'usage') return { exitCode: 0, stdout: usageNdjson(this.now()), stderr: '' };
    this.log(`[dev mock] pocketshell verb not mocked: ${inner}`);
    return failure(2, `pocketshell: '${args.join(' ')}' is not available on the browser dev mock host`);
  }

  /** The few commands the PTY shell understands. Null means "not a builtin". */
  runShellLine(line: string, cwd: string): { output: string; exitCode: number; cwd?: string; clear?: boolean; exit?: boolean } | null {
    const words = shellWords(line);
    const [name, ...args] = words;
    const resolve = (target: string) => {
      const expanded = target === '~' || target.startsWith('~/') ? `${MOCK_HOME}${target.slice(1)}` : target;
      return normalizePath(expanded.startsWith('/') ? expanded : `${cwd}/${expanded}`);
    };
    switch (name) {
      case undefined: return { output: '', exitCode: 0 };
      case 'echo': return { output: `${args.join(' ')}\n`, exitCode: 0 };
      case 'pwd': return { output: `${cwd}\n`, exitCode: 0 };
      case 'whoami': return { output: 'dev\n', exitCode: 0 };
      case 'hostname': return { output: `${MOCK_HOSTNAME}\n`, exitCode: 0 };
      case 'date': return { output: `${new Date(this.now()).toString()}\n`, exitCode: 0 };
      case 'uname': return { output: 'Linux devbox 6.8.0-mock x86_64 GNU/Linux\n', exitCode: 0 };
      case 'clear': return { output: '', exitCode: 0, clear: true };
      case 'exit': return { output: 'logout\n', exitCode: 0, exit: true };
      case 'seq': {
        const end = Math.min(500, Number(args[0] ?? 0) || 0);
        return { output: Array.from({ length: end }, (_, index) => `${index + 1}\n`).join(''), exitCode: 0 };
      }
      case 'help': return {
        output: 'browser dev mock shell: echo, pwd, cd, ls, cat, seq, date, whoami, hostname, uname, clear, exit\n',
        exitCode: 0,
      };
      case 'cd': {
        const target = resolve(args[0] ?? MOCK_HOME);
        if (this.files.get(target)?.kind !== 'directory') return { output: `cd: ${args[0]}: No such file or directory\n`, exitCode: 1 };
        return { output: '', exitCode: 0, cwd: target };
      }
      case 'ls': {
        const target = resolve(args.find((arg) => !arg.startsWith('-')) ?? '.');
        const names = this.childrenOf(target).map(([path, file]) => `${path.split('/').pop()}${file.kind === 'directory' ? '/' : ''}`);
        if (!this.files.has(target)) return { output: `ls: cannot access '${target}': No such file or directory\n`, exitCode: 2 };
        return { output: names.length ? `${names.join('  ')}\n` : '', exitCode: 0 };
      }
      case 'cat': {
        const file = this.files.get(resolve(args[0] ?? ''));
        if (!file || file.kind !== 'file') return { output: `cat: ${args[0] ?? ''}: No such file or directory\n`, exitCode: 1 };
        const text = decoder.decode(file.content);
        return { output: text.endsWith('\n') ? text : `${text}\n`, exitCode: 0 };
      }
      default: return null;
    }
  }

  childrenOf(directory: string): Array<[string, MockFile]> {
    const prefix = directory === '/' ? '/' : `${directory}/`;
    return [...this.files.entries()]
      .filter(([path]) => path !== directory && path.startsWith(prefix) && !path.slice(prefix.length).includes('/'))
      .sort(([a], [b]) => a.localeCompare(b));
  }
}

export function normalizePath(path: string): string {
  const parts: string[] = [];
  for (const part of path.split('/')) {
    if (!part || part === '.') continue;
    if (part === '..') parts.pop();
    else parts.push(part);
  }
  return `/${parts.join('/')}`;
}

/**
 * The shell behind a mock session PTY: echoes keystrokes, edits the line
 * (backspace, Ctrl-C, Ctrl-U, bracketed paste) and answers the builtins.
 */
export class MockShell {
  private line = '';
  private cwd: string;
  private escape = '';
  closed = false;

  constructor(
    private readonly host: MockHost,
    private readonly session: MockSession | null,
    private readonly output: (text: string) => void,
  ) {
    this.cwd = session?.workspace ?? MOCK_HOME;
  }

  prompt(): string {
    const shown = this.cwd.startsWith(MOCK_HOME) ? `~${this.cwd.slice(MOCK_HOME.length)}` : this.cwd;
    return `\x1b[1;32mdev@${MOCK_HOSTNAME}\x1b[0m:\x1b[1;34m${shown}\x1b[0m$ `;
  }

  start(): void {
    const name = this.session?.name ?? 'shell';
    this.output(`\x1b[2m[browser dev mock] ${name} on ${MOCK_HOSTNAME} \u2014 a scripted shell, try "help"\x1b[0m\r\n${this.prompt()}`);
  }

  input(text: string): void {
    if (this.closed) return;
    // Bracketed-paste markers from the composer carry no keystrokes.
    const cleaned = text.replace(/\x1b\[20[01]~/gu, '');
    for (const char of cleaned) this.key(char);
  }

  private key(char: string): void {
    if (this.escape) {
      this.escape += char;
      // CSI/SS3 sequences (arrows, function keys) end on a letter or '~'.
      if (/^\x1b(?:\[[0-9;?]*[A-Za-z~]|O[A-Za-z])$/u.test(this.escape) || this.escape.length > 8) this.escape = '';
      return;
    }
    if (char === '\x1b') {
      this.escape = char;
      return;
    }
    if (char === '\r' || char === '\n') {
      this.output('\r\n');
      this.run(this.line);
      this.line = '';
      return;
    }
    if (char === '\x7f' || char === '\b') {
      if (this.line.length > 0) {
        this.line = this.line.slice(0, -1);
        this.output('\b \b');
      }
      return;
    }
    if (char === '\x03') {
      this.line = '';
      this.output(`^C\r\n${this.prompt()}`);
      return;
    }
    if (char === '\x15') {
      this.output('\b \b'.repeat(this.line.length));
      this.line = '';
      return;
    }
    if (char === '\x0c') {
      this.output(`\x1b[H\x1b[2J${this.prompt()}${this.line}`);
      return;
    }
    if (char === '\x04') {
      if (!this.line) this.run('exit');
      return;
    }
    if (char < ' ') return;
    this.line += char;
    this.output(char);
  }

  private run(line: string): void {
    if (this.session) this.host.touch(this.session);
    const result = this.host.runShellLine(line.trim(), this.cwd)
      ?? { output: `${shellWords(line)[0]}: command not found (browser dev mock shell; try "help")\n`, exitCode: 127 };
    if (result.cwd) this.cwd = result.cwd;
    if (result.clear) this.output('\x1b[H\x1b[2J');
    this.output(result.output.replace(/\n/gu, '\r\n'));
    if (result.exit) {
      this.closed = true;
      return;
    }
    this.output(this.prompt());
  }
}
