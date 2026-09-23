<script setup lang="ts">
import { computed, onBeforeUnmount, ref, shallowRef, watch } from 'vue';
import {
  formatBytes,
  isEditableFileKind,
  isRemotePathWithin,
  joinRemoteChildPath,
  normalizeRemotePath,
  parentRemotePath,
  remotePathName,
  renderSanitizedFilename,
  sanitizeFilename,
  type FileClassification,
  type SshConnectionRef,
} from '@pocketshell/core';
import { AppIcon } from '@pocketshell/ui';
import {
  createFileWorkspaceService,
  FileWorkspaceError,
  MAX_SFTP_FILE_BYTES,
  type FileEditSnapshot,
  type FileReadResult,
  type FileWorkspaceEntry,
  type FileWorkspaceListing,
} from '../session/files';
import type { SshCapabilityPlugin } from '../native/sshCapability';
import { documentContent } from '../native/documentContent';
import { createDocumentTransferService } from '../session/documentTransfers';

const props = defineProps<{
  connection: SshConnectionRef | null;
  initialRootDirectory: string;
  capability: SshCapabilityPlugin;
}>();

type Workspace = ReturnType<typeof createFileWorkspaceService>;
interface Breadcrumb { label: string; path: string; current: boolean }

const workspace = shallowRef<Workspace | null>(null);
const documentTransfers = createDocumentTransferService(documentContent);
const listing = shallowRef<FileWorkspaceListing | null>(null);
const activeRoot = ref('');
const rootDraft = ref(props.initialRootDirectory);
const pathDraft = ref('');
const openedEntry = shallowRef<FileWorkspaceEntry | null>(null);
const loadedFile = shallowRef<FileReadResult | null>(null);
const editSnapshot = shallowRef<FileEditSnapshot | null>(null);
const editorText = ref('');
const editorStale = ref(false);
const fileClassification = shallowRef<FileClassification | null>(null);
const previewUrl = ref<string | null>(null);
const loading = ref(false);
const saving = ref(false);
const uploading = ref(false);
const errorMessage = ref('');
const statusMessage = ref('');
const conflictMessage = ref('');
const viewer = ref<HTMLElement | null>(null);
let workspaceGeneration = 0;
let boundConnectionKey = '';

const connected = computed(() => props.connection !== null);
const currentPath = computed(() => listing.value?.path ?? activeRoot.value);
const dirty = computed(() => editSnapshot.value !== null && editorText.value !== editSnapshot.value.text);
const canSave = computed(() => dirty.value && !saving.value && !editorStale.value && connected.value);
const isTooLarge = computed(() => openedEntry.value !== null && openedEntry.value.sizeBytes > MAX_SFTP_FILE_BYTES);
const breadcrumbs = computed<Breadcrumb[]>(() => {
  const root = normalizeRemotePath(activeRoot.value);
  const current = normalizeRemotePath(currentPath.value);
  if (root == null || current == null || !isRemotePathWithin(root, current)) return [];
  const result: Breadcrumb[] = [{ label: remotePathName(root) ?? root, path: root, current: current === root }];
  if (current === root) return result;
  const rest = current.slice(root === '/' ? 1 : root.length + 1);
  let path = root;
  for (const segment of rest.split('/').filter(Boolean)) {
    const child = joinRemoteChildPath(path, segment);
    if (!child.ok) return result;
    path = child.path;
    result.push({ label: segment, path, current: path === current });
  }
  return result;
});

function connectionKey(connection: SshConnectionRef | null): string {
  return connection ? `${connection.connectionId}\u0000${connection.generationId}` : '';
}

function makeWorkspace(root: string, connection: SshConnectionRef): Workspace {
  const capturedKey = connectionKey(connection);
  return createFileWorkspaceService(props.capability, {
    connection,
    rootDirectory: root,
    initialDirectory: root,
    homeDirectory: props.initialRootDirectory,
    isCurrent: () => connectionKey(props.connection) === capturedKey,
  });
}

async function bindWorkspace(root: string, startPath = root): Promise<void> {
  const connection = props.connection;
  if (connection === null) {
    errorMessage.value = 'Connect to an SSH host to browse files.';
    return;
  }
  const generation = ++workspaceGeneration;
  loading.value = true;
  errorMessage.value = '';
  statusMessage.value = '';
  try {
    const next = makeWorkspace(root, connection);
    let nextListing: FileWorkspaceListing;
    try {
      nextListing = await next.navigate(startPath);
    } catch (firstError) {
      if (startPath === root) throw firstError;
      nextListing = await next.navigate(root);
    }
    if (generation !== workspaceGeneration) return;
    workspace.value = next;
    activeRoot.value = next.rootDirectory;
    rootDraft.value = next.rootDirectory;
    listing.value = nextListing;
    pathDraft.value = nextListing.path;
    boundConnectionKey = connectionKey(connection);
  } catch (error) {
    if (generation !== workspaceGeneration) return;
    errorMessage.value = messageOf(error);
  } finally {
    if (generation === workspaceGeneration) loading.value = false;
  }
}

watch(
  () => connectionKey(props.connection),
  (key) => {
    if (key === '') {
      // A temporary transport loss invalidates pending SFTP work, but the
      // listing and any editor draft stay visible until the user reconnects.
      workspaceGeneration += 1;
      loading.value = false;
      errorMessage.value = 'SSH connection is unavailable. Your open file and edits are still here.';
      return;
    }
    if (key === boundConnectionKey) return;
    const oldPath = listing.value?.path || activeRoot.value || rootDraft.value;
    if (editSnapshot.value !== null) editorStale.value = true;
    void bindWorkspace(activeRoot.value || rootDraft.value || props.initialRootDirectory, oldPath);
  },
  { immediate: true },
);

onBeforeUnmount(() => {
  workspaceGeneration += 1;
  releasePreview();
});

function messageOf(error: unknown): string {
  if (error instanceof FileWorkspaceError) return error.message;
  if (error instanceof Error && error.message !== '') return error.message;
  return 'The file operation failed. Check the connection and try again.';
}

function releasePreview(): void {
  if (previewUrl.value !== null) URL.revokeObjectURL(previewUrl.value);
  previewUrl.value = null;
}

function clearOpenedFile(): void {
  releasePreview();
  openedEntry.value = null;
  loadedFile.value = null;
  editSnapshot.value = null;
  editorText.value = '';
  editorStale.value = false;
  fileClassification.value = null;
  conflictMessage.value = '';
}

function blockIfDirty(): boolean {
  if (!dirty.value) return false;
  statusMessage.value = 'Save or discard your edits before changing folders or opening another file.';
  return true;
}

async function refreshListing(): Promise<void> {
  const active = workspace.value;
  if (!active || !connected.value || loading.value) return;
  loading.value = true;
  errorMessage.value = '';
  statusMessage.value = '';
  try {
    listing.value = await active.navigate(active.currentDirectory);
    pathDraft.value = listing.value.path;
  } catch (error) {
    errorMessage.value = messageOf(error);
  } finally {
    loading.value = false;
  }
}

async function navigateTo(path: string): Promise<void> {
  if (blockIfDirty()) return;
  const active = workspace.value;
  if (!active || !connected.value) {
    errorMessage.value = 'Connect to an SSH host before changing folders.';
    return;
  }
  loading.value = true;
  errorMessage.value = '';
  statusMessage.value = '';
  try {
    clearOpenedFile();
    listing.value = await active.navigate(path);
    pathDraft.value = listing.value.path;
  } catch (error) {
    errorMessage.value = messageOf(error);
  } finally {
    loading.value = false;
  }
}

async function applyRoot(): Promise<void> {
  if (blockIfDirty()) return;
  const candidate = rootDraft.value.trim();
  if (!candidate) {
    errorMessage.value = 'Enter an absolute remote folder for the file workspace root.';
    return;
  }
  const normalized = normalizeRemotePath(candidate);
  if (normalized == null || !normalized.startsWith('/')) {
    errorMessage.value = 'The file workspace root must be an absolute remote path.';
    return;
  }
  clearOpenedFile();
  await bindWorkspace(normalized);
}

async function goUp(): Promise<void> {
  if (!activeRoot.value || currentPath.value === activeRoot.value) return;
  const parent = parentRemotePath(currentPath.value);
  if (parent !== null && isRemotePathWithin(activeRoot.value, parent)) await navigateTo(parent);
}

async function openEntry(entry: FileWorkspaceEntry): Promise<void> {
  if (blockIfDirty()) return;
  if (entry.type === 'directory') {
    await navigateTo(entry.path);
    return;
  }
  clearOpenedFile();
  openedEntry.value = entry;
  errorMessage.value = '';
  statusMessage.value = '';
  if (entry.type === 'symlink') {
    errorMessage.value = 'Symbolic links cannot be opened safely.';
    return;
  }
  if (entry.type !== 'file') {
    errorMessage.value = 'This remote entry is not a regular file and cannot be opened.';
    return;
  }
  if (entry.sizeBytes > MAX_SFTP_FILE_BYTES) {
    statusMessage.value = `This file is ${formatBytes(entry.sizeBytes)}. The secure SFTP bridge reads at most ${formatBytes(MAX_SFTP_FILE_BYTES)} at a time.`;
    return;
  }
  const active = workspace.value;
  if (!active || !connected.value) {
    errorMessage.value = 'Connect to an SSH host before opening files.';
    return;
  }
  loading.value = true;
  errorMessage.value = '';
  statusMessage.value = '';
  try {
    const loaded = await active.readFile(entry.path);
    fileClassification.value = loaded.classification;
    if (isEditableFileKind(loaded.classification.kind)) {
      const snapshot = await active.loadTextForEdit(entry.path);
      editSnapshot.value = snapshot;
      editorText.value = snapshot.text;
      loadedFile.value = loaded;
      await scrollViewerIntoView();
      return;
    }
    loadedFile.value = loaded;
    if ((loaded.classification.kind === 'image' || loaded.classification.kind === 'audio')
      && loaded.classification.mime !== null) {
      previewUrl.value = URL.createObjectURL(new Blob([loaded.bytes], { type: loaded.classification.mime }));
    }
    await scrollViewerIntoView();
  } catch (error) {
    errorMessage.value = messageOf(error);
  } finally {
    loading.value = false;
  }
}

async function scrollViewerIntoView(): Promise<void> {
  await new Promise<void>((resolve) => requestAnimationFrame(() => resolve()));
  viewer.value?.scrollIntoView({ behavior: 'smooth', block: 'start' });
}

function discardEdits(): void {
  clearOpenedFile();
  errorMessage.value = '';
  statusMessage.value = 'Edits discarded.';
}

async function reloadOpenedFile(): Promise<void> {
  const entry = openedEntry.value;
  if (!entry) return;
  editorStale.value = false;
  clearOpenedFile();
  await openEntry(entry);
}

async function saveEdits(): Promise<void> {
  const active = workspace.value;
  const snapshot = editSnapshot.value;
  if (!active || !snapshot || !canSave.value) return;
  saving.value = true;
  errorMessage.value = '';
  statusMessage.value = '';
  conflictMessage.value = '';
  try {
    const result = await active.saveText(snapshot, editorText.value);
    if (result.status === 'conflict') {
      conflictMessage.value = 'The remote file changed or was removed. Your edits are still here; reload only if you want to discard them.';
      return;
    }
    const updatedSnapshot = await active.loadTextForEdit(result.path);
    editSnapshot.value = updatedSnapshot;
    editorText.value = updatedSnapshot.text;
    editorStale.value = false;
    const updatedListing = await active.navigate(active.currentDirectory);
    listing.value = updatedListing;
    pathDraft.value = updatedListing.path;
    openedEntry.value = updatedListing.entries.find((entry) => entry.path === result.path) ?? openedEntry.value;
    statusMessage.value = `Saved ${formatBytes(result.bytesWritten)}.`;
  } catch (error) {
    errorMessage.value = messageOf(error);
  } finally {
    saving.value = false;
  }
}

async function downloadFile(entry = openedEntry.value): Promise<void> {
  if (!entry || entry.type !== 'file') return;
  if (entry.sizeBytes > MAX_SFTP_FILE_BYTES) {
    errorMessage.value = `This file exceeds the ${formatBytes(MAX_SFTP_FILE_BYTES)} transfer limit.`;
    return;
  }
  const active = workspace.value;
  if (!active || !connected.value) {
    errorMessage.value = 'Connect to an SSH host before downloading files.';
    return;
  }
  loading.value = true;
  errorMessage.value = '';
  try {
    const loaded = loadedFile.value?.path === entry.path ? loadedFile.value : await active.readFile(entry.path);
    const result = await documentTransfers.saveAs(
      renderSanitizedFilename(sanitizeFilename(entry.name)),
      loaded.classification.mime,
      loaded.bytes,
    );
    statusMessage.value = result.cancelled
      ? 'Download cancelled.'
      : `Saved ${formatBytes(result.bytesWritten)} to ${result.name}.`;
  } catch (error) {
    errorMessage.value = messageOf(error);
  } finally {
    loading.value = false;
  }
}

async function uploadFile(): Promise<void> {
  const active = workspace.value;
  if (!active || !connected.value || !listing.value) {
    errorMessage.value = 'Connect to an SSH host before uploading files.';
    return;
  }
  uploading.value = true;
  errorMessage.value = '';
  statusMessage.value = '';
  try {
    const picked = await documentTransfers.pickUploadFile();
    if (picked.cancelled) {
      statusMessage.value = 'Upload cancelled.';
      return;
    }
    const name = renderSanitizedFilename(sanitizeFilename(picked.document.name));
    const target = joinRemoteChildPath(listing.value.path, name);
    if (!target.ok || !isRemotePathWithin(active.rootDirectory, target.path)) {
      errorMessage.value = 'This filename cannot be used safely in the current folder.';
      return;
    }
    const result = await active.writeFile(target.path, picked.bytes);
    listing.value = await active.navigate(active.currentDirectory);
    statusMessage.value = `Uploaded ${name} (${formatBytes(result.bytesWritten)}).`;
  } catch (error) {
    errorMessage.value = messageOf(error);
  } finally {
    uploading.value = false;
  }
}

function openUploadPicker(): void {
  if (!connected.value || uploading.value || loading.value) return;
  void uploadFile();
}

function entryKind(entry: FileWorkspaceEntry): string {
  if (entry.type === 'directory') return 'Folder';
  if (entry.type === 'symlink') return 'Link';
  if (entry.type !== 'file') return 'Unsupported';
  return entry.classification?.kind === 'unknown' ? 'File' : (entry.classification?.kind ?? 'File');
}

function sizeLabel(entry: FileWorkspaceEntry): string {
  return entry.type === 'directory' ? 'Folder' : Number.isFinite(entry.sizeBytes) ? formatBytes(entry.sizeBytes) : 'Size unknown';
}
</script>

<template>
  <main class="screen-content files-screen" data-testid="files-screen" aria-labelledby="files-title">
    <header class="files-header">
      <div>
        <p class="eyebrow">REMOTE FILES</p>
        <h1 id="files-title">Files</h1>
        <p class="files-subtitle">Browse and edit files over the current SSH connection.</p>
      </div>
      <button class="files-icon-button" type="button" data-testid="file-refresh" aria-label="Refresh files" :disabled="loading || !connected" @click="refreshListing">
        <AppIcon name="refresh" />
      </button>
    </header>

    <details class="files-root-settings">
      <summary>Workspace root <code>{{ activeRoot || rootDraft }}</code></summary>
      <form class="files-root-form" @submit.prevent="applyRoot">
        <label class="files-field">
          <span>Remote folder boundary</span>
          <input v-model="rootDraft" data-testid="file-root" type="text" inputmode="url" autocomplete="off" spellcheck="false" placeholder="/home/user" />
        </label>
        <button class="files-secondary-button" type="submit" :disabled="loading || dirty || !connected">Use folder</button>
      </form>
      <p class="files-help">All navigation and transfers stay inside this remote folder. Links and special files are not opened.</p>
    </details>

    <section class="files-browser" aria-label="Remote file browser">
      <nav class="files-path-bar" aria-label="File path">
        <button class="files-icon-button" type="button" data-testid="file-up" aria-label="Go up one folder" :disabled="!connected || loading || !activeRoot || currentPath === activeRoot" @click="goUp">
          <AppIcon name="arrow-up" />
        </button>
        <div class="files-breadcrumbs" data-testid="file-breadcrumbs">
          <template v-for="(crumb, index) in breadcrumbs" :key="crumb.path">
            <span v-if="index > 0" class="files-separator" aria-hidden="true">/</span>
            <button
              v-if="!crumb.current"
              class="files-crumb"
              type="button"
              :data-file-crumb="crumb.path"
              @click="navigateTo(crumb.path)"
            >{{ crumb.label }}</button>
            <span v-else class="files-crumb files-crumb--current" aria-current="page">{{ crumb.label }}</span>
          </template>
        </div>
      </nav>

      <form class="files-goto" @submit.prevent="navigateTo(pathDraft)">
        <label class="sr-only" for="file-path-input">Go to remote path</label>
        <input id="file-path-input" v-model="pathDraft" data-testid="file-path" type="text" inputmode="url" autocomplete="off" spellcheck="false" placeholder="/path inside workspace" />
        <button class="files-secondary-button" type="submit" :disabled="loading || !connected">Go</button>
      </form>

      <div class="files-toolbar">
        <span class="files-count">{{ listing ? `${listing.entries.length} items` : 'No folder open' }}</span>
        <span class="files-toolbar-spacer" />
        <button class="files-primary-button" type="button" data-testid="file-upload" :disabled="!connected || uploading || loading || !listing" @click="openUploadPicker">
          <AppIcon name="plus" />
          {{ uploading ? 'Uploading…' : 'Upload' }}
        </button>
      </div>

      <p v-if="!connected" class="files-banner files-banner--warning" role="status">SSH is disconnected. The current view and any edit draft are kept until you reconnect.</p>
      <p v-if="loading" class="files-banner" role="status" data-testid="file-loading">{{ saving ? 'Saving…' : uploading ? 'Uploading…' : 'Loading…' }}</p>
      <p v-if="errorMessage" class="files-banner files-banner--error" role="alert" data-testid="file-error">{{ errorMessage }}</p>
      <p v-if="statusMessage" class="files-banner" role="status" data-testid="file-status">{{ statusMessage }}</p>

      <ul v-if="listing" class="files-list" data-testid="file-list" aria-label="Files in current folder">
        <li v-for="entry in listing.entries" :key="entry.path" class="files-row" :data-file-name="entry.name" :data-file-type="entry.type">
          <button class="files-row-open" type="button" :data-testid="`open-file-${entry.name}`" @click="openEntry(entry)">
            <AppIcon :name="entry.type === 'directory' ? 'folder' : entry.type === 'symlink' ? 'symlink' : 'file'" class="files-row-icon" />
            <span class="files-row-copy">
              <span class="files-row-name">{{ entry.name }}</span>
              <span class="files-row-meta">{{ entryKind(entry) }} · {{ sizeLabel(entry) }}</span>
            </span>
            <AppIcon v-if="entry.type === 'directory'" name="chevron-right" class="files-row-chevron" />
          </button>
          <button
            v-if="entry.type === 'file'"
            class="files-row-download"
            type="button"
            :aria-label="`Download ${entry.name}`"
            :disabled="!connected || loading || entry.sizeBytes > MAX_SFTP_FILE_BYTES"
            @click="downloadFile(entry)"
          ><AppIcon name="download" /></button>
        </li>
        <li v-if="listing.entries.length === 0 && !loading" class="files-empty">This folder is empty.</li>
      </ul>

      <section v-if="openedEntry" ref="viewer" class="files-viewer" data-testid="file-viewer" :aria-label="`Viewer for ${openedEntry.name}`">
        <header class="files-viewer-header">
          <div class="files-viewer-title">
            <AppIcon name="file" />
            <div>
              <h2 data-testid="file-open-name">{{ openedEntry.name }}</h2>
              <p>{{ openedEntry.path }} · {{ sizeLabel(openedEntry) }}</p>
            </div>
          </div>
          <div class="files-viewer-actions">
            <button
              v-if="openedEntry.type === 'file'"
              class="files-icon-button"
              type="button"
              data-testid="file-download"
              aria-label="Download opened file"
              :disabled="!connected || loading || isTooLarge"
              @click="downloadFile()"
            ><AppIcon name="download" /></button>
            <button class="files-icon-button" type="button" data-testid="file-close" aria-label="Close file" @click="dirty ? discardEdits() : clearOpenedFile()">
              <AppIcon name="close" />
            </button>
          </div>
        </header>

        <div v-if="editSnapshot" class="files-editor">
          <p v-if="editorStale" class="files-banner files-banner--warning" role="alert" data-testid="file-stale-editor">
            The SSH connection changed while this file was open. Reload from the host before editing again; the old draft is kept below.
          </p>
          <p v-if="conflictMessage" class="files-banner files-banner--warning" role="alert" data-testid="file-conflict">{{ conflictMessage }}</p>
          <div class="files-editor-toolbar">
            <span class="files-editor-kind"><AppIcon name="edit-2" /> Text file</span>
            <span v-if="dirty" class="files-dirty"><span /> Unsaved edits</span>
            <span class="files-toolbar-spacer" />
            <button v-if="editorStale || conflictMessage" class="files-secondary-button" type="button" data-testid="file-reload" :disabled="loading || !connected" @click="reloadOpenedFile">Reload</button>
            <button v-if="dirty" class="files-secondary-button" type="button" data-testid="file-discard" @click="discardEdits">Discard</button>
            <button class="files-primary-button" type="button" data-testid="file-save" :disabled="!canSave" @click="saveEdits">{{ saving ? 'Saving…' : 'Save' }}</button>
          </div>
          <textarea v-model="editorText" data-testid="file-editor" :readonly="editorStale || !connected" autocapitalize="off" autocomplete="off" autocorrect="off" spellcheck="false" aria-label="Text file contents" />
          <p class="files-help">Text editing is limited to {{ formatBytes(MAX_SFTP_FILE_BYTES) }} per file. Save checks for changes on the host first.</p>
        </div>

        <div v-else-if="loading && !loadedFile" class="files-viewer-state" role="status">Opening file…</div>
        <div v-else-if="isTooLarge" class="files-viewer-state files-viewer-state--warning" data-testid="file-too-large">
          <AppIcon name="alert-triangle" />
          <h3>File is too large to open here</h3>
          <p>{{ formatBytes(openedEntry.sizeBytes) }} exceeds the {{ formatBytes(MAX_SFTP_FILE_BYTES) }} transfer limit. No file contents were read.</p>
        </div>
        <div v-else-if="loadedFile && fileClassification?.kind === 'image' && previewUrl" class="files-media-viewer" data-testid="file-image-viewer">
          <img :src="previewUrl" :alt="openedEntry.name" />
        </div>
        <div v-else-if="loadedFile && fileClassification?.kind === 'audio' && previewUrl" class="files-media-viewer" data-testid="file-audio-viewer">
          <p>{{ fileClassification.mime }} · {{ formatBytes(loadedFile.bytes.byteLength) }}</p>
          <audio controls :src="previewUrl">Audio preview is not available on this device.</audio>
        </div>
        <div v-else-if="loadedFile" class="files-viewer-state" data-testid="file-binary-viewer">
          <AppIcon :name="fileClassification?.kind === 'pdf' ? 'file' : 'alert-triangle'" />
          <h3>{{ fileClassification?.kind === 'pdf' ? 'PDF document' : 'Binary file' }}</h3>
          <p>{{ fileClassification?.mime ?? 'Unknown file type' }} · {{ formatBytes(loadedFile.bytes.byteLength) }}</p>
          <p>Download a copy to open this file in another app.</p>
        </div>
        <div v-else-if="errorMessage" class="files-viewer-state files-viewer-state--warning" data-testid="file-open-error">
          <AppIcon name="alert-triangle" />
          <h3>Could not open this file</h3>
          <p>{{ errorMessage }}</p>
        </div>
      </section>
    </section>
  </main>
</template>

<style scoped>
.files-screen { display: flex; flex-direction: column; gap: var(--sp-3); }
.files-header { display: flex; align-items: center; justify-content: space-between; gap: var(--sp-3); }
.files-header h1 { margin: 0; color: var(--fg); font-size: var(--fs-500); font-weight: 600; line-height: 1.25; }
.files-subtitle { margin: 4px 0 0; color: var(--fg-secondary); font-size: var(--fs-200); line-height: 1.4; }
.files-root-settings { flex: 0 0 auto; border: 1px solid var(--border); border-radius: var(--r-lg); background: var(--surface); padding: 0 var(--sp-3); }
.files-root-settings summary { display: flex; min-height: 48px; align-items: center; gap: var(--sp-2); color: var(--fg-secondary); font-size: var(--fs-200); cursor: pointer; list-style-position: inside; }
.files-root-settings summary code { overflow: hidden; min-width: 0; color: var(--fg-muted); font: var(--fs-100) var(--font-mono); text-overflow: ellipsis; white-space: nowrap; }
.files-root-form { display: flex; align-items: end; gap: var(--sp-2); padding: 0 0 var(--sp-2); }
.files-field { display: grid; min-width: 0; flex: 1; gap: var(--sp-1); color: var(--fg-secondary); font-size: var(--fs-100); }
.files-field input, .files-goto input { width: 100%; min-width: 0; height: 48px; border: 1px solid var(--border-strong); border-radius: var(--r-md); background: var(--bg); padding: 0 var(--sp-2); color: var(--fg); font: var(--fs-200) var(--font-mono); outline: none; }
.files-field input:focus, .files-goto input:focus, .files-editor textarea:focus { border-color: var(--accent); box-shadow: 0 0 0 2px var(--accent-soft); }
.files-help { margin: 0 0 var(--sp-2); color: var(--fg-muted); font-size: var(--fs-100); line-height: 1.45; }
.files-browser { display: flex; min-width: 0; flex: 1 1 auto; flex-direction: column; border: 1px solid var(--border); border-radius: var(--r-lg); background: var(--surface); overflow: hidden; }
.files-path-bar { display: flex; min-width: 0; min-height: 56px; align-items: center; gap: var(--sp-2); border-bottom: 1px solid var(--border-soft); padding: var(--sp-1) var(--sp-2); }
.files-breadcrumbs { display: flex; min-width: 0; align-items: center; gap: var(--sp-1); overflow: auto hidden; scrollbar-width: none; white-space: nowrap; }
.files-breadcrumbs::-webkit-scrollbar { display: none; }
.files-crumb { flex: 0 0 auto; min-width: 48px; max-width: 38vw; min-height: 48px; overflow: hidden; border: 0; border-radius: var(--r-sm); background: transparent; padding: 0 var(--sp-1); color: var(--fg-secondary); font: var(--fs-200) var(--font-mono); text-overflow: ellipsis; white-space: nowrap; }
.files-crumb:not(.files-crumb--current) { cursor: pointer; }
.files-crumb:not(.files-crumb--current):active { background: var(--accent-soft); color: var(--accent); }
.files-crumb--current { display: inline-flex; align-items: center; color: var(--fg); font-weight: 600; }
.files-separator { flex: 0 0 auto; color: var(--fg-muted); font: var(--fs-100) var(--font-mono); }
.files-goto { display: flex; gap: var(--sp-2); padding: var(--sp-2); }
.files-toolbar { display: flex; min-height: 56px; align-items: center; gap: var(--sp-2); border-top: 1px solid var(--border-soft); padding: var(--sp-1) var(--sp-2); }
.files-count { color: var(--fg-muted); font-size: var(--fs-100); }
.files-toolbar-spacer { min-width: 0; flex: 1; }
.files-icon-button { display: inline-flex; width: 48px; height: 48px; flex: 0 0 auto; align-items: center; justify-content: center; border: 1px solid var(--border); border-radius: var(--r-md); background: var(--surface-2); color: var(--fg-secondary); }
.files-icon-button svg { width: 20px; height: 20px; }
.files-icon-button:active:not(:disabled), .files-row-download:active:not(:disabled) { border-color: var(--accent); color: var(--accent); }
.files-icon-button:disabled, .files-primary-button:disabled, .files-secondary-button:disabled, .files-row-download:disabled { cursor: not-allowed; opacity: .48; }
.files-primary-button, .files-secondary-button { display: inline-flex; min-width: 48px; min-height: 48px; flex: 0 0 auto; align-items: center; justify-content: center; gap: var(--sp-1); border: 1px solid var(--accent); border-radius: var(--r-md); padding: 0 var(--sp-3); color: var(--fg); font-size: var(--fs-200); font-weight: 600; }
.files-primary-button { background: var(--accent); color: var(--bg); }
.files-primary-button svg { width: 18px; height: 18px; }
.files-secondary-button { background: var(--surface-2); }
.files-banner { flex: 0 0 auto; margin: 0 var(--sp-2) var(--sp-2); border: 1px solid var(--border-soft); border-radius: var(--r-md); background: var(--surface-2); padding: var(--sp-2); color: var(--fg-secondary); font-size: var(--fs-200); line-height: 1.45; overflow-wrap: anywhere; }
.files-banner--error { border-color: var(--error); background: var(--error-soft); color: var(--error); }
.files-banner--warning { border-color: var(--warning); background: var(--warning-soft); color: var(--warning); }
.files-list { min-height: 0; margin: 0; padding: var(--sp-1) 0; overflow: auto; list-style: none; }
.files-row { display: flex; min-width: 0; min-height: 56px; align-items: center; gap: var(--sp-1); border-bottom: 1px solid var(--border-soft); padding: 0 var(--sp-2); }
.files-row:last-of-type { border-bottom: 0; }
.files-row-open { display: flex; min-width: 0; min-height: 56px; flex: 1; align-items: center; gap: var(--sp-2); border: 0; border-radius: var(--r-sm); background: transparent; padding: 0; color: var(--fg); text-align: left; }
.files-row-open:active { background: var(--accent-soft); }
.files-row-icon { width: 20px; height: 20px; flex: 0 0 auto; color: var(--fg-muted); }
.files-row[data-file-type="directory"] .files-row-icon { color: var(--warning); }
.files-row[data-file-type="symlink"] .files-row-icon { color: var(--fg-secondary); }
.files-row-copy { display: grid; min-width: 0; flex: 1; gap: 2px; }
.files-row-name { overflow: hidden; color: var(--fg); font: var(--fs-200) var(--font-mono); text-overflow: ellipsis; white-space: nowrap; }
.files-row-meta { overflow: hidden; color: var(--fg-muted); font-size: var(--fs-100); text-overflow: ellipsis; white-space: nowrap; }
.files-row-chevron { width: 18px; height: 18px; flex: 0 0 auto; color: var(--fg-muted); }
.files-row-download { display: inline-flex; width: 48px; height: 48px; flex: 0 0 auto; align-items: center; justify-content: center; border: 1px solid transparent; border-radius: var(--r-md); background: transparent; color: var(--fg-secondary); }
.files-row-download svg { width: 20px; height: 20px; }
.files-empty { padding: var(--sp-4); color: var(--fg-muted); font-size: var(--fs-200); text-align: center; }
.files-viewer { display: flex; min-width: 0; min-height: 190px; flex: 1 1 auto; flex-direction: column; margin-top: var(--sp-2); border: 1px solid var(--border); border-radius: var(--r-lg); background: var(--surface); overflow: hidden; scroll-margin-top: var(--sp-2); }
.files-viewer-header { display: flex; min-width: 0; min-height: 64px; align-items: center; justify-content: space-between; gap: var(--sp-2); border-bottom: 1px solid var(--border-soft); padding: var(--sp-1) var(--sp-2); }
.files-viewer-title { display: flex; min-width: 0; align-items: center; gap: var(--sp-2); }
.files-viewer-title > svg { width: 20px; height: 20px; flex: 0 0 auto; color: var(--fg-muted); }
.files-viewer-title > div { min-width: 0; }
.files-viewer-title h2 { overflow: hidden; margin: 0; color: var(--fg); font: 600 var(--fs-200) var(--font-mono); text-overflow: ellipsis; white-space: nowrap; }
.files-viewer-title p { overflow: hidden; margin: 3px 0 0; color: var(--fg-muted); font: var(--fs-100) var(--font-mono); text-overflow: ellipsis; white-space: nowrap; }
.files-viewer-actions { display: flex; flex: 0 0 auto; gap: var(--sp-1); }
.files-editor { display: flex; min-height: 220px; flex: 1; flex-direction: column; }
.files-editor-toolbar { display: flex; min-height: 56px; align-items: center; gap: var(--sp-1); border-bottom: 1px solid var(--border-soft); padding: var(--sp-1) var(--sp-2); }
.files-editor-kind, .files-dirty { display: inline-flex; align-items: center; gap: var(--sp-1); color: var(--fg-secondary); font-size: var(--fs-100); white-space: nowrap; }
.files-editor-kind svg { width: 16px; height: 16px; }
.files-dirty { color: var(--warning); }
.files-dirty span { width: 7px; height: 7px; border-radius: 50%; background: currentColor; }
.files-editor textarea { width: 100%; min-height: 220px; flex: 1; resize: vertical; border: 0; border-bottom: 1px solid var(--border-soft); border-radius: 0; background: var(--bg); padding: var(--sp-3); color: var(--fg); font: 13px/1.55 var(--font-mono); outline: none; }
.files-editor textarea[readonly] { opacity: .8; }
.files-editor .files-help { padding: var(--sp-2) var(--sp-2) 0; }
.files-viewer-state { display: grid; min-height: 180px; flex: 1; align-content: center; justify-items: center; gap: var(--sp-2); padding: var(--sp-4); color: var(--fg-secondary); text-align: center; }
.files-viewer-state > svg { width: 26px; height: 26px; color: var(--fg-muted); }
.files-viewer-state h3 { margin: 0; color: var(--fg); font-size: var(--fs-300); font-weight: 600; }
.files-viewer-state p { max-width: 36rem; margin: 0; font-size: var(--fs-200); line-height: 1.45; overflow-wrap: anywhere; }
.files-viewer-state--warning > svg { color: var(--warning); }
.files-media-viewer { display: grid; min-height: 180px; flex: 1; place-items: center; gap: var(--sp-2); background: var(--term-bg); padding: var(--sp-3); }
.files-media-viewer img { display: block; max-width: 100%; max-height: min(56vh, 640px); object-fit: contain; }
.files-media-viewer p { margin: 0; color: var(--fg-muted); font: var(--fs-100) var(--font-mono); }
.files-media-viewer audio { width: min(100%, 480px); }

@media (min-width: 760px) {
  .files-screen { overflow: hidden; }
  .files-browser { display: grid; grid-template-columns: minmax(250px, .82fr) minmax(0, 1.18fr); grid-template-rows: auto auto minmax(0, 1fr); }
  .files-path-bar { grid-column: 1 / -1; }
  .files-goto { grid-column: 1 / -1; }
  .files-toolbar { grid-column: 1; grid-row: 3; align-self: start; border-top: 0; }
  .files-list { grid-column: 1; grid-row: 3; margin-top: 56px; border-right: 1px solid var(--border-soft); }
  .files-viewer { grid-column: 2; grid-row: 3; min-height: 0; margin: var(--sp-2); }
  .files-editor textarea { min-height: 180px; }
}
</style>
