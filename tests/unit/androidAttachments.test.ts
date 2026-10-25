import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { AndroidConnectionHub } from '@/platform/android/connectionHub';
import { createAndroidAttachments, androidAttachmentProgress } from '@/platform/android/attachments';

const mock = vi.hoisted(() => ({
  pick: vi.fn(), write: vi.fn(), stage: vi.fn(), workspace: vi.fn(),
}));
vi.mock('@/session/platformInput', () => ({ platformInput: { pickAttachments: mock.pick } }));
vi.mock('@/native/sshCapability', () => ({ sshCapability: new Proxy({ sftpWrite: mock.write }, {
  get(target, property) { if (property === 'sftpList') return () => 'native proxy list'; return Reflect.get(target, property); },
}) }));
vi.mock('@/session/files', () => ({ createFileWorkspaceService: mock.workspace }));

function harness() {
  let generationId = 'generation-1';
  const exec = vi.fn(async (_id: string, command: string) => ({ exitCode: 0, stdout: command.startsWith('printf') ? "/home/phone's account" : '', stderr: '' }));
  const hub = { exec, controllerOf: () => ({ getSnapshot: () => ({ connectionId: 'native-1', generationId }) }) } as unknown as AndroidConnectionHub;
  return { api: createAndroidAttachments(hub), exec, swapGeneration: () => { generationId = 'generation-2'; } };
}
beforeEach(() => {
  vi.resetAllMocks();
  androidAttachmentProgress.value = null;
  mock.pick.mockResolvedValue({ sources: [{ kind: 'bytes', data: new Uint8Array([1, 2, 3]), name: 'picked.bin' }], failures: [] });
  mock.write.mockResolvedValue({ bytesWritten: 3 });
  mock.stage.mockResolvedValue({ decision: { kind: 'complete', attachments: [{ path: '/remote/picked.bin' }] }, failures: [] });
  mock.workspace.mockReturnValue({ stageAttachments: mock.stage });
});

describe('Android shared composer attachments', () => {
  it('preserves picker bytes behind opaque handles and stages them on the current native generation', async () => {
    const { api, exec } = harness();
    const [path] = await api.pickFiles();
    expect(await api.readLocal(path!)).toEqual(new Uint8Array([1, 2, 3]));
    const result = await api.stage({ connectionId: 'logical', scopeKey: 'workspace/session', sources: [{ kind: 'file', path: path! }] });
    expect(result).toEqual({ ok: true, paths: ['/remote/picked.bin'], failedCount: 0 });
    expect(mock.workspace.mock.calls[0]![0].sftpList()).toBe('native proxy list');
    expect(mock.workspace.mock.calls[0]![1].connection).toEqual({ connectionId: 'native-1', generationId: 'generation-1' });
    expect(mock.stage.mock.calls[0]![0].timestamp).toMatch(/^\d{8}-\d{6}$/);
    expect(mock.stage.mock.calls[0]![0].attachments).toEqual([{ name: 'picked.bin', bytes: new Uint8Array([1, 2, 3]) }]);
    expect(exec.mock.calls[1]![1]).toContain("'\\''");
    await expect(api.readLocal(path!)).rejects.toThrow('again');
  });
  it('retains successfully uploaded paths when another source fails', async () => {
    const { api } = harness();
    mock.stage.mockResolvedValue({ decision: { kind: 'partial', attachments: [{ path: '/remote/first.bin' }] }, failures: [{ message: 'second file refused' }] });
    const result = await api.stage({ connectionId: 'logical', scopeKey: 'session', sources: [{ kind: 'bytes', data: new Uint8Array([1]) }] });
    expect(result).toEqual({ ok: false, paths: ['/remote/first.bin'], failedCount: 1, error: 'second file refused' });
    expect(androidAttachmentProgress.value).toBeNull();
  });
  it('advances progress only after native acknowledgement and removes the bar on completion', async () => {
    const { api } = harness();
    let release!: () => void;
    const ack = new Promise<void>((resolve) => { release = resolve; });
    mock.write.mockImplementation(async () => { await ack; return { bytesWritten: 3 }; });
    mock.stage.mockImplementation(async () => {
      const capability = mock.workspace.mock.calls[0]![0];
      const write = capability.sftpWrite({ dataBase64: 'AQID' });
      expect(androidAttachmentProgress.value).toBeNull();
      release();
      await write;
      expect(androidAttachmentProgress.value).toMatchObject({ batchBytesWritten: 3, batchBytesTotal: 6, fileCount: 2 });
      return { decision: { kind: 'complete', attachments: [] }, failures: [] };
    });
    await api.stage({ connectionId: 'logical', scopeKey: 'session', sources: [1, 2].map(() => ({ kind: 'bytes' as const, data: new Uint8Array([1, 2, 3]) })) });
    expect(androidAttachmentProgress.value).toBeNull();
  });
  it('refuses an outdated generation through the existing file staging guard and clears progress after errors', async () => {
    const { api, swapGeneration } = harness();
    mock.stage.mockImplementation(async () => {
      const configuration = mock.workspace.mock.calls[0]![1];
      expect(configuration.isCurrent()).toBe(true);
      swapGeneration();
      expect(configuration.isCurrent()).toBe(false);
      throw new Error('generation changed');
    });
    expect(await api.stage({ connectionId: 'logical', scopeKey: 'session', sources: [{ kind: 'bytes', data: new Uint8Array([1]) }] })).toMatchObject({ ok: false, failedCount: 1, paths: [], error: 'generation changed' });
    expect(androidAttachmentProgress.value).toBeNull();
  });
});
