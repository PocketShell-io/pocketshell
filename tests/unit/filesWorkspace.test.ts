import { beforeEach, describe, expect, it, vi } from 'vitest';
import {
  bytesAttachmentSource,
  createFileWorkspaceService,
  DEFAULT_FILE_READ_BYTES,
  MAX_SFTP_FILE_BYTES,
  type AttachmentSource,
} from '../../src/session/files';
import { ATTACHMENT_WRITE_CHUNK_BYTES, MAX_ATTACHMENT_FILE_BYTES } from '../../src/session/attachmentLimits';
import type { SshCapabilityPlugin, SshConnectionRef, SshSftpEntry } from '../../src/native/sshCapability';

interface MockFile {
  bytes: Uint8Array;
  modifiedEpochMs: number;
  symlink?: boolean;
}

interface MockSftp {
  capability: SshCapabilityPlugin;
  files: Map<string, MockFile>;
  directories: Set<string>;
  atomicWrite: (options: {
    requestId: string;
    path: string;
    expectedMetadata: { isDirectory: false; sizeBytes: number; modifiedEpochMs: number };
    dataBase64: string;
  }) => Promise<{ requestId: string; status: 'written' | 'conflict'; verdict?: 'missing' | 'changed'; bytesWritten?: number }>;
  calls: {
    list: ReturnType<typeof vi.fn>;
    read: ReturnType<typeof vi.fn>;
    write: ReturnType<typeof vi.fn>;
    conditionalWrite: ReturnType<typeof vi.fn>;
    chunk: ReturnType<typeof vi.fn>;
    mkdir: ReturnType<typeof vi.fn>;
    rename: ReturnType<typeof vi.fn>;
    remove: ReturnType<typeof vi.fn>;
  };
}

const connection: SshConnectionRef = { connectionId: 'connection-1', generationId: 'generation-1' };

function bytesToBase64(bytes: Uint8Array): string {
  let binary = '';
  for (const byte of bytes) binary += String.fromCharCode(byte);
  return btoa(binary);
}

function base64ToBytes(value: string): Uint8Array {
  const binary = atob(value);
  return Uint8Array.from(binary, (character) => character.charCodeAt(0));
}

function parent(path: string): string {
  const slash = path.lastIndexOf('/');
  return slash <= 0 ? '/' : path.slice(0, slash);
}

function basename(path: string): string {
  return path.slice(path.lastIndexOf('/') + 1);
}

function createMockSftp(seed?: {
  directories?: string[];
  files?: Record<string, MockFile>;
  includeDotEntries?: boolean;
}): MockSftp {
  const files = new Map<string, MockFile>(Object.entries(seed?.files ?? {}));
  const directories = new Set(['/home', '/home/alex', '/home/alex/project', ...(seed?.directories ?? [])]);
  const list = vi.fn(async (options: { requestId: string; path: string }) => {
    if (!directories.has(options.path)) throw new Error(`No such directory: ${options.path}`);
    const entries: Array<SshSftpEntry & { type?: string }> = [];
    if (seed?.includeDotEntries) {
      entries.push(
        { path: options.path, name: '.', isDirectory: true, sizeBytes: 0, modifiedEpochMs: 1 },
        { path: parent(options.path), name: '..', isDirectory: true, sizeBytes: 0, modifiedEpochMs: 1 },
      );
    }
    for (const directory of directories) {
      if (directory !== options.path && parent(directory) === options.path) {
        entries.push({
          path: directory,
          name: basename(directory),
          type: 'directory',
          isDirectory: true,
          sizeBytes: 0,
          modifiedEpochMs: 1,
        });
      }
    }
    for (const [path, file] of files) {
      if (parent(path) !== options.path) continue;
      entries.push({
        path,
        name: basename(path),
        type: file.symlink ? 'symlink' : 'file',
        isDirectory: false,
        sizeBytes: file.bytes.byteLength,
        modifiedEpochMs: file.modifiedEpochMs,
      });
    }
    return { requestId: options.requestId, entries };
  });
  const read = vi.fn(async (options: { requestId: string; path: string; maxBytes: number }) => {
    const file = files.get(options.path);
    if (!file) throw new Error(`No such file: ${options.path}`);
    return { requestId: options.requestId, dataBase64: bytesToBase64(file.bytes.subarray(0, options.maxBytes)) };
  });
  const write = vi.fn(async (options: { requestId: string; path: string; dataBase64: string; createOnly?: boolean }) => {
    if (options.createOnly && files.has(options.path)) throw new Error(`Already exists: ${options.path}`);
    const content = base64ToBytes(options.dataBase64);
    files.set(options.path, { bytes: content, modifiedEpochMs: 2_000 });
    return { requestId: options.requestId, bytesWritten: content.byteLength };
  });
  const atomicWrite = async (options: {
    requestId: string;
    path: string;
    expectedMetadata: { isDirectory: false; sizeBytes: number; modifiedEpochMs: number };
    dataBase64: string;
  }) => {
    const file = files.get(options.path);
    if (file == null) return { requestId: options.requestId, status: 'conflict' as const, verdict: 'missing' as const };
    if (file.symlink) throw new Error('Symlinks are rejected by the native SFTP adapter.');
    if (
      options.expectedMetadata.isDirectory
      || options.expectedMetadata.sizeBytes !== file.bytes.byteLength
      || options.expectedMetadata.modifiedEpochMs !== file.modifiedEpochMs
    ) {
      return { requestId: options.requestId, status: 'conflict' as const, verdict: 'changed' as const };
    }
    const content = base64ToBytes(options.dataBase64);
    files.set(options.path, { bytes: content, modifiedEpochMs: 2_000 });
    return { requestId: options.requestId, status: 'written' as const, bytesWritten: content.byteLength };
  };
  const conditionalWrite = vi.fn(atomicWrite);
  // SshCapabilityPlugin.sftpWriteChunk: offset 0 creates exclusively, later
  // chunks must start exactly at the file's end.
  const chunk = vi.fn(async (options: { requestId: string; path: string; offset: number; dataBase64: string }) => {
    const content = base64ToBytes(options.dataBase64);
    if (content.byteLength > 512 * 1024) throw new Error('SFTP write exceeds 512 KiB.');
    const existing = files.get(options.path);
    if (options.offset === 0) {
      if (existing) throw new Error(`Already exists: ${options.path}`);
      files.set(options.path, { bytes: content, modifiedEpochMs: 2_000 });
    } else {
      if (!existing) throw new Error(`Missing: ${options.path}`);
      if (existing.bytes.byteLength !== options.offset) throw new Error('offset mismatch');
      const joined = new Uint8Array(existing.bytes.byteLength + content.byteLength);
      joined.set(existing.bytes);
      joined.set(content, existing.bytes.byteLength);
      files.set(options.path, { bytes: joined, modifiedEpochMs: 2_000 });
    }
    return { requestId: options.requestId, bytesWritten: content.byteLength, sizeBytes: files.get(options.path)!.bytes.byteLength };
  });
  const mkdir = vi.fn(async (options: { requestId: string; path: string }) => {
    directories.add(options.path);
    return { requestId: options.requestId };
  });
  const rename = vi.fn(async (options: { requestId: string; path: string; destination: string }) => {
    const file = files.get(options.path);
    if (file) {
      files.delete(options.path);
      files.set(options.destination, file);
    }
    return { requestId: options.requestId };
  });
  const remove = vi.fn(async (options: { requestId: string; path: string }) => {
    files.delete(options.path);
    return { requestId: options.requestId };
  });
  const capability = {
    addListener: vi.fn(),
    removeAllListeners: vi.fn(),
    sftpList: list,
    sftpRead: read,
    sftpWrite: write,
    sftpWriteIfUnchanged: conditionalWrite,
    sftpWriteChunk: chunk,
    sftpMkdir: mkdir,
    sftpRename: rename,
    sftpDelete: remove,
  } as unknown as SshCapabilityPlugin;
  return {
    capability,
    files,
    directories,
    atomicWrite,
    calls: { list, read, write, conditionalWrite, chunk, mkdir, rename, remove },
  };
}

function serviceFor(mock: MockSftp, options?: {
  rootDirectory?: string;
  initialDirectory?: string;
  isCurrent?: () => boolean;
  nextRequestId?: () => string;
}) {
  let request = 0;
  return createFileWorkspaceService(mock.capability, {
    connection,
    rootDirectory: options?.rootDirectory ?? '/home/alex/project',
    initialDirectory: options?.initialDirectory,
    isCurrent: options?.isCurrent,
    nextRequestId: options?.nextRequestId ?? (() => `test-${++request}`),
  });
}

describe('SFTP file workspace policy and byte-I/O adapter', () => {
  beforeEach(() => vi.clearAllMocks());

  it('resolves paths under the configured remote root and rejects traversal and NUL', () => {
    const service = serviceFor(createMockSftp());

    expect(service.resolvePath('notes/../readme.md')).toBe('/home/alex/project/readme.md');
    expect(() => service.resolvePath('../outside.txt')).toThrowError(
      expect.objectContaining({ code: 'outside-root' }),
    );
    expect(() => service.resolvePath('bad\0name')).toThrowError(
      expect.objectContaining({ code: 'invalid-path' }),
    );
  });

  it('lists valid children with shared directory-first ordering and name classification', async () => {
    const mock = createMockSftp({
      directories: ['/home/alex/project/Zed'],
      files: {
        '/home/alex/project/b.txt': { bytes: new TextEncoder().encode('b'), modifiedEpochMs: 10 },
        '/home/alex/project/a.png': { bytes: new Uint8Array([1]), modifiedEpochMs: 10 },
        '/home/alex/project/Readme.MD': { bytes: new TextEncoder().encode('hello'), modifiedEpochMs: 10 },
      },
      includeDotEntries: true,
    });

    const listing = await serviceFor(mock).listDirectory();

    expect(listing.entries.map(({ name }) => name)).toEqual(['Zed', 'a.png', 'b.txt', 'Readme.MD']);
    expect(listing.entries.map(({ classification }) => classification?.kind ?? null)).toEqual([
      null, 'image', 'text', 'markdown',
    ]);
  });

  it('rejects unsafe names returned by native SFTP listing', async () => {
    const mock = createMockSftp();
    mock.calls.list.mockImplementationOnce(async (options) => ({
      requestId: options.requestId,
      entries: [{ path: '/tmp/escape', name: '../escape', isDirectory: false, sizeBytes: 1, modifiedEpochMs: 1 }],
    }));

    await expect(serviceFor(mock).listDirectory()).rejects.toMatchObject({ code: 'invalid-entry' });
  });

  it('rejects duplicate child names instead of selecting an ambiguous remote path', async () => {
    const mock = createMockSftp();
    mock.calls.list.mockImplementationOnce(async (options) => ({
      requestId: options.requestId,
      entries: [
        { path: options.path, name: 'same.txt', isDirectory: false, sizeBytes: 1, modifiedEpochMs: 1 },
        { path: options.path, name: 'same.txt', isDirectory: false, sizeBytes: 2, modifiedEpochMs: 2 },
      ],
    }));

    await expect(serviceFor(mock).listDirectory()).rejects.toMatchObject({ code: 'invalid-entry' });
  });

  it('reads bounded bytes and combines filename and magic classification', async () => {
    const pdf = Uint8Array.from([0x25, 0x50, 0x44, 0x46, 0x2d, 0x31]);
    const mock = createMockSftp({
      files: { '/home/alex/project/mystery.data': { bytes: pdf, modifiedEpochMs: 10 } },
    });

    const result = await serviceFor(mock).readFile('mystery.data', 64);

    expect([...result.bytes]).toEqual([...pdf]);
    expect(result.classification).toEqual({ kind: 'pdf', mime: 'application/pdf' });
    expect(mock.calls.read).toHaveBeenCalledWith(expect.objectContaining({
      path: '/home/alex/project/mystery.data',
      maxBytes: 64,
      connectionId: connection.connectionId,
      generationId: connection.generationId,
    }));
  });

  it('rejects oversized files before reading and detects a size change during bounded reads', async () => {
    const mock = createMockSftp({
      files: {
        '/home/alex/project/large.dat': { bytes: new Uint8Array(128), modifiedEpochMs: 10 },
        '/home/alex/project/raced.dat': { bytes: new Uint8Array(10), modifiedEpochMs: 10 },
      },
    });
    const service = serviceFor(mock);

    await expect(service.readFile('large.dat', 32)).rejects.toMatchObject({ code: 'file-too-large' });
    expect(mock.calls.read).not.toHaveBeenCalled();

    mock.calls.read.mockImplementationOnce(async (options) => ({
      requestId: options.requestId,
      dataBase64: bytesToBase64(new Uint8Array(9)),
    }));
    await expect(service.readFile('raced.dat', 32)).rejects.toMatchObject({ code: 'file-changed' });
  });

  it('refuses to save when metadata changes immediately before the native conditional write', async () => {
    const mock = createMockSftp({
      files: { '/home/alex/project/readme.md': { bytes: new TextEncoder().encode('before'), modifiedEpochMs: 10 } },
    });
    const service = serviceFor(mock);
    const snapshot = await service.loadTextForEdit('readme.md');
    expect(snapshot.text).toBe('before');

    const checkAndWrite = mock.calls.conditionalWrite.getMockImplementation();
    expect(checkAndWrite).toBeDefined();
    mock.calls.conditionalWrite.mockImplementationOnce(async (options) => {
      // Model a server-side edit after the caller captured metadata but before
      // the native adapter compares it and writes.
      mock.files.set('/home/alex/project/readme.md', {
        bytes: new TextEncoder().encode('different length'),
        modifiedEpochMs: 20,
      });
      return checkAndWrite!(options);
    });
    const result = await service.saveText(snapshot, 'after');

    expect(result).toEqual({ status: 'conflict', path: '/home/alex/project/readme.md', verdict: 'changed' });
    expect(mock.calls.conditionalWrite).toHaveBeenCalledTimes(1);
    expect(mock.calls.write).not.toHaveBeenCalled();
  });

  it('saves UTF-8 edits only through the native conditional write when metadata still matches', async () => {
    const mock = createMockSftp({
      files: { '/home/alex/project/readme.md': { bytes: new TextEncoder().encode('before'), modifiedEpochMs: 10 } },
    });
    const service = serviceFor(mock);
    const snapshot = await service.loadTextForEdit('readme.md');
    const result = await service.saveText(snapshot, 'café 🧪');

    expect(result).toEqual({ status: 'saved', path: '/home/alex/project/readme.md', bytesWritten: new TextEncoder().encode('café 🧪').length });
    expect(mock.files.get(snapshot.path)?.bytes).toEqual(new TextEncoder().encode('café 🧪'));
    expect(mock.calls.conditionalWrite).toHaveBeenCalledWith(expect.objectContaining({
      path: snapshot.path,
      rootPath: '/home/alex/project',
      expectedMetadata: snapshot.metadata,
      dataBase64: bytesToBase64(new TextEncoder().encode('café 🧪')),
    }));
    expect(mock.calls.write).not.toHaveBeenCalled();
  });

  it('fails closed on a symlink entry before reading or writing', async () => {
    const mock = createMockSftp({
      files: { '/home/alex/project/link.txt': { bytes: new TextEncoder().encode('target'), modifiedEpochMs: 10, symlink: true } },
    });
    const service = serviceFor(mock);

    await expect(service.readFile('link.txt')).rejects.toMatchObject({ code: 'symlink-unsupported' });
    await expect(service.writeFile('link.txt', new TextEncoder().encode('replace'), true))
      .rejects.toMatchObject({ code: 'symlink-unsupported' });
    expect(mock.calls.read).not.toHaveBeenCalled();
    expect(mock.calls.write).not.toHaveBeenCalled();
  });

  it('fails closed when the native listing omits a regular-file type', async () => {
    const mock = createMockSftp({
      files: { '/home/alex/project/untyped.txt': { bytes: new TextEncoder().encode('text'), modifiedEpochMs: 10 } },
    });
    mock.calls.list.mockImplementation(async (options) => ({
      requestId: options.requestId,
      entries: [{ path: options.path, name: 'untyped.txt', isDirectory: false, sizeBytes: 4, modifiedEpochMs: 10 }],
    }));

    const listing = await serviceFor(mock).listDirectory();

    expect(listing.entries[0]?.type).toBe('other');
    await expect(serviceFor(mock).readFile('untyped.txt')).rejects.toMatchObject({ code: 'not-file' });
    expect(mock.calls.read).not.toHaveBeenCalled();
  });

  it('does not offer binary magic content to the text editor', async () => {
    const mock = createMockSftp({
      files: { '/home/alex/project/unknown.data': { bytes: Uint8Array.from([0x25, 0x50, 0x44, 0x46]), modifiedEpochMs: 10 } },
    });

    await expect(serviceFor(mock).loadTextForEdit('unknown.data')).rejects.toMatchObject({ code: 'not-editable' });
  });

  it('uploads bytes through a resolved path and prevents implicit overwrite', async () => {
    const mock = createMockSftp();
    const service = serviceFor(mock);
    const content = new TextEncoder().encode('upload bytes');
    const uploaded = await service.writeFile('new.txt', content);

    expect(uploaded).toEqual({ path: '/home/alex/project/new.txt', bytesWritten: content.length });
    await expect(service.writeFile('new.txt', content)).rejects.toMatchObject({ code: 'file-exists' });
    expect(mock.calls.write).toHaveBeenCalledTimes(1);
  });

  it('stages sanitized attachment names, preserves partial success, and returns retention candidates', async () => {
    const mock = createMockSftp({ directories: ['/home/alex/project/uploads'] });
    const service = serviceFor(mock);
    const valid = new TextEncoder().encode('attachment');
    const attachments: AttachmentSource[] = [
      bytesAttachmentSource('../plan for review?.md', valid),
      { name: 'too-large.bin', sizeBytes: 11, read: async () => ({ bytes: new Uint8Array(11), eof: true }) },
    ];
    const staged = await service.stageAttachments({
      directory: 'uploads',
      scopeKey: 'Host A/session',
      timestamp: '20260102-030405',
      attachments,
      maxFileBytes: 10,
    });

    expect(staged.directory).toBe('/home/alex/project/uploads/host-a-session');
    expect(staged.decision).toMatchObject({ kind: 'partial', failedCount: 1, attachments: [{
      filename: '20260102-030405-01-plan_for_review.md',
      sourceName: '../plan for review?.md',
      sizeBytes: valid.length,
    }] });
    expect(mock.files.has(`${staged.directory}/20260102-030405-02-too-large.bin`)).toBe(false);
    expect(staged.failures).toEqual([{ sourceName: 'too-large.bin', sourceIndex: 1, message: expect.stringMatching(/^This file is .* MB; the limit is .* MB\.$/) }]);

    mock.files.set(`${staged.directory}/old.txt`, { bytes: valid, modifiedEpochMs: 1_000 });
    mock.files.set(`${staged.directory}/new.txt`, { bytes: valid, modifiedEpochMs: 9_500 });
    const plan = await service.planAttachmentRetention(staged.directory, 10_000, {
      ttlMillis: 2_000,
      keepNewest: 1,
      protectNewestMillis: 0,
    });
    expect(plan.pathsToDelete).toContain(`${staged.directory}/old.txt`);
    expect(plan.pathsToDelete).not.toContain(`${staged.directory}/new.txt`);
  });

  it('does not return completed uploads as attachments after cancellation', async () => {
    const mock = createMockSftp({ directories: ['/home/alex/project/uploads'] });
    const service = serviceFor(mock);
    const staged = await service.stageAttachments({
      directory: 'uploads',
      scopeKey: 'session-a',
      timestamp: '20260102-030405',
      attachments: [
        bytesAttachmentSource('first.txt', new TextEncoder().encode('one')),
        bytesAttachmentSource('second.txt', new TextEncoder().encode('two')),
      ],
      // Cancelled once the first file has been renamed into place.
      isCancelled: () => mock.calls.rename.mock.calls.length >= 1,
    });

    expect(staged.decision).toEqual({ kind: 'cancelled', attachments: [], completedUploadCount: 1 });
    expect(mock.calls.chunk).toHaveBeenCalledTimes(1);
  });

  it('streams a 1.7 MB photo through bounded .part chunks and renames it into place byte-exact (#3191)', async () => {
    const mock = createMockSftp({ directories: ['/home/alex/project/uploads'] });
    const service = serviceFor(mock);
    const photo = new Uint8Array(1_700_000);
    for (let index = 0; index < photo.length; index += 1) photo[index] = (index * 31 + 7) % 251;
    const progress: Array<[number, number]> = [];
    const staged = await service.stageAttachments({
      directory: 'uploads',
      scopeKey: 'session',
      timestamp: '20261118-101500',
      attachments: [bytesAttachmentSource('PXL_20261118_101530789.jpg', photo, 'image/jpeg')],
      onProgress: (index, written) => progress.push([index, written]),
    });

    const finalPath = '/home/alex/project/uploads/session/20261118-101500-01-PXL_20261118_101530789.jpg';
    expect(staged.failures).toEqual([]);
    expect(staged.decision).toMatchObject({ kind: 'complete', attachments: [{ path: finalPath, sizeBytes: photo.length }] });
    // Buffer comparison: a deep toEqual over 1.7 M elements is needlessly slow.
    expect(Buffer.from(mock.files.get(finalPath)!.bytes).equals(Buffer.from(photo))).toBe(true);
    expect(mock.files.has(`${finalPath}.part`)).toBe(false);
    expect(mock.calls.write).not.toHaveBeenCalled();
    const chunks = mock.calls.chunk.mock.calls.map(([options]) => options as { path: string; offset: number; dataBase64: string });
    expect(chunks.map((chunk) => chunk.offset)).toEqual([0, 524_288, 1_048_576, 1_572_864]);
    expect(chunks.every((chunk) => chunk.path === `${finalPath}.part`)).toBe(true);
    expect(chunks.every((chunk) => atob(chunk.dataBase64).length <= ATTACHMENT_WRITE_CHUNK_BYTES)).toBe(true);
    expect(mock.calls.rename).toHaveBeenCalledWith(expect.objectContaining({ path: `${finalPath}.part`, destination: finalPath }));
    expect(progress).toEqual([[0, 524_288], [0, 1_048_576], [0, 1_572_864], [0, 1_700_000]]);
  });

  it('removes the partial file when one file is cancelled mid-upload and keeps the rest of the batch', async () => {
    const mock = createMockSftp({ directories: ['/home/alex/project/uploads'] });
    const service = serviceFor(mock);
    const large = new Uint8Array(3 * ATTACHMENT_WRITE_CHUNK_BYTES).fill(9);
    let cancelFirst = false;
    const staged = await service.stageAttachments({
      directory: 'uploads',
      scopeKey: 'session',
      timestamp: '20261118-101500',
      attachments: [bytesAttachmentSource('video.mp4', large), bytesAttachmentSource('note.txt', new Uint8Array([1, 2]))],
      // The user taps Cancel after the host acknowledged the first chunk.
      onProgress: (index) => { if (index === 0) cancelFirst = true; },
      isSourceCancelled: (index) => index === 0 && cancelFirst,
    });

    expect(staged.failures).toEqual([{ sourceName: 'video.mp4', sourceIndex: 0, message: 'Upload cancelled.', cancelled: true }]);
    expect(staged.decision).toMatchObject({ kind: 'partial', attachments: [{ sourceName: 'note.txt' }] });
    const left = [...mock.files.keys()].filter((path) => path.includes('video.mp4'));
    expect(left).toEqual([]);
    expect(mock.calls.remove).toHaveBeenCalledWith(expect.objectContaining({
      path: '/home/alex/project/uploads/session/20261118-101500-01-video.mp4.part',
    }));
  });

  it('removes the partial file when the host refuses a later chunk', async () => {
    const mock = createMockSftp({ directories: ['/home/alex/project/uploads'] });
    const service = serviceFor(mock);
    const real = mock.calls.chunk.getMockImplementation()!;
    mock.calls.chunk.mockImplementation(async (options: { offset: number }) => {
      if (options.offset > 0) throw new Error('No space left on device');
      return real(options);
    });
    const staged = await service.stageAttachments({
      directory: 'uploads',
      scopeKey: 'session',
      timestamp: '20261118-101500',
      attachments: [bytesAttachmentSource('photo.jpg', new Uint8Array(ATTACHMENT_WRITE_CHUNK_BYTES + 10))],
    });

    expect(staged.failures).toEqual([{ sourceName: 'photo.jpg', sourceIndex: 0, message: 'No space left on device' }]);
    expect([...mock.files.keys()].filter((path) => path.includes('photo.jpg'))).toEqual([]);
  });

  it('sweeps a .part a dropped connection left behind on the next upload to that folder, never a live one (#3191)', async () => {
    const mock = createMockSftp({ directories: ['/home/alex/project/uploads'] });
    const service = serviceFor(mock);
    const folder = '/home/alex/project/uploads/session';
    // First upload: the connection drops after the first chunk, so neither the
    // next chunk nor the cleanup delete can reach the host.
    const real = mock.calls.chunk.getMockImplementation()!;
    mock.calls.chunk.mockImplementation(async (options: { offset: number }) => {
      if (options.offset > 0) throw new Error('connection lost');
      return real(options);
    });
    mock.calls.remove.mockRejectedValueOnce(new Error('connection lost'));
    const dropped = await service.stageAttachments({
      directory: 'uploads', scopeKey: 'session', timestamp: '20261118-100000',
      attachments: [bytesAttachmentSource('video.mp4', new Uint8Array(ATTACHMENT_WRITE_CHUNK_BYTES + 1))],
    });
    expect(dropped.failures[0]?.message).toBe('connection lost');
    const orphan = `${folder}/20261118-100000-01-video.mp4.part`;
    expect(mock.files.has(orphan), 'the dropped upload left its .part').toBe(true);
    mock.calls.chunk.mockImplementation(real);
    // Its last write was 11 minutes ago; another upload is writing right now.
    mock.files.get(orphan)!.modifiedEpochMs = 1_000_000;
    const live = `${folder}/20261118-101000-01-other.mp4.part`;
    mock.files.set(live, { bytes: new Uint8Array(8), modifiedEpochMs: 1_000_000 + 11 * 60_000 - 5_000 });
    const oldDone = `${folder}/20261118-090000-01-done.jpg`;
    mock.files.set(oldDone, { bytes: new Uint8Array(4), modifiedEpochMs: 1 });

    const next = await service.stageAttachments({
      directory: 'uploads', scopeKey: 'session', timestamp: '20261118-101100',
      attachments: [bytesAttachmentSource('photo.jpg', new Uint8Array([1, 2, 3]))],
      nowMillis: 1_000_000 + 11 * 60_000,
    });

    expect(next.failures).toEqual([]);
    expect(mock.files.has(orphan), 'the stale .part is swept').toBe(false);
    expect(mock.files.has(live), 'a .part written seconds ago belongs to a live upload').toBe(true);
    expect(mock.files.has(oldDone), 'finished attachments are retention\'s business, not this sweep').toBe(true);
    expect(mock.files.has(`${folder}/20261118-101100-01-photo.jpg`)).toBe(true);
  });

  it('refuses a source that grows past the attachment limit while streaming and leaves nothing behind', async () => {
    const mock = createMockSftp({ directories: ['/home/alex/project/uploads'] });
    const service = serviceFor(mock);
    const staged = await service.stageAttachments({
      directory: 'uploads',
      scopeKey: 'session',
      timestamp: '20261118-101500',
      maxFileBytes: ATTACHMENT_WRITE_CHUNK_BYTES + 1,
      attachments: [{
        name: 'clip.mov', mimeType: 'video/quicktime', sizeBytes: null,
        read: async (_offset, maxBytes) => ({ bytes: new Uint8Array(maxBytes), eof: false }),
      }],
    });

    expect(staged.failures[0]?.message).toMatch(/^This video is .* MB; the limit is .* MB\.$/);
    expect([...mock.files.keys()].filter((path) => path.includes('clip.mov'))).toEqual([]);
  });

  it('keeps the text editor read limit at 512 KiB, separate from the 100 MiB attachment limit (#3191)', async () => {
    expect(MAX_SFTP_FILE_BYTES).toBe(512 * 1024);
    expect(DEFAULT_FILE_READ_BYTES).toBe(512 * 1024);
    expect(MAX_ATTACHMENT_FILE_BYTES).toBe(100 * 1024 * 1024);
    const mock = createMockSftp({ files: { '/home/alex/project/big.txt': { bytes: new Uint8Array(MAX_SFTP_FILE_BYTES + 1), modifiedEpochMs: 1_000 } } });
    const service = serviceFor(mock);
    await expect(service.readFile('big.txt', MAX_SFTP_FILE_BYTES + 1)).rejects.toMatchObject({ code: 'file-too-large' });
    await expect(service.readFile('big.txt')).rejects.toMatchObject({ code: 'file-too-large' });
    expect(mock.calls.read).not.toHaveBeenCalled();
  });

  it('leaves the remote tree untouched for an empty attachment batch', async () => {
    const mock = createMockSftp({ directories: ['/home/alex/project/uploads'] });
    const staged = await serviceFor(mock).stageAttachments({
      directory: 'uploads',
      scopeKey: 'empty-session',
      timestamp: '20260102-030405',
      attachments: [],
    });

    expect(staged.decision).toEqual({ kind: 'empty', attachments: [] });
    expect(mock.calls.list).not.toHaveBeenCalled();
    expect(mock.calls.mkdir).not.toHaveBeenCalled();
  });

  it('rejects files above the native transfer limit before issuing a write', async () => {
    const mock = createMockSftp();
    const service = serviceFor(mock);

    await expect(service.writeFile('large.bin', new Uint8Array(MAX_SFTP_FILE_BYTES + 1)))
      .rejects.toMatchObject({ code: 'file-too-large' });
    expect(mock.calls.write).not.toHaveBeenCalled();
  });

  it('rejects stale generations before requests and after in-flight responses', async () => {
    let current = false;
    const staleMock = createMockSftp();
    const staleService = serviceFor(staleMock, { isCurrent: () => current });
    await expect(staleService.listDirectory()).rejects.toMatchObject({ code: 'stale-generation' });
    expect(staleMock.calls.list).not.toHaveBeenCalled();

    current = true;
    const racingMock = createMockSftp();
    const racingService = serviceFor(racingMock, { isCurrent: () => current });
    racingMock.calls.write.mockImplementationOnce(async (options) => {
      const result = await Promise.resolve({ requestId: options.requestId, bytesWritten: 4 });
      current = false;
      return result;
    });
    await expect(racingService.writeFile('raced.txt', new TextEncoder().encode('late')))
      .rejects.toMatchObject({ code: 'stale-generation' });
    expect(racingMock.calls.write).toHaveBeenCalledTimes(1);
  });

  it('rejects native responses with an unrelated request ID', async () => {
    const mock = createMockSftp();
    mock.calls.list.mockImplementationOnce(async () => ({ requestId: 'wrong-id', entries: [] }));

    await expect(serviceFor(mock).listDirectory()).rejects.toMatchObject({ code: 'invalid-response' });
  });
});
