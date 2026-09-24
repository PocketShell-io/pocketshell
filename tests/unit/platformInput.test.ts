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
  const pendingShares: NativeSharedContent[] = [];
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
      for (const event of pendingShares.splice(0)) shareListener(event);
      return { remove: vi.fn(async () => { shareListener = undefined; }) };
    }),
    emitShare(event: NativeSharedContent) {
      if (shareListener) shareListener(event);
      else pendingShares.push(event);
    },
  };
  return plugin;
}

function makeSpeechPlugin() {
  let dictationListener: ((event: NativeDictationEvent) => void) | undefined;
  const plugin = {
    getCapabilities: vi.fn(async () => ({ speechRecognitionAvailable: true, microphonePermissionGranted: false })),
    startDictation: vi.fn(async ({ requestId }: { requestId: string }) => ({ requestId, started: true })),
    stopDictation: vi.fn(async ({ requestId }: { requestId: string }) => ({ requestId, stopped: true })),
    addListener: vi.fn(async (_name: string, listener: (event: NativeDictationEvent) => void) => {
      dictationListener = listener;
      return { remove: vi.fn(async () => { dictationListener = undefined; }) };
    }),
    emitDictation(event: NativeDictationEvent) { dictationListener?.(event); },
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
      { nextRequestId: () => 'request-1', chunkBytes: 3, maxFileBytes: 40, maxBatchBytes: 80 },
    );

    const result = await service.pickAttachments();

    expect(documents.pickFiles).toHaveBeenCalledWith({ mimeType: '*/*', multiple: true });
    expect(result.failures).toEqual([]);
    expect(result.sources).toHaveLength(1);
    expect(result.sources[0]).toMatchObject({ kind: 'bytes', name: 'notes.txt', mimeType: 'text/plain' });
    expect((result.sources[0] as { kind: 'bytes'; data: Uint8Array }).data).toEqual(originalBytes);
    expect(documents.readPickedFileChunk.mock.calls.map(([request]) => request.offset)).toEqual([0, 3, 6, 9, 12, 15, 18]);
    expect(documents.releasePickedFile).toHaveBeenCalledWith({ fileId: 'picked' });
  });

  it('preserves valid files when one selection is over the shared native transfer limit', async () => {
    const documents = makeDocumentPlugin({ small: new Uint8Array([0, 1, 2]) });
    documents.pickFiles.mockResolvedValue({ cancelled: false, files: [
      { fileId: 'large', name: 'large.bin', mimeType: 'application/octet-stream', sizeBytes: 5 },
      { fileId: 'small', name: 'small.bin', mimeType: 'application/octet-stream', sizeBytes: 3 },
    ] });
    const service = createPlatformInputService(
      documents as unknown as DocumentContentPlugin,
      makeSpeechPlugin() as unknown as SpeechRecognitionPlugin,
      { chunkBytes: 2, maxFileBytes: 4, maxBatchBytes: 8 },
    );

    const result = await service.pickAttachments();

    expect(result.sources).toHaveLength(1);
    expect(result.sources[0]).toMatchObject({ kind: 'bytes', name: 'small.bin' });
    expect(result.failures).toEqual([{ name: 'large.bin', message: 'This transfer supports files up to 4 bytes.' }]);
    expect(documents.readPickedFileChunk.mock.calls.map(([request]) => request.fileId)).toEqual(['small', 'small']);
    expect(documents.releasePickedFile).toHaveBeenCalledTimes(2);
  });

  it('rejects mismatched chunk identity and still releases the native document reference', async () => {
    const documents = makeDocumentPlugin({ picked: new Uint8Array([1, 2]) });
    documents.pickFiles.mockResolvedValue({ cancelled: false, files: [picked('picked', 'notes.bin', new Uint8Array([1, 2]))] });
    documents.readPickedFileChunk.mockResolvedValue({
      fileId: 'another-file', offset: 0, bytesRead: 2, eof: true, base64: 'AQI=',
    });
    const service = createPlatformInputService(
      documents as unknown as DocumentContentPlugin,
      makeSpeechPlugin() as unknown as SpeechRecognitionPlugin,
      { nextRequestId: () => 'request-3', maxFileBytes: 4, maxBatchBytes: 4 },
    );

    const result = await service.pickAttachments();

    expect(result.sources).toEqual([]);
    expect(result.failures[0]?.message).toBe('The Android document bridge returned an invalid file chunk.');
    expect(documents.releasePickedFile).toHaveBeenCalledWith({ fileId: 'picked' });
  });

  it('materializes incoming shared text and files before delivering the share callback', async () => {
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
      attachments: [{ kind: 'bytes', name: 'shared.txt', mimeType: 'text/plain', data: bytes }],
      failures: [],
    });
    expect(documents.releasePickedFile).toHaveBeenCalledWith({ fileId: 'shareFile' });
  });

  it('delivers a retained share that arrived before the JS listener registered', async () => {
    const bytes = Uint8Array.from([0, 0xff, 0x41, 0x0a]);
    const documents = makeDocumentPlugin({ late: bytes });
    documents.emitShare({
      requestId: 'late-share-1',
      action: 'android.intent.action.SEND',
      mimeType: 'application/octet-stream',
      text: 'review this file',
      files: [picked('late', 'bytes.bin', bytes, 'application/octet-stream')],
    });
    const service = createPlatformInputService(
      documents as unknown as DocumentContentPlugin,
      makeSpeechPlugin() as unknown as SpeechRecognitionPlugin,
      { chunkBytes: 2, maxFileBytes: 64, maxBatchBytes: 64 },
    );
    const onShare = vi.fn();

    await service.listenForShares(onShare);
    await vi.waitFor(() => expect(onShare).toHaveBeenCalledTimes(1));

    expect(onShare.mock.calls[0]?.[0]).toMatchObject({
      requestId: 'late-share-1',
      text: 'review this file',
      attachments: [{ kind: 'bytes', name: 'bytes.bin', data: bytes }],
      failures: [],
    });
    expect(documents.releasePickedFile).toHaveBeenCalledWith({ fileId: 'late' });
  });

  it('registers before starting speech, filters foreign events, and stops the matching request', async () => {
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
      { nextRequestId: () => 'dictation-1' },
    );

    const session = await service.startDictation((event) => events.push(event), {
      languageTag: 'fr-FR',
      silenceWindowMs: 9_000,
    });
    await session.stop();

    expect(speech.addListener.mock.invocationCallOrder[0]).toBeLessThan(speech.startDictation.mock.invocationCallOrder[0]);
    expect(events).toEqual([{ requestId: 'dictation-1', type: 'partial', text: 'café' }]);
    expect(speech.startDictation).toHaveBeenCalledWith({
      requestId: 'dictation-1',
      languageTag: 'fr-FR',
      silenceWindowMs: 9_000,
    });
    expect(speech.stopDictation).toHaveBeenCalledWith({ requestId: 'dictation-1' });
  });
});
