import { describe, expect, it, vi } from 'vitest';
import type { DocumentContentPlugin, NativePickedDocument, NativeSharedContent } from '../../src/native/documentContent';
import type { NativeDictationEvent, SpeechRecognitionPlugin } from '../../src/native/speechRecognition';
import { createPlatformInputService } from '../../src/session/platformInput';

function asBase64(bytes: Uint8Array): string {
  return btoa(String.fromCharCode(...bytes));
}

function picked(fileId: string, name: string, bytes: Uint8Array, mimeType = 'text/plain'): NativePickedDocument {
  return { fileId, name, mimeType, sizeBytes: bytes.byteLength };
}

function makeDocumentPlugin(files: Record<string, Uint8Array>) {
  let shareListener: ((event: NativeSharedContent) => void) | undefined;
  const plugin = {
    pickFiles: vi.fn(async () => ({ cancelled: false, files: [] as NativePickedDocument[] })),
    readPickedFileChunk: vi.fn(async ({ fileId, offset, maxBytes }: { fileId: string; offset: number; maxBytes: number }) => {
      const source = files[fileId];
      const bytes = source.slice(offset, offset + maxBytes);
      return {
        fileId,
        offset,
        bytesRead: bytes.byteLength,
        eof: offset + bytes.byteLength >= source.byteLength,
        base64: asBase64(bytes),
      };
    }),
    releasePickedFile: vi.fn(async () => ({ released: true })),
    addListener: vi.fn(async (_name: string, listener: (event: NativeSharedContent) => void) => {
      shareListener = listener;
      return { remove: vi.fn(async () => { shareListener = undefined; }) };
    }),
    emitShare(event: NativeSharedContent) { shareListener?.(event); },
  };
  return plugin;
}

function makeSpeechPlugin() {
  let dictationListener: ((event: NativeDictationEvent) => void) | undefined;
  let lastDictationListener: ((event: NativeDictationEvent) => void) | undefined;
  const plugin = {
    getCapabilities: vi.fn(async () => ({ speechRecognitionAvailable: true, microphonePermissionGranted: false })),
    startDictation: vi.fn(async ({ requestId }: { requestId: string }) => ({ requestId, started: true })),
    stopDictation: vi.fn(async ({ requestId }: { requestId: string }) => ({ requestId, stopped: true })),
    cancelDictation: vi.fn(async ({ requestId }: { requestId: string }) => ({ requestId, cancelled: true })),
    addListener: vi.fn(async (_name: string, listener: (event: NativeDictationEvent) => void) => {
      dictationListener = listener;
      lastDictationListener = listener;
      return { remove: vi.fn(async () => { dictationListener = undefined; }) };
    }),
    emitDictation(event: NativeDictationEvent) { dictationListener?.(event); },
    emitLateDictation(event: NativeDictationEvent) { lastDictationListener?.(event); },
  };
  return plugin;
}

describe('Android platform input adapter', () => {
  it('reads selected documents in ordered bounded chunks and returns exact attachment bytes', async () => {
    const originalBytes = new TextEncoder().encode('café 🧪\nline two');
    const documents = makeDocumentPlugin({ picked: originalBytes });
    documents.pickFiles.mockResolvedValue({ cancelled: false, files: [picked('picked', 'notes.txt', originalBytes)] });
    const service = createPlatformInputService(
      documents as unknown as DocumentContentPlugin,
      makeSpeechPlugin() as unknown as SpeechRecognitionPlugin,
      { chunkBytes: 3, maxFileBytes: 40, maxBatchBytes: 80 },
    );

    const result = await service.pickAttachments();

    expect(documents.pickFiles).toHaveBeenCalledWith({ mimeType: '*/*', multiple: true });
    expect(result.failures).toEqual([]);
    expect(result.documents).toEqual([{ fileId: 'picked', name: 'notes.txt', mimeType: 'text/plain', sizeBytes: originalBytes.byteLength }]);
    // Picking reads nothing: the uploader streams the document later.
    expect(documents.readPickedFileChunk).not.toHaveBeenCalled();
    const first = await service.readDocumentChunk(result.documents[0]!, 0, 7);
    expect(first.eof).toBe(false);
    const rest = await service.readDocumentChunk(result.documents[0]!, 7, 64);
    expect(rest.eof).toBe(true);
    expect(new Uint8Array([...first.bytes, ...rest.bytes])).toEqual(originalBytes);
    expect(documents.readPickedFileChunk.mock.calls.map(([request]) => request.offset)).toEqual([0, 3, 6, 7, 10, 13, 16]);
    expect(await service.readDocument(result.documents[0]!)).toEqual(originalBytes);
    await service.releaseDocument(result.documents[0]!);
    expect(documents.releasePickedFile).toHaveBeenCalledWith({ fileId: 'picked' });
  });

  it('admits normal phone photos far above the 512 KiB editor limit with the default attachment limits (#3191)', async () => {
    // The maintainer's two failing camera JPEGs and the third 1.67 MB pick.
    const documents = makeDocumentPlugin({});
    documents.pickFiles.mockResolvedValue({ cancelled: false, files: [
      { fileId: 'a', name: 'PXL_20261118_101500123.jpg', mimeType: 'image/jpeg', sizeBytes: 627_672 },
      { fileId: 'b', name: 'PXL_20261118_101512456.jpg', mimeType: 'image/jpeg', sizeBytes: 667_110 },
      { fileId: 'c', name: 'PXL_20261118_101530789.jpg', mimeType: 'image/jpeg', sizeBytes: 1_670_000 },
    ] });
    const service = createPlatformInputService(
      documents as unknown as DocumentContentPlugin,
      makeSpeechPlugin() as unknown as SpeechRecognitionPlugin,
    );

    const result = await service.pickAttachments();

    expect(result.failures).toEqual([]);
    expect(result.documents.map((document) => document.fileId)).toEqual(['a', 'b', 'c']);
    expect(documents.releasePickedFile).not.toHaveBeenCalled();
  });

  it('refuses an over-limit file with a readable human-sized reason and keeps the valid ones', async () => {
    const documents = makeDocumentPlugin({ small: new Uint8Array([0, 1, 2]) });
    documents.pickFiles.mockResolvedValue({ cancelled: false, files: [
      { fileId: 'large', name: 'IMG_0001.jpg', mimeType: 'image/jpeg', sizeBytes: 120 * 1024 * 1024 },
      { fileId: 'small', name: 'small.bin', mimeType: 'application/octet-stream', sizeBytes: 3 },
    ] });
    const service = createPlatformInputService(
      documents as unknown as DocumentContentPlugin,
      makeSpeechPlugin() as unknown as SpeechRecognitionPlugin,
    );

    const result = await service.pickAttachments();

    expect(result.documents.map((document) => document.name)).toEqual(['small.bin']);
    expect(result.failures).toEqual([{
      name: 'IMG_0001.jpg', mimeType: 'image/jpeg', sizeBytes: 120 * 1024 * 1024,
      message: 'This photo is 120.0 MB; the limit is 100.0 MB.',
    }]);
    expect(result.failures[0]!.message).not.toMatch(/\d{5,}|bytes/);
    expect(documents.releasePickedFile).toHaveBeenCalledWith({ fileId: 'large' });
    expect(documents.releasePickedFile).toHaveBeenCalledTimes(1);
  });

  it('refuses the part of a selection past the batch limit in plain words', async () => {
    const documents = makeDocumentPlugin({});
    documents.pickFiles.mockResolvedValue({ cancelled: false, files: [
      { fileId: 'one', name: 'one.mp4', mimeType: 'video/mp4', sizeBytes: 90 * 1024 * 1024 },
      { fileId: 'two', name: 'two.mp4', mimeType: 'video/mp4', sizeBytes: 90 * 1024 * 1024 },
      { fileId: 'three', name: 'three.mp4', mimeType: 'video/mp4', sizeBytes: 90 * 1024 * 1024 },
    ] });
    const service = createPlatformInputService(
      documents as unknown as DocumentContentPlugin,
      makeSpeechPlugin() as unknown as SpeechRecognitionPlugin,
    );

    const result = await service.pickAttachments();

    expect(result.documents.map((document) => document.fileId)).toEqual(['one', 'two']);
    expect(result.failures.map((failure) => failure.message)).toEqual([
      'Together these files are over the 200.0 MB limit for one selection. Pick fewer files.',
    ]);
  });

  it('enforces the file limit while streaming a document whose size the provider did not report', async () => {
    const documents = makeDocumentPlugin({ unknown: new Uint8Array(12) });
    documents.pickFiles.mockResolvedValue({ cancelled: false, files: [{ fileId: 'unknown', name: 'clip.mov', mimeType: null, sizeBytes: null }] });
    const service = createPlatformInputService(
      documents as unknown as DocumentContentPlugin,
      makeSpeechPlugin() as unknown as SpeechRecognitionPlugin,
      { chunkBytes: 4, maxFileBytes: 8, maxBatchBytes: 8 },
    );
    const { documents: [document] } = await service.pickAttachments();
    await expect(service.readDocumentChunk(document!, 0, 12)).rejects.toThrow('This video is');
  });

  it('rejects mismatched chunk identity with a readable message', async () => {
    const documents = makeDocumentPlugin({ picked: new Uint8Array([1, 2]) });
    documents.pickFiles.mockResolvedValue({ cancelled: false, files: [picked('picked', 'notes.bin', new Uint8Array([1, 2]))] });
    documents.readPickedFileChunk.mockResolvedValue({
      fileId: 'another-file', offset: 0, bytesRead: 2, eof: true, base64: 'AQI=',
    });
    const service = createPlatformInputService(
      documents as unknown as DocumentContentPlugin,
      makeSpeechPlugin() as unknown as SpeechRecognitionPlugin,
      { maxFileBytes: 4, maxBatchBytes: 4 },
    );

    const result = await service.pickAttachments();

    await expect(service.readDocumentChunk(result.documents[0]!, 0, 4)).rejects.toThrow('Android returned an unreadable part of the file. Pick it again.');
  });

  it('delivers incoming shared text and open file references without reading them', async () => {
    const bytes = new TextEncoder().encode('from another app');
    const documents = makeDocumentPlugin({ shareFile: bytes });
    const service = createPlatformInputService(
      documents as unknown as DocumentContentPlugin,
      makeSpeechPlugin() as unknown as SpeechRecognitionPlugin,
      { chunkBytes: 5, maxFileBytes: 64, maxBatchBytes: 64 },
    );
    const onShare = vi.fn();
    await service.listenForShares(onShare);
    documents.emitShare({
      requestId: 'share-1',
      action: 'android.intent.action.SEND',
      mimeType: 'text/plain',
      subject: 'Shared subject',
      text: 'Shared body',
      files: [picked('shareFile', 'shared.txt', bytes)],
    });
    await vi.waitFor(() => expect(onShare).toHaveBeenCalledTimes(1));

    expect(onShare.mock.calls[0]?.[0]).toMatchObject({
      requestId: 'share-1',
      subject: 'Shared subject',
      text: 'Shared body',
      documents: [{ fileId: 'shareFile', name: 'shared.txt', mimeType: 'text/plain', sizeBytes: bytes.byteLength }],
      failures: [],
    });
    expect(documents.readPickedFileChunk).not.toHaveBeenCalled();
    expect(documents.releasePickedFile).not.toHaveBeenCalled();
  });

  it('registers before starting one turn, filters foreign events, and stops the matching request', async () => {
    const documents = makeDocumentPlugin({});
    const speech = makeSpeechPlugin();
    const events: NativeDictationEvent[] = [];
    speech.startDictation.mockImplementation(async ({ requestId }) => {
      speech.emitDictation({ requestId: 'foreign', type: 'partial', text: 'ignored' });
      speech.emitDictation({ requestId, type: 'partial', text: 'café' });
      return { requestId, started: true };
    });
    const service = createPlatformInputService(
      documents as unknown as DocumentContentPlugin,
      speech as unknown as SpeechRecognitionPlugin,
      {},
    );

    await service.startRecognition('dictation-1', (event) => events.push(event), { languageTag: 'fr-FR', silenceWindowMs: 7_000 });
    await service.stopRecognition('dictation-1');
    speech.emitDictation({ requestId: 'dictation-1', type: 'result', text: 'café' });

    expect(speech.addListener.mock.invocationCallOrder[0]).toBeLessThan(speech.startDictation.mock.invocationCallOrder[0]);
    expect(events).toEqual([
      { requestId: 'dictation-1', type: 'partial', text: 'café' },
      { requestId: 'dictation-1', type: 'result', text: 'café' },
    ]);
    expect(speech.startDictation).toHaveBeenCalledWith({ requestId: 'dictation-1', languageTag: 'fr-FR', silenceWindowMs: 7_000 });
    expect(speech.stopDictation).toHaveBeenCalledWith({ requestId: 'dictation-1' });
  });

  it('cancels a request and drops even a late queued partial or final event', async () => {
    const documents = makeDocumentPlugin({});
    const speech = makeSpeechPlugin();
    const events: NativeDictationEvent[] = [];
    const service = createPlatformInputService(
      documents as unknown as DocumentContentPlugin,
      speech as unknown as SpeechRecognitionPlugin,
      {},
    );

    await service.startRecognition('dictation-cancel-1', (event) => events.push(event));
    speech.emitDictation({ requestId: 'dictation-cancel-1', type: 'partial', text: 'draft preview' });
    await service.cancelRecognition('dictation-cancel-1');
    speech.emitLateDictation({ requestId: 'dictation-cancel-1', type: 'result', text: 'must be ignored' });

    expect(events).toEqual([{ requestId: 'dictation-cancel-1', type: 'partial', text: 'draft preview' }]);
    expect(speech.cancelDictation).toHaveBeenCalledWith({ requestId: 'dictation-cancel-1' });
    expect(speech.stopDictation).not.toHaveBeenCalled();
  });

  it('cancels a pending native start without allowing its eventual turn or events through', async () => {
    const documents = makeDocumentPlugin({});
    const speech = makeSpeechPlugin();
    let resolveNativeStart!: (response: { requestId: string; started: boolean }) => void;
    speech.startDictation.mockImplementation(({ requestId }) => new Promise((resolve) => {
      resolveNativeStart = resolve;
    }));
    const events: NativeDictationEvent[] = [];
    const service = createPlatformInputService(
      documents as unknown as DocumentContentPlugin,
      speech as unknown as SpeechRecognitionPlugin,
      {},
    );
    const pendingStart = service.startRecognition('dictation-pending-cancel', (event) => events.push(event));
    await vi.waitFor(() => expect(speech.startDictation).toHaveBeenCalledTimes(1));

    await service.cancelRecognition('dictation-pending-cancel');
    resolveNativeStart({ requestId: 'dictation-pending-cancel', started: true });

    await expect(pendingStart).rejects.toMatchObject({ code: 'dictation-cancelled' });
    speech.emitLateDictation({ requestId: 'dictation-pending-cancel', type: 'partial', text: 'late' });
    expect(events).toEqual([]);
    expect(speech.cancelDictation).toHaveBeenCalledWith({ requestId: 'dictation-pending-cancel' });
  });
});
