<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, shallowRef } from 'vue';
import { useRouter } from 'vue-router';
import { groupSessionsIntoRoots, sessionIdentityKey } from '@pocketshell/core';
import { attachmentScopeKey } from '@pocketshell/core/shared/composerAttachments';
import { useConnectionStore } from '@ui/app/stores/connection';
import { useSessionsStore } from '@ui/app/stores/sessions';
import { useComposerStore } from '@ui/app/stores/composer';
import { useProjectsStore } from '@ui/app/stores/projects';
import { useWorkspaceRootsStore } from '@ui/app/stores/workspaceRoots';
import { platformInput, type SharedContent } from '@/session/platformInput';
import { androidAttachmentProgress } from '@/platform/android/attachments';

const router = useRouter();
const projects = useProjectsStore();
const workspaceRoots = useWorkspaceRootsStore();
const busy = shallowRef(false);
const connection = useConnectionStore();
const sessions = useSessionsStore();
const composer = useComposerStore();
const pending = shallowRef<SharedContent | null>(null);
const failure = shallowRef('');
let removeShare: (() => Promise<void>) | null = null;
const targets = computed(() => connection.connectionId ? sessions.sessions : []);
let disposed = false;
onMounted(async () => {
  const remove = await platformInput.listenForShares((content) => { pending.value = content; failure.value = content.failures.map((item) => item.message).join('\n'); });
  if (disposed) await remove();
  else removeShare = remove;
});
onBeforeUnmount(() => { disposed = true; void removeShare?.(); });
async function chooseHost(name: string) {
  const host = connection.hosts.find((entry) => entry.name === name);
  if (host && host.name !== connection.activeHost?.name) await connection.connect(host);
}
async function chooseTarget(index: number) {
  const session = targets.value[index];
  const content = pending.value;
  const connectionId = connection.connectionId;
  const host = connection.activeHost;
  if (!session || !content || !connectionId || !host || busy.value) return;
  busy.value = true;
  const identity = sessionIdentityKey(session.name, { backend: session.backend, workspace: session.workspace ?? undefined });
  const key = composer.targetKey(connectionId, session.name, identity);
  try {
    // Resolve through the shared tree algebra: identical tags in different
    // workspaces must select their own folder, pane and composer record.
    const folder = groupSessionsIntoRoots(sessions.sessions, projects.home, workspaceRoots.rootsFor(host.name, projects.home))
      .flatMap((root) => root.directories)
      .find((directory) => directory.rows.some(({ session: candidate }) => sessionIdentityKey(candidate.name, {
        backend: candidate.backend, workspace: candidate.workspace ?? undefined,
      }) === identity));
    if (!folder) throw new Error('That session is no longer available. Choose another session.');
    await router.push({ name: 'folder', params: { name: host.name, folder: folder.key }, query: { tab: session.name } });
    await nextTick();
    if (connection.connectionId !== connectionId) throw new Error('The connected host changed. Choose the target again.');
    if (content.text) composer.seedPrompt(key, content.text);
    composer.setMode('docked');
    pending.value = null;
    await composer.stage(key, { connectionId, scopeKey: attachmentScopeKey(session.name, session.workspace), sources: content.attachments });
    const error = composer.states[key]?.error;
    if (error) { pending.value = content; failure.value = error; }
  } catch (error) {
    pending.value = content;
    failure.value = error instanceof Error ? error.message : String(error);
  } finally {
    busy.value = false;
  }
}

</script>

<template>
  <section v-if="pending" class="android-share" role="dialog" aria-modal="true" aria-label="Share to a session" data-testid="android-share-target">
    <h2>Share to a session</h2>
    <label>Host
      <select data-testid="android-share-host" :disabled="busy" :value="connection.activeHost?.name ?? ''" @change="chooseHost(($event.target as HTMLSelectElement).value)">
        <option v-for="host in connection.hosts" :key="host.name" :value="host.name">{{ host.name }}</option>
      </select>
    </label>
    <p v-if="!targets.length">Connect to a host and choose its session.</p>
    <button v-for="(session, index) in targets" :key="`${session.workspace}/${session.name}`" type="button" :disabled="busy" data-testid="android-share-session" :data-session-name="session.name" @click="chooseTarget(index)">{{ session.name }}</button>
    <p v-if="failure" role="alert">{{ failure }}</p>
    <button type="button" @click="pending = null">Cancel</button>
  </section>
  <section v-if="androidAttachmentProgress" class="android-upload" role="status" data-testid="android-upload-progress"
    :data-written="androidAttachmentProgress.batchBytesWritten" :data-total="androidAttachmentProgress.batchBytesTotal"
    :data-file-count="androidAttachmentProgress.fileCount">
    <span>Uploading {{ androidAttachmentProgress.fileName }} · {{ androidAttachmentProgress.fileIndex + 1 }}/{{ androidAttachmentProgress.fileCount }}</span>
    <progress :value="androidAttachmentProgress.batchBytesWritten" :max="androidAttachmentProgress.batchBytesTotal" />
  </section>
</template>

<style scoped>
.android-share { position: fixed; z-index: 1000; inset: max(var(--sp-3), env(safe-area-inset-top)) var(--sp-3) auto; max-height: 80dvh; overflow: auto; padding: var(--sp-4); background: var(--surface); color: var(--fg); border: 1px solid var(--border); box-shadow: var(--shadow-overlay); }
.android-share button, .android-share select { display: block; min-height: 48px; width: 100%; margin-block: var(--sp-2); }
.android-upload { position: fixed; z-index: 999; bottom: max(var(--sp-3), env(safe-area-inset-bottom)); inset-inline: var(--sp-3); padding: var(--sp-3); background: var(--surface); color: var(--fg); }
.android-upload progress { display: block; width: 100%; }
</style>
