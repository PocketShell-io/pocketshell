import { createRenderer, createSSRApp, getCurrentInstance, h, ssrContextKey } from 'vue';
import { renderToString } from 'vue/server-renderer';
import { createPinia } from 'pinia';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { useComposerDrafts } from '../../src/stores/composerDrafts';
import type { AttachmentUploadProgressSnapshot } from '@pocketshell/core';

const mocks = vi.hoisted(() => ({
  addListener: vi.fn(),
  pickAttachments: vi.fn(),
  startDictation: vi.fn(),
}));

vi.mock('@capacitor/app', () => ({ App: { addListener: mocks.addListener } }));
vi.mock('@pocketshell/ui', () => ({ ComposerControls: { render: () => null } }));
vi.mock('../../src/session/platformInput', () => ({
  platformInput: { pickAttachments: mocks.pickAttachments, startDictation: mocks.startDictation },
}));

import PromptComposer from '../../src/components/PromptComposer.vue';

let renderedSetupState: Record<string, unknown> | undefined;

interface HostNode {
  type: string;
  props: Record<string, unknown>;
  children: HostNode[];
  parent?: HostNode;
}

function node(type: string): HostNode {
  return { type, props: {}, children: [] };
}

const renderer = createRenderer<HostNode, HostNode>({
  patchProp(element, key, _previous, next) {
    if (next == null) delete element.props[key];
    else element.props[key] = next;
  },
  insert(child, parent, anchor) {
    if (child.parent) {
      const oldIndex = child.parent.children.indexOf(child);
      if (oldIndex >= 0) child.parent.children.splice(oldIndex, 1);
    }
    child.parent = parent;
    const index = anchor ? parent.children.indexOf(anchor) : -1;
    if (index < 0) parent.children.push(child);
    else parent.children.splice(index, 0, child);
  },
  remove(child) {
    if (!child.parent) return;
    const index = child.parent.children.indexOf(child);
    if (index >= 0) child.parent.children.splice(index, 1);
    child.parent = undefined;
  },
  createElement: (type) => node(type),
  createText: () => node('#text'),
  createComment: () => node('#comment'),
  setText() {},
  setElementText(element) { element.children = []; },
  parentNode: (element) => element.parent ?? null,
  nextSibling: (element) => {
    if (!element.parent) return null;
    const index = element.parent.children.indexOf(element);
    return element.parent.children[index + 1] ?? null;
  },
});

const mountedPromptComposer = {
  ...PromptComposer,
  render() {
    const internalInstance = getCurrentInstance() as unknown as { setupState?: Record<string, unknown> } | null;
    renderedSetupState = internalInstance?.setupState;
    return h('composer-state');
  },
};

async function flushPromises() {
  await Promise.resolve();
  await Promise.resolve();
}

function progressUpdate(overrides: Partial<AttachmentUploadProgressSnapshot> = {}): AttachmentUploadProgressSnapshot {
  return {
    fileName: 'notes.bin',
    fileIndex: 0,
    fileCount: 1,
    fileBytesWritten: 32,
    fileBytesTotal: 64,
    batchBytesWritten: 32,
    batchBytesTotal: 64,
    completedFileCount: 0,
    ...overrides,
  };
}

describe('prompt composer upload progress', () => {
  afterEach(() => {
    vi.clearAllMocks();
    vi.unstubAllGlobals();
    renderedSetupState = undefined;
  });

  it('shows the latest byte-derived percentage while staging and clears it on success', async () => {
    mocks.addListener.mockImplementation(async () => ({ remove: vi.fn(async () => {}) }));
    let onProgress: ((progress: AttachmentUploadProgressSnapshot) => void) | undefined;
    let finishStage!: (result: {
      staged: Array<{ id: string; path: string; name: string; sizeBytes: number }>;
      failures: [];
    }) => void;
    const stageAttachments = vi.fn((_targetKey: string, _pending: unknown, callback?: typeof onProgress) => {
      onProgress = callback;
      return new Promise<{
        staged: Array<{ id: string; path: string; name: string; sizeBytes: number }>;
        failures: [];
      }>((resolve) => { finishStage = resolve; });
    });
    const pinia = createPinia();
    const drafts = useComposerDrafts(pinia);
    const root = node('root');
    const app = renderer.createApp(mountedPromptComposer, {
      targetKey: 'host/session',
      targetLabel: 'session',
      transportState: 'connected',
      dictationLanguageTag: 'auto',
      dictationSilenceWindowMs: 4_000,
      writePty: vi.fn(async () => ({ ok: true })),
      stageAttachments,
    });
    app.use(pinia);
    app.provide(ssrContextKey, { modules: new Set<string>() });
    app.mount(root);
    await flushPromises();

    const frames = new Map<number, FrameRequestCallback>();
    let nextFrame = 0;
    vi.stubGlobal('requestAnimationFrame', (callback: FrameRequestCallback) => {
      const id = ++nextFrame;
      frames.set(id, callback);
      return id;
    });
    vi.stubGlobal('cancelAnimationFrame', (id: number) => { frames.delete(id); });

    const added = drafts.addPendingAttachments('host/session', [{
      kind: 'bytes', data: Uint8Array.from([1, 2, 3, 4]), name: 'notes.bin', mimeType: 'application/octet-stream',
    }]);
    await vi.waitFor(() => expect(stageAttachments).toHaveBeenCalledTimes(1));
    expect(renderedSetupState?.attachmentProgress).toBeNull();
    expect(onProgress).toBeTypeOf('function');

    onProgress?.(progressUpdate({ fileBytesWritten: 8, batchBytesWritten: 8 }));
    onProgress?.(progressUpdate({ fileBytesWritten: 32, batchBytesWritten: 32 }));
    expect(renderedSetupState?.attachmentProgress).toBeNull();
    const queuedFrame = [...frames.entries()][0];
    queuedFrame?.[1](0);
    frames.delete(queuedFrame?.[0] ?? -1);
    await flushPromises();

    expect(renderedSetupState?.attachmentProgress).toMatchObject({ fileBytesWritten: 32, batchBytesWritten: 32 });
    expect(renderedSetupState?.attachmentProgressValue).toBe(50);
    expect(renderedSetupState?.attachmentProgressDescription).toContain('Uploading notes.bin, file 1 of 1');
    expect(renderedSetupState?.attachmentProgressDescription).toContain('32 B of 64 B overall');

    finishStage({
      staged: [{ id: added[0]!.id, path: '/home/testuser/notes.bin', name: 'notes.bin', sizeBytes: 4 }],
      failures: [],
    });
    await vi.waitFor(() => expect(drafts.stagedAttachmentsFor('host/session')).toHaveLength(1));
    expect(renderedSetupState?.attachmentProgress).toBeNull();
    app.unmount();
  });

  it('removes progress after failure and retains the pending file bytes for retry', async () => {
    mocks.addListener.mockImplementation(async () => ({ remove: vi.fn(async () => {}) }));
    let onProgress: ((progress: AttachmentUploadProgressSnapshot) => void) | undefined;
    let finishStage!: (result: {
      staged: [];
      failures: Array<{ id: string; name: string; message: string }>;
    }) => void;
    const stageAttachments = vi.fn((_targetKey: string, _pending: unknown, callback?: typeof onProgress) => {
      onProgress = callback;
      return new Promise<{
        staged: [];
        failures: Array<{ id: string; name: string; message: string }>;
      }>((resolve) => { finishStage = resolve; });
    });
    const pinia = createPinia();
    const drafts = useComposerDrafts(pinia);
    const root = node('root');
    const app = renderer.createApp(mountedPromptComposer, {
      targetKey: 'host/session',
      targetLabel: 'session',
      transportState: 'connected',
      dictationLanguageTag: 'auto',
      dictationSilenceWindowMs: 4_000,
      writePty: vi.fn(async () => ({ ok: true })),
      stageAttachments,
    });
    app.use(pinia);
    app.provide(ssrContextKey, { modules: new Set<string>() });
    app.mount(root);
    await flushPromises();

    const added = drafts.addPendingAttachments('host/session', [{
      kind: 'bytes', data: Uint8Array.from([0, 0xff, 4]), name: 'retry.bin', mimeType: 'application/octet-stream',
    }]);
    await vi.waitFor(() => expect(stageAttachments).toHaveBeenCalledTimes(1));
    onProgress?.(progressUpdate({ fileName: 'retry.bin', fileBytesWritten: 16, fileBytesTotal: 32, batchBytesWritten: 16, batchBytesTotal: 32 }));
    expect(renderedSetupState?.attachmentProgressValue).toBe(50);

    finishStage({
      staged: [],
      failures: [{ id: added[0]!.id, name: 'retry.bin', message: 'SFTP write failed' }],
    });
    await vi.waitFor(() => expect(drafts.attachmentIssuesFor('host/session')).toHaveLength(1));
    expect(renderedSetupState?.attachmentProgress).toBeNull();
    expect(drafts.pendingAttachmentsFor('host/session')[0]?.source.data).toEqual(Uint8Array.from([0, 0xff, 4]));
    expect(drafts.attachmentIssuesFor('host/session')[0]?.message).toBe('SFTP write failed');
    app.unmount();
  });

  it('clears a failed file bar while the next upload remains pending', async () => {
    let onProgress: ((progress: AttachmentUploadProgressSnapshot) => void) | undefined;
    let onProgressCleared: (() => void) | undefined;
    let finishStage!: (result: {
      staged: Array<{ id: string; path: string; name: string; sizeBytes: number }>;
      failures: Array<{ id: string; name: string; message: string }>;
    }) => void;
    let stageSettled = false;
    const stageAttachments = vi.fn((
      _targetKey: string,
      _pending: unknown,
      onProgressCallback?: typeof onProgress,
      onProgressClearedCallback?: typeof onProgressCleared,
    ) => {
      onProgress = onProgressCallback;
      onProgressCleared = onProgressClearedCallback;
      return new Promise<{
        staged: Array<{ id: string; path: string; name: string; sizeBytes: number }>;
        failures: Array<{ id: string; name: string; message: string }>;
      }>((resolve) => {
        finishStage = (result) => {
          stageSettled = true;
          resolve(result);
        };
      });
    });
    const pinia = createPinia();
    const drafts = useComposerDrafts(pinia);
    const root = node('root');
    const app = renderer.createApp(mountedPromptComposer, {
      targetKey: 'host/session',
      targetLabel: 'session',
      transportState: 'connected',
      dictationLanguageTag: 'auto',
      dictationSilenceWindowMs: 4_000,
      writePty: vi.fn(async () => ({ ok: true })),
      stageAttachments,
    });
    app.use(pinia);
    app.provide(ssrContextKey, { modules: new Set<string>() });
    app.mount(root);
    await flushPromises();

    const added = drafts.addPendingAttachments('host/session', [
      { kind: 'bytes', data: Uint8Array.from([1, 2, 3, 4]), name: 'first.bin', mimeType: 'application/octet-stream' },
      { kind: 'bytes', data: Uint8Array.from([5, 6, 7, 8]), name: 'second.bin', mimeType: 'application/octet-stream' },
    ]);
    await vi.waitFor(() => expect(stageAttachments).toHaveBeenCalledTimes(1));
    onProgress?.(progressUpdate({
      fileName: 'first.bin', fileIndex: 0, fileCount: 2,
      fileBytesWritten: 4, fileBytesTotal: 4, batchBytesWritten: 4, batchBytesTotal: 8,
    }));
    expect(renderedSetupState?.attachmentProgressValue).toBe(50);
    expect(stageSettled).toBe(false);

    // The first file failed. The session asks the composer to clear its bar
    // while it continues writing the second file in the still-pending batch.
    onProgressCleared?.();
    expect(renderedSetupState?.attachmentProgress).toBeNull();
    expect(renderedSetupState?.attachmentProgressValue).toBe(0);
    await flushPromises();
    expect(stageSettled).toBe(false);
    expect(renderedSetupState?.attachmentProgress).toBeNull();

    finishStage({
      staged: [{ id: added[1]!.id, path: '/home/testuser/second.bin', name: 'second.bin', sizeBytes: 4 }],
      failures: [{ id: added[0]!.id, name: 'first.bin', message: 'SFTP write failed' }],
    });
    await vi.waitFor(() => expect(drafts.stagedAttachmentsFor('host/session')).toHaveLength(1));
    expect(drafts.stagedAttachmentsFor('host/session')[0]?.name).toBe('second.bin');
    expect(drafts.pendingAttachmentsFor('host/session').map((attachment) => attachment.source.name)).toEqual(['first.bin']);
    expect(renderedSetupState?.attachmentProgress).toBeNull();
    app.unmount();
  });

  it('keeps progress inside the existing single status row', async () => {
    const props = {
      targetKey: 'host/session',
      targetLabel: 'session',
      transportState: 'connected' as const,
      dictationLanguageTag: 'auto',
      dictationSilenceWindowMs: 4_000,
      writePty: vi.fn(async () => ({ ok: true })),
    };
    const idleApp = createSSRApp(PromptComposer, {
      ...props,
      stageAttachments: vi.fn(async () => ({ staged: [], failures: [] })),
    });
    idleApp.use(createPinia());
    const idleHtml = await renderToString(idleApp);

    const pinia = createPinia();
    useComposerDrafts(pinia).addPendingAttachments('host/session', [{
      kind: 'bytes', data: Uint8Array.from([1, 2]), name: 'layout.bin', mimeType: 'application/octet-stream',
    }]);
    const activeApp = createSSRApp(PromptComposer, {
      ...props,
      stageAttachments: (_targetKey: string, _pending: unknown, onProgress?: (progress: AttachmentUploadProgressSnapshot) => void) => {
        onProgress?.(progressUpdate({ fileName: 'layout.bin', fileBytesWritten: 1, fileBytesTotal: 2, batchBytesWritten: 1, batchBytesTotal: 2 }));
        return new Promise<{ staged: []; failures: [] }>(() => {});
      },
    });
    activeApp.use(pinia);
    const activeHtml = await renderToString(activeApp);

    expect(idleHtml.match(/data-testid="composer-status"/g)).toHaveLength(1);
    expect(activeHtml.match(/data-testid="composer-status"/g)).toHaveLength(1);
    expect(activeHtml).toContain('data-testid="composer-upload-progress"');
    expect(activeHtml).toContain('role="progressbar"');
    expect(activeHtml).toContain('aria-valuenow="50"');
    expect(activeHtml).toMatch(/<p class="composer-status"[^>]*>\s*<span class="composer-upload-progress"/);
  });
});
