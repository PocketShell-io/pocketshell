import { beforeEach, describe, expect, it, vi } from 'vitest';
import {
  createFileWorkspaceService,
  MAX_SFTP_FILE_BYTES,
  type AttachmentSource,
} from '../../src/session/files';
import type {
  SshCapabilityPlugin,
  SshConnectionRef,
  SshSftpEntry,
  SshSftpWriteProgressEvent,
} from '../../src/native/sshCapability';
import type { AttachmentUploadProgressSnapshot } from '@pocketshell/core';

interface MockFile {
  bytes: Uint8Array;
  modifiedEpochMs: number;
  symlink?: boolean;
}

interface MockSftp {
  capability: SshCapabilityPlugin;
  files: Map<string, MockFile>;
  directories: Set<string>;
  emitProgress: (event: SshSftpWriteProgressEvent) => void;
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
  const progressListeners = new Set<(event: SshSftpWriteProgressEvent) => void>();
  const emitProgress = (event: SshSftpWriteProgressEvent) => {
    for (const listener of progressListeners) listener(event);
  };
  const addListener = vi.fn(async (_eventName: string, listener: (event: SshSftpWriteProgressEvent) => void) => {
    progressListeners.add(listener);
    return { remove: vi.fn(async () => { progressListeners.delete(listener); }) };
  });
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
  const write = vi.fn(async (options: {
    requestId: string;
    connectionId: string;
    generationId: string;
    path: string;
    dataBase64: string;
    createOnly?: boolean;
    reportProgress?: boolean;
  }) => {
    if (options.createOnly && files.has(options.path)) throw new Error(`Already exists: ${options.path}`);
    const content = base64ToBytes(options.dataBase64);
    if (options.reportProgress) {
      for (let offset = 0; offset < content.byteLength; offset += 2) {
        emitProgress({
          requestId: options.requestId,
          connectionId: options.connectionId,
          generationId: options.generationId,
          path: options.path,
          bytesWritten: Math.min(content.byteLength, offset + 2),
          totalBytes: content.byteLength,
        });
      }
    }
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
    addListener,
    removeAllListeners: vi.fn(),
    sftpList: list,
    sftpRead: read,
    sftpWrite: write,
    sftpWriteIfUnchanged: conditionalWrite,
    sftpMkdir: mkdir,
    sftpRename: rename,
    sftpDelete: remove,
  } as unknown as SshCapabilityPlugin;
  return {
    capability,
    files,
    directories,
    emitProgress,
    atomicWrite,
    calls: { list, read, write, conditionalWrite, mkdir, rename, remove },
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
      { name: '../plan for review?.md', bytes: valid },
      { name: 'too-large.bin', bytes: new Uint8Array(MAX_SFTP_FILE_BYTES + 1) },
    ];
    const staged = await service.stageAttachments({
      directory: 'uploads',
      scopeKey: 'Host A/session',
      timestamp: '20260102-030405',
      attachments,
    });

    expect(staged.directory).toBe('/home/alex/project/uploads/host-a-session');
    expect(staged.decision).toMatchObject({ kind: 'partial', failedCount: 1, attachments: [{
      sourceIndex: 0,
      filename: '20260102-030405-01-plan_for_review.md',
      sourceName: '../plan for review?.md',
      sizeBytes: valid.length,
    }] });
    expect(staged.failures).toEqual([expect.objectContaining({ sourceIndex: 1, sourceName: 'too-large.bin' })]);
    expect(mock.files.has(`${staged.directory}/20260102-030405-02-too-large.bin`)).toBe(false);

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

  it('aggregates only request-matched SFTP callback bytes across staged files', async () => {
    const mock = createMockSftp();
    const updates: Array<{
      fileName: string;
      fileIndex: number;
      fileBytesWritten: number;
      batchBytesWritten: number;
      batchBytesTotal: number;
    }> = [];
    const staged = await serviceFor(mock).stageAttachments({
      directory: '/home/alex/project/uploads',
      scopeKey: 'session-a',
      timestamp: '20260102-030405',
      attachments: [
        { name: 'first.bin', bytes: Uint8Array.from([1, 2, 3, 4, 5]) },
        { name: 'second.bin', bytes: Uint8Array.from([6, 7]) },
        { name: 'empty.bin', bytes: new Uint8Array(0) },
      ],
      onProgress: (progress) => updates.push({
        fileName: progress.fileName,
        fileIndex: progress.fileIndex,
        fileBytesWritten: progress.fileBytesWritten,
        batchBytesWritten: progress.batchBytesWritten,
        batchBytesTotal: progress.batchBytesTotal,
      }),
    });

    expect(staged.decision.kind).toBe('complete');
    expect(mock.calls.write).toHaveBeenCalledTimes(3);
    expect(mock.calls.write.mock.calls.map(([options]) => options.reportProgress)).toEqual([true, true, false]);
    expect(updates.map((progress) => progress.batchBytesWritten)).toEqual([2, 4, 5, 5, 7, 7]);
    expect(updates.map((progress) => progress.fileIndex)).toEqual([0, 0, 0, 0, 1, 1]);
    expect(updates.at(-1)).toMatchObject({
      fileName: 'second.bin',
      fileBytesWritten: 2,
      batchBytesWritten: 7,
      batchBytesTotal: 7,
    });
    expect(updates.every((progress, index) => index === 0
      || progress.batchBytesWritten >= updates[index - 1]!.batchBytesWritten)).toBe(true);
  });

  it('stops accepting byte callbacks when a write fails or its connection goes stale', async () => {
    const failedMock = createMockSftp();
    const failedUpdates: number[] = [];
    failedMock.calls.write.mockImplementationOnce(async (options: {
      requestId: string;
      connectionId: string;
      generationId: string;
      path: string;
      dataBase64: string;
    }) => {
      failedMock.emitProgress({ ...options, bytesWritten: 2, totalBytes: 4 });
      throw new Error('SFTP write failed');
    });
    const failed = await serviceFor(failedMock).stageAttachments({
      directory: '/home/alex/project/uploads',
      scopeKey: 'failed-session',
      timestamp: '20260102-030405',
      attachments: [{ name: 'failed.bin', bytes: Uint8Array.from([1, 2, 3, 4]) }],
      onProgress: (progress) => failedUpdates.push(progress.fileBytesWritten),
    });
    const failedRequest = failedMock.calls.write.mock.calls[0]?.[0];
    failedMock.emitProgress({
      requestId: failedRequest?.requestId ?? 'late',
      connectionId: connection.connectionId,
      generationId: connection.generationId,
      path: failedRequest?.path ?? '/late',
      bytesWritten: 4,
      totalBytes: 4,
    });

    expect(failed.decision.kind).toBe('failed');
    expect(failedUpdates).toEqual([2]);

    const closedMock = createMockSftp();
    let current = true;
    const closedUpdates: number[] = [];
    closedMock.calls.write.mockImplementationOnce(async (options: {
      requestId: string;
      connectionId: string;
      generationId: string;
      path: string;
      dataBase64: string;
    }) => {
      closedMock.emitProgress({ ...options, bytesWritten: 2, totalBytes: 4 });
      current = false;
      closedMock.emitProgress({ ...options, bytesWritten: 4, totalBytes: 4 });
      return { requestId: options.requestId, bytesWritten: 4 };
    });
    await expect(createFileWorkspaceService(closedMock.capability, {
      connection,
      rootDirectory: '/home/alex/project',
      isCurrent: () => current,
      nextRequestId: (() => { let request = 0; return () => `closed-${++request}`; })(),
    }).stageAttachments({
      directory: '/home/alex/project/uploads',
      scopeKey: 'closed-session',
      timestamp: '20260102-030405',
      attachments: [{ name: 'closed.bin', bytes: Uint8Array.from([1, 2, 3, 4]) }],
      onProgress: (progress) => closedUpdates.push(progress.fileBytesWritten),
    })).rejects.toMatchObject({ code: 'stale-generation' });

    expect(closedUpdates).toEqual([2]);
  });

  it('clears failed-file progress while continuing a later upload without reporting fabricated bytes', async () => {
    const mock = createMockSftp();
    let startSecondWrite!: () => void;
    const secondWriteStarted = new Promise<void>((resolve) => { startSecondWrite = resolve; });
    let continueSecondWrite!: () => void;
    const secondWriteGate = new Promise<void>((resolve) => { continueSecondWrite = resolve; });
    const secondWriteCapture: { options?: {
      requestId: string;
      connectionId: string;
      generationId: string;
      path: string;
      dataBase64: string;
    } } = {};
    mock.calls.write.mockImplementation(async (options: {
      requestId: string;
      connectionId: string;
      generationId: string;
      path: string;
      dataBase64: string;
    }) => {
      const bytes = base64ToBytes(options.dataBase64);
      if (options.path.endsWith('first.bin')) {
        mock.emitProgress({ ...options, bytesWritten: 2, totalBytes: bytes.byteLength });
        throw new Error('SFTP write failed');
      }
      secondWriteCapture.options = options;
      startSecondWrite();
      await secondWriteGate;
      return { requestId: options.requestId, bytesWritten: bytes.byteLength };
    });

    const progressUpdates: Array<AttachmentUploadProgressSnapshot> = [];
    const clears: string[] = [];
    let settled = false;
    const staging = serviceFor(mock).stageAttachments({
      directory: '/home/alex/project/uploads',
      scopeKey: 'failure-then-next-session',
      timestamp: '20260102-030405',
      attachments: [
        { name: 'first.bin', bytes: Uint8Array.from([1, 2, 3, 4]) },
        { name: 'second.bin', bytes: Uint8Array.from([5, 6, 7, 8]) },
      ],
      onProgress: (progress) => progressUpdates.push(progress),
      onProgressCleared: () => clears.push('cleared'),
    }).then((result) => {
      settled = true;
      return result;
    });

    await secondWriteStarted;
    expect(settled).toBe(false);
    expect(progressUpdates).toHaveLength(1);
    expect(progressUpdates[0]).toMatchObject({ fileName: 'first.bin', fileBytesWritten: 2 });
    expect(clears).toEqual(['cleared']);

    const secondWriteOptions = secondWriteCapture.options;
    if (!secondWriteOptions) throw new Error('The second SFTP write did not start.');
    mock.emitProgress({ ...secondWriteOptions, bytesWritten: 2, totalBytes: 4 });
    expect(progressUpdates).toHaveLength(1);
    expect(clears).toEqual(['cleared']);

    continueSecondWrite();
    const staged = await staging;
    expect(staged.decision.kind).toBe('partial');
    expect(staged.failures.map((failure) => failure.sourceName)).toEqual(['first.bin']);
    expect(staged.decision.kind === 'partial' ? staged.decision.attachments.map((file) => file.sourceName) : [])
      .toEqual(['second.bin']);
    expect(progressUpdates).toHaveLength(1);
  });

  it('creates the desktop-compatible hidden attachment tree and writes exact bytes', async () => {
    const mock = createMockSftp();
    const service = serviceFor(mock, { rootDirectory: '/home/alex' });
    const bytes = Uint8Array.from([0, 0xff, 0x41, 0x0a]);

    const staged = await service.stageAttachments({
      directory: '/home/alex/.pocketshell/attachments',
      scopeKey: 'project-main',
      timestamp: '20260924-101112',
      attachments: [{ name: 'shared bytes.bin', bytes }],
    });

    expect(mock.directories).toContain('/home/alex/.pocketshell');
    expect(mock.directories).toContain('/home/alex/.pocketshell/attachments');
    expect(staged.directory).toBe('/home/alex/.pocketshell/attachments/project-main');
    expect(staged.decision.kind).toBe('complete');
    const attachment = staged.decision.kind === 'complete' ? staged.decision.attachments[0] : undefined;
    expect(attachment).toMatchObject({ sourceIndex: 0, sourceName: 'shared bytes.bin', sizeBytes: bytes.length });
    expect(mock.files.get(attachment!.path)?.bytes).toEqual(bytes);
  });

  it('does not return completed uploads as attachments after cancellation', async () => {
    const mock = createMockSftp({ directories: ['/home/alex/project/uploads'] });
    const service = serviceFor(mock);
    let checks = 0;
    const staged = await service.stageAttachments({
      directory: 'uploads',
      scopeKey: 'session-a',
      timestamp: '20260102-030405',
      attachments: [
        { name: 'first.txt', bytes: new TextEncoder().encode('one') },
        { name: 'second.txt', bytes: new TextEncoder().encode('two') },
      ],
      isCancelled: () => ++checks >= 3,
    });

    expect(staged.decision).toEqual({ kind: 'cancelled', attachments: [], completedUploadCount: 1 });
    expect(mock.calls.write).toHaveBeenCalledTimes(1);
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
