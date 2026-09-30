import type {
  DocumentContentPlugin,
  NativeCreatedDocument,
  NativeDocumentChunk,
  NativePickedDocument,
} from '../native/documentContent';

export const MAX_DOCUMENT_TRANSFER_BYTES = 512 * 1024;
export const DOCUMENT_TRANSFER_CHUNK_BYTES = 48 * 1024;

export type PickedDocumentBytes = {
  document: NativePickedDocument;
  bytes: Uint8Array;
};

export type PickDocumentResult =
  | { cancelled: true }
  | ({ cancelled: false } & PickedDocumentBytes);

export type SaveDocumentResult =
  | { cancelled: true }
  | { cancelled: false; name: string; bytesWritten: number };

type DocumentTransferPort = Pick<
  DocumentContentPlugin,
  | 'pickFiles'
  | 'readPickedFileChunk'
  | 'releasePickedFile'
  | 'createDocument'
  | 'writeCreatedDocumentChunk'
  | 'completeCreatedDocument'
  | 'abortCreatedDocument'
>;

/**
 * Moves bytes between the native SAF URI adapter and JS SFTP API in chunks.
 * Android retains URI permissions and its destination stream; JS carries at
 * most 48 KiB of content per bridge call (64 KiB after base64 encoding).
 */
export function createDocumentTransferService(
  documents: DocumentTransferPort,
  options: { maxBytes?: number; chunkBytes?: number } = {},
) {
  const maxBytes = boundedInteger(options.maxBytes ?? MAX_DOCUMENT_TRANSFER_BYTES, 1, MAX_DOCUMENT_TRANSFER_BYTES, 'maxBytes');
  const chunkBytes = boundedInteger(options.chunkBytes ?? DOCUMENT_TRANSFER_CHUNK_BYTES, 1, DOCUMENT_TRANSFER_CHUNK_BYTES, 'chunkBytes');

  async function pickUploadFile(): Promise<PickDocumentResult> {
    const result = await documents.pickFiles({ mimeType: '*/*', multiple: false });
    if (!isRecord(result) || typeof result.cancelled !== 'boolean' || !Array.isArray(result.files)) {
      throw new Error('The Android file picker returned an invalid response.');
    }
    if (result.cancelled) return { cancelled: true };
    try {
      if (result.files.length !== 1) throw new Error('Choose exactly one file to upload.');
      const document = parsePickedDocument(result.files[0]);
      const bytes = await readPickedFile(document);
      return { cancelled: false, document, bytes };
    } finally {
      await Promise.all(result.files.map(async (candidate) => {
        if (!isRecord(candidate) || typeof candidate.fileId !== 'string' || candidate.fileId.trim() === '') return;
        await documents.releasePickedFile({ fileId: candidate.fileId }).catch(() => undefined);
      }));
    }
  }

  async function readPickedFile(document: NativePickedDocument): Promise<Uint8Array> {
    if (document.sizeBytes !== null && document.sizeBytes > maxBytes) {
      throw tooLargeError();
    }
    const parts: Uint8Array[] = [];
    let offset = 0;
    while (true) {
      const probingLimit = offset === maxBytes;
      const requestBytes = probingLimit ? 1 : Math.min(chunkBytes, maxBytes - offset);
      const response = await documents.readPickedFileChunk({
        fileId: document.fileId,
        offset,
        maxBytes: requestBytes,
      });
      const bytes = validateDocumentChunk(response, document.fileId, offset, requestBytes);
      if (probingLimit && bytes.byteLength > 0) throw tooLargeError();
      if (offset + bytes.byteLength > maxBytes) throw tooLargeError();
      if (bytes.byteLength > 0) parts.push(bytes);
      offset += bytes.byteLength;

      if (document.sizeBytes !== null && offset > document.sizeBytes) {
        throw new Error('The selected document changed while it was being read.');
      }
      if (response.eof) {
        if (document.sizeBytes !== null && offset !== document.sizeBytes) {
          throw new Error('The selected document changed while it was being read.');
        }
        return joinBytes(parts, offset);
      }
      if (bytes.byteLength === 0) throw new Error('The document provider stopped before reaching the end of the file.');
      if (document.sizeBytes !== null && offset === document.sizeBytes) {
        throw new Error('The document provider did not confirm the end of the selected file.');
      }
      if (offset === maxBytes) continue;
    }
  }

  async function saveAs(name: string, mimeType: string | null, bytes: Uint8Array): Promise<SaveDocumentResult> {
    if (!(bytes instanceof Uint8Array) || bytes.byteLength > maxBytes) throw tooLargeError();
    if (name.trim() === '' || name.length > 255) throw new Error('The download filename is invalid.');
    const safeMimeType = validMimeType(mimeType) ? mimeType : 'application/octet-stream';
    const created = await documents.createDocument({ name, mimeType: safeMimeType });
    let destination: NativeCreatedDocument & { fileId: string; name: string };
    try {
      destination = parseCreatedDocument(created);
    } catch (error) {
      if (isRecord(created) && typeof created.fileId === 'string' && created.fileId.trim() !== '') {
        await documents.abortCreatedDocument({ fileId: created.fileId }).catch(() => undefined);
      }
      throw error;
    }
    if (destination.cancelled) return { cancelled: true };

    let offset = 0;
    try {
      while (offset < bytes.byteLength) {
        const chunk = bytes.subarray(offset, Math.min(offset + chunkBytes, bytes.byteLength));
        const response = await documents.writeCreatedDocumentChunk({
          fileId: destination.fileId,
          offset,
          base64: encodeBase64(chunk),
        });
        const expectedOffset = offset + chunk.byteLength;
        if (!isRecord(response)
          || response.fileId !== destination.fileId
          || response.offset !== expectedOffset
          || response.bytesWritten !== chunk.byteLength) {
          throw new Error('The Android document provider did not confirm the written bytes.');
        }
        offset = expectedOffset;
      }
      const completed = await documents.completeCreatedDocument({
        fileId: destination.fileId,
        expectedBytes: bytes.byteLength,
      });
      if (!isRecord(completed)
        || completed.fileId !== destination.fileId
        || completed.complete !== true
        || completed.bytesWritten !== bytes.byteLength
        || completed.name !== destination.name) {
        throw new Error('The Android document provider did not confirm the saved file.');
      }
      return { cancelled: false, name: completed.name, bytesWritten: completed.bytesWritten };
    } catch (error) {
      let deleted = false;
      try {
        deleted = (await documents.abortCreatedDocument({ fileId: destination.fileId })).deleted;
      } catch {
        // A provider failure must not hide the original write error.
      }
      if (deleted) throw error;
      const reason = error instanceof Error ? error.message : String(error);
      throw new Error(`${reason} The selected destination may contain an incomplete file; check it before using it.`);
    }
  }

  return { pickUploadFile, saveAs };

  function tooLargeError(): Error {
    return new Error(`Choose a file no larger than ${maxBytes} bytes.`);
  }

  function validateDocumentChunk(
    value: NativeDocumentChunk,
    fileId: string,
    offset: number,
    maxChunkBytes: number,
  ): Uint8Array {
    if (!isRecord(value)
      || value.fileId !== fileId
      || value.offset !== offset
      || typeof value.bytesRead !== 'number'
      || !Number.isSafeInteger(value.bytesRead)
      || value.bytesRead < 0
      || value.bytesRead > maxChunkBytes
      || typeof value.eof !== 'boolean'
      || typeof value.base64 !== 'string') {
      throw new Error('The Android document provider returned a mismatched chunk.');
    }
    const bytes = decodeBase64(value.base64);
    if (bytes.byteLength !== value.bytesRead) throw new Error('The Android document provider returned an invalid byte count.');
    return bytes;
  }
}

function parsePickedDocument(value: unknown): NativePickedDocument {
  if (!isRecord(value)
    || typeof value.fileId !== 'string'
    || value.fileId.trim() === ''
    || typeof value.name !== 'string'
    || value.name.trim() === ''
    || !(value.mimeType === null || typeof value.mimeType === 'string')
    || !(value.sizeBytes === null || (typeof value.sizeBytes === 'number'
      && Number.isSafeInteger(value.sizeBytes) && value.sizeBytes >= 0))) {
    throw new Error('The Android file picker returned invalid document metadata.');
  }
  return value as unknown as NativePickedDocument;
}

function parseCreatedDocument(value: NativeCreatedDocument): NativeCreatedDocument & { fileId: string; name: string } {
  if (!isRecord(value) || typeof value.cancelled !== 'boolean') {
    throw new Error('The Android save picker returned an invalid response.');
  }
  if (value.cancelled) return value as NativeCreatedDocument & { fileId: string; name: string };
  if (typeof value.fileId !== 'string' || value.fileId.trim() === ''
    || typeof value.name !== 'string' || value.name.trim() === '') {
    throw new Error('The Android save picker returned invalid document metadata.');
  }
  return value as NativeCreatedDocument & { fileId: string; name: string };
}

function joinBytes(parts: readonly Uint8Array[], length: number): Uint8Array {
  const bytes = new Uint8Array(length);
  let offset = 0;
  for (const part of parts) {
    bytes.set(part, offset);
    offset += part.byteLength;
  }
  return bytes;
}

function encodeBase64(bytes: Uint8Array): string {
  let binary = '';
  for (let offset = 0; offset < bytes.length; offset += 0x8000) {
    binary += String.fromCharCode(...bytes.subarray(offset, Math.min(offset + 0x8000, bytes.length)));
  }
  return btoa(binary);
}

function decodeBase64(encoded: string): Uint8Array {
  try {
    const binary = atob(encoded);
    const bytes = new Uint8Array(binary.length);
    for (let index = 0; index < binary.length; index += 1) bytes[index] = binary.charCodeAt(index);
    return bytes;
  } catch {
    throw new Error('The Android document provider returned invalid file bytes.');
  }
}

function validMimeType(value: string | null): value is string {
  return value !== null && value.length > 0 && value.length <= 127
    && /^[A-Za-z0-9!#$&^_.+-]+\/[A-Za-z0-9!#$&^_.+-]+$/.test(value);
}

function boundedInteger(value: number, minimum: number, maximum: number, label: string): number {
  if (!Number.isSafeInteger(value) || value < minimum || value > maximum) {
    throw new RangeError(`${label} must be an integer between ${minimum} and ${maximum}.`);
  }
  return value;
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null;
}
