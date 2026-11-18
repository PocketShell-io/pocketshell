import { afterEach, describe, expect, it } from 'vitest';
import { createPinia, setActivePinia } from 'pinia';
import {
  BACKGROUND_GRACE_OPTIONS,
  ConnectionController,
  DEFAULT_BACKGROUND_GRACE_MS,
  RECONNECT_DECLINED_MESSAGE,
  type ConnectionSnapshot,
} from '@pocketshell/core';
import { useSettingsStore } from '@ui/app/stores/settings';
import { createAndroidPlatform } from '@/platform/android/androidApi';
import { AndroidConnectionHub, gaveUp } from '@/platform/android/connectionHub';
import { AndroidHostStore } from '@/platform/android/hostStore';
import { createLocalTrustStore } from '@/platform/android/trustStore';
import { createBackgroundHold, type BackgroundHold, type BackgroundHoldPlugin } from '@/native/backgroundHold';
import { sharedBackgroundGraceMs, sharedReconnectOnReturn } from '@/sharedApp/lifecycleSettings';
import { FakeNative, HOST_KEY, MemoryStorage, settle, target } from './support/androidFakeNative';

/** The native hold as the platform sees it: the pushed windows, and a network-return trigger. */
class FakeHold implements BackgroundHold {
  readonly pushed: number[] = [];
  private handlers = new Set<() => void>();

  async setGrace(graceMs: number): Promise<void> {
    this.pushed.push(graceMs);
  }

  onNetworkAvailable(handler: () => void): () => void {
    this.handlers.add(handler);
    return () => this.handlers.delete(handler);
  }

  networkReturns(): void {
    for (const handler of this.handlers) handler();
  }
}

const open: Array<{ hub: AndroidConnectionHub; id: string }> = [];
afterEach(async () => {
  await Promise.all(open.splice(0).map(({ hub, id }) => hub.close(id)));
});

function platform(options: { graceMs?: () => number; reconnectOnReturn?: () => boolean } = {}) {
  const native = new FakeNative();
  const storage = new MemoryStorage();
  const trust = createLocalTrustStore(storage);
  void trust.record(target.hostId, { kind: 'wire-key', ...HOST_KEY });
  const hosts = new AndroidHostStore({ storage, readLegacyHosts: async () => [] });
  hosts.save({ name: 'fixture', hostname: 'fixture', port: 2222, user: 'u', keyHandleId: 'handle-1' });
  const hold = new FakeHold();
  const controllers: ConnectionController[] = [];
  let lifecycle: ((active: boolean) => void) | null = null;
  let ids = 0;
  const created = createAndroidPlatform({
    createController: () => {
      const controller = new ConnectionController({
        capability: native.capability(),
        trustStore: trust,
        createId: () => `h-${++ids}`,
        // A short ladder, as on a phone whose network is not usable yet.
        retryDelaysMs: [0, 0],
      });
      controllers.push(controller);
      return controller;
    },
    hosts,
    remoteDirectory: async () => '/home/u',
    lifecycle: { onActiveChange: (handler) => { lifecycle = handler; return () => { lifecycle = null; }; } },
    backgroundGraceMs: options.graceMs ?? (() => 30_000),
    ...(options.reconnectOnReturn ? { reconnectOnReturn: options.reconnectOnReturn } : {}),
    backgroundHold: hold,
    addHostRoute: '/android/hosts',
  });
  const phase = () => controllers.at(-1)!.getSnapshot().phase;
  return { native, created, hold, controllers, phase, emitActive: (active: boolean) => lifecycle?.(active) };
}

async function connected(harness: ReturnType<typeof platform>): Promise<string> {
  const result = await harness.created.api.ssh.connect({ host: 'fixture', port: 2222, user: 'u' });
  expect(result.ok).toBe(true);
  open.push({ hub: harness.created.hub, id: result.connectionId! });
  await settle(() => harness.phase() === 'connected');
  return result.connectionId!;
}

/** The transport dies while every dial fails: the controller's ladder gives up. */
async function giveUp(harness: ReturnType<typeof platform>): Promise<void> {
  harness.native.refuseDials = true;
  harness.native.dropTransport();
  await settle(() => harness.phase() === 'lost');
  expect(harness.controllers.at(-1)!.getSnapshot().error).toMatch(/^Could not reconnect to fixture after 2 attempts/);
}

/** A hub with one attached session, and the shared states it announces from now on. */
async function attachedHub() {
  const native = new FakeNative();
  const trust = createLocalTrustStore(new MemoryStorage());
  void trust.record(target.hostId, { kind: 'wire-key', ...HOST_KEY });
  let ids = 0;
  const controllers: ConnectionController[] = [];
  const hub = new AndroidConnectionHub({
    createController: () => {
      const controller = new ConnectionController({
        capability: native.capability(), trustStore: trust, createId: () => `q-${++ids}`, retryDelaysMs: [0, 0],
      });
      controllers.push(controller);
      return controller;
    },
  });
  const { connectionId } = await hub.connect(target);
  open.push({ hub, id: connectionId! });
  await hub.sessionsList(connectionId!);
  await hub.attachSession({ connectionId: connectionId!, sessionName: 'main', aplexerId: 'main-id' });
  const states: string[] = [];
  hub.onState(({ connectionId: id, state }) => {
    if (id === connectionId) states.push(state);
  });
  const phase = () => controllers.at(-1)!.getSnapshot().phase;
  return { native, hub, states, phase, connectionId: connectionId! };
}

describe('background grace hold and reconnect budget (#3160)', () => {
  it('retries a given-up connection by itself when the network returns, with a fresh budget', async () => {
    const harness = platform();
    await connected(harness);
    await giveUp(harness);
    const dialsAtGiveUp = harness.native.connects.length;

    // The phone's network works again, but nothing re-dials on its own: the
    // banner would strand the user here.
    harness.native.refuseDials = false;
    await new Promise((resolve) => setTimeout(resolve, 20));
    expect(harness.phase()).toBe('lost');
    expect(harness.native.connects.length).toBe(dialsAtGiveUp);

    harness.hold.networkReturns();
    await settle(() => harness.phase() === 'connected');
    expect(harness.native.connects.length).toBe(dialsAtGiveUp + 1);
    expect(harness.controllers.at(-1)!.getSnapshot().error).toBeNull();
  });

  it('a foreground return resets the budget of a connection whose ladder gave up', async () => {
    const harness = platform();
    await connected(harness);
    await giveUp(harness);
    harness.native.refuseDials = false;

    harness.emitActive(false);
    await new Promise((resolve) => setTimeout(resolve, 10));
    expect(harness.phase()).toBe('lost');
    harness.emitActive(true);
    await settle(() => harness.phase() === 'connected');
  });

  it('does not retry on network return while backgrounded, or while dials still fail', async () => {
    const harness = platform();
    await connected(harness);
    await giveUp(harness);
    const dials = harness.native.connects.length;

    harness.emitActive(false);
    harness.native.refuseDials = false;
    harness.hold.networkReturns();
    await new Promise((resolve) => setTimeout(resolve, 20));
    expect(harness.native.connects.length).toBe(dials);
    expect(harness.phase()).toBe('lost');
  });

  it('re-attaches silently on a return past grace: no reconnecting state for the first attempt', async () => {
    const { native, hub, states, phase } = await attachedHub();
    await hub.enterBackground(30_000);
    await settle(() => phase() === 'background');
    native.dropTransport(); // grace spent while away: the transport is gone
    const dials = native.connects.length;
    await hub.returnToForeground();
    await settle(() => phase() === 'live');
    expect(native.connects.length).toBe(dials + 1);
    expect(states).not.toContain('reconnecting');
    expect(states).not.toContain('lost');
  });

  it('still announces the loss when the first re-attach attempt after a return fails', async () => {
    const { native, hub, states, phase } = await attachedHub();
    await hub.enterBackground(30_000);
    await settle(() => phase() === 'background');
    native.dropTransport();
    native.refuseDials = true;
    await hub.returnToForeground();
    await settle(() => phase() === 'lost');
    expect(states).toContain('reconnecting');
    expect(states.at(-1)).toBe('lost');
  });

  it('connects the host first when a restored tab attaches after the link was lost, instead of failing the join', async () => {
    const { native, hub, phase, connectionId } = await attachedHub();
    native.refuseDials = true;
    native.dropTransport();
    await settle(() => phase() === 'lost');
    native.refuseDials = false;
    const dials = native.connects.length;
    // The restored pane joins its tab again (on main: "Connect to a host
    // before attaching a session." in red, and nothing else).
    const joined = await hub.attachSession({ connectionId, sessionName: 'main', aplexerId: 'main-id' });
    expect(joined.shellId).toMatch(/:shell-/);
    expect(phase()).toBe('live');
    expect(native.connects.length).toBe(dials + 1);
  });

  it('a return that arrives while the app is still entering the background leaves the terminal live, not stranded in background', async () => {
    const harness = platform();
    await connected(harness);
    // A fast app switch: the return lands while enterBackground still awaits
    // its native scheduleClose.
    harness.emitActive(false);
    harness.emitActive(true);
    await new Promise((resolve) => setTimeout(resolve, 50));
    await settle(() => harness.phase() !== 'background');
    expect(harness.phase()).toBe('connected');
    // And stays so: no late background transition after the return.
    await new Promise((resolve) => setTimeout(resolve, 50));
    expect(harness.phase()).toBe('connected');
  });

  it('leaves a connection the user chose not to reconnect, or one awaiting a host-key decision, alone', () => {
    const lost = { phase: 'lost', trustDecision: null, error: 'Could not reconnect to h after 5 attempts.' } as unknown as ConnectionSnapshot;
    expect(gaveUp(lost)).toBe(true);
    expect(gaveUp({ ...lost, error: RECONNECT_DECLINED_MESSAGE })).toBe(false);
    expect(gaveUp({ ...lost, trustDecision: {} as ConnectionSnapshot['trustDecision'] })).toBe(false);
    expect(gaveUp({ ...lost, phase: 'reconnecting' })).toBe(false);
    expect(gaveUp({ ...lost, phase: 'live' })).toBe(false);
  });

  it('honours Reconnect when I return: off leaves a grace-spent connection waiting', async () => {
    const harness = platform({ reconnectOnReturn: () => false });
    await connected(harness);
    harness.emitActive(false);
    await settle(() => harness.phase() === 'background');
    // Grace spent: the native transport is gone by the time the app returns.
    harness.native.dropTransport();
    const dials = harness.native.connects.length;
    harness.emitActive(true);
    await settle(() => harness.phase() === 'lost');
    expect(harness.controllers.at(-1)!.getSnapshot().error).toBe(RECONNECT_DECLINED_MESSAGE);
    harness.hold.networkReturns();
    await new Promise((resolve) => setTimeout(resolve, 20));
    expect(harness.native.connects.length).toBe(dials);
  });

  it('pushes the user grace window to the native hold at start and on every return, and arms core with it', async () => {
    let graceMs = 30_000;
    const harness = platform({ graceMs: () => graceMs });
    expect(harness.hold.pushed).toEqual([30_000]);
    await connected(harness);
    graceMs = 300_000;
    harness.emitActive(false);
    await settle(() => harness.phase() === 'background');
    harness.emitActive(true);
    await settle(() => harness.phase() === 'connected');
    expect(harness.hold.pushed).toEqual([30_000, 300_000]);
  });

  it('declares background grace so shared Settings shows the grace and reconnect controls', () => {
    const harness = platform();
    expect(harness.created.api.app.backgroundGrace).toBe(true);
  });

  it('reads the grace window and reconnect-on-return from the shared settings store', () => {
    setActivePinia(createPinia());
    const settings = useSettingsStore();
    const fiveMinutes = BACKGROUND_GRACE_OPTIONS.find((option) => option.milliseconds === 5 * 60_000)!.milliseconds;
    settings.set('backgroundGraceMs', fiveMinutes);
    settings.set('reconnectOnReturn', false);
    expect(sharedBackgroundGraceMs()).toBe(fiveMinutes);
    expect(sharedReconnectOnReturn()).toBe(false);
  });

  it('answers core defaults before the app has installed Pinia', () => {
    setActivePinia(undefined as never);
    expect(sharedBackgroundGraceMs()).toBe(DEFAULT_BACKGROUND_GRACE_MS);
    expect(sharedReconnectOnReturn()).toBe(true);
  });

  function fakePlugin(answer: (requestId: string) => string) {
    const calls: Array<{ requestId: string; graceMs: number }> = [];
    const plugin = {
      setBackgroundGrace: async (options: { requestId: string; graceMs: number }) => {
        calls.push(options);
        return { requestId: answer(options.requestId), graceMs: options.graceMs };
      },
      addListener: async () => ({ remove: async () => undefined }),
    } as unknown as BackgroundHoldPlugin;
    return { plugin, calls };
  }

  it('sends a whole, non-negative window and rejects a stale reply', async () => {
    const good = fakePlugin((id) => id);
    const hold = createBackgroundHold(good.plugin);
    await hold.setGrace(90_000.4);
    await hold.setGrace(-5);
    await hold.setGrace(Number.NaN);
    expect(good.calls.map((call) => call.graceMs)).toEqual([90_000, 0, 0]);

    const stale = createBackgroundHold(fakePlugin(() => 'other').plugin);
    await expect(stale.setGrace(30_000)).rejects.toThrow(/stale request/);
  });
});
