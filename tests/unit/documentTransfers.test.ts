import { describe, expect, it, vi } from 'vitest';
import { createDocumentTransferService } from '../../src/session/documentTransfers';
import type { DocumentContentPlugin } from '../../src/native/documentContent';

const file = { fileId: 'source-1', name: 'upload.bin', mimeType: 'application/octet-stream', sizeBytes: 10 };

function encoded(bytes: Uint8Array): string {
  return btoa(String.fromCharCode(...bytes));
}

function decoded(value: string): Uint8Array {
  return Uint8Array.from(atob(value), (character) => character.charCodeAt(0));
}

describe('bounded Android document transfers', () => {
  it('reads selected content URIs in ordered byte chunks and releases the URI', async () => {
    const source = Uint8Array.from([0, 1, 2, 127, 128, 254, 255, 8, 9, 10]);
    const offsets: number[] = [];
    const documents = {
      pickFiles: vi.fn(async () => ({ cancelled: false, files: [{ ...file, sizeBytes: null }] })),
      readPickedFileChunk: vi.fn(async ({ fileId, offset, maxBytes }: { fileId: string; offset: number; maxBytes: number }) => {
        offsets.push(offset);
        const bytes = source.subarray(offset, Math.min(offset + maxBytes, source.length));
        return { fileId, offset, bytesRead: bytes.length, eof: bytes.length < maxBytes, base64: encoded(bytes) };
      }),
      releasePickedFile: vi.fn(async () => ({ released: true })),
      createDocument: vi.fn(),
      writeCreatedDocumentChunk: vi.fn(),
      completeCreatedDocument: vi.fn(),
      abortCreatedDocument: vi.fn(),
    };
    const service = createDocumentTransferService(documents as unknown as DocumentContentPlugin, { maxBytes: 10, chunkBytes: 4 });

    const result = await service.pickUploadFile();

    expect(result).toEqual({ cancelled: false, document: { ...file, sizeBytes: null }, bytes: source });
    expect(offsets).toEqual([0, 4, 8, 10]);
    expect(documents.readPickedFileChunk.mock.calls.map(([request]) => request.maxBytes)).toEqual([4, 4, 2, 1]);
    expect(documents.releasePickedFile).toHaveBeenCalledWith({ fileId: 'source-1' });
  });

  it('treats picker and Save As cancellation as no-ops', async () => {
    const documents = {
      pickFiles: vi.fn(async () => ({ cancelled: true, files: [] })),
      readPickedFileChunk: vi.fn(),
      releasePickedFile: vi.fn(),
      createDocument: vi.fn(async () => ({ cancelled: true })),
      writeCreatedDocumentChunk: vi.fn(),
      completeCreatedDocument: vi.fn(),
      abortCreatedDocument: vi.fn(),
    };
    const service = createDocumentTransferService(documents as unknown as DocumentContentPlugin, { maxBytes: 10, chunkBytes: 4 });

    expect(await service.pickUploadFile()).toEqual({ cancelled: true });
    expect(await service.saveAs('binary.bin', null, Uint8Array.from([0, 255]))).toEqual({ cancelled: true });
    expect(documents.readPickedFileChunk).not.toHaveBeenCalled();
    expect(documents.releasePickedFile).not.toHaveBeenCalled();
    expect(documents.writeCreatedDocumentChunk).not.toHaveBeenCalled();
  });

  it('rejects a mismatched source chunk and still releases its document ID', async () => {
    const documents = {
      pickFiles: vi.fn(async () => ({ cancelled: false, files: [file] })),
      readPickedFileChunk: vi.fn(async () => ({
        fileId: 'foreign-id', offset: 0, bytesRead: 1, eof: true, base64: encoded(Uint8Array.of(7)),
      })),
      releasePickedFile: vi.fn(async () => ({ released: true })),
      createDocument: vi.fn(),
      writeCreatedDocumentChunk: vi.fn(),
      completeCreatedDocument: vi.fn(),
      abortCreatedDocument: vi.fn(),
    };
    const service = createDocumentTransferService(documents as unknown as DocumentContentPlugin, { maxBytes: 10, chunkBytes: 4 });

    await expect(service.pickUploadFile()).rejects.toThrow('mismatched chunk');
    expect(documents.releasePickedFile).toHaveBeenCalledWith({ fileId: 'source-1' });
  });

  it('writes exact binary bytes to the selected document and completes the byte count', async () => {
    const source = Uint8Array.from([0, 255, 1, 128, 2, 3, 250, 4, 5, 6]);
    const actual: number[] = [];
    const offsets: number[] = [];
    const documents = {
      pickFiles: vi.fn(),
      readPickedFileChunk: vi.fn(),
      releasePickedFile: vi.fn(),
      createDocument: vi.fn(async () => ({ cancelled: false, fileId: 'destination-1', name: 'download.bin' })),
      writeCreatedDocumentChunk: vi.fn(async ({ fileId, offset, base64 }: { fileId: string; offset: number; base64: string }) => {
        offsets.push(offset);
        const bytes = decoded(base64);
        actual.push(...bytes);
        return { fileId, offset: offset + bytes.length, bytesWritten: bytes.length };
      }),
      completeCreatedDocument: vi.fn(async ({ fileId, expectedBytes }: { fileId: string; expectedBytes: number }) => ({
        fileId, name: 'download.bin', bytesWritten: expectedBytes, complete: true,
      })),
      abortCreatedDocument: vi.fn(async () => ({ aborted: true, deleted: true })),
    };
    const service = createDocumentTransferService(documents as unknown as DocumentContentPlugin, { maxBytes: 10, chunkBytes: 4 });

    const result = await service.saveAs('download.bin', null, source);

    expect(result).toEqual({ cancelled: false, name: 'download.bin', bytesWritten: 10 });
    expect(offsets).toEqual([0, 4, 8]);
    expect(Uint8Array.from(actual)).toEqual(source);
    expect(documents.completeCreatedDocument).toHaveBeenCalledWith({ fileId: 'destination-1', expectedBytes: 10 });
    expect(documents.abortCreatedDocument).not.toHaveBeenCalled();
  });

  it('aborts a partial save when the document provider rejects a chunk', async () => {
    const documents = {
      pickFiles: vi.fn(),
      readPickedFileChunk: vi.fn(),
      releasePickedFile: vi.fn(),
      createDocument: vi.fn(async () => ({ cancelled: false, fileId: 'destination-1', name: 'download.bin' })),
      writeCreatedDocumentChunk: vi.fn(async () => { throw new Error('provider full'); }),
      completeCreatedDocument: vi.fn(),
      abortCreatedDocument: vi.fn(async () => ({ aborted: true, deleted: false })),
    };
    const service = createDocumentTransferService(documents as unknown as DocumentContentPlugin, { maxBytes: 10, chunkBytes: 4 });

    await expect(service.saveAs('download.bin', null, Uint8Array.of(1, 2, 3)))
      .rejects.toThrow(/provider full.*incomplete file/);
    expect(documents.completeCreatedDocument).not.toHaveBeenCalled();
    expect(documents.abortCreatedDocument).toHaveBeenCalledWith({ fileId: 'destination-1' });
  });
});
