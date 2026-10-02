/**
 * Browser dev mode (#3022): `DocumentContent` over `<input type=file>` for
 * picks and a Blob download for "Save to device", keeping Android's opaque
 * file-ID contract so the upload/download code paths run unchanged.
 */
import type { DevPlugin, FakeNativeBridge } from './nativeBridge';
import { DevPluginError } from './nativeBridge';
import { base64ToBytes, bytesToBase64, randomId } from './bytes';

export interface PickedBrowserFile {
  name: string;
  type: string;
  size: number;
  arrayBuffer(): Promise<ArrayBuffer>;
}

export type FilePicker = (accept: string, multiple: boolean) => Promise<PickedBrowserFile[] | null>;
export type FileSaver = (name: string, mimeType: string, bytes: Uint8Array) => void;

/** Open the browser's file chooser; resolves null when the user cancels. */
export function browserFilePicker(doc: Document): FilePicker {
  return (accept, multiple) => new Promise((resolve) => {
    const input = doc.createElement('input');
    input.type = 'file';
    input.multiple = multiple;
    if (accept && accept !== '*/*') input.accept = accept;
    input.style.display = 'none';
    let settled = false;
    const settle = (files: PickedBrowserFile[] | null) => {
      if (settled) return;
      settled = true;
      input.remove();
      resolve(files);
    };
    input.addEventListener('change', () => settle(input.files ? [...input.files] : null));
    input.addEventListener('cancel', () => settle(null));
    doc.body.append(input);
    input.click();
  });
}

export function browserFileSaver(doc: Document): FileSaver {
  return (name, mimeType, bytes) => {
    const url = URL.createObjectURL(new Blob([bytes], { type: mimeType || 'application/octet-stream' }));
    const anchor = doc.createElement('a');
    anchor.href = url;
    anchor.download = name;
    doc.body.append(anchor);
    anchor.click();
    anchor.remove();
    setTimeout(() => URL.revokeObjectURL(url), 10_000);
  };
}

export interface DocumentPluginOptions {
  bridge: () => FakeNativeBridge;
  pick: FilePicker;
  save: FileSaver;
}

export interface DocumentPlugin extends DevPlugin {
  /** Simulate an Android share-sheet delivery into the app. */
  share(content: { text?: string; subject?: string; files?: PickedBrowserFile[] }): number;
}

export function createDocumentPlugin(options: DocumentPluginOptions): DocumentPlugin {
  const picked = new Map<string, PickedBrowserFile>();
  const created = new Map<string, { name: string; mimeType: string; chunks: Uint8Array[]; bytes: number }>();
  const describe = (fileId: string, file: PickedBrowserFile) => ({
    fileId,
    name: file.name,
    mimeType: file.type || null,
    sizeBytes: file.size,
  });
  const remember = (file: PickedBrowserFile) => {
    const fileId = randomId();
    picked.set(fileId, file);
    return describe(fileId, file);
  };
  return {
    methods: {
      pickFiles: async (request) => {
        const files = await options.pick(String(request.mimeType ?? '*/*'), request.multiple === true);
        if (!files || files.length === 0) return { cancelled: true, files: [] };
        return { cancelled: false, files: files.map(remember) };
      },
      readPickedFileChunk: async (request) => {
        const fileId = String(request.fileId);
        const file = picked.get(fileId);
        if (!file) throw new DevPluginError('Picked file is no longer available.', 'NOT_FOUND');
        const offset = Number(request.offset ?? 0);
        const maxBytes = Number(request.maxBytes ?? 65_536);
        const all = new Uint8Array(await file.arrayBuffer());
        const chunk = all.subarray(offset, Math.min(all.length, offset + maxBytes));
        return {
          fileId,
          offset,
          bytesRead: chunk.length,
          eof: offset + chunk.length >= all.length,
          base64: bytesToBase64(chunk),
        };
      },
      releasePickedFile: (request) => ({ released: picked.delete(String(request.fileId)) }),
      createDocument: (request) => {
        const fileId = randomId();
        const name = String(request.name ?? 'download');
        const mimeType = String(request.mimeType ?? 'application/octet-stream');
        created.set(fileId, { name, mimeType, chunks: [], bytes: 0 });
        return { cancelled: false, fileId, name, mimeType };
      },
      writeCreatedDocumentChunk: (request) => {
        const fileId = String(request.fileId);
        const target = created.get(fileId);
        if (!target) throw new DevPluginError('Document is not open for writing.', 'NOT_FOUND');
        if (Number(request.offset) !== target.bytes) throw new DevPluginError('Document chunk is out of order.', 'INVALID_ARGUMENT');
        const bytes = base64ToBytes(String(request.base64 ?? ''));
        target.chunks.push(bytes);
        target.bytes += bytes.length;
        return { fileId, offset: request.offset, bytesWritten: bytes.length };
      },
      completeCreatedDocument: (request) => {
        const fileId = String(request.fileId);
        const target = created.get(fileId);
        if (!target) throw new DevPluginError('Document is not open for writing.', 'NOT_FOUND');
        created.delete(fileId);
        if (Number(request.expectedBytes) !== target.bytes) {
          throw new DevPluginError('Document size does not match the expected bytes.', 'INVALID_ARGUMENT');
        }
        const joined = new Uint8Array(target.bytes);
        let offset = 0;
        for (const chunk of target.chunks) {
          joined.set(chunk, offset);
          offset += chunk.length;
        }
        options.save(target.name, target.mimeType, joined);
        return { fileId, name: target.name, bytesWritten: target.bytes, complete: true };
      },
      abortCreatedDocument: (request) => {
        const deleted = created.delete(String(request.fileId));
        return { aborted: true, deleted };
      },
    },
    share(content) {
      const files = (content.files ?? []).map(remember);
      return options.bridge().emit('DocumentContent', 'shareReceived', {
        requestId: randomId(),
        action: files.length > 1 ? 'android.intent.action.SEND_MULTIPLE' : 'android.intent.action.SEND',
        mimeType: files.length > 0 ? files[0].mimeType : 'text/plain',
        ...(content.subject ? { subject: content.subject } : {}),
        ...(content.text ? { text: content.text } : {}),
        files,
      });
    },
  };
}
