import {
  MAX_ATTACHMENT_BATCH_BYTES,
  MAX_ATTACHMENT_FILE_BYTES,
  attachmentBatchTooLargeMessage,
  attachmentTooLargeMessage,
} from './attachmentLimits';
import type {
  DocumentContentPlugin,
  NativeDocumentChunk,
  NativePickedDocument,
  NativeSharedContent,
} from '../native/documentContent';
import type {
  DictationEventType,
  NativeDictationEvent,
  SpeechRecognitionPlugin,
} from '../native/speechRecognition';
import { documentContent } from '../native/documentContent';
import { speechRecognition } from '../native/speechRecognition';

const CHUNK_BYTES = 48 * 1024;

export interface PickedAttachmentFailure {
  name: string;
  message: string;
  mimeType?: string | null;
  sizeBytes?: number | null;
}

/**
 * A picked or shared Android document that is still open on the native side
 * (#3191). Nothing is read up front: the uploader streams it in bounded
 * chunks, so a 100 MB video never sits in the WebView as one buffer. Release
 * it with `releaseDocument` once it is uploaded or abandoned.
 */
export interface PickedDocument {
  fileId: string;
  name: string;
  mimeType: string | null;
  /** Provider-reported size; null when the provider does not know it. */
  sizeBytes: number | null;
}

export interface PickedDocumentBatch {
  documents: PickedDocument[];
  failures: PickedAttachmentFailure[];
}

export interface PickedDocumentChunk {
  bytes: Uint8Array;
  eof: boolean;
}

export interface SharedContent {
  requestId: string;
  mimeType: string | null;
  subject?: string;
  text?: string;
  documents: PickedDocument[];
  failures: PickedAttachmentFailure[];
  fileError?: string;
}

export interface DictationEvent {
  requestId: string;
  type: DictationEventType;
  text?: string;
  code?: string;
}

/** Reusable Android recognizer options for composer and inline dictation. */
export interface DictationStartOptions {
  languageTag?: string;
  silenceWindowMs?: number;
}

export interface PlatformInputServiceOptions {
  chunkBytes?: number;
  maxFileBytes?: number;
  maxBatchBytes?: number;
  dictationTestMode?: () => boolean;
}

interface DictationRequestState {
  cancelled: boolean;
  nativeStartRequested: boolean;
  listener?: Awaited<ReturnType<SpeechRecognitionPlugin['addListener']>>;
}

export class PlatformInputError extends Error {
  constructor(readonly code: string, message: string) {
    super(message);
    this.name = 'PlatformInputError';
  }
}

/**
 * TypeScript adapter for native microphone, document-picker, and share I/O.
 * It validates request/event identity and turns opaque, bounded native reads
 * into the shared byte-based attachment shape consumed by file staging.
 */
export function createPlatformInputService(
  documents: DocumentContentPlugin,
  speech: SpeechRecognitionPlugin,
  serviceOptions: PlatformInputServiceOptions = {},
) {
  const dictationRequests = new Map<string, DictationRequestState>();
  const chunkBytes = boundedInteger(serviceOptions.chunkBytes ?? CHUNK_BYTES, 1, 64 * 1024, 'chunkBytes');
  const maxFileBytes = boundedInteger(serviceOptions.maxFileBytes ?? MAX_ATTACHMENT_FILE_BYTES, 1, Number.MAX_SAFE_INTEGER, 'maxFileBytes');
  const maxBatchBytes = boundedInteger(serviceOptions.maxBatchBytes ?? MAX_ATTACHMENT_BATCH_BYTES, maxFileBytes, Number.MAX_SAFE_INTEGER, 'maxBatchBytes');

  async function pickAttachments(): Promise<PickedDocumentBatch> {
    const picked = await documents.pickFiles({ mimeType: '*/*', multiple: true });
    if (!isPickedDocuments(picked)) {
      throw new PlatformInputError('invalid-picker-response', 'The Android file picker returned an invalid response.');
    }
    if (picked.cancelled) return { documents: [], failures: [] };
    return admitPickedDocuments(picked.files);
  }

  /**
   * Validate a selection against the attachment limits without reading it.
   * Refused documents are released here and reported with a readable reason;
   * admitted ones stay open for the uploader to stream.
   */
  async function admitPickedDocuments(files: readonly NativePickedDocument[]): Promise<PickedDocumentBatch> {
    const admitted: PickedDocument[] = [];
    const failures: PickedAttachmentFailure[] = [];
    let batchBytes = 0;
    for (const file of files) {
      const name = validName(file?.name) ? file.name : 'Shared file';
      const mimeType = typeof file?.mimeType === 'string' && file.mimeType.length > 0 ? file.mimeType : null;
      try {
        validatePickedDocument(file);
        const sizeBytes = file.sizeBytes ?? null;
        if (sizeBytes != null && sizeBytes > maxFileBytes) {
          throw new PlatformInputError('file-too-large', attachmentTooLargeMessage(name, mimeType, sizeBytes, maxFileBytes));
        }
        if (sizeBytes != null && batchBytes + sizeBytes > maxBatchBytes) {
          throw new PlatformInputError('batch-too-large', attachmentBatchTooLargeMessage(maxBatchBytes));
        }
        batchBytes += sizeBytes ?? 0;
        admitted.push({ fileId: file.fileId, name, mimeType, sizeBytes });
      } catch (error) {
        failures.push({ name, message: errorMessage(error), mimeType, sizeBytes: typeof file?.sizeBytes === 'number' ? file.sizeBytes : null });
        await releaseFileId(file?.fileId);
      }
    }
    return { documents: admitted, failures };
  }

  async function releaseFileId(fileId: unknown): Promise<void> {
    if (typeof fileId !== 'string' || fileId === '') return;
    try {
      await documents.releasePickedFile({ fileId });
    } catch {
      // Releasing is best-effort; the native registry is process-local and bounded.
    }
  }

  /** Release an admitted document once it is uploaded or abandoned. */
  async function releaseDocument(document: PickedDocument): Promise<void> {
    await releaseFileId(document.fileId);
  }

  /**
   * Read up to `maxBytes` starting at `offset`, in bounded native chunks.
   * Returns fewer bytes only at the end of the document (`eof`). Enforces the
   * per-file cap even when the provider did not report a size.
   */
  async function readDocumentChunk(document: PickedDocument, offset: number, maxBytes: number): Promise<PickedDocumentChunk> {
    if (!Number.isSafeInteger(offset) || offset < 0 || !Number.isSafeInteger(maxBytes) || maxBytes < 1) {
      throw new PlatformInputError('invalid-read-range', 'The file could not be read. Pick it again.');
    }
    const parts: Uint8Array[] = [];
    let read = 0;
    let eof = false;
    while (read < maxBytes && !eof) {
      const position = offset + read;
      const requestedBytes = Math.min(chunkBytes, maxBytes - read);
      const response = await documents.readPickedFileChunk({ fileId: document.fileId, offset: position, maxBytes: requestedBytes });
      const bytes = decodeChunk(response, document.fileId, position, requestedBytes);
      if (bytes.byteLength === 0 && response.eof !== true) {
        throw new PlatformInputError('empty-chunk', 'The Android document provider stopped before the file was complete.');
      }
      parts.push(bytes);
      read += bytes.byteLength;
      eof = response.eof;
      if (position + bytes.byteLength > maxFileBytes) {
        throw new PlatformInputError('file-too-large',
          attachmentTooLargeMessage(document.name, document.mimeType, position + bytes.byteLength, maxFileBytes));
      }
    }
    if (eof && document.sizeBytes != null && offset + read !== document.sizeBytes) {
      throw new PlatformInputError('file-changed', 'The selected file changed while it was being read. Pick it again.');
    }
    return { bytes: concatenate(parts, read), eof };
  }

  /** The whole document in memory (an image opened for annotation), bounded by `maxBytes`. */
  async function readDocument(document: PickedDocument, maxBytes = maxFileBytes): Promise<Uint8Array> {
    const parts: Uint8Array[] = [];
    let total = 0;
    for (;;) {
      const chunk = await readDocumentChunk(document, total, 512 * 1024);
      parts.push(chunk.bytes);
      total += chunk.bytes.byteLength;
      if (total > maxBytes) {
        throw new PlatformInputError('file-too-large', attachmentTooLargeMessage(document.name, document.mimeType, total, maxBytes));
      }
      if (chunk.eof) break;
    }
    return concatenate(parts, total);
  }

  async function listenForShares(onShare: (content: SharedContent) => void): Promise<() => Promise<void>> {
    const handle = await documents.addListener('shareReceived', (event) => {
      void materializeShare(event).then(onShare).catch((error: unknown) => {
        onShare({
          requestId: typeof event?.requestId === 'string' ? event.requestId : '',
          mimeType: typeof event?.mimeType === 'string' ? event.mimeType : null,
          documents: [],
          failures: [{ name: 'Shared content', message: errorMessage(error) }],
          ...(typeof event?.text === 'string' ? { text: event.text } : {}),
          ...(typeof event?.subject === 'string' ? { subject: event.subject } : {}),
        });
      });
    });
    return () => handle.remove();
  }

  async function materializeShare(event: NativeSharedContent): Promise<SharedContent> {
    validateShare(event);
    const admitted = await admitPickedDocuments(event.files);
    return {
      requestId: event.requestId,
      mimeType: event.mimeType,
      documents: admitted.documents,
      failures: admitted.failures,
      ...(typeof event.text === 'string' ? { text: event.text } : {}),
      ...(typeof event.subject === 'string' ? { subject: event.subject } : {}),
      ...(typeof event.fileError === 'string' ? { fileError: event.fileError } : {}),
    };
  }

  async function startRecognition(
    requestId: string,
    onEvent: (event: DictationEvent) => void,
    options: DictationStartOptions = {},
  ): Promise<void> {
    if (typeof requestId !== 'string' || requestId.trim() === '') {
      throw new PlatformInputError('invalid-request-id', 'Speech recognition could not start. Try again.');
    }
    if (dictationRequests.has(requestId)) {
      throw new PlatformInputError('duplicate-request-id', 'This recognition request is already active.');
    }
    const request: DictationRequestState = { cancelled: false, nativeStartRequested: false };
    dictationRequests.set(requestId, request);
    try {
      request.listener = await speech.addListener('dictationEvent', (event: NativeDictationEvent) => {
        if (!isDictationEvent(event) || event.requestId !== requestId) return;
        if (request.cancelled) return;
        onEvent({ ...event });
        if (isTerminalDictationEvent(event.type)) void removeDictationRequest(requestId, request);
      });
      if (request.cancelled) {
        throw new PlatformInputError('dictation-cancelled', 'Recognition was cancelled before it started.');
      }
      request.nativeStartRequested = true;
      const started = await speech.startDictation({
        requestId,
        ...(options.languageTag ? { languageTag: options.languageTag } : {}),
        ...(options.silenceWindowMs !== undefined ? { silenceWindowMs: options.silenceWindowMs } : {}),
        ...(serviceOptions.dictationTestMode?.() === true ? { testMode: true } : {}),
      });
      if (request.cancelled) {
        throw new PlatformInputError('dictation-cancelled', 'Recognition was cancelled before it started.');
      }
      if (started.requestId !== requestId || started.started !== true) {
        throw new PlatformInputError('invalid-dictation-response', 'Android did not start the requested recognition turn.');
      }
    } catch (error) {
      await removeDictationRequest(requestId, request);
      if (request.cancelled) {
        throw new PlatformInputError('dictation-cancelled', 'Recognition was cancelled before it started.');
      }
      throw error;
    }
  }

  async function stopRecognition(requestId: string): Promise<void> {
    const request = dictationRequests.get(requestId);
    if (!request || request.cancelled) return;
    const stopped = await speech.stopDictation({ requestId });
    if (stopped.requestId !== requestId || stopped.stopped !== true) {
      throw new PlatformInputError('invalid-dictation-response', 'Android stopped a different recognition turn.');
    }
  }

  async function cancelRecognition(requestId: string): Promise<void> {
    const request = dictationRequests.get(requestId);
    if (!request || request.cancelled) return;
    request.cancelled = true;
    try {
      if (request.nativeStartRequested) {
        const cancelled = await speech.cancelDictation({ requestId });
        if (cancelled.requestId !== requestId || typeof cancelled.cancelled !== 'boolean') {
          throw new PlatformInputError('invalid-dictation-response', 'Android cancelled a different recognition turn.');
        }
      }
    } finally {
      // JavaScript cancellation is authoritative for this request. Even if the
      // narrow native cancel bridge fails, no late result may reach the draft.
      await removeDictationRequest(requestId, request);
    }
  }

  async function removeDictationRequest(
    requestId: string,
    request: DictationRequestState,
  ): Promise<void> {
    if (dictationRequests.get(requestId) === request) dictationRequests.delete(requestId);
    const listener = request.listener;
    request.listener = undefined;
    await listener?.remove();
  }

  return {
    pickAttachments,
    readDocumentChunk,
    readDocument,
    releaseDocument,
    listenForShares,
    startRecognition,
    stopRecognition,
    cancelRecognition,
  };
}

export const platformInput = createPlatformInputService(documentContent, speechRecognition, {
  dictationTestMode: () => typeof window !== 'undefined'
    && (window as Window & { __ps2857DictationTestMode?: boolean }).__ps2857DictationTestMode === true,
});

function decodeChunk(chunk: NativeDocumentChunk, fileId: string, offset: number, maxBytes: number): Uint8Array {
  if (chunk.fileId !== fileId || chunk.offset !== offset
      || !Number.isSafeInteger(chunk.bytesRead) || chunk.bytesRead < 0 || chunk.bytesRead > maxBytes
      || typeof chunk.eof !== 'boolean' || typeof chunk.base64 !== 'string') {
    throw new PlatformInputError('invalid-document-chunk', 'Android returned an unreadable part of the file. Pick it again.');
  }
  try {
    const decoded = globalThis.atob(chunk.base64);
    if (decoded.length !== chunk.bytesRead) {
      throw new PlatformInputError('invalid-document-chunk', 'Android returned an incomplete part of the file. Pick it again.');
    }
    return Uint8Array.from(decoded, (character) => character.charCodeAt(0));
  } catch (error) {
    if (error instanceof PlatformInputError) throw error;
    throw new PlatformInputError('invalid-document-chunk', 'Android returned file contents that could not be read. Pick it again.');
  }
}

function concatenate(parts: readonly Uint8Array[], total: number): Uint8Array {
  if (parts.length === 1) return parts[0]!;
  const output = new Uint8Array(total);
  let offset = 0;
  for (const part of parts) {
    output.set(part, offset);
    offset += part.byteLength;
  }
  return output;
}

function validatePickedDocument(file: NativePickedDocument): void {
  if (typeof file?.fileId !== 'string' || file.fileId.trim() === '' || !validName(file.name)) {
    throw new PlatformInputError('invalid-picked-document', 'The Android file picker returned an invalid document reference.');
  }
  if (file.sizeBytes != null && (!Number.isSafeInteger(file.sizeBytes) || file.sizeBytes < 0)) {
    throw new PlatformInputError('invalid-picked-document', 'The Android file picker returned an invalid file size.');
  }
}

function validateShare(event: NativeSharedContent): void {
  if (typeof event?.requestId !== 'string' || event.requestId.trim() === ''
      || (event.action !== 'android.intent.action.SEND' && event.action !== 'android.intent.action.SEND_MULTIPLE')
      || !Array.isArray(event.files)) {
    throw new PlatformInputError('invalid-share-event', 'Android returned an invalid share intent.');
  }
}

function isPickedDocuments(value: unknown): value is { cancelled: boolean; files: NativePickedDocument[] } {
  return typeof value === 'object' && value !== null
    && typeof (value as Record<string, unknown>).cancelled === 'boolean'
    && Array.isArray((value as Record<string, unknown>).files);
}

function isDictationEvent(value: unknown): value is NativeDictationEvent {
  if (typeof value !== 'object' || value === null) return false;
  const event = value as Record<string, unknown>;
  if (typeof event.requestId !== 'string') return false;
  if (event.type === 'audio') {
    // #3062: a bounded sound/silence flag only; never text or other payload.
    return event.text === undefined && (event.code === 'sound' || event.code === 'silence');
  }
  return ['partial', 'result', 'recoverable', 'error'].includes(String(event.type))
    && (event.text === undefined || typeof event.text === 'string')
    && (event.code === undefined || typeof event.code === 'string');
}

function isTerminalDictationEvent(type: DictationEventType): boolean {
  return type !== 'partial' && type !== 'audio';
}

function validName(value: unknown): value is string {
  return typeof value === 'string' && value.trim() !== '';
}

function boundedInteger(value: number, minimum: number, maximum: number, name: string): number {
  if (!Number.isSafeInteger(value) || value < minimum || value > maximum) {
    throw new RangeError(`${name} is outside its supported range.`);
  }
  return value;
}

function errorMessage(error: unknown): string {
  return error instanceof Error && error.message !== '' ? error.message : String(error);
}
