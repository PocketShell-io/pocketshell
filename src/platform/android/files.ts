/**
 * The Android half of the shared Files pane and folder picker (#3105): the
 * `sftp` group of `PocketShellApi` over the native SFTP client. Semantics
 * follow the desktop/web adapters: absolute (or `~`) remote paths, entries
 * normalised into core's `DirEntry`/`FileStat`, text read as UTF-8, no root
 * confinement (the Files pane browses the whole host).
 *
 * Differences that are deliberate and visible to the user:
 * - the native client moves at most 512 KiB per call, so `capabilities`
 *   advertises `maxReadBytes` (a bigger file shows as over the limit, never
 *   truncated) and a larger write is refused;
 * - a save is conditional: the metadata recorded when the file was read is
 *   sent with the write (`sftpWriteIfUnchanged`), so a file another client
 *   changed or deleted in between is NOT overwritten (the desktop silently
 *   overwrites; the mobile native contract has the stronger primitive);
 * - download/upload/saveAs stay unsupported (no local file API yet) and are
 *   advertised off through `capabilities.download`.
 *
 * Every call is bound to the controller's CURRENT native connection and fails
 * if the generation changed while it ran (D28: one connection owner).
 */
import type { DirEntry, FileStat } from '@pocketshell/core';
import type { PocketShellApi } from '@ui/app/api';
import { MAX_SFTP_FILE_BYTES } from '@/session/files';
import { readSshError } from '@/native/sshCapability';
import type { NativeSftpStat, SshSftpEntry } from '@/native/sshCapability';

type Ref = { connectionId: string; generationId: string };
type Request = Ref & { requestId: string; path: string };

/** The native SFTP surface this adapter needs (the real plugin, or a fake in tests). */
export interface AndroidFileNative {
  list(options: Request): Promise<{ requestId: string; entries: Array<SshSftpEntry & { type?: string }>; truncated?: boolean; maxEntries?: number; totalEntries?: number }>;
  stat(ref: Ref, path: string): Promise<NativeSftpStat>;
  read(options: Request & { maxBytes: number }): Promise<{ requestId: string; dataBase64: string }>;
  write(options: Request & { dataBase64: string; createOnly?: boolean }): Promise<{ requestId: string; bytesWritten: number }>;
  writeIfUnchanged(options: Request & {
    rootPath: string;
    expectedMetadata: { isDirectory: false; sizeBytes: number; modifiedEpochMs: number };
    dataBase64: string;
  }): Promise<{ requestId: string; status: 'written' | 'conflict'; verdict?: 'missing' | 'changed'; bytesWritten?: number }>;
  mkdir(options: Request): Promise<{ requestId: string }>;
  rename(options: Request & { destination: string }): Promise<{ requestId: string }>;
  remove(options: Request): Promise<{ requestId: string }>;
}

export interface AndroidFilesDeps {
  native: AndroidFileNative;
  /** The controller's current native connection, or null when not connected. */
  current(connectionId: string): Ref | null;
  /** The login directory (`~`) on this native connection. */
  home(ref: Ref): Promise<string>;
  /**
   * A visible diagnostic notice. Core's `DirEntry[]` list contract has no
   * "truncated" field, so a folder past the native entry cap is reported here
   * (the Diagnostics banner) instead of being shown as if it were complete.
   */
  notice?(entry: { kind: string; message: string; detail?: Record<string, unknown> }): void;
}

export const ANDROID_FILE_CAPABILITIES: NonNullable<PocketShellApi['sftp']['capabilities']> = {
  write: true,
  download: false,
  maxReadBytes: MAX_SFTP_FILE_BYTES,
};

const encoder = new TextEncoder();
const decoder = new TextDecoder('utf-8');

function toBase64(bytes: Uint8Array): string {
  let binary = '';
  for (let index = 0; index < bytes.length; index += 0x8000) binary += String.fromCharCode(...bytes.subarray(index, index + 0x8000));
  return btoa(binary);
}

function fromBase64(value: string): Uint8Array {
  const binary = atob(value);
  const bytes = new Uint8Array(binary.length);
  for (let index = 0; index < binary.length; index += 1) bytes[index] = binary.charCodeAt(index);
  return bytes;
}

/** The native listing carries no mode/owner/longname; the shared Files pane does not render them, so they are placeholders. */
const rwx = { user: '---', group: '---', other: '---' };

function entryType(type: unknown, isDirectory: boolean): DirEntry['type'] {
  if (type === 'directory' || type === 'dir') return 'dir';
  if (type === 'file') return 'file';
  if (type === 'symlink') return 'symlink';
  return isDirectory ? 'dir' : 'other';
}

function tooLarge(size: number, limit: number): string {
  const mb = (value: number) => (value / (1024 * 1024)).toFixed(value >= 1024 * 1024 ? 1 : 2);
  return `File too large to open on the phone (${mb(size)} MB; the limit is ${mb(limit)} MB).`;
}

function toError(error: unknown): Error {
  const normalised = readSshError(error);
  return normalised;
}

export function createAndroidFiles(deps: AndroidFilesDeps): Pick<PocketShellApi['sftp'], 'list' | 'stat' | 'readFile' | 'readBinary' | 'writeFile' | 'createFile' | 'mkdir' | 'rename' | 'deleteFile' | 'rmdir' | 'capabilities'> {
  let sequence = 0;
  const requestId = () => `android-files-${++sequence}`;
  /** What each file looked like when this phone last read or wrote it. */
  const known = new Map<string, { sizeBytes: number; modifiedEpochMs: number }>();
  const key = (connectionId: string, path: string) => `${connectionId}\u0000${path}`;

  async function bound<T>(connectionId: string, work: (ref: Ref) => Promise<T>): Promise<T> {
    const ref = deps.current(connectionId);
    if (!ref) throw new Error('Connect before using remote files.');
    let result: T;
    try { result = await work(ref); } catch (error) { throw toError(error); }
    const after = deps.current(connectionId);
    if (after?.connectionId !== ref.connectionId || after.generationId !== ref.generationId) {
      throw new Error('The connection changed while the file operation ran.');
    }
    return result;
  }

  function checkEcho(response: { requestId?: unknown } | null | undefined, id: string): void {
    if (!response || typeof response !== 'object' || response.requestId !== id) throw new Error('The host sent an unexpected reply. Try again.');
  }

  /** Absolute remote path. `~` and `~/x` resolve against the home directory the native client reports. */
  async function absolute(ref: Ref, path: string): Promise<string> {
    if (!path || /[\u0000\r\n]/.test(path)) throw new Error('Choose a valid remote path.');
    if (path === '~' || path.startsWith('~/')) {
      const home = (await deps.home(ref)).replace(/\/+$/, '');
      return path === '~' ? home : `${home}/${path.slice(2)}`;
    }
    if (!path.startsWith('/') && !/^[A-Za-z]:[\\/]/.test(path)) throw new Error('Remote paths must be absolute.');
    return path;
  }

  async function statOf(ref: Ref, path: string): Promise<NativeSftpStat> {
    return deps.native.stat(ref, path);
  }

  async function readBytes(connectionId: string, path: string, maxBytes: number): Promise<Uint8Array> {
    if (!Number.isSafeInteger(maxBytes) || maxBytes < 1) throw new Error('The read limit is invalid.');
    const limit = Math.min(maxBytes, MAX_SFTP_FILE_BYTES);
    return bound(connectionId, async (ref) => {
      const abs = await absolute(ref, path);
      const before = await statOf(ref, abs);
      if (before.type !== 'file') throw new Error(before.isDirectory ? 'That path is a folder, not a file.' : 'That path is not a regular file.');
      if (before.sizeBytes > limit) throw new Error(tooLarge(before.sizeBytes, limit));
      const id = requestId();
      const response = await deps.native.read({ ...ref, requestId: id, path: abs, maxBytes: limit });
      checkEcho(response, id);
      const bytes = fromBase64(response.dataBase64);
      // A read cut short by the native cap (or a concurrent change) must never become a buffer that could be saved back.
      if (bytes.length !== before.sizeBytes) {
        throw new Error(bytes.length >= limit ? tooLarge(Math.max(before.sizeBytes, bytes.length), limit) : 'The file changed while it was being read. Open it again.');
      }
      known.set(key(connectionId, abs), { sizeBytes: before.sizeBytes, modifiedEpochMs: before.modifiedEpochMs });
      return bytes;
    });
  }

  async function writeBytes(connectionId: string, path: string, bytes: Uint8Array, createOnly: boolean): Promise<boolean> {
    if (bytes.length > MAX_SFTP_FILE_BYTES) throw new Error(`The file is over the ${MAX_SFTP_FILE_BYTES} byte limit for saving from the phone.`);
    return bound(connectionId, async (ref) => {
      const abs = await absolute(ref, path);
      const id = requestId();
      const dataBase64 = toBase64(bytes);
      let expected = known.get(key(connectionId, abs));
      if (!createOnly && !expected) {
        // Never read in this session (e.g. a save after the cache was dropped): an existing file is
        // still written only if it is unchanged since this stat; a missing one is simply created.
        // The guarantee is therefore weaker than for a file opened here (the stat is taken at save
        // time, not at open time).
        const existing = await statOf(ref, abs).catch(() => null);
        if (existing?.isDirectory) throw new Error('That path is a folder, not a file.');
        if (existing) expected = { sizeBytes: existing.sizeBytes, modifiedEpochMs: existing.modifiedEpochMs };
      }
      if (!createOnly && expected) {
        const response = await deps.native.writeIfUnchanged({
          ...ref, requestId: id, rootPath: '/', path: abs, dataBase64,
          expectedMetadata: { isDirectory: false, sizeBytes: expected.sizeBytes, modifiedEpochMs: expected.modifiedEpochMs },
        });
        checkEcho(response, id);
        if (response.status === 'conflict') {
          known.delete(key(connectionId, abs));
          throw new Error(response.verdict === 'missing'
            ? 'This file was deleted on the host since you opened it. Nothing was saved.'
            : 'This file changed on the host since you opened it. Nothing was saved; reopen it to see the new version.');
        }
        if (response.status !== 'written' || response.bytesWritten !== bytes.length) throw new Error('The host did not confirm the whole file was written.');
      } else {
        const response = await deps.native.write({ ...ref, requestId: id, path: abs, dataBase64, ...(createOnly ? { createOnly: true } : {}) });
        checkEcho(response, id);
        if (response.bytesWritten !== bytes.length) throw new Error('The host did not confirm the whole file was written.');
      }
      try {
        const after = await statOf(ref, abs);
        known.set(key(connectionId, abs), { sizeBytes: after.sizeBytes, modifiedEpochMs: after.modifiedEpochMs });
      } catch { known.delete(key(connectionId, abs)); }
      return true;
    });
  }

  return {
    capabilities: ANDROID_FILE_CAPABILITIES,

    list: (connectionId, path) => bound(connectionId, async (ref) => {
      const abs = await absolute(ref, path);
      const id = requestId();
      const response = await deps.native.list({ ...ref, requestId: id, path: abs });
      checkEcho(response, id);
      if (!Array.isArray(response.entries)) throw new Error('The host sent an unreadable folder listing.');
      if (response.truncated === true) {
        deps.notice?.({
          kind: 'files-list-truncated',
          message: `Only the first ${response.maxEntries ?? response.entries.length} of ${response.totalEntries ?? 'more'} entries of ${abs} are shown; this phone cannot list the rest yet.`,
          detail: { path: abs, shown: response.entries.length, total: response.totalEntries ?? null },
        });
      }
      return response.entries
        .filter((entry) => typeof entry.name === 'string' && entry.name !== '.' && entry.name !== '..')
        .map((entry): DirEntry => ({
          name: entry.name,
          longname: '',
          type: entryType(entry.type, entry.isDirectory === true),
          size: Number.isFinite(entry.sizeBytes) ? entry.sizeBytes : 0,
          modifyTime: Number.isFinite(entry.modifiedEpochMs) ? entry.modifiedEpochMs : 0,
          accessTime: 0,
          rights: { ...rwx },
          owner: 0,
          group: 0,
        }));
    }),

    stat: (connectionId, path) => bound(connectionId, async (ref): Promise<FileStat> => {
      const row = await statOf(ref, await absolute(ref, path));
      return { type: entryType(row.type, row.isDirectory), size: row.sizeBytes, modifyTime: row.modifiedEpochMs, accessTime: row.accessedEpochMs };
    }),

    readBinary: (connectionId, path, maxBytes) => readBytes(connectionId, path, maxBytes ?? MAX_SFTP_FILE_BYTES),
    readFile: async (connectionId, path) => decoder.decode(await readBytes(connectionId, path, MAX_SFTP_FILE_BYTES)),

    writeFile: (connectionId, path, content) => writeBytes(connectionId, path, encoder.encode(content), false),
    createFile: (connectionId, path, content = '') => writeBytes(connectionId, path, encoder.encode(content), true),

    mkdir: (connectionId, path) => bound(connectionId, async (ref) => {
      const id = requestId();
      checkEcho(await deps.native.mkdir({ ...ref, requestId: id, path: await absolute(ref, path) }), id);
      return true;
    }),

    rename: (connectionId, fromPath, toPath) => bound(connectionId, async (ref) => {
      const from = await absolute(ref, fromPath);
      const id = requestId();
      checkEcho(await deps.native.rename({ ...ref, requestId: id, path: from, destination: await absolute(ref, toPath) }), id);
      known.delete(key(connectionId, from));
      return true;
    }),

    deleteFile: (connectionId, path) => bound(connectionId, async (ref) => {
      const abs = await absolute(ref, path);
      // The native delete picks rm or rmdir by type; this verb must never remove a folder.
      const row = await statOf(ref, abs).catch(() => null);
      if (row?.isDirectory) throw new Error('That path is a folder; delete it as a folder.');
      const id = requestId();
      checkEcho(await deps.native.remove({ ...ref, requestId: id, path: abs }), id);
      known.delete(key(connectionId, abs));
      return true;
    }),

    rmdir: (connectionId, path) => bound(connectionId, async (ref) => {
      const abs = await absolute(ref, path);
      const row = await statOf(ref, abs);
      if (!row.isDirectory) throw new Error('That path is not a folder.');
      const id = requestId();
      checkEcho(await deps.native.remove({ ...ref, requestId: id, path: abs }), id);
      return true;
    }),
  };
}
