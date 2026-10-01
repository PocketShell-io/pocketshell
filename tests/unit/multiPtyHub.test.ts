/**
 * #2955 (U2): one PTY per attached session behind the Android hub.
 *
 * The shared app keeps one TerminalView per visited session tab mounted
 * (FolderWorkspaceView), so a tab switch must not cost the other tab its PTY:
 * its scrollback stays in its pane and its live output keeps arriving there.
 * These run the REAL core ConnectionController over the physical-effects fake
 * native, so they fail on the single-PTY controller (attaching B closed A).
 */
import { afterEach, describe, expect, it } from 'vitest';
import { ConnectionController } from '@pocketshell/core';
import { AndroidConnectionHub } from '@/platform/android/connectionHub';
import { createLocalTrustStore } from '@/platform/android/trustStore';
import { FakeNative, MemoryStorage, settle, target } from './support/androidFakeNative';

const WORKSPACE = '/home/u/git/demo';

function harness() {
  const native = new FakeNative();
  const trust = createLocalTrustStore(new MemoryStorage());
  let ids = 0;
  const controllers: ConnectionController[] = [];
  const hub = new AndroidConnectionHub({
    createController: () => {
      const controller = new ConnectionController({
        capability: native.capability(),
        trustStore: trust,
        createId: () => `m-${++ids}`,
        retryDelaysMs: [0, 0],
      });
      controllers.push(controller);
      return controller;
    },
  });
  return { native, hub, controllers };
}

const open: Array<{ hub: AndroidConnectionHub; id: string }> = [];
afterEach(async () => {
  await Promise.all(open.splice(0).map(({ hub, id }) => hub.close(id)));
});

async function connected() {
  const h = harness();
  const { connectionId } = await h.hub.connect(target);
  open.push({ hub: h.hub, id: connectionId! });
  await h.hub.sessionsList(connectionId!);
  const received: Array<[string, string]> = [];
  h.hub.onData(({ shellId, data }) => received.push([shellId, new TextDecoder().decode(data)]));
  const exited: string[] = [];
  h.hub.onExited(({ shellId }) => exited.push(shellId));
  const attach = async (name: 'main' | 'tests') => {
    const shell = await h.hub.attachSession({
      connectionId: connectionId!, sessionName: name, aplexerId: `${name}-id`, workspace: WORKSPACE, cols: 80, rows: 24,
    });
    await h.hub.resize(shell.shellId, 80, 24); // the pane claims its id
    return shell;
  };
  const bytesOf = (shellId: string) => received.filter(([id]) => id === shellId).map(([, text]) => text).join('');
  return { ...h, connectionId: connectionId!, attach, received, exited, bytesOf };
}

describe('Android hub keeps one PTY per attached session (#2955)', () => {
  it('keeps every attached session live, routing each one\'s bytes and keystrokes to its own shell id', async () => {
    const { native, hub, attach, exited, bytesOf } = await connected();
    const a = await attach('main');
    const channelA = native.channelOf('main');
    await settle(() => native.hasPendingReadOn(channelA));
    native.outputOn(channelA, 'A-before\r\n');
    await settle(() => bytesOf(a.shellId).includes('A-before'));

    const b = await attach('tests');
    const channelB = native.channelOf('tests');
    expect(b.shellId).not.toBe(a.shellId);
    // Attaching B is not a reason to close A.
    expect(native.closedPtys).toEqual([]);
    expect(exited).toEqual([]);
    await settle(() => native.hasPendingReadOn(channelA) && native.hasPendingReadOn(channelB));

    // A keeps producing while B is the tab in front: A's pane gets it, live.
    native.outputOn(channelA, 'A-while-hidden\r\n');
    native.outputOn(channelB, 'B-only\r\n');
    await settle(() => bytesOf(a.shellId).includes('A-while-hidden') && bytesOf(b.shellId).includes('B-only'));
    expect(bytesOf(a.shellId)).toBe('A-before\r\nA-while-hidden\r\n');
    expect(bytesOf(b.shellId)).toBe('B-only\r\n');

    // Keystrokes go to their own session's PTY, never the other one.
    expect(await hub.input(a.shellId, 'to-a\r', 'main', WORKSPACE)).toBe(true);
    expect(await hub.input(b.shellId, 'to-b\r', 'tests', WORKSPACE)).toBe(true);
    expect(native.channelWrites).toEqual([[channelA, 'to-a\r'], [channelB, 'to-b\r']]);

    // Back to A: the same shell, no second attach (no repaint, scrollback intact).
    const again = await attach('main');
    expect(again).toEqual({ shellId: a.shellId, switched: true });
    expect(native.opened).toHaveLength(2);
  });

  it('closes only the PTY of the shell the pane gave up', async () => {
    const { native, hub, attach, exited } = await connected();
    const a = await attach('main');
    const b = await attach('tests');
    const channelA = native.channelOf('main');
    const channelB = native.channelOf('tests');

    expect(await hub.closeShell(b.shellId)).toBe(true);
    expect(native.closedPtys).toEqual([channelB]);
    expect(exited).toEqual([]);
    expect(await hub.input(b.shellId, 'gone', 'tests', WORKSPACE)).toBe(false);
    expect(await hub.input(a.shellId, 'still\r', 'main', WORKSPACE)).toBe(true);
    expect(native.channelWrites).toEqual([[channelA, 'still\r']]);
  });

  it('re-attaches every open session exactly once after a drop, under the same shell ids', async () => {
    const { native, hub, controllers, attach, bytesOf } = await connected();
    const a = await attach('main');
    const b = await attach('tests');
    const dials = native.connects.length;
    native.dropTransport();
    await settle(() => native.opened.length === 4 && controllers.at(-1)!.getSnapshot().phase === 'live'
      && native.hasPendingReadOn(native.channelOf('main')) && native.hasPendingReadOn(native.channelOf('tests')));
    await new Promise((resolve) => setTimeout(resolve, 20));
    expect(native.connects.length - dials).toBe(1);
    const reopened = native.opened.slice(2).map((options) => options.command);
    expect(reopened.filter((command) => command.includes(":main'"))).toHaveLength(1);
    expect(reopened.filter((command) => command.includes(":tests'"))).toHaveLength(1);

    native.outputOn(native.channelOf('main'), 'A-after');
    native.outputOn(native.channelOf('tests'), 'B-after');
    await settle(() => bytesOf(a.shellId).includes('A-after') && bytesOf(b.shellId).includes('B-after'));
    expect(bytesOf(a.shellId)).not.toContain('B-after');
    expect(await hub.input(a.shellId, 'a\r', 'main', WORKSPACE)).toBe(true);
    expect(await hub.input(b.shellId, 'b\r', 'tests', WORKSPACE)).toBe(true);
    expect(native.channelWrites).toEqual([[native.channelOf('main'), 'a\r'], [native.channelOf('tests'), 'b\r']]);
  });

  it('retires only the shell whose session ended', async () => {
    const { native, hub, controllers, attach, exited } = await connected();
    const a = await attach('main');
    const b = await attach('tests');
    const channelB = native.channelOf('tests');
    await settle(() => native.hasPendingReadOn(channelB));
    const dials = native.connects.length;
    native.outputOn(channelB, '', true);
    await settle(() => exited.length === 1);
    expect(exited).toEqual([b.shellId]);
    expect(controllers.at(-1)!.getSnapshot().phase).toBe('live');
    expect(native.connects).toHaveLength(dials);
    expect(await hub.input(a.shellId, 'alive\r', 'main', WORKSPACE)).toBe(true);
  });
});
