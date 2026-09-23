import { registerPlugin, type Plugin, type PluginListenerHandle } from '@capacitor/core';

export interface NativePickedDocument {
  fileId: string;
  name: string;
  mimeType: string | null;
  sizeBytes: number | null;
}

export interface NativePickedDocuments {
  cancelled: boolean;
  files: NativePickedDocument[];
}

export interface NativeDocumentChunk {
  fileId: string;
  offset: number;
  bytesRead: number;
  eof: boolean;
  base64: string;
}

export interface NativeSharedContent {
  requestId: string;
  action: 'android.intent.action.SEND' | 'android.intent.action.SEND_MULTIPLE';
  mimeType: string | null;
  subject?: string;
  text?: string;
  files: NativePickedDocument[];
  fileError?: string;
}

export type DocumentContentPlugin = Plugin & {
  pickFiles(options: { mimeType: string; multiple: boolean }): Promise<NativePickedDocuments>;
  readPickedFileChunk(options: {
    fileId: string;
    offset: number;
    maxBytes: number;
  }): Promise<NativeDocumentChunk>;
  releasePickedFile(options: { fileId: string }): Promise<{ released: boolean }>;
  addListener(
    eventName: 'shareReceived',
    listener: (content: NativeSharedContent) => void,
  ): Promise<PluginListenerHandle>;
};

/** Android owns URI permission and stream I/O; this bridge exposes opaque file IDs only. */
export const documentContent = registerPlugin<DocumentContentPlugin>('DocumentContent');
