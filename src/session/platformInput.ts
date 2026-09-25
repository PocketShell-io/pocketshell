import type { AttachmentSource } from '@pocketshell/core';
import { MAX_SFTP_FILE_BYTES } from './files';
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
  StartDictationOptions,
} from '../native/speechRecognition';
import { documentContent } from '../native/documentContent';
import { speechRecognition } from '../native/speechRecognition';

const CHUNK_BYTES = 48 * 1024;
const MAX_PICKED_BATCH_BYTES = 4 * 1024 * 1024;

export interface PickedAttachmentFailure {
  name: string;
  message: string;
}

export interface PickedAttachmentBatch {
  sources: AttachmentSource[];
  failures: PickedAttachmentFailure[];
}

export interface SharedContent {
  requestId: string;
  mimeType: string | null;
  subject?: string;
  text?: string;
  attachments: AttachmentSource[];
  failures: PickedAttachmentFailure[];
  fileError?: string;
}

export interface DictationEvent {
  requestId: string;
  type: DictationEventType;
  text?: string;
  code?: string;
}

export interface PlatformInputServiceOptions {
  nextRequestId?: () => string;
  chunkBytes?: number;
  maxFileBytes?: number;
  maxBatchBytes?: number;
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
  options: PlatformInputServiceOptions = {},
) {
  let requestSequence = 0;
  const chunkBytes = boundedInteger(options.chunkBytes ?? CHUNK_BYTES, 1, 64 * 1024, 'chunkBytes');
  const maxFileBytes = boundedInteger(options.maxFileBytes ?? MAX_SFTP_FILE_BYTES, 1, Number.MAX_SAFE_INTEGER, 'maxFileBytes');
  const maxBatchBytes = boundedInteger(options.maxBatchBytes ?? MAX_PICKED_BATCH_BYTES, maxFileBytes, Number.MAX_SAFE_INTEGER, 'maxBatchBytes');

  function nextRequestId(): string {
    const requestId = options.nextRequestId?.() ?? defaultRequestId(++requestSequence);
    if (typeof requestId !== 'string' || requestId.trim() === '') {
      throw new PlatformInputError('invalid-request-id', 'A non-empty platform request ID is required.');
    }
    return requestId;
  }

  async function pickAttachments(): Promise<PickedAttachmentBatch> {
    const picked = await documents.pickFiles({ mimeType: '*/*', multiple: true });
    if (!isPickedDocuments(picked)) {
      throw new PlatformInputError('invalid-picker-response', 'The Android file picker returned an invalid response.');
    }
    if (picked.cancelled) return { sources: [], failures: [] };
    return readPickedDocuments(picked.files);
  }

  async function readPickedDocuments(files: readonly NativePickedDocument[]): Promise<PickedAttachmentBatch> {
    const sources: AttachmentSource[] = [];
    const failures: PickedAttachmentFailure[] = [];
    let batchBytes = 0;
    try {
      for (const file of files) {
        const name = validName(file?.name) ? file.name : 'Shared file';
        try {
          validatePickedDocument(file);
          if (file.sizeBytes != null && file.sizeBytes > maxFileBytes) {
            throw new PlatformInputError('file-too-large', `This transfer supports files up to ${maxFileBytes} bytes.`);
          }
          const remainingBatchBytes = maxBatchBytes - batchBytes;
          if (remainingBatchBytes <= 0) {
            throw new PlatformInputError('batch-too-large', `A single selection can contain at most ${maxBatchBytes} bytes.`);
          }
          const bytes = await readDocument(file, Math.min(maxFileBytes, remainingBatchBytes));
          batchBytes += bytes.byteLength;
          sources.push({
            kind: 'bytes',
            data: bytes,
            name,
            mimeType: typeof file.mimeType === 'string' && file.mimeType.length > 0 ? file.mimeType : null,
          });
        } catch (error) {
          failures.push({ name, message: errorMessage(error) });
        }
      }
    } finally {
      await Promise.all(files.map(async (file) => {
        if (typeof file?.fileId !== 'string' || file.fileId === '') return;
        try {
          await documents.releasePickedFile({ fileId: file.fileId });
        } catch {
          // Releasing is best-effort; the native registry is process-local and bounded.
        }
      }));
    }
    return { sources, failures };
  }

  async function listenForShares(onShare: (content: SharedContent) => void): Promise<() => Promise<void>> {
    const handle = await documents.addListener('shareReceived', (event) => {
      void materializeShare(event).then(onShare).catch((error: unknown) => {
        onShare({
          requestId: typeof event?.requestId === 'string' ? event.requestId : '',
          mimeType: typeof event?.mimeType === 'string' ? event.mimeType : null,
          attachments: [],
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
    const materialized = await readPickedDocuments(event.files);
    return {
      requestId: event.requestId,
      mimeType: event.mimeType,
      attachments: materialized.sources,
      failures: materialized.failures,
      ...(typeof event.text === 'string' ? { text: event.text } : {}),
      ...(typeof event.subject === 'string' ? { subject: event.subject } : {}),
      ...(typeof event.fileError === 'string' ? { fileError: event.fileError } : {}),
    };
  }

  async function startDictation(
    onEvent: (event: DictationEvent) => void,
    settings: Pick<StartDictationOptions, 'languageTag' | 'silenceWindowMs'> = {},
  ): Promise<{ requestId: string; stop: () => Promise<void> }> {
    const requestId = nextRequestId();
    let listener: Awaited<ReturnType<SpeechRecognitionPlugin['addListener']>> | undefined;
    try {
      listener = await speech.addListener('dictationEvent', (event: NativeDictationEvent) => {
        if (!isDictationEvent(event) || event.requestId !== requestId) return;
        onEvent({ ...event });
        if (event.type === 'stopped') void listener?.remove();
      });
      const started = await speech.startDictation({ requestId, ...settings });
      if (started.requestId !== requestId || started.started !== true) {
        throw new PlatformInputError('invalid-dictation-response', 'Android did not start the requested dictation session.');
      }
    } catch (error) {
      await listener?.remove();
      throw error;
    }

    let stopRequested = false;
    return {
      requestId,
      stop: async () => {
        if (stopRequested) return;
        stopRequested = true;
        const stopped = await speech.stopDictation({ requestId });
        if (stopped.requestId !== requestId || stopped.stopped !== true) {
          throw new PlatformInputError('invalid-dictation-response', 'Android stopped a different dictation session.');
        }
      },
    };
  }

  async function readDocument(file: NativePickedDocument, maxBytes: number): Promise<Uint8Array> {
    const chunks: Uint8Array[] = [];
    let totalBytes = 0;
    let eof = false;
    while (!eof) {
      if (totalBytes > maxBytes) {
        throw new PlatformInputError('file-too-large', `This transfer supports files up to ${maxBytes} bytes.`);
      }
      const requestedBytes = Math.min(chunkBytes, maxBytes - totalBytes || chunkBytes);
      const response = await documents.readPickedFileChunk({
        fileId: file.fileId,
        offset: totalBytes,
        maxBytes: requestedBytes,
      });
      const bytes = decodeChunk(response, file.fileId, totalBytes, requestedBytes);
      if (totalBytes + bytes.byteLength > maxBytes) {
        throw new PlatformInputError('file-too-large', `This transfer supports files up to ${maxBytes} bytes.`);
      }
      if (bytes.byteLength === 0 && response.eof !== true) {
        throw new PlatformInputError('empty-chunk', 'The Android document provider stopped before the file was complete.');
      }
      chunks.push(bytes);
      totalBytes += bytes.byteLength;
      eof = response.eof;
    }
    if (file.sizeBytes != null && totalBytes !== file.sizeBytes) {
      throw new PlatformInputError('file-changed', 'The selected file changed while it was being read. Pick it again.');
    }
    const output = new Uint8Array(totalBytes);
    let offset = 0;
    for (const chunk of chunks) {
      output.set(chunk, offset);
      offset += chunk.byteLength;
    }
    return output;
  }

  return {
    pickAttachments,
    listenForShares,
    startDictation,
  };
}

export const platformInput = createPlatformInputService(documentContent, speechRecognition);

function decodeChunk(chunk: NativeDocumentChunk, fileId: string, offset: number, maxBytes: number): Uint8Array {
  if (chunk.fileId !== fileId || chunk.offset !== offset
      || !Number.isSafeInteger(chunk.bytesRead) || chunk.bytesRead < 0 || chunk.bytesRead > maxBytes
      || typeof chunk.eof !== 'boolean' || typeof chunk.base64 !== 'string') {
    throw new PlatformInputError('invalid-document-chunk', 'The Android document bridge returned an invalid file chunk.');
  }
  try {
    const decoded = globalThis.atob(chunk.base64);
    if (decoded.length !== chunk.bytesRead) {
      throw new PlatformInputError('invalid-document-chunk', 'The Android document bridge returned a mismatched chunk size.');
    }
    return Uint8Array.from(decoded, (character) => character.charCodeAt(0));
  } catch (error) {
    if (error instanceof PlatformInputError) throw error;
    throw new PlatformInputError('invalid-document-chunk', 'The Android document bridge returned invalid Base64 data.');
  }
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
  return typeof event.requestId === 'string'
    && ['started', 'ready', 'listening', 'processing', 'partial', 'result', 'error', 'stopped'].includes(String(event.type))
    && (event.text === undefined || typeof event.text === 'string')
    && (event.code === undefined || typeof event.code === 'string');
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

function defaultRequestId(sequence: number): string {
  const random = globalThis.crypto?.randomUUID?.() ?? Math.random().toString(36).slice(2, 12);
  return `device-input-${Date.now().toString(36)}-${sequence.toString(36)}-${random}`;
}

function errorMessage(error: unknown): string {
  return error instanceof Error && error.message !== '' ? error.message : String(error);
}
