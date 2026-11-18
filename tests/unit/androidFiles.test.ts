import { describe, it, expect, vi } from 'vitest';
import { ConnectionController, type SshCapability } from '@pocketshell/core';
import { createAndroidPlatform, UnsupportedCapability } from '@/platform/android/androidApi';
import type { AndroidFileNative } from '@/platform/android/files';
import { AndroidHostStore } from '@/platform/android/hostStore';
import { createLocalTrustStore } from '@/platform/android/trustStore';
import { FakeNative, MemoryStorage, HOST_KEY, sessionJson } from './support/androidFakeNative';

type Node = { kind: 'file' | 'directory' | 'symlink'; content: Uint8Array; mtime: number };
const enc = new TextEncoder();
const b64 = (bytes: Uint8Array) => btoa(String.fromCharCode(...bytes));
const unb64 = (text: string) => Uint8Array.from(atob(text), (c) => c.charCodeAt(0));

/** An in-memory SFTP host that follows the native plugin's reply shapes and error codes. */
class FakeHost {
  nodes = new Map<string, Node>([
    ['/home/u', { kind: 'directory', content: new Uint8Array(), mtime: 1000 }],
    ['/home/u/proj', { kind: 'directory', content: new Uint8Array(), mtime: 1000 }],
    ['/home/u/notes.txt', { kind: 'file', content: enc.encode('hello'), mtime: 1000 }],
    ['/home/u/link', { kind: 'symlink', content: new Uint8Array(), mtime: 1000 }],
  ]);
  clock = 2000;
  calls: string[] = [];
  private req(o: { requestId: string; path: string }, name: string) { this.calls.push(`${name} ${o.path}`); }
  native: AndroidFileNative = {
    list: async (o) => {
      this.req(o, 'list');
      if (this.nodes.get(o.path)?.kind !== 'directory') throw { code: 'SFTP_NOT_DIRECTORY', message: 'SFTP path is not a directory.' };
      const prefix = o.path === '/' ? '/' : `${o.path}/`;
      const entries = [{ path: o.path, name: '.', isDirectory: true, sizeBytes: 0, modifiedEpochMs: 0, type: 'directory' }];
      for (const [path, n] of this.nodes) {
        if (path.startsWith(prefix) && !path.slice(prefix.length).includes('/')) {
          entries.push({ path, name: path.slice(prefix.length), isDirectory: n.kind === 'directory', sizeBytes: n.content.length, modifiedEpochMs: n.mtime * 1000, type: n.kind });
        }
      }
      return { requestId: o.requestId, entries };
    },
    stat: async (_ref, path) => {
      this.calls.push(`stat ${path}`);
      const n = this.nodes.get(path);
      if (!n) throw { code: 'SFTP_NOT_FOUND', message: 'No such file' };
      return { type: n.kind === 'symlink' ? 'file' : n.kind, isDirectory: n.kind === 'directory', sizeBytes: n.content.length, modifiedEpochMs: n.mtime * 1000, accessedEpochMs: 0 };
    },
    read: async (o) => { this.req(o, 'read'); return { requestId: o.requestId, dataBase64: b64(this.nodes.get(o.path)!.content.subarray(0, o.maxBytes)) }; },
    write: async (o) => {
      this.req(o, 'write');
      if (o.createOnly && this.nodes.has(o.path)) throw { code: 'SFTP_FILE_EXISTS', message: 'SFTP write target already exists.' };
      const bytes = unb64(o.dataBase64);
      this.nodes.set(o.path, { kind: 'file', content: bytes, mtime: ++this.clock });
      return { requestId: o.requestId, bytesWritten: bytes.length };
    },
    writeIfUnchanged: async (o) => {
      this.req(o, 'writeIfUnchanged');
      const n = this.nodes.get(o.path);
      if (!n) return { requestId: o.requestId, status: 'conflict', verdict: 'missing' };
      if (n.content.length !== o.expectedMetadata.sizeBytes || n.mtime * 1000 !== o.expectedMetadata.modifiedEpochMs) return { requestId: o.requestId, status: 'conflict', verdict: 'changed' };
      const bytes = unb64(o.dataBase64);
      this.nodes.set(o.path, { kind: 'file', content: bytes, mtime: ++this.clock });
      return { requestId: o.requestId, status: 'written', bytesWritten: bytes.length };
    },
    mkdir: async (o) => { this.req(o, 'mkdir'); this.nodes.set(o.path, { kind: 'directory', content: new Uint8Array(), mtime: ++this.clock }); return { requestId: o.requestId }; },
    rename: async (o) => { this.req(o, 'rename'); const n = this.nodes.get(o.path)!; this.nodes.delete(o.path); this.nodes.set(o.destination, n); return { requestId: o.requestId }; },
    remove: async (o) => {
      this.req(o, 'remove');
      if ([...this.nodes.keys()].some((p) => p.startsWith(`${o.path}/`))) throw { code: 'SFTP_FAILURE', message: 'Directory is not empty.' };
      this.nodes.delete(o.path); return { requestId: o.requestId };
    },
  };
}

async function setup() {
  const host = new FakeHost();
  const native = new FakeNative(); native.sessions = [];
  const capability = native.capability(); const baseExec = capability.exec;
  capability.exec = async (options) => {
    if (options.command.startsWith('mkdir -p -- ')) {
      native.execs.push(options.command);
      const path = options.command.slice('mkdir -p -- '.length).replace(/^'|'$/g, '');
      host.nodes.set(path, { kind: 'directory', content: new Uint8Array(), mtime: 5 });
      return { ...options, exitCode: 0, stdout: '', stderr: '', timedOut: false };
    }
    if (options.command.includes('sessions create')) {
      native.execs.push(options.command);
      native.sessions = [sessionJson('proj', '/home/u/proj')];
      return { ...options, exitCode: 0, stdout: JSON.stringify({ schema: 3, name: 'proj', id: 'proj-id', created: true }), stderr: '', timedOut: false };
    }
    if (options.command.includes('a rename ')) {
      native.execs.push(options.command);
      return { ...options, exitCode: 0, stdout: '', stderr: '', timedOut: false };
    }
    return baseExec(options);
  };
  const storage = new MemoryStorage(); const hosts = new AndroidHostStore({ storage, readLegacyHosts: async () => [] });
  hosts.save({ name: 'fixture', hostname: 'host', port: 22, user: 'u', keyHandleId: '00000000-0000-4000-8000-000000000001' });
  const trust = createLocalTrustStore(storage); await trust.record('u@host:22', { kind: 'wire-key', ...HOST_KEY });
  const remoteDirectory = vi.fn(async (_ref: unknown, path: string) => (path === '.' || path === '~' ? '/home/u' : path));
  const platform = createAndroidPlatform({
    createController: () => new ConnectionController({ capability: capability as SshCapability, trustStore: trust }),
    hosts, remoteDirectory, fileNative: host.native, backgroundGraceMs: () => 60_000, addHostRoute: '/add',
  });
  const connected = await platform.api.ssh.connect({ host: 'host', user: 'u', hostAlias: 'fixture', port: 22 });
  if (!connected.ok || !connected.connectionId) throw new Error('fixture failed to connect');
  return { host, native, platform, api: platform.api, id: connected.connectionId };
}

describe('Android Files and folder-picker adapters (#3105)', () => {
  it('lists a folder as core DirEntry rows without dot entries (the New session folder picker and the Files tree)', async () => {
    const f = await setup();
    const rows = await f.api.sftp.list(f.id, '/home/u');
    expect(rows.map((r) => [r.name, r.type]).sort()).toEqual([['link', 'symlink'], ['notes.txt', 'file'], ['proj', 'dir']]);
    expect(rows.find((r) => r.name === 'notes.txt')).toMatchObject({ size: 5, modifyTime: 1_000_000 });
    expect((await f.api.sftp.list(f.id, '~')).length).toBe(3);
    await expect(f.api.sftp.list(f.id, '/home/u/notes.txt')).rejects.toThrow('not a directory');
    await expect(f.api.sftp.list(f.id, 'relative')).rejects.toThrow('absolute');
    f.platform.dispose();
  });
  it('is no longer the unsupported stub for any file verb', async () => {
    const f = await setup();
    for (const call of [
      () => f.api.sftp.list(f.id, '/home/u'), () => f.api.sftp.stat(f.id, '/home/u'), () => f.api.sftp.readFile(f.id, '/home/u/notes.txt'),
      () => f.api.sftp.mkdir(f.id, '/home/u/x'), () => f.api.helper.sessionsCreate(f.id, 'proj', '/home/u/proj'),
    ]) await call().catch((error) => expect(error).not.toBeInstanceOf(UnsupportedCapability));
    expect(f.api.sftp.capabilities).toMatchObject({ write: true, download: false, maxReadBytes: 512 * 1024 });
    f.platform.dispose();
  });
  it('stats, reads text as UTF-8 and binary with the size cap, and refuses folders and oversize files', async () => {
    const f = await setup();
    expect(await f.api.sftp.stat(f.id, '/home/u/notes.txt')).toEqual({ type: 'file', size: 5, modifyTime: 1_000_000, accessTime: 0 });
    expect(await f.api.sftp.readFile(f.id, '/home/u/notes.txt')).toBe('hello');
    expect(await f.api.sftp.readBinary(f.id, '/home/u/notes.txt')).toEqual(enc.encode('hello'));
    await expect(f.api.sftp.readBinary(f.id, '/home/u/notes.txt', 3)).rejects.toThrow('over the 3 byte limit');
    await expect(f.api.sftp.readFile(f.id, '/home/u/proj')).rejects.toThrow('folder');
    f.host.nodes.set('/home/u/big.bin', { kind: 'file', content: new Uint8Array(512 * 1024 + 1), mtime: 1 });
    await expect(f.api.sftp.readBinary(f.id, '/home/u/big.bin')).rejects.toThrow('limit');
    f.platform.dispose();
  });
  it('edits and saves a file; a second save and a create/mkdir/rename/delete/rmdir round trip reach the host', async () => {
    const f = await setup();
    await f.api.sftp.readFile(f.id, '/home/u/notes.txt');
    expect(await f.api.sftp.writeFile(f.id, '/home/u/notes.txt', 'héllo edited')).toBe(true);
    expect(new TextDecoder().decode(f.host.nodes.get('/home/u/notes.txt')!.content)).toBe('héllo edited');
    expect(f.host.calls).toContain('writeIfUnchanged /home/u/notes.txt');
    expect(await f.api.sftp.writeFile(f.id, '/home/u/notes.txt', 'second')).toBe(true);
    expect(new TextDecoder().decode(f.host.nodes.get('/home/u/notes.txt')!.content)).toBe('second');

    expect(await f.api.sftp.createFile(f.id, '/home/u/new.txt', 'x')).toBe(true);
    await expect(f.api.sftp.createFile(f.id, '/home/u/new.txt', 'y')).rejects.toThrow('already exists');
    expect(await f.api.sftp.mkdir(f.id, '/home/u/dir')).toBe(true);
    expect(await f.api.sftp.rename(f.id, '/home/u/new.txt', '/home/u/dir/moved.txt')).toBe(true);
    expect(f.host.nodes.has('/home/u/dir/moved.txt')).toBe(true);
    await expect(f.api.sftp.rmdir(f.id, '/home/u/dir')).rejects.toThrow('not empty');
    await expect(f.api.sftp.deleteFile(f.id, '/home/u/dir')).rejects.toThrow('folder');
    expect(await f.api.sftp.deleteFile(f.id, '/home/u/dir/moved.txt')).toBe(true);
    expect(await f.api.sftp.rmdir(f.id, '/home/u/dir')).toBe(true);
    await expect(f.api.sftp.rmdir(f.id, '/home/u/notes.txt')).rejects.toThrow('not a folder');
    expect(f.host.nodes.has('/home/u/dir')).toBe(false);
    f.platform.dispose();
  });
  it('never overwrites a file another client changed or deleted since it was opened', async () => {
    const f = await setup();
    await f.api.sftp.readFile(f.id, '/home/u/notes.txt');
    f.host.nodes.set('/home/u/notes.txt', { kind: 'file', content: enc.encode('theirs!'), mtime: 99 });
    await expect(f.api.sftp.writeFile(f.id, '/home/u/notes.txt', 'mine')).rejects.toThrow('changed on the host');
    expect(new TextDecoder().decode(f.host.nodes.get('/home/u/notes.txt')!.content)).toBe('theirs!');
    await f.api.sftp.readFile(f.id, '/home/u/notes.txt');
    f.host.nodes.delete('/home/u/notes.txt');
    await expect(f.api.sftp.writeFile(f.id, '/home/u/notes.txt', 'mine')).rejects.toThrow('deleted on the host');
    f.platform.dispose();
  });
  it('refuses a write over the native 512 KiB limit and a call on a closed connection', async () => {
    const f = await setup();
    await expect(f.api.sftp.writeFile(f.id, '/home/u/big.txt', 'x'.repeat(512 * 1024 + 1))).rejects.toThrow('limit');
    await expect(f.api.sftp.list('nope', '/home/u')).rejects.toThrow('Connect');
    f.platform.dispose();
  });
  it('creates a folder, derives a session name and creates and renames a session through the host', async () => {
    const f = await setup();
    expect(await f.api.projects.createFolder(f.id, { parent: '/home/u', name: 'fresh' })).toEqual({ ok: true, path: '/home/u/fresh', error: null });
    expect(f.host.nodes.get('/home/u/fresh')?.kind).toBe('directory');
    expect(await f.api.projects.createFolder(f.id, { parent: '/home/u', name: 'a/b' })).toMatchObject({ ok: false });
    expect(await f.api.projects.deriveName(f.id, '/home/u/proj')).toBe('proj');
    expect(await f.api.helper.sessionsCreate(f.id, 'proj', '/home/u/proj')).toBe(true);
    expect(f.native.execs.some((c) => c.includes('sessions create') && c.includes('/home/u/proj'))).toBe(true);
    expect(await f.api.projects.renameSession(f.id, 'proj', 'proj2')).toEqual({ ok: true, sessionName: 'proj2', error: null, code: null });
    expect(f.native.execs.some((c) => c.includes('a rename ') && c.includes('proj-id') && c.includes('--tag') && c.includes('proj2'))).toBe(true);
    expect(await f.api.projects.renameSession(f.id, 'proj', 'bad name')).toMatchObject({ ok: false, code: 'illegal-name' });
    f.platform.dispose();
  });
});
