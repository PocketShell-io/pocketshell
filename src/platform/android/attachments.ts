import type { AttachmentSource as CoreAttachmentSource } from '@pocketshell/core';
import type { PocketShellApi } from '@ui/app/api';
import { platformInput, type PickedDocument } from '@/session/platformInput';
import { bytesAttachmentSource, createFileWorkspaceService, type AttachmentSource } from '@/session/files';
import { sshCapability } from '@/native/sshCapability';
import type { AndroidConnectionHub } from './connectionHub';
import {
  clearRejected,
  finishUpload,
  isUploadCancelled,
  markUploading,
  queueUploads,
  rejectFiles,
  reportUploadProgress,
} from './attachmentUploads';

const DOCUMENT_PATH_PREFIX = 'android-document:';

/**
 * The shared app's `attachments` API on Android (#3191). Picked and shared
 * documents stay open natively behind opaque `android-document:` paths and
 * are streamed to the host in bounded chunks at stage time: nothing is read
 * into the WebView up front, so a photo or video is limited by the attachment
 * cap (100 MB), not by the text editor's 512 KiB read limit.
 */
/**
 * Documents still open natively, by opaque path. Process-wide, like the
 * native registry behind it: the picker and the share target both feed it.
 */
const opened = new Map<string, { document: PickedDocument; origin: 'picker' | 'share' }>();
let sequence = 0;

function register(document: PickedDocument, origin: 'picker' | 'share'): string {
  const path = `${DOCUMENT_PATH_PREFIX}${++sequence}/${encodeURIComponent(document.name)}`;
  opened.set(path, { document, origin });
  return path;
}

async function release(path: string): Promise<void> {
  const entry = opened.get(path);
  if (!entry) return;
  opened.delete(path);
  await platformInput.releaseDocument(entry.document);
}

function documentSource(document: PickedDocument): AttachmentSource {
  return {
    name: document.name,
    mimeType: document.mimeType,
    sizeBytes: document.sizeBytes,
    read: (offset, maxBytes) => platformInput.readDocumentChunk(document, offset, maxBytes),
  };
}

/** Shared documents as staging sources for `composer.stage` (the share target). */
export function sharedDocumentSources(documents: readonly PickedDocument[]): CoreAttachmentSource[] {
  return documents.map((document) => ({
    kind: 'file' as const,
    path: register(document, 'share'),
    name: document.name,
    mimeType: document.mimeType,
  }));
}

/** Release every open document (a share the user dismissed, tests). */
export async function releaseOpenedDocuments(origin?: 'picker' | 'share'): Promise<void> {
  await Promise.all([...opened].filter(([, entry]) => origin === undefined || entry.origin === origin).map(([path]) => release(path)));
}

export function createAndroidAttachments(hub: AndroidConnectionHub): PocketShellApi['attachments'] {
  return {
    async pickFiles() {
      // A new pick supersedes documents picked earlier but never staged.
      await releaseOpenedDocuments('picker');
      clearRejected();
      const batch = await platformInput.pickAttachments();
      // A refused file is shown on its own chip with a readable reason. It
      // is not thrown: a rejection here would surface only as a diagnostic.
      rejectFiles(batch.failures);
      return batch.documents.map((document) => register(document, 'picker'));
    },
    async readLocal(path) {
      const entry = opened.get(path);
      if (!entry) throw new Error('Choose this Android document again.');
      return platformInput.readDocument(entry.document);
    },
    async stage(payload) {
      // Documents are released once uploaded or cancelled. One that failed
      // stays open so the share target can retry it; the next pick or a
      // dismissed share releases it.
      const settled = new Set<number>();
      let chipIds: string[] = [];
      try {
        const sources: AttachmentSource[] = payload.sources.map((source) => {
          if (source.kind === 'bytes') return bytesAttachmentSource(source.name ?? 'attachment', source.data, source.mimeType ?? null);
          const entry = opened.get(source.path);
          if (!entry) throw new Error('Choose this Android document again.');
          return documentSource(entry.document);
        });
        chipIds = queueUploads(sources.map((source) => ({ name: source.name, mimeType: source.mimeType ?? null, sizeBytes: source.sizeBytes })));
        const snapshot = hub.controllerOf(payload.connectionId)?.getSnapshot();
        if (!snapshot?.connectionId || !snapshot.generationId) throw new Error('Connect before uploading attachments.');
        const homeResult = await hub.exec(payload.connectionId, 'printf "%s" "$HOME"');
        const home = homeResult.stdout.trim();
        if (homeResult.exitCode !== 0 || !home.startsWith('/') || /[\r\n\0]/.test(home)) throw new Error('The remote home directory could not be verified.');
        const directory = `${home}/.pocketshell/attachments`;
        const quoted = `'${directory.replaceAll("'", "'\\''")}'`;
        const mkdir = await hub.exec(payload.connectionId, `mkdir -p -- ${quoted}`);
        if (mkdir.exitCode !== 0) throw new Error('The attachment directory could not be created.');
        const workspace = createFileWorkspaceService(sshCapability, {
          connection: { connectionId: snapshot.connectionId, generationId: snapshot.generationId },
          rootDirectory: home,
          homeDirectory: home,
          isCurrent: () => hub.controllerOf(payload.connectionId)?.getSnapshot().generationId === snapshot.generationId,
        });
        const ids = chipIds;
        const result = await workspace.stageAttachments({
          directory,
          scopeKey: payload.scopeKey,
          timestamp: new Date().toISOString().slice(0, 19).replace(/[-:]/g, '').replace('T', '-'),
          attachments: sources,
          isSourceCancelled: (index) => isUploadCancelled(ids[index]!),
          onStart: (index) => markUploading(ids[index]!),
          onProgress: (index, written) => reportUploadProgress(ids[index]!, written),
        });
        const paths = result.decision.attachments.map((attachment) => attachment.path);
        // A file the user cancelled is not a failure: it simply is not staged.
        const failures = result.failures.filter((failure) => failure.cancelled !== true);
        const failed = new Set(failures.map((failure) => failure.sourceIndex));
        payload.sources.forEach((_, index) => { if (!failed.has(index)) settled.add(index); });
        return { ok: failures.length === 0, paths, failedCount: failures.length,
          ...(failures.length ? { error: failures.map((failure) => `${failure.sourceName}: ${failure.message}`).join('\n') } : {}) };
      } catch (error) {
        return { ok: false, paths: [], failedCount: payload.sources.length,
          error: error instanceof Error ? error.message : String(error) };
      } finally {
        for (const id of chipIds) finishUpload(id);
        await Promise.all(payload.sources.map((source, index) =>
          (source.kind === 'file' && settled.has(index) ? release(source.path) : undefined)));
      }
    },
  };
}
