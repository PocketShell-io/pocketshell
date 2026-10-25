import { nextTick, shallowRef } from 'vue';
import type { PocketShellApi } from '@ui/app/api';
import { createAttachmentUploadProgressTracker, type AttachmentUploadProgressSnapshot } from '@pocketshell/core';
import { platformInput } from '@/session/platformInput';
import { createFileWorkspaceService } from '@/session/files';
import { sshCapability } from '@/native/sshCapability';
import type { AndroidConnectionHub } from './connectionHub';

/** Only acknowledged native SFTP bytes advance the mobile progress surface. */
export const androidAttachmentProgress = shallowRef<AttachmentUploadProgressSnapshot | null>(null);

export function createAndroidAttachments(hub: AndroidConnectionHub): PocketShellApi['attachments'] {
  const picked = new Map<string, Uint8Array>();
  let sequence = 0;
  return {
    async pickFiles() {
      picked.clear();
      const batch = await platformInput.pickAttachments();
      if (batch.failures.length) throw new Error(batch.failures.map((failure) => failure.message).join('\n'));
      return batch.sources.map((source) => {
        if (source.kind !== 'bytes') throw new Error('The Android picker did not return file bytes.');
        const path = `android-document:${++sequence}/${encodeURIComponent(source.name ?? 'attachment')}`;
        picked.set(path, source.data);
        return path;
      });
    },
    async readLocal(path) {
      const bytes = picked.get(path);
      if (!bytes) throw new Error('Choose this Android document again.');
      return bytes.slice();
    },
    async stage(payload) {
      const sources = [];
      try {
        for (const source of payload.sources) {
          const bytes = source.kind === 'bytes' ? source.data : picked.get(source.path);
          if (!bytes) throw new Error('Choose this Android document again.');
          const name = source.name ?? (source.kind === 'file' ? decodeURIComponent(source.path.split('/').at(-1)!) : 'attachment');
          sources.push({ name, bytes });
        }
        const snapshot = hub.controllerOf(payload.connectionId)?.getSnapshot();
        if (!snapshot?.connectionId || !snapshot.generationId) throw new Error('Connect before uploading attachments.');
        const homeResult = await hub.exec(payload.connectionId, 'printf "%s" "$HOME"');
        const home = homeResult.stdout.trim();
        if (homeResult.exitCode !== 0 || !home.startsWith('/') || /[\r\n\0]/.test(home)) throw new Error('The remote home directory could not be verified.');
        const directory = `${home}/.pocketshell/attachments`;
        const quoted = `'${directory.replaceAll("'", "'\\''")}'`;
        const mkdir = await hub.exec(payload.connectionId, `mkdir -p -- ${quoted}`);
        if (mkdir.exitCode !== 0) throw new Error('The attachment directory could not be created.');
        const tracker = createAttachmentUploadProgressTracker(sources.map((source) => ({ name: source.name, sizeBytes: source.bytes.length })));
        let index = 0;
        // Capacitor methods are synthesized by a Proxy, not enumerable own
        // properties. Preserve that proxy instead of spreading it into an
        // object that silently loses sftpList/sftpMkdir and other effects.
        const capability = new Proxy(sshCapability, {
          get(target, property) {
            if (property === 'sftpWrite') return async (options: Parameters<typeof sshCapability.sftpWrite>[0]) => {
              const result = await target.sftpWrite(options);
              androidAttachmentProgress.value = tracker.complete(index++, result.bytesWritten);
              await nextTick();
              return result;
            };
            return Reflect.get(target, property, target);
          },
        });
        const workspace = createFileWorkspaceService(capability, {
          connection: { connectionId: snapshot.connectionId, generationId: snapshot.generationId },
          rootDirectory: home,
          homeDirectory: home,
          isCurrent: () => hub.controllerOf(payload.connectionId)?.getSnapshot().generationId === snapshot.generationId,
        });
        const result = await workspace.stageAttachments({
          directory,
          scopeKey: payload.scopeKey,
          timestamp: new Date().toISOString().slice(0, 19).replace(/[-:]/g, '').replace('T', '-'),
          attachments: sources,
        });
        const paths = result.decision.attachments.map((attachment) => attachment.path);
        return { ok: result.failures.length === 0, paths, failedCount: result.failures.length,
          ...(result.failures.length ? { error: result.failures.map((failure) => failure.message).join('\n') } : {}) };
      } catch (error) {
        return { ok: false, paths: [], failedCount: payload.sources.length,
          error: error instanceof Error ? error.message : String(error) };
      } finally {
        androidAttachmentProgress.value = null;
        for (const source of payload.sources) if (source.kind === 'file') picked.delete(source.path);
      }
    },
  };
}
