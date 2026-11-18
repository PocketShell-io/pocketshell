import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { AndroidConnectionHub } from '@/platform/android/connectionHub';
import { createAndroidAttachments, releaseOpenedDocuments, sharedDocumentSources } from '@/platform/android/attachments';
import { androidUploads, cancelUpload, resetAndroidUploads } from '@/platform/android/attachmentUploads';
import type { PickedDocument } from '@/session/platformInput';

const mock = vi.hoisted(() => ({
  pick: vi.fn(), readChunk: vi.fn(), readAll: vi.fn(), release: vi.fn(), stage: vi.fn(), workspace: vi.fn(),
}));
vi.mock('@/session/platformInput', () => ({ platformInput: {
  pickAttachments: mock.pick, readDocumentChunk: mock.readChunk, readDocument: mock.readAll, releaseDocument: mock.release,
} }));
vi.mock('@/native/sshCapability', () => ({ sshCapability: { native: 'ssh capability proxy' } }));
vi.mock('@/session/files', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/session/files')>()),
  createFileWorkspaceService: mock.workspace,
}));

const photo: PickedDocument = { fileId: 'doc-photo', name: 'PXL_20261118_101512456.jpg', mimeType: 'image/jpeg', sizeBytes: 667_110 };

function harness() {
  let generationId = 'generation-1';
  const exec = vi.fn(async (_id: string, command: string) => ({ exitCode: 0, stdout: command.startsWith('printf') ? "/home/phone's account" : '', stderr: '' }));
  const hub = { exec, controllerOf: () => ({ getSnapshot: () => ({ connectionId: 'native-1', generationId }) }) } as unknown as AndroidConnectionHub;
  return { api: createAndroidAttachments(hub), exec, swapGeneration: () => { generationId = 'generation-2'; } };
}

beforeEach(async () => {
  vi.resetAllMocks();
  await releaseOpenedDocuments();
  vi.resetAllMocks();
  resetAndroidUploads();
  mock.pick.mockResolvedValue({ documents: [photo], failures: [] });
  mock.readChunk.mockResolvedValue({ bytes: new Uint8Array([1, 2, 3]), eof: true });
  mock.readAll.mockResolvedValue(new Uint8Array([1, 2, 3]));
  mock.release.mockResolvedValue(undefined);
  mock.stage.mockResolvedValue({ decision: { kind: 'complete', attachments: [{ path: '/remote/photo.jpg' }] }, failures: [] });
  mock.workspace.mockReturnValue({ stageAttachments: mock.stage });
});

describe('Android shared composer attachments', () => {
  it('keeps picked documents open behind opaque handles and streams them on the current native generation', async () => {
    const { api, exec } = harness();
    const [path] = await api.pickFiles();
    expect(path).toMatch(/^android-document:\d+\/PXL_20261118_101512456\.jpg$/);
    expect(mock.readChunk).not.toHaveBeenCalled();
    expect(await api.readLocal(path!)).toEqual(new Uint8Array([1, 2, 3]));

    const result = await api.stage({ connectionId: 'logical', scopeKey: 'workspace/session', sources: [{ kind: 'file', path: path! }] });

    expect(result).toEqual({ ok: true, paths: ['/remote/photo.jpg'], failedCount: 0 });
    expect(mock.workspace.mock.calls[0]![0]).toEqual({ native: 'ssh capability proxy' });
    expect(mock.workspace.mock.calls[0]![1].connection).toEqual({ connectionId: 'native-1', generationId: 'generation-1' });
    const request = mock.stage.mock.calls[0]![0];
    expect(request.timestamp).toMatch(/^\d{8}-\d{6}$/);
    expect(request.attachments[0]).toMatchObject({ name: photo.name, mimeType: 'image/jpeg', sizeBytes: 667_110 });
    // The source reads the open native document lazily, chunk by chunk.
    await request.attachments[0].read(524_288, 524_288);
    expect(mock.readChunk).toHaveBeenCalledWith(photo, 524_288, 524_288);
    expect(exec.mock.calls[1]![1]).toContain("'\\''");
    expect(mock.release).toHaveBeenCalledWith(photo);
    expect(androidUploads.value).toEqual([]);
    await expect(api.readLocal(path!)).rejects.toThrow('again');
  });

  it('shows an over-limit pick as a readable chip error instead of throwing a diagnostic', async () => {
    const { api } = harness();
    mock.pick.mockResolvedValue({ documents: [photo], failures: [{
      name: 'IMG_0001.jpg', mimeType: 'image/jpeg', sizeBytes: 120 * 1024 * 1024, message: 'This photo is 120.0 MB; the limit is 100.0 MB.',
    }] });

    const paths = await api.pickFiles();

    expect(paths).toHaveLength(1);
    expect(androidUploads.value).toEqual([expect.objectContaining({
      name: 'IMG_0001.jpg', state: 'rejected', message: 'This photo is 120.0 MB; the limit is 100.0 MB.',
    })]);
    // A new pick clears the stale refusal and releases the never-staged document.
    mock.pick.mockResolvedValue({ documents: [], failures: [] });
    await api.pickFiles();
    expect(androidUploads.value).toEqual([]);
    expect(mock.release).toHaveBeenCalledWith(photo);
  });

  it('shows per-file progress from acknowledged writes and lets one file be cancelled', async () => {
    const { api } = harness();
    const second: PickedDocument = { fileId: 'doc-2', name: 'second.jpg', mimeType: 'image/jpeg', sizeBytes: 1_700_000 };
    mock.pick.mockResolvedValue({ documents: [photo, second], failures: [] });
    const paths = await api.pickFiles();
    mock.stage.mockImplementation(async (request: {
      onStart(index: number): void; onProgress(index: number, written: number): void; isSourceCancelled(index: number): boolean;
    }) => {
      request.onStart(0);
      request.onProgress(0, 524_288);
      expect(androidUploads.value.map((chip) => [chip.name, chip.state, chip.written])).toEqual([
        [photo.name, 'uploading', 524_288], ['second.jpg', 'queued', 0],
      ]);
      expect(request.isSourceCancelled(1)).toBe(false);
      cancelUpload(androidUploads.value[1]!.id);
      expect(request.isSourceCancelled(1)).toBe(true);
      expect(androidUploads.value[1]!.message).toBe('Cancelling…');
      return {
        decision: { kind: 'partial', attachments: [{ path: '/remote/photo.jpg' }], failedCount: 1 },
        failures: [{ sourceName: 'second.jpg', sourceIndex: 1, message: 'Upload cancelled.', cancelled: true }],
      };
    });

    const result = await api.stage({ connectionId: 'logical', scopeKey: 'session', sources: paths.map((path) => ({ kind: 'file' as const, path })) });

    expect(result).toEqual({ ok: true, paths: ['/remote/photo.jpg'], failedCount: 0 });
    expect(androidUploads.value).toEqual([]);
    expect(mock.release).toHaveBeenCalledWith(photo);
    expect(mock.release).toHaveBeenCalledWith(second);
  });

  it('retains uploaded paths when another file fails and keeps the failed document open for a retry', async () => {
    const { api } = harness();
    const sources = sharedDocumentSources([photo, { ...photo, fileId: 'doc-3', name: 'third.jpg' }]);
    mock.stage.mockResolvedValue({
      decision: { kind: 'partial', attachments: [{ path: '/remote/first.jpg' }], failedCount: 1 },
      failures: [{ sourceName: 'third.jpg', sourceIndex: 1, message: 'No space left on device' }],
    });

    const result = await api.stage({ connectionId: 'logical', scopeKey: 'session', sources });

    expect(result).toEqual({ ok: false, paths: ['/remote/first.jpg'], failedCount: 1, error: 'third.jpg: No space left on device' });
    expect(mock.release).toHaveBeenCalledTimes(1);
    expect(mock.release).toHaveBeenCalledWith(photo);
    expect(androidUploads.value).toEqual([]);
  });

  it('streams in-memory bytes (a pasted or annotated image) through the same chunked path', async () => {
    const { api } = harness();
    await api.stage({ connectionId: 'logical', scopeKey: 'session', sources: [{ kind: 'bytes', data: new Uint8Array([5, 6, 7]), name: 'drawing.png' }] });
    const source = mock.stage.mock.calls[0]![0].attachments[0];
    expect(source).toMatchObject({ name: 'drawing.png', sizeBytes: 3 });
    expect(await source.read(1, 10)).toEqual({ bytes: new Uint8Array([6, 7]), eof: true });
  });

  it('refuses an outdated generation through the existing file staging guard and clears the chips after errors', async () => {
    const { api, swapGeneration } = harness();
    mock.stage.mockImplementation(async () => {
      const configuration = mock.workspace.mock.calls[0]![1];
      expect(configuration.isCurrent()).toBe(true);
      swapGeneration();
      expect(configuration.isCurrent()).toBe(false);
      throw new Error('generation changed');
    });
    expect(await api.stage({ connectionId: 'logical', scopeKey: 'session', sources: [{ kind: 'bytes', data: new Uint8Array([1]) }] })).toMatchObject({ ok: false, failedCount: 1, paths: [], error: 'generation changed' });
    expect(androidUploads.value).toEqual([]);
  });
});
