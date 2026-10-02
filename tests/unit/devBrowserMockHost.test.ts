import { describe, expect, it } from 'vitest';
import {
  HostCliCore,
  parseHostEnginesList,
  parseHostProfilesList,
  parseHostSessionsList,
  parseHostWorkspaces,
  parseUsageNdjson,
  runHostBootstrap,
  scanRemotePorts,
} from '@pocketshell/core';
import { MockHost, MockShell, shellWords, unwrapCommand, MOCK_HOME } from '../../src/dev/browser/mockHost';
import { createMockSshPlugin, mockPresentedHostKey } from '../../src/dev/browser/mockSsh';
import { createFakeNativeBridge } from '../../src/dev/browser/nativeBridge';

function mockPlugin() {
  const bridge = createFakeNativeBridge({});
  const plugin = createMockSshPlugin({ bridge: () => bridge, latencyMs: 0 });
  return { bridge, plugin, call: async (method: string, options: Record<string, unknown>) => plugin.methods[method](options) };
}

async function connected() {
  const harness = mockPlugin();
  const presented = await mockPresentedHostKey();
  const result = await harness.call('connect', {
    requestId: 'c1', generationId: 'g1', hostId: 'dev@devbox.mock:22', hostname: 'devbox.mock', port: 22, username: 'dev',
    credential: { kind: 'key-handle', handleId: 'h' }, expectedHostKey: { kind: 'sha256-fingerprint', fingerprintSha256: presented.fingerprintSha256 },
  }) as { connectionId: string; generationId: string };
  return { ...harness, ref: { connectionId: result.connectionId, generationId: result.generationId } };
}

const decode = (base64: string) => new TextDecoder().decode(Uint8Array.from(atob(base64), (char) => char.charCodeAt(0)));
const encode = (text: string) => btoa(text);

describe('browser dev mock host', () => {
  it('answers the pocketshell CLI verbs in the shapes pocketshell-core parses', () => {
    const host = new MockHost({ now: () => Date.UTC(2026, 9, 2, 12) });
    const sessions = parseHostSessionsList(host.exec('pocketshell sessions list --json').stdout);
    expect(sessions.errors).toEqual([]);
    expect(sessions.sessions.map((session) => session.name)).toEqual([
      'pocketshell:claude-main', 'pocketshell:codex-review', 'pocketshell:shell', 'pocketshell-site:dev-server', 'notes:scratch',
    ]);
    expect(new Set(sessions.sessions.map((session) => session.workspace)).size).toBe(3);
    expect(sessions.sessions[0]).toMatchObject({ agent: 'claude', agentState: 'working', agentStateSource: 'reported' });
    expect(parseHostWorkspaces(host.exec("pocketshell workspaces list --host 'devbox' --json").stdout).workspaces).toHaveLength(3);
    expect(parseHostEnginesList(host.exec('pocketshell engines list --json').stdout).map((engine) => engine.id)).toEqual(['claude', 'codex', 'shell']);
    expect(parseHostProfilesList(host.exec('pocketshell profiles list --json').stdout).length).toBeGreaterThan(0);
    const usage = parseUsageNdjson(host.exec('pocketshell usage --json').stdout);
    expect(usage.map((row) => row.provider)).toEqual(['claude', 'codex', 'copilot', 'gemini']);
    expect(host.exec('pocketshell sessions warnings --json')).toMatchObject({ exitCode: 0, stdout: '[]\n' });
    const unknown = host.exec('pocketshell frobnicate');
    expect(unknown.exitCode).not.toBe(0);
  });

  it('creates and kills sessions through the real HostCliCore contract', async () => {
    const host = new MockHost();
    const cli = new HostCliCore({ exec: async (command) => host.exec(command) });
    await expect(cli.createSession('feature-x', { cwd: `${MOCK_HOME}/notes`, engine: 'claude' })).resolves.toMatchObject({ name: 'notes:feature-x', created: true });
    await expect(cli.createSession('feature-x', { cwd: `${MOCK_HOME}/notes` })).resolves.toMatchObject({ created: false });
    expect((await cli.listSessions()).sessions[0]).toMatchObject({ name: 'notes:feature-x', agent: 'claude' });
    await cli.killSession('notes:feature-x');
    expect((await cli.listSessions()).sessions.some((session) => session.name === 'notes:feature-x')).toBe(false);
    await expect(cli.killSession('nope:missing')).rejects.toThrow(/failed on the host/u);
    await expect(cli.addWorkspace('devbox', '/srv/app')).resolves.toMatchObject({ workspaces: expect.arrayContaining([{ path: '/srv/app', displayPath: '/srv/app' }]) });
    await expect(cli.removeWorkspace('devbox', '/srv/app')).resolves.toMatchObject({ workspaces: expect.not.arrayContaining([{ path: '/srv/app', displayPath: '/srv/app' }]) });
  });

  it('passes the shared host bootstrap probe and the listener scan with process directories', async () => {
    const { call, ref } = await connected();
    const capability = { exec: (options: Record<string, unknown>) => call('exec', options) } as never;
    const bootstrap = await runHostBootstrap(async (command) => {
      const result = await call('exec', { ...ref, requestId: 'b', command, timeoutMs: 1000 }) as { exitCode: number; stdout: string; stderr: string };
      return result;
    });
    expect(bootstrap).toMatchObject({
      pocketshell: { installed: true }, tmux: { installed: true }, tmuxctl: { installed: true }, aplexer: { installed: true },
      installer: 'uv', daemonRunning: true, daemonEnabled: true,
    });
    const scan = await scanRemotePorts(capability, ref, { createRequestId: (() => { let n = 0; return () => `p${n++}`; })() });
    expect(scan.ok).toBe(true);
    expect(scan.ports.map((port) => port.port)).toEqual(expect.arrayContaining([22, 3000, 5173, 5432, 8000]));
    expect(scan.ports.find((port) => port.port === 5173)?.cwd).toBe(`${MOCK_HOME}/git/pocketshell`);
  });

  it('refuses an untrusted host key with the presented key, like the Android plugin', async () => {
    const { call } = mockPlugin();
    const presented = await mockPresentedHostKey();
    expect(presented.fingerprintSha256).toMatch(/^SHA256:[A-Za-z0-9+/]{43}$/u);
    const base = { requestId: 'c', generationId: 'g', hostId: 'h', hostname: 'devbox.mock', port: 22, username: 'dev', credential: { kind: 'key-handle', handleId: 'x' } };
    await expect(call('connect', { ...base, expectedHostKey: null })).rejects.toMatchObject({ code: 'HOST_KEY_REJECTED', data: presented });
    await expect(call('connect', { ...base, expectedHostKey: { kind: 'sha256-fingerprint', fingerprintSha256: 'SHA256:other' } })).rejects.toMatchObject({ code: 'HOST_KEY_REJECTED' });
    await expect(call('connect', { ...base, expectedHostKey: { kind: 'wire-key', ...presented } })).resolves.toMatchObject({ hostKey: presented });
    await expect(call('connect', { ...base, hostname: 'fail.mock', expectedHostKey: null })).rejects.toMatchObject({ code: 'CONNECTION_FAILED' });
  });

  it('runs an echoing line-editing shell behind each session PTY with Android sequence rules', async () => {
    const { call, ref, bridge, plugin } = await connected();
    const pty = await call('openPty', { ...ref, requestId: 'o', command: "exec pocketshell sessions attach -- 'pocketshell:shell'", cols: 80, rows: 24 }) as { channelId: string };
    const read = async (sequence: number) => call('readPty', { ...ref, channelId: pty.channelId, requestId: 'r', sequence, waitMs: 50 }) as Promise<{ sequence: number; dataBase64: string; eof: boolean }>;
    const banner = await read(0);
    expect(banner.sequence).toBe(1);
    expect(decode(banner.dataBase64)).toContain('dev@devbox');
    await expect(read(0)).rejects.toMatchObject({ code: 'STALE_SEQUENCE' });
    await call('writePty', { ...ref, channelId: pty.channelId, requestId: 'w', sequence: 1, dataBase64: encode('echo hello mockx\x7f\r') });
    await expect(call('writePty', { ...ref, channelId: pty.channelId, requestId: 'w', sequence: 1, dataBase64: encode('x') })).rejects.toMatchObject({ code: 'STALE_SEQUENCE' });
    const echoed = await read(1);
    expect(decode(echoed.dataBase64)).toContain('echo hello mockx\b \b\r\nhello mock\r\n');
    await call('resizePty', { ...ref, channelId: pty.channelId, requestId: 'z', sequence: 2, cols: 40, rows: 10 });
    await expect(call('openPty', { ...ref, requestId: 'o2', command: "exec pocketshell sessions attach -- 'no:such'", cols: 80, rows: 24 })).rejects.toMatchObject({ code: 'CHANNEL_OPEN_FAILED' });
    await call('writePty', { ...ref, channelId: pty.channelId, requestId: 'w', sequence: 3, dataBase64: encode('exit\r') });
    let last = await read(2);
    while (!last.eof) last = await read(last.sequence);
    expect(last.eof).toBe(true);

    const lost: unknown[] = [];
    bridge.nativeCallback('SshCapability', 'addListener', { eventName: 'connectionState' }, (data) => lost.push(data));
    expect(plugin.dropConnections()).toBe(1);
    expect(lost).toEqual([{ ...ref, state: 'lost', reason: 'mock network drop' }]);
    await expect(call('exec', { ...ref, requestId: 'e', command: 'pwd', timeoutMs: 1000 })).rejects.toMatchObject({ code: 'CONNECTION_LOST' });
  });

  it('keeps an in-memory SFTP tree with root containment and write-if-unchanged conflicts', async () => {
    const { call, ref } = await connected();
    const root = `${MOCK_HOME}/notes`;
    const listing = await call('sftpList', { ...ref, requestId: 'l', path: root, rootPath: root }) as { entries: Array<{ name: string; sizeBytes: number; modifiedEpochMs: number; isDirectory: boolean }> };
    expect(listing.entries.map((entry) => entry.name)).toEqual(['server.py', 'todo.md']);
    const todo = listing.entries[1];
    expect(decode((await call('sftpRead', { ...ref, requestId: 'r', path: `${root}/todo.md`, rootPath: root, maxBytes: 6 }) as { dataBase64: string }).dataBase64)).toBe('# TODO');
    await expect(call('sftpList', { ...ref, requestId: 'l', path: MOCK_HOME, rootPath: root })).rejects.toMatchObject({ code: 'SFTP_OUTSIDE_ROOT' });
    await expect(call('sftpWrite', { ...ref, requestId: 'w', path: `${root}/todo.md`, rootPath: root, createOnly: true, dataBase64: encode('x') })).rejects.toMatchObject({ code: 'SFTP_FILE_EXISTS' });
    const expectedMetadata = { isDirectory: false, sizeBytes: todo.sizeBytes, modifiedEpochMs: todo.modifiedEpochMs };
    await expect(call('sftpWriteIfUnchanged', { ...ref, requestId: 'u', path: `${root}/todo.md`, rootPath: root, expectedMetadata, dataBase64: encode('new') })).resolves.toMatchObject({ status: 'written', bytesWritten: 3 });
    await expect(call('sftpWriteIfUnchanged', { ...ref, requestId: 'u', path: `${root}/todo.md`, rootPath: root, expectedMetadata, dataBase64: encode('again') })).resolves.toMatchObject({ status: 'conflict', verdict: 'changed' });
    await call('sftpMkdir', { ...ref, requestId: 'm', path: `${root}/sub`, rootPath: root });
    await call('sftpRename', { ...ref, requestId: 'n', path: `${root}/server.py`, destination: `${root}/sub/server.py` });
    await call('sftpDelete', { ...ref, requestId: 'd', path: `${root}/todo.md` });
    const after = await call('sftpList', { ...ref, requestId: 'l', path: root }) as { entries: Array<{ name: string }> };
    expect(after.entries.map((entry) => entry.name)).toEqual(['sub']);
    expect(await call('resourceSnapshot', { requestId: 's' })).toEqual({ requestId: 's', connections: 1, ptys: 0, sftpClients: 1, forwards: 0 });
  });

  it('splits shell words and peels the path-aware command wrapper', () => {
    expect(shellWords(`pocketshell sessions attach -- 'a b' "c\\"d" e\\ f`)).toEqual(['pocketshell', 'sessions', 'attach', '--', 'a b', 'c"d', 'e f']);
    expect(unwrapCommand(`/bin/sh -lc 'export PATH="$HOME/.local/bin:$PATH"; command -v tmux'`)).toBe('command -v tmux');
    expect(unwrapCommand(`/bin/sh -lc 'export PATH="x"; export A=1 B=2; systemctl --user is-active x'`)).toBe('systemctl --user is-active x');
    const host = new MockHost();
    const output: string[] = [];
    const shell = new MockShell(host, null, (text) => output.push(text));
    shell.input('cd ~/git\rls\rcat pocketshell/README.md\rnope\r\x1b[A\x03');
    const text = output.join('');
    expect(text).toContain('pocketshell/  pocketshell-site/');
    expect(text).toContain('# PocketShell');
    expect(text).toContain('nope: command not found');
    expect(text).toContain('^C');
  });
});
